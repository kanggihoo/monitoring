package com.ssafy.a605.backend.domain.account.service;

import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.ssafy.a605.backend.domain.account.entity.Account;
import com.ssafy.a605.backend.domain.account.repository.AccountRepository;
import com.ssafy.a605.backend.domain.replay.dto.GameClockSnapshot;
import com.ssafy.a605.backend.domain.replay.service.ReplayService;
import com.ssafy.a605.backend.domain.season.entity.Season;
import com.ssafy.a605.backend.domain.season.repository.SeasonRepository;
import com.ssafy.a605.backend.global.exception.BusinessException;

import lombok.extern.slf4j.Slf4j;

/**
 * 시즌 참가 = 계좌 생성 (기획서 3.2, {@code sea.md}).
 *
 * <p><b>참가 전용 엔드포인트가 없다.</b> 기획서 3.2.1 이 "별도의 참가 버튼은 없다"로 정했으므로
 * 시즌 중 로그인이 곧 참가이고, 이 연산은 로그인 처리의 일부로 불린다. 호출 시점은 가입이
 * 아니라 <b>매 로그인</b>이다 — 이미 가입해 있던 사용자가 재로그인으로 계좌를 받아야 한다.
 *
 * <p>계좌 도메인에 두는 이유는 <b>의존 방향</b>이다. 시드·재생 시계·일차 판정을 사용자 서비스가
 * 알게 되면 User 도메인이 게임 규칙을 알게 된다.
 *
 * <p><b>멱등이다.</b> 이미 있으면 조회만 하고 없으면 만든다. 동시 세션이 허용되므로
 * ({@code _common.md §2}) 같은 사용자의 로그인이 겹칠 수 있고, 계좌가 둘 생기면 시드를 두 배로
 * 받는다 — {@code uk_accounts_user_season} 이 그 방어선이다. 삽입이 무결성 위반으로 거부되면
 * <b>재조회로 판별한다</b>: 계좌가 잡히면 다른 세션이 먼저 만든 것이고, 잡히지 않으면 그대로
 * 실패한다 (실패를 성공으로 오인하지 않는다).
 *
 * <p><b>자기 트랜잭션에서 커밋하고 반환한다.</b> 로그인 조율은 트랜잭션 밖에서 일어나며, 계좌가
 * 만들어진 뒤에 세션이 발급된다. 호출자가 읽기 전용 트랜잭션 안에 있으면 INSERT 가 flush 되지
 * 않고 <b>예외 없이 행만 생기지 않으므로</b>, 전파를 호출자에게 맡기지 않는다.
 */
@Slf4j
@Service
public class AccountEnrollmentService {

    /** 시즌 시작 전에도 계좌를 만든다. 시계가 그 구간을 1일차 브리핑으로 내므로 일차는 1 이다. */
    private static final long NO_PUBLISHED_TICK = 0L;

    /** 소급 인덱스 잔고를 적용하지 않는다 (ADR-0006). 소급 계산을 한 척하지 않는다. */
    private static final boolean INDEX_UNADJUSTED = false;

    private final AccountRepository accountRepository;
    private final SeasonRepository seasonRepository;
    private final ReplayService replay;
    private final TransactionTemplate write;
    private final long seasonId;

    /**
     * @param accountRepository 계좌 저장소
     * @param seasonRepository  시드를 읽을 시즌 편성
     * @param replay            일차와 공개 틱을 정하는 재생 시계
     * @param manager           계좌 생성의 트랜잭션 경계를 만들 매니저
     * @param seasonId          참가 대상 시즌. 재생이 진행하는 시즌과 같은 설정값이다
     */
    public AccountEnrollmentService(AccountRepository accountRepository, SeasonRepository seasonRepository,
                                    ReplayService replay, PlatformTransactionManager manager,
                                    @Value("${app.replay.season-id:1}") long seasonId) {
        this.accountRepository = accountRepository;
        this.seasonRepository = seasonRepository;
        this.replay = replay;
        this.seasonId = seasonId;
        write = new TransactionTemplate(manager);
        write.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * 그 시즌 계좌 전부를 <b>참가 시점의 값</b>으로 되돌린다 (개발·테스트 전용 시즌 초기화).
     *
     * <p><b>계좌 행을 지우지 않는다.</b> 계좌는 로그인 경로에서만 만들어지므로 지우면 접속
     * 중인 사람 전원이 재로그인할 때까지 모든 계좌 응답이 404 다.
     *
     * <p>되돌리는 값이 {@link #newAccount} 와 같아야 하므로 여기에 둔다 — 참가 시점의 값을
     * 정하는 곳과 그것으로 되돌리는 곳이 갈리면 한쪽만 고치는 사고가 난다. 합류 일차는 항상
     * 1 이다: 초기화 뒤의 재생은 1일차 브리핑부터 다시 시작한다.
     *
     * @throws BusinessException 시즌 편성이 없을 때 (500)
     */
    @Transactional
    public void resetSeasonToSeed() {
        Season season = seasonRepository.findById(seasonId).orElseThrow(() -> {
            log.error("Season {} is absent from seasons", seasonId);
            return new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "시즌 편성이 없습니다");
        });
        for (Account account : accountRepository.findBySeasonId(seasonId)) {
            account.resetToSeed(season.getSeedKrw());
        }
    }

    /**
     * 그 사용자의 이 시즌 계좌를 보장한다. 없으면 시드를 지급해 만들고, 있으면 그대로 돌려준다 —
     * 재로그인이 잔고·보유·확인한 틱을 초기화하지 않는다.
     *
     * <p><b>불변식: 호출 시점에 User 는 커밋돼 있어야 한다.</b> 계좌의 외래키가 미커밋 행을
     * 참조하면 안 된다.
     *
     * @param userId 계좌 주인
     * @return 그 시즌 계좌
     * @throws BusinessException 재생 상태가 초기화되지 않았을 때 (409, 재생 도메인이 판정한다),
     *                           시즌 편성이 없을 때 (500)
     */
    public Account enroll(Long userId) {
        Optional<Account> existing = find(userId);
        if (existing.isPresent()) {
            return existing.get();
        }
        Account created = newAccount(userId);
        try {
            return write.execute(status -> accountRepository.save(created));
        } catch (DataIntegrityViolationException e) {
            // 다른 세션이 먼저 만들었다면 그 계좌가 이 사용자의 계좌다. 그 계좌는 이 예외가
            // 도착한 시점에 이미 커밋돼 있다 - 유니크 인덱스 대기가 커밋에 풀린다.
            return find(userId).orElseThrow(() -> {
                log.error("Account creation for user {} in season {} failed and no account exists",
                        userId, seasonId, e);
                return e;
            });
        }
    }

    /**
     * 생성 시점의 시계로 초기값을 채운 계좌. 시드는 편성값을 읽고 소급 인덱스 잔고는 적용하지
     * 않는다 (ADR-0006) — 합류 이전 시장 수익률은 참가자의 수익률에 포함하지 않는다.
     *
     * <p>공개 틱은 일차가 끝나도 초기화되지 않으므로 2·3일차 개장 전에는 <b>전날 마지막 틱</b>이
     * 들어간다. {@code 0} 은 시즌 최초 틱 이전에만 나온다.
     */
    private Account newAccount(Long userId) {
        GameClockSnapshot clock = replay.clock();
        Season season = seasonRepository.findById(seasonId).orElseThrow(() -> {
            log.error("Season {} is absent from seasons", seasonId);
            return new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "시즌 편성이 없습니다.");
        });
        return new Account(userId, seasonId, season.getSeedKrw(), INDEX_UNADJUSTED, clock.seasonDay(),
                clock.tickSeq() == null ? NO_PUBLISHED_TICK : clock.tickSeq());
    }

    private Optional<Account> find(Long userId) {
        return accountRepository.findByUserIdAndSeasonId(userId, seasonId);
    }
}

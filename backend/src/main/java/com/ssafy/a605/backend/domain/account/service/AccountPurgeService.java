package com.ssafy.a605.backend.domain.account.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ssafy.a605.backend.domain.account.entity.Account;
import com.ssafy.a605.backend.domain.account.repository.AccountRepository;
import com.ssafy.a605.backend.domain.account.repository.ExchangeRepository;
import com.ssafy.a605.backend.domain.account.repository.PositionRepository;
import com.ssafy.a605.backend.domain.ipo.repository.IpoSubscriptionRepository;
import com.ssafy.a605.backend.domain.notification.repository.NotificationRepository;
import com.ssafy.a605.backend.domain.order.repository.ExecutionRepository;
import com.ssafy.a605.backend.domain.order.repository.OrderRepository;
import com.ssafy.a605.backend.domain.ranking.repository.DailyHoldingRepository;
import com.ssafy.a605.backend.domain.ranking.repository.DailySettlementRepository;
import com.ssafy.a605.backend.domain.report.repository.DailyReportTextRepository;
import com.ssafy.a605.backend.domain.report.repository.SeasonReportRepository;
import com.ssafy.a605.backend.domain.report.repository.SeasonReportTextRepository;

import lombok.RequiredArgsConstructor;

/**
 * 탈퇴가 부르는 계좌 삭제 (ADR-0007). 계좌와 <b>그에 매달린 것 전부</b>를 지운다 — 확정 정산과
 * 개인 수상 기록도 함께 사라진다.
 *
 * <p><b>DB {@code ON DELETE CASCADE} 를 쓰지 않는다.</b> 저장소 규약이 CASCADE 를 쓰지 않으며,
 * 자식 테이블이 늘었을 때 외래키 위반이 알려 주는 쪽이 조용히 지워지는 쪽보다 낫다.
 *
 * <p><b>다른 도메인의 저장소를 직접 주입한다.</b> 도메인 간 협력을 Service 로 한다는 규약의
 * 명시된 예외이며, 근거는 <i>순서가 규칙</i>이라는 점이다 — 도메인마다 한 줄짜리 삭제 Service 를
 * 두면 순서만 여섯 곳으로 흩어지고 외래키 위반 지점은 그대로 여기에 남는다
 * (<a href="../../../../../../../docs/architecture/overview.md">architecture/overview.md</a> §의존성 원칙).
 *
 * <p><b>순서가 외래키다.</b> 체결이 예약을 참조하므로 체결을 먼저 지운다. 계좌는 자식이 모두
 * 사라진 뒤에 지운다.
 *
 * <p>수상은 별도 테이블 없이 정산 행의 1위를 조회하는 구조이고 개인 수상 알림은 계좌에 매달려
 * 있으므로, <b>정산 행 삭제가 곧 수상 기록 삭제</b>다. 남은 참가자의 확정 순위는 재산정하지
 * 않으며 순위 번호의 공백을 허용한다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AccountPurgeService {

    private final AccountRepository accountRepository;
    private final PositionRepository positionRepository;
    private final OrderRepository orderRepository;
    private final ExecutionRepository executionRepository;
    private final ExchangeRepository exchangeRepository;
    private final IpoSubscriptionRepository ipoSubscriptionRepository;
    private final DailySettlementRepository dailySettlementRepository;
    private final DailyHoldingRepository dailyHoldingRepository;
    private final SeasonReportRepository seasonReportRepository;
    private final DailyReportTextRepository dailyReportTextRepository;
    private final SeasonReportTextRepository seasonReportTextRepository;
    private final NotificationRepository notificationRepository;

    /**
     * 그 사용자의 계좌 전부와 종속 데이터를 지운다. 계좌가 없으면 아무것도 하지 않는다.
     *
     * <p>시즌으로 좁히지 않는다 — 탈퇴는 이 시즌만 떠나는 것이 아니다.
     *
     * @param userId 탈퇴하는 User 식별자
     */
    @Transactional
    public void purgeByUser(Long userId) {
        for (Account account : accountRepository.findByUserId(userId)) {
            purgeChildren(account.getId());
            accountRepository.delete(account);
        }
    }

    /**
     * 그 시즌 <b>모든 계좌의 진행 결과</b>를 지운다. 계좌 행은 남긴다 — 개발·테스트 전용
     * 시즌 초기화가 쓰며, 계좌를 지우면 접속 중인 사람 전원이 재로그인해야 한다.
     *
     * <p>탈퇴와 같은 순서를 쓴다. 순서를 두 곳에 복제하면 테이블이 늘었을 때 한쪽이 빠지고
     * 그 실패는 런타임 외래키 위반으로만 드러난다.
     *
     * <p>계좌에 매달리지 않는 것(채팅 메시지)은 여기 없다. 그것은 조율하는 쪽의 몫이다.
     *
     * @param seasonId 초기화할 시즌
     */
    @Transactional
    public void purgeSeasonProgress(Long seasonId) {
        for (Account account : accountRepository.findBySeasonId(seasonId)) {
            purgeChildren(account.getId());
        }
    }

    /**
     * <b>순서가 외래키다.</b> 체결이 예약을 참조하므로 체결을 먼저 지운다.
     *
     * @param accountId 대상 계좌
     */
    private void purgeChildren(Long accountId) {
        dailyReportTextRepository.deleteByAccountId(accountId);
        seasonReportTextRepository.deleteByAccountId(accountId);
        seasonReportRepository.deleteByAccountId(accountId);
        // 마감 보유 스냅샷이 확정 정산을 복합 외래키로 참조한다 (V42). 확정 정산은 그날의
        // 최선·최악 체결을 가리키고, 체결이 예약을 가리킨다.
        dailyHoldingRepository.deleteByAccountId(accountId);
        dailySettlementRepository.deleteByAccountId(accountId);
        executionRepository.deleteByAccountId(accountId);
        orderRepository.deleteByAccountId(accountId);
        positionRepository.deleteByAccountId(accountId);
        exchangeRepository.deleteByAccountId(accountId);
        ipoSubscriptionRepository.deleteByAccountId(accountId);
        notificationRepository.deleteByAccountId(accountId);
    }
}

package com.ssafy.a605.backend.domain.account.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.ssafy.a605.backend.domain.account.dto.ExchangePreviewResponse;
import com.ssafy.a605.backend.domain.account.dto.ExchangeRequest;
import com.ssafy.a605.backend.domain.account.dto.ExchangeResponse;
import com.ssafy.a605.backend.domain.account.entity.Account;
import com.ssafy.a605.backend.domain.account.entity.Exchange;
import com.ssafy.a605.backend.domain.account.entity.ExchangeDirection;
import com.ssafy.a605.backend.domain.account.repository.AccountRepository;
import com.ssafy.a605.backend.domain.account.repository.ExchangeRepository;
import com.ssafy.a605.backend.domain.replay.dto.GameClockSnapshot;
import com.ssafy.a605.backend.domain.replay.service.PhaseGate;
import com.ssafy.a605.backend.domain.season.entity.GameTick;
import com.ssafy.a605.backend.domain.season.service.GameTickCatalog;
import com.ssafy.a605.backend.global.exception.BusinessException;
import com.ssafy.a605.backend.global.response.PageResponse;

import lombok.extern.slf4j.Slf4j;

/**
 * 환전 (기획서 7.2, {@code acc.md}). 원화와 달러 사이를 즉시 옮긴다 — 나스닥·코인을 사는 데
 * 필요한 달러를 마련하는 유일한 경로다.
 *
 * <p>조회만 하는 {@link AccountService} 와 나눠 둔 이유는 트랜잭션이다. 계좌 조회는 한 응답이
 * 여러 조회를 합치므로 {@code REPEATABLE_READ} 로 읽어야 하지만, 현금을 옮기는 경로는 같은 행에
 * 대한 동시 갱신이 직렬화 실패로 거부되면 안 되므로 <b>행 잠금 + 기본 격리</b>가 맞다. 두 요구를
 * 한 클래스에 섞으면 메서드마다 어느 쪽인지 읽어야 한다.
 *
 * <p><b>미리보기와 실행이 같은 계산을 쓴다</b> ({@link ExchangeQuote}). 두 벌로 두면 미리 보여준
 * 수령액과 실제 수령액이 어긋나고, 그 차이가 곧 "환전했는데 주문이 또 막힌다"가 된다.
 */
@Slf4j
@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class ExchangeService {

    /**
     * 금액의 정수부 상한. 저장 컬럼이 {@code NUMERIC(38, 4)} 라 정수부가 34 자리다. 이보다 큰
     * 값은 어차피 저장되지 않으므로 계산에 들어가기 전에 거른다.
     */
    private static final int MAX_INTEGER_DIGITS = 34;

    private final AccountRepository accountRepository;
    private final ExchangeRepository exchangeRepository;
    private final GameTickCatalog gameTicks;
    private final PhaseGate phases;
    private final Clock clock;
    private final long seasonId;
    private final com.ssafy.a605.backend.global.websocket.CommittedUserPublisher updates;

    public ExchangeService(AccountRepository accountRepository, ExchangeRepository exchangeRepository,
                           GameTickCatalog gameTicks, PhaseGate phases, Clock clock,
                           @Value("${app.replay.season-id:1}") long seasonId,
                           com.ssafy.a605.backend.global.websocket.CommittedUserPublisher updates) {
        this.accountRepository = accountRepository;
        this.exchangeRepository = exchangeRepository;
        this.gameTicks = gameTicks;
        this.phases = phases;
        this.clock = clock;
        this.seasonId = seasonId;
        this.updates = updates;
    }

    /**
     * 환전 미리보기. 상태를 바꾸지 않으므로 사용자가 금액을 타이핑하는 동안 여러 번 불러도 된다.
     *
     * @param userId    세션의 User 식별자
     * @param direction 환전 방향
     * @param amount    보내는 통화 기준 금액
     * @return 적용 환율·수수료·수령액과 잠기지 않은 보내는 통화 현금
     * @throws BusinessException 거래 구간이 아닐 때 (409), 금액이 통화의 단위에 맞지 않을 때 (400),
     *                           그 시즌 계좌가 없을 때 (404)
     */
    public ExchangePreviewResponse preview(Long userId, ExchangeDirection direction, BigDecimal amount) {
        GameTick tick = tradingTick();
        Account account = accountRepository.findByUserIdAndSeasonId(userId, seasonId)
                .orElseThrow(ExchangeService::accountMissing);

        ExchangeQuote quote = quote(direction, amount, tick);
        return ExchangePreviewResponse.of(quote, availableCash(account, direction));
    }

    /**
     * 환전을 즉시 실행한다 (기획서 7.2.2). 미리보기와 실행 사이에 환율이 바뀌는 일은 틱이
     * 넘어갈 때만 생기며, 그때는 실행 시점의 환율이 적용된다 — 응답이 진실이다.
     *
     * @param userId  세션의 User 식별자
     * @param request 환전 방향과 보내는 통화 기준 금액
     * @return 저장된 환전 한 건
     * @throws BusinessException 거래 구간이 아닐 때 (409), 금액이 통화의 단위에 맞지 않거나
     *                           수령액이 0 이 될 때 (400), 그 시즌 계좌가 없을 때 (404),
     *                           잠기지 않은 현금이 부족할 때 (409)
     */
    @Transactional
    public ExchangeResponse exchange(Long userId, ExchangeRequest request) {
        GameTick tick = tradingTick();
        // 읽고 계산한 뒤 쓰는 경로다. 행을 잠그지 않으면 같은 사용자의 동시 요청 둘이 같은
        // 잔고를 읽어 각자의 결과를 덮어쓴다.
        Account account = accountRepository.findWithLockByUserIdAndSeasonId(userId, seasonId)
                .orElseThrow(ExchangeService::accountMissing);
        account.requireDailyReady(tick.getSeasonDay());

        ExchangeQuote quote = quote(request.direction(), request.amount(), tick);
        if (availableCash(account, request.direction()).compareTo(quote.sendAmount()) < 0) {
            throw new BusinessException(HttpStatus.CONFLICT, "환전할 현금이 부족합니다.");
        }

        account.exchange(request.direction(), quote.sendAmount(), quote.receiveAmount());
        Exchange saved = exchangeRepository.save(new Exchange(account.getId(), quote.direction(),
                quote.rate(), quote.sendAmount(), quote.feeRate(), quote.fee(), quote.receiveAmount(),
                tick.getTickSeq(), Instant.now(clock)));
        var response = ExchangeResponse.of(saved, tick.getGameDate());
        updates.send(userId, "account.exchanged", response);
        return response;
    }

    /**
     * 부족분을 채우는 데 필요한 보내는 통화 최소 금액. 주문 미리보기의 부족분 환전 버튼이 이
     * 값을 그대로 {@code POST /exchanges} 본문에 넣는다 ({@code ord.md} §{@code shortfallExchange}).
     *
     * <p>주문 도메인이 공식을 복제하지 않고 여기로 묻는 이유는 <b>답이 공식이 아니라 실제 환전
     * 계산이기 때문</b>이다. 반올림이 두 번 들어가 역산과 결과가 어긋나므로, 계산이 한 벌이
     * 아니면 안내한 금액을 그대로 환전해도 1원·1센트가 모자란다.
     *
     * @param direction 환전 방향. 부족한 통화를 <b>받는</b> 방향이다
     * @param shortfall 채워야 할 받는 통화 기준 부족분
     * @param rate      그 게임 거래일의 원/달러 매매기준율
     * @return 보내는 통화 기준 최소 금액
     */
    public BigDecimal shortfallSendAmount(ExchangeDirection direction, BigDecimal shortfall,
                                          BigDecimal rate) {
        return ExchangeQuote.minimumSendAmount(direction, shortfall, rate);
    }

    /**
     * 환전 이력 (기획서 7.4.3). 항목의 형태는 실행 응답과 같다.
     *
     * <p>거래 구간이 아니어도 열람할 수 있다 — 기획서 0장 시간표가 열람 전용 시간에도 계좌·내역
     * 조회를 허용한다.
     *
     * @param userId   세션의 User 식별자
     * @param pageable 페이지와 정렬. 최신순은 호출 측이 고정한다
     * @return 환전 한 페이지
     * @throws BusinessException 그 시즌 계좌가 없을 때 (404)
     */
    public PageResponse<ExchangeResponse> history(Long userId, Pageable pageable) {
        Account account = accountRepository.findByUserIdAndSeasonId(userId, seasonId)
                .orElseThrow(ExchangeService::accountMissing);

        Page<Exchange> page = exchangeRepository.findByAccountId(account.getId(), pageable);
        Map<Long, LocalDate> gameDates = gameDates(page.getContent());
        return PageResponse.from(page.map(
                exchange -> ExchangeResponse.of(exchange, gameDates.get(exchange.getTickSeq()))));
    }

    /**
     * 거래 구간인지 확인하고 적용 환율의 기준 틱을 얻는다.
     *
     * <p>거래 구간인데도 공개된 틱이 없는 순간이 있다 — 개장(11:05) 직후 첫 틱이 나가기 전
     * 30여 초다. 그때는 적용할 환율이 없으므로 거절한다. 조용히 직전 거래일 환율을 쓰면
     * 브로드캐스트된 시세와 다른 값으로 환전되고, 그 차이는 사용자가 볼 수 없다.
     */
    private GameTick tradingTick() {
        GameClockSnapshot clock = phases.requireTrading();
        if (clock.tickSeq() == null) {
            throw new BusinessException(HttpStatus.CONFLICT, "환율이 아직 공개되지 않았습니다.");
        }
        return gameTicks.find(clock.tickSeq()).orElseThrow(() -> {
            log.error("Published tick {} is absent from game_ticks (season {})", clock.tickSeq(), seasonId);
            return new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "공개 기준 틱을 찾을 수 없습니다.");
        });
    }

    /**
     * 금액을 검증하고 계산한다. 형식 검증을 서비스에서 하는 이유는 허용 자릿수가 방향에 따라
     * 달라(원화 0, 달러 2) Bean Validation 으로 표현할 수 없기 때문이다.
     *
     * <p>검사 순서가 중요하다. <b>크기를 먼저 본다</b> — 뒤의 검사와 계산이 값을 전개하므로,
     * 그 전에 걸러야 한다.
     */
    private static ExchangeQuote quote(ExchangeDirection direction, BigDecimal amount, GameTick tick) {
        if (amount.signum() <= 0) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "환전 금액은 0보다 커야 합니다.");
        }
        // 지수 표기(예: 1E+9000000)는 입력이 열 글자인데 전개하면 900만 자리가 된다. 자릿수
        // 검사는 이것을 통과시킨다 — scale 이 음수라 "소수가 있는가"에 걸리지 않는다. 정수부
        // 자릿수를 전개 없이 세어(precision - scale) 먼저 거른다. 상한은 컬럼이 정한다:
        // NUMERIC(38, 4) 의 정수부는 34 자리다.
        if (amount.precision() - amount.scale() > MAX_INTEGER_DIGITS) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "환전 금액이 너무 큽니다.");
        }
        int sendScale = ExchangeQuote.sendScale(direction);
        if (amount.stripTrailingZeros().scale() > sendScale) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, direction == ExchangeDirection.KRW_TO_USD
                    ? "원화는 1원 단위로만 환전할 수 있습니다."
                    : "달러는 1센트 단위로만 환전할 수 있습니다.");
        }

        ExchangeQuote quote = ExchangeQuote.of(direction, amount, tick.getUsdKrw());
        // 수령액이 0 이면 보낸 금액만 사라진다. 수수료 반올림과 수령액 내림이 겹쳐 생기는
        // 구간이고(예: 1원), 명세에 규정이 없어 거절로 정했다.
        if (quote.receiveAmount().signum() <= 0) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "환전 금액이 너무 작아 받을 금액이 없습니다.");
        }
        return quote;
    }

    /** 잠기지 않은 보내는 통화 현금. 통화는 방향이 결정한다. */
    private static BigDecimal availableCash(Account account, ExchangeDirection direction) {
        return direction == ExchangeDirection.KRW_TO_USD ? account.availableKrw() : account.availableUsd();
    }

    /**
     * 이력 항목의 게임 거래일. 환전 행은 틱 순번만 갖고 있어 편성에서 거래일을 얻는다 —
     * 한 페이지의 틱을 한 번에 읽는다.
     */
    private Map<Long, LocalDate> gameDates(List<Exchange> exchanges) {
        List<Long> tickSeqs = exchanges.stream().map(Exchange::getTickSeq).distinct().toList();
        return gameTicks.findAll(tickSeqs).stream()
                .collect(Collectors.toMap(GameTick::getTickSeq, GameTick::getGameDate,
                        (first, second) -> first));
    }

    private static BusinessException accountMissing() {
        return new BusinessException(HttpStatus.NOT_FOUND, "시즌 계좌가 없습니다.");
    }
}

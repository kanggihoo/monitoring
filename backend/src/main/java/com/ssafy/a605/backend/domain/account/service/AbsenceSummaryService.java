package com.ssafy.a605.backend.domain.account.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.ssafy.a605.backend.domain.account.dto.AbsenceSummaryResponse;
import com.ssafy.a605.backend.domain.account.entity.Account;
import com.ssafy.a605.backend.domain.account.entity.Position;
import com.ssafy.a605.backend.domain.account.repository.AccountRepository;
import com.ssafy.a605.backend.domain.account.repository.ExchangeRepository;
import com.ssafy.a605.backend.domain.account.repository.PositionRepository;
import com.ssafy.a605.backend.domain.ipo.repository.IpoOfferingRepository;
import com.ssafy.a605.backend.domain.ipo.repository.IpoSubscriptionRepository;
import com.ssafy.a605.backend.domain.market.service.MarketPriceService;
import com.ssafy.a605.backend.domain.market.service.MarketPriceService.AsOfPrice;
import com.ssafy.a605.backend.domain.order.repository.ExecutionRepository;
import com.ssafy.a605.backend.domain.replay.entity.ReplayState;
import com.ssafy.a605.backend.domain.replay.service.ReplayService;
import com.ssafy.a605.backend.domain.season.entity.GameTick;
import com.ssafy.a605.backend.domain.season.repository.GameTickRepository;
import com.ssafy.a605.backend.domain.season.service.GameTickCatalog;
import com.ssafy.a605.backend.global.exception.BusinessException;
import com.ssafy.a605.backend.global.response.Amounts;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 부재 중 요약 (기획서 7.5, {@code acc.md} §부재 요약).
 *
 * <p><b>판정 기준은 시각이 아니라 확인한 틱이다.</b> 계좌가 마지막으로 확인 처리한 틱과 현재
 * 틱의 차이가 50 이상이면 부재로 본다 — 연결 기반 신호는 자동 재연결이 리셋하고, 탭만 열어 둔
 * 채 자리를 비우면 어떤 신호로도 잡히지 않는다.
 *
 * <p><b>호출 시점은 서버가 정하지 않는다.</b> 프론트가 앱 로드·탭 활성화 때 물어보고 서버는
 * 판정만 돌려준다. 장중에 모달이 저절로 튀어나오는 경로는 없다.
 */
/**
 * <b>{@code REPEATABLE_READ} 를 유지한다</b> ({@code S15P21A605-205} 감사). 부재 동안 벌어진
 * 일을 하나로 합치느라 계좌·체결·환전·공모 청약을 각각 읽는데, 그 사이에도 사용자의 다른
 * 요청과 틱이 계속 돈다. 읽기마다 다른 스냅샷이면 합계와 내역이 서로 맞지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class AbsenceSummaryService {

    private final AccountRepository accountRepository;
    private final PositionRepository positionRepository;
    private final ExchangeRepository exchangeRepository;
    private final AccountLedgerService ledger;
    private final ExecutionRepository executionRepository;
    private final IpoOfferingRepository offeringRepository;
    private final IpoSubscriptionRepository subscriptionRepository;
    private final GameTickRepository gameTickRepository;
    private final GameTickCatalog gameTicks;
    private final MarketPriceService marketPrices;
    private final ReplayService replay;

    @Value("${app.replay.season-id:1}")
    private long seasonId;

    /**
     * 부재로 보는 최소 틱 수. 기본값 50 은 <b>게임 50 거래일이며 실제로 약 30 분</b>이다
     * (기획서 7.5.1).
     *
     * <p>재생 페이스가 바뀌면 30 분에 해당하는 틱 수도 바뀌므로 편성값으로 둔다 —
     * {@code poll-delay-ms} 와 같은 자리다.
     */
    @Value("${app.absence.threshold-ticks:50}")
    private int absenceTicks;

    /**
     * 그 사람이 자리를 비운 사이에 무슨 일이 있었는지.
     *
     * <p><b>상태를 바꾸지 않는다.</b> 확인 처리는 별도 요청이다 — 조회가 확인까지 하면 모달을
     * 띄우기만 하고 못 본 경우에도 본 것이 된다.
     *
     * @param userId 보는 사람
     * @return 부재 요약. 기준에 못 미치면 판정 결과 하나만 담긴다
     */
    public AbsenceSummaryResponse summary(Long userId) {
        Long tickSeq = replay.clock().tickSeq();
        Account account = accountRepository.findByUserIdAndSeasonId(userId, seasonId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND,
                        "이 시즌의 계좌가 없습니다."));
        long from = account.getLastAckedTickSeq();
        // 개장 전이라 공개된 틱이 없으면 놓친 구간도 없다.
        if (tickSeq == null || tickSeq - from < absenceTicks) {
            return AbsenceSummaryResponse.present();
        }
        Change changed = change(account, from, tickSeq);
        return AbsenceSummaryResponse.of(from, tickSeq,
                gameDate(from), gameDate(tickSeq),
                filledOrderCount(account.getId(), from, tickSeq),
                eventCount(from, tickSeq),
                changed == null ? null : changed.valuationKrw(),
                changed == null ? null : changed.returnRate());
    }

    /**
     * 확인한 지점을 옮긴다.
     *
     * @param userId       확인한 사람
     * @param ackedTickSeq 모달이 보여준 마지막 틱
     */
    @Transactional
    public void ack(Long userId, long ackedTickSeq) {
        Account account = ledger.lockedAccount(userId);
        account.ackAbsence(ackedTickSeq);
    }

    /**
     * 구간 중 체결된 예약 건수. <b>확인한 틱 자체는 구간에 넣지 않는다</b> — 그 틱은 이미 본
     * 것이므로, 넣으면 같은 체결이 두 번 보고된다.
     */
    private int filledOrderCount(Long accountId, long from, long to) {
        return executionRepository
                .countByAccountIdAndOrderIdIsNotNullAndTickSeqGreaterThanAndTickSeqLessThanEqual(
                        accountId, from, to);
    }

    /**
     * 구간 중 일어난 주요 이벤트 수. 기획서 7.5.2 가 꼽은 <b>IPO 와 무료 타임</b> 둘을 센다.
     *
     * <p>IPO 는 공시·마감·상장 셋을 각각 센다 ({@code ipo.md} §실시간이 셋으로 나눠 둔 그
     * 순간들이다). 무료 타임은 일차마다 한 번 시작하므로, 구간이 그 경계를 넘은 일차 수를 센다.
     *
     * <p><b>랭킹 암전과 뉴스는 세지 않는다.</b> 암전은 리더보드를 가리는 화면 상태이지 놓쳐서
     * 아쉬운 사건이 아니고, 기획서도 꼽지 않았다. 뉴스는 80 개월에 400~600 건이라 30 분 부재에도
     * 수십 건이 잡혀 "주요"가 아니게 된다 — 그쪽은 알림 센터가 맡는다 (기획서 15.3).
     */
    private int eventCount(long from, long to) {
        long ipo = offeringRepository.countEventsBetween(seasonId, from + 1, to);
        long feeFree = gameTickRepository.findDayOffsets(seasonId, from + 1, to).stream()
                .filter(day -> day.crossed(ReplayState.FEE_FREE_START))
                .count();
        return Math.toIntExact(ipo + feeFree);
    }

    /**
     * 구간 동안 총평가액이 얼마나 움직였나.
     *
     * <p><b>시작 시점의 평가액은 저장돼 있지 않다.</b> 대신 지금 상태에서 그 사이의 사건을
     * 되돌려 그때의 현금·수량을 복원하고({@link AbsenceRewind}), 그 시점의 종가·환율로 다시
     * 평가한다 — {@code acc.md} 가 이 값을 <b>파생</b>으로 표기한 것이 이 뜻이다.
     *
     * <p><b>값을 지어내지 않는다.</b> 시작 틱이 없거나(개장 전에 만들어진 계좌) 그 시점 종가가
     * 없으면 {@code null} 을 주고, 응답에서 그 두 필드가 빠진다. 0 을 채우면 화면이 "변동이
     * 없었다"로 읽는다.
     *
     * <p><b>일차 초기화를 건너뛴 구간도 {@code null} 이다.</b> 초기화는 현금을 시드로 되돌리고
     * 보유를 지우면서 <b>체결을 만들지 않으므로</b>, 되짚기가 그 사건을 볼 수 없다. 그대로
     * 빼면 "자는 사이 -6조" 같은 숫자가 나온다.
     *
     * <p><b>틱의 일차만 비교해서는 부족하다.</b> 브리핑에 자산이 초기화된 뒤에도 공개된 틱은
     * 한동안 전일 마지막 틱 그대로다 — 그 구간은 시작·끝이 모두 어제 틱이라 일차 비교로는
     * 걸리지 않는다. 그래서 <b>계좌가 초기화를 받은 일차</b>를 기준으로 본다.
     */
    private Change change(Account account, long from, long to) {
        GameTick fromTick = gameTicks.find(from).orElse(null);
        GameTick toTick = gameTicks.find(to).orElse(null);
        if (fromTick == null || toTick == null) {
            return null;
        }
        if (account.getDailyBaseSeasonDay() > fromTick.getSeasonDay()) {
            return null;
        }
        List<Position> positions = positionRepository.findByAccountId(account.getId());
        AbsenceRewind.Snapshot then = AbsenceRewind.to(account, positions,
                executionRepository.findByAccountIdAndTickSeqGreaterThanAndTickSeqLessThanEqual(
                        account.getId(), from, to),
                exchangeRepository.findByAccountIdAndTickSeqGreaterThanAndTickSeqLessThanEqual(
                        account.getId(), from, to),
                subscriptionRepository.findConvertedBetween(account.getId(), from, to));

        BigDecimal before = valuation(then.krwCash(), then.usdCash(), then.quantities(), fromTick);
        BigDecimal after = valuation(account.getKrwCash(), account.getUsdCash(),
                quantities(positions), toTick);
        if (before == null || after == null) {
            return null;
        }
        BigDecimal changed = after.subtract(before);
        return new Change(changed, Amounts.percent(changed, account.getStartingBalanceKrw()));
    }

    /**
     * 그 틱의 종가와 환율로 평가한다. 현금은 <b>잠긴 몫을 포함한 총액</b>이다 — 잠금은 총평가액을
     * 바꾸지 않는다.
     *
     * @return 총평가액. 보유한 종목의 그 시점 종가가 하나라도 없으면 {@code null}
     */
    private BigDecimal valuation(BigDecimal krwCash, BigDecimal usdCash,
                                 Map<Long, Integer> quantities, GameTick tick) {
        Map<Long, Integer> held = quantities.entrySet().stream()
                .filter(entry -> entry.getValue() > 0)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        Map<Long, AsOfPrice> prices = marketPrices.pricesAsOf(held.keySet(), tick.getGameDate());
        if (!prices.keySet().containsAll(held.keySet())) {
            // 그 시점에 상장 전이었던 종목을 들고 있다고 나온 것이다. 되짚기가 어긋났다는 뜻이라
            // 값을 만들지 않는다.
            log.error("Cannot value account as of tick {}: no close for {}", tick.getTickSeq(),
                    held.keySet().stream().filter(id -> !prices.containsKey(id)).toList());
            return null;
        }
        BigDecimal rate = tick.getUsdKrw();
        BigDecimal holdings = held.entrySet().stream()
                .map(entry -> prices.get(entry.getKey()).closeKrw(rate)
                        .multiply(BigDecimal.valueOf(entry.getValue())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return krwCash.add(usdCash.multiply(rate)).add(holdings);
    }

    private static Map<Long, Integer> quantities(List<Position> positions) {
        return positions.stream()
                .collect(Collectors.toMap(Position::getInstrumentId, Position::getQuantity));
    }

    /**
     * 구간의 평가액·수익률 변동. 둘은 같은 사실을 두 단위로 말한다 — 분모가 사람마다 다른
     * 시작 잔고라 프론트가 나누게 두지 않는다.
     *
     * @param valuationKrw 총평가액 변동
     * @param returnRate   누적 수익률 변동(%p)
     */
    record Change(BigDecimal valuationKrw, BigDecimal returnRate) {
    }

    /**
     * 그 틱의 게임 거래일. 편성에 없으면 <b>시즌의 첫 틱</b>으로 대신한다.
     *
     * <p>계좌가 개장 전에 만들어지면 확인한 틱이 0 이라 행이 없다 — 그때는 놓친 구간의 첫 틱이
     * 곧 시작이다.
     *
     * <p><b>{@code tickSeq + 1} 로 대신하지 않는다.</b> 그 방식은 "시즌의 첫 틱이 1 번" 이라는
     * 가정인데, {@code tick_seq} 는 전역 기본키라 시즌마다 구간을 나눠 쓴다 (시즌 2 는
     * 1,000,001 부터). 시즌 2 에서는 1 번 틱이 편성에 없어 폴백이 함께 빗나가고, 그러면
     * {@code fromGameDate} 가 빠진 응답이 나가 화면이 그 자리에서 깨진다.
     *
     * @param tickSeq 보고 싶은 틱
     * @return 그 거래일. 편성이 비어 있으면 {@code null}
     */
    private LocalDate gameDate(long tickSeq) {
        return gameTicks.find(tickSeq)
                .or(gameTicks::first)
                .map(GameTick::getGameDate)
                .orElse(null);
    }

}

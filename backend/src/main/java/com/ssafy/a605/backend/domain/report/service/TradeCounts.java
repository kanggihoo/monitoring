package com.ssafy.a605.backend.domain.report.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;

import com.ssafy.a605.backend.domain.order.entity.OrderSide;
import com.ssafy.a605.backend.domain.order.repository.ExecutionRepository.DailyTrade;
import com.ssafy.a605.backend.domain.report.service.DailyFifo.Paired;

/**
 * 성향 판정의 재료를 체결에서 센다 (AI 전처리 요청서 v5 §7.1).
 *
 * <p><b>세기만 하고 판정하지 않는다.</b> 몇 건부터 어느 단계인지는 AI 쪽 임계값이다 — 백엔드가
 * 비율로 나눠 보내면 그 분모의 정의가 배포에 굳는다.
 *
 * <p><b>구간을 모른다.</b> 넘겨받은 체결 목록이 구간을 정한다 — 시즌 리포트는 3일치를 한 번에
 * 넣고, 일일 리포트의 누적 집계는 그 일차까지를 넣는다 (요청서 2026-09-18 요청 ②). 그래서
 * "일차별로 다시 계산하는 새 로직"이 없다.
 *
 * <p><b>단, 매수·매도 짝짓기만은 일차로 끊는다.</b> 일차 경계에서 보유가 초기화되므로 1 일차에
 * 산 것을 3 일차에 팔 수 없고, 구간 전체를 한 번에 짝지으면 그 불가능한 짝이 생긴다
 * ({@link #pairWithinDays}).
 *
 * <p>시즌 쪽과 일일 쪽이 이 셈을 공유해야 하는 이유는 3일차의 누적이 곧 시즌 전체라는 계약이다 —
 * 두 경로가 다른 코드로 세면 같은 계좌가 두 값을 받는다.
 */
final class TradeCounts {

    /**
     * 상대 가격 위치의 고점·저점 기준. 판정은 AI 가 하지만 <b>세는 것은</b> 백엔드라, 이 경계만은
     * 양쪽이 같은 값을 알아야 한다.
     */
    private static final BigDecimal HIGH = new BigDecimal("0.85");
    private static final BigDecimal LOW = new BigDecimal("0.15");

    /** '금방 팔았다'의 기준(게임 거래일). 게임 1 거래일이 실시간 약 36 초다. */
    private static final int QUICK_SELL_DAYS = 20;

    /** 빠른 익절·손절의 수익률 구간(%). */
    private static final BigDecimal QUICK_PROFIT_FROM = BigDecimal.ONE;
    private static final BigDecimal QUICK_PROFIT_TO = new BigDecimal("3");
    private static final BigDecimal QUICK_STOP_FROM = new BigDecimal("-5");
    private static final BigDecimal QUICK_STOP_TO = new BigDecimal("-3");

    /** 저점 손절로 세는 손실의 경계(%). */
    private static final BigDecimal PANIC_LOSS = new BigDecimal("-5");

    int buyCount;
    int sellCount;
    int fomoBuyCount;
    int bottomSnipeBuyCount;
    int peakSellWinCount;
    int panicBottomSellCount;
    int quickProfitCount;
    int quickStopCount;
    int winCount;
    int holdingDaysSum;
    int closedPositionCount;

    private TradeCounts() {
    }

    /**
     * 체결 하나하나를 재료로 센다.
     *
     * @param trades     셀 구간의 체결. 이 목록이 곧 구간이다
     * @param tickByDate 게임 거래일 → 틱 순번. 보유 기간을 재는 자다
     * @param positions  상대 가격 위치를 잴 종가 창
     * @return 그 구간의 셈
     */
    static TradeCounts of(java.util.List<DailyTrade> trades, Map<LocalDate, Long> tickByDate,
                          PricePosition positions) {
        TradeCounts counts = new TradeCounts();
        Map<Long, Paired> paired = pairWithinDays(trades, tickByDate);
        for (DailyTrade trade : trades) {
            Optional<BigDecimal> position = positions.of(trade.instrumentId(), trade.gameDate(),
                    trade.executedPrice());
            boolean high = position.filter(r -> r.compareTo(HIGH) >= 0).isPresent();
            boolean low = position.filter(r -> r.compareTo(LOW) <= 0).isPresent();
            if (trade.side() == OrderSide.BUY) {
                counts.buyCount++;
                counts.fomoBuyCount += high ? 1 : 0;
                counts.bottomSnipeBuyCount += low ? 1 : 0;
                continue;
            }
            counts.sellCount++;
            boolean won = trade.realizedPnl() != null && trade.realizedPnl().signum() > 0;
            counts.winCount += won ? 1 : 0;
            counts.peakSellWinCount += high && won ? 1 : 0;

            Paired pair = paired.get(trade.executionId());
            if (pair == null) {
                // 짝지을 매수가 구간 안에 없다. 보유 기간과 수익률이 필요한 셈에서만 빠진다.
                continue;
            }
            counts.holdingDaysSum += pair.holdingDays();
            counts.closedPositionCount++;
            if (low && pair.returnRate().compareTo(PANIC_LOSS) <= 0) {
                counts.panicBottomSellCount++;
            }
            if (pair.holdingDays() <= QUICK_SELL_DAYS) {
                counts.quickProfitCount += between(pair.returnRate(), QUICK_PROFIT_FROM,
                        QUICK_PROFIT_TO) ? 1 : 0;
                counts.quickStopCount += between(pair.returnRate(), QUICK_STOP_FROM,
                        QUICK_STOP_TO) ? 1 : 0;
            }
        }
        return counts;
    }

    /**
     * 선입선출 짝짓기를 <b>일차 안에서만</b> 한다.
     *
     * <p>일차 경계에서 보유가 초기화되므로(일차 초기화, {@code DailySettlementService.resetDaily})
     * 1 일차에 산 것을 3 일차에 팔 수 없다. 구간 전체를 한 번에 짝지으면 그 불가능한 짝이
     * 생기고, 보유 기간이 이틀치로 늘어나 "금방 팔았다" 판정이 통째로 어긋난다.
     *
     * <p><b>건수는 구간 전체로 센다.</b> 매수·매도·고점·저점 판정은 체결 하나만 보므로 경계와
     * 무관하다. 경계가 갈라놓는 것은 매수와 매도의 <b>관계</b>뿐이다.
     *
     * <p>일차가 하나뿐인 호출(폐장 당일 집계)에서는 묶음이 하나라 이전과 결과가 같다.
     */
    private static Map<Long, Paired> pairWithinDays(java.util.List<DailyTrade> trades,
                                                    Map<LocalDate, Long> tickByDate) {
        Map<Short, java.util.List<DailyTrade>> byDay = trades.stream().collect(
                java.util.stream.Collectors.groupingBy(DailyTrade::seasonDay,
                        java.util.LinkedHashMap::new, java.util.stream.Collectors.toList()));
        Map<Long, Paired> paired = new java.util.HashMap<>();
        byDay.values().forEach(day -> paired.putAll(DailyFifo.pair(day, tickByDate)));
        return paired;
    }

    private static boolean between(BigDecimal value, BigDecimal from, BigDecimal to) {
        return value.compareTo(from) >= 0 && value.compareTo(to) <= 0;
    }
}

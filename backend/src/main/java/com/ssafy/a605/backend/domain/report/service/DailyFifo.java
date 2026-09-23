package com.ssafy.a605.backend.domain.report.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.ssafy.a605.backend.domain.order.entity.OrderSide;
import com.ssafy.a605.backend.domain.order.repository.ExecutionRepository.DailyTrade;
import com.ssafy.a605.backend.global.response.Amounts;

/**
 * 매도를 매수와 <b>선입선출</b>로 짝지어 보유 기간과 수익률을 낸다 (전처리 요청서 v5 §5).
 *
 * <p><b>FIFO 로 고정한다.</b> 평균 단가나 후입선출을 쓰면 같은 체결 목록에서 다른 값이 나오는데,
 * 리포트는 폐장 스냅샷이라 그러면 안 된다.
 *
 * <p><b>수익률에 환율이 필요 없다.</b> 매수가와 매도가가 같은 통화라 비율에서 약분된다.
 *
 * <p><b>보유 기간은 틱 번호의 차이다.</b> 틱 하나가 게임 거래일 하나이므로 (하루가 게임 2년 3개월,
 * 약 560 거래일이다) 날짜를 빼는 것보다 정확하다 — 달력 날짜를 빼면 주말과 휴장이 섞인다.
 *
 * <p><b>그 일차 밖에서 넘어온 포지션은 짝지을 매수가 없다.</b> 시즌 전체를 훑으면 찾을 수 있지만
 * 일차 하나의 스냅샷을 만드는 자리라 그렇게 하지 않는다 — 그런 매도는 결과에서 빠지고, AI 는
 * 보유 기간 문장을 쓰지 않는다.
 */
final class DailyFifo {

    /** 평단 계산의 중간 자릿수. 저장하지 않고 비율을 내는 데만 쓴다. */
    private static final int COST_SCALE = 12;

    private DailyFifo() {
    }

    /**
     * 짝지어진 매도 한 건.
     *
     * @param holdingDays 짝지은 매수까지의 게임 거래일 수. 여러 묶음이면 수량 가중 평균이다
     * @param returnRate  취득 원가 대비 수익률(%)
     */
    record Paired(int holdingDays, BigDecimal returnRate) {
    }

    /**
     * 그 계좌의 하루를 짝지운다.
     *
     * @param trades    그 계좌의 그 일차 체결. <b>체결 순서</b>여야 한다 — 선입선출이 순서에
     *                  기대기 때문이다
     * @param tickByDate 게임 거래일 -> 틱 번호. 보유 기간을 재는 자다
     * @return 짝지어진 매도만. 체결 식별자로 찾는다
     */
    static Map<Long, Paired> pair(List<DailyTrade> trades, Map<LocalDate, Long> tickByDate) {
        Map<Long, Deque<Lot>> lots = new HashMap<>();
        Map<Long, Paired> paired = new HashMap<>();

        for (DailyTrade trade : trades) {
            Long tickSeq = tickByDate.get(trade.gameDate());
            if (tickSeq == null) {
                continue;
            }
            if (trade.side() == OrderSide.BUY) {
                lots.computeIfAbsent(trade.instrumentId(), id -> new ArrayDeque<>())
                        .addLast(new Lot(trade.quantity(), trade.executedPrice(), tickSeq));
                continue;
            }
            consume(lots.get(trade.instrumentId()), trade, tickSeq)
                    .ifPresent(result -> paired.put(trade.executionId(), result));
        }
        return paired;
    }

    /**
     * 매도 하나가 먹어 치우는 매수 묶음들. <b>부분만 짝지어지면 짝짓지 않는다</b> — 절반은 그
     * 일차 밖에서 온 포지션이라 보유 기간이 뜻을 잃는다.
     */
    private static Optional<Paired> consume(Deque<Lot> lots, DailyTrade sell,
                                                      long sellTickSeq) {
        if (lots == null) {
            return Optional.empty();
        }
        int remaining = sell.quantity();
        BigDecimal cost = BigDecimal.ZERO;
        long weightedTicks = 0;
        int matched = 0;

        while (remaining > 0 && !lots.isEmpty()) {
            Lot lot = lots.peekFirst();
            int taken = Math.min(remaining, lot.quantity());
            cost = cost.add(lot.price().multiply(BigDecimal.valueOf(taken)));
            weightedTicks += (long) taken * lot.tickSeq();
            matched += taken;
            remaining -= taken;
            lots.removeFirst();
            if (taken < lot.quantity()) {
                lots.addFirst(new Lot(lot.quantity() - taken, lot.price(), lot.tickSeq()));
            }
        }
        if (remaining > 0 || matched == 0) {
            return Optional.empty();
        }

        BigDecimal avgBuyPrice = cost.divide(BigDecimal.valueOf(matched), COST_SCALE,
                RoundingMode.HALF_UP);
        BigDecimal returnRate = Amounts.percent(sell.executedPrice().subtract(avgBuyPrice),
                avgBuyPrice);
        // 보유 기간은 묶음마다의 차이를 수량으로 가중 평균한 값이다 (요청서 v5 §5). 평균 매수
        // 틱을 먼저 반올림하면 정확히 절반인 경우 보유 기간이 하루 짧게 나온다.
        long spread = sellTickSeq * matched - weightedTicks;
        int holdingDays = (int) Math.round((double) spread / matched);
        return Optional.of(new Paired(Math.max(holdingDays, 0), returnRate));
    }

    /**
     * 아직 팔리지 않은 매수 묶음.
     *
     * @param quantity 남은 수량
     * @param price    그 매수의 체결 단가
     * @param tickSeq  그 매수의 틱. 보유 기간의 시작이다
     */
    private record Lot(int quantity, BigDecimal price, long tickSeq) {
    }
}

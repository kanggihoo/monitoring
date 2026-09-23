package com.ssafy.a605.backend.domain.report.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.ssafy.a605.backend.domain.order.entity.OrderSide;
import com.ssafy.a605.backend.domain.order.repository.ExecutionRepository.DailyTrade;
import com.ssafy.a605.backend.domain.report.dto.PatternTag;
import com.ssafy.a605.backend.global.response.Amounts;

/**
 * 폐장 일일 복기 집계의 계산 규칙 ({@code rpt.md} §summary·bestTrade·worstTrade·patternTags,
 * 기획서 13.1.3 · 13.1.4).
 *
 * <p>조회 없이 넘겨받은 값으로만 계산하므로 단위 테스트가 DB 를 필요로 하지 않는다 —
 * {@code HeatmapCalculator} 와 같은 배치다.
 *
 * <p><b>원화 환산은 체결마다 그 틱의 환율로 한다.</b> 일차 하나가 게임 거래일 500여 일을
 * 재생하므로(V18: 1일차 = 2020-01~2022-03) 하루의 환율로 일차 전체를 환산할 수 없다.
 *
 * <p><b>합은 전체 정밀도로 더하고 마지막에 한 번 원화 자릿수로 맞춘다.</b> 체결마다 원 단위로
 * 끊어 더하면 수수료처럼 작은 값이 건수만큼 반올림되어 오차가 쌓인다
 * ({@code Amounts} §나눗셈은 표현 자릿수에서 한 번만 한다).
 *
 * <p><b>실현 손익은 매도에만 있다.</b> {@code executions.realized_pnl} 이 nullable 이고 매수는
 * {@code null} 이다 ({@code OrderService.settle}). 합에서도 빠지고 상·하위 후보에서도 빠지므로
 * 매수만 한 날은 {@code best}·{@code worst} 가 {@code null} 이다 ({@code rpt.md}).
 */
public final class DailySettlementCalculator {

    /**
     * 패턴 태그 판정 기준(%). 체결 뒤 가격이 이만큼 움직였으면 태그가 붙는다.
     *
     * <p>상수로 두는 이유는 기준이 바뀔 수 있기 때문이다. 바뀌어도 이미 저장된 과거 일차의
     * 태그는 그대로다 — 그것이 판정 결과를 저장하는 이유다 ({@code rpt.md}).
     */
    static final BigDecimal TAG_THRESHOLD = BigDecimal.TEN;

    private DailySettlementCalculator() {
    }

    /**
     * 계좌별 당일 집계.
     *
     * @param trades    그 일차의 체결 전부. 계좌가 섞여 있어도 된다
     * @param dayCloses 종목별 <b>그 일차 폐장 시점의 종가</b>. 패턴 태그의 비교 기준이다
     * @return 체결이 있는 계좌의 집계. 거래가 없는 계좌는 키가 없다
     */
    public static Map<Long, SettlementAggregate> aggregate(
            List<DailyTrade> trades, Map<Long, BigDecimal> dayCloses) {

        Map<Long, List<DailyTrade>> byAccount = new HashMap<>();
        for (DailyTrade trade : trades) {
            byAccount.computeIfAbsent(trade.accountId(), key -> new ArrayList<>()).add(trade);
        }
        Map<Long, SettlementAggregate> aggregates = new HashMap<>();
        byAccount.forEach((accountId, rows) -> aggregates.put(accountId, of(rows, dayCloses)));
        return aggregates;
    }

    private static SettlementAggregate of(List<DailyTrade> rows, Map<Long, BigDecimal> dayCloses) {
        BigDecimal fee = BigDecimal.ZERO;
        BigDecimal realized = BigDecimal.ZERO;
        List<Sold> sells = new ArrayList<>();
        List<PatternTag> tags = new ArrayList<>();

        for (DailyTrade row : rows) {
            BigDecimal rate = row.krwRate();
            fee = fee.add(row.fee().multiply(rate));
            if (row.realizedPnl() != null) {
                BigDecimal pnl = row.realizedPnl().multiply(rate);
                realized = realized.add(pnl);
                sells.add(new Sold(row.executionId(), pnl));
            }
            tag(row, dayCloses).ifPresent(tags::add);
        }

        // 상·하위는 원화 환산으로 비교한다. 통화가 섞인 계좌라 체결 통화로 비교하면 달러 손익과
        // 원화 손익이 같은 축에 놓이지 않는다. 같은 손익이면 체결 순서로 갈라 결과를 고정한다.
        sells.sort(Comparator.comparing(Sold::pnl).thenComparing(Sold::executionId));
        tags.sort(Comparator.comparing(PatternTag::executionId));
        return new SettlementAggregate(rows.size(), krw(fee), krw(realized),
                sells.isEmpty() ? null : sells.get(sells.size() - 1).executionId(),
                sells.isEmpty() ? null : sells.get(0).executionId(),
                List.copyOf(tags));
    }

    /**
     * 이 체결에 태그가 붙는가.
     *
     * <p><b>비교 대상은 그 일차 폐장 시점의 종가다.</b> {@code rpt.md} 의 조건은 "매수 후 당일
     * -10%"이고, 기획서가 "당일"을 일관되게 <b>일차</b>(플레이어가 앉아 있는 하루)로 쓴다 —
     * 13.1.3 의 "당일 요약 지표", 11.2 의 "당일 수익률"이 모두 일차 기준이다. 실제 주식에서
     * 아침에 사고 종가를 보는 것과 같은 구조이며, 매도도 대칭이다.
     *
     * <p>게임 거래일 하나를 "당일"로 읽을 수는 없다. 체결가가 그 거래일의 종가이므로
     * ({@code OrderService.referencePrice}, {@code OrderFillService.fill}) 변화율이 늘 0 이 된다.
     * 같은 이유로 <b>일차의 마지막 거래일에 체결한 건은 자연히 태그가 붙지 않는다</b> —
     * 체결가와 기준 종가가 같아 변화율이 0 이다. 따로 걸러내지 않는다.
     *
     * <p><b>네 종류가 같은 계산에서 갈린다.</b> 매수 뒤 빠졌으면 고점 매수, 올랐으면 저점
     * 매수이고 매도는 대칭이다 ({@link #kind}).
     *
     * <p>판정 구간의 길이가 체결 시점에 따라 다르다. 개장 직후 매수는 게임 시각 27 개월을
     * 견디고 폐장 직전 매수는 며칠만 견딘다 — 실제 장에서 9 시 매수와 15 시 55 분 매수가 같은
     * 관계이므로 그대로 둔다.
     */
    private static Optional<PatternTag> tag(DailyTrade row, Map<Long, BigDecimal> dayCloses) {
        BigDecimal close = dayCloses.get(row.instrumentId());
        if (close == null) {
            return Optional.empty();
        }
        BigDecimal price = row.executedPrice();
        BigDecimal changeRate = Amounts.percent(close.subtract(price), price);
        return kind(row.side(), changeRate).map(kind ->
                new PatternTag(kind, row.executionId(), row.instrumentId(), changeRate));
    }

    /**
     * 방향과 변화율이 만나 정해지는 태그 이름.
     *
     * <p><b>이익 쪽도 판정한다.</b> 손실 쪽 둘만 붙이면 리포트가 질책만 하게 되고, 잘한 거래를
     * 짚어 줄 문장이 재료를 못 받는다 (AI 전처리 요청서 v5 §1.1). 부호만 뒤집은 같은 계산이라
     * 조회가 늘지 않는다.
     */
    private static Optional<String> kind(OrderSide side, BigDecimal changeRate) {
        boolean fell = changeRate.compareTo(TAG_THRESHOLD.negate()) <= 0;
        boolean rose = changeRate.compareTo(TAG_THRESHOLD) >= 0;
        if (side == OrderSide.BUY) {
            if (fell) {
                return Optional.of(PatternTag.PANIC_BUY_HIGH);
            }
            return rose ? Optional.of(PatternTag.LUCKY_BUY_LOW) : Optional.empty();
        }
        if (rose) {
            return Optional.of(PatternTag.PANIC_SELL_LOW);
        }
        return fell ? Optional.of(PatternTag.LUCKY_SELL_HIGH) : Optional.empty();
    }

    private static BigDecimal krw(BigDecimal value) {
        return value.setScale(Amounts.KRW_SCALE, RoundingMode.HALF_UP);
    }

    /** 상·하위를 고르기 위한 (체결, 원화 실현 손익) 짝. */
    private record Sold(Long executionId, BigDecimal pnl) {
    }

    /**
     * 한 계좌의 당일 집계. 태그는 아직 객체이며 JSON 직렬화는 저장 직전에 한다 — 계산 규칙과
     * 표현을 섞지 않는다.
     *
     * @param executionCount   당일 체결 건수
     * @param totalFeeKrw      당일 거래 수수료 합계(원화 환산)
     * @param realizedPnlKrw   당일 실현 손익 합계(원화 환산)
     * @param bestExecutionId  실현 손익 상위 1건. 매도가 없으면 {@code null}
     * @param worstExecutionId 실현 손익 하위 1건. 매도가 없으면 {@code null}
     * @param patternTags      자동 태깅 결과. 없으면 빈 목록
     */
    public record SettlementAggregate(int executionCount, BigDecimal totalFeeKrw,
                                      BigDecimal realizedPnlKrw, Long bestExecutionId,
                                      Long worstExecutionId, List<PatternTag> patternTags) {
    }
}

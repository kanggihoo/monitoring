package com.ssafy.a605.backend.domain.social.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import com.ssafy.a605.backend.domain.social.dto.HeatmapBucket;
import com.ssafy.a605.backend.domain.social.dto.HeatmapSnapshot;
import com.ssafy.a605.backend.domain.social.dto.HeatmapSnapshot.Bucket;
import com.ssafy.a605.backend.global.response.Amounts;

/**
 * 카테고리 합계를 비중과 등락으로 바꾼다. DB·시계·Spring 을 모르는 순수 계산이라 집계 규칙은
 * 여기서만 검증하면 된다 ({@code RankingCalculator} 와 같은 자리).
 *
 * <p><b>현금은 빼서 구한다.</b> 총평가액에서 종목 카테고리 합을 뺀 것이 현금이다. 계좌마다 현금을
 * 따로 더하지 않는 이유는 네 버킷의 합이 총평가액과 <b>정확히</b> 같아야 하기 때문이다 — 따로
 * 더하면 같은 값을 두 경로로 구하게 되고, 두 경로가 어긋나면 화면의 비중 합이 100%가 아니다.
 */
public class HeatmapCalculator {

    /** 화면이 매 틱 같은 순서로 그리도록 고정한다. */
    private static final List<HeatmapBucket> ORDER =
            List.of(HeatmapBucket.NASDAQ, HeatmapBucket.KRX, HeatmapBucket.CRYPTO, HeatmapBucket.CASH);

    /**
     * @param tickSeq           기준 틱
     * @param gameDate          그 틱의 게임 거래일
     * @param totalValuationKrw 순위에 든 전 계좌의 평가액 합
     * @param holdings          종목 카테고리별 원화 환산 합계. 비어 있어도 된다
     * @param previous          직전 틱의 집계. 없으면 {@code null} 이고 모든 등락이 0 이다
     * @return 이 틱의 집계
     */
    public HeatmapSnapshot aggregate(long tickSeq, LocalDate gameDate, BigDecimal totalValuationKrw,
                                     Map<HeatmapBucket, BigDecimal> holdings, HeatmapSnapshot previous) {
        Map<HeatmapBucket, BigDecimal> valuations = new EnumMap<>(HeatmapBucket.class);
        BigDecimal invested = BigDecimal.ZERO;
        for (HeatmapBucket bucket : ORDER) {
            if (bucket == HeatmapBucket.CASH) {
                continue;
            }
            BigDecimal value = holdings.getOrDefault(bucket, BigDecimal.ZERO);
            valuations.put(bucket, value);
            invested = invested.add(value);
        }
        valuations.put(HeatmapBucket.CASH, totalValuationKrw.subtract(invested));

        Map<HeatmapBucket, BigDecimal> before = weightsOf(previous);
        List<Bucket> buckets = new ArrayList<>(ORDER.size());
        for (HeatmapBucket bucket : ORDER) {
            BigDecimal value = valuations.get(bucket);
            BigDecimal weight = weight(value, totalValuationKrw);
            buckets.add(new Bucket(bucket, value, weight, delta(weight, before.get(bucket))));
        }
        return new HeatmapSnapshot(tickSeq, gameDate, totalValuationKrw, List.copyOf(buckets));
    }

    /** 참가자가 없는 틱에는 분모가 0 이다. 그때 비중은 0 이며 나눗셈을 하지 않는다. */
    private static BigDecimal weight(BigDecimal value, BigDecimal total) {
        return total.signum() == 0 ? Amounts.ratio(BigDecimal.ZERO) : Amounts.percent(value, total);
    }

    /**
     * 직전 비중이 없으면 등락은 0 이다 — 첫 틱과 재시작 직후가 그렇다 ({@code rank.md} 의
     * {@code rankDelta} 와 같은 규칙). 없는 값을 0 으로 두고 빼면 등락이 비중값 그대로 나와,
     * 아무 일도 없었던 틱이 전부 새로 들어온 것처럼 보인다.
     */
    private static BigDecimal delta(BigDecimal weight, BigDecimal before) {
        return before == null ? Amounts.ratio(BigDecimal.ZERO) : weight.subtract(before);
    }

    private static Map<HeatmapBucket, BigDecimal> weightsOf(HeatmapSnapshot previous) {
        Map<HeatmapBucket, BigDecimal> weights = new EnumMap<>(HeatmapBucket.class);
        if (previous != null) {
            previous.buckets().forEach(bucket -> weights.put(bucket.category(), bucket.weight()));
        }
        return weights;
    }
}

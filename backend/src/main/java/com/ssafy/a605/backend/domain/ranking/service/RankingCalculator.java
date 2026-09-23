package com.ssafy.a605.backend.domain.ranking.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.ToIntFunction;

import com.ssafy.a605.backend.domain.account.dto.AccountValuation;
import com.ssafy.a605.backend.domain.ranking.dto.RankingSnapshot;
import com.ssafy.a605.backend.domain.ranking.dto.RankingSnapshot.Entry;

/**
 * 계좌 평가 결과를 세 트랙의 순위로 바꾼다. DB·시계·Spring 을 모르는 순수 계산이라 순위 규칙은
 * 여기서만 검증하면 된다.
 *
 * <p><b>수익률을 다시 계산하지 않는다.</b> 계좌 도메인의 매 틱 스윕이 낸 {@code cumulativeReturn}
 * · {@code dailyReturn} 을 그대로 순위의 근거로 쓴다 — 기획서 11.1.2 가 "계좌 요약과 동일 기준"을
 * 명시하므로, 여기서 따로 계산하면 계좌 화면과 리더보드의 수익률이 어긋날 길이 생긴다.
 *
 * <p><b>동점은 같은 순위를 주고 다음 순위를 건너뛴다</b> (1, 1, 3). 시드가 같고 표시가 소수
 * 둘째 자리라 초반에는 동점이 흔한데, 가입 순 같은 임의 tie-break 를 넣으면 실력과 무관한 순서가
 * 순위처럼 보인다 ({@code rank.md} §동점 처리). 수익률이 이미 표시 자릿수(둘째 자리)라 동점 판정도
 * 그 자릿수에서 된다 — 화면에 같은 수익률로 보이는 두 사람의 순위가 다르면 설명할 수 없다.
 *
 * <p>같은 순위 안의 나열 순서는 계좌 식별자다. 이것은 순위가 아니라 목록을 매 틱 같은 모양으로
 * 그리기 위한 고정이다.
 */
public class RankingCalculator {

    /**
     * @param tickSeq    기준 틱
     * @param gameDate   그 틱의 게임 거래일
     * @param seasonDay  그 틱의 일차
     * @param valuations 순위에 넣을 모든 계좌. 비어 있어도 된다
     * @param previous   직전 틱의 결과. 없으면 {@code null} 이고 모든 등락이 0 이다
     * @return 이 틱의 순위
     */
    public RankingSnapshot rank(long tickSeq, LocalDate gameDate, short seasonDay,
                                List<AccountValuation> valuations, RankingSnapshot previous) {
        Map<Long, Integer> seasonRanks = competitionRanks(valuations, AccountValuation::cumulativeReturn);
        Map<Long, Integer> dailyRanks = competitionRanks(valuations, AccountValuation::dailyReturn);
        Map<Long, Integer> valuationRanks = competitionRanks(valuations, AccountValuation::totalValuationKrw);

        Map<Long, Entry> byAccountId = new HashMap<>();
        for (AccountValuation v : valuations) {
            Entry before = previous == null ? null : previous.byAccountId().get(v.accountId());
            int seasonRank = seasonRanks.get(v.accountId());
            int dailyRank = dailyRanks.get(v.accountId());
            int valuationRank = valuationRanks.get(v.accountId());
            byAccountId.put(v.accountId(), new Entry(v.accountId(), v.userId(),
                    seasonRank, before == null ? 0 : before.seasonRank() - seasonRank, v.cumulativeReturn(),
                    dailyRank, before == null ? 0 : before.dailyRank() - dailyRank, v.dailyReturn(),
                    valuationRank, before == null ? 0 : before.valuationRank() - valuationRank,
                    v.totalValuationKrw()));
        }
        return new RankingSnapshot(tickSeq, gameDate, seasonDay, valuations.size(),
                sorted(byAccountId, Entry::seasonRank), sorted(byAccountId, Entry::dailyRank),
                sorted(byAccountId, Entry::valuationRank), Map.copyOf(byAccountId), false);
    }

    /**
     * 기준값 내림차순으로 세우고, 앞사람과 값이 같으면 같은 순위를 준다.
     *
     * <p><b>평가액도 같은 함수를 쓴다.</b> 비율이든 금액이든 "내림차순으로 세우고 동점은 같은
     * 순위" 라는 규칙은 하나여야 한다 — 트랙마다 따로 적으면 한쪽만 동점 규칙이 달라진다.
     *
     * <p>다만 <b>동점 판정 자릿수는 기준값이 들고 온 자릿수 그대로다.</b> 수익률은 표시
     * 자릿수(둘째 자리)로 들어오므로 화면에 같게 보이는 둘은 같은 순위가 되고, 평가액은
     * 원 단위라 1원 차이도 순위를 가른다 — 화면에도 원 단위로 나가므로 같은 성질이다.
     */
    private static Map<Long, Integer> competitionRanks(List<AccountValuation> valuations,
                                                       Function<AccountValuation, BigDecimal> basis) {
        List<AccountValuation> order = new ArrayList<>(valuations);
        order.sort(Comparator.comparing(basis).reversed().thenComparingLong(AccountValuation::accountId));
        Map<Long, Integer> ranks = new HashMap<>();
        int rank = 0;
        BigDecimal last = null;
        for (int i = 0; i < order.size(); i++) {
            BigDecimal current = basis.apply(order.get(i));
            if (last == null || current.compareTo(last) != 0) {
                rank = i + 1;
                last = current;
            }
            ranks.put(order.get(i).accountId(), rank);
        }
        return ranks;
    }

    private static List<Entry> sorted(Map<Long, Entry> byAccountId, ToIntFunction<Entry> rank) {
        return byAccountId.values().stream()
                .sorted(Comparator.comparingInt(rank).thenComparingLong(Entry::accountId))
                .toList();
    }
}

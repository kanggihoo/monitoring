package com.ssafy.a605.backend.domain.ranking.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ssafy.a605.backend.domain.ranking.dto.DailySettlementFinalizedEvent;
import com.ssafy.a605.backend.domain.ranking.dto.PointStanding;
import com.ssafy.a605.backend.domain.ranking.repository.DailySettlementRepository;
import com.ssafy.a605.backend.domain.ranking.repository.DailySettlementRepository.PointRow;
import com.ssafy.a605.backend.domain.replay.entity.ReplayState;

import lombok.RequiredArgsConstructor;

/**
 * 포인트 합산 순위 ({@code S15P21A605-250}). {@link RankingRecalculator} 와 같은 일을 하지만
 * 축이 다르다 — 저쪽은 <b>매 틱</b> 전 계좌를 평가하고 이쪽은 <b>확정된 일차</b>를 더한다.
 *
 * <p><b>값이 바뀌는 순간이 하루 한 번뿐이라 스냅샷을 들고 있는다.</b> 점수의 재료는
 * {@code daily_settlements} 이고 그 테이블은 폐장 정산에만 쓰인다. 그런데 리더보드는 초당
 * 수십 건이 오는 경로라({@code /rankings} 가 30 분에 5,794 건) 요청마다 집계하면 하루 한 번
 * 바뀌는 값을 수만 번 다시 만든다. {@link RankingRecalculator#current} ·
 * {@code MarketPriceService.latest} 와 같은 자리다 (ADR-0001 단일 인스턴스 전제).
 *
 * <p><b>빈 결과도 담는다.</b> 무효화 시점이 명확하기 때문이다 — 확정 행이 생기는 순간이 곧
 * {@link DailySettlementFinalizedEvent} 이고, 그때 비운다. 시즌 첫 폐장 전까지 점수는 정말로
 * 없으며 그 사실을 매 요청 다시 확인할 이유가 없다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PointStandingsCalculator {

    private final DailySettlementRepository repository;

    /**
     * 집계를 이 시즌으로 좁힌다. 목록은 {@code RankingService#enrolled} 가 어차피 거르지만
     * <b>순위 번호</b>는 여기서 목록 전체를 세워 매기므로, 지난 시즌 계좌가 섞이면 이 시즌
     * 1 등이 그 수만큼 밀린 번호를 받는다.
     */
    @Value("${app.replay.season-id:1}")
    private long seasonId;

    /** 마지막으로 집계한 순위. 올리기 전에는 {@code null}. */
    private final AtomicReference<List<PointStanding>> current = new AtomicReference<>();

    /**
     * 포인트 순위, 1 등부터.
     *
     * @return 점수가 매겨진 계좌. 확정된 일차가 하나도 없으면 빈 목록
     */
    public List<PointStanding> standings() {
        List<PointStanding> loaded = current.get();
        if (loaded != null) {
            return loaded;
        }
        // 두 스레드가 동시에 집계해도 같은 내용이다. 잠그지 않는다.
        // 리더보드는 확정된 일차 전부를 본다. 마지막 일차까지로 열어 두면 아직 오지 않은
        // 일차는 행이 없어 자연히 빠진다.
        List<PointStanding> ranked = rank(
                repository.aggregatePoints(seasonId, (short) ReplayState.FINAL_DAY));
        current.set(ranked);
        return ranked;
    }

    /** 폐장 정산이 그 일차의 확정 행을 쓴 순간이 점수가 바뀌는 유일한 순간이다. */
    @EventListener
    public void on(DailySettlementFinalizedEvent event) {
        current.set(null);
    }

    /** 집계를 비운다. 개발·테스트 전용 시즌 초기화만 부른다 — {@link RankingRecalculator#clear()} 와 같은 자리다. */
    public void clear() {
        current.set(null);
    }

    /**
     * 점수 내림차순으로 세우고 동점을 가른다 ({@code S15P21A605-250} 확정 규칙).
     *
     * <p><b>동점은 세 값이 모두 같을 때만이다.</b> 점수 → 일간 1 등 횟수 → 일간 수익률 합계
     * 순으로 가르고, 셋이 다 같으면 같은 순위를 주고 다음 순위를 건너뛴다 (1, 1, 3).
     * {@code RankingCalculator.competitionRanks} 와 같은 규칙이며, 거기까지 같으면 공동
     * 시상이 그 결론이다.
     *
     * <p>계좌 식별자로 마지막을 고정하는 것은 <b>순위가 아니라 목록 순서</b>를 위해서다 —
     * 같은 순위 안에서 응답이 호출마다 흔들리지 않게 한다.
     *
     * <p><b>순수 함수라 공개한다.</b> AI 일일 리포트의 누적 순위도 같은 규칙을 써야 하는데,
     * 그쪽은 폐장 이벤트에 얹혀 비동기로 돌아 이 클래스의 캐시가 비워지기 전에 읽을 수 있다
     * ({@link #on}). 캐시를 거치지 않고 집계 결과를 직접 넘겨 세우면 그 경합이 사라지고,
     * 동점 규칙은 여전히 한 곳에만 있다.
     *
     * @param rows 계좌별 포인트 집계
     * @return 점수 내림차순 순위. 동점은 같은 순위이고 다음 순위를 건너뛴다
     */
    public static List<PointStanding> rank(List<PointRow> rows) {
        List<PointRow> order = new ArrayList<>(rows);
        order.sort(Comparator.comparingLong(PointRow::points).reversed()
                .thenComparing(Comparator.comparingLong(PointRow::wins).reversed())
                .thenComparing(Comparator.comparing(PointRow::dailyReturnSum).reversed())
                .thenComparingLong(PointRow::accountId));
        List<PointStanding> ranked = new ArrayList<>(order.size());
        int rank = 0;
        PointRow last = null;
        for (int i = 0; i < order.size(); i++) {
            PointRow row = order.get(i);
            if (last == null || !tied(last, row)) {
                rank = i + 1;
                last = row;
            }
            ranked.add(new PointStanding(row.accountId(), rank, row.points(), row.wins(), row.dailyReturnSum()));
        }
        return List.copyOf(ranked);
    }

    private static boolean tied(PointRow left, PointRow right) {
        return left.points() == right.points() && left.wins() == right.wins()
                && left.dailyReturnSum().compareTo(right.dailyReturnSum()) == 0;
    }
}

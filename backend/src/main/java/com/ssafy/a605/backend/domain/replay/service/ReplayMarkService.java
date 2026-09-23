package com.ssafy.a605.backend.domain.replay.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ssafy.a605.backend.domain.replay.dto.ReplayMark;
import com.ssafy.a605.backend.domain.replay.dto.ReplayMarkKind;
import com.ssafy.a605.backend.domain.replay.dto.ReplaySnapshot;
import com.ssafy.a605.backend.domain.replay.entity.ReplayState;
import com.ssafy.a605.backend.domain.replay.repository.ReplayQueryRepository;
import com.ssafy.a605.backend.domain.replay.repository.ReplayQueryRepository.IpoPoint;
import com.ssafy.a605.backend.domain.replay.repository.ReplayQueryRepository.ListingPoint;
import com.ssafy.a605.backend.domain.replay.repository.ReplayQueryRepository.MonthTick;
import com.ssafy.a605.backend.domain.replay.repository.ReplayQueryRepository.TickPoint;

/**
 * 시즌 편성에서 이동 지점 목록을 계산한다. <b>진행을 전진시키지 않는다</b> — 조회뿐이다.
 *
 * <p>목록은 다섯 갈래다: 일차 경계 7개 · 각 게임 월의 첫 Tick · 공모별 공시·마감·상장 ·
 * 공모 편성이 없는 상장 · 각 일차의 마지막 가격 Tick. 좌표를 상수로 두지 않고 매번 편성에서
 * 만든다.
 */
@Service
@Transactional(readOnly = true)
public class ReplayMarkService {

    /** 일차 경계의 식별자 조각과 표시 이름. 키는 {@link ReplayState#BOUNDARIES} 전부를 덮어야 한다. */
    private static final Map<Integer, BoundaryName> BOUNDARY_NAMES = Map.of(
            ReplayState.BRIEFING, new BoundaryName("briefing", "브리핑"),
            ReplayState.TRADING_OPEN, new BoundaryName("open", "개장"),
            ReplayState.FEE_FREE_START, new BoundaryName("feeFreeStart", "수수료 무료 시작"),
            ReplayState.FEE_FREE_END, new BoundaryName("feeFreeEnd", "수수료 무료 종료"),
            ReplayState.BLACKOUT_START, new BoundaryName("blackout", "랭킹 암전"),
            ReplayState.CLOSE, new BoundaryName("close", "폐장"),
            ReplayState.END, new BoundaryName("announce", "정산 발표"));

    private static final Map<String, String> IPO_NAMES =
            Map.of("DISCLOSURE", "공시", "CLOSE", "청약 마감", "LISTING", "상장");

    private final ReplayQueryRepository repository;
    private final ReplayService replay;

    /** 이동 지점을 계산할 시즌 ID */
    private final long seasonId;

    /**
     * @param repository 편성 조회 레포지토리
     * @param replay     현재 진행 위치를 아는 재생 서비스
     * @param seasonId   대상 시즌 ID (기본값: 1)
     */
    public ReplayMarkService(ReplayQueryRepository repository, ReplayService replay,
            @Value("${app.replay.season-id:1}") long seasonId) {
        this.repository = repository;
        this.replay = replay;
        this.seasonId = seasonId;
    }

    /**
     * 현재 시즌의 이동 지점 전체.
     *
     * @return 일차·가상 위치 오름차순. 편성이 적재되기 전에는 일차 경계만 들어 있다
     */
    public List<ReplayMark> marks() {
        ReplaySnapshot now = replay.snapshot();
        List<Draft> drafts = new ArrayList<>();

        for (short day : repository.findSeasonDays(seasonId)) {
            for (int boundary : ReplayState.BOUNDARIES) {
                BoundaryName name = BOUNDARY_NAMES.get(boundary);
                drafts.add(new Draft("day%d.%s".formatted(day, name.key()), ReplayMarkKind.BOUNDARY,
                        "%d일차 %s".formatted(day, name.label()), day, boundary, null));
            }
        }
        for (MonthTick month : repository.findMonthFirstTicks(seasonId)) {
            drafts.add(draft("month." + month.month(), ReplayMarkKind.MONTH, month.month() + " 첫 틱",
                    month.tick()));
        }
        for (IpoPoint ipo : repository.findIpoPoints(seasonId)) {
            drafts.add(draft("ipo%d.%s".formatted(ipo.offeringId(), ipo.kind().toLowerCase()), ReplayMarkKind.IPO,
                    "%s %s".formatted(ipo.name(), IPO_NAMES.get(ipo.kind())), ipo.tick()));
        }
        for (ListingPoint listing : repository.findListingPoints(seasonId)) {
            drafts.add(draft("listing" + listing.instrumentId(), ReplayMarkKind.LISTING,
                    listing.name() + " 상장", listing.tick()));
        }
        for (TickPoint last : repository.findDayLastTicks(seasonId)) {
            drafts.add(draft("day%d.lastTick".formatted(last.seasonDay()), ReplayMarkKind.DAY_LAST_TICK,
                    "%d일차 마지막 틱".formatted(last.seasonDay()), last));
        }

        return drafts.stream()
                .sorted(Comparator.comparingInt(Draft::seasonDay).thenComparingInt(Draft::offsetSec)
                        .thenComparing(Draft::key))
                .map(draft -> new ReplayMark(draft.key(), draft.kind(), draft.label(), draft.seasonDay(),
                        draft.offsetSec(), draft.tickSeq(), passed(draft, now)))
                .toList();
    }

    /**
     * 요청이 지정한 이동 지점 하나를 찾는다.
     *
     * @param key 이동 지점 식별자
     * @return 그 지점. 없으면 {@code null}
     */
    public ReplayMark find(String key) {
        return marks().stream().filter(mark -> mark.key().equals(key)).findFirst().orElse(null);
    }

    private static Draft draft(String key, ReplayMarkKind kind, String label, TickPoint tick) {
        return new Draft(key, kind, label, tick.seasonDay(), tick.offsetSec(), tick.seq());
    }

    /**
     * 현재 위치보다 앞에 있으면 지나간 것이다. 같은 위치는 아직 처리하지 않았을 수 있으므로
     * 지나간 것으로 보지 않는다 — 최초 상태의 1일차 브리핑이 그 경우다.
     *
     * @param draft 판정할 지점
     * @param now   현재 시계 스냅샷
     * @return 이미 지나갔으면 {@code true}
     */
    private static boolean passed(Draft draft, ReplaySnapshot now) {
        if (draft.seasonDay() != now.seasonDay()) return draft.seasonDay() < now.seasonDay();
        return now.offsetSec().compareTo(BigDecimal.valueOf(draft.offsetSec())) > 0;
    }

    /**
     * 일차 경계 하나의 이름.
     *
     * @param key   식별자의 경계 부분. {@code day1.} 뒤에 붙는다
     * @param label 표시 이름의 경계 부분. {@code 1일차 } 뒤에 붙는다
     */
    private record BoundaryName(String key, String label) {
    }

    /**
     * 지나갔는지를 붙이기 전의 지점.
     *
     * @param key       식별자
     * @param kind      지점의 갈래
     * @param label     표시 이름
     * @param seasonDay 일차
     * @param offsetSec 가상 초 위치
     * @param tickSeq   가격 Tick 이면 그 순번, 시간 경계면 {@code null}
     */
    private record Draft(String key, ReplayMarkKind kind, String label, short seasonDay, int offsetSec,
            Long tickSeq) {
    }
}

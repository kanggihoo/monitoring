package com.ssafy.a605.backend.domain.season.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.ssafy.a605.backend.domain.season.entity.GameTick;
import com.ssafy.a605.backend.domain.season.repository.GameTickRepository;

/**
 * 시즌의 가격 틱 편성을 프로세스 안에 들고 있다 ({@code S15P21A605-172}).
 *
 * <p><b>{@code game_ticks} 는 시즌 중 한 행도 바뀌지 않는다.</b> 시즌 시작 전에 전 구간이 적재되고
 * ({@code _common.md} §9) 재생은 그 위를 읽기만 한다. 그런데 "시계가 말하는 틱의 거래일·환율"을
 * 인증 요청 거의 전부가 PK 로 한 번씩 다시 읽고 있었다 — 계좌·시세·주문·환전·공모·알림, 그리고
 * 시계 자체까지. 1,727 행을 한 번 올려 두면 그 조회가 전부 맵 참조가 된다.
 *
 * <p><b>첫 접근에 통째로 올리고 다시 읽지 않는다.</b> 무효화 시점이 없기 때문이다. 비어 있으면
 * 올리지 않고 다음 접근에 다시 시도한다 — 편성이 없는 상태를 영구히 기억하지 않는다.
 *
 * <p><b>{@code REPEATABLE_READ} 트랜잭션 안에서 불려도 같은 스냅샷이 아니다.</b> 이 참조들 다수가
 * 시계와 같은 트랜잭션에서 일어나 형식상 "같은 스냅샷" 계약을 벗어나지만, 행이 불변이라 실질
 * 차이는 없다. 이것을 알고 쓴다.
 *
 * <p>대상은 재생과 같은 설정값이 정하는 시즌 하나다. 다른 시즌의 틱은 여기 없다.
 */
@Component
public class GameTickCatalog {

    private final GameTickRepository repository;
    private final long seasonId;

    /** 거래일 오름차순(= 틱 순번 오름차순). 올리기 전에는 {@code null}. */
    private volatile Loaded loaded;

    public GameTickCatalog(GameTickRepository repository, @Value("${app.replay.season-id:1}") long seasonId) {
        this.repository = repository;
        this.seasonId = seasonId;
    }

    /**
     * @param tickSeq 틱 순번. {@code null} 이면 빈 값
     * @return 그 틱. 편성에 없으면 빈 값
     */
    public Optional<GameTick> find(Long tickSeq) {
        return tickSeq == null ? Optional.empty() : Optional.ofNullable(load().byTickSeq().get(tickSeq));
    }

    /**
     * @param tickSeqs 틱 순번들. 중복·{@code null} 은 무시한다
     * @return 편성에 있는 것만. 순서는 요청 순서다
     */
    public List<GameTick> findAll(Collection<Long> tickSeqs) {
        Map<Long, GameTick> byTickSeq = load().byTickSeq();
        return tickSeqs.stream().filter(Objects::nonNull).distinct().map(byTickSeq::get).filter(Objects::nonNull).toList();
    }

    /** @return 시즌의 모든 틱, 거래일 오름차순. 편성이 없으면 빈 목록 */
    public List<GameTick> ordered() {
        return load().ordered();
    }

    /** @return 시즌의 첫 틱. 편성이 없으면 빈 값 */
    public Optional<GameTick> first() {
        List<GameTick> ordered = ordered();
        return ordered.isEmpty() ? Optional.empty() : Optional.of(ordered.getFirst());
    }

    /**
     * 그 일차에서 아직 공개하지 않은 가장 이른 틱 ({@code S15P21A605-247}).
     *
     * <p><b>재생 폴러가 100 ms 마다 부르던 질의를 대신한다.</b> 같은 뜻의 SQL 이
     * {@code ReplayQueryRepository.findNextTick} 에 있었는데, 폴러가 초당 10 회 부르고 시계
     * 스냅샷도 요청마다 부르는 바람에 운영 하루치 {@code game_ticks} 접근 99 만 건의 대부분이
     * 이 한 줄이었다. 행이 불변이므로 메모리에서 답할 수 있다.
     *
     * <p><b>일차별로 나눠 들고 틱 순번으로 이진 탐색한다.</b> 한 일차가 580 여 틱이라 선형 탐색도
     * 무해하지만, 폴러가 쉬지 않고 부르는 자리라 상수 비용을 낮춰 둔다. 일차 안의 틱 순번이
     * 연속이라는 가정은 하지 않는다 — 원래 질의도 하지 않았다.
     *
     * @param seasonDay 대상 일차
     * @param published 마지막으로 공개한 틱 순번. 아직 없으면 {@code null}
     * @return 그 일차의 다음 틱. 당일 편성이 끝났으면 빈 값
     */
    public Optional<GameTick> nextAfter(short seasonDay, Long published) {
        List<GameTick> ticks = load().byDay().get(seasonDay);
        if (ticks == null) {
            return Optional.empty();
        }
        long after = published == null ? Long.MIN_VALUE : published;
        int low = 0;
        int high = ticks.size();
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (ticks.get(mid).getTickSeq() <= after) {
                low = mid + 1;
            } else {
                high = mid;
            }
        }
        return low < ticks.size() ? Optional.of(ticks.get(low)) : Optional.empty();
    }

    /**
     * 그 일차의 마지막 틱 ({@code S15P21A605-248}).
     *
     * <p>일차가 끝난 뒤의 평가 기준이 되는 틱이다. 일차 전환은 {@code END} 경계를 지난 뒤에만
     * 일어나므로, 다음 일차의 브리핑에서 이 값은 <b>실제로 마지막까지 공개된 틱</b>과 같다.
     * 프로세스 안의 순위 스냅샷 대신 이것을 쓰는 이유는 재시작에 영향받지 않기 때문이다.
     *
     * @param seasonDay 대상 일차
     * @return 그 일차의 마지막 틱. 편성에 없는 일차면 빈 값
     */
    public Optional<GameTick> lastOf(short seasonDay) {
        List<GameTick> ticks = load().byDay().get(seasonDay);
        return ticks == null || ticks.isEmpty() ? Optional.empty() : Optional.of(ticks.getLast());
    }

    /**
     * 그 일차의 첫 틱 ({@code S15P21A605-253}).
     *
     * <p>일차 구간을 틱 순번으로 표현하는 데 쓴다 — 체결은 {@code season_day} 를 갖고 있지 않아
     * 일차로 거르려면 {@code game_ticks} 를 조인해야 하는데, 호출 측이 그 일차의 시작 틱을 먼저
     * 구해 넘기면 조인 없이 범위 조건이 된다 ({@code ExecutionRepository.search} 와 같은 판단).
     *
     * @param seasonDay 대상 일차
     * @return 그 일차의 첫 틱. 편성에 없는 일차면 빈 값
     */
    public Optional<GameTick> firstOf(short seasonDay) {
        List<GameTick> ticks = load().byDay().get(seasonDay);
        return ticks == null || ticks.isEmpty() ? Optional.empty() : Optional.of(ticks.getFirst());
    }

    private Loaded load() {
        Loaded current = loaded;
        if (current != null) {
            return current;
        }
        List<GameTick> ordered = repository.findBySeasonIdOrderByGameDateAsc(seasonId);
        Map<Long, GameTick> byTickSeq = new HashMap<>(ordered.size() * 2);
        Map<Short, List<GameTick>> byDay = new HashMap<>();
        for (GameTick tick : ordered) {
            byTickSeq.put(tick.getTickSeq(), tick);
            byDay.computeIfAbsent(tick.getSeasonDay(), day -> new ArrayList<>()).add(tick);
        }
        // 거래일 오름차순으로 읽어 왔으므로 일차별 목록도 틱 순번 오름차순이다 —
        // nextAfter 의 이진 탐색이 그것에 기댄다. 적재 순서가 바뀌면 여기서 정렬해야 한다.
        byDay.replaceAll((day, ticks) -> List.copyOf(ticks));
        current = new Loaded(List.copyOf(ordered), Map.copyOf(byTickSeq), Map.copyOf(byDay));
        if (!ordered.isEmpty()) {
            // 두 스레드가 동시에 올려도 같은 내용이다. 잠그지 않는다.
            loaded = current;
        }
        return current;
    }

    private record Loaded(List<GameTick> ordered, Map<Long, GameTick> byTickSeq,
                          Map<Short, List<GameTick>> byDay) {
    }
}

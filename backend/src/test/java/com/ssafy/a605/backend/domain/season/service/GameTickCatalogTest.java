package com.ssafy.a605.backend.domain.season.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ssafy.a605.backend.domain.season.entity.GameTick;
import com.ssafy.a605.backend.domain.season.repository.GameTickRepository;

/**
 * 한 번 올리고 다시 읽지 않는다는 성질만 본다. DB 가 없고 리포지토리는 목이다.
 */
class GameTickCatalogTest {

    private static final long SEASON = 1L;

    private GameTickRepository repository;
    private GameTickCatalog catalog;

    @BeforeEach
    void setUp() {
        repository = mock(GameTickRepository.class);
        catalog = new GameTickCatalog(repository, SEASON);
    }

    @Test
    @DisplayName("조회 여러 번_편성은 한 번만 읽는다")
    void manyLookups_loadOnce() {
        when(repository.findBySeasonIdOrderByGameDateAsc(SEASON)).thenReturn(ticks(1, 2, 3));

        assertThat(catalog.find(2L)).map(GameTick::getGameDate).contains(LocalDate.of(2020, 1, 3));
        assertThat(catalog.find(9L)).isEmpty();
        assertThat(catalog.find(null)).isEmpty();
        assertThat(catalog.findAll(Arrays.asList(3L, 1L, 3L, null, 7L))).extracting(GameTick::getTickSeq)
                .containsExactly(3L, 1L);
        assertThat(catalog.ordered()).extracting(GameTick::getTickSeq).containsExactly(1L, 2L, 3L);
        assertThat(catalog.first()).map(GameTick::getTickSeq).contains(1L);

        verify(repository, times(1)).findBySeasonIdOrderByGameDateAsc(anyLong());
    }

    /** 편성이 없는 상태를 기억하면 적재 뒤에도 영구히 빈 편성이다. */
    @Test
    @DisplayName("편성이 비어 있으면_기억하지 않고 다음에 다시 읽는다")
    void empty_isNotCached() {
        when(repository.findBySeasonIdOrderByGameDateAsc(SEASON)).thenReturn(List.of()).thenReturn(ticks(1));

        assertThat(catalog.first()).isEmpty();
        assertThat(catalog.first()).map(GameTick::getTickSeq).contains(1L);
        assertThat(catalog.find(1L)).isPresent();

        verify(repository, times(2)).findBySeasonIdOrderByGameDateAsc(anyLong());
    }

    /** 폴러가 100 ms 마다 부르는 자리라 경계값이 전부 맞아야 한다 (S15P21A605-247). */
    @Test
    @DisplayName("다음 틱_그 일차에서 아직 공개하지 않은 가장 이른 것")
    void nextAfter_returnsEarliestUnpublishedOfThatDay() {
        when(repository.findBySeasonIdOrderByGameDateAsc(SEASON)).thenReturn(twoDays());

        // 아직 하나도 공개하지 않았으면 그 일차의 첫 틱
        assertThat(catalog.nextAfter((short) 1, null)).map(GameTick::getTickSeq).contains(1L);
        assertThat(catalog.nextAfter((short) 2, null)).map(GameTick::getTickSeq).contains(11L);
        // 중간
        assertThat(catalog.nextAfter((short) 1, 1L)).map(GameTick::getTickSeq).contains(2L);
        assertThat(catalog.nextAfter((short) 1, 2L)).map(GameTick::getTickSeq).contains(3L);
        // 그 일차의 마지막을 공개했으면 비어 있다 — 다음 일차 것을 주지 않는다
        assertThat(catalog.nextAfter((short) 1, 3L)).isEmpty();
        // 다른 일차의 틱 순번을 받아도 그 일차 안에서만 답한다
        assertThat(catalog.nextAfter((short) 2, 3L)).map(GameTick::getTickSeq).contains(11L);
        // 편성에 없는 일차
        assertThat(catalog.nextAfter((short) 9, null)).isEmpty();

        verify(repository, times(1)).findBySeasonIdOrderByGameDateAsc(anyLong());
    }

    /** 일차를 틱 구간으로 바꾸는 자리다 — 양 끝이 맞아야 체결 집계가 하루를 정확히 덮는다 (S15P21A605-253). */
    @Test
    @DisplayName("일차의 양 끝_첫 틱과 마지막 틱")
    void firstOfAndLastOf_returnDayBounds() {
        when(repository.findBySeasonIdOrderByGameDateAsc(SEASON)).thenReturn(twoDays());

        assertThat(catalog.firstOf((short) 1)).map(GameTick::getTickSeq).contains(1L);
        assertThat(catalog.lastOf((short) 1)).map(GameTick::getTickSeq).contains(3L);
        assertThat(catalog.firstOf((short) 2)).map(GameTick::getTickSeq).contains(11L);
        assertThat(catalog.lastOf((short) 2)).map(GameTick::getTickSeq).contains(12L);
        // 편성에 없는 일차
        assertThat(catalog.firstOf((short) 9)).isEmpty();
        assertThat(catalog.lastOf((short) 9)).isEmpty();

        verify(repository, times(1)).findBySeasonIdOrderByGameDateAsc(anyLong());
    }

    private static List<GameTick> ticks(int... seqs) {
        return Arrays.stream(seqs)
                .mapToObj(seq -> tick(seq, (short) 1))
                .toList();
    }

    /** 1 일차는 1~3, 2 일차는 11~12. 일차 사이에 순번이 끊겨 있어도 맞아야 한다. */
    private static List<GameTick> twoDays() {
        return List.of(tick(1, (short) 1), tick(2, (short) 1), tick(3, (short) 1),
                tick(11, (short) 2), tick(12, (short) 2));
    }

    private static GameTick tick(int seq, short seasonDay) {
        return new GameTick((long) seq, SEASON, seasonDay, LocalDate.of(2020, 1, 1).plusDays(seq),
                seq * 30, BigDecimal.valueOf(1200));
    }
}

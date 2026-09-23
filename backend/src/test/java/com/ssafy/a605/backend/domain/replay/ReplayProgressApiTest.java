package com.ssafy.a605.backend.domain.replay;

import static com.ssafy.a605.backend.support.ReplayFixture.awaitQuiet;
import static com.ssafy.a605.backend.support.ReplayFixture.awaitUntil;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;

import com.ssafy.a605.backend.global.security.CookieOrHeaderHttpSessionIdResolver;
import com.ssafy.a605.backend.support.ContainersConfig;
import com.ssafy.a605.backend.support.MutableClock;
import com.ssafy.a605.backend.support.ReplayFixture;
import com.ssafy.a605.backend.support.SessionSupport;

/**
 * 시계를 옮겼을 때 <b>무엇이 어디까지 진행됐는가</b>를 관리자 조회로 확인한다. 폴러가 몇 번
 * 불렸는지나 어떤 메서드를 지났는지는 보지 않는다.
 *
 * <p>{@code ReplayApiTest} 와 설정을 같게 두어 같은 컨텍스트·컨테이너를 쓴다.
 */
@Tag("container")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestPropertySource(locations = "classpath:oauth2-test.properties",
        properties = {
                "app.replay.mode=test",
                "app.replay.fixture.enabled=true",
                "app.replay.season-id=1",
                "app.replay.poll-delay-ms=50"
        })
@Import({ContainersConfig.class, SessionSupport.class, ReplayFixture.class, MutableClock.Config.class})
class ReplayProgressApiTest {

    private static final String REPLAY_URI = "/api/v1/admin/replay";
    private static final String AUTH_TOKEN_HEADER = CookieOrHeaderHttpSessionIdResolver.HEADER_NAME;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SessionSupport sessions;

    @Autowired
    private ReplayFixture fixture;

    @Autowired
    private MutableClock clock;

    private String admin;

    @BeforeEach
    void resetReplay() {
        fixture.reset(clock);
        admin = sessions.createSessionFor(fixture.admin());
    }

    @Nested
    @DisplayName("가격 틱 공개")
    class TickPublication {

        @Test
        @DisplayName("틱 공개_틱 직전과 도달 시점_직전에는 미공개이고 도달하면 그 틱만 공개한다")
        void publishesExactlyTheReachedTick() throws Exception {
            resume(1);
            moveTo(ReplayFixture.FIRST_TICK - 1);
            awaitQuiet(ReplayProgressApiTest.this::watermark);
            assertThat(watermark()).isNull();

            moveTo(ReplayFixture.FIRST_TICK);

            awaitTick(1L);
            Map<String, Object> snapshot = snapshot();
            assertThat(snapshot.get("gameDate")).isEqualTo("2020-01-02");
            assertThat(snapshot.get("gameMonth")).isEqualTo("2020-01");
            // 두 번째 틱(65)에는 아직 도달하지 않았다.
            awaitQuiet(ReplayProgressApiTest.this::watermark);
            assertThat(watermark()).isEqualTo(1L);
        }

        @Test
        @DisplayName("틱 공개_여러 틱이 밀린 경우_순번 순서대로 따라잡는다")
        void catchesUpBacklogInOrder() throws Exception {
            resume(1);
            // 한 번에 세 틱을 지나치도록 옮긴다.
            moveTo(ReplayFixture.THIRD_TICK);

            awaitTick(3L);
            assertThat(snapshot().get("gameDate")).isEqualTo("2020-01-06");
        }

        @Test
        @DisplayName("틱 공개_정지 중 시계를 옮기면_아무 틱도 공개하지 않는다")
        void publishesNothingWhilePaused() throws Exception {
            // 정지 상태 그대로 하루치를 옮긴다.
            clock.advance(Duration.ofDays(1));

            awaitQuiet(ReplayProgressApiTest.this::watermark);
            assertThat(watermark()).isNull();
            assertThat(snapshot().get("offsetSec")).isEqualTo(-300.0);
        }
    }

    @Nested
    @DisplayName("시간 경계")
    class Boundaries {

        @Test
        @DisplayName("시간 경계_거래 시작에 도달하면_Phase 가 TRADING 으로 바뀐다")
        void tradingOpensAtOffsetZero() throws Exception {
            resume(1);
            assertThat(snapshot().get("phase")).isEqualTo("BRIEFING");

            moveTo(ReplayFixture.TRADING_OPEN);

            awaitUntil(() -> "TRADING".equals(snapshot().get("phase")), () -> "phase=" + snapshot().get("phase"));
        }

        @Test
        @DisplayName("시간 경계_무료 구간 시작과 종료_Phase 가 아니라 시계 플래그가 바뀐다")
        void feeFreeWindowTogglesFlag() throws Exception {
            resume(1);
            moveTo(ReplayFixture.FEE_FREE_START);
            awaitUntil(() -> Boolean.TRUE.equals(snapshot().get("feeFree")), () -> "무료 구간에 들어가지 않았다");

            assertThat(snapshot().get("phase")).isEqualTo("TRADING");
            assertThat(snapshot().get("feeFreeEndsAt")).isNotNull();

            moveTo(ReplayFixture.FEE_FREE_END);

            awaitUntil(() -> Boolean.FALSE.equals(snapshot().get("feeFree")), () -> "무료 구간이 끝나지 않았다");
            assertThat(snapshot().get("feeFreeEndsAt")).isNull();
        }

        @Test
        @DisplayName("시간 경계_암전 경계를 지나면_암전 플래그가 켜진다")
        void blackoutStarts() throws Exception {
            resume(1);
            moveTo(ReplayFixture.BLACKOUT_START);

            awaitUntil(() -> Boolean.TRUE.equals(snapshot().get("leaderboardBlackout")),
                    () -> "암전이 시작되지 않았다");
            assertThat(snapshot().get("phase")).isEqualTo("TRADING");
        }

        @Test
        @DisplayName("시간 경계_마지막 가격 틱 이후_가격 없는 폐장과 발표가 진행된다")
        void closeAndAnnounceRunWithoutPriceTicks() throws Exception {
            resume(1);
            moveTo(ReplayFixture.LAST_TICK_DAY_1_2);
            awaitTick(4L);

            // 마지막 틱(19440)과 폐장(20100)은 다른 좌표다.
            moveTo(ReplayFixture.CLOSE);
            awaitUntil(() -> "SETTLING".equals(snapshot().get("phase")), () -> "폐장 Phase 로 바뀌지 않았다");
            assertThat(watermark()).isEqualTo(4L);

            // 발표(21300)도 별개 경계다. 테스트 모드의 1일차는 이를 처리한 뒤 곧바로
            // 2일차 브리핑으로 이어지므로 REVIEW_ONLY 가 머무르지 않는다 (스펙 "일차 전환").
            moveTo(ReplayFixture.ANNOUNCE);
            awaitUntil(() -> ((Number) snapshot().get("seasonDay")).intValue() == 2,
                    () -> "발표 뒤 2일차로 이어지지 않았다: " + snapshot());
            assertThat(snapshot().get("phase")).isEqualTo("BRIEFING");
            // 폐장·발표를 가짜 가격 틱으로 만들지 않는다.
            assertThat(watermark()).isEqualTo(4L);
        }

        @Test
        @DisplayName("시간 경계_같은 경계를 다시 확인하면_중복 진행하지 않는다")
        void boundariesAreNotRepeated() throws Exception {
            resume(1);
            moveTo(ReplayFixture.FEE_FREE_START);
            awaitUntil(() -> Boolean.TRUE.equals(snapshot().get("feeFree")), () -> "무료 구간에 들어가지 않았다");
            awaitQuiet(() -> fixture.row().toString());
            Map<String, Object> settled = fixture.row();

            // 시계를 그대로 두고 폴러가 여러 번 더 돌게 한다.
            awaitQuiet(() -> fixture.row().toString());

            assertThat(fixture.row()).isEqualTo(settled);
        }

        @Test
        @DisplayName("시간 경계_경계를 처리하면_커서가 저장되어 재시작 후 재실행되지 않는다")
        void boundaryCursorSurvivesRestart() throws Exception {
            resume(1);
            moveTo(ReplayFixture.FEE_FREE_END);
            awaitUntil(() -> Integer.valueOf(ReplayFixture.FEE_FREE_END)
                            .equals(intOrNull(fixture.row().get("last_boundary_offset_sec"))),
                    () -> "무료 종료 경계가 저장되지 않았다: " + fixture.row().get("last_boundary_offset_sec"));

            Map<String, Object> row = fixture.row();
            assertThat(((Number) row.get("last_boundary_season_day")).intValue()).isEqualTo(1);
            assertThat(((Number) row.get("last_boundary_offset_sec")).intValue()).isEqualTo(ReplayFixture.FEE_FREE_END);
        }
    }

    @Nested
    @DisplayName("일차 전환")
    class DayTransition {

        @Test
        @DisplayName("일차 전환_발표 경계를 넘기면_초과 경과 시간이 다음 일차에 보존된다")
        void carriesOverflowIntoNextDay() throws Exception {
            // 1일차 16:59:58 에서 1배속으로 5초 흐르면 2일차 11:00:03 이다 (스펙 예시).
            fixture.store(1, BigDecimal.valueOf(21298), ReplayFixture.START, BigDecimal.ONE,
                    4L, 1, ReplayFixture.CLOSE);
            clock.set(ReplayFixture.START.plusSeconds(5));

            awaitUntil(() -> ((Number) snapshot().get("seasonDay")).intValue() == 2,
                    () -> "2일차로 넘어가지 않았다: " + snapshot());

            assertThat(snapshot().get("offsetSec")).isEqualTo(-297.0);
            assertThat(snapshot().get("phase")).isEqualTo("BRIEFING");
        }

        @Test
        @DisplayName("일차 전환_정지 중이면_발표 경계를 지나도 넘어가지 않는다")
        void doesNotTransitionWhilePaused() throws Exception {
            fixture.store(1, BigDecimal.valueOf(ReplayFixture.ANNOUNCE), ReplayFixture.START, BigDecimal.ZERO,
                    4L, 1, ReplayFixture.ANNOUNCE);
            clock.advance(Duration.ofHours(12));

            awaitQuiet(() -> snapshot().toString());

            assertThat(((Number) snapshot().get("seasonDay")).intValue()).isEqualTo(1);
            assertThat(snapshot().get("phase")).isEqualTo("REVIEW_ONLY");
        }

        @Test
        @DisplayName("일차 전환_3일차_마지막 틱과 폐장과 발표를 각각 구별해 진행한다")
        void finalDayDistinguishesLastTickCloseAndAnnounce() throws Exception {
            // 3일차 마지막 틱 직전에서 시작한다.
            fixture.store(3, BigDecimal.valueOf(ReplayFixture.LAST_TICK_DAY_3 - 1), ReplayFixture.START,
                    BigDecimal.ONE, 11L, 3, ReplayFixture.BLACKOUT_START);

            moveTo(ReplayFixture.LAST_TICK_DAY_3);
            awaitTick(12L);
            assertThat(snapshot().get("phase")).isEqualTo("TRADING");
            assertThat(snapshot().get("gameDate")).isEqualTo("2026-08-31");

            moveTo(ReplayFixture.CLOSE);
            awaitUntil(() -> "SETTLING".equals(snapshot().get("phase")), () -> "폐장이 진행되지 않았다");

            moveTo(ReplayFixture.ANNOUNCE);
            awaitUntil(() -> "REVIEW_ONLY".equals(snapshot().get("phase")), () -> "발표가 진행되지 않았다");
            assertThat(watermark()).isEqualTo(12L);
        }

        @Test
        @DisplayName("재생 조작_최종 일차 발표 이후_정지와 배속 변경이 계속 받아들여진다")
        void controlsStillWorkAfterFinalAnnounce() throws Exception {
            fixture.store(3, BigDecimal.valueOf(ReplayFixture.ANNOUNCE - 1), ReplayFixture.START,
                    BigDecimal.ONE, 12L, 3, ReplayFixture.CLOSE);
            clock.advance(Duration.ofSeconds(2));
            awaitUntil(() -> "REVIEW_ONLY".equals(snapshot().get("phase")), () -> "발표가 진행되지 않았다");

            // 고정된 뒤에도 실제 시간은 계속 흐르므로 rawOffset 은 상한을 넘어 자란다.
            // 그것을 일차 전환 대기로 오인하면 관리자가 시계를 영원히 되찾지 못한다.
            clock.advance(Duration.ofHours(3));

            resume(0);
            assertThat(((Number) snapshot().get("speed")).intValue()).isZero();
            assertThat(snapshot().get("offsetSec")).isEqualTo(21300.0);
            resume(10);
            assertThat(((Number) snapshot().get("speed")).intValue()).isEqualTo(10);
        }

        @Test
        @DisplayName("일차 전환_최종 일차 발표 이후_게임 위치가 고정된다")
        void finalDayPositionIsPinned() throws Exception {
            fixture.store(3, BigDecimal.valueOf(ReplayFixture.ANNOUNCE - 1), ReplayFixture.START,
                    BigDecimal.ONE, 12L, 3, ReplayFixture.CLOSE);

            clock.advance(Duration.ofHours(6));

            awaitUntil(() -> "REVIEW_ONLY".equals(snapshot().get("phase")), () -> "발표가 진행되지 않았다");
            awaitQuiet(() -> snapshot().toString());

            // 존재하지 않는 4일차로 넘어가지 않고 21300 에 머문다.
            assertThat(((Number) snapshot().get("seasonDay")).intValue()).isEqualTo(3);
            assertThat(snapshot().get("offsetSec")).isEqualTo(21300.0);
        }
    }

    @Nested
    @DisplayName("동시 실행")
    class Concurrency {

        /**
         * 폴러는 계속 도는 중이고 그 위로 배속 변경이 겹친다. 같은 {@code replay_state} 잠금을
         * 쓰므로 틱이 사라지거나 두 번 전진해서는 안 된다 (자동 검증 8).
         */
        @Test
        @DisplayName("배속 변경이 폴러와 겹쳐도_틱이 손실되거나 중복 전진하지 않는다")
        void concurrentSpeedChangesKeepTickSequenceIntact() throws Exception {
            resume(1);
            moveTo(ReplayFixture.TRADING_OPEN);
            awaitUntil(() -> "TRADING".equals(snapshot().get("phase")), () -> "거래가 시작되지 않았다");

            // 진행 중인 폴러와 동시에 여러 요청이 배속을 바꾼다.
            ExecutorService pool = Executors.newFixedThreadPool(4);
            try {
                clock.set(fixture.realTimeAt(ReplayFixture.THIRD_TICK));
                List<Future<?>> running = new ArrayList<>();
                for (int i = 0; i < 8; i++) {
                    int speed = 1 + (i % 4) * 10;
                    running.add(pool.submit(() -> {
                        try {
                            resume(speed);
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                    }));
                }
                for (Future<?> task : running) {
                    task.get(20, TimeUnit.SECONDS);
                }
            } finally {
                pool.shutdownNow();
            }

            // 재앵커가 겹쳐도 공개 틱은 건너뛰지 않고 3까지만 간다.
            awaitTick(3L);
            awaitQuiet(ReplayProgressApiTest.this::watermark);
            assertThat(watermark()).isEqualTo(3L);
            assertThat(snapshot().get("gameDate")).isEqualTo("2020-01-06");
        }
    }

    @Nested
    @DisplayName("고배속")
    class HighSpeed {

        @Test
        @DisplayName("고배속_하루를 한 번에 진행하면_틱을 건너뛰지 않는다")
        void runsAWholeDayWithoutSkippingTicks() throws Exception {
            resume(100);
            // 게임 속 하루 전체(브리핑~발표)를 실제 216초에 해당하는 만큼 옮긴다.
            moveTo(ReplayFixture.ANNOUNCE);

            awaitUntil(() -> ((Number) snapshot().get("seasonDay")).intValue() == 2,
                    () -> "2일차로 넘어가지 않았다: " + snapshot());

            // 1일차 네 틱을 모두 지나고 2일차 브리핑에 있다.
            assertThat(watermark()).isEqualTo(4L);
            assertThat(snapshot().get("phase")).isEqualTo("BRIEFING");
        }
    }

    /** 현재 앵커·배속 기준으로 그 게임 오프셋에 도달한 실제 시각으로 시계를 옮긴다. */
    private void moveTo(int offset) {
        clock.set(fixture.realTimeAt(offset));
    }

    private void resume(int speed) throws Exception {
        mockMvc.perform(patch(REPLAY_URI).header(AUTH_TOKEN_HEADER, admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"speed\":" + speed + "}"))
                .andExpect(status().isOk());
    }

    private void awaitTick(long expected) {
        awaitUntil(() -> expected == (watermark() == null ? -1L : watermark()),
                () -> "공개 틱=" + watermark() + ", 기대=" + expected);
    }

    private static Integer intOrNull(Object value) {
        return value == null ? null : ((Number) value).intValue();
    }

    /** 서버가 공개했다고 응답하는 틱. DB 워터마크와 같은 값이어야 한다. */
    private Long watermark() {
        Object value = snapshot().get("tickSeq");
        return value == null ? null : ((Number) value).longValue();
    }

    private Map<String, Object> snapshot() {
        try {
            String body = mockMvc.perform(get(REPLAY_URI).header(AUTH_TOKEN_HEADER, admin))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            return JsonPath.read(body, "$.data");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

}

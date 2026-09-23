package com.ssafy.a605.backend.domain.replay;

import static com.ssafy.a605.backend.support.SessionSupport.CSRF_COOKIE_NAME;
import static com.ssafy.a605.backend.support.SessionSupport.CSRF_HEADER_NAME;
import static com.ssafy.a605.backend.support.SessionSupport.csrfToken;
import static com.ssafy.a605.backend.support.ReplayFixture.awaitQuiet;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.ssafy.a605.backend.domain.user.entity.Role;
import com.ssafy.a605.backend.domain.user.entity.User;
import com.ssafy.a605.backend.domain.user.repository.UserRepository;
import com.ssafy.a605.backend.domain.user.service.UserService;
import com.ssafy.a605.backend.global.security.CookieOrHeaderHttpSessionIdResolver;
import com.ssafy.a605.backend.support.ContainersConfig;
import com.ssafy.a605.backend.support.MutableClock;
import com.ssafy.a605.backend.support.ReplayFixture;
import com.ssafy.a605.backend.support.SessionSupport;

import jakarta.servlet.http.Cookie;

/**
 * 관리자 재생 REST 를 HTTP 경계에서 검증한다 — 어떤 서비스가 불렸는지가 아니라 응답과
 * 저장된 진행 위치만 본다.
 *
 * <p>실제 시각은 {@link MutableClock} 이 쥔다. 폴러는 진짜 타이머로 돌지만 시계가 멈춰 있으면
 * 도달한 항목이 없어 아무것도 하지 않으므로, 진행 내용은 테스트가 결정한다.
 *
 * <p>진행·경계·일차 전환은 {@code ReplayProgressApiTest}, WS 발행은
 * {@code ReplayRealtimeApiTest} 가 본다. 세 클래스의 설정을 같게 두어 컨텍스트를 공유한다.
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
class ReplayApiTest {

    private static final String REPLAY_URI = "/api/v1/admin/replay";
    private static final String CLOCK_URI = "/api/v1/game/clock";
    private static final String AUTH_TOKEN_HEADER = CookieOrHeaderHttpSessionIdResolver.HEADER_NAME;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SessionSupport sessions;

    @Autowired
    private ReplayFixture fixture;

    @Autowired
    private MutableClock clock;

    @BeforeEach
    void resetReplay() {
        fixture.reset(clock);
    }

    @Nested
    @DisplayName("경로 보호와 환경 게이팅")
    class RouteProtection {

        @Test
        @DisplayName("재생 조회_세션이 없으면_401 을 받는다")
        void get_withoutSession_returnsUnauthorized() throws Exception {
            mockMvc.perform(get(REPLAY_URI)).andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("재생 조회_일반 사용자의 세션_403 을 받는다")
        void get_asNonAdmin_returnsForbidden() throws Exception {
            mockMvc.perform(get(REPLAY_URI).header(AUTH_TOKEN_HEADER, sessions.createSessionFor(fixture.user("홍길동"))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("배속 변경_일반 사용자의 세션_403 을 받고 진행도 바뀌지 않는다")
        void change_asNonAdmin_returnsForbidden() throws Exception {
            mockMvc.perform(patch(REPLAY_URI).header(AUTH_TOKEN_HEADER, sessions.createSessionFor(fixture.user("홍길동")))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"speed\":10}"))
                    .andExpect(status().isForbidden());

            assertThat(fixture.row().get("speed")).isEqualTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("배속 변경_쿠키 인증에 위조 방지 토큰이 없으면_거부된다")
        void change_withoutCsrfToken_returnsForbidden() throws Exception {
            Cookie session = sessions.sessionCookie(sessions.createSessionFor(fixture.admin()));

            mockMvc.perform(patch(REPLAY_URI).cookie(session)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"speed\":10}"))
                    .andExpect(status().isForbidden());

            assertThat(fixture.row().get("speed")).isEqualTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("시계 조회_인증만 있으면_일반 사용자도 읽는다")
        void clock_asAuthenticatedUser_isReadable() throws Exception {
            mockMvc.perform(get(CLOCK_URI).header(AUTH_TOKEN_HEADER, sessions.createSessionFor(fixture.user("홍길동"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.phase").value("BRIEFING"))
                    .andExpect(jsonPath("$.data.seasonDay").value(1))
                    .andExpect(jsonPath("$.data.serverTime").isNotEmpty())
                    // 최초 공개 전에는 준비 상태를 가짜 틱으로 표시하지 않는다.
                    .andExpect(jsonPath("$.data.tickSeq").doesNotExist())
                    .andExpect(jsonPath("$.data.gameDate").doesNotExist());
        }

        @Test
        @DisplayName("시계 조회_일반 사용자 응답_재생 조작에 필요한 값은 내주지 않는다")
        void clock_doesNotLeakReplayControls() throws Exception {
            // 이 경로는 인증만 통과하면 누구나, 운영에서도 읽는다. 앵커·배속·시작 예약이
            // 섞여 나가면 게임 진행 상태를 조작하는 정보가 그대로 노출된다.
            mockMvc.perform(get(CLOCK_URI).header(AUTH_TOKEN_HEADER, sessions.createSessionFor(fixture.user("홍길동"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.speed").doesNotExist())
                    .andExpect(jsonPath("$.data.anchorAt").doesNotExist())
                    .andExpect(jsonPath("$.data.anchorSeasonDay").doesNotExist())
                    .andExpect(jsonPath("$.data.anchorOffsetSec").doesNotExist())
                    .andExpect(jsonPath("$.data.offsetSec").doesNotExist())
                    .andExpect(jsonPath("$.data.scheduledStartAt").doesNotExist());
        }

        @Test
        @DisplayName("배속 변경_쿠키 인증에 위조 방지 토큰이 있으면_통과한다")
        void change_withCsrfToken_succeeds() throws Exception {
            Cookie session = sessions.sessionCookie(sessions.createSessionFor(fixture.admin()));
            String token = csrfToken(mockMvc.perform(get(REPLAY_URI).cookie(session)).andReturn());

            mockMvc.perform(patch(REPLAY_URI).cookie(session, new Cookie(CSRF_COOKIE_NAME, token))
                            .header(CSRF_HEADER_NAME, token)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"speed\":0}"))
                    .andExpect(status().isOk());
        }

    }

    @Nested
    @DisplayName("최초 상태")
    class InitialState {

        @Test
        @DisplayName("재생 조회_최초 실행_1일차 브리핑에 정지해 있고 공개 틱이 없다")
        void get_onFirstRun_isPausedAtBriefingWithoutPublishedTick() throws Exception {
            mockMvc.perform(get(REPLAY_URI).header(AUTH_TOKEN_HEADER, sessions.createSessionFor(fixture.admin())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.seasonDay").value(1))
                    .andExpect(jsonPath("$.data.offsetSec").value(-300.0))
                    .andExpect(jsonPath("$.data.phase").value("BRIEFING"))
                    .andExpect(jsonPath("$.data.speed").value(0))
                    .andExpect(jsonPath("$.data.replayEpoch").value(0))
                    // 준비 상태를 가짜 날짜나 틱으로 표시하지 않는다.
                    .andExpect(jsonPath("$.data.tickSeq").doesNotExist())
                    .andExpect(jsonPath("$.data.gameDate").doesNotExist())
                    .andExpect(jsonPath("$.data.gameMonth").doesNotExist())
                    // 정지 중에는 예정 시각이 없다.
                    .andExpect(jsonPath("$.data.nextTickAt").doesNotExist())
                    .andExpect(jsonPath("$.data.phaseEndsAt").doesNotExist())
                    .andExpect(jsonPath("$.data.scheduledStartAt").doesNotExist())
                    .andExpect(jsonPath("$.data.serverTime").value(ReplayFixture.START.toString()));
        }

        @Test
        @DisplayName("재생 조회_정지 중 실제 시간이 흘러도_위치와 공개 틱이 그대로다")
        void get_whilePaused_holdsPositionAsRealTimePasses() throws Exception {
            clock.advance(Duration.ofHours(6));
            awaitQuiet(() -> fixture.row().toString());

            mockMvc.perform(get(REPLAY_URI).header(AUTH_TOKEN_HEADER, sessions.createSessionFor(fixture.admin())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.offsetSec").value(-300.0))
                    .andExpect(jsonPath("$.data.tickSeq").doesNotExist());
        }

        @Test
        @DisplayName("재생 조회_반복 호출해도_저장된 진행 위치를 전진시키지 않는다")
        void get_repeated_doesNotAdvanceStoredProgress() throws Exception {
            resume(BigDecimal.ONE);
            // 첫 틱 직전까지만 옮긴다. 폴러가 가라앉은 뒤라야 미공개를 판정할 수 있다.
            clock.set(fixture.realTimeAt(ReplayFixture.FIRST_TICK).minusMillis(1));
            awaitQuiet(() -> fixture.row().toString());
            Map<String, Object> before = fixture.row();

            for (int i = 0; i < 5; i++) {
                mockMvc.perform(get(REPLAY_URI).header(AUTH_TOKEN_HEADER, sessions.createSessionFor(fixture.admin())))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.tickSeq").doesNotExist());
            }

            assertThat(fixture.row()).isEqualTo(before);
        }
    }

    @Nested
    @DisplayName("배속 검증")
    class SpeedValidation {

        @ParameterizedTest(name = "배속 {0} 은 400 이다")
        @ValueSource(strings = {"-1", "-0.01", "100.01", "101", "0.001", "1.234"})
        @DisplayName("배속 변경_범위 밖·음수·초과 소수 정밀도_400 을 받고 조용히 반올림되지 않는다")
        void change_outOfContract_returnsBadRequest(String speed) throws Exception {
            mockMvc.perform(change("{\"speed\":" + speed + "}")).andExpect(status().isBadRequest());

            assertThat(fixture.row().get("speed")).isEqualTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("배속 변경_double 로 받으면 반올림될 입력_거절한다")
        void change_withPrecisionThatWouldRoundInDouble_returnsBadRequest() throws Exception {
            // 이 값은 double 로 읽으면 정확히 1.1 이 되어 소수 둘째 자리 검사를 통과해 버린다.
            // BigDecimal 로 바인딩해야 표기된 자릿수 그대로 거절된다.
            mockMvc.perform(change("{\"speed\":1.100000000000000088817841970012523}"))
                    .andExpect(status().isBadRequest());

            assertThat(fixture.row().get("speed")).isEqualTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("배속 변경_소수 둘째 자리_그대로 저장된다")
        void change_withTwoDecimals_storesExactValue() throws Exception {
            mockMvc.perform(change("{\"speed\":0.25}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.speed").value(0.25));

            assertThat((BigDecimal) fixture.row().get("speed")).isEqualByComparingTo("0.25");
        }

        @Test
        @DisplayName("배속 변경_정지·재개 전후_게임 위치가 연속적이다")
        void change_acrossPauseAndResume_keepsPositionContinuous() throws Exception {
            resume(new BigDecimal("2.50"));
            clock.advance(Duration.ofSeconds(40));

            // 2.5배속 40초 = 게임 속 100초. -300 + 100 = -200.
            mockMvc.perform(change("{\"speed\":0}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.offsetSec").value(-200.0));

            // 정지한 채 한참 지나도 재개 위치는 -200 에서 이어진다.
            clock.advance(Duration.ofHours(2));
            mockMvc.perform(change("{\"speed\":1}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.offsetSec").value(-200.0));
        }

        @Test
        @DisplayName("배속 변경_미지원 seek 좌표_400 을 받는다")
        void change_withSeekCoordinate_returnsBadRequest() throws Exception {
            mockMvc.perform(change("{\"speed\":1,\"anchorGameMonth\":\"2021-05\"}"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("수동 전진_이번 범위에 없는 조작_실패한다")
        void advance_isNotSupported() throws Exception {
            mockMvc.perform(post(REPLAY_URI + "/advance").header(AUTH_TOKEN_HEADER, sessions.createSessionFor(fixture.admin())))
                    .andExpect(status().is4xxClientError());
        }
    }

    @Nested
    @DisplayName("시작 전 대기 (앵커가 미래인 상태)")
    class BeforeStart {

        private static final String START_AT = "2026-09-21T03:00:00Z";

        /**
         * 운영 적재({@code load_replay_data.py})가 만드는 모양이다 — 배속 1 에 앵커 시각만
         * 미래인 행. 관리자 API 로는 만들 수 없으므로 저장된 행을 직접 쓴다.
         */
        private void seedWaiting() {
            fixture.store(1, BigDecimal.valueOf(-300), Instant.parse(START_AT), BigDecimal.ONE, null, null, null);
        }

        @Test
        @DisplayName("시작 전 대기_예정 시각 전_게임 위치가 브리핑에 머물고 틱도 공개되지 않는다")
        void beforeStart_waitsAtBriefing() throws Exception {
            seedWaiting();
            clock.set(Instant.parse(START_AT).minusMillis(1));
            awaitQuiet(() -> fixture.row().toString());

            mockMvc.perform(get(REPLAY_URI).header(AUTH_TOKEN_HEADER, sessions.createSessionFor(fixture.admin())))
                    .andExpect(jsonPath("$.data.scheduledStartAt").value(START_AT))
                    .andExpect(jsonPath("$.data.offsetSec").value(-300.0))
                    .andExpect(jsonPath("$.data.tickSeq").doesNotExist());
        }

        @Test
        @DisplayName("시작 전 대기_예정 시각을 넘겨 확인하면_저장된 앵커 기준으로 경과가 반영된다")
        void beforeStart_whenCheckedLate_appliesElapsedFromStoredAnchor() throws Exception {
            seedWaiting();
            // 예정 시각을 10분 넘겨 확인한다. 새 기동 시각으로 앵커를 다시 잡지 않는다.
            clock.set(Instant.parse(START_AT).plus(Duration.ofMinutes(10)));

            mockMvc.perform(get(REPLAY_URI).header(AUTH_TOKEN_HEADER, sessions.createSessionFor(fixture.admin())))
                    .andExpect(jsonPath("$.data.offsetSec").value(300.0))
                    .andExpect(jsonPath("$.data.scheduledStartAt").doesNotExist());
        }

        @Test
        @DisplayName("시작 전 대기_배속만 바꾸면_시작 시각을 유지한다")
        void beforeStart_whenOnlySpeedChanges_keepsTheInstant() throws Exception {
            seedWaiting();

            mockMvc.perform(change("{\"speed\":10}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.scheduledStartAt").value(START_AT))
                    .andExpect(jsonPath("$.data.speed").value(10));
        }

        @Test
        @DisplayName("시작 전 대기_정지하면_대기가 풀리고 이후 재개는 즉시 시작이다")
        void beforeStart_whenPaused_releasesTheWait() throws Exception {
            seedWaiting();

            mockMvc.perform(change("{\"speed\":0}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.scheduledStartAt").doesNotExist())
                    .andExpect(jsonPath("$.data.offsetSec").value(-300.0));

            mockMvc.perform(change("{\"speed\":1}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.scheduledStartAt").doesNotExist());
            // 즉시 시작이므로 5분 뒤에 거래 시작 위치다.
            clock.advance(Duration.ofMinutes(5));
            mockMvc.perform(get(REPLAY_URI).header(AUTH_TOKEN_HEADER, sessions.createSessionFor(fixture.admin())))
                    .andExpect(jsonPath("$.data.offsetSec").value(0.0));
        }

        @Test
        @DisplayName("시작 시각 예약_없앤 조작_요청 필드가 무시된다")
        void schedulingField_isGone() throws Exception {
            // 알 수 없는 필드는 역직렬화에서 걸러진다. 예약이 조용히 살아 있지 않은 것을 본다.
            mockMvc.perform(change("{\"speed\":1,\"startAt\":\"" + START_AT + "\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.scheduledStartAt").doesNotExist());
        }
    }

    @Nested
    @DisplayName("재시작")
    class Restart {

        @Test
        @DisplayName("저장된 앵커와 진행 위치_기동 시각으로 초기화하지 않고 이어서 계산한다")
        void storedAnchorSurvivesRestart() throws Exception {
            // 종료 전에 저장돼 있던 것과 같은 행을 직접 심는다. 1일차 거래 시작에서 1배속.
            Instant anchorAt = ReplayFixture.START.minus(Duration.ofMinutes(30));
            fixture.store(1, BigDecimal.ZERO, anchorAt, BigDecimal.ONE, 3L, 1, ReplayFixture.TRADING_OPEN);

            // 기동 시각이 앵커보다 30분 뒤이므로 게임도 1800초 지나 있어야 한다.
            mockMvc.perform(get(REPLAY_URI).header(AUTH_TOKEN_HEADER, sessions.createSessionFor(fixture.admin())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.seasonDay").value(1))
                    .andExpect(jsonPath("$.data.offsetSec").value(1800.0))
                    .andExpect(jsonPath("$.data.tickSeq").value(3))
                    .andExpect(jsonPath("$.data.gameDate").value("2020-01-06"))
                    .andExpect(jsonPath("$.data.gameMonth").value("2020-01"))
                    .andExpect(jsonPath("$.data.phase").value("TRADING"));
        }

        @Test
        @DisplayName("시드가 다시 돌아도_진행 중인 재생 상태를 덮어쓰지 않는다")
        void repeatedSeedDoesNotOverwriteProgress() {
            fixture.store(2, new BigDecimal("5000.000000"), ReplayFixture.START, BigDecimal.ZERO,
                    6L, 2, ReplayFixture.TRADING_OPEN);

            // 시드는 ON CONFLICT DO NOTHING 이라 이미 있는 행을 건드리지 않는다.
            fixture.seedAgain();

            Map<String, Object> row = fixture.row();
            assertThat(((Number) row.get("anchor_season_day")).intValue()).isEqualTo(2);
            assertThat((BigDecimal) row.get("anchor_offset_sec")).isEqualByComparingTo("5000");
            assertThat(((Number) row.get("last_published_tick_seq")).longValue()).isEqualTo(6L);
        }
    }

    /** 재개는 관리자 REST 를 거친다 — 테스트가 서비스 내부를 부르지 않는다. */
    private void resume(BigDecimal speed) throws Exception {
        mockMvc.perform(change("{\"speed\":" + speed.toPlainString() + "}")).andExpect(status().isOk());
    }

    private MockHttpServletRequestBuilder change(String body) throws Exception {
        return patch(REPLAY_URI).header(AUTH_TOKEN_HEADER, sessions.createSessionFor(fixture.admin()))
                .contentType(MediaType.APPLICATION_JSON).content(body);
    }

}

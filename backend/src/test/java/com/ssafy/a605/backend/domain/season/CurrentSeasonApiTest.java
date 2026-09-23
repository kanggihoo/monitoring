package com.ssafy.a605.backend.domain.season;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;
import com.ssafy.a605.backend.domain.user.entity.User;
import com.ssafy.a605.backend.global.security.CookieOrHeaderHttpSessionIdResolver;
import com.ssafy.a605.backend.support.ContainersConfig;
import com.ssafy.a605.backend.support.MutableClock;
import com.ssafy.a605.backend.support.ReplayFixture;
import com.ssafy.a605.backend.support.SessionSupport;

/**
 * HTTP 경계에서 시즌 안내의 관찰 가능한 결과만 본다.
 *
 * <p><b>미인증 열람의 선례가 이 슬라이스다.</b> 공개 범위가 넓어진 뒤에도(ADR-0005) 열지 않은
 * 경로가 여전히 인증을 요구하는지 함께 본다 — 공개 경로를 열면서 접두사째 열어 버리는 사고가
 * 이 검증으로 드러난다.
 *
 * <p>재생 상태를 보지 않는 유일한 이번 슬라이스다. 그래도 픽스처를 쓰는 이유는 시계
 * ({@code nextOpenAt} 의 기준)를 테스트가 쥐어야 하기 때문이다.
 */
@Tag("container")
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(locations = "classpath:oauth2-test.properties",
        properties = {
                "app.replay.mode=test",
                "app.replay.fixture.enabled=true",
                "app.replay.season-id=1",
                "app.replay.poll-delay-ms=50"
        })
@Import({ContainersConfig.class, SessionSupport.class, ReplayFixture.class, MutableClock.Config.class})
class CurrentSeasonApiTest {

    private static final String SEASON_URI = "/api/v1/seasons/current";
    private static final String AUTH_TOKEN_HEADER = CookieOrHeaderHttpSessionIdResolver.HEADER_NAME;

    /** KST 시:분 문자열. 날짜를 담지 않는다 — 매일 반복되는 규칙이다. */
    private static final String HOUR_MINUTE = "\\d{2}:\\d{2}";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SessionSupport sessions;

    @Autowired
    private ReplayFixture fixture;

    @Autowired
    private MutableClock clock;

    @Autowired
    private JdbcTemplate jdbc;

    private User user;
    private String session;

    @BeforeEach
    void setUp() {
        fixture.reset(clock);
        user = fixture.user("시즌열람자");
        session = sessions.createSessionFor(user);
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM accounts WHERE user_id = ?", user.getId());
    }

    @Nested
    @DisplayName("경로 보호")
    class RouteProtection {

        // 다른 모든 조회와 다른 지점이다. 로그인 전 화면이 이 응답으로 규칙과 카운트다운을 그린다.
        @Test
        @DisplayName("시즌 안내 조회_세션이 없어도_200 을 받는다")
        void getCurrentSeason_withoutSession_returnsOk() throws Exception {
            mockMvc.perform(get(SEASON_URI))
                    .andExpect(status().isOk());
        }

        // 접두사를 와일드카드로 열면 브리핑까지 함께 열린다. 그것은 당일 재생 시작 시점을 담는다.
        @Test
        @DisplayName("개장 브리핑 조회_세션이 없으면_401 을 받는다")
        void getBriefing_withoutSession_returnsUnauthorized() throws Exception {
            mockMvc.perform(get(SEASON_URI + "/briefing"))
                    .andExpect(status().isUnauthorized());
        }

        /**
         * <b>이 목록에서 셋이 빠졌다</b> — 현재가·종목 목록·게임 시계는 미인증 열람으로 열렸다
         * (ADR-0005). 남은 것은 열지 않기로 한 것들이고, 그 판정 근거는 각각 다르다:
         * 판정 기준·정보 카드는 <b>공개 화면이 부르지 않아서</b>, 관심종목·내 순위는
         * <b>개인 데이터라서</b>다.
         */
        @Test
        @DisplayName("열지 않은 조회 경로_세션이 없으면_여전히 401 이다")
        void unopenedRoutes_withoutSession_stayProtected() throws Exception {
            for (String uri : List.of("/api/v1/market/signal-criteria",
                    "/api/v1/users/me/watchlist", "/api/v1/rankings/me")) {
                mockMvc.perform(get(uri))
                        .andExpect(status().isUnauthorized());
            }
        }
    }

    @Nested
    @DisplayName("시즌 편성값")
    class SeasonInfo {

        @Test
        @DisplayName("시즌 안내 조회_이름과 기간과 하루 시간표와 다음 개장 시각이_함께 온다")
        void getCurrentSeason_carriesNameDatesScheduleAndNextOpen() throws Exception {
            mockMvc.perform(get(SEASON_URI))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.name").value("마켓 리플레이 시즌 1"))
                    .andExpect(jsonPath("$.data.startDate").value("2026-09-21"))
                    .andExpect(jsonPath("$.data.endDate").value("2026-09-23"))
                    .andExpect(jsonPath("$.data.dailySchedule.length()").value(4))
                    .andExpect(jsonPath("$.data.nextOpenAt").exists());
        }

        @Test
        @DisplayName("시즌 안내 조회_하루 시간표가_다섯 phase 중 네 구간을 순서대로 담는다")
        void getCurrentSeason_dailySchedule_coversFourPhases() throws Exception {
            String body = body();

            assertThat(JsonPath.<List<String>>read(body, "$.data.dailySchedule[*].phase"))
                    .containsExactly("BRIEFING", "TRADING", "SETTLING", "REVIEW_ONLY");
        }

        // 매일 반복되는 규칙이라 날짜가 없다 (sea.md). 절대 시각으로 내리면 3일치를 다 보내야 한다.
        @Test
        @DisplayName("시즌 안내 조회_하루 시간표의 시각이_날짜 없는 KST 시:분이다")
        void getCurrentSeason_dailySchedule_isHourMinuteOnly() throws Exception {
            mockMvc.perform(get(SEASON_URI))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.dailySchedule[0].phase").value("BRIEFING"))
                    .andExpect(jsonPath("$.data.dailySchedule[0].startsAt").value("11:00"))
                    .andExpect(jsonPath("$.data.dailySchedule[0].endsAt").value("11:05"))
                    .andExpect(jsonPath("$.data.dailySchedule[1].endsAt").value("16:40"))
                    .andExpect(jsonPath("$.data.dailySchedule[2].endsAt").value("17:00"))
                    // 열람 전용은 다음 날 브리핑까지 이어진다.
                    .andExpect(jsonPath("$.data.dailySchedule[3].endsAt").value("11:00"));
        }

        @Test
        @DisplayName("시즌 안내 조회_규칙 요약이_시드와 두 요율과 두 구간과 두 집계를 담는다")
        void getCurrentSeason_rules_carrySeedFeesWindowsAndCounts() throws Exception {
            mockMvc.perform(get(SEASON_URI))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.rules.seedKrw").value("100000000"))
                    .andExpect(jsonPath("$.data.rules.tradeFeeRate").value(0.15))
                    .andExpect(jsonPath("$.data.rules.exchangeFeeRate").value(0.05))
                    .andExpect(jsonPath("$.data.rules.feeFreeWindow.startsAt").value("12:00"))
                    .andExpect(jsonPath("$.data.rules.feeFreeWindow.endsAt").value("12:10"))
                    .andExpect(jsonPath("$.data.rules.blackoutWindow.startsAt").value("16:00"))
                    .andExpect(jsonPath("$.data.rules.blackoutWindow.endsAt").value("16:40"))
                    .andExpect(jsonPath("$.data.rules.instrumentCount").exists())
                    .andExpect(jsonPath("$.data.rules.ipoCount").exists());
        }

        /**
         * 두 집계의 <b>출처</b>를 본다. 절대 개수를 단언하지 않는 이유는 테스트 DB 에 V11 의
         * 실제 28종과 픽스처 종목이 함께 있어서다 — 개수를 박으면 시드가 늘 때 계약과 무관한
         * 이유로 깨진다. 확인할 사실은 {@code ipoCount} 가 <b>공모 편성 테이블이 아니라 종목
         * 메타에서</b> 나온다는 것이고, 그것은 편성이 0행인 상태에서 값이 양수인 것으로 드러난다.
         */
        @Test
        @DisplayName("시즌 안내 조회_두 집계가_공모 편성이 비어 있어도 종목 메타에서 나온다")
        void getCurrentSeason_counts_comeFromInstrumentMeta() throws Exception {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM ipo_offerings", Long.class))
                    .as("공모 편성은 적재 스크립트가 넣으므로 테스트 DB 에서는 비어 있다")
                    .isZero();
            String body = body();

            assertThat(JsonPath.<Integer>read(body, "$.data.rules.instrumentCount")).isEqualTo(
                    jdbc.queryForObject("SELECT count(*) FROM instruments", Integer.class));
            assertThat(JsonPath.<Integer>read(body, "$.data.rules.ipoCount"))
                    .isEqualTo(jdbc.queryForObject(
                            "SELECT count(*) FROM instruments WHERE asset_type = 'IPO_STOCK'",
                            Integer.class))
                    .isPositive();
        }

        @Test
        @DisplayName("시즌 안내 조회_두 시간 구간도_날짜 없는 KST 시:분이다")
        void getCurrentSeason_windows_areHourMinuteOnly() throws Exception {
            mockMvc.perform(get(SEASON_URI))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.rules.feeFreeWindow.startsAt")
                            .value(matchesPattern(HOUR_MINUTE)))
                    .andExpect(jsonPath("$.data.rules.blackoutWindow.endsAt")
                            .value(matchesPattern(HOUR_MINUTE)));
        }

        // 열람 전용 시간이 18시간이라 남은 초로 보내면 백그라운드 탭 스로틀링에 깨진다.
        @Test
        @DisplayName("시즌 안내 조회_다음 개장 시각이_남은 초가 아니라 절대 시각이다")
        void getCurrentSeason_nextOpenAt_isAbsoluteInstant() throws Exception {
            String nextOpenAt = JsonPath.read(body(), "$.data.nextOpenAt");

            // 파싱이 성공하는 것 자체가 절대 시각이라는 뜻이다 — 남은 초라면 숫자였다.
            assertThat(Instant.parse(nextOpenAt)).isAfter(ReplayFixture.START);
            // 1일차 개장은 시계와 같은 시각이라 이미 지났고, 다음 것은 2일차다.
            assertThat(Instant.parse(nextOpenAt))
                    .isEqualTo(Instant.parse("2026-09-22T02:05:00Z"));
        }

        // 기획서 3.1.2 가 일자별 재생 구간의 공개를 명시적으로 금지한다.
        @Test
        @DisplayName("시즌 안내 조회_일자별 재생 구간이_응답 어디에도 없다")
        void getCurrentSeason_hidesReplayRange() throws Exception {
            String body = body();

            assertThat(body)
                    .doesNotContain("fromGameMonth").doesNotContain("from_game_month")
                    .doesNotContain("toGameMonth").doesNotContain("to_game_month")
                    // V18 이 시드한 1일차 구간의 두 값.
                    .doesNotContain("2020-01").doesNotContain("2022-03");
        }
    }

    @Nested
    @DisplayName("참가 상태")
    class Participation {

        @Test
        @DisplayName("시즌 안내 조회_미인증이면_참가 상태가 null 이다")
        void getCurrentSeason_unauthenticated_nullsParticipation() throws Exception {
            mockMvc.perform(get(SEASON_URI))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.participation").value(nullValue()));
        }

        // 404·409 를 내면 로그인만 해도 시즌 화면이 깨진다. 로그인이 계좌를 만들게 된 뒤에도
        // 이 상태는 실재한다 — 배포 직후 이미 로그인돼 있던 세션이 재로그인 전까지 그렇다.
        @Test
        @DisplayName("시즌 안내 조회_인증했지만 계좌가 없으면_참가 상태가 null 이고 실패하지 않는다")
        void getCurrentSeason_authenticatedWithoutAccount_nullsParticipationWithoutFailing()
                throws Exception {
            mockMvc.perform(get(SEASON_URI).header(AUTH_TOKEN_HEADER, session))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.participation").value(nullValue()));
        }

        @Test
        @DisplayName("시즌 안내 조회_계좌가 있으면_참가 상태 다섯 필드가 온다")
        void getCurrentSeason_withAccount_carriesParticipation() throws Exception {
            givenAccount();

            mockMvc.perform(get(SEASON_URI).header(AUTH_TOKEN_HEADER, session))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.participation.status").value("PARTICIPATING"))
                    .andExpect(jsonPath("$.data.participation.joinedAt").exists())
                    .andExpect(jsonPath("$.data.participation.joinedSeasonDay").value(1))
                    .andExpect(jsonPath("$.data.participation.startingBalanceKrw").value("100000000"))
                    .andExpect(jsonPath("$.data.participation.indexAdjusted").value(false));
        }

        @Test
        @DisplayName("시즌 안내 조회_남의 계좌는_내 참가 상태에 섞이지 않는다")
        void getCurrentSeason_otherUsersAccount_isNotVisible() throws Exception {
            User other = fixture.user("남의계좌");
            givenAccount(other.getId());

            mockMvc.perform(get(SEASON_URI).header(AUTH_TOKEN_HEADER, session))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.participation").value(nullValue()));

            jdbc.update("DELETE FROM accounts WHERE user_id = ?", other.getId());
        }
    }

    private String body() throws Exception {
        return mockMvc.perform(get(SEASON_URI))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private void givenAccount() {
        givenAccount(user.getId());
    }

    /**
     * 계좌 생성은 로그인 처리의 일부이고 이 슬라이스는 세션을 직접 만들어 그 경로를 지나지
     * 않으므로, 참가 상태를 검증하려면 테스트가 직접 심는다. 생성 규칙 자체는
     * {@code AccountEnrollmentServiceTest} 가 본다.
     */
    private void givenAccount(long userId) {
        jdbc.update("""
                INSERT INTO accounts (user_id, season_id, krw_cash, usd_cash, locked_krw, locked_usd,
                    starting_balance_krw, index_adjusted, joined_season_day,
                    daily_base_valuation_krw, daily_base_season_day, last_acked_tick_seq, created_at)
                VALUES (?, ?, 100000000, 0, 0, 0, 100000000, false, 1, 100000000, 1, 0, now())
                """, userId, ReplayFixture.SEASON_ID);
    }
}

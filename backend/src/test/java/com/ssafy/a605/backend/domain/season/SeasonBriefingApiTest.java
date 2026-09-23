package com.ssafy.a605.backend.domain.season;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;

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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;
import com.ssafy.a605.backend.domain.user.entity.User;
import com.ssafy.a605.backend.global.security.CookieOrHeaderHttpSessionIdResolver;
import com.ssafy.a605.backend.support.ContainersConfig;
import com.ssafy.a605.backend.support.MutableClock;
import com.ssafy.a605.backend.support.ReplayFixture;
import com.ssafy.a605.backend.support.SessionSupport;

/**
 * HTTP 경계에서 개장 브리핑의 관찰 가능한 결과만 본다.
 *
 * <p><b>담지 않는 것이 검증의 절반이다.</b> 기획서 3.5.2 가 재생 종료 지점·구간 내용·청약
 * 종목명을 명시적으로 금지하므로, 필드가 있는지가 아니라 <b>없는지</b>를 본다.
 *
 * <p>공모 편성({@code ipo_offerings})은 이 테스트가 직접 심는다 — 픽스처 틱 12개 위에 올려야
 * 하고, "청약이 없는 날"도 계약이 규정한 정상 상태라 항상 심어 두면 그것을 검증할 수 없다.
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
class SeasonBriefingApiTest {

    private static final String BRIEFING_URI = "/api/v1/seasons/current/briefing";
    private static final String AUTH_TOKEN_HEADER = CookieOrHeaderHttpSessionIdResolver.HEADER_NAME;

    /** V18 이 시드한 1일차 개장 시각. 절대 시각 계산의 기준점({@code TRADING_OPEN})이다. */
    private static final Instant DAY_1_OPENS_AT = Instant.parse("2026-09-21T02:05:00Z");

    /** V18 이 시드한 2일차 개장 시각. */
    private static final Instant DAY_2_OPENS_AT = Instant.parse("2026-09-22T02:05:00Z");

    /** 픽스처 1일차 틱(1~4)과 2일차 틱(5~8). 공모 편성을 이 위에 올린다. */
    private static final long DAY_1_TICK = 1L;
    private static final long DAY_1_SECOND_TICK = 2L;
    private static final long DAY_1_THIRD_TICK = 3L;
    private static final long DAY_2_TICK = 5L;

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

    private String session;
    private long firstInstrument;
    private long secondInstrument;

    @BeforeEach
    void setUp() {
        fixture.reset(clock);
        User user = fixture.user("브리핑열람자");
        session = sessions.createSessionFor(user);
        firstInstrument = instrumentId("REPLAY_TEST_A");
        secondInstrument = instrumentId("REPLAY_TEST_B");
        givenSeasonDay(1, ReplayFixture.TRADING_OPEN);
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM ipo_offerings WHERE instrument_id IN (?, ?)",
                firstInstrument, secondInstrument);
    }

    @Nested
    @DisplayName("경로 보호")
    class RouteProtection {

        @Test
        @DisplayName("개장 브리핑 조회_세션이 없으면_401 을 받는다")
        void getBriefing_withoutSession_returnsUnauthorized() throws Exception {
            mockMvc.perform(get(BRIEFING_URI))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @DisplayName("당일 편성")
    class Today {

        @Test
        @DisplayName("개장 브리핑 조회_일차와 재생 시작 시점이_함께 온다")
        void getBriefing_carriesSeasonDayAndReplayStart() throws Exception {
            mockMvc.perform(briefing())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.seasonDay").value(1))
                    .andExpect(jsonPath("$.data.replayStartGameMonth").value("2020-01"));
        }

        // 구간 전체를 알면 그날의 시장 국면을 아는 것과 같다 (기획서 3.5.2).
        @Test
        @DisplayName("개장 브리핑 조회_재생 종료 시점이_응답에 없다")
        void getBriefing_hidesReplayEnd() throws Exception {
            String body = body();

            assertThat(body)
                    .doesNotContain("replayEndGameMonth")
                    .doesNotContain("toGameMonth")
                    // V18 이 시드한 1일차 종료 구간.
                    .doesNotContain("2022-03");
        }

        @Test
        @DisplayName("개장 브리핑 조회_일차를 전진시키면_그 일차의 편성으로 바뀐다")
        void getBriefing_afterDayAdvance_switchesToThatDay() throws Exception {
            givenSeasonDay(2, ReplayFixture.TRADING_OPEN);

            mockMvc.perform(briefing())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.seasonDay").value(2))
                    .andExpect(jsonPath("$.data.replayStartGameMonth").value("2022-04"))
                    .andExpect(jsonPath("$.data.schedule.tradingStartsAt")
                            .value(DAY_2_OPENS_AT.toString()));
        }
    }

    @Nested
    @DisplayName("당일 일정")
    class Schedule {

        @Test
        @DisplayName("개장 브리핑 조회_일정 여섯 시각이_개장 시각에서 파생된 절대 시각이다")
        void getBriefing_schedule_isAbsoluteInstants() throws Exception {
            mockMvc.perform(briefing())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.schedule.tradingStartsAt")
                            .value(DAY_1_OPENS_AT.toString()))
                    .andExpect(jsonPath("$.data.schedule.feeFreeStartsAt")
                            .value(DAY_1_OPENS_AT.plusSeconds(3300).toString()))
                    .andExpect(jsonPath("$.data.schedule.feeFreeEndsAt")
                            .value(DAY_1_OPENS_AT.plusSeconds(3900).toString()))
                    .andExpect(jsonPath("$.data.schedule.blackoutStartsAt")
                            .value(DAY_1_OPENS_AT.plusSeconds(17700).toString()))
                    .andExpect(jsonPath("$.data.schedule.closesAt")
                            .value(DAY_1_OPENS_AT.plusSeconds(20100).toString()))
                    .andExpect(jsonPath("$.data.schedule.settlementAt")
                            .value(DAY_1_OPENS_AT.plusSeconds(21300).toString()));
        }

        // 기획서 3.5.3 - 브리핑 시간이 지나도 다시 열람할 수 있어야 한다.
        @Test
        @DisplayName("개장 브리핑 조회_거래 중과 정산 중에도_200 이다")
        void getBriefing_afterOpen_stillReturnsOk() throws Exception {
            givenSeasonDay(1, ReplayFixture.BLACKOUT_START);
            mockMvc.perform(briefing()).andExpect(status().isOk());

            givenSeasonDay(1, ReplayFixture.CLOSE);
            mockMvc.perform(briefing()).andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("당일 청약 일정")
    class IpoWindows {

        @Test
        @DisplayName("개장 브리핑 조회_당일 청약이 없으면_빈 배열이고 나머지 필드는 정상이다")
        void getBriefing_withoutOffering_emptiesWindowsOnly() throws Exception {
            mockMvc.perform(briefing())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.ipoWindows").isArray())
                    .andExpect(jsonPath("$.data.ipoWindows").isEmpty())
                    .andExpect(jsonPath("$.data.replayStartGameMonth").value("2020-01"))
                    .andExpect(jsonPath("$.data.schedule.closesAt").exists());
        }

        // 종목명은 공시 시점에 공모 영역이 공개한다. 브리핑은 시각만 안다 (기획서 3.5.2).
        @Test
        @DisplayName("개장 브리핑 조회_청약 일정 원소가_두 시각만 담고 종목을 특정하지 않는다")
        void getBriefing_window_carriesTimesOnly() throws Exception {
            givenOffering(firstInstrument, DAY_1_TICK, DAY_1_SECOND_TICK);
            String body = body();

            List<Map<String, Object>> windows = JsonPath.read(body, "$.data.ipoWindows[*]");
            assertThat(windows).hasSize(1);
            assertThat(windows.getFirst().keySet())
                    .containsExactlyInAnyOrder("disclosureAt", "subscriptionClosesAt");
            assertThat(body).doesNotContain("검증 종목 A");
        }

        @Test
        @DisplayName("개장 브리핑 조회_청약 시각이_그 틱의 오프셋을 더한 절대 시각이다")
        void getBriefing_windowTimes_addTickOffsetToOpen() throws Exception {
            givenOffering(firstInstrument, DAY_1_TICK, DAY_1_SECOND_TICK);

            mockMvc.perform(briefing())
                    .andExpect(status().isOk())
                    // 픽스처 1일차 첫 세 틱의 오프셋은 32 · 65 · 99 다.
                    .andExpect(jsonPath("$.data.ipoWindows[0].disclosureAt")
                            .value(DAY_1_OPENS_AT.plusSeconds(ReplayFixture.FIRST_TICK).toString()))
                    .andExpect(jsonPath("$.data.ipoWindows[0].subscriptionClosesAt")
                            .value(DAY_1_OPENS_AT.plusSeconds(ReplayFixture.SECOND_TICK).toString()));
        }

        @Test
        @DisplayName("개장 브리핑 조회_당일 청약이 여러 건이면_공시 순서대로 여러 원소가 온다")
        void getBriefing_multipleOfferings_returnsAllInDisclosureOrder() throws Exception {
            givenOffering(secondInstrument, DAY_1_SECOND_TICK, DAY_1_THIRD_TICK);
            givenOffering(firstInstrument, DAY_1_TICK, DAY_1_SECOND_TICK);

            assertThat(disclosureTimes(body())).containsExactly(
                    DAY_1_OPENS_AT.plusSeconds(ReplayFixture.FIRST_TICK).toString(),
                    DAY_1_OPENS_AT.plusSeconds(ReplayFixture.SECOND_TICK).toString());
        }

        @Test
        @DisplayName("개장 브리핑 조회_다른 일차의 청약은_당일 일정에 섞이지 않는다")
        void getBriefing_otherDaysOffering_isNotMixedIn() throws Exception {
            givenOffering(firstInstrument, DAY_2_TICK, DAY_2_TICK);

            mockMvc.perform(briefing())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.ipoWindows").isEmpty());

            givenSeasonDay(2, ReplayFixture.TRADING_OPEN);
            mockMvc.perform(briefing())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.ipoWindows.length()").value(1));
        }
    }

    private MockHttpServletRequestBuilder briefing() {
        return get(BRIEFING_URI).header(AUTH_TOKEN_HEADER, session);
    }

    private String body() throws Exception {
        return mockMvc.perform(briefing())
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static List<String> disclosureTimes(String body) {
        try {
            return JsonPath.read(body, "$.data.ipoWindows[*].disclosureAt");
        } catch (PathNotFoundException e) {
            return List.of();
        }
    }

    /** 픽스처 틱 위의 공모 편성 1건. 상장 틱은 브리핑이 쓰지 않으므로 마감 틱과 같게 둔다. */
    private void givenOffering(long instrumentId, long disclosureTick, long closeTick) {
        jdbc.update("""
                INSERT INTO ipo_offerings (season_id, instrument_id, currency, offer_price,
                    deposit_amount, disclosure_tick_seq, close_tick_seq, listing_tick_seq,
                    allocation_rule)
                VALUES (?, ?, 'KRW', 10000, 10000000, ?, ?, ?, '검증용 배정 규칙')
                ON CONFLICT (instrument_id) DO UPDATE
                    SET disclosure_tick_seq = excluded.disclosure_tick_seq,
                        close_tick_seq = excluded.close_tick_seq,
                        listing_tick_seq = excluded.listing_tick_seq
                """, ReplayFixture.SEASON_ID, instrumentId, disclosureTick, closeTick, closeTick);
    }

    /**
     * 그 일차의 그 위치에서 정지한 상태로 재생을 고정한다. phase 는 처리를 마친 경계가 정하므로
     * 경계 커서를 함께 옮긴다 — 거래 중·정산 중에도 브리핑이 열리는 것이 검증 대상이다.
     */
    private void givenSeasonDay(int seasonDay, int boundaryOffset) {
        fixture.store(seasonDay, BigDecimal.valueOf(boundaryOffset), ReplayFixture.START,
                BigDecimal.ZERO, null, seasonDay, boundaryOffset);
    }

    private long instrumentId(String symbol) {
        return jdbc.queryForObject("SELECT id FROM instruments WHERE symbol = ?", Long.class, symbol);
    }
}

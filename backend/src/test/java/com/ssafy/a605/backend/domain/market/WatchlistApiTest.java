package com.ssafy.a605.backend.domain.market;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

import com.ssafy.a605.backend.domain.user.entity.User;
import com.ssafy.a605.backend.global.security.CookieOrHeaderHttpSessionIdResolver;
import com.ssafy.a605.backend.support.ContainersConfig;
import com.ssafy.a605.backend.support.MutableClock;
import com.ssafy.a605.backend.support.ReplayFixture;
import com.ssafy.a605.backend.support.SessionSupport;

/**
 * HTTP 경계에서 관심종목의 관찰 가능한 결과만 본다.
 *
 * <p>재생 시계를 보지 않는 유일한 이번 슬라이스다 — 관심종목은 계좌가 아니라 User 에 붙어
 * 시즌·틱과 무관하다. 그래도 픽스처를 쓰는 이유는 종목 식별자가 필요하기 때문이다.
 *
 * <p><b>멱등성이 검증의 핵심이다.</b> 별 버튼을 여러 번 눌러도 의도한 상태가 되어야 하므로,
 * 같은 요청을 두 번 보낸 뒤의 상태를 본다.
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
class WatchlistApiTest {

    private static final String WATCHLIST_URI = "/api/v1/users/me/watchlist";
    private static final String ITEM_URI = WATCHLIST_URI + "/%d";
    private static final String AUTH_TOKEN_HEADER = CookieOrHeaderHttpSessionIdResolver.HEADER_NAME;

    /** 유니버스에 없는 식별자. */
    private static final long UNKNOWN_INSTRUMENT = 999_999L;

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
    private long instrument;
    private long otherInstrument;
    private long upcomingInstrument;

    @BeforeEach
    void setUp() {
        fixture.reset(clock);
        user = fixture.user("관심종목주인");
        session = sessions.createSessionFor(user);
        instrument = instrumentId("REPLAY_TEST_A");
        otherInstrument = instrumentId("REPLAY_TEST_C");
        upcomingInstrument = instrumentId("REPLAY_TEST_D");
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM watchlists WHERE user_id = ?", user.getId());
    }

    @Nested
    @DisplayName("경로 보호")
    class RouteProtection {

        @Test
        @DisplayName("관심종목 조회_세션이 없으면_401 을 받는다")
        void getWatchlist_withoutSession_returnsUnauthorized() throws Exception {
            mockMvc.perform(get(WATCHLIST_URI))
                    .andExpect(status().isUnauthorized());
        }

        // 상태를 바꾸는 두 경로는 위조 방지 토큰 검사가 인증보다 먼저 걸러내지만, 아직 누구인지
        // 모르는 요청은 401 이다 (SecurityErrorHandler). 세션 없는 브라우저 요청에는 토큰도
        // 없으므로 이 경로로 막히는 것 자체는 뒤집히지 않는다.
        @Test
        @DisplayName("관심종목 추가_세션이 없으면_401 을 받는다")
        void addToWatchlist_withoutSession_returnsUnauthorized() throws Exception {
            mockMvc.perform(put(ITEM_URI.formatted(instrument)))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("관심종목 제거_세션이 없으면_401 을 받는다")
        void removeFromWatchlist_withoutSession_returnsUnauthorized() throws Exception {
            mockMvc.perform(delete(ITEM_URI.formatted(instrument)))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @DisplayName("조회")
    class Read {

        @Test
        @DisplayName("관심종목 조회_담은 것이 없으면_빈 배열을 받는다")
        void getWatchlist_empty_returnsEmptyArray() throws Exception {
            mockMvc.perform(watchlist())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.instrumentIds").isArray())
                    .andExpect(jsonPath("$.data.instrumentIds").isEmpty());
        }

        @Test
        @DisplayName("관심종목 조회_다른 사용자가 담은 것은_섞이지 않는다")
        void getWatchlist_otherUsersEntries_areNotVisible() throws Exception {
            User other = fixture.user("남의관심종목");
            jdbc.update("INSERT INTO watchlists (user_id, instrument_id, created_at) VALUES (?, ?, now())",
                    other.getId(), otherInstrument);
            add(instrument);

            mockMvc.perform(watchlist())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.instrumentIds").value(contains((int) instrument)));

            jdbc.update("DELETE FROM watchlists WHERE user_id = ?", other.getId());
        }
    }

    @Nested
    @DisplayName("추가와 제거의 멱등성")
    class Idempotency {

        @Test
        @DisplayName("관심종목 추가_한 번 담으면_조회에 나타난다")
        void addToWatchlist_thenRead_containsIt() throws Exception {
            add(instrument);

            mockMvc.perform(watchlist())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.instrumentIds").value(contains((int) instrument)));
        }

        @Test
        @DisplayName("관심종목 추가_같은 종목을 두 번 담아도_200 이고 목록에 한 번만 있다")
        void addToWatchlist_twice_staysSingle() throws Exception {
            add(instrument);
            add(instrument);

            mockMvc.perform(watchlist())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.instrumentIds.length()").value(1));
        }

        @Test
        @DisplayName("관심종목 제거_담은 것을 빼면_조회에서 사라진다")
        void removeFromWatchlist_thenRead_omitsIt() throws Exception {
            add(instrument);
            remove(instrument);

            mockMvc.perform(watchlist())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.instrumentIds").isEmpty());
        }

        @Test
        @DisplayName("관심종목 제거_담지 않은 것을 빼도_204 다")
        void removeFromWatchlist_absentEntry_returnsNoContent() throws Exception {
            remove(instrument);
        }

        @Test
        @DisplayName("관심종목 제거_응답에_응답 봉투가 없다")
        void removeFromWatchlist_hasNoResponseEnvelope() throws Exception {
            add(instrument);

            String body = mockMvc.perform(delete(ITEM_URI.formatted(instrument)).header(AUTH_TOKEN_HEADER, session))
                    .andExpect(status().isNoContent())
                    .andReturn().getResponse().getContentAsString();

            assertThat(body).isEmpty();
        }
    }

    @Nested
    @DisplayName("대상 종목")
    class Target {

        @Test
        @DisplayName("관심종목 추가_상장 예정 종목도_담을 수 있다")
        void addToWatchlist_upcomingInstrument_isAllowed() throws Exception {
            add(upcomingInstrument);

            mockMvc.perform(watchlist())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.instrumentIds").value(contains((int) upcomingInstrument)));
        }

        @Test
        @DisplayName("관심종목 추가_없는 종목이면_404 를 받는다")
        void addToWatchlist_unknownInstrument_returnsNotFound() throws Exception {
            mockMvc.perform(put(ITEM_URI.formatted(UNKNOWN_INSTRUMENT)).header(AUTH_TOKEN_HEADER, session))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("종목이 없습니다."));
        }

        @Test
        @DisplayName("관심종목 제거_없는 종목이면_404 를 받는다")
        void removeFromWatchlist_unknownInstrument_returnsNotFound() throws Exception {
            mockMvc.perform(delete(ITEM_URI.formatted(UNKNOWN_INSTRUMENT)).header(AUTH_TOKEN_HEADER, session))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("종목이 없습니다."));
        }
    }

    private MockHttpServletRequestBuilder watchlist() {
        return get(WATCHLIST_URI).header(AUTH_TOKEN_HEADER, session);
    }

    private void add(long instrumentId) throws Exception {
        mockMvc.perform(put(ITEM_URI.formatted(instrumentId)).header(AUTH_TOKEN_HEADER, session))
                .andExpect(status().isOk());
    }

    private void remove(long instrumentId) throws Exception {
        mockMvc.perform(delete(ITEM_URI.formatted(instrumentId)).header(AUTH_TOKEN_HEADER, session))
                .andExpect(status().isNoContent());
    }

    private long instrumentId(String symbol) {
        return jdbc.queryForObject("SELECT id FROM instruments WHERE symbol = ?", Long.class, symbol);
    }
}

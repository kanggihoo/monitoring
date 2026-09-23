package com.ssafy.a605.backend.domain.market;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
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
 * HTTP 경계에서 종목 목록·상세의 관찰 가능한 결과만 본다.
 *
 * <p>편성·종목은 {@code R__replay_fixture.sql} 이 심은 검증용 시즌과 {@code V11} 의 실제 28종을
 * 함께 쓴다. <b>전체 개수를 단언하지 않는다</b> — 두 집합이 같은 DB 에 있으므로 개수를 박으면
 * 시드가 늘 때 계약과 무관한 이유로 깨진다. 특정 종목의 포함·제외와 필드 형태만 본다.
 *
 * <p><b>배속 0 으로 고정한 상태에서만 조회한다.</b> 폴러는 실제 타이머로 돌지만 배속이 0 이면
 * 도달한 항목이 없어 아무것도 진행하지 않으므로, 공개 기준 틱을 테스트가 정확히 쥔다.
 *
 * <p>항목을 인덱스가 아니라 {@code instrumentId} 로 찾는다 — 노출 필터가 응답에서 종목을 빼고
 * 넣는 것이 이 검증의 대상이라, 인덱스로 집으면 필터가 틀렸을 때 엉뚱한 종목을 검증한다.
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
class InstrumentUniverseApiTest {

    private static final String INSTRUMENTS_URI = "/api/v1/market/instruments";
    private static final String ITEM_URI = INSTRUMENTS_URI + "/%d";
    private static final String AUTH_TOKEN_HEADER = CookieOrHeaderHttpSessionIdResolver.HEADER_NAME;

    /** 유니버스에 없는 식별자. */
    private static final long UNKNOWN_INSTRUMENT = 999_999L;

    /** 픽스처 1일차 첫 틱. 게임 거래일 2020-01-02 — 검증 종목 D 의 상장일 하루 전이다. */
    private static final long BEFORE_LISTING_TICK = 1L;

    /** 게임 거래일 2020-01-03 = 검증 종목 D 의 상장일. */
    private static final long LISTING_DAY_TICK = 2L;

    /** 게임 거래일 2020-01-06 = 상장 다음 거래일. */
    private static final long AFTER_LISTING_TICK = 3L;

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
    private long krwStock;
    private long usdStock;
    private long ipoInstrument;

    @BeforeEach
    void setUp() {
        fixture.reset(clock);
        user = fixture.user("유니버스구독자");
        session = sessions.createSessionFor(user);
        krwStock = instrumentId("REPLAY_TEST_A");
        usdStock = instrumentId("REPLAY_TEST_C");
        ipoInstrument = instrumentId("REPLAY_TEST_D");
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM watchlists WHERE user_id = ?", user.getId());
        jdbc.update("DELETE FROM ipo_offerings WHERE instrument_id = ?", ipoInstrument);
    }

    @Nested
    @DisplayName("경로 보호")
    class RouteProtection {

        /**
         * <b>이전 판은 같은 요청에 401 을 단언했다.</b> ADR-0005 가 목록을 열었다.
         * 채울 관심 여부가 없으므로 {@code watched} 는 전부 {@code false} 다.
         */
        @Test
        @DisplayName("종목 목록 조회_세션이 없어도_200 이고 관심 여부가 전부 false 다")
        void getInstruments_withoutSession_returnsOkWithNothingWatched() throws Exception {
            givenPublishedTick(AFTER_LISTING_TICK);

            mockMvc.perform(get(INSTRUMENTS_URI))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.instruments[?(@.watched == true)]", hasSize(0)));
        }

        /**
         * 상세는 열지 않았다. 개인 데이터라서가 아니라 <b>공개 화면이 부르지 않아서</b>다 —
         * 목록을 열면서 접두사째 열어 버리는 사고가 이 검증으로 드러난다.
         */
        @Test
        @DisplayName("종목 상세 조회_세션이 없으면_401 을 받는다")
        void getInstrument_withoutSession_returnsUnauthorized() throws Exception {
            mockMvc.perform(get(ITEM_URI.formatted(krwStock)))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @DisplayName("항목의 형태")
    class ItemShape {

        @Test
        @DisplayName("종목 목록 조회_각 항목이_메타 여덟 필드를 담는다")
        void getInstruments_item_carriesMeta() throws Exception {
            givenPublishedTick(AFTER_LISTING_TICK);

            assertThat(itemOf(body(), krwStock))
                    .containsEntry("name", "검증 종목 A")
                    .containsEntry("category", "KRX")
                    .containsEntry("assetType", "STOCK")
                    .containsEntry("currency", "KRW")
                    .containsEntry("priceScale", 0)
                    .containsEntry("listingStatus", "LISTED")
                    .containsEntry("isNew", false)
                    .containsEntry("watched", false);
        }

        @Test
        @DisplayName("종목 목록 조회_응답 최상위에_공개 기준 거래일이 온다")
        void getInstruments_carriesAsOfGameDate() throws Exception {
            givenPublishedTick(AFTER_LISTING_TICK);

            mockMvc.perform(instruments())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.asOfGameDate").value("2020-01-06"));
        }
    }

    @Nested
    @DisplayName("카테고리 탭")
    class Category {

        @Test
        @DisplayName("종목 목록 조회_카테고리를 생략하면_세 시장이 함께 온다")
        void getInstruments_withoutCategory_returnsEveryMarket() throws Exception {
            givenPublishedTick(AFTER_LISTING_TICK);
            List<String> categories = categoriesIn(body());

            assertThat(categories).contains("KRX", "NASDAQ", "CRYPTO");
        }

        @Test
        @DisplayName("종목 목록 조회_카테고리가 NASDAQ 이면_그 탭만 남는다")
        void getInstruments_nasdaq_keepsOnlyThatTab() throws Exception {
            givenPublishedTick(AFTER_LISTING_TICK);
            String body = body("NASDAQ");

            assertThat(categoriesIn(body)).containsOnly("NASDAQ");
            assertThat(instrumentIdsIn(body)).contains(usdStock).doesNotContain(krwStock);
        }

        // 코인 4종은 daily_closes 가 없어 현재가 응답에는 나타나지 않는다. 목록은 메타 계약이라
        // 시세와 분리되어 있으므로 여기에는 나타나야 한다 - 빼면 CRYPTO 탭이 빈 배열이 된다.
        @Test
        @DisplayName("종목 목록 조회_카테고리가 CRYPTO 이면_시세가 없어도 빈 배열이 아니다")
        void getInstruments_crypto_isNotEmpty() throws Exception {
            givenPublishedTick(AFTER_LISTING_TICK);

            assertThat(instrumentIdsIn(body("CRYPTO"))).isNotEmpty();
        }
    }

    @Nested
    @DisplayName("공모 편성이 없을 때의 노출")
    class WithoutOffering {

        @Test
        @DisplayName("종목 목록 조회_상장일이 오지 않은 공모주는_목록에서 빠진다")
        void getInstruments_upcomingWithoutOffering_isOmitted() throws Exception {
            givenPublishedTick(BEFORE_LISTING_TICK);

            assertThat(instrumentIdsIn(body())).doesNotContain(ipoInstrument);
        }

        @Test
        @DisplayName("종목 상세 조회_상장일이 오지 않은 공모주는_404 다")
        void getInstrument_upcomingWithoutOffering_returnsNotFound() throws Exception {
            givenPublishedTick(BEFORE_LISTING_TICK);

            mockMvc.perform(get(ITEM_URI.formatted(ipoInstrument)).header(AUTH_TOKEN_HEADER, session))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("종목 목록 조회_상장일이 지난 공모주는_LISTED 이고 상장일이 null 이다")
        void getInstruments_listedIpo_hidesListedGameDate() throws Exception {
            givenPublishedTick(AFTER_LISTING_TICK);

            assertThat(itemOf(body(), ipoInstrument))
                    .containsEntry("assetType", "IPO_STOCK")
                    .containsEntry("listingStatus", "LISTED")
                    .containsEntry("listedGameDate", null);
        }
    }

    @Nested
    @DisplayName("공모 편성이 있을 때의 노출")
    class WithOffering {

        @Test
        @DisplayName("종목 목록 조회_공시 틱 이전이면_목록에서 빠진다")
        void getInstruments_beforeDisclosure_isOmitted() throws Exception {
            givenOffering(LISTING_DAY_TICK, LISTING_DAY_TICK, LISTING_DAY_TICK);
            givenPublishedTick(BEFORE_LISTING_TICK);

            assertThat(instrumentIdsIn(body())).doesNotContain(ipoInstrument);
        }

        @Test
        @DisplayName("종목 목록 조회_공시와 상장 사이면_UPCOMING 이고 상장일이 채워진다")
        void getInstruments_afterDisclosure_isUpcomingWithListedGameDate() throws Exception {
            givenOffering(BEFORE_LISTING_TICK, BEFORE_LISTING_TICK, LISTING_DAY_TICK);
            givenPublishedTick(BEFORE_LISTING_TICK);

            assertThat(itemOf(body(), ipoInstrument))
                    .containsEntry("listingStatus", "UPCOMING")
                    .containsEntry("listedGameDate", "2020-01-03");
        }

        // 상태가 바뀌어도 프론트가 같은 파서를 쓴다. 필드 하나가 사라지면 화면이 깨진다.
        @Test
        @DisplayName("종목 목록 조회_UPCOMING 에서 LISTED 로 전진해도_필드 집합이 유지된다")
        void getInstruments_upcomingToListed_keepsFieldSet() throws Exception {
            givenOffering(BEFORE_LISTING_TICK, BEFORE_LISTING_TICK, LISTING_DAY_TICK);
            givenPublishedTick(BEFORE_LISTING_TICK);
            Map<String, Object> upcoming = itemOf(body(), ipoInstrument);

            givenPublishedTick(AFTER_LISTING_TICK);
            Map<String, Object> listed = itemOf(body(), ipoInstrument);

            assertThat(listed.keySet()).isEqualTo(upcoming.keySet());
        }
    }

    @Nested
    @DisplayName("신규 배지")
    class NewBadge {

        @Test
        @DisplayName("종목 목록 조회_상장 당일이면_신규 배지가 참이다")
        void getInstruments_onListingDay_marksNew() throws Exception {
            givenPublishedTick(LISTING_DAY_TICK);

            assertThat(itemOf(body(), ipoInstrument)).containsEntry("isNew", true);
        }

        @Test
        @DisplayName("종목 목록 조회_다음 거래일로 전진하면_신규 배지가 거짓이 된다")
        void getInstruments_nextTradingDay_clearsNew() throws Exception {
            givenPublishedTick(AFTER_LISTING_TICK);

            assertThat(itemOf(body(), ipoInstrument)).containsEntry("isNew", false);
        }
    }

    @Nested
    @DisplayName("관심 여부")
    class Watched {

        @Test
        @DisplayName("종목 목록 조회_내가 담은 종목만_관심으로 표시된다")
        void getInstruments_watched_followsRequester() throws Exception {
            User other = fixture.user("남의관심종목");
            jdbc.update("INSERT INTO watchlists (user_id, instrument_id, created_at) VALUES (?, ?, now())",
                    other.getId(), usdStock);
            jdbc.update("INSERT INTO watchlists (user_id, instrument_id, created_at) VALUES (?, ?, now())",
                    user.getId(), krwStock);
            givenPublishedTick(AFTER_LISTING_TICK);
            String body = body();

            assertThat(itemOf(body, krwStock)).containsEntry("watched", true);
            assertThat(itemOf(body, usdStock)).containsEntry("watched", false);

            jdbc.update("DELETE FROM watchlists WHERE user_id = ?", other.getId());
        }
    }

    @Nested
    @DisplayName("상세")
    class Detail {

        @Test
        @DisplayName("종목 상세 조회_목록 필드에_사업 개요가 더해진다")
        void getInstrument_addsSummaryToListFields() throws Exception {
            givenPublishedTick(AFTER_LISTING_TICK);
            Map<String, Object> item = itemOf(body(), krwStock);

            String detail = mockMvc.perform(get(ITEM_URI.formatted(krwStock))
                            .header(AUTH_TOKEN_HEADER, session))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.summary").value("실제 시장 데이터가 아닌 재생 검증용 종목"))
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

            Map<String, Object> data = JsonPath.read(detail, "$.data");
            assertThat(data.keySet()).containsAll(item.keySet()).contains("summary");
        }

        @Test
        @DisplayName("종목 상세 조회_없는 종목이면_404 를 받는다")
        void getInstrument_unknownInstrument_returnsNotFound() throws Exception {
            givenPublishedTick(AFTER_LISTING_TICK);

            mockMvc.perform(get(ITEM_URI.formatted(UNKNOWN_INSTRUMENT)).header(AUTH_TOKEN_HEADER, session))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("종목이 없습니다."));
        }
    }

    private MockHttpServletRequestBuilder instruments() {
        return get(INSTRUMENTS_URI).header(AUTH_TOKEN_HEADER, session);
    }

    private String body() throws Exception {
        return mockMvc.perform(instruments())
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private String body(String category) throws Exception {
        return mockMvc.perform(instruments().param("category", category))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    /** 응답에서 그 종목의 줄. 없으면 실패한다 — 인덱스로 집으면 노출 필터 오류를 놓친다. */
    private static Map<String, Object> itemOf(String body, long instrumentId) {
        List<Map<String, Object>> matched =
                JsonPath.read(body, "$.data.instruments[?(@.instrumentId == " + instrumentId + ")]");
        assertThat(matched).as("종목 %d 의 목록 한 줄", instrumentId).hasSize(1);
        return matched.getFirst();
    }

    private static List<Long> instrumentIdsIn(String body) {
        List<Number> ids = JsonPath.read(body, "$.data.instruments[*].instrumentId");
        return ids.stream().map(Number::longValue).toList();
    }

    /** 빈 배열에서 경로가 없는 것과 값이 없는 것을 구별하지 않는다 — 둘 다 빈 목록이다. */
    private static List<String> categoriesIn(String body) {
        try {
            return JsonPath.read(body, "$.data.instruments[*].category");
        } catch (PathNotFoundException e) {
            return List.of();
        }
    }

    /** 픽스처 틱 위의 공모 편성 1건. 공모 편성 유무로 노출 규칙이 갈리는 것이 검증 대상이다. */
    private void givenOffering(long disclosureTick, long closeTick, long listingTick) {
        jdbc.update("""
                INSERT INTO ipo_offerings (season_id, instrument_id, currency, offer_price,
                    deposit_amount, disclosure_tick_seq, close_tick_seq, listing_tick_seq,
                    allocation_rule)
                VALUES (?, ?, 'KRW', 10000, 10000000, ?, ?, ?, '검증용 배정 규칙')
                ON CONFLICT (instrument_id) DO UPDATE
                    SET disclosure_tick_seq = excluded.disclosure_tick_seq,
                        close_tick_seq = excluded.close_tick_seq,
                        listing_tick_seq = excluded.listing_tick_seq
                """, ReplayFixture.SEASON_ID, ipoInstrument, disclosureTick, closeTick, listingTick);
    }

    /** 그 틱까지 공개된 상태로 재생을 고정한다. 배속 0 이라 폴러가 더 진행하지 않는다. */
    private void givenPublishedTick(long tickSeq) {
        fixture.store(1, BigDecimal.valueOf(ReplayFixture.TRADING_OPEN), ReplayFixture.START,
                BigDecimal.ZERO, tickSeq, 1, ReplayFixture.TRADING_OPEN);
    }

    private long instrumentId(String symbol) {
        return jdbc.queryForObject("SELECT id FROM instruments WHERE symbol = ?", Long.class, symbol);
    }
}

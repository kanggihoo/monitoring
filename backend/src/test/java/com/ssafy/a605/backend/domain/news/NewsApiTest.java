package com.ssafy.a605.backend.domain.news;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.hamcrest.Matchers;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jayway.jsonpath.JsonPath;

import com.ssafy.a605.backend.domain.user.entity.User;
import com.ssafy.a605.backend.global.security.CookieOrHeaderHttpSessionIdResolver;
import com.ssafy.a605.backend.support.ContainersConfig;
import com.ssafy.a605.backend.support.MutableClock;
import com.ssafy.a605.backend.support.ReplayFixture;
import com.ssafy.a605.backend.support.SessionSupport;

/**
 * HTTP 경계에서 뉴스 피드의 관찰 가능한 결과만 본다. 기사는 V12 시드(뉴스 440건)이며 여기서
 * 만들지 않는다 — 시드가 곧 콘텐츠라 테스트가 별도 픽스처를 갖는 것이 오히려 거짓이다.
 *
 * <p><b>절단 기준을 요청이 정하지 않으므로 편성을 쥐고 검증한다.</b> 재생 픽스처의 틱까지
 * 공개된 상태로 시계를 고정하고 (배속 0 이라 폴러가 더 진행하지 않는다) 그 기준일로 무엇이
 * 보이는지를 본다. 이전 판은 {@code asOfGameDate} 를 요청에 실었는데 그 파라미터가 사라졌다
 * (S15P21A605-131).
 *
 * <p>좌표는 픽스처 틱과 시드가 겹치는 곳을 골랐다.
 * <ul>
 *   <li>틱 4 (2022-03-31) — 이하 147건. 페이지·필터를 검증할 만큼 많다. 그 날짜 자체에는
 *       기사가 없어 가장 최근 기사가 2022-03-16 이다</li>
 *   <li>틱 2 (2020-01-03) — 이하 2건(1·2번). 경계를 한 건 단위로 셀 수 있다</li>
 *   <li>공개 전 — 기준일이 없다</li>
 * </ul>
 */
@Tag("container")
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(locations = "classpath:oauth2-test.properties",
        properties = {
                "app.replay.mode=test",
                // 픽스처를 여기서 켠다. 선언하지 않으면 .env 의 REPLAY_FIXTURE_ENABLED 가
                // 결과를 정하고, 그 파일은 버전 관리되지 않아 사람마다 다른 DB 를 본다.
                "app.replay.fixture.enabled=true",
                "app.replay.season-id=1",
                "app.replay.poll-delay-ms=50"
        })
@Import({ContainersConfig.class, SessionSupport.class, ReplayFixture.class, MutableClock.Config.class})
class NewsApiTest {

    private static final String NEWS_URI = "/api/v1/news";
    private static final String TICKER_URI = "/api/v1/news/ticker";
    private static final String AUTH_TOKEN_HEADER = CookieOrHeaderHttpSessionIdResolver.HEADER_NAME;

    /** 픽스처 1일차 마지막 틱. 기준일 2022-03-31, 이하 기사 147건. */
    private static final long WIDE_TICK = 4L;
    private static final String WIDE_AS_OF = "2022-03-31";

    /** 그 기준일 이하에서 가장 최근 기사의 날짜. 2022-03-31 당일에는 기사가 없다 (V30 이 그 주의 액면분할 기사를 뺐다). */
    private static final String NEWEST_WITHIN_WIDE = "2022-03-16";

    /** 픽스처 1일차 둘째 틱. 기준일 2020-01-03, 이하 기사는 1·2번 두 건뿐이다. */
    private static final long NARROW_TICK = 2L;
    private static final String NARROW_AS_OF = "2020-01-03";

    /** 그 다음 기사(3번, 2020-01-06). 좁은 틱에서는 보이지 않아야 한다. */
    private static final int NEWS_AFTER_NARROW = 3;

    /** 시드의 6번 종목 (붐 비디오). 2022-03-31 이전에 링크된 기사가 있다. */
    private static final long INSTRUMENT_WITH_NEWS = 6L;

    /**
     * 관련도 검증용 두 종목. 넓은 기준일 이하에서 1번은 기사 48건, 13번은 28건이라 첫 페이지에
     * 반드시 나오고, {@code {1, 13}} 을 함께 링크한 기사도 있어 "보유이면서 관심" 을 실제 시드로 본다.
     */
    private static final long HELD_INSTRUMENT = 13L;
    private static final long WATCHED_INSTRUMENT = 1L;

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
        user = fixture.user("뉴스독자");
        session = sessions.createSessionFor(user);
    }

    @Nested
    @DisplayName("미래 차단")
    class FutureBlocking {

        /**
         * <b>이 결함이 티켓의 이유다.</b> 이전 판은 절단 기준을 요청에서 읽어, 공개 경로에
         * 미래 날짜를 적는 것만으로 시즌 끝까지의 기사가 나왔다 (기획서 4.5 위반). 지금은
         * 그 이름의 파라미터를 보내도 서버가 무시한다.
         */
        @Test
        @DisplayName("뉴스 피드_미래 날짜를 파라미터로 넣어도_시계 기준일까지만 나온다")
        void getFeed_withForgedFutureDate_staysAtClock() throws Exception {
            givenPublishedTick(NARROW_TICK);

            MvcResult result = mockMvc.perform(feed().param("asOfGameDate", "2026-08-31").param("size", "100"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.asOfGameDate").value(NARROW_AS_OF))
                    .andExpect(jsonPath("$.data.items.length()").value(2))
                    .andReturn();

            List<Integer> ids = JsonPath.read(result.getResponse().getContentAsString(), "$.data.items[*].newsId");
            assertThat(ids).doesNotContain(NEWS_AFTER_NARROW);
        }

        @Test
        @DisplayName("뉴스 티커_미래 날짜를 파라미터로 넣어도_시계 기준일까지만 나온다")
        void getTicker_withForgedFutureDate_staysAtClock() throws Exception {
            givenPublishedTick(NARROW_TICK);

            mockMvc.perform(get(TICKER_URI).param("asOfGameDate", "2026-08-31"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.asOfGameDate").value(NARROW_AS_OF))
                    .andExpect(jsonPath("$.data.items.length()").value(2));
        }

        @Test
        @DisplayName("뉴스 피드_최초 공개 전_기준일이 없고 비어 있다")
        void getFeed_beforeFirstPublish_returnsEmpty() throws Exception {
            mockMvc.perform(feed())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.asOfGameDate").doesNotExist())
                    .andExpect(jsonPath("$.data.items").isEmpty())
                    .andExpect(jsonPath("$.data.hasNext").value(false))
                    .andExpect(jsonPath("$.data.nextCursor").doesNotExist());
        }

        @Test
        @DisplayName("뉴스 티커_최초 공개 전_기준일이 없고 비어 있다")
        void getTicker_beforeFirstPublish_returnsEmpty() throws Exception {
            mockMvc.perform(get(TICKER_URI))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.asOfGameDate").doesNotExist())
                    .andExpect(jsonPath("$.data.items").isEmpty());
        }
    }

    @Nested
    @DisplayName("경로 보호와 입력")
    class RouteProtection {

        /** <b>이전 판은 같은 요청에 401 을 단언했다.</b> ADR-0005 가 그 결정을 뒤집었다. */
        @Test
        @DisplayName("뉴스 피드_세션이 없어도_200 을 받는다")
        void getFeed_withoutSession_returnsOk() throws Exception {
            givenPublishedTick(WIDE_TICK);

            mockMvc.perform(get(NEWS_URI)).andExpect(status().isOk());
        }

        @Test
        @DisplayName("뉴스 피드_알 수 없는 채널_400 을 받는다")
        void getFeed_withUnknownChannel_returnsBadRequest() throws Exception {
            givenPublishedTick(WIDE_TICK);

            mockMvc.perform(feed().param("channel", "ALL"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("뉴스 피드_우리가 만든 모양이 아닌 커서_400 을 받는다")
        void getFeed_withMalformedCursor_returnsBadRequest() throws Exception {
            givenPublishedTick(WIDE_TICK);

            mockMvc.perform(feed().param("cursor", "not-a-cursor"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("커서가 올바르지 않습니다."));
        }

        /**
         * 최초 공개 전에도 커서 검증이 먼저다. 빈 페이지로 돌려주면 프론트가 깨진 커서를
         * 들고 다니다 공개 후에야 400 을 만난다.
         */
        @Test
        @DisplayName("뉴스 피드_최초 공개 전에 깨진 커서_빈 페이지가 아니라 400 이다")
        void getFeed_withMalformedCursorBeforeFirstPublish_returnsBadRequest() throws Exception {
            mockMvc.perform(feed().param("cursor", "not-a-cursor"))
                    .andExpect(status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("피드")
    class Feed {

        @Test
        @DisplayName("뉴스 피드_기준일 이하의 기사만_최신순으로 담긴다")
        void getFeed_returnsOnlyPastNewsNewestFirst() throws Exception {
            givenPublishedTick(WIDE_TICK);

            MvcResult result = mockMvc.perform(feed().param("size", "20"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.asOfGameDate").value(WIDE_AS_OF))
                    .andExpect(jsonPath("$.data.items.length()").value(20))
                    .andExpect(jsonPath("$.data.hasNext").value(true))
                    .andExpect(jsonPath("$.data.nextCursor").isString())
                    .andExpect(jsonPath("$.data.items[0].publishedGameDate").value(NEWEST_WITHIN_WIDE))
                    .andExpect(jsonPath("$.data.items[0].relevance").value("MARKET"))
                    .andExpect(jsonPath("$.data.items[0].instrumentIds").isArray())
                    .andExpect(jsonPath("$.data.items[0].tags").isArray())
                    .andExpect(jsonPath("$.data.items[0].publishedTime").isString())
                    .andReturn();

            List<String> dates = JsonPath.read(result.getResponse().getContentAsString(),
                    "$.data.items[*].publishedGameDate");
            LocalDate asOf = LocalDate.parse(WIDE_AS_OF);
            for (int i = 0; i < dates.size(); i++) {
                assertThat(LocalDate.parse(dates.get(i))).isBeforeOrEqualTo(asOf);
                if (i > 0) {
                    assertThat(LocalDate.parse(dates.get(i))).isBeforeOrEqualTo(LocalDate.parse(dates.get(i - 1)));
                }
            }
        }

        @Test
        @DisplayName("뉴스 피드_채널 필터_그 채널의 기사만 담긴다")
        void getFeed_withChannel_returnsOnlyThatChannel() throws Exception {
            givenPublishedTick(WIDE_TICK);

            mockMvc.perform(feed().param("channel", "CRYPTO"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.items").isNotEmpty())
                    .andExpect(jsonPath("$.data.items[*].channel", Matchers.everyItem(Matchers.is("CRYPTO"))));
        }

        @Test
        @DisplayName("뉴스 피드_종목 필터_그 종목이 링크된 기사만 담긴다")
        void getFeed_withInstrumentId_returnsOnlyLinkedNews() throws Exception {
            givenPublishedTick(WIDE_TICK);

            mockMvc.perform(feed().param("instrumentId", String.valueOf(INSTRUMENT_WITH_NEWS)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.items").isNotEmpty())
                    .andExpect(jsonPath("$.data.items[*].instrumentIds",
                            Matchers.everyItem(Matchers.hasItem((int) INSTRUMENT_WITH_NEWS))));
        }

        @Test
        @DisplayName("뉴스 피드_커서로 다음 페이지_이전 페이지와 겹치지 않고 이어진다")
        void getFeed_withCursor_continuesWithoutOverlap() throws Exception {
            givenPublishedTick(WIDE_TICK);

            String first = mockMvc.perform(feed().param("size", "5"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            List<Integer> firstIds = JsonPath.read(first, "$.data.items[*].newsId");
            String cursor = JsonPath.read(first, "$.data.nextCursor");

            String second = mockMvc.perform(feed().param("size", "5").param("cursor", cursor))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            List<Integer> secondIds = JsonPath.read(second, "$.data.items[*].newsId");

            assertThat(secondIds).hasSize(5).doesNotContainAnyElementsOf(firstIds);
            // 최신순이라 다음 페이지의 첫 기사는 이전 페이지의 마지막 기사보다 앞선 기사다.
            String tenInOnePage = mockMvc.perform(feed().param("size", "10"))
                    .andReturn().getResponse().getContentAsString();
            List<Integer> expected = JsonPath.read(tenInOnePage, "$.data.items[*].newsId");
            assertThat(secondIds).isEqualTo(expected.subList(5, 10));
        }
    }

    @Nested
    @DisplayName("티커")
    class Ticker {

        @Test
        @DisplayName("뉴스 티커_헤드라인 몇 건만 담기고_본문은 없다")
        void getTicker_returnsHeadlinesOnly() throws Exception {
            givenPublishedTick(WIDE_TICK);

            mockMvc.perform(get(TICKER_URI).header(AUTH_TOKEN_HEADER, session))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.asOfGameDate").value(WIDE_AS_OF))
                    .andExpect(jsonPath("$.data.items.length()").value(10))
                    .andExpect(jsonPath("$.data.items[0].newsId").isNumber())
                    .andExpect(jsonPath("$.data.items[0].headline").isString())
                    .andExpect(jsonPath("$.data.items[0].relevance").value("MARKET"))
                    .andExpect(jsonPath("$.data.items[0].publishedGameDate").value(NEWEST_WITHIN_WIDE))
                    .andExpect(jsonPath("$.data.items[0].body").doesNotExist());
        }

        @Test
        @DisplayName("뉴스 티커_세션이 없어도_200 을 받는다")
        void getTicker_withoutSession_returnsOk() throws Exception {
            givenPublishedTick(WIDE_TICK);

            mockMvc.perform(get(TICKER_URI)).andExpect(status().isOk());
        }
    }


    /**
     * 관련도는 서버가 정한다 (기획서 10.4.2, nws.md §우선 노출). 실제 시드 기사로 검증한다 —
     * 어느 기사가 어떤 라벨인지를 박아 두지 않고, <b>응답의 링크 종목과 라벨이 규칙대로 맞는지</b>를
     * 한 건씩 대조한다. 시드가 바뀌어도 규칙이 맞으면 통과다.
     */
    @Nested
    @DisplayName("관련도")
    class Relevance {

        @Test
        @DisplayName("보유·관심 종목이 있으면_링크 종목에 따라 HOLDING·WATCHLIST·MARKET 이 갈린다")
        void getFeed_withHoldingsAndWatchlist_labelsEveryItemByRule() throws Exception {
            givenPublishedTick(WIDE_TICK);
            givenHolding(HELD_INSTRUMENT);
            givenWatching(WATCHED_INSTRUMENT);

            String body = mockMvc.perform(feed().param("size", "100"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            List<List<Integer>> links = JsonPath.read(body, "$.data.items[*].instrumentIds");
            List<String> labels = JsonPath.read(body, "$.data.items[*].relevance");
            for (int i = 0; i < links.size(); i++) {
                assertThat(labels.get(i)).as("item %d linked to %s", i, links.get(i))
                        .isEqualTo(expectedRelevance(links.get(i)));
            }
            // 세 라벨이 다 나와야 규칙을 다 본 것이다.
            assertThat(labels).contains("HOLDING", "WATCHLIST", "MARKET");
        }

        @Test
        @DisplayName("보유이면서 관심인 기사_HOLDING 이 이긴다")
        void getFeed_heldAndWatchedSameInstrument_isHolding() throws Exception {
            givenPublishedTick(WIDE_TICK);
            givenHolding(HELD_INSTRUMENT);
            givenWatching(HELD_INSTRUMENT);

            mockMvc.perform(feed().param("instrumentId", String.valueOf(HELD_INSTRUMENT)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.items").isNotEmpty())
                    .andExpect(jsonPath("$.data.items[*].relevance", Matchers.everyItem(Matchers.is("HOLDING"))));
        }

        @Test
        @DisplayName("세션이 없으면_전부 MARKET")
        void getFeed_withoutSession_isAllMarket() throws Exception {
            givenPublishedTick(WIDE_TICK);

            mockMvc.perform(get(NEWS_URI).param("size", "100"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.items[*].relevance", Matchers.everyItem(Matchers.is("MARKET"))));
        }

        /** 참가 전에도 관심 종목은 있을 수 있다. 계좌가 없다고 404 를 내지 않는다 (공개 경로). */
        @Test
        @DisplayName("계좌 없이 관심 종목만_WATCHLIST 는 붙고 HOLDING 은 없다")
        void getFeed_watchlistWithoutAccount_labelsWatchlistOnly() throws Exception {
            givenPublishedTick(WIDE_TICK);
            givenWatching(WATCHED_INSTRUMENT);

            String body = mockMvc.perform(feed().param("size", "100"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            List<String> labels = JsonPath.read(body, "$.data.items[*].relevance");

            assertThat(labels).contains("WATCHLIST").doesNotContain("HOLDING");
        }

        @Test
        @DisplayName("티커_피드와 같은 규칙으로 관련도가 붙는다")
        void getTicker_labelsByTheSameRule() throws Exception {
            givenPublishedTick(WIDE_TICK);
            givenHolding(HELD_INSTRUMENT);
            givenWatching(WATCHED_INSTRUMENT);

            String body = mockMvc.perform(get(TICKER_URI).header(AUTH_TOKEN_HEADER, session))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            List<Integer> ids = JsonPath.read(body, "$.data.items[*].newsId");
            List<String> labels = JsonPath.read(body, "$.data.items[*].relevance");
            for (int i = 0; i < ids.size(); i++) {
                assertThat(labels.get(i)).as("news %d", ids.get(i))
                        .isEqualTo(expectedRelevance(linkedInstruments(ids.get(i))));
            }
        }
    }

    /**
     * {@code relevance=PERSONAL} — 보유·관심 기사만. 이 파라미터만 로그인이 필요하다 (nws.md).
     */
    @Nested
    @DisplayName("개인 필터")
    class PersonalFilter {

        @Test
        @DisplayName("보유·관심 종목이 링크된 기사만 나오고_페이지가 이어진다")
        void getFeed_personal_returnsOnlyHeldOrWatched() throws Exception {
            givenPublishedTick(WIDE_TICK);
            givenHolding(HELD_INSTRUMENT);
            givenWatching(WATCHED_INSTRUMENT);

            String first = mockMvc.perform(feed().param("relevance", "PERSONAL").param("size", "10"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.items.length()").value(10))
                    .andExpect(jsonPath("$.data.hasNext").value(true))
                    .andExpect(jsonPath("$.data.items[*].relevance", Matchers.everyItem(Matchers.not("MARKET"))))
                    .andReturn().getResponse().getContentAsString();
            List<Integer> firstIds = JsonPath.read(first, "$.data.items[*].newsId");
            String cursor = JsonPath.read(first, "$.data.nextCursor");

            String second = mockMvc.perform(feed().param("relevance", "PERSONAL").param("size", "10")
                            .param("cursor", cursor))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            List<Integer> secondIds = JsonPath.read(second, "$.data.items[*].newsId");
            List<List<Integer>> secondLinks = JsonPath.read(second, "$.data.items[*].instrumentIds");

            assertThat(secondIds).isNotEmpty().doesNotContainAnyElementsOf(firstIds);
            for (List<Integer> linked : secondLinks) {
                assertThat(linked).as("personal page must only hold linked news")
                        .containsAnyOf((int) HELD_INSTRUMENT, (int) WATCHED_INSTRUMENT);
            }
        }

        @Test
        @DisplayName("보유도 관심도 없으면_빈 페이지")
        void getFeed_personalWithNothing_isEmpty() throws Exception {
            givenPublishedTick(WIDE_TICK);

            mockMvc.perform(feed().param("relevance", "PERSONAL"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.asOfGameDate").value(WIDE_AS_OF))
                    .andExpect(jsonPath("$.data.items").isEmpty())
                    .andExpect(jsonPath("$.data.hasNext").value(false));
        }

        @Test
        @DisplayName("세션 없이 개인 필터_401")
        void getFeed_personalWithoutSession_isUnauthorized() throws Exception {
            givenPublishedTick(WIDE_TICK);

            mockMvc.perform(get(NEWS_URI).param("relevance", "PERSONAL"))
                    .andExpect(status().isUnauthorized());
        }

        /** 권한 판정은 기준일보다 먼저다 — 최초 공개 전이라고 미인증에 빈 페이지를 주지 않는다. */
        @Test
        @DisplayName("최초 공개 전에 세션 없이 개인 필터_빈 페이지가 아니라 401")
        void getFeed_personalWithoutSessionBeforeFirstPublish_isUnauthorized() throws Exception {
            mockMvc.perform(get(NEWS_URI).param("relevance", "PERSONAL"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("알 수 없는 관련도 필터_400")
        void getFeed_withUnknownRelevance_isBadRequest() throws Exception {
            givenPublishedTick(WIDE_TICK);

            mockMvc.perform(feed().param("relevance", "HOLDING"))
                    .andExpect(status().isBadRequest());
        }
    }


    /**
     * 우선 노출 — 보유 &gt; 관심 &gt; 일반 순이고 같은 구간 안은 최신순 (기획서 10.4.2). 커서가
     * 우선순위를 들고 있어 구간 경계를 넘어가도 페이지가 겹치거나 빠지지 않는다.
     */
    @Nested
    @DisplayName("우선 노출 정렬")
    class PriorityOrdering {

        private static final Map<String, Integer> PRIORITY = Map.of("HOLDING", 0, "WATCHLIST", 1, "MARKET", 2);

        @Test
        @DisplayName("보유 → 관심 → 일반 순이고_같은 구간 안은 최신순")
        void getFeed_ordersByPriorityThenNewest() throws Exception {
            givenPublishedTick(WIDE_TICK);
            givenHolding(HELD_INSTRUMENT);
            givenWatching(WATCHED_INSTRUMENT);

            String body = mockMvc.perform(feed().param("size", "100"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            List<String> labels = JsonPath.read(body, "$.data.items[*].relevance");
            List<String> dates = JsonPath.read(body, "$.data.items[*].publishedGameDate");
            List<Integer> ids = JsonPath.read(body, "$.data.items[*].newsId");

            assertThat(labels).contains("HOLDING", "WATCHLIST", "MARKET");
            for (int i = 1; i < labels.size(); i++) {
                int before = PRIORITY.get(labels.get(i - 1));
                int now = PRIORITY.get(labels.get(i));
                assertThat(now).as("item %d priority must not go back up", i).isGreaterThanOrEqualTo(before);
                if (now == before) {
                    LocalDate earlier = LocalDate.parse(dates.get(i));
                    LocalDate later = LocalDate.parse(dates.get(i - 1));
                    assertThat(earlier).as("item %d date within the same band", i).isBeforeOrEqualTo(later);
                    if (earlier.equals(later)) {
                        assertThat(ids.get(i)).as("item %d id within the same date", i).isLessThan(ids.get(i - 1));
                    }
                }
            }
        }

        /**
         * 13번 종목 기사가 28건이라 7건씩 다섯 장이면 보유 구간의 끝(4번째 장)과 관심 구간을 지난다.
         * 작은 장을 이어 붙인 것과 한 장에 다 받은 것이 같아야 커서가 구간 경계를 제대로 넘은 것이다.
         */
        @Test
        @DisplayName("구간 경계를 넘는 페이지들_이어 붙이면 한 장에 받은 것과 같다")
        void getFeed_pagesAcrossBands_concatenateToOnePage() throws Exception {
            givenPublishedTick(WIDE_TICK);
            givenHolding(HELD_INSTRUMENT);
            givenWatching(WATCHED_INSTRUMENT);

            String whole = mockMvc.perform(feed().param("size", "35"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            List<Integer> expected = JsonPath.read(whole, "$.data.items[*].newsId");
            assertThat(expected).hasSize(35);

            List<Integer> stitched = new java.util.ArrayList<>();
            String cursor = null;
            for (int page = 0; page < 5; page++) {
                MockHttpServletRequestBuilder request = feed().param("size", "7");
                if (cursor != null) {
                    request.param("cursor", cursor);
                }
                String body = mockMvc.perform(request).andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString();
                stitched.addAll(JsonPath.read(body, "$.data.items[*].newsId"));
                cursor = JsonPath.read(body, "$.data.nextCursor");
            }

            assertThat(stitched).isEqualTo(expected);
        }

        @Test
        @DisplayName("종목 필터 안에서도_보유 종목이면 전부 HOLDING 이고 최신순")
        void getFeed_withInstrumentFilter_keepsNewestOrder() throws Exception {
            givenPublishedTick(WIDE_TICK);
            givenHolding(HELD_INSTRUMENT);

            String body = mockMvc.perform(feed().param("instrumentId", String.valueOf(HELD_INSTRUMENT))
                            .param("size", "100"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.items[*].relevance", Matchers.everyItem(Matchers.is("HOLDING"))))
                    .andReturn().getResponse().getContentAsString();
            List<String> dates = JsonPath.read(body, "$.data.items[*].publishedGameDate");
            for (int i = 1; i < dates.size(); i++) {
                assertThat(LocalDate.parse(dates.get(i))).isBeforeOrEqualTo(LocalDate.parse(dates.get(i - 1)));
            }
        }

        /** 배포 직후 열려 있던 탭이 옛 커서를 보내면 첫 페이지부터 다시 읽게 400 이다 — 조용히 첫 페이지가 아니다. */
        @Test
        @DisplayName("우선순위가 없는 옛 커서_400")
        void getFeed_withTwoPartCursor_isBadRequest() throws Exception {
            givenPublishedTick(WIDE_TICK);
            String legacy = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString("2022-03-16:100".getBytes(StandardCharsets.UTF_8));

            mockMvc.perform(feed().param("cursor", legacy))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("커서가 올바르지 않습니다."));
        }
    }

    /** 그 틱까지 공개된 상태로 재생을 고정한다. 배속 0 이라 폴러가 더 진행하지 않는다. */
    private void givenPublishedTick(long tickSeq) {
        fixture.store(1, BigDecimal.valueOf(ReplayFixture.TRADING_OPEN), ReplayFixture.START,
                BigDecimal.ZERO, tickSeq, 1, ReplayFixture.TRADING_OPEN);
    }

    private MockHttpServletRequestBuilder feed() {
        return get(NEWS_URI).header(AUTH_TOKEN_HEADER, session);
    }

    /** 규칙 그대로: 보유가 하나라도 링크되면 HOLDING, 아니고 관심이 링크되면 WATCHLIST, 아니면 MARKET. */
    private static String expectedRelevance(List<Integer> linked) {
        if (linked.contains((int) HELD_INSTRUMENT)) {
            return "HOLDING";
        }
        if (linked.contains((int) WATCHED_INSTRUMENT)) {
            return "WATCHLIST";
        }
        return "MARKET";
    }

    private List<Integer> linkedInstruments(int newsId) {
        return jdbc.queryForList("SELECT instrument_id FROM news_instruments WHERE news_id = ?", Integer.class, newsId);
    }

    /** 이 사용자의 시즌 계좌에 그 종목을 1주 보유시킨다. 계좌가 없으면 만든다. */
    private void givenHolding(long instrumentId) {
        Long accountId = jdbc.query("SELECT id FROM accounts WHERE user_id = ? AND season_id = ?",
                        (rs, n) -> rs.getLong(1), user.getId(), ReplayFixture.SEASON_ID).stream()
                .findFirst()
                .orElseGet(() -> jdbc.queryForObject("""
                        INSERT INTO accounts (user_id, season_id, krw_cash, usd_cash, locked_krw, locked_usd,
                                              starting_balance_krw, index_adjusted, joined_season_day,
                                              daily_base_valuation_krw, daily_base_season_day,
                                              last_acked_tick_seq, created_at)
                        VALUES (?, ?, 100000000, 0, 0, 0, 100000000, false, 1, 100000000, 1, 0, now())
                        RETURNING id
                        """, Long.class, user.getId(), ReplayFixture.SEASON_ID));
        jdbc.update("INSERT INTO positions (account_id, instrument_id, quantity, total_cost) VALUES (?, ?, 1, 10000)",
                accountId, instrumentId);
    }

    private void givenWatching(long instrumentId) {
        jdbc.update("INSERT INTO watchlists (user_id, instrument_id, created_at) VALUES (?, ?, now())",
                user.getId(), instrumentId);
    }
}

package com.ssafy.a605.backend.domain.market;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;

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
 * HTTP 경계에서 종가 시계열의 관찰 가능한 결과만 본다.
 *
 * <p>픽스처 1일차의 게임 거래일은 2020-01-02 · 01-03 · 01-06 세 개이고, 검증 종목 B 는
 * 2020-01-03 종가가 없다 — 휴장일이 점으로 나타나지 않는 것을 그 종목으로 확인한다.
 *
 * <p>픽스처는 재생 구간 시작(2020-01-02)보다 이른 2019-12-30 종가를 갖고 있다. 시작 날짜
 * 기본값이 재생 구간 시작이라는 계약이 그 행으로 검증된다.
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
class InstrumentClosesApiTest {

    private static final String CLOSES_URI = "/api/v1/market/instruments/%d/closes";
    private static final String AUTH_TOKEN_HEADER = CookieOrHeaderHttpSessionIdResolver.HEADER_NAME;

    /** 픽스처 1일차 첫째 틱. 게임 거래일 2020-01-02. 그 하루만 공개된 상태다. */
    private static final long FIRST_TICK = 1L;

    /** 픽스처 1일차 셋째 틱. 게임 거래일 2020-01-06. 세 거래일이 모두 공개된 상태다. */
    private static final long THIRD_TICK = 3L;

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

    private String session;
    private long krwInstrument;
    private long carriedInstrument;
    private long usdInstrument;
    private long ipoInstrument;

    @BeforeEach
    void setUp() {
        fixture.reset(clock);
        User user = fixture.user("차트열람자");
        session = sessions.createSessionFor(user);
        krwInstrument = instrumentId("REPLAY_TEST_A");
        carriedInstrument = instrumentId("REPLAY_TEST_B");
        usdInstrument = instrumentId("REPLAY_TEST_C");
        ipoInstrument = instrumentId("REPLAY_TEST_D");
    }

    @Nested
    @DisplayName("경로 보호와 입력")
    class RouteProtection {

        /**
         * <b>이전 판은 같은 요청에 401 을 단언했다.</b> ADR-0005 가 그 결정을 뒤집었다.
         * 절단이 인증과 무관하다는 것은 같은 응답의 마지막 점이 공개 기준 거래일인 것으로 본다.
         */
        @Test
        @DisplayName("종가 시계열_세션이 없어도_200 이고_공개 기준 거래일까지만 나온다")
        void getCloses_withoutSession_returnsOkAndStaysCut() throws Exception {
            givenPublishedTick(FIRST_TICK);

            mockMvc.perform(get(CLOSES_URI.formatted(krwInstrument)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.asOfGameDate").value("2020-01-02"))
                    // 2020-01-03 · 2020-01-06 종가가 DB 에 이미 있다. 그것이 나왔다면 절단이 깨진 것이다.
                    .andExpect(jsonPath("$.data.points[*].gameDate").value(contains("2020-01-02")));
        }

        @Test
        @DisplayName("종가 시계열_없는 종목이면_404 를 받는다")
        void getCloses_unknownInstrument_returnsNotFound() throws Exception {
            mockMvc.perform(closes(UNKNOWN_INSTRUMENT))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("종목이 없습니다."));
        }

        @Test
        @DisplayName("종가 시계열_시작 날짜 형식이 틀리면_400 을 받는다")
        void getCloses_malformedFrom_returnsBadRequest() throws Exception {
            givenPublishedTick(THIRD_TICK);

            mockMvc.perform(closes(krwInstrument).param("from", "2020/01/03"))
                    .andExpect(status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("최초 공개 전 - 1일차 브리핑")
    class BeforeFirstPublish {

        @Test
        @DisplayName("종가 시계열_공개된 틱이 없으면_절단 기준이 비고 점이 없다")
        void getCloses_beforeFirstPublish_nullsCutoffAndEmptiesPoints() throws Exception {
            mockMvc.perform(closes(krwInstrument))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.asOfGameDate").value(nullValue()))
                    .andExpect(jsonPath("$.data.asOfTickSeq").value(nullValue()))
                    .andExpect(jsonPath("$.data.points").isEmpty())
                    // 통화와 자릿수는 틱에서 나오는 값이 아니라 종목 메타다.
                    .andExpect(jsonPath("$.data.currency").value("KRW"))
                    .andExpect(jsonPath("$.data.priceScale").value(0));
        }
    }

    @Nested
    @DisplayName("시작 날짜")
    class From {

        @Test
        @DisplayName("종가 시계열_시작 날짜를 생략하면_재생 구간 시작부터 온다")
        void getCloses_withoutFrom_startsAtReplayRangeStart() throws Exception {
            givenPublishedTick(THIRD_TICK);

            // 2019-12-30 종가가 DB 에 있지만 재생 구간(2020-01-02~) 밖이므로 점이 되지 않는다.
            mockMvc.perform(closes(krwInstrument))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.asOfGameDate").value("2020-01-06"))
                    .andExpect(jsonPath("$.data.asOfTickSeq").value(THIRD_TICK))
                    .andExpect(jsonPath("$.data.points[*].gameDate")
                            .value(contains("2020-01-02", "2020-01-03", "2020-01-06")))
                    .andExpect(jsonPath("$.data.points[*].close")
                            .value(contains("10100", "9800", "10300")));
        }

        @Test
        @DisplayName("종가 시계열_IPO 종목은 시작 날짜를 생략하면_상장일부터 온다")
        void getCloses_ipoWithoutFrom_startsAtListing() throws Exception {
            givenPublishedTick(THIRD_TICK);

            // 검증 종목 D 는 2020-01-03 상장이고 그 전 날짜에도 종가가 있다. 상장 전 구간에
            // 빈 선이 그려지지 않도록 상장일부터 자른다.
            mockMvc.perform(closes(ipoInstrument))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.points[*].gameDate")
                            .value(contains("2020-01-03", "2020-01-06")));
        }

        @Test
        @DisplayName("종가 시계열_시작 날짜를 주면_그 이후만 온다")
        void getCloses_withFrom_startsThere() throws Exception {
            givenPublishedTick(THIRD_TICK);

            mockMvc.perform(closes(krwInstrument).param("from", "2020-01-03"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.points[*].gameDate")
                            .value(contains("2020-01-03", "2020-01-06")));
        }

        @Test
        @DisplayName("종가 시계열_IPO 종목에 상장일보다 이른 시작 날짜를 주면_상장일로 잘린다")
        void getCloses_ipoWithFromBeforeListing_clampsToListing() throws Exception {
            givenPublishedTick(THIRD_TICK);

            // 검증 종목 D 는 2020-01-02·2019-12-30 에도 종가가 있다. 거래할 수 없던 구간에
            // 선이 그려지지 않아야 하므로 시작 날짜는 구간을 좁히기만 한다.
            mockMvc.perform(closes(ipoInstrument).param("from", "2019-12-01"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.points[*].gameDate")
                            .value(contains("2020-01-03", "2020-01-06")));
        }

        @Test
        @DisplayName("종가 시계열_재생 구간 시작보다 이른 시작 날짜를 주면_구간 시작으로 잘린다")
        void getCloses_fromBeforeReplayRange_clampsToRangeStart() throws Exception {
            givenPublishedTick(THIRD_TICK);

            mockMvc.perform(closes(krwInstrument).param("from", "2019-12-01"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.points[*].gameDate")
                            .value(contains("2020-01-02", "2020-01-03", "2020-01-06")));
        }

        @Test
        @DisplayName("종가 시계열_공개 기준일보다 뒤를 주면_빈 배열이고 오류가 아니다")
        void getCloses_fromAfterCutoff_returnsEmpty() throws Exception {
            givenPublishedTick(THIRD_TICK);

            mockMvc.perform(closes(krwInstrument).param("from", "2020-02-01"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.asOfGameDate").value("2020-01-06"))
                    .andExpect(jsonPath("$.data.points").isEmpty());
        }
    }

    @Nested
    @DisplayName("점의 내용")
    class Points {

        @Test
        @DisplayName("종가 시계열_휴장일_그 날짜가 점으로 나타나지 않는다")
        void getCloses_marketClosedDate_hasNoPoint() throws Exception {
            givenPublishedTick(THIRD_TICK);

            // 검증 종목 B 는 2020-01-03 종가가 없다. 현재가 조회는 이월해서 휴장으로 표시하지만
            // 시계열은 점을 만들지 않는다 — 두 엔드포인트의 이 차이는 의도된 것이다.
            mockMvc.perform(closes(carriedInstrument))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.points[*].gameDate")
                            .value(contains("2020-01-02", "2020-01-06")));
        }

        @Test
        @DisplayName("종가 시계열_달러 종목_통화와 자릿수를 담고 원화 환산을 담지 않는다")
        void getCloses_usdInstrument_hasNoKrwConversion() throws Exception {
            givenPublishedTick(THIRD_TICK);

            mockMvc.perform(closes(usdInstrument))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.currency").value("USD"))
                    .andExpect(jsonPath("$.data.priceScale").value(2))
                    .andExpect(jsonPath("$.data.points[0].close").value("10100.00"))
                    .andExpect(jsonPath("$.data.usdKrwRate").doesNotExist())
                    .andExpect(jsonPath("$.data.points[0].closeKrw").doesNotExist());
        }

        @Test
        @DisplayName("종가 시계열_공개 기준일보다 뒤의 종가_점으로 나타나지 않는다")
        void getCloses_futureClose_isNotIncluded() throws Exception {
            givenPublishedTick(THIRD_TICK);

            // DB 에는 2022-03-31 이후 구간까지 적재되어 있다.
            mockMvc.perform(closes(krwInstrument))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.points.length()").value(3));
        }
    }

    private MockHttpServletRequestBuilder closes(long instrumentId) {
        return get(CLOSES_URI.formatted(instrumentId)).header(AUTH_TOKEN_HEADER, session);
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

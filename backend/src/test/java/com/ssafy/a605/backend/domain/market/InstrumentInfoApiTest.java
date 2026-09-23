package com.ssafy.a605.backend.domain.market;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
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
import com.ssafy.a605.backend.domain.user.entity.User;
import com.ssafy.a605.backend.global.security.CookieOrHeaderHttpSessionIdResolver;
import com.ssafy.a605.backend.support.ContainersConfig;
import com.ssafy.a605.backend.support.MutableClock;
import com.ssafy.a605.backend.support.ReplayFixture;
import com.ssafy.a605.backend.support.SessionSupport;

/**
 * HTTP 경계에서 정보 카드와 신호등 판정 기준의 관찰 가능한 결과만 본다.
 *
 * <p><b>상품·프로젝트 카드는 {@code V20} 이 채운 실제 8종으로 검증한다.</b> 픽스처에 ETF·코인을
 * 따로 심지 않은 이유가 이것이다 — 마이그레이션이 넣은 값이 곧 계약의 대상이라, 픽스처로
 * 대신하면 마이그레이션이 잘못된 JSON 을 넣어도 통과한다.
 *
 * <p><b>재무 지표는 이 테스트가 직접 심는다.</b> {@code instrument_metrics} 는 어느
 * 마이그레이션도 채우지 않아 실제로 0행이며, 그 상태(빈 배열)도 계약이 규정한 정상 상태다.
 * 픽스처에 심어 버리면 "지표가 없는 종목"을 검증할 수 없다.
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
class InstrumentInfoApiTest {

    private static final String INFO_URI = "/api/v1/market/instruments/%d/info";
    private static final String CRITERIA_URI = "/api/v1/market/signal-criteria";
    private static final String AUTH_TOKEN_HEADER = CookieOrHeaderHttpSessionIdResolver.HEADER_NAME;

    private static final long UNKNOWN_INSTRUMENT = 999_999L;

    /** 게임 거래일 2020-01-06. 아래 두 분기의 공시일 뒤이고 미래 분기의 공시일 앞이다. */
    private static final long AS_OF_TICK = 3L;

    /** 공시가 지난 두 분기 중 이른 것. */
    private static final String OLDER_QUARTER = "2019Q1";

    /** 공시가 지난 두 분기 중 최신. 이것이 선택되어야 한다. */
    private static final String LATEST_QUARTER = "2019Q3";

    /** 공시일이 공개 기준 거래일보다 뒤인 분기. 응답에 나타나면 안 된다. */
    private static final String FUTURE_QUARTER = "2019Q4";

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
    private long stockWithMetrics;
    private long stockWithoutMetrics;
    private long stockWithNaMetric;
    private long etf;
    private long inverseEtf;
    private long crypto;

    @BeforeEach
    void setUp() {
        fixture.reset(clock);
        User user = fixture.user("정보카드구독자");
        session = sessions.createSessionFor(user);
        stockWithMetrics = instrumentId("REPLAY_TEST_A");
        stockWithoutMetrics = instrumentId("REPLAY_TEST_B");
        stockWithNaMetric = instrumentId("REPLAY_TEST_C");
        etf = instrumentId("NQX");
        inverseEtf = instrumentId("SNQX");
        crypto = instrumentId("ETRA");
        givenPublishedTick(AS_OF_TICK);
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM instrument_metrics WHERE instrument_id IN (?, ?, ?)",
                stockWithMetrics, stockWithoutMetrics, stockWithNaMetric);
    }

    @Nested
    @DisplayName("경로 보호")
    class RouteProtection {

        @Test
        @DisplayName("정보 카드 조회_세션이 없으면_401 을 받는다")
        void getInfo_withoutSession_returnsUnauthorized() throws Exception {
            mockMvc.perform(get(INFO_URI.formatted(stockWithMetrics)))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("판정 기준 조회_세션이 없으면_401 을 받는다")
        void getSignalCriteria_withoutSession_returnsUnauthorized() throws Exception {
            mockMvc.perform(get(CRITERIA_URI))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @DisplayName("카드 종류 분기")
    class CardTypeDispatch {

        @Test
        @DisplayName("정보 카드 조회_주식이면_STOCK 이고 나머지 두 카드가 null 이다")
        void getInfo_stock_nullsOtherCards() throws Exception {
            mockMvc.perform(info(stockWithMetrics))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.cardType").value("STOCK"))
                    .andExpect(jsonPath("$.data.product").value(nullValue()))
                    .andExpect(jsonPath("$.data.project").value(nullValue()));
        }

        /**
         * <b>카드 문구를 단언하지 않는다.</b> {@code trackingIndex}·{@code riskNotice} 같은
         * 값은 편성 콘텐츠라 마이그레이션 UPDATE 로 조정할 수 있고, 문구를 박으면 그 조정이
         * 계약과 무관한 이유로 테스트를 깨뜨린다. 계약이 값을 열거한 두 필드
         * ({@code direction}·{@code leverage})만 정확히 본다.
         */
        @Test
        @DisplayName("정보 카드 조회_ETF 면_PRODUCT 이고 상품 구조 네 필드를 담는다")
        void getInfo_etf_carriesProductCard() throws Exception {
            mockMvc.perform(info(etf))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.cardType").value("PRODUCT"))
                    .andExpect(jsonPath("$.data.stock").value(nullValue()))
                    .andExpect(jsonPath("$.data.project").value(nullValue()))
                    .andExpect(jsonPath("$.data.product.trackingIndex").value(not(emptyString())))
                    .andExpect(jsonPath("$.data.product.direction").value("LONG"))
                    .andExpect(jsonPath("$.data.product.leverage").value(1))
                    .andExpect(jsonPath("$.data.product.expenseRatio").isNumber());
        }

        // 기획서 9.3.2 는 인버스·레버리지 상품에 위험 안내를 상시 표시하도록 요구한다.
        // 계약이 요구하는 것은 "비어 있지 않다"이고 문구 자체는 편성 콘텐츠다.
        @Test
        @DisplayName("정보 카드 조회_인버스 ETF 면_위험 안내가 비어 있지 않다")
        void getInfo_inverseEtf_carriesRiskNotice() throws Exception {
            mockMvc.perform(info(inverseEtf))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.product.direction").value("INVERSE"))
                    .andExpect(jsonPath("$.data.product.leverage").value(3))
                    .andExpect(jsonPath("$.data.product.riskNotice").value(not(emptyString())));
        }

        @Test
        @DisplayName("정보 카드 조회_코인이면_PROJECT 이고 프로젝트 세 필드를 담는다")
        void getInfo_crypto_carriesProjectCard() throws Exception {
            mockMvc.perform(info(crypto))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.cardType").value("PROJECT"))
                    .andExpect(jsonPath("$.data.stock").value(nullValue()))
                    .andExpect(jsonPath("$.data.product").value(nullValue()))
                    .andExpect(jsonPath("$.data.project.purpose").value(not(emptyString())))
                    .andExpect(jsonPath("$.data.project.supplyModel").value(not(emptyString())))
                    .andExpect(jsonPath("$.data.project.ecosystemRole").value(not(emptyString())));
        }

        /**
         * <b>카드가 실제로 채워졌는지를 본다.</b> {@code info_card} 가 비어 있어도 응답은 200
         * 이고 카드 필드만 {@code null} 이므로(카드 종류는 자산 유형이 정한다), 상태 코드나
         * {@code cardType} 만 보면 마이그레이션이 8건 중 하나를 빠뜨려도 통과한다. 그물이
         * 되려면 {@code product}·{@code project} 가 채워졌는지를 단언해야 한다.
         *
         * <p>필드 개수를 세지 않는다 — {@code riskNotice} 는 1배 정방향 상품에서 정당하게
         * {@code null} 이라 카드마다 개수가 다르다. 대신 반드시 값이 있어야 하는 잎 필드를 본다.
         *
         * <p>픽스처 종목을 제외하는 조건이 지금은 걸러내는 것이 없다 — 픽스처 4종이 전부
         * 주식이라 앞의 자산 유형 조건에서 이미 빠진다. 그래도 두는 이유는 사양이 픽스처에
         * ETF·코인 추가를 요구하고 있어서다: 그때 이 조건이 없으면 개수 단언이 무너진다.
         */
        @Test
        @DisplayName("정보 카드 조회_ETF·인버스·코인 8종이_모두 채워진 카드를 갖는다")
        void getInfo_everyNonStockInstrument_hasFilledCard() throws Exception {
            List<Map<String, Object>> targets = jdbc.queryForList(
                    "SELECT id, asset_type FROM instruments"
                            + " WHERE asset_type IN ('ETF','INVERSE_ETF','CRYPTO')"
                            + " AND symbol NOT LIKE 'REPLAY\\_TEST\\_%' ORDER BY id");
            // V20 이 UPDATE 하는 대상 수 (ETF 2 + INVERSE_ETF 2 + CRYPTO 4).
            assertThat(targets).hasSize(8);

            for (Map<String, Object> target : targets) {
                boolean coin = "CRYPTO".equals(target.get("asset_type"));
                String filled = coin ? "$.data.project" : "$.data.product";
                String empty = coin ? "$.data.product" : "$.data.project";

                mockMvc.perform(info((Long) target.get("id")))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.cardType").value(coin ? "PROJECT" : "PRODUCT"))
                        .andExpect(jsonPath("$.data.stock").value(nullValue()))
                        .andExpect(jsonPath(empty).value(nullValue()))
                        .andExpect(jsonPath(filled).exists())
                        .andExpect(jsonPath(filled + (coin ? ".purpose" : ".trackingIndex"))
                                .value(not(emptyString())))
                        .andExpect(jsonPath("$.data.summary").value(not(emptyString())));
            }
        }

        @Test
        @DisplayName("정보 카드 조회_사업 개요가_종목 메타의 값과 같다")
        void getInfo_summary_matchesInstrumentMeta() throws Exception {
            mockMvc.perform(info(crypto))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.summary").value(
                            jdbc.queryForObject("SELECT summary FROM instruments WHERE id = ?",
                                    String.class, crypto)));
        }

        @Test
        @DisplayName("정보 카드 조회_없는 종목이면_404 를 받는다")
        void getInfo_unknownInstrument_returnsNotFound() throws Exception {
            mockMvc.perform(get(INFO_URI.formatted(UNKNOWN_INSTRUMENT)).header(AUTH_TOKEN_HEADER, session))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("종목이 없습니다."));
        }
    }

    @Nested
    @DisplayName("지표가 아직 없는 주식")
    class WithoutMetrics {

        // info.md 가 이 상태를 계약으로 규정한다 - 카드 종류는 결정되어 있고 내용만 아직 없다.
        @Test
        @DisplayName("정보 카드 조회_공시된 분기가 없으면_지표와 신호가 빈 배열이고 카드는 null 이 아니다")
        void getInfo_noDisclosedQuarter_emptiesArraysButKeepsCard() throws Exception {
            mockMvc.perform(info(stockWithoutMetrics))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.stock").exists())
                    .andExpect(jsonPath("$.data.stock.metrics").isArray())
                    .andExpect(jsonPath("$.data.stock.metrics").isEmpty())
                    .andExpect(jsonPath("$.data.stock.signals").isArray())
                    .andExpect(jsonPath("$.data.stock.signals").isEmpty());
        }

        // 지표가 없는데 "2019Q3 기준"이라고 말하면 거짓이 된다.
        @Test
        @DisplayName("정보 카드 조회_지표가 빈 배열이면_분기와 공시일이 둘 다 null 이다")
        void getInfo_emptyMetrics_nullsQuarterAndDisclosedDate() throws Exception {
            mockMvc.perform(info(stockWithoutMetrics))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.stock.asOfFiscalQuarter").value(nullValue()))
                    .andExpect(jsonPath("$.data.stock.disclosedGameDate").value(nullValue()));
        }
    }

    @Nested
    @DisplayName("공시 지연 절단")
    class DisclosureDelay {

        @Test
        @DisplayName("정보 카드 조회_공시일이 지난 분기 중_가장 최근 것이 선택된다")
        void getInfo_picksLatestDisclosedQuarter() throws Exception {
            givenMetrics();

            mockMvc.perform(info(stockWithMetrics))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.stock.asOfFiscalQuarter").value(LATEST_QUARTER))
                    .andExpect(jsonPath("$.data.stock.disclosedGameDate").value("2019-11-14"));
        }

        // 미래 분기의 행이 DB 에 있는 상태에서 확인한다 - 없으면 필터가 아니라 데이터 부재를 본다.
        @Test
        @DisplayName("정보 카드 조회_공시일이 기준 거래일보다 뒤인 분기는_노출되지 않는다")
        void getInfo_futureQuarter_isNotExposed() throws Exception {
            givenMetrics();
            String body = body(stockWithMetrics);

            assertThat(body).doesNotContain(FUTURE_QUARTER);
            // 미래 분기에만 있는 값이 새지 않는 것까지 본다.
            assertThat(metricValue(body, "REVENUE_GROWTH")).isEqualTo("25");
        }
    }

    @Nested
    @DisplayName("신호등 판정")
    class Signals {

        @Test
        @DisplayName("정보 카드 조회_지표가 있으면_지표 배열과 신호 3개가 함께 온다")
        void getInfo_withMetrics_carriesMetricsAndThreeSignals() throws Exception {
            givenMetrics();
            String body = body(stockWithMetrics);

            assertThat(signalLevels(body))
                    .containsEntry("GROWTH", "GOOD")
                    .containsEntry("SAFETY", "NORMAL")
                    .containsEntry("VALUATION", "CAUTION")
                    .hasSize(3);
        }

        @Test
        @DisplayName("정보 카드 조회_각 신호가_판정에 쓴 지표 키를 담는다")
        void getInfo_eachSignal_carriesBasisKeys() throws Exception {
            givenMetrics();
            String body = body(stockWithMetrics);

            assertThat(basisKeys(body, "GROWTH")).containsExactly("REVENUE_GROWTH");
            assertThat(basisKeys(body, "SAFETY")).containsExactly("DEBT_RATIO");
            assertThat(basisKeys(body, "VALUATION")).containsExactly("PER");
        }

        // "판정할 수 없음"과 "보통"은 다른 사실이다 (info.md).
        @Test
        @DisplayName("정보 카드 조회_근거 지표가 산출 불가면_그 신호의 등급이 null 이고 NORMAL 과 구별된다")
        void getInfo_naBasis_nullsLevelAndDiffersFromNormal() throws Exception {
            givenNaMetrics();
            String body = body(stockWithNaMetric);

            assertThat(metricNa(body, "PER")).isTrue();
            assertThat(metricValue(body, "PER")).isNull();
            assertThat(signalLevels(body))
                    .containsEntry("VALUATION", null)
                    .containsEntry("SAFETY", "NORMAL");
        }
    }

    @Nested
    @DisplayName("판정 기준 공개")
    class Criteria {

        @Test
        @DisplayName("판정 기준 조회_세 질문이_질문 문장과 지표 키와 3단계를 담아 온다")
        void getSignalCriteria_carriesThreeQuestions() throws Exception {
            String body = mockMvc.perform(get(CRITERIA_URI).header(AUTH_TOKEN_HEADER, session))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

            assertThat(criterionOf(body, "GROWTH"))
                    .containsEntry("question", "이 회사는 크고 있나요?")
                    .containsEntry("metricKey", "REVENUE_GROWTH");
            assertThat(criterionOf(body, "SAFETY"))
                    .containsEntry("question", "빚이 무겁지 않나요?")
                    .containsEntry("metricKey", "DEBT_RATIO");
            assertThat(criterionOf(body, "VALUATION"))
                    .containsEntry("question", "지금 비싼 편인가요?")
                    .containsEntry("metricKey", "PER");
            for (String kind : List.of("GROWTH", "SAFETY", "VALUATION")) {
                assertThat(thresholdLevels(body, kind)).containsExactly("GOOD", "NORMAL", "CAUTION");
            }
        }

        // 숫자를 내리고 프론트가 조립하면 "20% 이상"과 "역성장"을 한 템플릿으로 만들 수 없다.
        @Test
        @DisplayName("판정 기준 조회_등급 설명이_숫자가 아니라 문장이다")
        void getSignalCriteria_descriptions_areSentences() throws Exception {
            String body = mockMvc.perform(get(CRITERIA_URI).header(AUTH_TOKEN_HEADER, session))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            List<String> descriptions = JsonPath.read(body, "$.data.criteria[*].thresholds[*].description");

            assertThat(descriptions).isNotEmpty().allSatisfy(description ->
                    assertThat(description).matches(".*[가-힣].*"));
            assertThat(descriptions).contains("매출이 줄었습니다 (역성장)");
        }

        // 종목 무관 전역 조회다 - 재무 지표가 하나도 없어도 답해야 한다.
        @Test
        @DisplayName("판정 기준 조회_재무 지표가 비어 있어도_정상 응답한다")
        void getSignalCriteria_withoutAnyMetric_stillResponds() throws Exception {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM instrument_metrics", Long.class))
                    .isZero();

            mockMvc.perform(get(CRITERIA_URI).header(AUTH_TOKEN_HEADER, session))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.criteria.length()").value(3));
        }
    }

    private MockHttpServletRequestBuilder info(long instrumentId) {
        return get(INFO_URI.formatted(instrumentId)).header(AUTH_TOKEN_HEADER, session);
    }

    private String body(long instrumentId) throws Exception {
        return mockMvc.perform(info(instrumentId))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    /**
     * 세 신호가 서로 다른 등급으로 갈리는 조합 + 공시가 지난 이른 분기 + 공시가 오지 않은 분기.
     * 세 상태를 한 종목에 함께 두어야 "최신 분기 선택"과 "미래 분기 차단"이 동시에 관찰된다.
     */
    private void givenMetrics() {
        insertMetric(stockWithMetrics, OLDER_QUARTER, "REVENUE_GROWTH", "매출 성장률", "2019-05-15", "5", "PERCENT");
        insertMetric(stockWithMetrics, OLDER_QUARTER, "DEBT_RATIO", "부채비율", "2019-05-15", "50", "PERCENT");
        insertMetric(stockWithMetrics, OLDER_QUARTER, "PER", "PER", "2019-05-15", "10", "TIMES");

        insertMetric(stockWithMetrics, LATEST_QUARTER, "MARKET_CAP", "시가총액", "2019-11-14", "612000000000", "KRW");
        insertMetric(stockWithMetrics, LATEST_QUARTER, "REVENUE_GROWTH", "매출 성장률", "2019-11-14", "25", "PERCENT");
        insertMetric(stockWithMetrics, LATEST_QUARTER, "DEBT_RATIO", "부채비율", "2019-11-14", "150", "PERCENT");
        insertMetric(stockWithMetrics, LATEST_QUARTER, "PER", "PER", "2019-11-14", "40", "TIMES");

        // 공개 기준 거래일(2020-01-06)보다 뒤에 공시된다.
        insertMetric(stockWithMetrics, FUTURE_QUARTER, "REVENUE_GROWTH", "매출 성장률", "2020-02-14", "99", "PERCENT");
    }

    /** PER 이 산출 불가인 분기. 상장 초기 종목이 이 모습이다 (기획서 9.1.3). */
    private void givenNaMetrics() {
        insertMetric(stockWithNaMetric, LATEST_QUARTER, "REVENUE_GROWTH", "매출 성장률", "2019-11-14", "25", "PERCENT");
        insertMetric(stockWithNaMetric, LATEST_QUARTER, "DEBT_RATIO", "부채비율", "2019-11-14", "150", "PERCENT");
        insertMetric(stockWithNaMetric, LATEST_QUARTER, "PER", "PER", "2019-11-14", null, "TIMES");
    }

    private void insertMetric(long instrumentId, String quarter, String key, String label,
                              String disclosedGameDate, String value, String unit) {
        jdbc.update("""
                INSERT INTO instrument_metrics (instrument_id, fiscal_quarter, metric_key, label,
                    disclosed_game_date, value, unit)
                VALUES (?, ?, ?, ?, ?::date, ?::numeric, ?)
                """, instrumentId, quarter, key, label, disclosedGameDate, value, unit);
    }

    private static Map<String, Object> metricOf(String body, String key) {
        List<Map<String, Object>> matched =
                JsonPath.read(body, "$.data.stock.metrics[?(@.key == '" + key + "')]");
        assertThat(matched).as("지표 %s 한 줄", key).hasSize(1);
        return matched.getFirst();
    }

    private static String metricValue(String body, String key) {
        return (String) metricOf(body, key).get("value");
    }

    private static boolean metricNa(String body, String key) {
        return (Boolean) metricOf(body, key).get("na");
    }

    /**
     * 신호 종류별 등급. 판정 불가({@code null})와 {@code NORMAL} 을 구별하는 것이 검증 대상이라
     * {@code null} 값을 담을 수 있는 맵으로 모은다 — {@code Collectors.toMap} 은 값이
     * {@code null} 이면 던진다.
     */
    private static Map<String, String> signalLevels(String body) {
        List<Map<String, Object>> signals = JsonPath.read(body, "$.data.stock.signals[*]");
        Map<String, String> levels = new HashMap<>();
        signals.forEach(signal -> levels.put((String) signal.get("kind"), (String) signal.get("level")));
        return levels;
    }

    private static List<String> basisKeys(String body, String kind) {
        return JsonPath.read(body, "$.data.stock.signals[?(@.kind == '" + kind + "')].basisKeys[*]");
    }

    private static Map<String, Object> criterionOf(String body, String kind) {
        List<Map<String, Object>> matched =
                JsonPath.read(body, "$.data.criteria[?(@.kind == '" + kind + "')]");
        assertThat(matched).as("판정 기준 %s", kind).hasSize(1);
        return matched.getFirst();
    }

    private static List<String> thresholdLevels(String body, String kind) {
        return JsonPath.read(body, "$.data.criteria[?(@.kind == '" + kind + "')].thresholds[*].level");
    }

    private void givenPublishedTick(long tickSeq) {
        fixture.store(1, BigDecimal.valueOf(ReplayFixture.TRADING_OPEN), ReplayFixture.START,
                BigDecimal.ZERO, tickSeq, 1, ReplayFixture.TRADING_OPEN);
    }

    private long instrumentId(String symbol) {
        return jdbc.queryForObject("SELECT id FROM instruments WHERE symbol = ?", Long.class, symbol);
    }
}

package com.ssafy.a605.backend.domain.report;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;

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
 * HTTP 경계에서 일일 복기의 관찰 가능한 결과만 본다.
 *
 * <p><b>확정 행을 직접 심는다.</b> 폐장 경계를 실제로 돌려 만드는 것은
 * {@code DailySettlementServiceTest} 가 본다. 여기서 볼 것은 이미 얼려진 값이 계약대로
 * 나가는지, 그리고 발표 전후로 문이 열리고 닫히는지다.
 *
 * <p>발표 여부는 게임 시계가 정하므로 재생 상태를 직접 써서 phase 를 만든다.
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
class DailyReportApiTest {

    private static final String DAILY_URI = "/api/v1/reports/daily";
    private static final String AUTH_TOKEN_HEADER = CookieOrHeaderHttpSessionIdResolver.HEADER_NAME;

    /** 픽스처 1일차 틱. 종가는 tick_seq 로 정해진다 (10,000 +- tick x 100). */
    private static final long TICK_1 = 1L;
    private static final long TICK_3 = 3L;
    private static final LocalDate DATE_1 = LocalDate.of(2020, 1, 2);
    private static final LocalDate DATE_3 = LocalDate.of(2020, 1, 6);

    private static final long ACCOUNT_ID = 940L;

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

    private User owner;
    private String session;
    private long instrumentA;
    private long instrumentC;

    @BeforeEach
    void setUp() {
        fixture.reset(clock);
        owner = fixture.user("복기조회");
        session = sessions.createSessionFor(owner);
        instrumentA = instrumentId("REPLAY_TEST_A");
        instrumentC = instrumentId("REPLAY_TEST_C");
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM daily_report_texts WHERE account_id = ?", ACCOUNT_ID);
        jdbc.update("DELETE FROM daily_holdings WHERE account_id = ?", ACCOUNT_ID);
        jdbc.update("DELETE FROM daily_settlements WHERE account_id = ?", ACCOUNT_ID);
        jdbc.update("DELETE FROM executions WHERE account_id = ?", ACCOUNT_ID);
        // 사건이 알림 행을 남기므로 계좌보다 먼저 지운다 (notifications.account_id FK).
        jdbc.update("DELETE FROM notifications WHERE account_id = ?", ACCOUNT_ID);
        jdbc.update("DELETE FROM accounts WHERE id = ?", ACCOUNT_ID);
    }

    @Nested
    @DisplayName("경로 보호")
    class RouteProtection {

        @Test
        @DisplayName("목록_세션이 없으면_401 을 받는다")
        void getList_withoutSession_returnsUnauthorized() throws Exception {
            mockMvc.perform(get(DAILY_URI))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("상세_세션이 없으면_401 을 받는다")
        void getDetail_withoutSession_returnsUnauthorized() throws Exception {
            mockMvc.perform(get(DAILY_URI + "/1"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("목록_시즌에 참가하지 않았으면_404 를 받는다")
        void getList_withoutAccount_returnsNotFound() throws Exception {
            mockMvc.perform(list())
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("상세_시즌에 없는 일차를 물으면_404 를 받는다")
        void getDetail_unknownSeasonDay_returnsNotFound() throws Exception {
            givenAccount();
            givenAnnounced(1);

            mockMvc.perform(detail(4))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("그 일차는 이 시즌에 없습니다."));
        }
    }

    @Nested
    @DisplayName("발표 전")
    class BeforeAnnouncement {

        @Test
        @DisplayName("목록_아직 발표된 일차가 없으면_세 일차 전부 available 이 false 다")
        void getList_beforeAnyAnnouncement_marksEveryDayUnavailable() throws Exception {
            givenAccount();
            givenSettling(1);

            mockMvc.perform(list())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.items", hasSize(3)))
                    .andExpect(jsonPath("$.data.items[0].available").value(false))
                    // 재생 구간을 숨긴다. 발표 전에는 종료 지점이 미래 정보다.
                    .andExpect(jsonPath("$.data.items[0].fromGameMonth").value(nullValue()))
                    .andExpect(jsonPath("$.data.items[0].toGameMonth").value(nullValue()))
                    .andExpect(jsonPath("$.data.items[0].dailyReturn").value(nullValue()))
                    .andExpect(jsonPath("$.data.items[2].available").value(false));
        }

        @Test
        @DisplayName("상세_정산 처리 중이면_확정 행이 있어도 409 를 받는다")
        void getDetail_beforeAnnouncement_returnsConflict() throws Exception {
            givenAccount();
            givenSettlement(1, "4.21", 0, "0", "0", null, null, "[]");
            givenSettling(1);

            mockMvc.perform(detail(1))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.message").value("정산 발표 후에 열람할 수 있습니다."));
        }
    }

    @Nested
    @DisplayName("목록")
    class ListReports {

        @Test
        @DisplayName("목록_1일차가 발표되면_그 일차만 구간과 수익률이 열린다")
        void getList_afterFirstAnnouncement_opensOnlyThatDay() throws Exception {
            givenAccount();
            givenSettlement(1, "4.21", 3, "210", "-1600", null, null, "[]");
            givenAnnounced(1);

            mockMvc.perform(list())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.items[0].seasonDay").value(1))
                    .andExpect(jsonPath("$.data.items[0].available").value(true))
                    .andExpect(jsonPath("$.data.items[0].fromGameMonth").value("2020-01"))
                    .andExpect(jsonPath("$.data.items[0].toGameMonth").value("2022-03"))
                    .andExpect(jsonPath("$.data.items[0].dailyReturn").value(4.21))
                    // 아직 오지 않은 일차의 구간은 여전히 가린다.
                    .andExpect(jsonPath("$.data.items[1].available").value(false))
                    .andExpect(jsonPath("$.data.items[1].fromGameMonth").value(nullValue()))
                    .andExpect(jsonPath("$.data.items[2].available").value(false));
        }

        @Test
        @DisplayName("목록_발표됐지만 내 확정 행이 없으면_available 은 true 이고 수익률만 비어 있다")
        void getList_announcedWithoutMyRow_keepsAvailableTrue() throws Exception {
            // 중간 합류자다. available 은 발표 여부라는 계약을 지킨다 (rpt.md).
            givenAccount();
            givenAnnounced(1);

            mockMvc.perform(list())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.items[0].available").value(true))
                    .andExpect(jsonPath("$.data.items[0].fromGameMonth").value("2020-01"))
                    .andExpect(jsonPath("$.data.items[0].dailyReturn").value(nullValue()));
        }
    }

    @Nested
    @DisplayName("상세")
    class DetailReport {

        @Test
        @DisplayName("상세_발표됐지만 내 확정 행이 없으면_404 를 받는다")
        void getDetail_announcedWithoutMyRow_returnsNotFound() throws Exception {
            givenAccount();
            givenAnnounced(1);

            mockMvc.perform(detail(1))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("그 일차의 복기가 없습니다."));
        }

        @Test
        @DisplayName("상세_AI 문구가 있으면_머리말과 별칭이 실려 나온다")
        void getDetail_withCommentary_carriesHeadlineAndAliases() throws Exception {
            givenAccount();
            givenSettlement(1, "4.21", 3, "210", "-1600", null, null, "[]");
            givenCommentary(1, "이긴 날에도 아쉬운 손이 하나 있었습니다.",
                    "[\"비싼 첫걸음\", \"때맞춘 작별\"]");
            givenAnnounced(1);

            mockMvc.perform(detail(1))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.commentary.headline")
                            .value("이긴 날에도 아쉬운 손이 하나 있었습니다."))
                    .andExpect(jsonPath("$.data.commentary.sceneAliases", hasSize(2)))
                    .andExpect(jsonPath("$.data.commentary.sceneAliases[0]").value("비싼 첫걸음"))
                    // 다음 일차의 변주를 피하려는 내부 값이라 화면으로 내보내지 않는다.
                    .andExpect(jsonPath("$.data.commentary.blockIds").doesNotExist());
        }

        @Test
        @DisplayName("상세_요약은_daily_settlements 에 얼린 값 그대로 나온다")
        void getDetail_summaryMirrorsStoredRow() throws Exception {
            givenAccount();
            givenSettlement(1, "4.21", 3, "210", "-1600", null, null, "[]");
            givenAnnounced(1);

            mockMvc.perform(detail(1))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.seasonDay").value(1))
                    .andExpect(jsonPath("$.data.fromGameMonth").value("2020-01"))
                    .andExpect(jsonPath("$.data.toGameMonth").value("2022-03"))
                    .andExpect(jsonPath("$.data.finalizedAt").exists())
                    .andExpect(jsonPath("$.data.summary.dailyReturn").value(4.21))
                    .andExpect(jsonPath("$.data.summary.executionCount").value(3))
                    // 금액은 문자열이다 (_common.md 6).
                    .andExpect(jsonPath("$.data.summary.totalFeeKrw").value("210"))
                    .andExpect(jsonPath("$.data.summary.realizedPnlKrw").value("-1600"))
                    // 문구는 폐장 뒤 외부 호출이 성공해야 생긴다. 없으면 자리를 비운 채 연다.
                    .andExpect(jsonPath("$.data.commentary").value(nullValue()))
                    .andExpect(jsonPath("$.data.regret").value(nullValue()));
        }

        @Test
        @DisplayName("상세_매도가 없는 날은_상하위 거래가 둘 다 비어 있다")
        void getDetail_withoutSells_hasNoBestOrWorstTrade() throws Exception {
            givenAccount();
            givenExecution(instrumentA, "BUY", "10100", "KRW", "15", null, TICK_1, DATE_1);
            givenSettlement(1, "1.00", 1, "15", "0", null, null, "[]");
            givenAnnounced(1);

            mockMvc.perform(detail(1))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.bestTrade").value(nullValue()))
                    .andExpect(jsonPath("$.data.worstTrade").value(nullValue()));
        }
    }

    @Nested
    @DisplayName("차트와 마커")
    class Replay {

        @Test
        @DisplayName("상세_거래한 종목만_차트와 마커로 나온다")
        void getDetail_onlyTradedInstruments() throws Exception {
            givenAccount();
            long bought = givenExecution(instrumentA, "BUY", "10100", "KRW", "15", null, TICK_1, DATE_1);
            long sold = givenExecution(instrumentA, "SELL", "10300", "KRW", "15", "2000", TICK_3, DATE_3);
            givenExecution(instrumentC, "BUY", "10300.00", "USD", "0.15", null, TICK_3, DATE_3);
            givenSettlement(1, "1.00", 3, "210", "2000", sold, sold, "[]");
            givenAnnounced(1);

            mockMvc.perform(detail(1))
                    .andExpect(status().isOk())
                    // 검증 종목 B 는 거래하지 않았으므로 나오지 않는다.
                    .andExpect(jsonPath("$.data.instruments", hasSize(2)))
                    .andExpect(jsonPath("$.data.instruments[0].instrumentId").value(instrumentA))
                    .andExpect(jsonPath("$.data.instruments[0].currency").value("KRW"))
                    .andExpect(jsonPath("$.data.instruments[0].priceScale").value(0))
                    .andExpect(jsonPath("$.data.instruments[0].markers", hasSize(2)))
                    .andExpect(jsonPath("$.data.instruments[0].markers[0].executionId").value(bought))
                    .andExpect(jsonPath("$.data.instruments[0].markers[0].gameDate").value("2020-01-02"))
                    .andExpect(jsonPath("$.data.instruments[0].markers[0].side").value("BUY"))
                    .andExpect(jsonPath("$.data.instruments[0].markers[0].quantity").value(1))
                    .andExpect(jsonPath("$.data.instruments[0].markers[0].price").value("10100"))
                    .andExpect(jsonPath("$.data.instruments[0].markers[1].executionId").value(sold))
                    .andExpect(jsonPath("$.data.instruments[0].markers[1].side").value("SELL"))
                    // 달러 종목은 자릿수 2 자리로 나간다.
                    .andExpect(jsonPath("$.data.instruments[1].currency").value("USD"))
                    .andExpect(jsonPath("$.data.instruments[1].priceScale").value(2))
                    .andExpect(jsonPath("$.data.instruments[1].markers[0].price").value("10300.00"));
        }

        @Test
        @DisplayName("상세_차트는_그 일차 재생 구간으로 잘린다")
        void getDetail_pointsAreCutToThatDay() throws Exception {
            givenAccount();
            long sold = givenExecution(instrumentA, "SELL", "10300", "KRW", "15", "2000", TICK_3, DATE_3);
            givenSettlement(1, "1.00", 1, "15", "2000", sold, sold, "[]");
            givenAnnounced(1);

            // 픽스처 1일차 틱은 2020-01-02 · 01-03 · 01-06 · 2022-03-31 네 개다.
            // 2019-12-30 종가 행은 구간 밖이라 점이 되지 않고, 2일차 종가도 섞이지 않는다.
            mockMvc.perform(detail(1))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.instruments[0].points", hasSize(4)))
                    .andExpect(jsonPath("$.data.instruments[0].points[0].gameDate").value("2020-01-02"))
                    .andExpect(jsonPath("$.data.instruments[0].points[0].close").value("10100"))
                    .andExpect(jsonPath("$.data.instruments[0].points[3].gameDate").value("2022-03-31"))
                    .andExpect(jsonPath("$.data.instruments[0].points[3].close").value("9600"));
        }

        @Test
        @DisplayName("상세_상하위 거래는_취득 원가 대비 수익률과 원화 환산 손익을 담는다")
        void getDetail_bestTradeCarriesReturnRate() throws Exception {
            givenAccount();
            long sold = givenExecution(instrumentA, "SELL", "10300", "KRW", "15", "2000", TICK_3, DATE_3);
            givenSettlement(1, "1.00", 1, "15", "2000", sold, sold, "[]");
            givenAnnounced(1);

            // 취득 원가 = 대금 10,300 - 수수료 15 - 실현 손익 2,000 = 8,285.
            // 수익률 = 2,000 / 8,285 = 24.14%
            mockMvc.perform(detail(1))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.bestTrade.executionId").value(sold))
                    .andExpect(jsonPath("$.data.bestTrade.instrumentId").value(instrumentA))
                    .andExpect(jsonPath("$.data.bestTrade.realizedPnlKrw").value("2000"))
                    .andExpect(jsonPath("$.data.bestTrade.returnRate").value(24.14))
                    // 매도가 한 건이면 상위와 하위가 같은 체결이다.
                    .andExpect(jsonPath("$.data.worstTrade.executionId").value(sold));
        }

        @Test
        @DisplayName("상세_저장된 패턴 태그가_그대로 나온다")
        void getDetail_returnsStoredPatternTags() throws Exception {
            givenAccount();
            long bought = givenExecution(instrumentA, "BUY", "11000", "KRW", "16", null, TICK_1, DATE_1);
            givenSettlement(1, "1.00", 1, "16", "0", null, null,
                    "[{\"kind\":\"PANIC_BUY_HIGH\",\"executionId\":%d,\"instrumentId\":%d,\"changeRate\":-12.73}]"
                            .formatted(bought, instrumentA));
            givenAnnounced(1);

            mockMvc.perform(detail(1))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.patternTags", hasSize(1)))
                    .andExpect(jsonPath("$.data.patternTags[0].kind").value("PANIC_BUY_HIGH"))
                    .andExpect(jsonPath("$.data.patternTags[0].executionId").value(bought))
                    .andExpect(jsonPath("$.data.patternTags[0].instrumentId").value(instrumentA))
                    .andExpect(jsonPath("$.data.patternTags[0].changeRate").value(-12.73));
        }

        @Test
        @DisplayName("상세_거래가 없는 날은_종목 목록이 빈 배열이다")
        void getDetail_withoutTrades_hasEmptyInstruments() throws Exception {
            givenAccount();
            givenSettlement(1, "0.00", 0, "0", "0", null, null, "[]");
            givenAnnounced(1);

            mockMvc.perform(detail(1))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.instruments", hasSize(0)))
                    .andExpect(jsonPath("$.data.patternTags", hasSize(0)));
        }
    }

    /**
     * 다음 일차 개장에 {@code positions} 가 초기화되므로 이 기록이 그 일차에 무엇을 들고
     * 끝냈는지의 유일한 근거다 (V42). 거래 목록과는 다른 질문에 답한다.
     */
    @Nested
    @DisplayName("마감 보유")
    class ClosingHoldings {

        @Test
        @DisplayName("상세_그 일차 마감 보유가_평가액 큰 것부터 평단과 함께 나온다")
        void getDetail_returnsClosingHoldingsByValuation() throws Exception {
            givenAccount();
            givenSettlement(1, "0.00", 0, "0", "0", null, null, "[]");
            // 원화 120 주, 취득 1,420,000 -> 평단 11,833 (priceScale 0). 평가 1,512,000.
            givenHolding(1, instrumentA, 120, "1420000", "1512000");
            // 달러 40 주, 취득 $4,736 -> 평단 $118.40 (priceScale 2). 평가 5,904,000 원.
            givenHolding(1, instrumentC, 40, "4736.00", "5904000");
            givenAnnounced(1);

            mockMvc.perform(detail(1))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.closingHoldings", hasSize(2)))
                    // 평가액이 큰 달러 종목이 먼저다.
                    .andExpect(jsonPath("$.data.closingHoldings[0].instrumentId").value((int) instrumentC))
                    .andExpect(jsonPath("$.data.closingHoldings[0].currency").value("USD"))
                    .andExpect(jsonPath("$.data.closingHoldings[0].quantity").value(40))
                    // 평단은 저장하지 않고 취득 총액 ÷ 수량으로 낸다. 통화 자릿수를 따른다.
                    .andExpect(jsonPath("$.data.closingHoldings[0].avgPrice").value("118.40"))
                    .andExpect(jsonPath("$.data.closingHoldings[0].totalCost").value("4736.00"))
                    .andExpect(jsonPath("$.data.closingHoldings[0].valuationKrw").value("5904000"))
                    .andExpect(jsonPath("$.data.closingHoldings[1].instrumentId").value((int) instrumentA))
                    .andExpect(jsonPath("$.data.closingHoldings[1].avgPrice").value("11833"));
        }

        /**
         * 보유가 없던 날과 스냅샷이 없는 일차가 같은 빈 배열이다. 화면은 그 구역을 그리지
         * 않는다 — 빈 표를 두면 둘을 구분하지 못한 채 "아무것도 안 들고 끝냈다" 로 읽힌다.
         */
        @Test
        @DisplayName("상세_그 일차 마감 보유가 없으면_빈 배열이다")
        void getDetail_withoutHoldings_isEmpty() throws Exception {
            givenAccount();
            givenSettlement(1, "0.00", 0, "0", "0", null, null, "[]");
            givenAnnounced(1);

            mockMvc.perform(detail(1))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.closingHoldings", hasSize(0)));
        }

        /** 다른 일차의 보유는 섞이지 않는다. */
        @Test
        @DisplayName("상세_다른 일차의 마감 보유는_섞이지 않는다")
        void getDetail_ignoresOtherDays() throws Exception {
            givenAccount();
            givenSettlement(1, "0.00", 0, "0", "0", null, null, "[]");
            givenSettlement(2, "0.00", 0, "0", "0", null, null, "[]");
            givenHolding(1, instrumentA, 120, "1420000", "1512000");
            givenHolding(2, instrumentC, 40, "4736.00", "5904000");
            givenAnnounced(2);

            mockMvc.perform(detail(1))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.closingHoldings", hasSize(1)))
                    .andExpect(jsonPath("$.data.closingHoldings[0].instrumentId").value((int) instrumentA));
        }
    }

    /**
     * 마감 자산. <b>합계는 일일 수익률과 같은 사실이지만 구성은 아니다</b> — 같은 수익률을
     * 현금을 들고 냈는지 전부 밀어 넣어 냈는지는 여기서만 갈린다.
     */
    @Nested
    @DisplayName("마감 자산")
    class Closing {

        @Test
        @DisplayName("상세_마감 현금과 기준 틱이 있으면_구성과 순위·낙폭이 함께 나온다")
        void getDetail_returnsClosingComposition() throws Exception {
            givenAccount();
            givenSettlement(1, "3.21", 2, "1500", "40000", null, null, "[]");
            givenClosing(1, "77666669", "4079.66", TICK_1, 12, "-18.4000");
            givenHolding(1, instrumentA, 120, "1420000", "1512000");
            givenAnnounced(1);

            mockMvc.perform(detail(1))
                    .andExpect(status().isOk())
                    // 확정 평가액이다 — 구성의 합이 아니라 순위·수익률의 근거다.
                    .andExpect(jsonPath("$.data.closing.totalValuationKrw").value("100000000"))
                    .andExpect(jsonPath("$.data.closing.krwCash").value("77666669"))
                    .andExpect(jsonPath("$.data.closing.usdCash").value("4079.66"))
                    // 보유 평가액은 그 일차 스냅샷의 합이다.
                    .andExpect(jsonPath("$.data.closing.holdingsValuationKrw").value("1512000"))
                    .andExpect(jsonPath("$.data.closing.dailyRank").value(12))
                    .andExpect(jsonPath("$.data.closing.participantCount").value(1))
                    .andExpect(jsonPath("$.data.closing.maxDrawdown").value(-18.4))
                    // 1일차는 다음 날 초기화된다. 화면이 맺음말을 그것으로 가른다.
                    .andExpect(jsonPath("$.data.closing.resetsNextDay").value(true));
        }

        /**
         * 이 컬럼이 생기기 전에 확정된 일차는 마감 현금도 기준 틱도 없다. 합계만 남기면
         * "현금 0 원으로 끝냈다" 로 읽히므로 통째로 비운다.
         */
        @Test
        @DisplayName("상세_마감 기록이 없는 옛 일차면_구성이 통째로 비어 있다")
        void getDetail_withoutClosingSnapshot_omitsComposition() throws Exception {
            givenAccount();
            givenSettlement(1, "0.00", 0, "0", "0", null, null, "[]");
            givenAnnounced(1);

            mockMvc.perform(detail(1))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.closing").doesNotExist());
        }

        /** 마지막 일차는 다음 날이 없다 — 화면이 "시즌을 끝냈다" 로 맺는다. */
        @Test
        @DisplayName("상세_마지막 일차면_다음 날 초기화 표시가 꺼진다")
        void getDetail_onFinalDay_doesNotResetNextDay() throws Exception {
            givenAccount();
            givenSettlement(3, "0.00", 0, "0", "0", null, null, "[]");
            givenClosing(3, "100000000", "0", TICK_1, 1, null);
            givenAnnounced(3);

            mockMvc.perform(detail(3))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.closing.resetsNextDay").value(false))
                    // 낙폭 스냅샷이 없는 일차는 그 값만 빈다 — 0 을 채우면 "내려온 적 없다" 다.
                    .andExpect(jsonPath("$.data.closing.maxDrawdown").doesNotExist());
        }
    }

    /** 폐장에 찍힌 마감 현금·기준 틱·순위·낙폭 (V42). */
    private void givenClosing(int seasonDay, String krwCash, String usdCash, long tickSeq,
                              int dailyRank, String maxDrawdown) {
        jdbc.update("""
                UPDATE daily_settlements
                SET closing_krw_cash = CAST(? AS NUMERIC), closing_usd_cash = CAST(? AS NUMERIC),
                    closing_tick_seq = ?, daily_rank = ?, max_drawdown = CAST(? AS NUMERIC)
                WHERE season_day = ? AND account_id = ?
                """, krwCash, usdCash, tickSeq, dailyRank, maxDrawdown, seasonDay, ACCOUNT_ID);
    }

    /** 그 일차 폐장에 찍힌 보유 한 줄 (V42). 찍는 과정은 {@code DailySettlementServiceTest} 가 본다. */
    private void givenHolding(int seasonDay, long instrumentId, int quantity, String totalCost,
                              String valuationKrw) {
        jdbc.update("""
                INSERT INTO daily_holdings (season_day, account_id, instrument_id, quantity,
                                            total_cost, valuation_krw)
                VALUES (?, ?, ?, ?, CAST(? AS NUMERIC), CAST(? AS NUMERIC))
                """, seasonDay, ACCOUNT_ID, instrumentId, quantity, totalCost, valuationKrw);
    }

    private MockHttpServletRequestBuilder list() {
        return get(DAILY_URI).header(AUTH_TOKEN_HEADER, session);
    }

    private MockHttpServletRequestBuilder detail(int seasonDay) {
        return get(DAILY_URI + "/" + seasonDay).header(AUTH_TOKEN_HEADER, session);
    }

    /** 그 일차의 정산 발표가 끝난 상태. 열람 전용 구간에 시계를 세운다. */
    private void givenAnnounced(int seasonDay) {
        fixture.store(seasonDay, BigDecimal.valueOf(ReplayFixture.ANNOUNCE), ReplayFixture.START,
                BigDecimal.ZERO, TICK_3, seasonDay, ReplayFixture.ANNOUNCE);
    }

    /** 폐장은 지났지만 발표 전인 상태. 리포트는 만들어졌고 문은 아직 닫혀 있다. */
    private void givenSettling(int seasonDay) {
        fixture.store(seasonDay, BigDecimal.valueOf(ReplayFixture.CLOSE), ReplayFixture.START,
                BigDecimal.ZERO, TICK_3, seasonDay, ReplayFixture.CLOSE);
    }

    private void givenAccount() {
        jdbc.update("""
                INSERT INTO accounts (id, user_id, season_id, krw_cash, usd_cash, locked_krw, locked_usd,
                                      starting_balance_krw, index_adjusted, joined_season_day,
                                      daily_base_valuation_krw, daily_base_season_day,
                                      last_acked_tick_seq, created_at)
                VALUES (?, ?, ?, 100000000, 0, 0, 0, 100000000, false, 1, 100000000, 1, 0, now())
                """, ACCOUNT_ID, owner.getId(), ReplayFixture.SEASON_ID);
    }

    /** AI 가 만들어 저장한 그 일차의 문구. 생성 경로는 {@code AiDailyReportApiTest} 가 본다. */
    private void givenCommentary(int seasonDay, String headline, String sceneAliases) {
        jdbc.update("""
                INSERT INTO daily_report_texts (season_day, account_id, headline, scene_aliases,
                                                block_ids, generated_at)
                VALUES (?, ?, ?, CAST(? AS JSONB), '["HEAD_1"]'::JSONB, now())
                """, seasonDay, ACCOUNT_ID, headline, sceneAliases);
    }

    /**
     * 폐장 확정 행 한 줄. 폐장 경계를 실제로 돌리지 않는다 — 여기서 볼 것은 얼려진 값이
     * 계약대로 나가는지이고, 얼리는 과정은 {@code DailySettlementServiceTest} 가 본다.
     */
    private void givenSettlement(int seasonDay, String dailyReturn, int executionCount,
                                 String totalFeeKrw, String realizedPnlKrw, Long bestExecutionId,
                                 Long worstExecutionId, String patternTags) {
        jdbc.update("""
                INSERT INTO daily_settlements (season_day, account_id, season_rank, daily_rank,
                                               season_return, daily_return, total_valuation_krw,
                                               execution_count, total_fee_krw, realized_pnl_krw,
                                               best_execution_id, worst_execution_id,
                                               pattern_tags, finalized_at)
                VALUES (?, ?, 1, 1, 0, CAST(? AS NUMERIC), 100000000, ?, CAST(? AS NUMERIC),
                        CAST(? AS NUMERIC), ?, ?, CAST(? AS JSONB), now())
                """, seasonDay, ACCOUNT_ID, dailyReturn, executionCount, totalFeeKrw,
                realizedPnlKrw, bestExecutionId, worstExecutionId, patternTags);
    }

    /** 체결 한 건. 수량 1 이므로 대금이 단가와 같다. */
    private long givenExecution(long instrumentId, String side, String price, String currency,
                                String fee, String realizedPnl, long tickSeq, LocalDate gameDate) {
        return jdbc.queryForObject("""
                INSERT INTO executions (account_id, instrument_id, order_id, order_type, side,
                                        quantity, executed_price, currency, amount,
                                        applied_fee_rate, fee, realized_pnl, tick_seq, game_date,
                                        executed_at)
                VALUES (?, ?, NULL, 'MARKET', ?, 1, CAST(? AS NUMERIC), ?, CAST(? AS NUMERIC),
                        0.15, CAST(? AS NUMERIC), CAST(? AS NUMERIC), ?, ?, now())
                RETURNING id
                """, Long.class, ACCOUNT_ID, instrumentId, side, price, currency, price, fee,
                realizedPnl, tickSeq, java.sql.Date.valueOf(gameDate));
    }

    private long instrumentId(String symbol) {
        return jdbc.queryForObject("SELECT id FROM instruments WHERE symbol = ?", Long.class, symbol);
    }
}

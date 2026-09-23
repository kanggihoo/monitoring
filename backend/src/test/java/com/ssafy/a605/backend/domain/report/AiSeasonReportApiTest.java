package com.ssafy.a605.backend.domain.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import com.jayway.jsonpath.JsonPath;
import com.sun.net.httpserver.HttpServer;

import com.ssafy.a605.backend.domain.report.service.AiSeasonReportService;
import com.ssafy.a605.backend.support.ContainersConfig;
import com.ssafy.a605.backend.support.MutableClock;
import com.ssafy.a605.backend.support.ReplayFixture;

/**
 * 시즌이 확정된 뒤 AI 에게 보내는 것과 받아 저장하는 것 (전처리 요청서 v5 §7).
 *
 * <p>보는 것은 <b>백엔드만 낼 수 있는 세 가지</b>다: 체결이 그 무렵 가격대의 어디였는지(상대
 * 가격 위치 R), 매수·매도를 선입선출로 짝지어 나오는 보유 기간과 수익률, 그리고 전원을 봐야
 * 나오는 순위와 참가자 수. 나머지는 세어 담는 일이다.
 *
 * <p>픽스처의 검증 종목 A 는 12 거래일치 종가가 8,800 ~ 11,100 이고 창(전후 20 거래일)이 그
 * 전부를 덮으므로, 11,100 에 산 체결은 R = 1 이고 8,800 에 산 체결은 R = 0 이다.
 */
@Tag("container")
@SpringBootTest
@TestPropertySource(locations = "classpath:oauth2-test.properties",
        properties = {
                "app.replay.mode=test",
                "app.replay.fixture.enabled=true",
                "app.replay.season-id=1",
                "app.replay.poll-delay-ms=50"
        })
@Import({ContainersConfig.class, ReplayFixture.class, MutableClock.Config.class})
class AiSeasonReportApiTest {

    private static final long ACCOUNT_ID = 960L;
    private static final short FINAL_DAY = 3;

    /** 3 일차의 틱. 종가는 10,000 +- tick x 100 이다. */
    private static final long TICK_9 = 9L;
    private static final long TICK_10 = 10L;
    private static final long TICK_11 = 11L;
    private static final long TICK_12 = 12L;

    private static HttpServer aiStub;
    private static final BlockingQueue<String> REQUESTS = new ArrayBlockingQueue<>(4);
    private static String response = "";

    @Autowired
    private AiSeasonReportService seasonTexts;

    @Autowired
    private ReplayFixture fixture;

    @Autowired
    private MutableClock clock;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeAll
    static void startStub() throws IOException {
        aiStub = HttpServer.create(new InetSocketAddress("localhost", freePort()), 0);
        aiStub.createContext("/api/ai/reports/season", exchange -> {
            REQUESTS.add(new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8));
            byte[] body = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        aiStub.start();
    }

    @AfterAll
    static void stopStub() {
        aiStub.stop(0);
    }

    @DynamicPropertySource
    static void aiEndpoint(DynamicPropertyRegistry registry) {
        registry.add("app.ai.base-url",
                () -> "http://localhost:" + aiStub.getAddress().getPort());
        registry.add("app.ai.internal-api-key", () -> "test-key");
    }

    @BeforeEach
    void setUp() {
        fixture.reset(clock);
        REQUESTS.clear();
        givenAccount();
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM season_report_texts WHERE account_id = ?", ACCOUNT_ID);
        jdbc.update("DELETE FROM season_reports WHERE account_id = ?", ACCOUNT_ID);
        jdbc.update("DELETE FROM daily_holdings WHERE account_id = ?", ACCOUNT_ID);
        jdbc.update("DELETE FROM daily_settlements WHERE account_id = ?", ACCOUNT_ID);
        jdbc.update("DELETE FROM executions WHERE account_id = ?", ACCOUNT_ID);
        jdbc.update("DELETE FROM accounts WHERE id = ?", ACCOUNT_ID);
    }

    @Test
    @DisplayName("시즌 payload_체결마다_상대 가격 위치와 보유 기간으로 센다")
    void countsByPricePositionAndHolding() throws Exception {
        givenSettlement("13.2000", 38);
        givenSeasonReport("13.2000", "-31.4000", "4.1200", "1284000", 38);
        // 10,000 에 사서 10,200 에 판다. 창 안에서 중간 위치이고 수익률 +2% 라 빠른 익절이다.
        givenExecution("BUY", TICK_9, "10000", null);
        givenExecution("SELL", TICK_10, "10200", "200");
        // 창 최고가(11,100)에 산다 -> R = 1.
        givenExecution("BUY", TICK_11, "11100", null);
        // 창 최저가(8,800)에 판다 -> R = 0. 수익률 -20.72% 라 저점 손절이다.
        givenExecution("SELL", TICK_12, "8800", "-2300");
        // 중간 가격에 사서 창 최고가에 이익으로 판다 -> 고점 익절. 9,000 에 사면 그 매수까지
        // 저점 매수로 세어진다 (창 최저 8,800 대비 R = 0.09).
        givenExecution("BUY", TICK_10, "10000", null);
        givenExecution("SELL", TICK_11, "11100", "1100");
        // 창 최저가에 산다 -> R = 0.
        givenExecution("BUY", TICK_12, "8800", null);
        response = canned();

        seasonTexts.generate();

        String sent = awaitRequest();
        Map<String, Object> account = JsonPath.read(sent, "$.accounts[0]");
        assertThat(account).containsEntry("buyCount", 4)
                .containsEntry("sellCount", 3)
                .containsEntry("winCount", 2)
                // R >= 0.85 인 매수와 R <= 0.15 인 매수.
                .containsEntry("fomoBuyCount", 1)
                .containsEntry("bottomSnipeBuyCount", 1)
                // R >= 0.85 이고 이익인 매도, R <= 0.15 이고 수익률 -5% 이하인 매도.
                .containsEntry("peakSellWinCount", 1)
                .containsEntry("panicBottomSellCount", 1)
                // 보유 20 거래일 이하이고 수익률 +1~+3%.
                .containsEntry("quickProfitCount", 1)
                .containsEntry("quickStopCount", 0)
                // 세 매도 모두 짝이 맞고 각각 한 거래일이다.
                .containsEntry("holdingDaysSum", 3)
                .containsEntry("closedPositionCount", 3);
    }

    @Test
    @DisplayName("시즌 payload_지표는_season_reports 의 저장값을 그대로 싣는다")
    void carriesStoredMetrics() throws Exception {
        givenSettlement("13.2000", 38);
        givenSeasonReport("13.2000", "-31.4000", "4.1200", "1284000", 38);
        response = canned();

        seasonTexts.generate();

        String sent = awaitRequest();
        Map<String, Object> account = JsonPath.read(sent, "$.accounts[0]");
        assertThat(account).containsEntry("maxDrawdown", "-31.40")
                .containsEntry("turnover", "4.12")
                .containsEntry("totalFeeKrw", "1284000")
                .containsEntry("finalRank", 38)
                .containsEntry("totalParticipants", 1)
                // 잠긴 몫을 빼지 않는다. 관망형 판정이 보는 것은 쓰지 않고 남긴 현금 전부다.
                .containsEntry("krwCash", "45000000")
                .containsEntry("totalValuationKrw", "113223530")
                // 화면에 그대로 실리는 문자열이라 서식까지 백엔드가 정한다.
                .containsEntry("returnRate", "+13.2%");
    }

    @Test
    @DisplayName("시즌 문구_받은 유형과 총평을_계좌에 저장한다")
    void savesReturnedTexts() throws Exception {
        givenSettlement("13.2000", 38);
        givenSeasonReport("13.2000", "-31.4000", "4.1200", "1284000", 38);
        response = canned();

        seasonTexts.generate();
        awaitRequest();

        Map<String, Object> text = jdbc.queryForMap(
                "SELECT * FROM season_report_texts WHERE account_id = ?", ACCOUNT_ID);
        assertThat(text).containsEntry("investor_type", "MOMENTUM_CHASER")
                .containsEntry("type_name", "추격 매수형")
                .containsEntry("verdict", "고점에서 자주 손이 나갔습니다.");
        assertThat(text.get("block_ids").toString()).contains("TYPE_MOMENTUM_1");
    }

    @Test
    @DisplayName("시즌 문구_시즌 리포트가 아직 없으면_AI 를 부르지 않는다")
    void withoutSeasonReport_doesNotCallAi() {
        givenSettlement("13.2000", 38);

        seasonTexts.generate();

        // 지표의 절반이 season_reports 에서 온다. 빈 채로 보내면 AI 가 그 위에 문장을 쌓는다.
        assertThat(REQUESTS).isEmpty();
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private String awaitRequest() throws InterruptedException {
        String sent = REQUESTS.poll(5, TimeUnit.SECONDS);
        assertThat(sent).as("AI 에 보낸 요청").isNotNull();
        return sent;
    }

    private static String canned() {
        return """
                {"success":true,"status":200,"message":"OK","data":{"reports":[
                  {"accountId":%d,"investorType":"MOMENTUM_CHASER","typeName":"추격 매수형",
                   "typeDescription":"오르는 것을 따라가는 편입니다.",
                   "verdict":"고점에서 자주 손이 나갔습니다.","blockIds":["TYPE_MOMENTUM_1"]}]},
                 "timestamp":"2026-09-11T08:05:27.558826Z"}
                """.formatted(ACCOUNT_ID);
    }

    private void givenAccount() {
        jdbc.update("""
                INSERT INTO accounts (id, user_id, season_id, krw_cash, usd_cash, locked_krw, locked_usd,
                                      starting_balance_krw, index_adjusted, joined_season_day,
                                      daily_base_valuation_krw, daily_base_season_day,
                                      last_acked_tick_seq, created_at)
                VALUES (?, ?, ?, 45000000, 0, 0, 0, 100000000, false, 1, 100000000, 1, 0, now())
                """, ACCOUNT_ID, fixture.user("시즌문구").getId(), ReplayFixture.SEASON_ID);
    }

    private void givenSettlement(String seasonReturn, int seasonRank) {
        jdbc.update("""
                INSERT INTO daily_settlements (season_day, account_id, season_rank, daily_rank,
                                               season_return, daily_return, total_valuation_krw,
                                               execution_count, total_fee_krw, realized_pnl_krw,
                                               best_execution_id, worst_execution_id,
                                               pattern_tags, finalized_at)
                VALUES (?, ?, ?, 1, CAST(? AS NUMERIC), 0, 113223530, 0, 0, 0,
                        NULL, NULL, '[]'::JSONB, now())
                """, FINAL_DAY, ACCOUNT_ID, seasonRank, seasonReturn);
    }

    private void givenSeasonReport(String finalReturn, String maxDrawdown, String turnover,
                                   String totalFeeKrw, int finalRank) {
        jdbc.update("""
                INSERT INTO season_reports (account_id, final_return, max_drawdown, win_rate,
                                            turnover, total_fee_krw, final_rank, finalized_at)
                VALUES (?, CAST(? AS NUMERIC), CAST(? AS NUMERIC), 0, CAST(? AS NUMERIC),
                        CAST(? AS NUMERIC), ?, now())
                """, ACCOUNT_ID, finalReturn, maxDrawdown, turnover, totalFeeKrw, finalRank);
    }

    /** 체결 한 건. 수량 1 이라 대금이 단가와 같다. */
    private void givenExecution(String side, long tickSeq, String price, String realizedPnl) {
        jdbc.update("""
                INSERT INTO executions (account_id, instrument_id, order_id, order_type, side,
                                        quantity, executed_price, currency, amount,
                                        applied_fee_rate, fee, realized_pnl, tick_seq, game_date,
                                        executed_at)
                SELECT ?, i.id, NULL, 'MARKET', ?, 1, CAST(? AS NUMERIC), i.currency,
                       CAST(? AS NUMERIC), 0, 0, CAST(? AS NUMERIC), t.tick_seq, t.game_date, now()
                FROM instruments i, game_ticks t
                WHERE i.symbol = 'REPLAY_TEST_A' AND t.tick_seq = ?
                """, ACCOUNT_ID, side, price, price, realizedPnl, tickSeq);
    }

}

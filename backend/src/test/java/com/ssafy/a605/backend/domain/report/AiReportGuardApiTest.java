package com.ssafy.a605.backend.domain.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import com.jayway.jsonpath.JsonPath;
import com.sun.net.httpserver.HttpServer;

import com.ssafy.a605.backend.domain.report.service.AiDailyReportService;
import com.ssafy.a605.backend.domain.report.service.AiSeasonReportService;
import com.ssafy.a605.backend.support.ContainersConfig;
import com.ssafy.a605.backend.support.MutableClock;
import com.ssafy.a605.backend.support.ReplayFixture;

/**
 * 코드 리뷰가 지목한 여섯 가지가 <b>정말 깨지는지</b> 확인하는 자리.
 *
 * <p>코드 리뷰가 여섯 가지를 지목했고, 그중 다섯이 실제로 깨지는 것을 먼저 테스트로 재현한 뒤
 * 고쳤다. <b>지금 이 테스트들은 고쳐진 동작을 지킨다</b> — 같은 입력이 다시 와도 한 계좌만
 * 잃고 나머지는 저장된다.
 *
 * <p>DB 제약이 막아 주는 것과 코드가 막아야 하는 것을 가르는 것이 목적이라, <b>막아 주는
 * 쪽도 테스트로 남긴다</b> — 다음 사람이 같은 의심을 다시 하지 않도록.
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
class AiReportGuardApiTest {

    private static final long ACCOUNT_ID = 970L;
    private static final long OTHER_ACCOUNT_ID = 971L;
    private static final short SEASON_DAY = 1;
    private static final short FINAL_DAY = 3;

    /** 1 일차 둘째 틱(2020-01-03). */
    private static final long SECOND_TICK = 2L;

    private static HttpServer aiStub;
    private static final BlockingQueue<String> REQUESTS = new ArrayBlockingQueue<>(4);
    private static String dailyResponse = "";
    private static String seasonResponse = "";

    @Autowired
    private AiDailyReportService dailyReports;

    @Autowired
    private AiSeasonReportService seasonReports;

    @Autowired
    private ReplayFixture fixture;

    @Autowired
    private MutableClock clock;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeAll
    static void startStub() throws IOException {
        aiStub = HttpServer.create(new InetSocketAddress("localhost", freePort()), 0);
        aiStub.createContext("/api/ai/reports/daily", exchange -> respond(exchange, dailyResponse));
        aiStub.createContext("/api/ai/reports/season", exchange -> respond(exchange, seasonResponse));
        aiStub.start();
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, String body)
            throws IOException {
        REQUESTS.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
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
        registry.add("app.ai.index-proxy-symbol", () -> "REPLAY_TEST_A");
    }

    @BeforeEach
    void setUp() {
        fixture.reset(clock);
        REQUESTS.clear();
        givenAccount(ACCOUNT_ID, fixture.user("가드주인"));
        givenAccount(OTHER_ACCOUNT_ID, fixture.user("가드옆자리"));
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM daily_report_texts WHERE account_id IN (?, ?)",
                ACCOUNT_ID, OTHER_ACCOUNT_ID);
        jdbc.update("DELETE FROM season_report_texts WHERE account_id IN (?, ?)",
                ACCOUNT_ID, OTHER_ACCOUNT_ID);
        jdbc.update("DELETE FROM season_reports WHERE account_id IN (?, ?)",
                ACCOUNT_ID, OTHER_ACCOUNT_ID);
        jdbc.update("DELETE FROM daily_holdings WHERE account_id IN (?, ?)",
                ACCOUNT_ID, OTHER_ACCOUNT_ID);
        jdbc.update("DELETE FROM daily_settlements WHERE account_id IN (?, ?)",
                ACCOUNT_ID, OTHER_ACCOUNT_ID);
        jdbc.update("DELETE FROM executions WHERE account_id IN (?, ?)",
                ACCOUNT_ID, OTHER_ACCOUNT_ID);
        jdbc.update("DELETE FROM accounts WHERE id IN (?, ?)", ACCOUNT_ID, OTHER_ACCOUNT_ID);
    }

    @Test
    @DisplayName("BE-1a_머리말이 null 이면_그 계좌만 버리고 나머지는 저장한다")
    void nullHeadline_dropsOnlyThatRow() throws Exception {
        givenSettlement(ACCOUNT_ID);
        givenSettlement(OTHER_ACCOUNT_ID);
        // 앞 계좌는 멀쩡하고 뒤 계좌만 머리말이 비었다.
        dailyResponse = """
                {"success":true,"status":200,"message":"OK","data":{"seasonDay":1,"reports":[
                  {"accountId":%d,"headline":"멀쩡한 문구다.","sceneAliases":[],"blockIds":[]},
                  {"accountId":%d,"headline":null,"sceneAliases":[],"blockIds":[]}]},
                 "timestamp":"2026-09-11T08:05:27Z"}
                """.formatted(ACCOUNT_ID, OTHER_ACCOUNT_ID);

        dailyReports.generate(SEASON_DAY);

        // 저장이 한 트랜잭션이라, 막지 않으면 한 행의 결함이 그 일차 전부를 되돌린다.
        assertThat(textCount()).isEqualTo(1);
        assertThat(headline(ACCOUNT_ID)).isEqualTo("멀쩡한 문구다.");
    }

    @Test
    @DisplayName("BE-1b_머리말이 컬럼 길이를 넘으면_그 계좌만 버린다")
    void tooLongHeadline_dropsOnlyThatRow() throws Exception {
        givenSettlement(ACCOUNT_ID);
        givenSettlement(OTHER_ACCOUNT_ID);
        dailyResponse = """
                {"success":true,"status":200,"message":"OK","data":{"seasonDay":1,"reports":[
                  {"accountId":%d,"headline":"멀쩡한 문구다.","sceneAliases":[],"blockIds":[]},
                  {"accountId":%d,"headline":"%s","sceneAliases":[],"blockIds":[]}]},
                 "timestamp":"2026-09-11T08:05:27Z"}
                """.formatted(ACCOUNT_ID, OTHER_ACCOUNT_ID, "가".repeat(201));

        dailyReports.generate(SEASON_DAY);

        // 길이 상한은 엔티티 상수를 본다 - 컬럼 정의와 갈라지면 검사를 통과한 값이 터진다.
        assertThat(textCount()).isEqualTo(1);
        assertThat(headline(ACCOUNT_ID)).isEqualTo("멀쩡한 문구다.");
    }

    @Test
    @DisplayName("BE-1c_시즌 유형 코드가 빠지면_그 계좌만 버리고 나머지는 저장한다")
    void missingInvestorType_dropsOnlyThatRow() throws Exception {
        givenSettlement(FINAL_DAY, ACCOUNT_ID);
        givenSettlement(FINAL_DAY, OTHER_ACCOUNT_ID);
        givenSeasonReport(ACCOUNT_ID);
        givenSeasonReport(OTHER_ACCOUNT_ID);
        // AI 응답 스키마(ai/app/schemas/report.py 의 SeasonReportText)에 investorType 이 없다.
        // 그 모양 그대로를 흉내낸다 - 필드를 통째로 빼고 보낸다.
        seasonResponse = """
                {"success":true,"status":200,"message":"OK","data":{"reports":[
                  {"accountId":%d,"investorType":"MOMENTUM_CHASER","typeName":"추격 매수형",
                   "typeDescription":"설명","verdict":"총평","blockIds":[]},
                  {"accountId":%d,"typeName":"존버형","typeDescription":"설명",
                   "verdict":"총평","blockIds":[]}]},
                 "timestamp":"2026-09-11T08:05:27Z"}
                """.formatted(ACCOUNT_ID, OTHER_ACCOUNT_ID);

        seasonReports.generate();

        // investorType 은 AI 만 아는 값이라(9종 판정) 비면 우리가 채울 수 없다.
        // 그 계좌만 유형 없이 두고 나머지는 살린다.
        assertThat(seasonTextCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("BE-2_체결이 가리키는 종목은_외래키가 지켜서 사라질 수 없다")
    void instrumentCannotVanishWhileExecutionsPointAtIt() {
        givenExecution(ACCOUNT_ID, "REPLAY_TEST_A", SECOND_TICK);

        // 종목 행이 사라지면 조립이 NPE 로 깨진다는 지적이 있었다. 그 전제가 성립하는지 본다.
        // 체결·종가·포지션 셋이 모두 종목을 참조한다. 어느 쪽이 먼저 막든 요지는 같다 —
        // 조립이 보는 종목 행은 그 조립이 읽는 체결이 살아 있는 한 사라질 수 없다.
        assertThatThrownBy(() -> jdbc.update(
                "DELETE FROM instruments WHERE symbol = 'REPLAY_TEST_A'"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("foreign key");
    }

    @Test
    @DisplayName("BE-3_지수 대리 종목의 첫 종가가 0 이면_원인을 말하고 멈춘다")
    void zeroProxyClose_failsWithReason() {
        givenSettlement(ACCOUNT_ID);
        // 1 일차 첫 거래일의 종가를 0 으로 바꾼다. tearDown 이 픽스처를 되돌린다.
        setProxyClose("0");
        try {
            // 대리 종목 없음·틱 없음과 같은 모양으로 끊는다. 나누면 ArithmeticException 이
            // 원인을 감춘다.
            assertThatThrownBy(() -> dailyReports.generate(SEASON_DAY))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("첫 종가가 0");
        } finally {
            // 픽스처는 기동 시 한 번만 적재된다. 되돌리지 않으면 뒤따르는 테스트가 오염된다.
            setProxyClose("10100");
        }
    }

    /** 1 일차 첫 거래일(2020-01-02)의 검증 종목 A 종가. 픽스처 값은 10,100 이다. */
    private void setProxyClose(String close) {
        jdbc.update("""
                UPDATE daily_closes SET close_price = CAST(? AS NUMERIC)
                WHERE game_date = DATE '2020-01-02'
                  AND instrument_id = (SELECT id FROM instruments WHERE symbol = 'REPLAY_TEST_A')
                """, close);
    }

    @Test
    @DisplayName("BE-4_거래대금이 0 이면_이름과 비중이 함께 빈다")
    void zeroTradedAmount_dropsBothNameAndShare() throws Exception {
        givenSettlement(ACCOUNT_ID);
        // 대금 0 인 체결. 요청서 §5 는 둘이 함께 null 이라고 적었다.
        givenExecution(ACCOUNT_ID, "REPLAY_TEST_A", SECOND_TICK, "0");
        dailyResponse = canned(ACCOUNT_ID);

        dailyReports.generate(SEASON_DAY);

        // 요청서 §5 는 둘이 함께 null 이다. 이름만 실으면 AI 가 비중 없이 "○○에 몰았습니다" 를 쓴다.
        Map<String, Object> metrics = JsonPath.read(awaitRequest(), "$.accounts[0].metrics");
        assertThat(metrics).doesNotContainKey("topInstrumentName")
                .doesNotContainKey("topInstrumentShareKrw");
    }

    @Test
    @DisplayName("BE-5_시즌 리포트가 없는 계좌가 빠져도_참가자 수가 보낸 계좌 수와 같다")
    void participantCountMatchesSentAccounts() throws Exception {
        givenSettlement(FINAL_DAY, ACCOUNT_ID);
        givenSettlement(FINAL_DAY, OTHER_ACCOUNT_ID);
        // 한 계좌만 시즌 확정이 있다. 나머지는 payload 에서 빠진다.
        givenSeasonReport(ACCOUNT_ID);
        seasonResponse = cannedSeason(ACCOUNT_ID);

        seasonReports.generate();

        String sent = awaitRequest();
        assertThat(JsonPath.<java.util.List<Object>>read(sent, "$.accounts")).hasSize(1);
        // AI 의 백분위 기준선은 받은 것만 보고 만든다 - 분모가 그와 같아야 "n명 중 k위" 가 참이다.
        assertThat(JsonPath.<Integer>read(sent, "$.accounts[0].totalParticipants")).isEqualTo(1);
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

    private static String canned(long accountId) {
        return """
                {"success":true,"status":200,"message":"OK","data":{"seasonDay":1,"reports":[
                  {"accountId":%d,"headline":"문구다.","sceneAliases":[],"blockIds":[]}]},
                 "timestamp":"2026-09-11T08:05:27Z"}
                """.formatted(accountId);
    }

    private static String cannedSeason(long accountId) {
        return """
                {"success":true,"status":200,"message":"OK","data":{"reports":[
                  {"accountId":%d,"investorType":"MOMENTUM_CHASER","typeName":"추격 매수형",
                   "typeDescription":"설명","verdict":"총평","blockIds":[]}]},
                 "timestamp":"2026-09-11T08:05:27Z"}
                """.formatted(accountId);
    }

    private String headline(long accountId) {
        return jdbc.queryForObject("""
                SELECT headline FROM daily_report_texts
                WHERE season_day = ? AND account_id = ?
                """, String.class, SEASON_DAY, accountId);
    }

    private int textCount() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM daily_report_texts WHERE account_id IN (?, ?)",
                Integer.class, ACCOUNT_ID, OTHER_ACCOUNT_ID);
    }

    private int seasonTextCount() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM season_report_texts WHERE account_id IN (?, ?)",
                Integer.class, ACCOUNT_ID, OTHER_ACCOUNT_ID);
    }

    private void givenAccount(long accountId, com.ssafy.a605.backend.domain.user.entity.User owner) {
        jdbc.update("""
                INSERT INTO accounts (id, user_id, season_id, krw_cash, usd_cash, locked_krw, locked_usd,
                                      starting_balance_krw, index_adjusted, joined_season_day,
                                      daily_base_valuation_krw, daily_base_season_day,
                                      last_acked_tick_seq, created_at)
                VALUES (?, ?, ?, 45000000, 0, 0, 0, 100000000, false, 1, 100000000, 1, 0, now())
                """, accountId, owner.getId(), ReplayFixture.SEASON_ID);
    }

    private void givenSettlement(long accountId) {
        givenSettlement(SEASON_DAY, accountId);
    }

    private void givenSettlement(short seasonDay, long accountId) {
        jdbc.update("""
                INSERT INTO daily_settlements (season_day, account_id, season_rank, daily_rank,
                                               season_return, daily_return, total_valuation_krw,
                                               execution_count, total_fee_krw, realized_pnl_krw,
                                               best_execution_id, worst_execution_id, pattern_tags,
                                               finalized_at)
                VALUES (?, ?, 1, 1, 0, 0, 100000000, 0, 0, 0, NULL, NULL, '[]'::JSONB, now())
                """, seasonDay, accountId);
    }

    private void givenSeasonReport(long accountId) {
        jdbc.update("""
                INSERT INTO season_reports (account_id, final_return, max_drawdown, win_rate,
                                            turnover, total_fee_krw, final_rank, finalized_at)
                VALUES (?, 0, 0, 0, 0, 0, 1, now())
                """, accountId);
    }

    private void givenExecution(long accountId, String symbol, long tickSeq) {
        givenExecution(accountId, symbol, tickSeq, "10000");
    }

    /** 체결 한 건. 대금을 인자로 받는 이유는 거래대금 0 을 만들기 위해서다. */
    private void givenExecution(long accountId, String symbol, long tickSeq, String amount) {
        jdbc.update("""
                INSERT INTO executions (account_id, instrument_id, order_id, order_type, side,
                                        quantity, executed_price, currency, amount, applied_fee_rate,
                                        fee, realized_pnl, tick_seq, game_date, executed_at)
                SELECT ?, i.id, NULL, 'MARKET', 'BUY', 1, 10000, i.currency, CAST(? AS NUMERIC),
                       0, 0, NULL, t.tick_seq, t.game_date, now()
                FROM instruments i, game_ticks t
                WHERE i.symbol = ? AND t.tick_seq = ?
                """, accountId, amount, symbol, tickSeq);
    }
}

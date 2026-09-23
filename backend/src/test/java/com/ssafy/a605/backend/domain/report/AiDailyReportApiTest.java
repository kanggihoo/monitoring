package com.ssafy.a605.backend.domain.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.List;
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

import com.ssafy.a605.backend.domain.report.service.AiDailyReportService;
import com.ssafy.a605.backend.support.ContainersConfig;
import com.ssafy.a605.backend.support.MutableClock;
import com.ssafy.a605.backend.support.ReplayFixture;

/**
 * 폐장 정산 뒤 AI 에게 보내는 것과 받아 저장하는 것 (전처리 요청서 v5 §4 · §5 · §6).
 *
 * <p><b>진짜 HTTP 로 본다.</b> 클라이언트를 흉내내면 객체가 맞는지만 알 수 있는데, AI 팀이
 * 계약한 것은 <b>전선 위의 JSON</b> 이다 — 필드 이름과 문자열 금액이 그대로 나가는지는 직렬화를
 * 거쳐야 드러난다. JDK 에 들어 있는 HTTP 서버로 세우므로 의존성이 늘지 않는다.
 *
 * <p>보는 것은 요청서가 콕 집은 것들이다: 태그에 붙는 <b>종목명·방향·연월</b>, 태그 <b>6건
 * 상한</b>, 손익의 <b>원화 환산</b>, 금액 기준 <b>집중도</b>, 선입선출로 나오는 <b>보유 기간</b>,
 * 그리고 <b>거래 0 건 계좌도 보내는지</b>.
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
class AiDailyReportApiTest {

    private static final long ACCOUNT_ID = 940L;
    private static final long QUIET_ACCOUNT_ID = 941L;
    private static final long THIRD_ACCOUNT_ID = 942L;
    private static final short SEASON_DAY = 1;

    /** 1 일차의 첫 틱(2020-01-02). 보유 기간의 시작이다. */
    private static final long FIRST_TICK = 1L;

    /** 1 일차의 둘째 틱(2020-01-03). 환율이 1,210 이다. */
    private static final long SECOND_TICK = 2L;

    /** 1 일차의 셋째 틱(2020-01-06). 첫 틱과 두 거래일 떨어져 있다. */
    private static final long THIRD_TICK = 3L;

    /** AI 를 대신하는 서버. 받은 본문을 여기 담아 두고 정해진 답을 돌려준다. */
    private static HttpServer aiStub;
    private static final BlockingQueue<String> REQUESTS = new ArrayBlockingQueue<>(4);
    private static String response = "";

    /** 이만큼의 요청에 503 을 준다. 재시도를 보는 테스트가 켠다. */
    private static int failuresToServe;

    /** 응답을 이만큼 늦춘다. 타임아웃 여유를 보는 테스트가 켠다. */
    private static long delayMillis;

    @Autowired
    private AiDailyReportService dailyReports;

    @Autowired
    private ReplayFixture fixture;

    @Autowired
    private MutableClock clock;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeAll
    static void startStub() throws IOException {
        aiStub = HttpServer.create(new InetSocketAddress("localhost", freePort()), 0);
        aiStub.createContext("/api/ai/reports/daily", exchange -> {
            REQUESTS.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            if (failuresToServe > 0) {
                failuresToServe--;
                exchange.sendResponseHeaders(503, -1);
                exchange.close();
                return;
            }
            if (delayMillis > 0) {
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
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
        // 픽스처에는 유니버스 종목이 없다. 검증 종목 A 를 시장 대리로 세운다.
        registry.add("app.ai.index-proxy-symbol", () -> "REPLAY_TEST_A");
    }

    @BeforeEach
    void setUp() {
        fixture.reset(clock);
        REQUESTS.clear();
        failuresToServe = 0;
        delayMillis = 0;
        givenAccount(ACCOUNT_ID, fixture.user("문구주인").getId());
        givenAccount(QUIET_ACCOUNT_ID, fixture.user("조용한사람").getId());
        givenAccount(THIRD_ACCOUNT_ID, fixture.user("세번째사람").getId());
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM daily_report_texts WHERE account_id IN (?, ?, ?)",
                ACCOUNT_ID, QUIET_ACCOUNT_ID, THIRD_ACCOUNT_ID);
        jdbc.update("DELETE FROM daily_holdings WHERE account_id IN (?, ?, ?)",
                ACCOUNT_ID, QUIET_ACCOUNT_ID, THIRD_ACCOUNT_ID);
        jdbc.update("DELETE FROM daily_settlements WHERE account_id IN (?, ?, ?)",
                ACCOUNT_ID, QUIET_ACCOUNT_ID, THIRD_ACCOUNT_ID);
        jdbc.update("DELETE FROM executions WHERE account_id IN (?, ?, ?)",
                ACCOUNT_ID, QUIET_ACCOUNT_ID, THIRD_ACCOUNT_ID);
        jdbc.update("DELETE FROM accounts WHERE id IN (?, ?, ?)", ACCOUNT_ID,
                QUIET_ACCOUNT_ID, THIRD_ACCOUNT_ID);
    }

    @Test
    @DisplayName("1단_태그에_종목명·방향·연월이 붙어 나간다")
    void enrichesPatternTags() throws Exception {
        long executionId = givenExecution(ACCOUNT_ID, "REPLAY_TEST_A", "BUY", SECOND_TICK,
                1, "10000", null);
        givenSettlement(ACCOUNT_ID, "4.1895", 1,
                tags(tag("PANIC_BUY_HIGH", executionId, "REPLAY_TEST_A", "-13.2")));
        givenSettlement(QUIET_ACCOUNT_ID, "0.0000", 0, "[]");
        response = canned(ACCOUNT_ID, "아쉬웠고 결과는 따라온 날이었다.");

        dailyReports.generate(SEASON_DAY);

        String sent = awaitRequest();
        assertThat(JsonPath.<Integer>read(sent, "$.seasonDay")).isEqualTo(1);
        // 1 일차 첫 거래일 종가 10,100 에서 마지막 9,600 이라 -4.95% 다. AI 스키마가 이 필드를
        // 필수로 두므로 비워 보낼 수 없다.
        assertThat(JsonPath.<String>read(sent, "$.indexReturn")).isEqualTo("-4.95");
        assertThat(JsonPath.<List<Object>>read(sent, "$.accounts")).hasSize(2);
        // 저장된 자릿수 그대로다. 반올림하면 AI 가 받는 값이 daily_settlements 와 달라진다.
        assertThat(JsonPath.<String>read(sent, "$.accounts[0].dailyReturn")).isEqualTo("4.1895");

        Map<String, Object> tag = JsonPath.read(sent, "$.accounts[0].patternTags[0]");
        // 백엔드 이름 그대로 나가야 한다. AI 는 모르는 kind 를 조용히 버려서, 이름이 어긋나면
        // 태그가 사라진 채로 문장이 만들어진다 (extract.TAG_FROM_PATTERN_KIND).
        assertThat(tag).containsEntry("kind", "PANIC_BUY_HIGH")
                .containsEntry("changeRate", "-13.20")
                .containsEntry("instrumentName", "검증 종목 A")
                .containsEntry("side", "BUY")
                // 2 틱은 2020-01-03 이다. AI 가 이 문자열을 그대로 문장에 넣는다.
                .containsEntry("gameMonth", "2020.01");
    }

    @Test
    @DisplayName("1단_체결 원본과 다음 종가는_더 보내지 않는다")
    void dropsRawExecutions() throws Exception {
        givenExecution(ACCOUNT_ID, "REPLAY_TEST_A", "BUY", SECOND_TICK, 1, "10000", null);
        givenSettlement(ACCOUNT_ID, "0.0000", 1, "[]");
        response = canned(ACCOUNT_ID, "조용한 하루였다.");

        dailyReports.generate(SEASON_DAY);

        // v4 는 체결 전부를 보냈고 계좌당 23KB 였다. AI 가 그중 셋만 쓴다 (요청서 v5 §0).
        String sent = awaitRequest();
        assertThat(sent).doesNotContain("executions").doesNotContain("nextClose");
    }

    @Test
    @DisplayName("1단_태그가 여섯을 넘으면_변화율이 큰 순으로 여섯만 보낸다")
    void capsPatternTagsAtSix() throws Exception {
        StringBuilder stored = new StringBuilder();
        for (int i = 1; i <= 7; i++) {
            long executionId = givenExecution(ACCOUNT_ID, "REPLAY_TEST_A", "BUY", SECOND_TICK,
                    1, "10000", null);
            stored.append(stored.isEmpty() ? "" : ",")
                    .append(tag("PANIC_BUY_HIGH", executionId, "REPLAY_TEST_A", "-1" + i));
        }
        givenSettlement(ACCOUNT_ID, "0.0000", 7, "[" + stored + "]");
        response = canned(ACCOUNT_ID, "많이도 틀렸다.");

        dailyReports.generate(SEASON_DAY);

        // AI 가 장면을 셋만 쓰므로 여유를 두고 여섯을 보낸다. 고르는 일은 AI 에 남긴다.
        String sent = awaitRequest();
        assertThat(JsonPath.<List<String>>read(sent, "$.accounts[0].patternTags[*].changeRate"))
                .containsExactly("-17.00", "-16.00", "-15.00", "-14.00", "-13.00", "-12.00");
    }

    @Test
    @DisplayName("2단_metrics 에_건수·거래대금·집중도·순위가 담긴다")
    void sendsMetrics() throws Exception {
        givenExecution(ACCOUNT_ID, "REPLAY_TEST_A", "BUY", FIRST_TICK, 2, "10000", null);
        givenExecution(ACCOUNT_ID, "REPLAY_TEST_A", "SELL", THIRD_TICK, 1, "12000", "2000");
        // 달러 체결이다. 2 틱의 환율 1,210 으로 환산하면 12,100 원이다.
        givenExecution(ACCOUNT_ID, "REPLAY_TEST_C", "SELL", SECOND_TICK, 1, "10.00", "10.00");
        givenSettlement(ACCOUNT_ID, "1.0000", 3, "[]", "500", "14100", null, null);
        givenSettlement(QUIET_ACCOUNT_ID, "0.0000", 0, "[]");
        response = canned(ACCOUNT_ID, "많이 돌린 날이었다.");

        dailyReports.generate(SEASON_DAY);

        String sent = awaitRequest();
        Map<String, Object> metrics = JsonPath.read(sent, "$.accounts[0].metrics");
        // 승률을 비율이 아니라 건수로 보낸다. 임계값은 AI 가 갖는다 (요청서 v5 §5).
        assertThat(metrics).containsEntry("buyCount", 1)
                .containsEntry("sellCount", 2)
                .containsEntry("winCount", 2)
                .containsEntry("realizedPnlKrw", "14100")
                .containsEntry("totalFeeKrw", "500")
                // 20,000 + 12,000 + 12,100 = 44,100 원. 달러 체결도 원화로 더한다.
                .containsEntry("tradedAmountKrw", "44100")
                .containsEntry("topInstrumentName", "검증 종목 A")
                // 32,000 / 44,100 = 0.7256. 건수로 재면 A 와 C 가 같은 무게가 된다.
                .containsEntry("topInstrumentShareKrw", "0.73")
                // 수익률 순위와는 다른 축이다 — 많이 거래한 순서다.
                .containsEntry("executionCountRank", 1)
                .containsEntry("participantCount", 2)
                // 1 틱에 사서 3 틱에 팔았으니 두 거래일이다. 달러 매도는 짝지을 매수가 없다.
                .containsEntry("avgHoldingDays", 2)
                .containsEntry("quickSellCount", 1);
    }

    @Test
    @DisplayName("2단_highlights 에_최고·최악 거래가 펼쳐진다")
    void sendsHighlights() throws Exception {
        givenExecution(ACCOUNT_ID, "REPLAY_TEST_A", "BUY", FIRST_TICK, 2, "10000", null);
        long best = givenExecution(ACCOUNT_ID, "REPLAY_TEST_A", "SELL", THIRD_TICK,
                1, "12000", "2000");
        long worst = givenExecution(ACCOUNT_ID, "REPLAY_TEST_C", "SELL", SECOND_TICK,
                1, "10.00", "-10.00");
        givenSettlement(ACCOUNT_ID, "1.0000", 3, "[]", "0", "0", best, worst);
        response = canned(ACCOUNT_ID, "하나는 잘했고 하나는 아쉬웠다.");

        dailyReports.generate(SEASON_DAY);

        String sent = awaitRequest();
        Map<String, Object> top = JsonPath.read(sent, "$.accounts[0].highlights[0]");
        assertThat(top).containsEntry("role", "BEST")
                .containsEntry("instrumentName", "검증 종목 A")
                .containsEntry("side", "SELL")
                .containsEntry("gameMonth", "2020.01")
                .containsEntry("realizedPnlKrw", "2000")
                // 10,000 에 사서 12,000 에 팔았다. 환율이 필요 없다 — 비율에서 약분된다.
                .containsEntry("returnRate", "20.00")
                .containsEntry("holdingDays", 2);

        Map<String, Object> bottom = JsonPath.read(sent, "$.accounts[0].highlights[1]");
        // 환산하지 않으면 AI 가 손익 절대값으로 장면을 고를 때 순서가 뒤집힌다.
        assertThat(bottom).containsEntry("role", "WORST")
                .containsEntry("realizedPnlKrw", "-12100")
                // 그 일차에 짝지을 매수가 없다. AI 는 그때 "며칠 만에"를 말하지 않는다.
                .doesNotContainKey("returnRate")
                .doesNotContainKey("holdingDays");
    }

    @Test
    @DisplayName("일일 문구_거래가 없는 계좌도_빈 태그 배열로 함께 보낸다")
    void includesAccountsWithoutTrades() throws Exception {
        givenSettlement(ACCOUNT_ID, "0.0000", 0, "[]");
        givenSettlement(QUIET_ACCOUNT_ID, "0.0000", 0, "[]");
        response = canned(ACCOUNT_ID, "조용한 하루였다.");

        dailyReports.generate(SEASON_DAY);

        // 걸러내면 그 사람만 리포트가 빈다. 빈 배열을 받은 AI 가 관망으로 판정한다 (요청서 v5 §4).
        String sent = awaitRequest();
        assertThat(JsonPath.<List<Object>>read(sent, "$.accounts")).hasSize(2);
        assertThat(JsonPath.<List<Object>>read(sent, "$.accounts[1].patternTags")).isEmpty();
        assertThat(JsonPath.<List<Object>>read(sent, "$.accounts[1].highlights")).isEmpty();
        Map<String, Object> metrics = JsonPath.read(sent, "$.accounts[1].metrics");
        assertThat(metrics).containsEntry("buyCount", 0)
                .containsEntry("tradedAmountKrw", "0")
                // 거래가 없으면 몰아줄 종목도, 평균 보유 기간도 없다.
                .doesNotContainKey("topInstrumentName")
                .doesNotContainKey("topInstrumentShareKrw")
                .doesNotContainKey("avgHoldingDays");
    }

    @Test
    @DisplayName("일일 문구_받은 문구를_그 일차의 계좌에 저장한다")
    void savesReturnedTexts() throws Exception {
        givenSettlement(ACCOUNT_ID, "0.0000", 0, "[]");
        response = canned(ACCOUNT_ID, "아쉬웠고 결과는 따라온 날이었다.");

        dailyReports.generate(SEASON_DAY);
        awaitRequest();

        assertThat(headline(ACCOUNT_ID)).isEqualTo("아쉬웠고 결과는 따라온 날이었다.");
        // 다음 일차의 변주를 피하려면 고른 블록도 남아 있어야 한다.
        assertThat(blockIds(ACCOUNT_ID)).contains("HEAD_ABOVE_OVERTRADE_1");
    }

    @Test
    @DisplayName("일일 문구_AI 가 답하지 않으면_문구 없이 넘어간다")
    void survivesAiFailure() {
        givenSettlement(ACCOUNT_ID, "0.0000", 0, "[]");
        response = "그럴듯하지 않은 응답";

        // 게임은 멈추지 않는다. 문구가 없는 리포트가 공개될 뿐이다 (요청서 v5 §6).
        dailyReports.generate(SEASON_DAY);

        assertThat(textCount()).isZero();
    }

    @Test
    @DisplayName("엣지_체결 건수가 같으면_같은 순위를 주고 다음을 건너뛴다")
    void tiedExecutionCountsShareTheSameRank() throws Exception {
        givenSettlement(ACCOUNT_ID, "0.0000", 5, "[]");
        givenSettlement(QUIET_ACCOUNT_ID, "0.0000", 5, "[]");
        givenSettlement(THIRD_ACCOUNT_ID, "0.0000", 2, "[]");
        response = canned(ACCOUNT_ID, "셋이 붐볐다.");

        dailyReports.generate(SEASON_DAY);

        // 동점을 1·2 로 갈라 주면 "100명 중 2번째" 가 사람마다 달라진다 (rank.md 의 순위 규칙).
        String sent = awaitRequest();
        assertThat(JsonPath.<List<Integer>>read(sent,
                "$.accounts[*].metrics.executionCountRank")).containsExactly(1, 1, 3);
        assertThat(JsonPath.<List<Integer>>read(sent,
                "$.accounts[*].metrics.participantCount")).containsExactly(3, 3, 3);
    }

    @Test
    @DisplayName("엣지_그 일차에 없는 체결을 가리키는 태그와 상하위는_빠진다")
    void referencesOutsideTheDayAreDropped() throws Exception {
        // 2 일차(5 틱)의 체결이다. 1 일차 payload 에는 이 체결이 없으므로 종목명을 채울 수 없다.
        long other = givenExecution(ACCOUNT_ID, "REPLAY_TEST_A", "BUY", 5L, 1, "10000", null);
        givenSettlement(ACCOUNT_ID, "0.0000", 1,
                tags(tag("PANIC_BUY_HIGH", other, "REPLAY_TEST_A", "-13.2")), "0", "0", other,
                other);
        response = canned(ACCOUNT_ID, "가리킬 것이 없었다.");

        dailyReports.generate(SEASON_DAY);

        // 이름을 못 채운 채 보내면 AI 가 빈 문자열을 문장에 넣는다. 아예 빼는 것이 낫다.
        String sent = awaitRequest();
        assertThat(JsonPath.<List<Object>>read(sent, "$.accounts[0].patternTags")).isEmpty();
        assertThat(JsonPath.<List<Object>>read(sent, "$.accounts[0].highlights")).isEmpty();
    }

    @Test
    @DisplayName("엣지_한 계좌의 태그가 읽히지 않아도_그 일차 전체는 나간다")
    void unreadableTagsDoNotSinkTheDay() throws Exception {
        // 모양이 어긋난 JSON 이다. 저장은 jsonb 라 통과하지만 역직렬화가 실패한다.
        givenSettlement(ACCOUNT_ID, "0.0000", 0, "{\"kind\":\"PANIC_BUY_HIGH\"}");
        givenSettlement(QUIET_ACCOUNT_ID, "0.0000", 0, "[]");
        response = canned(QUIET_ACCOUNT_ID, "옆자리는 무사했다.");

        dailyReports.generate(SEASON_DAY);

        // 한 사람의 데이터가 깨진 것과 100 명의 리포트를 비우는 것은 다른 이야기다.
        String sent = awaitRequest();
        assertThat(JsonPath.<List<Object>>read(sent, "$.accounts")).hasSize(2);
        assertThat(JsonPath.<List<Object>>read(sent, "$.accounts[0].patternTags")).isEmpty();
        assertThat(headline(QUIET_ACCOUNT_ID)).isEqualTo("옆자리는 무사했다.");
    }

    @Test
    @DisplayName("변주_앞선 일차에 쓴 블록을 모두 되돌려 보낸다")
    void sendsEveryEarlierBlockId() throws Exception {
        givenDailyText((short) 1, ACCOUNT_ID, "[\"HEAD_BELOW_PEAKBUY_3\",\"ALIAS_PEAKBUY_1\"]");
        givenDailyText((short) 2, ACCOUNT_ID, "[\"HEAD_BELOW_PEAKBUY_1\"]");
        givenSettlement((short) 3, ACCOUNT_ID, "0.0000", 0, "[]", "0", "0", null, null);
        response = canned(ACCOUNT_ID, "앞선 이틀과 다른 문구다.");

        dailyReports.generate((short) 3);

        // 직전 일차만 빼면 1 일차와 3 일차가 다시 겹친다 (목업에서 9.0% 잔존).
        assertThat(JsonPath.<List<String>>read(awaitRequest(), "$.accounts[0].previousBlockIds"))
                .containsExactlyInAnyOrder("HEAD_BELOW_PEAKBUY_3", "ALIAS_PEAKBUY_1",
                        "HEAD_BELOW_PEAKBUY_1");
    }

    @Test
    @DisplayName("변주_1일차는 전날이 없으므로_빈 배열이다")
    void firstDayHasNoPreviousBlocks() throws Exception {
        givenSettlement(ACCOUNT_ID, "0.0000", 0, "[]");
        response = canned(ACCOUNT_ID, "첫날이다.");

        dailyReports.generate(SEASON_DAY);

        assertThat(JsonPath.<List<String>>read(awaitRequest(), "$.accounts[0].previousBlockIds"))
                .isEmpty();
    }

    @Test
    @DisplayName("엣지_전날 블록이 읽히지 않아도_그 일차는 나간다")
    void unreadablePreviousBlocksDoNotSinkTheDay() throws Exception {
        // 모양이 어긋난 JSON 이다. 저장은 jsonb 라 통과하지만 역직렬화가 실패한다.
        givenDailyText((short) 1, ACCOUNT_ID, "{\"id\":\"HEAD_BELOW_PEAKBUY_3\"}");
        givenSettlement((short) 2, ACCOUNT_ID, "0.0000", 0, "[]", "0", "0", null, null);
        response = canned(ACCOUNT_ID, "그래도 나간다.");

        dailyReports.generate((short) 2);

        // 변주가 겹치는 것은 문구가 비는 것보다 가볍다. 그 계좌만 빼지 않고 넘어간다.
        String sent = awaitRequest();
        assertThat(JsonPath.<List<String>>read(sent, "$.accounts[0].previousBlockIds")).isEmpty();
        assertThat(headline((short) 2, ACCOUNT_ID)).isEqualTo("그래도 나간다.");
    }

    @Test
    @DisplayName("엣지_정산 행이 없으면_AI 를 부르지 않는다")
    void withoutSettlements_doesNotCallAi() {
        dailyReports.generate(SEASON_DAY);

        // 빈 배열을 보내 봐야 AI 도 빈 응답을 만들 뿐이다. 왕복을 아낀다.
        assertThat(REQUESTS).isEmpty();
        assertThat(textCount()).isZero();
    }

    @Test
    @DisplayName("엣지_편성에 없는 일차는_0 을 지어내는 대신 호출을 접는다")
    void withoutScheduledTicks_abortsBeforeCalling() {
        // 픽스처 편성은 3 일차까지다. 9 일차는 틱이 없어 시장 수익률을 계산할 수 없다.
        short unscheduled = 9;
        givenSettlement(unscheduled, ACCOUNT_ID, "0.0000", 0, "[]", "0", "0", null, null);

        // 시장이 움직이지 않았다고 보내면 AI 가 그 위에 문장을 쌓는다.
        assertThatThrownBy(() -> dailyReports.generate(unscheduled))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("편성에 없는 일차");
        assertThat(REQUESTS).isEmpty();
    }

    @Test
    @DisplayName("엣지_보낸 적 없는 계좌의 문구는_버리고 나머지를 저장한다")
    void dropsTextsForAccountsNotAskedAbout() throws Exception {
        givenSettlement(ACCOUNT_ID, "0.0000", 0, "[]");
        // 두 번째 문구의 주인은 이 일차 정산에 없다. 그 행은 accounts 외래키를 맞추지 못한다.
        response = twoReports(ACCOUNT_ID, "내 문구다.", 88888L, "남의 문구다.");

        dailyReports.generate(SEASON_DAY);
        awaitRequest();

        // 한 트랜잭션이라, 저 행 하나가 터지면 그 일차 문구를 전부 잃는다.
        assertThat(headline(ACCOUNT_ID)).isEqualTo("내 문구다.");
        assertThat(textCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("엣지_첫 호출이 실패하면_한 번 더 시도한다")
    void retriesOnceAfterAFailedCall() throws Exception {
        givenSettlement(ACCOUNT_ID, "0.0000", 0, "[]");
        response = canned(ACCOUNT_ID, "두 번째에 받았다.");
        failuresToServe = 1;

        dailyReports.generate(SEASON_DAY);

        // 같은 입력에 같은 결과가 나오는 호출이라 중복 요청이 안전하다.
        assertThat(awaitRequest()).isNotBlank();
        assertThat(awaitRequest()).isNotBlank();
        assertThat(headline(ACCOUNT_ID)).isEqualTo("두 번째에 받았다.");
    }

    @Test
    @DisplayName("엣지_같은 일차를 다시 만들면_문구가 갱신된다")
    void regeneratingTheSameDay_replacesTheText() throws Exception {
        givenSettlement(ACCOUNT_ID, "0.0000", 0, "[]");
        response = canned(ACCOUNT_ID, "처음 문구다.");
        dailyReports.generate(SEASON_DAY);
        awaitRequest();

        response = canned(ACCOUNT_ID, "다시 만든 문구다.");
        dailyReports.generate(SEASON_DAY);
        awaitRequest();

        // 키가 (일차, 계좌) 라 재실행이 행을 늘리지 않고 덮는다 - 운영에서 다시 돌릴 수 있어야 한다.
        assertThat(headline(ACCOUNT_ID)).isEqualTo("다시 만든 문구다.");
        assertThat(textCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("재시도_문구를 한 줄도 못 받은 일차는_발표 경계에서 다시 만든다")
    void regeneratesAtAnnouncementWhenNothingWasSaved() throws Exception {
        givenSettlement(ACCOUNT_ID, "0.0000", 0, "[]");
        response = canned(ACCOUNT_ID, "늦었지만 받았다.");

        // 폐장의 호출이 실패했다고 치고, 발표 경계가 부르는 경로를 그대로 탄다.
        dailyReports.regenerateIfMissing(SEASON_DAY);
        awaitRequest();

        // 발표 뒤로는 그 일차에 아무 경계도 남지 않는다 - 여기가 마지막 만회 지점이다.
        assertThat(headline(ACCOUNT_ID)).isEqualTo("늦었지만 받았다.");
    }

    @Test
    @DisplayName("재시도_이미 받은 일차는_발표 경계에서 다시 부르지 않는다")
    void doesNotRegenerateWhenTextsExist() throws Exception {
        givenSettlement(ACCOUNT_ID, "0.0000", 0, "[]");
        response = canned(ACCOUNT_ID, "처음에 받았다.");
        dailyReports.generate(SEASON_DAY);
        awaitRequest();

        dailyReports.regenerateIfMissing(SEASON_DAY);

        // 부분적으로 받은 날까지 되부르면 발표마다 같은 호출이 반복된다.
        assertThat(REQUESTS).isEmpty();
        assertThat(headline(ACCOUNT_ID)).isEqualTo("처음에 받았다.");
    }

    @Test
    @Tag("slow")
    @DisplayName("타임아웃_AI 가 오래 걸려도_10 초에 끊지 않는다")
    void waitsLongerThanTenSeconds() throws Exception {
        givenSettlement(ACCOUNT_ID, "0.0000", 0, "[]");
        response = canned(ACCOUNT_ID, "느렸지만 받았다.");
        // AI 는 계좌마다 LLM 을 부른다. 예전 상한 10 초는 그보다 짧아 정상 동작을 끊었다.
        delayMillis = 12_000;

        dailyReports.generate(SEASON_DAY);

        assertThat(awaitRequest()).isNotBlank();
        assertThat(headline(ACCOUNT_ID)).isEqualTo("느렸지만 받았다.");
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

    @Test
    @DisplayName("4단_누적 집계가_그 일차까지의 구간으로 함께 나간다")
    void sendsCumulativeForTheDaySoFar() throws Exception {
        givenExecution(ACCOUNT_ID, "REPLAY_TEST_A", "BUY", SECOND_TICK, 2, "10000", null);
        givenSettlement(ACCOUNT_ID, "4.1895", 1, "[]");
        // 폐장에 찍어 둔 낙폭 스냅샷 (V38). 여기가 비면 AI 가 손실 감내를 못 잰다.
        givenDrawdownSnapshot(ACCOUNT_ID, "-31.4000");
        givenSelfAnswers(ACCOUNT_ID, "24312");
        response = canned(ACCOUNT_ID, "누적도 함께 갔다.");

        dailyReports.generate(SEASON_DAY);

        String sent = awaitRequest();
        Map<String, Object> cumulative = JsonPath.read(sent, "$.accounts[0].cumulative");
        assertThat(cumulative)
                .containsEntry("buyCount", 1)
                .containsEntry("sellCount", 0)
                // 시즌 누적이 아니라 **그 일차 스냅샷**이어야 한다.
                .containsEntry("maxDrawdown", "-31.40")
                // 설문 답 원본을 그대로 싣는다. 채점은 화면이 한다.
                .containsEntry("selfAnswers", "24312")
                .containsEntry("totalParticipants", 1);
        // 달러 현금이 없는 계좌라 원화 현금과 같다. 달러를 들고 관망한 사람과 구분되는 자리다.
        assertThat(cumulative.get("totalCashKrw")).isEqualTo(cumulative.get("krwCash"));
    }

    /**
     * 결과지 생성은 폐장 뒤 <b>비동기</b>다. 다음 날 브리핑의 일차 초기화를 지나 실행되면
     * 계좌의 현금은 이미 시드로 덮여 있어, 계좌에서 읽으면 그 일차의 마감 현금이 아니다.
     */
    @Test
    @DisplayName("4단_누적 현금은_계좌의 현재 값이 아니라 폐장에 찍은 마감 현금이다")
    void sendsClosingCashNotLiveBalance() throws Exception {
        givenSettlement(ACCOUNT_ID, "0.0000", 0, "[]");
        givenClosingCash(ACCOUNT_ID, "77000000", "1000");
        // 초기화가 이미 지나가 계좌는 시드로 돌아간 상태를 흉내 낸다.
        jdbc.update("UPDATE accounts SET krw_cash = 100000000, usd_cash = 0 WHERE id = ?", ACCOUNT_ID);
        response = canned(ACCOUNT_ID, "마감 현금을 봤다.");

        dailyReports.generate(SEASON_DAY);

        Map<String, Object> cumulative = JsonPath.read(awaitRequest(), "$.accounts[0].cumulative");
        assertThat(cumulative).containsEntry("krwCash", "77000000");
        // 달러도 마감 값으로 환산된다 — 계좌의 0 이 아니다.
        assertThat(cumulative.get("totalCashKrw")).isNotEqualTo(cumulative.get("krwCash"));
    }

    /**
     * 일차 초기화 이후 {@code daily_settlements.season_rank}·{@code season_return} 은 누적이
     * 아니라 그날 값과 같다. 누적 순위는 시즌 성적을 정하는 규칙인 포인트 합산을 쓴다
     * ({@code S15P21A605-250}).
     */
    @Test
    @DisplayName("4단_누적 순위와 수익률은_포인트 순위와 일간 수익률 합이다")
    void sendsPointRankAndDailyReturnSum() throws Exception {
        // 1 일차에 다른 계좌가 1 등, 이 계좌가 2 등이었다. 2 일차는 이 계좌가 1 등이다.
        givenSettlement((short) 1, QUIET_ACCOUNT_ID, "10.0000", 1, "[]", "0", "0", null, null);
        jdbc.update("UPDATE daily_settlements SET daily_rank = 1 WHERE season_day = 1 AND account_id = ?",
                QUIET_ACCOUNT_ID);
        givenSettlement((short) 1, ACCOUNT_ID, "5.0000", 1, "[]", "0", "0", null, null);
        jdbc.update("UPDATE daily_settlements SET daily_rank = 2 WHERE season_day = 1 AND account_id = ?",
                ACCOUNT_ID);
        response = canned(ACCOUNT_ID, "포인트로 셌다.");

        dailyReports.generate((short) 1);

        Map<String, Object> mine = cumulativeOf(awaitRequest(), ACCOUNT_ID);
        // 2 등 80 점 < 1 등 100 점이라 포인트 순위는 2 위다 (season_rank 는 둘 다 1 이다).
        assertThat(mine).containsEntry("finalRank", 2).containsEntry("totalParticipants", 2);
        // 일간 수익률의 단순 합. season_return(0) 이 아니다.
        assertThat(mine).containsEntry("returnRate", "+5.0%");
    }

    /**
     * 누적 블록의 다른 값들(체결·수수료·회전율)은 모두 그 일차까지로 잘려 있다. 포인트만
     * 확정된 전부를 보면 <b>한 덩이 안에서 기준이 갈린다</b> — 1 일차 결과지에 2 일차 점수가
     * 실린다.
     */
    @Test
    @DisplayName("4단_뒤 일차가 확정된 뒤 지난 일차를 다시 만들어도_포인트는 그 일차까지만 센다")
    void regeneratingPastDay_countsPointsUpToThatDayOnly() throws Exception {
        // 1 일차: 상대가 1 등(100 점), 이 계좌가 2 등(80 점).
        givenSettlement((short) 1, QUIET_ACCOUNT_ID, "10.0000", 1, "[]", "0", "0", null, null);
        jdbc.update("UPDATE daily_settlements SET daily_rank = 1 WHERE season_day = 1 AND account_id = ?",
                QUIET_ACCOUNT_ID);
        givenSettlement((short) 1, ACCOUNT_ID, "5.0000", 1, "[]", "0", "0", null, null);
        jdbc.update("UPDATE daily_settlements SET daily_rank = 2 WHERE season_day = 1 AND account_id = ?",
                ACCOUNT_ID);
        // 2 일차: 이 계좌가 1 등(100 점). 합치면 180 점으로 순위가 뒤집힌다.
        givenSettlement((short) 2, ACCOUNT_ID, "40.0000", 1, "[]", "0", "0", null, null);
        response = canned(ACCOUNT_ID, "1일차를 다시 만들었다.");

        dailyReports.generate((short) 1);

        Map<String, Object> mine = cumulativeOf(awaitRequest(), ACCOUNT_ID);
        // 2 일차를 세면 1 위 · +45.0% 가 된다. 1 일차까지만 세야 2 위 · +5.0% 다.
        assertThat(mine).containsEntry("finalRank", 2).containsEntry("returnRate", "+5.0%");
        assertThat(mine).containsEntry("totalParticipants", 2);
    }

    /** 보낸 요청에서 그 계좌의 누적 블록. 계좌 순서를 가정하지 않는다. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> cumulativeOf(String sent, long accountId) {
        List<Map<String, Object>> accounts = JsonPath.read(sent, "$.accounts");
        return accounts.stream()
                .filter(account -> ((Number) account.get("accountId")).longValue() == accountId)
                .map(account -> (Map<String, Object>) account.get("cumulative"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("계좌 %d 의 누적 블록이 없습니다".formatted(accountId)));
    }

    /** 폐장에 찍은 마감 현금 (V42). 이 값이 비면 계좌의 현재 값으로 물러난다. */
    private void givenClosingCash(long accountId, String krw, String usd) {
        jdbc.update("""
                UPDATE daily_settlements
                SET closing_krw_cash = CAST(? AS NUMERIC), closing_usd_cash = CAST(? AS NUMERIC)
                WHERE season_day = ? AND account_id = ?
                """, krw, usd, SEASON_DAY, accountId);
    }

    @Test
    @DisplayName("4단_낙폭 스냅샷이 없는 일차는_그 값만 비워 보낸다")
    void withoutDrawdownSnapshot_omitsOnlyThatField() throws Exception {
        givenSettlement(ACCOUNT_ID, "0.0000", 0, "[]");
        response = canned(ACCOUNT_ID, "조용한 하루였다.");

        dailyReports.generate(SEASON_DAY);

        String sent = awaitRequest();
        // 시즌 누적을 대신 채우면 1일차에 3일차 값이 실린다. 비우는 편이 맞다.
        Map<String, Object> cumulative = JsonPath.read(sent, "$.accounts[0].cumulative");
        assertThat(cumulative).doesNotContainKey("maxDrawdown");
        // 나머지는 정상적으로 나가야 한다.
        assertThat(cumulative).containsEntry("buyCount", 0).containsKey("krwCash");
    }

    private void givenDrawdownSnapshot(long accountId, String maxDrawdown) {
        jdbc.update("""
                UPDATE daily_settlements SET max_drawdown = CAST(? AS NUMERIC)
                WHERE season_day = ? AND account_id = ?
                """, maxDrawdown, SEASON_DAY, accountId);
    }

    private void givenSelfAnswers(long accountId, String answers) {
        jdbc.update("UPDATE accounts SET self_answers = ?, self_answers_at = now() WHERE id = ?",
                answers, accountId);
    }

    /**
     * 결과지를 함께 담은 응답 (요청서 2026-09-18 ③).
     *
     * <p>설문을 하지 않은 사람의 모양이다 — 선언 쪽 넷이 비어 있고, 막대 하나는 잴 거래가 없어
     * {@code point} 가 {@code null} 이다. 둘 다 정상 경로라 저장에서 걸러지지 않아야 한다.
     */
    private static String cannedWithReview(long accountId, String headline) {
        return """
                {"success":true,"status":200,"message":"OK","data":{"seasonDay":1,"reports":[
                  {"accountId":%d,"headline":"%s","sceneAliases":["보통의 선택"],
                   "blockIds":["HEAD_ABOVE_OVERTRADE_1"],
                   "review":{
                     "investorType":"LEVEL_2","typeEmoji":"⚡",
                     "typeName":"돌다리 두드리는 분석가",
                     "typeDescription":"확인하고 들어가는 편입니다.",
                     "verdict":"고점에서 손이 나간 날이 있었습니다.",
                     "profileLevel":2,"profileTotal":9,
                     "profileName":"안정추구형","profilePersona":"돌다리 두드리는 분석가",
                     "profileItems":[
                       {"item":"INVESTED","label":"투입 비중","point":2,"provisional":false,"selfPoint":null},
                       {"item":"DRAWDOWN","label":"손실 감내","point":1,"provisional":false,"selfPoint":null},
                       {"item":"ENTRY","label":"진입 타이밍","point":3,"provisional":true,"selfPoint":null},
                       {"item":"DIP","label":"급락 대응","point":null,"provisional":false,"selfPoint":null},
                       {"item":"STOP","label":"손실 정리","point":2,"provisional":false,"selfPoint":null}],
                     "selfLevel":null,"selfTotal":null,"selfPersona":null,"profileMatch":null,
                     "narrative":{"oneLine":"신중했습니다.","selfView":null,
                       "actual":"고점 근처 매수가 두 번 있었습니다.",
                       "traits":[{"axis":"ENTRY","title":"확인 후 진입","body":"매수 7 건 중 2 건이 고점 근처였습니다."}],
                       "nextStep":"진입 자리를 한 번 더 보세요.","profileGap":null,
                       "lossTolerance":"최대 낙폭 -31.4%% 를 견뎠습니다."},
                     "blockIds":["TYPE_LEVEL2_1","NEXT_LEVEL2_1"]}}]},
                 "timestamp":"2026-09-09T08:05:27.558826Z"}
                """.formatted(accountId, headline);
    }

    @Test
    @DisplayName("3단_결과지가 함께 오면_일차별 행에 저장되고 블록이 합쳐진다")
    void storesTheReviewOnTheSameRow() throws Exception {
        givenSettlement(ACCOUNT_ID, "4.1895", 1, "[]");
        response = cannedWithReview(ACCOUNT_ID, "신중했던 하루였다.");

        dailyReports.generate(SEASON_DAY);
        awaitRequest();

        Map<String, Object> row = reviewRow(ACCOUNT_ID);
        assertThat(row).containsEntry("investor_type", "LEVEL_2")
                .containsEntry("type_name", "돌다리 두드리는 분석가")
                .containsEntry("profile_level", 2)
                .containsEntry("profile_total", 9)
                .containsEntry("profile_name", "안정추구형");
        // 설문을 안 한 사람이다. 이 넷이 비는 것은 정상 경로이며 실패가 아니다.
        assertThat(row.get("self_level")).isNull();
        assertThat(row.get("profile_match")).isNull();

        // 잴 거래가 없던 막대는 point 가 null 로 남는다 - 0 이 아니다.
        assertThat(JsonPath.<Object>read((String) row.get("profile_items"),
                "$[3].point")).isNull();
        assertThat(JsonPath.<String>read((String) row.get("narrative"),
                "$.nextStep")).isEqualTo("진입 자리를 한 번 더 보세요.");

        // 머리말 블록과 결과지 블록이 한 자리에 합쳐져야 previousBlockIds 가 둘 다 빼준다.
        assertThat(blockIds(ACCOUNT_ID))
                .contains("HEAD_ABOVE_OVERTRADE_1", "TYPE_LEVEL2_1", "NEXT_LEVEL2_1");
    }

    @Test
    @DisplayName("3단_결과지가 없는 응답이면_머리말만 저장되고 결과지 자리는 빈다")
    void withoutReview_keepsOnlyTheHeadline() throws Exception {
        givenSettlement(ACCOUNT_ID, "4.1895", 1, "[]");
        response = canned(ACCOUNT_ID, "조용한 하루였다.");

        dailyReports.generate(SEASON_DAY);
        awaitRequest();

        // 누적 집계를 싣지 않으면 AI 가 결과지를 만들지 않는다. 그때도 복기는 열려야 한다.
        assertThat(reviewRow(ACCOUNT_ID).get("investor_type")).isNull();
        assertThat(headline(SEASON_DAY, ACCOUNT_ID)).isEqualTo("조용한 하루였다.");
    }

    @Test
    @DisplayName("3단_결과지의 유형이 비어 오면_그 결과지만 버리고 머리말은 남는다")
    void unusableReview_dropsOnlyTheReview() throws Exception {
        givenSettlement(ACCOUNT_ID, "4.1895", 1, "[]");
        // investorType 은 비어 오지 않는다는 계약이다. 비면 우리가 채울 것이 없다.
        response = cannedWithReview(ACCOUNT_ID, "머리말은 남는다.")
                .replace("\"investorType\":\"LEVEL_2\"", "\"investorType\":null");

        dailyReports.generate(SEASON_DAY);
        awaitRequest();

        assertThat(headline(SEASON_DAY, ACCOUNT_ID)).isEqualTo("머리말은 남는다.");
        assertThat(reviewRow(ACCOUNT_ID).get("investor_type")).isNull();
    }

    private Map<String, Object> reviewRow(long accountId) {
        return jdbc.queryForMap("""
                SELECT investor_type, type_name, profile_level, profile_total, profile_name,
                       self_level, profile_match, profile_items::text, narrative::text
                FROM daily_report_texts WHERE season_day = ? AND account_id = ?
                """, SEASON_DAY, accountId);
    }

    private static String canned(long accountId, String headline) {
        return """
                {"success":true,"status":200,"message":"OK","data":{"seasonDay":1,"reports":[
                  {"accountId":%d,"headline":"%s","sceneAliases":["보통의 선택"],
                   "blockIds":["HEAD_ABOVE_OVERTRADE_1"]}]},
                 "timestamp":"2026-09-09T08:05:27.558826Z"}
                """.formatted(accountId, headline);
    }

    /** 문구 둘을 담은 응답. AI 가 묻지 않은 계좌를 섞어 보내는 경우를 만든다. */
    private static String twoReports(long first, String firstHeadline, long second,
                                     String secondHeadline) {
        return """
                {"success":true,"status":200,"message":"OK","data":{"seasonDay":1,"reports":[
                  {"accountId":%d,"headline":"%s","sceneAliases":[],"blockIds":[]},
                  {"accountId":%d,"headline":"%s","sceneAliases":[],"blockIds":[]}]},
                 "timestamp":"2026-09-09T08:05:27.558826Z"}
                """.formatted(first, firstHeadline, second, secondHeadline);
    }

    /** 저장된 태그 한 항목. 폐장 집계가 쓰는 모양 그대로다. */
    private String tag(String kind, long executionId, String symbol, String changeRate) {
        return """
                {"kind":"%s","executionId":%d,"instrumentId":%d,"changeRate":%s}"""
                .formatted(kind, executionId, instrumentId(symbol), changeRate);
    }

    private static String tags(String... items) {
        return "[" + String.join(",", items) + "]";
    }

    private String headline(long accountId) {
        return headline(SEASON_DAY, accountId);
    }

    private String headline(short seasonDay, long accountId) {
        return jdbc.queryForObject(
                "SELECT headline FROM daily_report_texts WHERE season_day = ? AND account_id = ?",
                String.class, seasonDay, accountId);
    }

    private String blockIds(long accountId) {
        return jdbc.queryForObject(
                "SELECT block_ids::text FROM daily_report_texts WHERE season_day = ? AND account_id = ?",
                String.class, SEASON_DAY, accountId);
    }

    private int textCount() {
        return jdbc.queryForObject("SELECT count(*) FROM daily_report_texts WHERE season_day = ?",
                Integer.class, SEASON_DAY);
    }

    private Long instrumentId(String symbol) {
        return jdbc.queryForObject("SELECT id FROM instruments WHERE symbol = ?", Long.class,
                symbol);
    }

    /** 전날 저장된 문구. 블록 목록만 쓰이므로 나머지는 자리만 채운다. */
    private void givenDailyText(short seasonDay, long accountId, String blockIds) {
        jdbc.update("""
                INSERT INTO daily_report_texts (season_day, account_id, headline, scene_aliases,
                                                block_ids, generated_at)
                VALUES (?, ?, '어제 문구', CAST('[]' AS JSONB), CAST(? AS JSONB), now())
                """, seasonDay, accountId, blockIds);
    }

    private void givenSettlement(long accountId, String dailyReturn, int executionCount,
                                 String patternTags) {
        givenSettlement(accountId, dailyReturn, executionCount, patternTags, "0", "0", null, null);
    }

    private void givenSettlement(long accountId, String dailyReturn, int executionCount,
                                 String patternTags, String totalFeeKrw, String realizedPnlKrw,
                                 Long bestExecutionId, Long worstExecutionId) {
        givenSettlement(SEASON_DAY, accountId, dailyReturn, executionCount, patternTags,
                totalFeeKrw, realizedPnlKrw, bestExecutionId, worstExecutionId);
    }

    private void givenSettlement(short seasonDay, long accountId, String dailyReturn,
                                 int executionCount, String patternTags, String totalFeeKrw,
                                 String realizedPnlKrw, Long bestExecutionId,
                                 Long worstExecutionId) {
        jdbc.update("""
                INSERT INTO daily_settlements (season_day, account_id, season_rank, daily_rank,
                                               season_return, daily_return, total_valuation_krw,
                                               execution_count, total_fee_krw, realized_pnl_krw,
                                               best_execution_id, worst_execution_id, pattern_tags,
                                               finalized_at)
                VALUES (?, ?, 1, 1, 0, CAST(? AS NUMERIC), 100000000, ?, CAST(? AS NUMERIC),
                        CAST(? AS NUMERIC), ?, ?, CAST(? AS JSONB), now())
                """, seasonDay, accountId, dailyReturn, executionCount, totalFeeKrw,
                realizedPnlKrw, bestExecutionId, worstExecutionId, patternTags);
    }

    /**
     * 체결 한 건. 태그와 상·하위가 체결 식별자로 가리키므로 만든 식별자를 돌려준다.
     *
     * <p>대금은 단가 × 수량이다 — 거래대금과 집중도가 이 값으로 계산된다.
     */
    private long givenExecution(long accountId, String symbol, String side, long tickSeq,
                                int quantity, String price, String realizedPnl) {
        return jdbc.queryForObject("""
                INSERT INTO executions (account_id, instrument_id, order_id, order_type, side,
                                        quantity, executed_price, currency, amount, applied_fee_rate,
                                        fee, realized_pnl, tick_seq, game_date, executed_at)
                SELECT ?, i.id, NULL, 'MARKET', ?, ?, CAST(? AS NUMERIC), i.currency,
                       CAST(? AS NUMERIC) * ?, 0, 0, CAST(? AS NUMERIC), t.tick_seq, t.game_date,
                       now()
                FROM instruments i, game_ticks t
                WHERE i.symbol = ? AND t.tick_seq = ?
                RETURNING id
                """, Long.class, accountId, side, quantity, price, price, quantity, realizedPnl,
                symbol, tickSeq);
    }

    private void givenAccount(long accountId, long userId) {
        jdbc.update("""
                INSERT INTO accounts (id, user_id, season_id, krw_cash, usd_cash, locked_krw, locked_usd,
                                      starting_balance_krw, index_adjusted, joined_season_day,
                                      daily_base_valuation_krw, daily_base_season_day,
                                      last_acked_tick_seq, created_at)
                VALUES (?, ?, ?, 45000000, 0, 0, 0, 100000000, false, 1, 100000000, 1, 0, now())
                """, accountId, userId, ReplayFixture.SEASON_ID);
    }
}

package com.ssafy.a605.backend.domain.replay;

import static com.ssafy.a605.backend.support.SessionSupport.CSRF_COOKIE_NAME;
import static com.ssafy.a605.backend.support.SessionSupport.CSRF_HEADER_NAME;
import static com.ssafy.a605.backend.support.SessionSupport.csrfToken;
import static com.ssafy.a605.backend.support.ReplayFixture.awaitQuiet;
import static com.ssafy.a605.backend.support.ReplayFixture.awaitUntil;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;

import com.ssafy.a605.backend.domain.user.entity.User;
import com.ssafy.a605.backend.global.security.CookieOrHeaderHttpSessionIdResolver;
import com.ssafy.a605.backend.support.AccountFixture;
import com.ssafy.a605.backend.support.ContainersConfig;
import com.ssafy.a605.backend.support.MutableClock;
import com.ssafy.a605.backend.support.ReplayFixture;
import com.ssafy.a605.backend.support.SessionSupport;

import jakarta.servlet.http.Cookie;

/**
 * 이동 지점 목록과 지점 이동(Seek · Warp)을 관리자 REST 경계에서 검증한다. 어떤 서비스가
 * 불렸는지가 아니라 <b>응답 · 이동 뒤 저장된 진행 · 초기화 여부</b>만 본다.
 *
 * <p>Warp 는 벽시계를 기다리지 않으므로 대부분의 검증에 시간 조작이 필요하지 않다 — 그것이
 * 이 기능의 요점이다. 시계는 다른 재생 테스트와 같은 이유로 {@link MutableClock} 이 쥔다.
 *
 * <p>실시간 수신을 보지 않으므로 웹 서버를 띄우지 않는다 — {@code MockMvc} 로 충분하고,
 * 띄우면 이 클래스만을 위한 Tomcat 이 컨텍스트마다 하나 더 뜬다.
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
@Import({ContainersConfig.class, SessionSupport.class, ReplayFixture.class, AccountFixture.class,
        MutableClock.Config.class})
class ReplaySeekApiTest {

    private static final String REPLAY_URI = "/api/v1/admin/replay";
    private static final String MARKS_URI = REPLAY_URI + "/marks";
    private static final String SEEK_URI = REPLAY_URI + "/seek";
    private static final String AUTH_TOKEN_HEADER = CookieOrHeaderHttpSessionIdResolver.HEADER_NAME;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SessionSupport sessions;

    @Autowired
    private ReplayFixture fixture;

    @Autowired
    private AccountFixture accounts;

    @Autowired
    private MutableClock clock;

    @Autowired
    private JdbcTemplate jdbc;

    private String admin;

    @BeforeEach
    void resetReplay() {
        fixture.reset(clock);
        admin = sessions.createSessionFor(fixture.admin());
    }

    @Nested
    @DisplayName("이동 지점 목록")
    class Marks {

        @Test
        @DisplayName("목록 조회_최초 상태_일차 경계 7개와 월 첫 틱과 일차 마지막 틱이 모두 있다")
        void marks_containsBoundariesMonthsAndDayLastTicks() throws Exception {
            List<Map<String, Object>> marks = marks();

            assertThat(keys(marks)).contains(
                    "day1.briefing", "day1.open", "day1.feeFreeStart", "day1.feeFreeEnd",
                    "day1.blackout", "day1.close", "day1.announce",
                    "day2.briefing", "day3.announce",
                    "month.2020-01", "month.2022-03", "month.2022-04", "month.2024-06",
                    "month.2024-07", "month.2026-08",
                    "day1.lastTick", "day2.lastTick", "day3.lastTick");
        }

        @Test
        @DisplayName("목록 조회_편성과 대조_일차와 가상 위치가 편성값과 같다")
        void marks_carryScheduledCoordinates() throws Exception {
            Map<String, Object> blackout = mark("day2.blackout");
            assertThat(blackout.get("seasonDay")).isEqualTo(2);
            assertThat(blackout.get("offsetSec")).isEqualTo(ReplayFixture.BLACKOUT_START);

            // 3일차는 1·2일차보다 이른 틱에서 끝난다. 상수로 둘 수 없는 값이다.
            assertThat(mark("day1.lastTick").get("offsetSec")).isEqualTo(ReplayFixture.LAST_TICK_DAY_1_2);
            assertThat(mark("day3.lastTick").get("offsetSec")).isEqualTo(ReplayFixture.LAST_TICK_DAY_3);
        }

        @Test
        @DisplayName("목록 조회_갈래_화면이 식별자를 파싱하지 않게 종류를 함께 준다")
        void marks_carryTheirKind() throws Exception {
            assertThat(mark("day1.close").get("kind")).isEqualTo("BOUNDARY");
            assertThat(mark("month.2020-01").get("kind")).isEqualTo("MONTH");
            assertThat(mark("day1.lastTick").get("kind")).isEqualTo("DAY_LAST_TICK");
        }

        @Test
        @DisplayName("목록 조회_시간 경계와 가격 틱_경계만 틱 번호가 없다")
        void marks_tickSeqIsNullOnlyForTimeBoundaries() throws Exception {
            assertThat(mark("day1.close").get("tickSeq")).isNull();
            assertThat(mark("day1.announce").get("tickSeq")).isNull();
            assertThat(mark("month.2020-01").get("tickSeq")).isEqualTo(1);
            assertThat(mark("day1.lastTick").get("tickSeq")).isEqualTo(4);
        }

        @Test
        @DisplayName("목록 조회_공모 편성이 있으면_공시·마감·상장이 각각 한 지점으로 나온다")
        void marks_containIpoPhases() throws Exception {
            long instrumentId = jdbc.queryForObject(
                    "SELECT id FROM instruments WHERE symbol='REPLAY_TEST_D'", Long.class);
            jdbc.update("""
                    INSERT INTO ipo_offerings (season_id, instrument_id, currency, offer_price, deposit_amount,
                        disclosure_tick_seq, close_tick_seq, listing_tick_seq, allocation_rule)
                    VALUES (?, ?, 'KRW', 10000, 100000, 1, 2, 3, '청약자의 30%')
                    """, ReplayFixture.SEASON_ID, instrumentId);
            try {
                Map<String, Object> mark = marks().stream()
                        .filter(row -> String.valueOf(row.get("key")).endsWith(".close"))
                        .filter(row -> String.valueOf(row.get("key")).startsWith("ipo"))
                        .findFirst().orElseThrow(() -> new AssertionError("공모 마감 지점이 없다"));

                assertThat(mark.get("kind")).isEqualTo("IPO");
                assertThat(mark.get("label")).asString().contains("검증 종목 D").contains("청약 마감");
                assertThat(mark.get("tickSeq")).isEqualTo(2);
                assertThat(mark.get("offsetSec")).isEqualTo(ReplayFixture.SECOND_TICK);
                assertThat(keys(marks())).filteredOn(key -> key.startsWith("ipo")).hasSize(3);
            } finally {
                jdbc.update("DELETE FROM ipo_offerings WHERE season_id=?", ReplayFixture.SEASON_ID);
            }
        }

        @Test
        @DisplayName("목록 조회_진행 전후_지나간 항목의 구분이 진행을 따라 바뀐다")
        void marks_passedFollowsProgress() throws Exception {
            assertThat(mark("month.2020-01").get("passed")).isEqualTo(false);

            seek("{\"markKey\":\"day1.lastTick\",\"ticksBefore\":0}").andExpect(status().isOk());
            awaitPublished(4L);

            assertThat(mark("month.2020-01").get("passed")).isEqualTo(true);
            assertThat(mark("day2.briefing").get("passed")).isEqualTo(false);
        }

        @Test
        @DisplayName("목록 조회_배속 0 인 정지 상태_진행을 전진시키지 않는다")
        void marks_doNotAdvanceProgress() throws Exception {
            marks();
            marks();
            awaitQuiet(() -> fixture.row().get("last_published_tick_seq"));

            assertThat(fixture.row().get("last_published_tick_seq")).isNull();
            assertThat(fixture.row().get("last_boundary_offset_sec")).isNull();
        }
    }

    @Nested
    @DisplayName("앞으로 이동 (Warp)")
    class Forward {

        @Test
        @DisplayName("앞으로 이동_당일 목표_목표에 정지하고 넘어가지 않는다")
        void forward_stopsAtTargetWithoutOvershooting() throws Exception {
            seek("{\"tickSeq\":2}").andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.targetSeasonDay").value(1))
                    .andExpect(jsonPath("$.data.targetTickSeq").value(2))
                    .andExpect(jsonPath("$.data.reseeded").value(false));

            awaitPublished(2L);
            awaitQuiet(() -> fixture.row().get("last_published_tick_seq"));

            assertThat(fixture.row().get("last_published_tick_seq")).isEqualTo(2L);
            assertThat(fixture.row().get("anchor_offset_sec"))
                    .isEqualTo(BigDecimal.valueOf(ReplayFixture.SECOND_TICK).setScale(6));
        }

        @Test
        @DisplayName("앞으로 이동_경유 구간_공개 기준 틱이 목표까지 순차 전진하고 경계를 전부 지난다")
        void forward_processesEveryPassedItem() throws Exception {
            seek("{\"markKey\":\"day1.close\",\"ticksBefore\":0}").andExpect(status().isOk());
            awaitUntil(() -> Integer.valueOf(ReplayFixture.CLOSE).equals(fixture.row().get("last_boundary_offset_sec")),
                    () -> "폐장 경계까지 진행하지 않았다: " + fixture.row());

            // 당일 틱 4건이 하나도 빠지지 않았다. 건너뛴 틱이 있으면 워터마크가 4 에 못 미친다.
            assertThat(fixture.row().get("last_published_tick_seq")).isEqualTo(4L);
            assertThat(day(fixture.row(), "last_boundary_season_day")).isEqualTo(1);
        }

        @Test
        @DisplayName("앞으로 이동_다음 일차 목표_일차 전환을 지나 목표 일차에 도달한다")
        void forward_crossesSeasonDayBoundary() throws Exception {
            seek("{\"markKey\":\"day2.open\",\"ticksBefore\":0}").andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.targetSeasonDay").value(2))
                    .andExpect(jsonPath("$.data.reseeded").value(false));

            awaitUntil(() -> day(fixture.row(), "anchor_season_day") == 2,
                    () -> "2일차로 전환하지 않았다: " + fixture.row());
            awaitQuiet(() -> fixture.row().get("last_boundary_offset_sec"));

            Map<String, Object> row = fixture.row();
            // 1일차 틱 4건을 전부 지나고 2일차 개장 경계에서 멈춘다.
            assertThat(row.get("last_published_tick_seq")).isEqualTo(4L);
            assertThat(day(row, "last_boundary_season_day")).isEqualTo(2);
            assertThat(row.get("last_boundary_offset_sec")).isEqualTo(ReplayFixture.TRADING_OPEN);
        }

        @Test
        @DisplayName("앞으로 이동_같은 요청을 다시_초기화하지 않고 그 자리를 유지한다")
        void forward_repeatedRequestNeitherReseedsNorRewinds() throws Exception {
            seek("{\"tickSeq\":3}").andExpect(status().isOk());
            awaitPublished(3L);

            // 도달하지 못하고 멈춘 이동을 관리자가 다시 누르는 경로가 이것이다. 여기서
            // 초기화가 일어나면 "앞으로는 이어서 진행한다"가 깨지고 세팅해 둔 계좌가 날아간다.
            seek("{\"tickSeq\":3}").andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.reseeded").value(false));
            awaitQuiet(() -> fixture.row().get("last_published_tick_seq"));

            assertThat(fixture.row().get("last_published_tick_seq")).isEqualTo(3L);
            assertThat(((Number) fixture.row().get("replay_epoch")).longValue()).isZero();
        }

        @Test
        @DisplayName("앞으로 이동_계좌 보유_현금과 확인한 틱이 유지되고 초기화 여부가 거짓이다")
        void forward_keepsAccounts() throws Exception {
            User owner = fixture.user("앞으로이동");
            accounts.create(owner.getId(), (short) 1, 2L, "12345678");

            seek("{\"tickSeq\":3}").andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.reseeded").value(false));
            awaitPublished(3L);

            Map<String, Object> account = accounts.rowOf(owner.getId());
            assertThat(account).isNotNull();
            assertThat((BigDecimal) account.get("krw_cash")).isEqualByComparingTo("12345678");
            assertThat(account.get("last_acked_tick_seq")).isEqualTo(2L);
            accounts.deleteAll(owner.getId());
        }

        @Test
        @DisplayName("앞으로 이동_수수료 무료 구간 안과 종료 지점_시계 플래그가 갈린다")
        void forward_landsInsideAndOutsideFeeFreeWindow() throws Exception {
            seek("{\"markKey\":\"day1.feeFreeStart\",\"ticksBefore\":0}").andExpect(status().isOk());
            awaitUntil(this::feeFree, () -> "수수료 무료 구간에 들어가지 않았다");

            seek("{\"markKey\":\"day1.feeFreeEnd\",\"ticksBefore\":0}").andExpect(status().isOk());
            awaitUntil(() -> !feeFree(), () -> "수수료 무료 구간을 빠져나오지 않았다");
        }

        @Test
        @DisplayName("앞으로 이동_암전 구간_리더보드 암전 플래그가 켜진다")
        void forward_landsInsideBlackout() throws Exception {
            seek("{\"markKey\":\"day1.blackout\",\"ticksBefore\":0}").andExpect(status().isOk());
            awaitUntil(() -> Boolean.TRUE.equals(snapshot("leaderboardBlackout")),
                    () -> "암전 구간에 들어가지 않았다");
        }

        private boolean feeFree() {
            return Boolean.TRUE.equals(snapshot("feeFree"));
        }
    }

    @Nested
    @DisplayName("착지 지점 (ticksBefore)")
    class Landing {

        @Test
        @DisplayName("이동_틱 수를 생략_기본 3 Tick 앞에 착지한다")
        void ticksBefore_defaultsToThree() throws Exception {
            seek("{\"markKey\":\"day1.lastTick\"}").andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.targetTickSeq").value(1));
        }

        @Test
        @DisplayName("이동_틱 수를 지정_그만큼 앞선 틱에 착지한다")
        void ticksBefore_honoursExplicitValue() throws Exception {
            seek("{\"markKey\":\"day1.lastTick\",\"ticksBefore\":1}").andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.targetTickSeq").value(3));
        }

        @Test
        @DisplayName("이동_시간 경계에 틱 수를 지정_그 경계보다 앞선 틱들에서 센다")
        void ticksBefore_onTimeBoundaryCountsPrecedingTicks() throws Exception {
            // 1일차 폐장(20100) 앞선 틱은 1·2·3·4 다. 2개 뒤는 3 번 틱이다.
            seek("{\"markKey\":\"day1.close\",\"ticksBefore\":2}").andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.targetTickSeq").value(3))
                    .andExpect(jsonPath("$.data.targetOffsetSec").value(ReplayFixture.THIRD_TICK));
        }

        @Test
        @DisplayName("이동_틱 수 0_경계 지점 자체에 선다")
        void ticksBefore_zeroLandsOnTheMarkItself() throws Exception {
            seek("{\"markKey\":\"day1.close\",\"ticksBefore\":0}").andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.targetOffsetSec").value(ReplayFixture.CLOSE))
                    .andExpect(jsonPath("$.data.targetTickSeq").doesNotExist());
        }
    }

    @Nested
    @DisplayName("뒤로 이동 (초기화 후 재진행)")
    class Backward {

        @Test
        @DisplayName("뒤로 이동_진행한 뒤 앞 지점 지정_초기화 여부가 참이고 세대가 오른다")
        void backward_reseedsAndBumpsEpoch() throws Exception {
            seek("{\"tickSeq\":3}").andExpect(status().isOk());
            awaitPublished(3L);
            long epoch = ((Number) fixture.row().get("replay_epoch")).longValue();

            seek("{\"tickSeq\":1}").andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.reseeded").value(true));
            awaitPublished(1L);

            assertThat(((Number) fixture.row().get("replay_epoch")).longValue()).isEqualTo(epoch + 1);
        }

        @Test
        @DisplayName("뒤로 이동_계좌 보유_행은 남고 컬럼이 시작 상태로 돌아간다")
        void backward_resetsAccountColumnsWithoutDeletingRows() throws Exception {
            User owner = fixture.user("뒤로이동");
            long accountId = accounts.create(owner.getId(), (short) 2, 3L, "7777");

            seek("{\"tickSeq\":2}").andExpect(status().isOk());
            awaitPublished(2L);
            seek("{\"tickSeq\":1}").andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.reseeded").value(true));

            Map<String, Object> account = accounts.rowOf(owner.getId());
            assertThat(account).isNotNull();
            assertThat(account.get("id")).isEqualTo(accountId);
            assertThat((BigDecimal) account.get("krw_cash")).isEqualByComparingTo(AccountFixture.SEED_KRW);
            assertThat((BigDecimal) account.get("locked_krw")).isEqualByComparingTo("0");
            assertThat(day(account, "joined_season_day")).isEqualTo(1);
            // 되돌리지 않으면 부재 판정의 기준이 미래를 가리켜 부재 요약이 조용히 죽는다.
            assertThat(account.get("last_acked_tick_seq")).isEqualTo(0L);
            accounts.deleteAll(owner.getId());
        }

        @Test
        @DisplayName("뒤로 이동_재생 상태 행_지우지 않고 컬럼만 되돌린다")
        void backward_keepsReplayStateRow() throws Exception {
            seek("{\"tickSeq\":3}").andExpect(status().isOk());
            awaitPublished(3L);

            seek("{\"tickSeq\":1}").andExpect(status().isOk());

            assertThat(jdbc.queryForObject("SELECT count(*) FROM replay_state WHERE season_id=?",
                    Integer.class, ReplayFixture.SEASON_ID)).isEqualTo(1);
            mockMvc.perform(get(REPLAY_URI).header(AUTH_TOKEN_HEADER, admin)).andExpect(status().isOk());
        }

        @Test
        @DisplayName("뒤로 이동_시즌 편성_재생 일정과 종목과 종가를 보존한다")
        void backward_preservesScheduleData() throws Exception {
            int ticks = count("game_ticks");
            int instruments = count("instruments");
            int closes = count("daily_closes");

            seek("{\"tickSeq\":2}").andExpect(status().isOk());
            awaitPublished(2L);
            seek("{\"tickSeq\":1}").andExpect(status().isOk());

            assertThat(count("game_ticks")).isEqualTo(ticks);
            assertThat(count("instruments")).isEqualTo(instruments);
            assertThat(count("daily_closes")).isEqualTo(closes);
        }

        @Test
        @DisplayName("뒤로 이동_시즌 밖의 것_사용자와 관심종목과 문의를 보존한다")
        void backward_preservesDataOutsideTheSeason() throws Exception {
            User owner = fixture.user("시즌밖");
            long instrumentId = jdbc.queryForObject(
                    "SELECT id FROM instruments WHERE symbol='REPLAY_TEST_A'", Long.class);
            jdbc.update("INSERT INTO watchlists (user_id, instrument_id, created_at) VALUES (?, ?, now())",
                    owner.getId(), instrumentId);
            jdbc.update("""
                    INSERT INTO inquiries (user_id, title, content, status, created_at, updated_at)
                    VALUES (?, '문의 제목', '문의 내용', 'OPEN', now(), now())
                    """, owner.getId());

            seek("{\"tickSeq\":2}").andExpect(status().isOk());
            awaitPublished(2L);
            seek("{\"tickSeq\":1}").andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.reseeded").value(true));

            // 관심종목은 계좌가 아니라 User 에 붙는 취향이라 시즌 초기화 대상이 아니다.
            assertThat(countOf("watchlists", owner.getId())).isEqualTo(1);
            assertThat(countOf("inquiries", owner.getId())).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE id=?", Integer.class, owner.getId()))
                    .isEqualTo(1);

            jdbc.update("DELETE FROM watchlists WHERE user_id=?", owner.getId());
            jdbc.update("DELETE FROM inquiries WHERE user_id=?", owner.getId());
        }

        @Test
        @DisplayName("뒤로 이동_경유한 공모_추첨이 다시 일어난다")
        void backward_rearmsOfferingsSoTheDrawHappensAgain() throws Exception {
            long instrumentId = jdbc.queryForObject(
                    "SELECT id FROM instruments WHERE symbol='REPLAY_TEST_D'", Long.class);
            jdbc.update("""
                    INSERT INTO ipo_offerings (season_id, instrument_id, currency, offer_price, deposit_amount,
                        disclosure_tick_seq, close_tick_seq, listing_tick_seq, allocation_rule)
                    VALUES (?, ?, 'KRW', 10000, 100000, 1, 2, 3, '청약자의 30%')
                    """, ReplayFixture.SEASON_ID, instrumentId);
            try {
                seek("{\"tickSeq\":3}").andExpect(status().isOk());
                awaitUntil(this::drawn, () -> "마감 틱을 지나도 추첨이 일어나지 않았다");

                seek("{\"tickSeq\":1}").andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.reseeded").value(true));
                awaitPublished(1L);
                // 결과만 남으면 마감 틱을 다시 지나도 재추첨이 건너뛰어진다.
                assertThat(drawn()).isFalse();

                seek("{\"tickSeq\":3}").andExpect(status().isOk());
                awaitUntil(this::drawn, () -> "초기화 뒤 다시 지난 마감 틱에서 추첨이 일어나지 않았다");
            } finally {
                jdbc.update("DELETE FROM ipo_subscriptions WHERE offering_id IN "
                        + "(SELECT id FROM ipo_offerings WHERE season_id=?)", ReplayFixture.SEASON_ID);
                jdbc.update("DELETE FROM ipo_offerings WHERE season_id=?", ReplayFixture.SEASON_ID);
            }
        }

        private boolean drawn() {
            return jdbc.queryForObject("SELECT count(*) FROM ipo_offerings WHERE season_id=? AND drawn_at IS NOT NULL",
                    Integer.class, ReplayFixture.SEASON_ID) > 0;
        }

        private int count(String table) {
            return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
        }

        private int countOf(String table, Long userId) {
            return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE user_id=?", Integer.class, userId);
        }
    }

    @Nested
    @DisplayName("입력 거절과 경로 보호")
    class Rejection {

        @Test
        @DisplayName("이동_지점과 틱 번호를 함께 지정_400 이다")
        void seek_withBothCoordinates_isRejected() throws Exception {
            seek("{\"markKey\":\"day1.open\",\"tickSeq\":2}").andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("이동_아무 좌표도 없으면_400 이다")
        void seek_withoutCoordinate_isRejected() throws Exception {
            seek("{}").andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("이동_없는 지점과 범위 밖 틱과 음수 틱 수_400 이다")
        void seek_withInvalidTarget_isRejected() throws Exception {
            seek("{\"markKey\":\"day9.open\"}").andExpect(status().isBadRequest());
            seek("{\"tickSeq\":9999}").andExpect(status().isBadRequest());
            seek("{\"markKey\":\"day1.close\",\"ticksBefore\":-1}").andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("이동_일반 사용자의 세션_403 이고 진행도 바뀌지 않는다")
        void seek_asNonAdmin_returnsForbidden() throws Exception {
            mockMvc.perform(post(SEEK_URI).header(AUTH_TOKEN_HEADER,
                            sessions.createSessionFor(fixture.user("일반")))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"tickSeq\":2}"))
                    .andExpect(status().isForbidden());

            awaitQuiet(() -> fixture.row().get("last_published_tick_seq"));
            assertThat(fixture.row().get("last_published_tick_seq")).isNull();
        }

        @Test
        @DisplayName("이동_쿠키 인증에 위조 방지 토큰이 없으면_거부되고 진행도 바뀌지 않는다")
        void seek_withCookieSessionAndNoCsrfToken_isRejected() throws Exception {
            Cookie session = sessions.sessionCookie(admin);
            mockMvc.perform(post(SEEK_URI).cookie(session)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"tickSeq\":2}"))
                    .andExpect(status().isForbidden());

            String token = csrfToken(mockMvc.perform(get(REPLAY_URI).cookie(session)).andReturn());
            mockMvc.perform(post(SEEK_URI).cookie(session, new Cookie(CSRF_COOKIE_NAME, token))
                            .header(CSRF_HEADER_NAME, token)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"tickSeq\":2}"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("이동 지점 목록_세션이 없으면_401 이다")
        void marks_withoutSession_returnsUnauthorized() throws Exception {
            mockMvc.perform(get(MARKS_URI)).andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @DisplayName("평상시 정지")
    class Paused {

        @Test
        @DisplayName("정지_이동을 요청하지 않은 상태_위치와 공개 기준 틱이 그대로다")
        void paused_withoutSeek_doesNotProgress() throws Exception {
            awaitQuiet(() -> fixture.row().get("anchor_offset_sec"));

            Map<String, Object> row = fixture.row();
            assertThat(row.get("last_published_tick_seq")).isNull();
            assertThat(row.get("last_boundary_offset_sec")).isNull();
            assertThat(row.get("anchor_offset_sec"))
                    .isEqualTo(BigDecimal.valueOf(ReplayFixture.BRIEFING).setScale(6));
            assertThat(day(row, "anchor_season_day")).isEqualTo(1);
        }

        @Test
        @DisplayName("정지_정산 발표에 도달한 뒤_다음 일차로 넘어가지 않는다")
        void paused_atAnnounce_doesNotRollOverToTheNextDay() throws Exception {
            seek("{\"markKey\":\"day1.announce\",\"ticksBefore\":0}").andExpect(status().isOk());
            awaitUntil(() -> Integer.valueOf(ReplayFixture.ANNOUNCE).equals(fixture.row().get("last_boundary_offset_sec")),
                    () -> "정산 발표 경계까지 진행하지 않았다: " + fixture.row());
            awaitQuiet(() -> fixture.row().get("anchor_season_day"));

            assertThat(day(fixture.row(), "anchor_season_day")).isEqualTo(1);
        }
    }

    private org.springframework.test.web.servlet.ResultActions seek(String body) throws Exception {
        return mockMvc.perform(post(SEEK_URI).header(AUTH_TOKEN_HEADER, admin)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private List<Map<String, Object>> marks() throws Exception {
        String body = mockMvc.perform(get(MARKS_URI).header(AUTH_TOKEN_HEADER, admin))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.data");
    }

    private Map<String, Object> mark(String key) throws Exception {
        return marks().stream().filter(mark -> key.equals(mark.get("key"))).findFirst()
                .orElseThrow(() -> new AssertionError("이동 지점이 목록에 없다: " + key));
    }

    private Object snapshot(String field) {
        try {
            String body = mockMvc.perform(get(REPLAY_URI).header(AUTH_TOKEN_HEADER, admin))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            return JsonPath.read(body, "$.data." + field);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void awaitPublished(long tickSeq) {
        awaitUntil(() -> Long.valueOf(tickSeq).equals(fixture.row().get("last_published_tick_seq")),
                () -> "틱 " + tickSeq + " 까지 진행하지 않았다: " + fixture.row());
    }

    /** 드라이버가 {@code smallint} 를 무엇으로 주는지에 테스트가 걸리지 않게 한다. */
    private static int day(Map<String, Object> row, String column) {
        return ((Number) row.get(column)).intValue();
    }

    private List<String> keys(List<Map<String, Object>> marks) {
        return marks.stream().map(mark -> (String) mark.get("key")).toList();
    }
}

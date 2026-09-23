package com.ssafy.a605.backend.domain.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import com.ssafy.a605.backend.domain.ipo.dto.IpoDrawnEvent;
import com.ssafy.a605.backend.domain.ipo.dto.IpoSubscriptionResponse.Result;
import com.ssafy.a605.backend.domain.ipo.service.IpoDrawService.Awarded;
import com.ssafy.a605.backend.domain.market.entity.Currency;
import com.ssafy.a605.backend.domain.order.dto.ExecutionResponse;
import com.ssafy.a605.backend.domain.order.dto.OpenOrderResponse;
import com.ssafy.a605.backend.domain.order.dto.OrderFilledEvent;
import com.ssafy.a605.backend.domain.order.dto.OrdersExpiredEvent;
import com.ssafy.a605.backend.domain.order.entity.OrderSide;
import com.ssafy.a605.backend.domain.order.entity.OrderType;
import com.ssafy.a605.backend.domain.ranking.dto.AwardsAnnouncedEvent;
import com.ssafy.a605.backend.domain.ranking.dto.AwardsAnnouncedEvent.DailyWinner;
import com.ssafy.a605.backend.domain.replay.dto.PhaseBoundaryEvent;
import com.ssafy.a605.backend.domain.replay.dto.TickPublishedEvent;
import com.ssafy.a605.backend.domain.replay.entity.ReplayState;
import com.ssafy.a605.backend.domain.user.entity.User;
import com.ssafy.a605.backend.support.ContainersConfig;
import com.ssafy.a605.backend.support.MutableClock;
import com.ssafy.a605.backend.support.ReplayFixture;

/**
 * 도메인 사건이 알림 행으로 남는지 본다 ({@code noti.md} §유형).
 *
 * <p><b>이벤트를 직접 발행한다.</b> 체결·취소·추첨을 실제로 일으키는 것은 각 도메인의 테스트가
 * 보고, 여기서 볼 것은 그 신호가 어떤 알림이 되는가다. 실제 스윕을 거치면 검증 대상이
 * 아닌 실패가 섞인다.
 */
@Tag("container")
@SpringBootTest
@TestPropertySource(locations = "classpath:oauth2-test.properties",
        properties = {
                "app.replay.mode=test",
                // 픽스처를 여기서 켠다. 선언하지 않으면 .env 의 REPLAY_FIXTURE_ENABLED 가
                // 결과를 정하고, 그 파일은 버전 관리되지 않아 사람마다 다른 DB 를 본다.
                "app.replay.fixture.enabled=true",
                "app.replay.season-id=1",
                "app.replay.poll-delay-ms=50"
        })
@Import({ContainersConfig.class, ReplayFixture.class, MutableClock.Config.class})
class NotificationPublishListenerTest {

    /** 픽스처 1일차 첫 틱. 게임 거래일 2020-01-02. */
    private static final long FIRST_TICK = 1L;

    private static final long ACCOUNT_ID = 960L;

    /** 픽스처 1일차 첫 틱의 게임 거래일. */
    private static final LocalDate GAME_DATE = LocalDate.of(2020, 1, 2);

    @Autowired
    private ApplicationEventPublisher events;

    @Autowired
    private ReplayFixture fixture;

    @Autowired
    private MutableClock clock;

    @Autowired
    private JdbcTemplate jdbc;

    private User owner;
    private long instrumentId;

    @BeforeEach
    void setUp() {
        fixture.reset(clock);
        owner = fixture.user("알림발행");
        instrumentId = jdbc.queryForObject(
                "SELECT id FROM instruments WHERE symbol = ?", Long.class, "REPLAY_TEST_A");
        givenAccount();
    }

    @AfterEach
    void tearDown() {
        // 전원 공지는 이 클래스가 만든 계좌 밖으로도 나간다. 만드는 곳이 여기뿐이라 유형으로 지운다.
        jdbc.update("DELETE FROM notifications WHERE kind IN ('NOTICE', 'IPO_NOTICE', 'NEW_LISTING')");
        jdbc.update("DELETE FROM notifications WHERE account_id = ?", ACCOUNT_ID);
        jdbc.update("DELETE FROM accounts WHERE id = ?", ACCOUNT_ID);
    }

    @Test
    @DisplayName("예약 체결_체결마다 한 통이_종목명과 함께 남는다")
    void onOrderFilled_recordsOnePerExecution() {
        events.publishEvent(new OrderFilledEvent(ACCOUNT_ID,
                List.of(execution(9001L, OrderSide.BUY, 3, "10100"),
                        execution(9002L, OrderSide.SELL, 1, "10300")),
                FIRST_TICK));

        List<Map<String, Object>> rows = notifications();
        assertThat(rows).hasSize(2);
        // referenceId 가 executions.id 라 알림에서 그 체결로 바로 갈 수 있다.
        assertThat(rows).extracting(row -> ((Number) row.get("reference_id")).longValue())
                .containsExactlyInAnyOrder(9001L, 9002L);
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.get("kind")).isEqualTo("ORDER_FILLED");
            assertThat(((Number) row.get("tick_seq")).longValue()).isEqualTo(FIRST_TICK);
            assertThat((String) row.get("body")).contains("검증 종목 A");
        });
    }

    @Test
    @DisplayName("폐장 자동 취소_예약이 여럿이어도_한 통이고 틱이 비어 있다")
    void onOrdersExpired_recordsOneWithoutTick() {
        events.publishEvent(new OrdersExpiredEvent(ACCOUNT_ID,
                List.of(order(4001L), order(4002L), order(4003L))));

        List<Map<String, Object>> rows = notifications();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("kind")).isEqualTo("ORDER_EXPIRED");
        assertThat((String) rows.get(0).get("body")).contains("3건");
        // 폐장은 16:40 이고 틱 경계와 일치하지 않는다 (noti.md).
        assertThat(rows.get(0).get("tick_seq")).isNull();
        assertThat(((Number) rows.get(0).get("reference_id")).longValue()).isEqualTo(4001L);
    }

    @Test
    @DisplayName("시상_일간 1위와 시즌 우승을 함께 받으면_두 통이 남는다")
    void onAwardsAnnounced_recordsBothTitles() {
        // 마지막 일차에는 한 사람이 둘 다 받을 수 있다. 다른 사건이라 합치지 않는다.
        events.publishEvent(new AwardsAnnouncedEvent((short) 3,
                List.of(new DailyWinner(owner.getId(), 1)), List.of(owner.getId())));

        List<Map<String, Object>> rows = notifications();
        assertThat(rows).hasSize(2);
        assertThat(rows).extracting(row -> (String) row.get("title"))
                .containsExactlyInAnyOrder("오늘의 투자자로 선정됐습니다", "시즌 우승을 축하합니다");
        // 이동 대상이 시상 화면 하나라 referenceId 가 없다.
        assertThat(rows).allSatisfy(row -> assertThat(row.get("reference_id")).isNull());
    }

    @Test
    @DisplayName("시상_계좌가 없는 수상자는_건너뛰고 나머지는 남는다")
    void onAwardsAnnounced_skipsWinnersWithoutAccount() {
        // 발표 전에 탈퇴하면 순위는 얼린 값으로 남지만 보낼 곳이 없다.
        User withdrawn = fixture.user("탈퇴수상자");

        events.publishEvent(new AwardsAnnouncedEvent((short) 1,
                List.of(new DailyWinner(withdrawn.getId(), 1), new DailyWinner(owner.getId(), 2)), List.of()));

        List<Map<String, Object>> rows = notifications();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("kind")).isEqualTo("AWARD");
    }

    /** 1위 문구는 그대로 두고 2~5위만 등수를 밝힌다 (S15P21A605-265). 등수가 없으면 2위가 1위처럼 읽힌다. */
    @Test
    @DisplayName("시상_2위부터는_제목과 본문에 등수가 들어간다")
    void onAwardsAnnounced_runnerUp_namesTheRank() {
        events.publishEvent(new AwardsAnnouncedEvent((short) 2,
                List.of(new DailyWinner(owner.getId(), 4)), List.of()));

        List<Map<String, Object>> rows = notifications();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("title")).isEqualTo("오늘의 투자자 4위에 올랐습니다");
        assertThat(rows.get(0).get("body")).isEqualTo("2일차 일간 수익률 4위입니다.");
    }

    @Test
    @DisplayName("청약 결과_당첨과 낙첨 모두_한 통씩 남는다")
    void onIpoDrawn_recordsBothOutcomes() {
        long offeringId = givenOffering();
        User loser = fixture.user("낙첨자");
        long loserAccount = givenAccountFor(loser, 961L);
        try {
            events.publishEvent(new IpoDrawnEvent(offeringId, List.of(
                    new Awarded(owner.getId(), new Result(true, 12, "10000000", "0", Instant.EPOCH)),
                    new Awarded(loser.getId(), new Result(false, 0, "0", "10000000", Instant.EPOCH)))));

            assertThat(notifications()).hasSize(1);
            assertThat(notifications().get(0).get("kind")).isEqualTo("IPO_RESULT");
            assertThat((String) notifications().get(0).get("title")).isEqualTo("청약에 당첨됐습니다");
            assertThat((String) notifications().get(0).get("body")).contains("검증 종목 A", "12주");

            // 낙첨자에게도 알린다 (기획서 8.4.3). 증거금 반환이 알려야 할 정보다.
            Map<String, Object> lost = jdbc.queryForMap(
                    "SELECT * FROM notifications WHERE account_id = ?", loserAccount);
            assertThat(lost.get("title")).isEqualTo("청약에 당첨되지 않았습니다");
            assertThat(((Number) lost.get("reference_id")).longValue()).isEqualTo(offeringId);
        } finally {
            jdbc.update("DELETE FROM notifications WHERE account_id = ?", loserAccount);
            jdbc.update("DELETE FROM accounts WHERE id = ?", loserAccount);
            jdbc.update("DELETE FROM ipo_offerings WHERE id = ?", offeringId);
        }
    }

    @Test
    @DisplayName("공모 공시_공시 틱에_전원에게 종목명과 함께 남는다")
    void onTickPublished_whenDisclosureTick_noticesEveryone() {
        long offeringId = givenOffering(FIRST_TICK, FIRST_TICK + 2);
        try {
            events.publishEvent(new TickPublishedEvent(FIRST_TICK, GAME_DATE));

            List<Map<String, Object>> rows = notices("IPO_NOTICE");
            assertThat(rows).hasSize(1);
            assertThat(rows.get(0).get("kind")).isEqualTo("IPO_NOTICE");
            assertThat((String) rows.get(0).get("title")).isEqualTo("검증 종목 A 청약이 시작됐습니다");
            assertThat(((Number) rows.get(0).get("tick_seq")).longValue()).isEqualTo(FIRST_TICK);
            // 공지는 고정 화면으로 간다 (noti.md 의 표).
            assertThat(rows.get(0).get("reference_id")).isNull();
        } finally {
            jdbc.update("DELETE FROM ipo_offerings WHERE id = ?", offeringId);
        }
    }

    @Test
    @DisplayName("공모 공시_마감이 바로 다음 틱이어도_공시 한 통만 남는다")
    void onTickPublished_whenCloseFollowsDisclosure_skipsClosingSoon() {
        // 방금 "시작됐습니다" 를 받은 사람에게 같은 틱에 "임박했습니다" 가 또 가면 안 된다.
        long offeringId = givenOffering(FIRST_TICK, FIRST_TICK + 1);
        try {
            events.publishEvent(new TickPublishedEvent(FIRST_TICK, GAME_DATE));

            assertThat(notices("IPO_NOTICE")).hasSize(1);
            assertThat((String) notices("IPO_NOTICE").get(0).get("title")).endsWith("청약이 시작됐습니다");
        } finally {
            jdbc.update("DELETE FROM ipo_offerings WHERE id = ?", offeringId);
        }
    }

    @Test
    @DisplayName("청약 마감 임박_마감 직전 틱에_아직 청약하지 않은 사람에게만 남는다")
    void onTickPublished_whenLastOpenTick_skipsSubscribers() {
        long offeringId = givenOffering(FIRST_TICK, FIRST_TICK + 2);
        User idle = fixture.user("미청약자");
        long idleAccount = givenAccountFor(idle, 962L);
        // 이 계좌는 이미 넣었다. 재촉할 것이 없다.
        jdbc.update("INSERT INTO ipo_subscriptions (offering_id, account_id, deposit_amount, subscribed_at)"
                + " VALUES (?, ?, 10000000, now())", offeringId, ACCOUNT_ID);
        try {
            events.publishEvent(new TickPublishedEvent(FIRST_TICK + 1, GAME_DATE));

            assertThat(notices("IPO_NOTICE")).isEmpty();
            Map<String, Object> sent = jdbc.queryForMap(
                    "SELECT * FROM notifications WHERE account_id = ?", idleAccount);
            assertThat((String) sent.get("title")).isEqualTo("검증 종목 A 청약 마감이 임박했습니다");
        } finally {
            jdbc.update("DELETE FROM notifications WHERE account_id = ?", idleAccount);
            jdbc.update("DELETE FROM ipo_subscriptions WHERE offering_id = ?", offeringId);
            jdbc.update("DELETE FROM accounts WHERE id = ?", idleAccount);
            jdbc.update("DELETE FROM ipo_offerings WHERE id = ?", offeringId);
        }
    }

    @Test
    @DisplayName("신규 상장_상장 거래일 틱에_전원에게 그 종목을 가리키며 남는다")
    void onTickPublished_whenListingDate_noticesEveryoneWithInstrument() {
        // 픽스처의 상장 예정 종목(검증 종목 D)이 이 거래일에 상장한다. 공모 편성은 없다 —
        // 가르지 않는 것이 이 유형의 계약이다.
        long listed = jdbc.queryForObject(
                "SELECT id FROM instruments WHERE symbol = ?", Long.class, "REPLAY_TEST_D");

        events.publishEvent(new TickPublishedEvent(FIRST_TICK + 1, LocalDate.of(2020, 1, 3)));

        List<Map<String, Object>> rows = notices("NEW_LISTING");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("title")).isEqualTo("검증 종목 D 거래가 시작됐습니다");
        // 공지 중 유일하게 이동 대상이 있다. 마켓까지만 데려가면 어느 종목이 새것인지 모른다.
        assertThat(((Number) rows.get(0).get("reference_id")).longValue()).isEqualTo(listed);
        assertThat(((Number) rows.get(0).get("tick_seq")).longValue()).isEqualTo(FIRST_TICK + 1);
    }

    @Test
    @DisplayName("신규 상장_상장일이 아닌 틱에는_남기지 않는다")
    void onTickPublished_whenNotListingDate_recordsNothing() {
        events.publishEvent(new TickPublishedEvent(FIRST_TICK, GAME_DATE));

        assertThat(notices("NEW_LISTING")).isEmpty();
    }

    @Test
    @DisplayName("무료 타임_시작 경계에_전원에게 남고 틱이 비어 있다")
    void onFeeFreeStart_noticesEveryoneWithoutTick() {
        events.publishEvent(new PhaseBoundaryEvent((short) 1, ReplayState.FEE_FREE_START));

        List<Map<String, Object>> rows = notices("NOTICE");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("title")).isEqualTo("수수료 무료 타임이 시작됐습니다");
        assertThat((String) rows.get(0).get("body")).contains("10분");
        // 시계 경계는 틱 경계와 일치하지 않는다 (noti.md).
        assertThat(rows.get(0).get("tick_seq")).isNull();
    }

    @Test
    @DisplayName("무료 타임_다른 시계 경계는_알림을 남기지 않는다")
    void onOtherBoundary_recordsNothing() {
        events.publishEvent(new PhaseBoundaryEvent((short) 1, ReplayState.FEE_FREE_END));
        events.publishEvent(new PhaseBoundaryEvent((short) 1, ReplayState.BLACKOUT_START));

        assertThat(notices("NOTICE")).isEmpty();
    }

    @Test
    @DisplayName("알림 쓰기가 실패해도_예외가 발행한 쪽으로 올라가지 않는다")
    void onOrderFilled_whenWriteFails_doesNotThrow() {
        // 이 설계의 근거다. 예외가 올라가면 체결 스윕의 루프가 끊겨 그 틱의 남은 계좌가
        // 체결되지 않는다. 없는 계좌로 발행해 알림 쓰기를 외래키 위반으로 깨뜨린다.
        long missing = 999_999L;

        events.publishEvent(new OrderFilledEvent(missing,
                List.of(execution(9003L, OrderSide.BUY, 1, "10100")), FIRST_TICK));

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM notifications WHERE account_id = ?", Integer.class, missing))
                .isZero();
    }

    /**
     * 이 계좌에 온 전원 공지. 다른 유형과 섞이지 않게 유형으로 좁힌다 — 공모 공지와 무료
     * 타임은 목적지가 달라 유형도 다르다.
     */
    private List<Map<String, Object>> notices(String kind) {
        return jdbc.queryForList(
                "SELECT * FROM notifications WHERE account_id = ? AND kind = ? ORDER BY id",
                ACCOUNT_ID, kind);
    }

    private List<Map<String, Object>> notifications() {
        return jdbc.queryForList(
                "SELECT * FROM notifications WHERE account_id = ? ORDER BY id", ACCOUNT_ID);
    }

    /** 픽스처 종목을 쓴다. 식별자를 박으면 문구에 다른 종목명이 들어간다. */
    private ExecutionResponse execution(long executionId, OrderSide side, int quantity,
                                        String price) {
        return new ExecutionResponse(executionId, OrderType.LIMIT, side, instrumentId, quantity,
                price, Currency.KRW, price, BigDecimal.ZERO, "0", null, LocalDate.of(2020, 1, 2),
                FIRST_TICK, Instant.EPOCH, null);
    }

    private OpenOrderResponse order(long orderId) {
        return new OpenOrderResponse(orderId, OrderSide.BUY, instrumentId, 1, "10000", Currency.KRW,
                "10000", null, Instant.EPOCH, LocalDate.of(2020, 1, 2), Instant.EPOCH);
    }

    private void givenAccount() {
        givenAccountFor(owner, ACCOUNT_ID);
    }

    private long givenAccountFor(User user, long accountId) {
        jdbc.update("""
                INSERT INTO accounts (id, user_id, season_id, krw_cash, usd_cash, locked_krw, locked_usd,
                                      starting_balance_krw, index_adjusted, joined_season_day,
                                      daily_base_valuation_krw, daily_base_season_day,
                                      last_acked_tick_seq, created_at)
                VALUES (?, ?, ?, 100000000, 0, 0, 0, 100000000, false, 1, 100000000, 1, 0, now())
                """, accountId, user.getId(), ReplayFixture.SEASON_ID);
        return accountId;
    }

    /** 공모 한 건. 종목명을 문구에 넣는 경로만 필요하므로 편성값은 최소로 둔다. */
    private long givenOffering() {
        return givenOffering(1L, 2L);
    }

    /**
     * 공시·마감 틱을 정한 공모 한 건. 전원 공지는 그 두 틱에 걸리므로 편성을 테스트가 정한다.
     *
     * @param disclosureTick 공시 틱
     * @param closeTick      마감 틱. 청약할 수 있는 마지막 틱은 그 직전이다
     */
    private long givenOffering(long disclosureTick, long closeTick) {
        return jdbc.queryForObject("""
                INSERT INTO ipo_offerings (season_id, instrument_id, currency, offer_price,
                                           deposit_amount, disclosure_tick_seq, close_tick_seq,
                                           listing_tick_seq, allocation_rule)
                VALUES (?, ?, 'KRW', 10000, 10000000, ?, ?, ?, '증거금 / 공모가, 정수 내림')
                RETURNING id
                """, Long.class, ReplayFixture.SEASON_ID, instrumentId, disclosureTick, closeTick,
                closeTick + 1);
    }
}

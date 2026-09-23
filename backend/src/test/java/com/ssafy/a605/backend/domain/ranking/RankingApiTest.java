package com.ssafy.a605.backend.domain.ranking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.ssafy.a605.backend.domain.replay.dto.PhaseBoundaryEvent;
import com.ssafy.a605.backend.domain.replay.dto.TickPublishedEvent;
import com.ssafy.a605.backend.domain.replay.entity.ReplayState;
import com.ssafy.a605.backend.domain.user.entity.User;
import com.ssafy.a605.backend.domain.user.service.UserService;
import com.ssafy.a605.backend.global.security.CookieOrHeaderHttpSessionIdResolver;
import com.ssafy.a605.backend.support.ContainersConfig;
import com.ssafy.a605.backend.support.MutableClock;
import com.ssafy.a605.backend.support.ReplayFixture;
import com.ssafy.a605.backend.support.SessionSupport;

/**
 * HTTP 경계에서 리더보드·내 순위의 관찰 가능한 결과만 본다.
 *
 * <p>순위는 {@code RankingRecalculatorTest} 와 같은 방식으로 만든다 — 틱 이벤트를 직접 발행해
 * 실제 스윕이 평가하고 실제 계산기가 순위를 매긴 결과를 읽는다. 그래야 리더보드의 수익률이
 * 계좌 요약의 수익률과 같은 값인지를 같은 테스트에서 교차 검증할 수 있다.
 *
 * <p><b>순위 스냅샷은 프로세스 상태다.</b> 컨텍스트를 공유하는 다른 테스트가 남긴 스냅샷이 등락
 * 계산의 직전 값이 되지 않도록, 계좌를 만들기 전에 빈 틱을 한 번 발행해 기준을 비운다.
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
class RankingApiTest {

    private static final String RANKINGS_URI = "/api/v1/rankings";
    private static final String ME_URI = "/api/v1/rankings/me";
    private static final String AWARDS_URI = "/api/v1/rankings/awards";
    private static final String ACCOUNT_URI = "/api/v1/accounts/me";
    private static final String AUTH_TOKEN_HEADER = CookieOrHeaderHttpSessionIdResolver.HEADER_NAME;

    /** 픽스처 1일차 첫 틱. 게임 거래일 2020-01-02, 환율 1200. 검증 종목 종가 10,100. */
    private static final long FIRST_TICK = 1L;
    private static final LocalDate FIRST_DATE = LocalDate.of(2020, 1, 2);

    /** 픽스처 2일차 첫 틱. 일간 체결 건수가 당일만 세는지 볼 때 쓴다. */
    private static final long DAY_2_TICK = 5L;

    private static final long ACCOUNT_A = 920L;
    private static final long ACCOUNT_B = 921L;
    private static final long ACCOUNT_C = 922L;

    /** 마지막 평가 틱 이후에 합류한 계좌. 어느 틱에서도 평가된 적이 없다. */
    private static final long ACCOUNT_LATE = 923L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SessionSupport sessions;

    @Autowired
    private ReplayFixture fixture;

    @Autowired
    private MutableClock clock;

    @Autowired
    private ApplicationEventPublisher events;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private UserService userService;

    private User ownerA;
    private User ownerB;
    private User ownerC;
    private User ownerLate;
    /** 세션 사용자는 C 다 — 1위라 첫 행에서 {@code isMe} 를 본다. */
    private String sessionC;
    private long krwInstrument;
    private long usdInstrument;

    @BeforeEach
    void setUp() {
        fixture.reset(clock);
        ownerA = fixture.user("랭킹조회A");
        ownerB = fixture.user("랭킹조회B");
        ownerC = fixture.user("랭킹조회C");
        ownerLate = fixture.user("랭킹조회지각");
        sessionC = sessions.createSessionFor(ownerC);
        krwInstrument = instrumentId("REPLAY_TEST_A");
        usdInstrument = instrumentId("REPLAY_TEST_C");
        // 다른 테스트가 남긴 스냅샷을 직전 값으로 삼지 않도록 빈 순위로 덮는다.
        events.publishEvent(new TickPublishedEvent(FIRST_TICK, FIRST_DATE));
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM daily_holdings WHERE account_id IN (?, ?, ?, ?)", ACCOUNT_A, ACCOUNT_B, ACCOUNT_C, ACCOUNT_LATE);
        jdbc.update("DELETE FROM daily_settlements WHERE account_id IN (?, ?, ?, ?)", ACCOUNT_A, ACCOUNT_B, ACCOUNT_C, ACCOUNT_LATE);
        jdbc.update("DELETE FROM executions WHERE account_id IN (?, ?, ?, ?)", ACCOUNT_A, ACCOUNT_B, ACCOUNT_C, ACCOUNT_LATE);
        jdbc.update("DELETE FROM positions WHERE account_id IN (?, ?, ?, ?)", ACCOUNT_A, ACCOUNT_B, ACCOUNT_C, ACCOUNT_LATE);
        // 사건이 알림 행을 남기므로 계좌보다 먼저 지운다 (notifications.account_id FK).
        jdbc.update("DELETE FROM notifications WHERE account_id IN (?, ?, ?, ?)", ACCOUNT_A, ACCOUNT_B, ACCOUNT_C, ACCOUNT_LATE);
        jdbc.update("DELETE FROM accounts WHERE id IN (?, ?, ?, ?)", ACCOUNT_A, ACCOUNT_B, ACCOUNT_C, ACCOUNT_LATE);
    }

    @Nested
    @DisplayName("경로 보호")
    class RouteProtection {

        /**
         * <b>이전 판은 같은 요청에 401 을 단언했다.</b> ADR-0005 가 그 결정을 뒤집었다.
         * 표시할 "내 행" 이 없으므로 어느 행도 {@code isMe} 가 아니다.
         */
        @Test
        @DisplayName("리더보드_세션이 없어도_200 이고_어느 행도 내 행이 아니다")
        void getLeaderboard_withoutSession_returnsOkWithoutMyRow() throws Exception {
            givenThreeRankedAccounts();

            mockMvc.perform(get(RANKINGS_URI))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content", hasSize(3)))
                    .andExpect(jsonPath("$.data.content[?(@.isMe == true)]", hasSize(0)));
        }

        /** 암전은 인증과 무관하다 — 미인증에도 같은 규칙이 걸린다. */
        @Test
        @DisplayName("리더보드_미인증이고 암전 중이면_행이 비어 있다")
        void getLeaderboard_withoutSessionDuringBlackout_hidesRows() throws Exception {
            givenThreeRankedAccounts();
            givenBlackout();

            mockMvc.perform(get(RANKINGS_URI))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.blackout").value(true))
                    .andExpect(jsonPath("$.data.content", hasSize(0)));
        }

        @Test
        @DisplayName("내 순위_세션이 없으면_401 을 받는다")
        void getMyRanking_withoutSession_returnsUnauthorized() throws Exception {
            mockMvc.perform(get(ME_URI))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("내 순위_시즌에 참가하지 않았으면_404 를 받는다")
        void getMyRanking_withoutAccount_returnsNotFound() throws Exception {
            mockMvc.perform(me())
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("시즌 계좌가 없습니다."));
        }

        @Test
        @DisplayName("리더보드_track 이 목록에 없는 값이면_400 을 받는다")
        void getLeaderboard_unknownTrack_returnsBadRequest() throws Exception {
            mockMvc.perform(rankings().param("track", "WEEKLY"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value("track"))
                    .andExpect(jsonPath("$.errors[0].rejectedValue").value("WEEKLY"));
        }
    }

    /**
     * 순위 스냅샷은 메모리에 있어 탈퇴를 모른다. 조회가 빼지 않으면 탈퇴자가 등수와 수익률을
     * 단 채 <b>이름 없는 행</b>으로 남고, 3일차 마지막 틱 이후에는 갱신이 없어 영구히 남는다
     * (기획서 11.1.5, ADR-0007).
     */
    @Nested
    @DisplayName("탈퇴자 제외")
    class Withdrawn {

        /**
         * 새 틱을 발행하지 않는다. 그래도 사라져야 한다 — 그것이 마지막 평가 틱 이후의 탈퇴에서
         * 유일하게 가능한 동작이다. 기준 틱이 그대로인 것으로 갱신이 없었음을 함께 단언한다.
         */
        @Test
        @DisplayName("리더보드_2위가 탈퇴하면_그 행이 사라지고 남은 순위 번호는 그대로다")
        void getLeaderboard_afterWithdrawal_dropsRowAndKeepsRemainingRanks() throws Exception {
            givenThreeRankedAccounts();

            userService.delete(ownerA.getId());

            mockMvc.perform(rankings())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.asOfTickSeq").value(FIRST_TICK))
                    .andExpect(jsonPath("$.data.content", hasSize(2)))
                    // 남은 두 사람의 등수는 재산정하지 않는다. 2 번이 비는 것을 허용한다.
                    .andExpect(jsonPath("$.data.content[0].rank").value(1))
                    .andExpect(jsonPath("$.data.content[0].nickname").value(ownerC.getNickname()))
                    .andExpect(jsonPath("$.data.content[1].rank").value(3))
                    .andExpect(jsonPath("$.data.content[1].nickname").value(ownerB.getNickname()));
        }

        /**
         * 개수는 <b>목록과 같은 기준</b>으로 세어야 한다 ({@code rank.md}: {@code totalParticipants} 는
         * 탈퇴자 제외). 자른 뒤에 빼면 개수는 스냅샷 값으로 남아 목록과 서로를 설명하지 못한다.
         */
        @Test
        @DisplayName("리더보드_탈퇴 직후_총 참가자 수와 총 행 수가 탈퇴자를 뺀 값이다")
        void getLeaderboard_afterWithdrawal_countsExcludeWithdrawn() throws Exception {
            givenThreeRankedAccounts();

            userService.delete(ownerA.getId());

            mockMvc.perform(rankings())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.totalParticipants").value(2))
                    .andExpect(jsonPath("$.data.totalElements").value(2));
        }

        /**
         * 페이지를 자른 <b>뒤에</b> 탈퇴자를 빼면 그 페이지만 비고 개수는 그대로 남는다. 탈퇴자만
         * 든 페이지가 통째로 비는 것이 그 증상이다.
         */
        @Test
        @DisplayName("리더보드_1위만 남기고 자른 페이지의 주인이 탈퇴하면_그 페이지가 비지 않는다")
        void getLeaderboard_afterWithdrawalOnSlicedPage_doesNotReturnEmptyPage() throws Exception {
            givenThreeRankedAccounts();

            userService.delete(ownerC.getId());

            mockMvc.perform(rankings().param("page", "0").param("size", "1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content", hasSize(1)))
                    // 1 위가 빠졌으므로 첫 페이지의 첫 행은 2 위다. 번호는 재산정하지 않는다.
                    .andExpect(jsonPath("$.data.content[0].rank").value(2))
                    .andExpect(jsonPath("$.data.content[0].nickname").value(ownerA.getNickname()))
                    .andExpect(jsonPath("$.data.totalElements").value(2));
        }
    }

    /**
     * 폐장 정산이 전 계좌를 다시 평가하지 않고 <b>마지막 틱의 랭킹 스냅샷을 확정</b>하므로, 중간
     * 합류 마감을 두지 않더라도 마지막 틱 이후 생성된 계좌는 그날 정산에 들어가지 않는다.
     *
     * <p>스펙이 <b>명시적으로 수용한 결과</b>다 — 3일차 마지막 평가 틱 이후 처음 합류한 사용자는
     * 계좌는 있지만 시즌 최종 순위·수상에 포함되지 않는다. 정산 구조는 바꾸지 않는다
     * ({@code market-replay-account-enrollment.md} §랭킹 참가 범위).
     */
    @Nested
    @DisplayName("마지막 틱 이후 합류")
    class JoiningAfterLastTick {

        @Test
        @DisplayName("폐장 확정_마지막 평가 틱 이후에 생긴 계좌_그날 확정 순위에 들어가지 않는다")
        void finalizeDay_withAccountCreatedAfterLastTick_excludesItFromSettlement() {
            givenThreeRankedAccounts();
            // 틱을 더 발행하지 않는다. 이 계좌는 어느 스냅샷에도 들어간 적이 없다.
            givenAccount(ACCOUNT_LATE, "100000000", "0");

            events.publishEvent(new PhaseBoundaryEvent((short) 1, ReplayState.CLOSE));

            assertThat(settledAccountIds()).containsExactlyInAnyOrder(ACCOUNT_A, ACCOUNT_B, ACCOUNT_C);
        }

        @Test
        @DisplayName("리더보드_마지막 평가 틱 이후에 생긴 계좌_다음 평가 틱까지 순위에 없다")
        void getLeaderboard_withAccountCreatedAfterLastTick_omitsItUntilNextTick() throws Exception {
            givenThreeRankedAccounts();
            givenAccount(ACCOUNT_LATE, "100000000", "0");

            mockMvc.perform(rankings())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.totalParticipants").value(3))
                    .andExpect(jsonPath("$.data.content", hasSize(3)));
        }

        /** 그날 확정된 정산 행의 계좌. 수상 기록도 이 행에서 나온다 (수상 테이블이 없다). */
        private List<Long> settledAccountIds() {
            return jdbc.queryForList(
                    "SELECT account_id FROM daily_settlements WHERE season_day = 1", Long.class);
        }
    }

    @Nested
    @DisplayName("리더보드")
    class Leaderboard {

        @Test
        @DisplayName("리더보드_track 을 생략하면_시즌 순위가 수익률 내림차순으로 온다")
        void getLeaderboard_defaultTrack_isSeasonOrderedByReturn() throws Exception {
            givenThreeRankedAccounts();

            mockMvc.perform(rankings())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.track").value("SEASON"))
                    .andExpect(jsonPath("$.data.asOfTickSeq").value(FIRST_TICK))
                    .andExpect(jsonPath("$.data.asOfGameDate").value("2020-01-02"))
                    .andExpect(jsonPath("$.data.blackout").value(false))
                    .andExpect(jsonPath("$.data.finalized").value(false))
                    .andExpect(jsonPath("$.data.totalParticipants").value(3))
                    .andExpect(jsonPath("$.data.totalElements").value(3))
                    .andExpect(jsonPath("$.data.content", hasSize(3)))
                    .andExpect(jsonPath("$.data.content[0].rank").value(1))
                    .andExpect(jsonPath("$.data.content[0].rankDelta").value(0))
                    .andExpect(jsonPath("$.data.content[0].nickname").value(ownerC.getNickname()))
                    .andExpect(jsonPath("$.data.content[0].avatarId").value(ownerC.getAvatarId().intValue()))
                    .andExpect(jsonPath("$.data.content[0].returnRate").value(12.60))
                    .andExpect(jsonPath("$.data.content[0].totalValuationKrw").value("112600000"))
                    .andExpect(jsonPath("$.data.content[1].rank").value(2))
                    .andExpect(jsonPath("$.data.content[1].nickname").value(ownerA.getNickname()))
                    .andExpect(jsonPath("$.data.content[1].returnRate").value(0.00))
                    .andExpect(jsonPath("$.data.content[1].totalValuationKrw").value("100000000"))
                    .andExpect(jsonPath("$.data.content[2].rank").value(3))
                    .andExpect(jsonPath("$.data.content[2].nickname").value(ownerB.getNickname()))
                    .andExpect(jsonPath("$.data.content[2].returnRate").value(-9.90))
                    .andExpect(jsonPath("$.data.content[2].totalValuationKrw").value("90101000"));
        }

        @Test
        @DisplayName("리더보드_세션 사용자의 행만_isMe 가 참이다")
        void getLeaderboard_marksOnlyMyRow() throws Exception {
            givenThreeRankedAccounts();

            mockMvc.perform(rankings())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content[0].isMe").value(true))
                    .andExpect(jsonPath("$.data.content[1].isMe").value(false))
                    .andExpect(jsonPath("$.data.content[2].isMe").value(false));
        }

        @Test
        @DisplayName("리더보드_행은 보유 종목 수와 체결 건수까지만_종목·비중·내역 필드가 없다")
        void getLeaderboard_rowExposesCountsOnly() throws Exception {
            givenThreeRankedAccounts();
            givenExecution(ACCOUNT_C, usdInstrument, FIRST_TICK);
            givenExecution(ACCOUNT_C, usdInstrument, DAY_2_TICK);

            mockMvc.perform(rankings())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content[0].holdingCount").value(1))
                    .andExpect(jsonPath("$.data.content[0].tradeCount").value(2))
                    .andExpect(jsonPath("$.data.content[1].holdingCount").value(0))
                    .andExpect(jsonPath("$.data.content[1].tradeCount").value(0))
                    .andExpect(jsonPath("$.data.content[0].positions").doesNotExist())
                    .andExpect(jsonPath("$.data.content[0].weight").doesNotExist())
                    .andExpect(jsonPath("$.data.content[0].executions").doesNotExist())
                    .andExpect(jsonPath("$.data.content[0].accountId").doesNotExist())
                    .andExpect(jsonPath("$.data.content[0].userId").doesNotExist());
        }

        @Test
        @DisplayName("리더보드_DAILY 트랙이면_당일 수익률 순위와 당일 체결 건수가 온다")
        void getLeaderboard_dailyTrack_usesDailyReturnAndTodayTrades() throws Exception {
            givenThreeRankedAccounts();
            givenExecution(ACCOUNT_C, usdInstrument, FIRST_TICK);
            givenExecution(ACCOUNT_C, usdInstrument, DAY_2_TICK);

            // 일간 기준값이 시작 잔고와 같아 당일 수익률도 시즌 수익률과 같다. 다른 것은 체결 건수다.
            mockMvc.perform(rankings().param("track", "DAILY"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.track").value("DAILY"))
                    .andExpect(jsonPath("$.data.content[0].rank").value(1))
                    .andExpect(jsonPath("$.data.content[0].nickname").value(ownerC.getNickname()))
                    .andExpect(jsonPath("$.data.content[0].returnRate").value(12.60))
                    // 평가액은 계좌의 상태지 트랙의 기준값이 아니라 SEASON 과 같은 금액이다.
                    .andExpect(jsonPath("$.data.content[0].totalValuationKrw").value("112600000"))
                    .andExpect(jsonPath("$.data.content[0].tradeCount").value(1));
        }

        /**
         * 셋 다 시드가 1억이라 평가액 순서가 시즌 순서와 같다 — 그것이 정상이고, 둘이 갈리는
         * 것은 시작 잔고가 다른 중간 합류자뿐이다 ({@code RankingCalculatorTest} 가 그 갈림을 본다).
         */
        @Test
        @DisplayName("리더보드_VALUATION 트랙이면_평가액 내림차순으로 오고 수익률은 시즌 기준이다")
        void getLeaderboard_valuationTrack_ordersByAmount() throws Exception {
            givenThreeRankedAccounts();
            givenExecution(ACCOUNT_C, usdInstrument, FIRST_TICK);
            givenExecution(ACCOUNT_C, usdInstrument, DAY_2_TICK);

            mockMvc.perform(rankings().param("track", "VALUATION"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.track").value("VALUATION"))
                    .andExpect(jsonPath("$.data.totalParticipants").value(3))
                    .andExpect(jsonPath("$.data.content[0].rank").value(1))
                    .andExpect(jsonPath("$.data.content[0].nickname").value(ownerC.getNickname()))
                    .andExpect(jsonPath("$.data.content[0].totalValuationKrw").value("112600000"))
                    // 평가액은 시즌 축의 값이라 함께 나가는 수익률·체결 건수도 시즌 기준이다.
                    .andExpect(jsonPath("$.data.content[0].returnRate").value(12.60))
                    .andExpect(jsonPath("$.data.content[0].tradeCount").value(2))
                    .andExpect(jsonPath("$.data.content[1].nickname").value(ownerA.getNickname()))
                    .andExpect(jsonPath("$.data.content[1].totalValuationKrw").value("100000000"))
                    .andExpect(jsonPath("$.data.content[2].nickname").value(ownerB.getNickname()))
                    .andExpect(jsonPath("$.data.content[2].totalValuationKrw").value("90101000"));
        }

        @Test
        @DisplayName("리더보드_페이지를 자르면_총 개수는 그대로고 다음 페이지에 나머지가 온다")
        void getLeaderboard_pagination_slicesOrderedRanks() throws Exception {
            givenThreeRankedAccounts();

            mockMvc.perform(rankings().param("size", "2"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content", hasSize(2)))
                    .andExpect(jsonPath("$.data.page").value(0))
                    .andExpect(jsonPath("$.data.size").value(2))
                    .andExpect(jsonPath("$.data.totalElements").value(3))
                    .andExpect(jsonPath("$.data.totalPages").value(2))
                    .andExpect(jsonPath("$.data.hasNext").value(true));

            mockMvc.perform(rankings().param("size", "2").param("page", "1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content", hasSize(1)))
                    .andExpect(jsonPath("$.data.content[0].rank").value(3))
                    .andExpect(jsonPath("$.data.content[0].nickname").value(ownerB.getNickname()))
                    .andExpect(jsonPath("$.data.hasNext").value(false));
        }

        @Test
        @DisplayName("리더보드_폐장 스냅샷이 그 일차에 확정돼 있으면_finalized 가 참이다")
        void getLeaderboard_afterSettlement_isFinalized() throws Exception {
            givenThreeRankedAccounts();
            events.publishEvent(new PhaseBoundaryEvent((short) 1, ReplayState.CLOSE));

            mockMvc.perform(rankings())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.finalized").value(true));
        }

        @Test
        @DisplayName("리더보드_다음 날 브리핑에 들어가면_finalized 가 풀린다")
        void getLeaderboard_afterNextBriefing_isNotFinalized() throws Exception {
            givenThreeRankedAccounts();
            events.publishEvent(new PhaseBoundaryEvent((short) 1, ReplayState.CLOSE));
            events.publishEvent(new PhaseBoundaryEvent((short) 2, ReplayState.BRIEFING));

            mockMvc.perform(rankings())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.finalized").value(false));
        }
    }

    @Nested
    @DisplayName("암전 - 16:00~16:40")
    class Blackout {

        @Test
        @DisplayName("리더보드_암전 중이면_content 만 비고 총 개수와 참가자 수는 그대로다")
        void getLeaderboard_duringBlackout_hidesRowsOnly() throws Exception {
            givenThreeRankedAccounts();
            givenBlackout();

            mockMvc.perform(rankings())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.blackout").value(true))
                    .andExpect(jsonPath("$.data.content", hasSize(0)))
                    .andExpect(jsonPath("$.data.totalElements").value(3))
                    .andExpect(jsonPath("$.data.totalParticipants").value(3))
                    .andExpect(jsonPath("$.data.asOfTickSeq").value(FIRST_TICK));
        }

        @Test
        @DisplayName("내 순위_암전 중이면_순위와 등락은 null 이고 수익률은 그대로다")
        void getMyRanking_duringBlackout_nullsRanksKeepsReturns() throws Exception {
            givenThreeRankedAccounts();
            givenBlackout();

            mockMvc.perform(me())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.blackout").value(true))
                    .andExpect(jsonPath("$.data.seasonRank").value(nullValue()))
                    .andExpect(jsonPath("$.data.seasonRankDelta").value(nullValue()))
                    .andExpect(jsonPath("$.data.dailyRank").value(nullValue()))
                    .andExpect(jsonPath("$.data.dailyRankDelta").value(nullValue()))
                    .andExpect(jsonPath("$.data.valuationRank").value(nullValue()))
                    .andExpect(jsonPath("$.data.valuationRankDelta").value(nullValue()))
                    .andExpect(jsonPath("$.data.seasonReturnRate").value(12.60))
                    .andExpect(jsonPath("$.data.dailyReturnRate").value(12.60))
                    // 평가액도 가리지 않는다 — 계좌 요약에서 이미 보이는 값이다.
                    .andExpect(jsonPath("$.data.totalValuationKrw").value("112600000"))
                    .andExpect(jsonPath("$.data.totalParticipants").value(3));
        }
    }

    @Nested
    @DisplayName("내 순위")
    class MyRanking {

        @Test
        @DisplayName("내 순위_두 트랙이 한 번에 오고_시즌 수익률은 계좌 요약의 누적 수익률과 같다")
        void getMyRanking_returnsBothTracksMatchingAccountSummary() throws Exception {
            givenThreeRankedAccounts();

            mockMvc.perform(me())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.asOfTickSeq").value(FIRST_TICK))
                    .andExpect(jsonPath("$.data.seasonRank").value(1))
                    .andExpect(jsonPath("$.data.seasonRankDelta").value(0))
                    .andExpect(jsonPath("$.data.seasonReturnRate").value(12.60))
                    .andExpect(jsonPath("$.data.dailyRank").value(1))
                    .andExpect(jsonPath("$.data.dailyRankDelta").value(0))
                    .andExpect(jsonPath("$.data.dailyReturnRate").value(12.60))
                    .andExpect(jsonPath("$.data.totalValuationKrw").value("112600000"))
                    .andExpect(jsonPath("$.data.valuationRank").value(1))
                    .andExpect(jsonPath("$.data.valuationRankDelta").value(0))
                    .andExpect(jsonPath("$.data.totalParticipants").value(3))
                    .andExpect(jsonPath("$.data.blackout").value(false));

            mockMvc.perform(get(ACCOUNT_URI).header(AUTH_TOKEN_HEADER, sessionC))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.cumulativeReturn").value(12.60))
                    .andExpect(jsonPath("$.data.dailyReturn").value(12.60))
                    // 순위의 평가액은 계좌 요약과 같은 스윕이 낸 값이라 두 화면이 어긋날 길이 없다.
                    .andExpect(jsonPath("$.data.totalValuationKrw").value("112600000"));
        }

        @Test
        @DisplayName("내 순위_계좌는 있지만 그 틱에 평가되지 않았으면_순위·등락·수익률이 전부 null 이다")
        void getMyRanking_accountAbsentFromSnapshot_nullsEverything() throws Exception {
            givenThreeRankedAccounts();
            // 세션 사용자 C 의 계좌를 순위가 만들어진 뒤에 새로 만든 것과 같은 상태로 둔다.
            jdbc.update("DELETE FROM positions WHERE account_id = ?", ACCOUNT_C);
            jdbc.update("DELETE FROM notifications WHERE account_id = ?", ACCOUNT_C);
            jdbc.update("DELETE FROM accounts WHERE id = ?", ACCOUNT_C);
            events.publishEvent(new TickPublishedEvent(FIRST_TICK, FIRST_DATE));
            givenAccount(ACCOUNT_C, "100000000", "0");

            mockMvc.perform(me())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.asOfTickSeq").value(FIRST_TICK))
                    .andExpect(jsonPath("$.data.seasonRank").value(nullValue()))
                    .andExpect(jsonPath("$.data.seasonReturnRate").value(nullValue()))
                    .andExpect(jsonPath("$.data.dailyRank").value(nullValue()))
                    .andExpect(jsonPath("$.data.dailyReturnRate").value(nullValue()))
                    .andExpect(jsonPath("$.data.totalValuationKrw").value(nullValue()))
                    .andExpect(jsonPath("$.data.totalParticipants").value(2));
        }
    }

    @Nested
    @DisplayName("시상")
    class Awards {

        /** <b>이전 판은 같은 요청에 401 을 단언했다.</b> 시상 결과는 전체 랭킹의 일부로 함께 열렸다. */
        @Test
        @DisplayName("시상_세션이 없어도_200 을 받는다")
        void getAwards_withoutSession_returnsOk() throws Exception {
            mockMvc.perform(get(AWARDS_URI))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("시상_폐장에 확정됐지만 정산 발표 전이면_그날 항목이 없다")
        void getAwards_beforeAnnouncement_hasNoEntryForThatDay() throws Exception {
            givenThreeRankedAccounts();
            events.publishEvent(new PhaseBoundaryEvent((short) 1, ReplayState.CLOSE));
            givenPublishedTick(FIRST_TICK, ReplayState.CLOSE);

            mockMvc.perform(awards())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.daily", hasSize(0)))
                    .andExpect(jsonPath("$.data.final", hasSize(0)));
        }

        /** 일간 시상은 5위까지다 (S15P21A605-265). 세 계좌면 셋 다 수상자이고 순위 순으로 온다. */
        @Test
        @DisplayName("시상_정산 발표 뒤_그날 일간 상위가 등수·칭호·발표 시각과 함께 순위 순으로 온다")
        void getAwards_afterAnnouncement_listsDailyWinnersInRankOrder() throws Exception {
            givenThreeRankedAccounts();
            events.publishEvent(new PhaseBoundaryEvent((short) 1, ReplayState.CLOSE));
            givenPublishedTick(FIRST_TICK, ReplayState.END);

            mockMvc.perform(awards())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.daily", hasSize(3)))
                    .andExpect(jsonPath("$.data.daily[0].seasonDay").value(1))
                    .andExpect(jsonPath("$.data.daily[0].rank").value(1))
                    .andExpect(jsonPath("$.data.daily[0].nickname").value(ownerC.getNickname()))
                    .andExpect(jsonPath("$.data.daily[0].avatarId").value(ownerC.getAvatarId().intValue()))
                    .andExpect(jsonPath("$.data.daily[0].returnRate").value(12.60))
                    .andExpect(jsonPath("$.data.daily[0].title").value("오늘의 투자자"))
                    // 편성된 1일차 개장 11:05 KST 에서 발표 경계까지 → 17:00 KST.
                    .andExpect(jsonPath("$.data.daily[0].announcedAt").value("2026-09-21T08:00:00Z"))
                    .andExpect(jsonPath("$.data.daily[1].rank").value(2))
                    .andExpect(jsonPath("$.data.daily[1].nickname").value(ownerA.getNickname()))
                    .andExpect(jsonPath("$.data.daily[1].returnRate").value(0.00))
                    // 칭호는 등수와 무관하게 하나다 — "2위" 는 화면이 rank 로 조합한다.
                    .andExpect(jsonPath("$.data.daily[1].title").value("오늘의 투자자"))
                    .andExpect(jsonPath("$.data.daily[2].rank").value(3))
                    .andExpect(jsonPath("$.data.daily[2].nickname").value(ownerB.getNickname()))
                    .andExpect(jsonPath("$.data.daily[2].returnRate").value(-9.90))
                    .andExpect(jsonPath("$.data.final", hasSize(0)));
        }

        /** 6위부터는 수상자가 아니다. 경계를 직접 심어 상한이 5 인지 본다. */
        @Test
        @DisplayName("시상_일간 6위는_daily 에 없다")
        void getAwards_sixthPlace_isNotAwarded() throws Exception {
            givenAccount(ACCOUNT_A, "100000000", "0");
            givenAccount(ACCOUNT_B, "100000000", "0");
            givenSettlementAtDailyRank(ACCOUNT_A, 1, 5);
            givenSettlementAtDailyRank(ACCOUNT_B, 1, 6);
            givenPublishedTick(FIRST_TICK, ReplayState.END);

            mockMvc.perform(awards())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.daily", hasSize(1)))
                    .andExpect(jsonPath("$.data.daily[0].rank").value(5))
                    .andExpect(jsonPath("$.data.daily[0].nickname").value(ownerA.getNickname()));
        }

        @Test
        @DisplayName("시상_일간 1위가 동점이면_같은 일차 항목이 여럿이다")
        void getAwards_tiedDailyWinners_listsAllOfThem() throws Exception {
            givenAccount(ACCOUNT_A, "100000000", "0");
            givenAccount(ACCOUNT_B, "100000000", "0");
            events.publishEvent(new TickPublishedEvent(FIRST_TICK, FIRST_DATE));
            events.publishEvent(new PhaseBoundaryEvent((short) 1, ReplayState.CLOSE));
            givenPublishedTick(FIRST_TICK, ReplayState.END);

            mockMvc.perform(awards())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.daily", hasSize(2)))
                    .andExpect(jsonPath("$.data.daily[0].rank").value(1))
                    .andExpect(jsonPath("$.data.daily[0].nickname").value(ownerA.getNickname()))
                    .andExpect(jsonPath("$.data.daily[1].rank").value(1))
                    .andExpect(jsonPath("$.data.daily[1].nickname").value(ownerB.getNickname()))
                    .andExpect(jsonPath("$.data.daily[1].returnRate").value(0.00));
        }

        @Test
        @DisplayName("시상_3일차 발표 전이면_final 이 비어 있다")
        void getAwards_beforeFinalAnnouncement_finalIsNull() throws Exception {
            givenAccount(ACCOUNT_C, "40000000", "10000");
            givenSettlement(ACCOUNT_C, 3);
            fixture.store(3, BigDecimal.valueOf(ReplayState.CLOSE), ReplayFixture.START, BigDecimal.ZERO, 12L, 3,
                    ReplayState.CLOSE);

            mockMvc.perform(awards())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.final", hasSize(0)));
        }

        @Test
        @DisplayName("시상_3일차 발표 뒤_final 에 시즌 우승자가 온다")
        void getAwards_afterFinalAnnouncement_hasChampion() throws Exception {
            givenAccount(ACCOUNT_C, "40000000", "10000");
            givenSettlement(ACCOUNT_C, 3);
            fixture.store(3, BigDecimal.valueOf(ReplayState.END), ReplayFixture.START, BigDecimal.ZERO, 12L, 3,
                    ReplayState.END);

            mockMvc.perform(awards())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.daily", hasSize(1)))
                    .andExpect(jsonPath("$.data.daily[0].seasonDay").value(3))
                    .andExpect(jsonPath("$.data.final", hasSize(1)))
                    .andExpect(jsonPath("$.data.final[0].rank").value(1))
                    .andExpect(jsonPath("$.data.final[0].nickname").value(ownerC.getNickname()))
                    .andExpect(jsonPath("$.data.final[0].title").value("시즌 우승자"))
                    .andExpect(jsonPath("$.data.final[0].returnRate").value(12.60))
                    .andExpect(jsonPath("$.data.final[0].announcedAt").value("2026-09-23T08:00:00Z"));
        }

        @Test
        @DisplayName("시상_시즌 1위가 동점이면_final 에 전원이 온다")
        void getAwards_tiedChampions_listsAllOfThem() throws Exception {
            givenAccount(ACCOUNT_A, "100000000", "0");
            givenAccount(ACCOUNT_B, "100000000", "0");
            givenSettlement(ACCOUNT_A, 3);
            givenSettlement(ACCOUNT_B, 3);
            fixture.store(3, BigDecimal.valueOf(ReplayState.END), ReplayFixture.START, BigDecimal.ZERO, 12L, 3,
                    ReplayState.END);

            mockMvc.perform(awards())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.final", hasSize(2)))
                    .andExpect(jsonPath("$.data.final[0].nickname").value(ownerA.getNickname()))
                    .andExpect(jsonPath("$.data.final[1].nickname").value(ownerB.getNickname()));
        }
    }

    private MockHttpServletRequestBuilder awards() {
        return get(AWARDS_URI).header(AUTH_TOKEN_HEADER, sessionC);
    }

    /** 일간 순위만 다른 확정 행. 시상 상한(5위)의 경계를 보는 데 쓴다. */
    private void givenSettlementAtDailyRank(long accountId, int seasonDay, int dailyRank) {
        jdbc.update("""
                INSERT INTO daily_settlements (season_day, account_id, season_rank, daily_rank, season_return,
                                               daily_return, total_valuation_krw, execution_count, total_fee_krw,
                                               realized_pnl_krw, pattern_tags, finalized_at)
                VALUES (?, ?, ?, ?, 0, 0, 100000000, 1, 0, 0, '[]'::jsonb, now())
                """, seasonDay, accountId, dailyRank, dailyRank);
    }

    /** 다른 일차의 확정 행을 직접 심는다. 폐장 이벤트는 메모리 순위의 일차만 얼리기 때문이다. */
    private void givenSettlement(long accountId, int seasonDay) {
        jdbc.update("""
                INSERT INTO daily_settlements (season_day, account_id, season_rank, daily_rank, season_return,
                                               daily_return, total_valuation_krw, execution_count, total_fee_krw,
                                               realized_pnl_krw, pattern_tags, finalized_at)
                VALUES (?, ?, 1, 1, 12.60, 12.60, 112600000, 0, 0, 0, '[]'::jsonb, now())
                """, seasonDay, accountId);
    }

    private MockHttpServletRequestBuilder rankings() {
        return get(RANKINGS_URI).header(AUTH_TOKEN_HEADER, sessionC);
    }

    private MockHttpServletRequestBuilder me() {
        return get(ME_URI).header(AUTH_TOKEN_HEADER, sessionC);
    }

    /**
     * A: 현금 1억 (0.00%) · B: 9천만 + 원화 종목 10주 x 10,100 (-9.90%) ·
     * C: 4천만 + 달러 1만 x 1200 + 달러 종목 5주 x 10,100 x 1200 (+12.60%). 첫 틱을 공개하고 순위를 만든다.
     */
    private void givenThreeRankedAccounts() {
        givenAccount(ACCOUNT_A, "100000000", "0");
        givenAccount(ACCOUNT_B, "90000000", "0");
        givenPosition(ACCOUNT_B, krwInstrument, 10);
        givenAccount(ACCOUNT_C, "40000000", "10000");
        givenPosition(ACCOUNT_C, usdInstrument, 5);
        givenPublishedTick(FIRST_TICK, ReplayFixture.TRADING_OPEN);
        events.publishEvent(new TickPublishedEvent(FIRST_TICK, FIRST_DATE));
    }

    /** 시계를 암전 경계에 둔다. 순위는 그대로고 시계의 플래그만 바뀐다. */
    private void givenBlackout() {
        givenPublishedTick(FIRST_TICK, ReplayFixture.BLACKOUT_START);
    }

    /** 그 틱까지 공개되고 그 경계까지 처리된 상태로 재생을 고정한다. 배속 0 이라 폴러가 더 진행하지 않는다. */
    private void givenPublishedTick(long tickSeq, int boundaryOffset) {
        fixture.store(1, BigDecimal.valueOf(boundaryOffset), ReplayFixture.START, BigDecimal.ZERO, tickSeq, 1,
                boundaryOffset);
    }

    private void givenAccount(long accountId, String krwCash, String usdCash) {
        jdbc.update("""
                INSERT INTO accounts (id, user_id, season_id, krw_cash, usd_cash, locked_krw, locked_usd,
                                      starting_balance_krw, index_adjusted, joined_season_day,
                                      daily_base_valuation_krw, daily_base_season_day,
                                      last_acked_tick_seq, created_at)
                VALUES (?, ?, ?, CAST(? AS NUMERIC), CAST(? AS NUMERIC), 0, 0,
                        100000000, false, 1, 100000000, 1, 0, now())
                """, accountId, ownerOf(accountId).getId(), ReplayFixture.SEASON_ID, krwCash, usdCash);
    }

    private User ownerOf(long accountId) {
        if (accountId == ACCOUNT_A) {
            return ownerA;
        }
        if (accountId == ACCOUNT_B) {
            return ownerB;
        }
        return accountId == ACCOUNT_LATE ? ownerLate : ownerC;
    }

    private void givenPosition(long accountId, long instrumentId, int quantity) {
        jdbc.update("INSERT INTO positions (account_id, instrument_id, quantity, total_cost) VALUES (?, ?, ?, 1)",
                accountId, instrumentId, quantity);
    }

    /** 게임 거래일은 그 틱의 것을 그대로 쓴다 — V17 이 체결에 자기 거래일을 들게 했다. */
    private void givenExecution(long accountId, long instrumentId, long tickSeq) {
        jdbc.update("""
                INSERT INTO executions (account_id, instrument_id, order_type, side, quantity, executed_price,
                                        currency, amount, applied_fee_rate, fee, tick_seq, game_date, executed_at)
                VALUES (?, ?, 'MARKET', 'BUY', 1, 10100, 'USD', 10100, 0.15, 15.15, ?,
                        (SELECT game_date FROM game_ticks WHERE tick_seq = ?), now())
                """, accountId, instrumentId, tickSeq, tickSeq);
    }

    private long instrumentId(String symbol) {
        return jdbc.queryForObject("SELECT id FROM instruments WHERE symbol = ?", Long.class, symbol);
    }
}

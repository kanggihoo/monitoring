package com.ssafy.a605.backend.domain.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import com.ssafy.a605.backend.domain.account.entity.Account;
import com.ssafy.a605.backend.domain.account.service.AccountEnrollmentService;
import com.ssafy.a605.backend.domain.user.entity.User;
import com.ssafy.a605.backend.global.exception.BusinessException;
import com.ssafy.a605.backend.support.AccountFixture;
import com.ssafy.a605.backend.support.ContainersConfig;
import com.ssafy.a605.backend.support.MutableClock;
import com.ssafy.a605.backend.support.ReplayFixture;

/**
 * 계좌 생성의 갈래를 실제 빈과 실제 DB 로 덮는다. 실제 트랜잭션을 지나므로 이 서비스 자신의
 * 커밋 경계와 멱등성도 함께 검증된다.
 *
 * <p><b>시계 위치는 재생 상태 행을 직접 써서 잡는다</b> — {@code ReplayFixture} 의 기존 상수만
 * 쓰고 새 좌표를 만들지 않는다 ({@code market-replay-account-enrollment.md} §Testing Decisions).
 *
 * <p>세 호출 지점(IdP 로그인·테스트 로그인·Sandbox)이 모두 이 한 연산을 부르므로 생성 규칙은
 * 여기서 한 번만 검증한다. 배선은 {@code TestSupportApiTest} 가 본다.
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
@Import({ContainersConfig.class, ReplayFixture.class, AccountFixture.class, MutableClock.Config.class})
class AccountEnrollmentServiceTest {

    /** 편성값 {@code seasons.seed_krw}. 전원이 이 값으로 시작한다 (ADR-0006). */
    private static final BigDecimal SEED_KRW = new BigDecimal("100000000.0000");

    /**
     * 픽스처의 틱 <b>순번</b>이다. {@link ReplayFixture} 의 {@code LAST_TICK_*} 은 게임 위치
     * 오프셋이므로 이름을 겹치지 않게 둔다 — 둘을 섞으면 조용히 다른 좌표를 가리킨다.
     */
    private static final long LAST_TICK_SEQ_DAY_1 = 4L;
    private static final long FIRST_TICK_SEQ_DAY_2 = 5L;

    /** 3일차 마지막 틱의 순번. 이 뒤로는 그날 정산에 들어갈 평가가 더 없다. */
    private static final long LAST_TICK_SEQ_DAY_3 = 12L;

    @Autowired
    private AccountEnrollmentService enrollment;

    @Autowired
    private ReplayFixture fixture;

    @Autowired
    private AccountFixture accounts;

    @Autowired
    private MutableClock clock;

    @Autowired
    private JdbcTemplate jdbc;

    private User user;

    @BeforeEach
    void setUp() {
        fixture.reset(clock);
        user = fixture.user("계좌생성");
    }

    @AfterEach
    void tearDown() {
        accounts.deleteAll(user.getId());
    }

    @Nested
    @DisplayName("개장 전 생성")
    class BeforeOpen {

        /**
         * 시즌 시작 전과 1일차 개장 전은 시계에서 <b>같은 상태</b>다 (둘 다 1일차 브리핑,
         * 공개 틱 없음). 구별에 필요한 값이 관리자 스냅샷에만 있어 갈래를 만들 수 없고, 그래서
         * 계약의 생성 시점이 셋에서 둘로 줄었다 ({@code sea.md}).
         */
        @Test
        @DisplayName("계좌 생성_공개된 틱이 없는 1일차 브리핑_시계가 1일차를 반환하고 1일차로 기록된다")
        void enroll_beforeFirstPublishedTick_recordsSeasonDayOne() {
            Account account = enrollment.enroll(user.getId());

            assertThat(account.getJoinedSeasonDay()).isEqualTo((short) 1);
            assertThat(account.getLastAckedTickSeq()).isZero();
        }

        @Test
        @DisplayName("계좌 생성_공개된 틱이 없는 1일차 브리핑_전원 시드로 시작하고 미조정으로 저장된다")
        void enroll_beforeFirstPublishedTick_startsWithFlatSeed() {
            Account account = enrollment.enroll(user.getId());

            assertThat(account.getStartingBalanceKrw()).isEqualByComparingTo(SEED_KRW);
            assertThat(account.getKrwCash()).isEqualByComparingTo(SEED_KRW);
            assertThat(account.getIndexAdjusted()).isFalse();
        }

        @Test
        @DisplayName("계좌 생성_공개된 틱이 없는 1일차 브리핑_일간 기준값이 시작 잔고와 합류 일차로 잡힌다")
        void enroll_beforeFirstPublishedTick_setsDailyBaseFromSeed() {
            Account account = enrollment.enroll(user.getId());

            assertThat(account.getDailyBaseValuationKrw()).isEqualByComparingTo(SEED_KRW);
            assertThat(account.getDailyBaseSeasonDay()).isEqualTo(account.getJoinedSeasonDay());
        }
    }

    @Nested
    @DisplayName("중간 합류")
    class JoiningMidSeason {

        @Test
        @DisplayName("계좌 생성_2일차 첫 틱이 공개된 뒤_합류 일차가 2 이고 확인한 틱이 그 틱이다")
        void enroll_afterFirstTickOfDayTwo_recordsSecondDayAndPublishedTick() {
            givenPublished(2, ReplayFixture.TRADING_OPEN, FIRST_TICK_SEQ_DAY_2);

            Account account = enrollment.enroll(user.getId());

            assertThat(account.getJoinedSeasonDay()).isEqualTo((short) 2);
            assertThat(account.getLastAckedTickSeq()).isEqualTo(FIRST_TICK_SEQ_DAY_2);
        }

        /**
         * 공개 틱은 일차가 끝나도 초기화되지 않는다. "개장 전이면 0" 이 아니라 전날 마지막
         * 틱이 들어가며, {@code 0} 은 시즌 최초 틱 이전에만 나온다.
         */
        @Test
        @DisplayName("계좌 생성_2일차 브리핑_확인한 틱이 0 이 아니라 전날 마지막 틱이다")
        void enroll_duringSecondDayBriefing_carriesPreviousDayLastTick() {
            givenPublished(2, ReplayFixture.BRIEFING, LAST_TICK_SEQ_DAY_1);

            Account account = enrollment.enroll(user.getId());

            assertThat(account.getLastAckedTickSeq()).isEqualTo(LAST_TICK_SEQ_DAY_1);
            assertThat(account.getJoinedSeasonDay()).isEqualTo((short) 2);
        }

        /**
         * 중간 합류 마감을 두지 않는다. 마지막 평가 틱 이후 합류자가 확정 순위에 들어가지 않는
         * 것은 폐장 정산이 랭킹 스냅샷을 얼리기 때문이며, 생성을 막아서가 아니다.
         */
        @Test
        @DisplayName("계좌 생성_3일차 마지막 평가 틱 이후_계좌는 그대로 만들어진다")
        void enroll_afterLastTickOfSeason_stillCreatesAccount() {
            givenPublished(3, ReplayFixture.CLOSE, LAST_TICK_SEQ_DAY_3);

            Account account = enrollment.enroll(user.getId());

            assertThat(account.getJoinedSeasonDay()).isEqualTo((short) 3);
            assertThat(accounts.countOf(user.getId())).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("멱등")
    class Idempotency {

        @Test
        @DisplayName("계좌 생성_이미 계좌가 있는 사용자_잔고와 보유와 확인한 틱이 보존된다")
        void enroll_withExistingAccount_preservesBalanceHoldingsAndAck() {
            long accountId = accounts.create(user.getId(), (short) 2, 7L, "12345678");
            givenPosition(accountId, 10);

            Account account = enrollment.enroll(user.getId());

            assertThat(account.getKrwCash()).isEqualByComparingTo(new BigDecimal("12345678"));
            assertThat(account.getLastAckedTickSeq()).isEqualTo(7L);
            assertThat(account.getJoinedSeasonDay()).isEqualTo((short) 2);
            assertThat(quantityOf(accountId)).isEqualTo(10);
        }

        /**
         * 동시 세션이 허용되므로 같은 사용자의 로그인이 겹친다. 계좌가 둘 생기면 시드가 두 배가
         * 되므로 <b>유니크 제약에 걸린 쪽이 기존 계좌를 재조회</b>해야 한다.
         */
        @Test
        @DisplayName("계좌 생성_같은 사용자의 동시 호출_계좌가 하나만 생기고 시드가 두 배가 되지 않는다")
        void enroll_calledConcurrently_createsSingleAccount() throws Exception {
            List<Account> created = inParallel(() -> enrollment.enroll(user.getId()));

            assertThat(accounts.countOf(user.getId())).isEqualTo(1);
            assertThat(created).extracting(Account::getId).containsOnly(created.getFirst().getId());
            assertThat(krwCashOf(user.getId())).isEqualByComparingTo(SEED_KRW);
        }
    }

    /**
     * 재생 상태는 로더가 소유하므로 기본 행을 마이그레이션으로 심지 않는다. 초기화되지 않은
     * 환경에서는 로그인이 <b>명확한 이유로</b> 실패해야 한다 — 로그인은 되는데 앱이 이상한
     * 상태가 더 나쁘다.
     */
    @Test
    @DisplayName("계좌 생성_재생 상태 행이 없으면_409 로 실패한다")
    void enroll_withoutReplayState_conflicts() {
        jdbc.update("DELETE FROM replay_state WHERE season_id = ?", ReplayFixture.SEASON_ID);
        try {
            assertThatThrownBy(() -> enrollment.enroll(user.getId()))
                    .isInstanceOf(BusinessException.class)
                    .extracting(exception -> ((BusinessException) exception).getStatus())
                    .isEqualTo(HttpStatus.CONFLICT);
        } finally {
            fixture.seedAgain();
            fixture.reset(clock);
        }
    }

    /** 픽스처가 심은 검증용 종목을 그 수량만큼 보유하게 한다. */
    private void givenPosition(long accountId, int quantity) {
        jdbc.update("""
                INSERT INTO positions (account_id, instrument_id, quantity, total_cost)
                VALUES (?, (SELECT id FROM instruments WHERE symbol = 'REPLAY_TEST_A'), ?, 1)
                """, accountId, quantity);
    }

    private int quantityOf(long accountId) {
        return jdbc.queryForObject("SELECT quantity FROM positions WHERE account_id = ?",
                Integer.class, accountId);
    }

    /** 재생 상태를 그 일차·오프셋·공개 틱에 놓는다. 경계는 처리된 것으로 기록한다. */
    private void givenPublished(int seasonDay, int offset, long publishedTick) {
        fixture.store(seasonDay, BigDecimal.valueOf(offset), ReplayFixture.START, BigDecimal.ZERO,
                publishedTick, seasonDay, offset);
    }

    /**
     * 같은 연산을 동시에 두 번 부른다. 둘이 같은 순간에 출발하도록 관문에서 맞춰야 경합이
     * 실제로 일어난다 — 순서대로 부르면 뒤에 온 쪽이 이미 커밋된 계좌를 조회로 찾는다.
     */
    private static <T> List<T> inParallel(Callable<T> operation) throws Exception {
        CyclicBarrier startTogether = new CyclicBarrier(2);
        Callable<T> aligned = () -> {
            startTogether.await();
            return operation.call();
        };
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            List<Future<T>> futures = pool.invokeAll(List.of(aligned, aligned));
            return List.of(futures.get(0).get(), futures.get(1).get());
        }
    }

    /** 그 시즌 계좌의 원화 현금. 생성이 두 번 일어나면 시드가 두 배로 들어온다. */
    private BigDecimal krwCashOf(long userId) {
        Map<String, Object> account = accounts.rowOf(userId);
        return (BigDecimal) account.get("krw_cash");
    }
}

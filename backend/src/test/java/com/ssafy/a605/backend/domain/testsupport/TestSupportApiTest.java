package com.ssafy.a605.backend.domain.testsupport;

import static com.ssafy.a605.backend.domain.testsupport.config.TestSupportConfig.LOGIN_PATH;
import static com.ssafy.a605.backend.domain.testsupport.config.TestSupportConfig.SANDBOX_PATH;
import static com.ssafy.a605.backend.domain.testsupport.controller.TestSupportController.API_KEY_HEADER;
import static com.ssafy.a605.backend.support.SessionSupport.SESSION_COOKIE_NAME;
import static com.ssafy.a605.backend.support.SessionSupport.setCookieHeader;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import javax.sql.DataSource;

import com.jayway.jsonpath.JsonPath;
import org.flywaydb.core.Flyway;
import org.hibernate.exception.ConstraintViolationException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.flyway.autoconfigure.FlywayProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.ssafy.a605.backend.domain.user.entity.Role;
import com.ssafy.a605.backend.domain.user.entity.User;
import com.ssafy.a605.backend.domain.user.repository.UserRepository;
import com.ssafy.a605.backend.domain.testsupport.service.TestSupportService;
import com.ssafy.a605.backend.global.security.CookieOrHeaderHttpSessionIdResolver;
import com.ssafy.a605.backend.support.AccountFixture;
import com.ssafy.a605.backend.support.ContainersConfig;
import com.ssafy.a605.backend.support.MutableClock;
import com.ssafy.a605.backend.support.ReplayFixture;
import com.ssafy.a605.backend.support.SessionSupport;

/**
 * 기능이 <b>켜진</b> 컨텍스트. HTTP 경계에서 두 진입점의 관찰 가능한 결과만 검증한다 —
 * 어떤 클래스가 호출됐는지나 세션이 저장소의 어떤 키에 들어갔는지는 보지 않는다.
 *
 * <p>꺼진 컨텍스트는 {@link TestSupportDisabledApiTest} 가 맡는다. 플래그가 빈 생성 여부를
 * 바꾸므로 컨텍스트를 따로 띄워야 한다.
 */
@Tag("container")
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(locations = "classpath:oauth2-test.properties",
        properties = {
                "app.test-support.enabled=true",
                "app.test-support.api-key=" + TestSupportApiTest.API_KEY,
                // 두 진입점이 계좌를 만들고 계좌 생성이 재생 시계를 읽는다. 재생 상태 행이 없으면
                // 시계가 409 를 던져 로그인 자체가 실패한다 (의도된 동작).
                "app.replay.mode=test",
                "app.replay.fixture.enabled=true",
                "app.replay.season-id=1",
                "app.replay.poll-delay-ms=50"
        })
@Import({ContainersConfig.class, SessionSupport.class, ReplayFixture.class, AccountFixture.class,
        MutableClock.Config.class})
class TestSupportApiTest {

    /** 32자 이상이어야 컨텍스트가 뜬다. */
    static final String API_KEY = "test-support-api-key-0123456789ab";

    private static final String ME_URI = "/api/v1/users/me";

    /** 계좌 생성의 관측면. 참가 상태는 계좌 조회식이므로 여기서 생성을 확인한다 ({@code sea.md}). */
    private static final String SEASON_URI = "/api/v1/seasons/current";
    private static final String AUTH_TOKEN_HEADER = CookieOrHeaderHttpSessionIdResolver.HEADER_NAME;

    /** 시드가 만드는 부하 테스트용 Test User 의 첫 번째 이름. */
    private static final String SEEDED_NAME = "k6-0001";
    private static final String SEED_LOCATION = "db/testdata/R__seed_test_users.sql";
    private static final long SEEDED_COUNT = 1000L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private FlywayProperties flywayProperties;

    @Autowired
    private SessionSupport sessions;

    @Autowired
    private ReplayFixture replay;

    @Autowired
    private AccountFixture accounts;

    @Autowired
    private MutableClock clock;

    @Autowired
    private TestSupportService testSupport;

    /** 1일차 브리핑, 정지, 공개 틱 없음. 계좌는 이 구간에도 만들어진다 ({@code sea.md}). */
    @BeforeEach
    void setUp() {
        replay.reset(clock);
    }

    @Nested
    @DisplayName("테스트 로그인")
    class TestLogin {

        @Test
        @DisplayName("테스트 로그인_유효한 키와 위조 방지 토큰 없음_쓸 수 있는 세션을 받는다")
        void login_withValidApiKeyAndNoCsrfToken_issuesUsableSession() throws Exception {
            String sessionId = mockMvc.perform(testLogin(SEEDED_NAME).header(AUTH_TOKEN_HEADER, "none"))
                    .andExpect(status().isNoContent())
                    .andReturn().getResponse().getHeader(AUTH_TOKEN_HEADER);

            mockMvc.perform(get(ME_URI).header(AUTH_TOKEN_HEADER, sessionId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.role").value(Role.USER.name()))
                    .andExpect(jsonPath("$.data.providers").isEmpty());
        }

        @Test
        @DisplayName("테스트 로그인_앱 인증 헤더를 얹은 요청_세션 식별자가 응답 헤더로 나온다")
        void login_withAuthTokenHeader_returnsSessionIdInHeader() throws Exception {
            mockMvc.perform(testLogin(SEEDED_NAME).header(AUTH_TOKEN_HEADER, "none"))
                    .andExpect(header().exists(AUTH_TOKEN_HEADER));
        }

        @Test
        @DisplayName("테스트 로그인_앱 인증 헤더가 없는 요청_세션 식별자가 쿠키로만 나온다")
        void login_withoutAuthTokenHeader_returnsSessionIdInCookieOnly() throws Exception {
            var response = mockMvc.perform(testLogin(SEEDED_NAME))
                    .andExpect(status().isNoContent())
                    .andExpect(header().doesNotExist(AUTH_TOKEN_HEADER))
                    .andReturn();

            assertThat(setCookieHeader(response, SESSION_COOKIE_NAME)).isNotBlank();
        }

        @Test
        @DisplayName("테스트 로그인_키가 없으면_401 을 받는다")
        void login_withoutApiKey_returnsUnauthorized() throws Exception {
            mockMvc.perform(post(LOGIN_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(SEEDED_NAME)))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("테스트 로그인_키가 틀리면_401 을 받는다")
        void login_withWrongApiKey_returnsUnauthorized() throws Exception {
            mockMvc.perform(post(LOGIN_PATH)
                            .header(API_KEY_HEADER, API_KEY + "-wrong")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(SEEDED_NAME)))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("테스트 로그인_Test User 가 아닌 실제 사용자의 이름_404 를 받는다")
        void login_withNonTestUserName_returnsNotFound() throws Exception {
            User real = user("진짜사람", Role.USER);

            mockMvc.perform(testLogin(real.getNickname()))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("테스트 로그인_관리자에게 배정된 Test User 의 이름_404 를 받는다")
        void login_withAssignedTestUserName_returnsNotFound() throws Exception {
            User admin = user("관리자", Role.ADMIN);
            sandboxUserIdOf(admin);

            mockMvc.perform(testLogin("sandbox-" + admin.getId()))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("테스트 로그인_이미 세션이 있는 상태_이전 세션 식별자로는 더 이상 접근되지 않는다")
        void login_withExistingSession_invalidatesPreviousSession() throws Exception {
            String previous = sessions.createSessionFor(user("홍길동", Role.USER));

            mockMvc.perform(testLogin(SEEDED_NAME).header(AUTH_TOKEN_HEADER, previous))
                    .andExpect(status().isNoContent());

            mockMvc.perform(get(ME_URI).header(AUTH_TOKEN_HEADER, previous))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @DisplayName("샌드박스")
    class Sandbox {

        @Test
        @DisplayName("샌드박스 진입_같은 관리자가 두 번_같은 Test User 를 받는다")
        void enterSandbox_twiceBySameAdmin_reusesSameTarget() throws Exception {
            User admin = user("관리자", Role.ADMIN);

            Long first = sandboxUserIdOf(admin);
            Long second = sandboxUserIdOf(admin);

            assertThat(first).isEqualTo(second);
        }

        @Test
        @DisplayName("샌드박스 진입_두 관리자_서로 다른 Test User 를 받는다")
        void enterSandbox_byTwoAdmins_assignsDifferentTestUsers() throws Exception {
            Long first = sandboxUserIdOf(user("관리자갑", Role.ADMIN));
            Long second = sandboxUserIdOf(user("관리자을", Role.ADMIN));

            assertThat(first).isNotEqualTo(second);
        }

        @Test
        @DisplayName("샌드박스 진입_성공하면_세션 식별자가 바뀌고 이전 관리자 세션은 거부된다")
        void enterSandbox_whenSucceeded_rotatesSessionAndRejectsPrevious() throws Exception {
            String adminSession = sessions.createSessionFor(user("관리자", Role.ADMIN));

            String sandboxSession = mockMvc.perform(post(SANDBOX_PATH).header(AUTH_TOKEN_HEADER, adminSession))
                    .andExpect(status().isNoContent())
                    .andReturn().getResponse().getHeader(AUTH_TOKEN_HEADER);

            assertThat(sandboxSession).isNotEqualTo(adminSession);
            mockMvc.perform(get(ME_URI).header(AUTH_TOKEN_HEADER, adminSession))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("샌드박스 진입_진입한 뒤_관리자 전용 경로가 403 이 된다")
        void enterSandbox_afterEntering_forbidsAdminPaths() throws Exception {
            String adminSession = sessions.createSessionFor(user("관리자", Role.ADMIN));
            String sandboxSession = mockMvc.perform(post(SANDBOX_PATH).header(AUTH_TOKEN_HEADER, adminSession))
                    .andReturn().getResponse().getHeader(AUTH_TOKEN_HEADER);

            mockMvc.perform(post(SANDBOX_PATH).header(AUTH_TOKEN_HEADER, sandboxSession))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("샌드박스 진입_관리자가 아닌 사용자_403 을 받는다")
        void enterSandbox_asNonAdmin_returnsForbidden() throws Exception {
            String sessionId = sessions.createSessionFor(user("일반사용자", Role.USER));

            mockMvc.perform(post(SANDBOX_PATH).header(AUTH_TOKEN_HEADER, sessionId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("샌드박스 진입_미인증 요청_401 을 받는다")
        void enterSandbox_withoutSession_returnsUnauthorized() throws Exception {
            mockMvc.perform(post(SANDBOX_PATH).header(AUTH_TOKEN_HEADER, "none"))
                    .andExpect(status().isUnauthorized());
        }
    }

    /**
     * 로그인 연결과 커밋 순서를 여기서 덮는다. <b>호출자가 만든 바깥 트랜잭션과의 상호작용은
     * 그 호출 경로를 실제로 지나야 확인된다</b> - 읽기 전용 트랜잭션 안의 INSERT 는 예외 없이
     * 행만 생기지 않으므로, 서비스 테스트 통과가 배선의 검증을 뜻하지 않는다.
     *
     * <p><b>동작 실행은 테스트 지원 로그인 POST, 결과 확인은 시즌 조회 GET</b> 이다. 계좌 생성의
     * 관측면은 DB 행이 아니라 {@code participation} 이다.
     */
    @Nested
    @DisplayName("시즌 참가")
    class Enrollment {

        @Test
        @DisplayName("테스트 로그인_세션을 받은 시점에_이미 계좌가 있다")
        void login_whenSessionIssued_alreadyHasAccount() throws Exception {
            String sessionId = mockMvc.perform(testLogin(SEEDED_NAME).header(AUTH_TOKEN_HEADER, "none"))
                    .andExpect(status().isNoContent())
                    .andReturn().getResponse().getHeader(AUTH_TOKEN_HEADER);

            expectParticipating(sessionId);
        }

        @Test
        @DisplayName("샌드박스 최초 진입_새 User 와 계좌가_함께 커밋된다")
        void enterSandbox_firstTime_commitsUserAndAccountTogether() throws Exception {
            String sandboxSession = enterSandbox(user("관리자", Role.ADMIN));

            expectParticipating(sandboxSession);
        }

        @Test
        @DisplayName("샌드박스 재진입_같은 계좌를_계속 쓴다")
        void enterSandbox_again_reusesSameAccount() throws Exception {
            User admin = user("관리자", Role.ADMIN);
            long first = accountIdOf(enterSandbox(admin));

            long second = accountIdOf(enterSandbox(admin));

            assertThat(second).isEqualTo(first);
        }

        /**
         * 배정만 커밋되고 계좌 생성이 실패한 계정이 있을 수 있다. 최초 진입에만 계좌를 만들면
         * 그 계정은 영구히 계좌 없이 남는다.
         */
        @Test
        @DisplayName("샌드박스 재진입_계좌가 없는 상태_그 자리에서 계좌가 생긴다")
        void enterSandbox_withoutAccount_createsItInPlace() throws Exception {
            User admin = user("관리자", Role.ADMIN);
            long sandboxUserId = sandboxUserIdOf(admin);
            accounts.deleteAll(sandboxUserId);

            expectParticipating(enterSandbox(admin));
        }
    }

    /**
     * 동시 진입은 <b>HTTP 경계에서 볼 수 없는 것을 본다</b> - 스레드 두 개의 교차와, 실패했을 때
     * 어느 제약이 깨졌는지다. 뒤의 것은 500 응답 한 줄로 뭉개지므로 서비스를 직접 부른다.
     */
    @Nested
    @DisplayName("샌드박스 동시 진입")
    class SandboxConcurrency {

        /**
         * 배정이 둘 생기면 한 관리자가 Test User 를 둘 점유한다. 고아 User 가 남으면 관리자
         * 목록에 배정 없는 샌드박스 계정이 쌓인다 - 두 저장이 한 트랜잭션이어야 하는 이유다.
         *
         * <p><b>진 쪽도 진입에 성공해야 한다.</b> 배정은 멱등이므로 먼저 만들어진 것을 그대로
         * 쓴다. 이 단언이 없으면 "행은 하나인데 한 요청은 500" 을 통과시킨다 - 실제로 먼저
         * 깨지는 제약은 배정 유니크가 아니라 닉네임 유니크라, 제약 이름으로 복구를 고르면
         * 정확히 그 상태가 된다.
         */
        @Test
        @DisplayName("샌드박스 최초 진입_같은 관리자의 동시 요청_두 요청이 모두 성공하고 배정과 User 가 각각 하나만 남는다")
        void enterSandbox_concurrentlyBySameAdmin_bothSucceedWithSingleAssignment() throws Exception {
            User admin = user("관리자", Role.ADMIN);

            assertThat(enterSandboxTwiceAtOnce(admin.getId())).isEmpty();

            assertThat(countOf("SELECT count(*) FROM test_users WHERE assigned_admin_id = ?", admin.getId()))
                    .isEqualTo(1L);
            assertThat(countOf("SELECT count(*) FROM users WHERE nickname = ?", sandboxNicknameOf(admin)))
                    .isEqualTo(1L);
        }

        /**
         * <b>무관한 사용자와의 닉네임 충돌은 멱등 복구 대상이 아니다.</b> 재조회해도 그 관리자의
         * 배정은 없으므로 그대로 실패해야 한다 — 삼키면 진입이 성공한 척하고 세션만 발급된다.
         * 선재 결함이라 고치지는 않되 복구 규칙이 이것까지 삼키지 않는 것을 여기서 지킨다
         * ({@code market-replay-account-enrollment.md} §발견한 선재 결함).
         *
         * <p>어느 제약이 깨지는지를 <b>실제 DB 로</b> 확인해 이름까지 단언한다. 같은 관리자의 동시
         * 진입에서 진 쪽이 만나는 것도 이 제약이며, 그쪽은 재조회로 복구되고 이쪽은 복구되지 않는
         * 것이 규칙의 경계다.
         */
        @Test
        @DisplayName("샌드박스 최초 진입_닉네임이 이미 쓰이고 있으면_닉네임 유니크 위반으로 실패하고 배정이 생기지 않는다")
        void enterSandbox_withTakenNickname_failsOnNicknameUniqueWithoutAssigning() {
            User admin = user("관리자", Role.ADMIN);
            userRepository.save(new User(null, sandboxNicknameOf(admin)));

            Throwable failure = catchThrowable(
                    () -> testSupport.enterSandbox(admin.getId(), new MockHttpServletRequest()));

            assertThat(constraintNameOf(failure)).isEqualTo("uk_users_nickname");
            assertThat(countOf("SELECT count(*) FROM test_users WHERE assigned_admin_id = ?", admin.getId()))
                    .isZero();
        }

        /**
         * 두 진입이 같은 순간에 출발하도록 관문에서 맞춘다.
         *
         * @return 실패한 쪽이 던진 것. 둘 다 성공하면 빈 목록이다
         */
        private List<Throwable> enterSandboxTwiceAtOnce(Long adminId) throws Exception {
            CyclicBarrier startTogether = new CyclicBarrier(2);
            Callable<Throwable> enter = () -> {
                startTogether.await();
                try {
                    testSupport.enterSandbox(adminId, new MockHttpServletRequest());
                    return null;
                } catch (RuntimeException e) {
                    return e;
                }
            };
            List<Throwable> failures = new ArrayList<>();
            try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
                for (Future<Throwable> done : pool.invokeAll(List.of(enter, enter))) {
                    Throwable failure = done.get();
                    if (failure != null) {
                        failures.add(failure);
                    }
                }
            }
            return failures;
        }
    }

    @Nested
    @DisplayName("부하 테스트용 시드")
    class Seed {

        @Test
        @DisplayName("시드_적용된 컨텍스트_배정되지 않은 Test User 가 심어져 있다")
        void seed_whenApplied_createsUnassignedTestUsers() {
            Long seeded = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM test_users WHERE name LIKE 'k6-%' AND assigned_admin_id IS NULL",
                    Long.class);

            assertThat(seeded).isEqualTo(SEEDED_COUNT);
        }

        @Test
        @DisplayName("시드_두 번 적용_고아 User 가 생기지 않는다")
        void seed_appliedTwice_createsNoOrphanUsers() throws IOException {
            long users = count("users");
            long testUsers = count("test_users");

            jdbcTemplate.execute(new ClassPathResource(SEED_LOCATION).getContentAsString(StandardCharsets.UTF_8));

            assertThat(count("users")).isEqualTo(users);
            assertThat(count("test_users")).isEqualTo(testUsers);
        }

        /**
         * 플래그를 끄면 시드 위치가 탐색 경로에서 빠진다. 이미 적용된 시드가 이력에 남아 있으므로,
         * 그 상태의 설정으로 검증이 통과하지 않으면 <b>플래그를 되돌리는 순간 애플리케이션이 뜨지 않는다.</b>
         * 컨텍스트마다 새 컨테이너를 받는 구조라 이 상황은 여기서만 재현된다.
         */
        @Test
        @DisplayName("시드_적용된 DB 에 기능을 끈 설정을 적용_마이그레이션 검증이 통과한다")
        void seed_withFeatureDisabledAfterApplied_passesValidation() {
            Flyway.configure()
                    .dataSource(dataSource)
                    .locations(flywayProperties.getLocations().toArray(String[]::new))
                    .ignoreMigrationPatterns(flywayProperties.getIgnoreMigrationPatterns().toArray(String[]::new))
                    .load()
                    .validate();
        }

        private long count(String table) {
            return jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Long.class);
        }
    }

    /** 샌드박스에 진입해 그 시점의 대상 User 식별자를 읽는다. */
    private Long sandboxUserIdOf(User admin) throws Exception {
        String sandboxSession = mockMvc.perform(
                        post(SANDBOX_PATH).header(AUTH_TOKEN_HEADER, sessions.createSessionFor(admin)))
                .andExpect(status().isNoContent())
                .andReturn().getResponse().getHeader(AUTH_TOKEN_HEADER);

        String body = mockMvc.perform(get(ME_URI).header(AUTH_TOKEN_HEADER, sandboxSession))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return Long.valueOf(JsonPath.read(body, "$.data.id").toString());
    }

    /** 계좌 생성의 관측면. 참가 상태 세 필드로 확인한다 - DB 행을 직접 보지 않는다. */
    private void expectParticipating(String sessionId) throws Exception {
        mockMvc.perform(get(SEASON_URI).header(AUTH_TOKEN_HEADER, sessionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.participation.status").value("PARTICIPATING"))
                .andExpect(jsonPath("$.data.participation.joinedSeasonDay").value(1))
                .andExpect(jsonPath("$.data.participation.startingBalanceKrw").value(AccountFixture.SEED_KRW));
    }

    /** 샌드박스에 진입해 그 세션 식별자를 받는다. */
    private String enterSandbox(User admin) throws Exception {
        return mockMvc.perform(post(SANDBOX_PATH).header(AUTH_TOKEN_HEADER, sessions.createSessionFor(admin)))
                .andExpect(status().isNoContent())
                .andReturn().getResponse().getHeader(AUTH_TOKEN_HEADER);
    }

    /** 그 세션 사용자의 계좌 식별자. 재진입이 같은 계좌를 쓰는지 이 값으로 본다. */
    private long accountIdOf(String sessionId) throws Exception {
        String body = mockMvc.perform(get(ME_URI).header(AUTH_TOKEN_HEADER, sessionId))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return jdbcTemplate.queryForObject("SELECT id FROM accounts WHERE user_id = ?", Long.class,
                Long.valueOf(JsonPath.read(body, "$.data.id").toString()));
    }

    /** 배정이 만들 샌드박스 User 의 닉네임. 서비스가 관리자 닉네임을 붙인다. */
    private static String sandboxNicknameOf(User admin) {
        return "샌드박스-" + admin.getNickname();
    }

    private long countOf(String sql, Object argument) {
        return jdbcTemplate.queryForObject(sql, Long.class, argument);
    }

    /**
     * 무결성 위반이 난 제약의 이름. 원인 사슬에서 Hibernate 의 위반을 찾아 읽는다.
     *
     * <p>제품의 제약 판별기를 쓰지 않고 여기서 다시 읽는다 — 그쪽이 이름을 잘못 읽어도 이
     * 단언은 통과해야 하지 않는다.
     */
    private static String constraintNameOf(Throwable failure) {
        for (Throwable cause = failure; cause != null && cause.getCause() != cause; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation) {
                return violation.getConstraintName();
            }
        }
        throw new AssertionError("무결성 위반이 아니다: " + failure);
    }

    private MockHttpServletRequestBuilder testLogin(String name) {
        return post(LOGIN_PATH)
                .header(API_KEY_HEADER, API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(name));
    }

    private static String body(String name) {
        return "{\"name\":\"" + name + "\"}";
    }

    private User user(String nickname, Role role) {
        // 닉네임에 유니크 제약이 있고(V24) 같은 컨테이너를 공유하므로 고유값을 붙인다.
        User user = new User(null, nickname + UUID.randomUUID());
        user.changeRole(role);
        return userRepository.save(user);
    }
}

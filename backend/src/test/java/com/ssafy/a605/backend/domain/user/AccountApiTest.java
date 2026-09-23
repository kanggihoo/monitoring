package com.ssafy.a605.backend.domain.user;

import static com.ssafy.a605.backend.support.SessionSupport.CSRF_COOKIE_NAME;
import static com.ssafy.a605.backend.support.SessionSupport.CSRF_HEADER_NAME;
import static com.ssafy.a605.backend.support.SessionSupport.csrfToken;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.ssafy.a605.backend.domain.moderation.service.ForbiddenWordFilter;
import com.ssafy.a605.backend.domain.user.dto.UserUpdateRequest;
import com.ssafy.a605.backend.domain.user.entity.User;
import com.ssafy.a605.backend.domain.user.repository.UserRepository;
import com.ssafy.a605.backend.domain.user.service.UserService;
import com.ssafy.a605.backend.global.security.CookieOrHeaderHttpSessionIdResolver;
import com.ssafy.a605.backend.support.AccountFixture;
import com.ssafy.a605.backend.support.ContainersConfig;
import com.ssafy.a605.backend.support.SessionSupport;

import jakarta.servlet.http.Cookie;

/**
 * HTTP 경계에서 마이페이지의 두 상태 변경(닉네임 변경·탈퇴)을 검증한다.
 */
@Tag("container")
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(locations = "classpath:oauth2-test.properties",
        properties = {
                // 계좌가 있는 사용자의 탈퇴를 보려면 계좌와 그 자식이 있어야 하고, 그 행들이
                // 가리킬 틱·종목은 재생 픽스처가 심는다. 픽스처를 여기서 켠다 (.env 가 결과를
                // 정하지 않게).
                "app.replay.mode=test",
                "app.replay.fixture.enabled=true",
                "app.replay.season-id=1",
                "app.replay.poll-delay-ms=50"
        })
@Import({ContainersConfig.class, SessionSupport.class, AccountFixture.class})
class AccountApiTest {

    private static final String ME_URI = "/api/v1/users/me";

    /** 이 테스트만 쓰는 금칙어. 사전 전체를 지우면 같은 컨테이너의 다른 테스트가 깨진다. */
    private static final String TEST_FORBIDDEN = "금칙어";
    /** 탈퇴가 다른 테이블의 행 때문에 깨지지 않는지 보려면 그 행을 만들어야 한다. */
    private static final String INQUIRIES_URI = "/api/v1/inquiries";
    private static final String AUTH_TOKEN_HEADER = CookieOrHeaderHttpSessionIdResolver.HEADER_NAME;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserService userService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ForbiddenWordFilter forbiddenWords;

    @Autowired
    private SessionSupport sessions;

    @Autowired
    private AccountFixture accounts;

    @Nested
    @DisplayName("닉네임 변경")
    class ChangeNickname {

        @Test
        @DisplayName("닉네임 변경_유효한 닉네임_현재 사용자 조회 결과에 반영된다")
        void updateCurrentUser_withValidNickname_isReflectedInCurrentUser() throws Exception {
            String sessionId = sessions.createSessionFor(googleUser());

            mockMvc.perform(patchNickname(sessionId, "새이름"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.nickname").value("새이름"));

            mockMvc.perform(get(ME_URI).header(AUTH_TOKEN_HEADER, sessionId))
                    .andExpect(jsonPath("$.data.nickname").value("새이름"));
        }

        @Test
        @DisplayName("닉네임 변경_공백뿐인 닉네임_필드별 오류와 함께 400 을 받는다")
        void updateCurrentUser_withBlankNickname_returnsValidationError() throws Exception {
            String sessionId = sessions.createSessionFor(googleUser());

            mockMvc.perform(patchNickname(sessionId, "   "))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.errors[0].field").value("nickname"))
                    .andExpect(jsonPath("$.errors[0].reason").isNotEmpty());
        }

        @Test
        @DisplayName("닉네임 변경_길이 상한을 넘는 닉네임_필드별 오류와 함께 400 을 받는다")
        void updateCurrentUser_withTooLongNickname_returnsValidationError() throws Exception {
            String sessionId = sessions.createSessionFor(googleUser());

            mockMvc.perform(patchNickname(sessionId, "가".repeat(UserUpdateRequest.MAX_LENGTH + 1)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value("nickname"));
        }

        @Test
        @DisplayName("닉네임 변경_한 글자 닉네임_필드별 오류와 함께 400 을 받는다")
        void updateCurrentUser_withTooShortNickname_returnsValidationError() throws Exception {
            // 컬럼 길이를 따라간 값이 아니라 기획서 2.1.2 의 하한이다.
            String sessionId = sessions.createSessionFor(googleUser());

            mockMvc.perform(patchNickname(sessionId, "가"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value("nickname"));
        }

        @Test
        @DisplayName("닉네임 변경_허용하지 않는 문자가 섞이면_필드별 오류와 함께 400 을 받는다")
        void updateCurrentUser_withDisallowedCharacters_returnsValidationError() throws Exception {
            String sessionId = sessions.createSessionFor(googleUser());

            // 리더보드·채팅이 이 값을 그대로 그리므로 공백·특수문자·이모지를 막는다.
            for (String rejected : new String[] {"두 단어", "홍길동!", "이름🙂"}) {
                mockMvc.perform(patchNickname(sessionId, rejected))
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.errors[0].field").value("nickname"));
            }
        }

        @Test
        @DisplayName("닉네임 변경_금칙어가 들어 있으면_400 을 받는다")
        void updateCurrentUser_withForbiddenWord_returnsBadRequest() throws Exception {
            // 채팅은 마스킹하지만 닉네임은 거부한다 (기획서 2.1.3).
            String sessionId = sessions.createSessionFor(googleUser());
            givenForbiddenWord(TEST_FORBIDDEN);

            try {
                mockMvc.perform(patchNickname(sessionId, "앞" + TEST_FORBIDDEN))
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.message").value("닉네임에 쓸 수 없는 말이 있습니다."));
            } finally {
                removeForbiddenWord(TEST_FORBIDDEN);
            }
        }

        @Test
        @DisplayName("닉네임 변경_이미 쓰이는 닉네임_400 을 받는다")
        void updateCurrentUser_withTakenNickname_returnsBadRequest() throws Exception {
            String taken = "선점" + Long.toString(System.nanoTime() % 100_000L);
            String owner = sessions.createSessionFor(googleUser());
            mockMvc.perform(patchNickname(owner, taken)).andExpect(status().isOk());

            String other = sessions.createSessionFor(googleUser());

            mockMvc.perform(patchNickname(other, taken))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("이미 쓰이는 닉네임입니다."));
        }

        @Test
        @DisplayName("닉네임 변경_지금 쓰는 자기 닉네임으로 다시 바꾸면_통과한다")
        void updateCurrentUser_withOwnNickname_isAllowed() throws Exception {
            // 자기 행이 중복으로 걸리면 사용자는 무엇을 잘못했는지 알 수 없다.
            String mine = "내이름" + Long.toString(System.nanoTime() % 100_000L);
            String sessionId = sessions.createSessionFor(googleUser());
            mockMvc.perform(patchNickname(sessionId, mine)).andExpect(status().isOk());

            mockMvc.perform(patchNickname(sessionId, mine))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.nickname").value(mine));
        }
    }

    @Nested
    @DisplayName("로그인 방법 표시")
    class LoginMethod {

        @Test
        @DisplayName("현재 사용자 조회_SSAFY 로 가입한 사용자_Provider 목록과 이메일을 받는다")
        void getCurrentUser_withSsafyIdentity_returnsProviderAndEmail() throws Exception {
            User user = ssafyUser();

            mockMvc.perform(get(ME_URI).header(AUTH_TOKEN_HEADER, sessions.createSessionFor(user)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.providers[0]").value("ssafy"))
                    .andExpect(jsonPath("$.data.email").value(user.getEmail()));
        }
    }

    @Nested
    @DisplayName("CSRF 보호")
    class CsrfProtection {

        @Test
        @DisplayName("닉네임 변경_쿠키 인증에 위조 방지 토큰이 없으면_거부된다")
        void updateCurrentUser_withoutCsrfToken_returnsForbidden() throws Exception {
            Cookie session = sessions.sessionCookie(sessions.createSessionFor(googleUser()));

            mockMvc.perform(patch(ME_URI).cookie(session)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"nickname\":\"새이름\"}"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("탈퇴_쿠키 인증에 위조 방지 토큰이 없으면_거부된다")
        void withdraw_withoutCsrfToken_returnsForbidden() throws Exception {
            Cookie session = sessions.sessionCookie(sessions.createSessionFor(googleUser()));

            mockMvc.perform(delete(ME_URI).cookie(session))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("탈퇴_쿠키 인증에 위조 방지 토큰이 있으면_통과한다")
        void withdraw_withCsrfToken_returnsNoContent() throws Exception {
            Cookie session = sessions.sessionCookie(sessions.createSessionFor(googleUser()));
            String token = csrfToken(mockMvc.perform(get(ME_URI).cookie(session)).andReturn());

            mockMvc.perform(delete(ME_URI)
                            .cookie(session, new Cookie(CSRF_COOKIE_NAME, token))
                            .header(CSRF_HEADER_NAME, token))
                    .andExpect(status().isNoContent());
        }
    }

    @Nested
    @DisplayName("탈퇴")
    class Withdraw {

        @Test
        @DisplayName("탈퇴_로그인한 사용자_User 가 사라진다")
        void withdraw_withLoggedInUser_deletesUser() throws Exception {
            User user = ssafyUser();

            mockMvc.perform(delete(ME_URI).header(AUTH_TOKEN_HEADER, sessions.createSessionFor(user)))
                    .andExpect(status().isNoContent());

            assertThat(userRepository.findById(user.getId())).isEmpty();
        }

        @Test
        @DisplayName("탈퇴_성공하면_그 User 의 세션이 저장소에서 사라진다")
        void withdraw_whenSucceeded_removesStoredSessions() throws Exception {
            User user = googleUser();
            String sessionId = sessions.createSessionFor(user);
            assertThat(sessions.hasStoredSession(user)).isTrue();

            mockMvc.perform(delete(ME_URI).header(AUTH_TOKEN_HEADER, sessionId))
                    .andExpect(status().isNoContent());

            assertThat(sessions.hasStoredSession(user)).isFalse();
        }

        /**
         * 문의는 작성자에 외래키를 갖고 DB CASCADE 가 없다. 탈퇴에서 문의를 먼저 지우지 않으면
         * 여기서 외래키 위반으로 깨진다 — 문의 기능의 버그가 아니라 <b>탈퇴의 회귀</b>로 나타나므로
         * 이 검증은 탈퇴 쪽에 둔다.
         */
        @Test
        @DisplayName("탈퇴_문의를 남긴 사용자_탈퇴가 성공하고 User 가 사라진다")
        void withdraw_withInquiry_succeeds() throws Exception {
            User user = googleUser();
            String sessionId = sessions.createSessionFor(user);
            mockMvc.perform(post(INQUIRIES_URI)
                            .header(AUTH_TOKEN_HEADER, sessionId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"title\":\"제목\",\"content\":\"내용\"}"))
                    .andExpect(status().isCreated());

            mockMvc.perform(delete(ME_URI).header(AUTH_TOKEN_HEADER, sessionId))
                    .andExpect(status().isNoContent());

            assertThat(userRepository.findById(user.getId())).isEmpty();
        }

        /**
         * 계좌에는 자식 테이블이 열 개 매달려 있고 DB CASCADE 가 없다. 하나라도 빠뜨리면
         * 탈퇴가 외래키 위반으로 깨진다 (ADR-0007).
         */
        @Test
        @DisplayName("탈퇴_거래·정산·수상 기록이 있는 사용자_탈퇴가 성공하고 계좌가 함께 사라진다")
        void withdraw_withTradingAndSettlementRecords_succeedsAndRemovesAccount() throws Exception {
            User user = googleUser();
            givenAccountWithRecords(user);

            mockMvc.perform(delete(ME_URI).header(AUTH_TOKEN_HEADER, sessions.createSessionFor(user)))
                    .andExpect(status().isNoContent());

            assertThat(userRepository.findById(user.getId())).isEmpty();
            assertThat(accounts.countOf(user.getId())).isZero();
        }

        /**
         * 관심종목은 계좌와 무관하게 <b>지금도</b> 탈퇴를 깨뜨리고 있던 선재 결함이다.
         * {@code watchlists.user_id} 에 DB CASCADE 가 없다.
         */
        @Test
        @DisplayName("탈퇴_관심종목을 등록한 사용자_탈퇴가 성공한다")
        void withdraw_withWatchlist_succeeds() throws Exception {
            User user = googleUser();
            jdbc.update("INSERT INTO watchlists (user_id, instrument_id, created_at) VALUES (?, ?, now())",
                    user.getId(), instrumentId());

            mockMvc.perform(delete(ME_URI).header(AUTH_TOKEN_HEADER, sessions.createSessionFor(user)))
                    .andExpect(status().isNoContent());

            assertThat(userRepository.findById(user.getId())).isEmpty();
        }

        @Test
        @DisplayName("현재 사용자 조회_탈퇴 후 기존 자격증명으로 요청하면_401 을 받는다")
        void getCurrentUser_afterWithdraw_returnsUnauthorized() throws Exception {
            User user = googleUser();
            String sessionId = sessions.createSessionFor(user);
            mockMvc.perform(delete(ME_URI).header(AUTH_TOKEN_HEADER, sessionId))
                    .andExpect(status().isNoContent());

            mockMvc.perform(get(ME_URI).header(AUTH_TOKEN_HEADER, sessionId))
                    .andExpect(status().isUnauthorized());
        }
    }

    /**
     * 계좌와 그에 매달린 행을 대표로 넷 심는다 — 예약·체결·확정 정산·개인 수상 알림이다.
     * 체결이 예약을 참조하고 확정 정산이 곧 수상 기록이므로 (수상 테이블이 없다), 이 넷이면
     * 삭제 순서가 전부 걸린다.
     *
     * <p>확정 정산이 체결을 가리키게 하고 체결이 예약을 가리키게 한다 — 자식 사이의 참조까지
     * 걸어야 삭제 순서가 실제로 검증된다.
     *
     * <p>예약은 체결된 상태로 심는다 — 미체결 예약은 잠금 컬럼이 채워져 있어야 한다는 제약이
     * 있고 (V14), 이 테스트가 보는 것은 잠금이 아니라 삭제 순서다.
     */
    private void givenAccountWithRecords(User user) {
        long accountId = accounts.create(user.getId());
        long instrumentId = instrumentId();
        jdbc.update("""
                INSERT INTO orders (account_id, instrument_id, side, limit_price, quantity, currency,
                                    status, expires_at, created_tick_seq, created_at, closed_at)
                VALUES (?, ?, 'BUY', 10000, 1, 'KRW', 'FILLED', now(), 1, now(), now())
                """, accountId, instrumentId);
        jdbc.update("""
                INSERT INTO executions (account_id, instrument_id, order_id, order_type, side, quantity,
                                        executed_price, currency, amount, applied_fee_rate, fee,
                                        tick_seq, game_date, executed_at)
                VALUES (?, ?, (SELECT id FROM orders WHERE account_id = ?), 'LIMIT', 'BUY', 1,
                        10000, 'KRW', 10000, 0.15, 15, 1, DATE '2020-01-02', now())
                """, accountId, instrumentId, accountId);
        jdbc.update("""
                INSERT INTO daily_settlements (season_day, account_id, season_rank, daily_rank,
                                               season_return, daily_return, total_valuation_krw,
                                               execution_count, total_fee_krw, realized_pnl_krw,
                                               best_execution_id, worst_execution_id,
                                               pattern_tags, finalized_at)
                VALUES (1, ?, 1, 1, 0, 0, 100000000, 1, 15, 0,
                        (SELECT id FROM executions WHERE account_id = ?),
                        (SELECT id FROM executions WHERE account_id = ?), '[]', now())
                """, accountId, accountId, accountId);
        jdbc.update("""
                INSERT INTO notifications (account_id, kind, title, body, tick_seq, created_at)
                VALUES (?, 'AWARD', '수상', '1위', 1, now())
                """, accountId);
    }

    /** 픽스처가 심은 검증용 종목. 예약·체결·관심종목이 가리킬 대상이다. */
    private long instrumentId() {
        return jdbc.queryForObject("SELECT id FROM instruments WHERE symbol = ?", Long.class, "REPLAY_TEST_A");
    }

    /** 사전에 한 단어만 넣는다. 넣고 나면 자동자를 다시 만들어야 판정에 반영된다. */
    private void givenForbiddenWord(String word) {
        jdbc.update("INSERT INTO forbidden_words (word, created_at) VALUES (?, now())", word);
        forbiddenWords.reload();
    }

    private void removeForbiddenWord(String word) {
        jdbc.update("DELETE FROM forbidden_words WHERE word = ?", word);
        forbiddenWords.reload();
    }

    private MockHttpServletRequestBuilder patchNickname(String sessionId, String nickname) {
        return patch(ME_URI)
                .header(AUTH_TOKEN_HEADER, sessionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"nickname\":\"" + nickname + "\"}");
    }

    /** 가입이 닉네임을 자동 생성하므로 이름을 정해 주지 않는다 — 유일성도 따라온다. */
    private User googleUser() {
        return userService.findOrCreate("google", UUID.randomUUID().toString(),
                UUID.randomUUID() + "@example.com");
    }

    /** SSAFY 는 이메일을 필수 동의항목으로 받으므로 이메일이 있다. */
    private User ssafyUser() {
        return userService.findOrCreate("ssafy", UUID.randomUUID().toString(),
                UUID.randomUUID() + "@example.com");
    }
}

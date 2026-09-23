package com.ssafy.a605.backend.domain.auth;

import static com.ssafy.a605.backend.support.SessionSupport.CSRF_COOKIE_NAME;
import static com.ssafy.a605.backend.support.SessionSupport.CSRF_HEADER_NAME;
import static com.ssafy.a605.backend.support.SessionSupport.SESSION_COOKIE_NAME;
import static com.ssafy.a605.backend.support.SessionSupport.csrfToken;
import static com.ssafy.a605.backend.support.SessionSupport.setCookieHeader;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.ssafy.a605.backend.domain.user.entity.Role;
import com.ssafy.a605.backend.domain.user.entity.User;
import com.ssafy.a605.backend.domain.user.service.UserService;
import com.ssafy.a605.backend.global.security.CookieOrHeaderHttpSessionIdResolver;
import com.ssafy.a605.backend.support.ContainersConfig;
import com.ssafy.a605.backend.support.SessionSupport;

import jakarta.servlet.http.Cookie;

/**
 * HTTP 경계에서 인증·인가를 검증한다. IdP 왕복은 자동화하지 않으므로, 토큰 교환 이후의
 * 상태(세션)를 만들어 두고 그 뒤의 모든 것을 확인한다.
 */
@Tag("container")
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(locations = "classpath:oauth2-test.properties")
@Import({ContainersConfig.class, SessionSupport.class})
class AuthenticationApiTest {

    private static final String ME_URI = "/api/v1/users/me";
    private static final String LOGOUT_URI = "/api/auth/logout";
    private static final String ADMIN_URI = "/api/v1/admin/users";
    private static final String LOGIN_START_URI = "/api/oauth2/authorization/google";

    private static final String AUTH_TOKEN_HEADER = CookieOrHeaderHttpSessionIdResolver.HEADER_NAME;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserService userService;

    @Autowired
    private SessionSupport sessions;

    @Nested
    @DisplayName("현재 사용자 조회")
    class CurrentUser {

        @Test
        @DisplayName("현재 사용자 조회_자격증명이 없으면_공통 에러 포맷의 401 을 받는다")
        void getCurrentUser_withoutCredentials_returnsUnauthorized() throws Exception {
            mockMvc.perform(get(ME_URI))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.status").value(401))
                    .andExpect(jsonPath("$.message").isNotEmpty());
        }

        @Test
        @DisplayName("현재 사용자 조회_발급받은 세션 쿠키로 요청하면_같은 사용자로 인식된다")
        void getCurrentUser_withSessionCookie_returnsCurrentUser() throws Exception {
            User user = createUser();

            mockMvc.perform(get(ME_URI).cookie(sessions.sessionCookie(sessions.createSessionFor(user))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(user.getId()))
                    .andExpect(jsonPath("$.data.nickname").value(user.getNickname()));
        }

        @Test
        @DisplayName("현재 사용자 조회_같은 세션 ID 를 인증 헤더로 보내면_동일하게 인식된다")
        void getCurrentUser_withAuthTokenHeader_returnsCurrentUser() throws Exception {
            User user = createUser();

            mockMvc.perform(get(ME_URI).header(AUTH_TOKEN_HEADER, sessions.createSessionFor(user)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(user.getId()));
        }
    }

    @Nested
    @DisplayName("세션 ID 운반")
    class SessionIdTransport {

        @Test
        @DisplayName("세션 쿠키_웹 응답에 실릴 때_스크립트 접근 차단·보안 채널·교차 사이트 제한이 걸린다")
        void startLogin_whenSessionIsCreated_setsSecureSessionCookie() throws Exception {
            MvcResult result = mockMvc.perform(get(LOGIN_START_URI))
                    .andExpect(status().is3xxRedirection())
                    .andReturn();

            assertThat(result.getResponse().getRedirectedUrl()).startsWith("https://accounts.google.com/");
            assertThat(setCookieHeader(result, SESSION_COOKIE_NAME))
                    .contains("HttpOnly")
                    .contains("Secure")
                    .contains("SameSite=Lax");
        }

        @Test
        @DisplayName("세션 ID_웹 응답일 때_인증 헤더로 실리지 않는다")
        void startLogin_whenSessionIsCreated_doesNotExposeSessionIdInHeader() throws Exception {
            MvcResult result = mockMvc.perform(get(LOGIN_START_URI)).andReturn();

            assertThat(result.getResponse().getHeader(AUTH_TOKEN_HEADER)).isNull();
        }
    }

    @Nested
    @DisplayName("CSRF 보호")
    class CsrfProtection {

        @Test
        @DisplayName("상태 변경 요청_쿠키 인증에 위조 방지 토큰이 없으면_거부된다")
        void logout_withSessionCookieAndWithoutCsrfToken_returnsForbidden() throws Exception {
            User user = createUser();

            mockMvc.perform(post(LOGOUT_URI).cookie(sessions.sessionCookie(sessions.createSessionFor(user))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("상태 변경 요청_쿠키 인증에 위조 방지 토큰이 있으면_통과한다")
        void logout_withSessionCookieAndCsrfToken_returnsNoContent() throws Exception {
            User user = createUser();
            Cookie session = sessions.sessionCookie(sessions.createSessionFor(user));
            String csrfToken = issueCsrfToken(session);

            mockMvc.perform(post(LOGOUT_URI)
                            .cookie(session, new Cookie(CSRF_COOKIE_NAME, csrfToken))
                            .header(CSRF_HEADER_NAME, csrfToken))
                    .andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("상태 변경 요청_인증 헤더로 보내면_위조 방지 토큰 없이도 통과한다")
        void logout_withAuthTokenHeader_returnsNoContent() throws Exception {
            User user = createUser();

            mockMvc.perform(post(LOGOUT_URI).header(AUTH_TOKEN_HEADER, sessions.createSessionFor(user)))
                    .andExpect(status().isNoContent());
        }
    }

    @Nested
    @DisplayName("로그아웃")
    class Logout {

        @Test
        @DisplayName("현재 사용자 조회_인증 헤더로 로그아웃한 뒤 같은 자격증명으로 요청하면_401 을 받는다")
        void getCurrentUser_afterLogoutWithAuthTokenHeader_returnsUnauthorized() throws Exception {
            User user = createUser();
            String sessionId = sessions.createSessionFor(user);
            mockMvc.perform(post(LOGOUT_URI).header(AUTH_TOKEN_HEADER, sessionId))
                    .andExpect(status().isNoContent());

            mockMvc.perform(get(ME_URI).header(AUTH_TOKEN_HEADER, sessionId))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("현재 사용자 조회_세션 쿠키로 로그아웃한 뒤 같은 쿠키로 요청하면_401 을 받는다")
        void getCurrentUser_afterLogoutWithSessionCookie_returnsUnauthorized() throws Exception {
            User user = createUser();
            Cookie session = sessions.sessionCookie(sessions.createSessionFor(user));
            String csrfToken = issueCsrfToken(session);
            mockMvc.perform(post(LOGOUT_URI)
                            .cookie(session, new Cookie(CSRF_COOKIE_NAME, csrfToken))
                            .header(CSRF_HEADER_NAME, csrfToken))
                    .andExpect(status().isNoContent());

            mockMvc.perform(get(ME_URI).cookie(session))
                    .andExpect(status().isUnauthorized());
        }
    }

    /**
     * 공개 경계의 양쪽을 한자리에서 본다 (ADR-0005). <b>열린 것이 열렸는지만 보면 안 된다</b> —
     * 공개 경로를 넓히다 개인 경로가 접두사째 딸려 열린 것을 놓친다.
     *
     * <p>공개 쪽은 "401 이 아니다"까지만 본다. 응답 내용은 각 슬라이스 테스트가 자기 픽스처로
     * 검증하며, 여기서 재생 상태를 세우면 그 검증을 두 곳에 복제하게 된다.
     */
    @Nested
    @DisplayName("공개 경계")
    class PublicBoundary {

        @Test
        @DisplayName("공개 조회_세션이 없어도_인증을 요구하지 않는다")
        void publicRoutes_withoutSession_doNotRequireAuthentication() throws Exception {
            for (String uri : List.of(
                    "/api/v1/seasons/current",
                    "/api/v1/game/clock",
                    "/api/v1/market/prices",
                    "/api/v1/market/instruments",
                    "/api/v1/market/instruments/1/closes",
                    "/api/v1/news?asOfGameDate=2020-01-02",
                    "/api/v1/news/ticker?asOfGameDate=2020-01-02",
                    "/api/v1/rankings",
                    "/api/v1/rankings/awards",
                    "/api/v1/chat/messages")) {
                mockMvc.perform(get(uri))
                        .andExpect(result -> assertThat(result.getResponse().getStatus())
                                .as("공개 경로 %s", uri)
                                .isNotEqualTo(401));
            }
        }

        /**
         * 개인 데이터는 <b>읽기 요청이라도</b> 열지 않는다. 목록에 정보 카드·판정 기준·종목 상세가
         * 섞여 있는 것은 그것이 개인 데이터라서가 아니라 <b>공개 화면이 부르지 않아서</b>다 —
         * 호출부가 생기면 그때 함께 연다.
         */
        @Test
        @DisplayName("공개하지 않은 조회_세션이 없으면_401 을 받는다")
        void nonPublicRoutes_withoutSession_returnUnauthorized() throws Exception {
            for (String uri : List.of(
                    "/api/v1/users/me",
                    "/api/v1/users/me/watchlist",
                    "/api/v1/rankings/me",
                    "/api/v1/accounts/me",
                    "/api/v1/orders",
                    "/api/v1/executions",
                    "/api/v1/notifications",
                    "/api/v1/social/heatmap",
                    "/api/v1/inquiries",
                    "/api/v1/reports/daily",
                    "/api/v1/reports/season",
                    "/api/v1/seasons/current/briefing",
                    "/api/v1/market/signal-criteria",
                    "/api/v1/market/instruments/1",
                    "/api/v1/market/instruments/1/info")) {
                mockMvc.perform(get(uri))
                        .andExpect(status().isUnauthorized());
            }
        }
    }

    @Nested
    @DisplayName("인가")
    class Authorization {

        @Test
        @DisplayName("관리자 전용 경로_권한이 부족한 사용자가 접근하면_공통 에러 포맷의 403 을 받는다")
        void adminPath_withUserRole_returnsForbidden() throws Exception {
            User user = createUser();

            mockMvc.perform(get(ADMIN_URI).header(AUTH_TOKEN_HEADER, sessions.createSessionFor(user)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.status").value(403))
                    .andExpect(jsonPath("$.message").isNotEmpty());
        }

        @Test
        @DisplayName("현재 사용자 조회_권한이 바뀐 뒤 기존 세션으로 요청하면_401 을 받는다")
        void getCurrentUser_afterRoleChange_returnsUnauthorized() throws Exception {
            User user = createUser();
            String sessionId = sessions.createSessionFor(user);
            mockMvc.perform(get(ME_URI).header(AUTH_TOKEN_HEADER, sessionId))
                    .andExpect(status().isOk());

            userService.changeRole(user.getId(), Role.ADMIN);

            mockMvc.perform(get(ME_URI).header(AUTH_TOKEN_HEADER, sessionId))
                    .andExpect(status().isUnauthorized());
        }
    }

    /** 가입이 닉네임을 자동 생성하므로 이름을 정해 주지 않는다 (기획서 1.1.4). */
    private User createUser() {
        return userService.findOrCreate("google", UUID.randomUUID().toString(),
                UUID.randomUUID() + "@example.com");
    }

    private String issueCsrfToken(Cookie session) throws Exception {
        return csrfToken(mockMvc.perform(get(ME_URI).cookie(session)).andReturn());
    }
}

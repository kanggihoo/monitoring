package com.ssafy.a605.backend.support;

import java.util.List;

import org.springframework.boot.test.context.TestComponent;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.test.web.servlet.MvcResult;

import com.ssafy.a605.backend.domain.user.entity.User;
import com.ssafy.a605.backend.global.security.LoginUser;

import jakarta.servlet.http.Cookie;
import lombok.RequiredArgsConstructor;

/**
 * IdP 왕복은 자동화하지 않으므로, 토큰 교환 이후의 상태(세션)를 여기서 만들어 두고 그 뒤의
 * 모든 것을 검증한다. `@Import` 로 가져다 쓴다.
 */
@TestComponent
@RequiredArgsConstructor
public class SessionSupport {

    public static final String SESSION_COOKIE_NAME = "SESSION";
    public static final String CSRF_COOKIE_NAME = "XSRF-TOKEN";
    public static final String CSRF_HEADER_NAME = "X-XSRF-TOKEN";

    private final FindByIndexNameSessionRepository<? extends Session> sessionRepository;
    private final CookieSerializer cookieSerializer;

    /** 로그인 성공 직후와 같은 모양의 세션을 만든다. */
    public String createSessionFor(User user) {
        return saveSession(sessionRepository, securityContextFor(user));
    }

    /**
     * 저장소에 이 User 의 세션이 남아 있는지 본다. 탈퇴는 행까지 지우므로 HTTP 응답만으로는
     * 세션을 실제로 끊었는지와 User 가 없어서 거부된 것인지를 구별할 수 없다.
     *
     * @param user 대상 User
     * @return 세션이 하나라도 남아 있으면 true
     */
    public boolean hasStoredSession(User user) {
        return !sessionRepository.findByPrincipalName(String.valueOf(user.getId())).isEmpty();
    }

    /** 세션 ID 를 브라우저가 실제로 돌려보낼 쿠키 값으로 바꾼다 (직렬화기가 값을 가공한다). */
    public Cookie sessionCookie(String sessionId) {
        MockHttpServletResponse response = new MockHttpServletResponse();
        cookieSerializer.writeCookieValue(
                new CookieSerializer.CookieValue(new MockHttpServletRequest(), response, sessionId));
        return new Cookie(SESSION_COOKIE_NAME, cookieValue(response.getHeader(HttpHeaders.SET_COOKIE)));
    }

    /**
     * 위조 방지 토큰은 조회 응답에 쿠키로 실려 온다.
     *
     * @param issued 토큰 쿠키가 실린 응답
     * @return 상태 변경 요청에 헤더로 되돌려 보낼 값
     */
    public static String csrfToken(MvcResult issued) {
        return cookieValue(setCookieHeader(issued, CSRF_COOKIE_NAME));
    }

    /**
     * @param result     응답
     * @param cookieName 찾을 쿠키 이름
     * @return 해당 쿠키의 Set-Cookie 헤더 한 줄
     */
    public static String setCookieHeader(MvcResult result, String cookieName) {
        return result.getResponse().getHeaders(HttpHeaders.SET_COOKIE).stream()
                .filter(header -> header.startsWith(cookieName + "="))
                .findFirst()
                .orElseThrow(() -> new AssertionError(cookieName + " 쿠키가 응답에 없다"));
    }

    /**
     * @param setCookieHeader Set-Cookie 헤더 한 줄
     * @return 속성을 뗀 쿠키 값
     */
    public static String cookieValue(String setCookieHeader) {
        int start = setCookieHeader.indexOf('=') + 1;
        int end = setCookieHeader.indexOf(';');
        return (end < 0) ? setCookieHeader.substring(start) : setCookieHeader.substring(start, end);
    }

    private static <S extends Session> String saveSession(FindByIndexNameSessionRepository<S> repository,
                                                          SecurityContext securityContext) {
        S session = repository.createSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, securityContext);
        repository.save(session);
        return session.getId();
    }

    private static SecurityContext securityContextFor(User user) {
        List<GrantedAuthority> authorities = List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()));
        return new SecurityContextImpl(new OAuth2AuthenticationToken(
                new LoginUser(user.getId(), authorities), authorities, "google"));
    }
}

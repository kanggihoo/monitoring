package com.ssafy.a605.backend.global.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.session.web.http.DefaultCookieSerializer;

import jakarta.servlet.http.Cookie;

/**
 * 세션 실체는 하나이고 운반 수단만 갈린다는 규칙(ADR-0002)이 이 클래스 하나에 들어 있다.
 * 분기 자체는 DB·Redis 가 필요 없어 컨테이너 없이 검증한다.
 */
class CookieOrHeaderHttpSessionIdResolverTest {

    private static final String SESSION_ID = "b3a1f0c2-0000-4000-8000-000000000001";

    private final CookieOrHeaderHttpSessionIdResolver resolver =
            new CookieOrHeaderHttpSessionIdResolver(new DefaultCookieSerializer());

    @Test
    @DisplayName("세션 ID 읽기_인증 헤더로 들어온 요청이면_헤더에서 읽는다")
    void resolveSessionIds_withAuthTokenHeader_readsFromHeader() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CookieOrHeaderHttpSessionIdResolver.HEADER_NAME, SESSION_ID);

        assertThat(resolver.resolveSessionIds(request)).containsExactly(SESSION_ID);
    }

    @Test
    @DisplayName("세션 ID 읽기_인증 헤더가 없으면_쿠키에서 읽는다")
    void resolveSessionIds_withoutAuthTokenHeader_readsFromCookie() {
        MockHttpServletResponse issued = new MockHttpServletResponse();
        resolver.setSessionId(new MockHttpServletRequest(), issued, SESSION_ID);

        MockHttpServletRequest request = new MockHttpServletRequest();
        carrySessionCookie(issued, request);

        assertThat(resolver.resolveSessionIds(request)).containsExactly(SESSION_ID);
    }

    @Test
    @DisplayName("세션 ID 쓰기_인증 헤더로 들어온 요청이면_헤더로만 응답한다")
    void setSessionId_withAuthTokenHeader_writesHeaderOnly() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CookieOrHeaderHttpSessionIdResolver.HEADER_NAME, SESSION_ID);
        MockHttpServletResponse response = new MockHttpServletResponse();

        resolver.setSessionId(request, response, SESSION_ID);

        assertThat(response.getHeader(CookieOrHeaderHttpSessionIdResolver.HEADER_NAME)).isEqualTo(SESSION_ID);
        assertThat(response.getHeader(HttpHeaders.SET_COOKIE)).isNull();
    }

    @Test
    @DisplayName("세션 ID 쓰기_쿠키로 들어온 요청이면_쿠키로만 응답한다")
    void setSessionId_withoutAuthTokenHeader_writesCookieOnly() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        resolver.setSessionId(new MockHttpServletRequest(), response, SESSION_ID);

        assertThat(response.getHeader(HttpHeaders.SET_COOKIE)).startsWith("SESSION=");
        assertThat(response.getHeader(CookieOrHeaderHttpSessionIdResolver.HEADER_NAME)).isNull();
    }

    /** 응답에 실린 세션 쿠키를 다음 요청으로 옮긴다. 직렬화기가 값을 가공하므로 직접 만들지 않는다. */
    private static void carrySessionCookie(MockHttpServletResponse from, MockHttpServletRequest to) {
        String nameAndValue = from.getHeader(HttpHeaders.SET_COOKIE).split(";", 2)[0];
        int separator = nameAndValue.indexOf('=');
        to.setCookies(new Cookie(nameAndValue.substring(0, separator), nameAndValue.substring(separator + 1)));
    }
}

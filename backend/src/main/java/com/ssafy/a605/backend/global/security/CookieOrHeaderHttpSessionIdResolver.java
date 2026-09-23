package com.ssafy.a605.backend.global.security;

import java.util.List;

import org.springframework.session.web.http.CookieHttpSessionIdResolver;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.HeaderHttpSessionIdResolver;
import org.springframework.session.web.http.HttpSessionIdResolver;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 세션 ID 를 웹은 쿠키로, 앱은 {@value #HEADER_NAME} 헤더로 나른다. 세션 실체는 하나이고
 * 운반 수단만 갈린다 (ADR-0002).
 *
 * <p>프레임워크의 {@code CompositeHttpSessionIdResolver} 를 쓰지 않는다. 읽기는 여러 곳에서
 * 하지만 쓰기는 위임 대상 전체에 하기 때문에, 웹 응답에도 세션 ID 가 헤더로 실려 쿠키의
 * 스크립트 접근 차단이 무력화된다.
 */
public class CookieOrHeaderHttpSessionIdResolver implements HttpSessionIdResolver {

    /** 앱이 세션 ID 를 실어 보내는 헤더. 브라우저가 자동으로 붙이지 않는다. */
    public static final String HEADER_NAME = "X-Auth-Token";

    private final CookieHttpSessionIdResolver cookieResolver = new CookieHttpSessionIdResolver();
    private final HttpSessionIdResolver headerResolver = new HeaderHttpSessionIdResolver(HEADER_NAME);

    /**
     * @param cookieSerializer 웹 응답에 쓸 세션 쿠키 직렬화기
     */
    public CookieOrHeaderHttpSessionIdResolver(CookieSerializer cookieSerializer) {
        this.cookieResolver.setCookieSerializer(cookieSerializer);
    }

    @Override
    public List<String> resolveSessionIds(HttpServletRequest request) {
        return resolverFor(request).resolveSessionIds(request);
    }

    @Override
    public void setSessionId(HttpServletRequest request, HttpServletResponse response, String sessionId) {
        resolverFor(request).setSessionId(request, response, sessionId);
    }

    @Override
    public void expireSession(HttpServletRequest request, HttpServletResponse response) {
        resolverFor(request).expireSession(request, response);
    }

    private HttpSessionIdResolver resolverFor(HttpServletRequest request) {
        return (request.getHeader(HEADER_NAME) != null) ? this.headerResolver : this.cookieResolver;
    }
}

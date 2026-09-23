package com.ssafy.a605.backend.global.security;

import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.security.oauth2.client.web.AuthorizationRequestRepository;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;

import lombok.RequiredArgsConstructor;

/**
 * state 없이 돌아온 콜백에 세션의 state 를 채워 넣는다. <b>대상 경로 하나에만 적용한다.</b>
 *
 * <p>SSAFY 인증 서버는 인가 코드만 돌려준다 (명세의 응답 쿼리 파라미터는 {@code Code} 하나다).
 * 프레임워크의 로그인 필터는 {@code code} 와 {@code state} 가 <b>둘 다</b> 있어야 그 요청을
 * 인가 응답으로 인정하므로, 그대로 두면 SSAFY 로그인은 토큰 교환까지 가지도 못한다.
 *
 * <p><b>이 경로에는 state 로 얻는 보호가 없다.</b> 서버가 값을 돌려주지 않으니 대조할 것이
 * 없고, 그것은 우리가 고를 수 있는 선택지가 아니다. 남는 위험은 로그인 CSRF — 공격자가
 * 자기 계정으로 남을 로그인시키는 것 — 이며, 세션 고정은 로그인 시 세션이 새로 발급되므로
 * 성립하지 않는다.
 *
 * <p>ponytail: SSAFY 가 state 를 되돌려주는 것으로 확인되면 이 필터와
 * {@link SessionAuthorizationRequestRepository} 를 통째로 지운다 — 그때는 프레임워크 기본
 * 동작이 그대로 맞는다.
 */
@RequiredArgsConstructor
public class OAuth2StateRestoringFilter extends OncePerRequestFilter {

    private final AuthorizationRequestRepository<OAuth2AuthorizationRequest> authorizationRequests;

    /** 이 경로의 콜백에만 손을 댄다. 다른 Provider 의 콜백은 그대로 흘려보낸다. */
    private final String callbackPath;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (needsState(request)) {
            OAuth2AuthorizationRequest pending = authorizationRequests.loadAuthorizationRequest(request);
            if (pending != null) {
                chain.doFilter(withState(request, pending.getState()), response);
                return;
            }
        }
        chain.doFilter(request, response);
    }

    private boolean needsState(HttpServletRequest request) {
        return callbackPath.equals(request.getRequestURI())
                && request.getParameter(OAuth2ParameterNames.CODE) != null
                && request.getParameter(OAuth2ParameterNames.STATE) == null;
    }

    /**
     * 파라미터 하나만 덧댄 요청. 로그인 필터는 {@code getParameterMap()} 으로 응답 전체를 읽고
     * 그 뒤 값 하나씩도 읽으므로 네 가지 읽는 길을 모두 맞춰 준다.
     */
    private static HttpServletRequest withState(HttpServletRequest request, String state) {
        return new HttpServletRequestWrapper(request) {

            @Override
            public Map<String, String[]> getParameterMap() {
                Map<String, String[]> parameters = new LinkedHashMap<>(super.getParameterMap());
                parameters.put(OAuth2ParameterNames.STATE, new String[] {state});
                return Collections.unmodifiableMap(parameters);
            }

            @Override
            public String getParameter(String name) {
                return OAuth2ParameterNames.STATE.equals(name) ? state : super.getParameter(name);
            }

            @Override
            public String[] getParameterValues(String name) {
                return OAuth2ParameterNames.STATE.equals(name)
                        ? new String[] {state} : super.getParameterValues(name);
            }

            @Override
            public Enumeration<String> getParameterNames() {
                return Collections.enumeration(getParameterMap().keySet());
            }
        };
    }
}

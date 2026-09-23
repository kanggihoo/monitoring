package com.ssafy.a605.backend.global.security;

import org.springframework.security.oauth2.client.web.AuthorizationRequestRepository;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

/**
 * 진행 중인 인가 요청을 세션에 담는다. 프레임워크 기본 구현과 다른 점은 하나뿐이다 —
 * <b>state 를 조회 키로 쓰지 않는다.</b>
 *
 * <p>SSAFY 는 콜백에 {@code code} 만 돌려주고 state 를 되돌려주지 않는다. 기본 구현은 요청
 * 파라미터의 state 로 저장된 요청을 찾으므로 그 경로에서 아무것도 찾지 못한다. 여기서는 세션에
 * 하나만 담고 그대로 꺼낸다 — 저장도 원래 하나뿐이라 의미가 달라지지 않는다.
 *
 * <p><b>state 검증이 사라지지는 않는다.</b> 응답과 요청의 state 비교는 그 뒤 인증 Provider 가
 * 하며, Google 로그인은 그 검사를 그대로 통과한다 ({@link OAuth2StateRestoringFilter} 가 값을
 * 지어내는 것은 SSAFY 콜백 하나뿐이다).
 */
public class SessionAuthorizationRequestRepository
        implements AuthorizationRequestRepository<OAuth2AuthorizationRequest> {

    private static final String ATTRIBUTE_NAME =
            SessionAuthorizationRequestRepository.class.getName() + ".AUTHORIZATION_REQUEST";

    @Override
    public OAuth2AuthorizationRequest loadAuthorizationRequest(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        return (session == null) ? null : (OAuth2AuthorizationRequest) session.getAttribute(ATTRIBUTE_NAME);
    }

    @Override
    public void saveAuthorizationRequest(OAuth2AuthorizationRequest authorizationRequest,
                                         HttpServletRequest request, HttpServletResponse response) {
        // 프레임워크는 실패 처리에서 null 저장으로 삭제를 요청한다.
        if (authorizationRequest == null) {
            removeAuthorizationRequest(request, response);
            return;
        }
        request.getSession().setAttribute(ATTRIBUTE_NAME, authorizationRequest);
    }

    @Override
    public OAuth2AuthorizationRequest removeAuthorizationRequest(HttpServletRequest request,
                                                                 HttpServletResponse response) {
        OAuth2AuthorizationRequest authorizationRequest = loadAuthorizationRequest(request);
        if (authorizationRequest != null) {
            request.getSession().removeAttribute(ATTRIBUTE_NAME);
        }
        return authorizationRequest;
    }
}

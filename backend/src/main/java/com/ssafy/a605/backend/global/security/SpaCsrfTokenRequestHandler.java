package com.ssafy.a605.backend.global.security;

import java.util.function.Supplier;

import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 쿠키에 담긴 CSRF 토큰을 그대로 헤더로 돌려주는 클라이언트를 위한 핸들러.
 *
 * <p>프레임워크 기본 동작에서 두 가지를 바꾼다.
 * <ul>
 *   <li>기본 핸들러는 토큰을 마스킹(XOR)해서 내보내므로, 쿠키 값을 그대로 보내는 클라이언트의
 *       토큰을 풀지 못한다. 마스킹하지 않는 {@link CsrfTokenRequestAttributeHandler} 를 쓴다.
 *   <li>토큰은 지연 생성이라 아무도 값을 읽지 않으면 쿠키가 실리지 않는다. 매 요청에서 값을
 *       한 번 읽어 응답에 쿠키가 실리게 한다.
 * </ul>
 */
public final class SpaCsrfTokenRequestHandler extends CsrfTokenRequestAttributeHandler {

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       Supplier<CsrfToken> csrfToken) {
        super.handle(request, response, csrfToken);
        csrfToken.get();
    }
}

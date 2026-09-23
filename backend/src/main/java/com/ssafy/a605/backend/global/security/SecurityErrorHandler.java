package com.ssafy.a605.backend.global.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

import com.ssafy.a605.backend.global.response.ErrorResponse;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import tools.jackson.databind.ObjectMapper;

/**
 * 시큐리티 필터 단계에서 걸러진 요청을 프로젝트 공통 에러 포맷으로 응답한다.
 *
 * <p>필터가 막은 요청은 컨트롤러에 닿지 않아 {@code GlobalExceptionHandler} 를 타지 않는다.
 * 프레임워크 기본 동작에 맡기면 미인증은 본문 없이, 인가 실패는 {@code sendError} 를 거쳐
 * 서블릿 컨테이너의 기본 에러 본문으로 나가면서 같은 API 의 오류 응답 모양이 갈린다.
 * 여기서 직접 써서 401·403 도 {@link ErrorResponse} 로 통일한다.
 *
 * <p>본문에는 상태 코드와 메시지만 담는다 (내부 구현 정보 노출 금지).
 *
 * <p>미인증과 인가 실패를 상태 코드로 가른다 — 인증하지 않은 요청은 어느 경로로 막혔든 401 이다
 * ({@link #handle} 주석 참조).
 */
@RequiredArgsConstructor
public class SecurityErrorHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authenticationException) throws IOException {
        write(request, response, HttpStatus.UNAUTHORIZED, "인증이 필요합니다.");
    }

    /**
     * 인가 실패. <b>인증하지 않은 요청은 403 이 아니라 401 이다</b> — 표가 401 을 "인증 실패",
     * 403 을 "접근 권한 부족"으로 갈라 두었고 (conventions/api.md 2절), 아직 누구인지도 모르는
     * 요청에 "권한이 없다"고 답하면 클라이언트가 재로그인 대신 포기한다.
     *
     * <p>이 판정이 여기 필요한 이유는 {@code CsrfFilter} 다. 그 필터는 인증 필터들보다 앞에
     * 있고 이 핸들러를 <b>직접</b> 부르므로, 익명 요청을 인증 진입점으로 보내는
     * {@code ExceptionTranslationFilter} 의 처리를 타지 않는다. 그래서 미인증 상태 변경
     * 요청(쿠키·토큰 없는 POST·PUT·DELETE)이 전부 403 으로 나가고 있었다. 같은 판정을 여기서
     * 한 번 더 해 두면 두 경로의 답이 갈리지 않는다.
     *
     * <p>{@code CsrfFilter} 시점에는 {@code AnonymousAuthenticationFilter} 가 아직 돌지 않아
     * 인증이 {@code null} 이다. 세션이 있는 요청은 {@code SecurityContextHolderFilter} 가 이미
     * 채워 두었으므로 로그인한 사용자의 토큰 불일치는 그대로 403 이다.
     */
    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        if (isUnauthenticated(SecurityContextHolder.getContext().getAuthentication())) {
            commence(request, response, null);
            return;
        }
        write(request, response, HttpStatus.FORBIDDEN, "접근 권한이 없습니다.");
    }

    /** 아직 인증되지 않은 주체. 익명 토큰도 인증으로 보지 않는다. */
    private static boolean isUnauthenticated(Authentication authentication) {
        return authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken;
    }

    private void write(HttpServletRequest request, HttpServletResponse response,
                       HttpStatus status, String message) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getOutputStream(),
                ErrorResponse.of(status, message, request.getRequestURI()));
    }
}

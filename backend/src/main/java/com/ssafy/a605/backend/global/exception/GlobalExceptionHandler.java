package com.ssafy.a605.backend.global.exception;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import com.ssafy.a605.backend.global.response.ErrorResponse;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 재시도 가능한 거부에는 언제 다시 오면 되는지를 {@code Retry-After} 로 함께 내려준다 —
     * 화면이 남은 시간을 그릴 수 있어야 하고, 그 값을 본문에 넣으면 표준 헤더를 두고 우리 규약을
     * 하나 더 만드는 일이 된다.
     *
     * <p>거부가 <b>사용자가 취할 행동</b>을 갖고 있으면 그것을 {@code data} 에 싣는다. 청약의
     * 잔고 부족이 부족분과 환전 요청을 그렇게 내려보낸다 ({@code ipo.md}).
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusiness(BusinessException e, HttpServletRequest request) {
        ResponseEntity.BodyBuilder response = ResponseEntity.status(e.getStatus());
        if (e.getRetryAfterSeconds() != null) {
            response.header(HttpHeaders.RETRY_AFTER, String.valueOf(e.getRetryAfterSeconds()));
        }
        return response.body(ErrorResponse.of(e.getStatus(), e.getMessage(), List.of(), e.getData(),
                request.getRequestURI()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException e,
                                                          HttpServletRequest request) {
        List<ErrorResponse.FieldError> errors = e.getBindingResult().getFieldErrors().stream()
                .map(f -> new ErrorResponse.FieldError(f.getField(), f.getRejectedValue(), f.getDefaultMessage()))
                .toList();
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of(HttpStatus.BAD_REQUEST, "입력값이 올바르지 않습니다.", errors,
                        request.getRequestURI()));
    }

    /**
     * 역직렬화 실패. record의 원시 타입에 값이 없는 요청도 여기로 들어온다 (conventions/code.md §1).
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleNotReadable(HttpMessageNotReadableException e,
                                                           HttpServletRequest request) {
        log.warn("Malformed request body: {}", request.getRequestURI(), e);
        return build(HttpStatus.BAD_REQUEST, "요청 본문을 읽을 수 없습니다.", request);
    }

    /**
     * 경로 변수·쿼리 파라미터를 선언된 타입으로 바꾸지 못했다. 열거형에 없는 값이나 숫자가 아닌
     * 식별자가 여기로 들어온다. 명시하지 않으면 아래 포괄 핸들러가 잡아 400 이어야 할 것이 500 이 된다.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException e,
                                                            HttpServletRequest request) {
        // 어느 값이 왜 틀렸는지는 예외가 이미 갖고 있다. 검증 실패와 같은 형태로 돌려준다
        // (conventions/api.md 2절). 기대 타입 이름은 내부 구현이므로 싣지 않는다.
        List<ErrorResponse.FieldError> errors =
                List.of(new ErrorResponse.FieldError(e.getName(), e.getValue(), "값의 형식이 올바르지 않습니다."));
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of(HttpStatus.BAD_REQUEST, "입력값이 올바르지 않습니다.", errors,
                        request.getRequestURI()));
    }

    /**
     * 필수 쿼리 파라미터가 빠졌다. 명시하지 않으면 아래 포괄 핸들러가 잡아 400 이어야 할 것이 500 이 된다.
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingParameter(MissingServletRequestParameterException e,
                                                                HttpServletRequest request) {
        List<ErrorResponse.FieldError> errors =
                List.of(new ErrorResponse.FieldError(e.getParameterName(), null, "필수 값입니다."));
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of(HttpStatus.BAD_REQUEST, "입력값이 올바르지 않습니다.", errors,
                        request.getRequestURI()));
    }

    /**
     * 매핑된 핸들러가 없다. 조건부로 꺼진 기능의 경로와 오타 난 경로가 여기로 들어온다.
     * 명시하지 않으면 아래 포괄 핸들러가 잡아 404 여야 할 것이 500 이 된다.
     */
    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
    public ResponseEntity<ErrorResponse> handleNoHandler(HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, "요청한 경로를 찾을 수 없습니다.", request);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException e, HttpServletRequest request) {
        return build(HttpStatus.FORBIDDEN, "접근 권한이 없습니다.", request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception e, HttpServletRequest request) {
        log.error("Unhandled exception: {}", request.getRequestURI(), e);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "서버 오류가 발생했습니다.", request);
    }

    private ResponseEntity<ErrorResponse> build(HttpStatus status, String message, HttpServletRequest request) {
        return ResponseEntity.status(status)
                .body(ErrorResponse.of(status, message, request.getRequestURI()));
    }
}

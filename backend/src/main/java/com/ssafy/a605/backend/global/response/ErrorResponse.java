package com.ssafy.a605.backend.global.response;

import java.time.Instant;
import java.util.List;

import org.springframework.http.HttpStatus;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 오류 응답 ({@code _common.md §4}). 거부의 식별은 <b>상태 코드와 {@code message}</b> 가 한다 —
 * 별도의 비즈니스 오류 코드를 두지 않는다.
 *
 * @param data 사용자가 취할 행동. 거의 모든 거부에 없으므로 <b>있을 때만</b> 직렬화된다 —
 *             {@code "data": null} 을 늘 실으면 형식이 바뀌었다고 읽힌다
 */
public record ErrorResponse(
        boolean success,
        int status,
        String message,
        List<FieldError> errors,
        @JsonInclude(JsonInclude.Include.NON_NULL) Object data,
        String path,
        Instant timestamp
) {

    public record FieldError(String field, Object rejectedValue, String reason) {
    }

    public static ErrorResponse of(HttpStatus status, String message, String path) {
        return of(status, message, List.of(), path);
    }

    public static ErrorResponse of(HttpStatus status, String message, List<FieldError> errors, String path) {
        return of(status, message, errors, null, path);
    }

    /**
     * @param data 사용자가 취할 행동. 없으면 {@code null} 이고 그때는 응답에 실리지 않는다
     * @return 행동을 함께 담은 오류 응답
     */
    public static ErrorResponse of(HttpStatus status, String message, List<FieldError> errors,
                                   Object data, String path) {
        return new ErrorResponse(false, status.value(), message, errors, data, path, Instant.now());
    }
}

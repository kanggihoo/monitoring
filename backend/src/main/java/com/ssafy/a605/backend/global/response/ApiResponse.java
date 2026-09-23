package com.ssafy.a605.backend.global.response;

import java.time.Instant;

import org.springframework.http.HttpStatus;

public record ApiResponse<T>(
        boolean success,
        int status,
        String message,
        T data,
        Instant timestamp
) {

    public static <T> ApiResponse<T> ok(T data) {
        return of(HttpStatus.OK, null, data);
    }

    public static <T> ApiResponse<T> ok(String message, T data) {
        return of(HttpStatus.OK, message, data);
    }

    public static ApiResponse<Void> ok(String message) {
        return of(HttpStatus.OK, message, null);
    }

    public static <T> ApiResponse<T> created(String message, T data) {
        return of(HttpStatus.CREATED, message, data);
    }

    public static <T> ApiResponse<T> of(HttpStatus status, String message, T data) {
        return new ApiResponse<>(true, status.value(), message, data, Instant.now());
    }
}

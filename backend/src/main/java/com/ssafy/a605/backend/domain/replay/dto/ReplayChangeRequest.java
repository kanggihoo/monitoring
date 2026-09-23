package com.ssafy.a605.backend.domain.replay.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Null;

/**
 * 재생 조작 요청. <b>배속 변경 전용이다.</b>
 *
 * <p><b>배속을 {@code BigDecimal} 로 받는 것이 계약의 일부다.</b> {@code double} 로 받으면
 * {@code 1.100000000000000088817841970012523} 같은 입력이 {@code 1.1} 로 조용히 반올림되어
 * 통과한다 — 스펙은 "초과 소수 정밀도를 거절하며 조용히 반올림하지 않는다"를 요구한다.
 * Jackson 은 대상 타입이 {@code BigDecimal} 이면 표기된 자릿수를 그대로 보존하므로
 * {@link Digits} 가 입력 그대로를 본다. DB의 {@code scale(speed) <= 2} CHECK 와 같은 계약이다.
 *
 * <p><b>시작 시각 예약은 없앴다.</b> 관리자가 "이 실제 시각에 1일차를 시작하라"를 거는
 * 조작이었는데, 지점 이동({@code POST /seek})이 들어오면서 개발 중 진행 수단으로서의 값이
 * 사라졌다. 프로덕션의 "시작 전 대기"는 그대로 남는다 — 그것은 API 가 아니라 적재 스크립트가
 * 앵커 시각을 미래로 심어 만드는 상태다.
 *
 * @param speed           0~100, 소수 둘째 자리까지의 배속. 0 이 정지다
 * @param anchorGameMonth 되감기 좌표. 이번 범위 밖이라 값이 있으면 거절한다
 * @param anchorTickSeq   되감기 좌표. 이번 범위 밖이라 값이 있으면 거절한다
 */
public record ReplayChangeRequest(
        @NotNull(message = "배속은 필수입니다.")
        @DecimalMin(value = "0", message = "배속은 0 이상이어야 합니다.")
        @DecimalMax(value = "100", message = "배속은 100 이하여야 합니다.")
        @Digits(integer = 3, fraction = 2, message = "배속은 소수 둘째 자리까지입니다.")
        BigDecimal speed,

        @Null(message = "되감기는 지원하지 않습니다.")
        Object anchorGameMonth,

        @Null(message = "되감기는 지원하지 않습니다.")
        Object anchorTickSeq
) {
}

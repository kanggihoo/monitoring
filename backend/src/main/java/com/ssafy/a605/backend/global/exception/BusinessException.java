package com.ssafy.a605.backend.global.exception;

import org.springframework.http.HttpStatus;

import lombok.Getter;

@Getter
public class BusinessException extends RuntimeException {

    private final HttpStatus status;

    /**
     * 다시 시도할 수 있게 되기까지 남은 초. 없으면 {@code null} 이고, 있으면 응답에
     * {@code Retry-After} 헤더가 붙는다.
     *
     * <p>재시도 가능 여부는 HTTP 의 개념이라 여기에 둔다 — 도메인이 헤더를 알 필요는 없고,
     * "이 거부는 잠시 뒤 풀린다"는 사실만 알면 된다. 지금 쓰는 곳은 채팅 도배 방지 쿨다운이다
     * ({@code soc.md}).
     */
    private final Long retryAfterSeconds;

    /**
     * 거부와 함께 내려보낼 <b>사용자가 취할 행동</b>. 없으면 {@code null} 이고, 있으면 오류 응답의
     * {@code data} 에 그대로 실린다.
     *
     * <p>지금 쓰는 곳은 청약의 잔고 부족이다 ({@code ipo.md}) — 부족분과 그것을 채우는 환전
     * 요청을 담아, 화면이 거부 자리에서 바로 환전 버튼을 그린다. 주문은 <b>미리보기</b>가 같은
     * 값을 성공 응답으로 주므로 이 자리를 쓰지 않는다. 청약에는 미리보기가 없다 — 신청 단위가
     * 고정이라 사용자가 조절할 값이 없고, 그래서 계산해 볼 것도 없기 때문이다.
     *
     * <p><b>오류 코드를 대신하는 자리가 아니다.</b> 거부의 식별은 여전히 상태 코드와
     * {@code message} 가 한다 ({@code _common.md §4}). 여기 담기는 것은 <i>왜 거부됐는지</i>가
     * 아니라 <i>무엇을 하면 되는지</i>다.
     */
    private final Object data;

    public BusinessException(HttpStatus status, String message) {
        this(status, message, null, null);
    }

    /**
     * @param status            응답 상태
     * @param message           한국어 사용자 메시지
     * @param retryAfterSeconds 다시 시도할 수 있게 되기까지 남은 초
     */
    public BusinessException(HttpStatus status, String message, Long retryAfterSeconds) {
        this(status, message, retryAfterSeconds, null);
    }

    /**
     * @param status  응답 상태
     * @param message 한국어 사용자 메시지
     * @param data    사용자가 취할 행동. 오류 응답의 {@code data} 가 된다
     * @return 행동을 함께 알리는 거부
     */
    public static BusinessException with(HttpStatus status, String message, Object data) {
        return new BusinessException(status, message, null, data);
    }

    private BusinessException(HttpStatus status, String message, Long retryAfterSeconds, Object data) {
        super(message);
        this.status = status;
        this.retryAfterSeconds = retryAfterSeconds;
        this.data = data;
    }
}

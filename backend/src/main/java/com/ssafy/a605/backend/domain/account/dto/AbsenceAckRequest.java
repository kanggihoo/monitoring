package com.ssafy.a605.backend.domain.account.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 부재 요약 확인 처리 ({@code acc.md}).
 *
 * <p><b>클라이언트가 실제로 본 틱을 돌려보낸다.</b> 서버가 수신 시점의 틱으로 갱신하면, 모달을
 * 다섯 시간 띄워 둔 채 자리를 비웠다가 닫는 경우에 그 다섯 시간까지 "본 것"이 되어 버린다.
 * 요약 응답의 {@code toTickSeq} 를 그대로 넣으면 확인되는 것은 <b>모달이 보여준 지점까지</b>다.
 *
 * @param ackedTickSeq 모달이 보여준 마지막 틱
 */
public record AbsenceAckRequest(@NotNull @PositiveOrZero Long ackedTickSeq) {
}

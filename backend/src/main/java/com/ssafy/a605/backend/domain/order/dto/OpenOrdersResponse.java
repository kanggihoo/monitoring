package com.ssafy.a605.backend.domain.order.dto;

import java.util.List;

/**
 * 미체결 예약 목록 (기획서 6.3.1, {@code ord.md}). 수정·취소의 대상이며 폐장에 소멸한다.
 *
 * <p><b>페이지를 두지 않는다.</b> 1인 1계좌에 미체결 예약은 몇 건이고, 틱마다 갱신되는
 * 목록이라 나눠 읽으면 페이지 사이에 체결이 일어나 같은 예약이 두 번 보이거나 빠진다.
 *
 * <p><b>{@code asOfTickSeq} 를 함께 싣는 이유</b>는 이 목록이 <b>그 틱 기준의 사실</b>이기
 * 때문이다 ({@code _common.md} §9). 프론트는 이 값으로 같은 틱의 {@code market.prices} 와 짝을
 * 맞춰, 화면의 시세와 예약 목록이 서로 다른 시점을 가리키지 않게 한다.
 *
 * @param asOfTickSeq 공개 기준 틱. 최초 공개 전에는 {@code null}
 * @param orders      미체결 예약. 등록 순서다
 */
public record OpenOrdersResponse(Long asOfTickSeq, List<OpenOrderResponse> orders) {
}

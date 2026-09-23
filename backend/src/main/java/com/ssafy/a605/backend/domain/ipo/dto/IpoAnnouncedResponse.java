package com.ssafy.a605.backend.domain.ipo.dto;

/**
 * 추첨 결과 전체 공지 ({@code ipo.announced}, 기획서 8.4.3). "○○○명 당첨"만 알린다.
 *
 * <p><b>누가 당첨됐는지는 담지 않는다.</b> 개인의 당락은 {@code ipo.result} 가 그 사람에게만
 * 보낸다 — 전체 공지에 명단이 실리면 남의 계좌 사정이 드러난다.
 *
 * @param offeringId  추첨을 마친 공모
 * @param winnerCount 확정된 당첨자 수. 청약자가 없었으면 0
 */
public record IpoAnnouncedResponse(Long offeringId, int winnerCount) {
}

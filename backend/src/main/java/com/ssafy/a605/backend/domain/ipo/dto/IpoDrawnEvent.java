package com.ssafy.a605.backend.domain.ipo.dto;

import java.util.List;

import com.ssafy.a605.backend.domain.ipo.service.IpoDrawService.Awarded;

/**
 * 한 공모의 추첨이 끝났다는 프로세스 내부 신호. 알림 센터가 청약자 전원에게 당첨·낙첨
 * 개인 알림을 남긴다 ({@code noti.md} §IPO_RESULT, 기획서 8.4.3).
 *
 * <p><b>낙첨자에게도 알린다.</b> 기획서 8.4.3 이 "개인 알림(당첨·낙첨)"이라고 둘을 나란히
 * 적었고, 낙첨자에게는 증거금이 반환됐다는 사실이 그 자체로 알려야 할 정보다 (8.4.4).
 *
 * <p>추첨 트랜잭션이 커밋된 뒤에 발행된다 — 알림 실패가 추첨을 롤백시키면 그 공모는
 * 다시 뽑히게 되고 당첨자가 바뀐다.
 *
 * @param offeringId 추첨한 공모
 * @param awarded    청약자별 결과. 당첨과 낙첨이 섞여 있다
 */
public record IpoDrawnEvent(Long offeringId, List<Awarded> awarded) {
}

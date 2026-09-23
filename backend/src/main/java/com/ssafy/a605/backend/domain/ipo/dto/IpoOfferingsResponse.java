package com.ssafy.a605.backend.domain.ipo.dto;

import java.util.List;

/**
 * 공시된 공모 목록 (기획서 8.1 · 8.3, {@code ipo.md}).
 *
 * <p><b>페이지를 두지 않는다.</b> 시즌 전체가 8건이고, 그중 화면이 관심 갖는 것은 창이 열린
 * 한둘이다 (기획서 IPO 편성표).
 *
 * @param asOfTickSeq 이 응답이 선 시점. 경쟁률과 국면이 이 틱 기준이며, 개장 전이라 공개된
 *                    틱이 없으면 {@code null} 이다
 * @param offerings   공시 순서대로. 공시 전 공모는 들어 있지 않다
 */
public record IpoOfferingsResponse(Long asOfTickSeq, List<IpoOfferingResponse> offerings) {
}

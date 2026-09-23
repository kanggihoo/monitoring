package com.ssafy.a605.backend.domain.onboarding.dto;

import java.time.Instant;

/**
 * 저장된 설문(선언) 성향.
 *
 * <p><b>채점하지 않는다.</b> 합계와 단계는 화면이 낸다 — 구간 경계가 베타에서 움직이는 값이라
 * 서버가 채점하면 그 정의가 배포에 굳는다. 서버는 답 원본을 맡아 두는 역할만 한다.
 *
 * @param answers   다섯 문항의 답 원본. 각 자리 1~4 이며 <b>아직 답하지 않았으면 {@code null}</b>
 * @param updatedAt 마지막으로 저장한 시각. 답이 없으면 {@code null}
 */
public record SelfProfileResponse(String answers, Instant updatedAt) {

    /** 아직 답하지 않은 상태. 404 가 아니라 빈 값이다 — 설문은 선택이고 미응답이 정상이다. */
    public static SelfProfileResponse empty() {
        return new SelfProfileResponse(null, null);
    }
}

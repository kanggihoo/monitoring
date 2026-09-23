package com.ssafy.a605.backend.domain.onboarding.controller.api;

import org.springframework.security.core.annotation.AuthenticationPrincipal;

import com.ssafy.a605.backend.domain.onboarding.dto.SelfProfileRequest;
import com.ssafy.a605.backend.domain.onboarding.dto.SelfProfileResponse;
import com.ssafy.a605.backend.global.response.ApiResponse;
import com.ssafy.a605.backend.global.security.LoginUser;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 온보딩 설문. 문서만 담당하며 구현은 컨트롤러가 한다.
 *
 * <p><b>인증 사용자 전용이다.</b> 답이 계좌에 붙으므로 세션 없이는 저장할 자리가 없다.
 */
@Tag(name = "Onboarding", description = "온보딩 설문 API")
public interface OnboardingApi {

    @Operation(
            summary = "내 설문 성향 조회",
            description = "다섯 문항의 답 원본(5글자)과 마지막 저장 시각을 돌려준다. "
                    + "**아직 답하지 않았으면 404 가 아니라 answers · updatedAt 이 모두 null 이다** — "
                    + "설문은 선택이고 미응답이 정상 경로다. 그 경우 복기 결과지는 여덟 칸 중 "
                    + "'내가 생각한 나'와 '예상과 실제의 차이' 두 칸만 빠진 채로 열린다. "
                    + "합계와 단계는 내려주지 않는다 — 채점은 화면이 한다. "
                    + "그 시즌에 참가하지 않아 계좌가 없으면 404 다."
    )
    ApiResponse<SelfProfileResponse> getSelfProfile(@AuthenticationPrincipal LoginUser loginUser);

    @Operation(
            summary = "내 설문 성향 저장",
            description = "답 원본 5글자를 덮어쓴다. 각 자리가 1~4 여야 하고 형식이 어긋나면 400 이다. "
                    + "**언제든 다시 고칠 수 있다** — 최초 저장과 수정을 가르지 않으므로 PUT 이며, "
                    + "같은 값을 여러 번 보내도 결과가 같고 저장 시각만 갱신된다. "
                    + "합산값이 아니라 원본을 저장하는 이유는 단계 구간을 나중에 조정해도 "
                    + "과거 답을 다시 채점할 수 있어야 하기 때문이다. "
                    + "저장한 값은 다음 폐장의 AI 일일 요청에 실려 결과지의 '내가 생각한 나'가 된다. "
                    + "그 시즌에 참가하지 않아 계좌가 없으면 404 다."
    )
    ApiResponse<SelfProfileResponse> putSelfProfile(@AuthenticationPrincipal LoginUser loginUser,
                                                    SelfProfileRequest request);
}

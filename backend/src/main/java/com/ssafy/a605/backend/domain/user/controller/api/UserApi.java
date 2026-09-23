package com.ssafy.a605.backend.domain.user.controller.api;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.RequestBody;

import com.ssafy.a605.backend.domain.user.dto.UserResponse;
import com.ssafy.a605.backend.domain.user.dto.UserUpdateRequest;
import com.ssafy.a605.backend.global.response.ApiResponse;
import com.ssafy.a605.backend.global.security.LoginUser;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/**
 * 마이페이지의 조회·수정·탈퇴. 문서만 담당하며 구현은 컨트롤러가 한다.
 *
 * <p>세 엔드포인트 모두 세션이 필요하다. 세션 ID 는 웹이면 {@code SESSION} 쿠키로, 앱이면
 * {@code X-Auth-Token} 헤더로 나른다 (ADR-0002). 쿠키로 인증하는 상태 변경 요청에는
 * {@code X-XSRF-TOKEN} 헤더가 함께 필요하다 — 헤더로 인증하면 이 검사는 면제된다.
 */
@Tag(name = "User", description = "마이페이지 API")
public interface UserApi {

    @Operation(
            summary = "현재 사용자 조회",
            description = "세션 주체의 정보와 로그인 방법(Provider 목록)을 반환한다. "
                    + "인증 여부를 묻는 API 가 아니라, 미인증이면 401 이 나올 뿐이다. "
                    + "email 은 없을 수 있다 — 신원 없이 만들어진 User(테스트 지원)와 과거 가입분이 그렇다."
    )
    ApiResponse<UserResponse> getCurrentUser(@AuthenticationPrincipal LoginUser loginUser);

    @Operation(
            summary = "현재 사용자 정보 수정",
            description = "닉네임을 바꾼다. 부분 수정이며, 수정 항목이 늘어도 엔드포인트는 이것 하나다. "
                    + "닉네임에 유일성 제약이 없어 동명이인이 허용된다. "
                    + "형식(공백뿐이거나 50자 초과)이 어긋나면 400 과 함께 어느 필드가 왜 틀렸는지 돌려준다."
    )
    ApiResponse<UserResponse> updateCurrentUser(@AuthenticationPrincipal LoginUser loginUser,
                                                @Valid @RequestBody UserUpdateRequest request);

    @Operation(
            summary = "회원 탈퇴",
            description = "되돌릴 수 없다. User 와 그 IdP 신원을 실제로 지우고(hard delete) 이 사람의 세션을 전부 끊는다. "
                    + "성공하면 204 이며 본문이 없다."
    )
    void withdraw(@AuthenticationPrincipal LoginUser loginUser);
}

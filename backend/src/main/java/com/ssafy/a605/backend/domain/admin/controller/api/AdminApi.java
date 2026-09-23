package com.ssafy.a605.backend.domain.admin.controller.api;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import com.ssafy.a605.backend.domain.admin.dto.AdminAuditLogResponse;
import com.ssafy.a605.backend.domain.admin.dto.AdminInquiryResponse;
import com.ssafy.a605.backend.domain.admin.dto.AdminNoticeRequest;
import com.ssafy.a605.backend.domain.admin.dto.AdminUserDetailResponse;
import com.ssafy.a605.backend.domain.admin.dto.AdminUserResponse;
import com.ssafy.a605.backend.domain.admin.dto.InquiryAnswerRequest;
import com.ssafy.a605.backend.domain.admin.dto.UserRoleUpdateRequest;
import com.ssafy.a605.backend.domain.admin.entity.AdminAuditAction;
import com.ssafy.a605.backend.domain.inquiry.entity.InquiryStatus;
import com.ssafy.a605.backend.global.response.ApiResponse;
import com.ssafy.a605.backend.global.response.PageResponse;
import com.ssafy.a605.backend.global.security.LoginUser;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/**
 * 관리자 콘솔. 문서만 담당하며 구현은 컨트롤러가 한다.
 *
 * <p>여덟 엔드포인트 모두 관리자 권한이 필요하다. 미인증은 401, 권한이 모자라면 403 이다.
 * 세션 ID 는 웹이면 {@code SESSION} 쿠키로, 앱이면 {@code X-Auth-Token} 헤더로 나른다
 * (ADR-0002). 쿠키로 인증하는 상태 변경 요청에는 {@code X-XSRF-TOKEN} 헤더가 함께 필요하다.
 */
@Tag(name = "Admin", description = "관리자 콘솔 API")
public interface AdminApi {

    /**
     * 페이지 파라미터의 기본값. 여기 두는 이유는 이 인터페이스가 Swagger 문서의 단일 진실이라
     * 구현과 갈리면 생성된 클라이언트가 거짓을 믿게 되기 때문이다 (conventions/api.md 서문).
     */
    String DEFAULT_PAGE = "0";
    String DEFAULT_SIZE = "20";

    @Operation(
            summary = "사용자 검색",
            description = "이메일·닉네임 부분일치(대소문자 무시)로 찾고 가입일 최신순으로 돌려준다. "
                    + "정렬은 고정이라 정렬 파라미터가 없다. 검색어를 비우면 최근 가입자부터 나온다. "
                    + "Test User 는 사용자가 아니라 테스트 장치이므로 결과에 나오지 않는다. "
                    + "이메일이 없는 사용자끼리의 동명이인은 식별자와 가입일로만 구별된다."
    )
    ApiResponse<PageResponse<AdminUserResponse>> searchUsers(
            @RequestParam(defaultValue = "") String keyword,
            @RequestParam(defaultValue = DEFAULT_PAGE) int page,
            @RequestParam(defaultValue = DEFAULT_SIZE) int size);

    @Operation(
            summary = "사용자 상세 조회",
            description = "로그인 방법·가입일과 지금 살아 있는 세션 수를 함께 돌려준다. "
                    + "세션은 개수만 담는다 — 세션 저장소가 접속 주소나 클라이언트 정보를 갖고 있지 않다. "
                    + "목록에 나오지 않는 Test User 는 여기서도 404 다."
    )
    ApiResponse<AdminUserDetailResponse> getUser(@PathVariable Long id);

    @Operation(
            summary = "사용자 권한 변경",
            description = "권한을 바꾸고 대상의 세션을 전부 끊는다 — 세션에 담긴 권한은 스스로 갱신되지 않아, "
                    + "끊지 않으면 변경이 세션 수명만큼 지연된다. "
                    + "자기 자신을 강등하려 하면 409 이며 권한은 그대로다 (관리자 화면에서 스스로 잠기는 것을 막는다). "
                    + "이 행위는 감사 로그에 남는다. 성공하면 204 이며 본문이 없다."
    )
    void changeUserRole(@AuthenticationPrincipal LoginUser loginUser,
                        @PathVariable Long id,
                        @Valid @RequestBody UserRoleUpdateRequest request);

    @Operation(
            summary = "사용자 세션 강제 종료",
            description = "이 User 의 세션을 전부 끊는다. 개별 세션을 고르는 수단은 없다. "
                    + "자기 자신도 대상이 될 수 있다 — 되돌리는 비용이 재로그인 한 번뿐이다. "
                    + "이 행위는 감사 로그에 남는다. 성공하면 204 이며 본문이 없다."
    )
    void revokeUserSessions(@AuthenticationPrincipal LoginUser loginUser, @PathVariable Long id);

    @Operation(
            summary = "감사 로그 조회",
            description = "관리자 권한으로 수행된 행위의 불변 기록을 최신순으로 돌려준다. 추가 전용이라 수정·삭제 API 가 없다. "
                    + "행위 종류로 거를 수 있다. 등급을 나누지 않으므로 모든 관리자가 전체를 본다. "
                    + "안에 담긴 사용자 식별자는 참조가 아니라 그 시점의 기록이다 — 대상이 탈퇴했다면 그 값으로 "
                    + "사용자를 조회할 수 없으며, 그것이 정상이다."
    )
    ApiResponse<PageResponse<AdminAuditLogResponse>> searchAuditLogs(
            @RequestParam(required = false) AdminAuditAction action,
            @RequestParam(defaultValue = DEFAULT_PAGE) int page,
            @RequestParam(defaultValue = DEFAULT_SIZE) int size);

    @Operation(
            summary = "문의 목록 조회",
            description = "들어온 문의를 최신순으로 돌려준다. 상태(`OPEN`·`ANSWERED`)로 거를 수 있고, "
                    + "비우면 전체가 나온다. 목록에서 바로 답변하므로 본문과 답변을 함께 담는다 — "
                    + "상세 조회 엔드포인트가 따로 없는 이유다. 작성자 닉네임이 함께 오며, 작성자에는 "
                    + "외래키가 있어 항상 채워진다. 반면 답변자 식별자는 그 시점의 기록이라 그 관리자가 "
                    + "탈퇴했다면 그 값으로 사용자를 조회할 수 없으며, 그것이 정상이다."
    )
    ApiResponse<PageResponse<AdminInquiryResponse>> searchInquiries(
            @RequestParam(required = false) InquiryStatus status,
            @RequestParam(defaultValue = DEFAULT_PAGE) int page,
            @RequestParam(defaultValue = DEFAULT_SIZE) int size);

    @Operation(
            summary = "문의 답변 등록·수정",
            description = "답변을 담고 문의를 답변 후 상태로 바꾼다. 이미 답변이 있으면 내용을 갱신하며 문의가 "
                    + "새로 생기지 않는다 — 오타 하나로 사용자에게 새 문의를 파게 할 수 없어 등록과 수정을 "
                    + "하나로 둔다. 상태는 따로 바꾸지 않는다 (등록이 전이를 함께 일으킨다). "
                    + "**이 행위는 감사 로그에 남지 않는다** — 권한 행위가 아니라 콘텐츠 편집이다. "
                    + "그런 문의가 없으면 404 다. 성공하면 204 이며 본문이 없다."
    )
    void answerInquiry(@AuthenticationPrincipal LoginUser loginUser,
                       @PathVariable Long id,
                       @Valid @RequestBody InquiryAnswerRequest request);

    @Operation(
            summary = "전원 공지 발송",
            description = "시즌에 참가한 계좌 전원의 알림 센터에 공지 한 통을 남기고, 접속 중인 사람에게는 "
                    + "실시간으로 전달한다 — 공모 공시·무료 타임과 같은 경로다. "
                    + "`urgent` 가 참이면 긴급 공지가 되며 목적지는 같고 화면의 표현만 달라진다. "
                    + "**취소·삭제가 없다** — 잘못 보낸 공지는 정정 공지로만 바로잡을 수 있다. "
                    + "멱등 키가 없어 같은 요청을 두 번 보내면 두 통이 간다. "
                    + "**이 행위는 감사 로그에 남지 않는다** — 대상이 전원이라 대조할 상대가 없고, "
                    + "보낸 문구와 시각은 알림 행으로 남는다. 성공하면 204 이며 본문이 없다."
    )
    void sendNotice(@Valid @RequestBody AdminNoticeRequest request);
}

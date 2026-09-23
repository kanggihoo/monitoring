package com.ssafy.a605.backend.domain.inquiry.controller.api;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import com.ssafy.a605.backend.domain.inquiry.dto.InquiryCreateRequest;
import com.ssafy.a605.backend.domain.inquiry.dto.InquiryResponse;
import com.ssafy.a605.backend.domain.inquiry.dto.InquirySummaryResponse;
import com.ssafy.a605.backend.global.response.ApiResponse;
import com.ssafy.a605.backend.global.response.PageResponse;
import com.ssafy.a605.backend.global.security.LoginUser;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/**
 * 사용자의 문의. 문서만 담당하며 구현은 컨트롤러가 한다.
 *
 * <p>네 엔드포인트 모두 로그인이 필요하고 <b>본인 것만</b> 다룬다. 세션 ID 는 웹이면
 * {@code SESSION} 쿠키로, 앱이면 {@code X-Auth-Token} 헤더로 나른다 (ADR-0002). 쿠키로
 * 인증하는 상태 변경 요청에는 {@code X-XSRF-TOKEN} 헤더가 함께 필요하다.
 *
 * <p>관리자의 문의 목록과 답변은 {@code Admin} 쪽에 있다.
 */
@Tag(name = "Inquiry", description = "문의 API")
public interface InquiryApi {

    /** 페이지 파라미터의 기본값. 이 인터페이스가 Swagger 문서의 단일 진실이라 구현과 갈리면 안 된다. */
    String DEFAULT_PAGE = "0";
    String DEFAULT_SIZE = "20";

    @Operation(
            summary = "문의 작성",
            description = "제목과 내용으로 문의를 남긴다. 만들어진 문의는 답변 전 상태다. "
                    + "작성 횟수 제한은 없다. 길이 제한을 넘기면 400 이며 필드별 이유가 담긴다. "
                    + "성공하면 201 이다."
    )
    ApiResponse<InquiryResponse> createInquiry(@AuthenticationPrincipal LoginUser loginUser,
                                              @Valid @RequestBody InquiryCreateRequest request);

    @Operation(
            summary = "내 문의 목록",
            description = "내가 남긴 문의를 최신순으로 돌려준다. 정렬은 고정이라 정렬 파라미터가 없다. "
                    + "다른 사용자의 문의는 담기지 않는다. 본문과 답변은 담지 않으므로 답변 내용은 상세로 읽는다."
    )
    ApiResponse<PageResponse<InquirySummaryResponse>> searchInquiries(
            @AuthenticationPrincipal LoginUser loginUser,
            @RequestParam(defaultValue = DEFAULT_PAGE) int page,
            @RequestParam(defaultValue = DEFAULT_SIZE) int size);

    @Operation(
            summary = "문의 상세 조회",
            description = "질문과 답변을 함께 돌려준다. "
                    + "**다른 사용자의 문의는 404 다** — 403 을 주면 그 식별자의 문의가 존재한다는 사실이 드러난다. "
                    + "수정 엔드포인트는 없다: 답변 근거가 되는 질문이 바뀌면 답변이 엉뚱해진다."
    )
    ApiResponse<InquiryResponse> getInquiry(@AuthenticationPrincipal LoginUser loginUser,
                                           @PathVariable Long id);

    @Operation(
            summary = "문의 삭제",
            description = "답변 전 문의만 지울 수 있다. 답변이 달린 뒤에는 409 이며 문의는 그대로 남는다 — "
                    + "답변만 남고 질문이 사라지는 일이 없어야 한다. "
                    + "다른 사용자의 문의는 404 다. 성공하면 204 이며 본문이 없다."
    )
    void deleteInquiry(@AuthenticationPrincipal LoginUser loginUser, @PathVariable Long id);
}

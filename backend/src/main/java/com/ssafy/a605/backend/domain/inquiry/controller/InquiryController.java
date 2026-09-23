package com.ssafy.a605.backend.domain.inquiry.controller;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.ssafy.a605.backend.domain.inquiry.controller.api.InquiryApi;
import com.ssafy.a605.backend.domain.inquiry.dto.InquiryCreateRequest;
import com.ssafy.a605.backend.domain.inquiry.dto.InquiryResponse;
import com.ssafy.a605.backend.domain.inquiry.dto.InquirySummaryResponse;
import com.ssafy.a605.backend.domain.inquiry.service.InquiryService;
import com.ssafy.a605.backend.global.response.ApiResponse;
import com.ssafy.a605.backend.global.response.PageResponse;
import com.ssafy.a605.backend.global.security.LoginUser;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * 사용자의 문의. 네 엔드포인트 모두 세션의 User 식별자로 대상을 좁히므로, 남의 문의는 애초에
 * 조회되지 않는다.
 */
@RestController
@RequestMapping("/api/v1/inquiries")
@RequiredArgsConstructor
public class InquiryController implements InquiryApi {

    /** 정렬은 최신순 고정이다. 같은 순간에 만들어진 두 행의 순서가 흔들리면 페이지 경계에서 행이 겹친다. */
    private static final String[] SORT_PROPERTIES = {"createdAt", "id"};

    /** 한 번에 가져갈 수 있는 최대치. */
    private static final int MAX_PAGE_SIZE = 100;

    private final InquiryService inquiryService;

    @Override
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<InquiryResponse> createInquiry(@AuthenticationPrincipal LoginUser loginUser,
                                                     @Valid @RequestBody InquiryCreateRequest request) {
        return ApiResponse.created("문의를 남겼습니다.", inquiryService.create(loginUser.getUserId(), request));
    }

    @Override
    @GetMapping
    public ApiResponse<PageResponse<InquirySummaryResponse>> searchInquiries(
            @AuthenticationPrincipal LoginUser loginUser,
            @RequestParam(defaultValue = DEFAULT_PAGE) int page,
            @RequestParam(defaultValue = DEFAULT_SIZE) int size) {
        return ApiResponse.ok(inquiryService.search(loginUser.getUserId(), pageRequest(page, size)));
    }

    @Override
    @GetMapping("/{id}")
    public ApiResponse<InquiryResponse> getInquiry(@AuthenticationPrincipal LoginUser loginUser,
                                                  @PathVariable Long id) {
        return ApiResponse.ok(inquiryService.get(loginUser.getUserId(), id));
    }

    @Override
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteInquiry(@AuthenticationPrincipal LoginUser loginUser, @PathVariable Long id) {
        inquiryService.delete(loginUser.getUserId(), id);
    }

    /**
     * 정렬을 여기서 못 박는다. {@code Pageable} 을 그대로 받으면 프레임워크가 정렬 파라미터를
     * 바인딩해 호출자가 정렬을 고를 수 있게 되는데, 그것을 만들지 않기로 했다.
     *
     * <p>범위를 벗어난 값은 거절하지 않고 맞춘다 — 목록 조회에서 잘못된 페이지 번호는 오류로
     * 다룰 만한 일이 아니다.
     */
    private static Pageable pageRequest(int page, int size) {
        return PageRequest.of(
                Math.max(page, 0),
                Math.clamp(size, 1, MAX_PAGE_SIZE),
                Sort.by(Sort.Direction.DESC, SORT_PROPERTIES));
    }
}

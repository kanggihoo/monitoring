package com.ssafy.a605.backend.domain.admin.service;

import java.util.Map;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ssafy.a605.backend.domain.admin.dto.AdminInquiryResponse;
import com.ssafy.a605.backend.domain.inquiry.entity.Inquiry;
import com.ssafy.a605.backend.domain.inquiry.entity.InquiryStatus;
import com.ssafy.a605.backend.domain.inquiry.service.InquiryService;
import com.ssafy.a605.backend.domain.user.service.UserService;
import com.ssafy.a605.backend.global.exception.BusinessException;
import com.ssafy.a605.backend.global.response.PageResponse;

import lombok.RequiredArgsConstructor;

/**
 * 관리자 콘솔의 문의 목록과 답변.
 *
 * <p>문의 자체는 문의 서비스가, 작성자 닉네임은 사용자 서비스가 갖고 있어 여기서 둘을 조립한다 —
 * 문의 쪽은 작성자를 식별자로만 안다.
 *
 * <p>답변에는 관리자 쪽이 더할 것이 없다 — 이 행위는 <b>감사 로그에 남기지 않는다.</b> 권한 행위가
 * 아니라 콘텐츠 편집이고, 감사 로그는 관리자 권한으로 수행된 행위의 기록이라는 정의를 지킨다.
 * 그래도 관리자 콘솔의 진입점을 여기 하나로 두는 편이, 문의 하나에 두 갈래 경로를 만드는 것보다
 * 다음 사람이 붙일 자리를 찾기 쉽다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminInquiryService {

    private final InquiryService inquiryService;
    private final UserService userService;

    /**
     * 들어온 문의를 최신순으로 읽는다.
     *
     * @param status   걸러낼 상태 (null 이면 전체)
     * @param pageable 페이지 요청
     * @return 작성자 정보를 붙인 문의 페이지
     */
    public PageResponse<AdminInquiryResponse> search(InquiryStatus status, Pageable pageable) {
        Page<Inquiry> inquiries = inquiryService.searchAll(status, pageable);
        // 행마다 작성자를 조회하면 N+1 이 된다.
        Map<Long, String> nicknames = userService.nicknamesByUserId(
                inquiries.getContent().stream().map(Inquiry::getUserId).toList());

        return PageResponse.from(inquiries.map(inquiry ->
                AdminInquiryResponse.from(inquiry, nicknames.get(inquiry.getUserId()))));
    }

    /**
     * 답변을 등록하거나 이미 있는 답변을 고친다. 등록은 상태 전이를 함께 일으키고, 답변자와 답변
     * 시각은 마지막 답변 기준으로 갱신된다.
     *
     * @param adminUserId 답변하는 관리자의 식별자
     * @param inquiryId   문의 식별자
     * @param answer      답변 내용
     * @throws BusinessException 그런 문의가 없을 때 (404)
     */
    @Transactional
    public void answer(Long adminUserId, Long inquiryId, String answer) {
        inquiryService.answer(adminUserId, inquiryId, answer);
    }
}

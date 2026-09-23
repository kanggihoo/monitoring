package com.ssafy.a605.backend.domain.inquiry.service;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ssafy.a605.backend.domain.inquiry.dto.InquiryCreateRequest;
import com.ssafy.a605.backend.domain.inquiry.dto.InquiryResponse;
import com.ssafy.a605.backend.domain.inquiry.dto.InquirySummaryResponse;
import com.ssafy.a605.backend.domain.inquiry.entity.Inquiry;
import com.ssafy.a605.backend.domain.inquiry.entity.InquiryStatus;
import com.ssafy.a605.backend.domain.inquiry.repository.InquiryRepository;
import com.ssafy.a605.backend.global.exception.BusinessException;
import com.ssafy.a605.backend.global.response.PageResponse;

import lombok.RequiredArgsConstructor;

/**
 * 문의의 작성·조회·삭제와 답변 등록.
 *
 * <p>사용자를 조회하지 않는다 — 작성자를 식별자로만 갖기 때문이다. 작성자의 닉네임이 필요한
 * 관리자 목록은 {@code AdminInquiryService} 가 사용자 서비스와 함께 조립한다. 이 방향을 지키는
 * 이유는 탈퇴가 반대 방향(사용자 → 문의)으로 문의 삭제를 부르기 때문이다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class InquiryService {

    private final InquiryRepository inquiryRepository;

    /**
     * 문의를 남긴다. 작성 횟수 제한은 두지 않는다 — 인증된 사용자만 쓸 수 있고, 남용에는
     * 세션 강제 종료라는 수단이 이미 있다.
     *
     * @param userId  작성자 User 식별자
     * @param request 제목과 내용
     * @return 만들어진 문의
     */
    @Transactional
    public InquiryResponse create(Long userId, InquiryCreateRequest request) {
        return InquiryResponse.from(inquiryRepository.save(request.toEntity(userId)));
    }

    /**
     * 내 문의를 읽는다.
     *
     * @param userId   조회하는 User 식별자
     * @param pageable 페이지 요청
     * @return 이 User 가 남긴 문의 페이지
     */
    public PageResponse<InquirySummaryResponse> search(Long userId, Pageable pageable) {
        return PageResponse.from(
                inquiryRepository.findByUserId(userId, pageable).map(InquirySummaryResponse::from));
    }

    /**
     * 내 문의 하나를 읽는다.
     *
     * @param userId 조회하는 User 식별자
     * @param id     문의 식별자
     * @return 질문과 답변
     * @throws BusinessException 그런 문의가 없거나 남의 문의일 때 (404)
     */
    public InquiryResponse get(Long userId, Long id) {
        return InquiryResponse.from(getOwned(userId, id));
    }

    /**
     * 내 문의를 지운다. 답변 전에만 지울 수 있다 — 답변만 남고 질문이 사라지는 일이 없어야 한다.
     *
     * @param userId 삭제하는 User 식별자
     * @param id     문의 식별자
     * @throws BusinessException 그런 문의가 없거나 남의 문의일 때 (404),
     *                           이미 답변이 달렸을 때 (409)
     */
    @Transactional
    public void delete(Long userId, Long id) {
        Inquiry inquiry = getOwned(userId, id);
        if (inquiry.isAnswered()) {
            throw new BusinessException(HttpStatus.CONFLICT, "답변이 등록된 문의는 삭제할 수 없습니다.");
        }
        inquiryRepository.delete(inquiry);
    }

    /**
     * 이 User 가 남긴 문의를 모두 지운다. 탈퇴가 부른다 — 문의 본문은 그 사람이 쓴 개인정보다.
     *
     * <p>⚠️ 이 삭제가 빠지면 문의를 남긴 사용자의 탈퇴가 외래키 위반으로 깨진다.
     *
     * @param userId 탈퇴하는 User 식별자
     */
    @Transactional
    public void deleteByUser(Long userId) {
        inquiryRepository.deleteByUserId(userId);
    }

    /**
     * 관리자에게 보일 문의를 읽는다. 작성자 정보를 붙이는 것은 호출부의 몫이다.
     *
     * @param status   걸러낼 상태 (null 이면 전체)
     * @param pageable 페이지 요청
     * @return 문의 페이지
     */
    public Page<Inquiry> searchAll(InquiryStatus status, Pageable pageable) {
        return (status == null)
                ? inquiryRepository.findAll(pageable)
                : inquiryRepository.findByStatus(status, pageable);
    }

    /**
     * 답변을 등록하거나 이미 있는 답변을 고친다. 하나의 동작으로 두는 이유는 같은 결과를 만드는
     * 두 경로를 만들 이유가 없기 때문이다. 등록은 상태 전이를 함께 일으킨다.
     *
     * <p>이 동작은 감사 로그에 남기지 않는다 — 권한 행위가 아니라 콘텐츠 편집이다.
     *
     * @param adminUserId 답변하는 관리자의 식별자
     * @param id          문의 식별자
     * @param answer      답변 내용
     * @throws BusinessException 그런 문의가 없을 때 (404)
     */
    @Transactional
    public void answer(Long adminUserId, Long id, String answer) {
        inquiryRepository.findById(id)
                .orElseThrow(InquiryService::notFound)
                .answer(adminUserId, answer);
    }

    private Inquiry getOwned(Long userId, Long id) {
        return inquiryRepository.findByIdAndUserId(id, userId).orElseThrow(InquiryService::notFound);
    }

    /**
     * 남의 문의도 없는 문의와 똑같이 다룬다. 403 을 주면 그 식별자의 문의가 존재한다는 사실이
     * 드러난다.
     */
    private static BusinessException notFound() {
        return new BusinessException(HttpStatus.NOT_FOUND, "문의를 찾을 수 없습니다.");
    }
}

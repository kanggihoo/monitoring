package com.ssafy.a605.backend.domain.inquiry.repository;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.ssafy.a605.backend.domain.inquiry.entity.Inquiry;
import com.ssafy.a605.backend.domain.inquiry.entity.InquiryStatus;

public interface InquiryRepository extends JpaRepository<Inquiry, Long> {

    /** 사용자는 자기 문의만 본다. */
    Page<Inquiry> findByUserId(Long userId, Pageable pageable);

    /**
     * 작성자까지 함께 조건에 넣는다. 식별자만으로 찾아 소유자를 뒤에서 비교하면 "없음" 과
     * "남의 것" 이 다른 흐름을 타게 되고, 그 차이가 상태 코드로 새어 나간다.
     */
    Optional<Inquiry> findByIdAndUserId(Long id, Long userId);

    /** 관리자 목록의 상태 필터. 필터가 없는 조회는 {@code findAll(Pageable)} 이 맡는다. */
    Page<Inquiry> findByStatus(InquiryStatus status, Pageable pageable);

    /** 탈퇴에서 User 보다 먼저 지운다 (user_id 외래키 때문에 순서가 고정된다). */
    void deleteByUserId(Long userId);
}

package com.ssafy.a605.backend.domain.admin.service;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ssafy.a605.backend.domain.admin.dto.AdminAuditLogResponse;
import com.ssafy.a605.backend.domain.admin.entity.AdminAuditAction;
import com.ssafy.a605.backend.domain.admin.entity.AdminAuditLog;
import com.ssafy.a605.backend.domain.admin.repository.AdminAuditLogRepository;
import com.ssafy.a605.backend.global.response.PageResponse;

import lombok.RequiredArgsConstructor;

/**
 * 감사 로그의 기록과 조회. 수정·삭제는 만들지 않는다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminAuditLogService {

    private final AdminAuditLogRepository auditLogRepository;

    /**
     * 기록을 남긴다. 호출부의 트랜잭션에 참여하므로 실제 변경과 기록이 함께 성공하거나 함께 실패한다 —
     * 기록 없는 권한 변경이 남는 것이 더 나쁘다.
     *
     * @param actorUserId  행위를 수행한 관리자의 식별자
     * @param action       행위의 종류
     * @param targetUserId 대상 User 의 식별자
     * @param detail       사람이 읽을 부연 (없으면 null)
     */
    @Transactional
    void write(Long actorUserId, AdminAuditAction action, Long targetUserId, String detail) {
        auditLogRepository.save(new AdminAuditLog(actorUserId, action, targetUserId, detail));
    }

    /**
     * 감사 로그를 읽는다. 등급을 나누지 않으므로 모든 관리자가 전체를 본다.
     *
     * @param action   걸러낼 행위 종류 (null 이면 전체)
     * @param pageable 페이지 요청
     * @return 감사 로그 페이지
     */
    public PageResponse<AdminAuditLogResponse> search(AdminAuditAction action, Pageable pageable) {
        Page<AdminAuditLog> logs = (action == null)
                ? auditLogRepository.findAll(pageable)
                : auditLogRepository.findByAction(action, pageable);
        return PageResponse.from(logs.map(AdminAuditLogResponse::from));
    }
}

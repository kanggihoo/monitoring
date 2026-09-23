package com.ssafy.a605.backend.domain.admin.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.ssafy.a605.backend.domain.admin.entity.AdminAuditAction;
import com.ssafy.a605.backend.domain.admin.entity.AdminAuditLog;

/**
 * 감사 로그는 추가와 조회만 한다. 수정·삭제 경로를 만들지 않는다.
 */
public interface AdminAuditLogRepository extends JpaRepository<AdminAuditLog, Long> {

    /** 행위 종류 필터. 필터가 없는 조회는 {@code findAll(Pageable)} 이 맡는다. */
    Page<AdminAuditLog> findByAction(AdminAuditAction action, Pageable pageable);
}

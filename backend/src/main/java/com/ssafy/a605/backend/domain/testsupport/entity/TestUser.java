package com.ssafy.a605.backend.domain.testsupport.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 테스트와 부하 측정 전용 User 를 가리키는 표식. 사용자가 아니라 테스트 장치다.
 *
 * <p>행의 존재가 곧 "Test User 여부"이므로 {@code users} 에는 컬럼을 하나도 추가하지 않는다
 * (ADR-0003). User 와의 연관관계를 매핑하지 않고 식별자만 갖는다 — 이 기능을 걷어낼 때
 * {@code users} 쪽에 남는 흔적이 없어야 한다.
 */
@Entity
@Table(name = "test_users")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TestUser {

    @Id
    @Column(name = "user_id")
    private Long userId;

    /**
     * 안정된 핸들. 부하 테스트 스크립트의 참조 키이자 시드 멱등성의 기준이다.
     * 닉네임은 사용자가 바꿀 수 있고 유일하지도 않아 키로 쓸 수 없다.
     */
    @Column(nullable = false, length = 30, unique = true)
    private String name;

    /** NULL 이면 부하 테스트 전용. 값이 있으면 그 관리자의 샌드박스 전용이다 (DB 가 1:1 을 강제한다). */
    @Column(name = "assigned_admin_id", unique = true)
    private Long assignedAdminId;

    /**
     * @param userId          이 표식이 가리키는 User 식별자
     * @param name            안정된 핸들
     * @param assignedAdminId 배정받은 관리자 식별자 (부하 테스트 전용이면 null)
     */
    public TestUser(Long userId, String name, Long assignedAdminId) {
        this.userId = userId;
        this.name = name;
        this.assignedAdminId = assignedAdminId;
    }
}

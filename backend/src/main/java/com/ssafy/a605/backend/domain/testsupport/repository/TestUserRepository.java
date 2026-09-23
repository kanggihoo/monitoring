package com.ssafy.a605.backend.domain.testsupport.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.ssafy.a605.backend.domain.testsupport.entity.TestUser;

public interface TestUserRepository extends JpaRepository<TestUser, Long> {

    /**
     * 테스트 로그인의 대상 조회. 이 조회가 "실제 사용자로는 로그인할 수 없다"의 마지막 방어선이다.
     *
     * <p>배정된 행은 제외한다. 이름만으로 찾으면 API 키를 아는 호출자가 관리자의 샌드박스 Test User 로
     * 로그인해 그 상태를 망가뜨릴 수 있어, 부하 테스트와 샌드박스의 분리가 이름 규칙에만 의존하게 된다.
     */
    Optional<TestUser> findByNameAndAssignedAdminIdIsNull(String name);

    /** 샌드박스의 대상 조회. 관리자당 최대 하나다 (DB UNIQUE). */
    Optional<TestUser> findByAssignedAdminId(Long assignedAdminId);
}

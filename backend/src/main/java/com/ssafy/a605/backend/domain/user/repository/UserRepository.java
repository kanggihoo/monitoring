package com.ssafy.a605.backend.domain.user.repository;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.ssafy.a605.backend.domain.user.entity.User;

public interface UserRepository extends JpaRepository<User, Long> {

    /**
     * 이 닉네임을 쓰는 사용자가 있는가 (기획서 2.1.3 유일성 검사).
     *
     * <p>유니크 제약(V24)이 최종 방어선이고 이 조회는 사용자에게 <b>사유를 알려 주기 위한</b>
     * 것이다 — 제약 위반으로 500 이 나가면 무엇을 잘못했는지 알 수 없다.
     *
     * @param nickname 검사할 닉네임
     * @return 이미 쓰이고 있으면 {@code true}
     */
    boolean existsByNickname(String nickname);

    /**
     * 이메일·닉네임 부분일치(대소문자 무시)로 찾는다. 정렬은 호출부가 정한다.
     *
     * <p>이메일이 없는 사용자도 닉네임이 맞으면 걸린다 — email 조건은 NULL 이 되지만 nickname
     * 조건이 참이면 OR 전체가 참이다. 검색어가 비면 두 조건 모두 {@code LIKE '%%'} 가 되어
     * 전체가 나온다.
     *
     * <p><b>Test User 를 제외한다.</b> 성능이나 편의를 위한 예외가 아니라 용어 정의의 구현이다 —
     * Test User 는 사용자가 아니라 테스트 장치다 (CONTEXT.md). 이 조건이 테스트 지원 테이블을
     * 참조하는 유일한 지점이며, 그 기능을 걷어낼 때 함께 지워야 한다 (ADR-0003).
     *
     * @param keyword  부분일치 검색어 (빈 문자열이면 전체)
     * @param pageable 페이지 요청
     * @return 조건에 맞는 User 페이지
     */
    @Query("""
            SELECT u FROM User u
            WHERE NOT EXISTS (SELECT 1 FROM TestUser t WHERE t.userId = u.id)
              AND (LOWER(u.email) LIKE LOWER(CONCAT('%', :keyword, '%'))
                   OR LOWER(u.nickname) LIKE LOWER(CONCAT('%', :keyword, '%')))
            """)
    Page<User> searchExcludingTestUsers(@Param("keyword") String keyword, Pageable pageable);

    /**
     * 목록에서 제외되는 Test User 는 상세로도 들어갈 수 없다.
     *
     * @param id 대상 User 식별자
     * @return 그런 User 가 없거나 Test User 면 빈 값
     */
    @Query("""
            SELECT u FROM User u
            WHERE u.id = :id
              AND NOT EXISTS (SELECT 1 FROM TestUser t WHERE t.userId = u.id)
            """)
    Optional<User> findByIdExcludingTestUser(@Param("id") Long id);
}

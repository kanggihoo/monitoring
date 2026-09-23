package com.ssafy.a605.backend.domain.user.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 서비스를 사용하는 사람 하나. 도메인 전반은 이 엔티티의 id 로 사람을 가리킨다.
 */
@Entity
@Table(name = "users")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User {

    // 가입 시 세트 중 하나를 무작위 배정하는 것은 기획서 1.1.5 지만, 세트 크기가 정해지지
    // 않아 아직 고정값을 쓴다. JPA 는 INSERT 에 모든 컬럼을 싣기 때문에 여기서 채우지 않으면
    // 컬럼 기본값이 적용되지 않고 NOT NULL 위반이 난다.
    private static final Short DEFAULT_AVATAR_ID = 1;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 신원 없이 만들어진 User(테스트 지원)와 과거 가입분은 이메일이 없다. 표시용 속성이라
    // 없을 수 있는 것이 정상이다 — V3 마이그레이션 참조.
    @Column
    private String email;

    // 가입 시 시스템이 만들고(투자자 + 4자리 난수) 사용자가 마이페이지에서 바꾼다. V24 가
    // 유니크 제약을 걸었으므로 매핑에도 적는다 — ForbiddenWord·TestUser 와 같은 표기다.
    @Column(nullable = false, length = 50, unique = true)
    private String nickname;

    // 제공 아바타 세트의 식별자. 이미지를 직접 올리지 않으므로 URL 이 아니라 작은 정수다
    // — V8 마이그레이션 참조. 리더보드·채팅 응답의 avatarId 가 이 값이다.
    @Column(name = "avatar_id", nullable = false)
    private Short avatarId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    /**
     * 최초 로그인으로 생성되는 User. 가입 폼이 없으므로 이메일과 닉네임은 IdP 가 준 값이다.
     *
     * @param email    표시용 이메일 (식별자가 아니며, IdP 가 주지 않으면 null 이다)
     * @param nickname 닉네임 초기값
     */
    public User(String email, String nickname) {
        this.email = email;
        this.nickname = nickname;
        this.role = Role.USER;
        this.avatarId = DEFAULT_AVATAR_ID;
    }

    /**
     * 닉네임을 바꾼다. 유일성 제약을 두지 않으므로 형식만 맞으면 된다 (검증은 요청 DTO 가 한다).
     *
     * @param nickname 새 닉네임 (앞뒤 공백은 제거해 저장한다)
     */
    public void changeNickname(String nickname) {
        this.nickname = nickname.trim();
    }

    /**
     * 권한을 바꾼다. 호출부는 이 User 의 세션을 무효화해야 한다 — 세션에 담긴 authorities 는
     * 스스로 갱신되지 않는다.
     *
     * @param role 새 권한
     */
    public void changeRole(Role role) {
        this.role = role;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }
}

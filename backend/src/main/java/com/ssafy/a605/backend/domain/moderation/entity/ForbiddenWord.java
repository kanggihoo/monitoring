package com.ssafy.a605.backend.domain.moderation.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 금칙어 한 단어 (기획서 12.2.2).
 *
 * <p>채팅 본문과 닉네임이 같은 사전을 쓴다. 그래서 이 엔티티가 소셜도 사용자도 아닌 별도 도메인에
 * 있다 — 한쪽에 두면 다른 쪽이 그 도메인을 참조하게 된다.
 *
 * <p>대소문자를 구분하지 않고 매칭하므로 소문자로 저장한다. 판정 시점에 양쪽을 소문자로 맞추면
 * 사전에 대문자가 섞여 있어도 동작하지만, 그러면 같은 단어가 두 행으로 들어올 수 있다.
 */
@Entity
@Table(name = "forbidden_words")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ForbiddenWord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50, unique = true)
    private String word;

    /** 어느 경로에 걸리나. 사전은 하나고 강도만 여기서 나눈다 (V31). */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ForbiddenWordScope scope;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * 채팅과 닉네임 둘 다에 걸리는 단어. 기존 사전 전부가 이 모양이다.
     *
     * @param word 금칙어. 소문자로 저장한다
     */
    public ForbiddenWord(String word) {
        this(word, ForbiddenWordScope.BOTH);
    }

    /**
     * @param word  금칙어. 소문자로 저장한다
     * @param scope 어느 경로에 걸리나
     */
    public ForbiddenWord(String word, ForbiddenWordScope scope) {
        this.word = word;
        this.scope = scope;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }
}

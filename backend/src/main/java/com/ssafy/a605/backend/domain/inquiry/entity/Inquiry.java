package com.ssafy.a605.backend.domain.inquiry.entity;

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
 * 사용자가 관리자에게 보내는 질문 하나와 그에 대한 답변 하나. 스레드가 아니므로 재질문은 새 문의다.
 *
 * <p>두 사용자 식별자는 연관관계로 매핑하지 않고 식별자만 갖는다 (기존 {@code TestUser} 와 같은 결).
 * 특히 답변자는 참조가 아니라 <b>기록</b>이다 — 답변한 관리자가 탈퇴하면 그 값으로 사용자를
 * 조회할 수 없으며, 화면은 그 경우를 정상으로 다뤄야 한다.
 */
@Entity
@Table(name = "inquiries")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Inquiry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 작성자. DB 에 FK 는 있지만 CASCADE 가 없어 탈퇴 시 서비스가 명시적으로 지운다. */
    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(nullable = false, length = 100)
    private String title;

    @Column(nullable = false, length = 2000)
    private String content;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private InquiryStatus status;

    @Column(length = 2000)
    private String answer;

    /** 답변한 관리자의 식별자. FK 가 없어 그 관리자가 탈퇴해도 값은 남는다. */
    @Column(name = "answered_by")
    private Long answeredBy;

    @Column(name = "answered_at")
    private Instant answeredAt;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    /**
     * 사용자가 남기는 문의. 답변 전 상태로 시작한다.
     *
     * @param userId  작성자 User 식별자
     * @param title   제목 (컬럼 길이는 100자)
     * @param content 내용 (컬럼 길이는 2000자)
     */
    public Inquiry(Long userId, String title, String content) {
        this.userId = userId;
        this.title = title;
        this.content = content;
        this.status = InquiryStatus.OPEN;
    }

    /**
     * 답변을 담고 답변 후 상태로 바꾼다. 이미 답변이 있으면 내용을 갱신한다 — 오타 하나로
     * 사용자에게 새 문의를 파게 할 수 없다.
     *
     * @param adminUserId 답변한 관리자의 식별자
     * @param answer      답변 내용 (컬럼 길이는 2000자)
     */
    public void answer(Long adminUserId, String answer) {
        this.answer = answer;
        this.answeredBy = adminUserId;
        this.answeredAt = Instant.now();
        this.status = InquiryStatus.ANSWERED;
    }

    /** @return 답변이 달렸으면 true (작성자는 이때부터 지울 수 없다) */
    public boolean isAnswered() {
        return this.status == InquiryStatus.ANSWERED;
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

package com.ssafy.a605.backend.domain.social.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 전체 공용 채팅 메시지 하나.
 *
 * <p>닉네임과 아바타를 복사 저장하지 않고 조회 시 users 를 조인한다. 닉네임 변경이 과거
 * 메시지에도 반영되어야 하고, 탈퇴자 익명화("탈퇴한 사용자")도 조인이 비는 것으로 자동
 * 처리되기 때문이다. 그래서 userId 에 FK 를 걸지 않는다 - 탈퇴는 users 를 물리 삭제하는데,
 * FK 가 있으면 탈퇴가 실패하거나 메시지가 함께 사라진다. 참조가 아니라 그 시점의 기록이다.
 * 같은 이유로 탈퇴 처리에 채팅 삭제 훅을 붙이면 안 된다.
 */
@Entity
@Table(name = "chat_messages")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChatMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 보낸 시점의 시즌. tick_seq 에서 유추하지 않는다 - 브리핑 구간의 tick_seq 는 null 이고,
    // 그 행이 바로 "어느 시즌인지 모르는" 행이 된다 (V40).
    @Column(name = "season_id", nullable = false)
    private Long seasonId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    // 금칙어 마스킹이 적용된 값을 저장한다. 차단이 아니라 마스킹이다 -
    // 어느 단어가 걸렸는지 알려주면 우회 방법을 알려주는 셈이다.
    @Column(nullable = false, length = 200)
    private String content;

    // 보낸 시점의 게임 시각. 나중에 복기에서 "폭락 때 사람들이 뭐라고 했나"를 볼 수 있어야 한다.
    // 채팅이 가능한 시간(11:00~17:00)과 재생이 도는 시간(11:05~16:29)이 달라 null 일 수 있다.
    // 1일차 브리핑에는 시즌 전체에 틱이 하나도 없어 직전 틱으로 채우는 우회도 성립하지 않는다.
    @Column(name = "tick_seq")
    private Long tickSeq;

    // 보낸 시점에 서버가 판단한 일차. 채팅창이 오늘 대화만 보여주는 기준이고, tick_seq 에서
    // 유추하지 않는다 - 브리핑(11:00~11:05)의 공개 기준 틱은 전날 것이라 그 구간의 대화가
    // 통째로 어제로 분류된다. dev 의 일시정지/지점 이동에서도 전송 시각과 일차가 어긋난다.
    @Column(name = "season_day", nullable = false)
    private Short seasonDay;

    @Column(name = "sent_at", nullable = false)
    private Instant sentAt;

    // 관리자 삭제 시각. 행을 지우지 않는다 - 커서 페이지네이션이 가리키는 지점이 사라지면 안 된다.
    @Column(name = "deleted_at")
    private Instant deletedAt;

    // 삭제한 관리자. 그 관리자가 탈퇴해도 기록이 남아야 하므로 FK 를 걸지 않는다.
    @Column(name = "deleted_by")
    private Long deletedBy;

    /**
     * @param seasonId  보낸 시점의 시즌
     * @param userId    보낸 사람
     * @param content   금칙어 마스킹을 마친 본문
     * @param tickSeq   보낸 시점의 게임 시각. 재생 중이 아니면 null
     * @param seasonDay 보낸 시점의 일차
     * @param sentAt    보낸 실제 시각
     */
    public ChatMessage(Long seasonId, Long userId, String content, Long tickSeq, Short seasonDay,
                       Instant sentAt) {
        this.seasonId = seasonId;
        this.userId = userId;
        this.content = content;
        this.tickSeq = tickSeq;
        this.seasonDay = seasonDay;
        this.sentAt = sentAt;
    }
}

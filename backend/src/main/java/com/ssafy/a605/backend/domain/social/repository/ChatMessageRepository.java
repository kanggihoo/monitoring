package com.ssafy.a605.backend.domain.social.repository;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

import com.ssafy.a605.backend.domain.social.entity.ChatMessage;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    /**
     * 그 일차의 최신 메시지 한 페이지. 삭제된 행도 포함한다 — 커서가 가리키는 지점이 사라지면
     * 안 되고, 화면은 그 자리를 "삭제된 메시지"로 그린다.
     *
     * <p>일차로 좁히는 이유는 {@link com.ssafy.a605.backend.domain.social.service.ChatService#history}
     * 에 있다. {@code idx_chat_messages_day_id} 가 이 모양이다.
     *
     * @param seasonId  진행 중인 시즌
     * @param seasonDay 보여줄 일차
     * @param limit     읽을 개수. 다음 페이지의 존재를 알기 위해 호출 측이 한 건 더 요청한다
     * @return 식별자 내림차순 메시지
     */
    List<ChatMessage> findBySeasonIdAndSeasonDayOrderByIdDesc(Long seasonId, short seasonDay,
                                                              Limit limit);

    /**
     * 그 일차에서 커서보다 과거인 메시지 한 페이지.
     *
     * @param seasonId  진행 중인 시즌
     * @param seasonDay 보여줄 일차
     * @param messageId 커서가 가리키는 식별자. 이 값은 포함하지 않는다
     * @param limit     읽을 개수
     * @return 식별자 내림차순 메시지
     */
    List<ChatMessage> findBySeasonIdAndSeasonDayAndIdLessThanOrderByIdDesc(
            Long seasonId, short seasonDay, Long messageId, Limit limit);

    /**
     * 도배 방지 쿨다운 판정 (기획서 12.2.1).
     *
     * <p>메모리가 아니라 저장된 사실로 판정한다 — 메모리에 두면 재시작 직후 쿨다운이 풀리고,
     * 서버가 둘이 되는 순간 사용자마다 두 배로 보낼 수 있게 된다.
     *
     * @param userId 보낸 사람
     * @param since  이 시각 이후를 본다
     * @return 그 사이에 보낸 것이 있으면 참
     */
    boolean existsByUserIdAndSentAtAfter(Long userId, Instant since);

    /**
     * 그 사용자의 마지막 전송 시각. 쿨다운의 남은 초를 계산하는 데 쓴다.
     *
     * @param userId 보낸 사람
     * @return 가장 최근 메시지 하나. 보낸 적이 없으면 비어 있다
     */
    List<ChatMessage> findByUserIdOrderBySentAtDesc(Long userId, Limit limit);

    /**
     * 그 시즌의 메시지를 전부 지운다. 시즌 초기화가 쓴다.
     *
     * <p><b>전체 삭제가 아니다.</b> 시즌 2 를 초기화하면서 시즌 1 의 대화까지 지우면 지난
     * 시즌의 기록이 사라진다 — 초기화는 그 시즌의 진행 상태만 되돌리는 조작이다.
     *
     * @param seasonId 초기화할 시즌
     */
    void deleteBySeasonId(Long seasonId);
}

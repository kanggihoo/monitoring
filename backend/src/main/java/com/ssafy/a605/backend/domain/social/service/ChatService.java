package com.ssafy.a605.backend.domain.social.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.ssafy.a605.backend.domain.moderation.service.ForbiddenWordFilter;
import com.ssafy.a605.backend.domain.replay.dto.GameClockSnapshot;
import com.ssafy.a605.backend.domain.replay.service.PhaseGate;
import com.ssafy.a605.backend.domain.replay.service.ReplayService;
import com.ssafy.a605.backend.domain.season.entity.GameTick;
import com.ssafy.a605.backend.domain.season.service.GameTickCatalog;
import com.ssafy.a605.backend.domain.social.dto.ChatCursor;
import com.ssafy.a605.backend.domain.social.dto.ChatHistoryResponse;
import com.ssafy.a605.backend.domain.social.dto.ChatMessageResponse;
import com.ssafy.a605.backend.domain.social.entity.ChatMessage;
import com.ssafy.a605.backend.domain.social.repository.ChatMessageRepository;
import com.ssafy.a605.backend.domain.user.entity.User;
import com.ssafy.a605.backend.domain.user.repository.UserRepository;
import com.ssafy.a605.backend.global.exception.BusinessException;
import com.ssafy.a605.backend.global.websocket.RealtimePublisher;
import com.ssafy.a605.backend.global.websocket.WsDestination;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;

/**
 * 전체 채팅 ({@code soc.md}, 기획서 12.1 · 12.2).
 *
 * <p><b>전송은 REST 이고 수신만 실시간이다.</b> WebSocket 도 양방향이지만, 보낸 것과 돌아온 것을
 * 짝지어 주는 장치가 프로토콜에 없다. 채팅 전송은 그 짝이 두 경우에 필요하다 — 쿨다운 거부에서
 * 어느 것이 실패했는지, 금칙어 마스킹에서 내가 띄운 문장을 무엇으로 바꿔 그릴지. 그 장치를
 * 다 만들고 남는 이득은 왕복 한 번이고, 이 게임은 30초 남짓의 틱으로 돈다.
 *
 * <p><b>금칙어는 차단이 아니라 마스킹이다</b> (기획서 12.2.2). 전송은 성공하고 마스킹된 본문이
 * 저장·반환된다. 400 을 주지 않는 이유는 어느 단어가 걸렸는지 알려주면 우회 방법을 알려주는
 * 셈이고, 사용자는 "왜 안 보내지나"를 모른 채 여러 번 시도하기 때문이다.
 *
 * <p><b>닉네임·아바타를 복사 저장하지 않는다.</b> 조회 시 사용자를 조인하므로 닉네임 변경이 과거
 * 메시지에 반영되고, 탈퇴자 익명화도 조인이 비는 것으로 자동 처리된다.
 */
@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class ChatService {

    static final String TYPE = "chat.message";

    /** 도배 방지 쿨다운 (기획서 12.2.1). */
    private static final Duration COOLDOWN = Duration.ofSeconds(3);

    @org.springframework.beans.factory.annotation.Value("${app.replay.season-id:1}")
    private long seasonId;

    private final ChatMessageRepository chatMessageRepository;
    private final UserRepository userRepository;
    private final GameTickCatalog gameTicks;
    private final ForbiddenWordFilter forbiddenWords;
    private final PhaseGate phaseGate;
    private final ReplayService replay;
    private final RealtimePublisher publisher;
    private final Clock clock;

    /**
     * 이 시즌의 채팅 기록을 지운다 (개발·테스트 전용 시즌 초기화).
     *
     * <p><b>전체 삭제가 아니다.</b> V40 이 {@code season_id} 를 넣기 전에는 메시지가 시즌을
     * 몰라 전체를 지우는 것이 유일하게 정확한 범위였지만, 이제는 초기화하는 시즌의 대화만
     * 지운다 — 시즌 2 를 초기화하면서 시즌 1 의 기록까지 없앨 이유가 없다.
     */
    @Transactional
    public void deleteBySeason(long seasonId) {
        chatMessageRepository.deleteBySeasonId(seasonId);
    }

    /**
     * 최신 메시지 한 페이지. 미인증도 읽을 수 있다 (기획서 12.1.5).
     *
     * <p><b>오늘 일차의 대화만 준다.</b> 2일차에 어제 대화를 최근 100건으로 받으면, 구분선이
     * 없는 화면에서 그것이 오늘 대화로 읽힌다. 자르는 기준을 서버에 두는 이유는 첫 조회와
     * 커서 조회가 같은 범위를 말해야 하기 때문이다 — 프론트가 페이징을 멈추는 방식이면 두
     * 경로가 갈린다. 지난 일차의 행은 지우지 않고 DB 에 그대로 남는다.
     *
     * @param cursor 이전 응답의 {@code nextCursor}. 첫 페이지면 {@code null}
     * @param size   페이지 크기
     * @return 최신순 메시지와 다음 커서
     * @throws BusinessException 커서가 우리가 만든 모양이 아닐 때 (400)
     */
    public ChatHistoryResponse history(String cursor, int size) {
        ChatCursor from = ChatCursor.decode(cursor);
        short seasonDay = replay.clock().seasonDay();
        // 한 건을 더 읽어 다음 페이지의 존재를 안다. COUNT 를 따로 치지 않는다.
        Limit limit = Limit.of(size + 1);
        List<ChatMessage> rows = from == null
                ? chatMessageRepository.findBySeasonIdAndSeasonDayOrderByIdDesc(
                        seasonId, seasonDay, limit)
                : chatMessageRepository.findBySeasonIdAndSeasonDayAndIdLessThanOrderByIdDesc(
                        seasonId, seasonDay, from.messageId(), limit);

        boolean hasNext = rows.size() > size;
        List<ChatMessage> page = hasNext ? rows.subList(0, size) : rows;
        Map<Long, User> senders = sendersOf(page);
        Map<Long, LocalDate> gameDates = gameDatesOf(page);

        List<ChatMessageResponse> items = page.stream()
                .map(message -> ChatMessageResponse.of(message, senders.get(message.getUserId()),
                        gameDates.get(message.getTickSeq())))
                .toList();
        String nextCursor = hasNext ? ChatCursor.after(page.getLast()).encode() : null;
        return new ChatHistoryResponse(items, nextCursor, hasNext);
    }

    /**
     * 한 줄 보낸다. 저장된 뒤 전체에 브로드캐스트된다.
     *
     * @param userId  보내는 사용자
     * @param content 원본 본문. 금칙어가 있으면 마스킹해 저장한다
     * @return 저장된 메시지. 마스킹 결과가 이 응답에 있다
     * @throws BusinessException 열람 전용 시간(409)이거나 쿨다운이 남았을 때(429)
     */
    @Transactional
    public ChatMessageResponse send(Long userId, String content) {
        GameClockSnapshot gameClock = phaseGate.requireOperating();
        Instant now = Instant.now(clock);
        requireCooldownElapsed(userId, now);

        // 일차는 게이트가 통과시킨 그 시계에서 가져온다. 나중에 tick_seq 로 되돌리지 않는
        // 이유는 브리핑의 틱이 전날 것이기 때문이다 (V36).
        ChatMessage saved = chatMessageRepository.save(
                new ChatMessage(seasonId, userId, forbiddenWords.mask(content),
                        gameClock.tickSeq(), gameClock.seasonDay(), now));
        User sender = userRepository.findById(userId).orElse(null);
        ChatMessageResponse response = ChatMessageResponse.of(saved, sender, gameClock.gameDate());
        // 커밋 뒤에 브로드캐스트한다. 롤백된 메시지가 남의 화면에 뜨면 되돌릴 방법이 없다.
        afterCommit(() -> broadcast(response, gameClock));
        return response;
    }

    /**
     * 3초가 지나지 않았으면 거부한다.
     *
     * <p><b>여기만 429 다.</b> 409 로 뭉뚱그리면 프론트가 "3초 뒤 다시"라는 재시도 가능한 실패와
     * "열람 전용이라 불가"라는 재시도 불가능한 실패를 구분하지 못한다. 남은 초를
     * {@code Retry-After} 로 함께 내려준다.
     */
    private void requireCooldownElapsed(Long userId, Instant now) {
        Instant since = now.minus(COOLDOWN);
        if (!chatMessageRepository.existsByUserIdAndSentAtAfter(userId, since)) {
            return;
        }
        long remaining = chatMessageRepository.findByUserIdOrderBySentAtDesc(userId, Limit.of(1)).stream()
                .findFirst()
                .map(last -> COOLDOWN.minus(Duration.between(last.getSentAt(), now)).toSeconds() + 1)
                .orElse(COOLDOWN.toSeconds());
        throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS,
                "너무 빠르게 연속으로 보냈습니다. 잠시 후 다시 시도하세요.", Math.max(remaining, 1));
    }

    private void broadcast(ChatMessageResponse message, GameClockSnapshot gameClock) {
        try {
            // 채팅은 게임 시계와 독립적으로 흐른다 — 봉투의 틱은 보낸 시점의 것이고 정렬 기준이
            // 아니다. 그래도 싣는 이유는 모든 실시간 메시지가 공개 기준을 함께 실어야 하기
            // 때문이다 ({@code _common.md} §10). 재생 중이 아니면 null 이다.
            publisher.broadcast(WsDestination.CHAT, TYPE, gameClock.tickSeq(), gameClock.gameDate(), message);
        } catch (RuntimeException e) {
            log.error("Failed to broadcast {} for message {}", TYPE, message.messageId(), e);
        }
    }

    /** 페이지에 든 사용자만 조인한다. 탈퇴한 사람은 결과에 없고 그것이 익명화다. */
    private Map<Long, User> sendersOf(List<ChatMessage> page) {
        if (page.isEmpty()) {
            return Map.of();
        }
        return userRepository.findAllById(page.stream().map(ChatMessage::getUserId).distinct().toList()).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
    }

    /** 페이지에 든 틱의 게임 거래일. 재생 중이 아니었던 메시지는 틱이 없다. */
    private Map<Long, LocalDate> gameDatesOf(List<ChatMessage> page) {
        List<Long> tickSeqs = page.stream().map(ChatMessage::getTickSeq).filter(Objects::nonNull).distinct().toList();
        if (tickSeqs.isEmpty()) {
            return Map.of();
        }
        return gameTicks.findAll(tickSeqs).stream()
                .collect(Collectors.toMap(GameTick::getTickSeq, GameTick::getGameDate, (a, b) -> a));
    }

    private static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }
}

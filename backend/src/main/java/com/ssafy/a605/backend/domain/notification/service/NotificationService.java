package com.ssafy.a605.backend.domain.notification.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.ssafy.a605.backend.domain.account.entity.Account;
import com.ssafy.a605.backend.domain.account.repository.AccountRepository;
import com.ssafy.a605.backend.domain.notification.dto.NotificationCreatedPayload;
import com.ssafy.a605.backend.domain.notification.dto.NotificationPageResponse;
import com.ssafy.a605.backend.domain.notification.dto.NotificationResponse;
import com.ssafy.a605.backend.domain.notification.entity.Notification;
import com.ssafy.a605.backend.domain.notification.entity.NotificationKind;
import com.ssafy.a605.backend.domain.notification.repository.NotificationBulkRepository;
import com.ssafy.a605.backend.domain.notification.repository.NotificationRepository;
import com.ssafy.a605.backend.domain.season.entity.GameTick;
import com.ssafy.a605.backend.domain.season.service.GameTickCatalog;
import com.ssafy.a605.backend.global.exception.BusinessException;
import com.ssafy.a605.backend.global.response.PageResponse;
import com.ssafy.a605.backend.global.websocket.RealtimePublisher;

import lombok.extern.slf4j.Slf4j;

/**
 * 알림 센터 ({@code noti.md}, 기획서 15장). 목록·일괄 읽음과, 다른 도메인이 알림을 만들 때 부르는
 * 단일 진입점 {@link #create} 를 둔다.
 *
 * <p><b>알림은 계좌에 매달린다.</b> 다섯 유형이 전부 게임 계좌의 사건이고 시즌에 종속되므로
 * 사용자가 아니라 계좌로 찾는다. 계좌가 없는 사용자에게는 알림도 없다 (404).
 *
 * <p><b>문구는 여기서 조립하지 않는다.</b> 발생 시점의 값(적용 수수료율·순위)이 들어가므로 사건을
 * 아는 쪽이 완성된 문장을 넘기고, 이 서비스는 저장하고 전달만 한다 ({@code noti.md} §문구를 저장한다).
 *
 * <p><b>개인 메시지는 커밋 뒤에 나간다.</b> 호출자가 트랜잭션 안(예약 체결 회계)이면 그 커밋에
 * 맞춰 보내고, 밖이면 저장 직후 보낸다. 롤백된 알림이 토스트로 뜨면 배지 수와 목록이 어긋난다.
 */
@Slf4j
@Service
@Transactional(readOnly = true)
public class NotificationService {

    static final String TYPE = "noti.created";
    private static final String NO_ACCOUNT = "시즌 계좌가 없습니다.";

    private final NotificationRepository notificationRepository;
    private final NotificationBulkRepository bulk;
    private final AccountRepository accountRepository;
    private final GameTickCatalog gameTicks;
    private final RealtimePublisher publisher;
    private final Clock clock;
    private final long seasonId;

    public NotificationService(NotificationRepository notificationRepository, NotificationBulkRepository bulk,
                               AccountRepository accountRepository,
                               GameTickCatalog gameTicks, RealtimePublisher publisher, Clock clock,
                               @Value("${app.replay.season-id:1}") long seasonId) {
        this.notificationRepository = notificationRepository;
        this.bulk = bulk;
        this.accountRepository = accountRepository;
        this.gameTicks = gameTicks;
        this.publisher = publisher;
        this.clock = clock;
        this.seasonId = seasonId;
    }

    /**
     * 내 알림 한 페이지와 읽지 않은 알림 수.
     *
     * @param userId   세션 사용자
     * @param pageable 페이지. 정렬은 최신순으로 고정이라 호출자가 넘긴 것을 그대로 쓴다
     * @return 알림 페이지와 미읽음 수
     * @throws BusinessException 그 시즌 계좌가 없을 때 (404)
     */
    public NotificationPageResponse list(Long userId, Pageable pageable) {
        Account account = account(userId);
        Page<Notification> page = notificationRepository.findByAccountId(account.getId(), pageable);
        Map<Long, LocalDate> gameDates = gameDatesOf(page);
        return NotificationPageResponse.of(notificationRepository.countByAccountIdAndReadAtIsNull(account.getId()),
                PageResponse.from(page.map(n -> NotificationResponse.from(n, gameDates.get(n.getTickSeq())))));
    }

    /**
     * 내 알림 중 읽지 않은 것 전부를 지금 읽음 처리한다. 알림 센터를 <b>열 때</b> 부른다 — 첫 페이지만이
     * 아니라 전부다. 배지가 "3"인데 열고 나서도 "1"이 남으면 사용자는 못 찾은 알림을 뒤진다.
     *
     * @param userId 세션 사용자
     * @throws BusinessException 그 시즌 계좌가 없을 때 (404)
     */
    @Transactional
    public void readAll(Long userId) {
        Account account = account(userId);
        notificationRepository.markAllRead(account.getId(), Instant.now(clock));
    }

    /**
     * 알림 한 건을 만들고 커밋 뒤 개인 메시지 {@code noti.created} 로 전달한다.
     *
     * @param accountId   받는 계좌
     * @param kind        유형
     * @param title       발생 시점에 확정한 표기 문구
     * @param body        발생 시점에 확정한 본문
     * @param referenceId 이동할 대상의 식별자. 고정 화면으로 가는 유형이면 {@code null}
     * @param tickSeq     발생 시점의 게임 시각. 틱에 속하지 않는 사건이면 {@code null}
     * @return 저장된 알림
     */
    @Transactional
    public Notification create(Long accountId, NotificationKind kind, String title, String body,
                               Long referenceId, Long tickSeq) {
        Notification saved = notificationRepository.save(
                new Notification(accountId, kind, title, body, referenceId, tickSeq));
        long unread = notificationRepository.countByAccountIdAndReadAtIsNull(accountId);
        Long userId = accountRepository.findById(accountId).map(Account::getUserId).orElse(null);
        LocalDate gameDate = tickSeq == null ? null
                : gameTicks.find(tickSeq).map(GameTick::getGameDate).orElse(null);
        if (userId != null) {
            afterCommit(() -> send(userId, saved, gameDate, unread));
        }
        return saved;
    }

    /**
     * 같은 문구를 여러 계좌에 한 번에 남긴다. 전원 공지({@link NotificationKind#NOTICE})가 쓴다.
     *
     * <p><b>{@link #create} 를 사람 수만큼 부르지 않는다.</b> 그러면 계좌마다 트랜잭션 하나와
     * 부수 질의 셋(미읽음 수 · 계좌 · 거래일)이 생겨, 참가자가 늘수록 틱 하나에 하는 일이
     * 사람 수에 비례해 늘어난다. 여기서는 트랜잭션 하나에 넣고 <b>INSERT 도 한 문장</b>이며
     * ({@link NotificationBulkRepository}) 부수 질의를 각각 한 번씩만 친다.
     *
     * <p>사람 수만큼 남는 것은 개인 큐 전송뿐이다 — 받는 사람이 다르므로 묶을 수 있는 것이 아니다.
     *
     * <p><b>전부 성공하거나 전부 없다.</b> 한 계좌의 실패로 나머지가 남으면 같은 공지를 받은
     * 사람과 못 받은 사람이 갈리고, 그 상태를 되짚을 방법이 없다.
     *
     * @param accountIds  받는 계좌. 비어 있으면 아무것도 하지 않는다
     * @param kind        유형
     * @param title       발생 시점에 확정한 표기 문구
     * @param body        발생 시점에 확정한 본문
     * @param referenceId 이동할 대상의 식별자. 고정 화면으로 가는 유형이면 {@code null}
     * @param tickSeq     발생 시점의 게임 시각. 틱에 속하지 않는 사건이면 {@code null}
     */
    @Transactional
    public void createForAll(List<Long> accountIds, NotificationKind kind, String title, String body,
                             Long referenceId, Long tickSeq) {
        if (accountIds.isEmpty()) {
            return;
        }
        // 행 생성 시각은 엔티티의 @PrePersist 와 같은 규칙이다 — 두 경로가 다른 시계를 쓰면
        // 같은 목록 안에서 정렬 기준이 갈린다.
        Instant createdAt = Instant.now();
        Map<Long, Long> ids = bulk.insertAll(accountIds, kind, title, body, referenceId, tickSeq, createdAt);
        // 방금 한 통씩 넣었으므로 모든 대상 계좌가 미읽음 하나 이상이다 — 빠지는 계좌가 없다.
        Map<Long, Long> unread = notificationRepository.countUnreadByAccountIds(accountIds).stream()
                .collect(Collectors.toMap(row -> (Long) row[0], row -> (Long) row[1]));
        Map<Long, Long> userIds = accountRepository.findUserIdsByIds(accountIds).stream()
                .collect(Collectors.toMap(row -> (Long) row[0], row -> (Long) row[1]));
        LocalDate gameDate = tickSeq == null ? null
                : gameTicks.find(tickSeq).map(GameTick::getGameDate).orElse(null);
        afterCommit(() -> ids.forEach((accountId, notificationId) -> {
            Long userId = userIds.get(accountId);
            if (userId != null) {
                send(userId, new NotificationResponse(notificationId, kind, title, body, referenceId,
                        tickSeq, gameDate, false, createdAt), unread.getOrDefault(accountId, 1L));
            }
        }));
    }

    private void send(Long userId, Notification notification, LocalDate gameDate, long unread) {
        send(userId, NotificationResponse.from(notification, gameDate), unread);
    }

    private void send(Long userId, NotificationResponse notification, long unread) {
        try {
            publisher.sendToUser(userId, TYPE, notification.tickSeq(), notification.gameDate(),
                    new NotificationCreatedPayload(notification, unread));
        } catch (RuntimeException e) {
            log.error("Failed to send {} to user {} for notification {}", TYPE, userId,
                    notification.notificationId(), e);
        }
    }

    /** 트랜잭션 안이면 커밋 뒤에, 밖이면 지금 실행한다. */
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

    /** 페이지에 든 틱의 게임 거래일. 틱이 없는 알림은 빠진다. */
    private Map<Long, LocalDate> gameDatesOf(Page<Notification> page) {
        return gameTicks.findAll(page.getContent().stream()
                        .map(Notification::getTickSeq).filter(Objects::nonNull).distinct().toList()).stream()
                .collect(Collectors.toMap(GameTick::getTickSeq, GameTick::getGameDate, (a, b) -> a));
    }

    private Account account(Long userId) {
        return accountRepository.findByUserIdAndSeasonId(userId, seasonId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, NO_ACCOUNT));
    }
}

package com.ssafy.a605.backend.domain.notification.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.ssafy.a605.backend.domain.notification.entity.Notification;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    /**
     * 한 계좌의 알림 한 페이지. 정렬은 호출자가 {@code Pageable} 로 준다 (최신순 고정).
     *
     * @param accountId 계좌
     * @param pageable  페이지와 정렬
     * @return 알림 페이지
     */
    Page<Notification> findByAccountId(Long accountId, Pageable pageable);

    /**
     * 읽지 않은 알림 수. 헤더 배지의 값이다 (기획서 15.1.4).
     *
     * @param accountId 계좌
     * @return {@code readAt} 이 비어 있는 행의 수
     */
    long countByAccountIdAndReadAtIsNull(Long accountId);

    /**
     * 그 계좌의 읽지 않은 알림 전부를 읽음 처리한다. 이미 읽은 행은 건드리지 않으므로 멱등이다.
     *
     * @param accountId 계좌
     * @param readAt    읽음 처리 시각
     * @return 갱신된 행 수
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE Notification n SET n.readAt = :readAt WHERE n.accountId = :accountId AND n.readAt IS NULL")
    int markAllRead(@Param("accountId") Long accountId, @Param("readAt") Instant readAt);

    /**
     * 그 계좌들의 읽지 않은 알림 수. 전원 공지가 계좌마다 배지 값을 실어 보내야 해서 필요하다
     * ({@code NotificationService.createForAll}).
     *
     * <p><b>계좌당 한 번씩 세지 않는다.</b> 같은 질의를 사람 수만큼 치면 공지 한 번이 참가자
     * 수만큼의 왕복이 된다 — 그룹으로 한 번에 센다.
     *
     * @param accountIds 대상 계좌
     * @return {@code [accountId, count]} 쌍. 미읽음이 없는 계좌는 빠진다
     */
    @Query("SELECT n.accountId, count(n) FROM Notification n "
            + "WHERE n.accountId IN :accountIds AND n.readAt IS NULL GROUP BY n.accountId")
    List<Object[]> countUnreadByAccountIds(@Param("accountIds") Collection<Long> accountIds);

    /**
     * 그 계좌의 행 전부를 지운다. 탈퇴가 쓴다 ({@code AccountPurgeService}).
     *
     * @param accountId 대상 계좌
     */
    void deleteByAccountId(Long accountId);
}

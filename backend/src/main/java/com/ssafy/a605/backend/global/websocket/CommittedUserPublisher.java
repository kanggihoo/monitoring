package com.ssafy.a605.backend.global.websocket;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** 사용자 요청의 성공한 변경만 개인 큐에 전달한다. 롤백되면 발행하지 않는다. */
@Component
@RequiredArgsConstructor
@Slf4j
public class CommittedUserPublisher {
    private final RealtimePublisher publisher;

    /**
     * 불변 응답을 커밋 뒤 전달한다. 틱과 무관한 요청이므로 봉투의 틱은 비운다.
     * @param userId 변경한 사용자
     * @param type 사건 종류
     * @param payload 트랜잭션 안에서 확정한 응답
     */
    public void send(Long userId, String type, Object payload) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    publisher.sendToUser(userId, type, null, null, payload);
                } catch (RuntimeException error) {
                    log.error("Committed {} delivery failed for user {}", type, userId, error);
                }
            }
        });
    }
}

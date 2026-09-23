package com.ssafy.a605.backend.domain.replay.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.ssafy.a605.backend.domain.replay.entity.ReplayMode;

import lombok.extern.slf4j.Slf4j;

/**
 * 트랜잭션 없는 반복 실행만 담당한다. 진행과 발행은 {@link ReplayService} 가 한다.
 *
 * <p>밀린 항목은 한 번에 최대 100개 또는 1초까지 처리하고 나머지는 다음 호출에서 이어간다.
 * 한도는 틱을 생략하거나 앵커를 늦추는 근거가 아니다.
 */
@Component
@Slf4j
public class ReplayScheduler {

    private static final int MAX_ITEMS_PER_RUN = 100;
    private static final long MAX_NANOS_PER_RUN = 1_000_000_000L;

    private final ReplayService replay;
    private final boolean enabled;

    /**
     * 진행을 멈춘 원인. 같은 원인으로는 한 번만 기록한다 — 폴러는 100ms마다 도는데 초기화되지
     * 않은 시즌이나 잘못된 운영 배속은 사람이 고칠 때까지 이어지므로, 매 주기 스택트레이스를
     * 남기면 초당 10줄로 로그가 묻힌다.
     */
    private String haltedReason;

    public ReplayScheduler(ReplayService replay, @Value("${app.replay.mode:disabled}") ReplayMode mode) {
        this.replay = replay;
        this.enabled = mode != ReplayMode.DISABLED;
    }

    @Scheduled(fixedDelayString = "${app.replay.poll-delay-ms:100}", scheduler = "replayTaskScheduler")
    public void poll() {
        if (!enabled) {
            return;
        }
        long deadline = System.nanoTime() + MAX_NANOS_PER_RUN;
        try {
            for (int i = 0; i < MAX_ITEMS_PER_RUN && System.nanoTime() < deadline; i++) {
                if (!replay.progress()) {
                    break;
                }
            }
            if (haltedReason != null) {
                log.info("Replay polling recovered from: {}", haltedReason);
                haltedReason = null;
            }
        } catch (RuntimeException e) {
            // 저장된 진행 위치는 그대로다. 원인이 해소되면 같은 지점에서 이어진다.
            String reason = e.getClass().getSimpleName() + ": " + e.getMessage();
            if (!reason.equals(haltedReason)) {
                haltedReason = reason;
                log.error("Replay polling halted; persisted position retained. "
                        + "Further identical failures are not logged until this clears.", e);
            }
        }
    }
}

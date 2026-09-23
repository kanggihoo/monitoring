package com.ssafy.a605.backend.domain.report.listener;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;

import com.ssafy.a605.backend.domain.ranking.dto.DailySettlementFinalizedEvent;
import com.ssafy.a605.backend.domain.replay.dto.PhaseBoundaryEvent;
import com.ssafy.a605.backend.domain.replay.entity.ReplayState;
import com.ssafy.a605.backend.domain.report.service.AiDailyReportService;
import com.ssafy.a605.backend.domain.report.service.AiSeasonReportService;

import lombok.extern.slf4j.Slf4j;

/**
 * 폐장 스냅샷이 확정되면 AI 문구 생성을 건다 (AI 요청서 §2).
 *
 * <p><b>다른 스레드로 넘긴다.</b> 이 이벤트는 폐장 경계에서 <b>동기</b>로 발행되고 그 경계는
 * 재생 폴러가 돌린다 — 여기서 AI 를 기다리면 응답 시간(최대 10 초, 재시도까지 20 초)만큼
 * 게임 진행이 멈춘다. 정산과 순위는 이미 커밋됐으므로 문구는 뒤따라가면 된다.
 *
 * <p>여유는 충분하다. 폐장 16:40 에 확정하고 정산 발표가 17:00 이라 20 분이 있다.
 *
 * <p><b>발표 경계에서 한 번 더 건다.</b> 폐장의 호출이 실패하면 그 일차는 문구 없이
 * 끝나는데, 발표 뒤로는 그 일차에 아무 경계도 남지 않아 만회할 자리가 사라진다.
 *
 * <p><b>예외를 밖으로 내보내지 않는다.</b> 넘기는 것 자체가 실패해도(큐 포화 등) 폐장 처리는
 * 계속돼야 한다 — 문구가 없는 리포트는 공개되지만, 멈춘 게임은 되돌릴 수 없다.
 *
 * <p><b>{@code app.report.ai.enabled=false} 로 끌 수 있다. 미설정은 켜짐이다</b> — 프로덕션
 * 동작은 바뀌지 않는다. 개발에서 지점 이동으로 폐장을 반복해 지날 때 모델 호출 비용이 쌓이는
 * 것이 유일한 실질 비용이라 이것만 스위치를 둔다. 배속처럼 런타임에 바꾸지 않는다 — 빈
 * 조건화가 이 리포의 기존 관용구이고 fail-closed 다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.report.ai.enabled", matchIfMissing = true)
@Order(AiReportListener.PRECEDENCE)
public class AiReportListener {

    /**
     * {@link SeasonReportListener} 보다 큰 값이라 <b>뒤에</b> 돈다. 시즌 payload 가 그
     * 리스너가 만드는 {@code season_reports} 를 읽기 때문이다 — 순서만 계약이다.
     */
    public static final int PRECEDENCE = SeasonReportListener.PRECEDENCE + 100;

    private final AiDailyReportService dailyReports;
    private final AiSeasonReportService seasonReports;

    /**
     * 프레임워크가 구성해 주는 공용 실행기. 문구 생성은 짧고 드물어 전용 풀을 두지 않는다.
     *
     * <p>구성은 {@code AiReportExecutorConfig} 에 있다 — 재생 스케줄러에 얹으면 다른 스레드로
     * 넘긴 의미가 사라진다.
     */
    private final TaskExecutor taskExecutor;

    /**
     * @param dailyReports  일일 문구 생성
     * @param seasonReports 시즌 문구 생성
     * @param taskExecutor 공용 실행기
     */
    public AiReportListener(AiDailyReportService dailyReports,
                            AiSeasonReportService seasonReports,
                            @Qualifier("aiReportExecutor")
                            TaskExecutor taskExecutor) {
        this.dailyReports = dailyReports;
        this.seasonReports = seasonReports;
        this.taskExecutor = taskExecutor;
    }

    /**
     * 그 일차의 문구를 만들게 한다.
     *
     * @param event 커밋된 폐장 스냅샷. 랭킹 도메인이 발행한다
     */
    @EventListener
    public void on(DailySettlementFinalizedEvent event) {
        short seasonDay = event.seasonDay();
        try {
            taskExecutor.execute(() -> generate(seasonDay));
        } catch (RuntimeException e) {
            log.error("Could not schedule AI daily texts for season day {}", seasonDay, e);
        }
        if (seasonDay != ReplayState.FINAL_DAY) {
            return;
        }
        // 마지막 일차의 문구는 둘이다. 시즌 쪽은 유형 판정에 전원의 분포가 필요해
        // 일일과 별개의 호출로 나간다 (요청서 v5 §7.1).
        try {
            taskExecutor.execute(this::generateSeason);
        } catch (RuntimeException e) {
            log.error("Could not schedule AI season texts", e);
        }
    }

    /**
     * 발표에 들어가면 못 받은 문구를 한 번 더 만든다 (마지막 만회 지점).
     *
     * <p>폐장에 걸어 둔 호출이 실패하면 그 일차는 문구 없이 끝난다 — 이 뒤로 그 일차에는
     * 아무 경계도 남지 않는다. 이미 받은 날은 조회 한 번으로 걸러지므로 평소에는 아무
     * 일도 일어나지 않는다.
     *
     * @param event 커밋된 Phase 경계. 재생 도메인이 발행한다
     */
    @EventListener
    public void on(PhaseBoundaryEvent event) {
        if (!event.isAnnounce()) {
            return;
        }
        short seasonDay = event.seasonDay();
        try {
            taskExecutor.execute(() -> retry(seasonDay));
        } catch (RuntimeException e) {
            log.error("Could not schedule the AI text retry for season day {}", seasonDay, e);
        }
    }

    private void retry(short seasonDay) {
        try {
            dailyReports.regenerateIfMissing(seasonDay);
        } catch (RuntimeException e) {
            log.error("AI daily text retry failed for season day {}", seasonDay, e);
        }
        if (seasonDay != ReplayState.FINAL_DAY) {
            return;
        }
        try {
            seasonReports.regenerateIfMissing();
        } catch (RuntimeException e) {
            log.error("AI season text retry failed", e);
        }
    }

    private void generate(short seasonDay) {
        try {
            dailyReports.generate(seasonDay);
        } catch (RuntimeException e) {
            log.error("AI daily text generation failed for season day {}", seasonDay, e);
        }
    }

    private void generateSeason() {
        try {
            seasonReports.generate();
        } catch (RuntimeException e) {
            log.error("AI season text generation failed", e);
        }
    }
}

package com.ssafy.a605.backend.domain.report.listener;

import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.ssafy.a605.backend.domain.ranking.dto.DailySettlementFinalizedEvent;
import com.ssafy.a605.backend.domain.report.service.SeasonReportService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 마지막 일차의 폐장 스냅샷이 커밋되면 시즌 리포트를 확정한다 ({@code rpt.md} 기획서 13.3).
 *
 * <p><b>폐장 트랜잭션 밖이다.</b> 정산이 커밋된 뒤 발행되는 이벤트를 받으므로, 여기서 무엇이
 * 잘못돼도 이미 확정된 순위는 되돌아가지 않는다.
 *
 * <p><b>예외를 밖으로 내보내지 않는다.</b> 이 이벤트는 재생 폴러가 경계를 커밋한 뒤 같은
 * 스레드에서 발행한다 — 던지면 리포트 실패가 아니라 재생 정지로 기록된다. 시즌 리포트는 발표
 * 까지 20 분이 남아 있고 다시 만들 수 있지만, 멈춘 재생은 사람이 붙어야 한다.
 */
@Slf4j
@Component
@Order(SeasonReportListener.PRECEDENCE)
@RequiredArgsConstructor
public class SeasonReportListener {

    /**
     * 같은 이벤트를 듣는 리스너 사이의 순서. AI 문구 리스너보다 작은 값이라 <b>먼저</b>
     * 돈다 — AI 시즌 payload 가 여기서 만드는 {@code season_reports} 를 읽으므로,
     * 뒤에 돌면 빈 테이블 위에서 문구를 만들게 된다. 값 자체는 임의이고 순서만 계약이다.
     */
    public static final int PRECEDENCE = 100;

    private final SeasonReportService seasonReports;

    /**
     * @param event 커밋된 폐장 스냅샷. 마지막 일차가 아니면 서비스가 그냥 돌아온다
     */
    @EventListener
    public void on(DailySettlementFinalizedEvent event) {
        try {
            seasonReports.finalizeSeason(event.seasonDay());
        } catch (RuntimeException e) {
            log.error("Season report finalization failed at day {}; replay continues",
                    event.seasonDay(), e);
        }
    }
}

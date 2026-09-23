package com.ssafy.a605.backend.domain.report.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * AI 문구 생성을 돌릴 스레드 (AI 요청서 §2).
 *
 * <p><b>전용 실행기를 둔다.</b> 이 프로세스에는 실행기가 여럿이고 그중 하나가 재생 폴러를
 * 돌린다 — 공용 실행기에 얹으면 이름으로 고르게 되어 프레임워크 판 올림에 흔들리고, 재생
 * 스케줄러에 얹으면 다른 스레드로 넘긴 의미가 사라진다.
 *
 * <p><b>스레드 하나면 된다.</b> 문구 생성은 하루에 한 번, 시즌에 네 번(3일차 + 시즌 리포트)
 * 일어나고 한 번이 몇 초다. 큐도 짧게 둔다 — 밀릴 일이 없고, 밀린다면 그것은 AI 가 죽은
 * 상황이라 쌓아 두는 것이 답이 아니다.
 */
@Configuration
public class AiReportExecutorConfig {

    /** 폐장 정산 리스너가 문구 생성을 넘길 자리. */
    @Bean
    public TaskExecutor aiReportExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(2);
        executor.setThreadNamePrefix("ai-report-");
        // 넘치면 조용히 버린다. 문구가 없는 리포트는 공개되지만(요청서 §8), 호출자를 붙들면
        // 폐장 경계가 그만큼 늦어진다.
        executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.DiscardPolicy());
        executor.initialize();
        return executor;
    }
}

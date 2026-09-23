package com.ssafy.a605.backend.domain.replay.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** 게임 폴러가 WS 하트비트 풀을 점유하지 않도록 명시적으로 분리한다. */
@Configuration
@EnableScheduling
public class ReplayConfig {
    @Bean
    public Clock replayClock() { return Clock.systemUTC(); }

    @Bean
    public ThreadPoolTaskScheduler replayTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("replay-");
        return scheduler;
    }
}

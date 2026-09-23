package com.ssafy.a605.backend.domain.report.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;

import com.ssafy.a605.backend.domain.report.service.AiDailyReportService;
import com.ssafy.a605.backend.domain.report.service.AiSeasonReportService;

/**
 * AI 문구 생성 스위치가 실제로 빈을 만들고 없앤다. <b>프로퍼티 이름의 오타는 이 자리에서만
 * 드러난다</b> — 이름이 틀리면 스위치를 꺼도 리스너가 살아 있고, 그 사실은 모델 호출 비용이
 * 청구될 때까지 보이지 않는다.
 *
 * <p><b>미설정은 켜짐이다.</b> 프로덕션은 이 값을 설정하지 않으므로 그 경우가 곧 운영 동작이다.
 */
class AiReportListenerConditionTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Stubs.class, AiReportListener.class);

    @Test
    @DisplayName("AI 리포트 리스너_설정하지 않은 환경_켜져 있다")
    void listener_whenUnset_isRegistered() {
        runner.run(context -> assertThat(context).hasSingleBean(AiReportListener.class));
    }

    @Test
    @DisplayName("AI 리포트 리스너_명시적으로 끈 환경_등록되지 않는다")
    void listener_whenDisabled_isNotRegistered() {
        runner.withPropertyValues("app.report.ai.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(AiReportListener.class));
    }

    @Test
    @DisplayName("AI 리포트 리스너_명시적으로 켠 환경_등록된다")
    void listener_whenEnabled_isRegistered() {
        runner.withPropertyValues("app.report.ai.enabled=true")
                .run(context -> assertThat(context).hasSingleBean(AiReportListener.class));
    }

    /** 조건만 보는 테스트라 협력자는 동작하지 않아도 된다. */
    @Configuration(proxyBeanMethods = false)
    static class Stubs {

        @Bean
        AiDailyReportService aiDailyReportService() {
            return mock(AiDailyReportService.class);
        }

        @Bean
        AiSeasonReportService aiSeasonReportService() {
            return mock(AiSeasonReportService.class);
        }

        @Bean
        TaskExecutor aiReportExecutor() {
            return new SyncTaskExecutor();
        }
    }
}

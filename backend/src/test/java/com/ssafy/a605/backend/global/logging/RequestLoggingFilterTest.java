package com.ssafy.a605.backend.global.logging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

class RequestLoggingFilterTest {

    @Test
    @DisplayName("API 요청_처리 전후에_메서드·경로·상태 코드 로그를 남긴다")
    void doFilterInternal_apiRequest_logsStartAndEnd() throws Exception {
        RequestLoggingFilter filter = new RequestLoggingFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/users/me");
        MockHttpServletResponse response = new MockHttpServletResponse();
        Logger logger = (Logger) LoggerFactory.getLogger(RequestLoggingFilter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            filter.doFilter(request, response, (ignoredRequest, ignoredResponse) ->
                    ((MockHttpServletResponse) ignoredResponse).setStatus(200));

            assertThat(appender.list)
                    .extracting(ILoggingEvent::getFormattedMessage)
                    .contains("API 시작: GET /api/v1/users/me")
                    .anyMatch(message -> message.startsWith("API 종료: GET /api/v1/users/me status=200 elapsed="));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    @DisplayName("API 외 요청_필터를 통과해도_요청 로그를 남기지 않는다")
    void doFilter_nonApiRequest_doesNotLog() throws Exception {
        RequestLoggingFilter filter = new RequestLoggingFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v3/api-docs");
        MockHttpServletResponse response = new MockHttpServletResponse();
        Logger logger = (Logger) LoggerFactory.getLogger(RequestLoggingFilter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> { });

            assertThat(appender.list).isEmpty();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}

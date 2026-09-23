package com.ssafy.a605.backend.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;
import org.springframework.session.web.http.HttpSessionIdResolver;

import com.ssafy.a605.backend.global.security.CookieOrHeaderHttpSessionIdResolver;

/**
 * 세션 ID 운반 방식을 구성한다. 세션 저장소 자체는 Boot 자동 설정이 잡는다
 * ({@code spring.session.data.redis.repository-type=indexed}).
 */
@Configuration
public class SessionConfig {

    /**
     * 세션 쿠키의 속성을 정한다.
     *
     * <p>커스텀 {@link HttpSessionIdResolver} 를 등록하면 Boot 의 {@code DefaultCookieSerializer}
     * 자동 설정이 물러나므로, {@code server.servlet.session.cookie.*} 대신 여기서 직접 정한다.
     *
     * @return 세션 쿠키 직렬화기
     */
    @Bean
    public CookieSerializer cookieSerializer() {
        DefaultCookieSerializer cookieSerializer = new DefaultCookieSerializer();
        cookieSerializer.setUseHttpOnlyCookie(true);
        // 개발 환경에서도 켠다. 브라우저가 localhost 를 신뢰할 수 있는 출처로 취급하므로
        // 환경별 분기 스위치가 필요 없다 (사설 IP 에는 이 예외가 적용되지 않는다).
        cookieSerializer.setUseSecureCookie(true);
        cookieSerializer.setSameSite("Lax");
        return cookieSerializer;
    }

    /**
     * @param cookieSerializer 세션 쿠키 직렬화기
     * @return 웹은 쿠키, 앱은 헤더로 세션 ID 를 나르는 리졸버
     */
    @Bean
    public HttpSessionIdResolver httpSessionIdResolver(CookieSerializer cookieSerializer) {
        return new CookieOrHeaderHttpSessionIdResolver(cookieSerializer);
    }
}

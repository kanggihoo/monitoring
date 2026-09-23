package com.ssafy.a605.backend.domain.testsupport.config;

import java.util.Arrays;
import java.util.stream.Stream;

import org.flywaydb.core.api.Location;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 테스트 지원 기능이 켜졌을 때만 만들어지는 인프라.
 *
 * <p>이 클래스가 존재하는 이유는 기존 설정을 건드리지 않기 위해서다. 테스트 로그인 경로는 전용
 * 필터 체인이 잡고, 시드는 전용 Flyway 위치가 잡는다. 기능이 꺼지면 둘 다 존재하지 않으므로
 * {@code SecurityConfig} 와 {@code db/migration} 에 이 기능의 흔적이 0 이다 (ADR-0003).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnTestSupport
@EnableConfigurationProperties(TestSupportProperties.class)
public class TestSupportConfig {

    /**
     * 테스트 로그인 경로. 아래 전용 체인이 이 <b>한 경로만</b> 잡는다 — 와일드카드로 접두사 전체를
     * 열면 이 아래 나중에 생길 경로까지 함께 열린다 (conventions/code.md §7).
     */
    public static final String LOGIN_PATH = "/api/v1/test-support/login";

    /**
     * 샌드박스 경로. 전용 체인의 매처에 걸리지 않으므로 기존 체인의 관리자 권한 검사를 그대로 받는다.
     * 컨트롤러 빈만 플래그로 꺼지고, 꺼진 상태에서 관리자가 호출하면 404 다.
     */
    public static final String SANDBOX_PATH = "/api/v1/admin/sandbox";

    /**
     * WS 관통 확인용 발행 트리거 경로. 위 전용 체인의 매처에 걸리지 않으므로 기존 체인의
     * {@code .anyRequest().authenticated()} 를 그대로 받는다 — 개인 메시지의 수신자를 호출자
     * 자신으로 정하려면 인증 주체가 있어야 한다.
     */
    public static final String BROADCAST_PATH = "/api/v1/test-support/broadcast";

    /** 시드가 있는 곳. {@code db/migration} 과 물리적으로 분리돼 프로덕션 혼입이 불가능하다. */
    private static final String TEST_DATA_LOCATION = "classpath:db/testdata";

    /**
     * 테스트 로그인 전용 필터 체인. 기존 체인보다 먼저 평가되며, 그 경로만 잡는다.
     *
     * <p>위조 방지 토큰 검사를 이 체인 안에서만 끈다. 전역 검사의 현재 예외는 앱 인증 헤더가 붙은
     * 요청뿐인데 최초 로그인 요청에는 그 헤더가 없어 거부된다. 이 경로는 API 키로 인증하므로
     * 위조 요청이 성립하지 않는다.
     *
     * <p>인가는 열어두고 <b>API 키 검사는 서비스가 한다.</b> 이 경로에 도달할 수 있다는 것과
     * 세션을 받을 수 있다는 것은 다르다.
     *
     * @param http 필터 체인 빌더
     * @return 테스트 지원 경로만 잡는 체인
     * @throws Exception 필터 체인 구성에 실패할 때
     */
    @Bean
    @Order(1)
    public SecurityFilterChain testSupportFilterChain(HttpSecurity http) throws Exception {
        return http
                .securityMatcher(LOGIN_PATH)
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .build();
    }

    /**
     * 시드 위치를 Flyway 탐색 경로에 <b>더한다.</b> 기존 위치를 대체하지 않는다 — 대체하면
     * 스키마 마이그레이션이 통째로 사라진다.
     *
     * @return 탐색 위치를 확장하는 커스터마이저
     */
    @Bean
    public FlywayConfigurationCustomizer testDataLocationsCustomizer() {
        return configuration -> configuration.locations(
                Stream.concat(Arrays.stream(configuration.getLocations()).map(Location::getDescriptor),
                                Stream.of(TEST_DATA_LOCATION))
                        .toArray(String[]::new));
    }
}

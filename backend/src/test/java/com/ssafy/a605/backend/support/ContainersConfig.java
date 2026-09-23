package com.ssafy.a605.backend.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 컨테이너 테스트가 쓰는 PostgreSQL·Redis 컨테이너를 정의한다.
 *
 * <p>`@ServiceConnection`이 컨테이너의 호스트·포트를 Spring 설정으로 주입하므로
 * `application.yml`의 접속 정보를 테스트에서 따로 덮어쓰지 않는다.
 * 이 설정을 쓰는 테스트에는 `@Tag("container")`를 붙인다 (conventions/code.md §9).
 */
@TestConfiguration(proxyBeanMethods = false)
public class ContainersConfig {

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
        return new PostgreSQLContainer("postgres:17-alpine");
    }

    @Bean
    @ServiceConnection(name = "redis")
    GenericContainer<?> redis() {
        return new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
    }
}

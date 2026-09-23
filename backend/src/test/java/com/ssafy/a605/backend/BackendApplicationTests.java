package com.ssafy.a605.backend;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import com.ssafy.a605.backend.support.ContainersConfig;

@Tag("container")
@SpringBootTest
@TestPropertySource(locations = "classpath:oauth2-test.properties")
@Import(ContainersConfig.class)
class BackendApplicationTests {

    @Test
    @DisplayName("애플리케이션 컨텍스트가 PostgreSQL·Redis 컨테이너와 함께 기동한다")
    void contextLoads_withContainers_startsSuccessfully() {
    }
}

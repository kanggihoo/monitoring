package com.ssafy.a605.backend.domain.testsupport;

import static com.ssafy.a605.backend.domain.testsupport.config.TestSupportConfig.BROADCAST_PATH;
import static com.ssafy.a605.backend.domain.testsupport.config.TestSupportConfig.LOGIN_PATH;
import static com.ssafy.a605.backend.domain.testsupport.config.TestSupportConfig.SANDBOX_PATH;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.ssafy.a605.backend.domain.user.entity.Role;
import com.ssafy.a605.backend.domain.user.entity.User;
import com.ssafy.a605.backend.domain.user.repository.UserRepository;
import com.ssafy.a605.backend.global.security.CookieOrHeaderHttpSessionIdResolver;
import com.ssafy.a605.backend.support.ContainersConfig;
import com.ssafy.a605.backend.support.SessionSupport;

/**
 * 기능이 <b>꺼진</b> 컨텍스트.
 *
 * <p>이 클래스가 지키는 것은 하나다 — 설정을 빠뜨린 배포에서 IdP 우회 경로가 조용히 열리지 않는 것.
 *
 * <p>플래그를 <b>명시적으로 끈다.</b> 예전에는 아무것도 지정하지 않아 "미설정도 꺼짐"까지 함께
 * 검증하려 했는데, {@code application.yml} 이 {@code .env} 를 import 하므로 기능을 켜 둔 로컬에서는
 * 그 전제 자체가 성립하지 않았다 — 개발자가 켰다면 그것은 미설정이 아니다. 버전 관리되지 않는
 * 파일이 결과를 정하면 이 테스트는 늘 빨갛고, 그러면 아무도 읽지 않는다. 인증 우회 경로를 지키는
 * 테스트라 그것이 가장 나쁘다.
 *
 * <p>미설정 시 꺼진다는 보장은 {@code application.yml} 의 {@code ${TEST_SUPPORT_ENABLED:false}}
 * 기본값이 갖는다 (ADR-0003).
 */
@Tag("container")
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(locations = "classpath:oauth2-test.properties",
        properties = "app.test-support.enabled=false")
@Import({ContainersConfig.class, SessionSupport.class})
class TestSupportDisabledApiTest {

    private static final String AUTH_TOKEN_HEADER = CookieOrHeaderHttpSessionIdResolver.HEADER_NAME;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private SessionSupport sessions;

    @Test
    @DisplayName("샌드박스 진입_기능이 꺼진 상태의 관리자 호출_404 를 받는다")
    void enterSandbox_whenDisabled_returnsNotFound() throws Exception {
        User admin = new User(null, "관리자");
        admin.changeRole(Role.ADMIN);
        String sessionId = sessions.createSessionFor(userRepository.save(admin));

        mockMvc.perform(post(SANDBOX_PATH).header(AUTH_TOKEN_HEADER, sessionId))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("발행 트리거_기능이 꺼진 상태의 인증된 호출_404 를 받는다")
    void broadcastSample_whenDisabled_returnsNotFound() throws Exception {
        String sessionId = sessions.createSessionFor(userRepository.save(new User(null, "사용자")));

        mockMvc.perform(post(BROADCAST_PATH).header(AUTH_TOKEN_HEADER, sessionId))
                .andExpect(status().isNotFound());
    }

    /**
     * 404 가 아니다. 빈이 없어 핸들러가 없지만 시큐리티 검사가 핸들러 매핑보다 먼저 걸린다.
     *
     * <p>막히는 지점은 위조 방지 토큰 검사다 — 전용 필터 체인이 없으니 이 요청은 기존 체인으로
     * 가는데, 거기서는 그 검사가 인가 검사보다 <b>먼저</b> 실행되고 세션도 토큰도 없는 POST 라
     * 그 자리에서 끊긴다. 상태 코드는 401 이다: 아직 누구인지 모르는 요청에 "권한이 없다"고
     * 답하지 않는다 ({@code SecurityErrorHandler}). 노출 위험은 어느 쪽이든 없다 — 세션이
     * 발급되지 않는다.
     */
    @Test
    @DisplayName("테스트 로그인_기능이 꺼진 상태_핸들러에 닿기 전에 거부된다")
    void login_whenDisabled_isRejectedBeforeReachingHandler() throws Exception {
        mockMvc.perform(post(LOGIN_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"k6-0001\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("시드_기능이 꺼진 상태_부하 테스트용 Test User 가 심어지지 않는다")
    void seed_whenDisabled_isNotApplied() {
        Long seeded = jdbcTemplate.queryForObject("SELECT count(*) FROM test_users", Long.class);

        assertThat(seeded).isZero();
    }
}

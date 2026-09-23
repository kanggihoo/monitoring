package com.ssafy.a605.backend.domain.replay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

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
import com.ssafy.a605.backend.domain.user.service.UserService;
import com.ssafy.a605.backend.global.security.CookieOrHeaderHttpSessionIdResolver;
import com.ssafy.a605.backend.support.ContainersConfig;
import com.ssafy.a605.backend.support.SessionSupport;

/**
 * 재생 기능이 <b>꺼진</b> 컨텍스트. 프로덕션 배포에서 게임 시간을 바꾸는 경로가 조용히 열리지
 * 않는 것과, 검증용 데이터가 실려 들어가지 않는 것을 지킨다.
 *
 * <p>{@code application.yml} 의 {@code ${REPLAY_MODE:disabled}} 기본값이 미설정 시의 꺼짐을
 * 보장하고, 이 테스트는 명시적으로 끈 상태를 본다 ({@code TestSupportDisabledApiTest} 와 같은 이유).
 */
@Tag("container")
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(locations = "classpath:oauth2-test.properties",
        properties = "app.replay.mode=disabled")
@Import({ContainersConfig.class, SessionSupport.class})
class ReplayDisabledApiTest {

    private static final String REPLAY_URI = "/api/v1/admin/replay";
    private static final String CLOCK_URI = "/api/v1/game/clock";
    private static final String AUTH_TOKEN_HEADER = CookieOrHeaderHttpSessionIdResolver.HEADER_NAME;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserService userService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private SessionSupport sessions;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("재생 조회_기능이 꺼진 환경_관리자에게도 경로가 없다")
    void get_whenDisabled_isNotExposedEvenToAdmin() throws Exception {
        mockMvc.perform(get(REPLAY_URI).header(AUTH_TOKEN_HEADER, adminSession()))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("배속 변경_기능이 꺼진 환경_관리자에게도 경로가 없다")
    void change_whenDisabled_isNotExposedEvenToAdmin() throws Exception {
        mockMvc.perform(patch(REPLAY_URI).header(AUTH_TOKEN_HEADER, adminSession())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"speed\":10}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("이동 지점 목록_기능이 꺼진 환경_관리자에게도 경로가 없다")
    void marks_whenDisabled_isNotExposedEvenToAdmin() throws Exception {
        mockMvc.perform(get(REPLAY_URI + "/marks").header(AUTH_TOKEN_HEADER, adminSession()))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("지점 이동_기능이 꺼진 환경_관리자에게도 경로가 없다")
    void seek_whenDisabled_isNotExposedEvenToAdmin() throws Exception {
        mockMvc.perform(post(REPLAY_URI + "/seek").header(AUTH_TOKEN_HEADER, adminSession())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"tickSeq\":1}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("시계 조회_기능이 꺼진 환경_경로가 없다")
    void clock_whenDisabled_isNotExposed() throws Exception {
        mockMvc.perform(get(CLOCK_URI).header(AUTH_TOKEN_HEADER, adminSession()))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("검증용 재생 데이터_기능이 꺼진 환경_적재되지 않는다")
    void fixture_whenDisabled_isNotLoaded() {
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM replay_state", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM game_ticks", Integer.class)).isZero();
    }

    private String adminSession() {
        User user = userService.findOrCreate("google", UUID.randomUUID().toString(),
                UUID.randomUUID() + "@example.com");
        userService.changeRole(user.getId(), Role.ADMIN);
        return sessions.createSessionFor(userRepository.findById(user.getId()).orElseThrow());
    }
}

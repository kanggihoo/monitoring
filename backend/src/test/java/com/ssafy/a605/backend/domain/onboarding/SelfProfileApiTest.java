package com.ssafy.a605.backend.domain.onboarding;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

import com.ssafy.a605.backend.domain.user.entity.User;
import com.ssafy.a605.backend.global.security.CookieOrHeaderHttpSessionIdResolver;
import com.ssafy.a605.backend.support.ContainersConfig;
import com.ssafy.a605.backend.support.MutableClock;
import com.ssafy.a605.backend.support.ReplayFixture;
import com.ssafy.a605.backend.support.SessionSupport;

/**
 * 온보딩 설문 성향 (AI 업무요청서 2026-09-18 요청 ④).
 *
 * <p>보는 것은 <b>계약의 네 지점</b>이다: 미응답이 404 가 아니라 빈 값인가, 저장이 덮어쓰기인가,
 * 형식이 어긋난 답을 400 으로 막는가, 그리고 남의 답에 닿을 수 없는가.
 *
 * <p>채점은 보지 않는다 — 합계와 단계는 화면이 내므로 서버 계약에 없다.
 */
@Tag("container")
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(locations = "classpath:oauth2-test.properties",
        properties = {
                "app.replay.mode=test",
                // 픽스처를 여기서 켠다. 선언하지 않으면 .env 의 REPLAY_FIXTURE_ENABLED 가
                // 그대로 들어와 시즌·계좌 전제가 환경마다 갈린다.
                "app.replay.fixture.enabled=true",
                "app.replay.season-id=1"
        })
@Import({ContainersConfig.class, SessionSupport.class, ReplayFixture.class, MutableClock.Config.class})
class SelfProfileApiTest {

    private static final String URI = "/api/v1/onboarding/self-profile";
    private static final String AUTH_TOKEN_HEADER = CookieOrHeaderHttpSessionIdResolver.HEADER_NAME;

    private static final long ACCOUNT_ID = 971L;
    private static final long OTHER_ACCOUNT_ID = 972L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SessionSupport sessions;

    @Autowired
    private ReplayFixture fixture;

    @Autowired
    private MutableClock clock;

    @Autowired
    private JdbcTemplate jdbc;

    private String ownerSession;

    @BeforeEach
    void setUp() {
        fixture.reset(clock);
        User owner = fixture.user("설문주인");
        ownerSession = sessions.createSessionFor(owner);
        givenAccount(ACCOUNT_ID, owner.getId());
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM accounts WHERE id IN (?, ?)", ACCOUNT_ID, OTHER_ACCOUNT_ID);
    }

    @Test
    @DisplayName("설문 조회_아직 답하지 않았으면_404 가 아니라 빈 값이 온다")
    void notAnswered_returnsEmptyRatherThan404() throws Exception {
        mockMvc.perform(get())
                .andExpect(MockMvcResultMatchers.status().isOk())
                // 설문은 선택이다. 없음을 404 로 알리면 화면이 오류 경로를 타야 한다.
                .andExpect(MockMvcResultMatchers.jsonPath("$.data.answers").doesNotExist())
                .andExpect(MockMvcResultMatchers.jsonPath("$.data.updatedAt").doesNotExist());
    }

    @Test
    @DisplayName("설문 저장_형식이 맞으면_그 답과 저장 시각이 온다")
    void validAnswers_areStored() throws Exception {
        mockMvc.perform(put("24312"))
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andExpect(MockMvcResultMatchers.jsonPath("$.data.answers").value("24312"))
                .andExpect(MockMvcResultMatchers.jsonPath("$.data.updatedAt").exists());

        mockMvc.perform(get())
                .andExpect(MockMvcResultMatchers.jsonPath("$.data.answers").value("24312"));
    }

    @Test
    @DisplayName("설문 저장_이미 답한 뒤에 다시 보내면_덮어쓴다")
    void secondAnswer_overwritesTheFirst() throws Exception {
        mockMvc.perform(put("11111")).andExpect(MockMvcResultMatchers.status().isOk());
        // 언제든 고칠 수 있다는 계약이다 (기획 확정). 최초 저장과 수정을 가르지 않는다.
        mockMvc.perform(put("44444")).andExpect(MockMvcResultMatchers.status().isOk());

        mockMvc.perform(get())
                .andExpect(MockMvcResultMatchers.jsonPath("$.data.answers").value("44444"));
    }

    @Test
    @DisplayName("설문 저장_같은 답을 두 번 보내도_결과가 같다")
    void sameAnswerTwice_isIdempotent() throws Exception {
        mockMvc.perform(put("23232")).andExpect(MockMvcResultMatchers.status().isOk());
        mockMvc.perform(put("23232"))
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andExpect(MockMvcResultMatchers.jsonPath("$.data.answers").value("23232"));
    }

    @Test
    @DisplayName("설문 저장_자리 수나 범위가 어긋나면_400 이고 아무것도 저장되지 않는다")
    void malformedAnswers_areRejected() throws Exception {
        // 네 자리, 여섯 자리, 범위 밖(5·0), 숫자가 아닌 값, 빈 값.
        for (String bad : new String[] {"2431", "243122", "24315", "24310", "2431a", ""}) {
            mockMvc.perform(put(bad))
                    .andExpect(MockMvcResultMatchers.status().isBadRequest());
        }

        mockMvc.perform(get())
                .andExpect(MockMvcResultMatchers.jsonPath("$.data.answers").doesNotExist());
    }

    @Test
    @DisplayName("설문 조회_세션이 없으면_401 이다")
    void withoutSession_isUnauthorized() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get(URI))
                .andExpect(MockMvcResultMatchers.status().isUnauthorized());
    }

    @Test
    @DisplayName("설문 조회_남의 답은_어떤 파라미터로도 닿지 않는다")
    void otherAccountAnswers_areUnreachable() throws Exception {
        User other = fixture.user("설문남");
        givenAccount(OTHER_ACCOUNT_ID, other.getId());
        jdbc.update("UPDATE accounts SET self_answers = '44444', self_answers_at = now() WHERE id = ?",
                OTHER_ACCOUNT_ID);

        // 계좌를 지정할 자리가 없다. 세션에서 유도하므로 남의 것을 고를 수단 자체가 없다.
        mockMvc.perform(MockMvcRequestBuilders.get(URI)
                        .header(AUTH_TOKEN_HEADER, ownerSession)
                        .param("accountId", String.valueOf(OTHER_ACCOUNT_ID)))
                .andExpect(MockMvcResultMatchers.jsonPath("$.data.answers").doesNotExist());
    }

    private RequestBuilder get() {
        return MockMvcRequestBuilders.get(URI).header(AUTH_TOKEN_HEADER, ownerSession);
    }

    private RequestBuilder put(String answers) {
        return MockMvcRequestBuilders.put(URI)
                .header(AUTH_TOKEN_HEADER, ownerSession)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"answers\":\"" + answers + "\"}");
    }

    private void givenAccount(long accountId, long userId) {
        jdbc.update("""
                INSERT INTO accounts (id, user_id, season_id, krw_cash, usd_cash, locked_krw, locked_usd,
                                      starting_balance_krw, index_adjusted, joined_season_day,
                                      daily_base_valuation_krw, daily_base_season_day,
                                      last_acked_tick_seq, created_at)
                VALUES (?, ?, ?, 45000000, 0, 0, 0, 100000000, false, 1, 100000000, 1, 0, now())
                """, accountId, userId, ReplayFixture.SEASON_ID);
    }
}

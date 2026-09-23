package com.ssafy.a605.backend.domain.admin;

import static com.ssafy.a605.backend.support.SessionSupport.CSRF_COOKIE_NAME;
import static com.ssafy.a605.backend.support.SessionSupport.CSRF_HEADER_NAME;
import static com.ssafy.a605.backend.support.SessionSupport.csrfToken;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jayway.jsonpath.JsonPath;

import com.ssafy.a605.backend.domain.testsupport.entity.TestUser;
import com.ssafy.a605.backend.domain.testsupport.repository.TestUserRepository;
import com.ssafy.a605.backend.domain.user.entity.Role;
import com.ssafy.a605.backend.domain.user.entity.User;
import com.ssafy.a605.backend.domain.user.repository.UserRepository;
import com.ssafy.a605.backend.domain.user.service.UserService;
import com.ssafy.a605.backend.global.security.CookieOrHeaderHttpSessionIdResolver;
import com.ssafy.a605.backend.support.ContainersConfig;
import com.ssafy.a605.backend.support.SessionSupport;

import jakarta.servlet.http.Cookie;

/**
 * HTTP 경계에서 관리자 콘솔의 관찰 가능한 결과만 검증한다 — 어떤 쿼리가 나갔는지나 감사 로그가
 * 어떤 컬럼에 담겼는지는 보지 않는다.
 *
 * <p>테스트 지원 기능은 <b>꺼진</b> 상태다. Test User 제외는 {@code test_users} 행의 존재만
 * 보는 조건이라 플래그와 무관하게 동작해야 한다 (ADR-0003).
 *
 * <p>한 클래스가 DB 를 공유하므로 검색 대상은 매번 고유한 문자열로 만든다.
 */
@Tag("container")
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(locations = "classpath:oauth2-test.properties")
@Import({ContainersConfig.class, SessionSupport.class})
class AdminConsoleApiTest {

    private static final String USERS_URI = "/api/v1/admin/users";
    private static final String AUDIT_LOGS_URI = "/api/v1/admin/audit-logs";
    private static final String NOTICES_URI = "/api/v1/admin/notices";
    private static final String ME_URI = "/api/v1/users/me";
    private static final String AUTH_TOKEN_HEADER = CookieOrHeaderHttpSessionIdResolver.HEADER_NAME;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserService userService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TestUserRepository testUserRepository;

    @Autowired
    private SessionSupport sessions;

    /**
     * 이 클래스가 심은 Test User 표식을 걷어낸다.
     *
     * <p>애노테이션 구성이 같은 테스트끼리는 Spring 이 컨텍스트를 공유하고, 그러면 컨테이너도
     * 같은 것을 쓴다. {@code TestSupportDisabledApiTest} 는 "시드가 돌지 않았다"를 빈
     * {@code test_users} 로 확인하므로, 여기서 남긴 행이 그 단언을 깬다.
     *
     * <p>User 를 지우면 표식은 {@code ON DELETE CASCADE} 로 따라 사라진다.
     */
    @AfterEach
    void removeTestUsers() {
        userRepository.deleteAllById(
                testUserRepository.findAll().stream().map(TestUser::getUserId).toList());
    }

    @Nested
    @DisplayName("경로 보호")
    class RouteProtection {

        @Test
        @DisplayName("사용자 검색_일반 사용자의 세션_403 을 받는다")
        void searchUsers_asNonAdmin_returnsForbidden() throws Exception {
            mockMvc.perform(get(USERS_URI).header(AUTH_TOKEN_HEADER, sessionOf(user("홍길동"))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("사용자 검색_세션이 없으면_401 을 받는다")
        void searchUsers_withoutSession_returnsUnauthorized() throws Exception {
            mockMvc.perform(get(USERS_URI))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("감사 로그 조회_일반 사용자의 세션_403 을 받는다")
        void searchAuditLogs_asNonAdmin_returnsForbidden() throws Exception {
            mockMvc.perform(get(AUDIT_LOGS_URI).header(AUTH_TOKEN_HEADER, sessionOf(user("홍길동"))))
                    .andExpect(status().isForbidden());
        }
    }

    @Nested
    @DisplayName("사용자 검색")
    class SearchUsers {

        @Test
        @DisplayName("사용자 검색_닉네임 일부로 찾으면_그 사용자가 결과에 있다")
        void searchUsers_byNicknameFragment_findsUser() throws Exception {
            String fragment = unique();
            User target = user("길동" + fragment);

            mockMvc.perform(search(fragment))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content.length()").value(1))
                    .andExpect(jsonPath("$.data.content[0].id").value(target.getId()))
                    .andExpect(jsonPath("$.data.content[0].nickname").value(target.getNickname()))
                    .andExpect(jsonPath("$.data.content[0].role").value("USER"))
                    .andExpect(jsonPath("$.data.content[0].createdAt").isNotEmpty())
                    .andExpect(jsonPath("$.data.content[0].providers[0]").value("google"));
        }

        @Test
        @DisplayName("사용자 검색_이메일 일부로 찾으면_그 사용자가 결과에 있다")
        void searchUsers_byEmailFragment_findsUser() throws Exception {
            String fragment = unique();
            User target = googleUser("홍길동", fragment + "@example.com");

            mockMvc.perform(search(fragment))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content.length()").value(1))
                    .andExpect(jsonPath("$.data.content[0].id").value(target.getId()));
        }

        @Test
        @DisplayName("사용자 검색_대소문자가 달라도_부분일치로 찾는다")
        void searchUsers_ignoringCase_findsUser() throws Exception {
            String fragment = unique();
            User target = googleUser("Hong" + fragment.toUpperCase(), null);

            mockMvc.perform(search(fragment.toLowerCase()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content[0].id").value(target.getId()));
        }

        @Test
        @DisplayName("사용자 검색_이메일이 없는 사용자_닉네임 검색 결과에 포함된다")
        void searchUsers_whenEmailIsNull_stillMatchesByNickname() throws Exception {
            String fragment = unique();
            User target = emailLessUser("이메일없음" + fragment);

            mockMvc.perform(search(fragment))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content.length()").value(1))
                    .andExpect(jsonPath("$.data.content[0].id").value(target.getId()))
                    .andExpect(jsonPath("$.data.content[0].email").isEmpty())
                    .andExpect(jsonPath("$.data.content[0].providers[0]").value("ssafy"));
        }

        @Test
        @DisplayName("사용자 검색_같은 조건의 사용자가 여럿이면_가입일 최신순으로 나온다")
        void searchUsers_withSeveralMatches_ordersByCreatedAtDesc() throws Exception {
            String fragment = unique();
            User older = user("먼저" + fragment);
            User newer = user("나중" + fragment);

            mockMvc.perform(search(fragment))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content[0].id").value(newer.getId()))
                    .andExpect(jsonPath("$.data.content[1].id").value(older.getId()));
        }

        @Test
        @DisplayName("사용자 검색_결과가 페이지 크기를 넘으면_페이지 정보가 정확하다")
        void searchUsers_withMoreThanOnePage_returnsPageMetadata() throws Exception {
            String fragment = unique();
            user("가" + fragment);
            user("나" + fragment);
            user("다" + fragment);

            mockMvc.perform(get(USERS_URI)
                            .param("keyword", fragment)
                            .param("page", "0")
                            .param("size", "2")
                            .header(AUTH_TOKEN_HEADER, adminSession()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content.length()").value(2))
                    .andExpect(jsonPath("$.data.page").value(0))
                    .andExpect(jsonPath("$.data.size").value(2))
                    .andExpect(jsonPath("$.data.totalElements").value(3))
                    .andExpect(jsonPath("$.data.totalPages").value(2))
                    .andExpect(jsonPath("$.data.hasNext").value(true));
        }

        @Test
        @DisplayName("사용자 검색_Test User_결과에 나오지 않는다")
        void searchUsers_withTestUser_excludesIt() throws Exception {
            String fragment = unique();
            User visible = user("보이는" + fragment);
            testUser("숨은" + fragment);

            mockMvc.perform(search(fragment))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content.length()").value(1))
                    .andExpect(jsonPath("$.data.content[0].nickname").value(visible.getNickname()));
        }

        @Test
        @DisplayName("사용자 검색_검색어가 없으면_최근 가입자부터 나온다")
        void searchUsers_withoutKeyword_returnsRecentUsersFirst() throws Exception {
            // 관리자를 먼저 만든다. 나중에 만들면 그쪽이 가장 최근 가입자가 된다.
            String adminSession = adminSession();
            User newest = user("가장최근" + unique());

            mockMvc.perform(get(USERS_URI).header(AUTH_TOKEN_HEADER, adminSession))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content[0].id").value(newest.getId()));
        }
    }

    @Nested
    @DisplayName("사용자 상세")
    class UserDetail {

        @Test
        @DisplayName("사용자 상세_존재하는 사용자_로그인 방법과 가입일과 활성 세션 수를 받는다")
        void getUser_withExistingUser_returnsProvidersAndCreatedAtAndSessionCount() throws Exception {
            User target = user("홍길동" + unique());
            sessions.createSessionFor(target);

            mockMvc.perform(get(USERS_URI + "/" + target.getId()).header(AUTH_TOKEN_HEADER, adminSession()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(target.getId()))
                    .andExpect(jsonPath("$.data.nickname").value(target.getNickname()))
                    .andExpect(jsonPath("$.data.role").value("USER"))
                    .andExpect(jsonPath("$.data.providers[0]").value("google"))
                    .andExpect(jsonPath("$.data.createdAt").isNotEmpty())
                    .andExpect(jsonPath("$.data.activeSessionCount").value(1));
        }

        @Test
        @DisplayName("사용자 상세_세션을 하나 더 만들면_활성 세션 수가 늘어난다")
        void getUser_afterAnotherLogin_showsIncreasedSessionCount() throws Exception {
            User target = user("홍길동" + unique());
            sessions.createSessionFor(target);
            sessions.createSessionFor(target);

            mockMvc.perform(get(USERS_URI + "/" + target.getId()).header(AUTH_TOKEN_HEADER, adminSession()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.activeSessionCount").value(2));
        }

        @Test
        @DisplayName("사용자 상세_Test User_404 를 받는다")
        void getUser_withTestUser_returnsNotFound() throws Exception {
            User target = testUser("숨은" + unique());

            mockMvc.perform(get(USERS_URI + "/" + target.getId()).header(AUTH_TOKEN_HEADER, adminSession()))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("사용자 상세_없는 식별자_404 를 받는다")
        void getUser_withUnknownId_returnsNotFound() throws Exception {
            mockMvc.perform(get(USERS_URI + "/99999999").header(AUTH_TOKEN_HEADER, adminSession()))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("권한 변경")
    class ChangeRole {

        @Test
        @DisplayName("권한 변경_대상을 관리자로 승격하면_상세 조회에 반영된다")
        void changeRole_toAdmin_isReflectedInDetail() throws Exception {
            User target = user("홍길동" + unique());

            mockMvc.perform(patchRole(adminSession(), target.getId(), "ADMIN"))
                    .andExpect(status().isNoContent());

            mockMvc.perform(get(USERS_URI + "/" + target.getId()).header(AUTH_TOKEN_HEADER, adminSession()))
                    .andExpect(jsonPath("$.data.role").value("ADMIN"));
        }

        @Test
        @DisplayName("권한 변경_대상의 기존 세션_더 이상 접근되지 않는다")
        void changeRole_withLiveTargetSession_makesItUnusable() throws Exception {
            User target = user("홍길동" + unique());
            String targetSession = sessions.createSessionFor(target);

            mockMvc.perform(patchRole(adminSession(), target.getId(), "ADMIN"))
                    .andExpect(status().isNoContent());

            mockMvc.perform(get(ME_URI).header(AUTH_TOKEN_HEADER, targetSession))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("권한 변경_자기 자신을 강등하면_409 를 받고 권한이 그대로다")
        void changeRole_demotingSelf_returnsConflictAndKeepsRole() throws Exception {
            User admin = admin("관리자" + unique());

            mockMvc.perform(patchRole(sessionOf(admin), admin.getId(), "USER"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.success").value(false));

            assertThat(userRepository.findById(admin.getId()).orElseThrow().getRole()).isEqualTo(Role.ADMIN);
        }

        @Test
        @DisplayName("권한 변경_다른 관리자를 강등하면_허용된다")
        void changeRole_demotingAnotherAdmin_isAllowed() throws Exception {
            User target = admin("다른관리자" + unique());

            mockMvc.perform(patchRole(adminSession(), target.getId(), "USER"))
                    .andExpect(status().isNoContent());

            assertThat(userRepository.findById(target.getId()).orElseThrow().getRole()).isEqualTo(Role.USER);
        }

        @Test
        @DisplayName("권한 변경_쿠키 인증에 위조 방지 토큰이 없으면_거부된다")
        void changeRole_withoutCsrfToken_returnsForbidden() throws Exception {
            User admin = admin("관리자" + unique());
            User target = user("홍길동" + unique());
            Cookie session = sessions.sessionCookie(sessions.createSessionFor(admin));

            mockMvc.perform(patch(USERS_URI + "/" + target.getId() + "/role").cookie(session)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"role\":\"ADMIN\"}"))
                    .andExpect(status().isForbidden());

            assertThat(userRepository.findById(target.getId()).orElseThrow().getRole()).isEqualTo(Role.USER);
        }

        @Test
        @DisplayName("권한 변경_쿠키 인증에 위조 방지 토큰이 있으면_통과한다")
        void changeRole_withCsrfToken_returnsNoContent() throws Exception {
            User admin = admin("관리자" + unique());
            User target = user("홍길동" + unique());
            Cookie session = sessions.sessionCookie(sessions.createSessionFor(admin));
            String token = csrfToken(mockMvc.perform(get(USERS_URI).cookie(session)).andReturn());

            mockMvc.perform(patch(USERS_URI + "/" + target.getId() + "/role")
                            .cookie(session, new Cookie(CSRF_COOKIE_NAME, token))
                            .header(CSRF_HEADER_NAME, token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"role\":\"ADMIN\"}"))
                    .andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("권한 변경_권한 값이 없으면_400 을 받는다")
        void changeRole_withoutRole_returnsBadRequest() throws Exception {
            User target = user("홍길동" + unique());

            mockMvc.perform(patch(USERS_URI + "/" + target.getId() + "/role")
                            .header(AUTH_TOKEN_HEADER, adminSession())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("세션 강제 종료")
    class RevokeSessions {

        @Test
        @DisplayName("세션 강제 종료_대상의 기존 세션으로 접근하면_401 을 받는다")
        void revokeSessions_withLiveTargetSession_makesItUnusable() throws Exception {
            User target = user("홍길동" + unique());
            String targetSession = sessions.createSessionFor(target);

            mockMvc.perform(delete(USERS_URI + "/" + target.getId() + "/sessions")
                            .header(AUTH_TOKEN_HEADER, adminSession()))
                    .andExpect(status().isNoContent());

            mockMvc.perform(get(ME_URI).header(AUTH_TOKEN_HEADER, targetSession))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("세션 강제 종료_자기 자신을 대상으로 해도_허용된다")
        void revokeSessions_targetingSelf_isAllowed() throws Exception {
            User admin = admin("관리자" + unique());
            String adminSession = sessions.createSessionFor(admin);

            mockMvc.perform(delete(USERS_URI + "/" + admin.getId() + "/sessions")
                            .header(AUTH_TOKEN_HEADER, adminSession))
                    .andExpect(status().isNoContent());

            assertThat(sessions.hasStoredSession(admin)).isFalse();
        }
    }

    @Nested
    @DisplayName("감사 로그")
    class AuditLogs {

        @Test
        @DisplayName("감사 로그 조회_권한 변경 직후_그 기록이 최신으로 남는다")
        void searchAuditLogs_afterRoleChange_hasNewestRecord() throws Exception {
            User admin = admin("관리자" + unique());
            User target = user("홍길동" + unique());
            mockMvc.perform(patchRole(sessionOf(admin), target.getId(), "ADMIN"))
                    .andExpect(status().isNoContent());

            mockMvc.perform(get(AUDIT_LOGS_URI).header(AUTH_TOKEN_HEADER, adminSession()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content[0].action").value("USER_ROLE_UPDATE"))
                    .andExpect(jsonPath("$.data.content[0].actorUserId").value(admin.getId()))
                    .andExpect(jsonPath("$.data.content[0].targetUserId").value(target.getId()))
                    .andExpect(jsonPath("$.data.content[0].detail").value("USER -> ADMIN"))
                    .andExpect(jsonPath("$.data.content[0].createdAt").isNotEmpty());
        }

        @Test
        @DisplayName("감사 로그 조회_세션 강제 종료 직후_그 기록이 최신으로 남는다")
        void searchAuditLogs_afterSessionRevoke_hasNewestRecord() throws Exception {
            User admin = admin("관리자" + unique());
            User target = user("홍길동" + unique());
            mockMvc.perform(delete(USERS_URI + "/" + target.getId() + "/sessions")
                            .header(AUTH_TOKEN_HEADER, sessionOf(admin)))
                    .andExpect(status().isNoContent());

            mockMvc.perform(get(AUDIT_LOGS_URI).header(AUTH_TOKEN_HEADER, adminSession()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content[0].action").value("SESSION_REVOKE"))
                    .andExpect(jsonPath("$.data.content[0].actorUserId").value(admin.getId()))
                    .andExpect(jsonPath("$.data.content[0].targetUserId").value(target.getId()));
        }

        @Test
        @DisplayName("감사 로그 조회_두 행위를 연달아 하면_나중 것이 앞에 온다")
        void searchAuditLogs_afterTwoActions_ordersNewestFirst() throws Exception {
            User admin = admin("관리자" + unique());
            User target = user("홍길동" + unique());
            mockMvc.perform(patchRole(sessionOf(admin), target.getId(), "ADMIN"))
                    .andExpect(status().isNoContent());
            mockMvc.perform(delete(USERS_URI + "/" + target.getId() + "/sessions")
                            .header(AUTH_TOKEN_HEADER, sessionOf(admin)))
                    .andExpect(status().isNoContent());

            mockMvc.perform(get(AUDIT_LOGS_URI).header(AUTH_TOKEN_HEADER, adminSession()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content[0].action").value("SESSION_REVOKE"))
                    .andExpect(jsonPath("$.data.content[1].action").value("USER_ROLE_UPDATE"));
        }

        @Test
        @DisplayName("감사 로그 조회_행위 종류로 거르면_그 종류만 나온다")
        void searchAuditLogs_filteredByAction_returnsOnlyThatAction() throws Exception {
            User admin = admin("관리자" + unique());
            User target = user("홍길동" + unique());
            mockMvc.perform(patchRole(sessionOf(admin), target.getId(), "ADMIN"))
                    .andExpect(status().isNoContent());
            mockMvc.perform(delete(USERS_URI + "/" + target.getId() + "/sessions")
                            .header(AUTH_TOKEN_HEADER, sessionOf(admin)))
                    .andExpect(status().isNoContent());

            String body = mockMvc.perform(get(AUDIT_LOGS_URI)
                            .param("action", "SESSION_REVOKE")
                            .param("size", "100")
                            .header(AUTH_TOKEN_HEADER, adminSession()))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            List<String> actions = JsonPath.read(body, "$.data.content[*].action");
            assertThat(actions).isNotEmpty().containsOnly("SESSION_REVOKE");
        }

        @Test
        @DisplayName("감사 로그 조회_없는 행위 종류로 거르면_400 을 받는다")
        void searchAuditLogs_withUnknownAction_returnsBadRequest() throws Exception {
            mockMvc.perform(get(AUDIT_LOGS_URI)
                            .param("action", "SANDBOX_ENTER")
                            .header(AUTH_TOKEN_HEADER, adminSession()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.errors[0].field").value("action"))
                    .andExpect(jsonPath("$.errors[0].rejectedValue").value("SANDBOX_ENTER"))
                    .andExpect(jsonPath("$.errors[0].reason").isNotEmpty());
        }

        @Test
        @DisplayName("감사 로그 조회_대상 사용자가 탈퇴한 뒤에도_그 기록이 남는다")
        void searchAuditLogs_afterTargetWithdrawal_keepsRecord() throws Exception {
            User admin = admin("관리자" + unique());
            User target = user("홍길동" + unique());
            mockMvc.perform(delete(USERS_URI + "/" + target.getId() + "/sessions")
                            .header(AUTH_TOKEN_HEADER, sessionOf(admin)))
                    .andExpect(status().isNoContent());

            userService.delete(target.getId());
            assertThat(userRepository.findById(target.getId())).isEmpty();

            String body = mockMvc.perform(get(AUDIT_LOGS_URI)
                            .param("action", "SESSION_REVOKE")
                            .param("size", "100")
                            .header(AUTH_TOKEN_HEADER, adminSession()))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            List<Integer> targets = JsonPath.read(body, "$.data.content[*].targetUserId");
            assertThat(targets).contains(target.getId().intValue());
        }
    }

    @Nested
    @DisplayName("전원 공지")
    class Notices {

        @Test
        @DisplayName("전원 공지_일반 사용자의 세션_403 을 받는다")
        void sendNotice_asNonAdmin_returnsForbidden() throws Exception {
            mockMvc.perform(postNotice(sessionOf(user("일반" + unique())), "점검 안내", "본문", false))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("전원 공지_관리자가 보내면_204 를 받는다")
        void sendNotice_asAdmin_returnsNoContent() throws Exception {
            mockMvc.perform(postNotice(adminSession(), "점검 안내", "잠시 뒤 재생이 멈춥니다.", true))
                    .andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("전원 공지_제목이 컬럼 길이를 넘으면_400 을 받는다")
        void sendNotice_withTooLongTitle_returnsBadRequest() throws Exception {
            // 화면에서만 막으면 API 를 직접 부른 요청이 DB 오류로 500 이 된다.
            mockMvc.perform(postNotice(adminSession(), "가".repeat(101), "본문", false))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.errors[0].field").value("title"));
        }

        @Test
        @DisplayName("전원 공지_본문이 공백뿐이면_400 을 받는다")
        void sendNotice_withBlankBody_returnsBadRequest() throws Exception {
            mockMvc.perform(postNotice(adminSession(), "제목", "   ", false))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value("body"));
        }
    }

    private MockHttpServletRequestBuilder postNotice(String sessionId, String title, String body, boolean urgent) {
        return post(NOTICES_URI)
                .header(AUTH_TOKEN_HEADER, sessionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"" + title + "\",\"body\":\"" + body + "\",\"urgent\":" + urgent + "}");
    }

    private MockHttpServletRequestBuilder search(String keyword) {
        return get(USERS_URI).param("keyword", keyword).header(AUTH_TOKEN_HEADER, adminSession());
    }

    private MockHttpServletRequestBuilder patchRole(String sessionId, Long targetId, String role) {
        return patch(USERS_URI + "/" + targetId + "/role")
                .header(AUTH_TOKEN_HEADER, sessionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"" + role + "\"}");
    }

    /** 검색어로 쓸 문자열. 한 클래스가 DB 를 공유하므로 테스트끼리 결과가 섞이지 않게 한다. */
    private static String unique() {
        return "x" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private String adminSession() {
        return sessionOf(admin("관리자" + unique()));
    }

    private String sessionOf(User user) {
        return sessions.createSessionFor(user);
    }

    private User user(String nickname) {
        return googleUser(nickname, unique() + "@example.com");
    }

    /** 가입은 닉네임을 자동 생성하므로 만든 뒤 엔티티로 덮는다 — 사용자 검색이 이 값을 본다. */
    private User googleUser(String nickname, String email) {
        return named(userService.findOrCreate("google", UUID.randomUUID().toString(), email),
                nickname);
    }

    /** 이메일 없이 만들어진 사용자. IdP 가 이메일을 주지 않는 경우를 대신한다. */
    private User emailLessUser(String nickname) {
        return named(userService.findOrCreate("ssafy", UUID.randomUUID().toString(), null),
                nickname);
    }

    /**
     * 표시 이름을 지정한다. 가입은 닉네임을 자동 생성하므로 만든 뒤 덮는다.
     *
     * <p><b>고유값을 뒤에 붙인다.</b> 닉네임에 유니크 제약이 있고(V24) 이 테스트들은 같은
     * 컨테이너를 공유하므로, 고정 이름을 그대로 쓰면 두 번째 사용자부터 저장이 깨진다.
     * 검색 조각은 호출부가 앞쪽에 넣으므로 부분일치는 그대로 걸린다.
     */
    private User named(User user, String nickname) {
        user.changeNickname(nickname + unique());
        return userRepository.save(user);
    }

    private User admin(String nickname) {
        User user = user(nickname);
        userService.changeRole(user.getId(), Role.ADMIN);
        return userRepository.findById(user.getId()).orElseThrow();
    }

    /** 신원이 없어 로그인 방법이 하나도 없는 User 에 Test User 표식을 붙인다. */
    private User testUser(String nickname) {
        User user = userRepository.save(new User(null, nickname));
        testUserRepository.save(new TestUser(user.getId(), unique(), null));
        return user;
    }
}

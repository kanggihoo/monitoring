package com.ssafy.a605.backend.domain.inquiry;

import static com.ssafy.a605.backend.support.SessionSupport.CSRF_COOKIE_NAME;
import static com.ssafy.a605.backend.support.SessionSupport.CSRF_HEADER_NAME;
import static com.ssafy.a605.backend.support.SessionSupport.csrfToken;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

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

import com.ssafy.a605.backend.domain.user.entity.Role;
import com.ssafy.a605.backend.domain.user.entity.User;
import com.ssafy.a605.backend.domain.user.repository.UserRepository;
import com.ssafy.a605.backend.domain.user.service.UserService;
import com.ssafy.a605.backend.global.security.CookieOrHeaderHttpSessionIdResolver;
import com.ssafy.a605.backend.support.ContainersConfig;
import com.ssafy.a605.backend.support.SessionSupport;

import jakarta.servlet.http.Cookie;

/**
 * HTTP 경계에서 문의의 관찰 가능한 결과만 검증한다 — 어떤 컬럼에 담겼는지나 상태 전이를 어느
 * 계층이 했는지는 보지 않는다.
 *
 * <p>한 클래스가 DB 를 공유하므로 사용자를 매번 새로 만들고, 전체 목록을 보는 검증은 고유한
 * 문자열로 대상을 좁힌다.
 */
@Tag("container")
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(locations = "classpath:oauth2-test.properties")
@Import({ContainersConfig.class, SessionSupport.class})
class InquiryApiTest {

    private static final String INQUIRIES_URI = "/api/v1/inquiries";
    private static final String ADMIN_INQUIRIES_URI = "/api/v1/admin/inquiries";
    private static final String ME_URI = "/api/v1/users/me";
    private static final String AUTH_TOKEN_HEADER = CookieOrHeaderHttpSessionIdResolver.HEADER_NAME;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserService userService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private SessionSupport sessions;

    @Nested
    @DisplayName("경로 보호")
    class RouteProtection {

        @Test
        @DisplayName("문의 목록_세션이 없으면_401 을 받는다")
        void searchInquiries_withoutSession_returnsUnauthorized() throws Exception {
            mockMvc.perform(get(INQUIRIES_URI))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("관리자 문의 목록_일반 사용자의 세션_403 을 받는다")
        void searchAdminInquiries_asNonAdmin_returnsForbidden() throws Exception {
            mockMvc.perform(get(ADMIN_INQUIRIES_URI).header(AUTH_TOKEN_HEADER, sessionOf(user("홍길동"))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("답변 등록_일반 사용자의 세션_403 을 받는다")
        void answer_asNonAdmin_returnsForbidden() throws Exception {
            String session = sessionOf(user("홍길동"));
            long inquiryId = createInquiry(session, "제목", "내용");

            mockMvc.perform(put(ADMIN_INQUIRIES_URI + "/" + inquiryId + "/answer")
                            .header(AUTH_TOKEN_HEADER, session)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"answer\":\"답변\"}"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("문의 작성_쿠키 인증에 위조 방지 토큰이 없으면_거부된다")
        void createInquiry_withoutCsrfToken_returnsForbidden() throws Exception {
            Cookie session = sessions.sessionCookie(sessionOf(user("홍길동")));

            mockMvc.perform(post(INQUIRIES_URI)
                            .cookie(session)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body("제목", "내용")))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("문의 작성_쿠키 인증에 위조 방지 토큰이 있으면_통과한다")
        void createInquiry_withCsrfToken_returnsCreated() throws Exception {
            Cookie session = sessions.sessionCookie(sessionOf(user("홍길동")));
            String token = csrfToken(mockMvc.perform(get(ME_URI).cookie(session)).andReturn());

            mockMvc.perform(post(INQUIRIES_URI)
                            .cookie(session, new Cookie(CSRF_COOKIE_NAME, token))
                            .header(CSRF_HEADER_NAME, token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body("제목", "내용")))
                    .andExpect(status().isCreated());
        }
    }

    @Nested
    @DisplayName("문의 작성")
    class Create {

        @Test
        @DisplayName("문의 작성_제목과 내용을 담아 보내면_답변 전 상태로 만들어진다")
        void createInquiry_withTitleAndContent_createsOpenInquiry() throws Exception {
            mockMvc.perform(create(sessionOf(user("홍길동")), "로그인이 안 됩니다", "버튼을 눌러도 반응이 없습니다"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.id").isNumber())
                    .andExpect(jsonPath("$.data.title").value("로그인이 안 됩니다"))
                    .andExpect(jsonPath("$.data.content").value("버튼을 눌러도 반응이 없습니다"))
                    .andExpect(jsonPath("$.data.status").value("OPEN"))
                    .andExpect(jsonPath("$.data.answer").doesNotExist())
                    .andExpect(jsonPath("$.data.createdAt").isNotEmpty());
        }

        @Test
        @DisplayName("문의 작성_제목이 길이 제한을 넘기면_400 이고 필드별 이유가 담긴다")
        void createInquiry_withTooLongTitle_returnsBadRequest() throws Exception {
            mockMvc.perform(create(sessionOf(user("홍길동")), "가".repeat(101), "내용"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.errors[0].field").value("title"))
                    .andExpect(jsonPath("$.errors[0].reason").isNotEmpty());
        }

        @Test
        @DisplayName("문의 작성_내용이 길이 제한을 넘기면_400 이고 필드별 이유가 담긴다")
        void createInquiry_withTooLongContent_returnsBadRequest() throws Exception {
            mockMvc.perform(create(sessionOf(user("홍길동")), "제목", "가".repeat(2001)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value("content"))
                    .andExpect(jsonPath("$.errors[0].reason").isNotEmpty());
        }

        @Test
        @DisplayName("문의 작성_제목이 비어 있으면_400 을 받는다")
        void createInquiry_withBlankTitle_returnsBadRequest() throws Exception {
            mockMvc.perform(create(sessionOf(user("홍길동")), "   ", "내용"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value("title"));
        }
    }

    @Nested
    @DisplayName("내 문의 조회")
    class ReadOwn {

        @Test
        @DisplayName("문의 목록_다른 사용자도 문의를 남겼을 때_내 문의만 나온다")
        void searchInquiries_withOtherUsersInquiries_returnsOnlyMine() throws Exception {
            String mine = sessionOf(user("홍길동"));
            String others = sessionOf(user("김철수"));
            createInquiry(mine, "내 문의", "내용");
            createInquiry(others, "남의 문의", "내용");

            mockMvc.perform(get(INQUIRIES_URI).header(AUTH_TOKEN_HEADER, mine))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content.length()").value(1))
                    .andExpect(jsonPath("$.data.content[0].title").value("내 문의"))
                    .andExpect(jsonPath("$.data.content[0].status").value("OPEN"));
        }

        @Test
        @DisplayName("문의 목록_한 페이지보다 많이 남겼을 때_페이지로 나뉘고 최신순이다")
        void searchInquiries_withMoreThanOnePage_splitsPages() throws Exception {
            String session = sessionOf(user("홍길동"));
            createInquiry(session, "첫 문의", "내용");
            createInquiry(session, "둘째 문의", "내용");
            createInquiry(session, "셋째 문의", "내용");

            mockMvc.perform(get(INQUIRIES_URI).param("size", "2").header(AUTH_TOKEN_HEADER, session))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content.length()").value(2))
                    .andExpect(jsonPath("$.data.totalElements").value(3))
                    .andExpect(jsonPath("$.data.hasNext").value(true))
                    // 최신순이라 마지막에 쓴 것이 첫 줄이다.
                    .andExpect(jsonPath("$.data.content[0].title").value("셋째 문의"));

            mockMvc.perform(get(INQUIRIES_URI)
                            .param("size", "2").param("page", "1")
                            .header(AUTH_TOKEN_HEADER, session))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content.length()").value(1))
                    .andExpect(jsonPath("$.data.hasNext").value(false))
                    .andExpect(jsonPath("$.data.content[0].title").value("첫 문의"));
        }

        @Test
        @DisplayName("문의 상세_내 문의를 식별자로 조회하면_질문이 나온다")
        void getInquiry_ownInquiry_returnsIt() throws Exception {
            String session = sessionOf(user("홍길동"));
            long inquiryId = createInquiry(session, "제목", "내용");

            mockMvc.perform(get(INQUIRIES_URI + "/" + inquiryId).header(AUTH_TOKEN_HEADER, session))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(inquiryId))
                    .andExpect(jsonPath("$.data.content").value("내용"));
        }

        @Test
        @DisplayName("문의 상세_다른 사용자의 문의를 식별자로 조회하면_404 를 받는다")
        void getInquiry_othersInquiry_returnsNotFound() throws Exception {
            long othersInquiryId = createInquiry(sessionOf(user("김철수")), "남의 문의", "내용");

            // 403 이 아니다 — 그 식별자의 문의가 존재한다는 사실 자체를 드러내지 않는다.
            mockMvc.perform(get(INQUIRIES_URI + "/" + othersInquiryId)
                            .header(AUTH_TOKEN_HEADER, sessionOf(user("홍길동"))))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("문의 상세_없는 식별자로 조회하면_404 를 받는다")
        void getInquiry_withUnknownId_returnsNotFound() throws Exception {
            mockMvc.perform(get(INQUIRIES_URI + "/999999999").header(AUTH_TOKEN_HEADER, sessionOf(user("홍길동"))))
                    .andExpect(status().isNotFound());
        }

        /**
         * 명세가 요구하는 것은 "수정 엔드포인트가 없다" 이지 특정 상태 코드가 아니다. 매핑이 없는
         * 메서드에 어떤 코드가 나가는지는 전역 예외 처리의 몫이라 여기서 못 박지 않고, 관찰 가능한
         * 결과인 "본문이 그대로다" 만 본다.
         */
        @Test
        @DisplayName("문의 수정_수정 엔드포인트가 없으므로_본문이 그대로다")
        void updateInquiry_hasNoEndpoint_keepsContent() throws Exception {
            String session = sessionOf(user("홍길동"));
            long inquiryId = createInquiry(session, "제목", "내용");

            mockMvc.perform(patch(INQUIRIES_URI + "/" + inquiryId)
                    .header(AUTH_TOKEN_HEADER, session)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body("바꾼 제목", "바꾼 내용")));

            mockMvc.perform(get(INQUIRIES_URI + "/" + inquiryId).header(AUTH_TOKEN_HEADER, session))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.title").value("제목"))
                    .andExpect(jsonPath("$.data.content").value("내용"));
        }
    }

    @Nested
    @DisplayName("문의 삭제")
    class Delete {

        @Test
        @DisplayName("문의 삭제_답변 전 문의_삭제되고 목록에서 사라진다")
        void deleteInquiry_beforeAnswer_removesIt() throws Exception {
            String session = sessionOf(user("홍길동"));
            long inquiryId = createInquiry(session, "제목", "내용");

            mockMvc.perform(delete(INQUIRIES_URI + "/" + inquiryId).header(AUTH_TOKEN_HEADER, session))
                    .andExpect(status().isNoContent());

            mockMvc.perform(get(INQUIRIES_URI + "/" + inquiryId).header(AUTH_TOKEN_HEADER, session))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("문의 삭제_답변 후 문의_삭제되지 않고 그대로 남는다")
        void deleteInquiry_afterAnswer_isRejected() throws Exception {
            String session = sessionOf(user("홍길동"));
            long inquiryId = createInquiry(session, "제목", "내용");
            answer(inquiryId, "답변입니다");

            mockMvc.perform(delete(INQUIRIES_URI + "/" + inquiryId).header(AUTH_TOKEN_HEADER, session))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.success").value(false));

            mockMvc.perform(get(INQUIRIES_URI + "/" + inquiryId).header(AUTH_TOKEN_HEADER, session))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("문의 삭제_다른 사용자의 문의_404 를 받고 그 문의는 남는다")
        void deleteInquiry_othersInquiry_returnsNotFound() throws Exception {
            String owner = sessionOf(user("김철수"));
            long inquiryId = createInquiry(owner, "남의 문의", "내용");

            mockMvc.perform(delete(INQUIRIES_URI + "/" + inquiryId)
                            .header(AUTH_TOKEN_HEADER, sessionOf(user("홍길동"))))
                    .andExpect(status().isNotFound());

            mockMvc.perform(get(INQUIRIES_URI + "/" + inquiryId).header(AUTH_TOKEN_HEADER, owner))
                    .andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("관리자 답변")
    class Answer {

        @Test
        @DisplayName("답변 등록_답변 전 문의_상태가 답변 후로 바뀌고 답변자와 답변 시각이 담긴다")
        void answer_openInquiry_marksAnswered() throws Exception {
            String session = sessionOf(user("홍길동"));
            long inquiryId = createInquiry(session, "제목" + unique(), "내용");
            User admin = admin("관리자" + unique());

            mockMvc.perform(putAnswer(sessionOf(admin), inquiryId, "답변입니다"))
                    .andExpect(status().isNoContent());

            mockMvc.perform(get(INQUIRIES_URI + "/" + inquiryId).header(AUTH_TOKEN_HEADER, session))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("ANSWERED"))
                    .andExpect(jsonPath("$.data.answer").value("답변입니다"))
                    .andExpect(jsonPath("$.data.answeredAt").isNotEmpty());

            assertThat(adminInquiryField(inquiryId, "answeredBy")).isEqualTo(admin.getId().intValue());
        }

        @Test
        @DisplayName("답변 등록_같은 문의에 다시 답변하면_내용이 갱신되고 문의가 새로 생기지 않는다")
        void answer_twice_updatesAnswerWithoutCreatingInquiry() throws Exception {
            String session = sessionOf(user("홍길동"));
            String title = "제목" + unique();
            long inquiryId = createInquiry(session, title, "내용");
            answer(inquiryId, "첫 답변");

            mockMvc.perform(putAnswer(adminSession(), inquiryId, "고친 답변"))
                    .andExpect(status().isNoContent());

            mockMvc.perform(get(INQUIRIES_URI + "/" + inquiryId).header(AUTH_TOKEN_HEADER, session))
                    .andExpect(jsonPath("$.data.answer").value("고친 답변"))
                    .andExpect(jsonPath("$.data.status").value("ANSWERED"));
            // 작성자 목록이 아니라 전체를 본다 — 새 행이 다른 작성자로 생겨도 잡히도록.
            assertThat(adminInquiryTitles()).containsOnlyOnce(title);
        }

        /**
         * 답변자와 답변 시각은 <b>마지막 답변</b> 기준이다. 다른 관리자가 내용을 다시 쓰면 그 사람이
         * 답변자가 된다 — 자기가 쓰지 않은 문장이 자기 이름으로 남는 것이 더 나쁘다.
         */
        @Test
        @DisplayName("답변 수정_다른 관리자가 다시 답변하면_답변자가 그 관리자로 바뀐다")
        void answer_byAnotherAdmin_replacesAnswerer() throws Exception {
            long inquiryId = createInquiry(sessionOf(user("홍길동")), "제목" + unique(), "내용");
            answer(inquiryId, "첫 답변");
            User second = admin("관리자" + unique());

            mockMvc.perform(putAnswer(sessionOf(second), inquiryId, "고친 답변"))
                    .andExpect(status().isNoContent());

            assertThat(adminInquiryField(inquiryId, "answeredBy")).isEqualTo(second.getId().intValue());
        }

        @Test
        @DisplayName("답변 등록_없는 문의_404 를 받는다")
        void answer_withUnknownId_returnsNotFound() throws Exception {
            mockMvc.perform(putAnswer(adminSession(), 999999999L, "답변"))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("답변 등록_내용이 길이 제한을 넘기면_400 이고 필드별 이유가 담긴다")
        void answer_withTooLongAnswer_returnsBadRequest() throws Exception {
            long inquiryId = createInquiry(sessionOf(user("홍길동")), "제목", "내용");

            mockMvc.perform(putAnswer(adminSession(), inquiryId, "가".repeat(2001)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value("answer"))
                    .andExpect(jsonPath("$.errors[0].reason").isNotEmpty());
        }
    }

    @Nested
    @DisplayName("관리자 문의 목록")
    class AdminList {

        @Test
        @DisplayName("관리자 문의 목록_문의가 들어왔을 때_작성자와 함께 나온다")
        void searchAdminInquiries_withInquiry_includesAuthor() throws Exception {
            User author = user("홍길동" + unique());
            String title = "제목" + unique();
            createInquiry(sessionOf(author), title, "내용");

            String body = mockMvc.perform(get(ADMIN_INQUIRIES_URI)
                            .param("size", "100")
                            .header(AUTH_TOKEN_HEADER, adminSession()))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            List<String> titles = JsonPath.read(body, "$.data.content[*].title");
            assertThat(titles).contains(title);
            assertThat(JsonPath.<List<Integer>>read(body, "$.data.content[?(@.title=='" + title + "')].authorUserId"))
                    .containsExactly(author.getId().intValue());
            assertThat(JsonPath.<List<String>>read(body,
                    "$.data.content[?(@.title=='" + title + "')].authorNickname"))
                    .containsExactly(author.getNickname());
        }

        @Test
        @DisplayName("관리자 문의 목록_답변 전으로 거르면_답변한 문의가 나오지 않는다")
        void searchAdminInquiries_filteredByOpen_excludesAnswered() throws Exception {
            String session = sessionOf(user("홍길동"));
            long answeredId = createInquiry(session, "답변한 문의" + unique(), "내용");
            long openId = createInquiry(session, "답변 전 문의" + unique(), "내용");
            answer(answeredId, "답변");

            String body = mockMvc.perform(get(ADMIN_INQUIRIES_URI)
                            .param("status", "OPEN")
                            .param("size", "100")
                            .header(AUTH_TOKEN_HEADER, adminSession()))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            List<String> statuses = JsonPath.read(body, "$.data.content[*].status");
            assertThat(statuses).isNotEmpty().containsOnly("OPEN");
            List<Integer> ids = JsonPath.read(body, "$.data.content[*].id");
            assertThat(ids).contains((int) openId).doesNotContain((int) answeredId);
        }

        @Test
        @DisplayName("관리자 문의 목록_없는 상태로 거르면_400 을 받는다")
        void searchAdminInquiries_withUnknownStatus_returnsBadRequest() throws Exception {
            mockMvc.perform(get(ADMIN_INQUIRIES_URI)
                            .param("status", "CLOSED")
                            .header(AUTH_TOKEN_HEADER, adminSession()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value("status"))
                    .andExpect(jsonPath("$.errors[0].rejectedValue").value("CLOSED"));
        }
    }

    @Nested
    @DisplayName("탈퇴 연동")
    class Withdrawal {

        @Test
        @DisplayName("탈퇴_문의를 남긴 사용자_탈퇴가 성공하고 그 문의가 사라진다")
        void withdraw_withInquiry_succeedsAndRemovesInquiry() throws Exception {
            User author = user("홍길동" + unique());
            String session = sessionOf(author);
            String title = "제목" + unique();
            createInquiry(session, title, "내용");

            assertThat(adminInquiryTitles()).contains(title);

            mockMvc.perform(delete(ME_URI).header(AUTH_TOKEN_HEADER, session))
                    .andExpect(status().isNoContent());

            assertThat(userRepository.findById(author.getId())).isEmpty();
            assertThat(adminInquiryTitles()).doesNotContain(title);
        }

        @Test
        @DisplayName("탈퇴_답변한 관리자가 탈퇴해도_답변이 남는다")
        void withdraw_byAnsweringAdmin_keepsAnswer() throws Exception {
            String session = sessionOf(user("홍길동"));
            long inquiryId = createInquiry(session, "제목", "내용");
            User admin = admin("관리자" + unique());
            String adminSession = sessions.createSessionFor(admin);
            mockMvc.perform(putAnswer(adminSession, inquiryId, "답변입니다"))
                    .andExpect(status().isNoContent());

            mockMvc.perform(delete(ME_URI).header(AUTH_TOKEN_HEADER, adminSession))
                    .andExpect(status().isNoContent());
            assertThat(userRepository.findById(admin.getId())).isEmpty();

            mockMvc.perform(get(INQUIRIES_URI + "/" + inquiryId).header(AUTH_TOKEN_HEADER, session))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.answer").value("답변입니다"))
                    .andExpect(jsonPath("$.data.status").value("ANSWERED"));
        }
    }

    /** 문의를 하나 만들고 그 식별자를 돌려준다. 대부분의 검증이 여기서 시작한다. */
    private long createInquiry(String sessionId, String title, String content) throws Exception {
        String body = mockMvc.perform(create(sessionId, title, content))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.<Object>read(body, "$.data.id")).longValue();
    }

    private MockHttpServletRequestBuilder create(String sessionId, String title, String content) {
        return post(INQUIRIES_URI)
                .header(AUTH_TOKEN_HEADER, sessionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(title, content));
    }

    private void answer(long inquiryId, String answer) throws Exception {
        mockMvc.perform(putAnswer(adminSession(), inquiryId, answer))
                .andExpect(status().isNoContent());
    }

    private MockHttpServletRequestBuilder putAnswer(String sessionId, long inquiryId, String answer) {
        return put(ADMIN_INQUIRIES_URI + "/" + inquiryId + "/answer")
                .header(AUTH_TOKEN_HEADER, sessionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"answer\":\"" + answer + "\"}");
    }

    /** 관리자 목록에서 문의 하나의 값을 읽는다. 사용자 응답에 담기지 않는 답변자 확인에 쓴다. */
    private Object adminInquiryField(long inquiryId, String field) throws Exception {
        String body = mockMvc.perform(get(ADMIN_INQUIRIES_URI)
                        .param("size", "100")
                        .header(AUTH_TOKEN_HEADER, adminSession()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        List<Object> found = JsonPath.read(body, "$.data.content[?(@.id==" + inquiryId + ")]." + field);
        assertThat(found).as("관리자 목록에 문의 %d 가 있다", inquiryId).hasSize(1);
        return found.getFirst();
    }

    private List<String> adminInquiryTitles() throws Exception {
        String body = mockMvc.perform(get(ADMIN_INQUIRIES_URI)
                        .param("size", "100")
                        .header(AUTH_TOKEN_HEADER, adminSession()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.data.content[*].title");
    }

    private static String body(String title, String content) {
        return "{\"title\":\"" + title + "\",\"content\":\"" + content + "\"}";
    }

    /** 한 클래스가 DB 를 공유하므로 전체 목록을 보는 검증은 이 문자열로 대상을 좁힌다. */
    private static String unique() {
        return "x" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private String adminSession() {
        return sessionOf(admin("관리자" + unique()));
    }

    private String sessionOf(User user) {
        return sessions.createSessionFor(user);
    }

    /**
     * 표시 이름을 지정한다. 닉네임에 유니크 제약이 있고(V24) 같은 컨테이너를 공유하므로
     * 고유값을 뒤에 붙인다 — 고정 이름을 그대로 쓰면 두 번째 사용자부터 저장이 깨진다.
     */
    private User user(String nickname) {
        User user = userService.findOrCreate("google", UUID.randomUUID().toString(),
                unique() + "@example.com");
        user.changeNickname(nickname + unique());
        return userRepository.save(user);
    }

    private User admin(String nickname) {
        User user = user(nickname);
        userService.changeRole(user.getId(), Role.ADMIN);
        return userRepository.findById(user.getId()).orElseThrow();
    }
}

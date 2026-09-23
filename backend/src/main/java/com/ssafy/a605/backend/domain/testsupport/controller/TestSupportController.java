package com.ssafy.a605.backend.domain.testsupport.controller;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.ssafy.a605.backend.domain.testsupport.config.ConditionalOnTestSupport;
import com.ssafy.a605.backend.domain.testsupport.config.TestSupportConfig;
import com.ssafy.a605.backend.domain.testsupport.controller.api.TestSupportApi;
import com.ssafy.a605.backend.domain.testsupport.dto.TestLoginRequest;
import com.ssafy.a605.backend.domain.testsupport.service.TestSupportBroadcastService;
import com.ssafy.a605.backend.domain.testsupport.service.TestSupportService;
import com.ssafy.a605.backend.global.security.LoginUser;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * IdP 를 거치지 않는 두 진입점. 플래그가 꺼지면 이 빈이 만들어지지 않으므로 핸들러 매핑도 없다.
 *
 * <p>두 경로의 인증 방식이 다르다. 테스트 로그인은 API 키로, 샌드박스는 기존 체인의 관리자 권한
 * 검사로 지킨다 — 그래서 샌드박스가 {@code /api/v1/admin} 아래에 있다 (ADR-0003).
 *
 * <p>세션과 무관한 진입점이 하나 더 있다 — WS 관통 확인용 발행 트리거({@link #broadcastSample}).
 * 같은 플래그 아래 두는 이유는 스위치를 늘리지 않기 위해서다. <b>임시</b>이며 제거 조건은
 * {@link com.ssafy.a605.backend.domain.testsupport.service.TestSupportBroadcastService} 에 있다.
 *
 * <p>응답에 세션 식별자를 담지 않는다 (conventions/code.md §7). 웹은 쿠키로, 앱은 헤더로 받는다.
 * 302 가 아니라 204 인 이유는, 호출이 리다이렉트를 따라가 버리면 프론트가 화면 이동의 통제를
 * 잃기 때문이다.
 */
@RestController
@ConditionalOnTestSupport
@RequiredArgsConstructor
public class TestSupportController implements TestSupportApi {

    /** 호출자가 API 키를 실어 보내는 헤더. */
    public static final String API_KEY_HEADER = "X-Test-Support-Key";

    private final TestSupportService testSupportService;
    private final TestSupportBroadcastService testSupportBroadcastService;

    @Override
    @PostMapping(TestSupportConfig.LOGIN_PATH)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void login(@RequestHeader(name = API_KEY_HEADER, required = false) String apiKey,
                      @Valid @RequestBody TestLoginRequest request,
                      HttpServletRequest servletRequest) {
        testSupportService.loginAsTestUser(apiKey, request.name(), servletRequest);
    }

    @Override
    @PostMapping(TestSupportConfig.SANDBOX_PATH)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void enterSandbox(@AuthenticationPrincipal LoginUser loginUser, HttpServletRequest servletRequest) {
        testSupportService.enterSandbox(loginUser.getUserId(), servletRequest);
    }

    @Override
    @PostMapping(TestSupportConfig.BROADCAST_PATH)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void broadcastSample(@AuthenticationPrincipal LoginUser loginUser,
                                @RequestParam(name = "tickSeq", required = false) Long tickSeq) {
        testSupportBroadcastService.publishSampleEnvelopes(loginUser.getUserId(), tickSeq);
    }
}

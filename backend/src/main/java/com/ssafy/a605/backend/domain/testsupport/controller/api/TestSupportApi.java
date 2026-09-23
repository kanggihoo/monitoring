package com.ssafy.a605.backend.domain.testsupport.controller.api;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

import com.ssafy.a605.backend.domain.testsupport.dto.TestLoginRequest;
import com.ssafy.a605.backend.global.security.LoginUser;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

/**
 * IdP 를 거치지 않고 Test User 의 세션을 발급하는 두 진입점. 문서만 담당하며 구현은 컨트롤러가 한다.
 *
 * <p><b>이 문서가 보인다는 것은 테스트 지원 기능이 켜져 있다는 뜻이다.</b> 플래그가 꺼지면 컨트롤러
 * 빈이 만들어지지 않아 두 경로가 문서에도 나타나지 않는다 (ADR-0003).
 */
@Tag(name = "Test Support", description = "테스트·부하 측정 전용 세션 발급 API (기능 플래그가 켜진 환경에만 존재한다)")
public interface TestSupportApi {

    @Operation(
            summary = "테스트 로그인 (기계용)",
            description = "이름으로 지정한 Test User 의 세션을 발급한다. 부하 테스트 도구가 쓴다. "
                    + "인증은 X-Test-Support-Key 헤더의 API 키로 하며, 키가 없거나 틀리면 401 이다. "
                    + "대상은 test_users 에 있고 아직 관리자에게 배정되지 않은 User 로 한정된다 — "
                    + "실제 사용자의 닉네임이나 관리자의 샌드박스 대상 이름을 보내면 404 다. "
                    + "이미 세션이 있는 상태로 부르면 그 세션은 무효화되고 새 식별자가 발급된다. "
                    + "응답은 204 이며 세션 ID 는 본문에 담기지 않는다 — "
                    + "요청에 X-Auth-Token 헤더를 아무 값으로든 얹으면 응답 헤더로 나오고, 얹지 않으면 쿠키로 나간다. "
                    + "세션 쿠키에는 Secure 속성이 항상 켜져 있어 부하 도구는 헤더 방식을 써야 한다."
    )
    void login(@RequestHeader(name = "X-Test-Support-Key", required = false) String apiKey,
               @Valid @RequestBody TestLoginRequest request,
               HttpServletRequest servletRequest);

    @Operation(
            summary = "샌드박스 진입 (사람용)",
            description = "관리자를 자신에게 배정된 Test User 시점으로 갈아 태운다. 재로그인이 없다. "
                    + "**대상 인자가 없다** — 인자가 있는 순간 다른 사용자를 사칭하는 API 가 되기 때문이다. "
                    + "배정된 Test User 가 없으면 그 자리에서 만들어 배정하며, 관리자당 하나로 고정된다. "
                    + "성공하면 관리자 세션이 무효화되고 Test User 세션이 새 식별자로 발급된다 — "
                    + "**이 시점부터 관리자 전용 경로는 403 이 된다.** "
                    + "관리자로 돌아가려면 로그아웃 후 다시 로그인한다 (복귀 장치는 만들지 않는다. "
                    + "보관해 둔 관리자 세션은 그 자체가 권한 상승 경로다). "
                    + "응답이 302 가 아니라 204 인 이유는, 호출이 리다이렉트를 따라가 버리면 "
                    + "프론트가 화면 이동의 통제를 잃기 때문이다."
    )
    void enterSandbox(@AuthenticationPrincipal LoginUser loginUser, HttpServletRequest servletRequest);

    @Operation(
            summary = "실시간 발행 트리거 (관통 확인용, 임시)",
            description = "계약이 정한 destination 넷에 봉투를 하나씩 내보낸다. "
                    + "브라우저에서 프론트 파서가 실제로 봉투를 읽는지 눈으로 확인하기 위한 장치이며, "
                    + "**가격을 계산하지 않는다 — 고정 더미를 싣는다.** "
                    + "개인 메시지의 수신자는 **호출자 자신**이다 (대상 인자가 있으면 아무에게나 "
                    + "메시지를 밀어 넣는 API 가 된다). "
                    + "tickSeq 는 선택이며 생략하면 137 이다. "
                    + "**화면까지 확인하려면 화면에 보이는 Tick 번호보다 큰 값을 줘야 한다** — "
                    + "프론트의 틱 스토어가 이미 확정된 순번 이하를 중복으로 버리기 때문이다. "
                    + "바로 다음 번호를 주면 구멍 판정 없이 깨끗하게 갱신된다. "
                    + "세션이 필요하므로 쿠키 또는 X-Auth-Token 헤더로 인증한다 — "
                    + "헤더 경로는 위조 방지 토큰 검사에서 제외되므로 도구로 부르기 쉽다. "
                    + "응답은 204 다. "
                    + "**임시 장치다** — POST /api/v1/admin/replay/advance 가 구현되면 삭제한다."
    )
    void broadcastSample(@AuthenticationPrincipal LoginUser loginUser,
                         @RequestParam(name = "tickSeq", required = false) Long tickSeq);
}

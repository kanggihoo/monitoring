package com.ssafy.a605.backend.domain.social.controller.api;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.RequestParam;

import com.ssafy.a605.backend.domain.social.dto.ChatHistoryResponse;
import com.ssafy.a605.backend.domain.social.dto.ChatMessageResponse;
import com.ssafy.a605.backend.domain.social.dto.ChatSendRequest;
import com.ssafy.a605.backend.global.response.ApiResponse;
import com.ssafy.a605.backend.global.security.LoginUser;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 전체 채팅 (기획서 12.1 · 12.2, {@code docs/projects/market-replay-api/soc.md}). 문서만 담당하며
 * 구현은 컨트롤러가 한다.
 *
 * <p><b>읽기는 공개, 쓰기는 로그인이다.</b> 미인증 사용자는 상호작용하지 않는다는 정책이 그대로
 * 적용된 형태이며, 기획서 12.1.5 가 같은 것을 규정한다.
 */
@Tag(name = "Chat", description = "전체 채팅 API")
public interface ChatApi {

    String DEFAULT_SIZE = "100";

    @Operation(
            summary = "채팅 히스토리",
            description = "**진행 중인 일차의** 최신 메시지를 커서 페이지로 돌려준다. 지난 일차의 대화는 "
                    + "커서를 아무리 올려도 나오지 않는다 — 구분선 없는 화면에서 어제 대화가 오늘 대화로 읽히기 "
                    + "때문이고, 행 자체는 DB 에 남는다. **미인증도 읽을 수 있다** (기획서 12.1.5). "
                    + "정렬은 메시지 식별자 내림차순 고정이라 정렬 파라미터를 받지 않는다 — 보낸 시각으로 정렬하면 "
                    + "같은 초에 들어온 메시지가 페이지 경계에서 겹치거나 사라진다. "
                    + "cursor 는 이전 응답의 nextCursor 를 그대로 넣는다. 우리가 만든 모양이 아니면 400 이다. "
                    + "닉네임·아바타는 저장값이 아니라 조회 시 조인한 결과다 — 닉네임을 바꾸면 과거 메시지에도 반영되고, "
                    + "탈퇴자는 withdrawn 이 true 이며 nickname · avatarId 가 null 이다 (기획서 2.1.4 · 2.4.6). "
                    + "관리자가 삭제한 메시지는 행이 남고 deleted 가 true 이며 content 가 null 이다 — "
                    + "커서가 가리키는 지점이 사라지면 안 되기 때문이다. "
                    + "gameDate 는 보낸 시점의 게임 거래일이고, 재생 중이 아니었으면 null 이다.",
            parameters = {
                    @Parameter(name = "cursor", description = "이전 응답의 nextCursor. 첫 페이지는 생략"),
                    @Parameter(name = "size", description = "페이지 크기. 기본 100, 최대 100")
            }
    )
    ApiResponse<ChatHistoryResponse> getMessages(@RequestParam String cursor, @RequestParam int size);

    @Operation(
            summary = "채팅 전송",
            description = "한 줄 보낸다. 201 이고 응답은 히스토리 항목과 같은 형태다 — 금칙어 마스킹 결과가 이 응답에 있으므로 "
                    + "화면이 자기가 띄운 문장을 저장된 것으로 바꿔 그릴 수 있다. "
                    + "로그인이 필요하다. 허용 phase 는 OPERATING(11:00~17:00) 이고 열람 전용 시간에는 409 다 (기획서 12.1.2). "
                    + "빈 문자열·공백만이거나 200자를 넘으면 400 이다 (12.2.1). "
                    + "3초 쿨다운이 남았으면 **429** 이고 Retry-After 헤더에 남은 초가 실린다 — 재시도 가능한 실패와 "
                    + "불가능한 실패를 프론트가 구분해야 하므로 이 한 곳만 429 를 쓴다. "
                    + "금칙어는 차단이 아니라 마스킹이다 (12.2.2). 전송은 성공하고 걸린 구간이 * 로 덮여 저장·반환된다 — "
                    + "어느 단어가 걸렸는지 알려주면 우회 방법을 알려주는 셈이다. "
                    + "종목 추천·선동성 발언은 차단하지 않는다 (12.2.3). "
                    + "저장된 메시지는 chat.message 로 전체에 브로드캐스트된다."
    )
    ApiResponse<ChatMessageResponse> send(@AuthenticationPrincipal LoginUser loginUser, ChatSendRequest request);
}

package com.ssafy.a605.backend.domain.social.controller;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.ssafy.a605.backend.domain.social.controller.api.ChatApi;
import com.ssafy.a605.backend.domain.social.dto.ChatHistoryResponse;
import com.ssafy.a605.backend.domain.social.dto.ChatMessageResponse;
import com.ssafy.a605.backend.domain.social.dto.ChatSendRequest;
import com.ssafy.a605.backend.domain.social.service.ChatService;
import com.ssafy.a605.backend.global.response.ApiResponse;
import com.ssafy.a605.backend.global.security.LoginUser;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * 전체 채팅. 읽기는 공개이고 쓰기는 로그인이 필요하다 ({@code soc.md}).
 *
 * <p>조회가 세션 사용자를 받지 않는다. 미인증도 읽을 수 있어야 하고, 응답에 "내 메시지" 표시가
 * 없어 개인화할 것도 없다 — 프론트가 방금 보낸 메시지의 식별자로 자기 것을 안다.
 */
@RestController
@RequestMapping("/api/v1/chat/messages")
@RequiredArgsConstructor
public class ChatController implements ChatApi {

    /** 한 번에 가져갈 수 있는 최대치. 기획서 12.1.3 의 예시값이 기본이다. */
    private static final int MAX_SIZE = 100;

    private final ChatService chatService;

    @Override
    @GetMapping
    public ApiResponse<ChatHistoryResponse> getMessages(@RequestParam(required = false) String cursor,
                                                        @RequestParam(defaultValue = DEFAULT_SIZE) int size) {
        return ApiResponse.ok(chatService.history(cursor, Math.clamp(size, 1, MAX_SIZE)));
    }

    @Override
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ChatMessageResponse> send(@AuthenticationPrincipal LoginUser loginUser,
                                                 @Valid @RequestBody ChatSendRequest request) {
        return ApiResponse.created("메시지를 보냈습니다.",
                chatService.send(loginUser.getUserId(), request.content()));
    }
}

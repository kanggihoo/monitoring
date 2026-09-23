package com.ssafy.a605.backend.domain.user.controller;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.ssafy.a605.backend.domain.user.controller.api.UserApi;
import com.ssafy.a605.backend.domain.user.dto.UserResponse;
import com.ssafy.a605.backend.domain.user.dto.UserUpdateRequest;
import com.ssafy.a605.backend.domain.user.service.UserService;
import com.ssafy.a605.backend.global.response.ApiResponse;
import com.ssafy.a605.backend.global.security.LoginUser;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController implements UserApi {

    private final UserService userService;

    @Override
    @GetMapping("/me")
    public ApiResponse<UserResponse> getCurrentUser(@AuthenticationPrincipal LoginUser loginUser) {
        return ApiResponse.ok(userService.getCurrentUser(loginUser.getUserId()));
    }

    @Override
    @PatchMapping("/me")
    public ApiResponse<UserResponse> updateCurrentUser(@AuthenticationPrincipal LoginUser loginUser,
                                                       @Valid @RequestBody UserUpdateRequest request) {
        return ApiResponse.ok(userService.changeNickname(loginUser.getUserId(), request.nickname()));
    }

    @Override
    @DeleteMapping("/me")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void withdraw(@AuthenticationPrincipal LoginUser loginUser) {
        userService.delete(loginUser.getUserId());
    }
}

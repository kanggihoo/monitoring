package com.ssafy.a605.backend.domain.replay.controller;

import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ssafy.a605.backend.domain.replay.controller.api.ReplayApi;
import com.ssafy.a605.backend.domain.replay.dto.ReplayChangeRequest;
import com.ssafy.a605.backend.domain.replay.dto.ReplayMark;
import com.ssafy.a605.backend.domain.replay.dto.ReplaySeekRequest;
import com.ssafy.a605.backend.domain.replay.dto.ReplaySeekResponse;
import com.ssafy.a605.backend.domain.replay.dto.ReplaySnapshot;
import com.ssafy.a605.backend.domain.replay.service.ReplayMarkService;
import com.ssafy.a605.backend.domain.replay.service.ReplaySeekService;
import com.ssafy.a605.backend.domain.replay.service.ReplayService;
import com.ssafy.a605.backend.global.response.ApiResponse;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/** 기존 ADMIN 필터 체인으로 보호되는 테스트 재생 조작. 문서는 {@link ReplayApi} 가 갖는다. */
@RestController
@RequestMapping("/api/v1/admin/replay")
@ConditionalOnProperty(name = "app.replay.mode", havingValue = "test")
@RequiredArgsConstructor
public class ReplayController implements ReplayApi {

    private final ReplayService replay;
    private final ReplayMarkService marks;
    private final ReplaySeekService seekService;

    @Override
    @GetMapping
    public ApiResponse<ReplaySnapshot> get() {
        return ApiResponse.ok(replay.snapshot());
    }

    @Override
    @PatchMapping
    public ApiResponse<ReplaySnapshot> change(@Valid @RequestBody ReplayChangeRequest request) {
        return ApiResponse.ok(replay.change(request.speed()));
    }

    @Override
    @GetMapping("/marks")
    public ApiResponse<List<ReplayMark>> marks() {
        return ApiResponse.ok(marks.marks());
    }

    @Override
    @PostMapping("/seek")
    public ApiResponse<ReplaySeekResponse> seek(@Valid @RequestBody ReplaySeekRequest request) {
        return ApiResponse.ok(seekService.seek(request));
    }
}

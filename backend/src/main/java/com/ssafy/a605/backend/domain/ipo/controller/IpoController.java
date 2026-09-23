package com.ssafy.a605.backend.domain.ipo.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.ssafy.a605.backend.domain.ipo.controller.api.IpoApi;
import com.ssafy.a605.backend.domain.ipo.dto.IpoOfferingResponse;
import com.ssafy.a605.backend.domain.ipo.dto.IpoOfferingsResponse;
import com.ssafy.a605.backend.domain.ipo.dto.IpoSubscriptionResponse;
import com.ssafy.a605.backend.domain.ipo.service.IpoService;
import com.ssafy.a605.backend.domain.ipo.service.IpoService.Subscribed;
import com.ssafy.a605.backend.global.response.ApiResponse;
import com.ssafy.a605.backend.global.security.LoginUser;

import lombok.RequiredArgsConstructor;

/**
 * 공모주 청약. 대상은 늘 세션의 User 이며 계좌 식별자를 받지 않는다.
 *
 * <p>청약이 {@code {offeringId}} 아래 <b>단수 리소스</b>인 것은 1인 1청약이기 때문이다 —
 * 식별자가 할 일이 없고, 덕분에 {@code POST} 가 멱등이 된다.
 */
@RestController
@RequestMapping("/api/v1/ipo/offerings")
@RequiredArgsConstructor
public class IpoController implements IpoApi {

    private final IpoService ipoService;

    @Override
    @GetMapping
    public ApiResponse<IpoOfferingsResponse> offerings(@AuthenticationPrincipal LoginUser loginUser) {
        return ApiResponse.ok(ipoService.offerings(loginUser.getUserId()));
    }

    @Override
    @GetMapping("/{offeringId}")
    public ApiResponse<IpoOfferingResponse> offering(@AuthenticationPrincipal LoginUser loginUser,
                                                     @PathVariable Long offeringId) {
        return ApiResponse.ok(ipoService.offering(loginUser.getUserId(), offeringId));
    }

    /**
     * <b>상태 코드가 둘</b>이라 이 메서드만 {@code ResponseEntity} 를 쓴다. 새로 잠갔으면 201,
     * 이미 청약한 공모면 200 이고, 그 구분이 곧 "증거금이 이번에 잠겼는가"라서 고정할 수 없다.
     */
    @Override
    @PostMapping("/{offeringId}/subscription")
    public ResponseEntity<ApiResponse<IpoSubscriptionResponse>> subscribe(
            @AuthenticationPrincipal LoginUser loginUser, @PathVariable Long offeringId) {
        Subscribed subscribed = ipoService.subscribe(loginUser.getUserId(), offeringId);
        if (subscribed.created()) {
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(ApiResponse.created("청약했습니다.", subscribed.subscription()));
        }
        return ResponseEntity.ok(ApiResponse.ok("이미 청약한 공모입니다.", subscribed.subscription()));
    }

    @Override
    @DeleteMapping("/{offeringId}/subscription")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancel(@AuthenticationPrincipal LoginUser loginUser, @PathVariable Long offeringId) {
        ipoService.cancel(loginUser.getUserId(), offeringId);
    }
}

package com.ssafy.a605.backend.domain.order.controller;

import java.math.BigDecimal;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.ssafy.a605.backend.domain.order.controller.api.OrderApi;
import com.ssafy.a605.backend.domain.order.dto.OpenOrderResponse;
import com.ssafy.a605.backend.domain.order.dto.OpenOrdersResponse;
import com.ssafy.a605.backend.domain.order.dto.OrderAmendRequest;
import com.ssafy.a605.backend.domain.order.dto.OrderCreatedResponse;
import com.ssafy.a605.backend.domain.order.dto.OrderPreviewResponse;
import com.ssafy.a605.backend.domain.order.dto.OrderRequest;
import com.ssafy.a605.backend.domain.order.entity.OrderSide;
import com.ssafy.a605.backend.domain.order.entity.OrderType;
import com.ssafy.a605.backend.domain.order.service.OrderService;
import com.ssafy.a605.backend.global.response.ApiResponse;
import com.ssafy.a605.backend.global.security.LoginUser;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * 주문·체결. 대상은 늘 세션의 User 이며 계좌 식별자를 받지 않는다.
 */
@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
public class OrderController implements OrderApi {

    private final OrderService orderService;

    @Override
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<OrderCreatedResponse> placeOrder(@AuthenticationPrincipal LoginUser loginUser,
                                                        @Valid @RequestBody OrderRequest request) {
        return ApiResponse.created("주문했습니다.", orderService.place(loginUser.getUserId(), request));
    }

    @Override
    @GetMapping
    public ApiResponse<OpenOrdersResponse> openOrders(@AuthenticationPrincipal LoginUser loginUser) {
        return ApiResponse.ok(orderService.openOrders(loginUser.getUserId()));
    }

    @Override
    @PatchMapping("/{orderId}")
    public ApiResponse<OpenOrderResponse> amendOrder(@AuthenticationPrincipal LoginUser loginUser,
                                                     @PathVariable Long orderId,
                                                     @RequestBody OrderAmendRequest request) {
        return ApiResponse.ok("예약을 수정했습니다.",
                orderService.amend(loginUser.getUserId(), orderId, request));
    }

    @Override
    @DeleteMapping("/{orderId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancelOrder(@AuthenticationPrincipal LoginUser loginUser,
                            @PathVariable Long orderId) {
        orderService.cancel(loginUser.getUserId(), orderId);
    }

    @Override
    @GetMapping("/preview")
    public ApiResponse<OrderPreviewResponse> previewOrder(@AuthenticationPrincipal LoginUser loginUser,
                                                          @RequestParam OrderType orderType,
                                                          @RequestParam OrderSide side,
                                                          @RequestParam Long instrumentId,
                                                          @RequestParam int quantity,
                                                          @RequestParam(required = false) BigDecimal limitPrice) {
        return ApiResponse.ok(orderService.preview(loginUser.getUserId(), orderType, side,
                instrumentId, quantity, limitPrice));
    }
}

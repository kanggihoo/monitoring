package com.ssafy.a605.backend.domain.order.controller.api;

import java.math.BigDecimal;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import com.ssafy.a605.backend.domain.order.dto.OpenOrderResponse;
import com.ssafy.a605.backend.domain.order.dto.OpenOrdersResponse;
import com.ssafy.a605.backend.domain.order.dto.OrderAmendRequest;
import com.ssafy.a605.backend.domain.order.dto.OrderCreatedResponse;
import com.ssafy.a605.backend.domain.order.dto.OrderPreviewResponse;
import com.ssafy.a605.backend.domain.order.dto.OrderRequest;
import com.ssafy.a605.backend.domain.order.entity.OrderSide;
import com.ssafy.a605.backend.domain.order.entity.OrderType;
import com.ssafy.a605.backend.global.response.ApiResponse;
import com.ssafy.a605.backend.global.security.LoginUser;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/**
 * 주문·체결 (기획서 6장, {@code docs/projects/market-replay-api/ord.md}). 문서만 담당하며 구현은
 * 컨트롤러가 한다. 로그인이 필요하고, 등록·수정·취소와 미리보기는 허용 phase 가
 * {@code TRADING} 이며 조회만 {@code ALWAYS} 다 — 기획서 6.2.1 이 운영 시간 외의 등록·수정·취소를
 * 막고, 열람 전용 시간에도 내역 조회는 허용한다 (0장 시간표).
 *
 * <p>대상은 늘 세션의 User 이며 계좌 식별자를 받지 않는다. 남의 주문은 어떤 경로로도 조회되지
 * 않는다.
 */
@Tag(name = "Order", description = "주문·체결 API")
public interface OrderApi {

    @Operation(
            summary = "주문 생성 (시장가·예약 통합)",
            description = "orderType 으로 시장가와 예약을 구분한다. 경로를 나누지 않는 이유는 잔고 검증과 "
                    + "수수료 계산이 둘에 동일하게 적용되어, 나누면 그 로직이 두 곳에 복제되기 때문이다. "
                    + "응답은 execution 과 order 중 하나만 채우고 어느 쪽인지는 orderType 이 말한다. "
                    + "시장가는 즉시 전량 체결되어 미체결 상태를 거치지 않으므로 orders 에 행을 만들지 않고 "
                    + "executions 에만 남는다 — 그래서 GET /orders 에는 절대 나타나지 않는다. "
                    + "체결가는 그 틱의 종가이고 수수료는 매도에만 붙는다. "
                    + "적용 요율은 이 응답이 진실이다 — 무료 타임 경계에서 요청이 넘어가 미리보기와 달라진 경우 "
                    + "409 를 주지 않고 체결시킨 뒤 실제 적용 요율을 담아 돌려준다. "
                    + "매도의 realizedPnl 은 체결 시점에 확정해 저장한다. 원가는 비례 차감된 취득 총액이며 "
                    + "전량 매도하면 보유 수량과 취득 총액이 함께 0 이 된다. "
                    + "매수는 잠기지 않은 결제 통화 현금이 모자라면 409 이고 message 가 환전을 안내한다 — "
                    + "원화가 1억 있어도 달러가 없으면 나스닥 매수는 막힌다. "
                    + "매도는 잠기지 않은 보유 수량이 모자라면 409 다. 예약 매도로 잠긴 수량은 시장가 매도에 쓸 수 없다. "
                    + "거래 시간이 아니거나 개장 직후 아직 공개된 시세가 없으면 409, "
                    + "상장일 전 종목이거나 휴장 종목의 시장가면 409, "
                    + "수량이 1 미만이면 400, 유니버스에 없는 종목이면 404, 그 시즌 계좌가 없으면 404 다. "
                    + "예약(LIMIT) 은 등록만 하고 체결을 판정하지 않는다 — 판정은 새 종가 틱이 도착할 때만 "
                    + "일어나므로, 현재 종가보다 유리한 지정가를 걸어도 다음 틱까지 기다린다. 즉시 체결을 원하는 "
                    + "요구는 시장가가 전담한다. "
                    + "매수는 지정가 x 수량을 현금에서 잠그고 매도는 보유 수량을 잠근다 — lockedAmount 와 "
                    + "lockedQuantity 중 하나만 채워지는 이유다. 유효기간은 당일 폐장까지이며(expiresAt), "
                    + "재생이 정지 중이면 폐장에 도달하는 시각이 없어 409 다."
    )
    ApiResponse<OrderCreatedResponse> placeOrder(@AuthenticationPrincipal LoginUser loginUser,
                                                 @Valid OrderRequest request);

    @Operation(
            summary = "미체결 예약 목록",
            description = "수정·취소의 대상이 되는 미체결 예약을 등록 순서로 돌려준다. "
                    + "페이지를 두지 않는다 — 1인 1계좌에 몇 건이고 틱마다 갱신되는 목록이라, 나눠 읽으면 "
                    + "페이지 사이에 체결이 일어나 같은 예약이 두 번 보이거나 빠진다. "
                    + "asOfTickSeq 는 이 목록이 어느 틱 기준의 사실인지다 — 같은 틱의 market.prices 와 짝을 "
                    + "맞춰 화면의 시세와 목록이 다른 시점을 가리키지 않게 한다. "
                    + "lockedAmount 와 lockedQuantity 중 하나는 항상 null 이다. 매수는 현금을, 매도는 수량을 "
                    + "잠그므로 한 필드로 합치면 금액과 주 수가 섞인다. "
                    + "거래 시간이 아니어도 열람할 수 있고, 폐장 뒤에는 자동 취소로 언제나 빈 목록이다. "
                    + "그 시즌 계좌가 없으면 404 다."
    )
    ApiResponse<OpenOrdersResponse> openOrders(@AuthenticationPrincipal LoginUser loginUser);

    @Operation(
            summary = "미체결 예약 수정",
            description = "수량과 지정가를 바꾼다. 둘 다 선택이며 최소 하나는 있어야 하고, 비운 쪽은 지금 값을 "
                    + "그대로 쓴다. 방향과 종목은 바꿀 수 없다 — 그것을 바꾸는 것은 다른 예약이다. "
                    + "취소 후 재등록과 동일하게 처리한다 — 잠금을 전액 반환하고 새 조건으로 다시 잠근다. "
                    + "orderId 는 유지되고 lockedAmount 가 재계산된다. "
                    + "부분 성공이 없다. 새 조건의 잠금이 잔고를 넘으면 409 이고 원래 예약이 그대로 남는다. "
                    + "거래 시간이 아니면 409, 둘 다 비었거나 수량이 1 미만이거나 지정가가 그 종목의 priceScale "
                    + "보다 잘면 400, 내 미체결 예약이 아니면 404, 이미 체결·취소된 예약이면 409 다.",
            parameters = @Parameter(name = "orderId", description = "고칠 예약")
    )
    ApiResponse<OpenOrderResponse> amendOrder(@AuthenticationPrincipal LoginUser loginUser,
                                              @PathVariable Long orderId,
                                              @RequestBody OrderAmendRequest request);

    @Operation(
            summary = "미체결 예약 취소",
            description = "204. 잠긴 현금·수량이 즉시 반환된다. "
                    + "남의 예약을 취소하려는 요청은 404 다 — 403 을 주면 그 orderId 의 예약이 존재한다는 "
                    + "사실이 드러난다. 없는 예약과 남의 예약을 같은 응답으로 답한다. "
                    + "반면 내 예약인데 이미 체결·취소됐다면 409 다. 숨길 것이 없고 사용자에게는 \"목록이 "
                    + "낡았다\"는 다른 사건이라, 404 로 뭉뚱그리면 방금 체결된 주문이 사라진 것으로 읽힌다. "
                    + "거래 시간이 아니면 409 다.",
            parameters = @Parameter(name = "orderId", description = "취소할 예약")
    )
    void cancelOrder(@AuthenticationPrincipal LoginUser loginUser, @PathVariable Long orderId);

    @Operation(
            summary = "주문 미리보기",
            description = "예상 체결 금액·수수료·부족분을 돌려준다. 상태를 바꾸지 않으므로 "
                    + "사용자가 수량을 타이핑하는 동안 여러 번 불러도 된다. "
                    + "기준가는 시장가면 공개 기준 종가, 예약이면 limitPrice 다 — 자릿수는 종목 메타의 "
                    + "priceScale 을 따른다. 금액·수수료·총액은 결제 통화 자릿수다 (원화 0 · 달러 2). "
                    + "수수료는 매도에만 부과한다 — 매수의 총액은 금액과 같고 appliedFeeRate 는 언제나 0 이다. "
                    + "매도 요율은 지금 이 순간의 값이라 무료 타임(12:00~12:10) 중에는 0 이고 feeFree 가 true 다. "
                    + "availableCash 는 잠기지 않은 결제 통화 현금이며 내림한 상한이다 — 매수 검증이 결제 통화 "
                    + "기준이라 원화가 1억 있어도 달러가 없으면 나스닥 매수는 막힌다. "
                    + "shortfall 은 결제 통화 기준 부족분이고 매도는 항상 \"0\" 이다. "
                    + "부족하면 shortfallExchange 가 채워지며, 프론트는 그 객체를 환전 실행 본문에 그대로 넣으면 된다 — "
                    + "sendAmount 는 보내는 통화 기준이고 실제 환전 계산을 만족하는 최소 금액이라 "
                    + "프론트가 역산할 때 생기는 1원·1센트 미달이 없다. "
                    + "예약 매도에 필요한 잠긴 보유 수량은 여기 없다 — 포지션 조회에 있으므로 중복하지 않는다. "
                    + "거래 시간이 아니거나 개장 직후 아직 공개된 시세가 없으면 409, "
                    + "상장일 전 종목이거나 휴장 종목의 시장가면 409, "
                    + "수량이 1 미만이거나, 예약인데 지정가가 없거나, 지정가가 그 종목의 priceScale 보다 잘면 400, "
                    + "유니버스에 없는 종목이면 404, 그 시즌 계좌가 없으면 404 다.",
            parameters = {
                    @Parameter(name = "orderType", description = "MARKET 또는 LIMIT"),
                    @Parameter(name = "side", description = "BUY 또는 SELL"),
                    @Parameter(name = "instrumentId", description = "대상 종목 식별자"),
                    @Parameter(name = "quantity", description = "주문 수량. 1 이상 정수"),
                    @Parameter(name = "limitPrice", description = "지정가. LIMIT 일 때 필수이며 시장가면 무시한다")
            }
    )
    ApiResponse<OrderPreviewResponse> previewOrder(@AuthenticationPrincipal LoginUser loginUser,
                                                   @RequestParam OrderType orderType,
                                                   @RequestParam OrderSide side,
                                                   @RequestParam Long instrumentId,
                                                   @RequestParam int quantity,
                                                   @RequestParam(required = false) BigDecimal limitPrice);
}

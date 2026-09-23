package com.ssafy.a605.backend.domain.account.controller.api;

import java.math.BigDecimal;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.RequestParam;

import com.ssafy.a605.backend.domain.account.dto.AbsenceAckRequest;
import com.ssafy.a605.backend.domain.account.dto.AbsenceSummaryResponse;
import com.ssafy.a605.backend.domain.account.dto.AccountSummaryResponse;
import com.ssafy.a605.backend.domain.account.dto.ExchangePreviewResponse;
import com.ssafy.a605.backend.domain.account.dto.ExchangeRequest;
import com.ssafy.a605.backend.domain.account.dto.ExchangeResponse;
import com.ssafy.a605.backend.domain.account.dto.PositionsResponse;
import com.ssafy.a605.backend.domain.account.entity.ExchangeDirection;
import com.ssafy.a605.backend.global.response.ApiResponse;
import com.ssafy.a605.backend.global.response.PageResponse;
import com.ssafy.a605.backend.global.security.LoginUser;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/**
 * 계좌·포트폴리오 (기획서 7장, {@code docs/projects/market-replay-api/acc.md}). 문서만 담당하며
 * 구현은 컨트롤러가 한다. 로그인이 필요하고, 조회는 허용 phase 가 {@code ALWAYS} 이며 환전
 * 미리보기·실행만 {@code TRADING} 이다 — 기획서 6.2.1 이 운영 시간 외의 등록·수정·취소를 막고,
 * 열람 전용 시간에도 계좌·내역 조회는 허용한다 (0장 시간표).
 *
 * <p>{@code /accounts/me} 만 두고 {@code /accounts/{id}} 를 두지 않는다. 타인의 계좌는 어떤
 * 경로로도 조회할 수 없다 — 기획서 11.6.2 가 상위권 미러링 차단을 요구한다.
 *
 * <p>평가 시점을 파라미터로 받지 않는다. 어느 틱까지 공개됐는지는 재생 상태가 갖는 값이고
 * 게임 시계가 그 단일 진실이다.
 */
@Tag(name = "Account", description = "계좌·포트폴리오 API")
public interface AccountApi {

    /** 페이지 기본값 ({@code _common.md} §5). */
    String DEFAULT_PAGE = "0";

    String DEFAULT_SIZE = "20";

    @Operation(
            summary = "계좌 요약",
            description = "세션의 사용자 계좌를 공개 기준 틱으로 평가해 돌려준다. "
                    + "총평가액 = 원화 현금 + 달러 현금 x 환율 + 보유 자산 원화 평가액이고, "
                    + "누적 수익률은 시작 잔고 대비, 일간 수익률은 당일 개장 시점 평가액 대비다. "
                    + "금액은 문자열이고 자릿수는 통화가 정한다 (원화 0 · 달러 2 · 환율 2). 비율은 number 다. "
                    + "krwCash · usdCash 는 잠긴 금액을 포함한 총액이며, 주문 가능 금액은 프론트가 lockedKrw · lockedUsd 를 뺀다. "
                    + "asOfTickSeq 는 실시간 account.valuation·market.prices 와 짝을 맞추는 기준이다 — "
                    + "프론트는 이보다 큰 틱만 이어붙인다. "
                    + "보유 종목이 그 거래일에 휴장이면 마지막 종가로 평가한다. "
                    + "최초 공개 전(1일차 브리핑)에는 asOfGameDate · asOfTickSeq · usdKrwRate 가 null 이고 "
                    + "총평가액은 원화 현금과 같다 — 게임 시계가 tickSeq · gameDate · gameMonth 를 같은 구간에 null 로 두는 것과 같은 이유다. "
                    + "그 시즌 계좌가 없으면 404, 재생 상태가 초기화되지 않았으면 409 다."
    )
    ApiResponse<AccountSummaryResponse> getSummary(@AuthenticationPrincipal LoginUser loginUser);

    @Operation(
            summary = "보유 포지션",
            description = "수량이 남은 보유 종목을 공개 기준 틱으로 평가해 돌려준다. 통화가 섞인 목록이므로 "
                    + "항목마다 currency 를 싣고, 현지 통화 금액은 그 통화의 자릿수 문자열이다 (원화 0 · 달러 2). "
                    + "avgPrice 는 저장하지 않고 totalCost / quantity 로 파생한다 — 나눗셈 결과를 저장한 채 "
                    + "매매를 반복하면 오차가 누적되어 전량 매도 후에도 잔고가 미세하게 남는다. "
                    + "avgPrice · currentPrice 의 자릿수는 종목 메타의 priceScale 을 따른다. "
                    + "손익은 현지 통화와 원화 두 가지로 내려준다 — 환율 변동으로 두 값의 부호가 다를 수 있다. "
                    + "returnRate 는 현지 통화 기준이고, weight 는 원화 환산 기준이며 분모는 현금을 포함한 총평가액이다. "
                    + "lockedQuantity 는 미체결 예약 매도가 잠근 수량이고, 주문 가능 수량은 프론트가 quantity 에서 뺀다. "
                    + "수량이 0 인 포지션은 포함하지 않으며 페이지도 없다. "
                    + "보유 종목이 그 거래일에 휴장이면 마지막 종가로 평가한다. "
                    + "최초 공개 전(1일차 브리핑)에는 asOfGameDate · asOfTickSeq · usdKrwRate 가 null 이고 목록이 비어 있다. "
                    + "그 시즌 계좌가 없으면 404, 재생 상태가 초기화되지 않았으면 409 다."
    )
    ApiResponse<PositionsResponse> getPositions(@AuthenticationPrincipal LoginUser loginUser);

    @Operation(
            summary = "환전 미리보기",
            description = "적용 환율·수수료·수령액과 잠기지 않은 보내는 통화 현금을 돌려준다. 상태를 바꾸지 않는다. "
                    + "amount 는 보내는 통화 기준이다 — 받는 통화 기준으로 두면 수수료를 역산해야 하고 "
                    + "반올림 방향이 애매해지며 전액 환전을 표현하기 어렵다. "
                    + "원화는 1원, 달러는 1센트 단위여야 하며 어긋나면 400 이다. "
                    + "수수료율은 항상 0.05% 이고 무료 타임 대상이 아니다 — 거래 수수료만 0 이 되는 것을 "
                    + "사용자가 오해하지 않도록 함께 내려준다. "
                    + "수령액은 받는 통화 자릿수로 내림한다. 남는 소수가 사용자에게 유리하게 붙지 않는다. "
                    + "환율은 하루 1개 값이라 같은 거래일 안에서는 언제 환전해도 같고, 미리보기와 실제 환전 사이에 "
                    + "환율이 바뀌는 일은 틱이 넘어갈 때만 생긴다. "
                    + "availableCash 의 통화는 direction 이 결정한다 — 이름에 통화를 박으면 방향이 뒤집힐 때 값과 어긋난다. "
                    + "거래 시간이 아니면 409, 개장 직후 아직 공개된 시세가 없으면 409, 그 시즌 계좌가 없으면 404 다.",
            parameters = {
                    @Parameter(name = "direction", description = "KRW_TO_USD 또는 USD_TO_KRW"),
                    @Parameter(name = "amount", description = "보내는 통화 기준 금액")
            }
    )
    ApiResponse<ExchangePreviewResponse> previewExchange(@AuthenticationPrincipal LoginUser loginUser,
                                                        @RequestParam ExchangeDirection direction,
                                                        @RequestParam BigDecimal amount);

    @Operation(
            summary = "환전 실행",
            description = "즉시 실행하고 201 로 저장된 한 건을 돌려준다. 미리보기와 같은 계산을 쓰므로 "
                    + "같은 틱 안에서는 미리 보여준 수령액과 실제 수령액이 같다. "
                    + "보내는 통화에서 amount 전액이 빠지고 받는 통화에 수령액이 더해진다 — 수수료는 보내는 금액 "
                    + "안에서 이미 차감되어 수령액에 반영돼 있다. "
                    + "잠기지 않은 현금(예약·청약이 점유하지 않은 몫)만 쓸 수 있고 부족하면 409 이며 잔고는 변하지 않는다. "
                    + "부족분 환전은 주문 미리보기의 shortfallExchange 객체를 그대로 본문에 넣으면 된다 — "
                    + "그 객체가 direction 과 sendAmount 를 담고 있어 프론트가 역산하지 않는다. "
                    + "거래 시간이 아니면 409, 그 시즌 계좌가 없으면 404 다."
    )
    ApiResponse<ExchangeResponse> exchange(@AuthenticationPrincipal LoginUser loginUser,
                                          @Valid ExchangeRequest request);

    @Operation(
            summary = "환전 이력",
            description = "최신순 고정. 항목의 형태는 환전 실행 응답과 같다. "
                    + "체결 내역과 합치지 않는다 — 환전은 종목·수량·손익이 없어 필드가 전혀 겹치지 않는다. "
                    + "기획서 7.4.3 이 환전 이력을 내역에 포함한다고 한 것은 화면에서의 통합이며 프론트가 두 응답을 "
                    + "시간순으로 병합해 그린다. "
                    + "거래 시간이 아니어도 열람할 수 있다. 그 시즌 계좌가 없으면 404 다."
    )
    ApiResponse<PageResponse<ExchangeResponse>> searchExchanges(@AuthenticationPrincipal LoginUser loginUser,
                                                               @RequestParam(defaultValue = DEFAULT_PAGE) int page,
                                                               @RequestParam(defaultValue = DEFAULT_SIZE) int size);

    @Operation(
            summary = "부재 중 요약",
            description = "장중 재접속 시 모달이 부르는 스냅샷이다. 판정 기준은 시각이 아니라 "
                    + "계좌가 마지막으로 확인 처리한 틱이며, 현재 틱과의 차이가 50(게임 50거래일, "
                    + "실제 약 30분) 이상이면 부재다. "
                    + "부재가 아니면 absent 하나만 내려간다 — 나머지 필드는 실리지 않는다. "
                    + "상태를 바꾸지 않는다. 조회가 확인까지 하면 모달을 띄우기만 하고 못 본 경우에도 "
                    + "본 것이 되므로, 확인은 별도 요청이다. "
                    + "toTickSeq 를 확인 처리에 그대로 돌려보내면 된다. "
                    + "eventCount 는 기획서 7.5.2 가 꼽은 IPO(공시·마감·상장)와 무료 수수료 타임 시작을 센다. "
                    + "valuationChangeKrw 와 returnChange 는 구간 시작 시점의 평가액이 저장되지 않아 "
                    + "아직 채우지 못한다 — 지금은 실리지 않는다."
    )
    ApiResponse<AbsenceSummaryResponse> absenceSummary(@AuthenticationPrincipal LoginUser loginUser);

    @Operation(
            summary = "부재 중 요약 확인 처리",
            description = "확인한 지점을 옮긴다. 204. "
                    + "클라이언트가 실제로 본 틱(요약 응답의 toTickSeq)을 돌려보낸다 — 서버가 수신 시점의 "
                    + "틱으로 갱신하면 모달을 띄워 둔 채 자리를 비웠다가 닫는 경우에 그 사이까지 본 것이 된다. "
                    + "갱신은 max(기존 값, 받은 값)이라 낡은 값이 도착해도 뒤로 가지 않고, 같은 값을 여러 번 "
                    + "보내도 결과가 같다. "
                    + "모달을 닫지 않고 나가면 다음 접속에 다시 뜬다 — 확인하지 않았기 때문이다."
    )
    void ackAbsenceSummary(@AuthenticationPrincipal LoginUser loginUser, AbsenceAckRequest request);
}
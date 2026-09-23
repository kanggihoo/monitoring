package com.ssafy.a605.backend.domain.order.controller.api;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.RequestParam;

import com.ssafy.a605.backend.domain.order.dto.ExecutionResponse;
import com.ssafy.a605.backend.global.response.ApiResponse;
import com.ssafy.a605.backend.global.response.PageResponse;
import com.ssafy.a605.backend.global.security.LoginUser;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 체결 내역 (기획서 7.4.1, {@code docs/projects/market-replay-api/ord.md}). 문서만 담당하며
 * 구현은 컨트롤러가 한다.
 *
 * <p><b>주문과 경로를 나눈 이유</b>는 {@code ord.md} §리소스 경계에 있다. 요약하면 생명주기가
 * 반대다 — 미체결 예약은 수정·취소되고 폐장에 소멸하지만 체결은 영구 불변이며 복기·시즌
 * 리포트·MDD·승률의 원천이다. 필드도 겹치지 않아 한 응답 타입으로 합치면 절반이 항상
 * {@code null} 이 된다.
 *
 * <p>허용 phase 는 {@code ALWAYS} 다. 기획서 6.2.1 이 제한하는 것은 등록·수정·취소이고, 열람
 * 전용 시간에도 내역 조회는 허용한다 (0장 시간표).
 */
@Tag(name = "Execution", description = "체결 내역 API")
public interface ExecutionApi {

    /** 페이지 기본값 ({@code _common.md} §5). */
    String DEFAULT_PAGE = "0";

    String DEFAULT_SIZE = "20";

    @Operation(
            summary = "체결 내역",
            description = "세션 사용자의 체결을 최신순으로 돌려준다. 체결은 불변이라 한 번 나온 항목의 값이 "
                    + "나중에 달라지지 않는다. "
                    + "executedPrice 는 그 틱의 종가다 — 예약에서 온 체결이라도 지정가가 아니며 차이는 항상 "
                    + "사용자에게 유리한 쪽이다. 자릿수는 종목 메타의 priceScale 을 따르고, 금액은 결제 통화 "
                    + "자릿수다 (원화 0 · 달러 2). "
                    + "appliedFeeRate 와 realizedPnl 은 체결 시점에 확정해 저장한 값이다 — 매도 요율이 "
                    + "시점에 의존해(무료 타임 0%) 나중에 재계산하면 값이 달라진다. 표시용이 아니라 회계값이며 "
                    + "승률과 최고·최악 거래의 근거다. "
                    + "수수료는 매도에만 붙으므로 매수 체결은 appliedFeeRate 가 0 이고 fee 가 \"0\" 이다. "
                    + "realizedPnl 은 매도만 채워지고 매수는 null 이다. "
                    + "게임 시각(gameDate · tickSeq)과 실제 시각(executedAt)을 둘 다 싣는다 — 전자는 시장에서 "
                    + "일어난 시점, 후자는 사용자의 체험 순서다. "
                    + "orderType 은 이 체결이 시장가에서 왔는지 예약에서 왔는지다. "
                    + "정렬은 (executedAt, id) 최신순 고정이다 — 같은 순간의 두 행이 흔들리면 페이지 경계에서 "
                    + "행이 겹치거나 빠진다. "
                    + "거래 시간이 아니어도 열람할 수 있고, 그 시즌 계좌가 없으면 404 다.",
            parameters = {
                    @Parameter(name = "instrumentId", description = "종목 필터. 없으면 전체"),
                    @Parameter(name = "seasonDay", description = "일차 필터. 일일 복기에서 쓴다. 없으면 전체"),
                    @Parameter(name = "page", description = "0부터 시작하는 페이지 번호"),
                    @Parameter(name = "size", description = "페이지당 항목 수. 최대 100")
            }
    )
    ApiResponse<PageResponse<ExecutionResponse>> searchExecutions(
            @AuthenticationPrincipal LoginUser loginUser,
            @RequestParam(required = false) Long instrumentId,
            @RequestParam(required = false) Short seasonDay,
            @RequestParam(defaultValue = DEFAULT_PAGE) int page,
            @RequestParam(defaultValue = DEFAULT_SIZE) int size);
}

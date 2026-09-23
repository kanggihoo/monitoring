package com.ssafy.a605.backend.domain.ipo.controller.api;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;

import com.ssafy.a605.backend.domain.ipo.dto.IpoOfferingResponse;
import com.ssafy.a605.backend.domain.ipo.dto.IpoOfferingsResponse;
import com.ssafy.a605.backend.domain.ipo.dto.IpoSubscriptionResponse;
import com.ssafy.a605.backend.global.response.ApiResponse;
import com.ssafy.a605.backend.global.security.LoginUser;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 공모주 청약 (기획서 8장, {@code docs/projects/market-replay-api/ipo.md}). 문서만 담당하며 구현은
 * 컨트롤러가 한다. 로그인이 필요하고, 신청·취소는 허용 phase 가 {@code TRADING} 이며 조회는
 * {@code ALWAYS} 다 — 청약 창이 거래 시간 안에만 열리고(기획서 IPO 편성표), 열람 전용 시간에도
 * 결과 조회는 허용한다 (0장 시간표).
 *
 * <p>대상은 늘 세션의 User 이며 계좌 식별자를 받지 않는다.
 */
@Tag(name = "IPO", description = "공모주 청약 API")
public interface IpoApi {

    @Operation(
            summary = "공시된 공모 목록",
            description = "공시 시점이 지난 공모만 돌려준다 — 공시 전 공모는 목록에서 빠지는 것이 아니라 "
                    + "응답에 존재하지 않는다. 종목명 자체가 미래 정보이기 때문이다. "
                    + "개장 전이라 공개된 틱이 없으면 asOfTickSeq 가 null 이고 목록은 비어 있다. "
                    + "페이지를 두지 않는다 — 시즌 전체가 8건이고 창이 동시에 열리는 것은 많아야 둘이다. "
                    + "각 항목이 mySubscription 을 담으므로 '내 청약 목록'을 따로 부르지 않아도 된다. "
                    + "subscriberCount 가 0 이면 expectedWinnerCount 는 0 이고 competitionRate 와 "
                    + "winProbability 는 null 이다 — 0 으로 나누지 않는다. "
                    + "subscriptionClosesAt 과 listedAt 은 오늘 편성이 아니거나 재생이 정지 중이면 null 이다. "
                    + "앵커가 진행 중인 일차 하나만 갖기 때문이며, 지난 공모의 화면은 결과만 보여주면 된다."
    )
    ApiResponse<IpoOfferingsResponse> offerings(@AuthenticationPrincipal LoginUser loginUser);

    @Operation(
            summary = "공시 상세",
            description = "목록 항목의 모든 필드에 공시 본문(summary·allocationRule)이 더해진다. "
                    + "allocationRule 은 문장 그대로다 — 당첨 비율이 편성값이라 숫자로 주면 편성이 바뀔 때마다 "
                    + "프론트가 문장을 다시 조립해야 한다. "
                    + "공시 전 공모와 없는 공모는 모두 404 다. 403 으로 나누면 그 식별자의 공모가 존재한다는 "
                    + "사실이 드러나고, 그것이 곧 편성 정보다."
    )
    ApiResponse<IpoOfferingResponse> offering(@AuthenticationPrincipal LoginUser loginUser,
                                              @PathVariable Long offeringId);

    @Operation(
            summary = "청약 신청",
            description = "본문이 없다 — 청약 단위가 고정이라 보낼 값이 없다. "
                    + "증거금 100% 가 결제 통화 현금에서 즉시 잠긴다. 국내 공모는 원화를, 나스닥 공모는 달러를 "
                    + "잠그며 잠긴 금액은 계좌의 lockedKrw·lockedUsd 에 예약 잠금과 합산된다. "
                    + "멱등이다 — 이미 청약했으면 200 과 기존 청약을 돌려주고 새로 잠그지 않는다. "
                    + "새로 잠갔을 때만 201 이다. "
                    + "거래 시간이 아니거나 청약이 마감된 공모면 409, 공시 전·없는 공모면 404 다. "
                    + "결제 통화 잔고가 모자라면 409 이고, 이때 응답의 data 에 shortfall 과 shortfallExchange 가 "
                    + "실린다 — 프론트는 그 객체를 환전 실행 본문에 그대로 넣으면 된다. 청약에는 미리보기가 없어 "
                    + "(단위가 고정이라 계산해 볼 값이 없다) 부족분을 알릴 자리가 이 거부뿐이다."
    )
    ResponseEntity<ApiResponse<IpoSubscriptionResponse>> subscribe(
            @AuthenticationPrincipal LoginUser loginUser, @PathVariable Long offeringId);

    @Operation(
            summary = "청약 취소",
            description = "증거금이 즉시 반환된다. 청약하지 않은 공모에 대한 요청도 204 다 — 목표 상태(청약 없음)가 "
                    + "이미 성립하므로 중복 클릭이나 재시도가 오류로 보일 이유가 없다. "
                    + "거래 시간이 아니거나 청약이 마감된 공모면 409, 공시 전·없는 공모면 404 다."
    )
    void cancel(@AuthenticationPrincipal LoginUser loginUser, @PathVariable Long offeringId);
}

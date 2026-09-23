package com.ssafy.a605.backend.domain.market.controller.api;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import com.ssafy.a605.backend.domain.market.dto.InstrumentDetailResponse;
import com.ssafy.a605.backend.domain.market.dto.InstrumentInfoResponse;
import com.ssafy.a605.backend.domain.market.dto.InstrumentListResponse;
import com.ssafy.a605.backend.domain.market.dto.SignalCriteriaResponse;
import com.ssafy.a605.backend.domain.market.entity.InstrumentCategory;
import com.ssafy.a605.backend.global.response.ApiResponse;
import com.ssafy.a605.backend.global.security.LoginUser;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 거래 유니버스와 종목 정보 카드 (기획서 5장 · 9장,
 * {@code docs/projects/market-replay-api/unv.md} · {@code info.md}). 문서만 담당하며 구현은
 * 컨트롤러가 한다. 허용 phase 는 넷 다 {@code ALWAYS} 다.
 *
 * <p><b>목록만 {@code 공개} 다</b> (ADR-0005). 종목 상세·정보 카드·판정 기준은 {@code 사용자} 로
 * 남아 있는데, 개인 데이터라서가 아니라 <b>공개 화면이 부르지 않아서</b>다 — 호출부가 생기면
 * 그때 함께 연다. 목록의 {@code watched} 는 미인증에는 전부 {@code false} 다.
 *
 * <p><b>검색·섹터 필터·등락 보드 엔드포인트를 두지 않는다.</b> 28종 소규모 유니버스라 목록 한
 * 화면이 곧 시장 전체 조망이고, 등락 보드는 이 목록과 현재가를 프론트가 조합해 그린다 —
 * 세 번째 엔드포인트는 같은 값을 다르게 정렬한 것뿐이다.
 *
 * <p><b>지표 시계열 엔드포인트도 두지 않는다.</b> 기획서 9.1.2 는 "게임 시각 기준 공시된 최신
 * 분기 값"만 요구한다.
 */
@Tag(name = "Instrument", description = "종목 목록·상세·정보 카드 API")
public interface InstrumentApi {

    @Operation(
            summary = "종목 목록",
            description = "그 시점에 노출되는 유니버스 전체를 돌려준다. **페이지가 없다** — 28종이므로 한 번에 내린다. "
                    + "**메타만 담고 시세를 담지 않는다**: 메타는 IPO 상장 때만 바뀌고 시세는 매 틱(31~36초) 바뀌므로, "
                    + "합치면 틱마다 정적인 메타까지 다시 보낸다. 프론트가 instrumentId 로 조합한다. "
                    + "**정렬을 서버가 하지 않는다** — 등락률·가격·이름 정렬은 시세를 이미 쥔 프론트가 즉시 한다. "
                    + "다만 응답이 호출마다 흔들리지 않도록 식별자 순으로 고정한다. "
                    + "listingStatus 는 UPCOMING · LISTED 두 값이고 공개 기준 게임 거래일에서 파생된다. "
                    + "listedGameDate 는 **UPCOMING 일 때만** 값이 있다 — 상장 후에는 조회 필요성이 없다. "
                    + "isNew 는 **상장된 게임 거래일 하루 동안만** 참이다. watched 는 요청자 기준이다. "
                    + "**공시 전 공모 종목은 응답에 아예 포함되지 않는다** (기획서 5.2.2) — 종목명 자체가 미래 정보다. "
                    + "공모 편성이 아직 적재되지 않았으면 상장 전 공모 종목을 전부 숨긴다(미래 차단 방향으로 더 엄격하다). "
                    + "코인 4종은 시세가 아직 수집되지 않았어도 목록에는 포함된다 — 목록은 메타 계약이고 시세와 분리되어 있다. "
                    + "최초 공개 전(1일차 브리핑)에는 asOfGameDate 가 null 이고 상시 종목만 담긴다. "
                    + "재생 상태가 초기화되지 않았으면 409 다.",
            parameters = @Parameter(name = "category", description = "탭 구분 (NASDAQ · KRX · CRYPTO). 생략하면 전체")
    )
    ApiResponse<InstrumentListResponse> getInstruments(@AuthenticationPrincipal LoginUser loginUser,
                                                       @RequestParam(required = false) InstrumentCategory category);

    @Operation(
            summary = "종목 메타 상세",
            description = "목록 항목의 모든 필드 + 사업 개요(summary)를 돌려준다. "
                    + "재무 지표·신호등·상품 구조·프로젝트 개요는 정보 카드 엔드포인트가 담당하며, summary 는 그 카드와 같은 값이다. "
                    + "**그 시점에 노출되지 않는 종목은 404 다** — 목록에서만 숨기면 식별자를 훑어 공시 전 공모 종목의 "
                    + "이름을 읽을 수 있다. 없는 식별자도 404 다. 재생 상태가 초기화되지 않았으면 409 다.",
            parameters = @Parameter(name = "instrumentId", description = "대상 종목 식별자")
    )
    ApiResponse<InstrumentDetailResponse> getInstrument(@AuthenticationPrincipal LoginUser loginUser,
                                                        @PathVariable Long instrumentId);

    @Operation(
            summary = "종목 정보 카드",
            description = "cardType 이 STOCK · PRODUCT · PROJECT 중 무엇인지가 **세 카드 필드 중 어느 것이 채워지는지를 "
                    + "결정론적으로** 말해 준다 (assetType 에서 파생). 나머지 둘은 null 이다. "
                    + "자산 유형마다 경로를 나누지 않은 이유는 딥링크·새로고침으로 상세에 바로 들어오면 프론트가 "
                    + "assetType 을 모르기 때문이다. "
                    + "**공시 지연을 반영한다** (기획서 9.1.2): 공개 기준 게임 거래일이 disclosedGameDate(분기 종료 + 45일) "
                    + "이후인 분기만 노출하고 그중 가장 최근 것을 고른다. "
                    + "아직 공시된 분기가 없으면 metrics 와 signals 가 빈 배열이고 asOfFiscalQuarter · disclosedGameDate 가 "
                    + "둘 다 null 이지만 **stock 객체 자체는 null 이 아니다** — 카드 종류는 결정되어 있고 내용만 아직 없다. "
                    + "신호등 판정은 서버가 한다 (기준이 편성값이라 프론트 판정은 배포를 요구한다). "
                    + "근거 지표가 na 이면 그 신호의 level 은 **null** 이다 — \"판정 불가\"와 \"보통\"은 다른 사실이다. "
                    + "basisKeys 가 신호와 지표를 잇는다 — 프론트가 어느 지표를 펼칠지 추측하지 않는다. "
                    + "지표 값은 문자열이고 비율(expenseRatio)은 number 다. "
                    + "인버스·레버리지 상품은 riskNotice 가 반드시 채워진다 (기획서 9.3.2). "
                    + "노출되지 않는 종목과 없는 식별자는 404, 재생 상태가 초기화되지 않았으면 409 다.",
            parameters = @Parameter(name = "instrumentId", description = "대상 종목 식별자")
    )
    ApiResponse<InstrumentInfoResponse> getInstrumentInfo(@PathVariable Long instrumentId);

    @Operation(
            summary = "신호등 판정 기준",
            description = "게임 내 도움말이 그대로 그리는 판정 기준이다. **종목과 무관한 전역 조회다** — 전 종목 공통이고 "
                    + "시즌 중 바뀌지 않으므로 종목마다 내려주면 28번 같은 값을 보낸다. "
                    + "재무 지표가 하나도 적재되지 않은 상태에서도 정상 응답한다. "
                    + "thresholds[].description 은 숫자가 아니라 **완성된 문장**이다 — 항목마다 표현 방식이 달라 "
                    + "(\"20% 이상\", \"역성장\") 프론트의 단일 템플릿으로 조립할 수 없고, 기준이 바뀔 때 클라이언트를 "
                    + "고치게 된다. 경계값의 실제 비교는 서버에만 있다."
    )
    ApiResponse<SignalCriteriaResponse> getSignalCriteria();
}

package com.ssafy.a605.backend.domain.report.controller.api;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import com.ssafy.a605.backend.domain.report.dto.DailyReportListResponse;
import com.ssafy.a605.backend.domain.report.dto.DailyReportResponse;
import com.ssafy.a605.backend.domain.report.dto.SeasonReportResponse;
import com.ssafy.a605.backend.domain.report.dto.WhatIfResponse;
import com.ssafy.a605.backend.global.response.ApiResponse;
import com.ssafy.a605.backend.global.security.LoginUser;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 복기·리포트 (기획서 13장, {@code docs/projects/market-replay-api/rpt.md}). 문서만 담당하며
 * 구현은 컨트롤러가 한다. 로그인이 필요하고 허용 phase 는 {@code ALWAYS} 다.
 *
 * <p><b>생성 엔드포인트가 없다.</b> 리포트는 폐장 스냅샷과 함께 자동으로 만들어진다.
 * {@code {seasonDay}} 를 키로 쓰는 것은 1 인 1 일차에 하나뿐이고 사용자가 항상 자기 것만
 * 보기 때문이다 — 대리키를 두면 "내 3일차 리포트 id 가 뭐지"를 먼저 조회해야 한다.
 */
@Tag(name = "Report", description = "복기·리포트 API")
public interface ReportApi {

    @Operation(
            summary = "열람 가능한 일차 목록",
            description = "시즌의 모든 일차를 오름차순으로 돌려준다. available 은 그 일차의 정산 발표가 끝났는지이고, "
                    + "발표 전 일차는 fromGameMonth · toGameMonth · dailyReturn 이 전부 null 이다 — 아직 오지 않은 일차의 "
                    + "재생 구간을 보여주면 미래 차단이 뚫린다 (기획서 4.5). 반대로 정산이 끝난 일차는 구간을 공개한다: "
                    + "브리핑이 당일 시작 시점만 알리고 종료 지점을 숨겼지만 (기획서 3.5.2) 발표 뒤에는 숨길 이유가 없다. "
                    + "dailyReturn 은 그 일차 폐장에 확정된 내 일간 수익률이다. 중간에 합류해 그 일차에 확정 행이 없으면 "
                    + "available 이 true 인데 dailyReturn 이 null 이고, 그 일차의 상세는 404 다. "
                    + "그 시즌 계좌가 없으면 404 다."
    )
    ApiResponse<DailyReportListResponse> getDailyReports(@AuthenticationPrincipal LoginUser loginUser);

    @Operation(
            summary = "일일 복기",
            description = "그 일차의 복기를 돌려준다. summary · bestTrade · worstTrade · patternTags 는 폐장에 얼린 "
                    + "확정값이라 이후 거래나 기준 변경에 흔들리지 않고, instruments 의 차트와 마커는 조회 시점에 만든다. "
                    + "instruments 에는 내가 그 일차에 거래한 종목만 담기고 (기획서 13.1.2) points 는 그 일차 재생 구간의 "
                    + "종가다 — 휴장일은 점을 만들지 않는다. markers 는 내 체결이며 날짜순이다. "
                    + "bestTrade · worstTrade 는 실현 손익 기준 상·하위 1 건이고 매도가 없는 날은 둘 다 null 이다. "
                    + "매도가 한 건인 날은 둘이 같은 체결을 가리킨다. returnRate 는 그 거래가 덜어낸 취득 원가 대비 비율이다. "
                    + "patternTags 는 폐장 종가 기준 판정 결과이며 (rpt.md 의 구현 확정 사항) 없으면 빈 배열이다. "
                    + "commentary 는 AI 가 만든 그날의 문구다 — 폐장 뒤 외부 호출이 성공해야 생기므로 "
                    + "생성 전이거나 실패했으면 null 이고, 화면은 그 영역을 비운 채 리포트를 연다. "
                    + "sceneAliases 는 0~3개이며 거래가 없으면 빈 배열이다. "
                    + "review 는 복기 결과지다 — 성향 판정(profileLevel 1~5 · profileItems 막대 다섯)과 "
                    + "그것을 풀어 쓴 문장(narrative)이며 그 일차까지를 누적해서 본 값이다. "
                    + "null 인 경우가 셋이다: 문구 생성 전, 일일 AI 요청에 누적 집계를 싣지 않았을 때, "
                    + "결과지만 저장에서 걸러졌을 때. 셋 다 화면에서는 같다 — 결과지 카드만 비고 나머지는 열린다. "
                    + "빈 값이 정상인 자리가 셋 더 있다: 설문에 답하지 않으면 selfLevel · selfTotal · selfPersona · "
                    + "profileMatch 와 narrative 의 selfView · profileGap 이 null 이고, 거래가 얇아 판정이 서지 않으면 "
                    + "profileLevel 이 null 이며 investorType 은 UNDETERMINED 다. profileItems[].point 의 null 은 "
                    + "0 이 아니라 그 항목을 잴 거래가 없었다는 뜻이다. "
                    + "regret 은 선택 기능(기획서 13.2)이라 필드만 예약돼 있고 항상 null 이다. "
                    + "정산 발표 전이면 409, 시즌에 없는 일차이거나 내 확정 행이 없으면 404 다.",
            parameters = @Parameter(name = "seasonDay", description = "일차. 1 부터 3 까지")
    )
    ApiResponse<DailyReportResponse> getDailyReport(@AuthenticationPrincipal LoginUser loginUser,
                                                    @PathVariable short seasonDay);

    @Operation(
            summary = "시즌 리포트",
            description = "3일차 정산 발표 이후에만 200 이고 그 전에는 409 다 (기획서 13.3). metrics 다섯은 3일차 "
                    + "폐장에 얼린 값이라 이후 조회에 흔들리지 않는다 — 특히 maxDrawdown 은 매 틱 평가액의 최고점 "
                    + "대비 하락률이라 시즌이 지나간 뒤에는 다시 만들 수 없다. winRate 는 실현 손익이 양수인 매도 "
                    + "비율이고 매도가 없으면 0, turnover 는 총 거래대금을 시작 잔고와 최종 평가액의 평균으로 나눈 "
                    + "값이다. benchmarks 는 조회 시점에 종가로 만들며 재생 구간 전체(1일차 첫 거래일 ~ 3일차 마지막 "
                    + "거래일)를 단순 보유했을 때의 수익률이고, 마지막 줄이 항상 \"나\" 여서 프론트가 한 배열로 "
                    + "정렬한다. 종가를 찾지 못한 기준선은 빠진다. totalParticipants 는 3일차에 확정된 계좌 수다. "
                    + "investorType 은 AI 가 판정한 유형·설명·총평이다 (기획서 13.4) — commentary 와 같은 이유로 "
                    + "없을 수 있고 그때는 null 이다. 유형 코드는 내보내지 않는다. shareCard 는 선택 기능(13.5)이라 "
                    + "필드만 예약돼 있고 항상 null 이다. 그 시즌 계좌가 없거나 내 확정 행이 없으면 404 다."
    )
    ApiResponse<SeasonReportResponse> getSeasonReport(@AuthenticationPrincipal LoginUser loginUser);

    @Operation(
            summary = "'만약에' 시뮬레이터",
            description = "그 종목을 전액 매수해 들고만 있었다면의 수익률을 돌려준다 (기획서 13.3.4). "
                    + "단순 계산이다 — 수수료와 환전 수수료를 반영하지 않고 시작 잔고 전액이 그 종목에 들어갔다고 본다. "
                    + "returnRate 는 시즌 전체(1일차 첫 거래일 → 3일차 마지막 거래일)이고 3일차 발표 전에는 null 이다. "
                    + "days 는 발표된 일차의 분해이며 각 항목은 그 일차 구간만 잰다. "
                    + "myReturnRate 는 같은 축으로 비교한다 — 시즌은 내 누적 수익률, 일차는 그날의 확정 일간 수익률이다. "
                    + "섞으면 비교가 성립하지 않으므로 축을 맞춘다. "
                    + "USD 종목은 양 끝 환율로 원화 환산한 값이라 환차손익이 반영된다. "
                    + "그 일차에 아직 상장 전이었던 IPO 종목은 days 의 returnRate 가 null 이다 — 살 수 없었으므로 가정이 성립하지 않는다. "
                    + "그 일차에 참가하지 않았으면 days 의 myReturnRate 가 null 이다. "
                    + "아직 발표된 일차가 하나도 없으면 409, 유니버스에 없는 종목이거나 그 시즌 계좌가 없으면 404 다.",
            parameters = @Parameter(name = "instrumentId", description = "28 종 중 하나")
    )
    ApiResponse<WhatIfResponse> getWhatIf(@AuthenticationPrincipal LoginUser loginUser,
                                          @RequestParam Long instrumentId);
}

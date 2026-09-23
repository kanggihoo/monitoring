package com.ssafy.a605.backend.domain.season.controller.api;

import org.springframework.security.core.annotation.AuthenticationPrincipal;

import com.ssafy.a605.backend.domain.season.dto.CurrentSeasonResponse;
import com.ssafy.a605.backend.domain.season.dto.SeasonBriefingResponse;
import com.ssafy.a605.backend.global.response.ApiResponse;
import com.ssafy.a605.backend.global.security.LoginUser;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 시즌 안내와 개장 브리핑 (기획서 3장, {@code docs/projects/market-replay-api/sea.md}).
 * 문서만 담당하며 구현은 컨트롤러가 한다. 둘 다 허용 phase 는 {@code ALWAYS} 다.
 *
 * <p><b>권한이 갈린다.</b> 시즌 안내는 미인증에 열려 있고(로그인 전 화면이 쓴다) 개장 브리핑은
 * 로그인이 필요하다. 그래서 {@code SecurityConfig} 가 {@code /seasons/current} 만 개별
 * 명시하고 {@code /seasons/**} 로 열지 않는다.
 *
 * <p><b>참가 엔드포인트를 두지 않는다.</b> 기획서 3.2.1 이 "별도의 참가 버튼은 없다"이고,
 * 시즌 기간 중 첫 로그인이 곧 참가이며 계좌 생성은 로그인 처리의 일부다. 액션으로 두면
 * "로그인은 했는데 참가는 안 한 상태"가 생기고 계좌에 매달린 모든 경로가 그 상태를 처리해야 한다.
 *
 * <p><b>시즌 목록과 {@code {seasonId}} 경로도 두지 않는다.</b> 시즌이 하나뿐이라 식별자가
 * 지목할 대상이 없고, 지금 파 두면 값이 항상 하나인 경로 변수를 모든 엔드포인트가 끌고 다닌다.
 * 지난 일차 복기는 {@code seasonDay} 로 처리하며 스키마는 이미 다중 시즌을 지원한다.
 *
 * <p><b>폐장을 앞당기는 조작이 없다.</b> 폐장 시각은 시간표에 고정되어 있고 장애가 나도 시계가
 * 스스로 제자리를 찾는다.
 */
@Tag(name = "Season", description = "시즌 안내·개장 브리핑 API")
public interface SeasonApi {

    @Operation(
            summary = "시즌 안내와 참가 상태",
            description = "**미인증에 열린 조회다** — 로그인 전 화면이 규칙 요약과 개장 카운트다운을 그린다. "
                    + "참가 상태를 별도 엔드포인트로 쪼개지 않은 이유는 로그인 직후 화면이 둘을 항상 함께 쓰기 때문이고, "
                    + "미인증에 null 로 내려주면 같은 화면을 로그인 전후로 재사용한다. "
                    + "dailySchedule 과 rules 의 두 구간은 **KST 시:분 문자열**이다 — 매일 반복되는 규칙이라 날짜가 없다. "
                    + "REVIEW_ONLY 의 종료 시각은 다음 날 브리핑 시각이다. "
                    + "반면 nextOpenAt 은 **절대 시각**이다: 열람 전용 시간이 18시간이라 남은 초로 보내면 "
                    + "백그라운드 탭 스로틀링에 깨진다. 남은 개장이 없으면 null 이다. "
                    + "요율은 퍼센트 단위다 (0.15 = 0.15%). ipoCount 는 종목 메타에서 세므로 공모 편성이 아직 "
                    + "적재되지 않아도 정확하다. "
                    + "**일자별 재생 구간을 담지 않는다** (기획서 3.1.2 명시 금지) — 구간을 알면 시장 국면을 아는 것과 같다. "
                    + "인증했지만 계좌가 없으면 participation 이 null 이고 **응답이 실패하지 않는다**: 404·409 를 내면 "
                    + "로그인만 해도 시즌 화면이 깨진다. "
                    + "startingBalanceKrw 와 indexAdjusted 는 계좌에 **저장된 값**이며 이 엔드포인트가 지수를 계산하지 않는다."
    )
    ApiResponse<CurrentSeasonResponse> getCurrentSeason(@AuthenticationPrincipal LoginUser loginUser);

    @Operation(
            summary = "당일 개장 브리핑",
            description = "브리핑 시간(11:00~11:05)에 표시하고 **개장 이후에도 다시 열람할 수 있다** (기획서 3.5.3) — "
                    + "그래서 허용 phase 가 ALWAYS 다. schedule 6개 시각은 전부 절대 시각이다. "
                    + "**담지 않는 것 세 가지** (기획서 3.5.2 명시 금지): "
                    + "(1) 당일 재생의 종료 지점이 없다 — 구간 전체를 알면 그날의 시장 국면을 아는 것과 같다. "
                    + "(2) 구간 내용·국면 설명·이벤트 요약이 없다. "
                    + "(3) ipoWindows[] 에 instrumentId 도 name 도 없고 **시각만** 있다 — 종목명은 공시 시점에 공모 영역이 공개한다. "
                    + "종목을 특정하지 않는데도 배열인 이유는 하루에 청약이 여러 건이기 때문이다 (1일차 4건, 2·3일차 각 2건). "
                    + "청약이 없는 날은 빈 배열이고 나머지 필드는 정상이다. "
                    + "재생 상태가 초기화되지 않았으면 409 다."
    )
    ApiResponse<SeasonBriefingResponse> getBriefing();
}

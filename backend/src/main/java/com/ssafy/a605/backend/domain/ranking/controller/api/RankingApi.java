package com.ssafy.a605.backend.domain.ranking.controller.api;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.RequestParam;

import com.ssafy.a605.backend.domain.ranking.dto.AwardsResponse;
import com.ssafy.a605.backend.domain.ranking.dto.MyRankingResponse;
import com.ssafy.a605.backend.domain.ranking.dto.RankingPageResponse;
import com.ssafy.a605.backend.domain.ranking.dto.RankingTrack;
import com.ssafy.a605.backend.global.response.ApiResponse;
import com.ssafy.a605.backend.global.security.LoginUser;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 랭킹 (기획서 11장, {@code docs/projects/market-replay-api/rank.md}). 문서만 담당하며 구현은
 * 컨트롤러가 한다. 허용 phase 는 셋 다 {@code ALWAYS} 다.
 *
 * <p><b>리더보드와 시상은 {@code 공개} 이고 내 순위는 {@code 사용자} 다</b> (ADR-0005). 미인증
 * 요청에는 어느 행도 {@code isMe} 가 아니다. 암전은 인증과 무관하게 같은 규칙으로 걸린다.
 *
 * <p>트랙은 경로가 아니라 {@code track} 파라미터다 — 응답 형태가 같고 다른 것은 서버 안의
 * 기준값뿐이다. 암전(16:00~16:40)은 프론트가 가리는 것이 아니라 <b>서버가 데이터를 주지 않는다</b>
 * (기획서 11.3.1 "열람 불가 마스킹").
 */
@Tag(name = "Ranking", description = "랭킹 API")
public interface RankingApi {

    /** 페이지 기본값 ({@code _common.md} §5). */
    String DEFAULT_PAGE = "0";

    String DEFAULT_SIZE = "20";

    @Operation(
            summary = "리더보드",
            description = "한 트랙의 순위를 페이지로 돌려준다. track 은 SEASON(기본)·DAILY·VALUATION·POINT 이고 목록의 형태는 같다 — "
                    + "다른 것은 줄 세우는 기준으로, SEASON 은 계좌 요약의 cumulativeReturn, DAILY 는 dailyReturn, "
                    + "VALUATION 은 totalValuationKrw 내림차순이다. "
                    + "VALUATION 의 returnRate · tradeCount 는 시즌 기준이다 — 평가액이 시즌 축의 값이라 당일 것을 붙이면 "
                    + "한 줄의 두 숫자가 서로 다른 구간을 말한다. 전원 시드가 같으면 VALUATION 은 SEASON 과 순서가 같고, "
                    + "둘이 갈리는 것은 시작 잔고가 다른 중간 합류자뿐이다. "
                    + "순위는 종가 틱마다 전체 재계산되며 동점은 같은 순위를 주고 다음 순위를 건너뛴다 (1, 1, 3). "
                    + "rankDelta 는 직전 틱 대비 등락이고 양수가 상승이다. 서버 재시작 직후 첫 틱은 비교할 직전 값이 없어 0 이다. "
                    + "행이 공개하는 것은 닉네임·아바타·순위·수익률·총평가액·보유 종목 수·체결 건수까지다. 타인의 보유 종목·비중·매매 내역은 "
                    + "어떤 필드로도 나가지 않는다 (기획서 11.6.2). totalValuationKrw 는 그 틱의 원화 총평가액 문자열이며 트랙과 무관하게 같은 값이다. "
                    + "tradeCount 는 DAILY 면 당일, 나머지 둘이면 시즌 전체 체결 건수다. "
                    + "암전(16:00~16:40) 중에는 blackout 이 true 이고 content 가 빈 배열이다. totalElements 와 totalParticipants 는 그대로다. "
                    + "finalized 는 그 일차의 폐장 스냅샷이 확정된 뒤부터 다음 일차 첫 틱 전까지 true 다. "
                    + "아직 어떤 틱도 순위가 매겨지지 않았으면 (시즌 첫 틱 전, 서버 재시작 직후 첫 틱 전) content 가 비고 "
                    + "asOfTickSeq · asOfGameDate 가 null, totalParticipants 와 totalElements 가 0 이다. "
                    + "잘못된 track 은 400 이다. "
                    + "POINT 는 다른 셋과 달리 폐장에 확정된 일간 순위를 점수로 바꿔 더한 값이다 "
                    + "(1등 100 · 2등 80 · 3등 65 · 4~10등 50에서 5씩 감소 · 11등부터 10, 그날 체결이 0건이면 0). "
                    + "그 트랙에서만 points · wins 가 채워지고, returnRate 는 일간 수익률의 단순 합계다 — 누적 수익률이 아니다. "
                    + "점수는 하루 한 번만 바뀌므로 rankDelta 는 항상 0 이고, 이미 확정·공개된 값이라 암전에도 가리지 않는다. "
                    + "확정된 일차가 없으면 빈 목록이다.",
            parameters = {
                    @Parameter(name = "track", description = "SEASON · DAILY · VALUATION · POINT. 생략하면 SEASON"),
                    @Parameter(name = "page", description = "0 부터"),
                    @Parameter(name = "size", description = "페이지 크기. 최대 100")
            }
    )
    ApiResponse<RankingPageResponse> getLeaderboard(@AuthenticationPrincipal LoginUser loginUser,
                                                    @RequestParam RankingTrack track,
                                                    @RequestParam int page,
                                                    @RequestParam int size);

    @Operation(
            summary = "내 순위",
            description = "세션 사용자의 시즌·일간·평가액 순위를 한 번에 돌려준다. 목록 상단 고정 표시용이며 어느 탭에서도 내 자리를 그릴 수 있어야 한다. "
                    + "암전(16:00~16:40) 중에는 seasonRank · dailyRank · valuationRank 와 각 Delta 가 null 이고 "
                    + "두 returnRate 와 totalValuationKrw 는 그대로다 — 수익률·평가액은 계좌 요약에서 이미 볼 수 있어 가릴 이유가 없고, "
                    + "가려지는 것은 순위다. totalValuationKrw 는 계좌 요약의 같은 이름 필드와 같은 값(원화 총평가액 문자열)이다. "
                    + "아직 어떤 틱도 순위가 매겨지지 않았거나 이 계좌가 그 틱에 평가되지 않았으면 (보유 종목의 종가 없음) "
                    + "세 순위와 등락·수익률·총평가액이 전부 null 이다. 그 시즌 계좌가 없으면 404 다."
    )
    ApiResponse<MyRankingResponse> getMyRanking(@AuthenticationPrincipal LoginUser loginUser);

    @Operation(
            summary = "시상 결과",
            description = "일간 1~5위(daily)와 시즌 우승자(final)를 돌려준다. 시상 테이블은 없고 폐장에 확정된 스냅샷의 상위 행을 읽으므로, "
                    + "수상자가 이후 거래했거나 탈퇴해도 결과가 바뀌지 않는다 (탈퇴자는 nickname · avatarId 가 null). "
                    + "daily 는 정산 발표(17:00) 가 끝난 일차만 담고 일차·순위 오름차순이다. 동점은 같은 순위라 5위 동점이면 다섯을 넘는다. "
                    + "rank 가 등수다 — title 은 서버 상수 \"오늘의 투자자\" 하나이고 \"2위\" 같은 문구는 rank 로 조합한다. final 의 rank 는 1 이다. "
                    + "폐장(16:40) 에 확정돼 리더보드가 finalized 를 내려주는 동안에도 발표 전이면 그날 항목은 없다. "
                    + "final 은 시즌 순위 1위 전원이며 (동점은 같은 순위, 1·1·3) 마지막 일차 발표 전에는 빈 배열이다. title 은 서버 상수(\"오늘의 투자자\" · \"시즌 우승자\") 이고 "
                    + "announcedAt 은 편성된 정산 발표 실제 시각이다. 발표 시점에는 실시간 ranking.awarded 가 "
                    + "빈 신호로 나가므로 상세는 이 경로를 다시 읽어 얻는다 — 공개 토픽에 수상자의 개인 정보를 싣지 않는다."
    )
    ApiResponse<AwardsResponse> getAwards();
}

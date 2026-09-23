package com.ssafy.a605.backend.domain.market.controller.api;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;

import com.ssafy.a605.backend.domain.market.dto.WatchlistResponse;
import com.ssafy.a605.backend.global.response.ApiResponse;
import com.ssafy.a605.backend.global.security.LoginUser;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 관심종목 (기획서 5.4, {@code docs/projects/market-replay-api/unv.md}). 문서만 담당하며
 * 구현은 컨트롤러가 한다. 셋 다 로그인이 필요하고 허용 phase 는 {@code ALWAYS} 다.
 *
 * <p>대상은 늘 세션의 User 이며 식별자를 경로로 받지 않는다 — 남의 관심종목을 읽거나 바꿀
 * 경로를 만들지 않는다.
 *
 * <p><b>경로는 사용자 아래지만 코드는 종목 도메인에 있다.</b> 엔티티가 이미 그 도메인에 있고,
 * 종목 목록의 관심 여부 파생이 나중에 같은 도메인 안에서 끝난다.
 */
@Tag(name = "Watchlist", description = "관심종목 API")
public interface WatchlistApi {

    @Operation(
            summary = "관심종목 조회",
            description = "식별자 배열 하나를 돌려준다. **종목 상세를 담지 않는다** — 관심 여부는 종목 목록·상세 응답에 "
                    + "이미 들어가고, 관심종목만 모아 보는 뷰는 프론트가 이 배열로 목록을 필터한다. "
                    + "비어 있으면 빈 배열이다 — 없음을 오류나 빈 값으로 표현하지 않는다. "
                    + "계좌가 아니라 사용자에 붙으므로 시즌과 무관하게 유지되고, 다른 사용자의 것이 섞이지 않는다."
    )
    ApiResponse<WatchlistResponse> getWatchlist(@AuthenticationPrincipal LoginUser loginUser);

    @Operation(
            summary = "관심종목 추가",
            description = "멱등하다 — 이미 담긴 종목을 다시 추가해도 200 이고 목록에 한 번만 남는다. "
                    + "토글 엔드포인트를 두지 않은 이유가 이것이다: 토글은 요청이 중복되면 결과가 뒤집혀 "
                    + "재시도나 더블 클릭에서 의도와 반대 상태가 된다. "
                    + "상장 예정 종목도 담을 수 있다 (다만 상장 알림은 없다). 없는 종목이면 404 다.",
            parameters = @Parameter(name = "instrumentId", description = "담을 종목 식별자")
    )
    ApiResponse<Void> addToWatchlist(@AuthenticationPrincipal LoginUser loginUser,
                                     @PathVariable Long instrumentId);

    @Operation(
            summary = "관심종목 제거",
            description = "멱등하다 — 없는 항목을 제거해도 204 다. 응답 본문이 없으므로 응답 봉투를 쓰지 않는다. "
                    + "종목은 존재하고 행만 없는 경우가 재시도이므로 204 이고, 404 는 종목 자체가 없는 경우뿐이다.",
            parameters = @Parameter(name = "instrumentId", description = "뺄 종목 식별자")
    )
    void removeFromWatchlist(@AuthenticationPrincipal LoginUser loginUser,
                             @PathVariable Long instrumentId);
}

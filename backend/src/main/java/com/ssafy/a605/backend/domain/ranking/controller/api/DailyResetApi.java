package com.ssafy.a605.backend.domain.ranking.controller.api;

import com.ssafy.a605.backend.domain.ranking.dto.DailyResetStatus;
import com.ssafy.a605.backend.global.response.ApiResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 일차 초기화의 상태 조회와 수동 재실행. 문서만 담당하며 구현은 컨트롤러가 한다.
 *
 * <p><b>왜 수동 경로가 필요한가.</b> 초기화는 브리핑 경계 이벤트에 얹혀 있고, 그 이벤트는
 * 재생 폴러가 경계를 커밋한 <b>뒤에</b> 발행된다 ({@code ReplayService#progress}). 그 사이에
 * 프로세스가 죽으면 경계는 지나갔는데 초기화는 돌지 않았고, 자동 재실행이 없다. 그 상태의
 * 계좌는 거래가 막히므로({@code Account#requireDailyReady}) 개장 전에 손으로 다시 돌릴
 * 길이 있어야 한다.
 *
 * <p><b>재생 조작 API 와 달리 프로덕션에서도 열려 있다.</b> {@code ReplayApi} 는
 * {@code REPLAY_MODE=test} 에서만 등록되지만, 이 경로가 필요한 순간은 정확히 프로덕션이다.
 * 보호는 {@code /api/v1/admin/**} 필터 체인의 ADMIN 권한이다.
 */
@Tag(name = "Daily Reset", description = "일차 계좌 초기화 상태 조회·재실행 (관리자)")
public interface DailyResetApi {

    @Operation(
            summary = "일차 초기화 상태",
            description = "그 일차의 초기화가 몇 계좌에 적용됐는지와, 전일 마감 보유 스냅샷이 "
                    + "보존됐는지를 준다. pending 이 0 이고 missingSnapshots 가 0 이면 개장해도 된다."
    )
    ApiResponse<DailyResetStatus> status(short seasonDay);

    @Operation(
            summary = "일차 초기화 재실행",
            description = "브리핑에 돌았어야 할 초기화를 손으로 다시 돌린다. **멱등이다** — 이미 "
                    + "초기화된 계좌는 그 뒤 거래를 했더라도 다시 지우지 않는다. 전일 정산이나 마감 "
                    + "보유 스냅샷이 없으면 아무것도 바꾸지 않고 상태만 돌려준다."
    )
    ApiResponse<DailyResetStatus> rerun(short seasonDay);
}

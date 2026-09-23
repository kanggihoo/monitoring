package com.ssafy.a605.backend.domain.notification.controller.api;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.RequestParam;

import com.ssafy.a605.backend.domain.notification.dto.NotificationPageResponse;
import com.ssafy.a605.backend.global.response.ApiResponse;
import com.ssafy.a605.backend.global.security.LoginUser;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 알림 센터 (기획서 15장, {@code docs/projects/market-replay-api/noti.md}). 문서만 담당하며 구현은
 * 컨트롤러가 한다. 로그인이 필요하고 허용 phase 는 {@code ALWAYS} 다.
 */
@Tag(name = "Notification", description = "알림 센터 API")
public interface NotificationApi {

    String DEFAULT_PAGE = "0";

    String DEFAULT_SIZE = "20";

    @Operation(
            summary = "알림 목록",
            description = "내 알림을 최신순 페이지로 돌려주고 봉투에 unreadCount(읽지 않은 알림 수, 페이지와 무관한 전체 기준)를 함께 싣는다. "
                    + "미읽음 수 전용 경로는 없다 — 헤더 배지는 이 값과 실시간 noti.created 의 unreadCount 로 갱신한다. "
                    + "kind 가 이동 화면과 referenceId 의 대상 테이블을 정한다 (ORDER_FILLED → executions, ORDER_EXPIRED → orders, "
                    + "IPO_RESULT → ipo_offerings, AWARD · NOTICE → null). tickSeq 는 발생 시점의 게임 시각이고 틱에 속하지 않는 사건은 null 이며 "
                    + "그때 gameDate 도 null 이다. title · body 는 발생 시점에 확정된 문구라 프론트는 그대로 그린다. "
                    + "알림은 계좌에 매달리므로 그 시즌 계좌가 없으면 404 다.",
            parameters = {
                    @Parameter(name = "page", description = "0 부터"),
                    @Parameter(name = "size", description = "페이지 크기. 최대 100")
            }
    )
    ApiResponse<NotificationPageResponse> getNotifications(@AuthenticationPrincipal LoginUser loginUser,
                                                           @RequestParam int page,
                                                           @RequestParam int size);

    @Operation(
            summary = "일괄 읽음 처리",
            description = "내 알림 중 읽지 않은 것 전부를 지금 시각으로 읽음 처리한다. 본문 없음, 204. 알림 센터를 열 때 호출한다 — "
                    + "목록 조회와 묶지 않는 이유는 GET 이 상태를 바꾸면 배지 수를 알아보려는 다른 화면이 알림을 읽음으로 만들기 때문이다. "
                    + "첫 페이지만이 아니라 전부이며 멱등이다. 개별 읽음·삭제는 없다. 그 시즌 계좌가 없으면 404 다."
    )
    void readAll(@AuthenticationPrincipal LoginUser loginUser);
}

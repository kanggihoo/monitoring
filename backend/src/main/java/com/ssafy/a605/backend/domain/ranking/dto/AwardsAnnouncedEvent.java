package com.ssafy.a605.backend.domain.ranking.dto;

import java.util.List;

/**
 * 한 일차의 정산 발표(17:00)가 나갔다는 프로세스 내부 신호. 수상자 개인 알림({@code AWARD},
 * {@code noti.md}) 은 알림 도메인이 이것을 듣고 만든다 — 랭킹은 시상을 조회로만 내려주고 알림
 * 행을 만들지 않는다.
 *
 * <p><b>일간 수상자는 등수를 함께 나른다</b> ({@code S15P21A605-265}). 알림 도메인은 사용자 목록을
 * 받아 한 통씩 쓸 뿐이라 몇 등인지 알 길이 없는데, 2~5 위의 문구에는 등수가 들어가야 한다.
 *
 * @param seasonDay          발표된 일차
 * @param dailyWinners       그 일차의 일간 상위 수상자와 등수. 동점이면 같은 등수가 여럿
 * @param finalWinnerUserIds 시즌 우승자 사용자. 동점이면 여럿, 마지막 일차가 아니면 비어 있다
 */
public record AwardsAnnouncedEvent(short seasonDay, List<DailyWinner> dailyWinners, List<Long> finalWinnerUserIds) {

    /**
     * @param userId 수상자 사용자
     * @param rank   일간 순위. 1~5
     */
    public record DailyWinner(Long userId, int rank) {
    }
}

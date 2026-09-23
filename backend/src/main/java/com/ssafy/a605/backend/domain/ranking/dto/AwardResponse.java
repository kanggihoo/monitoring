package com.ssafy.a605.backend.domain.ranking.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 시상 한 건 ({@code rank.md} §GET /api/v1/rankings/awards). 일간 1위와 시즌 우승자가 같은 모양이다.
 *
 * <p>저장하지 않는다 — 확정 스냅샷 {@code daily_settlements} 의 1위 행을 읽어 만든다. 칭호는
 * 유형이 둘뿐이라 서버 상수다 (기획서 11.4.2).
 *
 * @param seasonDay   수상한 일차. 시즌 우승자는 마지막 일차다
 * @param rank        수상 순위 ({@code S15P21A605-265}). 일간은 1~5, 시즌 우승자는 1 이다. 칭호는
 *                    유형이 둘뿐이라 그대로 두고 등수는 이 값으로 구분한다 — "오늘의 투자자 2위"
 *                    같은 문구는 화면이 조합한다
 * @param nickname    수상자 닉네임
 * @param avatarId    수상자 아바타
 * @param returnRate  확정 시점 수익률(%). 일간 1위는 당일, 시즌 우승자는 시즌 누적
 * @param title       칭호
 * @param announcedAt 정산 발표 실제 시각 (17:00)
 */
public record AwardResponse(short seasonDay, int rank, String nickname, Short avatarId, BigDecimal returnRate,
                            String title, Instant announcedAt) {
}

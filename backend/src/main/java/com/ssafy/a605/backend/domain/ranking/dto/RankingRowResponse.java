package com.ssafy.a605.backend.domain.ranking.dto;

import java.math.BigDecimal;

/**
 * 리더보드 한 행 ({@code rank.md} §GET /api/v1/rankings). 기획서 11.6.1 의 공개 범위가 곧 이 필드
 * 목록이다 — 타인의 보유 종목·비중·매매 내역은 어떤 필드로도 나가지 않고, {@code holdingCount}
 * 가 "몇 종목"까지만 말하는 것이 그 경계다 (11.6.2).
 *
 * <p><b>총평가액은 수익률과 같은 성질의 값이다.</b> 시드가 정해져 있어 수익률에서 이미 역산되므로
 * 새로 열리는 것이 없고, 순위를 "얼마를 굴리고 있는가"로 읽게 하는 표시용 값이다 — 무엇을 들고
 * 있어서 그 금액인지는 여전히 나가지 않는다.
 *
 * @param rank              1 부터. 동점은 같은 순위
 * @param rankDelta         직전 틱 대비 순위 등락. 양수가 상승
 * @param nickname          계좌 주인의 닉네임
 * @param avatarId          계좌 주인의 아바타
 * @param returnRate        트랙의 수익률(%). 시즌이면 누적, 일간이면 당일, <b>{@code POINT} 면
 *                          일간 수익률의 단순 합계</b>다 — 누적 수익률이 아니다 (100 % 뒤
 *                          −50 % 면 이 값은 50 % 지만 실제 누적은 0 % 다)
 * @param totalValuationKrw 그 틱의 원화 총평가액. 트랙과 무관하게 같은 값이다
 * @param holdingCount      보유 종목 수. 수량·비중이 아니다
 * @param tradeCount        체결 건수. 트랙이 {@code DAILY} 면 당일 건수
 * @param points            일간 순위 포인트 합. <b>{@code POINT} 트랙에서만 값이 있다</b>
 * @param wins              일간 1 등 횟수. 같은 조건에서만 값이 있다
 * @param isMe              세션 사용자의 행인가
 */
public record RankingRowResponse(int rank, int rankDelta, String nickname, Short avatarId, BigDecimal returnRate,
                                 String totalValuationKrw, int holdingCount, long tradeCount,
                                 Long points, Long wins, boolean isMe) {
}

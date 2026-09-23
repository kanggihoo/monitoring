package com.ssafy.a605.backend.domain.account.dto;

import java.math.BigDecimal;

/**
 * 한 틱에 평가한 계좌 하나 (매 틱 스윕의 결과 한 줄).
 *
 * <p><b>계산값과 전송 형태를 함께 담는다.</b> {@code summary} 는 그대로 개인 메시지의 payload 로
 * 나가고({@code account.valuation}), 앞의 세 금액·비율은 반올림 전 값이다. 랭킹처럼 이 결과를
 * 다시 계산에 쓰는 쪽은 문자열을 되파싱하지 않아야 한다 — 표현 자릿수로 깎인 값에서 순위를
 * 매기면 동점이 아닌 것이 동점이 된다.
 *
 * @param accountId            평가한 계좌
 * @param userId               그 계좌의 주인. 개인 메시지의 수신자다
 * @param holdingsValuationKrw 보유 자산 원화 평가액 (반올림 전)
 * @param totalValuationKrw    총평가액 (반올림 전)
 * @param cumulativeReturn     누적 수익률(%)
 * @param dailyReturn          일간 수익률(%)
 * @param summary              같은 시점 {@code GET /api/v1/accounts/me} 와 같은 형태의 응답
 */
public record AccountValuation(
        Long accountId,
        Long userId,
        BigDecimal holdingsValuationKrw,
        BigDecimal totalValuationKrw,
        BigDecimal cumulativeReturn,
        BigDecimal dailyReturn,
        AccountSummaryResponse summary,
        PositionsResponse positions
) {
}

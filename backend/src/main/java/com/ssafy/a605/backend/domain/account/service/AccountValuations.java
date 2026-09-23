package com.ssafy.a605.backend.domain.account.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import com.ssafy.a605.backend.domain.account.entity.Account;
import com.ssafy.a605.backend.domain.account.entity.Position;
import com.ssafy.a605.backend.domain.market.service.MarketPriceService.AsOfPrice;
import com.ssafy.a605.backend.global.response.Amounts;

/**
 * 계좌 평가의 공식. <b>조회 경로와 매 틱 스윕이 이것 하나를 쓴다</b> — 규칙이 두 벌이면 계좌
 * 화면과 랭킹이 다른 숫자를 말하게 되고, 그 불일치는 폐장 스냅샷까지 따라간다.
 *
 * <p><b>시세가 없는 종목의 처리는 여기서 정하지 않는다.</b> 부르는 쪽의 판단이기 때문이다 —
 * 요청 하나를 답하는 조회는 500 으로 드러내야 하고 (0 으로 넘기면 평가액과 순위가 조용히
 * 틀린다), 전 계좌를 도는 스윕은 그 계좌만 건너뛰고 나머지를 계속 보내야 한다. 그래서
 * {@link #missingPrices} 로 먼저 묻고, 계산 함수는 값이 다 있다고 가정한다.
 *
 * <p>반올림하지 않은 값을 돌려준다. 표현 자릿수는 응답 DTO 가 입힌다 — 표현용으로 깎은 값에서
 * 수익률을 나누면 화면의 금액과 비율이 서로를 설명하지 못한다.
 */
final class AccountValuations {

    private AccountValuations() {
    }

    /**
     * 가격을 구하지 못한 보유 종목.
     *
     * @param positions 평가할 포지션
     * @param prices    공개 기준 거래일의 가격
     * @return 가격이 없는 종목 식별자. 전부 있으면 빈 목록
     */
    static List<Long> missingPrices(List<Position> positions, Map<Long, AsOfPrice> prices) {
        return positions.stream()
                .map(Position::getInstrumentId)
                .filter(instrumentId -> !prices.containsKey(instrumentId))
                .distinct()
                .toList();
    }

    /**
     * 한 포지션의 원화 환산 평가액. 달러 종목은 그 거래일 환율로 환산하므로 환율이 오르면
     * 이 값이 오른다 — 환차손익을 별도 항목으로 분리하지 않는다 (기획서 7.1.2).
     *
     * @param position 평가할 포지션
     * @param price    그 종목의 공개 기준 가격
     * @param rate     그 거래일의 원/달러 매매기준율
     * @return 원화 환산 평가액
     */
    static BigDecimal valuationKrw(Position position, AsOfPrice price, BigDecimal rate) {
        return price.closeKrw(rate).multiply(BigDecimal.valueOf(position.getQuantity()));
    }

    /**
     * 보유 자산 전체의 원화 환산 평가액.
     *
     * @param positions 평가할 포지션. 가격이 모두 있어야 한다 ({@link #missingPrices})
     * @param prices    공개 기준 거래일의 가격
     * @param rate      그 거래일의 원/달러 매매기준율
     * @return 보유 자산 원화 평가액. 보유가 없으면 0
     */
    static BigDecimal holdingsValuationKrw(List<Position> positions, Map<Long, AsOfPrice> prices,
                                           BigDecimal rate) {
        BigDecimal sum = BigDecimal.ZERO;
        for (Position position : positions) {
            sum = sum.add(valuationKrw(position, prices.get(position.getInstrumentId()), rate));
        }
        return sum;
    }

    /**
     * 총평가액 = 원화 현금 + 달러 현금 x 환율 + 보유 자산 원화 평가액. 현금은 잠긴 몫을
     * 포함한다 — 잠금은 소유권을 옮기지 않는다.
     *
     * @param account              평가할 계좌
     * @param rate                 그 거래일의 원/달러 매매기준율
     * @param holdingsValuationKrw 보유 자산 원화 평가액
     * @return 총평가액
     */
    static BigDecimal totalValuationKrw(Account account, BigDecimal rate,
                                        BigDecimal holdingsValuationKrw) {
        return account.getKrwCash()
                .add(account.getUsdCash().multiply(rate))
                .add(holdingsValuationKrw);
    }

    /**
     * 기준값 대비 수익률(%). 분모는 시드(누적)이거나 당일 개장 시점 평가액(일간)이고,
     * 일간 기준값은 일간 리더보드와 같은 저장값이어야 한다 (기획서 7.1.4 · 11.2.1).
     *
     * @param valuation 총평가액
     * @param base      기준값
     * @return 수익률(%)
     */
    static BigDecimal returnRate(BigDecimal valuation, BigDecimal base) {
        return Amounts.percent(valuation.subtract(base), base);
    }
}

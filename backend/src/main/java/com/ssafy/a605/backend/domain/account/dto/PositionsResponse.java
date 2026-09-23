package com.ssafy.a605.backend.domain.account.dto;

import static com.ssafy.a605.backend.global.response.Amounts.KRW_SCALE;
import static com.ssafy.a605.backend.global.response.Amounts.RATE_SCALE;
import static com.ssafy.a605.backend.global.response.Amounts.USD_SCALE;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

import com.ssafy.a605.backend.domain.account.entity.Position;
import com.ssafy.a605.backend.domain.market.entity.Currency;
import com.ssafy.a605.backend.domain.market.service.MarketPriceService.AsOfPrice;
import com.ssafy.a605.backend.domain.season.entity.GameTick;
import com.ssafy.a605.backend.global.response.Amounts;

/**
 * 보유 포지션 (기획서 7.3, {@code acc.md}).
 *
 * <p>수량이 0 인 포지션은 담기지 않는다. 전량 매도한 행은 취득 총액도 0 으로 강제되므로
 * 평가액에 아무것도 더하지 않고, 평단 계산의 분모가 0 이 된다.
 *
 * <p>페이지를 두지 않는다. 유니버스가 28종이라 보유는 그보다 적고, 틱마다 다시 그리는
 * 목록이다.
 *
 * @param asOfGameDate 이 응답이 어느 게임 거래일까지 포함하는지. 최초 공개 전에는 {@code null}
 * @param asOfTickSeq  공개 기준 틱 순번. 프론트가 이보다 큰 틱만 이어붙인다. 최초 공개 전에는 {@code null}
 * @param usdKrwRate   원화 환산에 쓴 그 거래일의 매매기준율. 최초 공개 전에는 {@code null}
 * @param positions    보유 중인 포지션. 없으면 빈 목록
 */
public record PositionsResponse(
        LocalDate asOfGameDate,
        Long asOfTickSeq,
        String usdKrwRate,
        List<Item> positions
) {

    /**
     * 공개된 틱을 기준으로 평가한 포지션 목록.
     *
     * @param tick      공개 기준 틱. 게임 거래일·틱 순번·환율의 출처
     * @param positions 평가된 포지션
     * @return 포지션 응답
     */
    public static PositionsResponse of(GameTick tick, List<Item> positions) {
        return new PositionsResponse(tick.getGameDate(), tick.getTickSeq(),
                Amounts.of(tick.getUsdKrw(), RATE_SCALE), positions);
    }

    /**
     * 최초 공개 전(1일차 브리핑)의 목록. 평가할 종가도 환산할 환율도 없고, 거래 게이트가
     * 닫혀 있어 포지션 자체가 생길 수 없다.
     *
     * @return 틱 관련 세 필드가 {@code null} 이고 목록이 빈 응답
     */
    public static PositionsResponse beforeFirstPublish() {
        return new PositionsResponse(null, null, null, List.of());
    }

    /**
     * 포지션 한 줄.
     *
     * <p><b>{@code avgPrice} 를 저장하지 않는다.</b> 100 달러를 3주로 나누면 33.3333... 이고,
     * 이 나눗셈 결과를 저장한 채 매매를 반복하면 오차가 누적되어 "전량 매도 후에도 잔고가
     * 미세하게 남는" 정합성 불일치가 된다 ({@code _common.md} §6.3). 그래서 취득 총액과 수량만
     * 저장하고 평단은 여기서 나눈다. 이동평균법(기획서 7.3.3)이 이 구조로 자연히 성립한다 —
     * 추가 매수는 두 값을 더하기만 하므로 가중 평균이 되고 나눗셈이 없다.
     *
     * <p><b>손익을 현지 통화와 원화 두 가지로 내려준다</b> (기획서 7.3.2). 두 값의 부호가 다를
     * 수 있다 — 달러 기준으로 손실인데 환율이 크게 올라 원화로는 이익인 경우다. 이 게임에서
     * 의미 있는 학습 지점이므로 어느 하나로 대체하지 않는다.
     *
     * <p>자릿수가 셋으로 갈린다. 가격({@code avgPrice} · {@code currentPrice})은 종목 메타의
     * {@code priceScale}, 현지 통화 금액은 통화의 자릿수, 원화 환산 금액은 0 이다.
     *
     * @param instrumentId     종목 식별자. 종목명·심볼은 유니버스 응답이 담당한다
     * @param currency         결제 통화. 현지 통화 필드의 자릿수를 이 값이 정한다
     * @param quantity         총 보유 수량 (잠긴 수량 포함)
     * @param lockedQuantity   예약 매도로 잠긴 수량. '주문 중'으로 구분 표시한다 (기획서 7.3.4)
     * @param totalCost        취득 총액 (현지 통화). 저장값이다
     * @param avgPrice         평균 단가 = 취득 총액 / 보유 수량. 저장하지 않는다
     * @param currentPrice     공개 기준 거래일의 종가 (현지 통화)
     * @param valuation        평가액 = 현재가 x 수량 (현지 통화)
     * @param valuationKrw     원화 환산 평가액
     * @param unrealizedPnl    평가 손익 = 평가액 - 취득 총액 (현지 통화)
     * @param unrealizedPnlKrw 원화 환산 평가 손익
     * @param returnRate       수익률(%). <b>현지 통화 기준</b> — 원화 기준이면 환차손익이 섞여
     *                         종목 자체의 성과를 가린다
     * @param weight           포트폴리오 내 비중(%). <b>원화 환산 기준</b> — 통화가 섞인
     *                         포트폴리오에서 비중은 공통 통화로만 계산할 수 있다
     */
    public record Item(
            Long instrumentId,
            Currency currency,
            Integer quantity,
            Integer lockedQuantity,
            String totalCost,
            String avgPrice,
            String currentPrice,
            String valuation,
            String valuationKrw,
            String unrealizedPnl,
            String unrealizedPnlKrw,
            BigDecimal returnRate,
            BigDecimal weight
    ) {

        /**
         * 한 포지션을 공개 기준 가격으로 평가한다.
         *
         * <p>평단만 나눗셈이고 나머지는 곱셈·뺄셈이다. 그 나눗셈도 저장하지 않으므로 오차가
         * 다음 계산으로 넘어가지 않는다.
         *
         * @param position          평가할 포지션
         * @param price             공개 기준 거래일의 가격과 종목 메타
         * @param lockedQuantity    예약 매도로 잠긴 수량. 잠긴 것이 없으면 0
         * @param rate              그 거래일의 원/달러 매매기준율
         * @param valuationKrw      이 포지션의 원화 환산 평가액. 비중의 분자이며 호출 측이
         *                          이미 계산해 총평가액에 더한 값을 그대로 받는다
         * @param totalValuationKrw 계좌 총평가액. 비중의 분모다
         * @return 포지션 한 줄
         */
        public static Item of(Position position, AsOfPrice price, int lockedQuantity,
                              BigDecimal rate, BigDecimal valuationKrw,
                              BigDecimal totalValuationKrw) {
            BigDecimal quantity = BigDecimal.valueOf(position.getQuantity());
            BigDecimal valuation = price.close().multiply(quantity);
            BigDecimal unrealizedPnl = valuation.subtract(position.getTotalCost());
            int moneyScale = price.currency() == Currency.USD ? USD_SCALE : KRW_SCALE;

            return new Item(
                    position.getInstrumentId(),
                    price.currency(),
                    position.getQuantity(),
                    lockedQuantity,
                    Amounts.of(position.getTotalCost(), moneyScale),
                    Amounts.of(position.getTotalCost()
                            .divide(quantity, price.priceScale(), RoundingMode.HALF_UP),
                            price.priceScale()),
                    Amounts.of(price.close(), price.priceScale()),
                    Amounts.of(valuation, moneyScale),
                    Amounts.of(valuationKrw, KRW_SCALE),
                    Amounts.of(unrealizedPnl, moneyScale),
                    Amounts.of(price.currency() == Currency.USD
                            ? unrealizedPnl.multiply(rate) : unrealizedPnl, KRW_SCALE),
                    Amounts.percent(unrealizedPnl, position.getTotalCost()),
                    Amounts.percent(valuationKrw, totalValuationKrw));
        }
    }
}

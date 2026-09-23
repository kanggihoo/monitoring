package com.ssafy.a605.backend.domain.market.dto;

import static com.ssafy.a605.backend.global.response.Amounts.KRW_SCALE;
import static com.ssafy.a605.backend.global.response.Amounts.RATE_SCALE;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import com.ssafy.a605.backend.domain.market.repository.MarketPriceQueryRepository.PriceRow;
import com.ssafy.a605.backend.global.response.Amounts;

/**
 * 현재가 스냅샷 (기획서 4.1 · 4.4, {@code mkt.md}).
 *
 * <p><b>REST 응답과 실시간 {@code market.prices} 페이로드가 이 레코드를 공유한다.</b> 사양이
 * 둘을 동일 형태로 계약한 근거는 프론트가 초기 로드와 틱 수신에 같은 파서·같은 상태 갱신
 * 경로를 쓰게 하는 것이다. 타입을 공유하면 한쪽만 고쳐 형태가 갈리는 일이 구조적으로
 * 불가능해진다.
 *
 * <p>환율을 같은 응답에 싣는다. 환율은 거래일당 1개 값이고 시세 틱과 함께 갱신되므로, 별도
 * 호출로 나누면 두 값이 서로 다른 틱 것일 수 있게 되어 원화 환산이 어긋난다.
 *
 * @param asOfGameDate 이 응답이 어느 게임 거래일까지 포함하는지. 최초 공개 전에는 {@code null}
 * @param asOfTickSeq  공개 기준 틱 순번. 프론트가 이보다 큰 틱만 이어붙인다. 최초 공개 전에는 {@code null}
 * @param usdKrwRate   원화 환산에 쓴 그 거래일의 매매기준율. 최초 공개 전에는 {@code null}
 * @param prices       그 거래일에 상장된 종목의 가격. 최초 공개 전에는 빈 목록
 */
public record MarketPricesResponse(
        LocalDate asOfGameDate,
        Long asOfTickSeq,
        String usdKrwRate,
        List<Item> prices
) {

    /**
     * 공개된 틱을 기준으로 자른 스냅샷.
     *
     * @param asOfTickSeq  공개 기준 틱 순번
     * @param asOfGameDate 그 틱의 게임 거래일
     * @param usdKrw       그 거래일의 원/달러 매매기준율
     * @param rows         그 거래일에 상장된 종목의 가격 행
     * @param tradedAmounts 종목별 당일 거래대금(원화). 체결이 없는 종목은 담기지 않는다
     * @return 현재가 스냅샷
     */
    public static MarketPricesResponse of(long asOfTickSeq, LocalDate asOfGameDate, BigDecimal usdKrw,
                                          List<PriceRow> rows, Map<Long, BigDecimal> tradedAmounts) {
        return new MarketPricesResponse(asOfGameDate, asOfTickSeq, Amounts.of(usdKrw, RATE_SCALE),
                rows.stream()
                        .map(row -> Item.of(row, asOfGameDate, usdKrw,
                                tradedAmounts.getOrDefault(row.instrumentId(), BigDecimal.ZERO)))
                        .toList());
    }

    /**
     * 최초 공개 전(1일차 브리핑)의 스냅샷. 절단 기준이 없으므로 기준 필드를 비우고 목록을
     * 빈 배열로 내린다 — 준비 상태를 가짜 틱이나 가짜 가격으로 표시하지 않는다.
     *
     * @return 틱 관련 세 필드가 {@code null} 이고 목록이 빈 응답
     */
    public static MarketPricesResponse beforeFirstPublish() {
        return new MarketPricesResponse(null, null, null, List.of());
    }

    /**
     * 종목 한 줄.
     *
     * @param instrumentId 종목 식별자. 종목명·심볼은 유니버스 응답이 담당한다
     * @param close        공개 기준 거래일의 종가 (현지 통화). 휴장이면 직전 종가를 유지한다
     * @param closeKrw     원화 환산 종가. 원화 종목은 {@code close} 와 같다
     * @param prevClose    직전 거래일 종가. <b>휴장이거나 직전 종가가 아예 없으면 {@code close}
     *                     와 같다</b> — 계약이 이 필드를 nullable 로 두지 않았고(등락률만
     *                     nullable 이다), "등락률의 근거가 없다"는 사실은 {@code changeRate} 가
     *                     이미 나른다. 직전 종가가 없는 경우는 IPO 종목의 상장일마다 생긴다
     * @param changeRate   등락률(%). <b>휴장일과 직전 종가가 없는 첫 거래일에는 {@code null}</b> —
     *                     0 으로 두면 보합("안 움직였다")과 구별되지 않는다
     * @param marketClosed 그 거래일에 이 종목의 시장이 휴장인가
     * @param tradedAmountKrw <b>참가자들이</b> 그 일차에 이 종목으로 주고받은 금액, 원화 환산
     *                     ({@code S15P21A605-253}). 실제 시장 거래량이 아니다 — 단일 가격 모델이라
     *                     적재되지 않는다 (기획서 4.1.3). <b>매수·매도를 모두 더하므로</b> 사고 판
     *                     돈은 두 번 들어간다. 체결이 없으면 {@code "0"} 이며 비우지 않는다: 이 값으로
     *                     정렬하는 쪽이 {@code null} 을 다루게 하지 않는다.
     *                     <b>갱신은 틱 주기다</b> — 스냅샷이 틱 단위로 재사용되므로 방금 일어난
     *                     체결은 다음 틱에 반영된다
     */
    public record Item(
            Long instrumentId,
            String close,
            String closeKrw,
            String prevClose,
            BigDecimal changeRate,
            boolean marketClosed,
            String tradedAmountKrw
    ) {

        /**
         * 한 행을 표현 자릿수로 맞추고 등락률·휴장 여부를 파생한다.
         *
         * <p>원화 환산과 휴장 판정은 {@link PriceRow} 가 안다 — 규칙을 여기서 다시 풀면 같은
         * 식이 두 곳에 생긴다.
         *
         * <p><b>자릿수는 종목별 {@code priceScale} 이다</b> — 전부 2자리로 통일하면 저가 코인의
         * 등락이 화면에서 사라지고, 전부 4자리로 통일하면 고가 주식이 의미 없는 0 을 달고 나온다
         * ({@code _common.md} §6.2). 종가와 직전 종가에 같은 자릿수를 적용한다.
         *
         * @param row          공개 기준 가격 행
         * @param asOfGameDate 공개 기준 게임 거래일. 종가의 거래일이 이보다 이르면 그날 휴장이다
         * @param usdKrw       그 거래일의 원/달러 매매기준율
         * @param tradedAmountKrw 그 일차의 참가자 거래대금 (원화 환산)
         * @return 종목 한 줄
         */
        static Item of(PriceRow row, LocalDate asOfGameDate, BigDecimal usdKrw, BigDecimal tradedAmountKrw) {
            boolean closed = row.isMarketClosedOn(asOfGameDate);
            BigDecimal previous = row.previousClose();

            return new Item(
                    row.instrumentId(),
                    Amounts.of(row.close(), row.priceScale()),
                    Amounts.of(row.closeKrw(usdKrw), KRW_SCALE),
                    // 휴장이면 유지한 종가가 직전 종가이기도 하다 — 계약 예시의 휴장 행이
                    // close 와 prevClose 를 같은 값으로 싣는다 (mkt.md). 이월된 종가의 그
                    // 앞 거래일까지 거슬러 가면 계약과 형태가 달라진다.
                    Amounts.of(closed || previous == null ? row.close() : previous, row.priceScale()),
                    closed || previous == null || previous.signum() == 0 ? null
                            : Amounts.percent(row.close().subtract(previous), previous),
                    closed,
                    Amounts.of(tradedAmountKrw, KRW_SCALE));
        }
    }
}

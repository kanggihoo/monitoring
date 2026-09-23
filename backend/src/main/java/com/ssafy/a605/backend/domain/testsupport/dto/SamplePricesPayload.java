package com.ssafy.a605.backend.domain.testsupport.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * {@code market.prices} 페이로드의 <b>모양만</b> 따르는 고정 더미 ({@code mkt.md}).
 *
 * <p>값을 계산하지 않는다 — 상수를 싣는다. 여기 적힌 숫자는 트리거와 함께 사라지므로
 * 어디에도 남지 않는다.
 *
 * <p>{@code type} 을 아무 값으로나 둘 수 없어서 이 모양이 필요하다. 프론트의 디스패처가
 * {@code type != "market.prices"} 인 메시지를 조용히 버리므로, 다른 값을 보내면 전송이 완벽해도
 * 화면이 그대로여서 수동 확인이 성립하지 않는다.
 *
 * @param asOfGameDate 절단 기준 게임 거래일
 * @param asOfTickSeq  절단 기준 Tick 순번
 * @param usdKrwRate   해당 거래일의 매매기준율
 * @param prices       종목별 시세
 */
public record SamplePricesPayload(String asOfGameDate, Long asOfTickSeq, String usdKrwRate,
                                  List<SamplePrice> prices) {

    /**
     * 종목 하나의 시세.
     *
     * @param instrumentId  종목 식별자
     * @param close         현지 통화 종가
     * @param closeKrw      원화 환산 종가
     * @param prevClose     직전 거래일 종가
     * @param changeRate    등락률. 휴장이면 {@code null}
     * @param marketClosed  그 거래일에 해당 시장이 휴장인지
     */
    public record SamplePrice(Long instrumentId, String close, String closeKrw, String prevClose,
                              BigDecimal changeRate, Boolean marketClosed) {
    }
}

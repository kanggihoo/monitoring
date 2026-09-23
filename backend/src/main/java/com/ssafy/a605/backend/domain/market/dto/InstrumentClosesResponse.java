package com.ssafy.a605.backend.domain.market.dto;

import java.time.LocalDate;
import java.util.List;

import com.ssafy.a605.backend.domain.market.entity.Currency;
import com.ssafy.a605.backend.domain.market.entity.DailyClose;
import com.ssafy.a605.backend.domain.market.entity.Instrument;
import com.ssafy.a605.backend.global.response.Amounts;

/**
 * 종가 시계열 (기획서 4.2 · 4.3, {@code mkt.md}). 차트 초기 로드다.
 *
 * <p><b>휴장일은 점을 만들지 않는다.</b> 현재가 조회는 이월해서 휴장으로 표시하지만 시계열은
 * 그 날짜가 아예 나타나지 않는다. 차트는 선이므로 점이 없으면 앞뒤 점이 이어지고, 그것이
 * "그날 거래가 없었다"의 올바른 표현이다.
 *
 * <p>페이지를 두지 않는다. 시즌 말 기준 최대 약 1,700점이고 압축 전 약 60KB다. 페이지로
 * 쪼개면 이어붙이기 로직이 페이지 경계마다 반복된다.
 *
 * <p><b>원화 환산을 담지 않는다.</b> 차트는 현지 통화로 그린다 — 환율 변동이 종목의 움직임으로
 * 착각되지 않게 하기 위해서다. 원화 병기 요구는 현재가에 대한 것이며 현재가 조회가 담당한다.
 *
 * @param instrumentId 종목 식별자
 * @param currency     결제 통화. 점의 통화다
 * @param priceScale   가격의 표현 자릿수. 종목마다 다르다
 * @param asOfGameDate 이 응답이 어느 게임 거래일까지 포함하는지. 최초 공개 전에는 {@code null}
 * @param asOfTickSeq  공개 기준 틱 순번. 프론트가 이보다 큰 틱만 이어붙인다. 최초 공개 전에는 {@code null}
 * @param points       종가가 있는 날짜만의 점. 최초 공개 전에는 빈 목록
 */
public record InstrumentClosesResponse(
        Long instrumentId,
        Currency currency,
        short priceScale,
        LocalDate asOfGameDate,
        Long asOfTickSeq,
        List<Point> points
) {

    /**
     * 공개된 틱까지 자른 시계열.
     *
     * <p>절단 기준을 틱 엔티티가 아니라 두 값으로 받는다 — 시계열은 환율을 쓰지 않으므로
     * {@code game_ticks} 를 읽을 이유가 없고, 게임 시계가 이미 그 둘을 갖고 있다.
     *
     * @param instrument   대상 종목. 통화와 자릿수의 출처
     * @param asOfTickSeq  공개 기준 틱 순번
     * @param asOfGameDate 그 틱의 게임 거래일. 점의 끝 날짜다
     * @param closes       시작 날짜부터 공개 기준 거래일까지의 종가
     * @return 종가 시계열 응답
     */
    public static InstrumentClosesResponse of(Instrument instrument, Long asOfTickSeq,
                                              LocalDate asOfGameDate, List<DailyClose> closes) {
        return new InstrumentClosesResponse(instrument.getId(), instrument.getCurrency(),
                instrument.getPriceScale(), asOfGameDate, asOfTickSeq,
                closes.stream()
                        .map(close -> new Point(close.getGameDate(),
                                Amounts.of(close.getClosePrice(), instrument.getPriceScale())))
                        .toList());
    }

    /**
     * 최초 공개 전(1일차 브리핑)의 시계열. 절단 기준이 없으므로 기준 필드를 비우고 점을 내리지
     * 않는다. 통화와 자릿수는 틱에서 나오는 값이 아니라 종목 메타이므로 그대로 담는다.
     *
     * @param instrument 대상 종목
     * @return 틱 관련 두 필드가 {@code null} 이고 점이 빈 응답
     */
    public static InstrumentClosesResponse beforeFirstPublish(Instrument instrument) {
        return new InstrumentClosesResponse(instrument.getId(), instrument.getCurrency(),
                instrument.getPriceScale(), null, null, List.of());
    }

    /**
     * 시계열의 한 점.
     *
     * @param gameDate 게임 거래일. 그 종목에 종가가 있는 날짜만 나타난다
     * @param close    그날 종가 (현지 통화, 분할 조정된 값)
     */
    public record Point(LocalDate gameDate, String close) {
    }
}

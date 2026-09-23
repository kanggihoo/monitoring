package com.ssafy.a605.backend.domain.market.entity;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 일봉 종가 시계열의 한 점. 단일 가격 모델이라 시가·고가·저가·거래량을 담지 않는다
 * (기획서 4.1.3). 그 종목이 그날 휴장이면 행 자체가 없다.
 */
@Entity
@Table(name = "daily_closes")
@IdClass(DailyClose.Pk.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DailyClose {

    @Id
    @Column(name = "instrument_id", nullable = false)
    private Long instrumentId;

    @Id
    @Column(name = "game_date", nullable = false)
    private LocalDate gameDate;

    // 분할 조정된 종가. 화면·체결·평가에 쓰이는 값은 전부 이것이다.
    // 원주가를 그대로 재생하면 분할일마다 가짜 폭락이 생기고, 역분할이 4번인 SQQQ 는
    // 누적 625배가 되어 들고만 있어도 랭킹이 무너진다.
    @Column(name = "close_price", nullable = false, precision = 20, scale = 8)
    private BigDecimal closePrice;

    // 조정 전 원본. 버리지 않는다 - 조정 기준일을 나중에 바꾸려면 원본이 있어야 하고,
    // 없으면 그 결정이 되돌릴 수 없게 된다.
    @Column(name = "raw_close", nullable = false, precision = 20, scale = 8)
    private BigDecimal rawClose;

    /**
     * @param instrumentId 대상 종목
     * @param gameDate     게임 거래일
     * @param closePrice   분할 조정된 종가
     * @param rawClose     조정 전 원본 종가
     */
    public DailyClose(Long instrumentId, LocalDate gameDate, BigDecimal closePrice, BigDecimal rawClose) {
        this.instrumentId = instrumentId;
        this.gameDate = gameDate;
        this.closePrice = closePrice;
        this.rawClose = rawClose;
    }

    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class Pk implements Serializable {
        private Long instrumentId;
        private LocalDate gameDate;
    }
}

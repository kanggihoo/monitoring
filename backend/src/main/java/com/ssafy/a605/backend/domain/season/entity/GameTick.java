package com.ssafy.a605.backend.domain.season.entity;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 종가 틱 하나 = 게임 거래일 하루. 모든 응답의 tickSeq / asOfTickSeq 가 이 값이며,
 * 틱 순번과 게임 거래일을 잇는 단일 진실이다.
 *
 * <p>tickSeq 는 시퀀스가 아니라 재생 편성이 정하는 값이라 애플리케이션이 직접 채운다.
 * 공개 위치는 월 시작 오프셋에 round(720초 x k / N) 을 더한 불변 오프셋이고,
 * 그래서 틱 간격은 31~36초 사이에서 달마다 달라진다.
 *
 * <p>환율을 같은 행에 둔다. 환율도 거래일당 1개 값이라 이 테이블과 grain 이 완전히 같고,
 * 나누면 시세 틱과 환율이 서로 다른 거래일 것일 수 있게 되어 원화 환산이 어긋난다.
 */
@Entity
@Table(name = "game_ticks",
        uniqueConstraints = @UniqueConstraint(name = "uk_game_ticks_game_date",
                columnNames = {"season_id", "game_date"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class GameTick {

    @Id
    @Column(name = "tick_seq", nullable = false)
    private Long tickSeq;

    @Column(name = "season_id", nullable = false)
    private Long seasonId;

    @Column(name = "season_day", nullable = false)
    private Short seasonDay;

    @Column(name = "game_date", nullable = false)
    private LocalDate gameDate;

    @Column(name = "cum_offset_sec", nullable = false)
    private Integer cumOffsetSec;

    // 일별 매매기준율. 한국·미국 휴장이 어긋나는 날은 직전 거래일 값을 이월 적재한다.
    @Column(name = "usd_krw", nullable = false, precision = 12, scale = 4)
    private BigDecimal usdKrw;

    /**
     * @param tickSeq   시즌 시작부터의 틱 순번. 1 씩 증가하며 클라이언트가 이 연속성으로
     *                  동기화 구멍을 감지한다
     * @param seasonId  소속 시즌
     * @param seasonDay 이 틱이 속한 일차
     * @param gameDate  이 틱이 확정하는 게임 거래일 (한국·미국 거래일의 합집합)
     * @param cumOffsetSec 거래 시작으로부터의 가상 초 오프셋
     * @param usdKrw    그 거래일의 원/달러 환율
     */
    public GameTick(Long tickSeq, Long seasonId, Short seasonDay, LocalDate gameDate,
                    Integer cumOffsetSec, BigDecimal usdKrw) {
        this.tickSeq = tickSeq;
        this.seasonId = seasonId;
        this.seasonDay = seasonDay;
        this.gameDate = gameDate;
        this.cumOffsetSec = cumOffsetSec;
        this.usdKrw = usdKrw;
    }
}

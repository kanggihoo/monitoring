package com.ssafy.a605.backend.domain.market.entity;

import java.time.LocalDate;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 거래 유니버스의 종목 하나. 상장폐지가 없어 퇴장 상태를 두지 않는다.
 *
 * <p><b>한 행은 한 시즌에 귀속된다.</b> 편성 종목 수는 시즌마다 다르고, 같은 종목이 다음
 * 시즌에 다시 나와도 별도 행·별도 식별자를 받는다. 그래서 심볼의 유일성도 시즌 안이다 (V40).
 */
@Entity
@Table(name = "instruments",
        uniqueConstraints = @UniqueConstraint(name = "uk_instruments_season_symbol",
                columnNames = {"season_id", "symbol"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Instrument {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 이 종목이 속한 시즌. 연관관계를 걸지 않고 식별자로 둔다 - 종목에서 시즌을 따라갈 일이
    // 없고, 애플리케이션은 설정된 한 시즌만 서비스한다 (app.replay.season-id).
    @Column(name = "season_id", nullable = false)
    private Long seasonId;

    @Column(nullable = false, length = 20)
    private String symbol;

    @Column(nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private InstrumentCategory category;

    @Enumerated(EnumType.STRING)
    @Column(name = "asset_type", nullable = false, length = 20)
    private AssetType assetType;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3)
    private Currency currency;

    // 종목마다 다르다. 전부 2자리로 통일하면 저가 코인의 등락이 화면에서 사라지고,
    // 전부 4자리로 통일하면 고가 주식이 의미 없는 0 을 달고 나온다.
    @Column(name = "price_scale", nullable = false)
    private Short priceScale;

    // IPO 종목의 상장 게임 거래일. 상시 종목은 null 이다.
    @Column(name = "listed_game_date")
    private LocalDate listedGameDate;

    @Column(nullable = false, length = 500)
    private String summary;

    // ETF·코인의 정보 카드. 자산 유형마다 형태가 달라 JSON 으로 둔다.
    // 주식은 null 이고 instrument_metrics 가 그 자리를 대신한다.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "info_card")
    private String infoCard;

    // 업종. 주식은 GICS 섹터, ETF 는 기초자산, 코인은 크립토 섹터다 - 자산 유형마다 축이
    // 다르므로 화면이 assetType 으로 라벨 세트를 고른다. category(탭 구분)와는 다른 값이다.
    @Column(length = 30)
    private String sector;

    /**
     * @param seasonId       이 종목이 속한 시즌
     * @param symbol         티커. 시세 수집 파이프라인과 맞추는 키
     * @param name           화면에 표시할 종목명
     * @param category       목록 탭 구분
     * @param assetType      자산 유형. 어느 정보 카드를 그릴지 결정한다
     * @param currency       결제 통화
     * @param priceScale     가격 소수 자릿수
     * @param listedGameDate IPO 종목의 상장일. 상시 종목은 null
     * @param summary        사업·상품·프로젝트 개요 1~2줄
     * @param infoCard       ETF·코인의 정보 카드 JSON. 주식은 null
     * @param sector         업종. 없으면 null
     */
    public Instrument(Long seasonId, String symbol, String name, InstrumentCategory category,
                      AssetType assetType, Currency currency, Short priceScale,
                      LocalDate listedGameDate, String summary, String infoCard, String sector) {
        this.seasonId = seasonId;
        this.symbol = symbol;
        this.name = name;
        this.category = category;
        this.assetType = assetType;
        this.currency = currency;
        this.priceScale = priceScale;
        this.listedGameDate = listedGameDate;
        this.summary = summary;
        this.infoCard = infoCard;
        this.sector = sector;
    }

    /**
     * 상장일부터 거래·노출한다 (기획서 5.2.2). 상시 종목은 상장일이 없어 언제나 참이다.
     *
     * @param gameDate 판정할 게임 거래일
     * @return 그날 상장된 상태인가
     */
    public boolean isListedOn(LocalDate gameDate) {
        return isListedOn(this.listedGameDate, gameDate);
    }

    /**
     * 같은 규칙을 종목 메타만 들고 있는 곳에서도 쓴다 — 시세 조회는 엔티티가 아니라 자기
     * 레코드로 종목 메타를 나르므로, 규칙을 그쪽에 다시 적으면 상장 필터가 두 곳에서 갈린다.
     *
     * @param listedGameDate IPO 종목의 상장 게임 거래일. 상시 종목은 {@code null}
     * @param gameDate       판정할 게임 거래일
     * @return 그날 상장된 상태인가
     */
    public static boolean isListedOn(LocalDate listedGameDate, LocalDate gameDate) {
        return listedGameDate == null || !gameDate.isBefore(listedGameDate);
    }
}

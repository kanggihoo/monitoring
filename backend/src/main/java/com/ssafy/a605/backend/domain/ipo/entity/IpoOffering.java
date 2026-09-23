package com.ssafy.a605.backend.domain.ipo.entity;

import java.math.BigDecimal;
import java.time.Instant;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.ssafy.a605.backend.domain.market.entity.Currency;

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
 * 공모 한 건. 시즌당 8건이며 게임 시각으로 12분 살고 끝난다.
 * 종목과 생명주기가 달라 별도 테이블이다 - 종목은 상장 후 시즌 끝까지 유니버스에 남지만
 * 공모는 상장과 함께 역할이 끝난다.
 */
@Entity
@Table(name = "ipo_offerings",
        uniqueConstraints = @UniqueConstraint(name = "uk_ipo_offerings_instrument",
                columnNames = "instrument_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class IpoOffering {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "season_id", nullable = false)
    private Long seasonId;

    @Column(name = "instrument_id", nullable = false)
    private Long instrumentId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3)
    private Currency currency;

    // 공모가. 실제 데이터를 쓴다. 당첨 시 이 값이 그대로 평단이 된다.
    @Column(name = "offer_price", nullable = false, precision = 20, scale = 8)
    private BigDecimal offerPrice;

    // 청약 단위 = 증거금 100%. 수량·금액 선택이 없어 고정값이다.
    @Column(name = "deposit_amount", nullable = false, precision = 20, scale = 4)
    private BigDecimal depositAmount;

    // 공시 틱. 이 이전에는 종목명조차 노출하지 않는다.
    @Column(name = "disclosure_tick_seq", nullable = false)
    private Long disclosureTickSeq;

    @Column(name = "close_tick_seq", nullable = false)
    private Long closeTickSeq;

    // 상장 틱. 배정 물량이 이 시점에 포지션으로 전환된다 - 추첨 시점이 아니다.
    @Column(name = "listing_tick_seq", nullable = false)
    private Long listingTickSeq;

    // 당첨 인원 산정 방식의 설명 문장. 비율이 편성값이라 숫자로 두고 프론트가 문장을
    // 조립하면 편성이 바뀔 때마다 프론트를 고쳐야 한다.
    @Column(name = "allocation_rule", nullable = false, length = 300)
    private String allocationRule;

    // 추첨 전에는 null. 전체 공지의 "N명 당첨"이 이 값이다.
    @Column(name = "winner_count")
    private Integer winnerCount;

    @Column(name = "drawn_at")
    private Instant drawnAt;


    /**
     * @param seasonId          소속 시즌
     * @param instrumentId      공모 대상 종목
     * @param currency          증거금·공모가의 통화
     * @param offerPrice        공모가
     * @param depositAmount     청약 단위이자 증거금
     * @param disclosureTickSeq 공시 틱
     * @param closeTickSeq      청약 마감 틱. 마감 즉시 추첨한다
     * @param listingTickSeq    상장 틱
     * @param allocationRule    당첨 인원 산정 방식의 설명 문장
     */
    public IpoOffering(Long seasonId, Long instrumentId, Currency currency, BigDecimal offerPrice,
                       BigDecimal depositAmount, Long disclosureTickSeq, Long closeTickSeq,
                       Long listingTickSeq, String allocationRule) {
        this.seasonId = seasonId;
        this.instrumentId = instrumentId;
        this.currency = currency;
        this.offerPrice = offerPrice;
        this.depositAmount = depositAmount;
        this.disclosureTickSeq = disclosureTickSeq;
        this.closeTickSeq = closeTickSeq;
        this.listingTickSeq = listingTickSeq;
        this.allocationRule = allocationRule;
    }

    /**
     * 추첨 결과만 지워 다시 청약·추첨 가능한 상태로 되돌린다 (개발·테스트 전용 시즌 초기화).
     *
     * <p><b>편성값은 손대지 않는다.</b> 공시·마감·상장 틱은 시즌 편성이고 초기화 대상이
     * 아니다. 결과만 남으면 마감 틱을 다시 지나도 재추첨이 건너뛰어진다.
     */
    public void rearm() {
        this.winnerCount = null;
        this.drawnAt = null;
    }

    /**
     * 이 공모가 그 틱에 공시됐는가. 공시 전에는 <b>목록에 아예 나타나지 않는다</b> —
     * 종목명 자체가 미래 정보다 (기획서 3.5.2).
     *
     * @param tickSeq 지금까지 공개된 틱
     * @return 공시됐으면 참
     */
    public boolean isDisclosedAt(long tickSeq) {
        return disclosureTickSeq <= tickSeq;
    }

    /**
     * 그 틱에서 본 국면. 저장하지 않고 매번 파생한다.
     *
     * <p><b>시각이 먼저다.</b> {@code ipo.md} 의 국면 표대로 상장 틱을 지나면 {@code LISTED} 이며,
     * 추첨 여부는 그 앞의 두 값({@code DRAWING} · {@code ANNOUNCED})만 가른다.
     *
     * @param tickSeq 지금까지 공개된 틱
     * @return 그 시점의 국면
     */
    public IpoOfferingStatus statusAt(long tickSeq) {
        if (tickSeq < closeTickSeq) {
            return IpoOfferingStatus.OPEN;
        }
        if (tickSeq >= listingTickSeq) {
            return IpoOfferingStatus.LISTED;
        }
        return drawnAt == null ? IpoOfferingStatus.DRAWING : IpoOfferingStatus.ANNOUNCED;
    }

    /**
     * 추첨을 확정한다. 당첨자 수와 시각이 함께 남아야 {@link #statusAt} 이 {@code ANNOUNCED} 로
     * 넘어간다.
     *
     * @param winners 확정된 당첨자 수. 청약자가 없었으면 0
     * @param at      추첨 실제 시각
     * @throws IllegalStateException 이미 추첨한 공모면
     */
    public void draw(int winners, Instant at) {
        if (drawnAt != null) {
            throw new IllegalStateException(
                    "이미 추첨한 공모를 다시 추첨할 수 없습니다: offering=%d, drawnAt=%s"
                            .formatted(id, drawnAt));
        }
        this.winnerCount = winners;
        this.drawnAt = at;
    }

}

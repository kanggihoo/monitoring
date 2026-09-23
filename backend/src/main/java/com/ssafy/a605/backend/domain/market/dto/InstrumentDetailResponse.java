package com.ssafy.a605.backend.domain.market.dto;

import java.time.LocalDate;

import com.ssafy.a605.backend.domain.market.entity.AssetType;
import com.ssafy.a605.backend.domain.market.entity.Currency;
import com.ssafy.a605.backend.domain.market.entity.Instrument;
import com.ssafy.a605.backend.domain.market.entity.InstrumentCategory;
import com.ssafy.a605.backend.domain.market.entity.ListingStatus;

/**
 * 종목 메타 상세 (기획서 5.2, {@code unv.md}). 목록 항목의 모든 필드 + 사업 개요다.
 *
 * <p><b>목록 항목과 같은 필드를 다시 적었다.</b> 목록 항목을 그대로 품으면 응답이 한 겹
 * 깊어지는데 계약은 평평한 객체를 약속한다. 반대로 목록 항목에 개요를 넣고 목록에서 비우면
 * 목록의 28행마다 {@code "summary": null} 이 실린다.
 *
 * <p>재무 지표·신호등·상품 구조·프로젝트 개요는 여기 없다 — {@code info.md} 의 정보 카드가
 * 그것을 담당한다. {@code summary} 는 그 카드와 <b>같은 값</b>이다.
 *
 * @param instrumentId   종목 식별자
 * @param name           종목명
 * @param category       목록 탭 구분
 * @param assetType      자산 유형
 * @param currency       결제 통화
 * @param priceScale     가격 소수 자릿수
 * @param listingStatus  그 시점의 노출 상태
 * @param listedGameDate 상장 예정일. {@code UPCOMING} 일 때만 값이 있다
 * @param isNew          신규 배지. 상장된 게임 거래일 하루 동안만 참이다
 * @param watched        요청자의 관심종목 포함 여부
 * @param sector         업종. 목록 항목과 같은 값이다
 * @param summary        사업·상품·프로젝트 개요 1~2줄
 */
public record InstrumentDetailResponse(
        Long instrumentId,
        String name,
        InstrumentCategory category,
        AssetType assetType,
        Currency currency,
        Short priceScale,
        ListingStatus listingStatus,
        LocalDate listedGameDate,
        boolean isNew,
        boolean watched,
        String sector,
        String summary
) {

    /**
     * @param instrument   종목 메타
     * @param status       공개 기준 시점에 파생한 노출 상태
     * @param asOfGameDate 공개 기준 게임 거래일. 최초 공개 전에는 {@code null}
     * @param watched      요청자의 관심종목 포함 여부
     * @return 종목 메타 상세
     */
    public static InstrumentDetailResponse of(Instrument instrument, ListingStatus status,
                                              LocalDate asOfGameDate, boolean watched) {
        InstrumentListResponse.Item item =
                InstrumentListResponse.Item.of(instrument, status, asOfGameDate, watched);
        return new InstrumentDetailResponse(item.instrumentId(), item.name(), item.category(),
                item.assetType(), item.currency(), item.priceScale(), item.listingStatus(),
                item.listedGameDate(), item.isNew(), item.watched(), item.sector(),
                instrument.getSummary());
    }
}

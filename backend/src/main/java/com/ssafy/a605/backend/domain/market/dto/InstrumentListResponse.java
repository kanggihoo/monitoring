package com.ssafy.a605.backend.domain.market.dto;

import java.time.LocalDate;
import java.util.List;

import com.ssafy.a605.backend.domain.market.entity.AssetType;
import com.ssafy.a605.backend.domain.market.entity.Currency;
import com.ssafy.a605.backend.domain.market.entity.Instrument;
import com.ssafy.a605.backend.domain.market.entity.InstrumentCategory;
import com.ssafy.a605.backend.domain.market.entity.ListingStatus;

/**
 * 종목 목록 (기획서 5.1 · 5.2, {@code unv.md}).
 *
 * <p><b>메타만 담고 시세를 담지 않는다.</b> 메타는 IPO 상장 때만 바뀌고 시세는 매 틱(31~36초)
 * 바뀌므로, 합치면 틱마다 정적인 메타까지 다시 보낸다. 프론트가 {@code instrumentId} 로
 * 조합한다.
 *
 * <p><b>정렬하지 않는다</b>(계약이 약속하지 않는다) — 등락률·가격·이름 정렬은 시세를 이미 쥔
 * 프론트가 서버 재조회 없이 한다. 다만 식별자 순으로 고정해 응답이 호출마다 흔들리지 않게
 * 한다. 페이지도 없다: 28종이라 한 화면이 곧 시장 전체 조망이다.
 *
 * @param asOfGameDate 노출 상태를 판정한 게임 거래일. 최초 공개 전에는 {@code null}
 * @param instruments  그 시점에 노출되는 종목. 공시 전 공모 종목은 포함되지 않는다
 */
public record InstrumentListResponse(LocalDate asOfGameDate, List<Item> instruments) {

    /**
     * 종목 한 줄.
     *
     * @param instrumentId    종목 식별자
     * @param name            종목명
     * @param category        목록 탭 구분
     * @param assetType       자산 유형. 정보 카드 종류를 결정한다. <b>배수(2X·3X)는 담지 않는다</b>
     * @param currency        결제 통화
     * @param priceScale      가격 소수 자릿수
     * @param listingStatus   그 시점의 노출 상태
     * @param listedGameDate  상장 예정일. <b>{@code UPCOMING} 일 때만 값이 있다</b> — 상장 후에는
     *                        조회 필요성이 없다
     * @param isNew           신규 배지. <b>상장된 게임 거래일 하루 동안만 참</b>이다
     * @param watched         요청자의 관심종목 포함 여부
     * @param sector          업종. 주식은 GICS 섹터, ETF 는 기초자산, 코인은 크립토 섹터다 —
     *                        자산 유형마다 축이 달라 화면이 {@code assetType} 으로 읽는 법을
     *                        정한다. {@code category}(탭 구분)와는 다른 값이며, 편성에 업종이
     *                        없는 시즌은 {@code null} 이다
     */
    public record Item(
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
            String sector
    ) {

        /**
         * @param instrument   종목 메타
         * @param status       공개 기준 시점에 파생한 노출 상태
         * @param asOfGameDate 공개 기준 게임 거래일. 최초 공개 전에는 {@code null}
         * @param watched      요청자의 관심종목 포함 여부
         * @return 종목 한 줄
         */
        public static Item of(Instrument instrument, ListingStatus status, LocalDate asOfGameDate,
                              boolean watched) {
            return new Item(instrument.getId(), instrument.getName(), instrument.getCategory(),
                    instrument.getAssetType(), instrument.getCurrency(), instrument.getPriceScale(),
                    status,
                    status == ListingStatus.UPCOMING ? instrument.getListedGameDate() : null,
                    asOfGameDate != null && asOfGameDate.equals(instrument.getListedGameDate()),
                    watched, instrument.getSector());
        }
    }
}

package com.ssafy.a605.backend.domain.market.entity;

/**
 * 자산 유형. 종목 상세가 어느 정보 카드를 그릴지 결정한다 (기획서 9장).
 * 인버스 상품의 배수(2X·3X)는 담지 않는다 — 그 값은 상품 구조 카드의 몫이다.
 */
public enum AssetType {
    STOCK,
    ETF,
    INVERSE_ETF,
    IPO_STOCK,
    CRYPTO,
    /**
     * 지수 대리 종목. 시즌 리포트의 비교 기준선과 AI 일일 리포트의 시장 수익률이 쓰는 종가의
     * 출처이며, <b>목록·시세·차트·주문·관심 종목 어디에도 나오지 않는다</b>.
     *
     * <p>종목으로 두는 이유는 기준선 수익률이 결국 {@code daily_closes} 의 두 종가 차이이고
     * 그 표가 {@code instrument_id} 를 키로 쓰기 때문이다. 지수만 담는 표를 따로 만들면
     * 적재·조회 경로가 한 벌 더 생기는데 얻는 것은 "목록에 안 보인다" 하나뿐이다.
     */
    INDEX;

    /**
     * 화면에 내보내고 거래시킬 유형인가. {@code INDEX} 만 거짓이다.
     *
     * <p>판정을 한 곳에 둔다 - 목록·상세·시세·차트·주문·관심 종목이 같은 질문을 하는데,
     * 각자 {@code != INDEX} 를 적으면 유형이 하나 더 생기는 날 여섯 곳이 갈린다.
     *
     * @return 사용자에게 보이고 거래되는 종목인가
     */
    public boolean isTradable() {
        return this != INDEX;
    }

    /**
     * 이 유형이 그리는 정보 카드 ({@code info.md}). 주식·공모주는 재무 지표와 신호등,
     * ETF·인버스는 상품 구조, 코인은 프로젝트 개요다.
     *
     * @return 카드 종류
     */
    public CardType cardType() {
        return switch (this) {
            case STOCK, IPO_STOCK -> CardType.STOCK;
            case ETF, INVERSE_ETF -> CardType.PRODUCT;
            case CRYPTO -> CardType.PROJECT;
            // 상세 화면에 닿지 않으므로 카드가 없다. 여기까지 왔다면 노출 필터가 뚫린 것이다.
            case INDEX -> throw new IllegalStateException("지수 종목은 정보 카드를 갖지 않는다");
        };
    }
}

package com.ssafy.a605.backend.domain.market.entity;

/**
 * 정보 카드 종류 (기획서 9장, {@code info.md}). 저장하지 않고 {@link AssetType} 에서 파생한다.
 *
 * <p><b>이 값이 응답의 세 카드 필드 중 어느 것이 채워지는지를 결정론적으로 말해 준다.</b>
 * 자산 유형마다 경로를 나누지 않은 이유는 딥링크·새로고침으로 종목 상세에 바로 들어오면
 * 프론트가 {@code assetType} 을 모르기 때문이다.
 */
public enum CardType {
    STOCK,
    PRODUCT,
    PROJECT
}

package com.ssafy.a605.backend.domain.market.entity;

/**
 * 종목의 시점별 노출 상태 (기획서 5.2.2, {@code unv.md}). 저장하지 않고 공개 기준 게임
 * 거래일과 상장일을 비교해 파생한다.
 *
 * <p>상장폐지가 유니버스에 없으므로 퇴장 상태를 두지 않는다. 공시 전 공모 종목은 이 두 값 중
 * 어느 것도 아니라 <b>응답에 아예 포함되지 않는다</b> — 종목명 자체가 미래 정보다.
 */
public enum ListingStatus {
    UPCOMING,
    LISTED
}

package com.ssafy.a605.backend.domain.report.dto;

import java.math.BigDecimal;

/**
 * 자동 태깅 한 건 ({@code rpt.md} §patternTags 의 판정 규칙, 기획서 13.1.4).
 *
 * <p><b>판정 결과를 JSON 으로 굳혀 저장한다.</b> 판정 기준(-10%)이 나중에 바뀌어도 이미 발행된
 * 과거 일차의 태그가 소급 변경되지 않아야 하기 때문이다 ({@code rpt.md}).
 *
 * @param kind         태그 종류. 아래 네 상수 중 하나다
 * @param executionId  태그가 붙은 체결
 * @param instrumentId 그 체결의 종목. 리포트가 종목명을 붙이는 데 쓴다
 * @param changeRate   체결가 대비 변화율(%). 판정에 쓴 값 그대로다
 */
public record PatternTag(String kind, Long executionId, Long instrumentId, BigDecimal changeRate) {

    /** 매수 후 가격이 빠졌다 — 고점 매수. */
    public static final String PANIC_BUY_HIGH = "PANIC_BUY_HIGH";

    /** 매도 후 가격이 올랐다 — 저점 매도. */
    public static final String PANIC_SELL_LOW = "PANIC_SELL_LOW";

    /** 매수 후 가격이 올랐다 — 저점 매수. */
    public static final String LUCKY_BUY_LOW = "LUCKY_BUY_LOW";

    /** 매도 후 가격이 빠졌다 — 고점 매도. */
    public static final String LUCKY_SELL_HIGH = "LUCKY_SELL_HIGH";
}

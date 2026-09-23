package com.ssafy.a605.backend.domain.report.dto.ai;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * AI 에 보내는 일일 리포트 입력 (전처리 요청서 v5 §4 · §5).
 *
 * <p><b>체결 원본을 보내지 않는다.</b> 계좌 하나에 100 건을 보내도 문장에 닿는 것은 3~8 건이고,
 * 그것이 무엇인지는 <b>폐장 집계가 이미 판정했다</b> — 태그와 최고·최악 거래가 그것이다. 그래서
 * 백엔드가 추린 것만 보낸다 (v4 의 23KB → 350~650B).
 *
 * <p><b>비율이 아니라 건수로 보내는 것들이 있다.</b> 승률·집중도의 임계값은 베타에서 계속 만질
 * 값이라 AI 가 갖는다 — 백엔드가 나눠 보내면 그 분모의 정의가 배포에 굳는다.
 *
 * @param seasonId    시즌
 * @param seasonDay   그 일차
 * @param indexReturn 지수 대리 종목의 그 일차 구간 수익률(%). 시장 대비 위치를 가린다
 * @param accounts    계좌별 하루. 그 일차 정산에 든 계좌 전부다
 */
public record AiDailyReportRequest(long seasonId, short seasonDay, String indexReturn,
                                   List<Account> accounts) {

    /**
     * @param accountId      계좌
     * @param dailyReturn    당일 수익률(%)
     * @param executionCount 당일 체결 건수
     * @param patternTags    태그가 붙은 거래. 최대 6 건이며 <b>거래가 없으면 빈 배열</b>이다 —
     *                       그 자체가 관망이라는 뜻이다
     * @param metrics        계좌 요약. 숫자 줄을 여는 값들이다
     * @param highlights     최고·최악 거래. 매도가 없는 날은 빈 배열
     * @param previousBlockIds <b>앞선 일차에</b> 이 계좌가 받은 문구 블록 전부. AI 가 후보에서
     *                       빼 같은 문장이 두 번 나오지 않게 한다. 직전 일차만 빼면 1 일차와
     *                       3 일차가 다시 겹친다. <b>1 일차이거나 앞선 문구가 없으면 빈
     *                       배열</b>이고, 그때 AI 는 빼지 않고 그냥 고른다
     */
    public record Account(long accountId, String dailyReturn, int executionCount,
                          List<PatternTag> patternTags, Metrics metrics,
                          List<Highlight> highlights, List<String> previousBlockIds,
                          Cumulative cumulative) {
    }

    /**
     * 그 일차까지의 누적 집계 (AI 업무요청서 2026-09-18 요청 ②). 결과지를 만드는 재료다.
     *
     * <p><b>시즌 요청의 계좌에서 {@code accountId} 만 뺀 모양</b>이고, 값의 구간만 그 일차까지로
     * 좁는다. 낱개 필드가 아니라 덩이로 보내는 이유가 이것이다 — AI 쪽 두 경로가 같은 어댑터를
     * 지나 "3일차에만 맞는 값"이 생길 자리가 없다.
     *
     * <p><b>1일차는 1일치, 3일차는 시즌 전체와 같은 값이다.</b> 셈은 {@code TradeCounts} 가 하고
     * 구간만 달라지므로 새 계산 로직이 없다.
     *
     * <p>이 덩이를 싣지 않으면 AI 가 결과지를 만들지 않고 응답의 {@code review} 가 비어 온다.
     * 그때도 머리말과 장면 별칭은 나오므로 화면은 결과지 카드만 비운 채 열린다.
     *
     * @param maxDrawdown  그 일차 폐장 시점의 누적 최대 낙폭(%).
     *                     <b>{@code accounts.max_drawdown} 을 쓰면 안 된다</b> — 그 값은 시즌
     *                     누적이라 1·2일차에 3일차 값을 보내게 된다. 폐장에 찍어 둔
     *                     {@code daily_settlements.max_drawdown} 을 읽는다 (V38).
     *                     <b>스냅샷이 없는 일차는 {@code null}</b> 이고, 그때 AI 는 손실 감내
     *                     항목을 "못 쟀다"로 둔다
     * @param turnover     누적 거래대금 ÷ <b>일차별 평균 자산의 합</b>. 일차 경계마다 자산이
     *                     시드로 돌아가므로 분모를 그날 자산 하나로 두면 날이 갈수록 값이
     *                     부풀고, 그것은 거래를 더 해서가 아니다
     * @param finalRank    그 일차까지의 <b>포인트 순위</b> ({@code S15P21A605-250}).
     *                     {@code daily_settlements.season_rank} 를 쓰지 않는다 — 일차 초기화
     *                     이후 그 값은 누적이 아니라 그날 순위와 같다
     * @param totalParticipants 포인트 순위에 든 계좌 수. 위 순위의 분모다
     * @param returnRate   <b>일간 수익률의 단순 합</b>(%). 복리 누적이 아니다 — 100 % 뒤
     *                     -50 % 면 이 값은 50 % 지만 실제로는 0 % 다. 화면의 포인트 탭이 같은
     *                     값을 같은 이름으로 내보내므로 정의가 한 벌이다
     * @param selfAnswers  설문 답 원본 다섯 글자. 답하지 않았으면 {@code null}
     * @param lossTolerancePct 설문이 선언한 감내 손실률. 지금은 답 원본에서 화면이 내므로
     *                     서버가 보낼 값이 없다 — 자리만 두고 {@code null} 이다
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Cumulative(int buyCount, int sellCount, int fomoBuyCount,
                             int bottomSnipeBuyCount, int peakSellWinCount,
                             int panicBottomSellCount, int quickProfitCount, int quickStopCount,
                             String krwCash, String totalCashKrw, String totalValuationKrw,
                             int holdingDaysSum, int closedPositionCount, int winCount,
                             String maxDrawdown, String turnover, String totalFeeKrw,
                             int finalRank, long totalParticipants, String returnRate,
                             String selfAnswers, Integer lossTolerancePct) {
    }

    /**
     * 태그가 붙은 거래 한 건. 판정은 <b>백엔드만</b> 한다 — 같은 체결에 화면의 배지와 AI 문장이
     * 다른 태그를 달면 안 되므로 기준이 한 곳에만 있어야 한다 (요청서 v5 §1).
     *
     * @param kind           태그 종류. 백엔드 이름 그대로 보낸다
     * @param changeRate     체결가 대비 변화율(%). "13.2% 빠졌습니다" 가 이 값이다
     * @param instrumentName 종목명
     * @param side           그 체결의 방향
     * @param gameMonth      그 체결의 게임 연월 ({@code YYYY.MM}). 문장에 그대로 실린다
     */
    public record PatternTag(String kind, String changeRate, String instrumentName, String side,
                             String gameMonth) {
    }

    /**
     * 계좌의 그날 요약.
     *
     * @param buyCount              매수 건수
     * @param sellCount             매도 건수. 승률의 분모다
     * @param winCount              실현 손익이 <b>양수인 매도</b> 건수
     * @param realizedPnlKrw        실현 손익 합계(원화)
     * @param totalFeeKrw           거래 수수료 합계(원화)
     * @param avgHoldingDays        청산 포지션의 평균 보유 게임 거래일. 짝지을 매수가 그 일차에
     *                              없으면 셈에서 빠지고, 하나도 없으면 {@code null}
     * @param quickSellCount        보유 20 거래일 이하의 매도 건수
     * @param tradedAmountKrw       총 거래대금(원화)
     * @param topInstrumentName     거래액이 가장 큰 종목. 거래가 없으면 {@code null}
     * @param topInstrumentShareKrw 그 종목이 차지한 거래액 비중(0~1). 같은 이유로 {@code null}
     * @param executionCountRank    그 일차 체결 건수 순위. 1 이 가장 많이 거래한 사람이다
     * @param participantCount      그 일차 정산 대상 계좌 수. 위 순위의 분모다
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Metrics(int buyCount, int sellCount, int winCount, String realizedPnlKrw,
                          String totalFeeKrw, Integer avgHoldingDays, int quickSellCount,
                          String tradedAmountKrw, String topInstrumentName,
                          String topInstrumentShareKrw, int executionCountRank,
                          int participantCount) {
    }

    /**
     * 최고·최악 거래. 폐장 집계가 고른 체결을 문장에 쓸 수 있는 형태로 펼친 것이다.
     *
     * <p>매도가 한 건인 날은 둘이 같은 체결을 가리킬 수 있다 — AI 가 중복을 걸러낸다.
     *
     * @param role           {@code BEST} 또는 {@code WORST}
     * @param instrumentName 종목명
     * @param side           그 체결의 방향
     * @param gameMonth      게임 연월 ({@code YYYY.MM})
     * @param realizedPnlKrw 그 체결의 실현 손익(원화)
     * @param returnRate     취득 원가 대비 수익률(%). 짝지을 매수가 그 일차에 없으면 {@code null}
     * @param holdingDays    짝지은 매수까지의 게임 거래일 수. 같은 이유로 {@code null} 일 수 있다
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Highlight(String role, String instrumentName, String side, String gameMonth,
                            String realizedPnlKrw, String returnRate, Integer holdingDays) {
    }
}

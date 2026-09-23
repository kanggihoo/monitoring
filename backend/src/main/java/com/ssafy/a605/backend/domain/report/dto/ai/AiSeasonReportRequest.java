package com.ssafy.a605.backend.domain.report.dto.ai;

import java.util.List;

/**
 * AI 에 보내는 시즌 리포트 입력 (전처리 요청서 v5 §7).
 *
 * <p><b>처음부터 집계만 보낸다.</b> 3일치 체결을 그대로 보내면 5MB 이고 AI 는 비율만 본다 —
 * 계좌당 약 400B 다.
 *
 * <p><b>비율이 아니라 건수와 금액이다.</b> {@code GROUP BY} + {@code COUNT} 가 SQL 이 자연스럽게
 * 내는 모양이고, 비율을 보내면 그 분모의 정의가 백엔드 배포에 굳는다 — 유형 판정의 임계값은
 * 베타에서 계속 만질 값이라 AI 가 갖는다.
 *
 * <p><b>계좌를 나눠 보내도 된다.</b> 판정이 고정 참조 사분위 표를 쓰도록 바뀌어서, 요청에 몇
 * 명이 들어 있든 같은 계좌는 같은 점수를 받는다 (AI 업무요청서 2026-09-18 §7-1). 예전에는
 * 배치 안의 분포로 백분위를 냈으므로 전원이 한 요청에 있어야 했다 — 지금은 한 번에 보내는 것이
 * 호출을 줄이려는 선택일 뿐 제약이 아니다.
 *
 * @param seasonId 시즌
 * @param accounts 3일차 확정에 든 계좌 전부
 */
public record AiSeasonReportRequest(long seasonId, List<Account> accounts) {

    /**
     * 한 계좌의 시즌 (요청서 v5 §7.1 · §7.2).
     *
     * @param accountId            계좌
     * @param buyCount             시즌 매수 체결 건수
     * @param sellCount            시즌 매도 체결 건수. 승률의 분모다
     * @param fomoBuyCount         상대 가격 위치 R ≥ 0.85 인 매수 (창 안 고점 근처 매수)
     * @param bottomSnipeBuyCount  R ≤ 0.15 인 매수 (창 안 저점 근처 매수)
     * @param peakSellWinCount     R ≥ 0.85 이고 실현 손익이 양수인 매도 (고점 근처 익절)
     * @param panicBottomSellCount R ≤ 0.15 이고 수익률 ≤ -5% 인 매도 (저점 근처 손절)
     * @param quickProfitCount     보유 20 거래일 이하이고 수익률 +1%~+3% 인 매도
     * @param quickStopCount       보유 20 거래일 이하이고 수익률 -5%~-3% 인 매도
     * @param krwCash              시즌 종료 시점의 원화 현금 (잠긴 몫 포함). 관망형 판정에 쓴다
     * @param totalCashKrw         원화 현금 + 달러 현금을 폐장 틱 환율로 환산한 합. 투입 비중을
     *                             {@code (총평가액 − 현금) / 총평가액} 으로 재는데, 달러를 빼면
     *                             <b>달러를 들고 관망한 사람이 "현금을 남기지 않았다"로 잡혀</b>
     *                             실제보다 공격적인 단계로 올라간다
     * @param totalValuationKrw    시즌 종료 시점의 총평가액
     * @param holdingDaysSum       청산 포지션의 보유 거래일 합
     * @param closedPositionCount  그 청산 건수. 평균 보유 일수를 이 둘로 낸다
     * @param winCount             실현 손익이 양수인 매도 건수
     * @param maxDrawdown          최대 낙폭(%)
     * @param turnover             회전율
     * @param totalFeeKrw          시즌 총 거래 수수료 (원화 환산)
     * @param finalRank            최종 순위
     * @param totalParticipants    이 요청에 담긴 계좌 수. AI 의 백분위 기준선과 같은 분모다
     * @param returnRate           화면에 그대로 실리는 최종 수익률 문자열 (예: {@code +38.2%})
     */
    public record Account(long accountId, int buyCount, int sellCount, int fomoBuyCount,
                          int bottomSnipeBuyCount, int peakSellWinCount, int panicBottomSellCount,
                          int quickProfitCount, int quickStopCount, String krwCash,
                          String totalCashKrw, String totalValuationKrw, int holdingDaysSum,
                          int closedPositionCount, int winCount, String maxDrawdown,
                          String turnover, String totalFeeKrw, int finalRank,
                          long totalParticipants, String returnRate) {

        /**
         * 참가자 수만 채운 사본.
         *
         * <p>조립을 다 마쳐야 <b>몇 명을 실제로 보내는지</b> 알 수 있다 — 시즌 확정이 없는
         * 계좌는 도중에 빠지기 때문이다. 그래서 계좌를 만들 때는 자리를 비워 두고 마지막에
         * 한 번에 채운다.
         *
         * @param participants 이 요청에 실제로 담긴 계좌 수
         * @return 그 수를 담은 사본
         */
        public Account withParticipants(long participants) {
            return new Account(accountId, buyCount, sellCount, fomoBuyCount,
                    bottomSnipeBuyCount, peakSellWinCount, panicBottomSellCount,
                    quickProfitCount, quickStopCount, krwCash, totalCashKrw, totalValuationKrw,
                    holdingDaysSum, closedPositionCount, winCount, maxDrawdown, turnover,
                    totalFeeKrw, finalRank, participants, returnRate);
        }
    }
}

package com.ssafy.a605.backend.domain.report.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import com.ssafy.a605.backend.domain.market.entity.Currency;
import com.ssafy.a605.backend.domain.order.entity.OrderSide;

/**
 * 일일 복기 ({@code rpt.md} §GET /api/v1/reports/daily/{seasonDay}, 기획서 13.1).
 *
 * <p><b>저장값과 파생값이 한 응답에 섞여 있다.</b> {@code summary}·{@code bestTrade}·
 * {@code worstTrade}·{@code patternTags} 는 폐장에 얼린 {@code daily_settlements} 의 값이고,
 * {@code instruments} 는 조회 시점에 종가와 체결로 만든다. 앞의 것은 재집계하면 값이 달라지고
 * ({@code _common.md} §6.3) 뒤의 것은 분할 조정 기준이 바뀌면 함께 바뀐다.
 *
 * <p><b>차트와 마커를 함께 담는다.</b> 기획서 13.1.2 가 "당일 가격 흐름 위에 매수·매도 지점을
 * 마커로 표시"를 요구한다. 시계열을 따로 호출해 조합할 수도 있지만 복기의 구간은 그 일차뿐이라
 * 범위가 다르고, 종목이 5~10 개면 왕복이 그만큼 늘어난다.
 *
 * @param seasonDay     일차
 * @param fromGameMonth 그 일차가 재생한 구간의 시작 월
 * @param toGameMonth   재생 구간의 끝 월
 * @param finalizedAt   폐장 스냅샷 시각
 * @param summary       당일 요약 지표 (기획서 13.1.3)
 * @param bestTrade     가장 잘한 거래. 매도가 없는 날은 {@code null}
 * @param worstTrade    가장 아쉬운 거래. 매도가 없는 날은 {@code null}
 * @param instruments   내가 그 일차에 거래한 종목만. 거래가 없으면 빈 목록
 * @param patternTags   자동 태깅 결과. 없으면 빈 목록
 * @param commentary    AI 가 만든 그날의 문구. <b>생성 전이거나 실패했으면 {@code null}</b>
 * @param review        복기 결과지 (요청서 2026-09-18 ③). <b>{@code null} 인 경우가 셋</b>
 *                      이다 — 문구 생성 전, 일일 요청에 누적 집계를 싣지 않았을 때, 그리고
 *                      결과지만 저장에서 걸러졌을 때. 화면은 그 카드만 비우고 나머지 복기를 연다
 * @param closing       그 일차를 끝낸 자산 (V42). 이 컬럼이 생기기 전에 확정된 일차는
 *                      구성이 없어 {@code null} 이다
 * @param closingHoldings 그 일차를 들고 끝낸 종목 (V42). 다음 일차 개장에 보유가 초기화되므로
 *                      <b>이 기록이 없으면 무엇을 들고 끝냈는지 알 방법이 없다</b> — 체결
 *                      마커로 유추할 수는 있지만 IPO 배정처럼 체결로 잡히지 않는 경로가 있다.
 *                      아무것도 들고 있지 않았으면 빈 목록이다
 * @param regret        후회 연출 (기획서 13.2, 선택). 필드만 예약해 두고 항상 {@code null} 이다
 */
public record DailyReportResponse(
        short seasonDay,
        String fromGameMonth,
        String toGameMonth,
        Instant finalizedAt,
        Summary summary,
        Trade bestTrade,
        Trade worstTrade,
        List<InstrumentReplay> instruments,
        List<PatternTag> patternTags,
        Closing closing,
        List<ClosingHolding> closingHoldings,
        Commentary commentary,
        Review review,
        Void regret
) {

    /**
     * 그 일차를 끝낸 자산 (V42).
     *
     * <p><b>합계는 수익률과 같은 사실이다.</b> 일차 초기화 이후 모두 시드에서 출발하므로
     * {@code totalValuationKrw = 시드 x (1 + dailyReturn)} 이다. 그래도 싣는 이유는 둘이다 —
     * 저장된 수익률이 소수 둘째 자리라 화면에서 역산하면 최대 5,000 원 어긋나고, 아래
     * 구성의 분모가 필요하다.
     *
     * <p><b>새로운 것은 구성이다.</b> 같은 +11.82 % 라도 현금을 잔뜩 들고 낸 것과 전부
     * 밀어 넣어 낸 것은 다른 이야기인데, 수익률만으로는 그 둘이 구별되지 않는다.
     *
     * <p><b>합계와 구성의 시점이 다를 수 있다.</b> 합계는 그 일차 <b>마지막 틱</b>의 순위
     * 스냅샷이고 현금·보유는 <b>폐장 시점</b>이다. 마지막 틱은 폐장보다 31 분 앞서 끝나므로
     * 그 사이의 거래가 둘을 갈라놓는다 — 화면은 합계를 앵커로 쓰고 구성은 내역으로 읽는다.
     *
     * @param totalValuationKrw 확정 총평가액. 순위와 수익률의 근거다
     * @param krwCash           마감 원화 현금 (잠긴 몫 포함)
     * @param usdCash           마감 달러 현금 (잠긴 몫 포함)
     * @param usdKrw            마감 환율. 달러 현금을 원화로 읽는 데 쓴다
     * @param holdingsValuationKrw 마감 보유의 원화 평가액 합
     * @param asOfGameDate      평가에 쓴 게임 거래일. 왜 이 금액인지의 근거다
     * @param dailyRank         그 일차 일간 순위
     * @param participantCount  그 일차 정산 대상 계좌 수. 위 순위의 분모다
     * @param maxDrawdown       그 일차 최대 낙폭(%). 스냅샷이 없는 일차는 {@code null}
     * @param resetsNextDay     다음 일차 개장에 초기화되는가. 마지막 일차는 거짓이며,
     *                          화면이 "다음 날 다시 시작" 과 "시즌 종료" 를 가른다
     */
    public record Closing(String totalValuationKrw, String krwCash, String usdCash, String usdKrw,
                          String holdingsValuationKrw, LocalDate asOfGameDate,
                          Integer dailyRank, long participantCount, BigDecimal maxDrawdown,
                          boolean resetsNextDay) {
    }

    /**
     * 그 일차 폐장 시점의 보유 한 줄 (V42).
     *
     * <p><b>평가손익을 담지 않는다.</b> 스냅샷이 남긴 것은 취득 총액(결제 통화)과 원화
     * 평가액 둘뿐이고, 통화가 다르면 그 둘을 그대로 뺄 수 없다. 되살리려면 그 일차 마감
     * 환율로 되나눠야 하는데, 그것은 저장된 값을 다시 계산하는 일이라 스냅샷의 뜻과 어긋난다.
     *
     * <p>종목 이름은 싣지 않는다 — 화면이 {@code instrumentId} 로 유니버스에서 찾는다
     * ({@link InstrumentReplay} 와 같다).
     *
     * @param instrumentId 종목 식별자
     * @param currency     결제 통화. {@code totalCost} 와 {@code avgPrice} 의 통화다
     * @param priceScale   가격의 표현 자릿수
     * @param quantity     마감 보유 수량. 1 이상이다
     * @param totalCost    취득 총액 (결제 통화)
     * @param avgPrice     평단 = 취득 총액 ÷ 수량 (결제 통화). 저장하지 않고 여기서 나눈다
     * @param valuationKrw 그 일차 마지막 공개 틱의 종가·환율로 평가한 원화 금액
     */
    public record ClosingHolding(Long instrumentId, Currency currency, short priceScale,
                                 Integer quantity, String totalCost, String avgPrice,
                                 String valuationKrw) {
    }

    /**
     * AI 가 만든 그날의 문구 (AI 전처리 요청서 v5 §6).
     *
     * <p><b>없을 수 있다.</b> 문구는 폐장 뒤 외부 호출이 성공해야 생기므로, 실패하면 이
     * 자리가 {@code null} 이고 화면은 그 영역을 비운 채 리포트를 연다 — 문구가 없다고
     * 복기를 막으면 AI 한 번의 장애가 게임의 마무리를 통째로 가린다.
     *
     * <p><b>{@code blockIds} 는 내보내지 않는다.</b> 다음 일차에 같은 변주를 피하려고
     * 저장하는 내부 값이라 화면이 쓸 일이 없다.
     *
     * @param headline     머리말 한 줄
     * @param sceneAliases 결정적 장면의 별칭 0~3개. 거래가 없으면 빈 배열이며, 그 자체가
     *                     "조용한 하루" 라는 뜻이다
     */
    public record Commentary(String headline, List<String> sceneAliases) {
    }

    /**
     * 당일 요약 지표 (기획서 13.1.3).
     *
     * <p>기획서 문장은 네 항목이지만 실현 손익 합계를 함께 낸다 — 같은 문장이 상·하위 거래를
     * "실현 손익 기준"으로 정의하므로 그 값은 이미 계산되며, 없으면 화면에 그날 실현 손익
     * 총액이 빠진다 ({@code spec-revisions.md} R-11).
     *
     * @param dailyReturn     확정 일간 수익률(%)
     * @param executionCount  당일 체결 건수
     * @param totalFeeKrw     당일 거래 수수료 합계 (원화 환산)
     * @param realizedPnlKrw  당일 실현 손익 합계 (원화 환산)
     */
    public record Summary(BigDecimal dailyReturn, int executionCount, String totalFeeKrw,
                          String realizedPnlKrw) {
    }

    /**
     * 실현 손익 기준 상·하위 거래 한 건.
     *
     * @param executionId    그 체결
     * @param instrumentId   종목
     * @param realizedPnlKrw 실현 손익 (원화 환산)
     * @param returnRate     그 거래의 수익률(%). 실현 손익 ÷ 덜어낸 취득 원가
     */
    public record Trade(Long executionId, Long instrumentId, String realizedPnlKrw,
                        BigDecimal returnRate) {
    }

    /**
     * 거래한 종목 하나의 그 일차 흐름.
     *
     * @param instrumentId 종목 식별자
     * @param currency     결제 통화. 점과 마커의 통화다
     * @param priceScale   가격의 표현 자릿수
     * @param points       그 일차 구간의 종가. 휴장일은 점을 만들지 않는다
     * @param markers      그 일차의 내 체결. 날짜순이다
     */
    public record InstrumentReplay(Long instrumentId, Currency currency, short priceScale,
                                   List<Point> points, List<Marker> markers) {
    }

    /**
     * 차트의 한 점.
     *
     * @param gameDate 게임 거래일
     * @param close    그날 종가 (현지 통화, 분할 조정된 값)
     */
    public record Point(LocalDate gameDate, String close) {
    }

    /**
     * 차트 위의 체결 지점.
     *
     * @param executionId 그 체결
     * @param gameDate    체결한 게임 거래일
     * @param side        매수·매도
     * @param quantity    수량
     * @param price       체결 단가 (현지 통화)
     */
    public record Marker(Long executionId, LocalDate gameDate, OrderSide side, int quantity,
                         String price) {
    }
}

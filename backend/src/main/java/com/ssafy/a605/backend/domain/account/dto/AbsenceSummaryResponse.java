package com.ssafy.a605.backend.domain.account.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.ssafy.a605.backend.global.response.Amounts;

/**
 * 부재 중 요약 (기획서 7.5, {@code acc.md}). 장중 재접속 시 모달이 쓰는 스냅샷이다.
 *
 * <p><b>부재가 아니면 {@code absent} 하나만 나간다.</b> 나머지 필드는 실리지 않는다 — 값이 없는
 * 구간에 0 을 채우면 화면이 "아무 일도 없었다"와 "볼 구간이 없다"를 구분하지 못한다.
 *
 * <p><b>갱신되지 않는 스냅샷이다.</b> 부재 요약은 실시간 위젯이 아니라 "당신이 없던 구간"의
 * 정산 보고서다. 모달이 떠 있는 동안 뒤의 차트·계좌·랭킹은 계속 갱신되고 멈추는 것은 모달
 * 하나다.
 *
 * @param absent             부재 기준(현재 틱 − 확인한 틱 ≥ 50)을 넘었는가
 * @param fromTickSeq        구간의 시작. 계좌가 마지막으로 확인한 틱이다
 * @param toTickSeq          구간의 끝. 이 응답이 선 틱이며 <b>확인 처리에 그대로 돌려보낸다</b>
 * @param fromGameDate       구간 시작의 게임 거래일. "2개월치를 놓쳤습니다"를 만드는 값이다
 * @param toGameDate         구간 끝의 게임 거래일
 * @param filledOrderCount   구간 중 체결된 예약 건수
 * @param valuationChangeKrw 구간 중 총평가액 변동. 시작 시점 평가액은 저장돼 있지 않아 그
 *                           사이의 사건을 되돌려 복원한다. 복원할 수 없으면 실리지 않는다
 * @param returnChange       구간 중 누적 수익률 변동(%p). 같은 값을 시작 잔고로 나눈 것이다 —
 *                           분모가 사람마다 달라(중간 합류자는 소급 잔고) 프론트가 나누게 두지
 *                           않는다
 * @param eventCount         구간 중 일어난 주요 이벤트 수 (기획서 7.5.2 — IPO · 무료 타임)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AbsenceSummaryResponse(
        boolean absent,
        Long fromTickSeq,
        Long toTickSeq,
        LocalDate fromGameDate,
        LocalDate toGameDate,
        Integer filledOrderCount,
        String valuationChangeKrw,
        BigDecimal returnChange,
        Integer eventCount
) {

    /** 부재가 아닐 때. 판정 결과 하나만 담는다. */
    public static AbsenceSummaryResponse present() {
        return new AbsenceSummaryResponse(false, null, null, null, null, null, null, null, null);
    }

    /**
     * @param fromTickSeq      구간 시작 틱
     * @param toTickSeq        구간 끝 틱
     * @param fromGameDate     구간 시작의 게임 거래일
     * @param toGameDate       구간 끝의 게임 거래일
     * @param filledOrderCount 구간 중 체결된 예약 건수
     * @param eventCount       구간 중 주요 이벤트 수
     * @param valuationKrw     총평가액 변동. 복원할 수 없으면 {@code null}
     * @param returnRate       누적 수익률 변동(%p). 같은 이유로 {@code null} 일 수 있다
     * @return 부재 요약
     */
    public static AbsenceSummaryResponse of(long fromTickSeq, long toTickSeq,
                                            LocalDate fromGameDate, LocalDate toGameDate,
                                            int filledOrderCount, int eventCount,
                                            BigDecimal valuationKrw, BigDecimal returnRate) {
        return new AbsenceSummaryResponse(true, fromTickSeq, toTickSeq, fromGameDate, toGameDate,
                filledOrderCount, valuationKrw == null ? null : Amounts.of(valuationKrw, Amounts.KRW_SCALE),
                returnRate == null ? null : Amounts.ratio(returnRate), eventCount);
    }
}

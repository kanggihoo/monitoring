package com.ssafy.a605.backend.domain.market.repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.ssafy.a605.backend.domain.market.entity.DailyClose;

public interface DailyCloseRepository extends JpaRepository<DailyClose, DailyClose.Pk> {


    /**
     * 한 종목의 종가 시계열. 차트가 그리는 점이다.
     *
     * <p><b>이월하지 않는다.</b> 행이 있는 날짜만 나오므로 휴장일은 아예 나타나지 않는다 —
     * 차트는 선이고 점이 없으면 앞뒤 점이 이어지는 것이 "그날 거래가 없었다"의 올바른 표현이다
     * ({@code mkt.md}). 현재가 조회와 이 차이는 의도된 것이다.
     *
     * <p>끝 날짜가 공개 기준 거래일이다. 시계열은 시즌 시작 전에 전 구간이 적재되어 있으므로
     * 절단이 조회 계층의 책임이다 ({@code _common.md} §9). 시작이 끝보다 뒤면 빈 목록이며
     * 오류가 아니다.
     *
     * @param instrumentId 대상 종목
     * @param from         시작 게임 거래일 (포함)
     * @param to           끝 게임 거래일 (포함). 공개 기준 거래일이다
     * @return 날짜순 종가. 그 구간에 종가가 없으면 빈 목록
     */
    List<DailyClose> findByInstrumentIdAndGameDateBetweenOrderByGameDateAsc(Long instrumentId,
                                                                           LocalDate from,
                                                                           LocalDate to);

    /**
     * <b>그 거래일에 실제로 새 종가가 있는</b> 종목의 종가. 이월하지 않는다.
     *
     * <p>{@code MarketPriceQueryRepository.findLatestClosesAsOf} 와 정반대의 요구라 따로 둔다. 평가액은 휴장 종목의 값이
     * 0 이 되면 안 되므로 직전 종가를 이월하지만, <b>체결 판정은 이월해서는 안 된다</b> —
     * 기획서 6.4.5 가 "휴장 종목은 그날 판정되지 않는다"이고 그 이유가 "직전 종가 유지는 체결
     * 사유가 아니다"이기 때문이다. 이월하면 어제 이미 판정한 종가로 오늘 또 체결시킨다.
     *
     * <p>그래서 빠진 종목은 <b>휴장</b>이라는 뜻이며, 호출 측은 그 종목의 예약을 그날 건드리지
     * 않는다. 없는 값을 채우려 하지 않는 것이 이 질의의 계약이다.
     *
     * <p><b>{@link #findByInstrumentIdAndGameDateBetweenOrderByGameDateAsc} 와 계약이 같다</b> —
     * 둘 다 이월하지 않고 행이 있는 날짜만 낸다. 다른 것은 절단면뿐이라(저쪽은 한 종목의 날짜
     * 구간, 이쪽은 여러 종목의 하루) {@code IN + BETWEEN} 하나로 합칠 수 있지만 합치지 않았다:
     * 묻는 질문이 "이 종목의 시계열"과 "이 종목들에 오늘 새 종가가 있나"로 다르고, 그 차이를
     * {@code List.of(id)} 와 {@code (d, d)} 라는 인자 모양에 숨기면 호출부만 보고는 무엇을 묻는지
     * 알 수 없다. 이월 규칙 질의는 {@code MarketPriceQueryRepository} 에 모여 있다.
     *
     * @param instrumentIds 대상 종목
     * @param gameDate      판정할 게임 거래일
     * @return 그날 종가가 있는 종목의 행만. 휴장 종목은 빠진다
     */
    List<DailyClose> findByInstrumentIdInAndGameDate(Collection<Long> instrumentIds,
                                                     LocalDate gameDate);

    /**
     * 여러 종목의 종가 시계열. <b>이월하지 않는다</b> — 행이 있는 날짜만 나온다.
     *
     * <p>{@link #findByInstrumentIdAndGameDateBetweenOrderByGameDateAsc} 를 여러 종목으로
     * 넓힌 것이다. 일일 복기가 <b>내가 그 일차에 거래한 종목</b>의 차트를 한 응답에 담는데
     * ({@code rpt.md} §instruments), 종목마다 부르면 조회가 거래한 종목 수만큼 늘어난다.
     *
     * <p>이월하지 않는 것이 차트의 규칙이다 — 점이 없으면 앞뒤 점이 이어지고 그것이 "그날
     * 거래가 없었다"의 올바른 표현이다({@code mkt.md}). 호출 측이 종목별로 나눠 쓴다.
     *
     * @param instrumentIds 대상 종목
     * @param from          시작 게임 거래일 (포함)
     * @param to            끝 게임 거래일 (포함)
     * @return 날짜순 종가. 종목이 섞여 있으므로 호출 측이 종목별로 가른다
     */
    List<DailyClose> findByInstrumentIdInAndGameDateBetweenOrderByGameDateAsc(
            Collection<Long> instrumentIds, LocalDate from, LocalDate to);
}

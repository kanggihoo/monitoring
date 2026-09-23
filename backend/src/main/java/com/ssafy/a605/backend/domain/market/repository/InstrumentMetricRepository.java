package com.ssafy.a605.backend.domain.market.repository;

import java.time.LocalDate;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.ssafy.a605.backend.domain.market.entity.InstrumentMetric;

public interface InstrumentMetricRepository
        extends JpaRepository<InstrumentMetric, InstrumentMetric.Pk> {

    /**
     * 공시된 분기의 지표만. <b>공시 지연이 이 조건 하나다</b> — 기획서 9.1.2 는 분기 종료
     * 45일 후부터 노출하도록 요구하고, 그 날짜는 {@code disclosed_game_date} 에 이미 저장되어
     * 있으므로 계산이 아니라 비교다.
     *
     * <p>가장 최근 분기를 고르는 것은 호출 측이 한다 — 한 분기가 지표 여러 행이라 정렬만으로
     * 한 분기를 집을 수 없고, 종목당 행 수가 작아(6지표 × 27분기) 한 번에 읽어도 된다.
     *
     * @param instrumentId        대상 종목
     * @param disclosedGameDate   공개 기준 게임 거래일. 이 날짜 이하로 공시된 것만 돌려준다
     * @return 공시된 지표 행. 아직 공시된 분기가 없으면 빈 목록
     */
    List<InstrumentMetric> findByInstrumentIdAndDisclosedGameDateLessThanEqual(
            Long instrumentId, LocalDate disclosedGameDate);
}

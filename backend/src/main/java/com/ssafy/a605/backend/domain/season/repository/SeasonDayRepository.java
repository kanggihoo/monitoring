package com.ssafy.a605.backend.domain.season.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.ssafy.a605.backend.domain.season.entity.SeasonDay;

public interface SeasonDayRepository extends JpaRepository<SeasonDay, SeasonDay.Pk> {

    /**
     * 그 시즌의 일차 편성 전부. 3행이라 하루 시간표·다음 개장 시각·당일 브리핑이 이 한 번의
     * 조회를 나눠 쓴다.
     *
     * <p>일차 순이 곧 개장 시각 순이다 — 일차는 시간순으로 이어지고 서로 끼어들지 않는다.
     * 그래서 "다음 개장"은 이 목록에서 현재 시각보다 늦은 첫 항목이다.
     *
     * <p><b>{@code from_game_month} · {@code to_game_month} 는 응답에 함부로 싣지 않는다.</b>
     * 기획서 3.1.2 가 일자별 재생 구간의 공개를 명시적으로 금지하며, 브리핑만 당일 시작
     * 시점을 공개한다.
     *
     * @param seasonId 대상 시즌
     * @return 일차 순의 편성. 편성이 없으면 빈 목록
     */
    List<SeasonDay> findBySeasonIdOrderBySeasonDayAsc(Long seasonId);
}

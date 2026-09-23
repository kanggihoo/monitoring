package com.ssafy.a605.backend.domain.news.repository;

import java.time.LocalDate;
import java.util.List;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

import com.ssafy.a605.backend.domain.news.entity.News;

public interface NewsRepository extends JpaRepository<News, Long> {

    /**
     * 그 시즌·그 거래일에 발행된 기사 전부. 실시간 발행이 쓴다.
     *
     * <p>피드의 <b>이하</b>와 달리 <b>그 날짜</b>이고 오름차순이다 — 브로드캐스트는 그 틱에 나온
     * 것만 실으며, 프론트가 받은 순서대로 목록 앞에 붙이므로 발행 순서가 유지돼야 한다.
     *
     * <p>두 시즌이 같은 게임 거래일 구간을 재생하므로 날짜만으로는 가릴 수 없다.
     *
     * @param seasonId 진행 중인 시즌
     * @param gameDate 그 틱의 게임 거래일
     * @return 그 날의 기사. 없으면 빈 목록
     */
    List<News> findBySeasonIdAndPublishedGameDateOrderByIdAsc(Long seasonId, LocalDate gameDate);

    /** 티커용 최신 몇 건. 시즌 말고는 필터가 없다. */
    List<News> findBySeasonIdAndPublishedGameDateLessThanEqualOrderByPublishedGameDateDescIdDesc(
            Long seasonId, LocalDate asOfGameDate, Limit limit);
}

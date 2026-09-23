package com.ssafy.a605.backend.domain.news.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.ssafy.a605.backend.domain.news.entity.NewsInstrument;

public interface NewsInstrumentRepository extends JpaRepository<NewsInstrument, NewsInstrument.Pk> {

    /** 한 페이지의 기사 링크를 한 번에 가져온다. 기사마다 조회하면 페이지당 20번이 된다. */
    List<NewsInstrument> findByNewsIdIn(Collection<Long> newsIds);
}

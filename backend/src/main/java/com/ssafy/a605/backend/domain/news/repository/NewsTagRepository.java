package com.ssafy.a605.backend.domain.news.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.ssafy.a605.backend.domain.news.entity.NewsTag;

public interface NewsTagRepository extends JpaRepository<NewsTag, NewsTag.Pk> {

    /** 한 페이지의 기사 태그를 한 번에 가져온다. */
    List<NewsTag> findByNewsIdIn(Collection<Long> newsIds);
}

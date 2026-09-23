package com.ssafy.a605.backend.domain.season.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.ssafy.a605.backend.domain.season.entity.Season;

/**
 * 시즌 편성값을 읽는다. 이름·기간·시드·수수료율·IPO 추첨 비율이 여기 있고
 * (기획서 16.1, {@code adm.md}), 편성이 바뀌면 과거 계산과 어긋나므로 시즌 시작 뒤에는
 * 바뀌지 않는다.
 *
 * <p>"현재 시즌"을 여기서 묻지 않는다 — 시즌은 재생과 같은 설정값(`app.replay.season-id`)이
 * 정한다. 시즌 목록·{@code {seasonId}} 경로를 두지 않기로 했으므로({@code sea.md}) 조회 대상은
 * 언제나 그 하나다.
 */
public interface SeasonRepository extends JpaRepository<Season, Long> {
}

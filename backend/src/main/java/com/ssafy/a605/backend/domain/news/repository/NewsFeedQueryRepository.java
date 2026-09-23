package com.ssafy.a605.backend.domain.news.repository;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.ssafy.a605.backend.domain.news.dto.NewsCursor;
import com.ssafy.a605.backend.domain.news.dto.NewsInterest;
import com.ssafy.a605.backend.domain.news.dto.NewsRelevance;
import com.ssafy.a605.backend.domain.news.entity.NewsChannel;

import lombok.RequiredArgsConstructor;

/**
 * 피드 한 페이지의 기사 식별자를 정렬된 순서로 고른다.
 *
 * <p><b>정렬은 (우선순위, 거래일 DESC, 식별자 DESC) 다</b> — 보유 &gt; 관심 &gt; 일반이고 같은
 * 구간 안은 최신순 (기획서 10.4.2, {@code nws.md} §우선 노출). 우선순위는 요청자의 보유·관심
 * 집합으로 {@code CASE} 가 매기며, 서비스가 라벨을 정하는 {@link NewsInterest#relevanceOf} 와
 * 같은 두 집합을 쓰므로 정렬과 라벨의 출처가 하나다.
 *
 * <p><b>보유도 관심도 없으면 우선순위 식을 붙이지 않는다.</b> 전부 일반이라 상수 2 이고, 그때의
 * 문장은 관련도가 없던 시절의 순수 최신순과 같다 — 미인증 피드는 이 변경으로 한 줄도 달라지지
 * 않는다.
 *
 * <p><b>조건이 있는 것만 SQL 에 붙인다.</b> 채널·종목·개인 필터가 전부 선택이라 JPQL 로는
 * {@code :param IS NULL OR …} 를 조건마다 반복해야 하고, 그 방식은 컬렉션 파라미터(종목 집합)에는
 * 통하지 않는다. 여기서 문장을 조립하고 엔티티는 {@code NewsRepository} 가 식별자로 다시 읽는다 —
 * 식별자가 페이지 크기(최대 100)뿐이라 PK 조회 한 번이다.
 *
 * <p>커서는 세 정렬 키의 키셋이다. 우선순위를 서브쿼리 컬럼으로 한 번 계산해 키셋 조건과 정렬이
 * 같은 값을 보게 한다 — 식을 두 곳에 베껴 쓰면 한쪽만 고치는 사고가 난다.
 */
@Repository
@RequiredArgsConstructor
public class NewsFeedQueryRepository {

    private final JdbcTemplate jdbc;

    /**
     * @param asOfGameDate 절단 기준. 이 날짜 이하만
     * @param channel      채널 필터 (null 이면 전체)
     * @param instrumentId 종목 링크 필터 (null 이면 전체)
     * @param interest     요청자의 보유·관심. 우선순위의 근거이고 개인 필터의 기준이다
     * @param personalOnly 보유·관심 종목이 링크된 기사만. <b>보유도 관심도 없으면 결과가 비어 있다</b>
     * @param after        이전 페이지의 마지막 기사 (첫 페이지면 null)
     * @param limit        가져올 건수
     * @return 정렬된 기사 식별자
     */
    public List<Long> findFeedIds(long seasonId, LocalDate asOfGameDate, NewsChannel channel,
                                  Long instrumentId, NewsInterest interest, boolean personalOnly,
                                  NewsCursor after, int limit) {
        if (personalOnly && interest.isEmpty()) {
            return List.of();
        }
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT x.id FROM (SELECT n.id, n.published_game_date AS game_date, ");
        appendPriority(sql, args, interest);
        // 시즌을 날짜보다 앞에 둔다 — idx_news_season_date_id 가 그 순서다 (V40).
        sql.append(" AS priority FROM news n WHERE n.season_id = ? AND n.published_game_date <= ?");
        args.add(seasonId);
        args.add(asOfGameDate);
        if (channel != null) {
            sql.append(" AND n.channel = ?");
            args.add(channel.name());
        }
        if (instrumentId != null) {
            sql.append(" AND EXISTS (SELECT 1 FROM news_instruments ni"
                    + " WHERE ni.news_id = n.id AND ni.instrument_id = ?)");
            args.add(instrumentId);
        }
        if (personalOnly) {
            sql.append(" AND EXISTS (SELECT 1 FROM news_instruments ni"
                    + " WHERE ni.news_id = n.id AND ni.instrument_id = ANY (?))");
            args.add(interest.personal().toArray(Long[]::new));
        }
        sql.append(") x");
        if (after != null) {
            sql.append(" WHERE x.priority > ? OR (x.priority = ? AND (x.game_date < ?"
                    + " OR (x.game_date = ? AND x.id < ?)))");
            args.add(after.priority());
            args.add(after.priority());
            args.add(after.gameDate());
            args.add(after.gameDate());
            args.add(after.newsId());
        }
        sql.append(" ORDER BY x.priority, x.game_date DESC, x.id DESC LIMIT ?");
        args.add(limit);
        return jdbc.queryForList(sql.toString(), Long.class, args.toArray());
    }

    /**
     * 우선순위 식. 보유 종목이 링크되면 0, 관심 종목이면 1, 아니면 2 — {@link NewsRelevance#priority()}
     * 와 같은 숫자다. 비어 있는 집합의 분기는 만들지 않는다.
     */
    private static void appendPriority(StringBuilder sql, List<Object> args, NewsInterest interest) {
        if (interest.isEmpty()) {
            sql.append(NewsRelevance.MARKET.priority());
            return;
        }
        sql.append("CASE");
        appendBranch(sql, args, interest.held(), NewsRelevance.HOLDING);
        appendBranch(sql, args, interest.watched(), NewsRelevance.WATCHLIST);
        sql.append(" ELSE ").append(NewsRelevance.MARKET.priority()).append(" END");
    }

    private static void appendBranch(StringBuilder sql, List<Object> args, Set<Long> instrumentIds,
                                     NewsRelevance relevance) {
        if (instrumentIds.isEmpty()) {
            return;
        }
        sql.append(" WHEN EXISTS (SELECT 1 FROM news_instruments ni"
                + " WHERE ni.news_id = n.id AND ni.instrument_id = ANY (?)) THEN ").append(relevance.priority());
        args.add(instrumentIds.toArray(Long[]::new));
    }
}

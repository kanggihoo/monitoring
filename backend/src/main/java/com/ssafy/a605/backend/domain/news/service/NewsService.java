package com.ssafy.a605.backend.domain.news.service;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Limit;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.ssafy.a605.backend.domain.account.entity.Position;
import com.ssafy.a605.backend.domain.account.repository.AccountRepository;
import com.ssafy.a605.backend.domain.account.repository.PositionRepository;
import com.ssafy.a605.backend.domain.market.entity.Watchlist;
import com.ssafy.a605.backend.domain.market.repository.WatchlistRepository;
import com.ssafy.a605.backend.domain.news.dto.NewsCursor;
import com.ssafy.a605.backend.domain.news.dto.NewsFeedResponse;
import com.ssafy.a605.backend.domain.news.dto.NewsInterest;
import com.ssafy.a605.backend.domain.news.dto.NewsItemResponse;
import com.ssafy.a605.backend.domain.news.dto.NewsRelevanceFilter;
import com.ssafy.a605.backend.domain.news.dto.NewsTickerResponse;
import com.ssafy.a605.backend.domain.news.entity.News;
import com.ssafy.a605.backend.domain.news.entity.NewsChannel;
import com.ssafy.a605.backend.domain.news.entity.NewsInstrument;
import com.ssafy.a605.backend.domain.news.entity.NewsTag;
import com.ssafy.a605.backend.domain.news.repository.NewsFeedQueryRepository;
import com.ssafy.a605.backend.domain.news.repository.NewsInstrumentRepository;
import com.ssafy.a605.backend.domain.news.repository.NewsRepository;
import com.ssafy.a605.backend.domain.news.repository.NewsTagRepository;
import com.ssafy.a605.backend.domain.replay.service.ReplayService;
import com.ssafy.a605.backend.global.exception.BusinessException;

/**
 * 뉴스 피드와 티커 조회.
 *
 * <p><b>절단 기준은 게임 시계가 갖는다.</b> 어느 거래일까지 보여줄지를 호출자가 넘기지 않는다 —
 * 두 엔드포인트가 공개 경로라 (ADR-0005) 파라미터로 받으면 누구든 미래 날짜를 적어 시즌 끝까지의
 * 기사를 꺼낼 수 있고, 뉴스는 시세의 선행 신호라 그것으로 게임이 무너진다 (기획서 4.5).
 *
 * <p><b>시계와 기사를 한 스냅샷에서 읽는다.</b> 클래스 격리 수준이 {@code REPEATABLE_READ} 인
 * 이유다 — 두 읽기 사이에 틱이 넘어가면 응답이 말하는 기준일과 실제로 담긴 기사가 어긋난다
 * ({@code MarketQueryService} 와 같은 규율).
 *
 * <p><b>관련도(보유 &gt; 관심 &gt; 일반)는 서버가 계산한다</b> ({@code nws.md} §우선 노출). 프론트가
 * 자기 목록으로 다시 계산하면 서버 정렬과 어긋난 강조가 생긴다. 요청자의 보유·관심 종목을
 * 각 한 번씩 읽어 기사의 링크 종목과 대조한다 — 미인증이면 전부 {@code MARKET} 이다.
 *
 * <p>시즌은 재생과 같은 설정값에서 읽는다 — 보유 판정에 쓰는 계좌가 재생이 진행하는 시즌의
 * 것이어야 한다 ({@code AccountService} 와 같은 이유).
 */
@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class NewsService {

    /** 티커에 담는 건수. 기획서에 수치가 없어 롤링 한 바퀴 분량으로 잡았다. */
    private static final int TICKER_SIZE = 10;

    private final NewsRepository newsRepository;
    private final NewsFeedQueryRepository feedQuery;
    private final NewsInstrumentRepository newsInstrumentRepository;
    private final NewsTagRepository newsTagRepository;
    private final AccountRepository accountRepository;
    private final PositionRepository positionRepository;
    private final WatchlistRepository watchlistRepository;
    private final ReplayService replay;
    private final long seasonId;

    public NewsService(NewsRepository newsRepository, NewsFeedQueryRepository feedQuery,
                       NewsInstrumentRepository newsInstrumentRepository, NewsTagRepository newsTagRepository,
                       AccountRepository accountRepository, PositionRepository positionRepository,
                       WatchlistRepository watchlistRepository, ReplayService replay,
                       @Value("${app.replay.season-id:1}") long seasonId) {
        this.newsRepository = newsRepository;
        this.feedQuery = feedQuery;
        this.newsInstrumentRepository = newsInstrumentRepository;
        this.newsTagRepository = newsTagRepository;
        this.accountRepository = accountRepository;
        this.positionRepository = positionRepository;
        this.watchlistRepository = watchlistRepository;
        this.replay = replay;
        this.seasonId = seasonId;
    }

    /**
     * 피드 한 페이지를 읽는다. 절단 기준은 시계의 공개 기준 거래일이다.
     *
     * @param userId       요청자. 관련도와 개인 필터의 기준이다. 미인증이면 {@code null} 이고
     *                     모든 기사가 {@code MARKET} 이다
     * @param channel      채널 필터 (null 이면 전체 탭)
     * @param instrumentId 종목 링크 필터 (null 이면 전체). 종목 상세의 뉴스 탭이 쓴다
     * @param filter       관련도 필터 (null 이면 전체). {@code PERSONAL} 은 보유·관심 기사만
     * @param cursor       이전 응답의 nextCursor (첫 페이지면 null)
     * @param size         페이지 크기
     * @return 정렬된 기사와 다음 커서. 최초 공개 전이면 빈 페이지
     * @throws BusinessException 커서가 우리가 만든 모양이 아닐 때 (400),
     *                           개인 필터를 미인증으로 요청했을 때 (401)
     */
    public NewsFeedResponse getFeed(Long userId, NewsChannel channel, Long instrumentId,
                                    NewsRelevanceFilter filter, String cursor, int size) {
        // 커서와 권한은 기준일보다 먼저 푼다. 최초 공개 전에도 잘못된 커서는 400, 미인증의 개인
        // 필터는 401 이어야 한다 — 빈 페이지로 돌려주면 프론트가 깨진 요청을 계속 들고 다닌다.
        NewsCursor from = NewsCursor.decode(cursor);
        if (filter == NewsRelevanceFilter.PERSONAL && userId == null) {
            // 보유·관심은 개인 데이터다. 공개 경로에서 이 파라미터만 로그인을 요구한다 (nws.md).
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "인증이 필요합니다.");
        }
        LocalDate asOfGameDate = asOfGameDate();
        if (asOfGameDate == null) {
            return new NewsFeedResponse(null, List.of(), null, false);
        }
        NewsInterest interest = interestOf(userId);
        // 한 건을 더 읽어 다음 페이지의 존재를 안다. COUNT 를 따로 치지 않는다.
        List<Long> ids = feedQuery.findFeedIds(seasonId, asOfGameDate, channel, instrumentId,
                interest, filter == NewsRelevanceFilter.PERSONAL, from, size + 1);
        boolean hasNext = ids.size() > size;
        List<News> page = newsInOrder(hasNext ? ids.subList(0, size) : ids);
        List<NewsItemResponse> items = toItems(page, interest);
        // 커서의 우선순위는 마지막 기사의 관련도다 — 질의가 같은 두 집합으로 매긴 값이라 어긋나지 않는다.
        String nextCursor = hasNext
                ? NewsCursor.after(page.get(page.size() - 1), items.get(items.size() - 1).relevance()).encode()
                : null;
        return new NewsFeedResponse(asOfGameDate, items, nextCursor, hasNext);
    }

    /**
     * 티커의 최신 헤드라인을 읽는다.
     *
     * @param userId 요청자. 강조의 근거인 관련도를 이 사람 기준으로 채운다. 미인증이면 {@code null}
     * @return 최신 몇 건의 헤드라인. 최초 공개 전이면 빈 목록
     */
    public NewsTickerResponse getTicker(Long userId) {
        LocalDate asOfGameDate = asOfGameDate();
        if (asOfGameDate == null) {
            return new NewsTickerResponse(null, List.of());
        }
        List<News> latest = newsRepository
                .findBySeasonIdAndPublishedGameDateLessThanEqualOrderByPublishedGameDateDescIdDesc(
                        seasonId, asOfGameDate, Limit.of(TICKER_SIZE));
        NewsInterest interest = interestOf(userId);
        // 관련도가 전부 MARKET 일 사람에게는 종목 링크를 읽지 않는다 — 티커는 링크를 싣지 않으므로
        // 그 조회는 관련도를 정하는 데만 쓰인다.
        Map<Long, List<Long>> instrumentIds = interest.isEmpty()
                ? Map.of()
                : instrumentIdsOf(latest.stream().map(News::getId).toList());
        return new NewsTickerResponse(asOfGameDate, latest.stream()
                .map(news -> NewsTickerResponse.Item.from(news,
                        interest.relevanceOf(instrumentIds.getOrDefault(news.getId(), List.of()))))
                .toList());
    }

    /**
     * 그 거래일에 발행된 기사 전부. 실시간 발행이 쓴다 (→ {@code NewsPublishListener}).
     *
     * <p>피드와 달리 <b>이하</b>가 아니라 <b>그 날짜</b>다. 브로드캐스트는 방금 나온 것만 보내고
     * 과거는 이미 받아 갔거나 피드 재조회로 채운다 ({@code nws.md} §실시간).
     *
     * <p>관련도는 전부 {@code MARKET} 이다 — 전원에게 같은 페이로드를 보내는데 관련도는 사람마다
     * 다르다. 프론트가 덮어쓰고 다음 피드 조회에서 서버 값으로 교정된다.
     *
     * @param gameDate 그 틱의 게임 거래일
     * @return 그 날의 기사. 없으면 빈 목록
     */
    public List<NewsItemResponse> publishedOn(LocalDate gameDate) {
        return toItems(newsRepository.findBySeasonIdAndPublishedGameDateOrderByIdAsc(seasonId, gameDate),
                NewsInterest.NONE);
    }

    /**
     * 공개 기준 게임 거래일.
     *
     * @return 시계의 기준일. 최초 공개 전이면 {@code null} — 아직 아무 기사도 나오지 않은 시점이다
     */
    private LocalDate asOfGameDate() {
        return replay.clock().gameDate();
    }

    /**
     * 요청자의 보유·관심 종목. 보유는 시즌 계좌의 수량이 남은 포지션이고, 계좌가 없으면 빈
     * 집합이다 — 공개 경로라 참가하지 않은 사람에게 404 를 내지 않는다.
     */
    private NewsInterest interestOf(Long userId) {
        if (userId == null) {
            return NewsInterest.NONE;
        }
        Set<Long> held = accountRepository.findByUserIdAndSeasonId(userId, seasonId)
                .map(account -> positionRepository
                        .findByAccountIdAndQuantityGreaterThanOrderByInstrumentIdAsc(account.getId(), 0).stream()
                        .map(Position::getInstrumentId)
                        .collect(Collectors.toSet()))
                .orElse(Set.of());
        Set<Long> watched = watchlistRepository.findBySeason(userId, seasonId).stream()
                .map(Watchlist::getInstrumentId)
                .collect(Collectors.toSet());
        return new NewsInterest(held, watched);
    }

    /** 식별자 순서대로의 기사. PK 조회는 순서를 보장하지 않으므로 여기서 되돌린다. */
    private List<News> newsInOrder(List<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<Long, News> byId = newsRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(News::getId, Function.identity()));
        return ids.stream().map(byId::get).toList();
    }

    /** 기사에 종목 링크·태그·관련도를 붙인다. 링크·태그를 기사마다 따로 읽지 않고 두 번에 모은다. */
    private List<NewsItemResponse> toItems(List<News> rows, NewsInterest interest) {
        if (rows.isEmpty()) {
            return List.of();
        }
        List<Long> ids = rows.stream().map(News::getId).toList();
        Map<Long, List<Long>> instrumentIds = instrumentIdsOf(ids);
        Map<Long, List<String>> tags = newsTagRepository.findByNewsIdIn(ids).stream()
                .collect(Collectors.groupingBy(NewsTag::getNewsId,
                        Collectors.mapping(NewsTag::getTag, Collectors.toList())));
        return rows.stream()
                .map(news -> {
                    List<Long> linked = instrumentIds.getOrDefault(news.getId(), List.of());
                    return NewsItemResponse.from(news, linked, tags.getOrDefault(news.getId(), List.of()),
                            interest.relevanceOf(linked));
                })
                .toList();
    }

    private Map<Long, List<Long>> instrumentIdsOf(Collection<Long> newsIds) {
        return newsInstrumentRepository.findByNewsIdIn(newsIds).stream()
                .collect(Collectors.groupingBy(NewsInstrument::getNewsId,
                        Collectors.mapping(NewsInstrument::getInstrumentId, Collectors.toList())));
    }
}

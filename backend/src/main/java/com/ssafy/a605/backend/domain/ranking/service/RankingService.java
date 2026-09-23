package com.ssafy.a605.backend.domain.ranking.service;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.ToLongFunction;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ssafy.a605.backend.domain.account.entity.Account;
import com.ssafy.a605.backend.domain.account.entity.Position;
import com.ssafy.a605.backend.domain.account.repository.AccountRepository;
import com.ssafy.a605.backend.domain.account.repository.PositionRepository;
import com.ssafy.a605.backend.domain.order.repository.ExecutionRepository;
import com.ssafy.a605.backend.domain.order.repository.ExecutionRepository.AccountCount;
import com.ssafy.a605.backend.domain.ranking.dto.MyRankingResponse;
import com.ssafy.a605.backend.domain.ranking.dto.PointStanding;
import com.ssafy.a605.backend.domain.ranking.dto.RankingPageResponse;
import com.ssafy.a605.backend.domain.ranking.dto.RankingRowResponse;
import com.ssafy.a605.backend.domain.ranking.dto.RankingSnapshot;
import com.ssafy.a605.backend.domain.ranking.dto.RankingSnapshot.Entry;
import com.ssafy.a605.backend.domain.ranking.dto.RankingTrack;
import com.ssafy.a605.backend.domain.replay.dto.GameClockSnapshot;
import com.ssafy.a605.backend.domain.replay.service.ReplayService;
import com.ssafy.a605.backend.domain.user.entity.User;
import com.ssafy.a605.backend.domain.user.repository.UserRepository;
import com.ssafy.a605.backend.global.exception.BusinessException;
import com.ssafy.a605.backend.global.response.Amounts;
import com.ssafy.a605.backend.global.response.PageResponse;

/**
 * 리더보드·내 순위 조회 ({@code rank.md}). 순위를 계산하지 않는다 — {@link RankingRecalculator}
 * 가 매 틱 만들어 둔 {@link RankingSnapshot} 을 읽어 응답 형태로 바꾸고, 여기서 하는 판단은 둘뿐이다:
 * 암전이면 무엇을 가리는가, 어느 페이지를 잘라 내는가.
 *
 * <p><b>암전은 시계가 정한다.</b> {@link ReplayService#clock()} 의 {@code leaderboardBlackout}
 * 을 읽고 자체 시각 계산을 하지 않는다 — 서버가 유일한 시간 권위이고, 두 곳에서 따로 판정하면
 * 경계 순간에 두 판정이 갈린다 ({@code _common.md} §7.2).
 *
 * <p><b>공개 경계는 행 DTO 가 정한다.</b> 닉네임·아바타·순위·수익률·총평가액·보유 종목 수·체결 건수까지다.
 * 무엇을 들고 있는지는 {@code RankingSnapshot} 에도 없고, 여기서 포지션을 읽는 것은 종목 수를
 * 세기 위해서만이다 (기획서 11.6.2).
 */
@Service
@Transactional(readOnly = true)
public class RankingService {

    private static final String NO_ACCOUNT = "시즌 계좌가 없습니다.";
    /**
     * {@code POINT} 는 순위 스냅샷에서 오지 않는다 — {@link #leaderboard} 가 먼저 갈라 보내므로
     * 아래 셋에 도달할 수 없다. 나중에 그 분기를 지우면 조용히 틀린 순위가 나가는 대신 여기서
     * 터지게 둔다.
     */
    private static final String NOT_SNAPSHOT = "POINT 트랙은 순위 스냅샷에서 오지 않는다.";

    private final RankingRecalculator rankings;
    private final PointStandingsCalculator points;
    private final ReplayService replay;
    private final AccountRepository accountRepository;
    private final PositionRepository positionRepository;
    private final ExecutionRepository executionRepository;
    private final UserRepository userRepository;
    private final long seasonId;

    public RankingService(RankingRecalculator rankings, PointStandingsCalculator points, ReplayService replay,
                          AccountRepository accountRepository, PositionRepository positionRepository,
                          ExecutionRepository executionRepository, UserRepository userRepository,
                          @Value("${app.replay.season-id:1}") long seasonId) {
        this.rankings = rankings;
        this.points = points;
        this.replay = replay;
        this.accountRepository = accountRepository;
        this.positionRepository = positionRepository;
        this.executionRepository = executionRepository;
        this.userRepository = userRepository;
        this.seasonId = seasonId;
    }

    /**
     * 한 트랙의 리더보드 한 페이지.
     *
     * <p>순위가 아직 없으면(시즌 첫 틱 전, 재시작 직후 첫 틱 전) 빈 목록이고 기준 틱·거래일은
     * {@code null} 이다. 암전 중이면 {@code content} 만 비운다 — {@code totalElements} 와
     * {@code totalParticipants} 는 순위가 아니므로 그대로 내려간다.
     *
     * <p>두 개수는 <b>탈퇴자를 뺀 뒤</b>의 값이다 ({@link #enrolled}, {@code rank.md} 11.1.5).
     *
     * @param userId   세션 사용자. {@code isMe} 판정에만 쓴다. 미인증이면 {@code null} 이고
     *                 어떤 행도 내 행이 아니다
     * @param track    트랙
     * @param pageable 페이지. 정렬은 트랙의 순위로 고정이라 무시한다
     * @return 리더보드 한 페이지와 트랙 메타
     */
    public RankingPageResponse leaderboard(Long userId, RankingTrack track, Pageable pageable) {
        if (track == RankingTrack.POINT) {
            return pointLeaderboard(userId, pageable);
        }
        GameClockSnapshot clock = replay.clock();
        RankingSnapshot snapshot = rankings.current();
        if (snapshot == null) {
            return RankingPageResponse.of(track, null, null, clock.leaderboardBlackout(), false, 0,
                    PageResponse.from(new PageImpl<>(List.of(), pageable, 0)));
        }

        List<Entry> ordered = enrolled(ordering(snapshot, track), Entry::accountId);
        List<RankingRowResponse> rows = clock.leaderboardBlackout()
                ? List.of()
                : rows(userId, track, snapshot.seasonDay(), slice(ordered, pageable));
        return RankingPageResponse.of(track, snapshot.tickSeq(), snapshot.gameDate(), clock.leaderboardBlackout(),
                snapshot.finalized(), ordered.size(),
                PageResponse.from(new PageImpl<>(rows, pageable, ordered.size())));
    }

    /**
     * 세션 사용자의 두 트랙 순위.
     *
     * @param userId 세션 사용자
     * @return 내 순위. 암전 중이면 순위·등락이 {@code null}
     * @throws BusinessException 그 시즌 계좌가 없을 때 (404)
     */
    public MyRankingResponse me(Long userId) {
        Account account = accountRepository.findByUserIdAndSeasonId(userId, seasonId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, NO_ACCOUNT));
        GameClockSnapshot clock = replay.clock();
        RankingSnapshot snapshot = rankings.current();
        Entry me = snapshot == null ? null : snapshot.byAccountId().get(account.getId());
        if (me == null) {
            return new MyRankingResponse(snapshot == null ? null : snapshot.tickSeq(), null, null, null,
                    null, null, null, null, null, null,
                    snapshot == null ? 0 : snapshot.totalParticipants(), clock.leaderboardBlackout());
        }
        return MyRankingResponse.of(snapshot, me, clock.leaderboardBlackout());
    }

    /**
     * 탈퇴자를 뺀 순위. <b>자르기 전에</b> 빼야 한다 — 페이지를 자른 뒤에 빼면 그 페이지만 짧아지고
     * 총 개수는 스냅샷 값으로 남아, 목록과 개수가 서로를 설명하지 못한다 (탈퇴자만 든 페이지는
     * 통째로 빈다). 3일차 마지막 틱 이후에는 다음 갱신이 없어 그 상태가 시즌 끝까지 남는다.
     *
     * <p>판정 기준은 <b>계좌의 존재</b>다. 탈퇴가 계좌 행을 지우므로 순위 스냅샷에는 있는데 계좌가
     * 없는 항목이 곧 탈퇴자다 (기획서 11.1.5, ADR-0007).
     *
     * <p><b>확정 순위 번호는 건드리지 않는다.</b> 남은 참가자의 등수를 재산정하지 않으므로 순위
     * 번호에 공백이 생기며, 그것을 허용하는 것이 기록된 결정이다.
     */
    /** 그 트랙에서의 등수. */
    private static int rankOf(Entry entry, RankingTrack track) {
        return switch (track) {
            case SEASON -> entry.seasonRank();
            case DAILY -> entry.dailyRank();
            case VALUATION -> entry.valuationRank();
            case POINT -> throw new IllegalStateException(NOT_SNAPSHOT);
        };
    }

    /** 그 트랙에서의 직전 틱 대비 등락. */
    private static int rankDeltaOf(Entry entry, RankingTrack track) {
        return switch (track) {
            case SEASON -> entry.seasonRankDelta();
            case DAILY -> entry.dailyRankDelta();
            case VALUATION -> entry.valuationRankDelta();
            case POINT -> throw new IllegalStateException(NOT_SNAPSHOT);
        };
    }

    /** 그 트랙이 세워 둔 순서. 정렬은 계산기가 이미 끝냈고 여기서는 고르기만 한다. */
    private static List<Entry> ordering(RankingSnapshot snapshot, RankingTrack track) {
        return switch (track) {
            case SEASON -> snapshot.bySeasonRank();
            case DAILY -> snapshot.byDailyRank();
            case VALUATION -> snapshot.byValuationRank();
            case POINT -> throw new IllegalStateException(NOT_SNAPSHOT);
        };
    }

    private <T> List<T> enrolled(List<T> ordered, ToLongFunction<T> accountId) {
        Set<Long> accountIds = Set.copyOf(accountRepository.findIdsBySeasonId(seasonId));
        return ordered.stream().filter(row -> accountIds.contains(accountId.applyAsLong(row))).toList();
    }

    private static <T> List<T> slice(List<T> ordered, Pageable pageable) {
        int from = (int) Math.min(pageable.getOffset(), ordered.size());
        int to = Math.min(from + pageable.getPageSize(), ordered.size());
        return ordered.subList(from, to);
    }

    /**
     * 페이지에 든 계좌만 조인한다 — 사용자·포지션·체결 각 한 번씩이다.
     *
     * <p><b>조인이 빈 행도 뺀다.</b> 탈퇴자는 {@link #enrolled} 가 이미 걸렀지만, 그 조회와 이
     * 조회 사이에 탈퇴가 커밋될 수 있다 (이 트랜잭션은 격리 수준을 올리지 않는다). 그 창에서
     * 넘어온 항목이 <b>이름 없는 행</b>으로 렌더링되지 않게 여기서 한 번 더 막는다.
     */
    private List<RankingRowResponse> rows(Long userId, RankingTrack track, short seasonDay, List<Entry> page) {
        if (page.isEmpty()) {
            return List.of();
        }
        // 평가액 트랙은 시즌 축의 값이다 — 금액으로 줄 세우되 함께 보이는 수익률·체결 건수는
        // 시즌 기준이어야 한다. 당일 것을 붙이면 한 줄의 두 숫자가 서로 다른 구간을 말한다.
        boolean season = track != RankingTrack.DAILY;
        List<Long> accountIds = page.stream().map(Entry::accountId).toList();
        Map<Long, User> users = userRepository.findAllById(page.stream().map(Entry::userId).toList()).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
        Map<Long, Long> holdings = positionRepository.findByAccountIdInAndQuantityGreaterThan(accountIds, 0).stream()
                .collect(Collectors.groupingBy(Position::getAccountId, HashMap::new, Collectors.counting()));
        Map<Long, Long> trades = (season
                ? executionRepository.countByAccountIds(accountIds)
                : executionRepository.countByAccountIdsAndSeasonDay(accountIds, seasonDay)).stream()
                .collect(Collectors.toMap(AccountCount::accountId, AccountCount::count));

        return page.stream().filter(entry -> users.containsKey(entry.userId())).map(entry -> {
            User owner = users.get(entry.userId());
            return new RankingRowResponse(
                    rankOf(entry, track),
                    rankDeltaOf(entry, track),
                    owner.getNickname(),
                    owner.getAvatarId(),
                    Amounts.ratio(season ? entry.seasonReturn() : entry.dailyReturn()),
                    Amounts.of(entry.totalValuationKrw(), Amounts.KRW_SCALE),
                    holdings.getOrDefault(entry.accountId(), 0L).intValue(),
                    trades.getOrDefault(entry.accountId(), 0L),
                    // 점수는 확정된 일차에서만 나온다 — 틱 순위 트랙에는 담을 값이 없다.
                    null, null,
                    userId != null && entry.userId() == userId);
        }).toList();
    }

    /**
     * 포인트 합산 리더보드 한 페이지 ({@code S15P21A605-250}).
     *
     * <p><b>암전을 보지 않는다.</b> 암전은 폐장 직전의 순위를 가려 긴장을 만드는 장치인데, 점수는
     * <b>이미 확정·공개된 지난 일차</b>의 값이라 가릴 것이 없다 — 오늘 점수는 오늘 폐장 전에는
     * 존재하지도 않는다. 트랙마다 암전 판정이 갈리는 첫 자리라 이유를 여기 적어 둔다.
     *
     * <p><b>기준 틱과 거래일이 없다.</b> 점수는 틱이 아니라 일차에서 나오므로 그 두 필드가
     * 가리킬 값이 없다. {@code finalized} 는 확정 행이 하나라도 있으면 참이다 — 이 트랙의
     * 값은 전부 확정된 것뿐이라는 뜻이다.
     */
    private RankingPageResponse pointLeaderboard(Long userId, Pageable pageable) {
        List<PointStanding> ordered = enrolled(points.standings(), PointStanding::accountId);
        List<RankingRowResponse> rows = pointRows(userId, slice(ordered, pageable));
        return RankingPageResponse.of(RankingTrack.POINT, null, null, false, !ordered.isEmpty(), ordered.size(),
                PageResponse.from(new PageImpl<>(rows, pageable, ordered.size())));
    }

    /**
     * 페이지에 든 계좌만 조인한다. {@link #rows} 와 같은 재료를 읽지만 출처가 달라 따로 둔다 —
     * 저쪽은 순위 스냅샷의 {@code Entry} 가 주인·평가액을 이미 들고 오지만, 여기서는 계좌를
     * 직접 읽어야 한다.
     *
     * <p><b>총평가액은 실시간 스냅샷에서 온다.</b> 점수는 확정값이지만 평가액은 "지금 얼마를
     * 굴리고 있는가" 라 다른 트랙과 같은 값을 보여야 한다. 첫 틱 전이거나 그 계좌가 아직 순위에
     * 들지 않았으면 0 이다.
     */
    private List<RankingRowResponse> pointRows(Long userId, List<PointStanding> page) {
        if (page.isEmpty()) {
            return List.of();
        }
        List<Long> accountIds = page.stream().map(PointStanding::accountId).toList();
        Map<Long, Account> accounts = accountRepository.findAllById(accountIds).stream()
                .collect(Collectors.toMap(Account::getId, Function.identity()));
        Map<Long, User> users = userRepository
                .findAllById(accounts.values().stream().map(Account::getUserId).toList()).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
        Map<Long, Long> holdings = positionRepository.findByAccountIdInAndQuantityGreaterThan(accountIds, 0).stream()
                .collect(Collectors.groupingBy(Position::getAccountId, HashMap::new, Collectors.counting()));
        Map<Long, Long> trades = executionRepository.countByAccountIds(accountIds).stream()
                .collect(Collectors.toMap(AccountCount::accountId, AccountCount::count));
        RankingSnapshot snapshot = rankings.current();

        return page.stream().map(standing -> {
            Account account = accounts.get(standing.accountId());
            User owner = account == null ? null : users.get(account.getUserId());
            if (owner == null) {
                // enrolled 와 이 조회 사이에 탈퇴가 커밋되면 이름 없는 행이 된다 ({@link #rows} 와 같은 이유).
                return null;
            }
            Entry entry = snapshot == null ? null : snapshot.byAccountId().get(standing.accountId());
            return new RankingRowResponse(
                    standing.rank(),
                    // 하루 한 번 바뀌는 값이라 비교할 직전 틱이 없다.
                    0,
                    owner.getNickname(),
                    owner.getAvatarId(),
                    Amounts.ratio(standing.dailyReturnSum()),
                    Amounts.of(entry == null ? BigDecimal.ZERO : entry.totalValuationKrw(), Amounts.KRW_SCALE),
                    holdings.getOrDefault(standing.accountId(), 0L).intValue(),
                    trades.getOrDefault(standing.accountId(), 0L),
                    standing.points(),
                    standing.wins(),
                    userId != null && owner.getId().equals(userId));
        }).filter(Objects::nonNull).toList();
    }
}

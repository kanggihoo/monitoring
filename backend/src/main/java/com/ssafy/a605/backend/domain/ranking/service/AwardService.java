package com.ssafy.a605.backend.domain.ranking.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ssafy.a605.backend.domain.account.entity.Account;
import com.ssafy.a605.backend.domain.account.repository.AccountRepository;
import com.ssafy.a605.backend.domain.ranking.dto.AwardResponse;
import com.ssafy.a605.backend.domain.ranking.dto.AwardsAnnouncedEvent.DailyWinner;
import com.ssafy.a605.backend.domain.ranking.dto.AwardsResponse;
import com.ssafy.a605.backend.domain.ranking.entity.DailySettlement;
import com.ssafy.a605.backend.domain.ranking.repository.DailySettlementRepository;
import com.ssafy.a605.backend.domain.replay.dto.GameClockSnapshot;
import com.ssafy.a605.backend.domain.replay.entity.ReplayState;
import com.ssafy.a605.backend.domain.replay.service.ReplayService;
import com.ssafy.a605.backend.domain.season.entity.SeasonDay;
import com.ssafy.a605.backend.domain.season.repository.SeasonDayRepository;
import com.ssafy.a605.backend.domain.user.entity.User;
import com.ssafy.a605.backend.domain.user.repository.UserRepository;
import com.ssafy.a605.backend.global.response.Amounts;

/**
 * 시상 조회 ({@code rank.md} §GET /api/v1/rankings/awards, 기획서 11.4 · 11.5).
 *
 * <p><b>시상 테이블이 없다.</b> 일간 수상자는 {@code daily_settlements} 의 {@code daily_rank <= 5}
 * ({@code S15P21A605-265}), 시즌 우승자는 마지막 일차의 {@code season_rank = 1} 을 읽는다. 그 행이 이미 확정 스냅샷이라
 * 이 조회는 재계산이 아니고, 수상자가 이후 거래했거나 탈퇴해도 결과가 흔들리지 않는다.
 *
 * <p><b>발표 전에는 내려주지 않는다.</b> 폐장(16:40)에 확정되지만 공개는 정산 발표(17:00)다.
 * 발표 여부는 저장하지 않고 시계로 판정한다 — 지난 일차는 전부 발표됐고, 오늘은 {@code REVIEW_ONLY}
 * 에 들어섰을 때 발표된 것이다. 발표 시각은 편성({@code season_days.opens_at}) 에서 계산한다.
 */
@Service
@Transactional(readOnly = true)
public class AwardService {

    /** 칭호. 유형이 둘뿐이라 서버 상수다 (기획서 11.4.2). */
    public static final String DAILY_TITLE = "오늘의 투자자";
    public static final String FINAL_TITLE = "시즌 우승자";

    /**
     * 일간 시상 범위. 이 순위까지가 "오늘의 투자자" 다 ({@code S15P21A605-265}). 조회와 알림이 같은
     * 값을 보므로 화면에 뜬 사람이 곧 알림을 받는 사람이다. 한 곳에서만 쓰는 상수라 편성값으로
     * 빼지 않는다.
     */
    static final int DAILY_AWARD_RANKS = 5;

    private static final String REVIEW_ONLY = "REVIEW_ONLY";

    private final ReplayService replay;
    private final DailySettlementRepository settlementRepository;
    /** 시즌 우승자의 근거. 마지막 일차 순위가 아니라 포인트 합산이다 */
    private final PointStandingsCalculator points;
    private final AccountRepository accountRepository;
    private final UserRepository userRepository;
    private final SeasonDayRepository seasonDayRepository;
    private final long seasonId;

    public AwardService(ReplayService replay, DailySettlementRepository settlementRepository,
                        PointStandingsCalculator points,
                        AccountRepository accountRepository, UserRepository userRepository,
                        SeasonDayRepository seasonDayRepository,
                        @Value("${app.replay.season-id:1}") long seasonId) {
        this.replay = replay;
        this.settlementRepository = settlementRepository;
        this.points = points;
        this.accountRepository = accountRepository;
        this.userRepository = userRepository;
        this.seasonDayRepository = seasonDayRepository;
        this.seasonId = seasonId;
    }

    /**
     * 지금 시계 기준으로 발표된 시상 전부.
     *
     * @return 발표된 일차별 1위와, 마지막 일차가 발표됐으면 시즌 우승자들
     */
    public AwardsResponse awards() {
        return awardsThrough(lastAnnouncedDay(replay.clock()));
    }

    /**
     * 그 일차까지 발표된 시상. 정산 발표 경계가 직접 부를 때는 시계를 다시 읽지 않고 일차를 받는다.
     *
     * @param lastAnnouncedDay 마지막으로 발표된 일차. 0 이면 아직 발표가 없다
     * @return 시상 결과
     */
    public AwardsResponse awardsThrough(short lastAnnouncedDay) {
        List<Winner> daily = new ArrayList<>();
        for (short day = 1; day <= lastAnnouncedDay; day++) {
            daily.addAll(dailyWinners(day));
        }
        // 동점은 같은 순위다 (1, 1, 3). 시즌 우승자도 seasonRank = 1 인 행 전부이며 임의로 한 명을 고르지 않는다.
        List<Winner> champions = lastAnnouncedDay >= ReplayState.FINAL_DAY
                ? champions((short) ReplayState.FINAL_DAY) : List.of();

        Map<Long, User> owners = ownersOf(Stream.concat(daily.stream(), champions.stream()).toList());
        return new AwardsResponse(
                daily.stream().map(winner -> winner.toResponse(owners, announcedAt(winner.seasonDay()))).toList(),
                champions.stream().map(winner -> winner.toResponse(owners, announcedAt(winner.seasonDay()))).toList());
    }

    /**
     * 시계로 본 마지막 발표 일차. 오늘이 열람 전용({@code REVIEW_ONLY})이면 오늘까지, 아니면 어제까지다.
     *
     * @param clock 게임 시계
     * @return 발표된 마지막 일차. 아직 없으면 0
     */
    public static short lastAnnouncedDay(GameClockSnapshot clock) {
        return (short) (REVIEW_ONLY.equals(clock.phase()) ? clock.seasonDay() : clock.seasonDay() - 1);
    }

    /** 정산 발표 실제 시각. 편성된 개장 시각에서 발표 경계까지의 거리다. */
    private Instant announcedAt(Short seasonDay) {
        return seasonDayRepository.findById(new SeasonDay.Pk(seasonId, seasonDay))
                .map(day -> day.getOpensAt().plusSeconds(ReplayState.END - ReplayState.TRADING_OPEN))
                .orElse(null);
    }

    /** 수상 행의 계좌 주인. 계좌 한 번, 사용자 한 번이다. */
    private Map<Long, User> ownersOf(List<Winner> winners) {
        if (winners.isEmpty()) {
            return Map.of();
        }
        Map<Long, Long> userIdByAccount = accountRepository
                .findAllById(winners.stream().map(w -> w.accountId()).distinct().toList()).stream()
                .collect(Collectors.toMap(Account::getId, Account::getUserId));
        Map<Long, User> users = userRepository.findAllById(userIdByAccount.values()).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
        return userIdByAccount.entrySet().stream()
                .filter(e -> users.containsKey(e.getValue()))
                .collect(Collectors.toMap(Map.Entry::getKey, e -> users.get(e.getValue())));
    }

    /** 그 일차의 일간 상위 수상자. 조회와 알림이 이 하나를 읽는다. */
    private List<Winner> dailyWinners(short seasonDay) {
        return settlementRepository
                .findBySeasonDayAndDailyRankLessThanEqualOrderByDailyRankAscAccountIdAsc(seasonId, seasonDay, DAILY_AWARD_RANKS)
                .stream()
                .map(Winner::daily)
                .toList();
    }

    /**
     * 수상 한 줄. 계좌와 일차만 들고 있고 응답으로 바꿀 때 주인을 조인한다.
     *
     * <p><b>확정 행을 통째로 들지 않는다.</b> 시즌 우승자는 포인트 합산으로 정해져 확정 행
     * 하나로 표현되지 않는다 — 여러 일차의 점수 합이 근거이고, 그 계좌의 마지막 일차 행은
     * 그날 성적일 뿐이다.
     */
    private record Winner(long accountId, short seasonDay, String title,
                          java.math.BigDecimal returnRate, int rank) {

        static Winner daily(DailySettlement row) {
            return new Winner(row.getAccountId(), row.getSeasonDay(), DAILY_TITLE,
                    row.getDailyReturn(), row.getDailyRank());
        }

        /** 탈퇴한 수상자는 닉네임·아바타가 {@code null} 이다 — 순위와 수익률은 얼린 값 그대로 남는다. */
        AwardResponse toResponse(Map<Long, User> owners, Instant announcedAt) {
            User owner = owners.get(accountId);
            return new AwardResponse(seasonDay, rank,
                    owner == null ? null : owner.getNickname(),
                    owner == null ? null : owner.getAvatarId(),
                    Amounts.ratio(returnRate), title, announcedAt);
        }

        Long userId(Map<Long, User> owners) {
            User owner = owners.get(accountId);
            return owner == null ? null : owner.getId();
        }
    }

    /**
     * 그 일차 발표의 수상자 사용자들. 알림 훅({@code AwardsAnnouncedEvent}) 이 쓴다.
     *
     * @param seasonDay 발표된 일차
     * @return 일간 상위 수상자(등수 포함)와, 마지막 일차면 시즌 우승자 목록
     */
    public WinnerUsers winnerUsers(short seasonDay) {
        List<Winner> daily = dailyWinners(seasonDay);
        List<Winner> champions = seasonDay >= ReplayState.FINAL_DAY ? champions(seasonDay) : List.of();
        Map<Long, User> owners = ownersOf(Stream.concat(daily.stream(), champions.stream()).toList());
        return new WinnerUsers(
                daily.stream()
                        .filter(w -> w.userId(owners) != null)
                        .map(w -> new DailyWinner(w.userId(owners), w.rank()))
                        .toList(),
                champions.stream().map(w -> w.userId(owners)).filter(Objects::nonNull).toList());
    }

    /**
     * @param dailyWinners       일간 상위 수상자와 등수. 동점이면 같은 등수가 여럿
     * @param finalWinnerUserIds 시즌 우승자 사용자. 동점이면 여럿, 마지막 일차가 아니면 비어 있다
     */
    public record WinnerUsers(List<DailyWinner> dailyWinners, List<Long> finalWinnerUserIds) {
    }

    /**
     * 시즌 우승자 = <b>포인트 합산 1 위</b> 전부. 동점이면 여럿이다.
     *
     * <p><b>마지막 일차의 {@code season_rank} 를 보지 않는다.</b> 일차 경계마다 자산이 시드로
     * 돌아가므로 그 값은 누적 순위가 아니라 3일차 하루의 순위다 — 그대로 쓰면 마지막 날
     * 하루를 잘한 사람이 시즌 우승자가 된다. 시즌 성적을 정하는 규칙은 포인트 합산이고
     * ({@code S15P21A605-250}), 리더보드가 이미 그 순위를 보여주고 있다.
     *
     * <p>수익률은 <b>일간 수익률의 합</b>이다. 포인트 순위의 마지막 동점 기준과 같은 값이라
     * 화면의 포인트 탭과 여기가 같은 숫자를 말한다.
     *
     * @param seasonDay 발표된 마지막 일차. 수상 행의 일차로 실린다
     */
    private List<Winner> champions(short seasonDay) {
        return points.standings().stream()
                .filter(standing -> standing.rank() == 1)
                .map(standing -> new Winner(standing.accountId(), seasonDay, FINAL_TITLE,
                        standing.dailyReturnSum(), 1))
                .toList();
    }
}

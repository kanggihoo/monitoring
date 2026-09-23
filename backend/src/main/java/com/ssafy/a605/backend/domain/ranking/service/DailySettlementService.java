package com.ssafy.a605.backend.domain.ranking.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.ssafy.a605.backend.domain.account.entity.Account;
import com.ssafy.a605.backend.domain.account.repository.AccountRepository;
import com.ssafy.a605.backend.domain.account.dto.HoldingValuation;
import com.ssafy.a605.backend.domain.account.entity.Position;
import com.ssafy.a605.backend.domain.account.repository.PositionRepository;
import com.ssafy.a605.backend.domain.account.service.AccountValuationService;
import com.ssafy.a605.backend.domain.order.service.OrderExpiryService;
import com.ssafy.a605.backend.domain.ranking.dto.DailyResetStatus;
import com.ssafy.a605.backend.domain.ranking.dto.DailySettlementFinalizedEvent;
import com.ssafy.a605.backend.domain.season.entity.GameTick;
import com.ssafy.a605.backend.domain.season.entity.Season;
import com.ssafy.a605.backend.domain.season.repository.SeasonRepository;
import com.ssafy.a605.backend.domain.season.service.GameTickCatalog;
import com.ssafy.a605.backend.domain.ranking.dto.RankingSnapshot;
import com.ssafy.a605.backend.domain.ranking.dto.RankingSnapshot.Entry;
import com.ssafy.a605.backend.domain.ranking.entity.DailyHolding;
import com.ssafy.a605.backend.domain.ranking.entity.DailySettlement;
import com.ssafy.a605.backend.domain.ranking.repository.DailyHoldingRepository;
import com.ssafy.a605.backend.domain.ranking.repository.DailySettlementRepository;
import com.ssafy.a605.backend.domain.ranking.service.SettlementAggregator.Aggregate;
import com.ssafy.a605.backend.domain.replay.dto.PhaseBoundaryEvent;
import com.ssafy.a605.backend.domain.replay.entity.ReplayState;
import com.ssafy.a605.backend.domain.replay.service.ReplayService;
import com.ssafy.a605.backend.global.exception.BusinessException;

import lombok.extern.slf4j.Slf4j;

/**
 * 폐장 스냅샷 확정 ({@code rank.md} §폐장 스냅샷, 기획서 11.4.1).
 *
 * <p><b>폐장(16:40) 에 마지막 틱의 순위를 그대로 얼린다.</b> 순위를 다시 계산하지 않는다 —
 * {@link RankingRecalculator} 가 마지막 틱에 만든 {@link RankingSnapshot} 이 곧 폐장 시점의
 * 순위이고, 여기서 다시 돌리면 그 사이의 거래·탈퇴가 섞여 리더보드가 보여준 것과 다른 값이 얼린다.
 * 저장하는 것은 순위·수익률·총평가액이고, 복기 집계 컬럼은 {@link SettlementAggregator} 에 묻는다.
 *
 * <p><b>멱등이다.</b> 같은 일차로 다시 불려도 그 일차의 행이 하나라도 있으면 아무것도 쓰지 않는다
 * (전부 한 트랜잭션에 저장되므로 "일부만 있는" 상태는 없다). 재실행에서 덮어쓰지 않는 이유는
 * 이 행이 확정값이기 때문이다 — 나중에 다시 만들면 재시작 뒤의 스냅샷은 다른 틱 것일 수 있다.
 *
 * <p><b>폐장에 마감 보유도 함께 찍는다</b> (V42). 다음 일차 개장이 {@code positions} 를
 * 초기화하므로, 지우기 전에 남기지 않으면 그 일차에 무엇을 들고 끝냈는지가 영구히 사라진다.
 *
 * <p><b>다음 날 브리핑(11:00) 에 전 계좌를 시드로 되돌린다</b> ({@link #resetDaily}). 예전에는
 * 전날 총평가액을 일간 기준값으로 옮기기만 했는데, 압축 재생 구조에서 자산이 하루마다 수만
 * 배로 불어 순위가 첫날 한 번으로 결정됐다. 초기화하지 못한 계좌는 거래도 막힌다 —
 * {@code dailyBaseSeasonDay} 하나가 완료 표식이자 거래 게이트다.
 */
@Slf4j
@Service
public class DailySettlementService {

    private final RankingRecalculator rankings;
    private final DailySettlementRepository settlementRepository;
    /** 마감 보유 스냅샷. 폐장 정산과 같은 트랜잭션에 쓴다 (V42) */
    private final DailyHoldingRepository holdingRepository;
    private final AccountRepository accountRepository;
    /** 일차 초기화가 보유를 지운다. 스냅샷을 찍은 뒤다 */
    private final PositionRepository positionRepository;
    /** 초기화가 되돌릴 시드. 편성값이라 시즌에서 읽는다 */
    private final SeasonRepository seasonRepository;
    /** 폐장에 닫히지 않은 예약이 남아 있으면 초기화 전에 닫는다 */
    private final OrderExpiryService orderExpiries;
    /** 수동 재실행이 그 일차에 도달했는지 보는 시계 */
    private final ReplayService replay;
    /** 마감 보유를 그 일차 마지막 틱으로 평가한다 (V42) */
    private final AccountValuationService valuations;
    /** 기준이 되는 틱을 편성에서 가져온다 — 순위 스냅샷과 달리 재시작에 영향받지 않는다 */
    private final GameTickCatalog gameTicks;
    private final SettlementAggregator aggregator;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final TransactionTemplate write;
    /** 확정 행 조회를 이 시즌으로 좁힌다 — 일차는 시즌마다 1~3 으로 다시 시작한다 */
    private final long seasonId;

    public DailySettlementService(RankingRecalculator rankings, DailySettlementRepository settlementRepository,
                                  DailyHoldingRepository holdingRepository,
                                  AccountRepository accountRepository, PositionRepository positionRepository,
                                  SeasonRepository seasonRepository, OrderExpiryService orderExpiries,
                                  ReplayService replay, AccountValuationService valuations,
                                  GameTickCatalog gameTicks, SettlementAggregator aggregator,
                                  ApplicationEventPublisher events, Clock clock,
                                  PlatformTransactionManager manager,
                                  @Value("${app.replay.season-id:1}") long seasonId) {
        this.rankings = rankings;
        this.settlementRepository = settlementRepository;
        this.holdingRepository = holdingRepository;
        this.accountRepository = accountRepository;
        this.positionRepository = positionRepository;
        this.seasonRepository = seasonRepository;
        this.orderExpiries = orderExpiries;
        this.replay = replay;
        this.valuations = valuations;
        this.gameTicks = gameTicks;
        this.aggregator = aggregator;
        this.events = events;
        this.clock = clock;
        this.write = new TransactionTemplate(manager);
        this.seasonId = seasonId;
    }

    /**
     * 폐장이면 순위를 얼리고, 브리핑이면 전날 확정값으로 일간 기준값을 바꾼다.
     *
     * <p>예외는 여기서 끝낸다. 이 이벤트는 재생 폴러가 경계를 커밋한 뒤 같은 스레드에서 발행하므로,
     * 던지면 확정 실패가 아니라 재생 정지로 기록된다.
     *
     * @param event 커밋된 Phase 경계. 재생 도메인이 발행한다
     */
    @EventListener
    public void on(PhaseBoundaryEvent event) {
        try {
            if (event.isClose()) {
                finalizeDay(event.seasonDay());
            } else if (event.isBriefing() && event.seasonDay() > 1) {
                resetDaily(event.seasonDay());
            }
        } catch (RuntimeException e) {
            // SETTLEMENT-FAILED 는 운영이 잡는 표식이다. 이 리포에 경보 채널이 없어 로그가
            // 유일한 신호이고, 같은 사실을 화면에서 확인하는 방법은 폐장 직후 `ranking.finalized`
            // 가 오지 않는 것뿐이다. 던지지 않는 이유는 여전히 같다 — 정산 한 번의 실패보다
            // 재생이 멈춰 전원이 대기하는 쪽이 크다. 대신 다음 날 초기화가 같은 표식을
            // 한 번 더 남긴다 (resetDaily). **초기화가 실패한 계좌는 거래도 막힌다** —
            // dailyBaseSeasonDay 가 그 일차로 올라가지 않아 AccountLedgerService 의 게이트에
            // 걸린다.
            log.error("SETTLEMENT-FAILED day={} offset={}; replay continues, daily ranking/report missing",
                    event.seasonDay(), event.offset(), e);
        }
    }

    /**
     * 그 일차의 폐장 스냅샷을 저장하고 커밋 뒤 {@link DailySettlementFinalizedEvent} 를 발행한다.
     *
     * @param seasonDay 폐장한 일차
     */
    public void finalizeDay(short seasonDay) {
        RankingSnapshot snapshot = rankings.current();
        if (snapshot == null || snapshot.seasonDay() != seasonDay) {
            // 마지막 틱과 폐장 사이에 재시작하면 순위가 없다. 다른 일차 것을 얼리는 것보다 비는 편이 낫다.
            log.error("No ranking snapshot for season day {} at close (current: {}); settlement skipped",
                    seasonDay, snapshot == null ? null : snapshot.seasonDay());
            return;
        }
        Integer saved = write.execute(status -> {
            if (settlementRepository.existsBySeasonDay(seasonId, seasonDay)) {
                log.warn("Daily settlement for season day {} already exists; skipped", seasonDay);
                return null;
            }
            // 마지막 틱과 폐장 사이에 탈퇴한 계좌는 스냅샷에는 있지만 DB 에는 없다. 그 한 건이 FK 위반으로
            // 전 계좌의 확정을 롤백시키지 않도록, 지금 존재하는 계좌만 얼린다 (기획서 11.1.5 즉시 제외).
            // 계좌를 식별자만 모으지 않고 그대로 들고 있는다. 낙폭 스냅샷이 이 행에서 나온다 (V38).
            Map<Long, Account> existing = accountRepository
                    .findAllById(snapshot.bySeasonRank().stream().map(Entry::accountId).toList()).stream()
                    .collect(Collectors.toMap(Account::getId, Function.identity()));
            List<Entry> entries = snapshot.bySeasonRank().stream()
                    .filter(entry -> existing.containsKey(entry.accountId()))
                    .toList();
            if (entries.size() < snapshot.bySeasonRank().size()) {
                log.warn("{} ranked account(s) no longer exist at close of season day {}; excluded from settlement",
                        snapshot.bySeasonRank().size() - entries.size(), seasonDay);
            }
            Map<Long, Aggregate> aggregates = aggregator.aggregate(seasonDay, entries.stream().map(Entry::accountId).toList());
            Instant finalizedAt = Instant.now(clock);
            List<DailySettlement> rows = entries.stream()
                    .map(entry -> row(seasonDay, entry, aggregates.getOrDefault(entry.accountId(), Aggregate.EMPTY),
                            existing.get(entry.accountId()).getMaxDrawdown(), finalizedAt))
                    .toList();
            // **saveAll 이 돌려준 것을 쓴다.** 기본키가 배정 복합 키라 Spring Data 가 이 행을
            // "새 것" 으로 보지 못하고 merge 로 보낸다 — 원본 인스턴스는 준영속으로 남아,
            // 거기에 스냅샷을 찍으면 조용히 버려진다.
            snapshotHoldings(seasonDay, settlementRepository.saveAll(rows), existing, finalizedAt);
            return rows.size();
        });
        if (saved == null) {
            return;
        }
        log.info("Daily settlement finalized for season day {}: {} accounts", seasonDay, saved);
        events.publishEvent(new DailySettlementFinalizedEvent(seasonDay, saved));
    }

    /**
     * 그 일차의 마감 보유와 마감 현금을 확정 행에 붙여 저장한다 (V42).
     *
     * <p><b>폐장에 찍는다.</b> 다음 날 브리핑이 아니라 여기인 이유가 셋이다 — ① 3 일차에는
     * 다음 브리핑이 없어 브리핑에 두면 마지막 날 기록이 통째로 빠진다, ② 확정 행과 같은
     * 트랜잭션이라 {@code daily_holdings} 의 복합 외래키가 항상 성립한다, ③ 정산이 본 그
     * 틱과 스냅샷이 본 틱이 같아진다.
     *
     * <p><b>스냅샷 없이 표식만 남기지 않는다.</b> 보유 종목의 종가가 없어 평가하지 못한
     * 계좌는 {@link AccountValuationService#holdingsAsOf} 가 아예 돌려주지 않고, 그 계좌의
     * {@code holdingsSnapshottedAt} 은 비어 있게 둔다. 그 빈 값이 다음 날 초기화를 막는다.
     *
     * <p><b>합계를 정산 평가액과 대조하되 막지는 않는다.</b> 확정 평가액은 마지막 틱의 순위
     * 스냅샷이고 이 재계산은 폐장 시점의 실제 잔고라, 그 사이 31 분의 정상 거래로 값이
     * 갈릴 수 있다. 차이는 기록으로만 남긴다 — 표식을 빼면 늦은 거래 한 건이 다음 날
     * 전 계좌의 초기화를 막는다.
     */
    private void snapshotHoldings(short seasonDay, List<DailySettlement> rows,
                                  Map<Long, Account> accounts, Instant finalizedAt) {
        GameTick lastTick = gameTicks.lastOf(seasonDay).orElse(null);
        if (lastTick == null) {
            log.error("SNAPSHOT-FAILED day={} has no scheduled tick; closing holdings not recorded", seasonDay);
            return;
        }
        Map<Long, List<HoldingValuation>> holdings = valuations.holdingsAsOf(lastTick,
                rows.stream().map(DailySettlement::getAccountId).toList());
        BigDecimal rate = lastTick.getUsdKrw();
        List<DailyHolding> saved = new ArrayList<>();
        int marked = 0;
        for (DailySettlement row : rows) {
            List<HoldingValuation> held = holdings.get(row.getAccountId());
            if (held == null) {
                continue;
            }
            Account account = accounts.get(row.getAccountId());
            BigDecimal recomputed = held.stream().map(HoldingValuation::valuationKrw)
                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                    .add(account.getKrwCash())
                    .add(account.getUsdCash().multiply(rate));
            if (recomputed.compareTo(row.getTotalValuationKrw()) != 0) {
                // **막지 않는다.** 두 값은 서로 다른 시점을 본다 — 확정 평가액은 그 일차
                // 마지막 틱의 순위 스냅샷이고(재계산하지 않는 것이 정책이다), 이 재계산은
                // 폐장 시점의 실제 잔고다. 마지막 틱은 폐장보다 31 분 앞서 끝나므로
                // (season_days: 마지막 틱 19,440 초 · 폐장 21,300 초) 그 사이의 정상 매도·환전
                // 한 건으로 값이 갈린다. 여기서 표식을 빼면 그 하루의 늦은 거래 한 건이
                // 다음 날 <b>전 계좌의 초기화를 막는다.</b>
                //
                // 기록 보존은 이 차이와 무관하다 — 저장하는 것은 폐장 시점의 실제 보유이고,
                // 그것은 지금 손에 있다. 평가할 종가가 없는 계좌는 이미 위에서 빠졌다.
                log.warn("Closing snapshot differs from settled valuation day={} account={}:"
                                + " settled={} atClose={} (trades after the last tick)",
                        seasonDay, row.getAccountId(), row.getTotalValuationKrw().toPlainString(),
                        recomputed.toPlainString());
            }
            held.forEach(holding -> saved.add(new DailyHolding(seasonDay, holding.accountId(),
                    holding.instrumentId(), holding.quantity(), holding.totalCost(),
                    holding.valuationKrw())));
            row.recordClosingSnapshot(account.getKrwCash(), account.getUsdCash(),
                    lastTick.getTickSeq(), finalizedAt);
            marked++;
        }
        // 확정 행을 먼저 내보낸다. daily_holdings 가 그 행을 복합 외래키로 참조하므로,
        // 두 insert 의 순서가 뒤집히면 제약 위반으로 폐장 전체가 롤백된다.
        settlementRepository.flush();
        holdingRepository.saveAll(saved);
        log.info("Closing holdings snapshot for season day {}: {} rows, {} of {} accounts marked",
                seasonDay, saved.size(), marked, rows.size());
    }

    /**
     * 일차 경계에서 <b>전 계좌를 시드로 되돌린다</b> (시즌 2 일차 초기화).
     *
     * <p><b>왜 기준값 갱신에서 자산 초기화로 바뀌었나.</b> 실제 주가 6 년을 3 일로 압축
     * 재생하는 구조라 게임 하루가 실제 34 초이고, 1 일차에만 584 틱이 지나간다. 1,726 틱
     * 전부에 오르는 종목이 최소 하나 있어 미래를 아는 참가자에게는 손해 보는 틱이 없다 —
     * 시즌 2 1 일차에 1 위가 1 억을 6 조 5,900 억으로 불렸다. 자산을 그대로 넘기면 다음 날
     * 그 위에서 또 곱해지고, 순위는 첫날 한 번으로 결정된다. 매일 같은 자산에서 출발하게
     * 하는 것이 이 변경의 전부이고, <b>과거 시세를 아는 정보 우위 자체를 없애지는 못한다.</b>
     *
     * <p><b>매도 체결을 만들지 않는다.</b> 전일에 귀속시키면 확정된 집계와 어긋나고, 당일에
     * 귀속시키면 수수료·체결 수·승률·포인트를 오염시킨다. 대신 폐장에 찍어 둔
     * {@code daily_holdings} 가 그 일차의 보유 기록을 갖는다.
     *
     * <p><b>통과 조건이 셋이다.</b> ① 전날 정산이 있고, ② 그 정산 전부에 마감 보유 스냅샷
     * 표식이 있고, ③ 초기화 대상 중 전날 정산이 없는데 보유가 있는 계좌가 없어야 한다.
     * 하나라도 어긋나면 <b>아무것도 지우지 않는다</b> — 기록 없이 자산만 사라지는 것이
     * 초기화하지 못하는 것보다 나쁘다.
     *
     * <p><b>멱등이다.</b> 계좌마다 {@code dailyBaseSeasonDay} 로 판정하므로, 이미 초기화되고
     * 그 뒤 거래까지 한 계좌는 다시 불려도 손대지 않는다. 2 일차에 새로 만들어진 계좌도
     * 생성 시점부터 값이 2 라 대상이 아니다.
     *
     * <p><b>전 계좌를 돈다.</b> 예전 기준값 갱신은 그 틱에 평가된 계좌만 순회했는데, 시세가
     * 없어 평가에서 빠진 계좌가 초기화에서도 빠지면 그 계좌만 어제 자산으로 거래한다.
     *
     * @param seasonDay 브리핑에 들어간 일차. 이 값이 초기화 완료 표식이 된다
     */
    public void resetDaily(short seasonDay) {
        short previous = (short) (seasonDay - 1);
        // 정산이 통째로 없는 날은 건드리지 않는다. 확정 행이 없다는 것은 폐장 정산이
        // 실패했다는 뜻이고, 그 표식을 지우지 않는다.
        if (!settlementRepository.existsBySeasonDay(seasonId, previous)) {
            log.error("SETTLEMENT-FAILED day={} has no settlement; accounts of day {} left untouched"
                    + " — trading stays blocked until the reset is re-run", previous, seasonDay);
            return;
        }
        long unrecorded = settlementRepository.countMissingHoldingsSnapshot(seasonId, previous);
        if (unrecorded > 0) {
            log.error("RESET-BLOCKED day={} has {} settled account(s) without a closing holdings"
                    + " snapshot; accounts of day {} left untouched", previous, unrecorded, seasonDay);
            return;
        }
        write.executeWithoutResult(status -> {
            List<Account> targets = lockedTargets(seasonDay);
            if (targets.isEmpty()) {
                log.info("Daily reset for season day {}: nothing to do, all accounts already reset", seasonDay);
                return;
            }
            List<Long> targetIds = targets.stream().map(Account::getId).toList();
            requireRecorded(previous, targetIds);
            // 폐장에 닫혔어야 할 예약이 남아 있으면 먼저 닫는다. 그냥 두면 잠긴 현금의
            // 근거가 사라진 채 다음 날 체결되어, 초기화된 잔고에서 어제 주문이 돈을 쓴다.
            expireLeftoverOrders(targetIds);

            BigDecimal seed = seasonRepository.findById(seasonId).map(Season::getSeedKrw)
                    .orElseThrow(() -> new IllegalStateException("시즌 편성이 없습니다: " + seasonId));
            targets.forEach(account -> account.resetForSeasonDay(seed, seasonDay));
            long removed = positionRepository.deleteByAccountIdIn(targetIds);
            log.info("Daily reset for season day {}: {} accounts back to seed {}, {} position row(s) removed",
                    seasonDay, targets.size(), seed.toPlainString(), removed);
        });
    }

    /**
     * 관리자의 수동 재실행. 브리핑 경계에 얹힌 초기화가 돌지 않았을 때 쓴다.
     *
     * <p><b>일차를 검증하는 것이 이 메서드의 존재 이유다.</b> {@link #resetDaily} 자체는
     * 경계 이벤트가 주는 값을 믿는 내부 연산이라 범위를 보지 않는다. 그대로 열면
     * {@code POST /api/v1/admin/daily-reset/4} 가 3일차 정산·스냅샷 조건을 통과해
     * <b>시즌 최종 자산을 전부 시드로 지운다.</b> 1일차 폐장 직후의 {@code /2} 도 마찬가지로
     * 브리핑 전에 자산을 되돌린다.
     *
     * @param seasonDay 초기화할 일차
     * @return 재실행 뒤의 점검 결과
     * @throws BusinessException 초기화가 없는 일차일 때 (400),
     *                           아직 그 일차에 도달하지 않았을 때 (409)
     */
    public DailyResetStatus rerunDailyReset(short seasonDay) {
        requireResettableDay(seasonDay);
        if (replay.clock().seasonDay() < seasonDay) {
            throw new BusinessException(HttpStatus.CONFLICT,
                    "아직 %d일차에 도달하지 않았습니다.".formatted(seasonDay));
        }
        resetDaily(seasonDay);
        return resetStatus(seasonDay);
    }

    /** 초기화가 도는 일차는 2일차부터다 — 1일차는 시즌 참가가 곧 시드 지급이다. */
    private static void requireResettableDay(short seasonDay) {
        if (seasonDay < 2 || seasonDay > ReplayState.FINAL_DAY) {
            throw new BusinessException(HttpStatus.BAD_REQUEST,
                    "초기화할 수 있는 일차는 2~%d 입니다.".formatted(ReplayState.FINAL_DAY));
        }
    }

    /**
     * 개장 전 점검. 그 일차의 초기화가 끝났는지와 전날 기록이 보존됐는지를 한 번에 본다.
     *
     * @param seasonDay 점검할 일차
     * @return 계좌 수·미초기화 수·전일 확정 수·스냅샷 누락 수
     */
    public DailyResetStatus resetStatus(short seasonDay) {
        requireResettableDay(seasonDay);
        short previous = (short) (seasonDay - 1);
        return new DailyResetStatus(seasonDay,
                accountRepository.countBySeasonId(seasonId),
                accountRepository.countBySeasonIdAndDailyBaseSeasonDayLessThan(seasonId, seasonDay),
                settlementRepository.countBySeasonDay(seasonId, previous),
                settlementRepository.countMissingHoldingsSnapshot(seasonId, previous));
    }

    /**
     * 초기화할 계좌를 <b>행 잠금 안에서</b> 확정한다.
     *
     * <p><b>완료 여부를 잠근 뒤 한 번 더 본다.</b> 브리핑 경계에는 거래가 닫혀 있지만 수동
     * 재실행({@code POST /api/v1/admin/daily-reset})은 장중에도 부를 수 있고, 잠그지 않으면
     * 그 사이 체결된 주문이 초기화의 덮어쓰기에 사라진다. 두 번 겹쳐 돌아도 뒤엣것은 대상이
     * 비어 아무 일도 하지 않는다.
     *
     * <p>계좌 식별자 순서로 잠근다 — {@code OrderExpiryService} 와 같은 순서이며, 다르면
     * 두 스윕이 교착한다.
     */
    private List<Account> lockedTargets(short seasonDay) {
        // **식별자만 읽는다.** 엔티티로 읽으면 그 행이 영속성 컨텍스트에 올라가고, 뒤이어
        // 잠금 조회를 해도 이미 관리 중인 인스턴스의 상태는 갱신되지 않는다 — 겹친 초기화가
        // 앞엣것의 커밋을 기다린 뒤에도 낡은 표식을 보게 된다.
        List<Long> candidates = accountRepository.findIdsNeedingDailyReset(seasonId, seasonDay);
        List<Account> targets = new ArrayList<>(candidates.size());
        for (Long accountId : candidates) {
            accountRepository.findWithLockById(accountId)
                    .filter(account -> account.needsDailyReset(seasonDay))
                    .ifPresent(targets::add);
        }
        return targets;
    }

    /**
     * 초기화 대상 중 <b>전날 정산이 없는데 보유가 있는</b> 계좌를 찾는다.
     *
     * <p>그런 계좌의 보유는 {@code daily_holdings} 에도 없다 (스냅샷이 확정 행에 매달린다).
     * 지우면 어디에도 남지 않으므로 초기화 전체를 되돌린다 — 폐장 이후에 만들어진 계좌는
     * 보유가 없어 여기 걸리지 않는다.
     */
    private void requireRecorded(short previous, List<Long> targetIds) {
        Set<Long> settled = Set.copyOf(settlementRepository.findAccountIdsBySeasonDay(seasonId, previous));
        List<Long> missing = positionRepository
                .findByAccountIdInAndQuantityGreaterThan(targetIds, 0).stream()
                .map(Position::getAccountId)
                .filter(accountId -> !settled.contains(accountId))
                .distinct()
                .toList();
        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "전날(%d일차) 정산이 없는데 보유가 남은 계좌가 있어 초기화를 중단합니다: %s"
                            .formatted(previous, missing));
        }
    }

    /** 폐장 만료를 놓친 예약을 닫아 잠긴 현금을 돌려준다. 정상 경로라면 대상이 없다. */
    private void expireLeftoverOrders(List<Long> targetIds) {
        Set<Long> targets = Set.copyOf(targetIds);
        List<Long> leftover = orderExpiries.accountsWithOpenOrders().stream()
                .filter(targets::contains)
                .toList();
        if (leftover.isEmpty()) {
            return;
        }
        log.error("Daily reset found open orders left from the previous day on accounts {};"
                + " expiring them before the reset", leftover);
        Instant at = Instant.now(clock);
        leftover.forEach(accountId -> orderExpiries.expireFor(accountId, at));
    }

    /**
     * 확정 한 행.
     *
     * <p>{@code maxDrawdown} 만 집계가 아니라 <b>계좌에서 그대로 온다.</b> 매 틱 스윕이 이미
     * 갱신해 둔 값을 이 시점에 고정하는 것이고, 여기서 다시 계산할 수 있는 값이 아니다 —
     * 평가액 시계열이 없어서 폐장에 되짚을 재료가 없다 (V38).
     */
    private static DailySettlement row(short seasonDay, Entry entry, Aggregate aggregate,
                                       BigDecimal maxDrawdown, Instant finalizedAt) {
        return new DailySettlement(seasonDay, entry.accountId(), entry.seasonRank(), entry.dailyRank(),
                entry.seasonReturn(), entry.dailyReturn(), entry.totalValuationKrw(),
                aggregate.executionCount(), aggregate.totalFeeKrw(), aggregate.realizedPnlKrw(),
                maxDrawdown, aggregate.bestExecutionId(), aggregate.worstExecutionId(),
                aggregate.patternTags(), finalizedAt);
    }
}

package com.ssafy.a605.backend.domain.report.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ssafy.a605.backend.domain.account.entity.Account;
import com.ssafy.a605.backend.domain.account.repository.AccountRepository;
import com.ssafy.a605.backend.domain.account.service.AccountService;
import com.ssafy.a605.backend.domain.market.entity.Currency;
import com.ssafy.a605.backend.domain.market.entity.Instrument;
import com.ssafy.a605.backend.domain.market.repository.InstrumentRepository;
import com.ssafy.a605.backend.domain.market.service.MarketPriceService;
import com.ssafy.a605.backend.domain.market.service.MarketPriceService.AsOfPrice;
import com.ssafy.a605.backend.domain.order.entity.OrderSide;
import com.ssafy.a605.backend.domain.order.repository.ExecutionRepository;
import com.ssafy.a605.backend.domain.order.repository.ExecutionRepository.SeasonAggregate;
import com.ssafy.a605.backend.domain.ranking.dto.PointStanding;
import com.ssafy.a605.backend.domain.ranking.entity.DailySettlement;
import com.ssafy.a605.backend.domain.ranking.repository.DailySettlementRepository;
import com.ssafy.a605.backend.domain.ranking.service.AwardService;
import com.ssafy.a605.backend.domain.ranking.service.PointStandingsCalculator;
import com.ssafy.a605.backend.domain.replay.entity.ReplayState;
import com.ssafy.a605.backend.domain.replay.service.ReplayService;
import com.ssafy.a605.backend.domain.report.dto.SeasonReportResponse;
import com.ssafy.a605.backend.domain.report.dto.SeasonReportResponse.Benchmark;
import com.ssafy.a605.backend.domain.report.dto.SeasonReportResponse.InvestorType;
import com.ssafy.a605.backend.domain.report.dto.SeasonReportResponse.Metrics;
import com.ssafy.a605.backend.domain.report.entity.SeasonReport;
import com.ssafy.a605.backend.domain.report.repository.SeasonReportRepository;
import com.ssafy.a605.backend.domain.report.repository.SeasonReportTextRepository;
import com.ssafy.a605.backend.domain.season.repository.GameTickRepository;
import com.ssafy.a605.backend.domain.season.repository.GameTickRepository.GameDateRange;
import com.ssafy.a605.backend.global.exception.BusinessException;
import com.ssafy.a605.backend.global.response.Amounts;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 시즌 리포트의 확정과 조회 ({@code rpt.md} §GET /api/v1/reports/season, 기획서 13.3).
 *
 * <p><b>3일차 폐장에 한 번 얼린다.</b> 지표를 조회 시점에 계산하면 같은 화면이 호출마다 달라질
 * 수 있고({@code _common.md} §6.3), 무엇보다 <b>최대 낙폭은 시즌이 지나간 뒤에는 만들 수
 * 없다</b> — 평가액 시계열이 어디에도 저장되지 않기 때문이다. 그래서 낙폭만은 매 틱 스윕이
 * 계좌에 누적해 두고({@code Account.observeValuation}) 여기서는 그 값을 옮긴다.
 *
 * <p><b>발표 전에는 열리지 않는다.</b> 폐장에 만들어지지만 발표까지 20 분 동안 409 다 —
 * 판정은 {@link AwardService#lastAnnouncedDay} 하나를 일일 복기와 함께 쓴다. 두 곳이 같은
 * 시점을 다르게 보면 시상은 보이는데 리포트는 닫힌 순간이 생긴다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SeasonReportService {

    /** 평균 자산의 분모. 시작과 끝 두 점의 평균이다. */
    private static final BigDecimal TWO = BigDecimal.valueOf(2);

    /** 회전율의 자릿수. "자산의 4.12 배" 처럼 배수로 읽는 값이라 비율과 같은 두 자리다. */
    private static final int TURNOVER_SCALE = Amounts.RATIO_SCALE;

    private final SeasonReportRepository reportRepository;
    private final SeasonReportTextRepository textRepository;
    private final DailySettlementRepository settlementRepository;
    private final AccountRepository accountRepository;
    private final ExecutionRepository executionRepository;
    private final InstrumentRepository instrumentRepository;
    private final GameTickRepository gameTickRepository;
    private final MarketPriceService marketPrices;
    private final AccountService accounts;
    private final ReplayService replay;

    @Value("${app.replay.season-id:1}")
    private long seasonId;

    /** 비교 기준선의 나스닥 종목 (기획서 13.3.3). 유니버스 안에 있다. */
    @Value("${app.report.benchmark.us-symbol:QQQ}")
    private String usBenchmarkSymbol;

    /** 비교 기준선의 국내 종목. 심볼은 적재 편성값이라 이름이 바뀌어도 계약은 그대로다. */
    @Value("${app.report.benchmark.kr-symbol:069500}")
    private String krBenchmarkSymbol;

    /**
     * 3일차 폐장 스냅샷에서 시즌 리포트를 만든다.
     *
     * <p><b>멱등이다.</b> 이미 확정된 계좌가 하나라도 있으면 전체를 건너뛴다 — 같은 정책을
     * {@code daily_settlements} 가 쓴다. 다시 계산하면 그 사이 거래가 없더라도 회전율의 분모인
     * 최종 평가액이 다른 틱을 볼 수 있다.
     *
     * @param seasonDay 폐장한 일차. 마지막 일차가 아니면 아무 일도 하지 않는다
     */
    @Transactional
    public void finalizeSeason(short seasonDay) {
        if (seasonDay != ReplayState.FINAL_DAY) {
            return;
        }
        List<DailySettlement> settled = settlementRepository.findBySeasonDay(seasonId, seasonDay);
        if (settled.isEmpty()) {
            log.error("No day {} settlement; season report skipped", seasonDay);
            return;
        }
        List<Long> accountIds = settled.stream().map(DailySettlement::getAccountId).toList();
        if (reportRepository.existsByAccountIdIn(accountIds)) {
            log.warn("Season report already exists; skipped");
            return;
        }
        // 폐장과 이 시점 사이에 탈퇴한 계좌는 확정 행에는 있지만 DB 에는 없다. 그 한 건이 외래키
        // 위반으로 전원의 확정을 되돌리지 않게 지금 존재하는 계좌만 쓴다 (기획서 11.1.5).
        Map<Long, Account> accountsById = accountRepository.findAllById(accountIds).stream()
                .collect(Collectors.toMap(Account::getId, Function.identity()));
        Map<Long, SeasonAggregate> aggregates = executionRepository
                .aggregateSeason(accountIds, OrderSide.SELL, Currency.USD).stream()
                .collect(Collectors.toMap(SeasonAggregate::accountId, Function.identity()));
        // 최종 성적은 마지막 일차 행 하나로 만들 수 없다. 일차 경계마다 자산이 시드로
        // 돌아가므로 그 행의 season_return·season_rank 는 3일차 하루의 값이다.
        Map<Long, List<DailySettlement>> byAccount = settlementsByAccount();
        Map<Long, PointStanding> points = PointStandingsCalculator
                .rank(settlementRepository.aggregatePoints(seasonId, (short) ReplayState.FINAL_DAY))
                .stream().collect(Collectors.toMap(PointStanding::accountId, Function.identity()));

        List<SeasonReport> rows = settled.stream()
                .filter(row -> accountsById.containsKey(row.getAccountId()))
                .map(row -> report(row, accountsById.get(row.getAccountId()),
                        aggregates.get(row.getAccountId()),
                        byAccount.getOrDefault(row.getAccountId(), List.of()),
                        points.get(row.getAccountId())))
                .toList();
        reportRepository.saveAll(rows);
        log.info("Season report finalized: {} accounts", rows.size());
    }

    /**
     * 한 계좌의 시즌 리포트 한 행.
     *
     * <p><b>거래가 없는 계좌도 행을 만든다.</b> 승률과 회전율이 0 인 리포트가 나올 뿐이고,
     * 걸러내면 그 사람만 시즌 화면이 404 가 된다.
     */
    private SeasonReport report(DailySettlement settled, Account account, SeasonAggregate season,
                                List<DailySettlement> days, PointStanding point) {
        BigDecimal fee = season == null ? BigDecimal.ZERO : season.totalFeeKrw();
        BigDecimal traded = season == null ? BigDecimal.ZERO : season.tradedAmountKrw();
        return new SeasonReport(
                settled.getAccountId(),
                finalReturn(days),
                deepestDrawdown(days),
                winRate(season),
                turnover(traded, averageAsset(account, days)),
                fee,
                point == null ? settled.getSeasonRank() : point.rank(),
                settled.getFinalizedAt());
    }

    /**
     * 최종 수익률(%) = <b>참가한 일차의 일간 수익률 합</b>.
     *
     * <p><b>복리 누적이 아니다.</b> 일차 경계마다 자산이 시드로 돌아가므로 하루의 성과가
     * 다음 날 밑천이 되지 않는다 — 곱해서 합성하면 실제로 아무도 받지 못한 성과가 된다.
     * 화면의 포인트 탭과 일일 결과지의 누적 블록이 같은 값을 같은 이름으로 쓴다.
     *
     * <p><b>{@code season_return} 을 쓰지 않는다.</b> 그 값은 초기화 이후 그날 수익률과
     * 같아졌고, 마지막 일차 행에서 읽으면 3일차 하루가 시즌 전체로 나간다.
     */
    private static BigDecimal finalReturn(List<DailySettlement> days) {
        return days.stream().map(DailySettlement::getDailyReturn)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * 최대 낙폭(%) = 참가한 일차의 낙폭 중 <b>가장 깊은 값</b>. 음수이므로 수치상 최솟값이다.
     *
     * <p><b>{@code accounts.max_drawdown} 을 쓰지 않는다.</b> 초기화가 그 값을 매일 0 으로
     * 되돌리므로 마지막 일차의 낙폭만 남는다. 폐장에 찍어 둔 일차별 스냅샷이 유일한 기록이다
     * (V38).
     *
     * <p>스냅샷이 하나도 없으면 0 이다 — 저장 컬럼이 {@code NOT NULL} 이라 비울 수 없고,
     * "못 쟀다" 와 "내려온 적 없다" 를 여기서 가르지 않는다.
     */
    private static BigDecimal deepestDrawdown(List<DailySettlement> days) {
        return days.stream().map(DailySettlement::getMaxDrawdown)
                .filter(java.util.Objects::nonNull)
                .min(BigDecimal::compareTo)
                .orElse(BigDecimal.ZERO);
    }

    /** 계좌별 확정 행을 일차 순서로 모은다. 시즌이 3일이라 질의 세 번이면 끝난다. */
    private Map<Long, List<DailySettlement>> settlementsByAccount() {
        Map<Long, List<DailySettlement>> byAccount = new java.util.HashMap<>();
        for (short day = 1; day <= ReplayState.FINAL_DAY; day++) {
            for (DailySettlement row : settlementRepository.findBySeasonDay(seasonId, day)) {
                byAccount.computeIfAbsent(row.getAccountId(), id -> new ArrayList<>()).add(row);
            }
        }
        return byAccount;
    }

    /**
     * 승률(%). 실현 손익이 양수인 매도의 비율이고, 매도가 없으면 0 이다 — 나누지 않는 것이
     * "승률 0%" 보다 정확하지만 저장 컬럼이 {@code NOT NULL} 이라 0 으로 둔다.
     */
    private static BigDecimal winRate(SeasonAggregate season) {
        if (season == null || season.sellCount() == 0) {
            return BigDecimal.ZERO;
        }
        return Amounts.percent(BigDecimal.valueOf(season.winCount()),
                BigDecimal.valueOf(season.sellCount()));
    }

    /** 회전율 = 총 거래대금 ÷ 일차별 평균 자산의 합. 자산이 0 이면 나눌 것이 없다. */
    private static BigDecimal turnover(BigDecimal traded, BigDecimal averageAsset) {
        if (averageAsset.signum() == 0) {
            return BigDecimal.ZERO;
        }
        return traded.divide(averageAsset, TURNOVER_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 회전율의 분모 ({@code rpt.md} 열린 질문 2 의 답).
     *
     * <p><b>일차별 평균 자산을 더한다.</b> 일차 하나의 평균 자산은 그 일차 시작 자산(초기화가
     * 매일 시드로 되돌린다)과 마감 평가액의 평균이다. 시즌 전체를 두 점으로 잡으면 분자는
     * 사흘치 거래대금인데 분모는 하루치 자산이라, 회전율이 거래량이 아니라 <b>날 수</b>에
     * 비례해 커진다.
     *
     * <p>진짜 시간 가중 평균을 쓰려면 평가액 시계열이 필요한데 그것은 저장하지 않는다(최대
     * 낙폭이 매 틱 누적으로 만들어지는 것과 같은 이유다). 두 점의 평균은 그 둘 사이에서 가장
     * 값싼 근사이고, 일일 결과지의 누적 회전율이 같은 근사를 쓴다.
     *
     * <p><b>참가하지 않은 일차는 더하지 않는다.</b> 그 일차 확정 행이 없으면 자산도 거래대금도
     * 없으므로, 분모에 넣으면 중도 합류자의 회전율만 낮아진다.
     */
    private static BigDecimal averageAsset(Account account, List<DailySettlement> days) {
        return days.stream()
                .map(day -> account.getStartingBalanceKrw().add(day.getTotalValuationKrw())
                        .divide(TWO, RoundingMode.HALF_UP))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * 내 시즌 리포트.
     *
     * @param userId 세션의 User 식별자
     * @return 저장된 지표와 조회 시점에 만든 비교 기준선
     * @throws BusinessException 3일차 정산 발표 전이면 409, 그 시즌 계좌나 확정 행이 없으면 404
     */
    @Transactional(readOnly = true)
    public SeasonReportResponse detail(Long userId) {
        if (ReplayState.FINAL_DAY > AwardService.lastAnnouncedDay(replay.clock())) {
            throw new BusinessException(HttpStatus.CONFLICT, "시즌 정산 발표 후에 열람할 수 있습니다.");
        }
        Long accountId = accounts.accountId(userId);
        SeasonReport report = reportRepository.findById(accountId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND,
                        "시즌 리포트가 없습니다."));
        return new SeasonReportResponse(
                report.getFinalizedAt(),
                new Metrics(report.getFinalReturn(), report.getMaxDrawdown(), report.getWinRate(),
                        report.getTurnover(), Amounts.of(report.getTotalFeeKrw(),
                        Amounts.KRW_SCALE)),
                benchmarks(report.getFinalReturn()),
                report.getFinalRank(),
                settlementRepository.countBySeasonDay(seasonId, (short) ReplayState.FINAL_DAY),
                investorType(accountId),
                null);
    }

    /**
     * AI 가 판정한 투자자 유형. 없으면 {@code null} 이다.
     *
     * <p><b>유형이 없다고 리포트를 막지 않는다.</b> 지표는 3일차 폐장에 이미 얼려졌고
     * 문구는 그 뒤 외부 호출이 성공해야 생긴다 — 자리를 비운 채로 연다 (요청서 v5 §6).
     */
    private InvestorType investorType(Long accountId) {
        return textRepository.findById(accountId)
                .map(text -> new InvestorType(text.getTypeName(),
                        text.getTypeDescription(), text.getVerdict()))
                .orElse(null);
    }

    /**
     * 비교 기준선 (기획서 13.3.3 "가만히 있었다면 vs 나").
     *
     * <p>구간은 <b>1일차 첫 거래일부터 3일차 마지막 거래일까지</b>다 — 사용자가 본 재생 구간
     * 전체이고, 내 최종 수익률의 구간과 같아야 나란히 놓을 수 있다.
     *
     * <p><b>종가를 찾지 못한 기준선은 빠진다.</b> 적재가 덜 됐거나 그 종목이 유니버스에서 빠진
     * 경우인데, 한 줄이 없다고 시즌 리포트 전체를 닫을 이유가 없다 — "나"는 항상 남는다.
     */
    private List<Benchmark> benchmarks(BigDecimal finalReturn) {
        List<Benchmark> benchmarks = new ArrayList<>(3);
        GameDateRange first = gameTickRepository.findGameDateRange(seasonId, (short) 1);
        GameDateRange last = gameTickRepository
                .findGameDateRange(seasonId, (short) ReplayState.FINAL_DAY);
        if (first.fromGameDate() != null && last.toGameDate() != null) {
            Map<String, Instrument> instruments = benchmarkInstruments();
            Map<Long, AsOfPrice> opens = marketPrices.pricesAsOf(
                    instruments.values().stream().map(Instrument::getId).toList(),
                    first.fromGameDate());
            Map<Long, AsOfPrice> closes = marketPrices.pricesAsOf(
                    instruments.values().stream().map(Instrument::getId).toList(),
                    last.toGameDate());
            add(benchmarks, instruments.get(usBenchmarkSymbol), opens, closes);
            add(benchmarks, instruments.get(krBenchmarkSymbol), opens, closes);
        }
        benchmarks.add(new Benchmark(SeasonReportResponse.ME, finalReturn));
        return benchmarks;
    }

    private Map<String, Instrument> benchmarkInstruments() {
        return List.of(usBenchmarkSymbol, krBenchmarkSymbol).stream().distinct()
                .map(symbol -> instrumentRepository.findBySeasonIdAndSymbol(seasonId, symbol))
                .flatMap(java.util.Optional::stream)
                .collect(Collectors.toMap(Instrument::getSymbol, Function.identity()));
    }

    /**
     * 단순 보유 수익률 한 줄. 이름은 <b>종목명 + " 단순 보유"</b> 다 — 계약의 예시
     * ("QQQ 단순 보유" · "KODEX 200 단순 보유")가 곧 유니버스의 종목명이라, 문구를 따로
     * 박아 두면 종목이 바뀐 날 화면만 옛 이름을 말한다 ({@code rpt.md} 13.3.3).
     */
    private static void add(List<Benchmark> benchmarks, Instrument instrument,
                            Map<Long, AsOfPrice> opens, Map<Long, AsOfPrice> closes) {
        if (instrument == null) {
            return;
        }
        AsOfPrice open = opens.get(instrument.getId());
        AsOfPrice close = closes.get(instrument.getId());
        if (open == null || close == null || open.close().signum() == 0) {
            return;
        }
        benchmarks.add(new Benchmark(instrument.getName() + " 단순 보유",
                Amounts.percent(close.close().subtract(open.close()), open.close())));
    }

}

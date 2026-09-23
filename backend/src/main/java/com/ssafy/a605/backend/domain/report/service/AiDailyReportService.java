package com.ssafy.a605.backend.domain.report.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.ssafy.a605.backend.domain.account.entity.Account;
import com.ssafy.a605.backend.domain.account.repository.AccountRepository;
import com.ssafy.a605.backend.domain.market.entity.Instrument;
import com.ssafy.a605.backend.domain.market.repository.DailyCloseRepository;
import com.ssafy.a605.backend.domain.market.repository.InstrumentRepository;
import com.ssafy.a605.backend.domain.market.service.MarketPriceService;
import com.ssafy.a605.backend.domain.market.service.MarketPriceService.AsOfPrice;
import com.ssafy.a605.backend.domain.order.entity.OrderSide;
import com.ssafy.a605.backend.domain.order.repository.ExecutionRepository;
import com.ssafy.a605.backend.domain.order.repository.ExecutionRepository.DailyTrade;
import com.ssafy.a605.backend.domain.ranking.dto.PointStanding;
import com.ssafy.a605.backend.domain.ranking.entity.DailySettlement;
import com.ssafy.a605.backend.domain.ranking.service.PointStandingsCalculator;
import com.ssafy.a605.backend.domain.ranking.repository.DailySettlementRepository;
import com.ssafy.a605.backend.domain.report.client.AiReportClient;
import com.ssafy.a605.backend.domain.report.dto.PatternTag;
import com.ssafy.a605.backend.domain.report.dto.Review;
import com.ssafy.a605.backend.domain.report.dto.ai.AiDailyReportRequest;
import com.ssafy.a605.backend.domain.report.dto.ai.AiDailyReportRequest.Highlight;
import com.ssafy.a605.backend.domain.report.dto.ai.AiDailyReportRequest.Metrics;
import com.ssafy.a605.backend.domain.report.dto.ai.AiDailyReportResponse;
import com.ssafy.a605.backend.domain.report.entity.DailyReportText;
import com.ssafy.a605.backend.domain.report.repository.DailyReportTextRepository;
import com.ssafy.a605.backend.domain.report.service.DailyFifo.Paired;
import com.ssafy.a605.backend.domain.season.entity.GameTick;
import com.ssafy.a605.backend.domain.season.repository.GameTickRepository;
import com.ssafy.a605.backend.domain.season.service.GameTickCatalog;
import com.ssafy.a605.backend.domain.season.repository.GameTickRepository.TickRange;
import com.ssafy.a605.backend.global.response.Amounts;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * 폐장 정산이 끝난 뒤 AI 에게 그날의 문구를 받아 저장한다 (AI 전처리 요청서 v5 §4 · §5 · §6).
 *
 * <p><b>추린 것만 보낸다.</b> 계좌 하나의 체결 100 건 중 문장에 닿는 것은 3~8 건이고, 그것이
 * 무엇인지는 폐장 집계가 이미 판정해 두었다 — 태그와 최고·최악 거래가 그것이다. 체결 원본을
 * 보내면 계좌당 23KB 인데 그중 97%가 옮겨졌다 버려진다 (요청서 v5 §0 · §3).
 *
 * <p><b>계산은 여기서 끝내고 판정은 AI 가 한다.</b> 승률과 집중도를 비율이 아니라 건수·금액으로
 * 보내는 이유가 그것이다 — 몇 %부터 "높다"고 볼지는 베타에서 계속 만질 값이라 AI 가 갖는다.
 *
 * <p><b>외부 호출이 트랜잭션 밖이다.</b> 조립(읽기) · 호출 · 저장(쓰기) 셋으로 끊는다 —
 * 한 트랜잭션에 넣으면 AI 를 기다리는 동안(최대 4 분) 커넥션 하나를 붙들고 있게 된다.
 *
 * <p><b>정산 트랜잭션 밖이다.</b> 순위와 집계는 이미 커밋됐고 여기서 하는 일은 외부 호출과
 * 문구 저장이다 — 같은 트랜잭션에 넣으면 AI 의 응답 시간만큼 폐장 스냅샷의 잠금이 길어지고,
 * 호출이 실패하면 확정된 순위까지 되돌아간다.
 *
 * <p><b>계좌를 나눠 보내지 않는다.</b> 체결 건수 순위처럼 <b>전원을 봐야 나오는 값</b>이 있고,
 * 시즌 유형 판정의 백분위도 같은 이유로 한 배치를 요구한다. 조회도 계좌마다 돌지 않는다 —
 * 폐장부터 공개까지 20 분 안에 100 명분이 끝나야 한다.
 */
@Slf4j
@Service
public class AiDailyReportService {

    /**
     * 보내는 태그의 상한. AI 가 장면을 셋만 쓰므로 여유를 두고 여섯을 보낸다 (요청서 v5 §3).
     * 고르는 일은 계산이 아니라 선택이라 AI 에 남긴다.
     */
    private static final int MAX_PATTERN_TAGS = 6;

    /**
     * '금방 팔았다'의 기준(게임 거래일). 일차 하나가 게임 2년 3개월(약 560 거래일)이고 실시간
     * 5시간 35분이라 게임 1 거래일이 실시간 약 36 초다 (요청서 v5 §7.1).
     */
    private static final int QUICK_SELL_DAYS = 20;

    /** 게임 연월. AI 가 문자열을 그대로 문장에 넣는다 (요청서 v5 §4). */
    private static final DateTimeFormatter GAME_MONTH = DateTimeFormatter.ofPattern("yyyy.MM");

    /** 최고 거래. */
    private static final String BEST = "BEST";

    /** 최악 거래. */
    private static final String WORST = "WORST";

    /**
     * 상대 가격 위치의 창이 재생 구간 밖으로 나갈 여유(일). 창은 <b>거래일</b> 20 일이라
     * 주말·휴장을 감안해 넉넉히 잡는다 (시즌 쪽과 같은 값이다).
     */
    private static final int WINDOW_LOOKAROUND_DAYS = 60;

    /** 회전율의 자릿수. 시즌 리포트와 같아야 3일차의 누적이 시즌 값과 일치한다. */
    private static final int TURNOVER_SCALE = 2;

    /** 화면에 그대로 실리는 수익률의 자릿수. 요청서 예시가 {@code +38.2%} 다. */
    private static final int DISPLAY_SCALE = 1;

    private final DailySettlementRepository settlementRepository;
    private final ExecutionRepository executionRepository;
    /** 누적 집계의 현금·시작 잔고·설문 답이 여기서 온다 (요청서 2026-09-18 요청 ② · ④ · ⑤). */
    private final AccountRepository accountRepository;
    /** 누적 집계의 상대 가격 위치를 잴 종가. */
    private final DailyCloseRepository dailyCloseRepository;
    private final InstrumentRepository instrumentRepository;
    private final DailyReportTextRepository textRepository;
    private final GameTickRepository gameTickRepository;
    private final GameTickCatalog gameTicks;
    private final MarketPriceService marketPrices;
    private final AiReportClient aiReportClient;
    /** Jackson 3 이다. 직렬화 예외가 비검사라 쓰기 경로에서는 잡지 않는다. */
    private final ObjectMapper json;

    /** 실제 시각. 게임 시계와 헷갈리지 않게 이름을 나눠 뒀다. */
    private final Clock wallClock;

    /** 조립용 읽기 트랜잭션. 외부 호출이 이 밖에서 일어난다. */
    private final TransactionTemplate read;

    /** 저장용 쓰기 트랜잭션. */
    private final TransactionTemplate write;

    @SuppressWarnings("checkstyle:ParameterNumber")
    public AiDailyReportService(DailySettlementRepository settlementRepository,
                                ExecutionRepository executionRepository,
                                AccountRepository accountRepository,
                                DailyCloseRepository dailyCloseRepository,
                                InstrumentRepository instrumentRepository,
                                DailyReportTextRepository textRepository,
                                GameTickRepository gameTickRepository, GameTickCatalog gameTicks,
                                MarketPriceService marketPrices,
                                AiReportClient aiReportClient,
                                ObjectMapper json,
                                Clock wallClock,
                                PlatformTransactionManager manager) {
        this.settlementRepository = settlementRepository;
        this.executionRepository = executionRepository;
        this.accountRepository = accountRepository;
        this.dailyCloseRepository = dailyCloseRepository;
        this.instrumentRepository = instrumentRepository;
        this.textRepository = textRepository;
        this.gameTickRepository = gameTickRepository;
        this.gameTicks = gameTicks;
        this.marketPrices = marketPrices;
        this.aiReportClient = aiReportClient;
        this.json = json;
        this.wallClock = wallClock;
        this.read = new TransactionTemplate(manager);
        this.read.setReadOnly(true);
        this.write = new TransactionTemplate(manager);
    }

    @Value("${app.replay.season-id:1}")
    private long seasonId;

    /** 시장 수익률을 대신할 종목의 심볼. 요청서 v5 §8-1 이 미결로 둔 자리다. */
    @Value("${app.ai.index-proxy-symbol:NQX}")
    private String indexProxySymbol;

    /**
     * 그 일차의 문구를 만들어 저장한다.
     *
     * <p>실패하면 아무것도 저장하지 않고 끝낸다. 행이 없는 계좌의 리포트는 문구 자리를 비운 채
     * 공개된다 (요청서 v5 §6) — 게임은 멈추지 않는다.
     *
     * @param seasonDay 정산이 끝난 일차
     */
    public void generate(short seasonDay) {
        Payload payload = read.execute(status -> payload(seasonDay));
        if (payload == null) {
            return;
        }
        AiDailyReportResponse response = aiReportClient.daily(payload.request());
        if (response == null) {
            log.error("No AI daily texts for season day {}; reports open without commentary",
                    seasonDay);
            return;
        }
        write.executeWithoutResult(status -> save(seasonDay, response, payload.accountIds()));
    }

    /**
     * 문구를 한 줄도 받지 못했으면 다시 만든다. 발표 경계가 부른다.
     *
     * <p><b>부분적으로 받은 날은 다시 부르지 않는다.</b> AI 가 일부 계좌를 빼고 답하는 것은
     * 그 계좌에 할 말이 없다는 뜻일 수 있고, 그때 되부르면 발표마다 같은 호출이 반복된다.
     *
     * @param seasonDay 발표에 들어간 일차
     */
    public void regenerateIfMissing(short seasonDay) {
        if (read.execute(status -> textRepository.countBySeasonDay(seasonId, seasonDay)) > 0) {
            return;
        }
        log.warn("No AI daily texts for season day {} at announcement; retrying", seasonDay);
        generate(seasonDay);
    }

    /**
     * 보낼 것과, 그것을 보낸 계좌. 정산 행이 없으면 {@code null} 이다.
     *
     * <p>계좌 목록을 함께 들고 나오는 이유는 저장이 <b>보낸 적 없는 계좌를 버리기</b>
     * 때문이다 — 응답을 받은 뒤 다시 조회하면 그 사이 탈퇴한 계좌가 목록에서 빠져,
     * 보냈던 사람의 문구까지 버려진다.
     */
    private Payload payload(short seasonDay) {
        List<DailySettlement> settlements = settlementRepository.findBySeasonDay(seasonId, seasonDay);
        if (settlements.isEmpty()) {
            return null;
        }
        return new Payload(request(seasonDay, settlements), accountIds(settlements));
    }

    /** 조립이 끝난 요청과 그 대상. */
    private record Payload(AiDailyReportRequest request, Set<Long> accountIds) {
    }

    /**
     * 보낼 것을 조립한다. 조회는 넷이다 — 정산 행, 그 일차의 체결(환율 포함), 종목, 그리고
     * 보유 기간을 잴 그 일차의 틱.
     *
     * <p><b>거래가 0 건인 계좌도 담는다.</b> 걸러내면 그 사람만 리포트가 비고, 빈 태그 배열을
     * 받은 AI 는 관망으로 판정해 그에 맞는 문장을 고른다 (요청서 v5 §4).
     */
    private AiDailyReportRequest request(short seasonDay, List<DailySettlement> settlements) {
        List<Long> accountIds = settlements.stream().map(DailySettlement::getAccountId).toList();
        Map<Long, List<DailyTrade>> tradesByAccount =
                executionRepository.findDailyTrades(accountIds, seasonDay).stream()
                        .collect(Collectors.groupingBy(DailyTrade::accountId));
        Map<Long, Instrument> instruments = instruments(tradesByAccount);
        Map<LocalDate, Long> tickByDate = tickByDate(seasonDay);
        Map<Long, Integer> ranks = executionCountRanks(settlements);
        Map<Long, List<String>> previousBlocks = previousBlockIds(seasonDay, accountIds);
        Cumulatives cumulatives = cumulatives(seasonDay, accountIds, settlements);

        List<AiDailyReportRequest.Account> accounts = new ArrayList<>(settlements.size());
        for (DailySettlement settlement : settlements) {
            // 선입선출이 순서에 기대므로 체결 순서로 세운다.
            List<DailyTrade> trades = tradesByAccount
                    .getOrDefault(settlement.getAccountId(), List.of()).stream()
                    .sorted(Comparator.comparing(DailyTrade::executionId))
                    .toList();
            accounts.add(account(settlement, trades, instruments, tickByDate,
                    ranks.getOrDefault(settlement.getAccountId(), settlements.size()),
                    settlements.size(),
                    previousBlocks.getOrDefault(settlement.getAccountId(), List.of()),
                    cumulatives.of(settlement)));
        }
        return new AiDailyReportRequest(seasonId, seasonDay, indexReturn(seasonDay), accounts);
    }

    @SuppressWarnings("checkstyle:ParameterNumber")
    private AiDailyReportRequest.Account account(DailySettlement settlement,
                                                 List<DailyTrade> trades,
                                                 Map<Long, Instrument> instruments,
                                                 Map<LocalDate, Long> tickByDate,
                                                 int rank, int participantCount,
                                                 List<String> previousBlockIds,
                                                 AiDailyReportRequest.Cumulative cumulative) {
        Map<Long, DailyTrade> byExecution = trades.stream()
                .collect(Collectors.toMap(DailyTrade::executionId, Function.identity()));
        Map<Long, Paired> paired = DailyFifo.pair(trades, tickByDate);
        return new AiDailyReportRequest.Account(
                settlement.getAccountId(),
                // 저장된 자릿수 그대로 보낸다. 우리 응답 규약(비율 소수 둘째 자리)은 화면을
                // 위한 것이고, AI 는 daily_settlements.daily_return 을 그대로 받기로 계약했다.
                settlement.getDailyReturn().toPlainString(),
                settlement.getExecutionCount(),
                patternTags(settlement, byExecution, instruments),
                metrics(settlement, trades, paired, instruments, rank, participantCount),
                highlights(settlement, byExecution, paired, instruments),
                previousBlockIds,
                cumulative);
    }

    /**
     * 그 일차까지의 누적 집계를 계좌마다 만든다 (요청서 2026-09-18 요청 ②).
     *
     * <p><b>새 계산 로직이 없다.</b> 시즌 집계와 같은 {@link TradeCounts} 를 구간만 좁혀 한 번 더
     * 돌린다 — 1일차는 1일치, 3일차는 시즌 전체와 같은 값이 된다. 두 경로가 같은 코드로 세야
     * "3일차의 누적이 곧 시즌 전체"라는 계약이 성립한다.
     *
     * <p>조회는 계좌마다 돌지 않는다. 폐장부터 공개까지 20 분 안에 100 명분이 끝나야 한다.
     */
    private Cumulatives cumulatives(short seasonDay, List<Long> accountIds,
                                    List<DailySettlement> settlements) {
        List<DailyTrade> trades =
                executionRepository.findTradesUpToSeasonDay(accountIds, seasonDay);
        return new Cumulatives(
                trades.stream().collect(Collectors.groupingBy(DailyTrade::accountId)),
                accountRepository.findAllById(accountIds).stream()
                        .collect(Collectors.toMap(Account::getId, Function.identity())),
                tickByDateUpTo(seasonDay),
                pricePositions(trades),
                closingRate(seasonDay),
                feeUpTo(seasonDay, settlements),
                averageAssetUpTo(seasonDay, settlements, accountIds),
                pointStandings(seasonDay));
    }

    /**
     * 조립이 끝난 누적 집계의 재료. 계좌마다 이 안에서 한 덩이를 꺼낸다.
     *
     * @param tradesByAccount 그 일차까지의 체결
     * @param accounts        현금·시작 잔고·설문 답을 읽을 계좌
     * @param tickByDate      보유 기간을 재는 자. 1일차부터 그 일차까지다
     * @param positions       상대 가격 위치를 잴 종가 창
     * @param closingRate     그 일차 폐장 틱의 원/달러 환율
     * @param feeByAccount    그 일차까지의 누적 수수료
     * @param averageAssetByAccount 그 일차까지 <b>일차별 평균 자산의 합</b>. 회전율의 분모다
     * @param pointByAccount  그 일차까지의 포인트 순위. 누적 순위와 수익률이 여기서 나온다
     */
    private record Cumulatives(Map<Long, List<DailyTrade>> tradesByAccount,
                               Map<Long, Account> accounts,
                               Map<LocalDate, Long> tickByDate,
                               PricePosition positions,
                               BigDecimal closingRate,
                               Map<Long, BigDecimal> feeByAccount,
                               Map<Long, BigDecimal> averageAssetByAccount,
                               Map<Long, PointStanding> pointByAccount) {

        /**
         * 한 계좌의 누적 집계. 계좌 행이 없으면 {@code null} 이고, 그때 AI 는 결과지를 만들지
         * 않는다 — 현금 없이는 투입 비중을 잴 수 없다.
         */
        AiDailyReportRequest.Cumulative of(DailySettlement settled) {
            Account account = accounts.get(settled.getAccountId());
            if (account == null) {
                // 확정과 이 시점 사이에 탈퇴했다. 그 계좌만 결과지 없이 둔다.
                return null;
            }
            List<DailyTrade> trades = tradesByAccount
                    .getOrDefault(settled.getAccountId(), List.of());
            TradeCounts counts = TradeCounts.of(trades, tickByDate, positions);
            BigDecimal traded = trades.stream()
                    .map(trade -> trade.amount().multiply(trade.krwRate()))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal fee = feeByAccount.getOrDefault(settled.getAccountId(), BigDecimal.ZERO);
            PointStanding point = pointByAccount.get(settled.getAccountId());
            return new AiDailyReportRequest.Cumulative(
                    counts.buyCount, counts.sellCount,
                    counts.fomoBuyCount, counts.bottomSnipeBuyCount,
                    counts.peakSellWinCount, counts.panicBottomSellCount,
                    counts.quickProfitCount, counts.quickStopCount,
                    // 잠긴 몫을 빼지 않는다. 관망형 판정이 봐야 하는 것은 "쓰지 않고 남긴 현금"
                    // 전부다 (시즌 쪽과 같은 판단이다).
                    //
                    // **계좌가 아니라 확정 행에서 읽는다** (V42). 결과지 생성은 폐장 뒤 비동기라,
                    // 다음 날 브리핑의 일차 초기화를 지나 실행되면 계좌의 현금은 이미 시드로
                    // 덮여 있다. 폐장에 찍어 둔 값이 그 일차의 진짜 마감 현금이다.
                    Amounts.of(closingKrwCash(settled, account), Amounts.KRW_SCALE),
                    Amounts.of(closingKrwCash(settled, account)
                            .add(closingUsdCash(settled, account).multiply(closingRate)),
                            Amounts.KRW_SCALE),
                    Amounts.of(settled.getTotalValuationKrw(), Amounts.KRW_SCALE),
                    counts.holdingDaysSum, counts.closedPositionCount, counts.winCount,
                    // **그 일차 폐장에 찍어 둔 값이다** (V38). accounts.max_drawdown 은 시즌
                    // 누적이라 1·2일차에 쓰면 3일차 값을 보내게 된다. 스냅샷이 없는 일차는
                    // 비워 보내고, AI 는 손실 감내 항목을 "못 쟀다"로 둔다.
                    settled.getMaxDrawdown() == null
                            ? null : Amounts.ratio(settled.getMaxDrawdown()).toPlainString(),
                    turnover(traded, averageAssetByAccount
                            .getOrDefault(settled.getAccountId(), BigDecimal.ZERO)).toPlainString(),
                    Amounts.of(fee, Amounts.KRW_SCALE),
                    // **포인트 순위와 일간 수익률 합이다.** 일차 경계마다 자산이 시드로
                    // 돌아가므로 daily_settlements.season_return 은 더 이상 누적이 아니라
                    // 그날 수익률과 같은 값이고, season_rank 도 그날 순위와 같다. 시즌 성적을
                    // 정하는 규칙은 포인트 합산이므로(S15P21A605-250) 누적 순위도 그것을 쓴다.
                    //
                    // 수익률은 <b>일간 수익률의 단순 합</b>이다 — 복리 누적이 아니다. 100 % 뒤
                    // -50 % 면 이 값은 50 % 지만 실제로는 0 % 다. 포인트 탭이 화면에 같은 값을
                    // 같은 이름으로 이미 내보내고 있어 정의가 한 벌로 유지된다.
                    point == null ? 0 : point.rank(),
                    pointByAccount.size(),
                    display(point == null ? BigDecimal.ZERO : point.dailyReturnSum()),
                    account.getSelfAnswers(),
                    // 감내 손실률은 답 원본에서 화면이 낸다. 서버가 보낼 값이 없다.
                    null);
        }

        /**
         * 회전율 = 누적 거래대금 ÷ <b>일차별 평균 자산의 합</b>.
         *
         * <p><b>분모를 그날 자산 하나로 두면 안 된다.</b> 일차 경계마다 자산이 시드로
         * 돌아가므로 (일차 초기화) 그날 평균 자산은 매일 비슷한 크기인데 분자는 며칠치
         * 누적이다. 그대로 나누면 3 일차 회전율이 1 일차의 세 배쯤으로 부풀고, 그것은
         * 거래를 더 해서가 아니라 날이 지나서다.
         *
         * <p>일차별 평균 자산은 그 일차 시작 자산(=시드)과 마감 평가액의 평균이다 — 시즌
         * 리포트가 쓰는 두 점 근사와 같다. 진짜 시간 가중 평균을 쓰려면 평가액 시계열이
         * 필요한데 그것을 저장하지 않는다.
         */
        private static BigDecimal turnover(BigDecimal traded, BigDecimal averageAssetSum) {
            return averageAssetSum.signum() == 0
                    ? BigDecimal.ZERO
                    : traded.divide(averageAssetSum, TURNOVER_SCALE, RoundingMode.HALF_UP);
        }

        /** 그 일차 마감 원화 현금. 확정 행에 없는 옛 기록만 계좌의 현재 값으로 대신한다. */
        private static BigDecimal closingKrwCash(DailySettlement settled, Account account) {
            return settled.getClosingKrwCash() == null
                    ? account.getKrwCash() : settled.getClosingKrwCash();
        }

        /** 그 일차 마감 달러 현금. 같은 이유로 같은 폴백을 쓴다. */
        private static BigDecimal closingUsdCash(DailySettlement settled, Account account) {
            return settled.getClosingUsdCash() == null
                    ? account.getUsdCash() : settled.getClosingUsdCash();
        }

        /** 화면에 그대로 실리는 수익률. 서식까지 백엔드가 정한다 (요청서 v5 §7.2). */
        private static String display(BigDecimal seasonReturn) {
            BigDecimal rounded = seasonReturn.setScale(DISPLAY_SCALE, RoundingMode.HALF_UP);
            return (rounded.signum() >= 0 ? "+" : "") + rounded.toPlainString() + "%";
        }
    }

    /**
     * 그 일차까지의 누적 수수료. 일차가 셋뿐이라 확정 행을 일차별로 읽어 더한다 — 질의를 새로
     * 두는 것보다 싸다.
     */
    private Map<Long, BigDecimal> feeUpTo(short seasonDay, List<DailySettlement> today) {
        Map<Long, BigDecimal> fees = new HashMap<>();
        for (short day = 1; day < seasonDay; day++) {
            for (DailySettlement row : settlementRepository.findBySeasonDay(seasonId, day)) {
                fees.merge(row.getAccountId(), row.getTotalFeeKrw(), BigDecimal::add);
            }
        }
        for (DailySettlement row : today) {
            fees.merge(row.getAccountId(), row.getTotalFeeKrw(), BigDecimal::add);
        }
        return fees;
    }

    /**
     * 그 일차까지의 포인트 순위.
     *
     * <p><b>{@code PointStandingsCalculator} 의 캐시를 거치지 않는다.</b> 결과지 생성은 폐장
     * 이벤트에 얹혀 비동기로 도는데, 그 캐시를 비우는 것도 같은 이벤트의 리스너다. 캐시를
     * 읽으면 방금 확정된 일차가 빠진 순위를 받을 수 있다. 집계를 직접 읽고 같은 규칙으로
     * 세우면 그 경합이 사라진다 — 동점 규칙은 여전히 한 곳에만 있다.
     *
     * <p><b>그 일차까지로 좁혀 묻는다.</b> "폐장 직후라 어차피 그 일차까지" 에 기대면, 지난
     * 일차의 결과지를 나중에 다시 만들 때 그 사이에 확정된 일차의 점수가 섞인다 — 같은
     * 요청의 체결·수수료·회전율은 그 일차까지로 잘려 있어 한 덩이 안에서 기준이 갈린다.
     */
    private Map<Long, PointStanding> pointStandings(short seasonDay) {
        return PointStandingsCalculator.rank(settlementRepository.aggregatePoints(seasonId, seasonDay))
                .stream()
                .collect(Collectors.toMap(PointStanding::accountId, Function.identity()));
    }

    /**
     * 그 일차까지 <b>일차별 평균 자산의 합</b>. 누적 회전율의 분모다.
     *
     * <p>일차 하나의 평균 자산은 그 일차 시작 자산과 마감 평가액의 평균이다. 시작 자산은
     * 일차 초기화가 매일 시드로 되돌리므로 계좌의 시작 잔고와 같다.
     *
     * <p><b>참가하지 않은 일차는 더하지 않는다.</b> 그 일차 확정 행이 없으면 그날의 자산도
     * 거래대금도 없으므로, 분모에 넣으면 중도 합류자의 회전율만 낮아진다.
     */
    private Map<Long, BigDecimal> averageAssetUpTo(short seasonDay, List<DailySettlement> today,
                                                   List<Long> accountIds) {
        Map<Long, BigDecimal> startingBalances = accountRepository.findAllById(accountIds).stream()
                .collect(Collectors.toMap(Account::getId, Account::getStartingBalanceKrw));
        Map<Long, BigDecimal> sums = new HashMap<>();
        for (short day = 1; day < seasonDay; day++) {
            addAverageAssets(sums, settlementRepository.findBySeasonDay(seasonId, day), startingBalances);
        }
        addAverageAssets(sums, today, startingBalances);
        return sums;
    }

    private static void addAverageAssets(Map<Long, BigDecimal> sums, List<DailySettlement> rows,
                                         Map<Long, BigDecimal> startingBalances) {
        for (DailySettlement row : rows) {
            BigDecimal start = startingBalances.get(row.getAccountId());
            if (start == null) {
                continue;
            }
            sums.merge(row.getAccountId(),
                    start.add(row.getTotalValuationKrw()).divide(BigDecimal.TWO, RoundingMode.HALF_UP),
                    BigDecimal::add);
        }
    }

    /** 보유 기간을 재는 자. 누적이므로 1일차부터 그 일차까지의 틱을 모은다. */
    private Map<LocalDate, Long> tickByDateUpTo(short seasonDay) {
        Map<LocalDate, Long> ticks = new LinkedHashMap<>();
        for (short day = 1; day <= seasonDay; day++) {
            ticks.putAll(tickByDate(day));
        }
        return ticks;
    }

    /**
     * 상대 가격 위치를 잴 종가. <b>거래한 종목만</b> 읽고, 창이 재생 구간 밖으로 나가므로 양 끝에
     * 여유를 둔다 (시즌 쪽과 같은 이유다).
     */
    private PricePosition pricePositions(List<DailyTrade> trades) {
        if (trades.isEmpty()) {
            return PricePosition.of(List.of());
        }
        List<Long> instrumentIds = trades.stream().map(DailyTrade::instrumentId).distinct().toList();
        LocalDate from = trades.stream().map(DailyTrade::gameDate).min(LocalDate::compareTo)
                .orElseThrow().minusDays(WINDOW_LOOKAROUND_DAYS);
        LocalDate to = trades.stream().map(DailyTrade::gameDate).max(LocalDate::compareTo)
                .orElseThrow().plusDays(WINDOW_LOOKAROUND_DAYS);
        return PricePosition.of(dailyCloseRepository
                .findByInstrumentIdInAndGameDateBetweenOrderByGameDateAsc(instrumentIds, from, to));
    }

    /**
     * 그 일차 폐장 틱의 원/달러 환율. 달러 현금을 원화로 환산하는 데 쓴다.
     *
     * <p>총평가액 스냅샷과 같은 시점이어야 한다 — 다른 틱의 환율을 쓰면 현금이 총평가액보다
     * 커지는 계좌가 나온다. 틱이 없으면 원인을 말하고 멈춘다.
     */
    private BigDecimal closingRate(short seasonDay) {
        List<GameTick> ticks =
                gameTickRepository.findBySeasonIdAndSeasonDayOrderByTickSeqAsc(seasonId, seasonDay);
        if (ticks.isEmpty()) {
            // 원인이 indexReturn 과 같다 - 그 일차에 틱이 없다. 같은 사유에 두 가지 말을
            // 쓰지 않는다.
            throw new IllegalStateException("편성에 없는 일차입니다: seasonDay=" + seasonDay);
        }
        return ticks.getLast().getUsdKrw();
    }

    /**
     * 앞선 일차에 각 계좌가 받은 문구 블록. AI 가 후보에서 빼 같은 문장이 두 번 나오지 않게
     * 한다 (요청서 v5 §6 의 {@code blockIds} 를 저장하는 이유).
     *
     * <p><b>저장만 하고 아무도 읽지 않던 값이다.</b> 조건 칸마다 헤드라인 후보가 3 개인데
     * 시즌이 3 일이라, 되돌려 보내지 않으면 3 개에서 3 번 뽑는 꼴이 된다 — 목업에서 23.2%가
     * 시즌 3 장 중 같은 문장을 두 번 이상 봤다.
     *
     * <p><b>직전 일차만 빼서는 모자란다.</b> 그러면 1 일차와 3 일차가 다시 겹쳐 9.0%가 남는다.
     * 앞선 일차를 모두 빼면 0.0% 가 되고, 장면 별칭도 43.7% 에서 2.1% 로 떨어진다. 일차가
     * 셋뿐이라 읽는 양은 계좌당 최대 두 행이다.
     *
     * <p><b>1 일차는 앞이 없다.</b> 빈 표를 주면 AI 가 빼지 않고 그냥 고른다.
     *
     * @param seasonDay  지금 만드는 일차
     * @param accountIds 대상 계좌
     * @return 계좌별 지금까지 쓴 블록. 앞선 문구가 없는 계좌는 표에 없다
     */
    private Map<Long, List<String>> previousBlockIds(short seasonDay, List<Long> accountIds) {
        if (seasonDay <= 1) {
            return Map.of();
        }
        Map<Long, List<String>> previous = new HashMap<>();
        for (DailyReportText text : textRepository
                .findBySeasonDayLessThanAndAccountIdIn(seasonDay, accountIds)) {
            readBlockIds(text).ifPresent(ids -> previous
                    .computeIfAbsent(text.getAccountId(), id -> new ArrayList<>()).addAll(ids));
        }
        return previous;
    }

    /**
     * 저장된 블록 목록은 JSONB 문자열이다. 읽지 못하면 그 계좌만 빼지 않고 넘어간다 —
     * 변주가 겹치는 것은 문구가 비는 것보다 가볍다 ({@code stored()} 와 같은 판단이다).
     */
    private Optional<List<String>> readBlockIds(DailyReportText text) {
        try {
            return Optional.of(json.readValue(text.getBlockIds(),
                    new TypeReference<List<String>>() { }));
        } catch (RuntimeException e) {
            log.warn("Unreadable block ids for account {} on day {}; dedup skipped",
                    text.getAccountId(), text.getSeasonDay(), e);
            return Optional.empty();
        }
    }

    /**
     * 태그가 붙은 거래. 판정은 폐장 집계가 이미 했고 여기서는 <b>문장에 실릴 값을 붙인다</b> —
     * 종목명·방향·연월이 그것이다 (요청서 v5 §4).
     *
     * <p>여섯을 넘으면 <b>변화율 절대값이 큰 순</b>으로 자른다. 같은 변화율이면 체결 순서로
     * 갈라 같은 입력에 같은 여섯이 나오게 한다.
     */
    private List<AiDailyReportRequest.PatternTag> patternTags(DailySettlement settlement,
                                                              Map<Long, DailyTrade> byExecution,
                                                              Map<Long, Instrument> instruments) {
        return stored(settlement).stream()
                // 태그는 그 일차의 체결에만 붙지만, 없는 체결을 가리키면 이름을 채울 수 없다.
                .filter(tag -> byExecution.containsKey(tag.executionId()))
                .sorted(Comparator.comparing((PatternTag tag) -> tag.changeRate().abs()).reversed()
                        .thenComparing(PatternTag::executionId))
                .limit(MAX_PATTERN_TAGS)
                .map(tag -> {
                    DailyTrade trade = byExecution.get(tag.executionId());
                    return new AiDailyReportRequest.PatternTag(
                            // kind 는 백엔드 이름 그대로 보낸다. AI 는 모르는 이름을 조용히
                            // 버리므로(extract.TAG_FROM_PATTERN_KIND) 여기서 손대면 태그가
                            // 사라져도 티가 안 난다.
                            tag.kind(),
                            Amounts.ratio(tag.changeRate()).toPlainString(),
                            instruments.get(trade.instrumentId()).getName(),
                            trade.side().name(),
                            GAME_MONTH.format(trade.gameDate()));
                })
                .toList();
    }

    /**
     * 계좌 요약 (요청서 v5 §5).
     *
     * <p><b>승률과 집중도를 비율이 아니라 건수·금액으로 보낸다.</b> 몇 %부터 "높다"고 볼지가
     * AI 쪽 임계값이라, 나눠 보내면 그 분모의 정의가 백엔드 배포에 굳는다.
     *
     * <p><b>집중도는 금액 기준이다.</b> 건수로 재면 소액 10 건과 거액 1 건이 같은 무게가 되어
     * 실제 쏠림을 못 잡는다.
     */
    private Metrics metrics(DailySettlement settlement, List<DailyTrade> trades,
                            Map<Long, Paired> paired, Map<Long, Instrument> instruments, int rank,
                            int participantCount) {
        int buyCount = (int) trades.stream().filter(trade -> trade.side() == OrderSide.BUY).count();
        int winCount = (int) trades.stream()
                .filter(trade -> trade.realizedPnl() != null && trade.realizedPnl().signum() > 0)
                .count();
        // 원화로 환산해 더한다. 통화가 섞인 계좌라 결제 통화로 더하면 달러와 원화가 같은 축에
        // 놓이지 않아 "가장 크게 거래한 종목"이 뒤집힌다.
        Map<Long, BigDecimal> amountByInstrument = new HashMap<>();
        for (DailyTrade trade : trades) {
            amountByInstrument.merge(trade.instrumentId(),
                    trade.amount().multiply(trade.krwRate()), BigDecimal::add);
        }
        BigDecimal tradedAmount = amountByInstrument.values().stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        // 같은 금액이면 종목 순서로 갈라 결과를 고정한다.
        Optional<Map.Entry<Long, BigDecimal>> top = amountByInstrument.entrySet().stream()
                .max(Map.Entry.<Long, BigDecimal>comparingByValue()
                        .thenComparing(Map.Entry.comparingByKey()));

        return new Metrics(
                buyCount,
                trades.size() - buyCount,
                winCount,
                Amounts.of(settlement.getRealizedPnlKrw(), Amounts.KRW_SCALE),
                Amounts.of(settlement.getTotalFeeKrw(), Amounts.KRW_SCALE),
                avgHoldingDays(paired),
                (int) paired.values().stream()
                        .filter(pair -> pair.holdingDays() <= QUICK_SELL_DAYS).count(),
                Amounts.of(tradedAmount, Amounts.KRW_SCALE),
                // 이름과 비중은 함께 있거나 함께 없다 (요청서 v5 §5). 거래대금이 0 이면
                // 비중을 낼 수 없는데 이름만 실으면 AI 가 비중 없이 "○○에 몰았습니다" 를 쓴다.
                tradedAmount.signum() == 0 ? null
                        : top.map(entry -> instruments.get(entry.getKey()).getName()).orElse(null),
                tradedAmount.signum() == 0 ? null
                        : top.map(entry -> share(entry.getValue(), tradedAmount)).orElse(null),
                rank,
                participantCount);
    }

    /**
     * 청산 포지션의 평균 보유 거래일. 짝지은 매도가 하나도 없으면 {@code null} 이고, AI 는 그때
     * 보유 기간 문장을 쓰지 않는다.
     */
    private static Integer avgHoldingDays(Map<Long, Paired> paired) {
        if (paired.isEmpty()) {
            return null;
        }
        return (int) Math.round(paired.values().stream()
                .mapToInt(Paired::holdingDays).average().orElseThrow());
    }

    /**
     * 거래액 비중(0~1). 거래대금이 0 이면 나눌 것이 없다.
     *
     * <p>퍼센트가 아니라 비(比)로 보낸다 — 요청서가 {@code 0.43} 을 요구한다. 나눗셈은 표현
     * 자릿수에서 한 번만 한다 ({@code Amounts}).
     */
    private static String share(BigDecimal amount, BigDecimal total) {
        if (total.signum() == 0) {
            return null;
        }
        return amount.divide(total, Amounts.RATIO_SCALE, RoundingMode.HALF_UP).toPlainString();
    }

    /**
     * 최고·최악 거래. 고른 것은 폐장 집계이고 여기서는 문장에 실릴 형태로 펼친다
     * (요청서 v5 §5).
     *
     * <p>매도가 없는 날은 빈 배열이다. 한 건인 날은 둘이 같은 체결을 가리키며, 그 중복은 AI 가
     * 걸러낸다.
     */
    private List<Highlight> highlights(DailySettlement settlement, Map<Long, DailyTrade> byExecution,
                                       Map<Long, Paired> paired, Map<Long, Instrument> instruments) {
        List<Highlight> highlights = new ArrayList<>(2);
        highlight(BEST, settlement.getBestExecutionId(), byExecution, paired, instruments)
                .ifPresent(highlights::add);
        highlight(WORST, settlement.getWorstExecutionId(), byExecution, paired, instruments)
                .ifPresent(highlights::add);
        return highlights;
    }

    private Optional<Highlight> highlight(String role, Long executionId,
                                          Map<Long, DailyTrade> byExecution,
                                          Map<Long, Paired> paired,
                                          Map<Long, Instrument> instruments) {
        if (executionId == null || !byExecution.containsKey(executionId)) {
            return Optional.empty();
        }
        DailyTrade trade = byExecution.get(executionId);
        // 상·하위는 실현 손익으로 골라졌으므로 매도다. 그래도 null 을 곱하지는 않는다.
        BigDecimal realizedPnl = trade.realizedPnl() == null ? BigDecimal.ZERO
                : trade.realizedPnl().multiply(trade.krwRate());
        Paired pair = paired.get(executionId);
        return Optional.of(new Highlight(
                role,
                instruments.get(trade.instrumentId()).getName(),
                trade.side().name(),
                GAME_MONTH.format(trade.gameDate()),
                Amounts.of(realizedPnl, Amounts.KRW_SCALE),
                pair == null ? null : Amounts.ratio(pair.returnRate()).toPlainString(),
                pair == null ? null : pair.holdingDays()));
    }

    /**
     * 체결 건수 순위. <b>백엔드만 낼 수 있는 값이라</b> 요청서가 따로 요구한다 — 그 일차 전원의
     * 건수를 비교해야 나오고, 요청이 어차피 전원분을 담으므로 추가 조회가 없다. 수익률 순위
     * ({@code daily_rank}) 와는 다른 축이다 (요청서 v5 §5).
     *
     * <p>동점은 같은 순위를 주고 다음을 건너뛴다 — {@code rank.md} 의 순위 규칙과 같다.
     */
    private static Map<Long, Integer> executionCountRanks(List<DailySettlement> settlements) {
        List<DailySettlement> ordered = settlements.stream()
                .sorted(Comparator.comparing(DailySettlement::getExecutionCount).reversed())
                .toList();
        Map<Long, Integer> ranks = new HashMap<>();
        int rank = 0;
        int seen = 0;
        Integer previous = null;
        for (DailySettlement settlement : ordered) {
            seen++;
            if (!settlement.getExecutionCount().equals(previous)) {
                rank = seen;
                previous = settlement.getExecutionCount();
            }
            ranks.put(settlement.getAccountId(), rank);
        }
        return ranks;
    }

    /**
     * 그 일차의 시장 수익률(%). AI 가 "시장 대비 어디였나"를 가리는 데 쓴다.
     *
     * <p><b>지수 종목이 유니버스에 없다.</b> 게임은 28 종만 재생하고 지수 자체는 화면에 나오지
     * 않으므로, 대리 종목의 그 일차 <b>첫 거래일 종가 대비 마지막 거래일 종가</b>로 잡는다.
     * 어느 종목을 쓸지는 아직 정해지지 않았다 (요청서 v5 §8-1) — 편성값으로 두고 기본값은
     * 나스닥 100 ETF 다. {@code rpt.md} 13.3.3 이 비교 기준선으로 꼽은 둘 중 하나다.
     *
     * <p><b>0 을 지어내지 않는다.</b> 대리 종목이 없거나 그 일차 종가가 없으면 계산을 포기하는
     * 대신 호출을 접는다 — 시장이 움직이지 않았다고 보내면 AI 가 그 위에 문장을 쌓는다.
     *
     * @throws IllegalStateException 대리 종목이나 그 일차의 종가를 찾지 못했을 때
     */
    private String indexReturn(short seasonDay) {
        Instrument proxy = instrumentRepository.findBySeasonIdAndSymbol(seasonId, indexProxySymbol)
                .orElseThrow(() -> new IllegalStateException(
                        "지수 대리 종목이 없습니다: " + indexProxySymbol));
        TickRange range = gameTickRepository.findTickRange(seasonId, seasonDay);
        if (range.fromTickSeq() == null) {
            // 정산 행은 있는데 편성에 그 일차의 틱이 없다. 그대로 흘리면 findById(null) 이
            // 원인을 감춘 예외를 던진다.
            throw new IllegalStateException("편성에 없는 일차입니다: seasonDay=" + seasonDay);
        }
        BigDecimal first = closeAt(proxy.getId(), range.fromTickSeq());
        BigDecimal last = closeAt(proxy.getId(), range.toTickSeq());
        if (first.signum() == 0) {
            // 나누면 ArithmeticException 이 원인을 감춘다. 위 두 경로와 같은 모양으로 끊는다.
            throw new IllegalStateException(
                    "지수 대리 종목의 첫 종가가 0 입니다: instrument=" + proxy.getId());
        }
        return Amounts.percent(last.subtract(first), first).toPlainString();
    }

    private BigDecimal closeAt(Long instrumentId, Long tickSeq) {
        GameTick tick = gameTicks.find(tickSeq)
                .orElseThrow(() -> new IllegalStateException("편성에 없는 틱입니다: " + tickSeq));
        AsOfPrice price = marketPrices.pricesAsOf(List.of(instrumentId), tick.getGameDate())
                .get(instrumentId);
        if (price == null) {
            throw new IllegalStateException(
                    "지수 대리 종목의 종가가 없습니다: instrument=%d, date=%s"
                            .formatted(instrumentId, tick.getGameDate()));
        }
        return price.close();
    }

    /**
     * 보유 기간을 재는 자 (게임 거래일 -> 틱 번호). 일차당 한 번 읽어 계좌 전원이 나눠 쓴다.
     *
     * <p>한 거래일에 틱은 하나뿐이지만, 편성이 어긋나 둘이 오면 먼저 온 것을 남긴다 — 여기서
     * 터뜨릴 만한 일이 아니다.
     */
    private Map<LocalDate, Long> tickByDate(short seasonDay) {
        return gameTickRepository
                .findBySeasonIdAndSeasonDayOrderByTickSeqAsc(seasonId, seasonDay).stream()
                .collect(Collectors.toMap(GameTick::getGameDate, GameTick::getTickSeq,
                        (first, second) -> first));
    }

    private Map<Long, Instrument> instruments(Map<Long, List<DailyTrade>> tradesByAccount) {
        List<Long> ids = tradesByAccount.values().stream().flatMap(List::stream)
                .map(DailyTrade::instrumentId).distinct().toList();
        return instrumentRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(Instrument::getId, Function.identity()));
    }

    /** 저장된 태그는 JSON 문자열이다. 읽지 못하면 그 계좌만 태그 없이 보낸다. */
    private List<PatternTag> stored(DailySettlement settlement) {
        try {
            return json.readValue(settlement.getPatternTags(),
                    new TypeReference<List<PatternTag>>() { });
        } catch (RuntimeException e) {
            log.error("Unreadable pattern tags for account {} on day {}", settlement.getAccountId(),
                    settlement.getSeasonDay(), e);
            return List.of();
        }
    }

    /**
     * 받은 문구를 저장한다. 요청한 계좌가 다 오지 않을 수 있으므로 온 것만 쓴다.
     *
     * <p><b>보낸 적 없는 계좌는 버린다.</b> 그 행은 {@code accounts} 외래키를 맞추지 못해
     * insert 에서 터지고, 한 트랜잭션이라 그 일차 문구를 <b>전부</b> 잃는다 — AI 한쪽의 실수가
     * 100 명의 리포트를 비우게 둘 이유가 없다. 같은 계좌가 두 번 오면 첫 것만 쓴다(키가
     * 겹쳐 같은 결과가 된다).
     */
    private void save(short seasonDay, AiDailyReportResponse response, Set<Long> requested) {
        Instant generatedAt = Instant.now(wallClock);
        Map<Long, DailyReportText> texts = new LinkedHashMap<>();
        for (AiDailyReportResponse.Report report : response.reports()) {
            if (!requested.contains(report.accountId())) {
                log.warn("AI returned a text for account {} which day {} did not ask about;"
                        + " dropped", report.accountId(), seasonDay);
                continue;
            }
            if (!usable(report.headline(), DailyReportText.HEADLINE_MAX)) {
                log.warn("Unusable headline for account {} on day {}; dropped that row",
                        report.accountId(), seasonDay);
                continue;
            }
            DailyReportText text = new DailyReportText(seasonDay, report.accountId(),
                    report.headline(), write(report.sceneAliases()),
                    write(blockIds(report)), generatedAt);
            attachReview(text, report, seasonDay);
            texts.putIfAbsent(report.accountId(), text);
        }
        textRepository.saveAll(texts.values());
        log.info("Saved {} of {} AI daily texts for season day {}", texts.size(),
                response.reports().size(), seasonDay);
    }

    /**
     * 그 계좌가 이 일차에 받은 블록 전부 — 머리말 쪽과 결과지 쪽을 합친다.
     *
     * <p>{@code previousBlockIds} 가 이 값을 되돌려 보내 다음 일차의 후보에서 빼므로, 결과지
     * 블록을 빠뜨리면 결과지 문장이 일차를 건너 겹친다. 둘을 나눠 저장하지 않는 이유도
     * 같다 — 읽는 쪽이 보는 것은 "이미 받은 블록" 하나뿐이다.
     */
    private static List<String> blockIds(AiDailyReportResponse.Report report) {
        if (report.review() == null || report.review().blockIds() == null) {
            return report.blockIds();
        }
        List<String> merged = new ArrayList<>(report.blockIds());
        merged.addAll(report.review().blockIds());
        return merged;
    }

    /**
     * 결과지를 행에 붙인다. 없으면 머리말만 있는 행으로 남는다.
     *
     * <p><b>결과지 하나의 결함이 그 일차 전원의 문구를 날리지 않게 한다.</b> 저장이 한
     * 트랜잭션이라 컬럼 제약 하나를 어기면 전부 되돌아간다. 그래서 길이를 넘거나 필수 자리가
     * 빈 결과지는 <b>그 결과지만</b> 버리고 머리말은 저장한다 — 머리말이 있는 리포트는 결과지
     * 카드만 비운 채 열리기 때문이다.
     */
    private void attachReview(DailyReportText text, AiDailyReportResponse.Report report,
                              short seasonDay) {
        Review review = report.review();
        if (review == null) {
            return;
        }
        if (!usableReview(review)) {
            log.warn("Unusable review for account {} on day {}; kept the headline and dropped"
                    + " the review", report.accountId(), seasonDay);
            return;
        }
        text.attachReview(review, writeJson(review.profileItems()), writeJson(review.narrative()));
    }

    /**
     * 저장할 수 있는 결과지인가.
     *
     * <p>{@code investorType} 은 <b>AI 만 아는 값</b>이고 비어 오지 않는다는 계약이다 — 판정이
     * 서지 않아도 {@code UNDETERMINED} 가 온다. 그 자리가 비었으면 우리가 채울 수 있는 것이
     * 없으므로 결과지를 버린다.
     *
     * <p>선언 쪽 넷({@code selfLevel} · {@code profileMatch} · {@code selfView} ·
     * {@code profileGap})과 {@code profileLevel} 은 <b>비어 있는 것이 정상</b>이라 보지 않는다.
     */
    private static boolean usableReview(Review review) {
        return usable(review.investorType(), DailyReportText.INVESTOR_TYPE_MAX)
                && within(review.typeEmoji(), DailyReportText.TYPE_EMOJI_MAX)
                && within(review.typeName(), DailyReportText.TYPE_NAME_MAX)
                && within(review.typeDescription(), DailyReportText.TYPE_DESCRIPTION_MAX)
                && within(review.verdict(), DailyReportText.VERDICT_MAX)
                && within(review.profileName(), DailyReportText.PROFILE_NAME_MAX)
                && within(review.profilePersona(), DailyReportText.PROFILE_PERSONA_MAX)
                && within(review.selfPersona(), DailyReportText.SELF_PERSONA_MAX)
                && within(review.profileMatch(), DailyReportText.PROFILE_MATCH_MAX);
    }

    /** 비어 있어도 되지만, 있으면 컬럼 길이를 넘지 않아야 하는 자리. */
    private static boolean within(String value, int max) {
        return value == null || value.length() <= max;
    }

    /**
     * 저장할 수 있는 문자열인가 — 비어 있지 않고 컬럼 길이를 넘지 않는가.
     *
     * <p><b>한 행의 결함이 전원의 문구를 날리지 않게 한다.</b> 저장이 한 트랜잭션이라
     * 컬럼 제약 하나를 어기면 그 일차 전부가 되돌아간다 — 보낸 적 없는 계좌를 버리는 것과
     * 같은 판단이며, 막는 이유도 같다. AI 한쪽의 실수가 100 명의 리포트를 비우게 두지 않는다.
     *
     * <p>길이 상한은 엔티티의 상수를 본다. 컬럼 정의와 갈라지면 검사를 통과한 값이
     * insert 에서 터진다.
     */
    private static boolean usable(String value, int max) {
        return value != null && !value.isBlank() && value.length() <= max;
    }

    private static Set<Long> accountIds(List<DailySettlement> settlements) {
        return settlements.stream().map(DailySettlement::getAccountId)
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * 구조가 있는 값을 JSONB 문자열로. 결과지의 막대 다섯과 문장 묶음이 이것을 쓴다.
     *
     * <p>{@code null} 을 그대로 통과시킨다 — 그 자리가 비는 것이 정상인 결과지가 있고,
     * {@code "null"} 문자열을 넣으면 읽는 쪽이 그것을 값으로 본다.
     */
    private String writeJson(Object value) {
        return value == null ? null : json.writeValueAsString(value);
    }

    /**
     * 직렬화 실패를 잡지 않는다. Jackson 3 의 예외는 비검사이고, 문자열 배열을 쓰지 못한다면
     * 그것은 이 코드의 문제이지 데이터의 문제가 아니다 — {@code DailySettlementAggregator} 가
     * 같은 판단을 한다.
     */
    private String write(List<String> values) {
        return json.writeValueAsString(values);
    }
}

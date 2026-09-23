package com.ssafy.a605.backend.domain.report.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.ssafy.a605.backend.domain.account.entity.Account;
import com.ssafy.a605.backend.domain.account.repository.AccountRepository;
import com.ssafy.a605.backend.domain.market.repository.DailyCloseRepository;
import com.ssafy.a605.backend.domain.order.repository.ExecutionRepository;
import com.ssafy.a605.backend.domain.order.repository.ExecutionRepository.DailyTrade;
import com.ssafy.a605.backend.domain.ranking.entity.DailySettlement;
import com.ssafy.a605.backend.domain.ranking.repository.DailySettlementRepository;
import com.ssafy.a605.backend.domain.replay.entity.ReplayState;
import com.ssafy.a605.backend.domain.report.client.AiReportClient;
import com.ssafy.a605.backend.domain.report.dto.ai.AiSeasonReportRequest;
import com.ssafy.a605.backend.domain.report.dto.ai.AiSeasonReportResponse;
import com.ssafy.a605.backend.domain.report.entity.SeasonReport;
import com.ssafy.a605.backend.domain.report.entity.SeasonReportText;
import com.ssafy.a605.backend.domain.report.repository.SeasonReportRepository;
import com.ssafy.a605.backend.domain.report.repository.SeasonReportTextRepository;
import com.ssafy.a605.backend.domain.season.entity.GameTick;
import com.ssafy.a605.backend.domain.season.repository.GameTickRepository;
import com.ssafy.a605.backend.global.response.Amounts;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.ObjectMapper;

/**
 * 시즌이 확정된 뒤 AI 에게 투자자 유형과 총평을 받아 저장한다 (전처리 요청서 v5 §7).
 *
 * <p><b>체결을 보내지 않는다.</b> 3일치를 그대로 보내면 5MB 이고 AI 는 비율만 본다. 여기서
 * 세어 보내면 계좌당 400B 다.
 *
 * <p><b>판정에 필요한 세 가지를 백엔드만 낼 수 있다.</b> 상대 가격 위치 R 은 체결일 전후 20
 * 거래일 종가를 봐야 하고({@link PricePosition}), 보유 기간과 수익률은 매수·매도를 선입선출로
 * 짝지어야 나오며({@link DailyFifo}), 순위와 참가자 수는 전원을 봐야 나온다.
 *
 * <p><b>외부 호출이 트랜잭션 밖이다.</b> 조립(읽기) · 호출 · 저장(쓰기) 셋으로 끊는다 —
 * 한 트랜잭션에 넣으면 AI 를 기다리는 동안(최대 4 분) 커넥션 하나를 붙들고 있게 된다.
 *
 * <p><b>계좌를 나눠 보내도 된다.</b> 판정이 고정 참조 사분위 표를 쓰도록 바뀌어 같은 계좌는
 * 요청 크기와 무관하게 같은 점수를 받는다 (AI 업무요청서 2026-09-18 §7-1). 한 번에 보내는 것은
 * 호출을 줄이려는 선택일 뿐이다.
 *
 * <p><b>셈은 {@link TradeCounts} 가 갖는다.</b> 일일 리포트의 누적 집계가 같은 셈을 그 일차까지의
 * 구간으로 한 번 더 돌린다 — 3일차의 누적이 곧 시즌 전체라는 계약이라 두 경로가 같은 코드로
 * 세야 한다.
 */
@Slf4j
@Service
public class AiSeasonReportService {

    /**
     * 상대 가격 위치의 창이 재생 구간 밖으로 나갈 여유(일). 창은 <b>거래일</b> 20 일이라
     * 주말·휴장을 감안해 넉넉히 잡는다 — 넓게 읽어도 종목당 수십 행이다.
     */
    private static final int WINDOW_LOOKAROUND_DAYS = 60;

    /** 화면에 그대로 실리는 수익률의 자릿수. 요청서 예시가 {@code +38.2%} 다. */
    private static final int DISPLAY_SCALE = 1;

    private final DailySettlementRepository settlementRepository;
    private final SeasonReportRepository reportRepository;
    private final SeasonReportTextRepository textRepository;
    private final AccountRepository accountRepository;
    private final ExecutionRepository executionRepository;
    private final DailyCloseRepository dailyCloseRepository;
    private final GameTickRepository gameTickRepository;
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
    public AiSeasonReportService(DailySettlementRepository settlementRepository,
                                 SeasonReportRepository reportRepository,
                                 SeasonReportTextRepository textRepository,
                                 AccountRepository accountRepository,
                                 ExecutionRepository executionRepository,
                                 DailyCloseRepository dailyCloseRepository,
                                 GameTickRepository gameTickRepository,
                                 AiReportClient aiReportClient,
                                 ObjectMapper json,
                                 Clock wallClock,
                                 PlatformTransactionManager manager) {
        this.settlementRepository = settlementRepository;
        this.reportRepository = reportRepository;
        this.textRepository = textRepository;
        this.accountRepository = accountRepository;
        this.executionRepository = executionRepository;
        this.dailyCloseRepository = dailyCloseRepository;
        this.gameTickRepository = gameTickRepository;
        this.aiReportClient = aiReportClient;
        this.json = json;
        this.wallClock = wallClock;
        this.read = new TransactionTemplate(manager);
        this.read.setReadOnly(true);
        this.write = new TransactionTemplate(manager);
    }

    @Value("${app.replay.season-id:1}")
    private long seasonId;

    /**
     * 시즌 문구를 만들어 저장한다.
     *
     * <p>실패하면 아무것도 저장하지 않는다. 행이 없는 계좌의 시즌 리포트는 유형 자리를 비운 채
     * 공개된다 (요청서 v5 §6).
     */
    public void generate() {
        Payload payload = read.execute(status -> payload());
        if (payload == null) {
            return;
        }
        AiSeasonReportResponse response = aiReportClient.season(payload.request());
        if (response == null) {
            log.error("No AI season texts; season reports open without an investor type");
            return;
        }
        write.executeWithoutResult(status -> save(response, payload.accountIds()));
    }

    /**
     * 문구를 한 줄도 받지 못했으면 다시 만든다. 3일차 발표 경계가 부른다.
     *
     * <p>시즌 확정({@code season_reports})이 아직 없으면 재시도해도 보낼 것의 절반이 비므로
     * 조립 단계가 그대로 접는다.
     */
    public void regenerateIfMissing() {
        List<Long> accountIds = read.execute(status -> settlementRepository
                .findBySeasonDay(seasonId, (short) ReplayState.FINAL_DAY).stream()
                .map(DailySettlement::getAccountId).toList());
        if (accountIds.isEmpty()
                || Boolean.TRUE.equals(read.execute(status ->
                        textRepository.existsByAccountIdIn(accountIds)))) {
            return;
        }
        log.warn("No AI season texts at announcement; retrying");
        generate();
    }

    /** 보낼 것과 그것을 보낸 계좌. 보낼 수 없으면 {@code null} 이다. */
    private Payload payload() {
        short finalDay = (short) ReplayState.FINAL_DAY;
        List<DailySettlement> settled = settlementRepository.findBySeasonDay(seasonId, finalDay);
        if (settled.isEmpty()) {
            return null;
        }
        List<Long> accountIds = settled.stream().map(DailySettlement::getAccountId).toList();
        Map<Long, SeasonReport> reports = reportRepository.findAllById(accountIds).stream()
                .collect(Collectors.toMap(SeasonReport::getAccountId, report -> report));
        if (reports.isEmpty()) {
            // 시즌 확정이 먼저다 (SeasonReportListener). 그 값 없이는 보낼 것의 절반이 빈다.
            log.error("No season reports yet; AI season texts skipped");
            return null;
        }
        return new Payload(request(settled, reports), Set.copyOf(accountIds));
    }

    /** 조립이 끝난 요청과 그 대상. */
    private record Payload(AiSeasonReportRequest request, Set<Long> accountIds) {
    }

    /**
     * 보낼 것을 조립한다. 조회는 다섯이다 — 확정 행, 시즌 리포트, 계좌, 시즌 전체 체결, 그리고
     * 상대 가격 위치를 잴 종가와 보유 기간을 잴 틱.
     */
    private AiSeasonReportRequest request(List<DailySettlement> settled,
                                          Map<Long, SeasonReport> reports) {
        List<Long> accountIds = settled.stream().map(DailySettlement::getAccountId).toList();
        Map<Long, List<DailyTrade>> tradesByAccount =
                executionRepository.findSeasonTrades(accountIds).stream()
                        .collect(Collectors.groupingBy(DailyTrade::accountId));
        Map<Long, Account> accounts = accountRepository.findAllById(accountIds).stream()
                .collect(Collectors.toMap(Account::getId, account -> account));
        Map<LocalDate, Long> tickByDate = tickByDate();
        PricePosition positions = pricePositions(tradesByAccount);
        BigDecimal closingRate = closingRate();

        List<AiSeasonReportRequest.Account> payload = new ArrayList<>(settled.size());
        for (DailySettlement row : settled) {
            SeasonReport report = reports.get(row.getAccountId());
            Account account = accounts.get(row.getAccountId());
            if (report == null || account == null) {
                // 확정과 이 시점 사이에 탈퇴했거나 그 계좌만 확정에서 빠졌다.
                continue;
            }
            payload.add(account(row, report, account,
                    tradesByAccount.getOrDefault(row.getAccountId(), List.of()),
                    tickByDate, positions, closingRate, 0));
        }
        if (payload.size() < settled.size()) {
            log.warn("{} of {} settled accounts have no season report yet; excluded",
                    settled.size() - payload.size(), settled.size());
        }
        // 분모는 실제로 보낸 계좌 수다. AI 의 백분위 기준선("상위 10%")이 받은 것만 보고
        // 만들어지므로, 분모가 그보다 크면 "n 명 중 k 위" 가 그 기준선과 어긋난다.
        List<AiSeasonReportRequest.Account> sized = payload.stream()
                .map(one -> one.withParticipants(payload.size()))
                .toList();
        return new AiSeasonReportRequest(seasonId, sized);
    }

    @SuppressWarnings("checkstyle:ParameterNumber")
    private AiSeasonReportRequest.Account account(DailySettlement settled, SeasonReport report,
                                                  Account account, List<DailyTrade> trades,
                                                  Map<LocalDate, Long> tickByDate,
                                                  PricePosition positions, BigDecimal closingRate,
                                                  long participants) {
        TradeCounts counts = TradeCounts.of(trades, tickByDate, positions);
        return new AiSeasonReportRequest.Account(
                settled.getAccountId(),
                counts.buyCount, counts.sellCount,
                counts.fomoBuyCount, counts.bottomSnipeBuyCount,
                counts.peakSellWinCount, counts.panicBottomSellCount,
                counts.quickProfitCount, counts.quickStopCount,
                // 잠긴 몫을 빼지 않는다. 잠금은 총액을 건드리지 않으므로 (acc.md) 관망형 판정이
                // 봐야 하는 것은 "쓰지 않고 남긴 현금" 전부다.
                Amounts.of(closingKrwCash(settled, account), Amounts.KRW_SCALE),
                Amounts.of(totalCashKrw(settled, account, closingRate), Amounts.KRW_SCALE),
                Amounts.of(settled.getTotalValuationKrw(), Amounts.KRW_SCALE),
                counts.holdingDaysSum, counts.closedPositionCount,
                counts.winCount,
                Amounts.ratio(report.getMaxDrawdown()).toPlainString(),
                Amounts.ratio(report.getTurnover()).toPlainString(),
                Amounts.of(report.getTotalFeeKrw(), Amounts.KRW_SCALE),
                report.getFinalRank(),
                participants,
                display(report.getFinalReturn()));
    }

    /**
     * 상대 가격 위치를 잴 종가. <b>거래한 종목만</b> 읽고, 창이 재생 구간 밖으로 나가므로 양 끝에
     * 여유를 둔다.
     */
    private PricePosition pricePositions(Map<Long, List<DailyTrade>> tradesByAccount) {
        List<DailyTrade> trades = tradesByAccount.values().stream().flatMap(List::stream).toList();
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
     * 원화 현금 + 달러 현금의 원화 환산 합. 투입 비중 판정이 보는 "남긴 현금" 전부다.
     *
     * <p><b>달러를 빼면 판정이 틀린다.</b> 투입 비중을 {@code (총평가액 − 현금) / 총평가액} 으로
     * 재므로, 달러로 바꿔 들고 관망한 사람이 "현금을 하나도 남기지 않았다"로 잡혀 실제보다
     * 공격적인 단계로 올라간다.
     *
     * <p>{@code krwCash} 와 같은 이유로 잠긴 몫을 빼지 않는다.
     */
    private static BigDecimal totalCashKrw(DailySettlement settled, Account account,
                                           BigDecimal closingRate) {
        return closingKrwCash(settled, account)
                .add(closingUsdCash(settled, account).multiply(closingRate));
    }

    /**
     * 마감 원화 현금. <b>계좌가 아니라 확정 행에서 읽는다</b> (V42).
     *
     * <p>결과지 생성은 폐장 뒤 비동기라, 다음 시즌의 참가나 개발용 초기화를 지나 실행되면
     * 계좌의 현금은 이미 다른 값이다. 폐장에 찍어 둔 값이 그 일차의 진짜 마감 현금이고,
     * 일일 결과지가 같은 자리에서 같은 값을 읽는다.
     *
     * <p>이 컬럼이 생기기 전에 확정된 행만 계좌의 현재 값으로 물러난다.
     */
    private static BigDecimal closingKrwCash(DailySettlement settled, Account account) {
        return settled.getClosingKrwCash() == null
                ? account.getKrwCash() : settled.getClosingKrwCash();
    }

    /** 마감 달러 현금. 같은 이유로 같은 폴백을 쓴다. */
    private static BigDecimal closingUsdCash(DailySettlement settled, Account account) {
        return settled.getClosingUsdCash() == null
                ? account.getUsdCash() : settled.getClosingUsdCash();
    }

    /**
     * 폐장 시점의 원/달러 환율. 달러 현금을 원화로 환산하는 데 쓴다.
     *
     * <p><b>총평가액과 같은 시점의 값이어야 한다.</b> 정산 스냅샷이 마지막 틱에서 찍히므로
     * 그 틱의 환율을 쓴다 — 다른 틱의 환율을 쓰면 현금이 총평가액보다 커지는 계좌가 나온다.
     *
     * <p>틱이 없으면 원인을 말하고 멈춘다. 0 으로 넘기면 달러를 든 계좌의 현금이 조용히
     * 원화 몫만 남아 판정이 틀린 채로 저장된다.
     */
    private BigDecimal closingRate() {
        List<GameTick> ticks = gameTickRepository.findBySeasonIdAndSeasonDayOrderByTickSeqAsc(
                seasonId, (short) ReplayState.FINAL_DAY);
        if (ticks.isEmpty()) {
            throw new IllegalStateException(
                    "시즌 " + seasonId + " 의 " + ReplayState.FINAL_DAY + "일차 틱이 없어 "
                            + "폐장 환율을 알 수 없습니다");
        }
        return ticks.getLast().getUsdKrw();
    }

    /** 보유 기간을 재는 자. 시즌 전체이므로 일차 셋의 틱을 모은다. */
    private Map<LocalDate, Long> tickByDate() {
        Map<LocalDate, Long> ticks = new LinkedHashMap<>();
        for (short day = 1; day <= ReplayState.FINAL_DAY; day++) {
            for (GameTick tick : gameTickRepository
                    .findBySeasonIdAndSeasonDayOrderByTickSeqAsc(seasonId, day)) {
                ticks.putIfAbsent(tick.getGameDate(), tick.getTickSeq());
            }
        }
        return ticks;
    }

    /** 화면에 그대로 실리는 수익률. 서식까지 백엔드가 정한다 (요청서 v5 §7.2). */
    private static String display(BigDecimal finalReturn) {
        BigDecimal rounded = finalReturn.setScale(DISPLAY_SCALE, RoundingMode.HALF_UP);
        return (rounded.signum() >= 0 ? "+" : "") + rounded.toPlainString() + "%";
    }

    /**
     * 받은 문구를 저장한다. 보낸 적 없는 계좌는 버린다 — 그 행은 {@code accounts} 외래키를
     * 맞추지 못해 insert 에서 터지고, 한 트랜잭션이라 전원의 문구를 잃는다.
     */
    private void save(AiSeasonReportResponse response, Set<Long> requested) {
        Instant generatedAt = Instant.now(wallClock);
        Map<Long, SeasonReportText> texts = new LinkedHashMap<>();
        for (AiSeasonReportResponse.Report report : response.reports()) {
            if (!requested.contains(report.accountId())) {
                log.warn("AI returned a season text for unknown account {}; dropped",
                        report.accountId());
                continue;
            }
            if (!usable(report)) {
                log.warn("Unusable season text for account {}; dropped that row",
                        report.accountId());
                continue;
            }
            texts.putIfAbsent(report.accountId(), new SeasonReportText(report.accountId(),
                    report.investorType(), report.typeName(), report.typeDescription(),
                    report.verdict(), json.writeValueAsString(report.blockIds()), generatedAt));
        }
        textRepository.saveAll(texts.values());
        log.info("Saved {} of {} AI season texts", texts.size(), response.reports().size());
    }

    /**
     * 저장할 수 있는 문구인가 — 네 문자열이 모두 차 있고 컬럼 길이를 넘지 않는가.
     *
     * <p><b>한 행의 결함이 전원의 문구를 날리지 않게 한다.</b> 저장이 한 트랜잭션이라
     * 컬럼 제약 하나를 어기면 시즌 문구 전부가 되돌아간다.
     *
     * <p>특히 {@code investorType} 은 <b>AI 만 아는 값</b>이다 (9 종 판정은 AI 가 한다).
     * 그 자리가 비면 우리가 채울 수 있는 것이 없으므로 그 계좌만 문구 없이 둔다.
     */
    private static boolean usable(AiSeasonReportResponse.Report report) {
        return usable(report.investorType(), SeasonReportText.INVESTOR_TYPE_MAX)
                && usable(report.typeName(), SeasonReportText.TYPE_NAME_MAX)
                && usable(report.typeDescription(), SeasonReportText.TYPE_DESCRIPTION_MAX)
                && usable(report.verdict(), SeasonReportText.VERDICT_MAX);
    }

    private static boolean usable(String value, int max) {
        return value != null && !value.isBlank() && value.length() <= max;
    }

}

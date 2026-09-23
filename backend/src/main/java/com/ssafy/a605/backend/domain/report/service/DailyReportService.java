package com.ssafy.a605.backend.domain.report.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ssafy.a605.backend.domain.account.service.AccountService;
import com.ssafy.a605.backend.domain.market.entity.Currency;
import com.ssafy.a605.backend.domain.market.entity.DailyClose;
import com.ssafy.a605.backend.domain.market.entity.Instrument;
import com.ssafy.a605.backend.domain.market.repository.DailyCloseRepository;
import com.ssafy.a605.backend.domain.market.repository.InstrumentRepository;
import com.ssafy.a605.backend.domain.order.repository.ExecutionRepository;
import com.ssafy.a605.backend.domain.order.repository.ExecutionRepository.DailyTrade;
import com.ssafy.a605.backend.domain.ranking.entity.DailyHolding;
import com.ssafy.a605.backend.domain.ranking.entity.DailySettlement;
import com.ssafy.a605.backend.domain.ranking.repository.DailyHoldingRepository;
import com.ssafy.a605.backend.domain.ranking.repository.DailySettlementRepository;
import com.ssafy.a605.backend.domain.ranking.service.AwardService;
import com.ssafy.a605.backend.domain.replay.entity.ReplayState;
import com.ssafy.a605.backend.domain.replay.service.ReplayService;
import com.ssafy.a605.backend.domain.report.dto.DailyReportListResponse;
import com.ssafy.a605.backend.domain.report.dto.DailyReportListResponse.Item;
import com.ssafy.a605.backend.domain.report.dto.DailyReportResponse;
import com.ssafy.a605.backend.domain.report.dto.DailyReportResponse.Closing;
import com.ssafy.a605.backend.domain.report.dto.DailyReportResponse.ClosingHolding;
import com.ssafy.a605.backend.domain.report.dto.DailyReportResponse.InstrumentReplay;
import com.ssafy.a605.backend.domain.report.dto.DailyReportResponse.Marker;
import com.ssafy.a605.backend.domain.report.dto.DailyReportResponse.Point;
import com.ssafy.a605.backend.domain.report.dto.DailyReportResponse.Commentary;
import com.ssafy.a605.backend.domain.report.dto.DailyReportResponse.Summary;
import com.ssafy.a605.backend.domain.report.dto.DailyReportResponse.Trade;
import com.ssafy.a605.backend.domain.report.dto.PatternTag;
import com.ssafy.a605.backend.domain.report.dto.Review;
import com.ssafy.a605.backend.domain.report.entity.DailyReportText;
import com.ssafy.a605.backend.domain.report.repository.DailyReportTextRepository;
import com.ssafy.a605.backend.domain.season.entity.SeasonDay;
import com.ssafy.a605.backend.domain.season.entity.GameTick;
import com.ssafy.a605.backend.domain.season.repository.GameTickRepository;
import com.ssafy.a605.backend.domain.season.repository.GameTickRepository.GameDateRange;
import com.ssafy.a605.backend.domain.season.repository.SeasonDayRepository;
import com.ssafy.a605.backend.domain.season.service.GameTickCatalog;
import com.ssafy.a605.backend.global.exception.BusinessException;
import com.ssafy.a605.backend.global.response.Amounts;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * 일일 복기 조회 ({@code rpt.md} §GET /api/v1/reports/daily, 기획서 13.1).
 *
 * <p><b>정산 발표 전에는 열리지 않는다.</b> 폐장(16:40)에 리포트가 만들어지지만 발표(17:00)
 * 까지 20 분 동안 409 다 — 기획서 13.1.1 이 "정산 발표 이후"를 명시하고 시상이 같은 리듬을
 * 따른다. 발표 여부 판정은 {@link AwardService#lastAnnouncedDay} 를 그대로 쓴다. 두 곳이
 * 같은 시점을 다르게 판정하면 시상은 보이는데 복기는 닫힌 순간이 생긴다.
 *
 * <p><b>저장값을 다시 계산하지 않는다.</b> 요약·상하위·태그는 {@code daily_settlements} 에
 * 얼린 값을 그대로 읽는다 ({@code _common.md} §6.3). 차트와 마커만 조회 시점에 만든다.
 *
 * <p><b>거래한 종목만 담는다</b> (기획서 13.1.2). 유니버스 28 종을 다 내리면 사지도 않은
 * 종목의 차트가 복기 화면을 채운다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DailyReportService {

    /** 저장된 태그 JSON 을 되읽는 타입. */
    private static final TypeReference<List<PatternTag>> TAGS = new TypeReference<>() { };

    /** 저장된 별칭 JSON 을 되읽는 타입. */
    private static final TypeReference<List<String>> ALIASES = new TypeReference<>() { };

    /** 결과지의 구조 있는 두 덩이. JSONB 로 저장돼 있어 읽을 때 타입 토큰이 필요하다 (V37). */
    private static final TypeReference<List<Review.ProfileItem>> PROFILE_ITEMS =
            new TypeReference<>() { };

    private static final TypeReference<Review.Narrative> NARRATIVE = new TypeReference<>() { };

    private final ReplayService replay;
    private final AccountService accounts;
    private final DailySettlementRepository settlementRepository;
    /** 그 일차 마감 보유. 다음 일차 개장에 positions 가 초기화되므로 이것이 유일한 기록이다 (V42) */
    private final DailyHoldingRepository holdingRepository;
    /** 마감 평가에 쓴 틱. 거래일과 환율의 출처다 (V42) */
    private final GameTickCatalog gameTicks;
    private final SeasonDayRepository seasonDayRepository;
    private final GameTickRepository gameTickRepository;
    private final DailyCloseRepository dailyCloseRepository;
    private final InstrumentRepository instrumentRepository;
    private final ExecutionRepository executionRepository;
    private final DailyReportTextRepository textRepository;
    private final ObjectMapper json;

    @Value("${app.replay.season-id:1}")
    private long seasonId;

    /**
     * 열람 가능한 일차 목록.
     *
     * @param userId 세션의 User 식별자
     * @return 시즌의 모든 일차. 미발표 일차는 구간과 수익률이 비어 있다
     * @throws BusinessException 그 시즌에 참가하지 않아 계좌가 없을 때 (404)
     */
    public DailyReportListResponse list(Long userId) {
        Long accountId = accounts.accountId(userId);
        short lastAnnounced = AwardService.lastAnnouncedDay(replay.clock());

        List<Item> items = new ArrayList<>();
        for (short day = 1; day <= ReplayState.FINAL_DAY; day++) {
            if (day > lastAnnounced) {
                items.add(Item.unavailable(day));
                continue;
            }
            SeasonDay schedule = schedule(day);
            // 중간 합류자는 발표된 일차에도 확정 행이 없다. 그 경우 수익률만 비운다 -
            // available 은 발표 여부라는 계약을 지킨다 (rpt.md).
            items.add(new Item(day, schedule.getFromGameMonth(), schedule.getToGameMonth(),
                    settlement(day, accountId).map(DailySettlement::getDailyReturn).orElse(null),
                    true));
        }
        return new DailyReportListResponse(items);
    }

    /**
     * 그 일차의 일일 복기.
     *
     * @param userId    세션의 User 식별자
     * @param seasonDay 조회할 일차
     * @return 저장된 요약과 조회 시점에 만든 차트·마커
     * @throws BusinessException 시즌에 없는 일차이거나 내 확정 행이 없을 때 (404),
     *                           아직 정산 발표 전일 때 (409)
     */
    public DailyReportResponse detail(Long userId, short seasonDay) {
        if (seasonDay < 1 || seasonDay > ReplayState.FINAL_DAY) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "그 일차는 이 시즌에 없습니다.");
        }
        if (seasonDay > AwardService.lastAnnouncedDay(replay.clock())) {
            throw new BusinessException(HttpStatus.CONFLICT, "정산 발표 후에 열람할 수 있습니다.");
        }
        Long accountId = accounts.accountId(userId);
        DailySettlement row = settlement(seasonDay, accountId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND,
                        "그 일차의 복기가 없습니다."));

        List<DailyTrade> trades = executionRepository.findDailyTrades(List.of(accountId), seasonDay);
        SeasonDay schedule = schedule(seasonDay);
        // 머리말과 결과지가 같은 행이므로 한 번만 읽는다.
        Optional<DailyReportText> text =
                textRepository.findById(new DailyReportText.Pk(seasonDay, accountId));
        return new DailyReportResponse(seasonDay, schedule.getFromGameMonth(),
                schedule.getToGameMonth(), row.getFinalizedAt(), summary(row),
                trade(row.getBestExecutionId(), trades), trade(row.getWorstExecutionId(), trades),
                instruments(trades, seasonDay), json.readValue(row.getPatternTags(), TAGS),
                closing(row), closingHoldings(seasonDay, accountId),
                commentary(text, seasonDay, accountId), review(text, seasonDay, accountId), null);
    }

    /**
     * 그 일차를 끝낸 자산 (V42).
     *
     * <p><b>구성이 없으면 통째로 {@code null} 이다.</b> 이 컬럼이 생기기 전에 확정된 일차는
     * 마감 현금도 기준 틱도 없어, 합계만 남기면 "현금 0 원으로 끝냈다" 로 읽힌다.
     *
     * <p>합계는 확정 평가액이고 구성은 폐장 시점 잔고다. 마지막 틱이 폐장보다 31 분 앞서
     * 끝나므로 둘이 갈릴 수 있으며, 그 차이는 화면이 읽는 사람에게 설명한다.
     */
    private Closing closing(DailySettlement row) {
        if (row.getClosingTickSeq() == null) {
            return null;
        }
        GameTick tick = gameTicks.find(row.getClosingTickSeq()).orElse(null);
        if (tick == null) {
            // 편성이 바뀌어 그 틱이 사라졌다. 환율 없이는 달러 현금을 원화로 읽을 수 없다.
            log.error("Closing tick {} is absent from the schedule; closing composition skipped",
                    row.getClosingTickSeq());
            return null;
        }
        BigDecimal holdings = holdingRepository
                .findBySeasonDayAndAccountId(row.getSeasonDay(), row.getAccountId()).stream()
                .map(DailyHolding::getValuationKrw)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new Closing(
                Amounts.of(row.getTotalValuationKrw(), Amounts.KRW_SCALE),
                Amounts.of(row.getClosingKrwCash(), Amounts.KRW_SCALE),
                Amounts.of(row.getClosingUsdCash(), Amounts.USD_SCALE),
                Amounts.of(tick.getUsdKrw(), Amounts.RATE_SCALE),
                Amounts.of(holdings, Amounts.KRW_SCALE),
                tick.getGameDate(),
                row.getDailyRank(),
                settlementRepository.countBySeasonDay(seasonId, row.getSeasonDay()),
                row.getMaxDrawdown() == null ? null : Amounts.ratio(row.getMaxDrawdown()),
                row.getSeasonDay() < ReplayState.FINAL_DAY);
    }

    /**
     * 그 일차를 들고 끝낸 종목 (V42). 평가액이 큰 것부터다.
     *
     * <p><b>거래 종목 목록과 다르다.</b> 그쪽은 "그날 사고판 것" 이고 이쪽은 "그날 들고
     * 끝낸 것" 이다 — 산 뒤 그대로 들고 끝냈으면 양쪽에 있고, 전량 팔았으면 거래에만,
     * 전날 넘어온 것을 팔지 않았으면 보유에만 있다.
     *
     * <p>다음 일차 개장에 {@code positions} 가 초기화되므로 이 기록이 유일한 근거다.
     * 스냅샷이 없는 일차(이 테이블이 생기기 전)는 빈 목록이고, 화면은 그 구역을 비운다.
     */
    private List<ClosingHolding> closingHoldings(short seasonDay, Long accountId) {
        List<DailyHolding> held = holdingRepository.findBySeasonDayAndAccountId(seasonDay, accountId);
        if (held.isEmpty()) {
            return List.of();
        }
        Map<Long, Instrument> meta = instrumentRepository
                .findAllById(held.stream().map(DailyHolding::getInstrumentId).toList()).stream()
                .collect(Collectors.toMap(Instrument::getId, instrument -> instrument));
        return held.stream()
                .filter(holding -> meta.containsKey(holding.getInstrumentId()))
                .sorted(Comparator.comparing(DailyHolding::getValuationKrw).reversed())
                .map(holding -> closingHolding(holding, meta.get(holding.getInstrumentId())))
                .toList();
    }

    /** 평단만 나눗셈이다. 저장하지 않으므로 오차가 다음 계산으로 넘어가지 않는다. */
    private static ClosingHolding closingHolding(DailyHolding holding, Instrument instrument) {
        short priceScale = instrument.getPriceScale();
        int moneyScale = instrument.getCurrency() == Currency.USD
                ? Amounts.USD_SCALE : Amounts.KRW_SCALE;
        BigDecimal quantity = BigDecimal.valueOf(holding.getQuantity());
        return new ClosingHolding(holding.getInstrumentId(), instrument.getCurrency(), priceScale,
                holding.getQuantity(),
                Amounts.of(holding.getTotalCost(), moneyScale),
                Amounts.of(holding.getTotalCost().divide(quantity, priceScale, RoundingMode.HALF_UP),
                        priceScale),
                Amounts.of(holding.getValuationKrw(), Amounts.KRW_SCALE));
    }

    /**
     * AI 가 만든 그날의 문구. 없으면 {@code null} 이다.
     *
     * <p><b>문구가 없다고 복기를 막지 않는다.</b> 문구는 폐장 뒤 외부 호출이 성공해야
     * 생기는 값이고 집계는 이미 확정돼 있다 — 자리를 비운 채로 연다 (요청서 v5 §6).
     *
     * <p>저장된 별칭은 JSON 문자열이다. 읽지 못하면 그 사람만 문구 없이 본다 — 깨진
     * 한 줄 때문에 나머지 리포트까지 닫을 이유가 없다.
     */
    private Commentary commentary(Optional<DailyReportText> stored, short seasonDay,
                                  Long accountId) {
        return stored.map(text -> {
            try {
                return new Commentary(text.getHeadline(),
                        json.readValue(text.getSceneAliases(), ALIASES));
            } catch (RuntimeException e) {
                log.error("Unreadable scene aliases for account {} on day {}",
                        accountId, seasonDay, e);
                return null;
            }
        }).orElse(null);
    }

    /**
     * 복기 결과지. 없으면 {@code null} 이다.
     *
     * <p><b>없는 경우가 셋이다.</b> 문구 행 자체가 없을 때, 행은 있지만 결과지를 받지 못했을
     * 때(일일 요청에 누적 집계를 싣지 않으면 AI 가 만들지 않는다), 그리고 저장 단계에서 결과지만
     * 걸러졌을 때다. 셋 다 화면에서는 같다 — 결과지 카드만 비고 나머지 복기는 열린다.
     *
     * <p>구조가 있는 둘은 JSONB 다. 읽지 못하면 <b>그 사람만</b> 결과지 없이 본다 — 깨진 한
     * 덩이 때문에 나머지 리포트까지 닫을 이유가 없고, {@code commentary} 가 같은 판단을 한다.
     */
    private Review review(Optional<DailyReportText> stored, short seasonDay, Long accountId) {
        return stored.filter(DailyReportText::hasReview).map(text -> {
            try {
                return new Review(text.getInvestorType(), text.getTypeEmoji(), text.getTypeName(),
                        text.getTypeDescription(), text.getVerdict(), text.getProfileLevel(),
                        text.getProfileTotal(), text.getProfileName(), text.getProfilePersona(),
                        readJson(text.getProfileItems(), PROFILE_ITEMS), text.getSelfLevel(),
                        text.getSelfTotal(), text.getSelfPersona(), text.getProfileMatch(),
                        readJson(text.getNarrative(), NARRATIVE),
                        // 블록 목록은 저장용이다. 화면이 쓰지 않으므로 응답에 싣지 않는다.
                        null);
            } catch (RuntimeException e) {
                log.error("Unreadable review for account {} on day {}", accountId, seasonDay, e);
                return null;
            }
        }).orElse(null);
    }

    /** 비어 있을 수 있는 JSONB 한 덩이. 그 자리가 비는 것이 정상인 결과지가 있다. */
    private <T> T readJson(String value, TypeReference<T> type) {
        return value == null ? null : json.readValue(value, type);
    }

    private Summary summary(DailySettlement row) {
        return new Summary(row.getDailyReturn(), row.getExecutionCount(),
                Amounts.of(row.getTotalFeeKrw(), Amounts.KRW_SCALE),
                Amounts.of(row.getRealizedPnlKrw(), Amounts.KRW_SCALE));
    }

    /**
     * 상·하위 거래 한 건.
     *
     * <p>수익률은 <b>덜어낸 취득 원가 대비</b>다. 체결 행이 그 값을 되짚을 수 있게 담고 있어
     * ({@code DailyTrade.removedCost}) 평단가를 저장하지 않는 규칙과 어긋나지 않는다.
     * 비율은 통화 중립이라 환산 없이 계산하고, 손익 금액만 그 틱의 환율로 원화로 만든다.
     */
    private Trade trade(Long executionId, List<DailyTrade> trades) {
        if (executionId == null) {
            return null;
        }
        DailyTrade row = trades.stream()
                .filter(trade -> trade.executionId().equals(executionId))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "확정 행이 가리키는 체결이 그 일차에 없습니다: execution=%d".formatted(executionId)));
        return new Trade(row.executionId(), row.instrumentId(),
                Amounts.of(row.realizedPnl().multiply(row.krwRate()), Amounts.KRW_SCALE),
                Amounts.percent(row.realizedPnl(), row.removedCost()));
    }

    /**
     * 거래한 종목별 차트와 마커.
     *
     * <p>조회는 거래한 종목 수와 무관하게 세 번이다 — 편성 구간 한 번, 종목 메타 한 번,
     * 종가 한 번. 종목마다 시계열을 부르면 왕복이 종목 수만큼 늘어난다.
     *
     * <p>종가는 <b>그 일차 구간으로 자른다.</b> 시계열은 시즌 시작 전에 전 구간이 적재되어
     * 있어({@code _common.md} §9) 자르지 않으면 다음 일차의 종가가 차트에 실린다.
     */
    private List<InstrumentReplay> instruments(List<DailyTrade> trades, short seasonDay) {
        if (trades.isEmpty()) {
            return List.of();
        }
        GameDateRange range = gameTickRepository.findGameDateRange(seasonId, seasonDay);
        Set<Long> instrumentIds = trades.stream().map(DailyTrade::instrumentId)
                .collect(Collectors.toSet());
        Map<Long, Instrument> meta = instrumentRepository.findAllById(instrumentIds).stream()
                .collect(Collectors.toMap(Instrument::getId, instrument -> instrument));

        Map<Long, List<Point>> points = points(instrumentIds, range, meta);
        Map<Long, List<Marker>> markers = markers(trades, meta);

        return markers.keySet().stream().sorted()
                .map(instrumentId -> new InstrumentReplay(instrumentId,
                        meta.get(instrumentId).getCurrency(),
                        meta.get(instrumentId).getPriceScale(),
                        points.getOrDefault(instrumentId, List.of()),
                        markers.get(instrumentId)))
                .toList();
    }

    /** 종목별 종가 점. 휴장일은 행이 없어 점도 생기지 않는다 ({@code mkt.md}). */
    private Map<Long, List<Point>> points(Set<Long> instrumentIds, GameDateRange range,
                                          Map<Long, Instrument> meta) {
        Map<Long, List<Point>> points = new LinkedHashMap<>();
        for (DailyClose close : dailyCloseRepository
                .findByInstrumentIdInAndGameDateBetweenOrderByGameDateAsc(
                        instrumentIds, range.fromGameDate(), range.toGameDate())) {
            points.computeIfAbsent(close.getInstrumentId(), key -> new ArrayList<>())
                    .add(new Point(close.getGameDate(), Amounts.of(close.getClosePrice(),
                            meta.get(close.getInstrumentId()).getPriceScale())));
        }
        return points;
    }

    /** 종목별 체결 마커. 날짜순이고 같은 날은 체결 순서다. */
    private Map<Long, List<Marker>> markers(List<DailyTrade> trades, Map<Long, Instrument> meta) {
        Map<Long, List<Marker>> markers = new LinkedHashMap<>();
        trades.stream()
                .sorted(Comparator.comparing(DailyTrade::gameDate)
                        .thenComparing(DailyTrade::executionId))
                .forEach(trade -> markers
                        .computeIfAbsent(trade.instrumentId(), key -> new ArrayList<>())
                        .add(new Marker(trade.executionId(), trade.gameDate(), trade.side(),
                                trade.quantity(), Amounts.of(trade.executedPrice(),
                                meta.get(trade.instrumentId()).getPriceScale()))));
        return markers;
    }

    private Optional<DailySettlement> settlement(short seasonDay, Long accountId) {
        return settlementRepository.findById(new DailySettlement.Pk(seasonDay, accountId));
    }

    private SeasonDay schedule(short seasonDay) {
        return seasonDayRepository.findById(new SeasonDay.Pk(seasonId, seasonDay))
                .orElseThrow(() -> new IllegalStateException(
                        "발표된 일차가 편성에 없습니다: day=%d".formatted(seasonDay)));
    }
}

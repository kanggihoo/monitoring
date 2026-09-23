package com.ssafy.a605.backend.domain.report.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
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
import com.ssafy.a605.backend.domain.ranking.entity.DailySettlement;
import com.ssafy.a605.backend.domain.ranking.repository.DailySettlementRepository;
import com.ssafy.a605.backend.domain.ranking.service.AwardService;
import com.ssafy.a605.backend.domain.replay.entity.ReplayState;
import com.ssafy.a605.backend.domain.replay.service.ReplayService;
import com.ssafy.a605.backend.domain.report.dto.WhatIfResponse;
import com.ssafy.a605.backend.domain.report.dto.WhatIfResponse.Day;
import com.ssafy.a605.backend.domain.season.entity.GameTick;
import com.ssafy.a605.backend.domain.season.service.GameTickCatalog;
import com.ssafy.a605.backend.global.exception.BusinessException;
import com.ssafy.a605.backend.global.response.Amounts;

import lombok.RequiredArgsConstructor;

/**
 * '만약에' 시뮬레이터 (기획서 13.3.4, {@code rpt.md} §GET /api/v1/reports/season/what-if).
 *
 * <p><b>시즌 리포트와 분리된 이유</b>는 파라미터 의존 계산이기 때문이다. 사용자가 28 종을
 * 눌러 가며 비교하는 인터랙션이라 리포트 응답에 미리 담으면 대부분 안 쓰이고, 시즌 리포트는
 * 스냅샷인데 이것은 물을 때마다 계산하는 값이라 성격이 다르다.
 *
 * <p><b>조회가 종목 수와 무관하게 네 번이다.</b> 종목 메타, 시즌 틱, 종가 시계열, 내 확정 행.
 * 틱을 한 번에 읽어 일차 경계와 환율을 함께 얻는다 — 일차마다 부르면 왕복이 일차 수만큼 는다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WhatIfService {

    private final ReplayService replay;
    private final AccountService accounts;
    private final InstrumentRepository instrumentRepository;
    private final GameTickCatalog gameTicks;
    private final DailyCloseRepository dailyCloseRepository;
    private final DailySettlementRepository settlementRepository;

    @Value("${app.replay.season-id:1}")
    private long seasonId;

    /**
     * 그 종목을 들고만 있었다면의 수익률.
     *
     * @param userId       세션의 User 식별자
     * @param instrumentId 비교할 종목
     * @return 시즌 전체와 발표된 일차별 비교
     * @throws BusinessException 아직 발표된 일차가 없을 때 (409), 유니버스에 없는 종목이거나
     *                           그 시즌 계좌가 없을 때 (404)
     */
    public WhatIfResponse simulate(Long userId, Long instrumentId) {
        short lastAnnounced = AwardService.lastAnnouncedDay(replay.clock());
        if (lastAnnounced < 1) {
            throw new BusinessException(HttpStatus.CONFLICT, "정산 발표 후에 열람할 수 있습니다.");
        }
        // 시뮬레이션 대상도 화면에서 고른 종목이어야 한다 — 다른 시즌 종목과 지수 대리 종목은
        // 목록에 없으므로 여기로 들어올 이유가 없다.
        Instrument instrument = instrumentRepository.findByIdAndSeasonId(instrumentId, seasonId)
                .filter(found -> found.getAssetType().isTradable())
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "없는 종목입니다."));
        Long accountId = accounts.accountId(userId);

        List<GameTick> ticks = gameTicks.ordered();
        Map<Short, List<GameTick>> byDay = ticks.stream()
                .collect(Collectors.groupingBy(GameTick::getSeasonDay));
        Map<LocalDate, BigDecimal> fx = ticks.stream()
                .collect(Collectors.toMap(GameTick::getGameDate, GameTick::getUsdKrw));
        Map<Short, DailySettlement> mine = settlementRepository
                .findByAccountIdOrderBySeasonDayAsc(accountId).stream()
                .collect(Collectors.toMap(DailySettlement::getSeasonDay, Function.identity()));

        // 시즌 전체 구간의 종가를 한 번에 읽고 일차별로는 메모리에서 자른다.
        List<DailyClose> closes = dailyCloseRepository
                .findByInstrumentIdAndGameDateBetweenOrderByGameDateAsc(instrumentId,
                        ticks.get(0).getGameDate(), ticks.get(ticks.size() - 1).getGameDate());

        List<Day> days = new ArrayList<>();
        for (short day = 1; day <= lastAnnounced; day++) {
            DailySettlement row = mine.get(day);
            days.add(new Day(day, rateWithin(closes, fx, instrument, bounds(byDay, day)),
                    row == null ? null : row.getDailyReturn()));
        }

        boolean seasonClosed = lastAnnounced >= ReplayState.FINAL_DAY;
        return new WhatIfResponse(instrumentId,
                seasonClosed ? rateWithin(closes, fx, instrument, seasonBounds(ticks)) : null,
                seasonClosed ? seasonReturn(mine.values()) : null,
                List.copyOf(days));
    }

    /**
     * 시즌 내 성과(%) = <b>참가한 일차의 일간 수익률 합</b>.
     *
     * <p><b>마지막 정산의 {@code season_return} 을 쓰지 않는다.</b> 일차 경계마다 자산이
     * 시드로 돌아가므로 그 값은 누적이 아니라 3일차 하루의 수익률이다 — 그대로 비교에 쓰면
     * "그때 샀다면" 의 시즌 전체 수익률과 하루치를 나란히 놓게 된다. 시즌 리포트의
     * {@code finalReturn} 과 같은 정의다.
     *
     * <p>참가한 일차가 하나도 없으면 {@code null} 이다 — 0 을 주면 "본전이었다" 로 읽힌다.
     */
    private static BigDecimal seasonReturn(java.util.Collection<DailySettlement> mine) {
        return mine.isEmpty() ? null : mine.stream().map(DailySettlement::getDailyReturn)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * 그 구간을 들고 있었다면의 수익률(%).
     *
     * <p><b>상장일보다 앞선 날은 보지 않는다.</b> IPO 종목은 상장 전에 살 수 없었으므로
     * "그때 샀다면"이 성립하지 않는다 ({@code rpt.md}). 종가가 있는지로 판정하면 안 된다 —
     * 시세 시계열에는 상장 전 종가가 들어 있을 수 있고, 검증 픽스처가 그 상태를 일부러
     * 만들어 둔다.
     *
     * <p><b>구간 안에 실제로 종가가 있는 첫날과 마지막 날을 쓴다.</b> 휴장일은
     * {@code daily_closes} 에 행이 없으므로 경계 날짜를 그대로 집으면 값이 없다. 이월하지
     * 않는 것이 시계열의 규칙이라 ({@code mkt.md}) 있는 날로 좁힌다.
     *
     * <p><b>USD 종목은 양 끝 환율로 환산한다.</b> 원화 기준 비교인데 환산하지 않으면 환차손익이
     * 빠진다. 원화 종목은 곱수가 1 이라 같은 식이 그대로 쓰인다.
     *
     * @return 수익률(%). 구간에 종가가 없거나 한 점뿐이면 {@code null} — 그 일차에 아직
     *         상장 전이었던 IPO 종목이 그렇고, 살 수 없었으므로 가정이 성립하지 않는다
     */
    private static BigDecimal rateWithin(List<DailyClose> closes, Map<LocalDate, BigDecimal> fx,
                                         Instrument instrument, Bounds bounds) {
        LocalDate from = instrument.getListedGameDate() == null
                || instrument.getListedGameDate().isBefore(bounds.from())
                ? bounds.from() : instrument.getListedGameDate();
        List<DailyClose> within = closes.stream()
                .filter(close -> !close.getGameDate().isBefore(from)
                        && !close.getGameDate().isAfter(bounds.to()))
                .sorted(Comparator.comparing(DailyClose::getGameDate))
                .toList();
        if (within.size() < 2) {
            return null;
        }
        BigDecimal start = inKrw(within.get(0), fx, instrument);
        BigDecimal end = inKrw(within.get(within.size() - 1), fx, instrument);
        if (start.signum() == 0) {
            return null;
        }
        return Amounts.percent(end.subtract(start), start);
    }

    /** 그 거래일의 원화 환산 종가. 원화 종목은 환율을 보지 않는다. */
    private static BigDecimal inKrw(DailyClose close, Map<LocalDate, BigDecimal> fx,
                                    Instrument instrument) {
        if (instrument.getCurrency() != Currency.USD) {
            return close.getClosePrice();
        }
        return close.getClosePrice().multiply(fx.get(close.getGameDate()));
    }

    private static Bounds bounds(Map<Short, List<GameTick>> byDay, short seasonDay) {
        List<GameTick> ticks = byDay.getOrDefault(seasonDay, List.of());
        if (ticks.isEmpty()) {
            throw new IllegalStateException("발표된 일차가 편성에 없습니다: day=%d".formatted(seasonDay));
        }
        return new Bounds(ticks.get(0).getGameDate(), ticks.get(ticks.size() - 1).getGameDate());
    }

    private static Bounds seasonBounds(List<GameTick> ticks) {
        return new Bounds(ticks.get(0).getGameDate(), ticks.get(ticks.size() - 1).getGameDate());
    }

    /** 수익률을 잴 게임 거래일 구간. 양끝을 포함한다. */
    private record Bounds(LocalDate from, LocalDate to) {
    }
}

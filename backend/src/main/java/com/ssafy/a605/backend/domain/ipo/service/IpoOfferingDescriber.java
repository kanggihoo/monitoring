package com.ssafy.a605.backend.domain.ipo.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.ssafy.a605.backend.domain.ipo.dto.IpoCompetitionResponse;
import com.ssafy.a605.backend.domain.ipo.dto.IpoOfferingResponse;
import com.ssafy.a605.backend.domain.ipo.dto.IpoSubscriptionResponse;
import com.ssafy.a605.backend.domain.ipo.entity.IpoOffering;
import com.ssafy.a605.backend.domain.ipo.entity.IpoSubscription;
import com.ssafy.a605.backend.domain.ipo.repository.IpoSubscriptionRepository;
import com.ssafy.a605.backend.domain.market.entity.Instrument;
import com.ssafy.a605.backend.domain.market.repository.InstrumentRepository;
import com.ssafy.a605.backend.domain.replay.dto.GameClockSnapshot;
import com.ssafy.a605.backend.domain.replay.service.ReplayService;
import com.ssafy.a605.backend.domain.season.entity.GameTick;
import com.ssafy.a605.backend.domain.season.service.GameTickCatalog;
import com.ssafy.a605.backend.domain.season.repository.SeasonRepository;
import com.ssafy.a605.backend.global.response.Amounts;

import lombok.RequiredArgsConstructor;

/**
 * 공모를 화면이 읽을 형태로 옮긴다. 목록·상세·신청이 같은 항목을 그리므로 한곳에 둔다 —
 * {@code OpenOrderDescriber} 가 예약에 대해 같은 일을 한다.
 *
 * <p><b>여러 건을 한 번에 받는다.</b> 공모 하나를 그리는 데 종목 메타·청약자 수·내 청약·실제
 * 시각 넷이 필요한데, 건마다 물으면 목록 8건에 서른 번 넘는 조회가 된다. 그리고 그 조회들이
 * 갈라지면 경쟁률과 국면이 서로 다른 순간의 값이 된다.
 *
 * <p><b>종목 메타를 시장 도메인에서 직접 읽는다.</b> 공모 화면에 필요한 것은 이름·사업 요약
 * ·가격 자릿수이고, 시세 조회 경로({@code MarketPriceService})는 그중 자릿수만 준다.
 * {@code NewsService} 와 {@code HeatmapAggregator} 가 같은 이유로 같은 저장소를 읽는다.
 */
@Component
@RequiredArgsConstructor
public class IpoOfferingDescriber {

    private static final BigDecimal PERCENT = BigDecimal.valueOf(100);

    private final IpoSubscriptionRepository subscriptionRepository;
    private final InstrumentRepository instrumentRepository;
    private final GameTickCatalog gameTicks;
    private final SeasonRepository seasonRepository;
    private final ReplayService replay;

    @Value("${app.replay.season-id:1}")
    private long seasonId;

    /**
     * 당첨 예정 인원 비율(%). <b>시즌 편성값</b>이다 (기획서 8.4.2 — "비율은 시즌 편성값으로
     * 관리하며 시즌 1 은 30%").
     *
     * <p>매번 읽는 것은 편성이 한 행이고 조회가 이미 트랜잭션 안에 있기 때문이다. 값을 코드에
     * 박으면 편성 화면이 바꾼 값과 서버가 쓰는 값이 갈린다.
     *
     * @return 그 시즌의 비율
     * @throws IllegalStateException 시즌 편성이 없을 때 — 그 상태에서는 추첨 인원을 정할 수 없다
     */
    BigDecimal winnerRatio() {
        return seasonRepository.findById(seasonId)
                .orElseThrow(() -> new IllegalStateException(
                        "시즌 편성이 없어 당첨 예정 인원을 정할 수 없습니다: season=%d".formatted(seasonId)))
                .getIpoWinnerRatio();
    }

    /**
     * 여러 공모를 그린다.
     *
     * @param offerings 공시된 공모들. 순서를 그대로 유지한다
     * @param accountId 보는 사람의 계좌. {@code mySubscription} 이 이 계좌 기준이며,
     *                  <b>전체 브로드캐스트에는 보는 사람이 없으므로</b> {@code null} 이다
     * @param clock     이 응답이 서는 시점. 국면과 실제 시각이 여기서 나온다
     * @param detailed  공시 본문({@code summary}·{@code allocationRule})을 실을지
     * @return 화면 항목들
     */
    public List<IpoOfferingResponse> describe(List<IpoOffering> offerings, Long accountId,
                                              GameClockSnapshot clock, boolean detailed) {
        if (offerings.isEmpty()) {
            return List.of();
        }
        List<Long> offeringIds = offerings.stream().map(IpoOffering::getId).toList();
        Map<Long, Instrument> instruments = instrumentRepository
                .findAllById(offerings.stream().map(IpoOffering::getInstrumentId).toList()).stream()
                .collect(Collectors.toMap(Instrument::getId, Function.identity()));
        Map<Long, Integer> counts = subscriberCounts(offeringIds);
        Map<Long, IpoSubscription> mine = accountId == null ? Map.of() : subscriptionRepository
                .findByAccountIdAndOfferingIdIn(accountId, offeringIds).stream()
                .collect(Collectors.toMap(IpoSubscription::getOfferingId, Function.identity()));
        Map<Long, Instant> schedule = schedule(offerings, clock);
        BigDecimal ratio = winnerRatio();

        List<IpoOfferingResponse> described = new ArrayList<>(offerings.size());
        for (IpoOffering offering : offerings) {
            described.add(describe(offering, instruments.get(offering.getInstrumentId()),
                    counts.getOrDefault(offering.getId(), 0), mine.get(offering.getId()),
                    schedule, clock, detailed, ratio));
        }
        return described;
    }

    /**
     * 한 공모를 그린다. 목록과 같은 형태를 보장하려고 같은 경로를 탄다.
     *
     * @param offering  공시된 공모
     * @param accountId 보는 사람의 계좌
     * @param clock     이 응답이 서는 시점
     * @param detailed  공시 본문을 실을지
     * @return 화면 항목
     */
    public IpoOfferingResponse describe(IpoOffering offering, Long accountId,
                                        GameClockSnapshot clock, boolean detailed) {
        return describe(List.of(offering), accountId, clock, detailed).getFirst();
    }

    private IpoOfferingResponse describe(IpoOffering offering, Instrument instrument,
                                         int subscriberCount, IpoSubscription mine,
                                         Map<Long, Instant> schedule, GameClockSnapshot clock,
                                         boolean detailed, BigDecimal ratio) {
        int expectedWinners = expectedWinnerCount(subscriberCount, ratio);
        return new IpoOfferingResponse(
                offering.getId(),
                offering.getInstrumentId(),
                instrument.getName(),
                offering.statusAt(clock.tickSeq()),
                offering.getCurrency(),
                Amounts.of(offering.getOfferPrice(), instrument.getPriceScale()),
                Amounts.of(offering.getDepositAmount(), offering.getCurrency().scale()),
                instrument.getListedGameDate(),
                schedule.get(offering.getCloseTickSeq()),
                schedule.get(offering.getListingTickSeq()),
                subscriberCount,
                expectedWinners,
                competitionRate(subscriberCount, expectedWinners),
                winProbability(subscriberCount, expectedWinners),
                mine == null ? null
                        : IpoSubscriptionResponse.of(mine, offering.getCurrency(), offering.getDrawnAt()),
                detailed ? instrument.getSummary() : null,
                detailed ? offering.getAllocationRule() : null);
    }

    /**
     * 실시간 경쟁률 ({@code ipo.competition}). 청약자 수 하나에서 세 값이 모두 나오므로 화면
     * 항목 전체를 만들지 않고 그 셋만 뽑는다 — 매 틱 나가는 메시지에 공시 본문과 종목 메타를
     * 실으면 같은 값이 스무 번 반복해서 흐른다.
     *
     * @param offerings 청약 창이 열린 공모들
     * @return 공모마다 하나씩. 입력 순서를 유지한다
     */
    public List<IpoCompetitionResponse> competition(List<IpoOffering> offerings) {
        if (offerings.isEmpty()) {
            return List.of();
        }
        BigDecimal ratio = winnerRatio();
        Map<Long, Integer> counts = subscriberCounts(offerings.stream().map(IpoOffering::getId).toList());
        return offerings.stream().map(offering -> {
            int subscriberCount = counts.getOrDefault(offering.getId(), 0);
            int expectedWinners = expectedWinnerCount(subscriberCount, ratio);
            return new IpoCompetitionResponse(offering.getId(), subscriberCount, expectedWinners,
                    competitionRate(subscriberCount, expectedWinners),
                    winProbability(subscriberCount, expectedWinners));
        }).toList();
    }

    /**
     * 당첨 예정 인원 = 청약자 수의 30%(반올림), <b>최소 1명</b> (기획서 8.4.2).
     *
     * <p>청약자가 없으면 0 이다 — 아무도 없는데 1명을 뽑겠다고 하면 경쟁률이 0 대 1 이 되어
     * 화면이 없는 사람을 센다.
     */
    static int expectedWinnerCount(int subscriberCount, BigDecimal winnerRatio) {
        if (subscriberCount == 0) {
            return 0;
        }
        int expected = BigDecimal.valueOf(subscriberCount).multiply(winnerRatio)
                .divide(PERCENT, 0, RoundingMode.HALF_UP).intValueExact();
        return Math.max(1, expected);
    }

    /** 청약자가 없으면 {@code null} 이다. 0 으로 나누지 않는다. */
    private static BigDecimal competitionRate(int subscriberCount, int expectedWinners) {
        if (subscriberCount == 0) {
            return null;
        }
        return Amounts.ratio(BigDecimal.valueOf(subscriberCount)
                .divide(BigDecimal.valueOf(expectedWinners), Amounts.RATIO_SCALE, RoundingMode.HALF_UP));
    }

    /** 경쟁률의 역수지만 서버가 함께 계산한다. 프론트가 뒤집으면 두 숫자의 반올림이 갈린다. */
    private static BigDecimal winProbability(int subscriberCount, int expectedWinners) {
        if (subscriberCount == 0) {
            return null;
        }
        return Amounts.ratio(BigDecimal.valueOf(expectedWinners).multiply(PERCENT)
                .divide(BigDecimal.valueOf(subscriberCount), Amounts.RATIO_SCALE, RoundingMode.HALF_UP));
    }

    /**
     * 마감·상장 틱이 각각 실제로 몇 시인가.
     *
     * <p><b>오늘 편성만 답이 나온다.</b> 앵커는 진행 중인 일차 하나뿐이라 어제 공모의 마감이
     * 몇 시였는지는 남아 있지 않다 ({@code ReplayService#realTimesAt}). 그 경우 값이 빠지고
     * 응답의 두 시각은 {@code null} 이 된다 — 지난 공모의 화면은 결과를 보여줄 뿐 카운트다운을
     * 하지 않으므로 그것으로 충분하다.
     */
    private Map<Long, Instant> schedule(List<IpoOffering> offerings, GameClockSnapshot clock) {
        Set<Long> tickSeqs = new LinkedHashSet<>();
        for (IpoOffering offering : offerings) {
            tickSeqs.add(offering.getCloseTickSeq());
            tickSeqs.add(offering.getListingTickSeq());
        }
        List<GameTick> ticks = gameTicks.findAll(tickSeqs).stream()
                .filter(tick -> tick.getSeasonDay() == clock.seasonDay())
                .toList();
        Map<Integer, Instant> times = replay.realTimesAt(clock.seasonDay(),
                ticks.stream().map(GameTick::getCumOffsetSec).toList());

        Map<Long, Instant> schedule = new HashMap<>();
        for (GameTick tick : ticks) {
            Instant at = times.get(tick.getCumOffsetSec());
            if (at != null) {
                schedule.put(tick.getTickSeq(), at);
            }
        }
        return schedule;
    }

    private Map<Long, Integer> subscriberCounts(List<Long> offeringIds) {
        Map<Long, Integer> counts = new HashMap<>();
        for (Object[] row : subscriptionRepository.countByOfferingIds(offeringIds)) {
            counts.put((Long) row[0], ((Number) row[1]).intValue());
        }
        return counts;
    }
}

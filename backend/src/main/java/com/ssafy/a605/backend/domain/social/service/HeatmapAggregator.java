package com.ssafy.a605.backend.domain.social.service;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import com.ssafy.a605.backend.domain.account.dto.AccountValuation;
import com.ssafy.a605.backend.domain.account.dto.AccountsValuedEvent;
import com.ssafy.a605.backend.domain.account.entity.Position;
import com.ssafy.a605.backend.domain.account.repository.PositionRepository;
import com.ssafy.a605.backend.domain.market.entity.Instrument;
import com.ssafy.a605.backend.domain.market.repository.InstrumentRepository;
import com.ssafy.a605.backend.domain.market.service.MarketPriceService;
import com.ssafy.a605.backend.domain.market.service.MarketPriceService.AsOfPrice;
import com.ssafy.a605.backend.domain.season.entity.GameTick;
import com.ssafy.a605.backend.domain.season.service.GameTickCatalog;
import com.ssafy.a605.backend.domain.social.dto.HeatmapBucket;
import com.ssafy.a605.backend.domain.social.dto.HeatmapResponse;
import com.ssafy.a605.backend.domain.social.dto.HeatmapSnapshot;
import com.ssafy.a605.backend.global.websocket.RealtimePublisher;
import com.ssafy.a605.backend.global.websocket.WsDestination;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 종가 틱마다 히트맵을 다시 만든다 ({@code soc.md} §갱신 주기는 매 틱이다, 기획서 12.4.3).
 *
 * <p><b>전 계좌를 스스로 순회하지 않는다.</b> 계좌 도메인의 매 틱 스윕이 이미 전 계좌를 한 번 돌고
 * 그 결과를 {@link AccountsValuedEvent} 로 넘긴다 — 랭킹이 같은 이벤트를 듣는 것과 같은 자리다
 * ({@code rank.md} §매 틱 전 계좌 순회). 별도 주기를 두면 순회가 한 벌 더 돌고, 무엇보다 가격은
 * 137번 틱인데 사람들의 현금 비중은 136번인 화면이 생긴다.
 *
 * <p><b>평가액은 스윕이 낸 값을 그대로 쓴다.</b> 총평가액을 다시 계산하지 않는다 — 계좌 화면과
 * 히트맵의 합이 어긋날 길을 만들지 않기 위해서다. 여기서 추가로 읽는 것은 <b>카테고리로 쪼개기
 * 위한 것</b>뿐이며 계좌 수와 무관하게 고정 횟수다: 포지션 한 번, 종목 한 번, 종가 한 번, 틱 한 번.
 *
 * <p>스윕이 건너뛴 계좌(보유 종목의 종가 없음)는 이 집계에도 없다. 그 판단은 스윕의 것이다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HeatmapAggregator {

    static final String TYPE = "social.heatmap";

    private final PositionRepository positionRepository;
    private final InstrumentRepository instrumentRepository;
    private final MarketPriceService marketPrices;
    private final GameTickCatalog gameTicks;
    private final RealtimePublisher publisher;

    private final HeatmapCalculator calculator = new HeatmapCalculator();

    /** 마지막으로 만든 집계. 재시작 직후에는 다음 틱까지 {@code null} 이다. */
    private final AtomicReference<HeatmapSnapshot> current = new AtomicReference<>();

    /**
     * @return 마지막 틱의 집계. 아직 어떤 틱도 순회되지 않았으면 {@code null}
     */
    public HeatmapSnapshot current() {
        return current.get();
    }

    /**
     * 집계를 비운다. 개발·테스트 전용 시즌 초기화만 부른다 — 비우지 않으면 지워진 계좌의
     * 현금 비중이 초기화 뒤에도 계속 응답에 나간다.
     */
    public void clear() {
        current.set(null);
    }

    /**
     * 스윕이 낸 평가로 히트맵을 교체하고 전체에 브로드캐스트한다.
     *
     * <p>4개 버킷이라 페이로드가 작고 전원에게 같은 값이므로 매 틱 밀어도 된다 — 수천 행인
     * 리더보드를 밀지 않는 것과 다른 판단이며, 이유는 데이터 크기다 ({@code soc.md} §실시간).
     *
     * @param event 한 틱의 전 계좌 평가 결과
     */
    @EventListener
    public void on(AccountsValuedEvent event) {
        HeatmapSnapshot snapshot;
        try {
            snapshot = aggregate(event);
        } catch (RuntimeException e) {
            // 집계는 직전 틱 것으로 남는다. 폴러로 던지면 재생이 멈춘 것처럼 기록된다.
            log.error("Heatmap aggregation failed for tick {}; previous snapshot retained", event.tickSeq(), e);
            return;
        }
        current.set(snapshot);
        try {
            publisher.broadcast(WsDestination.EVENT, TYPE, snapshot.tickSeq(), snapshot.gameDate(),
                    HeatmapResponse.from(snapshot));
        } catch (RuntimeException e) {
            log.error("Failed to broadcast {} at tick {}", TYPE, snapshot.tickSeq(), e);
        }
    }

    private HeatmapSnapshot aggregate(AccountsValuedEvent event) {
        BigDecimal total = event.valuations().stream()
                .map(AccountValuation::totalValuationKrw)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return calculator.aggregate(event.tickSeq(), event.gameDate(), total,
                holdingsByCategory(event), current.get());
    }

    /** 순위에 든 계좌의 보유 자산을 카테고리로 합산한다. */
    private Map<HeatmapBucket, BigDecimal> holdingsByCategory(AccountsValuedEvent event) {
        List<Long> accountIds = event.valuations().stream().map(AccountValuation::accountId).toList();
        if (accountIds.isEmpty()) {
            return Map.of();
        }
        List<Position> positions = positionRepository.findByAccountIdInAndQuantityGreaterThan(accountIds, 0);
        if (positions.isEmpty()) {
            return Map.of();
        }

        List<Long> instrumentIds = positions.stream().map(Position::getInstrumentId).distinct().toList();
        Map<Long, HeatmapBucket> buckets = instrumentRepository.findAllById(instrumentIds).stream()
                .collect(Collectors.toMap(Instrument::getId, HeatmapAggregator::bucketOf));
        Map<Long, AsOfPrice> prices = marketPrices.pricesAsOf(instrumentIds, event.gameDate());
        BigDecimal rate = rateOf(event.tickSeq());

        Map<HeatmapBucket, BigDecimal> sums = new EnumMap<>(HeatmapBucket.class);
        for (Position position : positions) {
            AsOfPrice price = prices.get(position.getInstrumentId());
            HeatmapBucket bucket = buckets.get(position.getInstrumentId());
            if (price == null || bucket == null) {
                // 스윕이 평가한 계좌의 포지션이라 여기까지 오면 안 된다. 한 종목 때문에 화면을 비우지 않는다.
                log.error("Heatmap skipped instrument {} at tick {}: price or category missing",
                        position.getInstrumentId(), event.tickSeq());
                continue;
            }
            // 환산 규칙은 AsOfPrice 가 갖는다 — 수량을 곱하는 것만 여기서 한다.
            BigDecimal value = price.closeKrw(rate).multiply(BigDecimal.valueOf(position.getQuantity()));
            sums.merge(bucket, value, BigDecimal::add);
        }
        return sums;
    }

    /** 그 틱의 원/달러 매매기준율. 스윕이 쓴 것과 같은 값이어야 한다. */
    private BigDecimal rateOf(long tickSeq) {
        return gameTicks.find(tickSeq).map(GameTick::getUsdKrw)
                .orElseThrow(() -> new IllegalStateException("공개된 틱 " + tickSeq + " 이 game_ticks 에 없다"));
    }

    private static HeatmapBucket bucketOf(Instrument instrument) {
        return switch (instrument.getCategory()) {
            case NASDAQ -> HeatmapBucket.NASDAQ;
            case KRX -> HeatmapBucket.KRX;
            case CRYPTO -> HeatmapBucket.CRYPTO;
        };
    }
}

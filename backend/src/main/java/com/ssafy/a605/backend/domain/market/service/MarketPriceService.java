package com.ssafy.a605.backend.domain.market.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ssafy.a605.backend.domain.market.dto.MarketPricesResponse;
import com.ssafy.a605.backend.domain.market.entity.Currency;
import com.ssafy.a605.backend.domain.market.entity.DailyClose;
import com.ssafy.a605.backend.domain.market.entity.Instrument;
import com.ssafy.a605.backend.domain.market.repository.DailyCloseRepository;
import com.ssafy.a605.backend.domain.market.repository.InstrumentRepository;
import com.ssafy.a605.backend.domain.market.repository.MarketPriceQueryRepository;
import com.ssafy.a605.backend.domain.market.repository.MarketPriceQueryRepository.CloseRow;
import com.ssafy.a605.backend.domain.order.repository.ExecutionRepository;
import com.ssafy.a605.backend.domain.order.repository.ExecutionRepository.InstrumentAmount;
import com.ssafy.a605.backend.domain.season.entity.GameTick;
import com.ssafy.a605.backend.domain.season.service.GameTickCatalog;

import lombok.RequiredArgsConstructor;

/**
 * 공개 기준 거래일의 종목 가격. 평가액을 만드는 다른 도메인(계좌·주문·랭킹)이 이 서비스를
 * 거쳐 시장 데이터를 읽는다 — 도메인 간 협력은 Service 호출로 한다
 * ({@code architecture/overview.md} §3).
 *
 * <p>휴장 이월 규칙이 여기 있다. 재생 축이 한국·미국 거래일의 합집합이라 어느 한쪽만 휴장인
 * 날이 생기고, 그날 휴장 시장의 종목은 새 종가가 없어 <b>직전 종가를 그대로 유지</b>한다
 * ({@code mkt.md} §휴장 종목의 표현). 규칙이 이 도메인에 있어야 그것을 쓰는 모든 곳이 같은
 * 값을 본다.
 *
 * <p><b>이월 규칙의 세 호출자가 모두 이 서비스를 거친다</b> — 평가액을 만드는 도메인, 매 틱의
 * 실시간 브로드캐스트, 현재가 REST 조회. 브로드캐스트가 재생 도메인 안에서 같은 규칙을 따로
 * 풀고 있던 것을 {@link #snapshotAsOf} 로 옮긴 이유가 이것이다: 규칙이 두 도메인에 흩어져
 * 있으면 한쪽만 고치는 사고가 난다 (실제로 상장 필터와 직전 종가 자릿수가 그렇게 어긋났다).
 *
 * <p><b>마지막 스냅샷을 프로세스 안에 들고 있다.</b> 유니버스 가격 응답은 사용자 의존 필드가 없어
 * 같은 틱이면 전원에게 같다. 브로드캐스트가 매 틱 그것을 만드는데 REST 초기 로드가 요청마다 같은
 * 질의를 다시 돌리는 것은 이미 한 계산을 버리는 일이다 — 랭킹·히트맵이 스냅샷을 두는 것과 같은
 * 자리다 (S15P21A605-172). 재사용 여부는 <b>틱 순번 비교</b>로 정한다: 시계가 말하는 틱과 같을
 * 때만 쓰고 다르면 다시 만든다. 그래서 캐시 때문에 옛 값이 나가는 경로가 없다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MarketPriceService {

    @org.springframework.beans.factory.annotation.Value("${app.replay.season-id:1}")
    private long seasonId;

    private final InstrumentRepository instrumentRepository;
    private final DailyCloseRepository dailyCloseRepository;
    private final MarketPriceQueryRepository priceQueryRepository;
    private final ExecutionRepository executionRepository;
    private final GameTickCatalog gameTicks;

    /**
     * 마지막으로 만든 유니버스 스냅샷. 재시작 직후 첫 틱 전에는 {@code null} 이고, 시즌 초기화가
     * 비운다 — 초기화 뒤 틱 순번이 다시 작아지므로 옛 스냅샷이 우연히 같은 순번에 맞을 수 있다.
     */
    private final AtomicReference<MarketPricesResponse> latest = new AtomicReference<>();

    /**
     * 공개 기준 틱의 유니버스 가격 스냅샷.
     *
     * <p><b>REST 조회와 실시간 브로드캐스트가 이 메서드를 함께 쓴다.</b> 사양이 두 경로를
     * 동일 형태로 계약했으므로 한 타입을 한 자리에서 만든다 — 형태가 갈릴 여지를 남기지 않는다.
     * 절단 기준을 인자로 받는 이유는 <b>어느 틱까지 공개됐는지가 재생 상태의 값</b>이기
     * 때문이다. 여기서 스스로 판정하면 브로드캐스트한 가격과 REST 가격이 서로 다른 틱을 본다.
     *
     * @param asOfTickSeq  공개 기준 틱 순번
     * @param asOfGameDate 그 틱의 게임 거래일
     * @param usdKrw       그 거래일의 원/달러 매매기준율
     * @return 그 거래일에 상장된 종목의 종가·직전 종가·원화 환산·등락률·휴장 여부
     */
    public MarketPricesResponse snapshotAsOf(long asOfTickSeq, LocalDate asOfGameDate, BigDecimal usdKrw) {
        MarketPricesResponse snapshot = MarketPricesResponse.of(asOfTickSeq, asOfGameDate, usdKrw,
                priceQueryRepository.findPricesAsOf(seasonId, asOfGameDate),
                tradedAmounts(asOfTickSeq));
        // 더 이른 틱의 뒤늦은 계산이 새 틱의 스냅샷을 덮지 않게 한다 — REST 가 틱 N 을 만드는 사이에
        // 브로드캐스트가 N+1 을 넣었을 수 있다.
        latest.accumulateAndGet(snapshot,
                (current, fresh) -> current == null || fresh.asOfTickSeq() >= current.asOfTickSeq() ? fresh : current);
        return snapshot;
    }

    /**
     * 그 일차에 참가자들이 종목별로 얼마를 주고받았는가 ({@code S15P21A605-253}).
     *
     * <p><b>일차의 시작을 편성에서 얻는다.</b> 체결은 {@code season_day} 를 갖고 있지 않으므로
     * 일차를 틱 구간으로 바꿔 묻는다 — 그러면 {@code game_ticks} 조인 없이 범위 조건이 된다.
     * 편성은 시즌 중 불변이라 이 두 번의 참조는 맵 조회다 ({@code S15P21A605-172}).
     *
     * <p><b>공개 기준 틱까지만 센다.</b> 가격과 같은 절단 기준이라야 한 응답 안의 두 숫자가 같은
     * 시점을 말한다.
     *
     * <p>편성에 없는 틱이면 빈 값이다 — 호출 측이 이미 편성에서 얻은 틱을 넘기므로 일어나지
     * 않지만, 그 경우 대금이 없는 것이 맞다 (0 으로 내려간다).
     */
    private Map<Long, BigDecimal> tradedAmounts(long asOfTickSeq) {
        GameTick tick = gameTicks.find(asOfTickSeq).orElse(null);
        if (tick == null) {
            return Map.of();
        }
        GameTick first = gameTicks.firstOf(tick.getSeasonDay()).orElse(null);
        if (first == null) {
            return Map.of();
        }
        return executionRepository
                .sumAmountByInstrumentBetweenTicks(first.getTickSeq(), asOfTickSeq, Currency.USD).stream()
                .collect(Collectors.toMap(InstrumentAmount::instrumentId, InstrumentAmount::amountKrw));
    }

    /**
     * 그 틱의 스냅샷이 이미 만들어져 있으면 그것. 브로드캐스트가 매 틱 만들어 두므로 REST 초기
     * 로드는 대부분 여기서 끝난다.
     *
     * @param asOfTickSeq 시계가 말하는 공개 기준 틱
     * @return 같은 틱의 스냅샷. 없거나 다른 틱이면 빈 값 — 호출 측이 {@link #snapshotAsOf} 로 만든다
     */
    public Optional<MarketPricesResponse> snapshotIfCurrent(long asOfTickSeq) {
        MarketPricesResponse snapshot = latest.get();
        return snapshot != null && snapshot.asOfTickSeq() == asOfTickSeq ? Optional.of(snapshot) : Optional.empty();
    }

    /** 스냅샷을 비운다. 개발·테스트 전용 시즌 초기화만 부른다 — 히트맵의 {@code clear()} 와 같은 자리다. */
    public void clearSnapshot() {
        latest.set(null);
    }

    /**
     * 그 거래일 기준의 종목별 결제 통화와 종가.
     *
     * <p><b>종가가 없는 종목은 결과에서 빠진다.</b> 빈 값을 0 으로 채우지 않는 이유는 그것이
     * 호출 측의 판단이기 때문이다 — 평가액을 만드는 쪽은 드러내야 하고, 목록을 그리는 쪽은
     * 건너뛸 수 있다.
     *
     * @param instrumentIds 대상 종목
     * @param asOfGameDate  공개 기준 게임 거래일
     * @return 종목 식별자별 가격. 그 날짜 이하에 종가가 없는 종목은 담기지 않는다
     */
    public Map<Long, AsOfPrice> pricesAsOf(Collection<Long> instrumentIds, LocalDate asOfGameDate) {
        if (instrumentIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Instrument> instruments = instrumentRepository.findAllById(instrumentIds).stream()
                .collect(Collectors.toMap(Instrument::getId, Function.identity()));
        Map<Long, AsOfPrice> prices = new HashMap<>();
        for (CloseRow close : priceQueryRepository.findLatestClosesAsOf(instrumentIds, asOfGameDate)) {
            Instrument instrument = instruments.get(close.instrumentId());
            prices.put(close.instrumentId(),
                    new AsOfPrice(instrument.getCurrency(), instrument.getPriceScale(),
                            close.close(), close.closeDate()));
        }
        return prices;
    }

    /**
     * <b>그 거래일에 새로 도착한</b> 종가. 이월하지 않는다.
     *
     * <p>{@link #pricesAsOf} 와 정반대의 요구라 따로 둔다. 평가액은 휴장 종목의 값이 0 이 되면
     * 안 되므로 직전 종가를 이월하지만, <b>체결 판정은 이월해서는 안 된다</b> — 기획서 6.4.5 가
     * "휴장 종목은 그날 판정되지 않는다"이고 그 이유가 "직전 종가 유지는 체결 사유가
     * 아니다"이기 때문이다. 이월하면 어제 이미 판정한 종가로 오늘 또 체결시킨다.
     *
     * <p>그래서 <b>빠진 종목은 그날 휴장</b>이라는 뜻이고, 호출 측은 그 종목의 예약을 그날
     * 건드리지 않는다. 결제 통화·자릿수를 함께 싣지 않는 것은 예약이 그 둘을 등록 시점에
     * 복사해 두었기 때문이다 ({@code orders.currency}).
     *
     * @param instrumentIds 대상 종목
     * @param gameDate      판정할 게임 거래일
     * @return 종목 식별자별 그날의 종가. 그날 종가가 없는 종목은 담기지 않는다
     */
    public Map<Long, BigDecimal> closesOn(Collection<Long> instrumentIds, LocalDate gameDate) {
        if (instrumentIds.isEmpty()) {
            return Map.of();
        }
        return dailyCloseRepository.findByInstrumentIdInAndGameDate(instrumentIds, gameDate).stream()
                .collect(Collectors.toMap(DailyClose::getInstrumentId, DailyClose::getClosePrice));
    }

    /**
     * 종목별 가격 표현 자릿수.
     *
     * <p>가격이 아니라 <b>메타만</b> 필요한 경로가 있다. 체결 내역은 체결가를 이미 행에 갖고
     * 있고 필요한 것은 그것을 몇 자리로 찍을지뿐이다 — 종가를 다시 읽을 이유가 없다
     * ({@code _common.md} §6.2: 전부 2자리로 통일하면 MATIC 이 {@code "0.50"} 이 된다).
     *
     * @param instrumentIds 대상 종목
     * @return 종목 식별자별 표현 자릿수. 유니버스에 없는 종목은 담기지 않는다
     */
    public Map<Long, Short> priceScales(Collection<Long> instrumentIds) {
        if (instrumentIds.isEmpty()) {
            return Map.of();
        }
        return instrumentRepository.findAllById(instrumentIds).stream()
                .collect(Collectors.toMap(Instrument::getId, Instrument::getPriceScale));
    }

    /**
     * 한 종목이 그 거래일에 거래 가능한지 판정하는 데 필요한 메타와 공개 기준 종가.
     *
     * <p>{@link #pricesAsOf} 와 나눠 둔 이유는 묻는 질문이 다르기 때문이다. 저쪽은 "가진 것을
     * 얼마로 평가하나"라 이미 보유한 종목만 보고 상장 여부를 물을 일이 없지만, 주문은 <b>아직
     * 갖지 않은 종목</b>을 대상으로 하므로 상장일과 그날 휴장인지를 먼저 알아야 한다.
     *
     * <p><b>시즌 소속을 여기서 본다.</b> 주문·예약 주문·주문 미리보기가 전부 이 한 경로로
     * 종목을 얻으므로, 다른 시즌 종목의 식별자를 거절하는 자리도 하나면 된다. 호출 측이 각자
     * 검증하면 세 경로 중 하나가 빠지는 날 그 종목만 거래된다.
     *
     * <p>지수 대리 종목도 여기서 빠진다 — 거래 대상이 아니다.
     *
     * @param instrumentId 대상 종목
     * @param asOfGameDate 공개 기준 게임 거래일
     * @return 종목 메타와 그 날짜 이하의 마지막 종가. 이 시즌의 거래 대상이 아니면 빈 값
     */
    public Optional<AsOfInstrument> instrumentAsOf(Long instrumentId, LocalDate asOfGameDate) {
        return instrumentRepository.findByIdAndSeasonId(instrumentId, seasonId)
                .filter(found -> found.getAssetType().isTradable())
                .map(instrument -> {
                    CloseRow close = priceQueryRepository
                            .findLatestClosesAsOf(List.of(instrumentId), asOfGameDate).stream()
                            .findFirst()
                            .orElse(null);
                    return new AsOfInstrument(instrument.getId(), instrument.getCurrency(),
                            instrument.getPriceScale(), instrument.getListedGameDate(),
                            close == null ? null : close.close(),
                            close == null ? null : close.closeDate());
                });
    }

    /**
     * 거래 판정에 필요한 종목 메타와 공개 기준 종가.
     *
     * @param instrumentId   종목 식별자
     * @param currency       결제 통화. 나스닥·코인 {@code USD}, 국내 {@code KRW}
     * @param priceScale     가격의 표현 자릿수
     * @param listedGameDate IPO 종목의 상장 게임 거래일. 상시 종목은 {@code null}
     * @param close          그 거래일 이하의 마지막 종가. 시세가 하나도 없으면 {@code null}
     * @param closeDate      그 종가의 실제 거래일. 기준일보다 이르면 그날 휴장이다
     */
    public record AsOfInstrument(Long instrumentId, Currency currency, short priceScale,
                                 LocalDate listedGameDate, BigDecimal close, LocalDate closeDate) {

        /**
         * 상장일부터 거래할 수 있다 (기획서 5.2.2). 상시 종목은 상장일이 없어 언제나 참이다.
         *
         * @param gameDate 판정할 게임 거래일
         * @return 그날 거래 가능한 상태인가
         */
        public boolean isListedOn(LocalDate gameDate) {
            return Instrument.isListedOn(listedGameDate, gameDate);
        }

        /**
         * 그날 새 종가가 없어 직전 종가가 이월된 상태 = 그 시장이 휴장이다
         * ({@code mkt.md} §휴장 종목의 표현).
         *
         * @param gameDate 판정할 게임 거래일. 종가가 있어야 한다
         * @return 그날 휴장인가
         */
        public boolean isMarketClosedOn(LocalDate gameDate) {
            return closeDate.isBefore(gameDate);
        }
    }

    /**
     * 한 종목의 공개 기준 가격과 그것을 표현하는 데 필요한 종목 메타.
     *
     * @param currency   결제 통화. 원화 환산이 필요한지를 이 값이 정한다
     * @param priceScale 가격의 표현 자릿수. 종목마다 다르다 — 전부 2자리로 통일하면 MATIC 이
     *                   {@code "0.50"} 이 되어 등락이 안 보인다 ({@code _common.md} §6.2)
     * @param close      그 거래일 이하의 마지막 종가 (현지 통화, 분할 조정된 값)
     * @param closeDate  그 종가의 실제 거래일. 휴장 이월이면 기준일보다 이르다
     */
    public record AsOfPrice(Currency currency, short priceScale, BigDecimal close, LocalDate closeDate) {

        /**
         * @param rate 그 거래일의 원/달러 매매기준율
         * @return 원화 환산 종가. 원화 종목은 종가 그대로다
         */
        public BigDecimal closeKrw(BigDecimal rate) {
            return currency == Currency.USD ? close.multiply(rate) : close;
        }
    }
}

package com.ssafy.a605.backend.domain.market.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.ssafy.a605.backend.domain.ipo.entity.IpoOffering;
import com.ssafy.a605.backend.domain.ipo.repository.IpoOfferingRepository;
import com.ssafy.a605.backend.domain.market.dto.InstrumentDetailResponse;
import com.ssafy.a605.backend.domain.market.dto.InstrumentInfoResponse;
import com.ssafy.a605.backend.domain.market.dto.InstrumentInfoResponse.ProductCard;
import com.ssafy.a605.backend.domain.market.dto.InstrumentInfoResponse.ProjectCard;
import com.ssafy.a605.backend.domain.market.dto.InstrumentListResponse;
import com.ssafy.a605.backend.domain.market.dto.SignalCriteriaResponse;
import com.ssafy.a605.backend.domain.market.entity.Instrument;
import com.ssafy.a605.backend.domain.market.entity.InstrumentCategory;
import com.ssafy.a605.backend.domain.market.entity.ListingStatus;
import com.ssafy.a605.backend.domain.market.entity.SignalCriterion;
import com.ssafy.a605.backend.domain.market.entity.Watchlist;
import com.ssafy.a605.backend.domain.market.repository.InstrumentMetricRepository;
import com.ssafy.a605.backend.domain.market.repository.InstrumentRepository;
import com.ssafy.a605.backend.domain.market.repository.SignalCriterionRepository;
import com.ssafy.a605.backend.domain.market.repository.WatchlistRepository;
import com.ssafy.a605.backend.domain.replay.dto.GameClockSnapshot;
import com.ssafy.a605.backend.domain.replay.service.ReplayService;
import com.ssafy.a605.backend.global.exception.BusinessException;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.ObjectMapper;

/**
 * 거래 유니버스와 종목 정보 카드 ({@code unv.md} · {@code info.md}). 시세는 여기 없다 —
 * 그쪽은 {@link MarketQueryService} 다.
 *
 * <p><b>절단 기준을 스스로 정하지 않는다.</b> "지금 어느 틱까지 공개됐는가"는 재생 상태가 갖는
 * 값이고 {@link ReplayService#clock()} 이 그 단일 진실이다 — 여기서 게임 틱을 현재 시각과
 * 비교해 직접 구하면 브로드캐스트한 가격과 이 응답이 서로 다른 틱을 본다.
 *
 * <p><b>상장 판정 규칙을 다시 구현하지 않는다.</b> {@link Instrument#isListedOn(LocalDate)} 이
 * 그 규칙이고 시세 조회도 같은 것을 쓴다.
 *
 * <p><b>{@code REPEATABLE_READ} 로 읽는다 — 근거가 {@link MarketQueryService} 와 다르다.</b>
 * 저쪽은 변하는 것을 시계 하나만 읽어 {@code S15P21A605-205} 에서 격리를 내렸지만, 여기는
 * <b>시계와 함께 {@code ipo_offerings} 를 읽는다.</b> 공모 상태는 틱 경계마다 공시·추첨·상장으로
 * 바뀌므로, 두 읽기 사이에 틱이 넘어가면 응답이 말하는 틱과 실제로 담긴 공모 상태가 어긋난다
 * ({@code NewsService} 와 같은 규율).
 */
@Slf4j
@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class InstrumentQueryService {

    private final InstrumentRepository instrumentRepository;
    private final WatchlistRepository watchlistRepository;
    private final IpoOfferingRepository ipoOfferingRepository;
    private final InstrumentMetricRepository instrumentMetricRepository;
    private final SignalCriterionRepository signalCriterionRepository;
    private final ReplayService replay;
    private final ObjectMapper objectMapper;
    private final long seasonId;

    public InstrumentQueryService(InstrumentRepository instrumentRepository,
                                  WatchlistRepository watchlistRepository,
                                  IpoOfferingRepository ipoOfferingRepository,
                                  InstrumentMetricRepository instrumentMetricRepository,
                                  SignalCriterionRepository signalCriterionRepository,
                                  ReplayService replay, ObjectMapper objectMapper,
                                  @Value("${app.replay.season-id:1}") long seasonId) {
        this.instrumentRepository = instrumentRepository;
        this.watchlistRepository = watchlistRepository;
        this.ipoOfferingRepository = ipoOfferingRepository;
        this.instrumentMetricRepository = instrumentMetricRepository;
        this.signalCriterionRepository = signalCriterionRepository;
        this.replay = replay;
        this.objectMapper = objectMapper;
        this.seasonId = seasonId;
    }

    /**
     * 그 시점에 노출되는 유니버스 전체.
     *
     * @param userId   요청자. 관심 여부를 이 사람 기준으로 채운다. 미인증이면 {@code null} 이고
     *                 관심 종목이 없는 것으로 본다
     * @param category 탭 구분. {@code null} 이면 전체
     * @return 절단 기준 거래일과 종목 메타. 공시 전 공모 종목은 담기지 않는다
     * @throws BusinessException 재생 상태가 초기화되지 않았을 때 (409, 재생 도메인이 판정한다)
     */
    public InstrumentListResponse list(Long userId, InstrumentCategory category) {
        AsOf asOf = asOf();
        return listAt(asOf, watchedIds(userId), category);
    }

    /**
     * 모든 사용자에게 공유할 유니버스다. 개인 관심 여부는 포함하지 않는다.
     * @param tickSeq 공개 틱
     * @param gameDate 공개 거래일
     * @return 공개된 종목 목록
     */
    public InstrumentListResponse publicListAt(long tickSeq, LocalDate gameDate) {
        return listAt(new AsOf(gameDate, tickSeq), Set.of(), null);
    }

    private InstrumentListResponse listAt(AsOf asOf, Set<Long> watched, InstrumentCategory category) {
        Map<Long, IpoOffering> offerings = offerings();

        List<InstrumentListResponse.Item> items = new ArrayList<>();
        for (Instrument instrument : instrumentRepository.findBySeasonIdOrderByIdAsc(seasonId)) {
            // 지수 대리 종목은 목록에 나오지 않는다. 기준선의 원값일 뿐 거래 대상이 아니다.
            if (!instrument.getAssetType().isTradable()) {
                continue;
            }
            if (category != null && instrument.getCategory() != category) {
                continue;
            }
            ListingStatus status = listingStatus(instrument, asOf, offerings);
            if (status == null) {
                continue;
            }
            items.add(InstrumentListResponse.Item.of(instrument, status, asOf.gameDate(),
                    watched.contains(instrument.getId())));
        }
        return new InstrumentListResponse(asOf.gameDate(), items);
    }

    /**
     * 한 종목의 메타 상세.
     *
     * @param userId       요청자. 관심 여부를 이 사람 기준으로 채운다
     * @param instrumentId 대상 종목
     * @return 목록 항목의 모든 필드 + 사업 개요
     * @throws BusinessException 그 시점에 노출되지 않는 종목이면 (404),
     *                           재생 상태가 초기화되지 않았을 때 (409)
     */
    public InstrumentDetailResponse detail(Long userId, Long instrumentId) {
        AsOf asOf = asOf();
        Visible visible = visible(instrumentId, asOf);
        return InstrumentDetailResponse.of(visible.instrument(), visible.status(), asOf.gameDate(),
                watchedIds(userId).contains(instrumentId));
    }

    /**
     * 한 종목의 정보 카드. 자산 유형이 어느 카드를 채울지 결정한다.
     *
     * @param instrumentId 대상 종목
     * @return 카드 종류와 그 카드. 나머지 두 카드 필드는 {@code null}
     * @throws BusinessException 그 시점에 노출되지 않는 종목이면 (404),
     *                           재생 상태가 초기화되지 않았을 때 (409)
     */
    public InstrumentInfoResponse info(Long instrumentId) {
        AsOf asOf = asOf();
        Instrument instrument = visible(instrumentId, asOf).instrument();
        return switch (instrument.getAssetType().cardType()) {
            case STOCK -> InstrumentInfoResponse.stock(instrument,
                    instrumentMetricRepository.findByInstrumentIdAndDisclosedGameDateLessThanEqual(
                            instrumentId, asOf.cutoff()),
                    criteria());
            case PRODUCT -> InstrumentInfoResponse.product(instrument, card(instrument, ProductCard.class));
            case PROJECT -> InstrumentInfoResponse.project(instrument, card(instrument, ProjectCard.class));
        };
    }

    /**
     * 신호등 판정 기준. 종목과 무관하고 재생 시각도 보지 않는다.
     *
     * @return 세 질문의 기준 3단계
     */
    public SignalCriteriaResponse signalCriteria() {
        return SignalCriteriaResponse.from(criteria());
    }

    /**
     * 그 시점에 노출되는 종목과 그 노출 상태.
     *
     * <p><b>노출되지 않는 종목은 없는 것과 같게 404 다.</b> 목록에서만 숨기면 식별자를 훑어
     * 공시 전 공모 종목의 이름을 읽을 수 있고, 종목명 자체가 미래 정보다 (기획서 3.5.2).
     */
    private Visible visible(Long instrumentId, AsOf asOf) {
        // 다른 시즌의 종목 식별자와 지수 대리 종목은 여기서 404 가 된다 — 목록에 없는 것이
        // 상세로는 열리는 구멍을 두지 않는다.
        Instrument instrument = instrumentRepository.findByIdAndSeasonId(instrumentId, seasonId)
                .filter(found -> found.getAssetType().isTradable())
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "종목이 없습니다."));
        ListingStatus status = listingStatus(instrument, asOf, offerings());
        if (status == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "종목이 없습니다.");
        }
        return new Visible(instrument, status);
    }

    /**
     * 노출 상태. {@code null} 이면 그 시점에 응답에 담지 않는다.
     *
     * <p><b>공모 편성이 없으면 상장 전 공모 종목을 전부 숨긴다.</b> 공시 시점을 알 수 없으니
     * {@code UPCOMING} 을 판정할 수 없고, 전부 숨기는 것은 미래 차단 방향으로 더 엄격하므로
     * 누출이 생기지 않는다. 편성이 들어오면 같은 코드가 공시 전만 숨긴다 — 분기 플래그를 두지
     * 않은 이유가 이것이다.
     */
    private ListingStatus listingStatus(Instrument instrument, AsOf asOf,
                                        Map<Long, IpoOffering> offerings) {
        if (instrument.isListedOn(asOf.cutoff())) {
            return ListingStatus.LISTED;
        }
        IpoOffering offering = offerings.get(instrument.getId());
        if (offering == null || asOf.tickSeq() < offering.getDisclosureTickSeq()) {
            return null;
        }
        return ListingStatus.UPCOMING;
    }

    /**
     * {@code instruments.info_card} 를 카드 타입으로 읽는다.
     *
     * <p>카드가 아직 없으면 {@code null} 이다 — 카드 종류는 자산 유형이 정하므로 결정되어
     * 있고 내용만 없는 상태이며, 주식 카드가 지표 없이 나가는 것과 같은 형태다. 다만 8종은
     * 마이그레이션이 채워야 하는 값이라 로그를 남긴다.
     */
    private <T> T card(Instrument instrument, Class<T> type) {
        if (instrument.getInfoCard() == null) {
            log.warn("Instrument {} has asset type {} but no info_card", instrument.getId(),
                    instrument.getAssetType());
            return null;
        }
        return objectMapper.readValue(instrument.getInfoCard(), type);
    }

    /** 공개 기준 틱과 그 거래일. 시계가 이미 둘을 갖고 있어 게임 틱을 다시 읽지 않는다. */
    private AsOf asOf() {
        GameClockSnapshot clock = replay.clock();
        return new AsOf(clock.gameDate(), clock.tickSeq() == null ? 0L : clock.tickSeq());
    }

    private Map<Long, IpoOffering> offerings() {
        return ipoOfferingRepository.findBySeasonId(seasonId).stream()
                .collect(Collectors.toMap(IpoOffering::getInstrumentId, Function.identity()));
    }

    /** 미인증이면 관심 종목이 없다. 조회하지 않는 이유는 {@code user_id = null} 이 아무것도 맞히지 못해서다. */
    private Set<Long> watchedIds(Long userId) {
        if (userId == null) {
            return Set.of();
        }
        return watchlistRepository.findBySeason(userId, seasonId).stream()
                .map(Watchlist::getInstrumentId)
                .collect(Collectors.toCollection(HashSet::new));
    }

    private List<SignalCriterion> criteria() {
        return signalCriterionRepository.findAllByOrderBySortOrderAsc();
    }

    /**
     * 공개 기준 시점.
     *
     * @param gameDate 공개 기준 게임 거래일. 최초 공개 전에는 {@code null}
     * @param tickSeq  공개 기준 틱 순번. 최초 공개 전에는 0
     */
    private record AsOf(LocalDate gameDate, long tickSeq) {

        /**
         * 상장·공시 판정에 쓸 거래일. <b>최초 공개 전에는 어떤 상장일보다도 이른 날짜다</b> —
         * 아직 아무 틱도 공개되지 않았으므로 공모 종목은 하나도 상장·공시되지 않았고, 그것을
         * 분기 없이 표현하는 값이 {@link LocalDate#MIN} 이다.
         */
        LocalDate cutoff() {
            return gameDate == null ? LocalDate.MIN : gameDate;
        }
    }

    private record Visible(Instrument instrument, ListingStatus status) {
    }
}

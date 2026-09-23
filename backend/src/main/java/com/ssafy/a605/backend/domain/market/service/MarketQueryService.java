package com.ssafy.a605.backend.domain.market.service;

import java.time.LocalDate;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ssafy.a605.backend.domain.market.dto.InstrumentClosesResponse;
import com.ssafy.a605.backend.domain.market.dto.MarketPricesResponse;
import com.ssafy.a605.backend.domain.market.entity.Instrument;
import com.ssafy.a605.backend.domain.market.repository.DailyCloseRepository;
import com.ssafy.a605.backend.domain.market.repository.InstrumentRepository;
import com.ssafy.a605.backend.domain.replay.dto.GameClockSnapshot;
import com.ssafy.a605.backend.domain.replay.service.ReplayService;
import com.ssafy.a605.backend.domain.season.entity.GameTick;
import com.ssafy.a605.backend.domain.season.service.GameTickCatalog;
import com.ssafy.a605.backend.global.exception.BusinessException;

import lombok.extern.slf4j.Slf4j;

/**
 * 시세 조회 ({@code mkt.md}). 현재가 스냅샷과 종가 시계열이다.
 *
 * <p><b>절단 기준을 스스로 정하지 않는다.</b> "지금 어느 틱까지 공개됐는가"는 재생 상태가
 * 갖는 값이고 {@link ReplayService#clock()} 이 그 단일 진실이다. 여기서 게임 틱 테이블을 현재
 * 시각과 비교해 직접 구하면, 브로드캐스트된 {@code market.prices} 와 REST 응답이 서로 다른
 * 틱을 보게 된다 ({@code _common.md} §10 이 막으려는 화면 불일치다).
 *
 * <p>DB 에는 미래 데이터가 이미 다 들어 있다 — 28종 × 약 1,700거래일이 시즌 시작 전에 전부
 * 적재되어 있다. 절단은 조회 계층의 책임이며 응답에 그 기준을 함께 싣는다 ({@code _common.md}
 * §9). 두 필드는 미래 차단이 아니라 동기화 요구에서 나온다 — 클라이언트가 그보다 큰 틱만
 * 이어붙여 중복을 무시하고 누락을 감지한다.
 *
 * <p>시즌은 재생과 같은 설정값에서 읽는다 — 재생이 진행하는 시즌과 조회 대상 시즌이 어긋나면
 * 환율·종가와 절단 기준이 다른 시즌 것이 된다.
 *
 * <p><b>격리 수준을 올리지 않는다</b> ({@code S15P21A605-205}). 예전에는 {@code REPEATABLE_READ}
 * 였고 근거는 "시계가 요청한 격리가 참여 트랜잭션에서 조용히 버려지므로 같은 수준을 선언해
 * 이어받는다" 였다. 시계가 {@code S15P21A605-247} 이후 {@code replay_state} 한 행만 읽으므로
 * 그 근거가 통째로 사라졌다.
 *
 * <p><b>이 서비스 자신에게도 필요하지 않다.</b> 한 요청에서 <b>변하는 것을 시계 한 번</b>만
 * 읽고, 나머지 {@code daily_closes} · {@code instruments} · 틱 편성은 <b>시즌 시작 전에 적재되어
 * 런타임에 한 행도 쓰이지 않는다</b> (저장·삭제 호출이 없고 엔티티에 변경 메서드가 없다).
 * 불변인 표는 어느 스냅샷에서 읽어도 같은 답이므로 {@code REPEATABLE_READ} 가 보장할 것이 없다.
 *
 * <p>비용은 격리를 올릴 때마다 붙는 왕복 셋이다 — {@code SET SESSION CHARACTERISTICS} ·
 * 확인용 {@code SHOW} · 끝에 원복 {@code SET}. 데이터를 1 바이트도 읽지 않는 왕복이다.
 */
@Slf4j
@Service
@Transactional(readOnly = true)
public class MarketQueryService {

    private final InstrumentRepository instrumentRepository;
    private final DailyCloseRepository dailyCloseRepository;
    private final GameTickCatalog gameTicks;
    private final MarketPriceService marketPrices;
    private final ReplayService replay;
    private final long seasonId;

    public MarketQueryService(InstrumentRepository instrumentRepository,
                              DailyCloseRepository dailyCloseRepository,
                              GameTickCatalog gameTicks,
                              MarketPriceService marketPrices, ReplayService replay,
                              @Value("${app.replay.season-id:1}") long seasonId) {
        this.instrumentRepository = instrumentRepository;
        this.dailyCloseRepository = dailyCloseRepository;
        this.gameTicks = gameTicks;
        this.marketPrices = marketPrices;
        this.replay = replay;
        this.seasonId = seasonId;
    }

    /**
     * 공개 기준 틱의 현재가 스냅샷. 초기 로드가 이것을 읽고 이후 틱을 이어붙인다.
     *
     * <p>실시간 {@code market.prices} 와 같은 레코드를 같은 서비스에서 만들므로 두 경로의
     * 형태가 갈릴 수 없다.
     *
     * @return 절단 기준·환율과 종목별 가격. 최초 공개 전에는 기준이 비고 목록도 빈 배열
     * @throws BusinessException 재생 상태가 초기화되지 않았을 때 (409, 재생 도메인이 판정한다),
     *                           공개 기준 틱이 편성에 없을 때 (500)
     */
    public MarketPricesResponse getPrices() {
        GameClockSnapshot clock = replay.clock();
        if (clock.tickSeq() == null) {
            return MarketPricesResponse.beforeFirstPublish();
        }
        // 브로드캐스트가 이 틱의 스냅샷을 이미 만들었으면 그것이다 — 전원 동일 응답을 요청마다 다시
        // 만들지 않는다. 틱 순번이 다르면(재시작 직후 첫 틱 전, 시즌 초기화 직후) 지금처럼 만든다.
        return marketPrices.snapshotIfCurrent(clock.tickSeq()).orElseGet(() -> {
            GameTick tick = tick(clock.tickSeq());
            return marketPrices.snapshotAsOf(tick.getTickSeq(), tick.getGameDate(), tick.getUsdKrw());
        });
    }

    /**
     * 한 종목의 종가 시계열. 차트 초기 로드다.
     *
     * <p>끝 날짜 파라미터를 두지 않는다 — 미래는 게임 시각이 자른다. 시작 날짜의 기본값은
     * 그 종목의 상장일이거나 재생 구간 시작이며, 둘 다 편성·종목 메타에서 나오는 값이라
     * 클라이언트가 알 필요가 없다.
     *
     * @param instrumentId 대상 종목
     * @param from         시작 게임 거래일. {@code null} 이면 상장일 또는 재생 구간 시작이고,
     *                     그보다 이른 날짜를 주면 그 경계로 잘린다
     * @return 통화·자릿수·절단 기준과 종가가 있는 날짜의 점
     * @throws BusinessException 유니버스에 없는 종목이면 (404),
     *                           재생 상태가 초기화되지 않았을 때 (409, 재생 도메인이 판정한다),
     *                           재생 편성이 비어 시작 날짜를 정할 수 없을 때 (500)
     */
    public InstrumentClosesResponse getCloses(Long instrumentId, LocalDate from) {
        // 차트도 목록과 같은 대상만 연다 — 다른 시즌 종목과 지수 대리 종목은 404 다.
        Instrument instrument = instrumentRepository.findByIdAndSeasonId(instrumentId, seasonId)
                .filter(found -> found.getAssetType().isTradable())
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "종목이 없습니다."));
        GameClockSnapshot clock = replay.clock();
        if (clock.tickSeq() == null) {
            return InstrumentClosesResponse.beforeFirstPublish(instrument);
        }
        // 시계가 공개 기준 틱과 그 거래일을 이미 갖고 있다. 시계열은 환율을 쓰지 않으므로
        // 현재가 조회와 달리 game_ticks 를 다시 읽지 않는다.
        return InstrumentClosesResponse.of(instrument, clock.tickSeq(), clock.gameDate(),
                dailyCloseRepository.findByInstrumentIdAndGameDateBetweenOrderByGameDateAsc(
                        instrumentId, lowerBound(instrument, from), clock.gameDate()));
    }

    /**
     * 실제로 조회할 시작 날짜. <b>요청의 시작 날짜는 구간을 좁히기만 한다.</b>
     *
     * <p>기본값보다 이른 날짜를 그대로 받으면 IPO 종목의 그래프가 상장 전부터 시작해 거래할
     * 수 없던 구간에 선이 그려진다. 실데이터에는 상장 전 종가 행이 없어 지금은 결과가 같지만,
     * 계약("그래프는 상장일부터 시작한다")을 데이터가 없다는 전제에 맡기지 않는다.
     *
     * @param instrument 대상 종목
     * @param from       요청의 시작 게임 거래일. {@code null} 이면 기본값을 쓴다
     * @return 기본값과 요청 중 더 늦은 날짜
     */
    private LocalDate lowerBound(Instrument instrument, LocalDate from) {
        LocalDate earliest = defaultFrom(instrument);
        return from != null && from.isAfter(earliest) ? from : earliest;
    }

    /**
     * 시작 날짜 기본값. IPO 종목은 상장일부터 그려야 상장 전 구간에 빈 선이 생기지 않고,
     * 상시 종목은 재생 구간 시작부터다.
     */
    private LocalDate defaultFrom(Instrument instrument) {
        if (instrument.getListedGameDate() != null) {
            return instrument.getListedGameDate();
        }
        return gameTicks.first()
                .orElseThrow(() -> {
                    log.error("Season {} has no game ticks; the replay range start is unknown", seasonId);
                    return new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR,
                            "재생 편성이 없어 시계열을 만들 수 없습니다.");
                })
                .getGameDate();
    }

    /** 시계가 공개했다고 말한 틱. 편성에 없으면 재생 상태와 편성이 어긋난 것이다. */
    private GameTick tick(Long tickSeq) {
        return gameTicks.find(tickSeq).orElseThrow(() -> {
            log.error("Published tick {} is absent from game_ticks (season {})", tickSeq, seasonId);
            return new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "공개 기준 틱을 찾을 수 없습니다.");
        });
    }
}

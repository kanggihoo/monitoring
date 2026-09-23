package com.ssafy.a605.backend.domain.season.service;

import static com.ssafy.a605.backend.domain.replay.entity.ReplayState.BLACKOUT_START;
import static com.ssafy.a605.backend.domain.replay.entity.ReplayState.BRIEFING;
import static com.ssafy.a605.backend.domain.replay.entity.ReplayState.CLOSE;
import static com.ssafy.a605.backend.domain.replay.entity.ReplayState.END;
import static com.ssafy.a605.backend.domain.replay.entity.ReplayState.FEE_FREE_END;
import static com.ssafy.a605.backend.domain.replay.entity.ReplayState.FEE_FREE_START;
import static com.ssafy.a605.backend.domain.replay.entity.ReplayState.TRADING_OPEN;
import static com.ssafy.a605.backend.global.response.Amounts.KRW_SCALE;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.ssafy.a605.backend.domain.account.repository.AccountRepository;
import com.ssafy.a605.backend.domain.ipo.entity.IpoOffering;
import com.ssafy.a605.backend.domain.ipo.repository.IpoOfferingRepository;
import com.ssafy.a605.backend.domain.market.entity.AssetType;
import com.ssafy.a605.backend.domain.market.repository.InstrumentRepository;
import com.ssafy.a605.backend.domain.replay.service.ReplayService;
import com.ssafy.a605.backend.domain.season.dto.CurrentSeasonResponse;
import com.ssafy.a605.backend.domain.season.dto.CurrentSeasonResponse.Participation;
import com.ssafy.a605.backend.domain.season.dto.CurrentSeasonResponse.PhaseWindow;
import com.ssafy.a605.backend.domain.season.dto.CurrentSeasonResponse.Rules;
import com.ssafy.a605.backend.domain.season.dto.CurrentSeasonResponse.Window;
import com.ssafy.a605.backend.domain.season.dto.SeasonBriefingResponse;
import com.ssafy.a605.backend.domain.season.dto.SeasonBriefingResponse.IpoWindow;
import com.ssafy.a605.backend.domain.season.dto.SeasonBriefingResponse.Schedule;
import com.ssafy.a605.backend.domain.season.entity.GameTick;
import com.ssafy.a605.backend.domain.season.entity.Season;
import com.ssafy.a605.backend.domain.season.entity.SeasonDay;
import com.ssafy.a605.backend.domain.season.repository.GameTickRepository;
import com.ssafy.a605.backend.domain.season.service.GameTickCatalog;
import com.ssafy.a605.backend.domain.season.repository.SeasonDayRepository;
import com.ssafy.a605.backend.domain.season.repository.SeasonRepository;
import com.ssafy.a605.backend.global.exception.BusinessException;
import com.ssafy.a605.backend.global.response.Amounts;

import lombok.extern.slf4j.Slf4j;

/**
 * 시즌 안내와 개장 브리핑 ({@code sea.md}).
 *
 * <p><b>시간 상수를 다시 정의하지 않는다.</b> 하루의 경계는
 * {@link com.ssafy.a605.backend.domain.replay.entity.ReplayState} 가 갖고 있고 재생 진행이
 * 같은 값을 쓴다 — 여기에 복사하면 시간표와 실제 진행이 갈릴 수 있다. 절대 시각은
 * {@code season_days.opens_at}({@code TRADING_OPEN} 지점)에 그 오프셋을 더해 얻는다.
 *
 * <p><b>시즌 목록·{@code {seasonId}} 경로를 두지 않았으므로</b> 조회 대상은 재생과 같은
 * 설정값이 정하는 시즌 하나다 — 재생이 진행하는 시즌과 안내하는 시즌이 어긋나면 개장 시각이
 * 다른 시즌 것이 된다.
 */
/**
 * <b>{@code REPEATABLE_READ} 를 유지한다</b> ({@code S15P21A605-205} 감사). 시계와 함께
 * 계좌·공모 편성을 읽고 그것으로 브리핑 일정을 만든다. 공모 상태는 틱 경계마다 바뀌므로
 * 두 읽기가 다른 스냅샷이면 브리핑이 말하는 일정과 실제 상태가 어긋난다.
 */
@Slf4j
@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class SeasonQueryService {

    /** 하루 시간표는 KST 시:분으로 내린다 — 매일 반복되는 규칙이라 날짜가 없다. */
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter HOUR_MINUTE = DateTimeFormatter.ofPattern("HH:mm");

    private final SeasonRepository seasonRepository;
    private final SeasonDayRepository seasonDayRepository;
    private final InstrumentRepository instrumentRepository;
    private final IpoOfferingRepository ipoOfferingRepository;
    private final GameTickRepository gameTickRepository;
    private final GameTickCatalog gameTicks;
    private final AccountRepository accountRepository;
    private final ReplayService replay;
    private final Clock clock;
    private final long seasonId;

    public SeasonQueryService(SeasonRepository seasonRepository,
                              SeasonDayRepository seasonDayRepository,
                              InstrumentRepository instrumentRepository,
                              IpoOfferingRepository ipoOfferingRepository,
                              GameTickRepository gameTickRepository, GameTickCatalog gameTicks,
                              AccountRepository accountRepository, ReplayService replay, Clock clock,
                              @Value("${app.replay.season-id:1}") long seasonId) {
        this.seasonRepository = seasonRepository;
        this.seasonDayRepository = seasonDayRepository;
        this.instrumentRepository = instrumentRepository;
        this.ipoOfferingRepository = ipoOfferingRepository;
        this.gameTickRepository = gameTickRepository;
        this.gameTicks = gameTicks;
        this.accountRepository = accountRepository;
        this.replay = replay;
        this.clock = clock;
        this.seasonId = seasonId;
    }

    /**
     * 시즌 안내와 참가 상태.
     *
     * <p><b>재생 상태를 보지 않는다.</b> 이 경로는 미인증에 열려 있고 로그인 전 화면이 쓰므로,
     * 재생이 아직 초기화되지 않은 상태에서도 답해야 한다.
     *
     * @param userId 요청자. <b>미인증이면 {@code null}</b> 이고 그때 참가 상태도 {@code null} 이다
     * @return 시즌 편성값·하루 시간표·규칙 요약·다음 개장 시각과 참가 상태
     * @throws BusinessException 시즌 편성이 없을 때 (500)
     */
    public CurrentSeasonResponse current(Long userId) {
        Season season = seasonRepository.findById(seasonId).orElseThrow(() -> {
            log.error("Season {} is absent from seasons", seasonId);
            return new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "시즌 편성이 없습니다.");
        });
        List<SeasonDay> days = days();
        // 세 일차가 같은 시각(11:05 KST)에 열리는 반복 규칙이므로 첫 일차를 대표로 쓴다 —
        // 그래서 하루 시간표에 날짜가 없다 (sea.md).
        Instant opensAt = days.getFirst().getOpensAt();
        Instant now = clock.instant();

        return new CurrentSeasonResponse(
                season.getName(), season.getStartDate(), season.getEndDate(),
                List.of(
                        new PhaseWindow("BRIEFING", kst(opensAt, BRIEFING), kst(opensAt, TRADING_OPEN)),
                        new PhaseWindow("TRADING", kst(opensAt, TRADING_OPEN), kst(opensAt, CLOSE)),
                        new PhaseWindow("SETTLING", kst(opensAt, CLOSE), kst(opensAt, END)),
                        // 열람 전용은 다음 날 브리핑까지 이어진다. 반복 규칙이라 날짜가 없어
                        // 종료 시각이 브리핑 시작 시각과 같은 문자열이다.
                        new PhaseWindow("REVIEW_ONLY", kst(opensAt, END), kst(opensAt, BRIEFING))),
                new Rules(
                        Amounts.of(season.getSeedKrw(), KRW_SCALE),
                        Amounts.ratio(season.getTradeFeeRate()),
                        Amounts.ratio(season.getExchangeFeeRate()),
                        new Window(kst(opensAt, FEE_FREE_START), kst(opensAt, FEE_FREE_END)),
                        new Window(kst(opensAt, BLACKOUT_START), kst(opensAt, CLOSE)),
                        instrumentRepository.countBySeasonIdAndAssetTypeNot(seasonId, AssetType.INDEX),
                        instrumentRepository.countBySeasonIdAndAssetType(seasonId, AssetType.IPO_STOCK)),
                days.stream().map(SeasonDay::getOpensAt).filter(now::isBefore).findFirst().orElse(null),
                participation(userId));
    }

    /**
     * 당일 개장 브리핑.
     *
     * @return 당일 일차·재생 시작 시점·일정 절대 시각과 청약 일정
     * @throws BusinessException 재생 상태가 초기화되지 않았을 때 (409, 재생 도메인이 판정한다),
     *                           시즌 편성에 그 일차가 없을 때 (500)
     */
    public SeasonBriefingResponse briefing() {
        short seasonDay = replay.clock().seasonDay();
        List<SeasonDay> days = days();
        SeasonDay today = days.stream()
                .filter(day -> day.getSeasonDay() == seasonDay)
                .findFirst()
                .orElseThrow(() -> {
                    log.error("Season {} has no day {} in season_days", seasonId, seasonDay);
                    return new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR,
                            "일차 편성이 없습니다.");
                });
        Instant opensAt = today.getOpensAt();

        return new SeasonBriefingResponse(seasonDay, today.getFromGameMonth(),
                new Schedule(opensAt.plusSeconds(TRADING_OPEN), opensAt.plusSeconds(FEE_FREE_START),
                        opensAt.plusSeconds(FEE_FREE_END), opensAt.plusSeconds(BLACKOUT_START),
                        opensAt.plusSeconds(CLOSE), opensAt.plusSeconds(END)),
                ipoWindows(seasonDay, days));
    }

    /**
     * 당일 청약 일정. 공시 틱이 그 일차의 틱 구간에 드는 공모가 그날의 청약이다.
     *
     * <p>틱 하나의 절대 시각은 <b>그 틱이 속한 일차</b>의 개장 시각에 불변 오프셋을 더한
     * 값이다. 공시와 마감이 서로 다른 일차에 놓이는 편성은 지금 없지만, 오프셋의 기준을 틱마다
     * 그 틱의 일차로 잡아 두면 그런 편성이 와도 시각이 어긋나지 않는다.
     *
     * <p>공모 편성이 비어 있으면 빈 배열이고 나머지 필드는 정상이다.
     */
    private List<IpoWindow> ipoWindows(short seasonDay, List<SeasonDay> days) {
        GameTickRepository.TickRange range = gameTickRepository.findTickRange(seasonId, seasonDay);
        if (range.fromTickSeq() == null) {
            return List.of();
        }
        List<IpoOffering> today = ipoOfferingRepository.findBySeasonId(seasonId).stream()
                .filter(offering -> offering.getDisclosureTickSeq() >= range.fromTickSeq()
                        && offering.getDisclosureTickSeq() <= range.toTickSeq())
                .sorted(Comparator.comparing(IpoOffering::getDisclosureTickSeq))
                .toList();
        if (today.isEmpty()) {
            return List.of();
        }
        Map<Short, Instant> opensAtByDay = days.stream()
                .collect(Collectors.toMap(SeasonDay::getSeasonDay, SeasonDay::getOpensAt));
        Map<Long, GameTick> ticks = gameTicks.findAll(today.stream()
                        .flatMap(offering -> Stream.of(offering.getDisclosureTickSeq(),
                                offering.getCloseTickSeq()))
                        .toList()).stream()
                .collect(Collectors.toMap(GameTick::getTickSeq, Function.identity()));

        return today.stream()
                .map(offering -> new IpoWindow(at(ticks, opensAtByDay, offering.getDisclosureTickSeq()),
                        at(ticks, opensAtByDay, offering.getCloseTickSeq())))
                .toList();
    }

    /** 그 틱이 공개되는 실제 시각. 편성에 없는 틱이면 외래키가 이미 막았어야 하는 상태다. */
    private Instant at(Map<Long, GameTick> ticks, Map<Short, Instant> opensAtByDay, Long tickSeq) {
        GameTick tick = ticks.get(tickSeq);
        if (tick == null) {
            log.error("Offering tick {} is absent from game_ticks (season {})", tickSeq, seasonId);
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "공모 편성이 가리키는 틱을 찾을 수 없습니다.");
        }
        return opensAtByDay.get(tick.getSeasonDay()).plusSeconds(tick.getCumOffsetSec());
    }

    /**
     * 참가 상태. <b>계좌 조회식이다</b> — 계좌를 만드는 코드가 아직 없어 지금은 항상 비지만,
     * 생성이 붙으면 이 경로가 코드 변경 없이 채워진다.
     *
     * <p>인증인데 계좌가 없어도 {@code null} 이고 <b>응답이 실패하지 않는다</b>. 404·409 를
     * 내면 로그인만 해도 시즌 화면이 깨진다.
     */
    private Participation participation(Long userId) {
        if (userId == null) {
            return null;
        }
        return accountRepository.findByUserIdAndSeasonId(userId, seasonId)
                .map(Participation::from)
                .orElse(null);
    }

    private List<SeasonDay> days() {
        List<SeasonDay> days = seasonDayRepository.findBySeasonIdOrderBySeasonDayAsc(seasonId);
        if (days.isEmpty()) {
            log.error("Season {} has no rows in season_days", seasonId);
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "일차 편성이 없습니다.");
        }
        return days;
    }

    /** 개장 시각에서 오프셋만큼 떨어진 지점의 KST 시:분. */
    private static String kst(Instant opensAt, int offsetSec) {
        return opensAt.plusSeconds(offsetSec).atZone(KST).format(HOUR_MINUTE);
    }
}

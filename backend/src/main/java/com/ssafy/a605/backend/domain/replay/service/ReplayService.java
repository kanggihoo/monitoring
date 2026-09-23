package com.ssafy.a605.backend.domain.replay.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.ssafy.a605.backend.domain.market.service.MarketPriceService;
import com.ssafy.a605.backend.domain.replay.dto.GameClockSnapshot;
import com.ssafy.a605.backend.domain.replay.dto.PhaseBoundaryEvent;
import com.ssafy.a605.backend.domain.replay.dto.ReplaySnapshot;
import com.ssafy.a605.backend.domain.replay.dto.TickPublishedEvent;
import com.ssafy.a605.backend.domain.replay.entity.ReplayMode;
import com.ssafy.a605.backend.domain.replay.entity.ReplayState;
import com.ssafy.a605.backend.domain.replay.repository.ReplayQueryRepository;
import com.ssafy.a605.backend.domain.replay.repository.ReplayQueryRepository.Tick;
import com.ssafy.a605.backend.domain.season.entity.GameTick;
import com.ssafy.a605.backend.domain.season.service.GameTickCatalog;
import com.ssafy.a605.backend.global.exception.BusinessException;
import com.ssafy.a605.backend.global.websocket.RealtimePublisher;
import com.ssafy.a605.backend.global.websocket.WsDestination;

import lombok.extern.slf4j.Slf4j;

/**
 * 게임 가상 시계(Game Clock) 및 시장 데이터 재생(Replay)의 상태 전진과 발행을 총괄하는 도메인 서비스.
 *
 * <p>현실 시각과 게임 가상 시간 간의 선형 앵커 수식을 기반으로 동작하며,
 * 백그라운드 폴러({@link ReplayScheduler})에 의한 틱/이벤트 전진과
 * 관리자의 재생 제어(배속 변경, 일시정지, 재개, 시작 예약)를 조율합니다.
 *
 * <h3>주요 설계 원칙</h3>
 * <ul>
 *   <li><b>선형 앵커 시간 모델:</b> 가상 시각을 매번 DB에 기록하지 않고,
 *       기준 시각({@code anchorAt}), 기준 오프셋({@code anchorOffsetSec}), 배속({@code speed})을
 *       이용한 수식 {@code rawOffset(now) = anchorOffsetSec + (now - anchorAt) * speed}으로 동적 계산합니다.</li>
 *   <li><b>선 커밋 후 발행 (Commit-then-Publish):</b> DB 트랜잭션을 먼저 완전히 커밋한 뒤
 *       웹소켓으로 이벤트를 브로드캐스트(best-effort)합니다. 네트워크 전송 실패가 이미 커밋된 비즈니스 상태를 롤백시키지 않습니다.</li>
 *   <li><b>비관적 락을 통한 직렬화:</b> 스케줄러의 주기적 진행({@link #progress()})과 관리자의 상태 변경({@link #change(BigDecimal, Instant)})이
 *       동일한 시즌 행에 대한 {@code PESSIMISTIC_WRITE} 락 안에서 수행되어 동시성 충돌(Lost Update)을 원천 차단합니다.</li>
 *   <li><b>트랜잭션 템플릿 분리:</b> 읽기는 읽기 전용으로 {@code replay_state} 한 행만 읽고,
 *       쓰기는 {@code REQUIRES_NEW} 전파 수준으로 각 작업 단위의 즉각적인 커밋을 보장합니다.</li>
 * </ul>
 */
@Service
@Slf4j
public class ReplayService {

    /** 불변 편성 데이터 및 재생 상태 조회를 담당하는 전용 레포지토리 */
    private final ReplayQueryRepository repository;

    /** 공개 기준 틱의 가격 스냅샷. 휴장 이월 규칙은 시장 도메인의 것이다 */
    private final MarketPriceService marketPrices;
    /** 불변 틱 편성. 시계 스냅샷이 공개 틱의 거래일과 시즌 첫 틱을 여기서 읽는다 */
    private final GameTickCatalog gameTicks;

    /** 현실 서버 시각 제공 시계 */
    private final Clock clock;

    /** STOMP 웹소켓 브로드캐스트 발행자 */
    private final RealtimePublisher publisher;

    /** 틱 커밋 후 프로세스 내부 리스너(랭킹 재계산 등)에 알리는 발행자 */
    private final ApplicationEventPublisher events;

    /** 쓰기 전용 독립 트랜잭션 템플릿 (PROPAGATION_REQUIRES_NEW) */
    private final TransactionTemplate write;

    /**
     * 읽기 전용 트랜잭션 템플릿 (readOnly=true, 기본 격리).
     *
     * <p><b>격리 수준을 올리지 않는다</b> ({@code S15P21A605-205}). 예전에는
     * {@code REPEATABLE_READ} 였고 근거는 "시계가 {@code replay_state} 와 {@code game_ticks}
     * 두 곳을 한 스냅샷에서 읽어야 한다" 였다. {@code S15P21A605-247} 이후 {@code game_ticks}
     * 는 편성 카탈로그에서 읽으므로 <b>이 템플릿이 DB 에서 읽는 것은 {@code replay_state} 한
     * 행뿐</b>이고, 한 행에는 {@code REPEATABLE_READ} 가 보장할 것이 없다.
     *
     * <p>비용은 격리를 올릴 때마다 붙는 왕복 셋이다 — pgjdbc 가 {@code SET SESSION
     * CHARACTERISTICS} · 확인용 {@code SHOW} · 끝에 원복 {@code SET} 을 낸다. 데이터를
     * 1 바이트도 읽지 않는 왕복이고, 시계는 인증 요청 거의 전부가 부른다.
     */
    private final TransactionTemplate read;

    /** 재생 대상 시즌 ID */
    private final long seasonId;

    /** 재생 실행 모드 (TEST / PRODUCTION / DISABLED) */
    private final ReplayMode mode;

    /**
     * 유휴 판정의 재확인 상한(초). 이보다 오래 DB 를 안 보고 있지 않는다 (S15P21A605-247).
     */
    private static final int MAX_IDLE_SECONDS = 60;

    /**
     * 다음에 잠금 트랜잭션을 열어야 할 시각. 그 전까지 {@link #progress()} 는 DB 를 열지 않는다.
     *
     * <p><b>{@code null} 은 "즉시 열라"다.</b> 관리자 조작처럼 상태가 프로세스 밖 이유로 바뀌었을
     * 수 있는 모든 자리에서 이 값을 비운다 — {@code replay_state} 를 바꾸는 코드가 이 클래스
     * 안에만 있어서 그 자리를 전부 셀 수 있다.
     */
    private final AtomicReference<Instant> nextDueAt = new AtomicReference<>();

    /**
     * 진행 중인 지점 이동(Warp)의 목표. 도달하거나 진행이 멈추면 비운다.
     *
     * <p><b>컬럼을 만들지 않는다.</b> 재시작이나 진행 실패에서 이동이 조용히 이어지면
     * 안 되고("도중 실패하면 그 자리에 멈춘다"), 다중 인스턴스 조율은 범위 밖이다.
     * 이 값이 비어 있으면 배속 0 은 평상시 정지와 완전히 같게 동작한다.
     */
    private final AtomicReference<Warp> warp = new AtomicReference<>();

    /**
     * ReplayService 생성자.
     *
     * @param repository   재생 전용 쿼리 레포지토리
     * @param marketPrices 가격 스냅샷을 만드는 시장 도메인 서비스
     * @param gameTicks    불변 틱 편성
     * @param clock        현실 시각 제공 시계
     * @param publisher    웹소켓 메시지 발행기
     * @param events       프로세스 내부 이벤트 발행기
     * @param manager      스프링 트랜잭션 매니저
     * @param seasonId     대상 시즌 ID (기본값: 1)
     * @param mode         재생 실행 모드 (기본값: disabled)
     */
    public ReplayService(ReplayQueryRepository repository, MarketPriceService marketPrices,
            GameTickCatalog gameTicks, Clock clock,
            RealtimePublisher publisher, ApplicationEventPublisher events, PlatformTransactionManager manager,
            @Value("${app.replay.season-id:1}") long seasonId,
            @Value("${app.replay.mode:disabled}") ReplayMode mode) {
        this.repository = repository;
        this.marketPrices = marketPrices;
        this.gameTicks = gameTicks;
        this.clock = clock;
        this.publisher = publisher;
        this.events = events;
        this.seasonId = seasonId;
        this.mode = mode;
        write = new TransactionTemplate(manager);
        write.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        read = new TransactionTemplate(manager);
        read.setReadOnly(true);
    }

    /**
     * 관리자용 전체 시계 스냅샷을 조회합니다.
     *
     * <p>조회 작업은 시계를 저장하거나 백그라운드 폴러를 실행하지 않으며,
     * {@code replay_state} 한 행만 읽어 나머지는 산술로 만듭니다.
     *
     * @return 앵커 시각, 가상 오프셋, 배속, 페이즈 등 관리자 조작에 필요한 전체 정보가 포함된 스냅샷
     */
    public ReplaySnapshot snapshot() {
        return read.execute(status -> snapshot(state(false), now()));
    }

    /**
     * 일반 사용자용 공개 시계 스냅샷을 조회합니다.
     *
     * <p>{@link #snapshot()}과 동일한 계산 로직을 사용하되, 배속 조작이나 앵커 시각 등
     * 일반 사용자에게 노출되면 안 되는 관리자 전용 필드를 제외하고 반환합니다.
     *
     * @return 앵커·배속·시작 예약 필드가 제외된 일반 사용자용 시계 스냅샷
     */
    public GameClockSnapshot clock() {
        return GameClockSnapshot.from(snapshot());
    }

    /**
     * 오늘 그리드의 가상 위치들이 각각 실제로 언제인가.
     *
     * <p><b>오늘 것만 답한다.</b> 앵커는 진행 중인 일차 하나만 갖고 일차가 넘어가면 덮어쓰므로,
     * 지난 일차의 위치가 실제로 몇 시였는지는 남아 있지 않고 다음 일차는 아직 정해지지 않았다.
     * 물어본 일차가 오늘이 아니거나 재생이 정지 중(배속 0)이면 <b>빈 맵</b>을 준다 — 그 경우
     * "모른다"가 정답이며, 억지로 값을 만들면 화면이 오지 않을 시각을 카운트다운한다.
     *
     * <p>한 번에 여러 위치를 받는 것은 상태를 한 번만 읽기 위해서다. 공모 하나가 마감·상장 두
     * 시각을 필요로 하므로({@code ipo.md}), 위치마다 물으면 같은 행을 여러 번 읽고 그 사이에
     * 앵커가 바뀔 수 있다.
     *
     * @param seasonDay 그 위치들이 속한 일차
     * @param offsets   가상 위치들 ({@code game_ticks.cum_offset_sec})
     * @return 위치 -> 실제 시각. 답할 수 없으면 빈 맵
     */
    public Map<Integer, Instant> realTimesAt(short seasonDay, Collection<Integer> offsets) {
        if (offsets.isEmpty()) {
            return Map.of();
        }
        return read.execute(status -> {
            ReplayState state = state(false);
            if (state.getAnchorSeasonDay() == null || state.getAnchorSeasonDay() != seasonDay) {
                return Map.of();
            }
            Map<Integer, Instant> times = new LinkedHashMap<>();
            for (Integer offset : Set.copyOf(offsets)) {
                Instant at = state.atOffset(offset);
                if (at != null) {
                    times.put(offset, at);
                }
            }
            return times;
        });
    }

    /**
     * 재생 배속을 바꿉니다 (변경, 일시정지, 재개).
     *
     * <p>비관적 잠금을 확보한 뒤 실제 시각을 읽어 재앵커({@code reanchor})합니다.
     * 락 대기 시간으로 인한 시차 오차를 없애기 위해 잠금 확보 전의 시각은 사용하지 않습니다.
     * DB 트랜잭션이 안전하게 커밋된 후 {@code game.clock} 웹소켓 이벤트를 발행합니다.
     *
     * <p><b>시작 시각 예약은 없습니다.</b> 위치를 정하는 것은 지점 이동({@link #warpTo})의
     * 일입니다. 다만 <b>앵커 시각이 미래인 상태(시작 전 대기)는 그대로 다룹니다</b> — 운영
     * 적재가 1일차 브리핑 시각을 앵커로 심어 만드는 상태이며, 그때 배속만 바꾸면 그 시각을
     * 보존하고 정지하면 즉시 시작으로 풀립니다.
     *
     * @param speed 새로운 재생 배속 (0은 일시정지, 최대 100까지 소수 둘째 자리 허용)
     * @return 커밋된 상태로 구성된 새로운 시계 스냅샷
     * @throws BusinessException 다음의 경우 발생:
     *                           <ul>
     *                             <li>재생 모드가 {@link ReplayMode#TEST}가 아닌 경우 (404 NOT_FOUND)</li>
     *                             <li>배속이 계약 범위(0~100, 소수점 둘째 자리)를 벗어난 경우 (400 BAD_REQUEST)</li>
     *                             <li>일차 전환 경계 처리가 진행 중인 경우 (409 CONFLICT)</li>
     *                           </ul>
     */
    public ReplaySnapshot change(BigDecimal speed) {
        mode.requireTest();
        try { ReplayState.validateSpeed(speed); }
        catch (IllegalArgumentException e) { throw new BusinessException(HttpStatus.BAD_REQUEST, e.getMessage()); }
        Changed result = write.execute(status -> {
            ReplayState state = state(true);
            Instant now = now();
            // 배속을 바꾸는 순간 진행 중이던 지점 이동은 끝난 것이다. 남겨 두면 관리자가 되찾은
            // 시계 위에서 이동이 계속 앵커를 밀어 정지·예약이 제자리에 서 있지 못한다.
            warp.set(null);
            // 앵커가 바뀌므로 유휴 판정을 버린다. 다음 폴링이 즉시 DB 를 연다 (S15P21A605-247).
            nextDueAt.set(null);
            boolean waiting = state.getAnchorAt().isAfter(now);
            BigDecimal wasPaused = state.getSpeed();
            BigDecimal position = state.rawOffset(now);
            // 상한 초과는 아직 처리하지 않은 일차 전환을 뜻한다. 여기서 재앵커하면 전환에 쓸
            // 초과 경과 시간이 사라지므로 전환이 끝난 뒤에 다시 받는다.
            //
            // **최종 일차에는 전환이 없다.** 위치가 상한에 고정된 뒤에도 실제 시간은 계속
            // 흐르므로 rawOffset 은 상한을 넘어 자란다. 그것을 전환 대기로 보면 발표 이후
            // 정지·배속 변경이 영구히 409 가 되어 관리자가 시계를 되찾을 수 없다.
            if (position.compareTo(BigDecimal.valueOf(ReplayState.END)) > 0
                    && state.getAnchorSeasonDay() < ReplayState.FINAL_DAY)
                throw new BusinessException(HttpStatus.CONFLICT, "일차 경계 처리 중입니다. 잠시 후 다시 시도하세요");
            // 시작 전 대기(앵커가 미래)에서 배속만 바꾸면 그 시각을 보존한다. 정지는 대기를
            // 풀어 즉시 시작으로 만든다 — 운영 적재가 심은 앵커를 관리자가 되찾는 유일한 길이다.
            state.reanchor(state.getAnchorSeasonDay(), position.min(BigDecimal.valueOf(ReplayState.END)),
                    waiting && speed.signum() > 0 ? state.getAnchorAt() : now, speed);
            String reason = speed.signum() == 0 ? "PAUSED"
                    : wasPaused.signum() == 0 ? "RESUMED" : "SPEED_CHANGED";
            return new Changed(snapshot(state, now), reason);
        });
        emit(new Message("game.clock", null, null, result.snapshot().replayEpoch(),
                Map.of("reason", result.reason(), "clock", GameClockSnapshot.from(result.snapshot()))));
        return result.snapshot();
    }

    /**
     * 재생 위치를 목표 지점으로 옮깁니다 (Warp). <b>앞으로만 간다</b> — 뒤로 가는 목표는
     * 호출 측이 시즌을 초기화해 1일차 브리핑으로 되돌린 뒤 이 메서드를 부릅니다.
     *
     * <p>새 진행 엔진을 만들지 않습니다. 앵커를 목표로 옮기고 배속을 0 으로 두면 밀린 Tick 과
     * 시간 경계를 순서대로 따라잡는 기존 경로({@link #progress()})가 목표까지 진행한 뒤 더
     * 나아갈 것이 없어 멈춥니다. 요청 스레드는 진행을 대신 돌리지 않고 즉시 돌아옵니다.
     *
     * <p>목표가 다음 일차면 앵커를 오늘 상한까지만 밀고, 일차 전환은 기존 전환 경로가 합니다.
     * 전환 뒤 다음 폴러 실행이 다시 목표 쪽으로 밀어 결국 목표 일차에 도달합니다.
     *
     * @param day    목표 일차
     * @param offset 그 일차의 목표 가상 초 위치
     * @return 요청 직후의 시계 스냅샷. 도달 전이므로 현재 위치는 목표와 다를 수 있습니다
     * @throws BusinessException 재생 모드가 {@link ReplayMode#TEST} 가 아니거나 (404),
     *                           일차 전환 처리 중이면 (409)
     */
    public ReplaySnapshot warpTo(short day, int offset) {
        mode.requireTest();
        ReplaySnapshot moved = write.execute(status -> {
            ReplayState state = state(true);
            Instant now = now();
            BigDecimal position = state.rawOffset(now);
            // 배속 변경과 같은 이유로 거절한다 — 여기서 재앵커하면 전환에 쓸 초과 경과 시간이 사라진다.
            if (position.compareTo(BigDecimal.valueOf(ReplayState.END)) > 0
                    && state.getAnchorSeasonDay() < ReplayState.FINAL_DAY)
                throw new BusinessException(HttpStatus.CONFLICT, "일차 경계 처리 중입니다. 잠시 후 다시 시도하세요");
            state.reanchor(state.getAnchorSeasonDay(), position.min(BigDecimal.valueOf(ReplayState.END)), now,
                    BigDecimal.ZERO);
            warp.set(new Warp(day, offset));
            // 앵커가 바뀌고 이동이 시작된다. 유휴 판정을 버려야 폴러가 즉시 따라온다 (S15P21A605-247).
            nextDueAt.set(null);
            advanceWarp(state, now);
            return snapshot(state, now);
        });
        emit(new Message("game.clock", null, null, moved.replayEpoch(),
                Map.of("reason", "SEEK_STARTED", "clock", GameClockSnapshot.from(moved))));
        return moved;
    }

    /**
     * 진행 중인 지점 이동을 취소한다. 앵커는 그 자리에 둔다 — 되돌리는 것은 초기화의 일이다.
     *
     * <p><b>새 이동을 시작하기 전에 반드시 부른다.</b> 뒤로 가는 이동은 시즌 초기화와 앵커
     * 이동이 서로 다른 트랜잭션이라 그 사이에 폴러가 한 번 돈다. 옛 목표가 남아 있으면 폴러가
     * 방금 1일차 브리핑으로 되돌아온 앵커를 <b>옛 목표 쪽으로 다시 밀어</b>, 새 목표를 이미
     * 지나친 자리에서 이동이 끝난다.
     */
    public void cancelWarp() {
        warp.set(null);
        // 이동이 끝났으므로 다음 폴링이 새 상태를 직접 확인하게 한다 (S15P21A605-247).
        nextDueAt.set(null);
    }

    /**
     * 앵커를 목표 쪽으로 민다. 목표가 뒤 일차면 오늘 상한까지만 민다.
     *
     * @param state 잠긴 재생 상태
     * @param now   현재 실제 시각
     * @return 지점 이동이 진행 중이면 {@code true}
     */
    private boolean advanceWarp(ReplayState state, Instant now) {
        Warp goal = warp.get();
        if (goal == null) return false;
        int day = state.getAnchorSeasonDay();
        BigDecimal to = BigDecimal.valueOf(day < goal.day() ? ReplayState.END : goal.offsetSec());
        if (state.getAnchorOffsetSec().compareTo(to) < 0) {
            state.reanchor((short) day, to, now, state.getSpeed());
        }
        return true;
    }

    /**
     * 가상 시간에 도달한 항목 하나(시간 경계 통과, 주가 틱 공개, 일차 전환)를 처리하여 DB에 커밋하고 웹소켓으로 발행합니다.
     *
     * <p>백그라운드 스케줄러({@link ReplayScheduler})에 의해 주기적으로 호출됩니다.
     * 트랜잭션 성공 후 발행하며, 웹소켓 전송 실패가 이미 완료된 DB 커밋을 롤백시키지 않습니다.
     *
     * @return 항목 하나를 성공적으로 진행했으면 {@code true}, 처리할 항목이 없으면 {@code false}
     */
    public boolean progress() {
        long started = System.nanoTime();
        // 아무것도 할 수 없는 시각이면 DB 를 열지 않는다. 폴러는 100 ms 마다 오는데 실제로
        // 진행할 것은 하루 590 건(틱 584 + 경계 6)뿐이라, 나머지 99.3 % 가 잠금 트랜잭션을
        // 열었다 닫기만 했다 (S15P21A605-247).
        Instant due = nextDueAt.get();
        if (due != null && now().isBefore(due)) return false;
        Step step;
        try {
            step = write.execute(status -> {
                ReplayState state = state(true);
                Step next = next(state, now());
                // 더 진행할 것이 없으면 목표에 도달한 것이다. 같은 행 잠금 안에서 지워야
                // 그 사이 들어온 새 이동 요청의 목표를 지우지 않는다.
                if (next == null) warp.set(null);
                // 다음에 열어야 할 시각은 **잠금 안에서 확정된 상태**로만 계산한다.
                nextDueAt.set(next == null ? dueAfter(state, now()) : null);
                return next;
            });
        } catch (RuntimeException e) {
            // 위치는 커밋된 곳에 남고 목표는 사라진다 — 자동 재시도 없이 "목표 미도달 정지"다.
            warp.set(null);
            nextDueAt.set(null);
            throw e;
        }
        if (step == null) return false;
        Instant processedAt = now();
        if (step.message() != null) {
            emit(step.message());
            if (step.message().type().equals("market.prices"))
                events.publishEvent(new TickPublishedEvent(step.message().seq(), step.message().date()));
        }
        // Phase 경계도 커밋 뒤에 알린다. 폐장 스냅샷 확정처럼 경계에 얹히는 업무가 듣는다.
        if (step.boundary() != null) events.publishEvent(step.boundary());
        // 부하 측정용. 리스너(체결·평가·랭킹)까지 끝난 뒤의 소요라, 이 한 줄이 틱 하나의 실제 처리 비용이다.
        // dueAt 은 일정상 도달 예정 시각이고 lag 는 그 대비 커밋 시각의 지연이다 — 따라잡기 중이면 커진다.
        log.info("Replay step: type={} tickSeq={} dueAt={} processedAt={} lagMs={} totalMs={}",
                step.message() == null ? "day.transition" : step.message().type(),
                step.message() == null ? null : step.message().seq(), step.dueAt(), processedAt,
                step.dueAt() == null ? null : Duration.between(step.dueAt(), processedAt).toMillis(),
                (System.nanoTime() - started) / 1_000_000);
        return true;
    }

    /**
     * 현재 시각을 기준으로 가상 시계에서 다음에 처리할 단일 단계를 결정하고 상태를 갱신합니다.
     *
     * <p>우선순위:
     * <ol>
     *   <li>미처리된 시간 경계(페이즈 전환, 수수료 무료 구간, 랭킹 암전 등)가 틱보다 앞서거나 같으면 경계 완료 기록</li>
     *   <li>공개 시점이 도래한 주가 틱이 있으면 최신 가격 조회 및 틱 발행 기록</li>
     *   <li>당일 일정 종료({@code END})에 도달했고 다음 일차가 있으면 다음 일차 브리핑 시점으로 재앵커</li>
     * </ol>
     *
     * @param state 비관적 락으로 확보된 현재 재생 상태 엔티티
     * @param now   현재 현실 서버 시각
     * @return 진행된 단계와 발행할 메시지를 담은 {@link Step}, 처리할 항목이 없으면 {@code null}
     */
    private Step next(ReplayState state, Instant now) {
        if (mode == ReplayMode.PRODUCTION && state.getSpeed().compareTo(BigDecimal.ONE) != 0)
            throw new IllegalStateException("운영 재생 배속은 1이어야 합니다");
        if (now.isBefore(state.getAnchorAt())) return null;
        // 배속 0 은 평상시 정지다. 지점 이동 중일 때만 앵커를 목표 쪽으로 밀어 도달한 항목을
        // 기존 경로가 그대로 따라잡게 한다.
        if (state.getSpeed().signum() == 0 && !advanceWarp(state, now)) return null;
        BigDecimal raw = state.rawOffset(now);
        int day = state.getAnchorSeasonDay();
        int completed = state.getLastBoundarySeasonDay() != null && state.getLastBoundarySeasonDay() == day
                ? state.getLastBoundaryOffsetSec() : -301;
        Integer boundary = null;
        for (int value : ReplayState.BOUNDARIES) if (value > completed) { boundary = value; break; }
        Tick tick = nextTick(state);
        if (boundary != null && (tick == null || boundary <= tick.offset())
                && raw.compareTo(BigDecimal.valueOf(boundary)) >= 0) {
            state.completeBoundary(boundary);
            boolean phase = ReplayState.PHASE_BOUNDARIES.contains(boundary);
            String type = phase ? "game.phase" : "game.clock";
            Object payload = phase ? snapshot(state, now) : Map.of("reason", boundaryReason(boundary),
                    "clock", GameClockSnapshot.from(snapshot(state, now)));
            // Phase 경계가 아니어도 신호를 낸다 — 무료 타임 시작이 알림으로 남아야 하고, 그
            // 순간에 커밋된 신호는 여기서만 나온다. 듣는 쪽은 전부 자기 경계를 먼저 본다.
            return new Step(new Message(type, null, null, state.getReplayEpoch(), payload),
                    new PhaseBoundaryEvent((short) day, boundary), state.atOffset(boundary));
        }
        if (tick != null && raw.compareTo(BigDecimal.valueOf(tick.offset())) >= 0) {
            state.publish(tick.seq());
            var current = marketPrices.snapshotAsOf(tick.seq(), tick.date(), tick.fx());
            Object prices = Map.of("asOfTickSeq", current.asOfTickSeq(),
                    "asOfGameDate", current.asOfGameDate(), "usdKrwRate", current.usdKrwRate(),
                    "prices", current.prices(), "clock", GameClockSnapshot.from(snapshot(state, now)));
            return new Step(new Message("market.prices", tick.seq(), tick.date(), state.getReplayEpoch(), prices),
                    null, state.atOffset(tick.offset()));
        }
        if (completed == ReplayState.END) {
            if (day == ReplayState.FINAL_DAY) {
                // 마지막 위치는 고정하되 운영의 speed=1 계약은 유지한다.
                if (state.getAnchorOffsetSec().intValue() != ReplayState.END)
                    state.reanchor((short) day, BigDecimal.valueOf(ReplayState.END), now, state.getSpeed());
                return null;
            }
            // 정지 중의 일차 전환은 다음 일차를 목표로 하는 이동일 때만 일어난다. 평상시
            // 정지는 발표 위치에 그대로 서 있어야 한다.
            Warp goal = warp.get();
            if (state.getSpeed().signum() == 0 && (goal == null || goal.day() <= day)) return null;
            Instant scheduled = repository.findBriefingStart(seasonId, day + 1);
            if (scheduled == null) throw new IllegalStateException("다음 일차 편성이 없습니다");
            // 테스트는 야간 대기를 생략하고 발표 직후로 잇는다. 운영은 편성된 브리핑을 기다린다.
            // 정지 중에는 도달 예정 시각이 없다 — 지점 이동으로 상한에 서 있는 경우이고,
            // 그 위치에 도달한 시각은 이미 지났으므로 지금 바로 잇는다.
            Instant start = mode == ReplayMode.TEST
                    ? (state.getSpeed().signum() == 0 ? now : state.atOffset(ReplayState.END)) : scheduled;
            if (now.isBefore(start)) return null;
            state.reanchor((short) (day + 1), BigDecimal.valueOf(ReplayState.BRIEFING), start, state.getSpeed());
            return new Step(null, null, null);
        }
        return null;
    }

    /**
     * DB에서 현재 시즌의 재생 상태 엔티티를 조회합니다.
     *
     * @param lock 쓰기 잠금({@code PESSIMISTIC_WRITE}) 여부
     * @return 조회된 {@link ReplayState} 엔티티
     * @throws BusinessException 시즌 재생 상태가 DB에 초기화되어 있지 않은 경우 (409 CONFLICT)
     */
    private ReplayState state(boolean lock) {
        ReplayState state = repository.findState(seasonId, lock);
        if (state == null) throw new BusinessException(HttpStatus.CONFLICT, "시즌 재생 상태 초기화가 필요합니다");
        return state;
    }

    /**
     * 다음에 잠금 트랜잭션을 열어야 할 가장 이른 실제 시각 ({@code S15P21A605-247}).
     *
     * <p><b>틀리는 방향이 정해져 있다.</b> 실제보다 이른 시각을 주면 DB 를 괜히 한 번 더 열 뿐이고,
     * 늦은 시각을 주면 <b>틱이 늦게 나간다</b>. 그래서 판단이 서지 않는 모든 경우에 {@code null}
     * 을 주고, {@code null} 은 "다음 폴링에서 즉시 열라"는 뜻이다. 재생 정확도가 이 서비스의
     * 계약이므로 절약보다 그쪽이 먼저다.
     *
     * <p><b>{@link #next} 의 판단을 복제하지 않고 같은 재료로 최솟값만 구한다.</b> 후보는 다음
     * 경계와 다음 틱 둘이고 둘 다 {@link ReplayState#atOffset} 이라는 같은 산술로 시각이 나온다.
     * 어느 쪽이 먼저인지 고르는 일은 {@code next} 가 하므로 여기서는 <b>둘 중 이른 쪽</b>만 주면
     * 된다 — 그보다 이른 시각은 어차피 아무 일도 일어나지 않는다.
     *
     * <p><b>최대 {@value #MAX_IDLE_SECONDS} 초마다는 무조건 연다.</b> 폐장부터 다음 개장까지는
     * 18 시간을 건너뛸 수 있지만, 그동안 운영자가 DB 를 직접 고치거나 이 계산에 내가 놓친 경우가
     * 있으면 그만큼 늦게 따라잡는다. 상한을 두면 하루 열기가 590 회 대신 1,440 회가 되는데,
     * 864,000 회에 견주면 차이가 없고 얻는 안전은 크다.
     *
     * @param state 잠금 안에서 확정된 재생 상태
     * @param now   현재 실제 시각
     * @return 그 전까지는 열 필요가 없는 시각. 즉시 열어야 하면 {@code null}
     */
    private Instant dueAfter(ReplayState state, Instant now) {
        // 시작 예약(앵커가 미래)은 그 시각까지 아무 일도 없다.
        if (now.isBefore(state.getAnchorAt())) return capped(now, state.getAnchorAt());
        // 정지 중에는 시각으로 예측할 수 없다 — 지점 이동이 앵커를 미는 방식으로 진행한다.
        if (state.getSpeed().signum() == 0) return null;

        Instant earliest = null;
        int day = state.getAnchorSeasonDay();
        int completed = state.getLastBoundarySeasonDay() != null && state.getLastBoundarySeasonDay() == day
                ? state.getLastBoundaryOffsetSec() : -301;
        for (int value : ReplayState.BOUNDARIES) {
            if (value > completed) {
                earliest = state.atOffset(value);
                break;
            }
        }
        Tick tick = nextTick(state);
        if (tick != null) {
            Instant at = state.atOffset(tick.offset());
            if (at != null && (earliest == null || at.isBefore(earliest))) earliest = at;
        }
        // 경계도 틱도 남지 않았다 = 일차 전환 대기. 다음 일차의 브리핑 시각까지는 열 것이 없다.
        if (earliest == null) {
            if (day >= ReplayState.FINAL_DAY) return capped(now, null);
            Instant briefing = repository.findBriefingStart(seasonId, day + 1);
            return briefing == null ? null : capped(now, briefing);
        }
        return capped(now, earliest);
    }

    /** 상한을 씌운다. {@code at} 이 {@code null} 이면 상한 자체가 답이다. */
    private Instant capped(Instant now, Instant at) {
        Instant cap = now.plusSeconds(MAX_IDLE_SECONDS);
        return at == null || at.isAfter(cap) ? cap : at;
    }

    /**
     * 현재 일차에서 아직 공개되지 않은 가장 이른 주가 틱을 조회합니다.
     *
     * <p><b>편성 카탈로그에서 답한다</b> ({@code S15P21A605-247}). 같은 뜻의 SQL 이 있었지만
     * 폴러가 100 ms 마다, 시계 스냅샷이 요청마다 불러서 운영 하루치 {@code game_ticks} 접근
     * 99 만 건의 대부분이 이 한 줄이었다. {@code game_ticks} 는 시즌 중 불변이라 메모리로 옮겨도
     * 답이 달라지지 않는다.
     *
     * @param state 현재 재생 상태
     * @return 다음에 공개될 {@link Tick}, 당일 가격이 모두 공개되었으면 {@code null}
     */
    private Tick nextTick(ReplayState state) {
        return gameTicks.nextAfter(state.getAnchorSeasonDay(), state.getLastPublishedTickSeq())
                .map(tick -> new Tick(tick.getTickSeq(), tick.getGameDate(),
                        tick.getCumOffsetSec(), tick.getUsdKrw()))
                .orElse(null);
    }

    /**
     * 주어진 재생 상태와 현재 시각을 바탕으로 전체 시계 스냅샷 DTO를 조립합니다 (내부 계산 전용).
     *
     * @param state 재생 상태 엔티티
     * @param now   현재 현실 서버 시각
     * @return 조립된 {@link ReplaySnapshot} 객체
     */
    private ReplaySnapshot snapshot(ReplayState state, Instant now) {
        BigDecimal offset = state.rawOffset(now).min(BigDecimal.valueOf(ReplayState.END));
        int completed = state.getLastBoundarySeasonDay() != null
                && state.getLastBoundarySeasonDay().equals(state.getAnchorSeasonDay())
                ? state.getLastBoundaryOffsetSec() : ReplayState.BRIEFING;
        String phase = completed < ReplayState.TRADING_OPEN ? "BRIEFING" : completed < ReplayState.CLOSE ? "TRADING"
                : completed < ReplayState.END ? "SETTLING" : "REVIEW_ONLY";
        Tick next = nextTick(state);
        LocalDate date = state.getLastPublishedTickSeq() == null ? null
                : gameTicks.find(state.getLastPublishedTickSeq()).map(GameTick::getGameDate).orElse(null);
        Instant phaseEnd = completed < ReplayState.TRADING_OPEN ? state.atOffset(ReplayState.TRADING_OPEN)
                : completed < ReplayState.CLOSE ? state.atOffset(ReplayState.CLOSE)
                : completed < ReplayState.END ? state.atOffset(ReplayState.END) : null;
        if (completed >= ReplayState.END && state.getAnchorSeasonDay() < ReplayState.FINAL_DAY
                && state.getSpeed().signum() > 0) {
            phaseEnd = mode == ReplayMode.TEST ? state.atOffset(ReplayState.END)
                    : repository.findBriefingStart(seasonId, state.getAnchorSeasonDay() + 1);
        }
        return new ReplaySnapshot(seasonId, state.getAnchorSeasonDay(), offset.setScale(6, RoundingMode.HALF_UP),
                state.getAnchorOffsetSec(), state.getAnchorAt(), state.getSpeed(), phase,
                now, state.getLastPublishedTickSeq(), date, date == null ? null : date.toString().substring(0, 7),
                next == null ? null : state.atOffset(next.offset()), phaseEnd, feeFree(completed),
                feeFree(completed) ? state.atOffset(ReplayState.FEE_FREE_END) : null,
                completed >= ReplayState.BLACKOUT_START && completed < ReplayState.CLOSE, state.getReplayEpoch(),
                state.getSpeed().signum() > 0 && state.getAnchorAt().isAfter(now) ? state.getAnchorAt() : null,
                warp.get() != null, gameTicks.first().map(GameTick::getTickSeq).orElse(null));
    }

    /**
     * 특정 틱에 대한 주가 스냅샷 페이로드를 생성합니다.
     * 봉투(Envelope)의 공개 기준과 본문의 공개 기준을 같은 틱으로 일치시킵니다.
     *
     * <p><b>REST 조회와 같은 레코드를 씁니다.</b> 사양이 두 경로를 동일 형태로 계약했고
     * ({@code mkt.md}), 타입을 공유하면 한쪽만 고쳐 형태가 갈릴 수 없습니다. 휴장 이월·상장
     * 필터·자릿수는 전부 시장 도메인의 규칙입니다.
     *
     * @param tick 발행할 틱 정보
     * @return 틱 순번, 거래일, 환율, 종목별 가격을 담은 현재가 스냅샷
     */
    private Object prices(Tick tick) {
        return marketPrices.snapshotAsOf(tick.seq(), tick.date(), tick.fx());
    }

    /**
     * 마이크로초 단위로 절삭된 현재 현실 서버 시각을 반환합니다.
     *
     * @return 마이크로초 정밀도의 {@link Instant}
     */
    private Instant now() { return clock.instant().truncatedTo(ChronoUnit.MICROS); }

    /**
     * 웹소켓 발행기를 통해 클라이언트들에게 메시지를 브로드캐스트합니다.
     *
     * <p>전송 실패가 발생하더라도 로그만 남기고 예외를 삼켜, 이미 커밋된 DB 작업에 영향을 주지 않습니다.
     *
     * @param message 발행할 내부 메시지 객체
     */
    private void emit(Message message) {
        try {
            publisher.broadcastReplay(message.type().equals("market.prices") ? WsDestination.TICK : WsDestination.EVENT,
                    message.type(), message.seq(), message.date(), message.epoch(), message.payload());
        } catch (RuntimeException e) {
            log.error("Replay WS delivery failed: type={}, tick={}", message.type(), message.seq(), e);
        }
    }

    /**
     * Phase를 바꾸지 않는 시계 경계 통과 시 클라이언트에게 전달할 사유 코드를 반환합니다.
     *
     * @param boundary 도달한 경계 오프셋
     * @return 변경 사유 문자열 (FEE_FREE_STARTED, FEE_FREE_ENDED, LEADERBOARD_BLACKOUT_STARTED)
     */
    private static String boundaryReason(int boundary) {
        return switch (boundary) {
            case ReplayState.FEE_FREE_START -> "FEE_FREE_STARTED";
            case ReplayState.FEE_FREE_END -> "FEE_FREE_ENDED";
            case ReplayState.BLACKOUT_START -> "LEADERBOARD_BLACKOUT_STARTED";
            default -> throw new IllegalStateException("Phase 경계는 game.phase 로 발행한다: " + boundary);
        };
    }

    /**
     * 현재 처리 완료된 경계를 바탕으로 거래 수수료 무료 구간인지 여부를 판단합니다.
     * 수수료 무료는 별도 Phase가 아니라 TRADING 안의 시계 플래그입니다.
     *
     * @param completed 완료된 최근 경계 오프셋
     * @return 수수료 무료 구간이면 {@code true}
     */
    private static boolean feeFree(int completed) {
        return completed >= ReplayState.FEE_FREE_START && completed < ReplayState.FEE_FREE_END;
    }

    /**
     * 관리자 시계 조작({@link #change(BigDecimal, Instant)})의 결과와 변경 사유를 담는 내부 레코드.
     *
     * @param snapshot 변경 완료 후의 시계 스냅샷
     * @param reason   웹소켓 알림에 실어 보낼 변경 사유 (예: PAUSED, RESUMED, SPEED_CHANGED 등)
     */
    private record Changed(ReplaySnapshot snapshot, String reason) { }

    /**
     * 웹소켓 브로드캐스트 발행을 위해 필요한 공통 봉투 규격 데이터를 담는 내부 레코드.
     *
     * @param type    메시지 종류 (market.prices, game.phase, game.clock)
     * @param seq     공개 기준 주가 틱 순번 (틱 무관 이벤트면 null)
     * @param date    공개 기준 게임 거래일 (틱 무관 이벤트면 null)
     * @param epoch   재생 실행 세대 식별자
     * @param payload 실제 전송할 본문 데이터
     */
    private record Message(String type, Long seq, LocalDate date, long epoch, Object payload) { }

    /**
     * 단일 진행 단계({@link #next(ReplayState, Instant)})의 실행 결과와 발행할 메시지를 래핑하는 내부 레코드.
     *
     * @param message  웹소켓으로 발행할 메시지 (발행할 메시지가 없는 단계인 경우 null)
     * @param boundary 커밋 뒤 프로세스 안에 알릴 Phase 경계 (Phase 경계 단계가 아니면 null)
     */
    private record Step(Message message, PhaseBoundaryEvent boundary, Instant dueAt) { }

    /**
     * 진행 중인 지점 이동의 목표 좌표.
     *
     * @param day       목표 일차
     * @param offsetSec 그 일차의 목표 가상 초 위치
     */
    private record Warp(short day, int offsetSec) { }
}

package com.ssafy.a605.backend.support;

import static org.awaitility.Awaitility.await;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import org.springframework.boot.test.context.TestComponent;
import org.springframework.jdbc.core.JdbcTemplate;

import com.ssafy.a605.backend.domain.replay.service.ReplayService;
import com.ssafy.a605.backend.domain.user.entity.Role;
import com.ssafy.a605.backend.domain.user.entity.User;
import com.ssafy.a605.backend.domain.user.repository.UserRepository;
import com.ssafy.a605.backend.domain.user.service.UserService;

import lombok.RequiredArgsConstructor;

/**
 * {@code R__replay_fixture.sql} 이 심은 검증용 시즌의 좌표와, 매 테스트가 재생을 최초 상태로
 * 되돌리는 방법을 한곳에 둔다.
 *
 * <p>진행 위치를 되돌리는 것은 <b>테스트 준비</b>지 제품 기능이 아니다 — 스펙은 시드 재실행이나
 * 재시작이 상태를 덮어쓰지 않기를 요구하므로, 초기화는 여기에서만 SQL 로 한다.
 */
@TestComponent
@RequiredArgsConstructor
public class ReplayFixture {

    /** 이 시계가 가리키는 실제 시각이 모든 테스트의 출발점이다. */
    public static final Instant START = Instant.parse("2026-09-21T02:05:00Z");

    public static final long SEASON_ID = 1L;

    /** 게임 위치 경계 (스펙 "경계" 표). */
    public static final int BRIEFING = -300;
    public static final int TRADING_OPEN = 0;
    public static final int FEE_FREE_START = 3300;
    public static final int FEE_FREE_END = 3900;
    public static final int BLACKOUT_START = 17700;
    public static final int CLOSE = 20100;
    public static final int ANNOUNCE = 21300;

    /** 1·2일차 마지막 가격 틱 (16:29). 3일차는 그보다 이르다 (16:17). */
    public static final int LAST_TICK_DAY_1_2 = 19440;
    public static final int LAST_TICK_DAY_3 = 18720;

    /** 1일차 첫 세 틱의 오프셋. 간격이 불규칙하다. */
    public static final int FIRST_TICK = 32;
    public static final int SECOND_TICK = 65;
    public static final int THIRD_TICK = 99;

    /** 프레임·상태가 도달하기를 기다리는 한도. */
    private static final Duration AWAIT_LIMIT = Duration.ofSeconds(10);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(20);

    /** 이만큼 연속으로 값이 같으면 가라앉은 것으로 본다. */
    private static final int STABLE_POLLS = 10;

    private final JdbcTemplate jdbc;
    private final UserService userService;
    private final UserRepository userRepository;
    private final ReplayService replay;

    /**
     * 최초 테스트 상태: 1일차 브리핑, 정지, 공개 틱 없음, 경계 미처리, epoch 0.
     *
     * <p><b>진행 중인 지점 이동도 끊는다.</b> 이 행을 SQL 로 되돌려도 목표는 프로세스 안에
     * 남으므로, 끊지 않으면 폴러가 앞 테스트의 목표를 향해 방금 되돌린 앵커를 다시 민다.
     */
    public void reset(MutableClock clock) {
        clock.set(START);
        replay.cancelWarp();
        jdbc.update("""
                UPDATE replay_state SET anchor_season_day=1, anchor_offset_sec=-300, anchor_at=?,
                    speed=0, last_published_tick_seq=NULL, replay_epoch=0,
                    last_boundary_season_day=NULL, last_boundary_offset_sec=NULL
                WHERE season_id=?
                """, java.sql.Timestamp.from(START), SEASON_ID);
    }

    /** 재시작 시나리오용. 종료 전에 저장돼 있던 것과 같은 모양의 행을 직접 쓴다. */
    public void store(int day, BigDecimal offset, Instant anchorAt, BigDecimal speed,
            Long publishedTick, Integer boundaryDay, Integer boundaryOffset) {
        jdbc.update("""
                UPDATE replay_state SET anchor_season_day=?, anchor_offset_sec=?, anchor_at=?, speed=?,
                    last_published_tick_seq=?, last_boundary_season_day=?, last_boundary_offset_sec=?
                WHERE season_id=?
                """, day, offset, java.sql.Timestamp.from(anchorAt), speed, publishedTick,
                boundaryDay, boundaryOffset, SEASON_ID);
    }

    public Map<String, Object> row() {
        return jdbc.queryForMap("SELECT * FROM replay_state WHERE season_id=?", SEASON_ID);
    }

    /** 시드가 다시 도는 것과 같은 문장. 이미 있는 행은 건드리지 않아야 한다. */
    public void seedAgain() {
        jdbc.update("""
                INSERT INTO replay_state (season_id, anchor_season_day, anchor_offset_sec, anchor_at, speed)
                VALUES (?, 1, -300, ?, 0) ON CONFLICT DO NOTHING
                """, SEASON_ID, java.sql.Timestamp.from(START));
    }

    /** 그 일차의 오프셋에 해당하는 실제 시각. 앵커와 배속을 DB 에서 그대로 읽어 계산한다. */
    public Instant realTimeAt(int offset) {
        Map<String, Object> state = row();
        BigDecimal anchorOffset = (BigDecimal) state.get("anchor_offset_sec");
        BigDecimal speed = (BigDecimal) state.get("speed");
        Instant anchorAt = ((java.sql.Timestamp) state.get("anchor_at")).toInstant();
        if (speed.signum() == 0) {
            throw new IllegalStateException("정지 중에는 도달 예정 시각이 없다");
        }
        long nanos = BigDecimal.valueOf(offset).subtract(anchorOffset)
                .multiply(BigDecimal.valueOf(1_000_000_000L))
                .divide(speed, 0, java.math.RoundingMode.CEILING).longValueExact();
        return anchorAt.plusNanos(nanos);
    }

    /**
     * 폴러는 실제 타이머로 도니 시계를 옮긴 결과가 즉시 보이지는 않는다. 관찰 가능한 상태가
     * 기대에 도달할 때까지만 기다린다 — 고정 대기를 쓰지 않는다.
     *
     * @param condition 도달을 판정할 조건
     * @param failure   도달하지 않았을 때 남길 설명. 실패 시점에만 평가한다
     */
    public static void awaitUntil(BooleanSupplier condition, Supplier<String> failure) {
        try {
            await().atMost(AWAIT_LIMIT).pollInterval(POLL_INTERVAL).until(condition::getAsBoolean);
        } catch (org.awaitility.core.ConditionTimeoutException e) {
            throw new AssertionError("기대 상태에 도달하지 않았다: " + failure.get(), e);
        }
    }

    /**
     * 더 이상 진행할 것이 없는 상태로 가라앉기를 기다린다. "오지 않아야 한다"를 판정하기 전에
     * 쓴다 — 폴러가 아직 일하는 중인 것을 미발행으로 오인하지 않기 위해서다.
     *
     * <p>이것만 Awaitility 로 쓰지 않았다. 기다리는 대상이 "조건이 참이 되는 것"이 아니라
     * "값이 더 이상 바뀌지 않는 것"이고, 그 형태는 Awaitility 에 대응하는 기본형이 없다 —
     * {@code during()} 은 조건이 그 시간 동안 참인지를 보므로 값의 변화 자체를 판정하지 못한다.
     *
     * @param observed 안정됐는지 볼 관찰값. 연속으로 같은 값이 나오면 가라앉은 것으로 본다
     */
    public static void awaitQuiet(Supplier<?> observed) {
        Object last = observed.get();
        int stable = 0;
        Instant deadline = Instant.now().plus(AWAIT_LIMIT);
        while (stable < STABLE_POLLS && Instant.now().isBefore(deadline)) {
            sleep(POLL_INTERVAL);
            Object current = observed.get();
            stable = Objects.equals(current, last) ? stable + 1 : 0;
            last = current;
        }
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /**
     * 재생 API 는 전부 ADMIN 이라 네 테스트 클래스가 같은 사용자를 만든다.
     *
     * @return 새로 만든 ADMIN 사용자
     */
    public User admin() {
        User user = user("재생관리자");
        userService.changeRole(user.getId(), Role.ADMIN);
        return userRepository.findById(user.getId()).orElseThrow();
    }

    /**
     * 표시 이름을 지정한 사용자. 가입은 닉네임을 자동 생성하므로
     * ({@code UserService.create}) 만든 뒤 엔티티로 덮는다 — 랭킹·채팅 응답에 이 값이 뜨고
     * 테스트가 그것으로 사람을 구분한다.
     *
     * @param nickname 표시 이름. 같은 DB 를 공유하므로 뒤에 고유값을 붙인다
     * @return 새로 만든 USER 권한 사용자
     */
    public User user(String nickname) {
        User user = userService.findOrCreate("google", UUID.randomUUID().toString(),
                UUID.randomUUID() + "@example.com");
        user.changeNickname(nickname + UUID.randomUUID());
        return userRepository.save(user);
    }
}

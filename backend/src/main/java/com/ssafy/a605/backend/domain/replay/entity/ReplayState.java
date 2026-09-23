package com.ssafy.a605.backend.domain.replay.entity;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 실제 시각과 불변 게임 일정 사이의 앵커. 쓰기는 시즌 행 잠금 안에서 수행한다. */
@Entity
@Table(name = "replay_state")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReplayState {
    public static final int BRIEFING = -300;
    public static final int TRADING_OPEN = 0;
    public static final int FEE_FREE_START = 3300;
    public static final int FEE_FREE_END = 3900;
    public static final int BLACKOUT_START = 17700;
    public static final int CLOSE = 20100;
    public static final int END = 21300;

    /** 편성된 마지막 일차. 이 일차에는 다음 일차로의 전환이 없다. */
    public static final int FINAL_DAY = 3;

    /** 처리 순서대로의 시간 경계. 오름차순이어야 커서가 다음 경계를 고른다. */
    public static final List<Integer> BOUNDARIES =
            List.of(BRIEFING, TRADING_OPEN, FEE_FREE_START, FEE_FREE_END, BLACKOUT_START, CLOSE, END);

    /**
     * Phase 를 바꾸는 경계. 나머지({@code FEE_FREE_*}·{@code BLACKOUT_START})는 별도 Phase 가
     * 아니라 {@code TRADING} 안의 시계 플래그다.
     */
    public static final List<Integer> PHASE_BOUNDARIES = List.of(BRIEFING, TRADING_OPEN, CLOSE, END);

    @Id
    private Long seasonId;
    private Short anchorSeasonDay;
    @Column(precision = 16, scale = 6)
    private BigDecimal anchorOffsetSec;
    private Instant anchorAt;
    @Column(columnDefinition = "numeric")
    private BigDecimal speed;
    private Long lastPublishedTickSeq;
    private long replayEpoch;
    private Short lastBoundarySeasonDay;
    private Integer lastBoundaryOffsetSec;

    /**
     * 최초 상태만 만든다. 기존 실행의 초기화에는 사용하지 않는다.
     *
     * @param seasonId 대상 시즌
     * @param at       브리핑 위치에 대응시킬 실제 시각
     * @param speed    시작 배속
     */
    public ReplayState(Long seasonId, Instant at, BigDecimal speed) {
        this.seasonId = seasonId;
        reanchor((short) 1, BigDecimal.valueOf(BRIEFING), at, speed);
    }

    /**
     * 상한을 자르지 않아 일차 전환 시 초과 경과 시간을 보존한다.
     *
     * @param now 한 번 읽은 실제 시각. 앵커보다 이르면(시작 예약 대기) 앵커 위치를 그대로 돌려준다
     * @return 그 시각의 가상 위치. {@link #END} 를 넘을 수 있다
     */
    public BigDecimal rawOffset(Instant now) {
        if (now.isBefore(anchorAt)) return anchorOffsetSec;
        Duration elapsed = Duration.between(anchorAt, now);
        return anchorOffsetSec.add(BigDecimal.valueOf(elapsed.getSeconds())
                .add(BigDecimal.valueOf(elapsed.getNano(), 9)).multiply(speed));
    }

    /**
     * @param offset 도달 예정 시각을 구할 가상 위치
     * @return 그 위치에 도달하는 실제 시각. 정지 중(배속 0)이면 도달하지 않으므로 {@code null}
     */
    public Instant atOffset(int offset) {
        if (speed.signum() == 0) return null;
        BigDecimal nanos = BigDecimal.valueOf(offset).subtract(anchorOffsetSec)
                .multiply(BigDecimal.valueOf(1_000_000_000L)).divide(speed, 0, RoundingMode.CEILING);
        return anchorAt.plusNanos(nanos.longValueExact());
    }

    /**
     * 앵커와 배속을 함께 저장한다. DB와 같은 마이크로초 정밀도로 계산한다.
     *
     * @param day      앵커 일차
     * @param offset   앵커 가상 위치 ({@link #BRIEFING} ~ {@link #END})
     * @param at       그 위치에 대응하는 실제 시각. 시작 예약은 미래 시각을 쓴다
     * @param newSpeed 새 배속
     * @throws IllegalArgumentException 위치가 계약 범위를 벗어나거나 배속이 계약을 어기면
     */
    public void reanchor(short day, BigDecimal offset, Instant at, BigDecimal newSpeed) {
        if (day < 1 || day > 3 || offset.compareTo(BigDecimal.valueOf(BRIEFING)) < 0
                || offset.compareTo(BigDecimal.valueOf(END)) > 0) {
            throw new IllegalArgumentException("앵커 위치 범위를 벗어났습니다");
        }
        validateSpeed(newSpeed);
        anchorSeasonDay = day;
        anchorOffsetSec = offset.setScale(6, RoundingMode.HALF_UP);
        anchorAt = at.truncatedTo(ChronoUnit.MICROS);
        speed = newSpeed;
    }

    /**
     * 음수·범위 밖·초과 소수 입력을 반올림하지 않고 거절한다. DB의 CHECK 와 같은 계약이다.
     *
     * @param speed 검사할 배속
     * @throws IllegalArgumentException 0~100 밖이거나 소수 둘째 자리를 넘으면
     */
    public static void validateSpeed(BigDecimal speed) {
        if (speed == null || speed.signum() < 0 || speed.compareTo(BigDecimal.valueOf(100)) > 0
                || speed.scale() > 2) throw new IllegalArgumentException("배속은 0~100, 소수 둘째 자리까지입니다");
    }

    /**
     * 다음 틱만 전진시킨다. 첫 틱 번호는 실제 시즌 일정에서 결정한다.
     *
     * @param seq 공개한 틱 순번
     * @throws IllegalStateException 직전 공개 틱의 다음 순번이 아니면
     */
    public void publish(long seq) {
        if (lastPublishedTickSeq != null && seq != lastPublishedTickSeq + 1)
            throw new IllegalStateException("공개 틱 순서가 연속적이지 않습니다");
        lastPublishedTickSeq = seq;
    }

    /**
     * 경계 커서는 가격 워터마크나 실제 정산 완료와 별개다.
     *
     * @param offset 처리를 마친 경계 위치
     * @throws IllegalStateException 같은 일차에서 이미 지난 경계를 다시 처리하려 하면
     */
    public void completeBoundary(int offset) {
        if (lastBoundarySeasonDay != null && lastBoundarySeasonDay.equals(anchorSeasonDay)
                && offset <= lastBoundaryOffsetSec) throw new IllegalStateException("이미 처리한 경계입니다");
        lastBoundarySeasonDay = anchorSeasonDay;
        lastBoundaryOffsetSec = offset;
    }

    /**
     * 시즌 진행 결과를 지운 뒤 이 행을 1일차 브리핑 정지 상태로 되돌린다. <b>행을 지우지
     * 않는다</b> — 행이 없으면 재생 상태 조회가 409 가 되고 그 실패가 로그인 경로로 퍼진다.
     *
     * <p>세대를 올려 끊겼던 클라이언트가 재접속 직후 첫 봉투에서 불일치를 알아차리게 한다.
     *
     * @param at 브리핑 위치에 대응시킬 실제 시각
     */
    public void resetProgress(Instant at) {
        reanchor((short) 1, BigDecimal.valueOf(BRIEFING), at, BigDecimal.ZERO);
        lastPublishedTickSeq = null;
        lastBoundarySeasonDay = null;
        lastBoundaryOffsetSec = null;
        replayEpoch++;
    }
}

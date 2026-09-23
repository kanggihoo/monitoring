package com.ssafy.a605.backend.domain.replay.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 시간 계산만 떼어 컨테이너 없이 검증한다 — 실제 시각을 인자로 받는 순수 계산이라
 * 대기 없이 수시간·수일의 경계를 옮겨 다닐 수 있다.
 *
 * <p>진행·발행·권한은 여기서 보지 않는다. 그쪽은 REST 와 STOMP 경계에서 본다.
 */
class ReplayStateTest {

    private static final Instant T0 = Instant.parse("2026-09-21T02:05:00Z");

    private static ReplayState paused() {
        return new ReplayState(1L, T0, BigDecimal.ZERO);
    }

    private static BigDecimal speed(String value) {
        return new BigDecimal(value);
    }

    @Nested
    @DisplayName("정지와 재개")
    class PauseResume {

        @Test
        @DisplayName("가상 위치_정지 중 실제 시간이 흐르면_위치가 그대로다")
        void rawOffset_whilePaused_holdsPosition() {
            ReplayState state = paused();

            BigDecimal after = state.rawOffset(T0.plus(Duration.ofHours(3)));

            assertThat(after).isEqualByComparingTo(BigDecimal.valueOf(-300));
        }

        @Test
        @DisplayName("가상 위치_정지 후 재개하면_멈춰 있던 실제 시간이 더해지지 않는다")
        void rawOffset_afterResume_excludesPausedRealTime() {
            ReplayState state = paused();
            // 브리핑 정지 상태에서 3시간 뒤 1배속으로 재개한다.
            Instant resumedAt = T0.plus(Duration.ofHours(3));
            state.reanchor((short) 1, state.rawOffset(resumedAt), resumedAt, BigDecimal.ONE);

            // 재개 5분 뒤면 게임 속 브리핑 300초가 지나 거래 시작 위치다.
            BigDecimal atTradingStart = state.rawOffset(resumedAt.plus(Duration.ofMinutes(5)));

            assertThat(atTradingStart).isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("가상 위치_배속을 바꾸면_그 순간의 위치가 튀지 않는다")
        void reanchor_onSpeedChange_keepsPosition() {
            ReplayState state = paused();
            state.reanchor((short) 1, BigDecimal.valueOf(-300), T0, BigDecimal.ONE);
            Instant changedAt = T0.plus(Duration.ofSeconds(500));
            BigDecimal before = state.rawOffset(changedAt);

            state.reanchor((short) 1, before, changedAt, BigDecimal.valueOf(60));

            assertThat(state.rawOffset(changedAt)).isEqualByComparingTo(before);
            assertThat(before).isEqualByComparingTo(BigDecimal.valueOf(200));
        }

        @Test
        @DisplayName("가상 위치_소수 배속_반올림 없이 그대로 적용된다")
        void rawOffset_withFractionalSpeed_appliesWithoutRounding() {
            ReplayState state = paused();
            state.reanchor((short) 1, BigDecimal.ZERO, T0, speed("0.25"));

            // 실제 400초 x 0.25 = 게임 속 100초.
            assertThat(state.rawOffset(T0.plus(Duration.ofSeconds(400))))
                    .isEqualByComparingTo(BigDecimal.valueOf(100));
        }

        @Test
        @DisplayName("가상 위치_재앵커를 반복하면_허용 정밀도 이상의 드리프트가 쌓이지 않는다")
        void reanchor_repeated_doesNotDriftBeyondStoredPrecision() {
            ReplayState state = paused();
            state.reanchor((short) 1, BigDecimal.ZERO, T0, speed("2.5"));

            // 같은 위치에서 100번 재앵커해도 한 번에 계산한 기준선에서 벗어나지 않아야 한다.
            Instant at = T0;
            for (int i = 0; i < 100; i++) {
                at = at.plusMillis(37);
                state.reanchor((short) 1, state.rawOffset(at), at, speed("2.5"));
            }

            BigDecimal expected = new BigDecimal("3700").multiply(speed("2.5")).movePointLeft(3);
            assertThat(state.rawOffset(at).doubleValue()).isCloseTo(expected.doubleValue(), within(1e-6));
        }
    }

    @Nested
    @DisplayName("배속 검증")
    class SpeedValidation {

        @ParameterizedTest(name = "{0} 은 거절한다")
        @ValueSource(strings = {"-1", "-0.01", "100.01", "101", "0.001", "1.234"})
        @DisplayName("배속 검증_범위 밖과 초과 소수 정밀도_반올림하지 않고 거절한다")
        void validateSpeed_outOfContract_throws(String value) {
            assertThatThrownBy(() -> ReplayState.validateSpeed(speed(value)))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @ParameterizedTest(name = "{0} 은 받는다")
        @ValueSource(strings = {"0", "0.01", "1", "1.50", "99.99", "100"})
        @DisplayName("배속 검증_계약 범위의 경계값_받아들인다")
        void validateSpeed_atContractBounds_accepts(String value) {
            ReplayState.validateSpeed(speed(value));
        }

        @Test
        @DisplayName("앵커 검증_위치가 계약 범위를 벗어나면_거절한다")
        void reanchor_offsetOutOfRange_throws() {
            ReplayState state = paused();

            assertThatThrownBy(() -> state.reanchor((short) 1, BigDecimal.valueOf(-301), T0, BigDecimal.ONE))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> state.reanchor((short) 1, BigDecimal.valueOf(21301), T0, BigDecimal.ONE))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("시작 예약")
    class ScheduledStart {

        @Test
        @DisplayName("시작 예약_예정 시각 전_경과를 음수로 적용하지 않고 브리핑에 머문다")
        void rawOffset_beforeScheduledStart_holdsBriefingPosition() {
            ReplayState state = paused();
            Instant startAt = T0.plus(Duration.ofHours(2));
            state.reanchor((short) 1, BigDecimal.valueOf(-300), startAt, BigDecimal.ONE);

            assertThat(state.rawOffset(T0)).isEqualByComparingTo(BigDecimal.valueOf(-300));
            assertThat(state.rawOffset(startAt.minusMillis(1))).isEqualByComparingTo(BigDecimal.valueOf(-300));
        }

        @Test
        @DisplayName("시작 예약_예정 시각에 도달하면_그 시각부터 진행한다")
        void rawOffset_atScheduledStart_beginsProgress() {
            ReplayState state = paused();
            Instant startAt = T0.plus(Duration.ofHours(2));
            state.reanchor((short) 1, BigDecimal.valueOf(-300), startAt, BigDecimal.ONE);

            assertThat(state.rawOffset(startAt)).isEqualByComparingTo(BigDecimal.valueOf(-300));
            assertThat(state.rawOffset(startAt.plus(Duration.ofMinutes(5)))).isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("시작 예약_예정 시각을 넘겨 확인하면_저장된 앵커 기준으로 경과를 인정한다")
        void rawOffset_checkedAfterScheduledStart_usesStoredAnchor() {
            ReplayState state = paused();
            Instant startAt = T0.plus(Duration.ofHours(2));
            state.reanchor((short) 1, BigDecimal.valueOf(-300), startAt, BigDecimal.ONE);

            // 예정 시각을 10분 넘겨 기동했다면 게임은 이미 300초 지나 있어야 한다.
            assertThat(state.rawOffset(startAt.plus(Duration.ofMinutes(10))))
                    .isEqualByComparingTo(BigDecimal.valueOf(300));
        }
    }

    @Nested
    @DisplayName("예정 시각 계산")
    class ProjectedInstant {

        @Test
        @DisplayName("예정 시각_정지 중_도달 예정 시각이 없다")
        void atOffset_whilePaused_returnsNull() {
            assertThat(paused().atOffset(0)).isNull();
        }

        @Test
        @DisplayName("예정 시각_배속이 오르면_도달 예정 시각이 앞당겨진다")
        void atOffset_withHigherSpeed_returnsEarlierInstant() {
            ReplayState state = paused();
            state.reanchor((short) 1, BigDecimal.valueOf(-300), T0, BigDecimal.ONE);
            assertThat(state.atOffset(0)).isEqualTo(T0.plus(Duration.ofSeconds(300)));

            state.reanchor((short) 1, BigDecimal.valueOf(-300), T0, BigDecimal.valueOf(10));
            assertThat(state.atOffset(0)).isEqualTo(T0.plus(Duration.ofSeconds(30)));
        }
    }

    @Nested
    @DisplayName("일차 전환과 진행 커서")
    class Progress {

        @Test
        @DisplayName("가상 위치_상한을 넘어서면_일차 전환에 쓸 초과분이 남는다")
        void rawOffset_pastUpperBound_keepsOverflow() {
            ReplayState state = paused();
            // 1일차 16:59:58 위치에서 1배속으로 5초.
            state.reanchor((short) 1, BigDecimal.valueOf(21298), T0, BigDecimal.ONE);

            BigDecimal raw = state.rawOffset(T0.plus(Duration.ofSeconds(5)));

            // 21303 = 21300 + 3. 다음 일차 브리핑은 -300 + 3 = -297 (11:00:03) 이다.
            assertThat(raw).isEqualByComparingTo(BigDecimal.valueOf(21303));
        }

        @Test
        @DisplayName("공개 틱_연속되지 않은 순번_전진을 거절한다")
        void publish_nonConsecutiveSeq_throws() {
            ReplayState state = paused();
            state.publish(1L);
            state.publish(2L);

            assertThat(state.getLastPublishedTickSeq()).isEqualTo(2L);
            assertThatThrownBy(() -> state.publish(4L)).isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("경계 커서_같은 일차의 같은 경계_두 번 처리하지 않는다")
        void completeBoundary_sameBoundaryTwice_throws() {
            ReplayState state = paused();
            state.completeBoundary(-300);
            state.completeBoundary(0);

            assertThatThrownBy(() -> state.completeBoundary(0)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> state.completeBoundary(-300)).isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("경계 커서_일차가 바뀌면_처음부터 다시 센다")
        void completeBoundary_afterDayChange_restartsCursor() {
            ReplayState state = paused();
            state.completeBoundary(21300);
            state.reanchor((short) 2, BigDecimal.valueOf(-300), T0, BigDecimal.ONE);

            state.completeBoundary(-300);

            assertThat(state.getLastBoundarySeasonDay()).isEqualTo((short) 2);
            assertThat(state.getLastBoundaryOffsetSec()).isEqualTo(-300);
        }

        /**
         * 경계 커서와 달리 <b>공개 틱은 일차가 바뀌어도 이어진다.</b> 틱 순번이 시즌 전체를
         * 관통하는 연속 정수라 그것이 옳다 ({@code _common.md} §9 — 게임 거래일로 덮어쓰면
         * 중복은 처리하지만 누락을 감지하지 못한다).
         *
         * <p><b>그 결과 개장 직후에는 공개 틱이 어제를 가리킨다.</b> 새 일차의 첫 틱이 나오기
         * 전까지가 그 구간이고, 편성상 30초 남짓이다. 그때 이 값을 그대로 기준 시세로 쓰면
         * 어제 종가로 오늘 거래가 되므로, 읽는 쪽이 <b>틱의 일차를 함께 확인해야 한다</b> —
         * {@code OrderService.publishedTick} 이 그 판정을 한다. 이 테스트는 그 판정이 필요한
         * 이유가 여기 있다는 것을 고정한다.
         */
        @Test
        @DisplayName("공개 틱_일차가 바뀌어도_어제 마지막 틱을 그대로 가리킨다")
        void publishedTick_afterDayChange_stillPointsAtYesterday() {
            ReplayState state = paused();
            state.publish(1L);
            state.publish(2L);
            state.completeBoundary(21300);

            state.reanchor((short) 2, BigDecimal.valueOf(-300), T0, BigDecimal.ONE);
            state.completeBoundary(-300);
            state.completeBoundary(0);

            // 2일차 개장을 마쳤는데도 공개 틱은 1일차의 마지막 값이다. 경계 커서만 리셋된다.
            assertThat(state.getAnchorSeasonDay()).isEqualTo((short) 2);
            assertThat(state.getLastBoundaryOffsetSec()).isEqualTo(0);
            assertThat(state.getLastPublishedTickSeq()).isEqualTo(2L);
        }
    }
}

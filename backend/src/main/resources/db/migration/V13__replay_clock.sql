-- 불변 재생 일정(cum_offset_sec)과 가변 재생 시계(replay_state)를 분리한다.
--
-- 기존 tick_at 을 오프셋으로 자동 변환하지 않는다. 스펙(53 체크리스트)이 요구하는 검산은
-- **독립 거래일 달력**과의 대조인데 이 마이그레이션은 그것을 갖고 있지 않다. 기존 틱 목록만으로
-- 기대 그리드를 만들면 그 목록이 스스로를 검증하게 되고, 누락된 거래일이 정상 일정으로
-- 통과한다 ("월별 행 수만으로 계산하면 누락된 거래일을 정상 일정으로 오인할 수 있다").
--
-- 이 마이그레이션 이전에 game_ticks·season_days 에 행을 넣는 것은 없다. 즉 이 시점의 두 테이블은
-- 비어 있는 것이 정상이며, 행이 있다면 손으로 넣은 일정이므로 변환 근거를 사람이 확인해야 한다.
-- 그래서 조용히 변환하지 않고 **명시적으로 실패시킨다.** 변환이 필요해지면 검산한 달력과 함께
-- docs/specs/market-replay-clock-migration.sql 의 절차를 별도 버전으로 올린다.

ALTER TABLE game_ticks ADD COLUMN cum_offset_sec INTEGER;

DO $$
BEGIN
    -- 손으로 넣은 일정이 있으면 사람이 변환 근거를 확인해야 한다.
    IF EXISTS (SELECT 1 FROM game_ticks) THEN
        RAISE EXCEPTION
            'game_ticks has rows: tick_at cannot be converted without an independent trading-day calendar. '
            'Verify the original schedule and run the documented conversion as a separate migration.';
    END IF;

    -- 편성이 이미 있다면 새 고정 규칙과 어긋나지 않는지만 본다.
    IF EXISTS (
        SELECT 1 FROM season_days
        WHERE season_day NOT BETWEEN 1 AND 3
           OR from_game_month !~ '^[0-9]{4}-(0[1-9]|1[0-2])$'
           OR to_game_month !~ '^[0-9]{4}-(0[1-9]|1[0-2])$'
           OR from_game_month > to_game_month
    ) THEN
        RAISE EXCEPTION 'Invalid season day or game month range';
    END IF;

    IF EXISTS (
        SELECT 1 FROM season_days
        WHERE closes_at <> opens_at + INTERVAL '20100 seconds'
    ) THEN
        RAISE EXCEPTION 'Existing close schedule conflicts with the fixed close rule';
    END IF;
END $$;

ALTER TABLE season_days ADD CONSTRAINT ck_season_days_day CHECK (season_day BETWEEN 1 AND 3);
ALTER TABLE game_ticks
    ALTER COLUMN cum_offset_sec SET NOT NULL,
    ADD CONSTRAINT ck_game_ticks_offset CHECK (cum_offset_sec >= 0 AND cum_offset_sec < 20100),
    ADD CONSTRAINT uk_game_ticks_offset UNIQUE (season_id, season_day, cum_offset_sec),
    ADD CONSTRAINT uk_game_ticks_season_seq UNIQUE (season_id, tick_seq),
    ADD CONSTRAINT fk_game_ticks_day FOREIGN KEY (season_id, season_day)
        REFERENCES season_days (season_id, season_day);

CREATE TABLE replay_state (
    season_id BIGINT PRIMARY KEY REFERENCES seasons (id),
    anchor_season_day SMALLINT NOT NULL,
    anchor_offset_sec NUMERIC(16,6) NOT NULL,
    anchor_at TIMESTAMPTZ NOT NULL,
    -- scale을 지정하면 CHECK 전에 반올림되므로 입력 정밀도는 CHECK로 검증한다.
    speed NUMERIC NOT NULL,
    last_published_tick_seq BIGINT,
    replay_epoch BIGINT NOT NULL DEFAULT 0,
    -- 시간 경계 커서. 둘 다 NULL이면 아직 경계 처리 전이다.
    last_boundary_season_day SMALLINT,
    last_boundary_offset_sec INTEGER,
    CONSTRAINT ck_replay_anchor CHECK (anchor_offset_sec BETWEEN -300 AND 21300),
    CONSTRAINT ck_replay_speed CHECK (speed BETWEEN 0 AND 100 AND scale(speed) <= 2),
    CONSTRAINT ck_replay_epoch CHECK (replay_epoch >= 0),
    CONSTRAINT ck_replay_boundary CHECK (
        (last_boundary_season_day IS NULL AND last_boundary_offset_sec IS NULL)
        OR (last_boundary_season_day IS NOT NULL AND last_boundary_offset_sec IS NOT NULL
            AND last_boundary_offset_sec IN (-300, 0, 3300, 3900, 17700, 20100, 21300))
    ),
    CONSTRAINT fk_replay_anchor_day FOREIGN KEY (season_id, anchor_season_day)
        REFERENCES season_days (season_id, season_day),
    CONSTRAINT fk_replay_published_tick FOREIGN KEY (season_id, last_published_tick_seq)
        REFERENCES game_ticks (season_id, tick_seq),
    CONSTRAINT fk_replay_boundary_day FOREIGN KEY (season_id, last_boundary_season_day)
        REFERENCES season_days (season_id, season_day)
);

COMMENT ON COLUMN game_ticks.cum_offset_sec IS '일차 거래 시작으로부터의 불변 가상 초 오프셋';
COMMENT ON COLUMN replay_state.anchor_at IS '앵커 게임 위치에 대응하는 실제 시각. 최초 시작 예약은 미래 시각을 사용';
COMMENT ON COLUMN replay_state.last_boundary_offset_sec IS '마지막 처리 시간 경계. 체결 또는 정산 완료를 의미하지 않음';

-- 이전 스키마에는 과거 공개 완료 위치가 없다. tick_at<=현재시각으로 추정하지 않는다.
-- 따라서 기존 시즌의 replay_state는 이 마이그레이션에서 자동 생성하지 않는다.
-- 애플리케이션은 replay_state 없는 시즌을 진행하지 않고 초기화 필요 상태로 보고해야 한다.

-- 이 제거는 새 엔티티/조회/폴러를 같은 배포로 전환하는 조건이다.
ALTER TABLE game_ticks DROP COLUMN tick_at;
ALTER TABLE season_days DROP COLUMN closes_at;

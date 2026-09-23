-- 공모 편성의 세 시각을 **자기 시즌의 틱으로** 제한한다.
--
-- game_ticks 의 기본키는 tick_seq 하나다 (V7). 시즌이 둘 이상이면 순번이 겹치지 않게 나눠
-- 주어야 하는데, 그 규칙은 적재 스크립트(scripts/replay-loader) 안에만 있고 DB 는 모른다.
-- 그래서 지금까지는 "시즌 2 의 공모가 시즌 1 의 틱을 가리키는" 상태를 DB 가 허용했다 —
-- 실제로 시즌 2 첫 적재에서 그 일이 일어났고, 외래키가 단일 컬럼이라 아무 제약도 걸리지 않았다.
--
-- ipo_offerings 는 season_id 를 이미 갖고 있고 game_ticks 에는 uk_game_ticks_season_seq
-- (season_id, tick_seq) 가 이미 있으므로, 참조를 복합키로 바꾸는 것만으로 그 상태가
-- 외래키 위반이 된다. 다른 테이블은 손대지 않는다 — orders · executions · exchanges ·
-- chat_messages · notifications 에는 season_id 컬럼이 없어 같은 방법을 쓸 수 없고, 그쪽은
-- 순번 구간 분리와 적재 스크립트의 검증이 계속 맡는다.
--
-- V13 의 replay_state.fk_replay_published_tick 이 같은 방식의 선례다.
--
-- 순번 구간 분리는 **충돌을 예방**하고 이 제약은 **오참조를 차단**한다. 역할이 다르므로 함께 둔다.

ALTER TABLE ipo_offerings
    DROP CONSTRAINT ipo_offerings_disclosure_tick_seq_fkey,
    DROP CONSTRAINT ipo_offerings_close_tick_seq_fkey,
    DROP CONSTRAINT ipo_offerings_listing_tick_seq_fkey;

ALTER TABLE ipo_offerings
    ADD CONSTRAINT fk_ipo_disclosure_tick
        FOREIGN KEY (season_id, disclosure_tick_seq) REFERENCES game_ticks (season_id, tick_seq),
    ADD CONSTRAINT fk_ipo_close_tick
        FOREIGN KEY (season_id, close_tick_seq) REFERENCES game_ticks (season_id, tick_seq),
    ADD CONSTRAINT fk_ipo_listing_tick
        FOREIGN KEY (season_id, listing_tick_seq) REFERENCES game_ticks (season_id, tick_seq);

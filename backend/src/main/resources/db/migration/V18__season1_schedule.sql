-- 시즌 1 편성과 유니버스 시드 정정.
--
-- 편성값(시드·수수료율·추첨 비율·재생 구간)은 두 환경에서 같다. 운영은 편성된 개장 시각을
-- 그대로 쓰고, 테스트 모드는 일차 전환에서 그 값을 무시하고 발표 직후로 이어붙이며, 편성
-- 조회는 다음 일차만 대상으로 하므로 1일차 opens_at 은 런타임에 읽히지 않는다. 따라서 환경별로
-- 갈리지 않고 버전 마이그레이션이 소유한다.
--
-- 대량 데이터(게임 틱·일별 종가·분할 이력)와 환경별로 갈리는 replay_state 는 여기 없다 —
-- scripts/replay-loader 가 넣는다. replay_state 를 여기 두면 시드 재실행이 진행 중인 재생
-- 상태를 되돌리게 된다.

-- 충돌 시 덮어쓴다. 이 마이그레이션 이전에 시즌 1 을 만든 것은 재생 검증 픽스처뿐이고
-- (REPLAY_MODE=test 를 켠 dev·CI DB), 그 행은 이제 이 편성이 소유할 자리에 놓인 임시값이다.
-- DO NOTHING 으로 두면 그 임시 편성이 조용히 남고, 아무것도 안 하면 PK 충돌로 기동이 막힌다.
INSERT INTO seasons (id, name, start_date, end_date, seed_krw,
                     trade_fee_rate, exchange_fee_rate, ipo_winner_ratio, created_at)
VALUES (1, '마켓 리플레이 시즌 1', '2026-09-21', '2026-09-23', 100000000,
        0.15, 0.05, 30, now())
ON CONFLICT (id) DO UPDATE
    SET name = excluded.name,
        start_date = excluded.start_date,
        end_date = excluded.end_date,
        seed_krw = excluded.seed_krw,
        trade_fee_rate = excluded.trade_fee_rate,
        exchange_fee_rate = excluded.exchange_fee_rate,
        ipo_winner_ratio = excluded.ipo_winner_ratio;

-- 요율은 퍼센트 단위다 (0.15 = 0.15%). 일차별 재생 구간은 운영 내부 자료이며 플레이어에게
-- 공개하지 않는다.
INSERT INTO season_days (season_id, season_day, from_game_month, to_game_month, opens_at)
VALUES (1, 1, '2020-01', '2022-03', '2026-09-21T11:05:00+09'),
       (1, 2, '2022-04', '2024-06', '2026-09-22T11:05:00+09'),
       (1, 3, '2024-07', '2026-08', '2026-09-23T11:05:00+09')
ON CONFLICT (season_id, season_day) DO UPDATE
    SET from_game_month = excluded.from_game_month,
        to_game_month = excluded.to_game_month,
        opens_at = excluded.opens_at;

-- id 를 직접 지정했으므로 IDENTITY 시퀀스가 따라오지 않는다. 시즌 2 삽입이 1 에서 충돌한다.
SELECT setval(pg_get_serial_sequence('seasons', 'id'), (SELECT max(id) FROM seasons));

-- SNQX 는 조정 종가 최저가가 0.057704 USD 다. price_scale=2 로 반올림하면 0.06 이 되어 1틱이
-- 가격의 17% 가 된다 — 등락을 읽을 수 없다. 원본 데이터가 기록한 값은 4 다. 시즌과 무관한
-- 유니버스 시드(V11)의 오류이지만 적용된 마이그레이션은 체크섬 때문에 고칠 수 없다.
UPDATE instruments SET price_scale = 4 WHERE symbol = 'SNQX';

-- app.replay.fixture.enabled=true 전용. 실제 시장 가격이 아닌 동작 검증 데이터다.
--
-- 시즌 1 과 일차 3건은 V18 이 편성한다 — 여기서 다시 넣지 않는다. 이 픽스처가 채우는 것은
-- 그 편성 위에 올라가는 검증용 틱·종목·종가와 재생 상태다.
DO $$
BEGIN
    -- 시즌 이름으로 점유를 판별하지 않는다 (V18 가 시즌 1 을 실제 이름으로 소유한다).
    -- 격리 여부는 실제 시세가 적재됐는지로 본다 — 픽스처는 REPLAY_TEST_* 종가만 쓰기 때문에,
    -- 그 밖의 종가가 있으면 여기는 로더가 다녀간 DB 다.
    IF EXISTS (SELECT 1 FROM seasons WHERE id <> 1)
        OR EXISTS (SELECT 1
                   FROM daily_closes dc
                            JOIN instruments i ON i.id = dc.instrument_id
                   WHERE i.symbol NOT LIKE 'REPLAY\_TEST\_%') THEN
        RAISE EXCEPTION 'Replay fixture requires an isolated test database';
    END IF;
END $$;

INSERT INTO game_ticks (tick_seq, season_id, season_day, game_date, cum_offset_sec, usd_krw)
VALUES (1,1,1,'2020-01-02',32,1200), (2,1,1,'2020-01-03',65,1210),
       (3,1,1,'2020-01-06',99,1190), (4,1,1,'2022-03-31',19440,1200),
       (5,1,2,'2022-04-01',31,1210), (6,1,2,'2022-04-04',65,1200),
       (7,1,2,'2022-04-05',98,1190), (8,1,2,'2024-06-28',19440,1200),
       (9,1,3,'2024-07-01',33,1210), (10,1,3,'2024-07-02',67,1220),
       (11,1,3,'2024-07-03',100,1210), (12,1,3,'2026-08-31',18720,1200)
ON CONFLICT DO NOTHING;

-- season_id 를 명시한다. V40 이 유니크를 (season_id, symbol) 로 바꿔서 ON CONFLICT 의 추론
-- 대상도 그 쌍이어야 하고, 추론 대상에 쓰는 컬럼은 값도 함께 적어 두는 편이 읽기 쉽다.
-- 픽스처는 언제나 시즌 1 이다 - 아래 게임 틱도 season_id=1 로 넣는다.
INSERT INTO instruments (season_id,symbol,name,category,asset_type,currency,price_scale,listed_game_date,summary)
VALUES (1,'REPLAY_TEST_A','검증 종목 A','KRX','STOCK','KRW',0,NULL,'실제 시장 데이터가 아닌 재생 검증용 종목'),
       (1,'REPLAY_TEST_B','검증 종목 B','KRX','STOCK','KRW',0,NULL,'실제 시장 데이터가 아닌 재생 검증용 종목'),
       -- USD 종목이 하나 있어야 usd_krw 환산과 priceScale 2 자리 경로가 검증된다.
       (1,'REPLAY_TEST_C','검증 종목 C','NASDAQ','STOCK','USD',2,NULL,'실제 시장 데이터가 아닌 재생 검증용 종목'),
       -- 상장일이 있는 IPO 종목. 상장 필터가 검증되려면 이것이 있어야 한다 - 유니버스는 IPO 를
       -- 따라 늘어나므로 상장 전에는 빠지고 상장일부터 나타나야 한다.
       (1,'REPLAY_TEST_D','검증 종목 D','KRX','IPO_STOCK','KRW',0,DATE '2020-01-03',
        '실제 시장 데이터가 아닌 재생 검증용 상장 예정 종목')
ON CONFLICT (season_id, symbol) DO NOTHING;

INSERT INTO daily_closes (instrument_id,game_date,close_price,raw_close)
SELECT id, '2019-12-30'::date, 10000, 10000 FROM instruments
WHERE symbol IN ('REPLAY_TEST_A','REPLAY_TEST_B','REPLAY_TEST_C','REPLAY_TEST_D')
ON CONFLICT DO NOTHING;
-- 검증 종목 D 는 상장일(2020-01-03) 이전 날짜에도 종가를 갖는다. 실제 IPO 종목에는 없는 상태이며
-- 일부러 그렇게 둔다 - 그래야 상장 전에 응답에서 빠지는 것이 종가 부재가 아니라 상장 필터의
-- 결과임이 드러나고, 종가 시계열의 시작이 상장일로 잘리는 것도 검증된다.
INSERT INTO daily_closes (instrument_id,game_date,close_price,raw_close)
SELECT i.id,t.game_date,10000 + (CASE WHEN t.tick_seq % 2 = 0 THEN -1 ELSE 1 END)*t.tick_seq*100,
       10000 + (CASE WHEN t.tick_seq % 2 = 0 THEN -1 ELSE 1 END)*t.tick_seq*100
FROM instruments i CROSS JOIN game_ticks t
WHERE i.symbol IN ('REPLAY_TEST_A','REPLAY_TEST_B','REPLAY_TEST_C','REPLAY_TEST_D') AND t.season_id=1
  AND NOT (i.symbol='REPLAY_TEST_B' AND t.tick_seq=2)
ON CONFLICT DO NOTHING;

-- 새 시즌에서만 생성된다. 다시 기동해도 앵커·진행 위치는 보존된다.
INSERT INTO replay_state (season_id,anchor_season_day,anchor_offset_sec,anchor_at,speed)
VALUES (1,1,-300,CURRENT_TIMESTAMP,0) ON CONFLICT DO NOTHING;

-- 일차 경계를 넘는 공모 하나(EIDO 에이도스게임즈)를 3일차 안으로 옮긴다.
--
-- **문제.** 이 공모만 마감·추첨이 2일차, 상장이 3일차였다. 당첨 배정액은 추첨에서 풀리지
-- 않고 **상장까지 잠긴 채로** 남는다 (IpoDrawService: "상장 시점에 포지션으로 바뀌기
-- 때문이다"). 일차 경계에서 잔고를 시드로 되돌리면 그 잠금의 근거가 사라지고, 3일차 상장
-- 전환이 초기화된 1 억에서 어제의 배정액을 빼거나 unlock 에서 터진다.
--
-- **왜 옮기는 쪽인가.** 초기화 코드에 "경계를 넘는 청약" 예외 경로를 만드는 것보다, 공모
-- 편성이 일차 안에서 닫히게 하는 쪽이 규칙이 하나다. 시즌 2 의 나머지 공모 열일곱 건은
-- 이미 전부 한 일차 안에서 공시·마감·상장이 끝난다.
--
-- **상장 틱 선택.** 종목의 시세가 2024-07-11 부터라 그 앞으로는 옮길 수 없다. 3일차 30 번째
-- 틱(2024-08-09, 종가 71,600)을 고른 이유는 공모가 60,000 대비 +19.3% 로, 원래 상장일
-- (2024-07-11, 71,000 · +18.3%) 의 프리미엄과 거의 같기 때문이다 — 공모가를 손대지 않고
-- 상장 첫날의 체감을 그대로 유지한다.
--
-- **아직 추첨되지 않은 공모만 옮긴다.** 이미 추첨된 뒤라면 배정 결과가 편성과 어긋나므로
-- 옮기지 않고 그대로 두는 편이 안전하다 (그때는 코드 쪽 예외 처리가 필요하다는 신호다).
WITH day3 AS (SELECT tick_seq, row_number() OVER (ORDER BY tick_seq) AS n
              FROM game_ticks
              WHERE season_id = 2
                AND season_day = 3)
UPDATE ipo_offerings o
SET disclosure_tick_seq = (SELECT tick_seq FROM day3 WHERE n = 1),
    close_tick_seq      = (SELECT tick_seq FROM day3 WHERE n = 13),
    listing_tick_seq    = (SELECT tick_seq FROM day3 WHERE n = 30)
WHERE o.season_id = 2
  AND o.drawn_at IS NULL
  AND o.instrument_id = (SELECT id FROM instruments WHERE season_id = 2 AND symbol = 'EIDO');

-- 상장일은 종목 쪽에도 있다. 시장 화면과 상장 알림이 이 컬럼으로 노출 시점을 정하므로
-- (Instrument.isListedOn · NotificationPublishListener) 함께 옮기지 않으면 공모는 3일차인데
-- 종목만 2024-07-11 부터 보인다.
UPDATE instruments i
SET listed_game_date = (SELECT t.game_date
                        FROM ipo_offerings o
                                 JOIN game_ticks t
                                      ON t.season_id = o.season_id AND t.tick_seq = o.listing_tick_seq
                        WHERE o.instrument_id = i.id)
WHERE i.season_id = 2
  AND i.symbol = 'EIDO'
  AND EXISTS (SELECT 1 FROM ipo_offerings o WHERE o.instrument_id = i.id AND o.drawn_at IS NULL);

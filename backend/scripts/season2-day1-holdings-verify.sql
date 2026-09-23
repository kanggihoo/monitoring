-- 시즌 2 · 1일차 마감 보유 스냅샷 대조 (백필 전후에 모두 돌린다).
--
-- 아무것도 바꾸지 않는다. 배포 전·후, 그리고 2일차 개장 전에 같은 값을 보기 위한 것이다.
--
--   psql "$DATABASE_URL" -f season2-day1-holdings-verify.sql
\set season 2
\set day 1

\echo '=== 1) 평가 기준 틱'
SELECT tick_seq, game_date, usd_krw
FROM game_ticks
WHERE season_id = :season AND season_day = :day
ORDER BY tick_seq DESC
LIMIT 1;

\echo '=== 2) 대상 규모 (정산 / 보유 / 스냅샷 / 표식)'
SELECT (SELECT count(*) FROM daily_settlements d JOIN accounts a ON a.id = d.account_id
        WHERE a.season_id = :season AND d.season_day = :day)                       AS settlements,
       (SELECT count(*) FROM positions p JOIN accounts a ON a.id = p.account_id
        WHERE a.season_id = :season AND p.quantity > 0)                            AS positions_held,
       (SELECT count(*) FROM daily_holdings h JOIN accounts a ON a.id = h.account_id
        WHERE a.season_id = :season AND h.season_day = :day)                       AS holdings_rows,
       (SELECT count(*) FROM daily_settlements d JOIN accounts a ON a.id = d.account_id
        WHERE a.season_id = :season AND d.season_day = :day
          AND d.holdings_snapshotted_at IS NOT NULL)                               AS marked;

\echo '=== 3) 확정 평가액 대조 — 0 행이어야 한다 (행별 반올림 오차 0.01 원까지 허용)'
WITH tick AS (SELECT usd_krw AS rate
              FROM game_ticks
              WHERE season_id = :season AND season_day = :day
              ORDER BY tick_seq DESC LIMIT 1)
SELECT d.account_id,
       d.total_valuation_krw                                                        AS settled,
       round(a.krw_cash + a.usd_cash * tick.rate
                 + coalesce((SELECT sum(h.valuation_krw) FROM daily_holdings h
                             WHERE h.season_day = d.season_day AND h.account_id = d.account_id), 0), 4)
                                                                                    AS recomputed
FROM daily_settlements d
         JOIN accounts a ON a.id = d.account_id
         CROSS JOIN tick
WHERE a.season_id = :season
  AND d.season_day = :day
  AND abs(round(a.krw_cash + a.usd_cash * tick.rate
                    + coalesce((SELECT sum(h.valuation_krw) FROM daily_holdings h
                                WHERE h.season_day = d.season_day AND h.account_id = d.account_id), 0), 4)
              - d.total_valuation_krw) > 0.01;

\echo '=== 4) 수량·원가 대조 (positions vs daily_holdings) — 0 행이어야 한다'
SELECT coalesce(p.account_id, h.account_id)     AS account_id,
       coalesce(p.instrument_id, h.instrument_id) AS instrument_id,
       p.quantity AS position_qty, h.quantity AS holding_qty,
       p.total_cost AS position_cost, h.total_cost AS holding_cost
FROM (SELECT p.* FROM positions p JOIN accounts a ON a.id = p.account_id
      WHERE a.season_id = :season AND p.quantity > 0) p
         FULL JOIN (SELECT h.* FROM daily_holdings h JOIN accounts a ON a.id = h.account_id
                    WHERE a.season_id = :season AND h.season_day = :day) h
                   ON h.account_id = p.account_id AND h.instrument_id = p.instrument_id
WHERE p.account_id IS NULL
   OR h.account_id IS NULL
   OR p.quantity <> h.quantity
   OR p.total_cost <> h.total_cost;

\echo '=== 5) 기록 없이 초기화될 계좌 — 0 행이어야 한다'
SELECT DISTINCT p.account_id
FROM positions p
         JOIN accounts a ON a.id = p.account_id
WHERE a.season_id = :season
  AND p.quantity > 0
  AND NOT EXISTS (SELECT 1 FROM daily_settlements d
                  WHERE d.season_day = :day AND d.account_id = p.account_id);

\echo '=== 6) 초기화 준비 상태 (개장 전 확인)'
SELECT (SELECT count(*) FROM accounts WHERE season_id = :season)                    AS accounts,
       (SELECT count(*) FROM accounts WHERE season_id = :season
          AND daily_base_season_day <= :day)                                        AS pending_reset,
       (SELECT count(*) FROM orders o JOIN accounts a ON a.id = o.account_id
        WHERE a.season_id = :season AND o.status = 'OPEN')                          AS open_orders,
       (SELECT count(*) FROM accounts WHERE season_id = :season
          AND (locked_krw <> 0 OR locked_usd <> 0))                                 AS locked_accounts;

-- k6 부하용 데이터. 로그인 패스가 계좌를 만든 뒤 실행한다.
-- 알림: 계좌당 500건 (총 계좌수 x 500), 80% 읽음. 종류는 돌려 넣는다.
INSERT INTO notifications (account_id, kind, title, body, reference_id, tick_seq, read_at, created_at)
SELECT a.id,
       (ARRAY['ORDER_FILLED','ORDER_EXPIRED','IPO_RESULT','AWARD','NOTICE'])[1 + (g % 5)],
       'k6 알림 ' || g, 'k6 부하 테스트용 본문 ' || g, g, NULL,
       CASE WHEN g % 5 = 0 THEN NULL ELSE now() - (g || ' seconds')::interval END,
       now() - (g || ' seconds')::interval
FROM accounts a CROSS JOIN generate_series(1, 500) g;

-- 주문: 계좌당 닫힌 주문 200건(FILLED) + 짝수 계좌에 OPEN 1건. 매 틱 스윕은 OPEN 만 찾는다.
INSERT INTO orders (account_id, instrument_id, side, limit_price, quantity, currency, locked_amount,
                    locked_quantity, status, expires_at, created_tick_seq, created_at, closed_at)
SELECT a.id, (SELECT id FROM instruments WHERE symbol = 'REPLAY_TEST_A'),
       'BUY', 10000, 1, 'KRW', NULL, NULL, 'FILLED', now(), 1, now() - (g || ' seconds')::interval, now()
FROM accounts a CROSS JOIN generate_series(1, 200) g;

INSERT INTO orders (account_id, instrument_id, side, limit_price, quantity, currency, locked_amount,
                    locked_quantity, status, expires_at, created_tick_seq, created_at, closed_at)
SELECT a.id, (SELECT id FROM instruments WHERE symbol = 'REPLAY_TEST_A'),
       'BUY', 9000, 1, 'KRW', 9000, NULL, 'OPEN', now() + interval '1 day', 1, now(), NULL
FROM accounts a WHERE a.id % 2 = 0;

ANALYZE notifications; ANALYZE orders;
SELECT 'notifications' t, count(*) FROM notifications UNION ALL SELECT 'orders', count(*) FROM orders
UNION ALL SELECT 'orders OPEN', count(*) FROM orders WHERE status = 'OPEN' UNION ALL SELECT 'accounts', count(*) FROM accounts;

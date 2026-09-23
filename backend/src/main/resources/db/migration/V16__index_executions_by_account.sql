-- 체결 내역은 언제나 "한 계좌의" 것으로 읽힌다. PK 만 있어 계좌별 조회가 풀스캔이었고,
-- 소비자가 셋이다:
--   * 복기 리포트 — 폐장에서 공개까지 20분 안에 100명분을 돌려야 한다 (ai/docs/architecture/backend-data-contract.md 6.6)
--   * 리더보드의 tradeCount — executions x game_ticks 를 tick_seq 로 조인하고 account_id IN 으로 거른다
--   * 체결 내역 조회 (GET /api/v1/executions) — 최신순 페이지
-- tick_seq 를 두 번째 열로 두는 이유는 체결이 게임 시각을 틱으로만 갖고 있어서다. 일차 필터도
-- game_ticks 를 거쳐 tick_seq 범위가 되고, 최신순 정렬도 이 열로 풀린다.
CREATE INDEX ix_executions_account_tick ON executions (account_id, tick_seq);

COMMENT ON INDEX ix_executions_account_tick IS '계좌별 체결 조회·집계. 복기 리포트·리더보드 tradeCount·체결 내역이 함께 쓴다';

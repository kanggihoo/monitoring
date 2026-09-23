-- 매 틱 · 매 화면마다 도는데 인덱스가 없어 표를 전부 훑던 조회 둘 (S15P21A605-160).
--
-- 둘 다 행이 시즌 내 누적되는 표다. 지금은 적어 티가 안 나지만, 조회는 행 수와 무관하게
-- 같은 빈도로 돌고 비용만 행 수에 비례해 커진다.
--
-- 1. orders — 체결 판정이 매 틱 findByStatus(OPEN) 으로 시작하고 폐장 만기도 같은 질의다.
--    기존 인덱스 ix_orders_account_status 는 account_id 가 선행 컬럼이라 status 만으로는 좁히지
--    못한다 — 플래너가 그 인덱스를 쓰기는 하는데 (Bitmap Index Scan) 선행 컬럼이 없어 엔트리
--    전부를 읽고 status 로 거른다. Seq Scan 은 아니지만 비용이 전체 주문 수에 비례한다
--    (k6 검증에서 40,100 행일 때 추정 비용 461 vs 새 인덱스 14.6, S15P21A605-160 보고서).
--    행은 지우지 않는다 (orders.status 주석) — FILLED · CANCELLED · EXPIRED 가 전부 남는다.
--
--    부분 인덱스로 둔다. OPEN 은 언제나 소수(당일 미체결분)라 인덱스가 작고, 예약이 닫히는
--    순간 인덱스에서 빠져 스스로 정리된다. status 전체 인덱스는 다시 읽을 일 없는 FILLED
--    수천 행을 실어 나른다. 지금 규모에서는 0.3 ms → 0.07 ms 정도라 체감이 없고, 값은 행이
--    늘어도 비용이 OPEN 건수에만 비례한다는 데 있다 — 보험이지 즉효약이 아니다.
--
-- 2. notifications — 목록 · 미읽음 배지 · 읽음 처리 셋이 전부 account_id 로 거르는데 PK 외
--    인덱스가 없다. 행은 체결 · 만기 · 수상마다 전원분이 쌓이고, 특히 만기는 폐장마다
--    미체결 하나에 한 건이라 참가자 × 일차만큼 확실히 늘어난다. 배지는 자주 불린다.
--
--    컬럼 순서는 목록의 정렬 그대로다 (NotificationController.LATEST_FIRST: created_at DESC,
--    id DESC). 그래야 계좌로 좁힌 뒤 정렬까지 인덱스가 먹고 Sort 노드가 사라진다. 미읽음
--    카운트는 계좌로 좁힌 뒤 read_at IS NULL 필터라 이 인덱스로 충분하다 — 미읽음 전용
--    부분 인덱스까지 두는 것은 한 계좌의 행 수 대비 과하다.
--
-- CONCURRENTLY 는 붙이지 않는다. Flyway 가 트랜잭션 안에서 실행해 쓸 수 없고, 두 표 다
-- 잠금 시간이 문제될 크기가 아니다.

CREATE INDEX ix_orders_open ON orders (status) WHERE status = 'OPEN';

COMMENT ON INDEX ix_orders_open IS '매 틱 체결 판정 · 폐장 만기의 진입점. OPEN 만 실은 부분 인덱스라 예약이 닫히면 스스로 빠진다';

CREATE INDEX ix_notifications_account_latest ON notifications (account_id, created_at DESC, id DESC);

COMMENT ON INDEX ix_notifications_account_latest IS '알림 목록(최신순) · 미읽음 배지 · 읽음 처리용. 컬럼 순서가 목록 정렬과 같아 Sort 없이 읽는다';

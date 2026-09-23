-- 계좌별 환전 이력 조회. V14 가 orders 에 대해 한 것과 같은 이유다 — V7 이 "인덱스는
-- PK·UNIQUE 외에 잡지 않았다. 조회 패턴이 확정되면 별도 버전으로 추가한다"고 했고,
-- 환전 이력 API 가 exchanges 의 첫 조회 패턴을 확정했다.
--
-- 정렬까지 인덱스에 담는 이유는 이 목록이 최신순 고정이기 때문이다. 정렬 방향을 담지 않으면
-- 페이지마다 정렬이 따로 돌고, 환전은 지우지 않는 기록이라 시즌이 갈수록 그 비용이 자란다.
-- id 를 함께 넣는 것은 같은 순간에 만들어진 두 행의 순서를 고정하기 위해서다 — 흔들리면
-- 페이지 경계에서 행이 겹치거나 빠진다.
CREATE INDEX ix_exchanges_account_time ON exchanges (account_id, exchanged_at DESC, id DESC);

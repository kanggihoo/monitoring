-- 잠금·취득 총액의 규약을 DB 로 옮긴다.
--
-- V7 이 이 규칙들을 COMMENT 로만 적어 두었다. 규약이 주석에만 있으면 그것을 읽는 코드가
-- 방어 코드를 쓰거나(값이 없을 수 있다고 가정) 쓰지 않는다(가정하고 터진다). 둘 다 나쁘므로
-- 제약으로 만들어 읽는 쪽이 가정할 수 있게 한다.
--
-- 인덱스도 함께 잡는다. V7 이 "인덱스는 PK·UNIQUE 외에 잡지 않았다. 조회 패턴이 확정되면
-- 별도 버전으로 추가한다"고 했고, 계좌 포지션 조회가 orders 의 첫 조회 패턴을 확정했다.


-- 미체결(OPEN) 예약은 방향에 맞는 잠금 컬럼이 반드시 채워져 있다.
--
-- 매수는 현금을, 매도는 보유 수량을 잠근다 (기획서 6.2.2~3). 그래서 컬럼이 둘로 나뉘어
-- 있고 각 행은 하나만 쓴다. 이 제약이 없으면 잠긴 수량을 더하는 조회가 NULL 을 만나
-- 계좌 화면 전체를 죽인다 — 잠금이 비어 있는 미체결 예약은 그 자체가 정합성 사고이므로
-- 조회가 방어할 것이 아니라 애초에 저장되지 않아야 한다.
--
-- 상태를 OPEN 으로 한정하는 이유: 체결·취소·만료된 행의 잠금 컬럼을 비울지 기록으로
-- 남길지는 아직 정하지 않았다. 살아 있는 잠금만 규정하고 나머지는 열어 둔다.
ALTER TABLE orders
    ADD CONSTRAINT ck_orders_open_locks
        CHECK (status <> 'OPEN'
            OR ((side = 'BUY') = (locked_amount IS NOT NULL)
                AND (side = 'SELL') = (locked_quantity IS NOT NULL)));


-- 계좌별 예약 조회. 계좌 포지션 응답이 잠긴 수량을 종목별로 더하려고 매 틱 호출하고,
-- ord.md 의 미체결 예약 목록도 같은 세 컬럼으로 좁힌다. orders 는 행을 지우지 않는
-- 테이블이라(취소·만료도 남긴다) 시즌이 갈수록 풀스캔 비용이 늘어난다.
CREATE INDEX ix_orders_account_status ON orders (account_id, status, side);


-- 보유 수량과 취득 총액은 함께 0 이 되고, 둘 다 음수가 될 수 없다.
--
-- 수량이 0 이 되면 취득 총액도 0 으로 강제하는 것이 평단 오차 누적을 끊는 규칙이고
-- (_common.md 6.3), 그 역도 성립해야 한다 — 취득 총액이 0 인데 수량이 남아 있으면
-- 평단(취득 총액 / 수량)과 수익률(손익 / 취득 총액)의 분모가 0 이 된다.
-- 공매도가 없으므로 수량은 음수가 될 수 없다 (기획서 6.5.3).
ALTER TABLE positions
    ADD CONSTRAINT ck_positions_cost_matches_quantity
        CHECK (quantity >= 0 AND total_cost >= 0 AND (quantity = 0) = (total_cost = 0));

-- 일차 마감 보유 스냅샷 (시즌 2 일차 초기화).
--
-- **왜 필요한가.** 일차 경계에서 보유 종목을 지우고 현금을 시드로 되돌리기로 했다. 지금은
-- 그 일차에 무엇을 들고 폐장했는지가 `positions` 한 곳에만 있고, 그 행은 다음 날 사라진다.
-- `executions` 로 되짚을 수도 없다 — 초기화는 매도 체결을 만들지 않으므로 체결 내역의 합과
-- 실제 보유가 일차 경계에서 끊긴다. 지우기 전에 찍어 두지 않으면 영구 소실이다.
--
-- **평가액을 함께 저장하는 이유.** 수량과 원가만 남기면 나중에 "그날 얼마였나"를 다시
-- 계산해야 하는데, 그 계산에 필요한 그 일차 마지막 틱의 종가·환율이 시즌마다 바뀐다.
-- 확정값을 그대로 박아 두는 것은 daily_settlements 가 순위를 다루는 방식과 같다.
--
-- **daily_settlements 를 복합 FK 로 참조한다.** 스냅샷은 폐장 정산과 같은 트랜잭션에서
-- 만들어지므로 부모 행이 항상 먼저 있다. 정산이 실패한 날은 스냅샷도 없고, 그 사실이
-- 초기화를 막는 표식이 된다.
CREATE TABLE daily_holdings
(
    season_day    smallint      NOT NULL,
    account_id    bigint        NOT NULL,
    instrument_id bigint        NOT NULL,
    quantity      integer       NOT NULL,
    total_cost    numeric(38, 4) NOT NULL,
    valuation_krw numeric(38, 4) NOT NULL,

    PRIMARY KEY (season_day, account_id, instrument_id),
    CONSTRAINT fk_daily_holdings_settlement FOREIGN KEY (season_day, account_id)
        REFERENCES daily_settlements (season_day, account_id),
    CONSTRAINT fk_daily_holdings_instrument FOREIGN KEY (instrument_id)
        REFERENCES instruments (id),
    -- 수량 0 포지션은 복사하지 않는다. 평가액에 아무것도 더하지 않고, 원가도 0 이라
    -- 남겨도 읽는 쪽이 걸러야 할 행만 늘어난다.
    CONSTRAINT ck_daily_holdings_quantity CHECK (quantity > 0),
    CONSTRAINT ck_daily_holdings_cost CHECK (total_cost >= 0),
    CONSTRAINT ck_daily_holdings_valuation CHECK (valuation_krw >= 0)
);

-- 계좌별 조회와 탈퇴 삭제가 쓴다. PK 선두가 season_day 라 계좌만으로는 색인을 못 탄다.
CREATE INDEX idx_daily_holdings_account ON daily_holdings (account_id);

COMMENT ON TABLE daily_holdings IS
    '일차 폐장 시점의 보유 종목 스냅샷. 다음 일차 개장에 positions 가 초기화되므로 지우기 전에 남긴다';
COMMENT ON COLUMN daily_holdings.total_cost IS
    '마감 보유 취득 총액. positions.total_cost 를 그대로 옮긴 값이라 **종목의 결제 통화 기준**이다 (원화 환산이 아니다)';
COMMENT ON COLUMN daily_holdings.valuation_krw IS
    '그 일차 마지막 공개 틱의 종가와 환율로 평가한 원화 금액';

-- 마감 현금과 평가 기준 틱.
--
-- **초기화가 지우는 값이라 여기 남긴다.** 다음 날 현금은 시드로 덮이므로, 저장해 두지 않으면
-- "정산 평가액 = 현금 + 보유 평가액" 이라는 대조식을 사후에 세울 수 없다. 이 넷이 있으면
-- 초기화 후에도 그날의 정산값을 다시 검증할 수 있다.
--
-- **전부 nullable 이다.** 시즌 1 과 시즌 2 의 1일차는 이 컬럼이 생기기 전에 확정됐다. 0 을
-- 채우면 "현금이 없었다"로 읽히므로 비워 둔다 — V38 이 max_drawdown 에 쓴 판단과 같다.
--
-- `holdings_snapshotted_at` 은 **보유 0 계좌와 스냅샷 실패 계좌를 가르는 유일한 표식**이다.
-- 보유가 없으면 daily_holdings 에 정상적으로 0 행이므로, "행이 있는가" 로는 판정할 수 없다.
ALTER TABLE daily_settlements
    ADD COLUMN closing_krw_cash        numeric(38, 4),
    ADD COLUMN closing_usd_cash        numeric(38, 4),
    ADD COLUMN closing_tick_seq        bigint,
    ADD COLUMN holdings_snapshotted_at timestamptz;

-- closing_tick_seq 에 FK 를 걸지 않는다. game_ticks 의 PK 가 (season_id, tick_seq) 인데
-- daily_settlements 에는 season_id 가 없다 — 시즌은 계좌를 통해서만 결정된다.
COMMENT ON COLUMN daily_settlements.closing_krw_cash IS
    '폐장 시점의 원화 현금(잠긴 몫 포함). 이 컬럼이 생기기 전의 행은 NULL';
COMMENT ON COLUMN daily_settlements.closing_usd_cash IS
    '폐장 시점의 달러 현금(잠긴 몫 포함). 이 컬럼이 생기기 전의 행은 NULL';
COMMENT ON COLUMN daily_settlements.closing_tick_seq IS
    '평가에 쓴 그 일차 마지막 공개 틱. 종가와 환율의 출처다. 이 컬럼이 생기기 전의 행은 NULL';
COMMENT ON COLUMN daily_settlements.holdings_snapshotted_at IS
    '마감 보유 스냅샷을 저장한 시각. NULL 이면 스냅샷이 없다는 뜻이고, 그 일차는 초기화 대상이 아니다';

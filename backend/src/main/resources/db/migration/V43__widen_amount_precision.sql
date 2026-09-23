-- 자산을 따라 커지는 금액 컬럼의 상한을 넓힌다.
--
-- **관측값.** 시즌 2 1일차에 1 위 계좌가 시드 1 억을 6 조 5,900 억(65,900 배)으로 불렸다.
-- 실제 주가 6 년을 3 일로 압축 재생하는 구조라 게임 하루가 실제 34 초이고, 미래를 아는
-- 참가자에게 손해 보는 틱이 사실상 없다. NUMERIC(20, 4) 의 상한은 약 1 경 원이므로 1일차
-- 마감 기준으로 남은 여유가 1,517 배뿐이다.
--
-- **넘으면 한 계좌가 아니라 그날 전체가 사라진다.** 폐장 확정은 전 계좌를 한 트랜잭션에
-- 저장하고 (DailySettlementService, 멱등 판정이 "그 일차 행이 하나라도 있으면 완료" 라
-- 쪼갤 수 없다) 한 행의 `numeric field overflow` 가 그날 정산 전체를 롤백시킨다. 그러면
-- 참가자 전원의 일일 랭킹·리포트가 빠지고, 다음 날 기준값 갱신까지 막힌다.
--
-- **일차 초기화가 이것을 대신하지 않는다.** 초기화는 다음 날 출발 규모를 1 억으로 되돌릴
-- 뿐, 하루 안의 폭증을 막지 않는다. 1일차 하나에서 이미 65,900 배가 났다.
--
-- **자릿수는 정수부 34 자리(NUMERIC(38, 4))로 통일한다.** 1 경(1e16)에서 1e34 로 18 자리를
-- 더 쓰는 것이고, 하루 최대 배수가 1e5 수준이라 세 일차를 곱해도 닿지 않는다.
ALTER TABLE accounts
    ALTER COLUMN krw_cash                 TYPE numeric(38, 4),
    ALTER COLUMN usd_cash                 TYPE numeric(38, 4),
    ALTER COLUMN locked_krw               TYPE numeric(38, 4),
    ALTER COLUMN locked_usd               TYPE numeric(38, 4),
    ALTER COLUMN starting_balance_krw     TYPE numeric(38, 4),
    ALTER COLUMN daily_base_valuation_krw TYPE numeric(38, 4),
    ALTER COLUMN peak_valuation_krw       TYPE numeric(38, 4);

ALTER TABLE positions
    ALTER COLUMN total_cost TYPE numeric(38, 4);

ALTER TABLE orders
    ALTER COLUMN locked_amount TYPE numeric(38, 4);

ALTER TABLE executions
    ALTER COLUMN amount       TYPE numeric(38, 4),
    ALTER COLUMN fee          TYPE numeric(38, 4),
    ALTER COLUMN realized_pnl TYPE numeric(38, 4);

ALTER TABLE exchanges
    ALTER COLUMN send_amount    TYPE numeric(38, 4),
    ALTER COLUMN receive_amount TYPE numeric(38, 4),
    ALTER COLUMN fee            TYPE numeric(38, 4);

ALTER TABLE daily_settlements
    ALTER COLUMN total_valuation_krw TYPE numeric(38, 4),
    ALTER COLUMN total_fee_krw       TYPE numeric(38, 4),
    ALTER COLUMN realized_pnl_krw    TYPE numeric(38, 4);

ALTER TABLE season_reports
    ALTER COLUMN total_fee_krw TYPE numeric(38, 4);

-- 수익률도 함께 넓힌다. 금액만 넓히면 이번에는 수익률 저장에서 같은 정산이 실패한다.
-- V35 가 잡은 NUMERIC(15, 4) 는 자산 상한이 1 경이던 시절의 역산값(1e11 %)이고, 자산이
-- 1e34 로 올라가면 시드 1 억 대비 수익률은 1e28 % 까지 나온다.
ALTER TABLE daily_settlements
    ALTER COLUMN season_return TYPE numeric(38, 4),
    ALTER COLUMN daily_return  TYPE numeric(38, 4);

-- **넓히지 않는 것.**
--   * 가격·환율(NUMERIC(20, 8)) — 실제 시세라 자산과 함께 커지지 않는다.
--   * 수수료율·승률·낙폭(NUMERIC(9, 4)) — 값 자체가 [-100, 100] 안에 갇혀 있다.
--   * seasons.seed_krw, ipo_offerings/ipo_subscriptions 금액 — 편성값과 그 파생이라 상한이 있다.
--   * positions/orders/executions.quantity(integer) — 상한이 21.47 억 주이고 요청 DTO 가
--     Integer 라 초과가 거부된다. 넓히면 DTO·계산·화면까지 번지는 별도 변경이다.
--   * season_reports.final_return / turnover — 시즌 최종 성적의 정의를 바꾸는 작업과 함께
--     다룬다. 지금 넓히면 의미가 바뀌기 전의 자릿수를 고정하게 된다.
COMMENT ON COLUMN daily_settlements.season_return IS
    '확정 시점 시즌 누적 수익률(%). 자릿수는 자산 상한(NUMERIC(38,4))을 시드로 나눈 값보다 크게 잡는다';

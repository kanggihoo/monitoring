-- 수익률 컬럼의 상한을 넓힌다.
--
-- V7 은 수익률을 NUMERIC(9, 4) 로 잡았다. 단위가 %(소수 2자리 표기)라 담을 수 있는 최댓값이
-- +99,999.9999% 이고, 그 위는 저장이 아니라 `numeric field overflow` 예외다.
--
-- **베타 1일차에 이미 +48,250.78% 가 나왔다.** 2020-04 알트코인 바닥에 전액을 넣고
-- 2021 년에 판 계좌이고, 유니버스에는 그런 구간이 더 있다 (2일차 최대 34배, 3일차 43배).
-- 1위가 2.07 배만 더 불리면 상한을 넘는다.
--
-- 넘으면 그 한 계좌만 실패하지 않는다. 폐장 확정은 전 계좌를 한 트랜잭션에 저장하므로
-- (DailySettlementService, 멱등 판정이 "그 일차 행이 하나라도 있으면 완료" 라 쪼갤 수 없다)
-- 한 행의 오버플로가 그날 정산 전체를 롤백시키고, 다음 날 일간 기준값 갱신까지 막는다.
--
-- **자릿수는 자산 상한에서 거꾸로 잡았다.** 평가액 컬럼이 NUMERIC(20, 4) 이므로 표현 가능한
-- 최대 자산은 약 1경 원이고, 시드 1억 기준 수익률로는 1e10 % 다. NUMERIC(15, 4) 는 1e11 %
-- 까지 담아 그보다 한 자리 크다 — 자산이 먼저 막히고 수익률이 뒤따르지 않는다.
ALTER TABLE daily_settlements
    ALTER COLUMN season_return TYPE NUMERIC(15, 4),
    ALTER COLUMN daily_return  TYPE NUMERIC(15, 4);

ALTER TABLE season_reports
    ALTER COLUMN final_return TYPE NUMERIC(15, 4);

-- 낙폭·승률은 넓히지 않는다. 낙폭은 [-100, 0], 승률은 [0, 100] 으로 값 자체가 갇혀 있다.
COMMENT ON COLUMN daily_settlements.season_return IS '확정 시점 시즌 누적 수익률(%). 계좌 요약의 cumulativeReturn 과 같은 기준. 자릿수는 자산 상한(NUMERIC(20,4))을 시드로 나눈 값보다 한 자리 크게 잡는다';
COMMENT ON COLUMN daily_settlements.daily_return IS '확정 시점 당일 수익률(%)';
COMMENT ON COLUMN season_reports.final_return IS '최종 수익률(%). 3일차 폐장 시점 확정값';

-- 시즌 최종 수익률의 상한을 일간 수익률과 맞춘다.
--
-- **정의가 정해져서 넓힌다.** V43 이 이 컬럼을 건드리지 않은 이유는 최종 성적의 정의가
-- 미정이었기 때문이다. 일차 초기화 이후 `daily_settlements.season_return` 은 누적이 아니라
-- 그날 값과 같아졌고, 최종 수익률을 **참가한 일차의 daily_return 합**으로 정했다.
--
-- 그러면 담을 수 있어야 하는 범위가 곧 일간 수익률의 범위다. `daily_return` 은 V43 에서
-- NUMERIC(38, 4) 로 넓혔는데 이 컬럼만 NUMERIC(15, 4)(상한 1e11 %) 로 남으면, 일간 정산은
-- 통과한 값이 시즌 확정에서 `numeric field overflow` 로 터진다 — 시즌 2 1일차 하루가
-- 이미 +6,589,883.61 % 였고 그것을 셋 더하는 값이다.
ALTER TABLE season_reports
    ALTER COLUMN final_return TYPE numeric(38, 4);

COMMENT ON COLUMN season_reports.final_return IS
    '최종 수익률(%). 참가한 일차의 daily_return 합이다 — 일차 경계마다 자산이 시드로 돌아가므로 복리 누적이 아니다. 자릿수는 daily_settlements.daily_return 과 같게 둔다';

-- **turnover 는 넓히지 않는다.** 분모가 일차별 평균 자산의 합이 되면서 값이 오히려 작아진다
-- (분자는 그대로인데 분모가 일차 수만큼 커진다). 시즌 1 실측 최댓값이 54.09 이고
-- NUMERIC(9, 4) 의 상한은 99,999.9999 다.
--
-- **max_drawdown·win_rate 도 그대로다.** 낙폭은 일차별 값 중 가장 깊은 것을 고르는 것이라
-- 여전히 [-100, 0] 이고, 승률은 [0, 100] 이다.

-- 최대 낙폭(MDD)을 만들 자리 (rpt.md 열린 질문 1, S15P21A605-89).
--
-- **평가액 시계열이 어디에도 없다.** 매 틱 스윕(AccountValuationService.valueAll)이 전 계좌의
-- 원화 총평가액을 내지만 랭킹에 넘기고 버리고, daily_settlements 는 일차당 한 점뿐이라 시즌
-- 전체가 세 점이다 — 세 점으로 낸 낙폭은 값이 아니다.
--
-- 그래서 **시계열을 저장하는 대신 최고점만 들고 다닌다.** 낙폭은 "지금까지의 최고점 대비 지금"
-- 이므로 과거 전부가 아니라 최고점 하나만 있으면 갱신된다. 틱마다 계좌 하나에 두 숫자가
-- 오가고, 시즌이 끝나면 max_drawdown 이 곧 답이다.
--
-- 시즌 종료 후 되짚는 방법도 있었지만 접었다. 틱 t 의 평가액을 복원하려면 그 시점의 현금을
-- 재현해야 하는데(체결 대금·수수료·환전·공모 환불·소급 인덱스 조정) 현금 이동이 하나 늘 때마다
-- 조용히 틀리기 시작한다. 여기서 누적하면 화면·랭킹·리포트가 같은 수를 본다.
ALTER TABLE accounts
    ADD COLUMN peak_valuation_krw NUMERIC(20, 4) NOT NULL DEFAULT 0,
    ADD COLUMN max_drawdown       NUMERIC(9, 4)  NOT NULL DEFAULT 0;

-- 최고점의 출발은 시작 잔고다. 첫 틱 평가액으로 잡으면 참가 직후의 하락이 낙폭에서 빠진다 —
-- 중간 합류자의 소급 인덱스 잔고도 이미 starting_balance_krw 에 반영돼 있다.
UPDATE accounts SET peak_valuation_krw = starting_balance_krw;

-- 기본값은 남겨 둔다. 계좌를 만드는 유일한 경로가 JPA 라 (Account 생성자가 시작 잔고를
-- 최고점으로 넣는다) 기본값이 쓰이는 곳은 SQL 로 직접 행을 심는 검증 픽스처뿐이고,
-- 거기서는 첫 틱의 평가액이 곧 최고점이 된다.

COMMENT ON COLUMN accounts.peak_valuation_krw IS '지금까지의 최고 원화 총평가액. 낙폭의 분모이며 시작 잔고에서 출발한다';
COMMENT ON COLUMN accounts.max_drawdown IS '최대 낙폭(%). 음수이고 0 이면 최고점에서 내려온 적이 없다는 뜻이다 (season_reports.max_drawdown 의 출처)';

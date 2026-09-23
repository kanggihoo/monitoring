-- 신호등 판정 기준 9행과 ETF·코인 정보 카드 8건. (S15P21A605-99)
--
-- 둘 다 편성값이라 행으로 둔다 — 기준을 조정하거나 카드 문구를 고치는 것은 UPDATE 이며
-- 배포가 필요 없다. 대량 데이터(게임 틱·일별 종가·공모 편성)는 여기 없다: 그쪽은
-- game_ticks 에 의존하므로 V18 의 소유 규칙대로 scripts/replay-loader 가 넣는다.
--
-- 번호가 V19 가 아닌 이유: V19__recomment_order_locked_amount.sql 을 미병합 브랜치가 선점했다.

-- 경계는 하한 포함·상한 미포함이다 (SignalCriterion 주석). NULL 은 그쪽 경계가 없다는 뜻이다.
-- kind 당 metric_key 는 하나이고 UNIQUE (kind, level) 이 그것을 강제한다.
--
-- GROWTH 3행은 기획서 9.2 의 예시를 그대로 옮겼다. SAFETY·VALUATION 6행은 기획서가
-- 임계값을 서버 설정값으로 위임했고(9.2 마지막 항목) 리포에 값이 없어 이 작업에서 정했다 —
-- 재무 분석 통념(부채비율 100% 이하 양호·200% 초과 위험, PER 15/30)을 따랐다.
-- 이 기준이면 수페르비아·코일모터스가 시즌 내내 VALUATION: CAUTION 이다. 의도한 결과다.
--
-- question 세 문장은 컬럼이 아니라 SignalKind enum 상수다 — kind 당 3행에 같은 문장이
-- 중복 저장되는 것을 피하고, 그 문장은 기획서 9.2.1 이 못 박은 고정값이라 편성값이 아니다.
INSERT INTO signal_criteria (kind, metric_key, level, lower_bound, upper_bound, description, sort_order)
VALUES ('GROWTH', 'REVENUE_GROWTH', 'GOOD', 20, NULL, '매출 성장률 20% 이상', 1),
       ('GROWTH', 'REVENUE_GROWTH', 'NORMAL', 0, 20, '매출 성장률 0~20%', 2),
       ('GROWTH', 'REVENUE_GROWTH', 'CAUTION', NULL, 0, '매출이 줄었습니다 (역성장)', 3),
       ('SAFETY', 'DEBT_RATIO', 'GOOD', NULL, 100, '부채비율 100% 미만', 4),
       ('SAFETY', 'DEBT_RATIO', 'NORMAL', 100, 200, '부채비율 100~200%', 5),
       ('SAFETY', 'DEBT_RATIO', 'CAUTION', 200, NULL, '부채비율 200% 이상', 6),
       ('VALUATION', 'PER', 'GOOD', NULL, 15, 'PER 15배 미만', 7),
       ('VALUATION', 'PER', 'NORMAL', 15, 30, 'PER 15~30배', 8),
       ('VALUATION', 'PER', 'CAUTION', 30, NULL, 'PER 30배 이상', 9)
ON CONFLICT (kind, level) DO UPDATE
    SET metric_key  = excluded.metric_key,
        lower_bound = excluded.lower_bound,
        upper_bound = excluded.upper_bound,
        description = excluded.description,
        sort_order  = excluded.sort_order;

-- info_card 는 ETF·인버스·코인 8종만 채운다. 주식 20종은 NULL 로 두고 instrument_metrics 를
-- 쓴다 (V7 의 instruments.info_card 주석).
--
-- summary 는 여기 넣지 않는다 — 응답의 summary 는 instruments.summary 에서 읽으며 V11 이
-- 이미 시드했다. cardType 도 넣지 않는다: asset_type 에서 파생되는 값이다 (info.md).
--
-- riskNotice 는 direction=INVERSE 또는 leverage>1 이면 반드시 채워진다 (info.md). NQX·KTOP 은
-- 둘 다 아니므로 null 이다. expenseRatio 는 대응 실제 상품(QQQ 0.20 / SQQQ 0.95 /
-- KODEX 200 0.15 / KODEX 인버스2X 0.64)의 총보수다.
--
-- 배수 3X·2X 는 적재된 원본 그대로다 (conflicts.md C-1). 1배로 바꾸면 daily_closes 33,860행과
-- 적재 스크립트의 SQQQ 역분할 4건을 다시 만들어야 한다. 레버리지 제한은 상품이 아니라
-- 주문 규칙으로 지킨다.
UPDATE instruments i
SET info_card = card.payload::jsonb
FROM (VALUES
    ('NQX', '{"trackingIndex":"나스닥 100","direction":"LONG","leverage":1,"expenseRatio":0.20,"riskNotice":null}'),
    ('SNQX', '{"trackingIndex":"나스닥 100","direction":"INVERSE","leverage":3,"expenseRatio":0.95,"riskNotice":"장기 보유 시 변동성에 의해 가치가 잠식될 수 있습니다. 지수가 제자리로 돌아와도 손실이 남을 수 있습니다."}'),
    ('KTOP', '{"trackingIndex":"코스피 200","direction":"LONG","leverage":1,"expenseRatio":0.15,"riskNotice":null}'),
    ('KTIV', '{"trackingIndex":"코스피 200 선물","direction":"INVERSE","leverage":2,"expenseRatio":0.64,"riskNotice":"장기 보유 시 변동성에 의해 가치가 잠식될 수 있습니다. 지수가 제자리로 돌아와도 손실이 남을 수 있습니다."}'),
    ('ETRA', '{"purpose":"네트워크 수수료 지불과 스테이킹에 쓰인다.","supplyModel":"발행 상한 없음. 스테이킹 보상으로 신규 발행되고 수수료 일부가 소각된다.","ecosystemRole":"다수의 토큰과 애플리케이션이 이 네트워크 위에서 동작한다."}'),
    ('CNTA', '{"purpose":"외부 데이터를 블록체인에 전달하는 노드 운영자에게 보수로 지불된다.","supplyModel":"총 발행량이 고정되어 있고 일부가 노드 보상으로 순차 배분된다.","ecosystemRole":"여러 블록체인의 계약이 가격·날씨 같은 외부 값을 받을 때 이 네트워크를 거친다."}'),
    ('HEXA', '{"purpose":"확장 네트워크의 수수료 지불과 검증자 스테이킹에 쓰인다.","supplyModel":"총 발행량 상한이 있고 수수료 일부가 소각된다.","ecosystemRole":"본체 네트워크의 혼잡과 높은 수수료를 우회하려는 애플리케이션이 이 위에서 동작한다."}'),
    ('LAND', '{"purpose":"가상 토지와 아이템 거래의 결제 수단이다.","supplyModel":"총 발행량 상한이 있고 토지 거래 수수료가 소각된다.","ecosystemRole":"플랫폼 안의 토지 소유·경매·아이템 제작이 이 코인으로 정산된다."}')
) AS card(symbol, payload)
WHERE i.symbol = card.symbol;

-- V7 의 주석이 "주식 24종"이라고 하는데 V11 이 실제로 시드한 주식은 20종(STOCK 12 +
-- IPO_STOCK 8)이다. 나머지 8종은 ETF 2 + INVERSE_ETF 2 + CRYPTO 4 이고 위 UPDATE 가 채운
-- info_card 를 쓴다. 적용된 마이그레이션은 체크섬 때문에 고칠 수 없으므로 V18 이 V11 의
-- price_scale 오류를 고친 것과 같은 방식으로 여기서 다시 선언한다.
COMMENT ON COLUMN instrument_metrics.label IS '화면 표기 ("시가총액", "PER"). info.md 의 metrics[].label. metric_key 에 종속이라 행마다 중복되지만, 주식 20종 × 6지표 × 27분기 ≈ 3,200행 규모라 별도 정의 테이블을 두지 않는다';

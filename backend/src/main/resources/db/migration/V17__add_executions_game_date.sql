-- 체결이 자기 게임 거래일을 들고 있게 한다. ord.md 의 체결 필드 표가 gameDate 를 '저장'으로
-- 표기했는데 컬럼이 없어 game_ticks 를 조인해 만들고 있었다 — _common.md 6.4 의 정의로는
-- 표기가 '저장'이고 구현이 '파생'이라 어긋난 상태였다.
--
-- 역정규화가 안전한 이유: 체결은 불변이다 (ord.md). 갱신 이상이 원천적으로 없다.
-- 그리고 편성이 재적재되면 tick_seq 가 다른 거래일을 가리킬 수 있는데, 행이 자기 거래일을
-- 들고 있으면 확정된 과거 기록이 흔들리지 않는다 (rpt.md 가 요구하는 성질).
--
-- tick_seq 는 그대로 둔다. 실시간 짝 맞추기(_common.md 10)와 부재 판정 산술이 그 축에 걸려 있다.
ALTER TABLE executions ADD COLUMN game_date DATE;

-- 이미 쌓인 행을 채운 뒤 NOT NULL 로 올린다. 빈 테이블이어도 그대로 통과한다.
UPDATE executions e SET game_date = t.game_date
FROM game_ticks t WHERE t.tick_seq = e.tick_seq;

ALTER TABLE executions ALTER COLUMN game_date SET NOT NULL;

COMMENT ON COLUMN executions.game_date IS '체결 시점의 게임 거래일. tick_seq 로 조인해도 얻지만, 체결이 불변이라 저장이 안전하고 편성 재적재에도 과거 기록이 흔들리지 않는다';

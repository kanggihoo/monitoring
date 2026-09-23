-- 복기 결과지를 담을 자리 (AI 업무요청서 2026-09-18 요청 ③).
--
-- **일차별 표에 붙인다.** 같은 사람이 1·2·3 일차에 서로 다른 결과지를 받으므로 저장 자리가
-- 일차별이어야 하고, daily_report_texts 가 이미 (season_day, account_id) 키에 머리말을 들고
-- 있다. season_report_texts 에 season_day 를 더하는 길도 있었지만 '시즌'이라는 이름과 일차별
-- 저장이 어긋나고, 조회(GET /reports/daily/{seasonDay})가 읽는 표도 여기다.
--
-- **행은 없을 수 있고, 있어도 결과지는 비어 있을 수 있다.** 이 표 자체가 외부 호출 성공 뒤에
-- 생기고(V25), 그 안에서도 결과지는 일일 요청에 누적 집계(cumulative)가 실렸을 때만 온다.
-- 그래서 전부 nullable 이다 — 머리말만 있고 결과지가 없는 행이 정상이다.
ALTER TABLE daily_report_texts
    ADD COLUMN investor_type    varchar(30),
    ADD COLUMN type_emoji       varchar(8),
    ADD COLUMN type_name        varchar(50),
    ADD COLUMN type_description varchar(200),
    ADD COLUMN verdict          varchar(500),
    ADD COLUMN profile_level    integer,
    ADD COLUMN profile_total    integer,
    ADD COLUMN profile_name     varchar(20),
    ADD COLUMN profile_persona  varchar(50),
    ADD COLUMN profile_items    jsonb,
    ADD COLUMN self_level       integer,
    ADD COLUMN self_total       integer,
    ADD COLUMN self_persona     varchar(50),
    ADD COLUMN profile_match    varchar(8),
    ADD COLUMN narrative        jsonb;

-- 단계·합계를 integer 로 둔다. 값이 1~5 와 5~20 뿐이라 smallint 가 맞는 폭이지만, AI·화면
-- 계약이 Integer 이고 (요청서 ③ 의 record 정의) 엔티티도 그 타입을 그대로 쓴다 — 폭을 줄이려고
-- Short 로 좁히면 양쪽 경계에서 변환이 여덟 군데 생긴다. 행이 계좌 x 3 일이라 2 바이트 차이는
-- 값이 없다.
--
-- profile_items 와 narrative 만 jsonb 다. 구조가 있는 값이라 칼럼으로 펼치면 막대 다섯 x 다섯
-- 필드가 스물다섯 칼럼이 되고, 항목이 늘 때마다 마이그레이션이 붙는다. block_ids 가 이미 같은
-- 이유로 jsonb 다.
--
-- 결과지가 왔는지는 investor_type 하나로 판정한다. AI 가 이 값을 비워 보내지 않으므로 —
-- 판정이 서지 않아도 UNDETERMINED 가 들어온다 — 이 칼럼이 NULL 인 것은 곧 "결과지를 받지
-- 못했다"는 뜻이다. 다른 칼럼으로 판정하면 '설문 안 함' 이나 '판정 불가' 를 '결과지 없음' 으로
-- 잘못 읽는다.
COMMENT ON COLUMN daily_report_texts.investor_type IS
    'LEVEL_1~LEVEL_5 또는 UNDETERMINED. NULL 이면 결과지를 받지 못한 행이다';
COMMENT ON COLUMN daily_report_texts.profile_level IS
    '관찰로 판정한 단계 1~5. 잴 수 있었던 항목이 셋 미만이면 NULL 이고 그때 investor_type 은 UNDETERMINED';
COMMENT ON COLUMN daily_report_texts.profile_total IS
    '관찰 합계 5~20. 막대의 단순 합이 아니다 — 막대는 항목별 반올림값이고 이 값은 반올림 전 평균에서 나온다';
COMMENT ON COLUMN daily_report_texts.self_level IS
    '설문으로 선언한 단계. 설문에 답하지 않았으면 NULL 이며 self_total·self_persona·profile_match 도 함께 NULL 이다';
COMMENT ON COLUMN daily_report_texts.profile_items IS
    '항목 막대 다섯. point 가 null 인 항목은 0 이 아니라 분모가 0 이어서 못 쟀다는 뜻이다';

-- 설문(선언) 성향의 답 원본을 담을 자리 (AI 업무요청서 2026-09-18 요청 ④).
--
-- 복기 결과지가 "내가 생각한 나"와 "실제 모습"을 나란히 놓는데, 왼쪽이 이 값에서 나온다.
-- 다섯 문항에 1~4 점씩 답하고 합계 5~20 이 그대로 다섯 단계를 지난다 — 관찰 쪽과 같은
-- 척도를 써야 두 값을 나란히 놓을 수 있다.
--
-- **합산값이 아니라 답 원본 5 글자를 저장한다.** 구간 경계는 베타에서 만질 값이고, 합계만
-- 두면 경계를 옮기는 순간 과거 답을 다시 채점할 수 없다.
--
-- 자리를 accounts 에 둔 이유. 리포트가 계좌·일차 단위이고 집계 조립이 이미 accounts 를 손에
-- 들고 있어 조인이 늘지 않는다. onboarding_completed_at 이 같은 자리에 있는 것도 같은 판단이다
-- (V33). 사람 단위로 옮겨야 할 이유가 생기면 — 시즌이 바뀌어도 답을 물려받는 계약이 되면 —
-- users 로 옮기고 accounts 는 조회만 하게 바꾼다.
ALTER TABLE accounts
    ADD COLUMN self_answers    varchar(5),
    ADD COLUMN self_answers_at timestamptz;

-- 형식을 DB 가 지킨다. 화면이 채점하고 서버는 형식만 보는 계약이라, 검증이 애플리케이션에만
-- 있으면 다른 경로로 들어온 값이 결과지를 조용히 깨뜨린다 (다섯 자리 밖의 값은 AI 가 읽지
-- 못해 selfLevel 이 빈 채로 나간다).
ALTER TABLE accounts
    ADD CONSTRAINT ck_accounts_self_answers_format
        CHECK (self_answers IS NULL OR self_answers ~ '^[1-4]{5}$');

-- 답과 시각은 함께 있거나 함께 없다. 시각만 남으면 "답했는데 값이 없다"가 되어 조회가
-- 무엇을 돌려줄지 정할 수 없다.
ALTER TABLE accounts
    ADD CONSTRAINT ck_accounts_self_answers_paired
        CHECK ((self_answers IS NULL) = (self_answers_at IS NULL));

COMMENT ON COLUMN accounts.self_answers IS
    '설문 다섯 문항의 답 원본. 각 자리 1~4 이며 합계가 그대로 성향 단계가 된다. 미응답은 NULL';
COMMENT ON COLUMN accounts.self_answers_at IS
    '설문을 마지막으로 저장한 시각. 설문은 언제든 다시 고칠 수 있어 덮어쓰기마다 갱신된다';

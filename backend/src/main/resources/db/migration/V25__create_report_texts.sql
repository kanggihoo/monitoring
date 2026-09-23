-- AI 가 만든 리포트 문구를 담는 자리 (AI 요청서 §5). 지금 rpt.md 의 복기 응답에는 문구 필드가
-- 없어서, AI 가 문장을 만들어도 화면까지 갈 경로가 없다. AI 서비스는 쓰기를 하지 않으므로
-- 백엔드가 받아서 저장한다.
--
-- **집계와 문구를 한 테이블에 합치지 않는다.** daily_settlements 는 폐장 트랜잭션이 순위와 함께
-- 얼리는 행이고 컬럼이 전부 NOT NULL 인데, 문구는 그 뒤 외부 호출이 성공해야 생긴다. 한 행에
-- 두면 AI 가 늦거나 실패할 때 정산 자체가 미완이 되거나 NULL 을 허용해야 한다.
--
-- 생성이 실패하면 행이 없고, 조회는 그 자리를 비운 채 리포트를 공개한다 (요청서 §8 실패 처리).
CREATE TABLE daily_report_texts
(
    season_day    SMALLINT     NOT NULL,
    account_id    BIGINT       NOT NULL REFERENCES accounts (id),
    headline      VARCHAR(200) NOT NULL,
    scene_aliases JSONB        NOT NULL,
    block_ids     JSONB        NOT NULL,
    generated_at  TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (season_day, account_id)
);

COMMENT ON TABLE daily_report_texts IS '일일 복기의 AI 문구 1행 = (일차, 계좌). 정산 집계(daily_settlements)와 키가 같지만 별도 테이블인 것은 생성 시점과 실패 가능성이 다르기 때문이다';
COMMENT ON COLUMN daily_report_texts.headline IS '머리말 한 줄. 그 사람의 그날을 요약한다';
COMMENT ON COLUMN daily_report_texts.scene_aliases IS '결정적 장면의 별칭 0~3개. 거래가 없으면 빈 배열';
COMMENT ON COLUMN daily_report_texts.block_ids IS '고른 문구 블록 식별자. 다음 일차에 같은 변주를 피하는 데 쓰인다 — 저장하지 않으면 AI 가 매일 같은 문장을 고를 수 있다';
COMMENT ON COLUMN daily_report_texts.generated_at IS '생성 실제 시각';

CREATE TABLE season_report_texts
(
    account_id       BIGINT       PRIMARY KEY REFERENCES accounts (id),
    investor_type    VARCHAR(30)  NOT NULL,
    type_name        VARCHAR(50)  NOT NULL,
    type_description VARCHAR(300) NOT NULL,
    verdict          VARCHAR(500) NOT NULL,
    block_ids        JSONB        NOT NULL,
    generated_at     TIMESTAMPTZ  NOT NULL
);

COMMENT ON TABLE season_report_texts IS '시즌 복기의 AI 문구 1행 = 계좌. 3일차 정산 뒤 한 번 만들어진다';
COMMENT ON COLUMN season_report_texts.investor_type IS '투자자 유형 9종 + UNDETERMINED. 어느 조건에도 닿지 못하면 억지로 유형을 붙이지 않는다';
COMMENT ON COLUMN season_report_texts.type_name IS '화면에 보일 유형 이름';
COMMENT ON COLUMN season_report_texts.type_description IS '유형 설명 문장';
COMMENT ON COLUMN season_report_texts.verdict IS '그 사람의 시즌에 대한 총평';
COMMENT ON COLUMN season_report_texts.block_ids IS '고른 문구 블록 식별자';
COMMENT ON COLUMN season_report_texts.generated_at IS '생성 실제 시각';

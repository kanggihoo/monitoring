-- 뉴스 콘텐츠 적재를 위한 news 스키마 보정. (S15P21A605-50)
--
-- V7 은 발행 시점을 published_tick_seq (game_ticks FK) 로 잡았다. 그런데 뉴스는 시즌을
-- 편성하기 전에 적재되는 콘텐츠라 참조할 틱이 아직 없고, API 명세(nws.md)도
-- publishedGameDate 를 저장, publishedTickSeq 를 "그 거래일의 틱 순번" 파생으로 정의한다.
-- 그래서 저장 키를 게임 거래일로 바꾼다. 틱 순번은 조회 시 game_ticks 를 game_date 로
-- 조인해 얻는다. news 는 비어 있어 데이터 이전은 없다.
--
-- 표시용 시각과 중요 표시는 뉴스 피드 화면의 요구다 — 날짜·시각, 헤드라인 앞의 강조 점.
-- 분류 태그(지수 전반·환율 등)는 종목 링크 태그(news_instruments)와 성격이 다르다.
-- 링크 태그는 종목 상세로 이동하고 우선 노출(보유 > 관심) 판정에 쓰이지만, 분류 태그는
-- 표시만 한다. 그래서 테이블을 따로 둔다.

ALTER TABLE news
    DROP COLUMN published_tick_seq;

ALTER TABLE news
    ADD COLUMN published_game_date DATE    NOT NULL,
    ADD COLUMN published_time      TIME    NOT NULL,
    ADD COLUMN major               BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN news.published_game_date IS '기사가 매핑된 게임 거래일 (한·미 거래일의 합집합). 그 거래일의 종가 틱과 함께 발행되며, 틱 순번(publishedTickSeq)은 game_ticks 를 game_date 로 조인해 파생한다. 현재 게임 거래일 이하인 기사만 노출한다';
COMMENT ON COLUMN news.published_time IS '표시용 발행 시각 (편성값). 정렬·노출 판정에는 쓰지 않는다 — 하루의 기사는 전부 그날 종가 틱에 함께 나간다';
COMMENT ON COLUMN news.major IS '중요 뉴스 표시. 피드에서 강조 점을 찍고 티커 강조 후보가 된다 (기획서 10.2.2)';


CREATE TABLE news_tags
(
    news_id BIGINT      NOT NULL REFERENCES news (id),
    tag     VARCHAR(50) NOT NULL,
    PRIMARY KEY (news_id, tag)
);

COMMENT ON TABLE news_tags IS '기사의 분류 태그 (0~N개). 지수 전반·금리·환율처럼 표시만 하는 태그다. 종목으로 이동하는 링크 태그는 news_instruments';
COMMENT ON COLUMN news_tags.news_id IS '기사';
COMMENT ON COLUMN news_tags.tag IS '태그 표시 문자열';

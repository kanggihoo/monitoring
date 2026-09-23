-- 시즌 경계를 DB 가 알게 한다. 시즌 1 과 2 를 같은 DB 에 함께 두고, Spring 인스턴스 하나가
-- 설정된 한 시즌만 서비스하기 위한 자리다 (app.replay.season-id).
--
-- 지금까지 시즌은 seasons · season_days · game_ticks · ipo_offerings · accounts · replay_state
-- 여섯 테이블만 알고 있었다. 종목·뉴스·채팅은 몰랐고, 그래서 시즌 2 를 적재하는 순간 시즌 1 을
-- 서비스하는 인스턴스의 종목 목록·시세·뉴스에 시즌 2 의 행이 그대로 섞인다. 컬럼이 없으면
-- 애플리케이션이 걸러낼 근거도 없다.
--
-- **종가·분할·지표·관심 종목은 컬럼을 두지 않는다.** instrument_id 로 종목에 매달려 있어
-- 종목이 시즌을 알면 따라 안다. 계좌 하위(주문·체결·정산)도 account_id 가 같은 역할을 한다.
-- 컬럼을 더하는 것은 **그 자체로 시즌을 갖는 것**뿐이다.
--
-- **DEFAULT 1 을 붙여 추가한다.** 새 컬럼을 모르는 코드의 INSERT 가 깨지지 않으므로 스키마와
-- 애플리케이션을 같은 배포에 실을 수 있다 — 점검 배포나 단계적 배포를 고르지 않아도 된다.
-- 기존 행이 전부 시즌 1 이라는 사실이 그대로 백필이 된다 (시즌 2 는 아직 적재 전이다).
-- Postgres 11 이후 DEFAULT 가 있는 ADD COLUMN 은 테이블을 다시 쓰지 않아 잠금도 짧다.


-- 1. instruments -----------------------------------------------------------------
--
-- sector 를 같은 마이그레이션에 얹는다. 어차피 같은 테이블을 잠그고, 둘 다 시즌 2 적재 전에
-- 있어야 하는 컬럼이다.
ALTER TABLE instruments
    ADD COLUMN season_id BIGINT      NOT NULL DEFAULT 1 REFERENCES seasons (id),
    ADD COLUMN sector    VARCHAR(30);

-- 심볼의 유일성을 시즌 안으로 좁힌다. 시즌이 바뀌면 같은 가상 심볼을 다시 쓸 수 있어야 한다 —
-- 종목 한 행은 한 시즌에 귀속되고, 같은 종목이 다시 등장해도 별도 instrument_id 를 받는다.
-- (시즌 2 의 NQX · KTOP 이 실제로 그 경우다. 시즌 1 의 같은 심볼과 다른 행이다.)
--
-- 이 변경은 findBySymbol() 을 쓰는 모든 경로를 시즌 포함 조회로 바꾸도록 강제한다. 같은 심볼이
-- 두 행이면 Optional 을 돌려주는 조회가 IncorrectResultSizeDataAccessException 으로 터지므로,
-- 빠뜨린 자리가 조용히 엉뚱한 행을 집는 대신 시끄럽게 드러난다.
ALTER TABLE instruments
    DROP CONSTRAINT uk_instruments_symbol,
    ADD CONSTRAINT uk_instruments_season_symbol UNIQUE (season_id, symbol);

COMMENT ON COLUMN instruments.season_id IS
    '이 종목이 속한 시즌. 한 행은 한 시즌에 귀속되고, 같은 종목이 다음 시즌에 다시 나오면 별도 행이 된다';
COMMENT ON COLUMN instruments.sector IS
    '업종. 주식은 GICS 11 섹터, ETF 는 기초자산 유형, 코인은 크립토 섹터다 — 자산 유형마다 축이 달라 화면이 asset_type 으로 라벨 세트를 고른다. category(탭 구분)와는 다른 관심사다. 시즌 1 은 NULL';
COMMENT ON TABLE instruments IS
    '거래 유니버스. 편성 종목 수는 시즌마다 다르다. 상장폐지가 없으므로 퇴장 상태를 두지 않는다 (unv.md)';


-- 2. news ------------------------------------------------------------------------
--
-- 뉴스는 시즌을 알 길이 **아예 없었다.** V7 의 published_tick_seq 는 V10 에서
-- published_game_date 로 바뀌었고, 게임 거래일은 두 시즌이 같은 구간(2020-01~2026-08)을
-- 재생하므로 날짜만으로는 어느 시즌 기사인지 가릴 수 없다. 컬럼이 유일한 방법이다.
ALTER TABLE news
    ADD COLUMN season_id BIGINT NOT NULL DEFAULT 1 REFERENCES seasons (id);

-- 피드가 (시즌, 날짜 이하) 로 좁힌 뒤 날짜·식별자 역순으로 읽는다
-- (NewsFeedQueryRepository#findFeedIds). 지금까지 published_game_date 에 인덱스가 하나도
-- 없어 매번 전체를 훑었는데, 시즌이 둘이 되면 훑는 양이 그대로 두 배가 된다.
CREATE INDEX idx_news_season_date_id ON news (season_id, published_game_date DESC, id DESC);

COMMENT ON COLUMN news.season_id IS
    '이 기사가 속한 시즌. 두 시즌이 같은 게임 거래일 구간을 재생하므로 날짜로는 가릴 수 없다';


-- 3. chat_messages ---------------------------------------------------------------
--
-- 채팅도 시즌을 몰랐다. tick_seq 로 유추할 수 없다 — V9 가 NULL 을 허용했고(브리핑 구간),
-- 그 NULL 행이 정확히 "어느 시즌인지 모르는" 행이 된다.
ALTER TABLE chat_messages
    ADD COLUMN season_id BIGINT NOT NULL DEFAULT 1 REFERENCES seasons (id);

-- 시즌과 틱이 어긋나는 것을 DB 가 막는다. game_ticks 의 기본키는 tick_seq 하나지만
-- uk_game_ticks_season_seq (season_id, tick_seq) 가 이미 있어 복합 참조가 가능하다 —
-- V32 가 ipo_offerings 에 쓴 것과 같은 방법이다.
--
-- tick_seq 가 NULL 인 브리핑 구간 행은 MATCH SIMPLE 규칙상 검사를 건너뛴다. 그쪽은 season_day
-- 와 함께 애플리케이션이 채우는 값이고, 이 제약이 노리는 것은 "시즌 2 메시지가 시즌 1 틱을
-- 가리키는" 상태다.
ALTER TABLE chat_messages
    ADD CONSTRAINT fk_chat_messages_season_tick
        FOREIGN KEY (season_id, tick_seq) REFERENCES game_ticks (season_id, tick_seq);

-- 커서 페이지네이션이 (시즌, 일차, id) 로 좁혀진다. V36 이 만든 (season_day, id DESC) 는
-- 시즌이 둘이 되면 다른 시즌의 같은 일차를 함께 훑으므로 앞에 시즌을 세운다.
DROP INDEX idx_chat_messages_day_id;
CREATE INDEX idx_chat_messages_season_day_id ON chat_messages (season_id, season_day, id DESC);

COMMENT ON COLUMN chat_messages.season_id IS
    '보낸 시점의 시즌. tick_seq 로 유추하지 않는다 — 브리핑 구간의 tick_seq 는 NULL 이다 (V9)';


-- 4. instrument_metrics ----------------------------------------------------------
--
-- 산출 불가의 이유를 담는다. 지금은 value 가 NULL 이면 화면이 "산출 불가" 한 마디로 끝내는데,
-- 그 중 가장 많은 사유가 **적자**다 (시즌 2 원본 기준 NULL 1,010 건 중 410 건). 적자라서 PER 이
-- 없다는 것은 수집 결함이 아니라 게임 안의 사실이고, 신호등의 VALUATION 이 비어 있는 이유이기도
-- 하다 — 그 빈칸이 설명되지 않으면 사용자는 고장으로 읽는다.
--
-- **화면이 모든 사유를 말하지는 않는다.** NO_SHARES · NO_PRICE 처럼 원본에 자료가 없어 생긴
-- 사유는 게임 안에서 의미가 없어 "산출 불가" 로 남긴다. 컬럼에는 원본 그대로 담아 두고 무엇을
-- 말할지는 화면이 고른다 — 나중에 사유를 하나 더 드러내기로 해도 적재를 다시 하지 않는다.
ALTER TABLE instrument_metrics
    ADD COLUMN null_reason VARCHAR(30);

COMMENT ON COLUMN instrument_metrics.null_reason IS
    '산출 불가 사유. value 가 NULL 일 때만 채운다. LOSS(적자) · NO_BASE_QUARTER(직전 분기 없음) · NO_REVENUE · NO_SHARES 등 원본의 값 그대로이며, 화면은 이 중 일부만 문구로 옮긴다';

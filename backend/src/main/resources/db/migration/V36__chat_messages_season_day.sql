-- 채팅 메시지에 일차를 기록한다. 전체 채팅을 "오늘 대화" 로 자르기 위한 컬럼이다.
--
-- **왜 tick_seq 로 유추하면 안 되는가.** 브리핑(11:00~11:05)에 보낸 메시지의 공개 기준 틱은
-- 그날 첫 틱이 아직 없어서 **전날 마지막 틱**이다 (V9 가 NULL 을 허용한 것도 같은 구간 때문이다).
-- tick_seq 로 일차를 되돌리면 2일차 브리핑에 나눈 대화가 개장하는 순간 1일차로 분류돼 사라진다.
-- dev 의 일시정지·배속·지점 이동에서도 실제 전송 시각과 게임 일차가 어긋난다. 보낼 때 서버가
-- 알고 있던 일차를 그대로 적어 두는 것이 유일하게 정확한 방법이다.
--
-- 커서 페이지네이션이 (일차, id) 로 좁혀지므로 인덱스도 그 모양이다 — 오늘 대화가 적은
-- 개장 직후에 어제 행을 훑고 내려가지 않는다.
ALTER TABLE chat_messages
    ADD COLUMN season_day SMALLINT;

-- 기존 행은 틱으로 메운다. 지금 있는 것은 전부 지나간 대화라 브리핑 경계 문제가 없고,
-- 이 백필이 tick_seq 유추를 쓰는 유일한 자리다.
UPDATE chat_messages c
SET season_day = g.season_day
FROM game_ticks g
WHERE g.tick_seq = c.tick_seq
  AND c.season_day IS NULL;

-- 틱이 없던 시간(브리핑·재생 종료 후)에 보낸 기존 행은 어느 일차인지 복원할 근거가 없다.
-- 1일차로 둔다 — 지금까지 열린 일차가 그것뿐이다.
UPDATE chat_messages SET season_day = 1 WHERE season_day IS NULL;

ALTER TABLE chat_messages
    ALTER COLUMN season_day SET NOT NULL;

CREATE INDEX idx_chat_messages_day_id ON chat_messages (season_day, id DESC);

COMMENT ON COLUMN chat_messages.season_day IS '보낸 시점에 서버가 판단한 일차(1~3). 채팅창은 이 값으로 오늘 대화만 보여준다. tick_seq 에서 유추하지 않는다 — 브리핑 구간의 틱은 전날 것이다';

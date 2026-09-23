-- 도배 방지 쿨다운이 "이 사용자가 최근 3초 안에 보냈는가"를 묻는다 (기획서 12.2.1).
--
-- 판정을 메모리에 두지 않고 이 질의로 하는 이유는, 메모리에 두면 재시작 직후 쿨다운이 풀리고
-- 서버가 둘이 되는 순간 사용자마다 두 배로 보낼 수 있게 되기 때문이다. 저장된 사실로 판정하면
-- 그 두 실패 방식이 생기지 않는다.
--
-- 인덱스가 없으면 그 질의가 chat_messages 전체를 훑는다. 채팅은 시즌 동안 가장 빠르게 쌓이는
-- 테이블이고, 전송마다 한 번씩 걸리는 질의다.

CREATE INDEX idx_chat_messages_sender_sent_at ON chat_messages (user_id, sent_at DESC);

COMMENT ON INDEX idx_chat_messages_sender_sent_at IS '도배 방지 쿨다운 판정용. 사용자별 최근 전송 시각을 찾는다';

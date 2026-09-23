-- 금칙어에 적용 범위를 둔다 (S15P21A605-170). 채팅(마스킹)과 닉네임(거부)이 한 사전을
-- 그대로 쓰던 것을, 사전은 하나로 두고 어느 경로에 적용할지만 행마다 정한다.
--
-- 표를 둘로 쪼개지 않는다. 3,500여 건 중 거의 전부가 양쪽에 해당하는데 두 표에 복사하면
-- 보강할 때마다 두 번 넣어야 하고, 하나 빠지면 "채팅에선 덮이는데 닉네임은 통과"가 생긴다 -
-- V20 이 공용 사전을 택한 이유가 그것이다. 기존 행은 전부 BOTH 다.
--
-- NICKNAME 은 닉네임에만 걸린다. 약한 비하어처럼 채팅에서 덮을 일은 아니지만 이름으로
-- 달고 다니게 둘 수는 없는 말이 들어간다. CHAT 은 반대 - 지금은 쓰이는 행이 없고 자리만 있다.

ALTER TABLE forbidden_words
    ADD COLUMN scope VARCHAR(10) NOT NULL DEFAULT 'BOTH'
        CONSTRAINT ck_forbidden_words_scope CHECK (scope IN ('BOTH', 'CHAT', 'NICKNAME'));

COMMENT ON COLUMN forbidden_words.scope IS 'BOTH | CHAT | NICKNAME. 이 단어를 어느 경로에 적용하나. 채팅은 마스킹, 닉네임은 거부다. 사전은 하나고 강도만 여기서 나눈다 (S15P21A605-170)';

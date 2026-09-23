-- 부하 테스트용 Test User 시드. 샌드박스용은 여기서 만들지 않는다 (진입 시점에 생긴다).
--
-- 이 파일은 db/migration 이 아니라 별도 디렉터리에 있고, app.test-support.enabled 가 켜졌을 때만
-- Flyway 의 탐색 위치에 추가된다 (TestSupportConfig). 파일이 물리적으로 분리돼 프로덕션 혼입이
-- 구조적으로 불가능하다.
--
-- 반복 적용(R__)이어야 한다. 버전 마이그레이션으로 두면 인원을 바꾸는 순간 체크섬 불일치로
-- 애플리케이션이 뜨지 않는다.

-- 인원은 아래 generate_series 상한 하나로 조정한다. 올리는 것은 안전하다 — 이 시드는 추가만
-- 하므로 기존 행을 건드리지 않는다. 반대로 줄여도 아무 일도 일어나지 않는다.
--
-- LPAD 4 자리는 고정이다. 천장 9999 는 k6 단일 실행 규모를 넘으므로 닿지 않고, 자리수를 바꾸면
-- 기존 이름과 다른 이름이 되어 전원이 다시 만들어진다.
-- 부하 스크립트는 같은 규칙으로 VU 번호에서 이름을 만든다 (k6 의 __VU 를 4자리로 채운다).
-- 주석에도 달러-중괄호를 쓰지 않는다 — Flyway 가 placeholder 로 읽어 기동이 실패한다.
WITH wanted AS (SELECT 'k6-' || LPAD(i::text, 4, '0')    AS name,
                       '테스트' || LPAD(i::text, 4, '0') AS nickname
                FROM generate_series(1, 1000) AS i),
     -- 멱등성은 "아직 없는 이름만 골라 넣는" 방식으로 얻는다. test_users 에만 충돌 무시를 걸면
     -- users INSERT 는 막지 못해 재적용 때 고아 User 가 남는다.
     missing AS (SELECT w.*
                 FROM wanted w
                 WHERE NOT EXISTS (SELECT 1 FROM test_users t WHERE t.name = w.name)),
     -- id 를 명시하지 않는다. users.id 는 BY DEFAULT AS IDENTITY 라, 명시하면 시퀀스가
     -- 전진하지 않아 나중에 실제 가입자가 같은 id 를 받아 충돌한다.
     created AS (INSERT INTO users (email, nickname, role, created_at, updated_at)
                 SELECT NULL, m.nickname, 'USER', now(), now()
                 FROM missing m
                 RETURNING id, nickname)
INSERT INTO test_users (user_id, name, assigned_admin_id)
SELECT c.id, m.name, NULL
FROM created c
         JOIN missing m ON m.nickname = c.nickname;

-- 로그인용 테스트 사용자 200명. test-support 로그인이 test_users.name 으로 찾는다.
INSERT INTO users (email, nickname, role, created_at, updated_at)
SELECT 'k6-' || lpad(g::text, 3, '0') || '@k6.local', 'k6투자자' || lpad(g::text, 3, '0'), 'USER', now(), now()
FROM generate_series(1, 200) g;
INSERT INTO test_users (user_id, name, assigned_admin_id)
SELECT id, 'k6-' || substring(email from 4 for 3), NULL FROM users WHERE email LIKE 'k6-%@k6.local';
SELECT count(*) AS test_users FROM test_users;

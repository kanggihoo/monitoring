-- Kakao 는 이메일 동의항목을 비즈 앱 권한 없이는 설정할 수 없고, 우리는 그 항목을 요청하지
-- 않는다. 이메일은 표시용 속성이지 식별자가 아니므로(CONTEXT.md) 없는 것이 정상이며,
-- placeholder 를 채우면 진짜 이메일과 구분할 수 없게 된다.
ALTER TABLE users
    ALTER COLUMN email DROP NOT NULL;

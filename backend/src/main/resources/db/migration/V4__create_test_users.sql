-- 이 테이블은 테스트 지원 기능 전용이다. 프로덕션에서는 빈 테이블이고, 기능을 걷어낼 때
-- DROP TABLE 하나로 사라진다 (단, 관리자 사용자 검색의 제외 조건도 함께 지워야 한다).
--
-- 행의 존재가 곧 "Test User 여부"다. users 에는 컬럼을 하나도 추가하지 않는다 (ADR-0003).
CREATE TABLE test_users
(
    -- 부하 시나리오가 탈퇴를 호출할 수 있다. 그때 사용자 서비스가 테스트 지원 개념을 알아야
    -- 하는 것은 방향이 거꾸로이므로, 이 리포의 다른 FK 와 달리 예외적으로 CASCADE 를 건다.
    user_id           BIGINT PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    -- 안정된 핸들. 닉네임은 사용자가 바꿀 수 있고 유일하지도 않아 키로 쓸 수 없다.
    name              VARCHAR(30) NOT NULL UNIQUE,
    -- NULL 이면 부하 테스트 전용. UNIQUE 라 DB 가 관리자 1:1 을 강제한다.
    -- 관리자가 탈퇴하면 배정이 자동 회수된다 (유령 배정이 남으면 그 행은 다시 못 쓴다).
    assigned_admin_id BIGINT UNIQUE REFERENCES users (id) ON DELETE SET NULL
);

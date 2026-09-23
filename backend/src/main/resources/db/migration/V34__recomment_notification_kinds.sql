-- 알림 유형이 다섯에서 여덟으로 늘었다. NOTICE 하나에 성격이 다른 공지가 섞여 있었다.
--
-- IPO_NOTICE 는 목적지가 갈려 나왔다 — 공모 공시·마감 임박은 할 일이 청약이라 청약 화면으로,
-- 무료 타임은 마켓으로 간다. ADMIN_NOTICE·URGENT_NOTICE 는 보낸 주체가 갈려 나왔다 — 관리자가
-- 보낸 말인지를 화면이 표시해야 하는데, 문구를 검사해서는 알 수 없고 그것을 아는 것은 유형뿐이다.
-- 표시 문구를 제목에 끼워 넣지 않는 이유는 제목이 VARCHAR(100) 고정이라 접두어가 그 길이를
-- 먹기 때문이다. 둘의 차이는 긴급 여부이며 목적지는 같고 표현(색·체류 시간)만 다르다.
--
-- 우선순위 컬럼을 두지 않는다. 단계가 긴급이냐 아니냐 둘뿐이라 값 하나가 컬럼 하나보다 싸다.
-- 셋 이상으로 나눠야 하면 그때 컬럼으로 옮긴다 — 그러면 유형 × 우선순위 조합이 폭발한다.
--
-- kind 는 VARCHAR(20) 이고 CHECK 제약이 없어 값 추가에 스키마 변경이 필요 없다. V7 을 직접
-- 고치지 않는 이유는 Flyway 체크섬이다 (V33 과 같은 이유). 설명만 바뀌므로 데이터 변경은 없다.
COMMENT ON COLUMN notifications.kind IS
    'ORDER_FILLED | ORDER_EXPIRED | IPO_RESULT | AWARD | NOTICE | IPO_NOTICE | ADMIN_NOTICE | URGENT_NOTICE. 화면 분류이자 reference_id 가 어느 테이블을 가리키는지를 정하는 값이다. 뒤의 넷은 전원 공지로 reference_id 가 NULL 이다. IPO_NOTICE(공모 공시·청약 마감 임박)는 청약 화면으로, NOTICE(수수료 무료 타임)와 ADMIN_NOTICE·URGENT_NOTICE(관리자가 보낸 평시·긴급 공지)는 마켓으로 간다';

COMMENT ON COLUMN notifications.reference_id IS
    '이동할 대상의 식별자 (기획서 15.1.3). kind 가 어느 테이블인지를 정한다 - ORDER_FILLED 는 executions, ORDER_EXPIRED 는 orders, IPO_RESULT 는 ipo_offerings. AWARD 와 공지 넷은 고정 화면으로 가므로 NULL 이다. 대상 테이블이 kind 마다 달라 FK 를 걸 수 없다';

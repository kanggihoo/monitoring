-- 알림 유형이 여덟에서 아홉으로 늘었다. 신규 상장(NEW_LISTING)이 들어온다.
--
-- 상장은 지금까지 알림이 없었다. 공모주는 ipo.listed 실시간 이벤트 하나가 전부라 종목명도
-- 없고 알림 센터에 남지도 않았으며, 공모 편성이 없는 상장(코인·직상장·SPAC·이전상장)은
-- 아무 신호도 내지 않았다. 시즌 2 에서 재생 도중 등장하는 종목이 35 종이고 그중 15 종이
-- 후자다 — 유니버스가 늘어나는 순간을 사용자가 알 방법이 없었다.
--
-- 공모주와 코인·직상장을 가르지 않는다. 사용자에게는 "오늘부터 거래된다" 하나이고, 가르려면
-- ipo_offerings 를 봐야 하는데 그 답이 문구도 목적지도 바꾸지 않는다. IPO_NOTICE 와 갈린
-- 것은 할 일이 달라서다 — 공시·마감은 청약이고 이것은 매매다.
--
-- reference_id 가 instruments 를 가리키는 첫 유형이며, 공지 중 유일하게 값이 있다. 목적지가
-- 고정 화면이 아니라 그 종목이라 비워 둘 수 없다.
--
-- kind 는 VARCHAR(20) 이고 CHECK 제약이 없어 값 추가에 스키마 변경이 필요 없다. V7·V34 를
-- 직접 고치지 않는 이유는 Flyway 체크섬이다. 설명만 바뀌므로 데이터 변경은 없다.
COMMENT ON COLUMN notifications.kind IS
    'ORDER_FILLED | ORDER_EXPIRED | IPO_RESULT | AWARD | NOTICE | IPO_NOTICE | NEW_LISTING | ADMIN_NOTICE | URGENT_NOTICE. 화면 분류이자 reference_id 가 어느 테이블을 가리키는지를 정하는 값이다. 뒤의 다섯은 전원 공지이며 그중 NEW_LISTING 만 reference_id 가 있다. IPO_NOTICE(공모 공시·청약 마감 임박)는 청약 화면으로, NEW_LISTING(신규 상장 — 공모주·코인·직상장을 가리지 않는다)은 그 종목으로, NOTICE(수수료 무료 타임)와 ADMIN_NOTICE·URGENT_NOTICE(관리자가 보낸 평시·긴급 공지)는 마켓으로 간다';

COMMENT ON COLUMN notifications.reference_id IS
    '이동할 대상의 식별자 (기획서 15.1.3). kind 가 어느 테이블인지를 정한다 - ORDER_FILLED 는 executions, ORDER_EXPIRED 는 orders, IPO_RESULT 는 ipo_offerings, NEW_LISTING 은 instruments. AWARD 와 나머지 공지 넷은 고정 화면으로 가므로 NULL 이다. 대상 테이블이 kind 마다 달라 FK 를 걸 수 없다';

# 프론트엔드 도메인 용어

프론트엔드 고유 용어 정의. 코드, 문서, 커밋에 공통 적용.
서버 정의 용어([`../../backend/docs/CONTEXT.md`](../../backend/docs/CONTEXT.md))는 중복 정의 금지 (서버가 진실 원천). 충돌 시 동기화 해결.

디자인 토큰의 3층 구조(`1층 primitive` / `2층 semantic` / `3층 components`)는 구현 세부사항이라 여기 두지 않는다. [`../DESIGN.md`](../DESIGN.md) 헤더와 [ADR-0002](adr/0002-design-md-token-pipeline.md) 참조.

## 가격과 주문 (Price & Order)

**가격 방향 (Price Direction)**:
자산 가격 상승/하락 사실 (사용자 의도 무관). 토큰 `price-up`(빨강), `price-down`(파랑).
_Avoid_: 등락 (방향 소실), 손익 (계좌 개념).

**주문 의도 (Order Intent)**:
사용자 매수/매도 의도의 화면 표현 방식. 토큰 `order-buy`, `order-sell`.
가격 방향과 별개 토큰 (하락 종목 매수 버튼은 `order-buy` 빨강 적용). 매수/매도 도메인 개념 자체는 백엔드 정의 준수.
_Avoid_: `order-buy`/`order-sell` 대신 `price-up`/`price-down` 쓰기.

**파괴적 액션 (Destructive Action)**:
되돌리기 어려운 조작 (회원 탈퇴, 예약 주문 취소, `aria-invalid` 입력 오류).
토큰 `destructive` 사용 (`order-buy`와 색상각 분리: 주문 화면 내 매수/취소 혼동 방지).
_Avoid_: 위험색, 에러색 (경고(warning)와 혼동 방지. 시스템 내 경고색 없음).

## 게임 시계와 틱 (Game Clock & Tick)

`Tick`·`Instrument`·`Account` 등 서버가 정의하는 용어는 [`../../backend/docs/CONTEXT.md`](../../backend/docs/CONTEXT.md)가 진실이다. 여기 넷은 **서버에 대응물이 없는 화면 쪽 개념**이다 — 서버는 한 Tick의 조각을 흩어서 보내고, 그것을 모아 한 번에 그리는 일은 전적으로 클라이언트 몫이다.

**조립 버퍼 (Assembly Buffer)**:
아직 확정되지 않은 Tick의 조각을 모아 두는 자리. 한 Tick의 조각은 여러 메시지로 흩어져 도착하므로 다 모일 때까지 화면에 반영하지 않는다.
_Avoid_: 큐, 대기열, 캐시 (버릴 것을 쌓는 게 아니라 한 번에 반영할 것을 모은다).

**틱 확정 (Tick Commit)**:
다음 `tickSeq`가 도착해 이전 Tick의 조각이 더 오지 않는다고 판단하는 순간. 이때 조립 버퍼를 한 번에 반영하고 비운다.
**조각 개수를 세지 않는다** — Tick마다 오는 종류가 다르다(뉴스는 기사가 있을 때만, 청약 경쟁률은 창이 열린 동안만).
_Avoid_: 플러시, 배치 렌더 (무엇을 하는지가 아니라 무엇이 정해지는지가 이름이다).

**구멍 (Gap)**:
받은 `tickSeq`가 1씩 이어지지 않는 상태. 히스토리를 다시 조회해 메운다.
_Avoid_: 유실, 누락, 드롭 (원인의 이름. 화면이 다루는 것은 결과인 불연속이다).

**게임 시계 오프셋 (Clock Offset)**:
서버 시각과 브라우저 로컬 시계의 차. 한 번 구해 두고 카운트다운을 매 렌더링마다 다시 계산한다 — **남은 초를 세어 내려가지 않는다** (백그라운드 탭에서 타이머가 스로틀되면 거짓이 된다).
_Avoid_: 시간차, 드리프트, 동기화 지연.

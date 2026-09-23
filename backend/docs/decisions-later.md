# Decisions Later — 미결정 사항

> ADR이 아닌 것들의 거주지. 해결 시 → ADR 승격 또는 항목 삭제.
> 작성 형식은 [`templates/decisions-later.md`](templates/decisions-later.md) 참조.

## 🕐 고도화 백로그 (나중에 할 것)

방향은 합의됐고 시점만 미룬 것. MVP 범위 밖.

| 항목 | 배경 | 우선순위 | 관련 |
|---|---|---|---|
| 앱 딥링크 · 일회용 코드 교환 | 인증 코어는 앱을 견디도록 짰다. 앱 작업 시 추가만 하면 되므로 웹 슬라이스에 넣지 않았다 | P2 | [ADR-0002](adr/0002-web-cookie-app-header-transport.md) |
| 배포 nginx의 `/ws` location | STOMP over WebSocket이 `/ws`에 뜨는데 **그 앞의 프록시 설정이 이 리포에 없다.** 업그레이드가 통과하려면 전용 location에 `proxy_set_header Upgrade $http_upgrade`·`proxy_set_header Connection "upgrade"`·`proxy_http_version 1.1`이 필요하고, 하트비트가 10초라 `proxy_read_timeout`을 그보다 넉넉히(60s 이상) 잡아야 한다. 기본값(60s)으로도 동작하지만 **명시하지 않으면 나중에 값을 줄인 사람이 연결이 주기적으로 끊기는 이유를 찾지 못한다.** 배포하고 나서야 알게 되는 것을 막기 위해 여기 적는다 | P2 | [ADR-0004](adr/0004-stomp-realtime-transport.md) |
| 감사 로그의 실제 클라이언트 IP | 관리자 행위의 접속 주소를 남기려면 `server.forward-headers-strategy`가 필요한데, 그 설정은 `X-Forwarded-For`를 무조건 신뢰한다. nginx 설정이 이 리포에 없어 그 헤더를 덮어쓰는지 확인할 수 없고, 로컬은 프록시가 없어 위조 가능하다. **컬럼을 만들지 않고 미뤘다** — 값이 들어 있는데 믿을 수 없는 컬럼은 사고 조사에서 오판을 만든다. 프록시 설정을 리포로 가져올 때 `proxy_set_header X-Forwarded-For $remote_addr`와 함께 넣는다 | P3 | [`admin-console.md`](../../docs/specs/admin-console.md) |
| 부하 테스트 시나리오 파일 | 세션을 얻는 수단(테스트 로그인)은 만들지만 시나리오는 만들지 않는다. 부하를 걸 대상 도메인 API가 아직 없어 지금 쓰면 곧 버린다. 인증은 앱 헤더 모드로 고정한다 (쿠키의 `Secure` 속성 때문에 부하 도구는 쿠키 모드로 동작하지 않는다) | P3 | [ADR-0003](adr/0003-test-support-isolation.md), [`../../docs/specs/test-support.md`](../../docs/specs/test-support.md) |
| 부하 테스트용 Test User 인원 확대 | 시드 기본값은 10명이다. 대상 API와 VU 규모가 정해지면 시드 파일의 숫자를 올린다 (멱등이라 안전하며, 관리자 검색에서 제외되므로 인원이 늘어도 콘솔은 오염되지 않는다) | P3 | [`../../docs/specs/test-support.md`](../../docs/specs/test-support.md) |
| 브라우저 자동화 테스트의 인증 셋업 | 현재 스모크는 백엔드 없이 돌아 미인증 경로만 본다. 테스트 로그인으로 로그인 상태를 만들면 인증 화면들을 덮을 수 있지만, 브라우저 자동화 구성에 백엔드 기동을 붙이는 별도 작업이다 | P3 | [`../../docs/specs/test-support.md`](../../docs/specs/test-support.md) |
| 익명 연결·구독·호출 빈도 상한 | 미인증 열람을 열면서 상한을 신설하지 않았다. 지금까지는 로그인이 사실상 상한이었다 — 붙으려면 IdP 계정이 필요했고, 그 상한이 사라지는데 대체물이 없다. **애플리케이션에 카운터를 심지 않기로 했다.** 자연스러운 자리는 프록시의 `limit_conn`·`limit_req`인데 **nginx 설정이 이 리포에 없다** (위 `/ws` location 항목과 같은 이유). 배포는 상시이되 트래픽이 사실상 없는 현재 상태에서는 문제가 되지 않는다. 프록시 설정을 리포로 가져올 때 함께 넣는다 | P2 | [ADR-0005](adr/0005-public-read-access.md) |
| `social.heatmap` 의 조회 권한과 실시간 권한이 갈린다 | 히트맵은 조회(`GET /social/heatmap`)가 `사용자` 인데 실시간(`social.heatmap`)은 공개 토픽의 **전체 브로드캐스트**라, 미인증 연결도 그 값을 받는다. 익명 4버킷 집계라 개인 데이터 유출은 아니고 화면도 미인증에 그리지 않지만, **틱 스토어의 개인 데이터 정리가 히트맵에 대해서는 다음 틱까지만 유효하다** — 지워도 브로드캐스트가 다시 채운다. 이번 작업은 발행을 건드리지 않기로 했다(익명 집계라 손댈 것이 없다는 판단). 맞추려면 발행을 개인 큐로 옮기거나 조회를 `공개` 로 내려야 하고, 어느 쪽이든 히트맵을 누구에게 보여줄 것인지를 먼저 정해야 한다 | P3 | [ADR-0005](adr/0005-public-read-access.md), [`../../docs/projects/market-replay-api/soc.md`](../../docs/projects/market-replay-api/soc.md) |
| FCM 디바이스 토큰 (`device_tokens`) | 알림 기능이 아직 없다. 토큰 수명이 세션과 달라 세션이 아닌 별도 테이블에 둔다 | P3 | — |
| 활성 세션의 개별 목록 | "이 User 세션 전부 끊기"는 관리자 콘솔에 들어간다. 세션을 **하나씩** 보여주려면 접속 주소·클라이언트 정보가 필요한데 세션 저장소가 그것을 갖고 있지 않고, 심으면 "세션 페이로드는 인증 주체만" 규약과 충돌한다 | P3 | [ADR-0001](adr/0001-session-based-authentication.md), [`admin-console.md`](../../docs/specs/admin-console.md) |
| 계정 연결 (로그인 상태에서 두 번째 Provider 붙이기) | 같은 사람이 Google과 Kakao로 각각 로그인하면 별개 User가 된다(정책으로 확정). 연결은 **로그인된 상태에서 시작하는 형태**여야 한다 — 비로그인 상태에서 기존 회원 여부를 판단할 근거가 하나도 없다 | P3 | [`../../docs/specs/kakao-login-and-account-ui.md`](../../docs/specs/kakao-login-and-account-ui.md) |
| 공모 청약 화면 (신청·취소·경쟁률) | **목록 표시는 끝났다** — 상장 전 종목이 가격 자리에 `청약 중`·`추첨 중`·`청약 마감` 과 상장 예정일을 쓴다. 남은 것은 **신청·취소를 실제로 하는 화면**이다: `POST`·`DELETE /ipo/offerings/{id}/subscription`, 실시간 경쟁률·당첨 확률, 내 청약과 추첨 결과. `ipo-card.tsx` 에 6단계 UI 가 있으나 목 데이터 모양(`offerPrice: number`·경쟁률로 확률을 역산)이라 **계약 모양으로 고쳐 붙여야 한다** — 금액은 문자열이고 확률은 서버가 준다(`_common.md §6.1`, 프론트가 역수를 뒤집으면 반올림이 갈려 "경쟁률 3.32:1인데 확률 30.1%"가 된다). 진입점 위치(상세 카드 안 / 별도 화면)도 정해지지 않았다. **마감 시각을 `listedGameDate` 에서 역산하지 않는다** — 편성 규칙이 두 곳에 생기면 편성이 바뀔 때 화면이 조용히 거짓말한다 | P2 | [`../../docs/projects/market-replay-api/ipo.md`](../../docs/projects/market-replay-api/ipo.md) |

## ⚠️ 보류/조율 (지금 갈등이거나 미정인 것)

방향이 안 정해진 것. 해결되면 ADR로 승격.

| 항목 | 쟁점 | 옵션 | 상태 |
|---|---|---|---|
| 닉네임 중복 정책 | 랭킹 화면에 동명이인이 나란히 보인다. 지목 기능(친구·대결)이 있으면 유일성이 필요해진다 | A: 유일성 없음(현재) / B: `#tag` / C: 고유 nickname | 미정 (랭킹·프로필 화면 설계 시) |
| 절대 만료 | 유휴 만료만 두면 탈취된 세션을 요청으로 계속 연장할 수 있다. 모의투자라 기대 피해는 낮다 | A: 유휴만(현재) / B: 유휴 + 절대 2단 (필터 ~20줄) | 미정 |
| `develop` 브랜치 부재 | `docs/conventions/git.md`는 `main ── develop ── <type>/...`을 규정하는데 원격에 `develop`이 없다 | A: `develop` 생성 / B: 문서를 현실에 맞춤 | **A 확정 (2026-08-28)** — 관리자 콘솔·테스트 지원·문의 작업 6건이 이어지는 시점이라 통합 브랜치가 필요해졌다. `develop`을 원격에 만든 뒤 이 행을 삭제한다 |

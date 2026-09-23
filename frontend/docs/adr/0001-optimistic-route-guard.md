# ADR-0001: 낙관적 라우트 가드와 데이터 계층 인증

- 상태: 승인
- 결정일: 2026-08-27
- 관련: [backend ADR-0001](../../../backend/docs/adr/0001-session-based-authentication.md), [backend ADR-0002](../../../backend/docs/adr/0002-web-cookie-app-header-transport.md), [code.md §7](../conventions/code.md), [backend ADR-0005](../../../backend/docs/adr/0005-public-read-access.md)

## 문맥

- `proxy.ts`: 프리페치 포함 모든 라우트 요청에서 실행.
- 세션 소유권: 백엔드(Redis). 쿠키 `HttpOnly`라 프론트 판정 불가.
- 서버 컴포넌트: 상대경로 불가. 검증 시 백엔드 HTTP 왕복 필수.
- 상태 변경 요청: 위조 방지 토큰 부착 필수.

## 결정

1. **`proxy.ts`**: 세션 쿠키 유무만 확인. 백엔드 호출 금지. — **[backend ADR-0005](../../../backend/docs/adr/0005-public-read-access.md)로 보완 (2026-09-09).** 쿠키 유무에 더해 **경로가 공개인지 보호인지**를 함께 본다. 백엔드 호출 금지는 그대로다.
2. **`lib/auth.ts`**: 백엔드 호출해 실제 검증. React `cache`로 렌더당 1회 제한.
3. **`lib/api.ts` (`apiFetch`)**: 위조 방지 토큰 일괄 부착.
4. **보호 페이지**: 반드시 `requireUser()` 호출. `proxy.ts` 단독 방어 금지. — 공개 페이지가 생기면서 **무엇이 보호 페이지인가**가 처음으로 자명하지 않게 됐다. 공개 페이지는 `requireUser()` 대신 선택적 사용자 조회를 쓴다. 경계 목록은 `lib/public-routes.ts`가 갖고 `proxy.ts`와 화면들이 그것을 함께 읽는다 — 목록이 둘이면 프록시는 통과시키는데 화면이 로그인을 요구하는 상태가 된다.

## 대안 비교

- **`proxy.ts` 백엔드 호출 검증**: 기각. 프리페치마다 백엔드 호출 폭증, 라우팅 지연.
- **쿠키 내 사용자 정보 저장 판정**: 기각. 위조 취약, 즉시 무효화 불가.
- **각 페이지 개별 검증**: 기각. 누락 위험, 일관성 결여.
- **호출부 개별 토큰 부착**: 기각. 누락 위험.

## 핵심 근거

- **라우팅 힌트와 인증 판정 분리**: 라우팅 오판은 로그인 재이동으로 수습 가능. 데이터 유출은 불가.
- **비용 최적화**: 쿠키 유무(I/O 0) 자주 확인, 백엔드 검증은 필요 시 1회만 수행.
- Next.js 공식 권장: proxy/middleware 단독 인증 지양.

## 영향

- ~~만료 쿠키 보유 시: `proxy.ts` 통과 후 `lib/auth.ts`에서 로그인 이동 (의도된 동작).~~ **[backend ADR-0005](../../../backend/docs/adr/0005-public-read-access.md)로 뒤집혔다 (2026-09-09).** 만료 쿠키로 **공개** 경로에 들어오면 로그인으로 보내지 않고 미인증 상태로 그 화면을 유지한다. **보호** 경로에서는 종전대로 로그인으로 이동한다.
- `requireUser()` 누락 시 보안 취약점 발생.
- `apiFetch` 미사용 상태 변경 요청: 토큰 누락으로 403 실패.
- 제외 경로(`/api`, `/login`, 정적 자산): 2층(`lib/auth.ts`)이 전담 방어.

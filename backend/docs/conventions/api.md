# API 규칙

> 엔드포인트 명세는 Swagger(`springdoc-openapi`)가 단일 진실.
> 응답 구조(`ApiResponse` / `ErrorResponse`)는 [`code.md`](code.md) §6 참조.

## 1. 경로 규칙

- **기본 접두사**: 모든 API는 `/api/v1/`로 시작 (`/api/v1/{도메인}/{리소스}`)
- **버전 관리**: URI 경로 기반 버전 관리 사용 (`/api/v1/...`)

### 예외 경로 (프레임워크/도구 전용)

| 경로 | 소유 |
|---|---|
| `/swagger-ui/**`, `/v3/api-docs/**`, `/scalar/**` | springdoc-openapi & Scalar |
| `/api/oauth2/**`, `/api/login/oauth2/**`, `/api/auth/logout` | Spring Security — 콜백 주소가 IdP 콘솔에 등록되어 있어 코드만으로 바꿀 수 없다 |
| `/ws` | STOMP over WebSocket — **전송 채널이지 리소스가 아니다.** 긴 `proxy_read_timeout`을 요구해 어느 경로든 nginx 전용 location이 필요하므로 `/api` 아래로 옮겨도 얻는 것이 없다 ([ADR-0004](../adr/0004-stomp-realtime-transport.md)) |

> 새 비즈니스 엔드포인트는 예외 경로에 추가하지 않는다.

---

## 2. 에러 및 상태 코드 규칙

- **식별 기준**: HTTP 상태 코드 + `message` (별도 비즈니스 에러 코드 필드 미사용).
- **검증 실패 응답**: `errors` 배열에 `{ field, rejectedValue, reason }` 포함.
- **보안 원칙**:
  - 계정 미존재와 비밀번호 불일치는 동일하게 `401` 반환 (계정 존재 여부 노출 방지).
  - 외부 응답에 stack trace 및 내부 구현 정보 노출 금지.

### 상태 코드 사용 기준

| 상태 코드 | 사용 상황 |
|---|---|
| **400** | 입력값 검증 실패 (`@Valid`) |
| **401** | 인증 실패 (자격 증명 불일치, 토큰 만료/무효, 미존재 계정) |
| **403** | 인가 실패 (접근 권한 부족) |
| **404** | 대상 리소스가 없음 (없는 식별자, 없는 경로) — 존재하지 않는 것과 권한이 없는 것을 구별한다 |
| **409** | 리소스 상태 충돌 (이메일 중복, 비활성 외부 리소스 거절 등 — 상태 해소 시 동일 요청 재시도 가능) |
| **502** | 외부 도구 연동 실패 (외부 API 호출 오류) |
| **503** | 인프라 일시 장애 (Redis/DB 연결 실패, 타임아웃 — 애플리케이션 500 에러와 구분) |

---

## 3. 메서드 규칙

- **GET은 상태를 변경하지 않는다**: 조회 전용. 상태 변경은 POST/PUT/PATCH/DELETE로만 한다.
  - **이유**: 세션 쿠키의 `SameSite=Lax`는 교차 사이트 **POST는 막지만 top-level GET은 허용**한다.
    GET에 상태 변경을 두면 그 예외가 그대로 CSRF 통로가 된다 ([`code.md`](code.md) §7).

---

## 4. 공개 엔드포인트 표기

권한은 세 가지뿐이다. 표기는 [`_common.md §2`](../../../docs/projects/market-replay-api/_common.md)의 것을 그대로 쓴다 — 명세 문서 47개 엔드포인트 행에 이미 채워져 굴러가는 규약이라 새 체계를 만들지 않는다.

| 표기 | 뜻 | `SecurityConfig` |
|---|---|---|
| `공개` | 미인증 접근 가능 | `permitAll()` |
| `사용자` | 로그인 필요 | `anyRequest().authenticated()` (기본) |
| `관리자` | `ROLE_ADMIN` 필요 | `hasRole("ADMIN")` |

**규칙 세 개** ([ADR-0005](../adr/0005-public-read-access.md)):

1. **공개 경로는 개별 명시한다. 와일드카드로 열지 않는다.** `/api/v1/seasons/**`로 열면 같은 접두사의 개장 브리핑까지 딸려 열린다. 접두사가 아니라 경로 하나씩 적는다.
2. **메서드까지 구분해 연다.** GET은 공개 여부의 기준이 될 수 없다 — 개인 자산·주문내역·복기도 GET이다. 같은 경로에서 메서드마다 갈리는 것이 정상이다(`GET /chat/messages`는 `공개`, `POST`는 `사용자`).
3. **판정은 현재 로그인 상태 하나로 한다.** 가입 이력을 보지 않으므로 컨트롤러의 `@AuthenticationPrincipal`은 공개 경로에서 `null`일 수 있다. 그 값을 쓰는 코드는 `null`을 정상 입력으로 다뤄야 한다.

Swagger 설명(`controller/api` 인터페이스)에 그 엔드포인트의 권한을 적는다. 명세는 Swagger가 단일 진실이므로, 열고 나서 설명을 갱신하지 않으면 계약 문서와 갈린다.

# 프론트엔드 코드 컨벤션

---

## 1. 포맷 및 린트

- **Prettier**: 코드 포맷팅 전담.
- **ESLint**: 코드 품질/안티패턴 검사 전담 (포맷 규칙 중복 금지).
- **Import 정렬**: `simple-import-sort` 자동 정렬.

### Tailwind CSS 및 컴포넌트 스타일

- 조건부 Tailwind CSS 클래스 조합은 공통 `cn()` 사용.
- `cn()`은 `clsx`와 `tailwind-merge` 기반으로 작성.
- Tailwind CSS 클래스 충돌은 `tailwind-merge`로 해결.
- 컴포넌트 variant가 여러 개일 때 `class-variance-authority(cva)` 사용.
- 단순한 클래스 조합에는 `cva` 사용 금지.

---

## 2. 로깅

| 위치                                  | 수단                       | 규칙                                                                                                                                      |
| ------------------------------------- | -------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------- |
| 서버 (RSC, Route Handler, `proxy.ts`) | `src/lib/logger.ts` (pino) | `logger.info({ key: val }, "msg")`<br>- 첫 인자 객체, 둘째 메시지 (문자열 보간 ❌)<br>- 에러 전달: `{ err }`<br>- `server-only` 유지 필수 |
| 브라우저 (`"use client"`)             | `console.*`                | 운영 빌드 시 `error`/`warn`만 유지 (`compiler.removeConsole`)                                                                             |

- **보안**: 토큰·비밀번호·쿠키·인증 헤더 로깅 절대 금지 (redact 의존 금지).

---

## 3. JSDoc

- **작성 대상**: 커스텀 훅(`use*`), 유틸(`src/lib/`) 필수. 컴포넌트는 제약·부작용 있을 때만.
- **규칙**:
  - TS 타입 중복 작성 금지 (`@param`/`@returns` 타입 생략).
  - "이유", "비명시적 제약" (단위, 범위, 부작용, 실패 조건) 위주 작성.
  - 한글 평서문, 태그 순서: `@param` → `@returns` → `@throws`.

```ts
/**
 * 디바운스된 값 반환. 언마운트 시 타이머 자동 정리.
 * @param delay 대기 시간(ms). 0 이하면 즉시 반영.
 */
export function useDebouncedValue<T>(value: T, delay: number): T { ... }
```

---

## 4. 테스트

| 대상              | 러너                 | 파일 패턴              |
| ----------------- | -------------------- | ---------------------- |
| 컴포넌트·단위     | Vitest + RTL (jsdom) | `src/**/*.test.tsx`    |
| 전체 화면 E2E     | Playwright           | `e2e/*.spec.ts`        |
| 컴포넌트 카탈로그 | Storybook            | `src/**/*.stories.tsx` |

- 재사용 UI 컴포넌트는 Storybook 스토리 작성.
- 주요 variant, 크기, 상태, disabled 상태를 스토리로 검증.
- 스토리는 대상 컴포넌트와 동일 디렉토리에 작성.
- 페이지 전용 컴포넌트와 단순 wrapper는 작성 대상에서 제외 가능.

---

## 5. 알려진 제약

1. **async Server Component**: Vitest 미지원 (Playwright로 검증).
2. **`next/font/local`**: Vitest 단위 테스트 시 모킹 필요 (`layout.tsx` import 주의).
3. **백엔드 의존**: 백엔드 미실행 시 서버 `fetch` 경로 E2E 불가.
4. **`pnpm typecheck`**: `pnpm build`(또는 `pnpm dev`) 선행 필요. `LayoutProps`/`PageProps`는 Next가 `.next/types`에 생성하므로, `.next`가 없으면 손대지 않은 파일에서도 `Cannot find name`으로 실패한다.

---

## 6. API 호출 경로

- **규칙**: 브라우저는 항상 상대경로 `/api/*` 호출 (절대 URL 금지).
- **프록시 주체**:
  - 로컬: `next.config.ts` `rewrites`
  - 배포: nginx (빌드 타임 고정 방지)

---

## 7. 인증 방어선

3개 층 분리 방어.

| 층  | 위치                      | 담당 작업                       | 제외 작업              |
| --- | ------------------------- | ------------------------------- | ---------------------- |
| 1   | `proxy.ts`                | 세션 쿠키 유무 + 공개/보호 경로 | 인증 판정, 백엔드 호출 |
| 2   | `lib/auth.ts`             | 백엔드 호출 실제 검증           | —                      |
| 3   | `lib/api.ts` (`apiFetch`) | 위조 방지 토큰 부착             | —                      |

- **공개 페이지**: `lib/public-routes.ts`의 목록이 경계다. `requireUser()` 대신 `getCurrentUser()`로 선택적 조회하고, 개인 영역만 로그인 안내로 갈린다 ([backend ADR-0005](../../../backend/docs/adr/0005-public-read-access.md)).
- **보호 페이지**: 반드시 `requireUser()` 호출. 1층 단독 방어 금지. **목록에 없는 경로가 보호 경로다** — 새 화면의 기본값이 보호인 쪽이 안전하다.
- **상태 변경 요청**: 반드시 `apiFetch` 사용. 직접 `fetch` 호출 시 토큰 누락 403 실패.
- **`proxy.ts` 백엔드 호출 금지**: 프리페치 포함 모든 라우트 실행으로 호출 폭증.
- **개인 조회의 401만 세션 만료다**: 공개 조회의 401은 경계 설정이 어긋난 것이므로 "다시 로그인하세요"로 안내하지 않는다 (`lib/market-api.ts`의 `readJson`).

---

## 8. 오류 처리

예상 가능한 실패와 예기치 못한 예외 분리.

| 유형                        | 처리 위치               | 규칙                                                        |
| --------------------------- | ----------------------- | ----------------------------------------------------------- |
| API `4xx`/`5xx` 응답        | 호출 컴포넌트           | 원인/재시도 안내 표시. 검증 실패는 백엔드 필드별 오류 우선  |
| 이벤트 핸들러 네트워크 예외 | 이벤트 핸들러           | `try/catch/finally`로 안내 표시, `finally`에서 pending 해제 |
| 라우트 렌더링 미처리 예외   | 가장 가까운 `error.tsx` | 화면 대체 복구 UI 제공                                      |
| 루트 레이아웃 전파 예외     | `app/global-error.tsx`  | 전역 복구 UI 제공                                           |

- 라우트 렌더링 복구: 커스텀 Error Boundary 대신 Next.js 파일 규약(`error.tsx`) 사용.
- `error.tsx` 범위: 전 디렉터리 생성 금지. 독립 복구 단위 라우트 경계에만 배치.
- Error Boundary 한계: 이벤트 핸들러/비동기 콜백 예외 미포착. 호출부 직접 처리 필수.
- 보안: stack trace, 내부 구현, 민감정보 노출 금지.
- 역할 분리: 공통 통신은 `lib/api.ts`, 사용자 안내/pending은 기능 컴포넌트 전담.

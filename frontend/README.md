# Frontend

Next.js 기반 마켓 리플레이 웹 프론트엔드 애플리케이션입니다.

---

## 1. 환경 설정 (Setup)

`.env.example`을 복사하여 `.env.local`을 생성하고 필요한 로컬 설정값을 채웁니다.

```bash
cp .env.example .env.local
```

주요 설정 항목:

- `BACKEND_ORIGIN`: 백엔드 주소 (기본값: `http://localhost:8080`)
- `TEST_SUPPORT_API_KEY`: 백엔드 `TEST_SUPPORT_API_KEY`와 일치하는 키 (OAuth 우회 테스트용)

---

## 2. 개발 서버 실행

```bash
# 의존성 설치
pnpm install

# 1. 기본 개발 서버 (로그인 화면 거침)
pnpm dev

# 2. 자동 로그인 개발 서버 (테스트 계정으로 자동 로그인되어 홈 직행)
pnpm dev:login
```

---

## 3. 인증 및 테스트 로그인 (OAuth 우회)

로컬에서 구글/카카오 OAuth 로그인 없이 백엔드 연동 및 API를 테스트할 수 있습니다.

1. **자동 로그인 서버 실행 (`pnpm dev:login`)**:
   - `pnpm dev:login`으로 서버를 띄우면 브라우저 접속 즉시 백그라운드에서 세션 쿠키를 발급받아 메인 페이지로 자동 이동합니다.
2. **수동 호출 (`/api/dev/login`)**:
   - 일반 `pnpm dev` 상태에서 테스트 세션만 받고 싶다면 브라우저 주소창에 `http://localhost:3000/api/dev/login`을 직접 입력합니다.
3. **다른 테스트 계정 지정**:
   - `http://localhost:3000/api/dev/login?user=k6-0002` 형태로 사용자명을 넘길 수 있습니다.

---

## 4. 주요 명령어 (Scripts)

| 명령어               | 역할                 | 설명                                                              |
| :------------------- | :------------------- | :---------------------------------------------------------------- |
| **`pnpm dev`**       | 개발 서버            | 기본 로컬 개발 서버 실행 (로그인 화면 거침)                       |
| **`pnpm dev:login`** | **자동 로그인 서버** | 테스트 계정 세션을 자동으로 심고 홈으로 직행                      |
| **`pnpm build`**     | 빌드                 | 프로덕션 번들 빌드 (`output: standalone`)                         |
| **`pnpm start`**     | 실행                 | 빌드된 프로덕션 서버 실행                                         |
| **`pnpm check`**     | **전체 검사**        | 타입(`tsc`) + 린트(`eslint`) + 포맷(`prettier --check`) 동시 검증 |
| **`pnpm fix`**       | **자동 수정**        | 서식 포맷팅(`prettier`) 및 린트 에러(`eslint --fix`) 자동 교정    |
| **`pnpm typecheck`** | 타입 검사            | Next.js 타입 에러 단독 검증                                       |
| **`pnpm test`**      | 단위 테스트          | Vitest 단위 테스트 실행 (`--watch`로 감시 모드)                   |
| **`pnpm test:e2e`**  | E2E 테스트           | Playwright 종단간 테스트 실행 (`--ui`로 UI 모드)                  |
| `pnpm mock`          | 목 서버              | 마켓 리플레이 전용 목 서버 실행                                   |
| `pnpm theme`         | 토큰 동기화          | `DESIGN.md` 기반 `globals.css` 재성성                             |
| `pnpm shots`         | 화면 캡처            | 데스크톱 라이트 모드 캡처 (`--all`, `--mobile`, `--dark` 지원)    |
| `pnpm perf`          | 성능 측정            | Core Web Vitals 및 번들 용량 표 출력 (`pnpm start` 대상)          |

---

## 5. 관련 문서

- [AGENTS.md](AGENTS.md): AI 및 프론트엔드 작업 지침, 3원칙, 라우팅 표
- [docs/conventions/code.md](docs/conventions/code.md): 코드 스타일, 네이밍, 제약 사항
- [DESIGN.md](DESIGN.md): 디자인 시스템 토큰 및 가이드라인

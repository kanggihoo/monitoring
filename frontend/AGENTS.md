<!-- BEGIN:nextjs-agent-rules -->

# This is NOT the Next.js you know

This version has breaking changes — APIs, conventions, and file structure may all differ from your training data. Read the relevant guide in `node_modules/next/dist/docs/` (resolved from this file's directory; in monorepos the `next` package may not be visible from the repo root) before writing any code. Heed deprecation notices.

This block is written and re-added by `next dev` — verify at `node_modules/next/dist/server/lib/generate-agent-files.js`. Removing it from a diff only re-creates the uncommitted change; committing it with your work keeps the tree clean.

<!-- END:nextjs-agent-rules -->

## 필독 순서

1. **코드 작성 3원칙** (항상 적용)
2. **`package.json`** — 스택/의존성
3. **`node_modules/next/dist/docs/`** — Next.js API (학습 지식 금지, 필수 확인)
4. **`docs/conventions/code.md`** — 코드 스타일/로깅/JSDoc/테스트
5. **`docs/adr/`** — 아키텍처 결정 (Why)
6. **`../backend/docs/CONTEXT.md`** — 도메인 용어 (서버 기준)
7. **`DESIGN.md`** - react 코드 작성시 참고

---

## 코드 작성 3원칙

### 1. 생각 먼저 (Think First)

- 모호하면 추측 말고 질문.
- 해석 갈리면 선택지 제시.
- 더 단순한 대안 있으면 제안.

### 2. 단순함 우선 (Simplicity First)

- 문제 해결 최소 코드 작성.
- 요청 없는 기능·추상화·설정성·불필요 예외 처리 금지.

### 3. 최소 침습 (Surgical Changes)

- 요청된 부분만 수정. 무관한 코드·포매팅·주석 수정 금지.
- 미요청 리팩터링 금지. 기존 스타일 준수.
- 내 작업으로 발생한 미사용 코드만 정리.

---

## 라우팅 표

| 목적                                     | 문서                                                                                                                                    |
| ---------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------- |
| Next.js API / 컨벤션                     | `node_modules/next/dist/docs/`                                                                                                          |
| 코드 스타일 / 로깅 / API 프록시 (`/api`) | [docs/conventions/code.md](docs/conventions/code.md)                                                                                    |
| 아키텍처 결정 (Why)                      | `docs/adr/`                                                                                                                             |
| 디자인 토큰 (색/타이포/radius)           | [DESIGN.md](DESIGN.md) — `src/app/globals.css`는 여기서 생성되는 **생성물**이다 ([ADR-0002](docs/adr/0002-design-md-token-pipeline.md)) |
| 화면 캡처 기반 디자인 점검 루프          | [docs/conventions/design-review.md](docs/conventions/design-review.md) — 기록은 [design-review/log.md](design-review/log.md)            |
| 착수 전 기능 명세 (범위/사용자 스토리)   | [../docs/specs/](../docs/specs/) (리포 전역, 컨텍스트 무관)                                                                             |
| 도메인 용어 (서버/프론트)                | `../backend/docs/CONTEXT.md`, `docs/CONTEXT.md`                                                                                         |
| 환경변수 / 컨테이너                      | [.env.example](.env.example), [Dockerfile](Dockerfile)                                                                                  |
| Git / Jira / MR 규칙                     | [../docs/conventions/git.md](../docs/conventions/git.md), [../docs/agents/issue-tracker.md](../docs/agents/issue-tracker.md)            |

---

## 규칙 요약

- **MR**: 프론트 전용 MR 제목 접두사 `[frontend]` 추가.
- **도메인**: 서버 API/용어는 `../backend/docs/CONTEXT.md`가 진실. UI 컴포넌트명과 도메인 분리.
- **ADR**: 되돌리기 어려운 핵심 아키텍처 결정만 기록.
- **Next.js**: `node_modules/next/dist/docs/` 코딩 전 필독 (ADR 아님).

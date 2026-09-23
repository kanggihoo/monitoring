# 문서 인덱스

> 작성 규칙은 [`conventions/documentation.md`](conventions/documentation.md) 참조 (지연 생성 원칙).

## 1. 운영 및 참조

| 문서 | 목적 |
|---|---|
| [`../AGENTS.md`](../AGENTS.md) | AI 코딩 진입점, 라우팅 표 |
| [`architecture/overview.md`](architecture/overview.md) | 패키지 구조, 계층 책임, 배치 규칙 |
| [`usage.md`](usage.md) | 로컬 기동/종료, 트러블슈팅 |
| [`decisions-later.md`](decisions-later.md) | 미결정 사항 (백로그 + 보류/조율) |
| [`ERD.md`](ERD.md) | DB 스키마 다이어그램 및 제약 조건 |

## 2. 규약 (conventions/)

| 문서 | 목적 |
|---|---|
| [`conventions/code.md`](conventions/code.md) | Java/Spring 컨벤션 (DTO, JPA, 인증, 테스트, Flyway) |
| [`conventions/api.md`](conventions/api.md) | API 경로 규칙, HTTP 상태 코드 기준, 에러 포맷 |
| [`conventions/environment.md`](conventions/environment.md) | 환경변수(`.env` vs `application.yml`) 배치 기준 |
| [`../../docs/conventions/git.md`](../../docs/conventions/git.md) | 리포 공통 브랜치 전략, 커밋 메시지 규약 |
| [`conventions/documentation.md`](conventions/documentation.md) | 문서별 역할/경계/트리거 |
| [`templates/`](templates/) | 문서 작성 템플릿 (ADR, Flow, Context, Decisions Later) |

## 3. 지연 생성 대상

| 위치 | 생성 시점 |
|---|---|
| `CONTEXT.md` | 도메인 용어 확정 시 (`templates/context.md`) |
| `flows/` | 기능 플로우 정의 시 (`templates/flow.md`) |
| `adr/` | 3-AND 충족 아키텍처 결정 발생 시 (`templates/adr.md`) |

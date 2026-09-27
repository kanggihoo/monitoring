# AGENTS.md

> 리포 전역 AI 진입점. **작업할 컨텍스트의 `AGENTS.md`를 먼저 읽는다.**

## 1. 컨텍스트 진입점

| 컨텍스트 | 설명 | 진입점 |
|---|---|---|
| `backend/` | Spring Boot 백엔드 | [backend/AGENTS.md](backend/AGENTS.md) |
| `frontend/` | Next.js 프론트엔드 | [frontend/AGENTS.md](frontend/AGENTS.md) |
| `infra/` | 관측성 스택 설정 (Alloy·Loki·Tempo·Prometheus·Grafana, 루트 `compose.yml`) | [infra/README.md](infra/README.md) · 용어 [CONTEXT.md](CONTEXT.md) |
| `load/` | k6 부하 테스트 (docker) | [load/README.md](load/README.md) |

---

## 2. 리포 전역 공통 규약

- **이슈 트래커 (Jira)**: [`docs/agents/issue-tracker.md`](docs/agents/issue-tracker.md) (키: `S15P21A605`)
- **Git 컨벤션**: [`docs/conventions/git.md`](docs/conventions/git.md) (브랜치 전략, Jira 키 커밋 메시지 규칙)

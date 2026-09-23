# AGENTS.md — ai

> AI 컨텍스트 작업 진입점. 문서 맵과 라우팅 표.
> 리포 전역 규칙(이슈 트래커, 컨텍스트 경계, Git 컨벤션)은 [../AGENTS.md](../AGENTS.md).
>
> 팀이 GitLab 에서 읽는 진입점은 [README.md](README.md)다 — 팀에 필요한 내용은 그쪽에 쓴다.
> 이 파일은 `backend/`·`frontend/` 와 같은 방식으로 커밋한다.

## 스택

Python 3.11 · FastAPI · uv · pydantic-settings · pytest · ruff.

**Spring 백엔드만 호출하는 내부 서비스다.** 브라우저가 직접 호출하지 않는다.
그래서 CORS 를 열지 않고, 사용자 JWT 도 검증하지 않는다. 인증은 서비스 간 공유 시크릿
(`X-Internal-Api-Key`) 하나다 → [docs/conventions/api.md](docs/conventions/api.md).

## 라우팅 표

| 질문 | 읽을 문서 |
| --- | --- |
| 이 코드를 어디에 넣나? | [docs/architecture/overview.md](docs/architecture/overview.md) |
| 엔드포인트를 어떻게 만드나? | [docs/conventions/api.md](docs/conventions/api.md) |
| 코드를 어떤 스타일로 쓰나? | [docs/conventions/code.md](docs/conventions/code.md) |
| 설정값을 `.env`와 `config.py` 중 어디에 두나? | [docs/conventions/code.md](docs/conventions/code.md) §설정값 |
| 요청이 어떤 순서로 흐르나? | `docs/flows/` (미작성) |
| 왜 이렇게 결정했나? | [docs/adr/](docs/adr/) |
| 이 개념을 무슨 단어로 부르나? | `docs/CONTEXT.md` (미작성) |
| 아직 안 정해진 건 뭔가? | [docs/decisions-later.md](docs/decisions-later.md) |
| 복기 리포트가 어떻게 조립되나? | [docs/architecture/report-assembly.md](docs/architecture/report-assembly.md) |
| 리포트를 어디로 발급하나? | `app/routers/reports.py` (`POST /api/ai/reports/daily`·`/season`) |
| 투자자 성향을 어떻게 판정하나? | [docs/specs/AI_투자자유형_분석.md](docs/specs/AI_투자자유형_분석.md) (항목·알고리즘·계약·테스트가 전부 여기) |
| 리포트를 어떻게 돌려보나? | [docs/usage.md](docs/usage.md) 7절 — `scripts/preview_report.py --llm --api` |
| 백엔드에서 무엇을 받나? | [docs/architecture/backend-data-contract.md](docs/architecture/backend-data-contract.md) · 필드는 `app/schemas/inbound.py` |
| 리포트 문구 블록을 어떻게 만드나? | [prompts/report-blocks.md](prompts/report-blocks.md) |
| 헤드라인을 어떻게 생성하나? | [prompts/daily-headline.md](prompts/daily-headline.md) · `app/services/headline.py` |
| 이 컨텍스트가 대체 뭔가? | [README.md](README.md) (팀 공유용 개요) |
| 브랜치·커밋 이름을 어떻게 쓰나? | [../docs/conventions/git.md](../docs/conventions/git.md) |
| 문서를 어디에 쓰나? | [../backend/docs/conventions/documentation.md](../backend/docs/conventions/documentation.md) |

`flows/`·`CONTEXT.md`는 아직 없다. **지연 생성이므로 정상이다.** 비어 있으면 조용히
넘어가고, 첫 항목이 생길 때 만든다.

`docs/usage.md`는 라우팅 표에 **없다.** 사람이 실행할 때 읽는 문서이고, 코드를 짤 때는 읽지 않는다.

## 다른 컨텍스트에서 빌려 쓰는 규칙

git·문서 규약·템플릿은 `backend/` 것이 진실이다. 이 컨텍스트 전용 사본을 만들지 않는다.
무엇을 어디서 빌려 쓰고 무엇이 다른지는 [docs/README.md](docs/README.md) "다른 컨텍스트에서 빌려 쓰는 규칙" 표가 진실이다.

## 작업 전 필수

- **이슈 트래커는 Jira.** 브랜치·커밋에 `S15P21A605-<번호>`를 반드시 넣는다
  → `../docs/agents/issue-tracker.md`
- **API 명세는 코드가 진실.** FastAPI 가 `/openapi.json`·`/docs`를 자동 생성한다.
  명세용 `.md`를 만들지 않는다.
- **커밋 전에** `uv run ruff format . && uv run ruff check . && uv run pytest` 를 통과시킨다.
- **`.env`는 커밋하지 않는다.** 키를 추가하면 `.env.example`에 의미를 주석으로 남긴다.

## 문서 인덱스

전체 목록은 [docs/README.md](docs/README.md).

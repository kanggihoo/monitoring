# ai

Spring 백엔드가 호출하는 **내부 AI 서비스.**

## 스택

Python 3.11 · FastAPI · uv · pydantic-settings · pytest · ruff

## 성격

브라우저가 직접 호출하지 않는 내부 서비스다. 그래서 **CORS 를 열지 않고, 사용자 JWT 도 검증하지 않는다.**
인증은 백엔드와 나눠 갖는 공유 시크릿(`X-Internal-Api-Key`) 하나다
→ [docs/conventions/api.md](docs/conventions/api.md).

## 빠른 시작

```bash
uv sync
cp .env.example .env
uv run python -m app.main
```

세 단계가 전부다 — `uv sync` 가 가상환경 생성과 의존성 설치를 함께 처리한다.

확인:

```bash
curl http://localhost:8000/health
```

기본 주소는 `http://localhost:8000`, Swagger UI 는 `http://localhost:8000/docs`.
사전 요구사항·컨테이너 실행·자주 겪는 문제는 [docs/usage.md](docs/usage.md).

## 패키지 구조

```
app/
├── main.py       ← 앱 조립 (라우터 마운트, 미들웨어, 예외 핸들러)
├── routers/      ← HTTP 경계
├── services/     ← 비즈니스 로직
├── models/       ← 도메인 데이터 구조, 모델 가중치 로딩
├── schemas/      ← 요청·응답 pydantic 모델
└── core/         ← 설정, 로깅, 인증
```

계층별 책임과 의존 방향은 [docs/architecture/overview.md](docs/architecture/overview.md).

## 엔드포인트

| 경로 | 인증 | 용도 |
| --- | --- | --- |
| `GET /health` | 없음 | Liveness. 컨테이너 healthcheck·로드밸런서용 |
| `GET /api/ai/health/internal` | `X-Internal-Api-Key` | 백엔드와 키가 맞는지 확인 |
| `POST /api/ai/reports/daily` | `X-Internal-Api-Key` | 일일 매매 코멘터리. 폐장 정산 뒤 일차마다 한 번 |
| `POST /api/ai/reports/season` | `X-Internal-Api-Key` | 시즌 총평과 투자자 유형. 3일차 정산 뒤 한 번 |
| `/docs` | 없음 | Swagger UI |

도메인 엔드포인트는 전부 `/api/ai/` 아래에 만든다 → [docs/conventions/api.md](docs/conventions/api.md).

리포트 두 개는 백엔드만 부른다. 목업으로 그 경로를 그대로 돌려 보려면:

```bash
uv run python scripts/preview_report.py --llm --api
```

## 문서

| 문서 | 목적 |
| --- | --- |
| [docs/architecture/overview.md](docs/architecture/overview.md) | 디렉터리·계층 책임, 의존 방향, 라우트 추가 절차 |
| [docs/conventions/code.md](docs/conventions/code.md) | 타입 힌트, pydantic, 설정값, 예외, 로깅, async, 테스트 |
| [docs/conventions/api.md](docs/conventions/api.md) | 경로 규칙, 내부 호출 인증, CORS, 응답, 상태 코드 |
| [docs/usage.md](docs/usage.md) | 로컬 실행 절차, 컨테이너 빌드, 자주 겪는 문제 |
| [docs/decisions-later.md](docs/decisions-later.md) | 미결정 사항 (백로그 + 보류/조율) |
| [docs/architecture/report-assembly.md](docs/architecture/report-assembly.md) | 복기 리포트 조립 규칙과 필요한 데이터 |
| [docs/architecture/backend-data-contract.md](docs/architecture/backend-data-contract.md) | 백엔드와 주고받는 데이터 (논의용) |
| [prompts/report-blocks.md](prompts/report-blocks.md) | 리포트 문구 블록 생성 프롬프트 |

전체 인덱스는 [docs/README.md](docs/README.md).

## 작업 전 확인

- **브랜치·커밋에 Jira 이슈 키를 넣는다** → [../docs/conventions/git.md](../docs/conventions/git.md)
- **API 명세는 코드가 진실.** FastAPI 가 `/openapi.json`·`/docs`를 자동 생성한다. 명세용 `.md`를 만들지 않는다.
- **커밋 전에** 세 개를 통과시킨다.

  ```bash
  uv run ruff format . && uv run ruff check . && uv run pytest
  ```

- **`.env`는 커밋하지 않는다.** 키를 추가하면 `.env.example`에 의미를 주석으로 남긴다.
- **GMS 키는 크레딧을 쓴다.** `AI_LLM_API_KEY` 를 채우면 헤드라인이 LLM 생성으로 바뀐다.
  값을 로그·커밋 어디에도 남기지 않는다 → [docs/usage.md](docs/usage.md) 3절.
- **모델 가중치를 커밋하지 않는다.** `.gitignore`가 `*.pt`·`*.bin`·`*.safetensors`·`*.onnx`
  등을 막는다. 경로는 `.env`의 `AI_MODEL_DIR`로 넘긴다.
- **의존성은 `uv add`로 추가한다.** `pip install`을 직접 쓰지 않는다. `uv.lock`은 커밋한다.

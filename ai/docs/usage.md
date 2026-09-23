# 실행 방법 — ai

> 로컬에서 AI 서비스를 띄우는 절차. 명령은 모두 `ai/` 디렉터리에서 실행한다.
> 코드를 어디에 넣는지·어떻게 짜는지는 [`architecture/overview.md`](architecture/overview.md),
> [`conventions/code.md`](conventions/code.md) 참조.

## 1. 사전 요구사항

- Git
- **uv** — Python 자체도 uv 가 관리한다. 별도로 Python 을 설치하지 않아도 된다.

Windows (PowerShell):

```powershell
winget install --id=astral-sh.uv -e
```

macOS / Linux:

```bash
curl -LsSf https://astral.sh/uv/install.sh | sh
```

확인:

```bash
uv --version
```

## 2. 의존성 설치

```bash
uv sync
```

`.venv/`가 만들어지고 `uv.lock`에 고정된 버전 그대로 설치된다.
`.python-version`이 3.11 을 가리키므로, 없으면 uv 가 알아서 받아온다.

> **`uv sync` 대신 `pip install`을 쓰지 않는다.** 락 파일을 우회하면 팀원 간 버전이 갈린다.
> 의존성을 추가할 때도 `uv add <패키지>`를 쓴다 (`pyproject.toml`과 `uv.lock`이 함께 갱신된다).

## 3. 환경 변수

`.env.example`을 복사해 `.env`를 만든다.

```bash
cp .env.example .env
```

PowerShell:

```powershell
Copy-Item .env.example .env
```

`.env`는 커밋하지 않는다. **로컬은 기본값 그대로 동작한다.**

`AI_INTERNAL_API_KEY`를 비워두면 내부 호출 검증이 꺼진 채로 뜨고 기동 로그에 WARNING 이 남는다.
Spring 백엔드와 붙여서 확인할 때는 값을 채우고 **양쪽에 같은 값**을 넣는다.

```bash
uv run python -c "import secrets; print(secrets.token_urlsafe(32))"
```

헤드라인을 LLM 으로 만들려면 `AI_LLM_API_KEY` 하나만 채운다. **키가 곧 스위치다** — 비워두면
지금까지처럼 블록 문장을 쓴다. 키는 [gms.ssafy.io](https://gms.ssafy.io) 에서 받고, 토큰
사용량만큼 크레딧이 차감된다.

```
AI_LLM_API_KEY=<발급받은 키>
```

주소(`https://gms.ssafy.io/gmsapi/api.openai.com/v1`)와 모델(`gpt-5-mini`)은 `app/core/config.py`
의 기본값이라 적지 않아도 된다. 연결 확인은 아래 7절의 `scripts/check_llm.py`.

각 변수의 의미는 `.env.example`의 주석을 참고한다.

## 4. 실행

**한 줄 기동 명령이다.**

```bash
uv run python -m app.main
```

`.env`의 `AI_HOST`·`AI_PORT`를 읽어 uvicorn 을 띄우고, `AI_ENV=local`이면 코드 변경 시
자동 재시작(reload)된다. 별도의 스크립트 파일을 두지 않는 이유는 이 한 줄이 OS 와 무관하게
같은 동작을 하기 때문이다 (`.sh`/`.ps1` 을 두 벌 관리할 이유가 없다).

커밋 전 검사는 세 줄이다.

```bash
uv run ruff format . && uv run ruff check . && uv run pytest
```

포트를 직접 지정해서 띄우려면:

```bash
uv run uvicorn app.main:app --reload --port 8000
```

기본 주소는 `http://localhost:8000`, API 문서는 `http://localhost:8000/docs`.

## 5. 실행 확인

```bash
curl http://localhost:8000/health
```

```json
{"success":true,"status":200,"message":"OK","data":{"env":"local","version":"0.1.0"},"timestamp":"..."}
```

내부 호출 키가 백엔드와 맞는지 확인한다 (`AI_INTERNAL_API_KEY`를 채운 경우).

```bash
curl -H "X-Internal-Api-Key: <키>" http://localhost:8000/api/ai/health/internal
```

키가 틀리면 401 이 온다. 키를 **비워둔 로컬에서는 검증이 꺼져 있어 항상 200** 이므로,
연동 확인은 양쪽에 값을 채운 뒤에 한다.

## 6. 컨테이너로 실행

배포 방식은 아직 정해지지 않았다 ([`decisions-later.md`](decisions-later.md)).
현재는 이미지 빌드까지만 확인한다.

```bash
docker build -t s15p21a605-ai .
```

```bash
docker run --rm -p 8000:8000 --env-file .env s15p21a605-ai
```

`--env-file .env`를 빼면 `AI_ENV`가 기본값 `local`로 뜬다. `dev`·`prod`로 띄우려면
`AI_INTERNAL_API_KEY`가 반드시 있어야 하고, 없으면 **기동이 실패한다** (의도된 동작).

## 7. 복기 리포트 확인

**명령 하나로 끝까지 돌려 본다.** 백엔드가 보낼 payload 와 같은 모양의 목업을 만들어
문장까지 조립한다 ([ADR-0002](adr/0002-backend-sends-preprocessed-payload.md)).

```bash
uv run python scripts/preview_report.py --llm --api
```

| 붙이는 것 | 무엇이 달라지나 |
| --- | --- |
| (없음) | 함수를 직접 불러 조립한다. 헤드라인은 블록 문장이다 |
| `--llm` | 헤드라인만 GMS 로 생성한다. 원래 나왔을 블록 문장을 괄호로 함께 찍는다 |
| `--api` | `POST /api/ai/reports/daily` 를 실제로 호출하고 **그 응답**을 찍는다. 통합 뒤로 그 한 번이 리포트 전부를 낸다 |
| `--api --url http://localhost:8000` | 이미 떠 있는 서버로 보낸다 (`--url` 이 없으면 서버 없이 앱을 그 자리에서 호출한다) |

**`--api` 가 백엔드와 같은 경로다.** 같은 엔드포인트, 같은 헤더(`X-Internal-Api-Key`),
같은 응답 봉투를 지난다. 계약·라우팅·인증·조립이 한 번에 걸린다. 이 모드에서 헤드라인을
생성할지는 **서버 쪽 `.env`(`AI_LLM_API_KEY`)가 정하므로** `--llm` 을 따로 붙이지 않아도 된다
(같이 붙여도 두 번 부르지 않는다).

| 옵션 | 값 |
| --- | --- |
| `--seed` | 같은 유저를 다시 보려면 출력 첫 줄의 seed 를 넣는다. 생략하면 매번 다른 유저 |
| `--users` | 연속으로 볼 인원 (기본 1). 판정은 고정 참조 표를 쓰므로 **인원이 판정을 바꾸지 않는다** — 분포를 보고 싶을 때만 늘린다 (300 이 유형 분포를 보는 기준이다) |
| `--day` | 일차 `1`·`2`·`3`. 체결의 게임 거래일 구간이 달라진다 |
| `--executions` | 사용자당 체결 건수 (기본 100) |
| `--json` | 입력 payload 전문을 함께 찍는다 |
| `--yes` | 모델 호출 상한(20건)을 넘겨서 진행한다 |

**크레딧이 나가는 실행은 20건에서 멈춘다.** `--llm` 은 인원만큼, `--api` 는 일일과 시즌을
둘 다 태우므로 인원의 두 배가 호출 수다. `--users 300 --api` 는 600건이라 멈추고, `--yes`
를 붙여야 지나간다. **유형 분포를 보는 `--users 300` 은 모드 없이 돌리면 호출이 0 이다** —
분포는 판정이 만드는 것이라 모델과 무관하다.

Git Bash 에서 VS Code 실행 버튼(▷)을 쓰면 인터프리터 경로의 백슬래시가 먹혀 실패한다
(`c:S15P21A605ai.venv...`). 그때는 아래 래퍼를 쓴다 — 옵션은 같다.

```bash
scripts/report.sh --llm --api
```

랜덤 목업 유저의 **입력 → 판정 → 문장** 세 단계를 다 찍는다. 문장만 보면 왜 그 문장이
나왔는지 알 수 없어서 조건 벡터와 그 근거를 함께 낸다.

### 커밋 전 검사 — 한 줄로

```bash
scripts/check.sh
```

```
format   OK
lint     OK
test     1061 passed in 5.31s
```

서식·린트·테스트 셋을 돌리고 **요약만** 찍는다. 깨지면 그 자리에 깨진 테스트 이름이 붙고,
본문은 `scripts/check.sh --why` 로 한 번 더 부른다. `--fix` 를 붙이면 ruff 가 고칠 수 있는
것은 고치고 간다.

**셋을 따로 부르는 것과 결과는 같다.** 다르게 만든 것은 출력뿐이다 — `pytest -q` 는 1,000건이면
점을 1,000개 찍고, 한 건이 깨지면 그 테스트가 남긴 로그와 stdout 을 통째로 게워낸다. 평소에
알아야 하는 것은 "깨졌나 안 깨졌나" 하나다.

| | 출력 |
| --- | --- |
| `uv run ruff format . && uv run ruff check . && uv run pytest` | 약 1.4KB |
| `scripts/check.sh` | 약 60B |
| `scripts/check.sh` (한 건 실패) | 약 200B — 깨진 이름만 |

`uv run pytest` 를 직접 불러도 헤더와 긴 트레이스백은 나오지 않는다 (`pyproject.toml` 의
`addopts`). 원인을 다 봐야 할 때는 이렇게 덮어쓴다.

```bash
uv run pytest --tb=long --show-capture=all -k 이름조각
```

### 무엇이 막혔는지 가릴 때

| 명령 | 보는 것 |
| --- | --- |
| `uv run python scripts/check_llm.py` | GMS 연결. 설정·프롬프트·호출·검사를 순서대로 짚는다 |
| `uv run python scripts/check_blocks.py --spread 100` | 블록 재고와 조건별 후보 수 |
| `uv run python scripts/mock_data.py --users 30 --out mock.json` | 목업 payload 만 (JSON) |
| `uv run pytest tests/test_reports.py -q` | 엔드포인트 — 인증·계약·응답 봉투 |
| `uv run pytest tests/test_inbound.py tests/test_extract.py -q` | 계약 변환과 판정 |
| `uv run pytest tests/test_report_blocks.py -q` | 블록 정적 검사와 조합 전수 |

`check_llm.py` 는 **실제로 한 번 호출하므로 크레딧이 조금 차감된다.** 생성된 문장은
길이(45자)·마침표 종결·숫자 금지·금지어 네 가지 검사를 지나고, 하나라도 걸리면 조용히 블록
문장으로 돌아간다 — 폐장 직후에는 다시 걸어볼 시간이 없다. 문안 기준은
[`../prompts/daily-headline.md`](../prompts/daily-headline.md).

### 얼마나 썼는지 볼 때

두 엔드포인트가 배치마다 로그 한 줄에 사용량을 남긴다. 누적이 아니라 **그 요청이 쓴 양**이다.

```
일일 문구 98/100건 (체결 없음 2건 제외, 헤드라인 생성 95건, 21.4s) day=1 ·
  호출 98건(실패 3) · 토큰 15,238 (입력 11,022 · 출력 4,216, 그중 추론 1,568)
```

`호출` 은 200 으로 돌아온 수다 — 전송 실패·4xx 는 토큰이 나가지 않아 `실패` 로만 잡힌다.
빈 본문(200)은 추론 토큰을 이미 쓴 뒤라 양쪽에 다 잡히고, `추론` 이 출력에 가깝게 붙으면
`AI_LLM_MAX_COMPLETION_TOKENS` 가 모자란 것이다.

`check_blocks.py` 의 조건별 후보 수가 곧 "같은 상황의 사용자가 몇 가지 문장 중에서
갈리는가"다. `--texts` 를 붙이면 문장까지 전부 찍는다.

### 서버에 직접 쏘아 볼 때

```bash
uv run python -m app.main
```

```bash
curl -X POST http://localhost:8000/api/ai/reports/daily \
  -H "X-Internal-Api-Key: <키>" -H "Content-Type: application/json" \
  -d @mock-daily.json
```

요청 모양은 `http://localhost:8000/docs` 가 정본이다. 목업 payload 는
`scripts/mock_data.py --out mock.json` 이 만든 파일의 `daily`·`season` 을 그대로 쓰면 된다.

설계 배경은 [`architecture/report-assembly.md`](architecture/report-assembly.md),
받는 값의 계약은 [`architecture/backend-data-contract.md`](architecture/backend-data-contract.md).

## 8. 자주 겪는 문제

### `uv run` 이 패키지를 못 찾는다

`uv sync`를 먼저 실행했는지, 그리고 명령을 `ai/`에서 실행했는지 확인한다.
`uv run`은 현재 디렉터리의 `pyproject.toml`을 기준으로 `.venv`를 찾는다.

### 포트 충돌

백엔드가 8080, 프론트엔드가 3000, AI 가 8000 이다.
8000 이 이미 사용 중이면 `.env`의 `AI_PORT`를 바꾼다.

```env
AI_PORT=8001
```

### 한글 로그가 깨지거나 로그 줄이 사라진다

`app/core/logging.py`가 stdout 을 UTF-8 로 고정하므로 예외로 죽지는 않는다.
그래도 콘솔에 깨져 보이면 터미널 코드페이지를 UTF-8 로 바꾼다.

```powershell
chcp 65001
```

### 환경 변수를 읽지 못한다

- 파일명이 정확히 `.env`인지 확인 (`.env.local` 등은 읽지 않는다)
- 접두사 `AI_`가 붙었는지 확인 — `PORT=8000`은 무시되고 `AI_PORT=8000`만 반영된다
- 명령을 `ai/`에서 실행했는지 확인

### `.env`를 바꿨는데 반영되지 않는다

`get_settings()`가 값을 캐시하고, uvicorn 의 reload 는 `.py` 변경만 감시한다.
서버를 완전히 재시작한다.

### 프론트엔드에서 부르면 CORS 에러가 난다

의도된 기본 동작이다. 이 서비스는 Spring 백엔드만 호출하는 내부 서비스라
`AI_CORS_ALLOW_ORIGINS`가 비어 있고, 그때는 CORS 미들웨어를 아예 붙이지 않는다.
프론트 직접 호출은 아직 결정되지 않았다 → [`decisions-later.md`](decisions-later.md).
정해졌다면 `.env`에 origin 을 채우고 서버를 재시작한다.

```env
AI_CORS_ALLOW_ORIGINS=http://localhost:3000
```

### 헤드라인이 계속 블록 문장으로 나온다

`scripts/check_llm.py` 를 먼저 돌린다. 네 단계 중 어디서 막혔는지 알려준다.
기동 로그에 `헤드라인 LLM 비활성` 이 찍혔으면 키가 안 읽힌 것이다 — 이름이
`AI_LLM_API_KEY` 인지, `AI_LLM_ENABLED` 를 `false` 로 적어두지 않았는지 본다.
호출은 되는데 문장이 안 나오면 검사에 걸린 것이고, 그때는 WARNING 로그가 남는다.

### 모델 가중치는 어디에 두나

**저장소에 커밋하지 않는다.** `.gitignore`가 `*.pt`·`*.bin`·`*.safetensors`·`*.onnx` 등을
막고 있다. 로컬 디렉터리에 두고 `.env`의 `AI_MODEL_DIR`로 경로를 넘긴다.
S3·HuggingFace Hub 중 무엇으로 받을지는 아직 미정이다 → [`decisions-later.md`](decisions-later.md).

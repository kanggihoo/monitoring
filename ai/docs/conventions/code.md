# 코드 컨벤션 — ai

> 코드를 **어떻게** 짜는지에 대한 규칙.
> **어디에** 넣는지는 [`../architecture/overview.md`](../architecture/overview.md).
> 엔드포인트 규약은 [`api.md`](api.md).

---

## 1. 도구와 검사

- **패키지 관리는 uv 하나로 한다.** `pip install`을 직접 쓰지 않는다.
  의존성 추가는 `uv add <pkg>` (개발용은 `uv add --dev <pkg>`) — `pyproject.toml`과
  `uv.lock`이 함께 갱신된다.
- **`uv.lock`은 커밋한다.** 팀원 간 버전이 갈리지 않게 하는 유일한 장치다.
- **린터·포매터는 ruff 하나로 한다.** 설정은 `pyproject.toml`에 있다.
- 커밋 전에 세 개를 통과시킨다.

  ```bash
  uv run ruff format . && uv run ruff check . && uv run pytest
  ```

---

## 2. 타입 힌트

- **모든 함수·메서드에 인자와 반환 타입을 적는다.** 반환이 없으면 `-> None`.
  라우트 함수의 반환 타입은 OpenAPI 스키마의 원본이므로 특히 생략하지 않는다.
- 모듈 첫 줄에 `from __future__ import annotations`를 둔다.
  런타임 평가를 미뤄서 순환 import 와 문자열 따옴표를 줄인다.
- `Optional[X]`·`Union[X, Y]` 대신 `X | None`·`X | Y`를 쓴다.
- FastAPI 의존성은 `Annotated[T, Depends(...)]` 형태로 쓴다. 기본값 위치의 `= Depends(...)`는
  쓰지 않는다 (인자 순서가 강제되고 재사용이 어렵다).

---

## 3. pydantic 모델

- **요청·응답 모델을 분리한다.** 역할이 드러나게 이름 붙인다
  (`SummaryCreateRequest`, `SummaryResponse`).
- **JSON 경계에 나가는 모델은 `CamelModel`을 상속한다** (`app/schemas/common.py`).
  Python 은 snake_case, JSON 은 camelCase 로 나간다. 필드명을 camelCase 로 쓰지 않는다.
- 내부에서만 쓰는 값 객체는 pydantic 대신 `@dataclass(frozen=True)`로 둔다.
  경계가 아닌 곳에 검증 비용을 지불하지 않는다.
- **가변 기본값을 직접 쓰지 않는다.** `= []`·`= {}` 대신 `Field(default_factory=list)`.
- 외부 응답을 모델로 받을 때 필드가 빠질 수 있으면 `X | None = None`으로 받는다.
  없는 값을 필수로 선언해두면 조회가 500 이 된다.

---

## 4. 설정값

**어디에 둘지** 판단 기준은 백엔드
[`environment.md`](../../../backend/docs/conventions/environment.md)와 같다.
`application.yml` 자리를 `app/core/config.py`의 `Settings` 필드 기본값이 맡는다.

| 성격 | 위치 |
| --- | --- |
| 비밀값 (내부 호출 키, 모델 API 키) | `.env` — 커밋 금지 |
| 환경마다 달라지는 값 (포트, 로그 레벨, 환경 이름) | `.env` |
| 환경과 무관한 기본값 (경로 접두사, 타임아웃) | `Settings` 필드 기본값 |

- **환경변수 접두사는 `AI_`로 고정한다.** `Settings.internal_api_key` ↔ `AI_INTERNAL_API_KEY`.
  이름이 어긋나면 로컬에서는 기본값으로 조용히 뜨고 배포에서 터진다.
- 키를 추가하면 **`.env.example`에 의미를 주석으로 남긴다.** 이 `.md`에 변수 표를 만들지 않는다
  (중복되고 낡는다).
- **설정을 `get_settings()`로만 읽는다.** 모듈 로드 시점에 `Settings()`를 만들어
  전역 변수에 담지 않는다 — 테스트에서 갈아끼울 수 없게 된다.
  라우트·의존성에서는 `Annotated[Settings, Depends(get_settings)]`로 주입받는다.
- `os.environ`을 직접 읽지 않는다. 모든 설정은 `Settings`를 지난다.

---

## 5. 예외 처리

- **라우트 안에서 `try/except`로 응답을 만들지 않는다.** 예외를 던지고
  `app/exceptions.py`의 전역 핸들러가 응답 봉투로 변환한다.
- `HTTPException`을 직접 던지지 않는다. `AiServiceError` 하위 클래스를 만들어 던진다.
  새 실패 유형이 생기면 클래스를 추가한다 (상태 코드 표는 [`api.md`](api.md)).
- `except Exception:`으로 삼켜서 정상 응답을 내지 않는다. 삼킬 이유가 있으면
  **왜 삼키는지 주석**을 남기고 로그를 남긴다.
- 외부 응답에 stack trace·내부 구현 정보를 노출하지 않는다 (서버 로그에만 기록).

---

## 6. 로깅

- `print()` 금지. `app.core.logging.get_logger(__name__)`을 쓴다.
- **`%s` 스타일 지연 포매팅을 쓴다.** f-string 을 로거에 넘기지 않는다
  (레벨에 걸러질 문자열을 미리 만든다).

  ```python
  logger.info("요약 생성 완료 (문서=%s, 소요=%.2fs)", document_id, elapsed)
  ```

- 예외는 `logger.exception(...)`으로 남긴다 (traceback 이 함께 붙는다).
- **민감정보 금지**: 내부 호출 키, 모델 API 키, 사용자 금융 데이터, 프롬프트·응답 원문.
  디버깅에 필요하면 길이·해시·식별자만 남긴다.

---

## 7. async

- **라우트는 `async def`로 쓴다.**
- `async def` 안에서 **블로킹 호출을 하지 않는다** — `time.sleep`, 동기 HTTP 클라이언트,
  동기 파일·DB I/O. 이벤트 루프가 멈춰 전체 처리량이 죽는다.
  외부 호출은 비동기 클라이언트(`httpx2`의 `AsyncClient`)를 쓴다.
- 블로킹밖에 없는 라이브러리는 `await anyio.to_thread.run_sync(...)`로 감싼다.
- **모든 외부 호출에 타임아웃을 준다.** 기본값은 `Settings.upstream_timeout_seconds`.
- `AsyncClient`를 요청마다 새로 만들지 않는다. 앱 수명주기(`lifespan`)에서 한 번 만들어
  `app.state`에 담고 재사용한다 (커넥션 풀이 매 요청 버려진다).

---

## 8. 테스트

- **파일·구조는 `app/`을 그대로 따라간다** (`app/api/routes/health.py` → `tests/test_health.py`).
- **메서드명은 영어** `method_scenario_expected` 형식.
- **docstring 은 한글** "주제_상황_기대" 한 줄 (예: `"""헬스체크_인증없이호출_성공응답봉투를반환한다"""`).
  백엔드의 `@DisplayName` 규칙과 같은 역할이다.
- 1 테스트 1 시나리오.
- **테스트가 개발자의 `.env`에 좌우되지 않게 한다.** `Settings(_env_file=None, ...)`로
  값을 명시하고 `tests/conftest.py`의 `settings`·`client` fixture 를 쓴다.
- 전역 예외 핸들러의 응답을 검증할 때는 `TestClient(app, raise_server_exceptions=False)`.
  기본값이면 예외가 그대로 올라와 핸들러를 지나치지 않는다.
- 검증용 임시 라우트는 **테스트 안에서** 앱에 붙인다 (`tests/test_exception_handlers.py` 참고).
  프로덕션 라우터에 테스트용 엔드포인트를 만들지 않는다.

---

## 9. 인라인 주석

- What ❌ (코드가 말한다), Why ⭕
- **실측으로 알아낸 함정은 반드시 주석으로 남긴다.** 예: `app/core/logging.py`의
  Windows cp949 인코딩 처리 — 이유가 없으면 다음 사람이 지운다.
- 결정이 ADR 에 있으면 `# ADR-NNNN 참조`로 연결한다.

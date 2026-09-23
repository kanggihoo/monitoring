# 구조 개요

> **코드를 어디에 넣을지**에 답한다. 실행 절차는 [`../usage.md`](../usage.md),
> 코드 스타일은 [`../conventions/code.md`](../conventions/code.md).

## 디렉터리

```
ai/
├── app/
│   ├── main.py            ← 앱 조립만 (create_app, lifespan, 미들웨어, 라우터 마운트)
│   ├── exceptions.py      ← 도메인 예외 + 전역 예외 핸들러
│   ├── routers/           ← HTTP 경계. 요청 하나당 함수 하나
│   │   └── __init__.py    ← 라우터 집계. 새 라우터를 여기서 한 번 등록한다
│   ├── services/          ← 비즈니스 로직 (첫 기능에서 채운다)
│   ├── models/            ← 도메인 데이터 구조, 모델 가중치 로딩 (첫 모델에서 채운다)
│   ├── schemas/           ← pydantic 모델 (요청·응답 경계, 공통 응답 봉투)
│   └── core/              ← 횡단 관심사 (설정, 로깅, 내부 호출 인증)
├── tests/                 ← app/ 구조를 그대로 따라간다
└── docs/
```

## 계층과 책임

| 위치 | 책임 | 넣지 않는 것 |
| --- | --- | --- |
| `app/routers/` | 요청 파싱, 의존성 주입, 응답 봉투 생성 | 비즈니스 로직, 외부 API 호출 |
| `app/services/` | 비즈니스 로직, 프롬프트 조립, 후처리 | HTTP 관심사(status, header) |
| `app/models/` | 도메인 데이터 구조, 모델 가중치 로딩 래퍼 | 경계로 나가는 DTO (→ `schemas/`) |
| `app/schemas/` | 경계에서 오가는 데이터 모양 | 로직 |
| `app/core/` | 설정, 로깅, 인증 등 모든 계층이 쓰는 것 | 도메인 지식 |

`services/`·`models/`는 패키지만 있고 **아직 비어 있다.** 각 `__init__.py` 의 docstring 이
무엇을 넣는 자리인지 설명한다. 첫 기능·첫 모델에서 모듈을 추가한다.

## 의존 방향

```
routers → services → models
   └─────────┴──────────┴──→ core, schemas, exceptions
```

- **역참조 금지.** `core`가 `routers`·`services`를 import 하지 않는다.
  (`core/security.py`가 `exceptions`를 쓰는 것은 허용 — `exceptions`는 계층이 아니다.)
- **`routers`가 `models`를 직접 만지지 않는다.** 라우트에서 바로 모델을 부르고 싶어지면
  그건 `services`가 필요하다는 신호다.

## 두 개의 라우터

`app/routers/__init__.py` 가 두 벌을 내보내고 `main.py` 가 각각 마운트한다.

| 라우터 | 마운트 | 용도 |
| --- | --- | --- |
| `root_router` | 접두사 없음 | `GET /health` — 컨테이너 healthcheck, 로드밸런서 |
| `api_router` | `/api/ai` | 도메인 엔드포인트 전부 |

헬스체크만 접두사 밖에 두는 이유는 [`../conventions/api.md`](../conventions/api.md) 참조.

## 라우트를 하나 추가할 때

1. `app/schemas/`에 요청·응답 모델을 만든다 (재사용 없으면 라우터 모듈 안에 둬도 된다).
2. `app/routers/<도메인>.py`에 `router = APIRouter(prefix="/<도메인>", tags=["<도메인>"])`.
3. 내부 호출 인증이 필요하면 라우터에 `dependencies=[Depends(require_internal_key)]`.
4. `app/routers/__init__.py`의 `api_router`에 `include_router` 한 줄 추가.
5. 로직은 `app/services/`에 두고 라우터는 얇게 유지한다.
6. `tests/test_<도메인>.py` 추가.

접두사(`/api/ai`)는 `main.py`가 한 번만 붙인다. 라우터 모듈에서 다시 붙이지 않는다.

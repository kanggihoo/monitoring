# API 작성 규칙 — ai

> 새 엔드포인트를 만들 때 지키는 규약.
> **개별 엔드포인트 명세는 여기에 쓰지 않는다** — FastAPI 가 만드는 `/openapi.json`이 단일 진실.
> 상태 코드 의미는 [`../../../backend/docs/conventions/api.md`](../../../backend/docs/conventions/api.md)
> "에러 식별" 표를 그대로 따른다. 아래에는 **이 컨텍스트에서 다른 것만** 적는다.

## 경로 규칙

- 도메인 엔드포인트는 **`/api/ai/`로 시작한다.** 접두사는 `main.py`가 한 번만 붙인다
  (`Settings.api_prefix`).
- **버전 세그먼트를 두지 않는다.** `/api/ai/v1/...` 형태를 만들지 않는다.
- 도메인 단위로 묶는다: `/api/ai/{도메인}/{동작 또는 리소스}`.
- 백엔드(`/api/...`)와 접두사가 겹치지 않는 이유: 나중에 두 서비스를 하나의 게이트웨이 뒤에
  두더라도 경로가 충돌하지 않는다.

## 예외 경로

접두사를 쓰지 않는 경로는 **운영 도구와 프레임워크가 정한 것뿐이다.**

| 경로 | 소유 | 이유 |
| --- | --- | --- |
| `GET /health` | 이 서비스 (`root_router`) | 컨테이너 healthcheck·로드밸런서가 찌른다. 이들은 애플리케이션의 접두사 정책을 모르고, 접두사가 바뀌어도 헬스체크는 그대로여야 한다 |
| `/docs`, `/openapi.json` | FastAPI | — |

**새 엔드포인트를 이 목록에 추가하지 않는다.** 도메인 기능은 예외 없이 `/api/ai/` 아래로 간다.

## 인증 — 내부 호출 키

이 서비스는 **Spring 백엔드만 호출한다.** 사용자 JWT 를 검증하지 않는다.

- 보호 엔드포인트는 `X-Internal-Api-Key` 헤더를 요구한다.
  라우터에 `dependencies=[Depends(require_internal_key)]`를 붙인다.
- **키 비교는 `secrets.compare_digest`로 한다.** `==`는 쓰지 않는다 (타이밍 공격).
- 키 불일치·누락은 **401**이다. 백엔드의 401(사용자 인증 실패)과 의미가 다르지만
  상태 코드는 같다 — 이 서비스의 호출자는 사람이 아니라 백엔드다.
- **키 값을 로그·응답·에러 메시지에 남기지 않는다.**
- 공개 엔드포인트는 **개별로만** 만든다. 현재 공개는 `GET /health` 하나다
  (컨테이너 healthcheck·로드밸런서용). 키 일치 여부는 `GET /api/ai/health/internal` 로 확인한다.
- `AI_INTERNAL_API_KEY`가 비어 있으면 **local 에서만** 검증이 꺼진다 (기동 시 WARNING).
  `AI_ENV`가 `dev`·`prod`면 `Settings` 검증이 기동 자체를 실패시킨다 — 조용히 열린 채로
  배포되는 것을 막기 위해서다.

## CORS

**기본값은 꺼짐이다.** `AI_CORS_ALLOW_ORIGINS` 가 비어 있으면 `main.py` 가 CORS 미들웨어를
**아예 붙이지 않는다.** 브라우저가 직접 호출하지 않는 내부 서비스이기 때문이다.

프론트엔드가 직접 호출하기로 정해지면 `.env` 의 `AI_CORS_ALLOW_ORIGINS` 에 origin 을
콤마로 구분해 채운다. 값은 **FE 이슈의 허용 origin 과 반드시 일치시킨다.**

```env
AI_CORS_ALLOW_ORIGINS=http://localhost:3000,https://<배포 도메인>
```

`allow_origins=["*"]` 를 쓰지 않는다. `allow_credentials=True` 와 함께 쓰면 브라우저가
요청 자체를 거부하고, 무엇보다 이 서비스는 아무 origin 에나 열려도 되는 서비스가 아니다.

CORS 를 켜는 것은 인증 구조 변경을 동반한다 (사용자 JWT 검증이 필요해진다).
켜기 전에 [`../decisions-later.md`](../decisions-later.md)의 "프론트엔드 직접 호출 여부"를
먼저 정리한다.

## 응답

- 성공·실패 모두 **공통 응답 봉투**를 쓴다 → `app/schemas/common.py`.
  필드 구성은 백엔드 `conventions/code.md` §6 과 같고, JSON 키는 **camelCase**로 낸다
  (Spring 쪽에서 같은 DTO 모양으로 역직렬화하기 위해서).
- 라우트 안에서 `try/except`로 응답을 만들지 않는다. 예외를 던지고
  `app/exceptions.py`의 전역 핸들러가 변환한다.
- 외부 응답에 stack trace·내부 구현 정보·프롬프트 원문을 노출하지 않는다 (서버 로그에만).
- 라우트 함수의 반환 타입 애노테이션(`-> ApiResponse[HealthData]`)을 생략하지 않는다.
  FastAPI 가 이걸로 OpenAPI 스키마를 만든다.

## 상태 코드 — 이 컨텍스트 추가분

| status | 사용 상황 |
| --- | --- |
| 401 | 내부 호출 키 누락·불일치 (`UnauthorizedError`) |
| 502 | 외부 모델·API 연동 실패 (`UpstreamError`) |
| 503 | 일시적 장애 — 타임아웃, 레이트 리밋 (`ServiceUnavailableError`). 애플리케이션 버그(500)와 구분한다 |

새 실패 유형이 생기면 `app/exceptions.py`에 `AiServiceError` 하위 클래스를 만든다.
라우트에서 `HTTPException`을 직접 던지지 않는다.

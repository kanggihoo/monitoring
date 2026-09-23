# 문서 인덱스 — ai

문서의 역할·경계·트리거는 백엔드
[`conventions/documentation.md`](../../backend/docs/conventions/documentation.md)를 따른다
(컨텍스트별 사본을 만들지 않는다).
없는 문서는 **아직 안 쓴 것**이다 (지연 생성). 내용이 생겼을 때만 추가한다.

| 문서 | 목적 |
| --- | --- |
| [../README.md](../README.md) | 컨텍스트 개요, 빠른 시작, 작업 전 확인 |
| [architecture/overview.md](architecture/overview.md) | 디렉터리·계층 책임, 의존 방향, 라우트 추가 절차 |
| [usage.md](usage.md) | 로컬 실행 절차, 컨테이너 빌드, 자주 겪는 문제 |
| [adr/0002-backend-sends-preprocessed-payload.md](adr/0002-backend-sends-preprocessed-payload.md) | 백엔드가 전처리한 payload 를 받기로 한 결정 (입력 경로의 진실) |
| [adr/0001-ai-reads-database-directly.md](adr/0001-ai-reads-database-directly.md) | AI 가 DB 를 직접 조회하기로 한 결정. **ADR-0002 로 대체됐다** |
| [decisions-later.md](decisions-later.md) | 미결정 사항 (백로그 + 보류/조율) |
| [architecture/report-assembly.md](architecture/report-assembly.md) | 복기 리포트 조립 규칙. 어떤 값이 어느 판정에 쓰이는지 |
| [architecture/backend-data-contract.md](architecture/backend-data-contract.md) | 백엔드에서 무엇을 어떤 모양으로 받는지. 필드의 정본은 `app/schemas/inbound.py` |
| [specs/AI_투자자유형_분석.md](specs/AI_투자자유형_분석.md) | 투자자 성향 5단계 판정(온보딩 선언 ↔ 실제 거래)의 **유일한 명세.** 항목·알고리즘·계약·테스트 |
| [Plan/블록_초안_투자자유형.md](Plan/블록_초안_투자자유형.md) | 그 기능의 문구 블록 55개 초안. 확정되면 `data/blocks/*.json` 으로 옮기고 이 파일은 지운다 |
| [AI_기능명세서.md](AI_기능명세서.md) | 지금 구현돼 있는 것(헤드라인·성향 판정·블록 시스템)의 상태. **4절 일부는 4축 시절 서술이 남아 있다** |
| [../prompts/report-blocks.md](../prompts/report-blocks.md) | 리포트 문구 블록 생성 프롬프트와 검수 체크리스트 |
| [../prompts/daily-headline.md](../prompts/daily-headline.md) | 런타임에 GMS 게이트웨이로 보내는 헤드라인 프롬프트 |

## 규칙 (conventions/)

| 문서 | 목적 |
| --- | --- |
| [conventions/code.md](conventions/code.md) | 타입 힌트, pydantic, 설정값, 예외, 로깅, async, 테스트 |
| [conventions/api.md](conventions/api.md) | 경로 규칙, 내부 호출 인증, CORS, 응답, 상태 코드 |

## 다른 컨텍스트에서 빌려 쓰는 규칙

리포 전체에 걸치는 규칙은 여기 복사하지 않는다 (한 종류의 정보는 한 곳에만).

| 규칙 | 진실의 위치 | 이 컨텍스트에서의 차이 |
| --- | --- | --- |
| 브랜치·커밋·Merge | [git.md](../../docs/conventions/git.md) | 없음. 리포 전체에 그대로 적용된다 |
| 문서의 역할·경계·트리거 (ADR 3-AND 포함) | [documentation.md](../../backend/docs/conventions/documentation.md) | 없음. 판단 기준을 그대로 쓴다 |
| ADR·flow·CONTEXT 작성 형식 | [templates/](../../backend/docs/templates/) | 없음. 형식을 그대로 쓴다 |
| 응답 봉투 필드 구성 | [code.md](../../backend/docs/conventions/code.md) §6 | 없음. `app/schemas/common.py`가 같은 모양을 낸다 |
| 에러 상태 코드 의미 | [api.md](../../backend/docs/conventions/api.md) | 401 의 의미만 다르다 (사용자 인증 → 내부 호출 키) |
| 설정값을 `.env`에 둘지 판단 | [environment.md](../../backend/docs/conventions/environment.md) | `application.yml` 자리를 `app/core/config.py`가 맡는다 |

## 아직 비어 있는 것

| 위치 | 언제 만드나 |
| --- | --- |
| `CONTEXT.md` | 이 컨텍스트의 도메인 용어가 확정될 때 (`../../backend/docs/templates/context.md`) |
| `flows/` | 첫 기능 플로우가 생길 때 (`../../backend/docs/templates/flow.md`) |

# AGENTS.md — backend

> 백엔드 작업 진입점. 전역 규칙은 [../AGENTS.md](../AGENTS.md).

## AI 필독 순서

1. **코드 작성 3원칙** (항상 적용)
2. **`build.gradle`** — 스택/의존성/빌드 태스크
3. **`docs/architecture/overview.md`** — 패키지/계층 배치
4. **`docs/conventions/code.md`** — 코드 스타일/JPA/테스트
5. **`docs/conventions/api.md`** — API 경로/에러 규약
6. **`docs/adr/`** — 아키텍처 결정 이유 (Why)
7. **`docs/CONTEXT.md`** — 도메인 용어집

- **지연 생성**: 없는 문서는 추측 금지. 코드베이스 관측 우선.
- **우선순위**: 문서 > 코드베이스 > 외부 모범사례 (문서-코드 충돌 시 코드가 진실, 문서 갱신 제안).
- **이슈/커밋**: Jira 키 `S15P21A605-<번호>` 필수 ([`../docs/conventions/git.md`](../docs/conventions/git.md), [`../docs/agents/issue-tracker.md`](../docs/agents/issue-tracker.md)).

---

## 코드 작성 3원칙

문서를 읽은 뒤 코드를 쓸 때 항상 적용한다. 이 셋은 무엇을 만들지가 아니라 **어떻게 일할지**에 대한 규칙이다.

### 1. 생각을 먼저 (Think Before Coding)

추측하지 마라. 혼란을 숨기지 마라. 트레이드오프를 드러내라.

해석을 조용히 하나 골라 진행하는 것이 가장 흔한 실패다. 명시적 추론을 강제한다.

- **가정을 말로 꺼낸다** — 불확실하면 추측하지 말고 묻는다.
- **해석이 갈리면 모두 제시한다** — 모호할 때 혼자 하나를 고르지 않는다.
- **더 단순한 방법이 있으면 반대한다** — 요청받은 대로만 하지 말고 말한다.
- **혼란스러우면 멈춘다** — 무엇이 불명확한지 이름 붙여 질문한다.

### 2. 단순함이 먼저 (Simplicity First)

문제를 푸는 최소한의 코드. 예측성 코드는 쓰지 않는다. 과설계 경향을 억제한다.

- 요청받지 않은 기능 ❌
- 한 곳에서만 쓰는 코드에 추상화 ❌
- 요청받지 않은 "유연성", "설정 가능성" ❌
- 일어날 수 없는 상황에 대한 예외 처리 ❌
- 200줄이 50줄이 될 수 있으면 다시 쓴다

> **판정 기준**: 시니어 엔지니어가 "이건 과하다"고 할 것 같으면 단순화한다.

### 3. 최소 침습 (Surgical Changes)

꼭 건드려야 하는 것만 건드린다. 내가 만든 쓰레기만 치운다.

**기존 코드를 수정할 때:**
- 인접한 코드·주석·포매팅을 "개선"하지 않는다
- 깨지지 않은 것을 리팩터링하지 않는다
- 내 스타일과 다르더라도 기존 스타일에 맞춘다
- 무관한 죽은 코드를 발견하면 말만 하고 지우지 않는다

**내 변경이 고아를 만들었을 때:**
- 내 변경 때문에 안 쓰이게 된 import·변수·함수는 제거한다
- 기존에 이미 죽어 있던 코드는 요청받지 않으면 건드리지 않는다

> **판정 기준**: 변경된 모든 줄이 사용자 요청으로 직접 추적되어야 한다.

---

## 라우팅 표

| 상황 / 목적 | 읽을 문서 |
|---|---|
| 패키지 구조 / 계층별 책임 | [docs/architecture/overview.md](docs/architecture/overview.md) |
| 기능 실행 시퀀스 / 플로우 | [docs/flows/](docs/flows/) |
| 착수 전 기능 명세 (범위/사용자 스토리) | [../docs/specs/](../docs/specs/) (리포 전역, 컨텍스트 무관) |
| 아키텍처 결정 맥락 (Why) | [docs/adr/](docs/adr/) |
| 도메인 고유 용어 | [docs/CONTEXT.md](docs/CONTEXT.md) |
| 미결정 사항 / 백로그 | [docs/decisions-later.md](docs/decisions-later.md) |
| DB 테이블 관계도 | [docs/ERD.md](docs/ERD.md) (엔티티가 진실) |
| Java/Spring/JPA/테스트 컨벤션 | [docs/conventions/code.md](docs/conventions/code.md) |
| API 경로 / 상태코드 규약 | [docs/conventions/api.md](docs/conventions/api.md) (명세는 Swagger) |
| 브랜치 전략 / Jira 커밋 규칙 | [../docs/conventions/git.md](../docs/conventions/git.md) |
| 환경변수(`.env` vs `yml`) 배치 | [docs/conventions/environment.md](docs/conventions/environment.md) |
| 문서 갱신 / 작성 판단 | [docs/conventions/documentation.md](docs/conventions/documentation.md) |

> `docs/usage.md`는 운영/실행 문서. 코딩 시 미참조.

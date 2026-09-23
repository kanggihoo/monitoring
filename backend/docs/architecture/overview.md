# 아키텍처 개요

> 패키지 구조 / 계층 책임. 코딩 스타일은 [`../conventions/code.md`](../conventions/code.md) 참조.

## 1. 패키지 구조

Base: `com.ssafy.a605.backend`

```
com/ssafy/a605/backend/
├── BackendApplication.java
├── global/                         # 공통 인프라 (도메인 무관)
│   ├── config/                     # Security, WebMvc, Swagger, WebSocket
│   ├── exception/                  # BusinessException, GlobalExceptionHandler
│   ├── response/                   # ApiResponse, ErrorResponse, PageResponse
│   └── websocket/                  # STOMP destination, 봉투, 발행 지점 (ADR-0004)
└── domain/                         # 도메인 패키지 (지연 생성)
    └── {domain}/                   # auth, user, project 등
        ├── controller/             # 엔드포인트
        ├── service/                # 비즈니스 로직, 트랜잭션
        ├── repository/             # Spring Data JPA
        ├── entity/                 # JPA 엔티티
        └── dto/                    # Request/Response DTO
```

---

## 2. 계층별 책임

| 계층 | 역할 | 핵심 규칙 |
|---|---|---|
| **Controller** | HTTP 라우팅, `@Valid` 검증, Swagger | Entity 노출 금지 (DTO 반환), 로직 위임 |
| **Service** | 비즈니스 로직, 트랜잭션 경계 | DTO ↔ Entity 변환, 외부 API 조율 |
| **Repository** | DB 접근 (`JpaRepository`) | N+1 방지 (`fetch join` / `@EntityGraph`) |
| **Entity** | 도메인 데이터 / 상태 변경 | `@Setter` 금지, 도메인 메서드 캡슐화 |
| **DTO** | 데이터 전송 | `record` 지향, 역직렬화 시 원시타입 금지 |

---

## 3. 의존성 원칙

- **단방향 흐름**: `Controller → Service → Repository → Entity`
- **계층 참조**: `domain` → `global` 허용 (`global` → `domain` 역참조 금지).
- **도메인 간 협력**: 엔티티 직접 참조 지양, Service 호출로 협력.
- **예외 — 다른 도메인 Repository 직접 주입**: 아래 두 경우에만 허용한다. 그 외에는 Service 를 부른다.
  - **집계 조회**: 한 응답이 여러 도메인의 행을 조인해 만들어지고, 중간 Service 가 그 조합을 그대로
    되돌려 주는 것 외에 할 일이 없을 때. 예: `RankingService`(계좌·포지션·체결·사용자),
    `SeasonQueryService`(계좌·종목·공모).
  - **삭제 조율**: 자식 테이블을 외래키 순서로 지워야 하고 그 순서 자체가 규칙일 때. 예:
    `AccountPurgeService`(계좌 자식 10개). 도메인마다 한 줄짜리 삭제 Service 를 두면 순서만 분산되고
    위반 지점은 그대로 남는다 → [ADR-0007](../adr/0007-withdrawal-leaves-no-trace.md).



# 백엔드 코드 컨벤션

> 코드 작성 규칙. 패키지/계층 구조는 [`../architecture/overview.md`](../architecture/overview.md) 참조.

---

## 1. DTO & Record

- **원칙**: Entity 직접 반환 금지 (항상 DTO 사용). Request/Response DTO 명확히 분리.
- **Record vs Class**: 불변 데이터/단순 DTO는 `record` 우선. 상태 변경이나 세부 설정 필요 시에만 `class`.

### JSON 매핑 시 원시 타입(primitive) 금지

- **규칙**: JSON 역직렬화 대상 `record`의 숫자·boolean은 반드시 박싱 타입(`Boolean`/`Integer`/`Long`) 사용 (`@RequestBody`, 외부 API 응답 공통).
- **이유**: Jackson 3(`tools.jackson`)은 `record`의 원시 타입 필드가 누락되면 기본값 대신 예외를 발생시킴 (`HttpMessageNotReadableException` 400 또는 500 에러 발생).
- **생략 기본값 처리**: 판정 메서드 작성 (`Boolean.TRUE.equals(field)`).
  - 예: `JenkinsJobInjectRequest.targetsAll()`, `JenkinsBuild.inProgress()`

### 데이터 변환 (Mapping)

- **Request DTO → Entity**: DTO 내 `toEntity()` 인스턴스 메서드.
- **Entity → Response DTO**: DTO 내 `from()` 정적 팩토리 메서드.
- **수정 요청**: DTO 변환 없이 Service에서 Entity 도메인 메서드로 직접 변경.
- **복잡한 변환**: 별도 Mapper 클래스(예: `UserMapper`)로 분리 (`toDto`, `convert`, `map` 등 모호한 명명 금지).

---

## 2. Entity & JPA

- **생성자**: `@NoArgsConstructor(access = AccessLevel.PROTECTED)` 필수.
- **상태 변경**: `@Setter` 금지. `changeXxx()` 등 의도가 드러나는 도메인 메서드 사용.
- **연관관계**: `FetchType.LAZY` 기본 적용 (N+1 방지는 `fetch join` / `@EntityGraph` 활용).
- **트랜잭션**: Service 상단 `@Transactional(readOnly = true)` 기본 선언, CUD 메서드에만 `@Transactional` 개별 부여.

---

## 3. Lombok & Java 스타일

- **금지**: `@Data`, 무분별한 `@Setter`
- **DI**: `@RequiredArgsConstructor` (생성자 주입)
- **조회**: `@Getter`
- **`@Builder`**: 인자/선택 옵션이 많은 경우에만 제한적 사용 (기본은 생성자 지향).

---

## 4. 검증 및 예외 처리

- **검증 분리**: DTO 형식 검증(`@Valid` + Bean Validation) vs Service 비즈니스 검증(권한, 중복, 존재 여부).
- **예외 처리**: Controller 내 `try-catch` 금지. 전역 예외 처리 체계(`global.exception`) 활용.
- **보안**: 외부 응답에 stack trace 및 내부 구현 정보 노출 금지 (서버 로그에만 기록).
- **에러 식별**: HTTP 상태 코드 + `message` 기반 (별도 `AUTH_001` 같은 비즈니스 코드 미사용).

---

## 5. 로깅 (Logging)

- **로거**: `@Slf4j` 사용 (`System.out.println` 금지).
- **예외 로깅**: Throwable 객체를 마지막 인자로 전달.
  ```java
  log.error("Failed to process user request: {}", userId, e);
  ```
- **보안**: 토큰, 비밀번호, Secret, SSH 키 등 민감정보 로깅 금지.

---

## 6. 응답 구조 (Response Structure)

- **참조 패키지**: `com.ssafy.a605.backend.global.response` (`ApiResponse`, `ErrorResponse`)
- **성공 포맷**: `success`, `status`, `message`, `data`, `timestamp`
- **204 예외**: `204 No Content`는 응답 본문이 없으므로 `ApiResponse`를 사용하지 않는다.
- **실패 포맷**: `success`, `status`, `message`, `errors`, `path`, `timestamp`
- **페이징 포맷**: `content`, `page`, `size`, `totalElements`, `totalPages`, `hasNext`

---

## 7. 인증 및 외부 연동

- **세션 기반 인증**: 로그인 성공 시 세션 발급, 세션 ID로 인가 판단 (서비스 인증용 JWT 미발급) → [ADR-0001](../adr/0001-session-based-authentication.md).
- **세션 저장소**: Redis (`spring-boot-starter-session-data-redis`, `spring.session.data.redis.repository-type=indexed`).
  `@EnableRedisIndexedHttpSession`을 직접 붙이지 않는다 — Boot의 `SessionRepository` 자동 설정이 물러나면서 `spring.session.timeout`이 무시된다.
- **세션 ID 운반**: 웹은 쿠키, 앱은 `X-Auth-Token` 헤더. 커스텀 `HttpSessionIdResolver`로 분기하며, `CompositeHttpSessionIdResolver`는 **사용 금지**(웹 응답에도 헤더를 실어 `HttpOnly`를 무력화) → [ADR-0002](../adr/0002-web-cookie-app-header-transport.md).
- **세션 쿠키**: `HttpOnly`, `Secure`, `SameSite=Lax` 세 속성 모두 지정 (응답 본문에 쿠키값 미포함).
- **세션 페이로드**: 인증 주체만 담는다. 잔고·보유 종목 등 **도메인 데이터 저장 금지**(세션에 담는 순간 DB의 캐시가 되어 기기 간 불일치가 생긴다). `role`은 authorities로 담되, **변경 시 해당 User의 세션을 무효화**한다.
- **CSRF 보호**: 쿠키 기반 요청은 **CSRF 비활성화(`csrf().disable()`) 금지**. `X-Auth-Token` 헤더로 인증된 요청만 검사에서 제외한다 — 브라우저가 커스텀 헤더를 자동 첨부하지 않아 CSRF가 성립하지 않는다.
- **CORS**: 설정하지 않는다. 웹은 프록시로 same-origin이고, 네이티브 앱은 `Origin`이 없다.
- **공개 엔드포인트**: `SecurityConfig`에 경로를 개별 명시한다 (와일드카드 전체 개방 금지).
- **OAuth2 상태**: 임시 상태(`state` 등)는 세션(`IF_REQUIRED`)에 유지.

---

## 8. 테스트 컨벤션

- **메서드명**: `method_scenario_expected` (영어)
- **`@DisplayName`**: `"주제_상황_기대"` 형식 한글 문장.
- **단위**: 1 테스트 1 시나리오 검증 (`@Nested` 그룹화 활용).
- **테스트 분리**:
  - **단위 테스트 (`./gradlew test`)**: Docker 없이 실행 (`container` 태그 제외).
  - **통합 테스트 (`./gradlew integrationTest`)**: PostgreSQL/Redis Testcontainers 기반 (`@Tag("container")`).
  - **느린 테스트 (`./gradlew slowTest`)**: 벽시계를 실제로 기다리는 것 (`@Tag("slow")`). 개발 루프의 `integrationTest` 에서 빠지므로 **CI 는 이 태스크도 함께 돌려야 한다.**
- **컨테이너 공유**: `support/ContainersConfig` (`@ServiceConnection`)를 `@Import`하여 사용.
  컨테이너는 Spring 빈이라 **컨텍스트마다 한 쌍이 새로 뜬다.** 속성·`@Import`·웹 환경이 하나라도 다르면 컨텍스트가 갈리므로, 같은 환경을 검증하는 테스트는 그 셋을 **글자 단위로 같게** 둔다. 설정 차이 자체가 검증 대상인 테스트(기능 비활성화 등)만 갈라 둔다.

---

## 9. Javadoc

- **대상**: `public`/`protected` 대상 (단, 단순 getter, `@Override`, 테스트는 생략).
- **태그 순서**: `@param` → `@return` → `@throws` (한글 평서문 서술).
  ```java
  /**
   * 로그인 자격 증명을 검증하고 세션을 발급한다.
   *
   * @param request 로그인 요청 본문
   * @throws BusinessException 자격 증명이 일치하지 않을 때 (401)
   */
  public void login(LoginRequest request) { ... }
  ```

---

## 10. DB 마이그레이션 (Flyway)

- **위치**: `src/main/resources/db/migration`
- **파일명 규칙**: `V{버전}__{설명}.sql` (언더스코어 2개 필수, 버전 정수 순번 권장).
  - 예: `V1__init_schema.sql`, `V2__add_user_table.sql`
- **JPA 스키마 자동 생성 금지**: `spring.jpa.hibernate.ddl-auto=validate` 사용 (Flyway가 단일 진실).
- **불변 원칙**: 배포/공유된 기존 마이그레이션 SQL 수정 절대 금지. 스키마 변경 시 새 버전(`V{next}`) 스크립트 추가.
- **테스트 데이터 시드**: `src/main/resources/db/testdata`에 **반복 적용(`R__`)** 배치.
  - 기능 플래그 활성화 시에만 Flyway 탐색 경로에 추가([ADR-0003](../adr/0003-test-support-isolation.md)). 프로덕션 혼입 불가.
  - **시드 위치는 스위치 단위로 나눈다.** `db/testdata`는 `TEST_SUPPORT_ENABLED`, `db/replay-testdata`는
    `REPLAY_FIXTURE_ENABLED`가 연다. 서로 다른 스위치의 시드를 한 폴더에 두면 한쪽만 켠 환경에서
    데이터가 없거나 원하지 않는 데이터가 실린다. 새 스위치를 만들면 폴더도 함께 만들고 여기에 적는다.
    재생 픽스처 스위치가 `REPLAY_MODE`와 갈라져 있는 이유는 [`ReplayTestDataConfig`](../../src/main/java/com/ssafy/a605/backend/domain/replay/config/ReplayTestDataConfig.java)
    주석에 있다 — 실행 환경(조작 경로)과 시드 적재는 dev에서 서로 반대 값을 요구한다.
  - 인원수 등 가변 값 포함되므로 버전 마이그레이션 금지 (수정 시 체크섬 불일치 기동 실패).
- **SQL 내 `${...}` 사용 금지**: 주석 포함 전체 금지 (Flyway 플레이스홀더 파싱 기동 실패 방지).


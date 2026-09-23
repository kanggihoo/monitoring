# ADR-0004: 실시간 전송을 STOMP over WebSocket으로 세운다

- 상태: 승인
- 결정일: 2026-09-04
- 관련: [ADR-0001](0001-session-based-authentication.md), [ADR-0002](0002-web-cookie-app-header-transport.md), [ADR-0003](0003-test-support-isolation.md), [ADR-0005](0005-public-read-access.md), `S15P21A605-44`

## 문맥

프론트엔드는 이미 STOMP 클라이언트를 붙였고 목 서버가 `/ws`에서 봉투를 내려주고 있다. 계약도 destination 넷·메시지 19종까지 확정돼 있다([`../../../docs/projects/market-replay-api/ws.md`](../../../docs/projects/market-replay-api/ws.md)).

**그런데 Spring 쪽에는 WebSocket 코드가 한 줄도 없었다.** 프론트를 실서버에 붙이면 `/ws`는 404도 아니고 `.anyRequest().authenticated()`에 걸려 401로 끊긴다. 그 결과 프론트는 목 서버를 벗어날 수 없고, 게임 시계(`S15P21A605-31`)가 Tick을 만들어도 내보낼 통로가 없었다.

이 ADR을 백엔드 문서에 남기는 이유는 근거의 중복이 아니라 **발견 가능성**이다 — `backend/AGENTS.md`의 필독 순서에 `docs/adr/`은 있지만 `docs/projects/market-replay-api/`는 없다. 백엔드만 읽는 사람이 "왜 서버에 `@MessageMapping`이 하나도 없나"를 찾을 곳이 여기다.

## 결정

1. **STOMP over WebSocket**: 순수 WebSocket이 아니라 STOMP를 쓴다. SockJS는 쓰지 않는다.
2. **`SEND` 프레임 없음**: 애플리케이션 메시지가 전부 서버→클라이언트다. 따라서 서버에 `@MessageMapping`이 하나도 없고 `setApplicationDestinationPrefixes`를 부르지 않는다.
3. **내장 simple broker**: 외부 메시지 브로커(RabbitMQ·ActiveMQ) 릴레이를 붙이지 않는다. 하트비트 `10000,10000`.
4. **핸드셰이크에서 인증한다**: ~~`SecurityConfig`를 수정하지 않고~~ 기존 필터 체인에 그대로 맡긴다. **인증용 `ChannelInterceptor`를 만들지 않는다.** — **[ADR-0005](0005-public-read-access.md)로 일부 개정 (2026-09-09).** `/ws`를 공개 경로에 넣기 위해 `SecurityConfig`를 수정한다. **나머지는 그대로 유지된다** — 인증은 여전히 핸드셰이크에서 끝나고 프레임 단위 인증은 없다. 미인증의 개인 큐 구독을 막는 것은 인증이 아니라 **인가**이며 7번의 구독 검증 인터셉터에 붙는다. 그리고 이 결정이 유지되기 **때문에** 인증 상태가 바뀌면 연결을 새로 맺어야 한다 — Principal이 핸드셰이크에 고정되어 기존 연결에 개인 구독만 추가할 수 없다.
5. ~~**미인증 연결을 허용하지 않는다**: `/ws`를 공개 경로에 넣지 않는다.~~ **[ADR-0005](0005-public-read-access.md)로 뒤집혔다 (2026-09-09).** 기각 근거였던 "REST 시세 인증 우회 경로"는 REST를 함께 열면서 소멸했다. 당시 판단의 기록으로 남긴다.
6. **봉투를 만드는 지점을 하나로 둔다**: `global/websocket`의 발행 컴포넌트. destination은 문자열이 아니라 열거값.
7. **계약에 없는 destination 구독을 거부한다**: 구독 검증용 `ChannelInterceptor` 하나. 4번의 "인증 인터셉터 없음"과 별개다.
8. **허용 출처를 `app.base-url` 하나로 명시한다**: `*`로 열지 않는다.
9. **관통 확인용 임시 트리거를 테스트 지원 플래그 아래 둔다**: `POST /api/v1/admin/replay/advance`가 구현되면 삭제한다.

## 검토한 대안들

### 순수 WebSocket (`@ServerEndpoint` / `WebSocketHandler`)

- 장점: 프레이밍이 단순하고 계약에 STOMP 어휘가 들어오지 않는다.
- 단점: 개인 메시지 6종을 나르려면 `userId → 세션` 매핑을 직접 관리해야 한다. 재연결·중복 접속·세션 정리가 모두 우리 코드가 된다.
- 기각 사유: Spring의 `/user/` prefix 라우팅이 그 매핑을 이미 해 준다. 직접 만들면 그 코드가 부재 판정·다중 기기와 얽힌다.

### SockJS

- 장점: WebSocket을 지원하지 않는 브라우저 대응.
- 단점: STOMP 위가 아니라 **아래**에 있는 별도 전송 계층이라 `/ws/info` 전송 협상·세션 URL·XHR 폴백 프레이밍이 계약과 목 서버에 들어온다(목 서버 80줄 → 수백 줄).
- 기각 사유: 이 게임의 대상 환경에 그런 브라우저가 없다.

### `CONNECT` 프레임의 `X-Auth-Token`을 `ChannelInterceptor`로 검사 (계약 원안)

- 장점: 브라우저 WebSocket API가 커스텀 헤더를 붙일 수 없다는 제약을 우회한다.
- 단점: 인증 경로가 둘이 된다. 한쪽만 고치는 사고가 나고, 세션 조회·주체 구성을 기존 필터 체인과 두 번 구현한다.
- 기각 사유: 그 제약은 **브라우저**에 대한 것이고 앱(Expo)은 브라우저가 아니다. 앱도 핸드셰이크에 헤더를 실을 수 있으므로 웹·앱이 같은 경로로 인증된다. 계약 문서의 인증 절을 이 결정에 맞춰 고쳤다.

### 미인증 연결 허용 (`/topic/chat` 열람)

- 장점: 기획서 12.1.5의 미인증 채팅 읽기가 실시간으로 성립한다.
- 단점: `/topic/tick`이 시세를 나르므로 **REST 시세 인증을 우회하는 경로**가 생긴다.
- 기각 사유: REST와 WS는 함께 열거나 함께 닫아야 한다. REST 채팅 조회가 이미 공개이므로 기획서 12.1.5는 새로고침으로 충족되며, 기획서가 실시간까지 보장하지는 않는다.

## 이유

**핸드셰이크 인증에는 새 코드가 없다.** WS 핸드셰이크는 `Upgrade` 헤더가 붙은 HTTP GET이므로 기존 필터 체인을 그대로 탄다. `CookieOrHeaderHttpSessionIdResolver`가 세션 ID를 해석하고(쿠키 또는 `X-Auth-Token`), `LoginUser` 주체가 서면 Spring의 기본 핸드셰이크 핸들러가 그것을 WebSocket 세션에 싣는다. `LoginUser.getName()`이 이미 `String.valueOf(userId)`라 `/user/` 라우팅 키가 그대로 정해진다. ADR-0002가 "리졸버 하나로 통일"을 결정한 것과 일관되고, 부수 효과로 부하 테스트가 쓰는 앱 헤더 경로가 코드 추가 없이 동작한다.

**미인증을 닫는 근거는 "미래 차단"이 아니다.** 응답 절단은 인증 여부와 무관하게 걸리고 재생 데이터는 과거 공개 정보이므로 인증이 미래를 막아 주지 않는다. 실제 근거는 **REST 정책과의 일치** 하나다. 로그인 전 랜딩 화면은 필요해지면 REST를 여는 것으로 충분하다 — Tick 간격이 31~36초라 30초 폴링과 실시간의 체감 차이가 거의 없다.

**봉투 조립을 한 곳에 모은 것은 추상화가 아니라 반복 방지다.** 봉투(`{type, tickSeq, gameDate, payload}`)를 바꾸면 프론트 전체와 재접속 동기화가 함께 깨진다. 메시지 19종이 각자 조립하면 그 구조물의 생성 지점이 19곳이 되고, 한 곳에서 `gameDate`를 빠뜨려도 컴파일이 통과해 프론트에서만 깨진다.

**구독 검증 인터셉터는 인증과 다른 문제를 푼다.** 브로커 접두사(`/topic`)에 걸리기만 하면 오타 난 destination도 정상 등록된다. 그러면 "메시지가 오지 않는다"는 사실만 남고 이유는 어디에도 나타나지 않아 프론트가 원인을 찾는 데 오래 걸린다. 계약이 `ERROR` 프레임을 규정하고 있으므로 그대로 구현한다.

**허용 출처를 기본값(same-origin)으로 두지 않았다.** 기능 명세는 기본값 유지를 적었지만, 그대로 두면 **두 환경에서 모두** 핸드셰이크가 403으로 끊긴다.

- **개발** — 프론트는 `:3000`, Spring은 `:8080`이다. Next의 rewrite가 업그레이드 요청을 넘기지 못해 브라우저가 백엔드에 직결한다. 명세가 정한 브라우저 확인 절차 자체가 성립하지 않는다.
- **배포** — 프레임워크의 same-origin 판정은 `Origin` 헤더를 **서블릿이 본** scheme·host·port와 비교한다. `server.forward-headers-strategy`를 켜지 않았으므로([`../decisions-later.md`](../decisions-later.md)의 클라이언트 IP 항목이 같은 이유로 미룬 설정) 그 값은 nginx 뒤의 `http://localhost:8080`이고, 브라우저가 보내는 `https://<공개 호스트>`와 절대 같아지지 않는다.

즉 기본값 유지는 개발 편의 문제가 아니라 **배포에서 WS가 아예 붙지 않는 선택**이었다. `app.base-url`은 OAuth 복귀 주소로도 쓰여 배포에서 이미 정확해야 하는 값이므로 새 실패 지점이 늘지 않는다 — 틀리면 WS보다 로그인이 먼저 깨진다. `*`로 열지 않으므로 허용 범위도 넓어지지 않는다.

**메시지 컨버터를 오버라이드하지 않는다.** 클래스패스에 Jackson 3(`tools.jackson`)이 있으면 Spring이 그것을 자동 선택한다. 이 프로젝트에는 `spring.jackson.*` 설정도 `ObjectMapper` 커스터마이징 빈도 없으므로 REST와 WS의 직렬화가 갈릴 자리가 없다. 금액을 문자열로 내보내는 것은 DTO 필드를 `String`으로 선언하는 문제이지 Jackson 설정 사안이 아니다.

## 결과

- **`/ws`는 `/api/v1/{도메인}/{리소스}` 규칙 밖이다.** 전송 채널이지 리소스가 아니므로 [`../conventions/api.md`](../conventions/api.md)의 예외 경로 표에 명시했다. `/api/ws`로 옮기지 않는 이유는, WS가 긴 `proxy_read_timeout`을 요구해 어느 쪽이든 nginx 전용 location이 필요하고 옮기면 프론트 4곳만 바뀌기 때문이다.
- **배포에 nginx `/ws` location이 필요하다** (`Upgrade`/`Connection` 헤더 + 긴 `proxy_read_timeout`). 그 설정이 이 리포에 없어 [`../decisions-later.md`](../decisions-later.md)로 넘겼다.
- **`TaskScheduler` 빈이 둘이다 — 하트비트용과 게임용을 분리했다.** 이 ADR은 원래 `@EnableWebSocketMessageBroker`가 등록하는 `messageBrokerTaskScheduler` 하나만 두고 게임 시계의 `@Scheduled`가 그 풀을 함께 쓰게 했고, "부하 데이터가 생기면 분리를 판단한다"고 남겼다. **`S15P21A605-53~56`에서 분리했다.** 근거는 부하 수치가 아니라 작업의 성질이다: 재생 폴러는 `replay_state` 행 잠금을 기다리고 밀린 틱을 따라잡는 동안 스레드를 붙잡는데, 그 스레드가 하트비트 풀과 같으면 **고배속 진행이나 DB 대기가 하트비트를 굶겨 연결이 끊긴다.** 끊긴 연결은 재접속하면서 다시 상태를 읽으므로, 진행이 바쁠 때 정확히 가장 나쁘게 겹친다.
  - `domain/replay/config/ReplayConfig`가 단일 스레드 `replayTaskScheduler`(스레드 이름 `replay-`)를 등록하고, 폴러는 `@Scheduled(scheduler = "replayTaskScheduler")`로 **어느 빈을 쓸지 이름으로 못 박는다** — 빈이 둘이 된 지금 자동 선택에 기대면 모호해진다는 원래의 우려가 그대로 유효하기 때문이다.
  - `messageBrokerTaskScheduler`는 하트비트 전용으로 남는다. 게임 진행은 이 풀을 쓰지 않는다.
- **스케일아웃 시 broker 설정을 바꿔야 한다.** 내장 broker는 인스턴스 안에서만 구독을 안다. ADR-0001이 단일 인스턴스를 전제하며, 필요해지면 릴레이 설정 몇 줄로 바뀐다.
- **`clientOutboundChannel` 스레드풀·버퍼는 기본값이다.** 부하 데이터 없이 정하면 추측이다.
- **관통 확인이 프론트의 틱 스토어를 통과해야 한다.** `S15P21A605-39~42`가 `/game-clock`의 단순 표시 컴포넌트를 `/market` + 틱 스토어로 대체했다. 그 스토어는 계약의 초기 로드 절차를 실제로 구현하므로 (REST 히스토리 전에는 버퍼링, 확정된 순번 이하는 중복으로 폐기) **고정 `tickSeq`를 실은 봉투는 도착해도 화면을 움직이지 않는다.** REST가 아직 목 서버에서 오는 동안은 화면의 Tick 번호를 목 시계가 정하기 때문이다. 그래서 트리거에 `tickSeq` 인자를 하나 뒀다 — 개발자도구로 프레임만 볼 때는 생략하고, 화면까지 볼 때는 화면 값 + 1을 준다. 수신자를 인자로 받지 않는 원칙과 충돌하지 않는다: 순번은 사칭 수단이 아니다.
- **프론트에 `onStompError` 핸들러가 생겼다.** 같은 작업이 STOMP `ERROR` 프레임을 토스트로 띄우게 했다. 결정 7의 구독 거부가 이제 **화면에 보인다** — 오타 난 destination이 조용히 무시되지 않는다는 사용자 스토리가 서버 응답을 넘어 사용자에게 도달한다. 핸드셰이크 401은 STOMP 프레임이 아니므로 이 핸들러를 타지 않고 재연결 백오프로 처리된다(결정 4의 전제가 유지된다).
- **임시 트리거의 제거 조건이 정해졌다.** `POST /api/v1/admin/replay/advance`가 "틱 하나를 정상 발행 — 브로드캐스트 전부"를 이미 계약하고 있어 역할이 겹친다. **그것이 구현되면 트리거 엔드포인트·서비스·더미 payload를 삭제한다.** 지금 `advance`를 만들지 않는 이유는 그것이 앵커·배속·가격을 요구해 게임 시계 도메인 전체를 이 작업으로 끌어오기 때문이다.
- **계약과 구현이 갈릴 수 있는 지점이 하나 남는다.** Expo를 실제로 붙일 때 `@stomp/stompjs`의 `webSocketFactory`로 헤더를 실을 수 없다는 것이 드러나면 `ChannelInterceptor`를 추가해 계약 원안으로 돌아온다. 되돌림 비용은 낮고, 부하 테스트가 앱 헤더 경로를 쓰므로 그 시점에 조기 검증된다.

## 후속 결정

- 로그인 전 랜딩 화면의 미인증 열람 경로 (REST·WS 함께 다룬다)
- 화면별 구독 분리 여부 — 지금은 넷을 전부 구독한다. 프론트 결정이고 서버는 넷 다 열어 둔다
- `clientOutboundChannel` 튜닝과 스케일아웃 시 릴레이 도입

# infra — 로컬 관측성 스택 읽기 안내

backend(Spring)와 ai(FastAPI)를 docker로 띄우고 **로그·트레이스·메트릭**을 Grafana 한 화면에서 본다.
이 문서는 "어떤 파일을 어떤 순서로 읽으면 전체가 이해되는지"를 안내한다. 각 파일 안에도 해설 주석이 있다.

- 용어(틱 트레이스, 빈 폴링 트레이스, 수집 선언, 틱 전달 지연): [`../CONTEXT.md`](../CONTEXT.md)
- 계측 방식을 고른 이유: [`../docs/adr/0001-otel-java-agent-over-boot-starter.md`](../docs/adr/0001-otel-java-agent-over-boot-starter.md)

## 1. 한 장 그림

```
                    ┌──────────── app profile ─────────────┐
                    │  backend (Spring + OTel Java agent)  │
                    │  ai      (FastAPI + opentelemetry-   │
                    │           instrument)                │
                    └───┬──────────────┬───────────────┬───┘
            stdout JSON │   OTLP http  │               │ /actuator/prometheus
                        ▼   :4318      │               │ (4단계)
                  Docker json-file     │               │
                        ▲              │               │
               GET only │              ▼               ▼
                 socket-proxy ◀──── alloy (수집기: 가공·필터·샘플링)
                                   │         │          │
                          push     │   OTLP  │  remote  │ write (4단계)
                                   ▼   grpc  ▼          ▼
                                 loki      tempo    prometheus
                                   ▲         ▲          ▲
                                   └──── grafana ───────┘   http://127.0.0.1:3001
```

핵심은 세 가지다.

1. **앱은 alloy만 안다.** 저장소 주소, 버릴 로그, 남길 트레이스는 전부 alloy 설정에서 정한다.
2. **저장소(loki·tempo·prometheus)와 화면(grafana)이 분리돼 있다.** grafana는 데이터를 갖고 있지 않고, 필요할 때마다 저장소에 묻는다.
3. **로그와 트레이스는 `trace_id`로 이어진다.** 앱이 로그 줄마다 `trace_id`를 싣는다. alloy는 그 값을 Loki의 structured metadata로 저장한다. grafana가 양쪽에 링크를 만든다.

## 2. 읽는 순서

신호 하나를 **처음부터 끝까지** 따라가며 읽는다. 파일 단위로 읽지 않는다.

### 0단계: 뼈대 (`../compose.yml`)

머리 주석의 "신호가 흐르는 길"과 "네트워크"를 먼저 읽는다. 그다음 서비스 목록을 세 층(기본, app, monitoring)으로 훑는다.
여기서 잡아야 할 개념은 네 가지다.
- profile: 층마다 따로 띄운다.
- 서비스 이름이 곧 호스트 이름이다.
- `ports`는 호스트(내 맥)에서 들어가기 위한 것이다.
- bind mount된 설정 파일은 고친 뒤 restart해야 다시 읽힌다.

### 1단계: 로그를 따라간다

| 순서 | 파일 | 볼 것 |
|---|---|---|
| 1 | `../compose.yml`의 backend `LOGGING_STRUCTURED_FORMAT_CONSOLE` | Spring이 로그를 JSON 한 줄로 내는 스위치 |
| 2 | `../ai/app/core/logging.py`의 `JsonFormatter` | ai가 같은 필드 이름으로 JSON을 내는 방법. `trace_id`의 출처 |
| 3 | `../compose.yml`의 `x-logging`, `labels: logging.collect` | Docker가 stdout을 파일에 쌓는 방식, 수집 선언 |
| 4 | `../compose.yml`의 `socket-proxy` | alloy에 Docker 조회 권한만 주는 이유 |
| 5 | `alloy/config.alloy` 맨 위의 "문법 읽는 법" | 컴포넌트와 참조로 파이프라인을 만드는 방식 |
| 6 | `alloy/config.alloy`의 로그 ①~⑤ | 대상 찾기, 라벨 규칙, 읽기, 가공(JSON 파싱, 헬스체크 버리기, 라벨, structured metadata), 보내기 |
| 7 | `loki/config.yaml` | 라벨로만 색인한다는 Loki의 아이디어. 보관 기간과 compactor |

### 2단계: 트레이스를 따라간다

| 순서 | 파일 | 볼 것 |
|---|---|---|
| 1 | `../compose.yml`의 `otel-agent`, backend의 `JAVA_TOOL_OPTIONS`와 `OTEL_*` | Dockerfile 없이 agent를 붙이는 init 컨테이너 방식. 트레이스만 켜고 메트릭과 로그는 끈 이유 |
| 2 | `../compose.yml`의 ai `command`, `OTEL_*` | `opentelemetry-instrument` 래퍼. OTEL_* 환경변수는 언어와 무관한 표준이다 |
| 3 | `../ai/app/services/llm.py`의 `llm.call` | 자동 계측이 안 되는 곳(httpx2)에 손으로 span을 추가하는 방법 |
| 4 | `alloy/config.alloy`의 트레이스 ①~④ | OTLP 수신, **tail sampling**(빈 폴링 트레이스 버리기), batch, Tempo로 전송 |
| 5 | `tempo/config.yaml` | WAL과 블록, 보관 기간 |

### 3단계: 화면에서 잇는다

| 순서 | 파일 | 볼 것 |
|---|---|---|
| 1 | `grafana/provisioning/datasources/datasources.yaml` | 데이터소스를 파일로 등록하는 이유, uid 고정, 로그→트레이스(`derivedFields`), 트레이스→로그(`tracesToLogsV2`) |

### 4단계 이후 (예정)

메트릭(Prometheus, exporter), 대시보드, k6 순서로 진행한다. 이 문서도 단계마다 갱신한다.

## 3. 직접 확인하기

읽은 것을 실제로 확인하는 방법이다. 스택이 떠 있어야 한다.

```bash
docker compose --profile app --profile monitoring up -d --build
./scripts/replay-speed.sh 10          # 틱을 흘린다 (약 3초에 한 틱)
```

| 보고 싶은 것 | 어디서 |
|---|---|
| 파이프라인 그래프, 컴포넌트 상태 | http://127.0.0.1:12345 (Alloy UI) |
| 로그 | Grafana → Explore → Loki → `{service="backend"} \|= "Replay step"` |
| 로그에서 트레이스로 | 위 결과의 줄을 펼친 뒤 `trace_id` 옆의 **Tempo 에서 보기** |
| 트레이스 검색 | Grafana → Explore → Tempo → TraceQL `{resource.service.name="backend" && kind=server}` |
| 트레이스에서 로그로 | 트레이스의 span을 펼친 뒤 **Logs for this span** |
| backend→ai로 이어진 트레이스 | TraceQL `{resource.service.name="backend"} >> {resource.service.name="ai"}` (폐장 때 리포트 호출. 폴링 → executor → ai로 한 트레이스) |
| 헬스체크 로그를 몇 줄 버렸나 | `curl -s 127.0.0.1:12345/metrics \| grep dropped_lines` |
| tail sampling 판정 수 | `curl -s 127.0.0.1:12345/metrics \| grep count_traces_sampled` |

### 설정을 고쳤을 때

설정 파일은 bind mount라서, compose가 파일 내용이 바뀐 것을 변경으로 보지 않는다. 고친 뒤에는 해당 서비스를 직접 재시작한다.

```bash
docker compose --profile monitoring restart alloy     # alloy/config.alloy 를 고쳤을 때
docker compose --profile monitoring restart grafana   # provisioning 을 고쳤을 때
```

## 4. 알려진 한계

- **frontend는 스택 밖에 있다.** 호스트에서 `cd frontend && pnpm dev`로 띄운다. 이유는 `../compose.yml` 머리 주석에 있다.
- **메트릭에서 트레이스로 가는 exemplar 링크는 아직 없다.** Tempo metrics-generator 단계에서 추가한다. 이유는 ADR 0001에 있다.

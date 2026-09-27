# HANDOFF — 로컬 관측성 스택 구축

> 작성: 2026-09-28. 다음 작업자(사람 또는 AI)가 이 문서만 읽고 이어서 작업할 수 있게 쓴다.
> 구조와 파일 읽는 순서: [`infra/README.md`](../infra/README.md). 용어: [`CONTEXT.md`](../CONTEXT.md). 결정 기록: [`docs/adr/`](adr/).

## 1. 이 리포는 무엇인가

- `backend/`(Spring Boot 4.1), `ai/`(FastAPI), `frontend/`(Next.js)는 **이전 팀 프로젝트 코드**다. 인프라와 모니터링 부분을 걷어낸 상태로 가져왔다.
- 목표는 **Grafana, Loki, Tempo, Prometheus 기반 관측 스택을 직접 다시 구성하는 것**이다. 로컬 docker에서 돌리고, 학습과 부하 분석 재현을 함께 한다.
- 원칙은 **비즈니스 코드를 고치지 않는 것**이다. 예외는 ai의 관측 관련 세 곳(아래 3절)뿐이다. backend는 코드를 한 줄도 바꾸지 않았고, 계측은 전부 compose 환경변수와 javaagent로 붙였다.

## 2. 한눈에 보는 현황

| 단계 | 내용 | 구현 | 실제 동작 확인 |
|---|---|---|---|
| 1 | 골격: 루트 `compose.yml`, 앱 기동, 리플레이 | ✅ | ✅ 틱이 약 3초 간격으로 흐른다(배속 10) |
| 2 | 로그: stdout JSON → socket-proxy → Alloy → Loki | ✅ | ✅ 라벨 3개, 헬스체크 줄 버리기(수치로 확인) |
| 3 | 트레이스: OTel → Alloy tail sampling → Tempo | ✅ | ✅ 빈 폴링 트레이스 97% 버림, 로그↔트레이스 양방향 연결, backend→ai 전파, Redis span |
| 4 | 메트릭: Alloy scrape → Prometheus remote write, exporter 4종 | ✅ | ✅ 대상 7개 모두 `up=1`, 다른 compose 프로젝트 컨테이너 제외 |
| 5 | 대시보드: 커뮤니티 6종 + 앱 개요 | ✅ | ✅ provisioning 등록, 쿼리 자동 검사, 브라우저에서 앱 개요 렌더링 |
| 6 | k6 스모크 → Prometheus 직접 쓰기 | ✅ | ✅ threshold 통과(p95 32ms, 실패 0%), 결과가 k6·backend 메트릭·Tempo·Loki에 모두 남음 |
| – | 알림, Tempo metrics-generator(exemplar), 부하 시나리오, frontend Sentry | ❌ | – 다음 단계(6절) |

## 3. 무엇을 코드로 구현했나

### 새로 만든 것

| 경로 | 역할 |
|---|---|
| `compose.yml` | 전체 스택. profile은 기본(postgres, redis), `app`(otel-agent, backend, ai), `monitoring`(socket-proxy, alloy, loki, tempo, prometheus, exporter 4종, grafana), `load`(k6)로 나뉜다. 해설 주석이 많다 |
| `infra/alloy/config.alloy` | 수집기 하나에 로그, 트레이스, 메트릭 파이프라인 세 개가 있다. 문법 설명 주석 포함 |
| `infra/loki/config.yaml`, `infra/tempo/config.yaml`, `infra/prometheus/prometheus.yml` | 저장소 세 개. 단일 바이너리, 로컬 디스크, 보관 7일. Prometheus는 스스로 긁지 않는다 |
| `infra/grafana/provisioning/` | 데이터소스(Loki, Tempo, Prometheus. uid 고정, 링크 설정)와 대시보드 provider |
| `infra/grafana/dashboards/community/*.json` | `scripts/fetch-dashboards.sh`가 생성한다. 손으로 고치지 않는다 |
| `infra/grafana/dashboards/app/overview.json` | 직접 만든 **앱 개요** 대시보드(22패널) |
| `infra/README.md` | 구조 그림, 파일 읽는 순서, 직접 확인하는 방법, 알려진 한계 |
| `load/smoke.js`, `load/README.md` | k6 스모크 시나리오와 실행 방법 |
| `scripts/replay-speed.sh` | ADMIN 승격, 로그인, 배속 변경, 필요하면 틱 이동(seek) |
| `scripts/fetch-dashboards.sh` | grafana.com 대시보드를 revision 고정으로 받고 `${DS_…}`를 uid로 치환한다 |
| `CONTEXT.md` | 용어: 틱 트레이스, 빈 폴링 트레이스, 틱 전달 지연, 수집 선언 |
| `docs/adr/0001-otel-java-agent-over-boot-starter.md` | Spring 계측을 agent로 한 이유. exemplar를 미룬 이유, Lettuce 결론 |

### 기존 코드를 고친 곳 (ai만)

| 파일 | 변경 | 테스트 |
|---|---|---|
| `ai/app/core/logging.py`, `config.py`, `main.py` | `AI_LOG_FORMAT=json`이면 한 줄 JSON을 낸다. 필드는 backend logstash와 같고, level은 Spring 표기(`WARN`)로 맞춘다 | `tests/test_logging.py` |
| `ai/app/services/llm.py` | 수동 span `llm.call`. httpx2는 자동 계측 대상이 아니다 | `tests/test_llm.py::test_complete_records_llm_call_span` |
| `ai/app/main.py` | `prometheus-fastapi-instrumentator`로 `/metrics`를 연다. 헬스체크를 제외하는 정규식에는 앵커를 붙였다 | `tests/test_health.py::test_metrics_counts_requests_but_not_healthchecks` |
| `ai/pyproject.toml`, `uv.lock` | OTel distro, exporter, fastapi·logging 계측, instrumentator 추가 | ai 전체 테스트 통과, ruff 통과 |

### backend는 환경변수로만 바꿨다 (`compose.yml`)

- `LOGGING_STRUCTURED_FORMAT_CONSOLE=logstash`: JSON 로그
- `JAVA_TOOL_OPTIONS=-javaagent:…`와 `OTEL_*`: 트레이스만 켠다. 메트릭과 로그 export는 끈다
- `SPRING_AUTOCONFIGURE_EXCLUDE=…LettuceObservationAutoConfiguration`: Redis span을 살린다(ADR 0001 참고)
- `MANAGEMENT_METRICS_DISTRIBUTION_PERCENTILESHISTOGRAM_HTTP_SERVER_REQUESTS=true`: p95를 구할 수 있는 버킷
- `SERVER_TOMCAT_MBEANREGISTRY_ENABLED=true`: Tomcat 스레드 지표

## 4. 실제로 확인한 것 (근거)

| 확인 항목 | 방법과 결과 |
|---|---|
| 헬스체크 로그 버리기 | Alloy 메트릭 기준. 287줄 수신, 2줄 드롭(`reason="healthcheck"`), 285줄 전송 |
| Loki 라벨 | `env`, `level`, `service` 세 개. level 값은 `DEBUG`, `INFO`, `WARN`으로 통일 |
| tail sampling | 538개 중 521개(97%) `not_sampled`. 남은 것은 틱 트레이스와 API 트레이스 |
| Boot 4.1에서 agent 동작 | tomcat-10.0, hibernate, jdbc, spring-data, spring-scheduling, lettuce span이 모두 잡힌다. `http.route`도 붙는다 |
| 로그↔트레이스 | 틱 트레이스 하나로 교차 확인. Loki `trace_id` 필터로 3줄, Tempo 조회 200 |
| backend→ai 전파 | 폐장 리포트 호출이 `ReplayScheduler.poll → (TaskExecutor) → POST → ai SERVER`로 한 트레이스에 묶인다 |
| 메트릭 대상 | ai, backend, cadvisor, node, postgres, redis exporter, alloy가 모두 `up=1`. cadvisor에는 a605 컨테이너 13개만 남는다 |
| 대시보드 | 6+1개 등록. 쿼리를 자동 검사했다. 빈 패널은 대부분 환경에 없는 값이다(Jetty, PG17 bgwriter, OrbStack PSI 등) |
| k6 스모크 | 1,410건 요청, 체크 100%, p95 32ms, 실패 0%. `testid` 라벨로 Prometheus에 남는다 |

## 5. 실행 방법과 주의점

```bash
docker compose --profile app --profile monitoring up -d --build
cd backend && ./scripts/load-replay.sh local --season 2 && cd ..   # 볼륨을 지웠을 때만(처음 한 번)
./scripts/replay-speed.sh 10                 # 틱을 흘린다
./scripts/replay-speed.sh 10 1000001         # 시즌이 끝났으면(REVIEW_ONLY) 첫 틱으로 되돌린다
docker compose --profile load run --rm -e TESTID=smoke-2 k6
docker compose --profile app --profile monitoring down            # 끝낼 때(볼륨은 남는다. 지우려면 -v)
```

- Grafana는 http://127.0.0.1:3001 (로그인 없음), Alloy UI는 :12345, Prometheus는 :9090이다. 3000은 frontend `pnpm dev` 몫이다.
- **전제:** `backend/.env`(커밋되지 않음)에 `TEST_SUPPORT_ENABLED=true`, `TEST_SUPPORT_API_KEY`, `REPLAY_MODE=test`, Google OAuth 값이 있어야 한다. 새 환경에서는 `backend/.env.example`을 복사해 채운다.
- **설정 파일을 고치면 해당 서비스를 restart해야 한다.** bind mount라서 compose가 변경을 감지하지 못한다. 대시보드 JSON만 예외로, 30초 안에 자동 반영된다.
- **리플레이 시즌은 배속 10에서 몇 시간이면 끝난다.** 틱 로그가 멈추면 `replay-speed.sh 10 1000001`을 실행한다.
- **쓰기 API(POST, PATCH)는 `X-Auth-Token` 헤더로 호출한다.** 쿠키로만 호출하면 CSRF 검사에서 403이 난다.
- **환경:** macOS + OrbStack. node-exporter는 OrbStack VM을 본다. cadvisor의 containerd 소켓 경로는 OrbStack 기준이다(Docker Desktop이면 `/run/containerd/containerd.sock`).
- **이 리포에서 뺀 것:** `data/prod_dump.sql`과 `data/trader_*analysis.html`. 운영 데이터이고 실제 사용자 이메일이 들어 있어서 `.gitignore`에 넣었고 git 이력에서도 지웠다.

## 6. 앞으로 할 일

우선순위 순서다. 각 항목은 새 결정이 필요하므로, 시작할 때 방향부터 합의한다.

1. **알림(Alerting).** 처음부터 "구축 후"로 미뤄 둔 항목이다.
   - 후보 규칙은 다섯 가지다. 틱 지연(`lagMs` p95) 증가, backend 5xx 비율, Hikari pending > 0 지속, Tomcat busy가 max에 근접, `up == 0`.
   - 정할 것은 두 가지다. Grafana Alerting(provisioning 파일)으로 할지 Prometheus rule + Alertmanager로 할지, 그리고 알림을 어디로 보낼지(로컬이라 Grafana UI만 볼지 등)다.
2. **Tempo metrics-generator로 exemplar를 연결한다.** ADR 0001의 후속이다.
   - span에서 RED 메트릭과 서비스 그래프를 만든다. Tempo는 `metrics_generator` 설정과 remote write(Prometheus)가 필요하고, Prometheus에는 `--enable-feature=exemplar-storage`가 필요하다.
   - Grafana에서는 Prometheus 데이터소스에 `exemplarTraceIdDestinations`, Tempo 데이터소스에 `serviceMap`과 `tracesToMetrics`를 설정한다.
   - Lettuce 메트릭을 끈 대가(클라이언트 관점의 Redis 지연)도 여기서 다시 얻는다.
3. **부하 시나리오.** 결정 사항 Q11이다.
   - 램프업으로 VU를 늘리는 시나리오를 `load/`에 추가한다.
   - STOMP 구독 상태에서 **틱 전달 지연**(CONTEXT.md)을 k6 커스텀 지표로 잰다. k6 websocket에서 STOMP 프레임을 직접 다뤄야 한다.
   - 서버 쪽에서는 `executor_queued_tasks{name="clientOutboundChannelExecutor"}`를 함께 본다.
4. **frontend Sentry.** 처음부터 이 스택과 분리하기로 한 항목이다.
5. 정리 후보:
   - 앱 개요 대시보드를 고칠 때는 UI에서 편집하고 Export → JSON으로 받아 덮어쓴다(생성 스크립트는 커밋하지 않았다).
   - 루트 `AGENTS.md`의 이슈 트래커와 git 컨벤션 링크는 리포에 없는 문서를 가리킨다. 이 리포는 Jira 키 없이 `feat:`/`chore:` 형식으로 커밋한다.

## 7. 알려진 한계

- frontend는 스택 밖에 있다. production 빌드는 nginx를 전제하므로, 호스트에서 `pnpm dev`로 띄운다.
- 메트릭→트레이스 exemplar는 아직 없다(6절 2번).
- cadvisor만 containerd 소켓(전체 권한)을 갖는다. socket-proxy 원칙의 유일한 예외다.
- ai의 `/api/ai/health/internal`은 트레이스에서 빠진다. OTel 제외 정규식 `health`가 부분 일치하기 때문이다(메트릭에는 잡힌다).
- 커뮤니티 PostgreSQL 대시보드(2021)의 Buffers 패널은 PG17과 맞지 않는다.

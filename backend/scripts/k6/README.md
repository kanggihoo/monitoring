# k6 부하 검증 — 인덱스 전후 비교 (S15P21A605-160)

같은 데이터·같은 부하로 **인덱스 없음 → 있음**을 비교하는 재현 가능한 절차다. 공유 dev/prod
DB·Redis 를 건드리지 않고, 테스트 서버에 일회용 컨테이너 3개(`k6pg` · `k6redis` · `k6app`)를
`a605-net` 에 띄워 돈다. 결과는 Prometheus remote write 로 들어가 Grafana `/grafana/d/a605-k6`
에서 `testid` 로 본다.

| 파일 | 역할 |
|---|---|
| `run.sh` | 오케스트레이터 — 기동 → 로그인 → 시드 → before/after k6 → EXPLAIN → 정리 |
| `load.js` | k6 시나리오. `notifications`(인증, 인덱스 영향 경로) + `control`(공개 티커, 대조군) |
| `users.sql` | test-support 로그인용 사용자 200명 |
| `seed.sql` | notifications 10만 · orders 4만 (계좌 200 기준) |

## 실행 (테스트 서버)

```bash
# 소스와 스크립트를 ~/it87 에 두고
sudo env HOME=/home/ubuntu VUS=200 SLEEP=2 LABEL=200vu bash k6/run.sh
```

| 변수 | 기본 | 뜻 |
|---|---|---|
| `VUS` | 40 | `notifications` 시나리오 동시 사용자. 대조군은 10 고정 |
| `SLEEP` | 0.2 | 요청 사이 쉼(초). 0.2 는 "소수가 쉴 새 없이", 2 는 "많은 사람이 드물게" |
| `DURATION` | 60s | 시나리오 길이 |
| `LABEL` | 40vu | testid 에 붙는 라벨. `160-{before|after}-{LABEL}-월일-시분` |

결과: `k6/out/summary-*.json` · `explain-*.txt` · `sizes.txt` · `testids.txt`.

## 전제

- 백엔드가 `/actuator/prometheus` 를 낸다 (160 의 두 번째 커밋). Prometheus 에 `backend-k6` 잡
  (`k6app:8080`)이 있어야 Hikari 패널이 채워진다 — `~/infra/prometheus/prometheus.yml`.
- 세션은 `Secure` 쿠키로만 오고 k6 는 http 에서 Secure 쿠키를 보내지 않는다. `load.js` 가 쿠키값을
  디코드해 `X-Auth-Token` 헤더로 보낸다.
- 한 실행에 약 5분. 앱·DB·k6 가 4코어 한 호스트에서 CPU 를 나누므로 절대 ms 는 운영 예측이 아니라
  before/after 의 방향·배율 근거로 읽는다.

## 2026-09-14 결과 (200명 · 2초 쉼)

| | before | after |
|---|---|---|
| `GET /notifications` p95 | 3,561 ms | 198 ms |
| 대조군 `GET /news/ticker` p95 | 3,914 ms | 38 ms |
| 처리량 | 57 req/s | 103 req/s (= 수요치) |
| Hikari pending (max / avg) | 125 / 78 | 0 / 0 |

대조군까지 같이 느렸던 것은 인덱스 없는 알림 질의가 커넥션 풀(10)을 붙들었기 때문이다.
인덱스 뒤에는 풀이 거의 비어(active 평균 1.1) **풀 상향은 필요하지 않다.** 40명·0.2초의 1차
실행에서 pending 40 을 보고 상향을 검토했었는데, 그것은 비현실적 패턴의 산물이었다.
전체 기록: Jira S15P21A605-160 첨부 PDF.

## 프로세스 안 스냅샷 전후 비교 (S15P21A605-172)

같은 하네스 방식으로 **코드만 다른 두 jar** 를 비교한다 — before = develop, after = 172 브랜치.
같은 일회용 Postgres/Redis 위에서 앱만 갈아 끼우므로 데이터·세션·부하가 같다. 마이그레이션 차이는 없다.

| 파일 | 역할 |
|---|---|
| `run-172.sh` | 기동 → jar 둘 빌드 → before 앱 → 로그인·틱 고정 → 예열 → k6 → after 앱 → 예열 → k6 → 정리 |
| `load-172.js` | `prices`(공개 `/market/prices`, 172-A) + `account`(인증 `/accounts/me`, 172-B) + `control`(공개 티커, 대조군) |

**1차 지표는 DB 문장 수다.** `k6pg` 를 `pg_stat_statements` 켜서 띄우고 구간마다 리셋해 문장별 호출 수를
`out172/statements-*.txt` · `calls-*.txt` · `tables-*.txt` 로 남긴다 — 이 티켓의 이득은 ms 가 아니라 "몇 번 가는가" 다.

**두 앱을 같은 예열(30 s) 뒤에 잰다.** 1차 실행에서 after 만 콜드 스타트였고 대조군 p95 까지 나빠졐다 — 코드가 아니라
JVM 첫 1분이었다. `WARMUP` 으로 조절한다.

```bash
# develop 소스는 git archive 로 ~/it87/backend-before 에, 172 소스는 ~/it87/backend 에
sudo env HOME=/home/ubuntu bash k6/run-172.sh        # 약 7분. 변수: PRICES_VUS ACCOUNT_VUS SLEEP DURATION WARMUP LABEL
```

### 2026-09-15 결과 (100+100+10 VU · 2 s 쉼 · 60 s · 예열 30 s)

| | before | after |
|---|---|---|
| DB 문장 실행 총합 | 67,314 | **45,710** (−32 %) |
| 유니버스 `JOIN LATERAL` 질의 | 3,000 | **0** |
| `daily_closes` 인덱스 스캔 | 96,416 | **0** |
| `game_ticks` PK 조회 | 6,000 | **0** |
| `GET /accounts/me` p95 | 92 ms | 70 ms |
| `GET /market/prices` p95 | 31 ms | 17 ms |
| 대조군 티커 p95 | 17 ms | 20 ms |

지연 개선은 노이즈와 같은 자릿수라 부차 지표다. 남은 요청당 7.1 문장 중 4개(격리 수준 SET/SHOW 셋 + `findNextTick`)는
데이터를 읽지 않는다 — S15P21A605-205. 전체 기록: Jira S15P21A605-172 첨부 PDF.

## 프로파일링 — 시간이 어디로 가는지 (2026-09-15 추가)

DB 문장 수만 세면 **왕복 비용이 안 보인다.** `pg_stat_statements` 의 실행 시간은 요청당 0.42 ms 인데, 실제로는
그 질의를 보내고 받는 소켓 왕복에 **22 배**가 든다. 그래서 하네스가 세 층을 함께 잰다.

| 층 | 도구 | 산출물 |
|---|---|---|
| DB | `pg_stat_statements` · `pg_stat_user_tables` | `statements-*.txt` · `calls-*.txt` · `tables-*.txt` |
| 서버 안 (CPU·I/O) | JFR (`jcmd` 로 측정 창에만) | `profile-*.jfr` |
| 서버 안 (처리 시간) | Micrometer `http_server_requests_seconds` | `http-*-{pre,post}.txt` |
| 클라이언트 | k6 | `summary-*.json` |

분석 스크립트 (서버에서 `python3` 로 실행, JDK 컨테이너의 `jfr` CLI 를 스트리밍으로 읽는다):

| 스크립트 | 답하는 것 |
|---|---|
| `server_timing.py` | 서버가 잰 시간 vs k6 가 잰 시간 → 시간이 서버 안인가 밖인가 |
| `jfr2.py` | CPU 표본의 하위 시스템 분포 · 자기 시간 상위 · 우리 코드 중 뜨거운 자리 |
| `jfr_socket.py` | 소켓 대기를 상대 포트로 분해 (5432 PostgreSQL · 6379 Redis · 나머지 HTTP) |

**두 가지 함정을 피해야 한다.**

1. **`settings=profile` 기본값은 `SocketRead` 를 10 ms 이상만 기록한다.** DB·Redis 왕복은 1 ms 미만이라 전부
   버려지고 "I/O 없음" 이라는 틀린 결론이 나온다. `jdk.SocketRead#threshold=0ms` 로 내려야 한다.
2. **그렇게 내리면 JFR 이 측정을 왜곡한다.** 초당 3,000 건 × 스택 192 프레임을 기록하느라 응답 시간이 **5.3 배**
   부푼다. 이 실행의 절대 ms 는 쓰지 말고 **비율 · 건수 · before/after 비교**로만 읽는다. 지연 수치는 JFR 없이
   잰 실행에서 따로 낸다.

### 2026-09-15 프로파일 결과 (요약)

- 시간의 **90 %** 는 서버 안 (Micrometer 131 ms vs k6 147 ms)
- 그 안에서 **CPU 는 1.9 %** — 자기 시간 1 위가 `String.hashCode` 1.9 %, 뜨거운 곳이 없다
- 측정 가능한 대기의 **92 % 가 PostgreSQL 소켓**, Redis 세션은 2.9 % (가설 기각)
- **DB 문장 1 개 = 소켓 왕복 2 회** (실측 2.05 / 2.08) — 문장 수를 줄인 것이 곧 왕복을 줄인 것
- 요청 시간의 **88 % 는 여전히 미설명** — 4 코어 공유 호스트의 런큐 대기가 유력. k6 를 다른 호스트로 빼야 갈린다

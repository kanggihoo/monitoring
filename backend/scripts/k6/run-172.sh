#!/bin/bash
# 프로세스 안 스냅샷(172) 전후 k6 비교. before = develop jar, after = 172 브랜치 jar.
# 같은 일회용 Postgres/Redis 위에서 앱만 갈아 끼운다 — 데이터·부하가 같고 코드만 다르다.
#   ~/it87/backend-before : develop 소스 (git archive)
#   ~/it87/backend        : 172 브랜치 소스
#
# 측정 층이 셋이다 — DB(pg_stat_statements) · 서버 안(JFR + http_server_requests) · 클라이언트(k6).
# DB 실행 시간이 응답의 1~2 % 뿐이라 나머지가 어디 있는지는 위 두 층을 봐야 안다.
set -euo pipefail
cd ~/it87
K6=~/it87/k6; OUT=$K6/out172; mkdir -p "$OUT"; chmod 777 "$OUT"
PGP=5432; RDP=6379; APP=8080; NET=a605-net; KEY=k6-load-test-key-0123456789abcdef0123456789
PSQL="docker exec -i k6pg psql -U k6 -d k6 -v ON_ERROR_STOP=1"
cleanup(){ docker rm -f k6app k6pg k6redis >/dev/null 2>&1 || true; }
step(){ echo; echo "===== $* ====="; }

step "0. 정리·기동"; cleanup
# pg_stat_statements 로 질의별 호출 횟수를 센다 — 이 티켓의 1차 지표는 ms 가 아니라 "몇 번 가는가" 다.
docker run -d --name k6pg --network $NET -e POSTGRES_USER=k6 -e POSTGRES_PASSWORD=k6 -e POSTGRES_DB=k6 \
  postgres:17-alpine -c shared_preload_libraries=pg_stat_statements -c pg_stat_statements.track=all >/dev/null
docker run -d --name k6redis --network $NET redis:7.4.11-alpine >/dev/null
for i in $(seq 1 30); do docker exec k6pg pg_isready -U k6 -q && break; sleep 1; done

build(){ # $1 = 소스 디렉터리
  docker run --rm --network host -v "$1":/w -w /w -v ~/.gradle-it:/root/.gradle eclipse-temurin:21-jdk \
    ./gradlew bootJar --no-daemon -x test -q 2>&1 | grep -v "^$" | tail -3 || true
  ls "$1"/build/libs/*.jar | grep -v plain | head -1
}
step "1. bootJar 둘 빌드"
JAR_BEFORE=$(build ~/it87/backend-before); echo "before: $JAR_BEFORE"
JAR_AFTER=$(build ~/it87/backend);         echo "after : $JAR_AFTER"

start_app(){ # $1 = jar
  docker rm -f k6app >/dev/null 2>&1 || true
  # JRE 이미지에는 jcmd 가 없다. 프로파일을 붙이려면 JDK 여야 한다.
  docker run -d --name k6app --network $NET -v "$(cd "$(dirname "$1")" && pwd)":/app -v $OUT:/out \
    -e SERVER_PORT=$APP -e POSTGRES_HOST=k6pg -e POSTGRES_PORT=$PGP -e POSTGRES_DB=k6 -e POSTGRES_USER=k6 -e POSTGRES_PASSWORD=k6 \
    -e REDIS_HOST=k6redis -e REDIS_PORT=$RDP -e REPLAY_MODE=test -e REPLAY_FIXTURE_ENABLED=true \
    -e TEST_SUPPORT_ENABLED=true -e TEST_SUPPORT_API_KEY=$KEY -e REPORT_AI_ENABLED=false \
    -e GOOGLE_CLIENT_ID=x -e GOOGLE_CLIENT_SECRET=x -e SSAFY_CLIENT_ID=x -e SSAFY_CLIENT_SECRET=x \
    -e JAVA_TOOL_OPTIONS="-Xmx1g -XX:FlightRecorderOptions=stackdepth=192" \
    eclipse-temurin:21-jdk java -jar "/app/$(basename "$1")" >/dev/null
  for i in $(seq 1 90); do docker exec a605-prometheus wget -T2 -qO- "http://k6app:$APP/api/v1/news/ticker" >/dev/null 2>&1 && break; sleep 2; done
  docker exec a605-prometheus wget -T2 -qO- "http://k6app:$APP/api/v1/news/ticker" >/dev/null || { docker logs k6app | tail -40; exit 1; }
  # 파이프로 grep -q 에 물리면 grep 이 먼저 끝나 wget 이 SIGPIPE 로 죽고, pipefail 이 그것을 실패로 본다.
  # 게다가 Micrometer 는 첫 요청 직후에도 Hikari 지표를 아직 안 올렸을 수 있다 — 파일로 받고 재시도한다.
  for i in $(seq 1 15); do
    docker exec a605-prometheus wget -qO- "http://k6app:$APP/actuator/prometheus" > $OUT/.metrics 2>/dev/null || true
    grep -q "^hikaricp_connections_active" $OUT/.metrics && break
    sleep 2
  done
  grep -q "^hikaricp_connections_active" $OUT/.metrics || { echo "hikaricp 지표 없음"; docker logs k6app | tail -20; exit 1; }
  echo "앱 응답 OK"
}

step "2. before 앱 기동"; start_app "$JAR_BEFORE"
$PSQL -c "CREATE EXTENSION IF NOT EXISTS pg_stat_statements" >/dev/null

step "3. 사용자 200명 + 로그인 패스(계좌 생성)"
$PSQL < $K6/users.sql
docker run --rm --network $NET -v $K6:/s grafana/k6:2.2.0 run -q -e MODE=login -e USERS=200 -e BASE_URL=http://k6app:$APP -e TEST_SUPPORT_KEY=$KEY /s/load-172.js | grep -E "login 2xx|checks" || true
$PSQL -tAc "SELECT count(*) FROM accounts"

step "4. 재생을 1일차 4번째 틱까지 공개된 상태로 고정 (배속 0)"
# 공개 틱이 없으면 시세·계좌 경로가 '최초 공개 전' 분기로 빠져 game_ticks 를 읽지 않는다 — 그러면 잴 것이 없다.
$PSQL -c "UPDATE replay_state SET anchor_season_day=1, anchor_offset_sec=0, anchor_at=now(), speed=0,
          last_published_tick_seq=4, last_boundary_season_day=1, last_boundary_offset_sec=0 WHERE season_id=1" >/dev/null
docker exec a605-prometheus wget -qO- "http://k6app:$APP/api/v1/market/prices" | head -c 200; echo

stats_reset(){ $PSQL -c "SELECT pg_stat_statements_reset(); SELECT pg_stat_reset();" >/dev/null; }
stats_dump(){ # $1 = before|after
  $PSQL -tAc "SELECT relname, seq_scan, idx_scan FROM pg_stat_user_tables
              WHERE relname IN ('game_ticks','daily_closes','instruments','accounts','positions','replay_state','news')
              ORDER BY relname" | tee $OUT/tables-$1.txt
  $PSQL -tAc "SELECT calls, round(total_exec_time)::bigint AS ms, left(regexp_replace(query, '\s+', ' ', 'g'), 110)
              FROM pg_stat_statements WHERE query NOT ILIKE '%pg_stat%' AND query NOT ILIKE '%pg_catalog%'
              ORDER BY calls DESC LIMIT 15" | tee $OUT/statements-$1.txt
  $PSQL -tAc "SELECT sum(calls) FROM pg_stat_statements WHERE query NOT ILIKE '%pg_stat%'" | tee $OUT/calls-$1.txt
}
# 측정 창에만 프로파일을 건다. 기동·예열을 포함하면 JIT 컴파일이 표본을 덮는다.
# SocketRead/Write 임계값을 0 으로 내리는 이유: DB·Redis 왕복은 1 ms 미만이라 기본 10 ms 에서 전부 버려진다.
profile_start(){
  docker exec k6app jcmd 1 JFR.start name=m settings=profile \
    jdk.SocketRead#threshold=0ms jdk.SocketWrite#threshold=0ms >/dev/null
}
profile_stop(){ # $1 = before|after
  docker exec k6app jcmd 1 JFR.dump name=m filename=/out/profile-$1.jfr >/dev/null
  docker exec k6app jcmd 1 JFR.stop name=m >/dev/null
  echo "jfr: $OUT/profile-$1.jfr ($(du -h $OUT/profile-$1.jfr | cut -f1))"
}

# 서버가 스스로 잰 처리 시간. k6 는 클라이언트 왕복을 재므로 둘의 차이가 네트워크+클라이언트다.
server_timing(){ # $1 = before|after, $2 = pre|post
  docker exec a605-prometheus wget -qO- "http://k6app:$APP/actuator/prometheus" \
    | grep -E '^http_server_requests_seconds_(sum|count)' > $OUT/http-$1-$2.txt || true
}

warmup(){ # 갓 뜬 JVM 은 JIT·Hibernate 준비로 첫 1분이 느리다. 두 앱 모두 같은 예열을 거친 뒤 잰다 —
  # 그러지 않으면 after 만 콜드 스타트라 p95 가 코드와 무관하게 나빠진다 (1차 실행에서 실제로 그랬다).
  step "예열 ${WARMUP:-30s} (기록하지 않음)"
  docker run --rm --network $NET -v $K6:/s -e K6_NO_USAGE_REPORT="true" grafana/k6:2.2.0 run -q     -e MODE=load -e PRICES_VUS=50 -e ACCOUNT_VUS=50 -e SLEEP=1 -e DURATION=${WARMUP:-30s} -e USERS=200     -e BASE_URL=http://k6app:$APP -e TEST_SUPPORT_KEY=$KEY /s/load-172.js | grep -E "http_reqs|checks" || true
}
phase(){ # $1 = before|after
  warmup
  step "5.$1 k6 ($1)"
  TESTID="172-$1-${LABEL:-200vu}-$(date +%m%d-%H%M)"; echo "testid=$TESTID"; echo "$TESTID" >> $OUT/testids.txt
  stats_reset; server_timing $1 pre; profile_start
  docker run --rm --network $NET -v $K6:/s -v $OUT:/out \
    -e K6_PROMETHEUS_RW_SERVER_URL="http://prometheus:9090/api/v1/write" \
    -e K6_PROMETHEUS_RW_TREND_STATS="p(95),p(99),avg,min,max" -e K6_PROMETHEUS_RW_STALE_MARKERS="true" -e K6_NO_USAGE_REPORT="true" \
    grafana/k6:2.2.0 run -q --out experimental-prometheus-rw --tag testid="$TESTID" \
    -e MODE=load -e PRICES_VUS=${PRICES_VUS:-100} -e ACCOUNT_VUS=${ACCOUNT_VUS:-100} -e SLEEP=${SLEEP:-2} -e DURATION=${DURATION:-60s} -e USERS=200 \
    -e BASE_URL=http://k6app:$APP -e TEST_SUPPORT_KEY=$KEY --summary-export /out/summary-$1.json /s/load-172.js | tail -30 || true
  profile_stop $1; server_timing $1 post
  step "5.$1 DB 통계 ($1)"; stats_dump $1
}

phase before

step "6. after 앱으로 교체 (같은 DB·Redis)"; start_app "$JAR_AFTER"
# 세션은 Redis 에 있어 로그인 토큰이 그대로 산다. 스냅샷은 첫 요청이 채운다.
docker exec a605-prometheus wget -qO- "http://k6app:$APP/api/v1/market/prices" | head -c 200; echo
phase after

step "7. 정리"; cleanup
echo "결과: $OUT (summary-*.json · profile-*.jfr · http-*-{pre,post}.txt · statements-*.txt · tables-*.txt)"
echo DONE

#!/bin/bash
# 인덱스(V29) 전후 k6 비교. 일회용 Postgres/Redis + 160 브랜치 백엔드를 host 네트워크에 띄운다.
set -euo pipefail
cd ~/it87
K6=~/it87/k6; OUT=$K6/out; mkdir -p "$OUT"; chmod 777 "$OUT"
PGP=5432; RDP=6379; APP=8080; NET=a605-net; KEY=k6-load-test-key-0123456789abcdef0123456789
PSQL="docker exec -i k6pg psql -U k6 -d k6 -v ON_ERROR_STOP=1"
cleanup(){ docker rm -f k6app k6pg k6redis >/dev/null 2>&1 || true; }
step(){ echo; echo "===== $* ====="; }

step "0. 정리·기동"; cleanup
docker run -d --name k6pg --network $NET -e POSTGRES_USER=k6 -e POSTGRES_PASSWORD=k6 -e POSTGRES_DB=k6 postgres:17-alpine >/dev/null
docker run -d --name k6redis --network $NET redis:7.4.11-alpine >/dev/null
for i in $(seq 1 30); do docker exec k6pg pg_isready -U k6 -q && break; sleep 1; done

step "1. bootJar 빌드 (V29 포함 확인)"; ls backend/src/main/resources/db/migration | grep -q V29 || { echo "V29 없음"; exit 1; }
docker run --rm --network host -v ~/it87/backend:/w -w /w -v ~/.gradle-it:/root/.gradle eclipse-temurin:21-jdk \
  ./gradlew bootJar --no-daemon -x test -q 2>&1 | grep -v "^$" | tail -3 || true
JAR=$(ls backend/build/libs/*.jar | grep -v plain | head -1); echo "jar: $JAR"

step "2. 앱 기동"
docker run -d --name k6app --network $NET -v "$(cd "$(dirname "$JAR")" && pwd)":/app \
  -e SERVER_PORT=$APP -e POSTGRES_HOST=k6pg -e POSTGRES_PORT=$PGP -e POSTGRES_DB=k6 -e POSTGRES_USER=k6 -e POSTGRES_PASSWORD=k6 \
  -e REDIS_HOST=k6redis -e REDIS_PORT=$RDP -e REPLAY_MODE=test -e REPLAY_FIXTURE_ENABLED=true \
  -e TEST_SUPPORT_ENABLED=true -e TEST_SUPPORT_API_KEY=$KEY -e REPORT_AI_ENABLED=false \
  -e GOOGLE_CLIENT_ID=x -e GOOGLE_CLIENT_SECRET=x -e SSAFY_CLIENT_ID=x -e SSAFY_CLIENT_SECRET=x \
  -e JAVA_TOOL_OPTIONS="-Xmx1g" eclipse-temurin:21-jre java -jar "/app/$(basename "$JAR")" >/dev/null
for i in $(seq 1 90); do docker exec a605-prometheus wget -T2 -qO- "http://k6app:$APP/api/v1/news/ticker" >/dev/null 2>&1 && break; sleep 2; done
docker exec a605-prometheus wget -T2 -qO- "http://k6app:$APP/api/v1/news/ticker" >/dev/null || { docker logs k6app | tail -40; exit 1; }
echo "앱 응답 OK"
echo "--- /actuator/prometheus 의 Hikari 지표 (없으면 계측 실패)"
docker exec a605-prometheus wget -qO- "http://k6app:$APP/actuator/prometheus" | grep -E "^hikaricp_connections(_max|_active|_pending)?[ {]" || { echo "hikaricp 지표 없음"; docker logs k6app | tail -20; exit 1; }

step "3. 사용자 200명 + 로그인 패스(계좌 생성)"
$PSQL < $K6/users.sql
docker run --rm --network $NET -v $K6:/s grafana/k6:2.2.0 run -q -e MODE=login -e USERS=200 -e BASE_URL=http://k6app:$APP -e TEST_SUPPORT_KEY=$KEY /s/load.js | grep -E "login 2xx|checks" || true
$PSQL -tAc "SELECT count(*) FROM accounts"

step "4. 시드 (알림 10만 · 주문 4만)"; $PSQL < $K6/seed.sql

explain_orders(){ for i in 1 2 3 4 5; do $PSQL -tAc "EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT) SELECT * FROM orders WHERE status = 'OPEN'" | grep -E "Execution Time|Seq Scan|Index|Bitmap"; done; }
explain_unread(){ for i in 1 2 3 4 5; do $PSQL -tAc "EXPLAIN (ANALYZE, FORMAT TEXT) SELECT count(*) FROM notifications WHERE account_id = (SELECT min(id) FROM accounts) AND read_at IS NULL" | grep -E "Execution Time|Seq Scan|Index|Bitmap"; done; }
phase(){ # $1 = before|after
  step "5.$1 k6 ($1)"
  # 지표를 Prometheus 로 밀어 Grafana(/grafana/d/a605-k6)에서 testid 로 필터해 본다.
  # ~/infra/run-k6.sh 와 같은 출력·환경변수다. 대상이 host 네트워크라 localhost 로 찌른다.
  TESTID="160-$1-${LABEL:-40vu}-$(date +%m%d-%H%M)"; echo "testid=$TESTID"; echo "$TESTID" >> $OUT/testids.txt
  docker run --rm --network $NET -v $K6:/s -v $OUT:/out     -e K6_PROMETHEUS_RW_SERVER_URL="http://prometheus:9090/api/v1/write"     -e K6_PROMETHEUS_RW_TREND_STATS="p(95),p(99),avg,min,max" -e K6_PROMETHEUS_RW_STALE_MARKERS="true" -e K6_NO_USAGE_REPORT="true"     grafana/k6:2.2.0 run -q --out experimental-prometheus-rw --tag testid="$TESTID"     -e MODE=load -e VUS=${VUS:-40} -e SLEEP=${SLEEP:-0.2} -e DURATION=${DURATION:-60s} -e USERS=200     -e BASE_URL=http://k6app:$APP -e TEST_SUPPORT_KEY=$KEY --summary-export /out/summary-$1.json /s/load.js | tail -25 || true
  step "5.$1 EXPLAIN ANALYZE orders OPEN ($1)"; explain_orders | tee $OUT/explain-orders-$1.txt
  step "5.$1 EXPLAIN ANALYZE unread count ($1)"; explain_unread | tee $OUT/explain-unread-$1.txt
}

$PSQL -c "DROP INDEX IF EXISTS ix_orders_open; DROP INDEX IF EXISTS ix_notifications_account_latest;" >/dev/null
$PSQL -tAc "SELECT indexname FROM pg_indexes WHERE tablename IN ('orders','notifications')" | tr '\n' ' '; echo " <- before 인덱스"
phase before

$PSQL -c "CREATE INDEX ix_orders_open ON orders (status) WHERE status = 'OPEN'; CREATE INDEX ix_notifications_account_latest ON notifications (account_id, created_at DESC, id DESC); ANALYZE orders; ANALYZE notifications;" >/dev/null
$PSQL -tAc "SELECT indexname FROM pg_indexes WHERE tablename IN ('orders','notifications')" | tr '\n' ' '; echo " <- after 인덱스"
phase after

step "6. 표 크기"; $PSQL -tAc "SELECT relname, pg_size_pretty(pg_total_relation_size(oid)), reltuples::bigint FROM pg_class WHERE relname IN ('orders','notifications','ix_orders_open','ix_notifications_account_latest')" | tee $OUT/sizes.txt
step "7. 정리"; cleanup; echo DONE

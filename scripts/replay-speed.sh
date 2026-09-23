#!/usr/bin/env bash
# 로컬 스택의 리플레이 배속을 바꾼다. 0 = 정지, 10 이면 틱이 약 3초마다 흐른다.
#   ./scripts/replay-speed.sh 10
# 전제: REPLAY_MODE=test · TEST_SUPPORT_ENABLED=true (backend/.env), 리플레이 적재 완료
#       (cd backend && ./scripts/load-replay.sh local).
set -euo pipefail
cd "$(dirname "$0")/.."

SPEED="${1:?배속을 준다 (0~100)}"
BASE="${BASE:-http://127.0.0.1:8080}"
KEY=$(grep '^TEST_SUPPORT_API_KEY=' backend/.env | cut -d= -f2-)
NAME=k6-0001

# 권한은 로그인 시점에 세션에 실리므로 승격을 먼저 한다.
docker compose exec -T postgres psql -U ssafy -d ssafydb -qc \
  "UPDATE users SET role='ADMIN' WHERE id=(SELECT user_id FROM test_users WHERE name='$NAME');"

# 세션 쿠키값(base64)을 디코드해 X-Auth-Token 으로 보낸다 — 이 헤더가 있으면 CSRF 검사가 빠진다.
TOKEN=$(curl -sf -o /dev/null -c - -X POST "$BASE/api/v1/test-support/login" \
  -H "Content-Type: application/json" -H "X-Test-Support-Key: $KEY" -d "{\"name\":\"$NAME\"}" \
  | awk '$6=="SESSION"{print $7}' | base64 -d)

curl -sf -X PATCH "$BASE/api/v1/admin/replay" -H "X-Auth-Token: $TOKEN" \
  -H "Content-Type: application/json" -d "{\"speed\":$SPEED}"
echo

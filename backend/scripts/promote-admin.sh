#!/bin/sh
# 최초 관리자 승격. 가입 폼이 없으므로 대상자가 한 번 로그인해 users 행이 생긴 뒤에 실행한다.
#
#   sh scripts/promote-admin.sh          # 최근 가입자 목록을 보고 대상의 식별자를 확인한다
#   sh scripts/promote-admin.sh 42       # 그 식별자 하나만 승격한다
#
# 이메일이 아니라 식별자를 키로 쓴다. 이메일에는 유일성이 없어 같은 값을 가진 User 가 둘이면
# 둘 다 승격되고, 이메일이 없는 사용자는 승격 자체가 불가능했다.
#
# 접속 정보는 postgres 컨테이너 안의 환경변수를 그대로 쓴다 — 호스트에서 .env 를 다시
# 읽지 않으므로 POSTGRES_USER 등을 바꿔도 이 스크립트를 고칠 필요가 없다.
set -e

# 최근 목록에 몇 명까지 보여줄지. 대상은 방금 로그인한 사람이므로 맨 위 몇 줄이면 충분하다.
RECENT_LIMIT=20

# compose.yml 이 있는 디렉터리에서 실행해야 한다.
cd "$(dirname "$0")/.."

# 표준입력으로 받은 SQL 을 컨테이너 안에서 실행한다. 사용자 입력은 psql 변수 :'id' 로만 들어간다 —
# 문자열에 직접 끼워 넣으면 따옴표가 든 값에서 깨진다.
psql_run() {
  docker compose exec -T "$@" postgres \
    sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -v ON_ERROR_STOP=1 -P pager=off \
             -v id="${TARGET_ID:-}" -f -'
}

target_id="$1"

# 인자가 없으면 목록만 보여주고 끝난다. 운영자는 승격 대상의 식별자를 모른다.
if [ -z "$target_id" ]; then
  echo "최근 가입자 $RECENT_LIMIT 명입니다. 대상의 식별자를 골라 다시 실행하세요."
  echo "  sh scripts/promote-admin.sh <식별자>"
  echo
  # 테스트용 사용자는 제외한다. 시드 인원이 늘면 최근 목록이 전부 그것들로 채워져
  # 정작 승격할 대상을 찾을 수 없다.
  # 인용부호 없는 heredoc — RECENT_LIMIT 은 사용자 입력이 아니라 이 파일의 상수다.
  psql_run <<SQL
SELECT u.id, u.nickname, u.email, u.role, u.created_at
FROM users u
WHERE NOT EXISTS (SELECT 1 FROM test_users t WHERE t.user_id = u.id)
ORDER BY u.created_at DESC, u.id DESC
LIMIT $RECENT_LIMIT;
SQL
  exit 0
fi

# SQL 에 닿기 전에 막는다. 숫자가 아니면 psql 이 형변환 오류로 죽어 원인이 드러나지 않는다.
case "$target_id" in
  ''|*[!0-9]*)
    echo "식별자는 숫자여야 합니다: $target_id" >&2
    exit 1
    ;;
esac

# 식별자는 psql 변수로 넘긴다. 문자열에 직접 끼워 넣지 않는다.
result=$(psql_run -e TARGET_ID="$target_id" <<'SQL'
UPDATE users SET role = 'ADMIN', updated_at = now() WHERE id = :'id'::bigint;
SQL
)

echo "$result"

case "$result" in
  *"UPDATE 0"*)
    echo "해당 식별자의 User 가 없습니다. 인자 없이 실행해 목록을 먼저 확인하세요." >&2
    exit 1
    ;;
esac

echo "승격 완료. 권한은 세션에 담기므로 대상자가 다시 로그인해야 반영됩니다."

#!/usr/bin/env bash
# 마켓 리플레이 시세 적재/검증/롤백 편의 스크립트.
# 리포 루트나 backend 디렉터리 어디서든 실행 가능합니다.
#
# 사용법:
#   ./scripts/load-replay.sh local         # 로컬 DB 적재 (ssafydb, mode=test)
#   ./scripts/load-replay.sh dev           # 개발 서버 DB 적재 (SSH 터널 자동, app_dev, mode=test)
#   ./scripts/load-replay.sh prod          # 운영 서버 DB 적재 (SSH 터널 자동, app_prod, mode=production)
#   ./scripts/load-replay.sh verify        # DB 접속 없이 원본 데이터 검증 및 행 수 계산
#
#   --season N 으로 시즌을 고릅니다. 생략하면 **시즌 1 과 2 를 차례로** 처리합니다
#   (로컬에서 두 시즌을 한 번에 채우고 한 번에 되돌리는 것이 기본 쓰임이기 때문입니다).
#   시즌 1 은 data/replay, 시즌 2 는 data/replay-s2 를 읽습니다.
#   예) ./scripts/load-replay.sh local --season 2
#
#   --start-date YYYY-MM-DD 를 붙이면 1일차 개장일을 그 날짜로 옮겨 적재합니다 (이후 연속 일자).
#   생략하면 DB 의 현재 편성을 그대로 씁니다 (시즌 1 은 V18, 시즌 2 는 로더의 기본 개장일).
#   두 시즌을 함께 적재할 때는 쓸 수 없습니다 — 시즌마다 개장일이 다르므로 --season 과 함께 씁니다.
#   예) ./scripts/load-replay.sh prod --season 1 --start-date 2026-09-15
#   ./scripts/load-replay.sh rollback      # 로컬 DB 리플레이 데이터 삭제
#   ./scripts/load-replay.sh rollback dev  # 개발 서버 DB 리플레이 데이터 삭제
#   ./scripts/load-replay.sh rollback prod # 운영 서버 DB 리플레이 데이터 삭제

set -e

# 스크립트 위치 기준 경로 계산
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BACKEND_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
LOADER_DIR="$SCRIPT_DIR/replay-loader"
BACKEND_ENV="$BACKEND_DIR/.env"
LOADER_ENV="$LOADER_DIR/.env"

# uv 설치 여부 확인
if ! command -v uv >/dev/null 2>&1; then
  echo "[오류] 'uv' 명령어를 찾을 수 없습니다. uv를 설치해주세요: https://docs.astral.sh/uv/" >&2
  exit 1
fi

check_port() {
  local port=55432
  if command -v nc >/dev/null 2>&1; then
    nc -z 127.0.0.1 "$port" >/dev/null 2>&1
  else
    (echo >/dev/tcp/127.0.0.1/"$port") >/dev/null 2>&1
  fi
}

load_local_env() {
  if [ ! -f "$LOADER_ENV" ] && [ -f "$BACKEND_ENV" ]; then
    while IFS='=' read -r key value || [ -n "$key" ]; do
      key="$(echo "$key" | tr -d ' ')"
      value="$(echo "$value" | tr -d ' ' | tr -d '\r')"
      case "$key" in
        POSTGRES_HOST|POSTGRES_PORT|POSTGRES_DB|POSTGRES_USER|POSTGRES_PASSWORD)
          if [ -z "${!key}" ]; then
            export "$key=$value"
          fi
          ;;
      esac
    done < "$BACKEND_ENV"
  fi
}

invoke_remote() {
  local target_db="$1"
  local action_type="$2"
  local mode="${3:-test}"
  local local_port=55432
  local container_ip="172.19.0.2"
  local remote_host="ssafy"
  local tunnel_started=0
  local ssh_pid=""

  if ! check_port; then
    echo "==> [SSH 터널] $remote_host 서버와 터널 연결 시작 (127.0.0.1:$local_port -> ${container_ip}:5432)..."
    ssh -N -L "${local_port}:${container_ip}:5432" "$remote_host" &
    ssh_pid=$!
    tunnel_started=1

    local ready=0
    for _ in $(seq 1 15); do
      sleep 0.4
      if check_port; then
        ready=1
        break
      fi
    done

    if [ "$ready" -ne 1 ]; then
      if [ -n "$ssh_pid" ]; then kill "$ssh_pid" >/dev/null 2>&1 || true; fi
      echo "[오류] SSH 터널 연결에 실패했습니다. 'ssh $remote_host' 접속을 확인해주세요." >&2
      exit 1
    fi
    echo "==> [SSH 터널] 포트 $local_port 연결 성공."
  else
    echo "==> [SSH 터널] 기존 활성 터널(포트 $local_port)을 재사용합니다."
  fi

  cleanup() {
    if [ "$tunnel_started" -eq 1 ] && [ -n "$ssh_pid" ]; then
      echo "==> [SSH 터널] 임시 터널 프로세스를 정상 종료합니다."
      kill "$ssh_pid" >/dev/null 2>&1 || true
    fi
  }
  trap cleanup EXIT INT TERM

  export POSTGRES_HOST="127.0.0.1"
  export POSTGRES_PORT="$local_port"
  export POSTGRES_DB="$target_db"
  export POSTGRES_USER="a605"
  export POSTGRES_PASSWORD="${POSTGRES_PASSWORD:-JxBaEABG0GMM33y33RiaRUUu9iWF}"

  for season in "${SEASON_LIST[@]}"; do
    if [ "$action_type" = "load" ]; then
      echo "==> [마켓 리플레이] $target_db 시즌 $season 적재 시작 (mode=$mode)..."
      uv run --directory "$LOADER_DIR" load_replay_data.py \
        --mode "$mode" --season "$season" "${LOAD_ARGS[@]}"
    else
      echo "==> [마켓 리플레이] $target_db 시즌 $season 롤백 시작..."
      uv run --directory "$LOADER_DIR" unload_replay_data.py --season "$season"
    fi
  done
}

COMMAND="${1:-local}"
if [ $# -gt 0 ]; then shift; fi

TARGET=""
START_DATE=""
SEASON=""
while [ $# -gt 0 ]; do
  case "$1" in
    --start-date) START_DATE="${2:-}"; shift 2 ;;
    --start-date=*) START_DATE="${1#*=}"; shift ;;
    --season) SEASON="${2:-}"; shift 2 ;;
    --season=*) SEASON="${1#*=}"; shift ;;
    *) TARGET="$1"; shift ;;
  esac
done

# --season 을 생략하면 두 시즌을 차례로 처리한다. 값 검사는 로더가 한다 — 한 곳에만 둔다.
if [ -n "$SEASON" ]; then
  SEASON_LIST=("$SEASON")
else
  SEASON_LIST=(1 2)
fi

# 개장일은 시즌마다 다르므로 한 시즌을 지목했을 때만 받는다. 조용히 양쪽에 같은 날짜를 넣으면
# 두 시즌이 같은 날 개장한다.
if [ -n "$START_DATE" ] && [ "${#SEASON_LIST[@]}" -gt 1 ]; then
  echo "[오류] --start-date 는 --season 과 함께 씁니다. 시즌마다 개장일이 다릅니다." >&2
  exit 1
fi

# 로더에 그대로 넘긴다. 형식·과거 날짜 검사는 로더가 한다 — 한 곳에만 둔다.
LOAD_ARGS=()
if [ -n "$START_DATE" ]; then
  LOAD_ARGS=(--start-date "$START_DATE")
fi

case "$COMMAND" in
  local|test)
    load_local_env
    for season in "${SEASON_LIST[@]}"; do
      echo "==> [마켓 리플레이] 로컬 DB(ssafydb) 시즌 $season 적재 시작 (mode=test)..."
      uv run --directory "$LOADER_DIR" load_replay_data.py \
        --mode test --season "$season" "${LOAD_ARGS[@]}"
    done
    ;;
  dev)
    invoke_remote "app_dev" "load" "test"
    ;;
  prod|production)
    echo "⚠️  [경고] 운영 서버 DB(app_prod)에 데이터를 적재합니다!"
    # 개장일은 눈으로 확인하고 yes 를 치게 한다. 잘못 넣으면 시즌이 엉뚱한 날 시작한다.
    if [ -n "$START_DATE" ]; then
      echo "    개장일: $START_DATE 부터 연속 일자 (1일차 11:05 KST, 브리핑 11:00)"
    else
      echo "    개장일: DB 의 현재 편성을 그대로 사용 (시즌 1 은 V18)"
    fi
    read -r -p "정말 운영 DB에 데이터를 적재하시겠습니까? (yes/no): " ans
    if [ "$ans" != "yes" ]; then
      echo "운영 적재 작업이 취소되었습니다."
      exit 0
    fi
    invoke_remote "app_prod" "load" "production"
    ;;
  verify|check)
    for season in "${SEASON_LIST[@]}"; do
      echo "==> [마켓 리플레이] 시즌 $season 데이터 검증 및 자기시험 실행 (--verify)..."
      uv run --directory "$LOADER_DIR" load_replay_data.py --verify --season "$season"
    done
    ;;
  rollback-dev)
    invoke_remote "app_dev" "unload"
    ;;
  rollback-prod)
    echo "⚠️  [경고] 운영 서버 DB(app_prod) 데이터를 롤백(삭제)합니다!"
    read -r -p "정말 운영 DB 데이터를 롤백하시겠습니까? (yes/no): " ans
    if [ "$ans" != "yes" ]; then
      echo "운영 롤백 작업이 취소되었습니다."
      exit 0
    fi
    invoke_remote "app_prod" "unload"
    ;;
  rollback|unload)
    if [ "$TARGET" = "dev" ]; then
      invoke_remote "app_dev" "unload"
    elif [ "$TARGET" = "prod" ]; then
      echo "⚠️  [경고] 운영 서버 DB(app_prod) 데이터를 롤백(삭제)합니다!"
      read -r -p "정말 운영 DB 데이터를 롤백하시겠습니까? (yes/no): " ans
      if [ "$ans" != "yes" ]; then
        echo "운영 롤백 작업이 취소되었습니다."
        exit 0
      fi
      invoke_remote "app_prod" "unload"
    else
      load_local_env
      for season in "${SEASON_LIST[@]}"; do
        echo "==> [마켓 리플레이] 로컬 DB 시즌 $season 데이터 롤백 실행..."
        uv run --directory "$LOADER_DIR" unload_replay_data.py --season "$season"
      done
    fi
    ;;
  *)
    echo "잘못된 인자: $COMMAND"
    echo "사용법: $0 [local|dev|prod|verify|rollback [dev|prod]] [--season 1|2] [--start-date YYYY-MM-DD]"
    exit 1
    ;;
esac

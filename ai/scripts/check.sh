#!/usr/bin/env bash
# 커밋 전 검사 셋을 한 번에 돌리고 **요약만** 찍는다.
#
#   scripts/check.sh            # 통과하면 세 줄, 깨지면 깨진 테스트 이름만
#   scripts/check.sh --why      # 깨진 자리의 본문까지 (원인을 볼 때만)
#   scripts/check.sh --fix      # ruff 가 고칠 수 있는 것은 고치고 간다
#
# **출력을 줄이는 것이 목적이다.** `pytest -q` 는 1,000건이면 점을 1,000개 찍고, 한 건이
# 깨지면 그 테스트가 남긴 로그와 stdout 을 통째로 게워낸다. 사람이 읽을 때도 스크롤이고,
# 에이전트가 읽을 때는 그대로 토큰이다. 평소에는 "깨졌나 안 깨졌나"만 알면 되고, 본문은
# 깨졌을 때 --why 로 한 번 더 부르면 된다.
#
# 실행 절차는 docs/usage.md 7절.

set -uo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$here/.." || exit 1

why=0
fix=0
for arg in "$@"; do
    case "$arg" in
        --why) why=1 ;;
        --fix) fix=1 ;;
        *) echo "모르는 옵션: $arg" >&2; exit 2 ;;
    esac
done

status=0

if [ "$fix" -eq 1 ]; then
    uv run ruff format . >/dev/null 2>&1
    uv run ruff check --fix . >/dev/null 2>&1
fi

# 1. 서식 — **종료 코드로 판정한다.**
#
# 예전에는 출력에서 `^Would reformat` 을 세고 있었다. ruff 가 출력 형식을 바꿔
# (`unformatted: File would be reformatted`) 세는 값이 늘 0 이 됐고, 어긋난 파일이 있는데도
# "format OK" 를 찍었다 — CI(`ruff format --check`)가 그 커밋에서 실패했다 (2026-09-18).
# 문자열이 아니라 종료 코드를 보면 형식이 또 바뀌어도 안 깨진다.
format_out="$(uv run ruff format --check . 2>&1)"
if [ $? -ne 0 ]; then
    echo "format   어긋남  —  uv run ruff format ."
    [ "$why" -eq 1 ] && printf '%s
' "$format_out"
    status=1
else
    echo "format   OK"
fi

# 2. 린트 — 규칙 위반 줄만 센다.
lint="$(uv run ruff check --quiet . 2>&1)"
if [ -n "$lint" ]; then
    echo "lint     $(printf '%s\n' "$lint" | grep -c '^')줄  —  uv run ruff check ."
    [ "$why" -eq 1 ] && printf '%s\n' "$lint"
    status=1
else
    echo "lint     OK"
fi

# 3. 테스트 — 마지막 요약 줄만. 깨지면 이름만 (--why 면 본문까지).
if [ "$why" -eq 1 ]; then
    uv run pytest --tb=short
    [ $? -eq 0 ] || status=1
else
    out="$(uv run pytest --tb=no --show-capture=no -p no:cacheprovider 2>&1)"
    code=$?
    # 요약 줄을 패턴으로 집는다. `tail -n 1` 은 진행 표시 줄을 집을 수 있다 — pyproject 의
    # addopts 가 이미 `-q` 라, 여기서 또 주면 `-qq` 가 되어 줄 순서가 바뀐다.
    summary="$(printf '%s\n' "$out" | grep -E '[0-9]+ (passed|failed|error)' | tail -n 1)"
    printf 'test     %s\n' "${summary:-요약 줄을 찾지 못했다}"
    if [ "$code" -ne 0 ]; then
        printf '%s\n' "$out" | grep '^FAILED\|^ERROR' | sed 's/^/         /'
        echo "         자세히 보려면  scripts/check.sh --why"
        status=1
    fi
fi

exit "$status"

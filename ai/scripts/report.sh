#!/usr/bin/env bash
# 목업 유저의 복기 리포트를 터미널에 찍는다. Git Bash 에서 그대로 돌아간다.
#
#   scripts/report.sh                 # 랜덤 유저 1명, 블록 문장
#   scripts/report.sh --llm --api      # 헤드라인 생성 + 실제 엔드포인트 호출
#   scripts/report.sh --users 5        # 5명 연속
#   scripts/report.sh --seed 42        # 같은 유저를 다시
#
# VS Code 의 실행 버튼(▷)은 인터프리터 경로를 백슬래시로 넘기는데, bash 는 백슬래시를
# 이스케이프 문자로 먹어서 경로가 뭉개진다 (`c:S15P21A605ai.venv...`). 이 파일은 자기 위치를
# 기준으로 가상환경을 찾으므로 그 문제를 겪지 않는다.
#
# 옵션은 scripts/preview_report.py 와 같다. 실행 절차는 docs/usage.md 7절.

set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
python="$here/../.venv/Scripts/python.exe"   # Windows
if [ ! -x "$python" ]; then
    python="$here/../.venv/bin/python"       # macOS · Linux
fi

if [ ! -x "$python" ]; then
    echo "가상환경을 찾지 못했다. ai/ 에서 'uv sync' 를 먼저 실행한다." >&2
    exit 1
fi

# 한글이 깨지지 않게 UTF-8 로 고정한다 (Windows 콘솔 기본 코드페이지 대응).
PYTHONUTF8=1 exec "$python" "$here/preview_report.py" "$@"

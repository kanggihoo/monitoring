#!/usr/bin/env bash
# grafana.com 커뮤니티 대시보드를 받아 provisioning 용으로 변환해 infra/grafana/dashboards/community/ 에 쓴다.
#   ./scripts/fetch-dashboards.sh
# 결과 JSON 은 커밋한다. 버전을 올릴 때는 아래 목록의 revision 만 바꾸고 다시 돌린다.
#
# 변환이 필요한 이유: 내려받은 JSON 은 UI 의 Import 화면용이라 데이터소스 자리에
# "${DS_PROMETHEUS}" 같은 자리표시자가 있다. Import 화면은 이걸 물어서 채우지만
# provisioning 은 채우지 않는다 → 그대로 두면 모든 패널이 "datasource not found" 로 깨진다.
set -euo pipefail
cd "$(dirname "$0")/.."

OUT=infra/grafana/dashboards/community
mkdir -p "$OUT"

python3 - "$OUT" <<'EOF'
import json, re, sys, urllib.request

out = sys.argv[1]

# (grafana.com ID, revision, 파일·uid 이름) — revision 을 고정한다 (이미지 태그 고정과 같은 이유).
DASHBOARDS = [
    (4701, 10, "jvm"),         # JVM (Micrometer) — backend. JVM·Hikari·HTTP·로그백
    (14114, 1, "postgres"),    # PostgreSQL Exporter Quickstart — 쿠버네티스 라벨을 전제하지 않는다
    (763, 6, "redis"),         # Redis Dashboard for redis_exporter 1.x
    (1860, 45, "node"),        # Node Exporter Full — OrbStack VM 의 CPU·메모리·디스크
    (19792, 6, "cadvisor"),    # cadvisor dashboard — compose 프로젝트·컨테이너 이름으로 거른다
]

# Prometheus 데이터소스 uid (infra/grafana/provisioning/datasources/datasources.yaml 에서 고정).
PROM_UID = "prometheus"
PLACEHOLDER = re.compile(r"\$\{DS_[A-Z0-9_]+\}")


def replace_placeholders(node):
    """JSON 트리 전체에서 "${DS_...}" 문자열을 데이터소스 uid 로 바꾼다."""
    if isinstance(node, dict):
        return {k: replace_placeholders(v) for k, v in node.items()}
    if isinstance(node, list):
        return [replace_placeholders(v) for v in node]
    if isinstance(node, str):
        return PLACEHOLDER.sub(PROM_UID, node)
    return node


for gid, rev, name in DASHBOARDS:
    url = f"https://grafana.com/api/dashboards/{gid}/revisions/{rev}/download"
    dash = json.load(urllib.request.urlopen(url))

    # Import 화면 전용 메타데이터. provisioning 에서는 의미가 없다.
    for key in ("__inputs", "__requires", "__elements"):
        dash.pop(key, None)
    dash = replace_placeholders(dash)

    # 데이터소스 선택 변수(type=datasource)는 남기되 기본값을 우리 Prometheus 로 둔다.
    for var in dash.get("templating", {}).get("list", []):
        if var.get("type") == "datasource":
            var["current"] = {"text": "Prometheus", "value": PROM_UID}

    # id 는 Grafana DB 의 내부 키라 비운다. uid 는 URL(/d/<uid>)이 되므로 고정한다.
    dash["id"] = None
    dash["uid"] = name
    dash["tags"] = sorted(set(dash.get("tags", [])) | {"community"})

    path = f"{out}/{name}.json"
    with open(path, "w") as f:
        json.dump(dash, f, ensure_ascii=False, indent=2)
        f.write("\n")
    print(f"{path}  ← grafana.com/dashboards/{gid} rev {rev}  ({dash.get('title')})")
EOF

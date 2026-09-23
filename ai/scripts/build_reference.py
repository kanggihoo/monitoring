"""성향 판정의 **사분위 컷**을 데이터에서 뽑는다.

```bash
# 리허설 시즌 집계를 받아서 (가장 정확하다)
uv run python scripts/build_reference.py --payload season-1-accounts.json

# 목업으로 (임시값. 절대 위치를 믿으면 안 된다)
uv run python scripts/build_reference.py --mock --users 3000 --seed 11
```

찍힌 블록을 `app/services/profile.py` 의 `QUARTILES` 에 그대로 붙여 넣는다.

**임계값을 코드에 박지 않는 이유가 이것이다.** 컷은 실제 사용자 분포에서 나와야 "왜 이
숫자가 기준이냐" 에 답할 수 있다 (기준 문서 6절). 지금 상수는 목업에서 뽑은 것이라,
리허설 집계가 한 번 들어오면 통째로 갈아 끼운다.
"""

from __future__ import annotations

import argparse
import json
import sys
from decimal import Decimal
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
sys.path.insert(0, str(Path(__file__).resolve().parent))

from app.schemas.inbound import InboundSeasonAccount, SeasonReportRequest
from app.services.inbound import to_raw_items
from app.services.profile import ITEMS, QUARTILES

#: 뽑는 분위수. `profile.to_point` 가 이 셋을 네 칸의 경계로 쓴다.
FRACTIONS = (0.25, 0.50, 0.75)


def quantile(values: list[Decimal], fraction: float) -> Decimal:
    """정렬된 표본에서 분위수 하나. 보간하지 않고 가장 가까운 관측값을 쓴다 —
    없는 정밀도를 만들지 않는다."""
    return values[min(len(values) - 1, int(len(values) * fraction))]


def collect(accounts: list[InboundSeasonAccount]) -> dict[str, list[Decimal]]:
    """계좌들에서 항목별 원지표를 모은다. **잴 수 없었던 것(`None`)은 빼고 센다** —
    0 으로 채우면 컷이 통째로 아래로 밀린다."""
    columns: dict[str, list[Decimal]] = {item: [] for item in ITEMS}
    for account in accounts:
        items = to_raw_items(account)
        for item in ITEMS:
            value = items.value(item)
            if value is not None:
                columns[item].append(value)
    for values in columns.values():
        values.sort()
    return columns


def _trim(value: Decimal) -> str:
    return f"{value:.4f}"


def render(columns: dict[str, list[Decimal]]) -> str:
    lines = ["QUARTILES: dict[str, tuple[Decimal, ...]] = {"]
    for item in ITEMS:
        values = columns[item]
        if not values:
            lines.append(f'    # "{item}": 표본이 없다 — 이 항목은 교체하지 못했다')
            continue
        cuts = ", ".join(f'"{_trim(quantile(values, f))}"' for f in FRACTIONS)
        lines.append(
            f'    "{item}": tuple(map(Decimal, ({cuts}))),'
            f"  # 표본 {len(values)}/{sum(1 for _ in columns[ITEMS[0]])}"
        )
    lines.append("}")
    return "\n".join(lines)


def load(payload: Path) -> list[InboundSeasonAccount]:
    body = json.loads(payload.read_text(encoding="utf-8"))
    return SeasonReportRequest.model_validate(body).accounts


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="성향 판정의 사분위 컷을 뽑는다")
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument("--payload", type=Path, help="시즌 요청 payload JSON")
    source.add_argument("--mock", action="store_true", help="목업으로 (임시값)")
    parser.add_argument("--users", type=int, default=3000)
    parser.add_argument("--seed", type=int, default=11)
    return parser.parse_args()


def main() -> None:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    args = parse_args()
    if args.mock:
        from mock_data import simulate

        _, season, _ = simulate(seed=args.seed, users=args.users, season_day=3)
        accounts = SeasonReportRequest.model_validate(season).accounts
        origin = f"목업 {args.users}계좌 (seed={args.seed})"
    else:
        accounts = load(args.payload)
        origin = f"{args.payload.name} ({len(accounts)}계좌)"

    columns = collect(accounts)
    print(f"# {origin} 에서 뽑았다")
    print(render(columns))
    if args.mock:
        print("#")
        print("# **목업 값이다.** 특히 DRAWDOWN 행은 생성된 시장이 통째로 좌우해서")
        print("# 시드만 바꿔도 크게 흔들린다. 리허설 집계로 다시 뽑아 교체할 것.")
    else:
        current = {item: tuple(map(str, QUARTILES[item])) for item in ITEMS}
        print("#")
        print(f"# 지금 코드에 박힌 값: {current}")


if __name__ == "__main__":
    main()

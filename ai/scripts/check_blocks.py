"""문구 블록 재고를 본다. 서버도 DB 도 필요 없다.

    uv run python scripts/check_blocks.py            # 재고와 조건별 후보 수
    uv run python scripts/check_blocks.py --texts     # 문장까지 전부
    uv run python scripts/check_blocks.py --spread 100  # 100명이 같은 상황일 때 몇 갈래인가

조건별 후보 수가 곧 "같은 상황의 사용자가 몇 가지 문장 중에서 갈리는가"다. 그 수가 작으면
옆자리와 같은 문장을 받는 사람이 늘어난다.

리포트 한 장을 보려면 `scripts/preview_report.py` 를 쓴다.
"""

from __future__ import annotations

import argparse
import itertools
import sys
from collections import Counter
from pathlib import Path
from typing import get_args

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.schemas.report import EffectAxis, MainMistake, TradeTag, VsIndex
from app.services.profile import ITEMS, LEVEL_NAMES
from app.services.report_blocks import SLOTS, candidates, choose, load_blocks

#: 조건 값의 정본은 전부 코드다. 베껴 두면 곧 어긋난다 — 실제로 `MainMistake` 에 `NORMAL`
#: 이 늘었을 때 이 목록만 넷으로 남아 재고 점검이 그 칸을 못 봤다.
VS_INDEX = get_args(VsIndex)
MISTAKES = get_args(MainMistake)
AXES = get_args(EffectAxis)
#: 태그 목록도 같은 이유로 스키마에서 가져온다.
TAGS = get_args(TradeTag)

#: 성향 5단계. `None` 은 판정이 안 선 계좌다 — 그 자리에도 카드가 나가야 한다.
PROFILE_LEVELS: tuple[int | None, ...] = (*sorted(LEVEL_NAMES), None)
#: 항목 점수를 두 갈래로 자른 값. `narrative.item_direction` 이 내는 것과 같다.
DIRECTIONS = ("H", "L")
#: 선언과 관찰의 거리. `None` 은 선언이 없는 계좌다.
MATCHES: tuple[str | None, ...] = ("SAME", "NEAR", "FAR", None)


#: `choose` 가 실제로 쓰는 후보. 실제 블록이 있으면 폴백은 빼므로 재고보다 하나 적다.
def real_pool(slot: str, features: dict) -> list[dict]:
    pool = candidates(slot, features)
    real = [block for block in pool if not block.get("fallback")]
    return real or pool


def show_inventory() -> None:
    blocks = load_blocks()
    print("블록 재고")
    total = 0
    for slot in SLOTS:
        items = blocks[slot]
        fallbacks = sum(1 for block in items if block.get("fallback"))
        total += len(items)
        print(f"  {slot:<16} {len(items):>3}개  (폴백 {fallbacks})")
    print(f"  {'합계':<16} {total:>3}개")
    print()


def show_daily_conditions(show_texts: bool) -> None:
    print("일일 헤드라인 — 조건별 후보 수")
    print(f"  {'vsIndex':<9}{'mainMistake':<14}후보")
    narrowest = None
    for vs_index, mistake in itertools.product(VS_INDEX, MISTAKES):
        pool = real_pool("daily_headline", {"vsIndex": vs_index, "mainMistake": mistake})
        narrowest = len(pool) if narrowest is None else min(narrowest, len(pool))
        print(f"  {vs_index:<9}{mistake:<14}{len(pool)}")
        if show_texts:
            for block in pool:
                print(f"      {block['id']:<28} {block['text']}")
    print(f"  → 가장 좁은 조건의 후보 수 {narrowest}")
    print()

    print("거래 별칭 — 태그별 후보 수")
    for tag in TAGS:
        pool = real_pool("trade_alias", {"tag": tag})
        print(f"  {tag:<14}{len(pool)}")
        if show_texts:
            for block in pool:
                print(f"      {block['id']:<28} {block['text']}")
    print()


def show_season_conditions(show_texts: bool) -> None:
    """결과지 쪽 재고. **조건 축이 성향 5단계와 항목 다섯이다** (4축이 아니다)."""
    print("결과지 ① 유형 카드 — 단계별 후보 수")
    for level in PROFILE_LEVELS:
        pool = real_pool("investor_type", {"profileLevel": level})
        label = f"{level} {LEVEL_NAMES[level]}" if level is not None else "판정 불가"
        print(f"  {label:<10}{len(pool)}")
        if show_texts:
            for block in pool:
                card = f"{block['emoji']} {block['name']}"
                print(f"      {block['id']:<20} {card} — {block['oneLine']}")
    print()

    print("결과지 ④ 실제 모습 폴백 — 대조 구간별 후보 수")
    for match in MATCHES:
        pool = real_pool("actual_fallback", {"profileMatch": match})
        print(f"  {match or '선언 없음':<10}{len(pool)}")
        if show_texts:
            for block in pool:
                print(f"      {block['id']:<20} {block['text']}")
    print()

    print("결과지 ⑥⑧ 항목별 후보 수 — 특징 제목 / 다음 방향")
    print(f"  {'항목':<12}{'방향':<6}{'⑥':<4}⑧")
    narrowest = None
    for item, direction in itertools.product(ITEMS, DIRECTIONS):
        traits = real_pool("trait_title", {"traitItem": item, "traitDirection": direction})
        nexts = real_pool("next_step", {"nextItem": item, "nextDirection": direction})
        fewest = min(len(traits), len(nexts))
        narrowest = fewest if narrowest is None else min(narrowest, fewest)
        print(f"  {item:<12}{direction:<6}{len(traits):<4}{len(nexts)}")
        if show_texts:
            for block in traits:
                print(f"      {block['id']:<24} {block['text']}")
            for block in nexts:
                print(f"      {block['id']:<24} {block['text']}")
    print(f"  → 가장 좁은 칸의 후보 수 {narrowest}")
    print()

    print("유형 작동·역작동 — 판정별 후보 수")
    for axis in AXES:
        pool = real_pool(
            "type_effect",
            # `rankRate` 는 순위를 아는 계좌에만 있다. 재고를 셀 때는 있다고 보고 센다 —
            # 없는 계좌에서 그 블록이 빠지는 것은 `requires` 가 하는 일이다.
            {"axis": axis, "patternCount": 999, "rankRate": "상위 10%"},
        )
        print(f"  {axis:<12}{len(pool)}")
        if show_texts:
            for block in pool:
                print(f"      {block['id']:<24} {block['text']}")
    print()


def show_spread(people: int) -> None:
    """같은 상황의 사용자 n 명이 몇 갈래로 갈리는지. 겹침의 상한을 눈으로 본다."""
    print(f"같은 상황 {people}명이 받는 헤드라인 분포")
    for vs_index, mistake in (("BELOW", "PEAK_BUY"), ("ABOVE", "QUIET")):
        counts: Counter[str] = Counter()
        for account_id in range(1, people + 1):
            block = choose(
                "daily_headline",
                {"vsIndex": vs_index, "mainMistake": mistake},
                seed=f"{account_id}:1:daily_headline",
            )
            counts[block["id"]] += 1
        spread = " · ".join(f"{block_id} {n}명" for block_id, n in counts.most_common())
        print(f"  {vs_index}/{mistake}")
        print(f"    {len(counts)}가지 · 최다 {max(counts.values())}명 / {people}명")
        print(f"    {spread}")
    print()


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="문구 블록 재고 점검")
    parser.add_argument("--texts", action="store_true", help="문장까지 함께 찍는다")
    parser.add_argument("--spread", type=int, default=0, help="같은 상황 n 명의 분포를 본다")
    return parser.parse_args()


def main() -> None:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    args = parse_args()
    show_inventory()
    show_daily_conditions(args.texts)
    show_season_conditions(args.texts)
    if args.spread:
        show_spread(args.spread)


if __name__ == "__main__":
    main()

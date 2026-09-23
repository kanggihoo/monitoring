"""문구 블록 조립.

`extract → filter → pick → dedup → fallback → render` 여섯 단계 중 뒤 다섯을 담는다.
`extract`(원자료 → 조건 벡터)는 입력 경로가 정해지면 별도 모듈이 맡는다.
설계 근거는 `docs/architecture/report-assembly.md`.

AI 호출은 없다. 조건 비교, 해시 나머지, 문자열 치환뿐이다.
"""

from __future__ import annotations

import hashlib
import json
import re
from functools import lru_cache
from pathlib import Path
from typing import Any

from app.exceptions import AiServiceError

#: 블록 JSON 이 사는 곳. 슬롯당 한 파일이라 유형 수가 바뀌어도 그 파일만 손댄다.
BLOCKS_DIR = Path(__file__).resolve().parents[2] / "data" / "blocks"

SLOTS = (
    "daily_headline",
    "trade_alias",
    "investor_type",
    "type_effect",
    "season_context",
    # 결과지 여덟 덩이 (명세 8절). `trait_title` 은 제목만이고 본문은 템플릿이 만든다.
    "trait_title",
    "next_step",
    # LLM 이 쓰는 자리(④⑦)의 폴백. 생성이 실패하거나 검사에 걸리면 이쪽으로 내려앉는다.
    "actual_fallback",
)

#: `render` 가 허용하는 변수. 목록 밖의 키가 쓰이면 정적 검사에서 걸린다.
ALLOWED_PLACEHOLDERS = frozenset({"count", "winCount", "returnRate", "rankRate"})

_PLACEHOLDER = re.compile(r"\{(\w+)\}")


class BlockError(AiServiceError):
    """블록 정의나 치환이 어긋난 경우. 사용자에게 반쪽 문장을 내보내지 않는다."""

    status_code = 500
    message = "리포트 문구를 만들지 못했습니다."


@lru_cache
def load_blocks() -> dict[str, list[dict[str, Any]]]:
    """슬롯별 블록을 읽어 캐시한다. 파일이 없으면 기동이 아니라 첫 호출에서 드러난다."""
    blocks: dict[str, list[dict[str, Any]]] = {}
    for slot in SLOTS:
        path = BLOCKS_DIR / f"{slot}.json"
        if not path.exists():
            raise BlockError(f"블록 파일이 없습니다: {path.name}")
        blocks[slot] = json.loads(path.read_text(encoding="utf-8"))
    return blocks


def candidates(slot: str, features: dict[str, Any]) -> list[dict[str, Any]]:
    """`when` 의 키가 전부 일치하는 블록만 남긴다. `when` 에 없는 키는 보지 않는다.

    폴백 블록(`when` 이 빈 것)은 항상 통과하므로 후보가 비는 일이 없다.
    """
    picked = []
    for block in load_blocks()[slot]:
        if any(features.get(k) != v for k, v in block["when"].items()):
            continue
        if features.get("patternCount", 0) < block.get("minCount", 0):
            continue
        # 값이 비면 문장이 반쪽이 된다 ("에 머물렀습니다"). 그 블록만 후보에서 뺀다 —
        # 예외를 던지면 순위를 모르는 계좌 하나가 리포트 전체를 막는다.
        if any(not features.get(name) for name in block.get("requires", ())):
            continue
        picked.append(block)
    return picked


def pick(pool: list[dict[str, Any]], seed: str) -> dict[str, Any]:
    """무작위를 쓰지 않는다.

    리포트는 폐장 스냅샷이라 같은 사용자가 다시 열면 같은 문장이 나와야 한다.
    시드에 계정 ID 가 섞여 있어 같은 조건의 다른 사용자는 다른 변주를 받는다.
    """
    if not pool:
        raise BlockError("후보 블록이 없습니다 — 슬롯에 폴백 블록이 빠졌습니다")
    digest = hashlib.sha256(seed.encode("utf-8")).hexdigest()
    return pool[int(digest, 16) % len(pool)]


def choose(
    slot: str,
    features: dict[str, Any],
    seed: str,
    *,
    exclude: set[str] | None = None,
) -> dict[str, Any]:
    """filter → dedup → fallback → pick 을 한 번에 수행한다.

    조건을 만족하는 실제 블록이 있으면 폴백을 후보에서 뺀다. 폴백은 마지막 안전망이므로
    다른 후보와 같은 확률로 뽑히면 안 된다.
    """
    pool = candidates(slot, features)
    real = [b for b in pool if not b.get("fallback")]
    if real:
        pool = real

    # 직전 리포트에서 쓴 블록을 뺀다. 빼고 나서 비면 빼지 않는다 — 중복이 빈 문장보다 낫다.
    if exclude:
        narrowed = [b for b in pool if b["id"] not in exclude]
        if narrowed:
            pool = narrowed

    return pick(pool, seed)


def render(text: str, values: dict[str, Any]) -> str:
    """플레이스홀더를 치환한다.

    없는 키를 빈 문자열로 두면 "0회 중 회가" 가 사용자에게 나가므로 예외를 던진다.
    """
    missing = [name for name in _PLACEHOLDER.findall(text) if name not in values]
    if missing:
        raise BlockError(f"치환할 값이 없습니다: {', '.join(missing)}")
    return _PLACEHOLDER.sub(lambda m: str(values[m.group(1)]), text)

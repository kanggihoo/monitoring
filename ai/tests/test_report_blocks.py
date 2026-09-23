"""블록 정적 검사와 조합 전수 시뮬레이션.

`docs/architecture/report-assembly.md` 와 `prompts/report-blocks.md` 체크리스트에 대응한다.
조합이 유한하고 결정론적이라 전수 검사가 가능하다 — 런타임 생성 방식으로는 못 하는 검증이다.
"""

from __future__ import annotations

import itertools
import re
from typing import get_args

import pytest

from app.schemas.report import (
    DailyFeatures,
    MainMistake,
    Scene,
    SeasonFeatures,
    TradeTag,
    VsIndex,
)
from app.services.report_assembly import assemble_daily, assemble_season
from app.services.report_blocks import ALLOWED_PLACEHOLDERS, SLOTS, load_blocks

#: 발표 자료가 종목 추천을 자문업 영역으로 선언했다. 투자 권유와 단정적 예측은 릴리스 게이트다.
FORBIDDEN = [
    "사세요",
    "파세요",
    "추천",
    "매수하는 게",
    "매도하는 게",
    "오를 것",
    "떨어질 것",
    "상승할 것",
    "하락할 것",
    "하셔야",
    "명심",
    "반드시 기억",
    "좋은 종목",
    "나쁜 종목",
    "위험한 종목",
]
#: 종목은 임의의 이름을 쓰므로 블록에 실제 고유명사가 들어가면 익명화가 무너진다.
REAL_NAMES = [
    "나스닥",
    "코스피",
    "엔비디아",
    "테슬라",
    "삼성전자",
    "카카오",
    "비트코인",
    "이더리움",
    "QQQ",
    "코로나",
    "금리 인상",
]

MAX_LENGTH = {"daily_headline": 30, "trade_alias": 8, "type_effect": 60}
SENTENCE_MAX = {"investor_type": 40}

ALL_BLOCKS = [b for slot in SLOTS for b in load_blocks()[slot]]
PLACEHOLDER = re.compile(r"\{(\w+)\}")
SAMPLE_VALUES = {"count": 24, "winCount": 17, "returnRate": "+38.2%", "rankRate": "상위 12%"}


def rendered(text: str) -> str:
    return PLACEHOLDER.sub(lambda m: str(SAMPLE_VALUES[m.group(1)]), text)


@pytest.mark.parametrize("block", ALL_BLOCKS, ids=lambda b: b["id"])
def test_block_shape(block: dict) -> None:
    assert block["slot"] in SLOTS
    assert isinstance(block["when"], dict)
    assert block["text"].strip()
    if block["slot"] == "investor_type":
        assert block["name"].strip()
        # 8유형 시절에는 6~7자였는데, 확정된 5단계 페르소나가 최대 13자다
        # ("변동성을 즐기는 트레이더"). DB 의 type_name 이 50자라 저장에는 여유가 있다.
        assert len(block["name"]) <= 16


def test_block_ids_are_unique() -> None:
    ids = [b["id"] for b in ALL_BLOCKS]
    assert len(ids) == len(set(ids))


def test_slot_matches_file() -> None:
    for slot in SLOTS:
        assert all(b["slot"] == slot for b in load_blocks()[slot])


@pytest.mark.parametrize("block", ALL_BLOCKS, ids=lambda b: b["id"])
def test_only_allowed_placeholders(block: dict) -> None:
    assert set(PLACEHOLDER.findall(block["text"])) <= ALLOWED_PLACEHOLDERS


@pytest.mark.parametrize("block", ALL_BLOCKS, ids=lambda b: b["id"])
def test_length_limit(block: dict) -> None:
    """치환 뒤 길이를 본다. 사용자가 보는 것이 그쪽이다."""
    text = rendered(block["text"])
    limit = MAX_LENGTH.get(block["slot"])
    if limit:
        assert len(text) <= limit, f"{len(text)}자 > {limit}자: {text}"
    sentence_limit = SENTENCE_MAX.get(block["slot"])
    if sentence_limit:
        for sentence in filter(None, (s.strip() for s in text.split("."))):
            assert len(sentence) <= sentence_limit, f"{len(sentence)}자: {sentence}"


@pytest.mark.parametrize("block", ALL_BLOCKS, ids=lambda b: b["id"])
def test_no_forbidden_expression(block: dict) -> None:
    haystack = block["text"] + block.get("name", "")
    for word in FORBIDDEN + REAL_NAMES:
        assert word not in haystack, f"금지 표현 '{word}': {haystack}"


@pytest.mark.parametrize("block", ALL_BLOCKS, ids=lambda b: b["id"])
def test_does_not_start_with_conjunction(block: dict) -> None:
    """앞뒤에 다른 블록이 붙으므로 접속사로 시작하면 조합이 바뀔 때 어색해진다."""
    assert not block["text"].startswith(("그리고", "그래서", "하지만", "그러나", "또한"))


def test_every_slot_has_exactly_one_fallback() -> None:
    for slot in SLOTS:
        fallbacks = [b for b in load_blocks()[slot] if b.get("fallback")]
        assert len(fallbacks) == 1, slot
        assert fallbacks[0]["when"] == {}


def test_expected_block_counts() -> None:
    counts = {
        slot: len([b for b in load_blocks()[slot] if not b.get("fallback")]) for slot in SLOTS
    }
    assert counts == {
        # vsIndex 3종 x mainMistake 5종 x 변주 3.
        "daily_headline": 45,
        # 태그 4종 + 최고·최악 거래 + NEUTRAL, 각 4개.
        "trade_alias": 28,
        # 성향 5단계 + 유보 각 1장. 8유형 카드에서 교체했다 (기준 문서 3절).
        "investor_type": 6,
        # 통했다 6 + 어긋났다 9. 유형에 묶인 문장은 카드(①)와 특징(⑥)이 대신한다.
        "type_effect": 15,
        "season_context": 9,
        # 항목 5 x 방향 2 x 변주 2.
        "trait_title": 20,
        # 항목 5 x 방향 2. **방향을 반드시 본다** — 항목만 보고 한 문장을 쓰면
        # 현금을 잔뜩 남긴 사람에게 "덜 실어 보세요" 가 나간다.
        "next_step": 10,
        # 일치도 구간 3 x 변주 2 + 선언 없음 2.
        "actual_fallback": 8,
    }
    assert sum(counts.values()) == 141


def test_daily_combinations_are_all_assemblable() -> None:
    """시장 대비 위치 곱하기 판정 곱하기 태그 조합을 전부 조립해 본다.

    목록을 베끼지 않고 스키마에서 가져온다. 예전에 여기 넷을 적어 두었다가 `MainMistake` 에
    `NORMAL` 이 늘었을 때 이 전수 검사가 그 칸을 한 번도 밟지 않았다.
    """
    vs = get_args(VsIndex)
    mistakes = get_args(MainMistake)
    tags = get_args(TradeTag)
    for i, (v, m, tag) in enumerate(itertools.product(vs, mistakes, tags)):
        text = assemble_daily(
            DailyFeatures(
                accountId=i + 1,
                seasonDay=1,
                vsIndex=v,
                mainMistake=m,
                executionCount=5,
                scenes=[Scene(gameMonth="2020.02", instrumentName="바나나", side="BUY", tag=tag)],
            )
        )
        assert text.headline
        assert len(text.scene_aliases) == 1
        assert "{" not in text.headline


def test_season_combinations_are_all_assemblable() -> None:
    # 다섯 단계 전부와 판정 불가. 판정 불가는 폴백으로 떨어지지만 문장은 완성돼야 한다.
    levels = [1, 2, 3, 4, 5, None]
    for i, (level, axis) in enumerate(itertools.product(levels, ["WORKED", "BACKFIRED"])):
        text = assemble_season(
            SeasonFeatures(
                accountId=i + 1,
                seasonId="S1",
                profileLevel=level,
                axis=axis,
                winCount=17,
                returnRate="+38.2%",
                rankRate="상위 12%",
            )
        )
        assert "{" not in text.verdict
        assert text.type_name and text.type_description

    # 순위를 모르는 계좌는 `rankRate` 를 쓰는 블록이 후보에서 빠질 뿐, 문장은 나와야 한다.
    without_rank = assemble_season(
        SeasonFeatures(
            accountId=1,
            seasonId="S1",
            axis="BACKFIRED",
            winCount=17,
            returnRate="-12.0%",
            rankRate="",
        )
    )
    assert "{" not in without_rank.verdict

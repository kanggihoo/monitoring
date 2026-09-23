"""화면 단위 조립 — 일일 매매 코멘터리와 시즌 총평.

블록 선택·치환은 `report_blocks` 가 맡고, 여기서는 어떤 슬롯을 어떤 순서로 붙여
화면 한 장을 만드는지만 정한다.
"""

from __future__ import annotations

from typing import Any

from app.schemas.report import (
    DailyFeatures,
    DailyReportText,
    SeasonFeatures,
    SeasonNarrative,
    SeasonReportText,
    TraitItem,
)
from app.services.narrative import (
    TraitFacts,
    item_direction,
    pick_trait_items,
    to_item_body,
    to_loss_tolerance_line,
    to_profile_gap_line,
    to_self_view_from_answers,
)
from app.services.report_blocks import choose, render
from app.services.season_narrative import Narration

#: 당일 수익 거래가 1건뿐일 때처럼, 규칙으로 못 가리는 자리에 코드가 직접 붙이는 별칭.
SOLE_WIN_ALIAS = "유일한 정답"
SOLE_LOSS_ALIAS = "오늘의 유일한 실수"

_WIN_TAGS = frozenset({"BOTTOM_BUY", "PEAK_SELL", "BEST_TRADE"})
_LOSS_TAGS = frozenset({"PEAK_BUY", "BOTTOM_SELL", "WORST_TRADE"})

#: '유일한' 별칭을 붙이지 않는 태그. 최고·최악 거래는 정의상 하루에 하나씩이라, 그 규칙에
#: 걸리면 전용 별칭이 영영 쓰이지 않는다.
_SOLE_EXEMPT_TAGS = frozenset({"BEST_TRADE", "WORST_TRADE"})


def assemble_daily(
    features: DailyFeatures,
    *,
    exclude: set[str] | None = None,
    headline_override: str | None = None,
) -> DailyReportText:
    """일일 리포트 문구 — 헤드라인 한 줄과 장면별 별칭.

    `headline_override` 는 LLM 이 만든 헤드라인이다 (`services/headline.py`). 없으면 블록에서
    고른다. 생성이 실패하면 호출부가 `None` 을 넘기므로 이 함수는 늘 문장을 낸다.
    """
    base = features.model_dump(by_alias=True)
    # 덮어쓸 문장이 있어도 블록은 고른다. 비용이 없고, 어느 블록이 대체됐는지 남겨두면
    # 미리보기에서 두 문장을 나란히 볼 수 있다.
    head = choose(
        "daily_headline",
        base,
        seed=f"{features.account_id}:{features.season_day}:daily_headline",
        exclude=exclude,
    )

    win_count = sum(1 for s in features.scenes if s.tag in _WIN_TAGS)
    loss_count = sum(1 for s in features.scenes if s.tag in _LOSS_TAGS)

    aliases: list[str] = []
    # 덮어썼으면 그 블록은 쓰이지 않았다. 목록에 남기면 다음 일차의 dedup 이 쓰지도 않은
    # 문장을 피하게 된다.
    block_ids: list[str] = [] if headline_override else [head["id"]]
    # 한 리포트 안에서 이미 쓴 별칭. 시드만 다르게 해서는 겹침을 막지 못한다 — 후보가 4개인데
    # 두 번 뽑으면 같은 것이 나올 수 있고, 실제로 한 화면에 같은 별칭이 두 번 나왔다.
    # `choose` 는 빼고 나서 후보가 비면 빼지 않으므로, 태그가 셋이어도 문장이 사라지지 않는다.
    used_aliases: set[str] = set(exclude or ())
    for index, scene in enumerate(features.scenes):
        if scene.tag not in _SOLE_EXEMPT_TAGS:
            if scene.tag in _WIN_TAGS and win_count == 1:
                aliases.append(SOLE_WIN_ALIAS)
                continue
            if scene.tag in _LOSS_TAGS and loss_count == 1:
                aliases.append(SOLE_LOSS_ALIAS)
                continue
        # 장면마다 시드가 달라야 같은 태그 두 건에 같은 별칭이 붙지 않는다.
        alias = choose(
            "trade_alias",
            {"tag": scene.tag},
            seed=f"{features.account_id}:{features.season_day}:trade_alias:{index}",
            exclude=used_aliases,
        )
        used_aliases.add(alias["id"])
        aliases.append(alias["text"])
        block_ids.append(alias["id"])

    return DailyReportText(
        headline=headline_override or head["text"],
        scene_aliases=aliases,
        block_ids=block_ids,
    )


def type_name_of(features: SeasonFeatures) -> str:
    """유형 카드 이름만. 프롬프트가 조립 전에 필요로 한다 — 조립기와 같은 시드를 쓴다."""
    card = choose(
        "investor_type",
        features.model_dump(by_alias=True),
        seed=f"{features.account_id}:investor_type",
    )
    return card["name"]


def assemble_season(
    features: SeasonFeatures,
    *,
    exclude: set[str] | None = None,
    narration: Narration | None = None,
) -> SeasonReportText:
    """결과지 여덟 덩이. 명세 8절.

    **LLM 이 쓰는 곳은 ④와 ⑦ 두 자리뿐이고, 나머지는 코드와 블록이라 같은 입력이면 같은
    문장이 나온다.** 지금은 그 두 자리도 폴백 블록으로 채운다 — 생성은 AI-7 에서 붙이고,
    붙인 뒤에도 실패하면 이 자리로 돌아온다.

    선언이 없는 계좌는 ③⑦ 이 통째로 빠지고 ①②④⑤⑥⑧ 은 그대로 나온다.
    """
    base = features.model_dump(by_alias=True)
    facts = TraitFacts(**features.facts.model_dump())
    block_ids: list[str] = []

    def take(slot: str, when: dict[str, Any], seed: str) -> dict[str, Any]:
        block = choose(slot, when, seed=f"{features.account_id}:{seed}", exclude=exclude)
        block_ids.append(block["id"])
        return block

    # ①② 카드와 한 줄. 한 블록이 둘을 함께 갖는다 — 조건이 `profileLevel` 하나라서다.
    #
    # **카드(①②)·특징(⑥)·대조(⑦)·앞으로(⑧)가 전부 성향 5단계에서 온다.**
    # 4축은 ④ 프롬프트와 옛 선언 대조에만 남아 있고, `selfChoices` 가 오지 않으면
    # 그 경로는 조용히 비활성이다.
    card = take("investor_type", base, "investor_type")

    # ⑥ 특징 셋. 제목은 블록, 본문은 템플릿이 수치를 넣어 만든다.
    points = {item.item: item.point for item in features.profile_items}
    traits = []
    # 판정이 안 선 계좌는 특징을 늘어놓지 않는다 — 근거가 없는 것을 세 번 말하게 된다.
    trait_items = (
        []
        if features.profile_level is None
        else pick_trait_items(features.profile_items, features.gap_item)
    )
    for item in trait_items:
        when = {"traitItem": item, "traitDirection": item_direction(points[item])}
        title = take("trait_title", when, f"trait:{item}")
        traits.append(TraitItem(axis=item, title=title["text"], body=to_item_body(item, facts)))

    if not trait_items:
        title = take("trait_title", {"traitItem": None}, "trait:fallback")
        traits.append(TraitItem(axis="", title=title["text"], body=""))

    # ④ LLM 이 쓴 문장이 있으면 그것, 없으면 폴백 블록. **블록 ID 는 폴백일 때만 남긴다** —
    # 생성에 성공하면 블록을 쓰지 않았으므로 남길 ID 가 없다.
    generated = narration or Narration()
    if generated.actual:
        actual_text = generated.actual
    else:
        actual_text = take("actual_fallback", {"profileMatch": features.profile_match}, "actual")[
            "text"
        ]

    # ⑧ 선언이 있으면 가장 갈린 항목, 없으면 가장 두드러진 항목으로.
    #
    # **판정이 안 선 계좌는 항목을 지목하지 않는다.** 다섯이 중앙 근처인데 "이번 시즌은
    # 거의 다 넣는 쪽이었습니다" 로 닫으면 없는 방향을 단정하는 문장이 된다 — 폴백으로 간다.
    #
    # 방향은 **그 항목의 관찰 점수**에서 온다. 선언에서 가져오면 "덜 실어 보세요" 를
    # 현금을 잔뜩 남긴 사람에게 말하게 된다.
    next_when: dict[str, Any] = {}
    if trait_items:
        next_item = features.gap_item if features.gap_item in trait_items else trait_items[0]
        next_when = {
            "nextItem": next_item,
            "nextDirection": item_direction(points[next_item]),
        }
    next_step = take("next_step", next_when, "next_step")

    # 총평. 국면 블록이 연결 어미로 끝나므로 사이에 접속사를 넣지 않는다.
    #
    # **국면은 그 구간의 지수 방향으로 고른다.** 예전에는 시즌마다 운영자가 쓴 한 문장을
    # 붙였는데, 리포트가 매일 나오면서 같은 문장이 모두에게 사흘 내내 나갔다. 지수 값이
    # 안 오면(`marketMove` 가 빈 문자열) 방향을 말하지 않는 폴백으로 간다.
    #
    # 시드에 방향을 섞는다 — 시즌 이름만으로 뽑으면 방향이 바뀌어도 같은 변주만 나온다.
    #
    # **앞선 일차에 쓴 것은 뺀다.** 리포트가 매일 나오는데 지수 방향은 며칠씩 이어지므로,
    # 빼지 않으면 같은 국면 문장이 사흘 내내 같은 자리에 나온다.
    context = choose(
        "season_context",
        base,
        seed=f"{features.season_id}:{features.market_move}:season_context",
        exclude=exclude,
    )
    effect = choose("type_effect", base, seed=f"{features.account_id}:type_effect", exclude=exclude)
    block_ids += [context["id"], effect["id"]]
    tail = render(
        effect["text"],
        {
            "winCount": features.win_count,
            "returnRate": features.return_rate,
            "rankRate": features.rank_rate,
        },
    )
    verdict = f"{context['text']} {tail}"

    return SeasonReportText(
        type_emoji=card.get("emoji", ""),
        type_name=card["name"],
        type_description=card["text"],
        narrative=SeasonNarrative(
            one_line=card.get("oneLine", ""),
            self_view=to_self_view_from_answers(features),
            actual=actual_text,
            traits=traits,
            next_step=next_step["text"],
            profile_gap=to_profile_gap_line(features),
            loss_tolerance=to_loss_tolerance_line(
                features.loss_tolerance_pct, features.max_drawdown
            ),
        ),
        verdict=verdict,
        block_ids=block_ids,
    )

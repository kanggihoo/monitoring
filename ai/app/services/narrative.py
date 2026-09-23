"""결과지 문장을 만드는 템플릿. 명세 8절.

**블록과 템플릿을 나눈 기준은 숫자다.** 블록에는 숫자를 쓰지 않는다(문안 규칙) — 수치가
들어가는 문장은 전부 여기서 만든다. 그래서 ⑥ 특징은 제목만 블록이고 본문은 템플릿이다.

LLM 은 ④⑦ 두 자리뿐이고, 그 둘도 실패하면 폴백 블록으로 내려앉는다. 나머지는 같은 입력이면
같은 문장이 나온다.
"""

from __future__ import annotations

from collections.abc import Sequence
from dataclasses import dataclass
from decimal import Decimal

from app.schemas.report import ProfileItem, SeasonFeatures
from app.services.profile import ITEMS
from app.services.profile import NEUTRAL as ITEM_MIDPOINT
from app.services.report_blocks import render

#: **부정형으로만 쓰이는 말은 사전에 넣지 않는다.** `무리하지` 를 수비 어휘로 두면
#: "무리하지 않으셨습니다" 에서 뒤의 `않` 이 부정으로 읽혀 공격으로 뒤집힌다.
#:
#: 부정 표현. 이 말이 뒤따르면 앞의 어휘는 **반대 뜻**이라 방향 위반으로 보지 않는다 —
#: "오래 머무르지 않았습니다" 는 `장기` 가 아니라 `단기` 다.
NEGATIONS: tuple[str, ...] = ("않", "못", "없")

#: 부정 표현을 찾아보는 범위(글자). "오래 머무르지 않" 이 들어갈 만큼만 본다.
NEGATION_WINDOW = 8

#: ⑥ 은 세 개까지 낸다. 흐린 항목을 빼고 나면 그보다 적을 수 있고, 그건 정상이다.
TRAIT_COUNT = 3


@dataclass(frozen=True)
class TraitFacts:
    """⑥ 본문과 ⑦ `scoreLabel` 이 인용하는 수치. 전부 payload 에서 그대로 온다."""

    max_drawdown: Decimal | None = None
    cash_ratio: Decimal | None = None
    closed_count: int = 0
    hold_days: Decimal | None = None
    buy_count: int = 0
    fomo_buy_count: int = 0
    profit_sells: int = 0
    loss_sells: int = 0
    dip_buys: int = 0
    dip_sells: int = 0
    quick_stops: int = 0
    quick_profits: int = 0

    def values(self) -> dict[str, str]:
        """템플릿 자리에 넣을 표시 문자열.

        **기간은 게임 거래일로 쓴다.** 게임 1거래일이 실시간 약 36초라 평균 보유가 수백
        거래일인데, "나흘" 로 쓰면 실투자 감각의 틀린 값이 문장에 실린다.
        """
        return {
            "mdd": (
                "알 수 없음" if self.max_drawdown is None else f"{abs(self.max_drawdown):.1f}%"
            ),
            "cashRatio": "알 수 없음" if self.cash_ratio is None else f"{self.cash_ratio:.1%}",
            "closed": str(self.closed_count),
            "holdDays": "알 수 없음" if self.hold_days is None else f"{self.hold_days:.0f}거래일",
            "buy": str(self.buy_count),
            "fomo": str(self.fomo_buy_count),
            "profitSells": str(self.profit_sells),
            "lossSells": str(self.loss_sells),
            "dipBuys": str(self.dip_buys),
            "dipSells": str(self.dip_sells),
            "quickStops": str(self.quick_stops),
            "quickProfits": str(self.quick_profits),
        }


def to_loss_tolerance_line(declared_pct: int | None, actual_mdd: Decimal | None) -> str | None:
    """손실감내 선언 대 실제 낙폭. **변환 없이 맞붙는 유일한 줄이다.**

    선언도 %, 실제도 % 라 백분위도 띠도 거치지 않는다. 그래서 설명이 필요 없다.

    `maxDrawdown` 은 **견딘 것이 아니라 겪은 것**이다 — 중간에 전량 정리한 사람은 낙폭이
    작게 찍힌다. "잘 견디셨습니다" 로 쓰면 틀린다.
    """
    # 실제를 재지 못했으면 맞붙일 상대가 없다. 0 으로 두면 "괜찮다고 하셨고 실제로는
    # -0.0% 를 겪으셨습니다" 가 되어, 재지 못한 것이 잘 버틴 것으로 읽힌다.
    if declared_pct is None or actual_mdd is None:
        return None
    return (
        f"{declared_pct}% 까지 괜찮다고 하셨고, 실제로는 -{abs(actual_mdd):.1f}% 를 겪으셨습니다."
    )


#: 항목별 방향 어휘. 선언과 관찰을 한 문장으로 잇는다. `(낮은 쪽, 높은 쪽)` 순서다.
PROFILE_WORDS: dict[str, tuple[str, str]] = {
    "INVESTED": ("현금을 넉넉히 두겠다", "가진 것을 거의 다 넣겠다"),
    "DRAWDOWN": ("조금만 빠져도 정리하겠다", "큰 하락도 견디겠다"),
    "ENTRY": ("충분히 떨어진 뒤에 사겠다", "오른 자리에서도 따라 사겠다"),
    "DIP": ("빠지면 바로 정리하겠다", "빠지면 더 담겠다"),
    "STOP": ("손실을 빨리 끊겠다", "돌아올 때까지 들고 가겠다"),
}

#: 관찰 쪽 서술. 선언과 같은 항목을 **과거형**으로 말한다.
PROFILE_OBSERVED: dict[str, tuple[str, str]] = {
    "INVESTED": ("현금을 넉넉히 남기셨습니다", "가진 것을 거의 다 넣으셨습니다"),
    "DRAWDOWN": ("크게 빠지기 전에 정리하셨습니다", "큰 하락을 그대로 안고 가셨습니다"),
    "ENTRY": ("충분히 내린 자리에서 담으셨습니다", "오른 자리에서도 따라 사셨습니다"),
    "DIP": ("빠진 자리에서 정리하셨습니다", "빠진 자리에서 더 담으셨습니다"),
    "STOP": ("손실을 빨리 끊으셨습니다", "손실을 오래 안고 가셨습니다"),
}

#: 1~4 점을 두 갈래로 자르는 경계. 2 이하가 낮은 쪽이다.
PROFILE_CUT = 2


def to_profile_gap_line(features: SeasonFeatures) -> str | None:
    """⑦ 선언과 관찰이 가장 크게 갈린 항목을 한 문장으로.

    **숫자를 쓰지 않는다** — 점수는 막대(⑤)에서 이미 보여줬다. 여기서 또 내면 같은 값이
    두 번 나오고, 그중 하나만 흐리게 그려져 있으면 읽는 사람이 어느 쪽을 믿을지 모른다.

    선언이 없거나 갈린 항목이 없으면 `None` 이고 그 칸이 통째로 빠진다. 없는 선언을 지어내
    "예상과 달랐습니다" 를 쓰면 거짓말이다.
    """
    item = features.gap_item
    if item is None or features.gap_self_point is None or features.gap_point is None:
        return None
    declared = PROFILE_WORDS[item][features.gap_self_point > PROFILE_CUT]
    observed = PROFILE_OBSERVED[item][features.gap_point > PROFILE_CUT]
    return f"{declared}고 하셨는데, 실제로는 {observed}."


#: ⑥ 특징 본문 — 항목별. **payload 값을 그대로 인용한다.** 축 점수만 보여주면
#: "왜 4점인가" 에 답이 없다.
ITEM_BODIES: dict[str, str] = {
    "INVESTED": "현금은 {cashRatio}를 남기셨습니다.",
    "DRAWDOWN": "최대 낙폭이 {mdd}였습니다.",
    "ENTRY": "매수 {buy}건 중 {fomo}건이 창 안 고점 근처였습니다.",
    "DIP": "급락 자리에서 담은 것이 {dipBuys}건, 정리한 것이 {dipSells}건이었습니다.",
    "STOP": "빨리 판 매도 중 손절이 {quickStops}건, 익절이 {quickProfits}건이었습니다.",
}

#: 잴 수 없었을 때. **"현금은 알 수 없음 를 남기셨습니다" 를 막는 자리다** — 값이 비면
#: 조사까지 어긋나 문장이 통째로 깨진다.
ITEM_BODIES_UNKNOWN: dict[str, str] = {
    "INVESTED": "현금 비중은 재지 못했습니다.",
    "DRAWDOWN": "최대 낙폭은 재지 못했습니다.",
    "ENTRY": "매수가 없어 들어간 자리는 재지 못했습니다.",
    "DIP": "급락 자리에서의 움직임은 재지 못했습니다.",
    "STOP": "빨리 판 매도가 없어 파는 쪽은 재지 못했습니다.",
}

#: 항목 점수를 두 갈래로 자르는 경계. 2 이하가 낮은 쪽(`L`)이다.
ITEM_CUT = 2


def item_direction(point: int | None) -> str | None:
    """항목 점수 → 방향. 블록 `when` 의 `traitDirection`·`nextDirection` 이 이 값이다."""
    return None if point is None else ("H" if point > ITEM_CUT else "L")


def pick_trait_items(items: Sequence[ProfileItem], gap_item: str | None) -> list[str]:
    """⑥ 특징 셋을 고른다 — 중앙에서 먼 둘 + 가장 갈린 항목.

    **흐린 항목은 후보에서 뺀다.** 근거가 얇은 자리를 "이 사람의 특징" 이라고 세 번 말하면
    안 된다. 그래서 개수가 셋보다 적을 수 있고, 그건 정상이다.

    동점이면 `ITEMS` 순서로 자른다 — 순서를 고정하지 않으면 같은 입력에 다른 문장이 나온다.
    """
    order = {item: index for index, item in enumerate(ITEMS)}
    usable = [item for item in items if item.point is not None and not item.provisional]
    ranked = sorted(
        usable,
        key=lambda item: (-abs(item.point - ITEM_MIDPOINT), order[item.item]),
    )
    picked = [item.item for item in ranked[:2]]
    if gap_item and gap_item not in picked and any(item.item == gap_item for item in usable):
        picked.append(gap_item)
    else:
        picked += [item.item for item in ranked[2:] if item.item not in picked][:1]
    return picked[:TRAIT_COUNT]


def to_item_body(item: str, facts: TraitFacts) -> str:
    """⑥ 본문. 잴 수 없었던 값은 빼고 쓴다.

    4축의 `to_trait_body` 와 달리 **제목과 어긋나는 값을 골라낼 필요가 없다** — 항목 하나가
    지표 하나라서, 인용한 수치가 곧 그 제목의 근거다. `RISK` 가 세 재료의 합성이라 현금
    비중만 반대쪽을 가리키던 문제가 여기서는 생기지 않는다.
    """
    missing = {
        "INVESTED": facts.cash_ratio is None,
        # 낙폭 스냅샷이 없는 일차가 있다. `ITEM_BODIES_UNKNOWN` 이 이 자리를 위해 있는데
        # 여기가 `False` 로 굳어 있어서 "최대 낙폭이 알 수 없음였습니다" 가 나갈 수 있었다.
        "DRAWDOWN": facts.max_drawdown is None,
        "ENTRY": facts.buy_count == 0,
        "DIP": facts.dip_buys + facts.dip_sells == 0,
        "STOP": facts.quick_stops + facts.quick_profits == 0,
    }[item]
    template = ITEM_BODIES_UNKNOWN[item] if missing else ITEM_BODIES[item]
    return render(template, facts.values())


#: ④ 방향 검사에 쓰는 어휘. 모델이 판정과 **반대 방향**을 말하면 그 문장을 버린다.
#:
#: 4축 시절 `DIRECTION_WORDS` 를 항목 기준으로 옮긴 것이다. 항목 하나가 지표 하나라
#: 어휘가 더 좁아졌고, 그만큼 스쳐 지나가는 말에 잘못 걸릴 여지도 줄었다.
ITEM_DIRECTION_WORDS: dict[str, dict[str, tuple[str, ...]]] = {
    "INVESTED": {
        "H": ("많이 넣", "거의 다 넣", "크게 실", "전부 담"),
        # 사이에 부사가 끼므로("현금을 **넉넉히** 남기") 조각을 짧게 잡는다.
        "L": ("넉넉히 남기", "여유를 남기", "현금을 두", "덜 실"),
    },
    "DRAWDOWN": {
        "H": ("큰 하락", "깊은 조정", "안고 가", "버티"),
        "L": ("깊이 빠지기 전", "일찍 정리", "크게 흔들리지"),
    },
    "ENTRY": {
        "H": ("오른 뒤", "오르는 흐름", "따라 사", "달리는", "추격"),
        "L": ("빠진 자리", "눌린 자리", "내려간 자리", "역발상", "떨어진 뒤"),
    },
    "DIP": {
        "H": ("더 담", "기회로 보", "받아내"),
        "L": ("덜어내", "발을 빼", "정리하고 나"),
    },
    "STOP": {
        "H": ("돌아올 때까지", "오래 안고", "버티"),
        "L": ("빨리 끊", "빠르게 끊", "먼저 정리", "물러서"),
    },
}


#: ④ 프롬프트에 넘기는 **방향 문장.** 점수를 주면 모델이 방향을 반대로 읽는다 —
#: 헤드라인에서 `mainMistake` 를 "가장 두드러졌다" 로만 적었다가 모델이 잘한 일로 읽고
#: "성과를 이끌었습니다" 를 낸 적이 있다(실측). 방향을 문장으로 확정해 넘기고 모델은 잇는다.
#:
#: **⑥ 제목과 같은 비교형으로 쓴다.** 절대 주장으로 쓰면 사분위 점수와 실제 수치가
#: 어긋나는 계좌에서 ④ 와 ⑥ 이 서로 다른 말을 한다.
ITEM_HINTS: dict[str, dict[str, str]] = {
    "INVESTED": {"H": "가진 것을 많이 넣은 편이었다", "L": "현금을 넉넉히 남긴 편이었다"},
    "DRAWDOWN": {"H": "큰 하락을 안고 갔다", "L": "깊이 빠지기 전에 움직였다"},
    "ENTRY": {"H": "오른 자리에서도 따라 샀다", "L": "빠진 자리를 노려 담았다"},
    "DIP": {"H": "떨어질 때 더 담았다", "L": "떨어질 때 덜어냈다"},
    "STOP": {"H": "손실을 오래 안고 갔다", "L": "손실을 빠르게 끊었다"},
}


#: ③ 선언 문장 조각. `(연결형, 종결형)` 이다.
#:
#: **연결형과 종결형을 쌍으로 둔다.** 종결형에서 어미만 잘라 붙이면 "크게 걸지 않겠고"
#: 같은 비문이 나온다 — 4축 시절에 실제로 겪었다.
#:
#: 답 1~4 를 두 갈래로만 가른다. 1과 2 를 달리 쓰지 않는 것은, 그 차이가 이미 ⑤ 막대에
#: 숫자로 나가기 때문이다. 문장까지 네 갈래로 나누면 같은 정보가 두 번 나온다.
SELF_VIEW_ITEMS: dict[str, dict[str, tuple[str, str]]] = {
    "INVESTED": {
        "H": ("가진 것을 거의 다 넣고", "가진 것을 거의 다 넣겠다고"),
        "L": ("현금을 넉넉히 남기고", "현금을 넉넉히 남기겠다고"),
    },
    "DRAWDOWN": {
        "H": ("큰 하락도 견디고", "큰 하락도 견디겠다고"),
        "L": ("조금만 빠져도 정리하고", "조금만 빠져도 정리하겠다고"),
    },
    "ENTRY": {
        "H": ("오른 자리에서도 따라 사고", "오른 자리에서도 따라 사겠다고"),
        "L": ("충분히 떨어진 뒤에 사고", "충분히 떨어진 뒤에 사겠다고"),
    },
    "DIP": {
        "H": ("떨어지면 더 담고", "떨어지면 더 담겠다고"),
        "L": ("떨어지면 바로 정리하고", "떨어지면 바로 정리하겠다고"),
    },
    "STOP": {
        "H": ("돌아올 때까지 들고 가고", "돌아올 때까지 들고 가겠다고"),
        "L": ("손실은 빨리 끊고", "손실은 빨리 끊겠다고"),
    },
}

#: ③ 에 싣는 선언 개수. 다섯을 다 이으면 한 문장이 너무 길어진다.
SELF_VIEW_COUNT = 2


def to_self_view_from_answers(features: SeasonFeatures) -> str | None:
    """③ 내가 생각한 나. 선언이 없으면 `None` 이고 그 칸이 통째로 빠진다.

    **가장 뚜렷하게 고른 둘만 싣는다.** 다섯을 다 이으면 읽기 어렵고, 나머지는 ⑤ 막대에
    선언 점수로 이미 나가 있다.
    """
    declared = [
        (item.item, item.self_point)
        for item in features.profile_items
        if item.self_point is not None
    ]
    if not declared:
        return None
    order = {item: index for index, item in enumerate(ITEMS)}
    ranked = sorted(
        declared,
        key=lambda pair: (-abs(pair[1] - ITEM_MIDPOINT), order[pair[0]]),
    )[:SELF_VIEW_COUNT]
    # 문장 순서는 `ITEMS` 순서로 되돌린다 — 뚜렷한 순서로 읽으면 어색하다.
    ranked.sort(key=lambda pair: order[pair[0]])
    pieces = [SELF_VIEW_ITEMS[item][item_direction(point)] for item, point in ranked]
    joined = [piece[0] for piece in pieces[:-1]] + [pieces[-1][1]]
    return "스스로는 " + ", ".join(joined) + " 보셨습니다."

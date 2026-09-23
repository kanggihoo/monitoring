"""투자자 성향 5단계 판정 — 온보딩 선언과 거래 행동을 **같은 척도**로 옮긴다.

기준 문서는 `투자자 유형 분류 개발 기준 문서 2-1` 이고, 요지는 이것이다.

```
온보딩    상황형 5문항, 각 4지선다  →  합계 5~20점  →  5단계
거래      행동 5항목, 각 1~4점      →  합계 5~20점  →  5단계    ← 같은 구간표
```

**뒤쪽 절반이 같아야 두 결과를 나란히 놓을 수 있다.** 한쪽만 백분위로 고르게 펴면 분포
모양이 어긋나, 아무 관계가 없어도 불일치가 구조적으로 생긴다 (목업에서 우연 일치가
32% → 20% 로 떨어졌다). 그래서 행동 쪽도 "항목마다 1~4점, 다섯 개 합산" 으로 맞춘다.

4축 8유형 판정(`axes.py` · `investor_type.py`)은 그대로 두었다. 조립기를 옮겨 붙이기 전까지
두 체계가 공존한다.
"""

from __future__ import annotations

from dataclasses import dataclass
from decimal import ROUND_HALF_UP, Decimal

#: 항목 순서. 온보딩 Q1~Q5 와 같은 순서다 — 1:1 로 계산하려는 것이 아니라,
#: 결과지에서 "가장 크게 갈린 항목" 의 짝을 찾기 쉬우라고 맞춰 둔 것이다.
ITEMS: tuple[str, ...] = ("INVESTED", "DRAWDOWN", "ENTRY", "DIP", "STOP")

#: 화면에 그대로 나가는 이름. 4점 쪽(공격)을 가리키는 말이 아니라 **항목 이름**이다 —
#: 막대가 길수록 위험을 더 진 것이라고 읽히면 되고, 축 이름으로 방향을 말하지 않는다.
ITEM_LABELS: dict[str, str] = {
    "INVESTED": "투입 비중",
    "DRAWDOWN": "손실 감내",
    "ENTRY": "진입 타이밍",
    "DIP": "급락 대응",
    "STOP": "손실 정리",
}

#: 대응하는 온보딩 문항. 결과지가 "무엇을 선언했고 무엇을 했는가" 를 짝지을 때 쓴다.
ITEM_QUESTIONS: dict[str, str] = {
    "INVESTED": "Q1",
    "DRAWDOWN": "Q2",
    "ENTRY": "Q3",
    "DIP": "Q4",
    "STOP": "Q5",
}

#: 값이 클수록 **보수적**인 항목. 점수를 뒤집는다.
#:
#: `STOP` 은 "빨리 판 것 중 손절 비중" 이라 높을수록 손실을 빨리 끊는 것이고, 그건 Q5 의
#: 1점("손실이 커지기 전에 바로 끊는다")이다. 나머지 넷은 높을수록 4점 쪽이다.
REVERSED: frozenset[str] = frozenset({"STOP"})

#: 항목별 사분위 컷 (P25 · P50 · P75). 이 셋이 1~4점의 경계다.
#:
#: **목업 3,000계좌(seed=11)에서 뽑은 임시값이다.** 리허설 시즌 집계가 들어오면
#: 통째로 교체한다 — 임계값을 코드에 박지 않고 여기 상수로 뺀 이유가 그것이다.
#: 교체 전까지는 점수의 **절대 위치**를 믿으면 안 된다. 순서만 맞다.
#:
#: `DRAWDOWN` 행이 특히 못 미덥다. 목업의 낙폭은 생성된 시장이 통째로 좌우해서, 시드만
#: 바꾸면 P50 이 0.57 · 3.88 · 15.08 로 26배 흔들린다. 나머지는 행동 비율이라 안정적이다.
QUARTILES: dict[str, tuple[Decimal, ...]] = {
    # 1 - 현금/총평가액. 얼마나 넣었나.
    "INVESTED": tuple(map(Decimal, ("0.2773", "0.3781", "0.4806"))),
    # 최대 낙폭(%, 절대값).                                          ★ 교체 필수
    "DRAWDOWN": tuple(map(Decimal, ("9.3664", "15.3041", "22.1612"))),
    # 창 안 고점 근처 매수 / 전체 매수.
    "ENTRY": tuple(map(Decimal, ("0.0698", "0.0922", "0.1186"))),
    # 저점 매수 / (저점 매수 + 저점 손절). 떨어질 때 담았나 팔았나.
    "DIP": tuple(map(Decimal, ("0.4000", "0.5263", "0.6667"))),
    # 빠른 손절 / (빠른 손절 + 빠른 익절). **뒤집어 쓴다.**
    "STOP": tuple(map(Decimal, ("0.2000", "0.5000", "0.6667"))),
}

#: 합계 점수 → 5단계. 온보딩 채점표와 **같은 표를 쓴다.**
LEVEL_BANDS: tuple[tuple[int, int, int], ...] = (
    (5, 7, 1),
    (8, 10, 2),
    (11, 13, 3),
    (14, 16, 4),
    (17, 20, 5),
)

LEVEL_NAMES: dict[int, str] = {
    1: "안정형",
    2: "안정추구형",
    3: "위험중립형",
    4: "적극투자형",
    5: "공격투자형",
}

#: 화면에 나가는 캐릭터 이름. **"학술적 정답 유형" 이 아니라 서비스가 붙인 이름**이라고
#: README 와 발표 자료에 적는다 (기준 문서 2절).
PERSONAS: dict[int, str] = {
    1: "철벽의 자산 방어자",
    2: "돌다리 두드리는 분석가",
    3: "유연한 자산 조율사",
    4: "변동성을 즐기는 트레이더",
    5: "한 방을 노리는 승부사",
}

#: 판정이 서지 않은 상태. 억지로 단계를 붙이지 않는다 — 조건을 못 넘은 사람에게 유형을
#: 주면 그 유형의 사람 수가 가장 많아지고 문장도 거짓이 된다.
UNDETERMINED = "UNDETERMINED"

#: 점수를 낼 수 있는 항목이 이보다 적으면 `UNDETERMINED`.
#: 다섯 중 셋은 있어야 평균이 그 사람을 대표한다고 볼 수 있다.
MIN_ITEMS = 3

#: 표본 축소의 세기. `n / (n + K)` 라 K 가 클수록 중앙으로 더 끌어당긴다.
#: 4축 판정과 같은 값을 쓴다 — 두 체계가 공존하는 동안 세기가 다르면 비교가 어긋난다.
SHRINK_K = 8

#: 이 미만이면 그 항목을 흐리게 그린다. 축소 계수가 0.38 이하로 떨어지는 지점이다.
PROVISIONAL_SAMPLE = 5

#: 1~4 척도의 중앙. 축소는 이 값 쪽으로 끌어당긴다.
NEUTRAL = Decimal("2.5")

#: 선언과 관찰의 거리. **우연 일치가 32% 라 "같은 단계" 만으로는 정보가 안 된다** —
#: 세 구간으로 나눠 "조금 달랐다" 를 따로 둔다 (기준 문서 6절).
MATCH_BANDS: dict[str, str] = {
    "SAME": "거의 그대로였습니다",
    "NEAR": "조금 달랐습니다",
    "FAR": "예상과 많이 달랐습니다",
}

#: 온보딩 답안 문자열의 길이와 허용 문자.
ANSWER_LENGTH = len(ITEMS)
ANSWER_CHOICES = ("1", "2", "3", "4")


@dataclass(frozen=True)
class RawItems:
    """항목 다섯의 원지표와 표본 수. 나눗셈은 `services/inbound.py` 가 끝내고 여기로 온다.

    **원지표가 `None` 이면 잴 수 없었다는 뜻이다** (분모 0). 0 으로 두면 한 번도 안 판 사람이
    "가장 빨리 끊었다" 가 된다. `None` 인 항목은 평균에서 빠진다.
    """

    invested: Decimal | None = None
    drawdown: Decimal | None = None
    entry: Decimal | None = None
    dip: Decimal | None = None
    stop: Decimal | None = None

    #: 표본 축소의 분모. **항목마다 다르다** — 고정하면 매도 4건으로 만든 `STOP` 점수가
    #: 전체 거래 30건을 근거로 축소를 빠져나간다.
    n_invested: int = 0
    n_drawdown: int = 0
    n_entry: int = 0
    n_dip: int = 0
    n_stop: int = 0

    def value(self, item: str) -> Decimal | None:
        return getattr(self, item.lower())

    def sample(self, item: str) -> int:
        return getattr(self, f"n_{item.lower()}")


@dataclass(frozen=True)
class ItemScore:
    """항목 하나의 결과. 결과지 막대 하나가 이 모양으로 그려진다."""

    item: str
    label: str
    #: 1~4. 축소를 거친 값이라 정수가 아니다. 화면에는 반올림해서 낸다.
    score: Decimal | None
    #: 표본이 모자라거나 잴 수 없어 흐리게 그려야 하는 항목인가.
    provisional: bool

    @property
    def rounded(self) -> int | None:
        return None if self.score is None else _round(self.score)


@dataclass(frozen=True)
class Profile:
    """판정 결과. `level` 이 `None` 이면 `UNDETERMINED` 다."""

    level: int | None
    total: int | None
    items: tuple[ItemScore, ...]

    @property
    def code(self) -> str:
        return UNDETERMINED if self.level is None else f"LEVEL_{self.level}"

    @property
    def name(self) -> str:
        return "" if self.level is None else LEVEL_NAMES[self.level]

    @property
    def persona(self) -> str:
        return "" if self.level is None else PERSONAS[self.level]


def _round(value: Decimal) -> int:
    return int(value.quantize(Decimal(1), rounding=ROUND_HALF_UP))


def to_point(value: Decimal | None, cuts: tuple[Decimal, ...], *, reverse: bool) -> Decimal | None:
    """원지표 → 1~4점. 사분위 컷 셋이 네 칸의 경계다.

    **표 밖을 더 쪼개지 않는다.** 상위 25% 안에서 얼마나 더 위인지는 컷 세 개로 알 수 없고,
    외삽하면 표를 만들 때 없던 정밀도를 지어내는 일이 된다. 그래서 위아래가 각각 한 칸이다.

    `reverse` 는 값이 클수록 보수적인 항목(`STOP`)에 쓴다.
    """
    if value is None:
        return None
    point = 1
    for cut in cuts:
        if value < cut:
            break
        point += 1
    if reverse:
        point = 5 - point
    return Decimal(point)


def shrink(point: Decimal, sample: int) -> Decimal:
    """근거가 적으면 중앙(2.5) 쪽으로 끌어당긴다.

    매도 2건 중 1건이 손절이라고 "손절 성향 50%" 라 말할 근거는 약하다. 하드 컷오프 대신
    연속적으로 줄이면, 거래를 거의 안 한 사람은 다섯 항목이 자연히 중앙에 모여 별도 분기
    없이 "아직 알 수 없음" 에 가까운 결과가 나온다.
    """
    if sample <= 0:
        return NEUTRAL
    return NEUTRAL + (point - NEUTRAL) * Decimal(sample) / Decimal(sample + SHRINK_K)


def score_items(raw: RawItems) -> tuple[ItemScore, ...]:
    """원지표 다섯 → 항목 점수 다섯. 순서는 `ITEMS` 와 같다."""
    scored = []
    for item in ITEMS:
        value, sample = raw.value(item), raw.sample(item)
        point = to_point(value, QUARTILES[item], reverse=item in REVERSED)
        scored.append(
            ItemScore(
                item=item,
                label=ITEM_LABELS[item],
                score=None if point is None else shrink(point, sample),
                provisional=point is None or sample < PROVISIONAL_SAMPLE,
            )
        )
    return tuple(scored)


def to_total(items: tuple[ItemScore, ...]) -> int | None:
    """항목 점수 → 합계 5~20.

    **잴 수 있었던 항목의 평균에 5를 곱한다.** 단순 합이 아닌 이유는 항목이 빠질 수 있어서다 —
    네 항목만 있는 사람의 합(4~16)을 다섯 항목 구간표(5~20)에 대면 전원이 안정형이 된다.

    셋 미만이면 `None` 이다. 그 사람에 대해 아는 것이 너무 적다.
    """
    measured = [item.score for item in items if item.score is not None]
    if len(measured) < MIN_ITEMS:
        return None
    total = _round(sum(measured, Decimal(0)) / Decimal(len(measured)) * 5)
    return max(5, min(20, total))


def to_level(total: int | None) -> int | None:
    """합계 5~20 → 1~5 단계. **온보딩 채점과 같은 표다.**"""
    if total is None:
        return None
    for low, high, level in LEVEL_BANDS:
        if low <= total <= high:
            return level
    return None


def to_profile(raw: RawItems) -> Profile:
    """원지표 → 판정 결과 한 덩이."""
    items = score_items(raw)
    total = to_total(items)
    return Profile(level=to_level(total), total=total, items=items)


def score_answers(answers: str) -> int | None:
    """온보딩 답안 `"24312"` → 합계 5~20.

    프론트가 그 자리에서 채점하지만 **서버도 같은 규칙을 갖는다** — 저장 전 검증과, 나중에
    구간표를 고친 뒤 과거 선언을 다시 계산하는 데 쓴다. 그래서 원본 문자열을 남긴다.

    형식이 어긋나면 `None` 이다. 선언이 없는 것과 같게 다룬다 — 온보딩 실패가 리포트를
    막을 이유가 아니다.
    """
    if len(answers) != ANSWER_LENGTH or any(c not in ANSWER_CHOICES for c in answers):
        return None
    return sum(int(c) for c in answers)


#: 손실 감내 문항(2번)의 답 1~4 → 선언한 감내 손실률(%).
#:
#: **프론트의 선택지 문구가 그대로 이 숫자다** — 5% 만 빠져도 정리한다 · 10% 까지는 견딘다 ·
#: 20% 까지는 각오한다 · 30% 이상도 감수한다
#: (`frontend/.../review/onboarding.tsx` 의 `QUESTIONS[1].choices`). 한쪽만 고치면 결과지가
#: 사용자가 고르지 않은 숫자를 인용한다.
LOSS_TOLERANCE_PCT: tuple[int, ...] = (-5, -10, -20, -30)


def to_loss_tolerance_pct(answers: str | None) -> int | None:
    """설문 답 원본 → 선언한 감내 손실률(%).

    **답에서 유도한다.** 백엔드에 `lossTolerancePct` 자리가 있지만 늘 비워 온다 — 채점을
    화면이 하므로 서버가 낼 값이 없어서다. 그러면 결과지의 '손실 감내' 줄이 아무에게도
    나가지 못한다. 같은 답에서 나오는 값이니 이쪽에서 낸다.

    형식이 어긋나면 `None` 이다 (`score_answers` 와 같은 규칙).
    """
    if answers is None or score_answers(answers) is None:
        return None
    return LOSS_TOLERANCE_PCT[int(answers[ITEMS.index("DRAWDOWN")]) - 1]


def compare(declared: int | None, observed: int | None) -> str | None:
    """선언 단계와 관찰 단계의 거리. `SAME` · `NEAR` · `FAR`.

    **한쪽이라도 없으면 `None`** 이고 결과지의 대조 칸이 통째로 빠진다. 없는 선언을 지어내
    "예상과 달랐습니다" 를 쓰면 그건 거짓말이다.
    """
    if declared is None or observed is None:
        return None
    gap = abs(declared - observed)
    return "SAME" if gap == 0 else "NEAR" if gap == 1 else "FAR"


def widest_gap(declared: str | None, items: tuple[ItemScore, ...]) -> tuple[str, int, int] | None:
    """선언과 관찰이 가장 크게 갈린 항목. `(항목, 선언 점수, 관찰 점수)`.

    종합 단계만 비교하면 "3단계라 하셨는데 4단계였습니다" 로 끝나 **왜 그런지에 대한 답이
    없다.** 항목별 점수를 양쪽 다 갖고 있으면 근거 문장을 뽑을 수 있다.

    흐린 항목은 후보에서 뺀다 — 근거가 얇은 자리를 "가장 크게 갈렸다" 고 짚으면 안 된다.
    동점이면 `ITEMS` 순서로 자른다. 순서를 고정하지 않으면 같은 입력에 다른 문장이 나온다.
    """
    if declared is None or len(declared) != ANSWER_LENGTH:
        return None
    best: tuple[str, int, int] | None = None
    widest = 0
    for index, item in enumerate(ITEMS):
        score = items[index]
        if score.provisional or score.rounded is None:
            continue
        gap = abs(int(declared[index]) - score.rounded)
        if gap > widest:
            best, widest = (item, int(declared[index]), score.rounded), gap
    return best


@dataclass(frozen=True)
class ProfileFeatures:
    """조립기가 받는 판정 결과 한 덩이. 선언 쪽과 관찰 쪽을 함께 담는다.

    **한쪽만 있어도 성립한다** — 선언이 없으면 관찰만, 거래가 없으면 선언만 나간다.
    둘 다 있을 때만 `match` 가 채워지고 결과지의 대조 칸이 나온다.
    """

    observed: Profile
    self_total: int | None
    self_level: int | None
    match: str | None
    gap: tuple[str, int, int] | None

    @property
    def self_persona(self) -> str:
        return "" if self.self_level is None else PERSONAS[self.self_level]

    @property
    def self_name(self) -> str:
        return "" if self.self_level is None else LEVEL_NAMES[self.self_level]

    def self_point(self, item: str) -> int | None:
        """그 항목에 해당하는 온보딩 문항의 답. 선언이 없으면 `None`."""
        if self._answers is None:
            return None
        return int(self._answers[ITEMS.index(item)])

    #: `self_point` 가 쓰는 원본. 데이터클래스 필드로 두면 응답에 새어 나가므로 숨긴다.
    _answers: str | None = None


def to_profile_features(raw: RawItems, answers: str | None) -> ProfileFeatures:
    """원지표와 온보딩 답안 → 조립기가 쓰는 판정 결과.

    답안 형식이 어긋나면 선언이 없는 것과 같게 다룬다 — 온보딩의 실패가 리포트를 막을
    이유가 아니다.
    """
    observed = to_profile(raw)
    total = score_answers(answers) if answers else None
    level = to_level(total)
    checked = answers if level is not None else None
    return ProfileFeatures(
        observed=observed,
        self_total=total,
        self_level=level,
        match=compare(level, observed.level),
        gap=widest_gap(checked, observed.items),
        _answers=checked,
    )


def profile_code(level: int | None) -> str:
    """응답에 싣는 유형 코드. `LEVEL_1`~`LEVEL_5` 또는 `UNDETERMINED`.

    **비어 있으면 안 된다.** 백엔드는 이 값이 빈 문구를 저장하지 않고 버린다
    (`AiSeasonReportService.usable`). 판정이 안 서도 `UNDETERMINED` 가 들어간다.
    """
    return UNDETERMINED if level is None else f"LEVEL_{level}"

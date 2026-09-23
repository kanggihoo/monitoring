"""복기 리포트 입출력 스키마.

**조건 벡터와 응답 모양이 여기 모인다.** 조립기는 이 스키마만 알고, 값을 누가 만들었는지는
알지 않는다 — 백엔드 payload 든 목업이든 같은 조건 벡터로 들어온다
(`docs/architecture/report-assembly.md` 3절).
"""

from __future__ import annotations

from decimal import Decimal
from typing import Literal

from pydantic import Field

from app.schemas.common import CamelModel

VsIndex = Literal["ABOVE", "SIMILAR", "BELOW"]
#: 그날을 한마디로 줄인 값. 앞의 둘은 백엔드 태그에서 오고, 뒤의 셋은 체결 건수로 갈린다.
#: `NORMAL` 은 **아쉬운 자리가 없었다**는 뜻이다 — 이 값이 없으면 `QUIET` 이 else 가지가 되어
#: 3~15건 거래한 사람이 "아무것도 하지 않았다" 는 문장을 받는다.
MainMistake = Literal["PEAK_BUY", "BOTTOM_SELL", "OVERTRADE", "NORMAL", "QUIET"]
#: 앞의 넷은 백엔드 태그(`pattern_tags.kind`)를 옮긴 것이고, `BEST_TRADE`·`WORST_TRADE` 는
#: 태그가 모자랄 때 채우는 그날의 최고·최악 매도다 — 손익 부호가 분명한 거래에만 붙인다.
TradeTag = Literal[
    "PEAK_BUY",
    "BOTTOM_SELL",
    "BOTTOM_BUY",
    "PEAK_SELL",
    "BEST_TRADE",
    "WORST_TRADE",
    "NEUTRAL",
]
#: 그 습관이 **이번 장에서** 통했는가. 성향 단계와 무관하다 — 단계는 카드가 말하고,
#: 이 값은 결과만 본다.
EffectAxis = Literal["WORKED", "BACKFIRED"]

#: `mainMistake` 판정 우선순위. 태그 건수가 같을 때 이 순서로 자른다.
#: 순서를 고정하지 않으면 같은 입력에 다른 리포트가 나온다.
#: 뒤의 셋은 태그에서 나오지 않아 동점 비교에 걸리지 않지만, 판정 순서를 그대로 적어 둔다.
MISTAKE_PRIORITY: tuple[MainMistake, ...] = (
    "PEAK_BUY",
    "BOTTOM_SELL",
    "OVERTRADE",
    "QUIET",
    "NORMAL",
)


class Scene(CamelModel):
    """'오늘의 세 장면' 한 줄. 종목명은 조립기가 만들지 않고 그대로 실어 보낸다."""

    game_month: str
    instrument_name: str
    side: Literal["BUY", "SELL"]
    tag: TradeTag


class DailyFeatures(CamelModel):
    """일일 리포트 조건 벡터. 임계값 판정이 끝난 뒤의 값만 담는다."""

    account_id: int
    season_day: int
    vs_index: VsIndex
    main_mistake: MainMistake
    execution_count: int = 0
    scenes: list[Scene] = Field(default_factory=list)


class SeasonFacts(CamelModel):
    """⑥ 특징 본문이 인용하는 수치. **전부 payload 값 그대로다** — 지어낸 숫자가 없어야 한다."""

    #: 그 구간의 최대 낙폭(%). 재지 못했으면 `None` 이다 (`inbound.InboundAggregate`).
    max_drawdown: Decimal | None = None
    cash_ratio: Decimal | None = None
    closed_count: int = 0
    hold_days: Decimal | None = None
    buy_count: int = 0
    fomo_buy_count: int = 0
    profit_sells: int = 0
    loss_sells: int = 0
    #: ④ 급락 대응이 인용한다 — 떨어진 자리에서 담은 건수와 정리한 건수.
    dip_buys: int = 0
    dip_sells: int = 0
    #: ⑤ 손실 정리가 인용한다 — 빨리 판 매도 중 손절과 익절.
    quick_stops: int = 0
    quick_profits: int = 0


class ProfileItem(CamelModel):
    """성향 판정 항목 하나. 결과지 막대 하나가 이 모양으로 그려진다."""

    item: str
    label: str
    #: 1~4. 표본 축소를 거친 값을 반올림한 것이다. 잴 수 없었으면 `None`.
    point: int | None = None
    #: 표본이 모자라 흐리게 그려야 하는 항목인가.
    provisional: bool = False
    #: 온보딩에서 그 항목에 해당하는 문항의 답(1~4). 선언이 없으면 `None`.
    self_point: int | None = None


class SeasonFeatures(CamelModel):
    """시즌 리포트 조건 벡터.

    치환값 둘(`winCount`·`rankRate`)은 백엔드가 보내는 값이 아니라 **AI 가 유도한 것이다.**
    예전 계약의 `patternCount`·`hitCount`·`gapRate` 는 순환이라(유형을 AI 가 정하는데 백엔드가
    유형별 건수를 미리 셀 수 없다) v5 에서 빠졌다
    (`docs/architecture/backend-data-contract.md`).
    """

    account_id: int
    season_id: str

    #: --- 성향 5단계 판정 (기준 문서 5·6절) ---

    #: 1~5. 판정이 안 서면 `None`.
    profile_level: int | None = None
    #: 5~20. 항목 평균에 5를 곱한 값이다.
    profile_total: int | None = None
    #: `안정형`~`공격투자형`. 판정이 안 서면 빈 문자열.
    profile_name: str = ""
    #: 화면에 나가는 캐릭터 이름.
    profile_persona: str = ""
    profile_items: list[ProfileItem] = Field(default_factory=list)

    #: --- 온보딩 선언과의 대조 ---

    #: 선언이 없으면 `None` 이고 결과지 ③⑦ 이 통째로 빠진다.
    self_level: int | None = None
    self_total: int | None = None
    self_persona: str = ""
    #: `SAME`·`NEAR`·`FAR`. 한쪽이라도 없으면 `None`.
    profile_match: str | None = None
    #: 선언과 관찰이 가장 크게 갈린 항목. ⑦ 근거 문장이 여기서 나온다.
    gap_item: str | None = None
    gap_item_label: str = ""
    gap_self_point: int | None = None
    gap_point: int | None = None

    #: 손실감내 선언(%)과 실제 최대 낙폭(%). **변환 없이 맞붙는 유일한 쌍이다.**
    loss_tolerance_pct: int | None = None
    #: 재지 못했으면 `None`. 그때는 맞붙일 상대가 없어 그 줄을 쓰지 않는다.
    max_drawdown: Decimal | None = None
    #: ⑥ 이 인용하는 수치.
    facts: SeasonFacts = Field(default_factory=SeasonFacts)

    #: 그 구간의 지수 방향 (`UP`·`FLAT`·`DOWN`). **모르면 빈 문자열이다.**
    #:
    #: 총평을 여는 국면 문장이 이 값으로 갈린다. 예전에는 시즌마다 운영자가 쓴 한 문장을
    #: 그대로 붙였는데, 리포트가 매일 나오면서 모두에게 같은 문장이 사흘 내내 나갔다.
    #: 일일 요청에는 그 일차 지수 수익률이 오므로 그것으로 고른다.
    market_move: str = ""

    axis: EffectAxis = "BACKFIRED"
    #: 실현 손익이 양수인 매도 건수. 문장의 "그중 몇 번" 자리다.
    win_count: int = 0
    #: 화면 문자열 그대로 (예: `+38.2%`).
    return_rate: str = ""
    #: 최종 순위의 백분위를 문장에 쓸 형태로 (예: `상위 12%`). 순위가 없으면 빈 문자열.
    rank_rate: str = ""


class DailyReportText(CamelModel):
    """일일 리포트의 문구 부분. 지표 칩은 백엔드 값이라 여기 담지 않는다."""

    headline: str
    scene_aliases: list[str]
    block_ids: list[str]


class TraitItem(CamelModel):
    """⑥ 투자 특징 하나. 제목은 블록, 본문은 템플릿이 만든다."""

    axis: str
    title: str
    #: **반드시 수치를 인용한다** — 축 점수만 보여주면 "왜 89점인가"에 답이 없다.
    body: str


class SeasonNarrative(CamelModel):
    """결과지 ②③④⑥⑦⑧. **백엔드는 이 칸을 `jsonb` 한 칼럼에 통째로 저장한다.**"""

    one_line: str = ""
    #: ③. 선언이 없으면 `None` 이고 그 칸 자체가 없다.
    self_view: str | None = None
    #: ④ LLM 자리. 실패하면 `actual_fallback` 블록.
    actual: str = ""
    traits: list[TraitItem] = Field(default_factory=list)
    #: ⑧ 앞으로의 제안 한 문장. 예전 이름은 `nextSeason` 이었는데, 리포트가 매일 나오면서
    #: '다음 시즌' 은 마지막 날에만 맞는 말이 됐다.
    next_step: str = ""
    #: ⑦ 선언과 관찰이 가장 크게 갈린 항목 한 문장. 선언이 없으면 `None` 이고 칸이 빠진다.
    #:
    #: **블록이 만든다.** 실호출에서 모델이 세 번 다 틀렸다 — 항목을 섞고, 틀을 섞고,
    #: 선언 쪽을 빠뜨렸다. 조합이 스무 가지뿐이라(항목 5 · 선언 방향 2 · 관찰 방향 2)
    #: 템플릿이 정확히 덮는다.
    profile_gap: str | None = None
    #: 손실감내를 선언했을 때만. 선언도 %, 실제도 % 라 변환 없이 맞붙는다.
    loss_tolerance: str | None = None


class SeasonReportText(CamelModel):
    """시즌 리포트의 문구 부분. 결과지 여덟 덩이가 여기 담긴다."""

    #: ① 유형 카드.
    type_emoji: str = ""
    type_name: str
    type_description: str
    narrative: SeasonNarrative = Field(default_factory=SeasonNarrative)
    #: 기존 시즌 총평. 이번 기능과 별개로 그대로 유지한다.
    verdict: str
    block_ids: list[str]


class ReviewReport(SeasonReportText):
    """복기 결과지 하나. **문구 여덟 덩이와 판정 값이 함께 있다.**

    일일 응답의 `review` 와 시즌 응답의 항목이 같은 모양을 쓴다 — 리포트가 매일 나오고
    형식이 하나라, 화면이 두 벌을 그릴 이유가 없다.

    **`investorType` 은 `LEVEL_1`~`LEVEL_5` 또는 `UNDETERMINED` 다.** 백엔드가
    `investor_type` 컬럼에 그대로 저장한다 — 컬럼이 30자라 여유가 있고, enum 이 없어 값이
    바뀌어도 저장 로직은 그대로다.
    """

    investor_type: str
    #: 관찰된 성향. 1 안정형 ~ 5 공격투자형.
    profile_level: int | None = None
    #: 5~20. 항목 다섯의 평균에 5를 곱한 값이다.
    profile_total: int | None = None
    profile_name: str = ""
    profile_persona: str = ""
    #: 막대 다섯. 선언 점수도 함께 들어 있어 화면이 나란히 그릴 수 있다.
    profile_items: list[ProfileItem] = Field(default_factory=list)
    #: 온보딩 선언 쪽. 선언이 없으면 전부 비어 있다.
    self_level: int | None = None
    self_total: int | None = None
    self_persona: str = ""
    #: `SAME`·`NEAR`·`FAR`. 한쪽이라도 없으면 `null`.
    profile_match: str | None = None


class SeasonReportItem(ReviewReport):
    """시즌 응답의 한 계좌. **결과지에 계좌 ID 만 얹은 것이다.**

    `POST /api/ai/reports/season` 은 통합 전의 경로라 그대로 둔다. 일일 응답의 `review` 와
    같은 값이 나온다 — 3일차의 누적이 곧 시즌 전체다.
    """

    account_id: int


class SeasonReportResponse(CamelModel):
    """`POST /api/ai/reports/season` 응답의 `data`."""

    reports: list[SeasonReportItem] = Field(default_factory=list)


class DailyReportItem(DailyReportText):
    """응답에 실리는 한 계좌의 일일 리포트.

    **헤드라인·장면 별칭은 그대로 두고 결과지를 얹는다.** 지금 배포돼 있는 화면이 앞의 둘을
    쓰고 있어서, 통합한다고 그 자리를 없애면 이미 나가 있는 리포트가 비어 버린다.
    """

    account_id: int
    #: 그 일차까지의 복기 결과지. **누적 집계(`cumulative`)가 와야 생긴다** — 오지 않으면
    #: `null` 이고 응답은 통합 전과 같은 모양이다.
    review: ReviewReport | None = None


class DailyReportResponse(CamelModel):
    """`POST /api/ai/reports/daily` 응답의 `data`.

    **요청한 계좌가 다 오지 않을 수 있다.** 한 계좌의 조립이 실패해도 나머지는 보낸다 —
    백엔드는 받은 것만 저장한다.
    """

    season_day: int
    reports: list[DailyReportItem] = Field(default_factory=list)

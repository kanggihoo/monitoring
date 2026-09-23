"""명세 12절 표에 있고 다른 파일이 덮지 않는 항목들.

한곳에 모아 두면 **명세 표와 줄 단위로 대조**할 수 있다 — 표에 줄이 늘면 여기가 빈다.
"""

from __future__ import annotations

import pytest

from app.schemas.inbound import SeasonReportRequest
from app.schemas.report import SeasonFeatures
from app.services.inbound import to_season_features
from app.services.report_assembly import assemble_season

TYPES = {"AMH", "AMI", "ALH", "ALI", "SMH", "SMI", "SLH", "SLI"}


def season(**fields) -> SeasonFeatures:
    request = SeasonReportRequest.model_validate(
        {"seasonId": 1, "accounts": [{"accountId": 4021, **fields}]}
    )
    return to_season_features(request, request.accounts[0])


TRADED = {
    "buyCount": 40,
    "sellCount": 30,
    "holdingDaysSum": 9000,
    "closedPositionCount": 30,
    "maxDrawdown": "-25",
    "turnover": "9",
    "peakSellWinCount": 10,
    "quickProfitCount": 5,
    "quickStopCount": 3,
    "panicBottomSellCount": 2,
}


# --- 온보딩 채점 · 8종 도달성 --------------------------------------------------


# --- 반반 vs 무응답 -----------------------------------------------------------


# --- 백분위 환산 · 표본 축소 ----------------------------------------------------


# --- riskLevel · provisional ---------------------------------------------------


# --- 특징 선택 ----------------------------------------------------------------


# --- 예외 · 계약 ---------------------------------------------------------------


# --- 결과지 여덟 덩이 전수 -------------------------------------------------------


DECLARATIONS = [
    None,
    {"risk": "Y", "time": "N", "entry": "Y", "exit": "N"},
    {"risk": "Y", "time": "M", "entry": "N", "exit": "-"},
    {"risk": "M", "time": "M", "entry": "M", "exit": "M"},
]


@pytest.mark.parametrize("answers", [None, "", "11111", "44444", "24312"])
@pytest.mark.parametrize("fomo", [2, 30])
@pytest.mark.parametrize("hold_sum", [1200, 27000])
def test_every_sheet_is_complete_or_null_by_rule(
    answers: str | None, fomo: int, hold_sum: int
) -> None:
    """선언 유무와 항목 방향의 조합에서 모든 자리가 규칙대로 채워지거나 빈다."""
    features = season(
        **{**TRADED, "holdingDaysSum": hold_sum},
        fomoBuyCount=fomo,
        selfAnswers=answers,
    )
    narrative = assemble_season(features).narrative
    assert narrative.one_line and narrative.actual and narrative.next_step
    assert narrative.traits
    for trait in narrative.traits:
        assert "{" not in trait.body
    if features.self_level is None:
        # 없는 선언을 지어내지 않는다 — ③⑦ 이 함께 빠진다.
        assert narrative.self_view is None
        assert narrative.profile_gap is None
        assert features.profile_match is None
    else:
        assert narrative.self_view is not None
        assert narrative.self_view.endswith("보셨습니다.")
        assert features.profile_match in {"SAME", "NEAR", "FAR"}

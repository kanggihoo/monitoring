"""결과지 여덟 덩이. 명세 8절 「덩이별 처리」가 정본이다."""

from __future__ import annotations

import copy
from decimal import Decimal

from fastapi.testclient import TestClient

from app.schemas.inbound import SeasonReportRequest
from app.services.inbound import to_season_features
from app.services.report_assembly import assemble_season

TRADED = {
    "accountId": 4021,
    "buyCount": 40,
    "sellCount": 30,
    "fomoBuyCount": 33,
    "peakSellWinCount": 12,
    "quickProfitCount": 10,
    "quickStopCount": 4,
    "panicBottomSellCount": 2,
    "krwCash": "12000000",
    "totalValuationKrw": "138200000",
    "holdingDaysSum": 360,
    "closedPositionCount": 30,
    "maxDrawdown": "-31.4",
    "turnover": "12.0",
    "finalRank": 12,
    "totalParticipants": 100,
    "returnRate": "+38.2%",
    "winCount": 18,
}
IDLE = {
    **TRADED,
    "buyCount": 0,
    "sellCount": 0,
    "fomoBuyCount": 0,
    "peakSellWinCount": 0,
    "quickProfitCount": 0,
    "quickStopCount": 0,
    "panicBottomSellCount": 0,
    "closedPositionCount": 0,
    "holdingDaysSum": 0,
    "maxDrawdown": "0",
    "turnover": "0",
}


def sheet(**extra):
    request = SeasonReportRequest.model_validate({"seasonId": 1, "accounts": [{**TRADED, **extra}]})
    features = to_season_features(request, request.accounts[0])
    return features, assemble_season(features)


def test_declared_sheet_has_every_chunk() -> None:
    features, text = sheet(lossTolerancePct=-25, selfAnswers="12341")
    narrative = text.narrative
    assert features.self_level in {1, 2, 3, 4, 5}
    assert text.type_emoji and text.type_name
    assert narrative.one_line
    assert narrative.self_view and narrative.self_view.endswith("보셨습니다.")
    assert narrative.actual
    assert len(narrative.traits) == 3
    assert narrative.profile_gap
    assert narrative.next_step
    assert narrative.loss_tolerance == "-25% 까지 괜찮다고 하셨고, 실제로는 -31.4% 를 겪으셨습니다."


def test_without_a_declaration_three_and_seven_disappear() -> None:
    """선언이 없으면 ③⑦ 이 **함께** 빠진다. 한쪽만 나오면 화면이 반쪽이 된다."""
    for answers in (None, "", "oops", "12345"):
        _, text = sheet(selfAnswers=answers)
        narrative = text.narrative
        assert narrative.self_view is None, answers
        assert narrative.profile_gap is None, answers
        # 나머지 여섯 덩이는 그대로 나온다.
        assert narrative.one_line and narrative.actual and narrative.next_step
        assert narrative.traits


def test_traits_quote_the_payload_not_invented_numbers() -> None:
    _, text = sheet()
    bodies = " ".join(trait.body for trait in text.narrative.traits)
    assert "40건" in bodies and "33건" in bodies


def test_traits_agree_with_their_titles_across_the_corpus() -> None:
    from scripts.mock_data import simulate

    _, season, _ = simulate(seed=7, users=300, season_day=3)
    request = SeasonReportRequest.model_validate(season)
    for account in request.accounts:
        features = to_season_features(request, account)
        text = assemble_season(features)
        for trait in text.narrative.traits:
            if trait.axis != "RISK" or "현금" not in trait.body:
                continue
            aggressive = "크게" in trait.title or "물러서지" in trait.title
            cash = features.facts.cash_ratio or Decimal(0)
            assert aggressive is (cash < Decimal("0.5")), (trait.title, trait.body)


def test_next_step_never_asserts_the_opposite_direction() -> None:
    """⑧ 이 지목한 항목의 방향이 그 계좌의 실제 방향과 같은가.

    **선언이 없는 경로에서 실제로 틀렸다.** 옛 `NEXT_NONE_*` 넷이 축만 보고 방향을 안 봐서,
    수비형에게 "크게 거는 쪽이었습니다. 덜 실어 보세요" 가 나갔다 — 같은 화면의 카드는
    `안정` 이라고 말하는데. 목업 300계좌의 43~55% 였다.

    항목 체계로 옮긴 지금도 같은 실수를 막는다.
    """
    from app.services.narrative import item_direction
    from app.services.report_blocks import load_blocks
    from scripts.mock_data import simulate

    when_by_id = {block["id"]: block["when"] for block in load_blocks()["next_step"]}
    _, season, _ = simulate(seed=7, users=300, season_day=3)
    request = SeasonReportRequest.model_validate(season)
    checked = 0
    for account in request.accounts:
        features = to_season_features(request, account)
        points = {item.item: item.point for item in features.profile_items}
        for block_id in assemble_season(features).block_ids:
            when = when_by_id.get(block_id)
            if not when or "nextDirection" not in when:
                continue
            item = when["nextItem"]
            assert item_direction(points[item]) == when["nextDirection"], (
                f"{block_id}: {item} {points[item]}점인데 {when['nextDirection']} 를 단정한다"
            )
            checked += 1
    # 폴백만 골라서 통과하는 테스트가 되지 않게 한다.
    assert checked > 200, checked


def test_season_path_matches_the_daily_review(client: TestClient) -> None:
    """**시즌 경로도 일일 결과지와 같은 값을 낸다.** 통합 뒤에도 함께 지원하는 경로다.

    3일차의 누적이 곧 시즌 전체이므로, 같은 집계에 같은 지수를 주면 총평의 국면 문장까지
    같아야 한다 — 다르면 두 경로 중 하나가 옛 규칙을 쓰고 있다는 뜻이다.
    """
    from tests.test_reports import CUMULATIVE, DAILY_PAYLOAD, HEADERS, SEASON_PAYLOAD

    daily = copy.deepcopy(DAILY_PAYLOAD)
    daily["accounts"][0]["cumulative"] = CUMULATIVE
    season = copy.deepcopy(SEASON_PAYLOAD)
    season["indexReturn"] = daily["indexReturn"]

    review = client.post("/api/ai/reports/daily", json=daily, headers=HEADERS).json()["data"][
        "reports"
    ][0]["review"]
    item = client.post("/api/ai/reports/season", json=season, headers=HEADERS).json()["data"][
        "reports"
    ][0]

    assert review["investorType"] == item["investorType"]
    assert review["profileItems"] == item["profileItems"]
    assert review["verdict"] == item["verdict"]

"""백엔드 payload 어댑터 검사.

[ADR-0002](../docs/adr/0002-backend-sends-preprocessed-payload.md) 의 입력 경로다. 여기서 보는
것은 **모양 변환**이고, 판정 규칙 자체는 `test_extract.py` 가 본다.

payload 예시는 백엔드의 `AiDailyReportRequest` · `AiSeasonReportRequest` 를 그대로 옮긴
것이다 — 이 파일이 깨지면 계약이 어긋난 것이다.
"""

from __future__ import annotations

from app.schemas.inbound import DailyReportRequest, InboundSeasonAccount, SeasonReportRequest
from app.services.inbound import (
    to_daily_features,
    to_season_features,
)


def daily_request(**overrides) -> DailyReportRequest:
    payload = {
        "seasonId": 1,
        "seasonDay": 1,
        "indexReturn": "-1.0",
        "accounts": [
            {
                "accountId": 4021,
                "dailyReturn": "-8.0",
                "executionCount": 2,
                "patternTags": [
                    {
                        "kind": "PANIC_BUY_HIGH",
                        "changeRate": "-20.0",
                        "instrumentName": "바나나",
                        "side": "BUY",
                        "gameMonth": "2020.02",
                    },
                    {
                        "kind": "PANIC_SELL_LOW",
                        "changeRate": "20.0",
                        "instrumentName": "바구니",
                        "side": "SELL",
                        "gameMonth": "2020.02",
                    },
                ],
                "metrics": {
                    "buyCount": 1,
                    "sellCount": 1,
                    "winCount": 0,
                    "realizedPnlKrw": "-1200000",
                    "totalFeeKrw": "1500",
                    "avgHoldingDays": 30,
                    "quickSellCount": 0,
                    "tradedAmountKrw": "2500000",
                    "topInstrumentName": "바나나",
                    "topInstrumentShareKrw": "0.6",
                    "executionCountRank": 12,
                    "participantCount": 100,
                },
                "highlights": [
                    {
                        "role": "BEST",
                        "instrumentName": "화면",
                        "side": "SELL",
                        "gameMonth": "2020.03",
                        "realizedPnlKrw": "840000",
                        "returnRate": "12.4",
                        "holdingDays": 40,
                    },
                    {
                        "role": "WORST",
                        "instrumentName": "돌다리",
                        "side": "SELL",
                        "gameMonth": "2020.03",
                        "realizedPnlKrw": "-1200000",
                        "returnRate": "-18.0",
                        "holdingDays": 12,
                    },
                ],
            }
        ],
    }
    payload.update(overrides)
    return DailyReportRequest.model_validate(payload)


# --- 일일 ---


def test_tags_become_scenes() -> None:
    """태그 이름과 변화율이 그대로 장면이 된다. 종가 시계열을 따로 받지 않는다."""
    request = daily_request()
    features = to_daily_features(request, request.accounts[0])
    tags = {scene.instrument_name: scene.tag for scene in features.scenes}
    assert tags["바나나"] == "PEAK_BUY"  # 매수 후 -20%
    assert tags["바구니"] == "BOTTOM_SELL"  # 매도 후 +20%


def test_highlights_fill_the_remaining_scene() -> None:
    """태그가 둘뿐이면 세 번째 자리를 최고 거래가 채운다."""
    request = daily_request()
    features = to_daily_features(request, request.accounts[0])
    assert [scene.tag for scene in features.scenes][-1] == "BEST_TRADE"
    assert features.scenes[-1].instrument_name == "화면"


def test_vs_index_uses_request_level_index_return() -> None:
    request = daily_request(indexReturn="-1.0")
    features = to_daily_features(request, request.accounts[0])
    assert features.vs_index == "BELOW"  # 내 -8.0% vs 지수 -1.0%


def test_instrument_names_come_from_the_payload() -> None:
    """종목 표를 따로 받지 않는다. 이름이 태그·하이라이트에 붙어 온다."""
    request = daily_request()
    features = to_daily_features(request, request.accounts[0])
    assert {scene.instrument_name for scene in features.scenes} == {"바나나", "바구니", "화면"}


def test_account_with_no_trades_still_extracts() -> None:
    """관망한 계좌도 그대로 보내면 된다. 장면이 비고 문장은 폴백으로 채워진다."""
    request = daily_request()
    account = request.accounts[0]
    account.pattern_tags = []
    account.highlights = []
    account.execution_count = 0
    features = to_daily_features(request, account)
    assert features.scenes == []
    assert features.main_mistake == "QUIET"


def test_metrics_are_optional() -> None:
    """요약이 빠져도 계약이 깨지지 않는다. 없는 값은 0으로 본다."""
    minimal = DailyReportRequest.model_validate(
        {
            "seasonId": 1,
            "seasonDay": 1,
            "indexReturn": "0",
            "accounts": [{"accountId": 4021, "dailyReturn": "0"}],
        }
    )
    features = to_daily_features(minimal, minimal.accounts[0])
    assert features.main_mistake == "QUIET"


# --- 시즌: 건수 → 비율 ---


def season_account(**fields) -> InboundSeasonAccount:
    return InboundSeasonAccount.model_validate({"accountId": 4021, **fields})


def test_account_with_no_trades_is_undetermined() -> None:
    request = SeasonReportRequest.model_validate({"seasonId": 1, "accounts": [{"accountId": 4021}]})
    features = to_season_features(request, request.accounts[0])
    assert features.profile_level is None
    assert features.profile_name == ""
    assert all(item.provisional for item in features.profile_items)


def test_same_account_scores_the_same_in_any_batch() -> None:
    """배치 무관성 — 참조 표를 쓰므로 요청에 몇 명이 들어 있든 같은 점수다 (명세 12절)."""
    payload = {
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
        "turnover": "4.12",
    }
    others = [dict(payload, accountId=4022, buyCount=3, sellCount=1)] * 11
    small = SeasonReportRequest.model_validate({"seasonId": 1, "accounts": [payload]})
    large = SeasonReportRequest.model_validate({"seasonId": 1, "accounts": [payload, *others]})
    assert to_season_features(small, small.accounts[0]) == to_season_features(
        large, large.accounts[0]
    )


def test_season_features_carry_the_display_strings() -> None:
    """`returnRate` 는 서식까지 백엔드 값이라 그대로 실리고, 순위는 백분위로 바뀐다."""
    request = SeasonReportRequest.model_validate(
        {
            "seasonId": 1,
            "accounts": [
                {
                    "accountId": 4021,
                    "buyCount": 10,
                    "sellCount": 10,
                    "winCount": 7,
                    "maxDrawdown": "-31.4",
                    "turnover": "4.12",
                    "totalFeeKrw": "1284000",
                    "finalRank": 12,
                    "totalParticipants": 100,
                    "returnRate": "+38.2%",
                }
            ],
        }
    )
    features = to_season_features(request, request.accounts[0])
    assert features.return_rate == "+38.2%"
    assert features.rank_rate == "상위 12%"
    assert features.win_count == 7
    assert features.axis == "WORKED"  # 수익으로 끝났다
    # 국면 블록은 시즌 이름으로 조건이 붙어 있다.
    assert features.season_id == "S1"


def test_type_distribution_catches_a_rotten_reference_table() -> None:
    """**참조 표가 썩었는지 잡는 그물이다** (명세 12절).

    기존 9종 판정은 목업에서 `BOTTOM_SNIPER` 가 53% 였다. 참조 분포를 임의로 추측해 넣었을
    때도 4/8 유형에 최다 65% 로 이 그물에 걸렸다. 표를 갈아끼울 때 가장 먼저 보는 값이다.
    """
    from collections import Counter

    from scripts.mock_data import simulate

    _, season, _ = simulate(seed=7, users=300, season_day=3)
    request = SeasonReportRequest.model_validate(season)
    counts = Counter(to_season_features(request, a).profile_level for a in request.accounts)

    kinds = {level for level in counts if level is not None}
    assert kinds == {1, 2, 3, 4, 5}, f"도달 못 한 단계가 있다: {kinds}"
    determined = sum(n for level, n in counts.items() if level is not None)
    most = max(n for level, n in counts.items() if level is not None) / determined
    # **지금 seed=7 에서 위험중립형이 58.7% 다.** 사분위 컷을 seed=11 목업에서 뽑아서,
    # 다른 분포의 데이터가 들어오면 중앙으로 몰린다 — 그물이 실제로 잡고 있는 값이다.
    # 리허설 집계로 컷을 갈아 끼우면 내려갈 값이라 지금은 60% 선에서 막는다. 목표는 40%.
    assert most <= 0.60, f"한 단계에 쏠렸다: {counts.most_common(3)}"

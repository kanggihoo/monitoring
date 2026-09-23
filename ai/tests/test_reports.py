"""리포트 발급 엔드포인트 검사 — 백엔드가 부르는 두 경로.

payload 는 백엔드 DTO(`AiDailyReportRequest`·`AiSeasonReportRequest`)와 같은 모양이다.
여기서 보는 것은 **경계**다 — 인증, 계약 수용, 응답 봉투, 한 계좌가 빠져도 나머지가 오는가.
문장 자체는 `test_report_assembly.py`, 판정은 `test_extract.py` 가 본다.
"""

from __future__ import annotations

import copy
import re
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from app.core.config import Settings, get_settings
from app.main import create_app
from app.services import headline
from app.services.report_blocks import load_blocks
from tests.conftest import TEST_INTERNAL_KEY

HEADERS = {"X-Internal-Api-Key": TEST_INTERNAL_KEY}

DAILY_PAYLOAD = {
    "seasonId": 1,
    "seasonDay": 1,
    "indexReturn": "-1.0",
    "accounts": [
        {
            "accountId": 4021,
            "dailyReturn": "-8.0",
            "executionCount": 7,
            "patternTags": [
                {
                    "kind": "PANIC_BUY_HIGH",
                    "changeRate": "-13.2",
                    "instrumentName": "바나나",
                    "side": "BUY",
                    "gameMonth": "2020.02",
                }
            ],
            "metrics": {
                "buyCount": 4,
                "sellCount": 3,
                "winCount": 1,
                "realizedPnlKrw": "-1200000",
                "totalFeeKrw": "18000",
                "avgHoldingDays": 41,
                "quickSellCount": 1,
                "tradedAmountKrw": "24000000",
                "topInstrumentName": "바나나",
                "topInstrumentShareKrw": "0.43",
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
                }
            ],
        },
        # 거래가 한 건도 없는 계좌. 백엔드가 걸러내지 않고 그대로 보낸다.
        {"accountId": 4022, "dailyReturn": "0.0", "executionCount": 0},
    ],
}

SEASON_PAYLOAD = {
    "seasonId": 1,
    "accounts": [
        {
            "accountId": 4021,
            "buyCount": 40,
            "sellCount": 30,
            "fomoBuyCount": 22,
            "bottomSnipeBuyCount": 3,
            "peakSellWinCount": 4,
            "panicBottomSellCount": 2,
            "quickProfitCount": 5,
            "quickStopCount": 1,
            "krwCash": "12000000",
            "totalValuationKrw": "138200000",
            "holdingDaysSum": 900,
            "closedPositionCount": 30,
            "winCount": 18,
            "maxDrawdown": "-31.40",
            "turnover": "4.12",
            "totalFeeKrw": "1284000",
            "finalRank": 12,
            "totalParticipants": 100,
            "returnRate": "+38.2%",
        },
        {"accountId": 4022, "finalRank": 99, "totalParticipants": 100, "returnRate": "-2.0%"},
    ],
}


def test_daily_requires_the_internal_key(client: TestClient) -> None:
    response = client.post("/api/ai/reports/daily", json=DAILY_PAYLOAD)
    assert response.status_code == 401
    assert response.json()["success"] is False


def test_season_requires_the_internal_key(client: TestClient) -> None:
    assert client.post("/api/ai/reports/season", json=SEASON_PAYLOAD).status_code == 401


def test_daily_returns_a_text_per_traded_account(client: TestClient) -> None:
    response = client.post("/api/ai/reports/daily", json=DAILY_PAYLOAD, headers=HEADERS)
    assert response.status_code == 200
    data = response.json()["data"]
    assert data["seasonDay"] == 1
    # 4022 는 체결이 0건이라 빠진다 (아래 테스트가 정본이다).
    assert [report["accountId"] for report in data["reports"]] == [4021]

    (first,) = data["reports"]
    # 태그 하나 + 최고 거래 하나 → 장면 둘.
    assert len(first["sceneAliases"]) == 2
    assert first["headline"] and "{" not in first["headline"]
    assert first["blockIds"]


def test_accounts_without_trades_get_no_text(client: TestClient) -> None:
    """체결이 없는 계좌는 문구를 만들지 않는다.

    **응답에서 빠지는 것이 곧 실패는 아니다.** 백엔드는 응답에 없는 계좌의 문구 자리를
    비운 채 리포트를 연다 — 지표와 순위는 그대로 나온다.

    `executionCount` 하나만 보지 않는다. 그 값은 정산 행에서 오고 건수는 집계에서 오므로,
    한쪽만 채워 보내는 배포에서 조용히 갈리면 안 된다.
    """
    for payload, path in (
        (DAILY_PAYLOAD, "/api/ai/reports/daily"),
        (SEASON_PAYLOAD, "/api/ai/reports/season"),
    ):
        response = client.post(path, json=payload, headers=HEADERS)
        assert [r["accountId"] for r in response.json()["data"]["reports"]] == [4021], path

    # 건수만 채워 온 일일 계좌는 걸러지지 않는다.
    payload = copy.deepcopy(DAILY_PAYLOAD)
    payload["accounts"][1]["metrics"] = {"buyCount": 1}
    reports = client.post("/api/ai/reports/daily", json=payload, headers=HEADERS).json()["data"]
    assert [r["accountId"] for r in reports["reports"]] == [4021, 4022]


def test_quiet_day_keeps_the_cumulative_review(client: TestClient) -> None:
    """**그날 쉬었다고 결과지를 빼지 않는다.**

    결과지는 그날이 아니라 그때까지의 누적을 보는 카드다. 관망한 일차에 계좌를 빼면 어제
    있던 성향 카드가 오늘 사라졌다가 내일 돌아온다 — 사용자에게는 그것이 버그로 보인다.

    헤드라인도 함께 나간다. `daily_headline` 에 `HEAD_*_QUIET_*` 아홉이 시장 대비 방향까지
    말하도록 쓰여 있고, 거르는 동안에는 그 아홉이 한 번도 나가지 못했다.
    """
    payload = copy.deepcopy(DAILY_PAYLOAD)
    # 4022 는 그날 체결이 0건이다. 거기에 1일차까지의 누적을 얹는다.
    payload["accounts"][1]["cumulative"] = dict(CUMULATIVE)

    reports = client.post("/api/ai/reports/daily", json=payload, headers=HEADERS).json()["data"]
    quiet = next(r for r in reports["reports"] if r["accountId"] == 4022)
    assert quiet["review"] is not None
    assert quiet["headline"]
    assert any(block.startswith("HEAD_") and "QUIET" in block for block in quiet["blockIds"])


def test_accounts_with_neither_trades_nor_cumulative_are_dropped(client: TestClient) -> None:
    """체결도 누적도 없으면 그 사람에 대해 할 말이 없다. 그때만 뺀다."""
    payload = copy.deepcopy(DAILY_PAYLOAD)
    assert "cumulative" not in payload["accounts"][1]

    reports = client.post("/api/ai/reports/daily", json=payload, headers=HEADERS).json()["data"]
    assert [r["accountId"] for r in reports["reports"]] == [4021]


def daily_headline(client: TestClient, previous: list[str]) -> tuple[str, list[str]]:
    """4021 계좌의 헤드라인과 블록 목록. 전날 블록만 바꿔 가며 부른다."""
    payload = copy.deepcopy(DAILY_PAYLOAD)
    payload["accounts"][0]["previousBlockIds"] = previous
    report = client.post("/api/ai/reports/daily", json=payload, headers=HEADERS).json()
    first = report["data"]["reports"][0]
    return first["headline"], first["blockIds"]


def test_daily_excludes_previous_block_ids(client: TestClient) -> None:
    """전날 쓴 블록은 후보에서 뺀다.

    조건 칸마다 후보가 3개인데 시즌이 3일이라, 이것이 없으면 3개에서 3번 뽑는 꼴이 된다 —
    조건이 3일 내내 같은 사람은 71%가 같은 문장을 두 번 이상 봤다.
    """
    first_headline, first_blocks = daily_headline(client, [])
    second_headline, second_blocks = daily_headline(client, first_blocks)

    assert second_headline != first_headline
    assert not set(second_blocks) & set(first_blocks)


def test_daily_keeps_a_sentence_when_everything_is_excluded(client: TestClient) -> None:
    """후보를 전부 빼도 문장은 나온다. 중복이 빈 자리보다 낫다 (`report_blocks.choose`)."""
    every_block = [block["id"] for blocks in load_blocks().values() for block in blocks]
    headline, blocks = daily_headline(client, every_block)
    assert headline and "{" not in headline
    assert blocks


def test_daily_without_previous_blocks_is_unchanged(client: TestClient) -> None:
    """1일차이거나 전날 문구가 없으면 빈 배열이 온다. 그때는 빼지 않고 그냥 고른다."""
    assert daily_headline(client, []) == daily_headline(client, [])


def test_daily_falls_back_to_block_headlines_without_a_key(client: TestClient) -> None:
    """`AI_LLM_API_KEY` 가 없으면 생성 없이 블록 문장으로 간다 — 발급이 막히지 않는다."""
    response = client.post("/api/ai/reports/daily", json=DAILY_PAYLOAD, headers=HEADERS)
    headline = response.json()["data"]["reports"][0]["headline"]
    assert headline.endswith("니다.")


def test_daily_survives_an_unreadable_prompt(monkeypatch: pytest.MonkeyPatch) -> None:
    """프롬프트를 못 읽어도 그 일차 전원이 문구를 받는다.

    컨테이너에 `prompts/` 가 없던 때의 재현이다 (S15P21A605-200). 예전에는 `PromptError` 가
    `generate()` 의 `try` 밖에서 올라가 라우터를 뚫고 **500** 이 됐고, 그러면 한 계좌가 아니라
    그 일차 100명 전부의 문구가 빈 채로 리포트가 열렸다.
    """
    monkeypatch.setattr(headline, "PROMPT_PATH", Path("/nonexistent/daily-headline.md"))
    headline.load_prompt.cache_clear()

    settings = Settings(
        _env_file=None,
        env="local",
        internal_api_key=TEST_INTERNAL_KEY,
        llm_api_key="dummy-key-so-generation-is-attempted",
    )
    app = create_app(settings)
    app.dependency_overrides[get_settings] = lambda: settings
    try:
        with TestClient(app, raise_server_exceptions=False) as client:
            response = client.post("/api/ai/reports/daily", json=DAILY_PAYLOAD, headers=HEADERS)
    finally:
        headline.load_prompt.cache_clear()

    assert response.status_code == 200
    reports = response.json()["data"]["reports"]
    # 거래한 계좌는 다 와야 한다. 한 명의 실패로 100명을 비우지 않는다.
    assert [report["accountId"] for report in reports] == [4021]
    assert all(report["headline"].endswith("니다.") for report in reports)


def test_season_returns_type_and_verdict(client: TestClient) -> None:
    response = client.post("/api/ai/reports/season", json=SEASON_PAYLOAD, headers=HEADERS)
    assert response.status_code == 200
    reports = response.json()["data"]["reports"]
    # 4022 는 시즌 내내 체결이 0건이라 빠진다.
    assert [report["accountId"] for report in reports] == [4021]

    first = reports[0]
    # 판정은 고정 참조 표에 대고 하므로 요청 인원과 무관하다. 그래도 어느 유형이 나오는지를
    # 못 박지 않는다 — 참조 표를 실데이터로 교체하면 바뀔 값이다.
    # LEVEL_1~LEVEL_5 또는 UNDETERMINED. 백엔드가 그대로 저장한다.
    assert re.fullmatch(r"LEVEL_[1-5]|UNDETERMINED", first["investorType"])
    assert first["typeName"] and first["typeDescription"]
    assert "{" not in first["verdict"]
    # 어느 변주가 뽑히든 총평에는 그 사람의 숫자가 들어간다 (건수·승률·수익률·백분위).
    assert any(char.isdigit() for char in first["verdict"])
    assert first["blockIds"]


def test_invalid_payload_is_a_400(client: TestClient) -> None:
    """계약과 어긋난 요청은 전역 핸들러가 필드까지 알려준다."""
    response = client.post(
        "/api/ai/reports/daily",
        json={"seasonId": 1, "seasonDay": 1, "accounts": []},
        headers=HEADERS,
    )
    assert response.status_code == 400
    assert response.json()["errors"][0]["field"] == "indexReturn"


def test_unknown_fields_are_ignored(client: TestClient) -> None:
    """백엔드가 필드를 더 실어 보내도 발급이 막히지 않는다."""
    payload = {**DAILY_PAYLOAD, "generatedBy": "backend", "accounts": DAILY_PAYLOAD["accounts"]}
    assert client.post("/api/ai/reports/daily", json=payload, headers=HEADERS).status_code == 200


# --- 통합 리포트 — 일일 응답 하나가 결과지까지 낸다 -------------------------------

#: 그 일차까지의 누적 집계. 시즌 payload 의 계좌에서 `accountId` 만 뺀 모양이다.
CUMULATIVE = {
    key: value for key, value in SEASON_PAYLOAD["accounts"][0].items() if key != "accountId"
}


def daily_with_cumulative(**extra: object) -> dict:
    payload = copy.deepcopy(DAILY_PAYLOAD)
    payload["accounts"][0]["cumulative"] = {**CUMULATIVE, **extra}
    return payload


def test_daily_carries_the_review_when_cumulative_arrives(client: TestClient) -> None:
    """일일 응답 하나가 헤드라인과 결과지를 함께 낸다.

    통합의 핵심이다 — 시즌 엔드포인트를 폐기해도 같은 내용이 매일 나간다.
    """
    response = client.post("/api/ai/reports/daily", json=daily_with_cumulative(), headers=HEADERS)
    assert response.status_code == 200

    report = response.json()["data"]["reports"][0]
    # 지금 배포된 화면이 쓰는 두 자리가 그대로 있다.
    assert report["headline"].endswith("니다.")
    assert isinstance(report["sceneAliases"], list)

    review = report["review"]
    assert re.fullmatch(r"LEVEL_[1-5]|UNDETERMINED", review["investorType"])
    assert review["typeName"] and review["verdict"]
    assert len(review["profileItems"]) == 5
    narrative = review["narrative"]
    assert narrative["oneLine"] and narrative["actual"] and narrative["nextStep"]
    assert len(narrative["traits"]) >= 1


def test_daily_without_cumulative_keeps_the_deployed_shape(client: TestClient) -> None:
    """누적 집계가 안 와도 헤드라인과 장면 별칭은 그대로 나간다.

    **베타로 이미 나가 있는 화면을 지키는 회귀다.** 백엔드가 `cumulative` 를 보내기 전까지
    통합 전과 같은 응답이어야 한다 — 결과지 자리만 비고 리포트가 사라지지 않는다.
    """
    response = client.post("/api/ai/reports/daily", json=DAILY_PAYLOAD, headers=HEADERS)
    assert response.status_code == 200

    report = response.json()["data"]["reports"][0]
    assert report["headline"].endswith("니다.")
    assert report["blockIds"]
    assert report["review"] is None


def test_daily_review_does_not_repeat_yesterdays_blocks(client: TestClient) -> None:
    """어제 쓴 블록은 결과지에서도 빠진다. 매일 나오므로 사흘 연속 같은 문장이 위험하다."""
    first = client.post("/api/ai/reports/daily", json=daily_with_cumulative(), headers=HEADERS)
    used = first.json()["data"]["reports"][0]["review"]["blockIds"]

    payload = daily_with_cumulative()
    payload["accounts"][0]["previousBlockIds"] = used
    again = client.post("/api/ai/reports/daily", json=payload, headers=HEADERS)
    second = again.json()["data"]["reports"][0]["review"]["blockIds"]

    # 조건 칸에 후보가 하나뿐인 슬롯(⑧)은 그대로 남는다 — 중복이 빈 자리보다 낫다.
    assert set(second) != set(used)


def test_daily_review_is_absent_for_an_account_without_trades(client: TestClient) -> None:
    """누적 체결이 0건이면 결과지를 만들지 않는다. 그 안에 그 사람에 대한 내용이 없다."""
    payload = daily_with_cumulative(buyCount=0, sellCount=0)
    response = client.post("/api/ai/reports/daily", json=payload, headers=HEADERS)

    assert response.json()["data"]["reports"][0]["review"] is None


def test_season_and_daily_agree_on_the_same_aggregate(client: TestClient) -> None:
    """같은 집계면 시즌 응답과 일일 결과지가 같은 판정을 낸다.

    3일차의 누적이 곧 시즌 전체다. 둘이 갈리면 폐기할 수 없다.
    """
    daily = client.post("/api/ai/reports/daily", json=daily_with_cumulative(), headers=HEADERS)
    season = client.post("/api/ai/reports/season", json=SEASON_PAYLOAD, headers=HEADERS)

    review = daily.json()["data"]["reports"][0]["review"]
    item = season.json()["data"]["reports"][0]
    assert review["investorType"] == item["investorType"]
    assert review["profileLevel"] == item["profileLevel"]
    assert review["profileItems"] == item["profileItems"]

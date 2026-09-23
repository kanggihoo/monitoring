"""성향 5단계 판정. 기준 문서 `투자자 유형 분류 개발 기준 문서 2-1` 5·6절이 정본이다."""

from __future__ import annotations

import collections
import itertools
from decimal import Decimal

import pytest

from app.schemas.inbound import SeasonReportRequest
from app.services.inbound import to_raw_items
from app.services.profile import (
    ITEMS,
    LEVEL_BANDS,
    LEVEL_NAMES,
    MIN_ITEMS,
    PERSONAS,
    QUARTILES,
    RawItems,
    compare,
    score_answers,
    score_items,
    shrink,
    to_level,
    to_point,
    to_profile,
    to_total,
    widest_gap,
)

#: 다섯 항목이 모두 잡히는 계좌. 숫자는 사분위 컷과 무관하게 넉넉히 둔다.
TRADED = {
    "accountId": 4021,
    "buyCount": 40,
    "sellCount": 30,
    "fomoBuyCount": 12,
    "bottomSnipeBuyCount": 6,
    "peakSellWinCount": 4,
    "panicBottomSellCount": 4,
    "quickProfitCount": 6,
    "quickStopCount": 4,
    "krwCash": "12000000",
    "totalValuationKrw": "138200000",
    "holdingDaysSum": 900,
    "closedPositionCount": 30,
    "winCount": 18,
    "maxDrawdown": "-31.40",
    "turnover": "4.12",
    "finalRank": 12,
    "totalParticipants": 100,
    "returnRate": "+38.2%",
}


def account(**overrides: object) -> RawItems:
    payload = {"seasonId": 1, "accounts": [TRADED | overrides]}
    request = SeasonReportRequest.model_validate(payload)
    return to_raw_items(request.accounts[0])


# --- ① 원지표 → 1~4점 -------------------------------------------------------------


def test_quartile_cuts_land_on_the_expected_point() -> None:
    """컷 셋이 네 칸의 경계다. **경계값은 위쪽 칸에 들어간다** (`<` 비교)."""
    cuts = QUARTILES["ENTRY"]
    below, first, second, third = Decimal("0.01"), cuts[0], cuts[1], cuts[2]
    assert to_point(below, cuts, reverse=False) == 1
    assert to_point(first, cuts, reverse=False) == 2
    assert to_point(second, cuts, reverse=False) == 3
    assert to_point(third, cuts, reverse=False) == 4
    assert to_point(Decimal("9.99"), cuts, reverse=False) == 4


def test_reversed_item_flips_the_point() -> None:
    """`STOP` 은 값이 클수록 손실을 빨리 끊는 것이고, 그건 보수(1점)다."""
    cuts = QUARTILES["STOP"]
    assert to_point(Decimal("0.99"), cuts, reverse=True) == 1
    assert to_point(Decimal("0"), cuts, reverse=True) == 4


def test_missing_value_is_none_not_a_point() -> None:
    """잴 수 없었던 것과 바닥인 것은 다르다."""
    assert to_point(None, QUARTILES["STOP"], reverse=True) is None


# --- ② 표본 축소 -------------------------------------------------------------------


@pytest.mark.parametrize(
    ("sample", "expected"),
    [(0, "2.5"), (2, "2.8"), (8, "3.25"), (100, "3.8889")],
)
def test_shrink_pulls_thin_evidence_toward_the_middle(sample: int, expected: str) -> None:
    got = shrink(Decimal(4), sample)
    assert abs(got - Decimal(expected)) < Decimal("0.01")


def test_shrink_never_crosses_the_middle() -> None:
    """4점이 2.5 아래로, 1점이 2.5 위로 가면 방향이 뒤집힌다."""
    for sample in range(0, 200):
        assert shrink(Decimal(4), sample) >= Decimal("2.5")
        assert shrink(Decimal(1), sample) <= Decimal("2.5")


# --- ③ 합계와 단계 -----------------------------------------------------------------


def test_total_uses_the_mean_so_missing_items_do_not_drag_it_down() -> None:
    """단순 합이면 네 항목만 있는 사람이 전원 안정형이 된다."""
    full = score_items(
        RawItems(
            invested=Decimal("0.9"),
            drawdown=Decimal("99"),
            entry=Decimal("0.9"),
            dip=Decimal("0.9"),
            stop=Decimal("0"),
            n_invested=100,
            n_drawdown=100,
            n_entry=100,
            n_dip=100,
            n_stop=100,
        )
    )
    partial = score_items(
        RawItems(
            invested=Decimal("0.9"),
            drawdown=Decimal("99"),
            entry=Decimal("0.9"),
            n_invested=100,
            n_drawdown=100,
            n_entry=100,
        )
    )
    # 20 이 아니라 19 다. **표본 축소 때문에 만점이 안 나온다** — n=100 이면 4점이 3.89 로
    # 눌린다. 만점은 표본이 수백 건일 때만 닿고, 그게 의도다.
    assert to_total(full) == 19
    # 세 항목만 있어도 같은 성향이면 같은 단계가 나와야 한다.
    assert to_level(to_total(partial)) == to_level(to_total(full)) == 5


def test_too_few_items_is_undetermined() -> None:
    """다섯 중 셋은 있어야 평균이 그 사람을 대표한다."""
    two = score_items(
        RawItems(invested=Decimal("0.5"), drawdown=Decimal("10"), n_invested=50, n_drawdown=50)
    )
    assert sum(1 for item in two if item.score is not None) == MIN_ITEMS - 1
    assert to_total(two) is None
    assert to_level(None) is None


def test_every_band_is_reachable_and_they_do_not_overlap() -> None:
    seen = {to_level(total) for total in range(5, 21)}
    assert seen == {1, 2, 3, 4, 5}
    assert [low for low, _, _ in LEVEL_BANDS] == sorted(low for low, _, _ in LEVEL_BANDS)
    for (_, high, _), (low, _, _) in itertools.pairwise(LEVEL_BANDS):
        assert low == high + 1
    assert set(LEVEL_NAMES) == set(PERSONAS) == {1, 2, 3, 4, 5}


# --- ④ 온보딩 채점 -----------------------------------------------------------------


def test_onboarding_scoring_matches_the_band_table() -> None:
    assert score_answers("11111") == 5
    assert score_answers("44444") == 20
    assert score_answers("24312") == 12
    assert to_level(score_answers("11111")) == 1
    assert to_level(score_answers("44444")) == 5


@pytest.mark.parametrize("answers", ["", "1234", "123456", "12305", "abcde", "1 234"])
def test_malformed_answers_are_treated_as_no_declaration(answers: str) -> None:
    """온보딩 실패가 리포트를 막을 이유가 아니다. 선언이 없는 것과 같게 다룬다."""
    assert score_answers(answers) is None


def test_all_answer_combinations_land_in_a_band() -> None:
    """4^5 = 1,024 조합 전부가 5~20 안이고 단계가 선다."""
    levels = collections.Counter()
    for combo in itertools.product("1234", repeat=5):
        total = score_answers("".join(combo))
        assert total is not None and 5 <= total <= 20
        levels[to_level(total)] += 1
    assert set(levels) == {1, 2, 3, 4, 5}
    # 양 끝이 얇고 **좌우가 비대칭**이라는 것을 수치로 고정해 둔다.
    # 구간 폭이 5~7(3점)과 17~20(4점)으로 달라서, 공격투자형이 안정형보다 2.7배 나온다.
    # 구간 경계를 조정할 때 이 수치가 근거가 된다.
    assert levels[1] == 21 and levels[5] == 56
    assert levels[3] > levels[2] > levels[1]


# --- ⑤ 선언과의 대조 ---------------------------------------------------------------


@pytest.mark.parametrize(
    ("declared", "observed", "expected"),
    [(3, 3, "SAME"), (3, 4, "NEAR"), (4, 3, "NEAR"), (1, 5, "FAR"), (3, 5, "FAR")],
)
def test_compare_splits_into_three_bands(declared: int, observed: int, expected: str) -> None:
    assert compare(declared, observed) == expected


def test_compare_without_a_declaration_is_none() -> None:
    """없는 선언을 지어내 '예상과 달랐습니다' 를 쓰면 거짓말이다."""
    assert compare(None, 3) is None
    assert compare(3, None) is None


def test_widest_gap_picks_the_item_that_split_the_most() -> None:
    profile = to_profile(account())
    # 선언을 전부 1점으로 두면, 관찰 점수가 가장 높은 항목이 가장 크게 갈린다.
    gap = widest_gap("11111", profile.items)
    assert gap is not None
    item, declared_point, observed_point = gap
    assert item in ITEMS
    assert declared_point == 1
    best = max(
        (score for score in profile.items if not score.provisional),
        key=lambda score: score.rounded or 0,
    )
    assert observed_point == best.rounded


def test_widest_gap_skips_thin_items() -> None:
    """근거가 얇은 자리를 '가장 크게 갈렸다' 고 짚으면 안 된다."""
    thin = to_profile(account(quickProfitCount=1, quickStopCount=0, sellCount=30))
    stop = next(score for score in thin.items if score.item == "STOP")
    assert stop.provisional
    gap = widest_gap("44444", thin.items)
    assert gap is None or gap[0] != "STOP"


def test_widest_gap_needs_a_declaration() -> None:
    profile = to_profile(account())
    assert widest_gap(None, profile.items) is None
    assert widest_gap("123", profile.items) is None


# --- ⑥ payload 어댑터 --------------------------------------------------------------


def test_adapter_builds_all_five_items_from_the_existing_payload() -> None:
    """**백엔드에 새로 요청할 필드가 없다.** 지금 오는 값으로 다섯이 다 나온다."""
    raw = account()
    assert all(raw.value(item) is not None for item in ITEMS)
    assert raw.invested == Decimal("12000000").copy_negate() / Decimal("138200000") + 1
    assert raw.drawdown == Decimal("31.40")
    assert raw.entry == Decimal(12) / Decimal(40)
    assert raw.dip == Decimal(6) / Decimal(6 + 4)
    assert raw.stop == Decimal(4) / Decimal(4 + 6)


def test_adapter_prefers_total_cash_over_krw_cash() -> None:
    """달러 현금이 오면 그것을 쓴다. 안 오면 원화로 내려간다."""
    with_usd = account(totalCashKrw="69100000")
    assert with_usd.invested == Decimal("0.5")


def test_adapter_returns_none_when_a_denominator_is_zero() -> None:
    """0 으로 두면 한 번도 안 판 사람이 '가장 빨리 끊었다' 가 된다."""
    quiet = account(
        buyCount=0,
        sellCount=0,
        fomoBuyCount=0,
        bottomSnipeBuyCount=0,
        panicBottomSellCount=0,
        quickProfitCount=0,
        quickStopCount=0,
    )
    assert quiet.entry is None
    assert quiet.dip is None
    assert quiet.stop is None
    assert to_profile(quiet).level is None


def test_samples_are_per_item_not_shared() -> None:
    """고정하면 매도 4건으로 만든 STOP 점수가 전체 거래 70건을 근거로 축소를 빠져나간다."""
    raw = account()
    assert raw.n_invested == raw.n_drawdown == 70
    assert raw.n_entry == 40
    assert raw.n_dip == 10
    assert raw.n_stop == 10


# --- ⑦ 코퍼스 -----------------------------------------------------------------------


def test_five_levels_all_appear_and_none_dominates() -> None:
    """다섯 단계가 다 나오는가, 한 단계가 지나치게 몰리지 않는가.

    **지금은 위험중립형이 약 50% 다.** 표본 축소가 점수를 중앙으로 끌어당기는데, 목업의
    `DIP`·`STOP` 표본이 열 건 남짓이라 그 두 항목이 세게 눌린다. 실데이터로 참조 컷을
    갈아 끼우고 표본이 늘면 완화될 값이라, 지금은 **다섯이 다 나오는지**를 고정하고
    쏠림은 55% 선에서 막아 둔다. 목표는 40% 이하다.
    """
    from scripts.mock_data import simulate

    _, season, _ = simulate(seed=11, users=600, season_day=3)
    request = SeasonReportRequest.model_validate(season)
    levels = collections.Counter(to_profile(to_raw_items(a)).level for a in request.accounts)
    determined = {level: n for level, n in levels.items() if level is not None}
    assert set(determined) == {1, 2, 3, 4, 5}, determined
    total = sum(determined.values())
    assert max(determined.values()) / total < 0.55, determined


def test_profile_is_deterministic() -> None:
    """같은 입력에 같은 결과. 리포트가 폐장 스냅샷이 되려면 이것이 성립해야 한다."""
    raw = account()
    first, second = to_profile(raw), to_profile(raw)
    assert first.level == second.level
    assert first.total == second.total
    assert [item.score for item in first.items] == [item.score for item in second.items]


# --- ⑧ 조립까지 -----------------------------------------------------------------


def season_features(**overrides: object):
    from app.services.inbound import to_season_features

    payload = {"seasonId": 1, "accounts": [TRADED | overrides]}
    request = SeasonReportRequest.model_validate(payload)
    return to_season_features(request, request.accounts[0])


def test_declaration_fills_the_comparison() -> None:
    """선언이 오면 대조가 서고, 항목마다 선언 점수가 붙는다."""
    features = season_features(selfAnswers="12341")
    assert features.self_total == 11
    assert features.self_level == 3
    assert features.self_persona
    assert features.profile_match in {"SAME", "NEAR", "FAR"}
    assert [item.self_point for item in features.profile_items] == [1, 2, 3, 4, 1]


def test_without_a_declaration_the_comparison_is_empty() -> None:
    """없는 선언을 지어내 '예상과 달랐습니다' 를 쓰면 거짓말이다."""
    features = season_features()
    assert features.self_level is None
    assert features.profile_match is None
    assert features.gap_item is None
    assert all(item.self_point is None for item in features.profile_items)


@pytest.mark.parametrize("answers", ["", "1234", "12305", "abcde"])
def test_malformed_declaration_is_treated_as_none(answers: str) -> None:
    features = season_features(selfAnswers=answers)
    assert features.self_level is None
    assert features.profile_match is None


def test_observed_side_stands_on_its_own() -> None:
    """선언이 없어도 관찰 쪽은 그대로 나온다. 리포트의 절반이 비지 않는다."""
    features = season_features()
    assert features.profile_level in {1, 2, 3, 4, 5}
    assert features.profile_name and features.profile_persona
    assert len(features.profile_items) == len(ITEMS)


def test_card_follows_the_profile_level_not_the_four_axes() -> None:
    from app.services.profile import PERSONAS
    from app.services.report_assembly import assemble_season

    features = season_features(selfAnswers="12341")
    text = assemble_season(features)
    assert text.type_name == PERSONAS[features.profile_level]


def test_gap_sentence_needs_both_sides() -> None:
    from app.services.report_assembly import assemble_season

    with_declaration = assemble_season(season_features(selfAnswers="11111"))
    without = assemble_season(season_features())
    assert with_declaration.narrative.profile_gap
    # **숫자를 쓰지 않는다** — 점수는 막대에서 이미 보여줬다.
    assert not any(char.isdigit() for char in with_declaration.narrative.profile_gap)
    assert without.narrative.profile_gap is None


def test_trait_titles_are_comparative_not_absolute() -> None:
    """⑥ 제목이 본문의 수치와 어긋나지 않는가.

    **실제로 어긋났다.** 점수가 사분위 상대 위치인데 제목이 절대 주장을 하고 있었다 —
    현금을 54.2% 남긴 계좌에 "현금을 남기지 않았습니다 / 현금은 54.2%를 남기셨습니다"
    가 함께 나갔다. 4축 시절 `RISK` 특징에서 겪은 것과 같은 종류다.

    비교형("덜 남긴 쪽이었습니다")으로 쓰면 어느 수치와도 부딪히지 않는다.
    """
    from app.services.report_blocks import load_blocks

    absolute = ("남기지 않았", "다 넣었", "전부", "하나도")
    for block in load_blocks()["trait_title"]:
        for word in absolute:
            assert word not in block["text"], f"{block['id']}: 절대 주장 '{word}'"


def test_trait_title_and_body_agree_across_the_corpus() -> None:
    """목업 전체에서 제목과 본문이 같은 방향을 가리키는가."""
    from app.services.report_assembly import assemble_season
    from scripts.mock_data import simulate

    _, season, _ = simulate(seed=7, users=300, season_day=3)
    request = SeasonReportRequest.model_validate(season)
    checked = 0
    for payload in request.accounts:
        features = season_from(request, payload)
        text = assemble_season(features)
        points = {item.item: item.point for item in features.profile_items}
        for trait in text.narrative.traits:
            if not trait.axis:
                continue
            # 제목이 '덜/빠른/일찍' 쪽이면 점수도 낮은 쪽이어야 한다.
            low_words = (
                "넉넉히 남긴",
                "많이 두신",
                "깊이 빠지기 전",
                "크게 흔들리지 않",
                "빠진 자리를 노린",
                "충분히 내린",
                "덜어낸",
                "발을 빼신",
                "빨리 끊은",
                "물러설 때를",
            )
            if any(word in trait.title for word in low_words):
                assert points[trait.axis] <= 2, (trait.title, points[trait.axis])
            else:
                assert points[trait.axis] > 2, (trait.title, points[trait.axis])
            checked += 1
    assert checked > 500, checked


def season_from(request, payload):
    from app.services.inbound import to_season_features

    return to_season_features(request, payload)


# --- ⑨ ③④ 도 항목에서 온다 --------------------------------------------------------


def test_self_view_comes_from_the_answers() -> None:
    """③ 은 `selfAnswers` 로 만든다. 4축 `selfChoices` 가 아니다."""
    from app.services.report_assembly import assemble_season

    text = assemble_season(season_features(selfAnswers="11111"))
    assert text.narrative.self_view is not None
    assert text.narrative.self_view.startswith("스스로는 ")
    assert text.narrative.self_view.endswith("보셨습니다.")
    # 종결형에서 어미만 잘라 붙이면 "현금을 넉넉히 남기겠고" 같은 비문이 나온다.
    assert "겠고" not in text.narrative.self_view


def test_self_view_is_empty_without_answers() -> None:
    from app.services.report_assembly import assemble_season

    assert assemble_season(season_features()).narrative.self_view is None
    assert assemble_season(season_features(selfAnswers="oops")).narrative.self_view is None


def test_prompt_carries_item_directions_not_scores() -> None:
    """④ 도 항목을 본다. 점수를 주면 모델이 방향을 반대로 읽는다."""
    from app.services.narrative import ITEM_HINTS
    from app.services.season_narrative import build_message

    features = season_features(selfAnswers="12341")
    message = build_message(features, "변동성을 즐기는 트레이더")
    hints = {hint for directions in ITEM_HINTS.values() for hint in directions.values()}
    assert any(hint in message for hint in hints)
    # 원지표도 항목 점수도 새어 나가지 않는다.
    assert str(features.accountId if hasattr(features, "accountId") else 4021) not in message


def test_prompt_and_traits_point_at_the_same_items() -> None:
    """④ 와 ⑥ 이 다른 자리를 짚으면 한 화면에서 두 가지 이야기를 하게 된다."""
    from app.services.narrative import pick_trait_items
    from app.services.season_narrative import _prominent_items

    features = season_features(selfAnswers="12341")
    traits = pick_trait_items(features.profile_items, features.gap_item)
    assert _prominent_items(features) == traits[:2]


# --- 낙폭 스냅샷이 없는 일차 ------------------------------------------------------


def test_missing_drawdown_is_unmeasured_not_zero() -> None:
    """`maxDrawdown` 이 빠져 오면 **못 쟀다**로 둔다. 0 으로 접으면 안 된다.

    백엔드는 그 일차의 폐장 스냅샷이 없을 때 이 필드를 통째로 빼고 보낸다
    (`AiDailyReportRequest.Cumulative` 가 `@JsonInclude(NON_NULL)` 이다). 기본값 0 을
    두면 낙폭을 재지 못한 계좌가 "한 번도 물리지 않았다" 가 되어 손실 감내가 가장
    보수적인 1점으로 나간다 — 다섯 중 하나가 거짓이 되고 합계·단계까지 끌려간다.
    """
    from app.services.profile import score_items

    without = {key: value for key, value in TRADED.items() if key != "maxDrawdown"}
    payload = {"seasonId": 1, "accounts": [without]}
    request = SeasonReportRequest.model_validate(payload)
    assert request.accounts[0].max_drawdown is None

    raw = to_raw_items(request.accounts[0])
    assert raw.drawdown is None

    drawdown = next(item for item in score_items(raw) if item.item == "DRAWDOWN")
    assert drawdown.rounded is None
    assert drawdown.provisional is True


def test_missing_drawdown_keeps_the_other_four() -> None:
    """낙폭 하나가 빠져도 나머지 넷은 그대로 판정된다 — 항목이 셋 이상이면 단계가 선다."""
    # 값이 빠진 것과 `null` 로 온 것은 파싱 뒤 같다 (위 테스트가 빠진 쪽을 덮는다).
    features = season_features(maxDrawdown=None)
    measured = [item for item in features.profile_items if item.point is not None]
    assert len(measured) == 4
    assert features.profile_level is not None


def test_missing_drawdown_does_not_write_a_number_it_does_not_have() -> None:
    """문장이 없는 값을 인용하지 않는다 — 특징 본문도, 손실 감내 줄도."""
    from app.services.narrative import TraitFacts, to_item_body, to_loss_tolerance_line

    facts = TraitFacts(max_drawdown=None)
    assert to_item_body("DRAWDOWN", facts) == "최대 낙폭은 재지 못했습니다."
    assert to_loss_tolerance_line(-25, None) is None
    assert to_loss_tolerance_line(-25, Decimal("-31.4")) is not None


# --- 감내 손실률은 설문 답에서 나온다 ------------------------------------------


def test_loss_tolerance_comes_from_the_answer() -> None:
    """백엔드가 `lossTolerancePct` 를 늘 비워 보내므로 답 원본에서 낸다.

    비워 두면 결과지의 '손실 감내' 줄이 아무에게도 나가지 못한다. 숫자는 프론트 2번 문항의
    선택지 문구와 같은 값이다 — 한쪽만 고치면 고르지 않은 숫자를 인용하게 된다.
    """
    from app.services.profile import to_loss_tolerance_pct

    # 두 번째 자리가 손실 감내다 (`ITEMS` 순서).
    assert to_loss_tolerance_pct("21111") == -5
    assert to_loss_tolerance_pct("22111") == -10
    assert to_loss_tolerance_pct("23111") == -20
    assert to_loss_tolerance_pct("24111") == -30
    # 형식이 어긋나거나 없으면 선언이 없는 것과 같다.
    assert to_loss_tolerance_pct(None) is None
    assert to_loss_tolerance_pct("oops") is None


def test_loss_tolerance_line_reaches_the_sheet() -> None:
    """설문만 있으면 결과지에 그 줄이 실린다 — 백엔드가 보내 주지 않아도."""
    from app.services.report_assembly import assemble_season

    features = season_features(selfAnswers="24312")
    assert features.loss_tolerance_pct == -30

    sheet = assemble_season(features)
    assert sheet.narrative.loss_tolerance is not None
    assert "30%" in sheet.narrative.loss_tolerance


def test_backend_value_wins_when_it_arrives() -> None:
    """백엔드가 언젠가 채워 보내면 그쪽이 정본이다 — 우리 유도값이 덮지 않는다."""
    features = season_features(selfAnswers="24312", lossTolerancePct=-25)
    assert features.loss_tolerance_pct == -25

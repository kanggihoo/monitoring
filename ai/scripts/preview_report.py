"""목업 유저의 복기 리포트를 끝까지 만들어 보여준다. **테스트의 단일 진입점이다.**

    uv run python scripts/preview_report.py                # 블록 문장으로 조립 (오프라인)
    uv run python scripts/preview_report.py --llm          # 헤드라인을 GMS 로 생성
    uv run python scripts/preview_report.py --api          # 실제 엔드포인트를 호출해서
    uv run python scripts/preview_report.py --api --url http://localhost:8000
    uv run python scripts/preview_report.py --seed 42      # 같은 유저를 다시
    uv run python scripts/preview_report.py --users 5      # 5명 연속
    uv run python scripts/preview_report.py --json         # 입력 payload 전문까지

`--llm`·`--api` 는 크레딧을 쓴다. 인원을 곱해 20건을 넘으면 멈추고 `--yes` 를 요구한다 —
분포를 볼 때 쓰는 `--users 300` 은 모드 없이 돌리면 호출이 0 이다.

모드가 둘이다.

- **조립 모드**(기본) — 함수를 직접 불러 `payload → 조건 벡터 → 문장` 을 찍는다. 서버도 DB 도
  필요 없고, `--llm` 을 붙이면 헤드라인만 GMS 로 만든다.
- **API 모드**(`--api`) — 같은 목업을 `POST /api/ai/reports/daily` · `/season` 에 그대로 태우고
  **응답으로 온 문장**을 찍는다. 백엔드가 부르는 경로와 같다. `--url` 을 주지 않으면 서버를
  띄우지 않고 앱을 그 자리에서 호출하므로, 이 한 줄로 계약·라우팅·인증·조립을 모두 지난다.
  헤드라인 생성 여부는 그쪽 `.env`(`AI_LLM_API_KEY`)가 정한다.

**입력 → 판정 → 문장** 세 단계를 다 찍는다. 문장만 보면 왜 그 문장이 나왔는지 알 수 없어서,
조건 벡터와 그 근거를 함께 낸다.

목업 데이터는 `scripts/mock_data.py` 가 만든다. 조건별 블록 재고는 `scripts/check_blocks.py`,
GMS 연결 확인은 `scripts/check_llm.py` 가 본다. 실행 절차는 `docs/usage.md` 7절.
"""

from __future__ import annotations

import argparse
import asyncio
import json
import random
import sys
from decimal import Decimal
from pathlib import Path
from typing import Any

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
sys.path.insert(0, str(Path(__file__).resolve().parent))

from mock_data import simulate

from app.core.config import Settings, get_settings
from app.core.logging import configure_logging
from app.schemas.inbound import (
    DailyReportRequest,
    InboundDailyAccount,
    SeasonReportRequest,
)
from app.schemas.report import DailyFeatures, SeasonFeatures
from app.services.headline import generate_many
from app.services.inbound import to_daily_features, to_daily_review_features
from app.services.llm import LlmClient
from app.services.report_assembly import assemble_daily, assemble_season

WIDTH = 74

#: 확인 없이 지나가는 모델 호출 수. 이 위로는 `--yes` 를 요구한다.
#: 한 명을 여러 번 보는 평소 사용(1~10명)은 걸리지 않고, `--users 300` 처럼 분포를 보려는
#: 실행만 걸린다. **분포는 블록 문장으로 봐도 되므로**, 그 실행에 크레딧이 나가는 것은
#: 대개 의도가 아니다.
CALL_BUDGET = 20
SIDE_LABEL = {"BUY": "매수", "SELL": "매도"}
TAG_LABEL = {
    "PEAK_BUY": "고점 매수",
    "BOTTOM_SELL": "저점 매도",
    "BOTTOM_BUY": "저점 매수",
    "PEAK_SELL": "고점 매도",
    "BEST_TRADE": "최고 거래",
    "WORST_TRADE": "최악 거래",
    "NEUTRAL": "-",
}
VS_INDEX_LABEL = {
    "ABOVE": "시장보다 앞섰다",
    "SIMILAR": "시장과 비슷했다",
    "BELOW": "시장에 뒤처졌다",
}
MISTAKE_LABEL = {
    "PEAK_BUY": "고점 매수가 가장 두드러졌다",
    "BOTTOM_SELL": "저점 매도가 가장 두드러졌다",
    "OVERTRADE": "과다 매매",
    "NORMAL": "별다른 실수가 없었다",
    "QUIET": "거래가 적었다",
}


def rule(char: str = "─") -> None:
    print(char * WIDTH)


def show_input(request: DailyReportRequest, account: InboundDailyAccount, *, full: bool) -> None:
    """백엔드가 보내는 것. v5 계약은 체결 원본 대신 판정이 끝난 것만 담는다."""
    metrics = account.metrics
    print("[입력]  백엔드가 보낼 payload — app/schemas/inbound.py")
    print(f"  지수 수익률     {request.index_return:>8}%")
    print(f"  계좌 {account.account_id}       당일 {account.daily_return:>7}%", end="")
    print(f" · 체결 {account.execution_count}건 · 태그 {len(account.pattern_tags)}건")
    print(
        f"  요약            매수 {metrics.buy_count} · 매도 {metrics.sell_count}"
        f" (수익 {metrics.win_count}) · 실현 {metrics.realized_pnl_krw:,}원"
        f" · 체결순위 {metrics.execution_count_rank}/{metrics.participant_count}"
    )
    if metrics.top_instrument_name:
        share = metrics.top_instrument_share_krw or Decimal(0)
        print(f"  집중            {metrics.top_instrument_name} {share * 100:.0f}%", end="")
        print(f" · 평균 보유 {metrics.avg_holding_days}일" if metrics.avg_holding_days else "")

    for tag in account.pattern_tags[:3]:
        print(
            f"    [태그] {tag.game_month}  {tag.instrument_name:<5}"
            f" {SIDE_LABEL[tag.side]}  {tag.kind:<16} {tag.change_rate:+.1f}%"
        )
    if len(account.pattern_tags) > 3:
        print(f"    … 그 외 태그 {len(account.pattern_tags) - 3}건")
    for highlight in account.highlights:
        print(
            f"    [{highlight.role:<5}] {highlight.game_month}  {highlight.instrument_name:<5}"
            f" {SIDE_LABEL[highlight.side]}  {highlight.realized_pnl_krw:>12,}원"
        )

    if full:
        print("  payload 전문")
        dumped = json.dumps(
            account.model_dump(mode="json", by_alias=True), ensure_ascii=False, indent=2
        )
        for line in dumped.splitlines():
            print(f"    {line}")
    else:
        print("  (payload 전문은 --json)")
    print()


def show_features(
    request: DailyReportRequest, account: InboundDailyAccount, features: DailyFeatures
) -> None:
    print("[판정]  extract 결과 — 조건 벡터")
    gap = Decimal(account.daily_return) - Decimal(request.index_return)
    print(
        f"  vsIndex          {features.vs_index:<12}"
        f" {VS_INDEX_LABEL[features.vs_index]} (차이 {gap:+}%p)"
    )
    print(f"  mainMistake      {features.main_mistake:<12} {MISTAKE_LABEL[features.main_mistake]}")
    print(f"  executionCount   {features.execution_count}")
    print(f"  scenes           {len(features.scenes)}건")
    for scene in features.scenes:
        print(
            f"    {scene.game_month}  {scene.instrument_name:<5}"
            f" {SIDE_LABEL[scene.side]}  [{TAG_LABEL[scene.tag]}]"
        )
    print()


def show_commentary(
    features: DailyFeatures,
    text: dict[str, Any],
    *,
    block_headline: str | None = None,
) -> None:
    """리포트의 앞머리 — 그날의 헤드라인과 세 장면.

    **결과지와 한 리포트다.** 배포돼 있는 화면이 이 둘을 쓰고 있어서, 통합한다고 이 자리를
    없애면 이미 나가 있는 리포트가 비어 버린다.
    """
    print(f"  헤드라인      {text['headline']}")
    if block_headline is not None and block_headline != text["headline"]:
        # 무엇이 무엇을 덮었는지 봐야 생성 문장이 나은지 판단할 수 있다.
        print(f'                (LLM 생성 · 블록이었다면 "{block_headline}")')
    print("  오늘의 세 장면")
    for scene, alias in zip(features.scenes, text["sceneAliases"], strict=False):
        label = f"{scene.instrument_name} {SIDE_LABEL[scene.side]}"
        print(f"       {scene.game_month}  {label:<10} · {alias:<14} [{TAG_LABEL[scene.tag]}]")
    if not features.scenes:
        print("       (태그도 하이라이트도 없는 하루다)")


MATCH_LABEL = {
    "SAME": "선언과 같은 단계",
    "NEAR": "한 단계 차이",
    "FAR": "두 단계 이상 차이",
}


def bar(point: int | None, *, provisional: bool = False) -> str:
    """1~4 점을 눈으로 비교할 수 있게 그린다. 잴 수 없었으면 빈 칸이다."""
    if point is None:
        return "····  -"
    drawn = "■" * point + "·" * (4 - point)
    return f"{drawn}  {point}{'~' if provisional else ''}"


def show_judgement(features: SeasonFeatures, text: dict[str, Any]) -> None:
    """판정 재료. **결과지가 아니라 그 앞의 진단이라 따로 찍는다.**

    섞어 놓으면 어디까지가 사용자가 보는 것인지 알 수 없다.
    """
    level = text.get("profileLevel") or features.profile_level
    total = text.get("profileTotal") or features.profile_total
    name = text.get("profileName") or features.profile_name

    print("[판정]  성향 판정")
    if level is None:
        print("  성향         판정 불가 — 잴 수 있었던 항목이 셋 미만이다")
    else:
        print(f"  성향         {level}단계 {name} · 합계 {total} (5~20)")
    print(
        f"  근거 수치     수익 매도 {features.win_count}회"
        f" · {features.return_rate or '-'} · {features.rank_rate or '순위 없음'}"
        f" · 최대 낙폭 {features.max_drawdown}%"
    )
    print()


def show_review(features: SeasonFeatures, text: dict[str, Any]) -> None:
    """복기 결과지 ①→⑧. **화면에 나가는 순서 그대로다.**"""
    # `investorType` 은 라우터가 붙이는 값이라 조립 모드에는 없다.
    code = text.get("investorType")
    card = f"{text.get('typeEmoji', '')} {text['typeName']}"
    print(f"  ① 유형 카드   {card}{f' ({code})' if code else ''}")

    narrative = text.get("narrative") or {}
    if not narrative:
        # 함수 직접 호출 모드는 조립기를 거치지 않아 여덟 덩이가 없다.
        print(f"  설명         {text['typeDescription']}")
        return

    print(f"  ② 한 줄      {narrative.get('oneLine', '')}")
    print(f"  ③ 내가 본 나  {narrative.get('selfView') or '— (선언 없음)'}")
    print(f"  ④ 실제 모습   {narrative.get('actual', '')}")
    show_items(features, text)
    print("  ⑥ 투자 특징")
    for trait in narrative.get("traits", []):
        print(f"       {trait['title']}")
        if trait["body"]:
            print(f"         {trait['body']}")
    print(f"  ⑦ 예상과 차이 {narrative.get('profileGap') or '— (선언 없음)'}")
    print(f"  ⑧ 앞으로      {narrative.get('nextStep', '')}")
    if narrative.get("lossTolerance"):
        print(f"  손실감내      {narrative['lossTolerance']}")

    print("  총평")
    for line in text["verdict"].splitlines():
        print(f"    {line}")


def show_items(features: SeasonFeatures, text: dict[str, Any]) -> None:
    """⑤ 막대 다섯과 선언 대조. **결과지에서 ④ 와 ⑥ 사이에 놓인다.**"""
    items = text.get("profileItems") or [
        item.model_dump(by_alias=True) for item in features.profile_items
    ]
    print("  ⑤ 항목 막대   (관찰 / 선언)")
    for item in items:
        declared = item.get("selfPoint")
        drawn = bar(item.get("point"), provisional=item.get("provisional", False))
        print(
            f"       {item['label']:<6} {drawn}   선언 {declared if declared is not None else '-'}"
        )

    self_level = text.get("selfLevel", features.self_level)
    match = text.get("profileMatch", features.profile_match)
    if self_level is None:
        print("       대조      선언 없음 — ③⑦ 이 빠진다")
    else:
        persona = text.get("selfPersona") or features.self_persona
        print(
            f"       대조      선언 {self_level}단계 {persona}"
            f" · {match} ({MATCH_LABEL.get(match, '-')})"
        )


def preview_one(
    daily_request: DailyReportRequest,
    season_request: SeasonReportRequest,
    index: int,
    persona: str,
    *,
    full_json: bool,
    headline: str | None = None,
    api: ApiTexts | None = None,
) -> None:
    """한 계좌의 리포트 한 장. **일일과 결과지가 한 리포트다.**

    `--api` 는 `POST /api/ai/reports/daily` 하나만 부른다 — 통합 뒤로는 그 한 번이 리포트
    전부를 낸다. 시즌 엔드포인트는 통합 전의 경로라 여기서 부르지 않는다.
    """
    account = daily_request.accounts[index]
    daily_features = to_daily_features(daily_request, account)
    review_features = to_daily_review_features(daily_request, account)

    rule("═")
    print(f"목업 유저 {account.account_id} · DAY {daily_request.season_day} · 성향 {persona}")
    rule("═")
    show_input(daily_request, account, full=full_json)
    show_features(daily_request, account, daily_features)

    block_text = assemble_daily(daily_features).model_dump(by_alias=True)
    if api is not None:
        source = "API 응답"
        daily_text = api.daily.get(account.account_id)
        if daily_text is None:
            print("  이 계좌의 문구가 응답에 없다 — 서버 로그를 본다.")
            print()
            return
        review_text = daily_text.get("review")
    else:
        source = "조립 결과"
        daily_text = (
            assemble_daily(daily_features, headline_override=headline).model_dump(by_alias=True)
            if headline is not None
            else block_text
        )
        review_text = (
            assemble_season(review_features).model_dump(by_alias=True)
            if review_features is not None
            else None
        )

    if review_features is not None and review_text is not None:
        show_judgement(review_features, review_text)

    print(f"[출력]  복기 리포트 ({source})")
    show_commentary(daily_features, daily_text, block_headline=block_text["headline"])
    if review_features is None or review_text is None:
        # 누적 집계가 없으면 결과지가 없다. 통합 전의 모양이고, 헤드라인은 그대로 나간다.
        print("  (결과지 없음 — 누적 집계 `cumulative` 가 payload 에 없다)")
    else:
        show_review(review_features, review_text)

    blocks = list(daily_text["blockIds"])
    if review_text is not None:
        blocks += review_text["blockIds"]
    print(f"  사용한 블록   {', '.join(blocks)}")
    print()


class ApiTexts:
    """엔드포인트가 돌려준 리포트를 계좌별로 세운 것."""

    def __init__(self, daily: dict[int, dict]) -> None:
        self.daily = daily


def call_api(daily: dict, url: str | None) -> ApiTexts:
    """실제 엔드포인트를 호출한다. `url` 이 없으면 서버 없이 앱을 그 자리에서 부른다.

    **백엔드가 부르는 경로 그대로다** — 같은 경로, 같은 헤더, 같은 응답 봉투를 지난다.
    통합 뒤로는 `/reports/daily` 한 번이 헤드라인·장면·결과지를 모두 낸다.
    """
    settings = get_settings()
    headers = {"X-Internal-Api-Key": settings.internal_api_key} if settings.internal_api_key else {}
    prefix = settings.api_prefix

    if url:
        import httpx2

        print(f"API 모드 — {url}{prefix}/reports/daily 로 보낸다")
        with httpx2.Client(base_url=url, timeout=240.0, headers=headers) as client:
            data = _data(client.post(f"{prefix}/reports/daily", json=daily))
    else:
        from fastapi.testclient import TestClient

        from app.main import create_app

        print(f"API 모드 — 서버 없이 앱을 직접 호출한다 ({prefix}/reports/daily)")
        with TestClient(create_app(settings), headers=headers) as client:
            data = _data(client.post(f"{prefix}/reports/daily", json=daily))

    print()
    return ApiTexts(daily={report["accountId"]: report for report in data["reports"]})


def _data(response: Any) -> dict:
    """응답 봉투에서 `data` 를 꺼낸다. 실패는 여기서 드러내야 한다 — 조용히 비우지 않는다."""
    if response.status_code != 200:
        raise SystemExit(f"{response.request.url} → {response.status_code}: {response.text[:300]}")
    return response.json()["data"]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="목업 유저의 복기 리포트 미리보기")
    parser.add_argument("--seed", type=int, default=None, help="생략하면 매번 다른 유저")
    parser.add_argument("--users", type=int, default=1, help="연속으로 볼 인원")
    parser.add_argument("--day", type=int, default=1, choices=[1, 2, 3])
    parser.add_argument("--executions", type=int, default=100, help="사용자당 체결 건수")
    parser.add_argument("--json", action="store_true", help="입력 payload 전문을 함께 찍는다")
    parser.add_argument(
        "--llm",
        action="store_true",
        help="헤드라인을 GMS 게이트웨이로 생성한다 (AI_LLM_API_KEY 필요, 크레딧 차감)",
    )
    parser.add_argument(
        "--api",
        action="store_true",
        help="실제 엔드포인트(POST /api/ai/reports/*)를 호출해 그 응답을 보여준다",
    )
    parser.add_argument(
        "--url",
        default=None,
        help="--api 와 함께. 이미 떠 있는 서버 주소 (예: http://localhost:8000)",
    )
    parser.add_argument(
        "--yes",
        action="store_true",
        help=f"모델 호출이 {CALL_BUDGET}건을 넘어도 그냥 진행한다",
    )
    return parser.parse_args()


def guard_call_volume(args: argparse.Namespace) -> None:
    """크레딧이 크게 나갈 실행을 막는다. **인원과 모드의 곱이 조용히 커지는 자리다.**

    `--api` 는 일일과 시즌을 둘 다 태우므로 인원의 두 배다. 게다가 생성 여부를 **서버 쪽**
    `.env` 가 정해서, 여기서는 켜져 있는지조차 알 수 없다 — 그래서 최대치로 세고 막는다.
    분포를 보려는 `--users 300` 은 `--api` 없이 돌리면 호출이 0 이다.
    """
    if not (args.llm or args.api):
        return
    per_user = 2 if args.api else 1
    calls = args.users * per_user
    if calls <= CALL_BUDGET or args.yes:
        return
    mode = "--api (일일+시즌)" if args.api else "--llm (일일)"
    print(
        f"멈춤: {mode} 로 {args.users}명이면 모델 호출이 최대 {calls}건이다 (상한 {CALL_BUDGET})."
    )
    print("  분포만 보려면      모드 없이 — uv run python scripts/preview_report.py --users 300")
    print(f"  적게 보려면        --users {CALL_BUDGET // per_user} 이하")
    print("  그래도 돌리려면    --yes")
    raise SystemExit(2)


async def make_headlines(daily_request: DailyReportRequest, settings: Settings) -> list[str | None]:
    """미리보기용 헤드라인을 한 번에 만든다. 실패한 자리는 None 이라 블록 문장으로 간다."""
    client = LlmClient(settings)
    await client.start()
    if not client.configured:
        print("AI_LLM_API_KEY 가 없어 --llm 을 건너뛴다 — scripts/check_llm.py 로 확인한다.")
        print()
        return [None] * len(daily_request.accounts)
    try:
        batch = [to_daily_features(daily_request, account) for account in daily_request.accounts]
        return await generate_many(client, batch)
    finally:
        await client.aclose()


def main() -> None:
    # Windows 콘솔 기본 코드페이지에서 한글이 깨지는 것을 막는다.
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    args = parse_args()
    guard_call_volume(args)
    settings = get_settings()
    # 폴백 사유가 WARNING 으로 남는다. 이걸 부르지 않으면 Windows 콘솔에서 그 줄이 깨진다.
    configure_logging(settings.log_level)
    seed = args.seed if args.seed is not None else random.randrange(1, 10_000)  # noqa: S311

    # 일일과 시즌을 같은 시뮬레이션에서 받는다. 따로 만들면 같은 계좌의 두 리포트가 어긋난다.
    daily, season, personas = simulate(
        seed=seed, users=args.users, season_day=args.day, executions_per_day=args.executions
    )
    daily_request = DailyReportRequest.model_validate(daily)
    season_request = SeasonReportRequest.model_validate(season)

    print(f"seed {seed} — 같은 결과를 다시 보려면 --seed {seed}")
    print()

    api_texts = None
    headlines: list[str | None] = [None] * len(daily_request.accounts)
    if args.api:
        # 생성 여부는 서버 쪽 설정이 정한다. 여기서 또 만들면 두 번 부르게 된다.
        api_texts = call_api(daily, args.url)
    elif args.llm:
        headlines = asyncio.run(make_headlines(daily_request, settings))

    for index in range(len(daily_request.accounts)):
        preview_one(
            daily_request,
            season_request,
            index,
            personas[index],
            full_json=args.json,
            headline=headlines[index],
            api=api_texts,
        )


if __name__ == "__main__":
    main()

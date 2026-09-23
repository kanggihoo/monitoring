"""복기 리포트 발급 — 백엔드가 폐장 뒤 한 번씩 부르는 두 엔드포인트.

```
폐장 정산 커밋 → POST /api/ai/reports/daily   (일차마다)
3일차 정산   → POST /api/ai/reports/season   (시즌에 한 번)
```

세 가지를 지킨다.

- **한 계좌가 실패해도 나머지는 보낸다.** 응답에 없는 계좌는 백엔드가 문구 자리를 비운 채
  리포트를 연다 — 한 명의 조립 실패로 100명의 리포트를 비울 이유가 없다.
- **체결이 없는 계좌는 아예 만들지 않는다.** 걸러내는 자리가 모델을 부르기 **전**이라
  그 계좌만큼 호출이 줄고, 응답에서 빠진 자리는 위와 같은 경로로 처리된다
  (`services/inbound.has_daily_trades` · `has_season_trades`).
- **헤드라인 생성 실패는 실패가 아니다.** `generate_many` 가 그 자리에 `None` 을 주고
  조립기가 블록 문장으로 간다 (`services/headline.py`).
- **요청 본문을 로그에 남기지 않는다.** 건수와 걸린 시간만 남긴다.

받는 모양은 `app/schemas/inbound.py`, 문장을 만드는 규칙은
`docs/architecture/report-assembly.md`.
"""

from __future__ import annotations

import time
from typing import Annotated

from fastapi import APIRouter, Depends

from app.core.logging import get_logger
from app.core.security import require_internal_key
from app.schemas.common import ApiResponse
from app.schemas.inbound import DailyReportRequest, SeasonReportRequest
from app.schemas.report import (
    DailyReportItem,
    DailyReportResponse,
    ReviewReport,
    SeasonFeatures,
    SeasonReportItem,
    SeasonReportResponse,
)
from app.services.headline import generate_many
from app.services.inbound import (
    has_daily_trades,
    has_season_trades,
    to_daily_features,
    to_daily_review_features,
    to_season_features,
)
from app.services.llm import LlmClient, get_llm_client
from app.services.profile import profile_code
from app.services.report_assembly import (
    assemble_daily,
    assemble_season,
    type_name_of,
)
from app.services.season_narrative import Narration
from app.services.season_narrative import generate_many as generate_season_many

logger = get_logger(__name__)

router = APIRouter(
    prefix="/reports",
    tags=["reports"],
    dependencies=[Depends(require_internal_key)],
)


def to_review_report(
    features: SeasonFeatures | None,
    narration: Narration | None,
    *,
    exclude: set[str] | None = None,
) -> ReviewReport | None:
    """결과지 하나를 조립한다. **일일과 시즌이 같은 이 함수를 지난다.**

    조립이 터지면 `None` 이다 — 일일에서는 그 계좌가 헤드라인까지 잃지 않게, 시즌에서는
    한 계좌 때문에 전원이 비지 않게. 원인은 로그에 남는다.
    """
    if features is None:
        return None
    try:
        text = assemble_season(features, exclude=exclude, narration=narration)
    except Exception:
        # 조건 조합에 맞는 블록이 없거나 그 계좌의 값에서 조립이 터진 경우다. **넓게 잡는다.**
        logger.exception("복기 결과지 조립 실패 (계정=%s)", features.account_id)
        return None

    return ReviewReport(
        # 백엔드의 usable() 이 이 값을 비우면 그 계좌 문구를 통째로 버린다.
        # 판정이 안 서도 `UNDETERMINED` 가 들어가므로 빈 적이 없다.
        investorType=profile_code(features.profile_level),
        profileLevel=features.profile_level,
        profileTotal=features.profile_total,
        profileName=features.profile_name,
        profilePersona=features.profile_persona,
        profileItems=features.profile_items,
        selfLevel=features.self_level,
        selfTotal=features.self_total,
        selfPersona=features.self_persona,
        profileMatch=features.profile_match,
        **text.model_dump(),
    )


@router.post(
    "/daily",
    summary="복기 리포트 — 폐장 정산 뒤 일차마다 한 번",
)
async def daily_reports(
    payload: DailyReportRequest,
    llm: Annotated[LlmClient, Depends(get_llm_client)],
) -> ApiResponse[DailyReportResponse]:
    """그 일차 계좌 전원의 리포트를 만든다. **헤드라인·장면 별칭 + 복기 결과지다.**

    결과지는 계좌의 `cumulative`(그 일차까지의 누적 집계)가 와야 생긴다. 오지 않으면
    `review` 가 `null` 이고 응답은 통합 전과 같은 모양이다 — 이미 배포된 화면이 헤드라인과
    장면 별칭을 쓰고 있어서, 그 둘은 어느 경우에도 사라지지 않는다.

    모델은 계좌마다 최대 두 번 부른다 (헤드라인 ①, 결과지 ④). 동시 호출 상한은 `LlmClient`
    가 쥐고 있어 100명이면 25배치가 순차로 돈다 — 백엔드의 읽기 상한이 그 시간을 본다.
    """
    started = time.perf_counter()

    # **그 하루 쉬었다고 빼지 않는다.** 결과지는 그날이 아니라 그때까지의 누적을 보는
    # 카드라, 관망한 일차에 계좌를 빼면 어제 있던 성향 카드가 오늘 사라졌다가 내일 돌아온다.
    # 체결도 누적도 없는 계좌만 뺀다 — 그 사람에겐 정말 할 말이 없다.
    kept = [
        account
        for account in payload.accounts
        if has_daily_trades(account) or account.cumulative is not None
    ]
    skipped = len(payload.accounts) - len(kept)

    features = [to_daily_features(payload, account) for account in kept]
    # 누적 집계가 온 계좌만 결과지를 받는다. 자리를 맞춰 두고 없는 자리는 `None` 으로 둔다 —
    # 뒤에서 zip 으로 함께 도는데 길이가 어긋나면 남의 문장을 받는다.
    reviews = [to_daily_review_features(payload, account) for account in kept]

    # 배치가 쓴 양만 보려고 앞뒤로 장부를 뜬다. 누적을 그냥 찍으면 프로세스가 살아 있는
    # 동안 계속 커져서 "이번 일차에 얼마 썼나" 를 읽어낼 수 없다.
    usage_before = llm.usage
    # **모델은 그날 거래한 계좌에만 부른다.** 관망한 하루는 블록에 이미 문장이 있고
    # (`HEAD_*_QUIET_*` 아홉), 없는 체결을 두고 모델에게 쓰라고 하면 지어낸다. 거르지 않던
    # 때와 호출 수가 같아 비용도 그대로다.
    traded = [
        feature
        for feature, account in zip(features, kept, strict=True)
        if has_daily_trades(account)
    ]
    written = await generate_many(llm, traded)
    by_headline = {
        feature.account_id: headline for feature, headline in zip(traded, written, strict=True)
    }
    headlines = [by_headline.get(feature.account_id) for feature in features]
    # ④ 는 결과지가 있는 계좌만 부른다. 모아서 한 번에 보내고 자리대로 되돌려 놓는다.
    narrated = [review for review in reviews if review is not None]
    narrations = await generate_season_many(
        llm, [(review, type_name_of(review)) for review in narrated]
    )
    by_account = {
        review.account_id: narration for review, narration in zip(narrated, narrations, strict=True)
    }
    usage = llm.usage - usage_before

    reports: list[DailyReportItem] = []

    for account, feature, review, headline in zip(
        kept,
        features,
        reviews,
        headlines,
        strict=True,
    ):
        try:
            # 전날 쓴 블록을 뺀다.
            # 빼고 나서 후보가 비면 조립기가 빼지 않으므로
            # (`report_blocks.choose`) 문장이 사라지지는 않는다 —
            # 중복이 빈 자리보다 낫다.
            text = assemble_daily(
                feature,
                exclude=set(account.previous_block_ids),
                headline_override=headline,
            )
        except Exception:
            # 조건 조합에 맞는 블록이 없거나, 그 계좌의 값에서 조립이 터진 경우다.
            # **넓게 잡는다** — 여기서 예외를 올리면 한 명 때문에 그 일차 전원의 문구가
            # 빈 채로 리포트가 열린다. 원인은 로그에 남는다.
            logger.exception(
                "일일 문구 조립 실패 (계정=%s)",
                feature.account_id,
            )
            continue

        reports.append(
            DailyReportItem(
                accountId=feature.account_id,
                review=to_review_report(
                    review,
                    by_account.get(feature.account_id),
                    exclude=set(account.previous_block_ids),
                ),
                **text.model_dump(),
            )
        )

    generated = sum(1 for headline in headlines if headline)
    # 결과지가 실린 계좌 수. 누적 집계가 안 온 배포에서는 0 이고, 그때도 헤드라인은 나간다.
    reviewed = sum(1 for report in reports if report.review is not None)

    logger.info(
        "복기 리포트 %d/%d건 (체결·누적 없음 %d건 제외, 헤드라인 %d건, 결과지 %d건, %.1fs)"
        " day=%s · %s",
        len(reports),
        len(payload.accounts),
        skipped,
        generated,
        reviewed,
        time.perf_counter() - started,
        payload.season_day,
        usage.line(),
    )

    return ApiResponse.ok(
        DailyReportResponse(
            seasonDay=payload.season_day,
            reports=reports,
        ),
        message="복기 리포트를 만들었습니다.",
    )


@router.post(
    "/season",
    summary="시즌 총평 — 3일차 정산 뒤 한 번",
)
async def season_reports(
    payload: SeasonReportRequest,
    llm: Annotated[LlmClient, Depends(get_llm_client)],
) -> ApiResponse[SeasonReportResponse]:
    """계좌 전원의 투자자 유형과 총평을 만든다.

    **일일 응답의 `review` 와 같은 값을 낸다.** 3일차의 누적이 곧 시즌 전체이고, 두 경로가
    같은 어댑터(`to_review_features`)와 같은 조립기를 지난다 — 테스트로 고정해 두었다.
    통합 뒤에도 이 경로를 함께 지원한다.

    **판정은 계좌마다 독립이다.** 고정 참조 분위수 표에 대고 환산하므로 요청에 몇 명이
    들어 있든 같은 계좌는 같은 점수를 받는다 (명세 6절 ②).

    결과지 ④⑦ 만 모델을 부른다. 계좌당 한 번이고, 실패하면 그 자리가 폴백 블록으로
    내려앉을 뿐 리포트는 그대로 열린다.
    """
    started = time.perf_counter()

    # 체결이 없는 계좌는 모델 호출 전에 제외한다.
    traded = [account for account in payload.accounts if has_season_trades(account)]
    skipped = len(payload.accounts) - len(traded)

    # 체결이 있는 계좌만 시즌 feature 를 만든다. **배치 기준선을 쓰지 않는다** —
    # 고정 참조 표에 대고 환산하므로 요청에 몇 명이 들어 있든 같은 계좌는 같은 점수를 받는다.
    features_by_account = [(account, to_season_features(payload, account)) for account in traded]

    # 카드 이름은 블록에서 오므로 생성 전에 한 번 뽑아 둔다.
    # 프롬프트에도 현재 투자자 유형 이름을 함께 전달한다.
    usage_before = llm.usage
    narrations = await generate_season_many(
        llm,
        [
            (
                features,
                type_name_of(features),
            )
            for _, features in features_by_account
        ],
    )

    usage = llm.usage - usage_before

    reports: list[SeasonReportItem] = []

    for (account, features), narration in zip(
        features_by_account,
        narrations,
        strict=True,
    ):
        review = to_review_report(features, narration)
        if review is None:
            continue
        reports.append(SeasonReportItem(accountId=account.account_id, **review.model_dump()))

    # ④ 가 모델 문장으로 채워진 계좌 수. 나머지는 폴백 블록으로 내려앉았다.
    generated = sum(1 for narration in narrations if narration.actual)

    logger.info(
        "시즌 문구 %d/%d건 (체결 없음 %d건 제외, 서술 생성 %d건, %.1fs) season=%s · %s",
        len(reports),
        len(payload.accounts),
        skipped,
        generated,
        time.perf_counter() - started,
        payload.season_id,
        usage.line(),
    )

    return ApiResponse.ok(
        SeasonReportResponse(
            reports=reports,
        ),
        message="시즌 문구를 만들었습니다.",
    )

"""온보딩 한 줄 해석 — 자연어를 축별 선택으로 옮기는 선택 경로.

**백엔드가 경유해서 부른다.** 프론트가 직접 부르면 CORS 를 열어야 하고 내부 키가 브라우저에
노출된다 — `ai/` 는 브라우저가 직접 호출하지 않는 서비스다 (`docs/conventions/api.md`).

버튼 4개가 기본 경로이므로 **이 엔드포인트가 실패해도 온보딩은 진행된다.** `confidence` 가
`LOW` 로 오면 프론트가 버튼 경로로 되돌린다.
"""

from __future__ import annotations

from typing import Annotated, Literal

from fastapi import APIRouter, Depends
from pydantic import Field

from app.core.security import require_internal_key
from app.schemas.common import ApiResponse, CamelModel
from app.services.llm import LlmClient, get_llm_client
from app.services.onboarding import MAX_INPUT, interpret, to_reply

router = APIRouter(
    prefix="/onboarding",
    tags=["onboarding"],
    dependencies=[Depends(require_internal_key)],
)


class InterpretRequest(CamelModel):
    """사용자가 적은 한 줄. **원문은 로그에 남기지 않는다.**"""

    text: str = Field(max_length=MAX_INPUT)


class InterpretResponse(CamelModel):
    """**출력이 숫자 다섯과 등급뿐이다.** 자유 문장을 돌려주지 않아서, 입력이 지시를
    덮어쓰려 해도 피해 범위가 구조적으로 막힌다."""

    #: 5문항의 답 원본 (`"24312"`). 버튼 경로가 만드는 값과 같은 모양이다.
    self_answers: str
    self_total: int
    #: 1~5. 합계가 구간을 벗어나는 일은 없지만 계약상 `None` 을 허용한다.
    self_level: int | None = None
    self_name: str = ""
    self_persona: str = ""
    confidence: Literal["HIGH", "MID", "LOW"]


@router.post("/interpret", summary="온보딩 한 줄 입력을 축별 선택으로 옮긴다")
async def interpret_onboarding(
    payload: InterpretRequest,
    llm: Annotated[LlmClient, Depends(get_llm_client)],
) -> ApiResponse[InterpretResponse]:
    """실패해도 200 이다. `confidence` 가 `LOW` 면 프론트가 버튼 경로로 되돌린다.

    500 을 내지 않는 이유는 **이 자리의 실패가 온보딩을 막을 이유가 아니기** 때문이다.
    """
    choices, confidence = await interpret(llm, payload.text)
    return ApiResponse.ok(
        InterpretResponse.model_validate(to_reply(choices, confidence)),
        message="한 줄 입력을 해석했습니다.",
    )

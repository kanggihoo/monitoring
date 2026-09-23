"""헬스체크.

`GET /health` 는 **접두사 없이 루트에 붙는다.** 컨테이너 healthcheck·로드밸런서·팀 간 연동
확인이 찌르는 경로라 API 버저닝·접두사 정책과 분리한다 (docs/conventions/api.md 참조).
"""

from __future__ import annotations

from typing import Annotated

from fastapi import APIRouter, Depends

from app import __version__
from app.core.config import Settings, get_settings
from app.core.security import require_internal_key
from app.schemas.common import ApiResponse, CamelModel

#: 루트에 붙는다 → `GET /health`
router = APIRouter(tags=["health"])

#: `/api/ai` 아래에 붙는다 → `GET /api/ai/health/internal`
internal_router = APIRouter(prefix="/health", tags=["health"])


class InternalHealthData(CamelModel):
    """내부 헬스체크가 함께 내는 값."""

    #: 모델을 부를 수 있는 상태인가. 꺼져 있으면 리포트는 블록 문장으로 나가고
    #: 온보딩 한 줄 입력은 아예 쓸 수 없다.
    llm_active: bool = False


class HealthData(CamelModel):
    env: str
    version: str


@router.get("/health", summary="Liveness — 인증 없이 호출 가능")
async def health(
    settings: Annotated[Settings, Depends(get_settings)],
) -> ApiResponse[HealthData]:
    """컨테이너 healthcheck 와 로드밸런서가 찌르는 엔드포인트라 인증을 걸지 않는다."""
    return ApiResponse.ok(HealthData(env=settings.env, version=__version__))


@internal_router.get(
    "/internal",
    summary="내부 호출 키 검증 — Spring 연동 확인용",
    dependencies=[Depends(require_internal_key)],
)
async def health_internal(
    settings: Annotated[Settings, Depends(get_settings)],
) -> ApiResponse[InternalHealthData]:
    """양쪽 `AI_INTERNAL_API_KEY` 가 일치하는지 확인한다. 불일치면 401.

    `llmActive` 를 함께 낸다 — **모델이 꺼져 있으면 온보딩의 한 줄 입력칸을 화면에서
    숨겨야 한다.** 그 경로는 LLM 이 없으면 존재할 수 없고, 백엔드·프론트가 그것을 알 방법이
    이 값 말고는 없다.
    """
    return ApiResponse.ok(
        InternalHealthData(llmActive=settings.llm_active and bool(settings.llm_api_key)),
        message="내부 호출 인증 성공",
    )

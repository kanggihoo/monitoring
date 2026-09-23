"""라우터 레이어 — HTTP 경계.

새 도메인 라우터는 이 패키지에 모듈로 추가하고 `api_router` 에 한 번만 등록한다.
"""

from __future__ import annotations

from fastapi import APIRouter

from app.routers import health, onboarding, reports

# `/api/ai` 접두사가 붙는 도메인 라우터. main.py 가 한 번만 마운트한다.
api_router = APIRouter()
api_router.include_router(health.internal_router)
api_router.include_router(reports.router)
api_router.include_router(onboarding.router)

# 접두사 없이 루트에 붙는 운영용 라우터 (헬스체크 등).
root_router = APIRouter()
root_router.include_router(health.router)

__all__ = ["api_router", "root_router"]

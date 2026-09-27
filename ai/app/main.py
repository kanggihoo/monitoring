"""FastAPI 애플리케이션 조립. 실행 절차는 docs/usage.md 참조."""

from __future__ import annotations

from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from prometheus_fastapi_instrumentator import Instrumentator

from app import __version__
from app.core.config import Settings, get_settings
from app.core.logging import configure_logging, get_logger
from app.core.security import INTERNAL_API_KEY_HEADER
from app.exceptions import AiServiceError, register_exception_handlers
from app.routers import api_router, root_router
from app.services.headline import load_prompt
from app.services.llm import LlmClient

logger = get_logger(__name__)


def warn_if_prompt_unreadable(settings: Settings) -> None:
    """LLM 을 켰으면 프롬프트를 기동에 한 번 읽어 본다.

    **기동을 막지는 않는다.** 헤드라인에는 블록 문장이라는 대안이 있어서, 프롬프트 하나 때문에
    컨테이너를 못 뜨게 하면 리포트 발급 전체를 잃는다 — 얻는 것보다 잃는 것이 크다.

    그래도 기동에서 한 번 읽는 이유는 **발견 시점을 옮기기 위해서다.** 이 검사가 없으면
    설정 실수가 폐장 직후에야 드러나고, 그때는 발표까지 20분뿐이라 손댈 사람이 없다.
    실제로 `prompts/` 가 이미지에 없던 적이 있고, 소스 트리에는 있어서 로컬에서 보이지 않았다.
    """
    if not settings.llm_active:
        return
    try:
        load_prompt()
    except AiServiceError as exc:
        logger.error("프롬프트를 읽지 못했다 — 헤드라인은 블록 문장으로 나간다: %s", exc)


@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncIterator[None]:
    settings: Settings = app.state.settings
    logger.info("AI 서비스 기동 (env=%s, prefix=%s)", settings.env, settings.api_prefix)
    if not settings.internal_api_key:
        logger.warning(
            "AI_INTERNAL_API_KEY 미설정 — %s 검증이 비활성화된 상태로 뜬다 (local 전용)",
            INTERNAL_API_KEY_HEADER,
        )
    # 키가 없으면 조용히 꺼진 채로 뜬다. 헤드라인은 블록 문장이라는 대안이 있다.
    await app.state.llm.start()
    warn_if_prompt_unreadable(settings)
    yield
    await app.state.llm.aclose()
    logger.info("AI 서비스 종료")


def create_app(settings: Settings | None = None) -> FastAPI:
    settings = settings or get_settings()
    configure_logging(settings.log_level, settings.log_format)

    app = FastAPI(
        title="S15P21A605 AI Service",
        version=__version__,
        summary="Spring 백엔드가 호출하는 내부 AI 서비스",
        lifespan=lifespan,
        docs_url="/docs",
        redoc_url=None,
        openapi_url="/openapi.json",
    )
    app.state.settings = settings
    app.state.llm = LlmClient(settings)

    # 내부 서비스라 기본값은 비어 있고, 그때는 미들웨어를 아예 붙이지 않는다.
    # 프론트엔드가 직접 호출하게 되면 AI_CORS_ALLOW_ORIGINS 에 origin 을 채운다.
    if settings.cors_allow_origins:
        logger.info("CORS 허용 origin: %s", ", ".join(settings.cors_allow_origins))
        app.add_middleware(
            CORSMiddleware,
            allow_origins=settings.cors_allow_origins,
            allow_credentials=True,
            allow_methods=["*"],
            allow_headers=["*"],
        )

    register_exception_handlers(app)
    app.include_router(root_router)  # GET /health — 접두사 없음
    app.include_router(api_router, prefix=settings.api_prefix)

    # GET /metrics — 요청 수·지연(http_request_duration_seconds 등). 루트 compose 의
    # Alloy 가 긁어 간다. 헬스체크와 자기 자신은 세지 않는다.
    # 문서(openapi)에는 싣지 않는다 — 내부 운영용 경로다.
    # excluded_handlers 는 정규식이다 — 앵커가 없으면 /api/ai/health/internal 까지 걸린다.
    Instrumentator(excluded_handlers=["^/health$", "^/metrics$"]).instrument(app).expose(
        app, include_in_schema=False
    )
    return app


app = create_app()


def main() -> None:
    """한 줄 기동 진입점 — `uv run python -m app.main`."""
    import uvicorn

    settings = get_settings()
    uvicorn.run(
        "app.main:app",
        host=settings.host,
        port=settings.port,
        reload=settings.is_local,
        log_config=None,  # configure_logging 이 이미 잡았다.
    )


if __name__ == "__main__":
    main()

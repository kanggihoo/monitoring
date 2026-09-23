"""도메인 예외와 전역 예외 핸들러.

라우터 안에서 `try/except` 로 응답을 만들지 않는다. 예외를 던지고 여기서 한 곳에서 변환한다.
상태 코드 의미는 백엔드 `conventions/api.md` "에러 식별" 표를 따른다.
"""

from __future__ import annotations

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from starlette.exceptions import HTTPException as StarletteHTTPException

from app.core.logging import get_logger
from app.schemas.common import ErrorResponse, FieldError

logger = get_logger(__name__)


class AiServiceError(Exception):
    """이 서비스가 의도적으로 던지는 예외의 기반. 상태 코드와 사용자에게 보일 메시지를 갖는다."""

    status_code: int = 500
    message: str = "요청을 처리하지 못했습니다."

    def __init__(self, message: str | None = None) -> None:
        super().__init__(message or self.message)
        if message:
            self.message = message


class UnauthorizedError(AiServiceError):
    """인증 실패 — 내부 호출 키 누락/불일치."""

    status_code = 401
    message = "인증에 실패했습니다."


class UpstreamError(AiServiceError):
    """외부 모델·API 연동 실패."""

    status_code = 502
    message = "외부 연동에 실패했습니다."


class ServiceUnavailableError(AiServiceError):
    """일시적 인프라 장애. 애플리케이션 버그(500)와 구분한다."""

    status_code = 503
    message = "일시적으로 요청을 처리할 수 없습니다."


def _json(error: ErrorResponse) -> JSONResponse:
    return JSONResponse(
        status_code=error.status,
        content=error.model_dump(mode="json", by_alias=True),
    )


def register_exception_handlers(app: FastAPI) -> None:
    @app.exception_handler(AiServiceError)
    async def _handle_service_error(request: Request, exc: AiServiceError) -> JSONResponse:
        logger.warning("%s: %s (%s)", type(exc).__name__, exc.message, request.url.path)
        return _json(ErrorResponse.of(exc.status_code, exc.message, path=request.url.path))

    @app.exception_handler(RequestValidationError)
    async def _handle_validation_error(
        request: Request, exc: RequestValidationError
    ) -> JSONResponse:
        errors = [
            FieldError(
                # loc 은 ("body", "field", ...) 형태다. 위치 접두사를 떼고 필드 경로만 남긴다.
                field=".".join(str(part) for part in error["loc"][1:]) or str(error["loc"][0]),
                rejected_value=error.get("input"),
                reason=error["msg"],
            )
            for error in exc.errors()
        ]
        return _json(
            ErrorResponse.of(
                400, "입력값이 올바르지 않습니다.", path=request.url.path, errors=errors
            )
        )

    @app.exception_handler(StarletteHTTPException)
    async def _handle_http_error(request: Request, exc: StarletteHTTPException) -> JSONResponse:
        # 404 등 프레임워크가 직접 던지는 것도 같은 응답 모양으로 맞춘다.
        return _json(ErrorResponse.of(exc.status_code, str(exc.detail), path=request.url.path))

    @app.exception_handler(Exception)
    async def _handle_unexpected(request: Request, exc: Exception) -> JSONResponse:
        # 원인은 서버 로그에만 남기고, 응답에는 내부 구현 정보를 노출하지 않는다.
        logger.exception("처리하지 못한 예외 (%s)", request.url.path)
        return _json(ErrorResponse.of(500, "서버 내부 오류가 발생했습니다.", path=request.url.path))

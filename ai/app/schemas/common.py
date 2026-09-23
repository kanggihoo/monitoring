"""공통 응답 객체.

필드 구성은 백엔드 `conventions/code.md` §6 의 응답 체계를 그대로 맞춘다.
Spring 쪽에서 같은 DTO 모양으로 역직렬화할 수 있게 JSON 키는 camelCase 로 낸다.
"""

from __future__ import annotations

from datetime import UTC, datetime
from typing import Any, Generic, TypeVar

from pydantic import BaseModel, ConfigDict
from pydantic.alias_generators import to_camel

T = TypeVar("T")


def _now() -> datetime:
    return datetime.now(UTC)


class CamelModel(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)


class ApiResponse(CamelModel, Generic[T]):
    """성공 응답 — `success`, `status`, `message`, `data`, `timestamp`."""

    success: bool = True
    status: int = 200
    message: str
    data: T | None = None
    timestamp: datetime

    @classmethod
    def ok(cls, data: T | None = None, message: str = "OK", status: int = 200) -> ApiResponse[T]:
        return cls(success=True, status=status, message=message, data=data, timestamp=_now())


class FieldError(CamelModel):
    """검증 실패 상세 — JSON 으로는 `{ field, rejectedValue, reason }`."""

    field: str
    rejected_value: Any | None = None
    reason: str


class ErrorResponse(CamelModel):
    """실패 응답 — `success`, `status`, `message`, `errors`, `path`, `timestamp`."""

    success: bool = False
    status: int
    message: str
    errors: list[FieldError] | None = None
    path: str | None = None
    timestamp: datetime

    @classmethod
    def of(
        cls,
        status: int,
        message: str,
        *,
        path: str | None = None,
        errors: list[FieldError] | None = None,
    ) -> ErrorResponse:
        return cls(
            success=False,
            status=status,
            message=message,
            errors=errors,
            path=path,
            timestamp=_now(),
        )

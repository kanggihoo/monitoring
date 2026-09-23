"""내부 호출 인증.

이 서비스는 Spring 백엔드만 호출하는 내부 서비스다. 사용자 JWT 를 검증하지 않고,
서비스 간 공유 시크릿(`X-Internal-Api-Key`)만 확인한다.
"""

from __future__ import annotations

import secrets
from typing import Annotated

from fastapi import Depends, Header

from app.core.config import Settings, get_settings
from app.core.logging import get_logger
from app.exceptions import UnauthorizedError

logger = get_logger(__name__)

INTERNAL_API_KEY_HEADER = "X-Internal-Api-Key"


def require_internal_key(
    settings: Annotated[Settings, Depends(get_settings)],
    x_internal_api_key: Annotated[str | None, Header(alias=INTERNAL_API_KEY_HEADER)] = None,
) -> None:
    """내부 호출 키를 검증한다.

    라우터 단위로 `dependencies=[Depends(require_internal_key)]` 를 붙여 쓴다.
    """
    if not settings.internal_api_key:
        # local 전용 우회. dev/prod 는 Settings 검증에서 기동이 막히므로 여기 도달하지 않는다.
        logger.warning(
            "%s 미설정 — 내부 호출 검증을 건너뛴다 (local 전용)", INTERNAL_API_KEY_HEADER
        )
        return

    # 타이밍 공격을 피하려고 문자열을 `==` 로 비교하지 않는다.
    if x_internal_api_key is None or not secrets.compare_digest(
        x_internal_api_key, settings.internal_api_key
    ):
        # 키 값 자체는 로그에 남기지 않는다.
        logger.warning("내부 호출 키 불일치 또는 누락")
        raise UnauthorizedError("내부 호출 인증에 실패했습니다.")

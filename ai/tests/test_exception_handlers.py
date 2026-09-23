"""전역 예외 핸들러가 모든 실패를 같은 응답 봉투로 변환하는지 검증한다."""

from __future__ import annotations

from collections.abc import Iterator

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient
from pydantic import BaseModel

from app.core.config import Settings
from app.exceptions import ServiceUnavailableError
from app.main import create_app


class _Body(BaseModel):
    count: int


@pytest.fixture
def probe_client(settings: Settings) -> Iterator[TestClient]:
    """핸들러 검증용 라우트를 임시로 붙인 앱. 프로덕션 라우터를 오염시키지 않는다."""
    app: FastAPI = create_app(settings)

    @app.post("/probe/validate")
    async def _validate(body: _Body) -> dict[str, int]:
        return {"count": body.count}

    @app.get("/probe/unavailable")
    async def _unavailable() -> None:
        raise ServiceUnavailableError

    @app.get("/probe/boom")
    async def _boom() -> None:
        raise RuntimeError("내부 구현 정보")

    with TestClient(app, raise_server_exceptions=False) as client:
        yield client


def test_validation_failure_returns_400_with_field_errors(probe_client: TestClient) -> None:
    """검증실패_잘못된타입_400과필드에러배열을반환한다"""
    response = probe_client.post("/probe/validate", json={"count": "숫자아님"})

    assert response.status_code == 400
    body = response.json()
    assert body["success"] is False
    assert body["errors"][0]["field"] == "count"
    assert body["errors"][0]["rejectedValue"] == "숫자아님"
    assert body["errors"][0]["reason"]


def test_service_error_maps_to_its_status(probe_client: TestClient) -> None:
    """서비스예외_ServiceUnavailable_503을반환한다"""
    response = probe_client.get("/probe/unavailable")

    assert response.status_code == 503
    assert response.json()["status"] == 503


def test_unexpected_error_hides_internal_detail(probe_client: TestClient) -> None:
    """처리못한예외_500_내부구현정보를노출하지않는다"""
    response = probe_client.get("/probe/boom")

    assert response.status_code == 500
    assert "내부 구현 정보" not in response.text
    assert response.json()["success"] is False


def test_unknown_path_returns_error_envelope(probe_client: TestClient) -> None:
    """없는경로_404_실패응답봉투를반환한다"""
    response = probe_client.get("/probe/does-not-exist")

    assert response.status_code == 404
    assert response.json()["success"] is False

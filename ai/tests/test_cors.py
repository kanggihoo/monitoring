"""CORS 미들웨어가 설정값에 따라만 붙는지 검증한다."""

from __future__ import annotations

from fastapi.testclient import TestClient

from app.core.config import Settings
from app.main import create_app

ORIGIN = "http://localhost:3000"


def test_cors_headers_absent_when_origins_empty(client: TestClient) -> None:
    """CORS_허용origin없음_응답에CORS헤더가없다"""
    response = client.get("/health", headers={"Origin": ORIGIN})

    assert response.status_code == 200
    assert "access-control-allow-origin" not in response.headers


def test_cors_headers_present_when_origin_allowed() -> None:
    """CORS_허용origin설정_응답에CORS헤더가붙는다"""
    settings = Settings(_env_file=None, env="local", cors_allow_origins=[ORIGIN])

    with TestClient(create_app(settings)) as client:
        response = client.get("/health", headers={"Origin": ORIGIN})

    assert response.status_code == 200
    assert response.headers["access-control-allow-origin"] == ORIGIN

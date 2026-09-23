from __future__ import annotations

from fastapi.testclient import TestClient

from app.core.security import INTERNAL_API_KEY_HEADER
from tests.conftest import TEST_INTERNAL_KEY

HEALTH = "/health"
INTERNAL_HEALTH = "/api/ai/health/internal"


def test_health_returns_success_envelope(client: TestClient) -> None:
    """헬스체크_인증없이호출_성공응답봉투를반환한다"""
    response = client.get(HEALTH)

    assert response.status_code == 200
    body = response.json()
    assert body["success"] is True
    assert body["status"] == 200
    assert body["data"]["env"] == "local"
    assert body["timestamp"]


def test_health_is_mounted_without_api_prefix(client: TestClient) -> None:
    """헬스체크_접두사경로로호출_404를반환한다"""
    # `/health` 는 접두사 밖에 있다. 접두사를 붙인 경로는 존재하지 않아야 한다.
    assert client.get("/api/ai/health").status_code == 404


def test_docs_and_openapi_are_reachable(client: TestClient) -> None:
    """문서_Swagger와OpenAPI스키마_200을반환한다"""
    assert client.get("/docs").status_code == 200

    schema = client.get("/openapi.json")
    assert schema.status_code == 200
    assert HEALTH in schema.json()["paths"]


def test_internal_health_without_key_returns_401(client: TestClient) -> None:
    """내부헬스체크_키누락_401과실패응답봉투를반환한다"""
    response = client.get(INTERNAL_HEALTH)

    assert response.status_code == 401
    body = response.json()
    assert body["success"] is False
    assert body["status"] == 401
    assert body["path"] == INTERNAL_HEALTH


def test_internal_health_with_wrong_key_returns_401(client: TestClient) -> None:
    """내부헬스체크_키불일치_401을반환한다"""
    response = client.get(INTERNAL_HEALTH, headers={INTERNAL_API_KEY_HEADER: "wrong-key"})

    assert response.status_code == 401


def test_internal_health_with_valid_key_returns_200(client: TestClient) -> None:
    """내부헬스체크_키일치_200을반환한다"""
    response = client.get(INTERNAL_HEALTH, headers={INTERNAL_API_KEY_HEADER: TEST_INTERNAL_KEY})

    assert response.status_code == 200
    assert response.json()["success"] is True

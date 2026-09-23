"""설정 로딩 규칙 검증. `.env` 이름이 어긋나거나 가드가 풀리면 배포에서 터진다."""

from __future__ import annotations

import pytest
from pydantic import ValidationError

from app.core.config import Settings


def test_internal_key_missing_outside_local_blocks_startup() -> None:
    """설정검증_dev환경에키없음_기동을차단한다"""
    with pytest.raises(ValidationError):
        Settings(_env_file=None, env="dev", internal_api_key="")


def test_internal_key_missing_in_local_is_allowed() -> None:
    """설정검증_local환경에키없음_기동을허용한다"""
    settings = Settings(_env_file=None, env="local", internal_api_key="")

    assert settings.is_local is True
    assert settings.internal_api_key == ""


def test_cors_origins_accepts_comma_separated_string() -> None:
    """CORS설정_콤마구분문자열_리스트로파싱한다"""
    settings = Settings(
        _env_file=None,
        cors_allow_origins="http://localhost:3000, https://example.com",
    )

    assert settings.cors_allow_origins == ["http://localhost:3000", "https://example.com"]


def test_cors_origins_default_is_empty() -> None:
    """CORS설정_미지정_빈리스트로둔다"""
    # 비어 있으면 main.py 가 CORS 미들웨어를 아예 붙이지 않는다.
    assert Settings(_env_file=None).cors_allow_origins == []


def test_env_file_with_empty_cors_value_loads(tmp_path) -> None:
    """설정로딩_env파일의CORS값이빈문자열_기동에성공한다"""
    # 회귀 테스트. `.env.example` 을 그대로 복사하면 `AI_CORS_ALLOW_ORIGINS=` 가 빈 값으로
    # 들어오는데, pydantic-settings 가 list 필드를 JSON 으로 선파싱해 SettingsError 로
    # 죽었다. `_env_file=None` 로 도는 다른 테스트들은 이 경로를 타지 않아 놓쳤다.
    env_file = tmp_path / ".env"
    env_file.write_text("AI_ENV=local\nAI_CORS_ALLOW_ORIGINS=\nAI_MODEL_DIR=\n", encoding="utf-8")

    settings = Settings(_env_file=str(env_file))

    assert settings.cors_allow_origins == []
    assert settings.model_dir is None


def test_env_file_with_comma_separated_cors_value_loads(tmp_path) -> None:
    """설정로딩_env파일의CORS값이콤마구분_리스트로파싱한다"""
    env_file = tmp_path / ".env"
    env_file.write_text(
        "AI_CORS_ALLOW_ORIGINS=http://localhost:3000,https://example.com\n", encoding="utf-8"
    )

    settings = Settings(_env_file=str(env_file))

    assert settings.cors_allow_origins == ["http://localhost:3000", "https://example.com"]


def test_llm_is_off_by_default() -> None:
    """LLM설정_키미설정_비활성으로둔다"""
    settings = Settings(_env_file=None)

    assert settings.llm_active is False


def test_llm_turns_on_with_key_alone() -> None:
    """LLM설정_키만설정_스위치없이도활성화된다"""
    # 인계 문서의 요구다 — 키만 넣으면 헤드라인 생성이 돌아야 한다.
    settings = Settings(_env_file=None, llm_api_key="gms-key")

    assert settings.llm_active is True


def test_llm_explicit_false_wins_over_key() -> None:
    """LLM설정_키가있고스위치가false_비활성으로둔다"""
    settings = Settings(_env_file=None, llm_api_key="gms-key", llm_enabled=False)

    assert settings.llm_active is False


def test_llm_explicit_true_without_key_blocks_startup() -> None:
    """LLM설정_스위치만true이고키없음_기동을차단한다"""
    # 켜겠다고 적어두고 키가 비면 의도와 결과가 어긋난다. 키 이름 오타도 여기 걸린다.
    with pytest.raises(ValidationError):
        Settings(_env_file=None, llm_enabled=True, llm_api_key="")


def test_llm_defaults_point_at_gms_gateway() -> None:
    """LLM설정_기본값_GMS게이트웨이와경량모델을가리킨다"""
    settings = Settings(_env_file=None)

    assert settings.llm_base_url == "https://gms.ssafy.io/gmsapi/api.openai.com/v1"
    assert settings.llm_model == "gpt-5-mini"


def test_env_file_with_empty_llm_values_loads(tmp_path) -> None:
    """설정로딩_env파일의LLM값이빈문자열_기동에성공한다"""
    # `.env.example` 을 그대로 복사하면 두 키가 빈 값으로 들어온다. bool 필드가 빈 문자열을
    # 만나면 죽지 않는지 본다 (CORS 필드에서 같은 사고가 있었다).
    env_file = tmp_path / ".env"
    env_file.write_text("AI_LLM_API_KEY=\nAI_LLM_ENABLED=\n", encoding="utf-8")

    settings = Settings(_env_file=str(env_file))

    assert settings.llm_active is False

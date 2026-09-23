"""설정값 로딩. 환경변수 접두사는 `AI_` 로 고정한다 (모노레포에서 다른 컨텍스트와 충돌 방지)."""

from __future__ import annotations

from functools import lru_cache
from pathlib import Path
from typing import Annotated, Literal

from pydantic import Field, field_validator, model_validator
from pydantic_settings import BaseSettings, NoDecode, SettingsConfigDict

Env = Literal["local", "dev", "prod"]


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        env_prefix="AI_",
        extra="ignore",
    )

    # --- .env 로 주입되는 값 (비밀값 · 환경마다 다른 값) ---
    env: Env = "local"
    host: str = "0.0.0.0"  # noqa: S104 — 컨테이너 안에서 외부 바인딩이 필요하다
    port: int = 8000
    log_level: str = "INFO"
    internal_api_key: str = ""

    #: 비우면 CORS 미들웨어를 아예 붙이지 않는다. 내부 서비스의 기본값이다.
    #: `NoDecode` — 아래 `_split_origins` 주석 참조.
    cors_allow_origins: Annotated[list[str], NoDecode] = Field(default_factory=list)

    #: 모델 가중치를 읽을 로컬 디렉터리. 가중치는 저장소에 커밋하지 않는다.
    model_dir: Path | None = None

    #: SSAFY GMS 게이트웨이 키. 헤드라인 생성의 유일한 필수 입력이다.
    #: 토큰 사용량만큼 크레딧이 차감되므로 로그·응답 어디에도 남기지 않는다.
    llm_api_key: str = ""

    #: 헤드라인을 LLM 으로 생성할지. `None` 이면 **키 유무로 정한다** — 키만 넣으면 켜진다.
    #: `true`/`false` 로 적으면 키와 무관하게 그 값이 이긴다. 명시적으로 켜 두면 키가 빠졌을 때
    #: 조용히 블록 문장으로 돌아가지 않고 기동에서 걸린다 (아래 검증).
    llm_enabled: bool | None = None

    # --- 환경과 무관한 기본값 (외부화하지 않는다) ---
    api_prefix: str = "/api/ai"
    upstream_timeout_seconds: float = Field(default=30.0, gt=0)

    #: GMS 게이트웨이 주소. 비밀이 아니고 환경마다 달라지지 않아 기본값으로 둔다.
    #: 필드가 있으므로 필요하면 `AI_LLM_BASE_URL` 로 덮을 수 있다.
    llm_base_url: str = "https://gms.ssafy.io/gmsapi/api.openai.com/v1"

    #: 목업 비교에서 `gpt-5-nano` 는 문장이 깨지는 일이 있었고 `gpt-5-mini` 는 없었다.
    #: 단가가 약 5배지만 시즌 한 번에 크레딧 수십 수준이라 품질을 택했다.
    llm_model: str = "gpt-5-mini"

    #: 한 호출을 기다리는 한도(초). 폐장~발표 예산이 20분이라 길게 잡을 수 없다.
    llm_timeout_seconds: float = Field(default=15.0, gt=0)

    #: 동시에 열어둘 호출 수. 계정 수만큼 호출하므로 상한이 없으면 429 로 무너진다.
    llm_max_concurrency: int = Field(default=8, ge=1)

    #: 추론 토큰이 이 한도를 함께 쓴다. 빠듯하게 잡으면 본문이 빈 응답이 돌아온다.
    #: 헤드라인은 한 문장이라 512 로 충분했는데, 시즌 서술은 JSON 두 필드(두 문장 + 한 문장)라
    #: 올려 잡았다. 실측 뒤에 다시 조정한다 (명세 15절 AI-12).
    llm_max_completion_tokens: int = Field(default=1024, ge=64)

    #: 헤드라인은 판정이 끝난 사실을 한 문장으로 옮기는 일이라 추론이 필요 없다.
    llm_reasoning_effort: Literal["minimal", "low", "medium", "high"] = "minimal"

    @property
    def is_local(self) -> bool:
        return self.env == "local"

    @property
    def llm_active(self) -> bool:
        """헤드라인 LLM 을 실제로 쓸지. `llm_enabled` 를 안 적었으면 키 유무가 곧 스위치다."""
        if self.llm_enabled is None:
            return bool(self.llm_api_key)
        return self.llm_enabled

    @field_validator("cors_allow_origins", mode="before")
    @classmethod
    def _split_origins(cls, value: object) -> object:
        """`.env` 에서 콤마 구분 문자열로 받는다.

        `NoDecode` 가 반드시 함께 있어야 한다. pydantic-settings 는 list 필드를 만나면
        이 validator 보다 **먼저** `.env` 값을 JSON 으로 파싱하려 든다. 그래서
        `AI_CORS_ALLOW_ORIGINS=` (빈 값)만 있어도 SettingsError 로 기동이 죽는다 (실측).
        `NoDecode` 로 그 선파싱을 끄고 원문 문자열을 여기서 직접 쪼갠다.
        """
        if isinstance(value, str):
            return [origin.strip() for origin in value.split(",") if origin.strip()]
        return value

    @field_validator("llm_enabled", mode="before")
    @classmethod
    def _empty_switch_is_none(cls, value: object) -> object:
        """빈 문자열을 `None`(= 키 유무로 결정) 으로 만든다.

        `.env.example` 을 복사하면 `AI_LLM_ENABLED=` 가 빈 값으로 들어온다. 그대로 두면
        pydantic 이 bool 파싱에 실패해 **기동이 죽는다** (실측). `AI_CORS_ALLOW_ORIGINS` 에서
        겪은 것과 같은 모양의 사고다.
        """
        if isinstance(value, str) and not value.strip():
            return None
        return value

    @field_validator("model_dir", mode="before")
    @classmethod
    def _empty_path_is_none(cls, value: object) -> object:
        """빈 문자열을 `None` 으로 만든다.

        `.env.example` 을 복사하면 `AI_MODEL_DIR=` 가 빈 값으로 들어온다. 그대로 두면
        pydantic 이 `Path("")` → `Path(".")` 로 바꿔서, "미설정"이 "현재 디렉터리"가 된다.
        `if settings.model_dir:` 가 참이 되어 앱 루트에서 가중치를 찾게 된다 (실측).
        """
        if isinstance(value, str) and not value.strip():
            return None
        return value

    @model_validator(mode="after")
    def _require_internal_key_outside_local(self) -> Settings:
        """local 을 벗어나면 내부 호출 키 없이 기동하지 못하게 막는다.

        키가 비어 있으면 인증이 사실상 열린다. 로컬에서는 편의를 위해 허용하되(기동 시 WARNING),
        dev/prod 에서는 기동 자체를 실패시켜 조용히 열린 채로 배포되는 것을 막는다.
        """
        if self.env != "local" and not self.internal_api_key:
            raise ValueError("AI_INTERNAL_API_KEY 는 env != local 에서 필수다")
        return self

    @model_validator(mode="after")
    def _require_llm_key_when_explicitly_enabled(self) -> Settings:
        """`AI_LLM_ENABLED=true` 인데 키가 없으면 기동을 막는다.

        키만 넣는 쪽(기본값)은 키가 없으면 그냥 꺼진 상태로 뜨는 게 맞다. 반대로 켜겠다고
        적어 두고 키가 비어 있으면 의도와 결과가 어긋난 상태이므로, 헤드라인이 조용히
        블록 문장으로 돌아가는 대신 여기서 걸리게 한다. 키 이름 오타도 같은 그물에 걸린다.
        """
        if self.llm_enabled and not self.llm_api_key:
            raise ValueError("AI_LLM_API_KEY 는 AI_LLM_ENABLED=true 에서 필수다")
        return self


@lru_cache
def get_settings() -> Settings:
    return Settings()

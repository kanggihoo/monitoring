# syntax=docker/dockerfile:1

# --- builder: 의존성만 먼저 설치해서 레이어 캐시를 살린다 ---
FROM python:3.11-slim AS builder

COPY --from=ghcr.io/astral-sh/uv:0.12.5 /uv /uvx /usr/local/bin/

ENV UV_COMPILE_BYTECODE=1 \
    UV_LINK_MODE=copy \
    UV_PROJECT_ENVIRONMENT=/opt/venv

WORKDIR /build

# 락 파일 기준으로만 설치한다. --frozen: 락을 갱신하지 않고, 어긋나면 빌드를 실패시킨다.
COPY pyproject.toml uv.lock ./
RUN uv sync --frozen --no-dev

# --- runtime ---
FROM python:3.11-slim AS runtime

ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    PATH="/opt/venv/bin:$PATH"

# root 로 돌리지 않는다.
RUN useradd --create-home --uid 10001 appuser

COPY --from=builder /opt/venv /opt/venv

WORKDIR /app
COPY --chown=appuser:appuser app ./app
# 리포트 문구 블록. app/services/report_blocks.py 가 런타임에 읽으므로 이미지에 들어가야 한다.
# 빼면 조립 단계에서 FileNotFoundError 로 죽는다.
COPY --chown=appuser:appuser data ./data
# 헤드라인 프롬프트. **런타임에 읽는다** — app/services/headline.py 의 PROMPT_PATH 가
# /app/prompts/daily-headline.md 를 가리킨다. 예전에 "블록을 만들 때만 쓴다"고 보고 빼 두었는데,
# 그래서 LLM 을 켠 컨테이너에서 일일 발급이 500 으로 죽었다. 소스 트리에는 있으니 로컬에서는
# 드러나지 않는 종류의 사고다 — tests/test_image_resources.py 가 이제 지킨다.
COPY --chown=appuser:appuser prompts ./prompts

USER appuser
EXPOSE 8000

# slim 이미지에 curl 이 없다. 표준 라이브러리로 찌른다.
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD python -c "import urllib.request,sys; sys.exit(0 if urllib.request.urlopen('http://127.0.0.1:8000/health', timeout=3).status == 200 else 1)"

# reload 없이 뜬다 (reload 는 local 전용).
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]

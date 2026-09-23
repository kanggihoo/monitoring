"""런타임에 읽는 파일이 이미지에 들어가는지 본다.

**로컬에서는 드러나지 않는 부류의 사고를 잡는 자리다.** 소스 트리에는 파일이 다 있으므로
`pytest` 도 프리뷰 스크립트도 통과한다. 그런데 `Dockerfile` 이 그 디렉터리를 COPY 하지 않으면
컨테이너에서만 죽는다 — 실제로 `prompts/` 가 그랬고(S15P21A605-200), LLM 을 켠 dev·prod 에서
일일 발급이 500 으로 죽을 상태였다.

코드가 `parents[2]`(= 컨테이너의 `/app`) 기준으로 경로를 잡으므로, 그 아래를 가리키는 상수가
생기면 여기에 한 줄 더한다.
"""

from __future__ import annotations

import re
from pathlib import Path

import pytest

from app.services.headline import PROMPT_PATH
from app.services.report_blocks import BLOCKS_DIR

AI_ROOT = Path(__file__).resolve().parents[1]
DOCKERFILE = AI_ROOT / "Dockerfile"

#: 런타임에 읽는 경로. 상수를 그대로 가져와 검사하므로 코드가 경로를 바꾸면 함께 움직인다.
RUNTIME_PATHS = (PROMPT_PATH, BLOCKS_DIR)


def copied_dirs() -> set[str]:
    """`Dockerfile` 이 이미지에 넣는 최상위 디렉터리 이름.

    `COPY --from=...` 은 빌더 산출물이라 제외한다 — 소스 트리와 무관하다.
    """
    copied: set[str] = set()
    for line in DOCKERFILE.read_text(encoding="utf-8").splitlines():
        stripped = line.strip()
        if not stripped.startswith("COPY") or "--from=" in stripped:
            continue
        # COPY [--chown=...] <src>... <dst>  — 마지막 인자가 목적지다.
        parts = [p for p in stripped.split()[1:] if not p.startswith("--")]
        copied.update(Path(src).parts[0] for src in parts[:-1])
    return copied


@pytest.mark.parametrize("path", RUNTIME_PATHS, ids=lambda p: p.name)
def test_runtime_path_exists_in_source_tree(path: Path) -> None:
    """먼저 소스 트리에 있는지 본다. 없으면 아래 검사가 무의미하다."""
    assert path.exists(), f"런타임에 읽는 경로가 소스 트리에 없다: {path}"


@pytest.mark.parametrize("path", RUNTIME_PATHS, ids=lambda p: p.name)
def test_runtime_path_is_copied_into_the_image(path: Path) -> None:
    """그 경로의 최상위 디렉터리가 `Dockerfile` 의 COPY 목록에 있는지 본다."""
    top = path.relative_to(AI_ROOT).parts[0]
    assert top in copied_dirs(), (
        f"{path.relative_to(AI_ROOT)} 를 런타임에 읽는데 Dockerfile 이 '{top}' 를 "
        f"COPY 하지 않는다. 컨테이너에서만 죽는다 (COPY 목록: {sorted(copied_dirs())})"
    )


def test_dockerignore_keeps_secrets_out() -> None:
    """`.env` 가 이미지에 들어가면 키가 레이어에 굳는다. Dockerfile 이 명시 COPY 라
    지금은 안 들어가지만, 누가 `COPY . .` 로 바꿀 때 이 검사가 남아 있어야 한다."""
    patterns = {
        line.strip()
        for line in (AI_ROOT / ".dockerignore").read_text(encoding="utf-8").splitlines()
        if line.strip() and not line.startswith("#")
    }
    assert ".env" in patterns
    assert ".env.*" in patterns


def test_prompt_file_shape_matches_the_loader() -> None:
    """프롬프트에 코드 블록이 둘이어야 로더가 (지침, 사용자 틀) 로 읽는다.

    문안만 고치려다 블록을 하나 지우면 `PromptError` 가 되고, 그때 헤드라인은 조용히 블록
    문장으로 돌아간다 — 조용하기 때문에 여기서 잡는다.
    """
    fence = re.compile(r"^```[^\n]*\n(.*?)^```", re.MULTILINE | re.DOTALL)
    blocks = fence.findall(PROMPT_PATH.read_text(encoding="utf-8"))
    assert len(blocks) == 2, f"코드 블록이 2개여야 한다 (현재 {len(blocks)}개)"

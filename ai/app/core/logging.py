"""로깅 설정. `print` 대신 표준 `logging` 만 쓴다."""

from __future__ import annotations

import logging
import sys
from logging.config import dictConfig

_FORMAT = "%(asctime)s %(levelname)-8s [%(name)s] %(message)s"


def _force_utf8_stdout() -> None:
    """stdout·stderr 를 UTF-8 로 고정한다.

    Windows 기본 콘솔 코드페이지는 cp949 라서, 한글 로그나 em dash 같은 문자를 쓰는 순간
    StreamHandler 가 UnicodeEncodeError 를 던지고 그 로그 줄이 통째로 사라진다 (실측).
    `backslashreplace` — 표현 못 하는 문자가 있어도 예외로 죽지는 않게 한다.
    """
    for stream in (sys.stdout, sys.stderr):
        reconfigure = getattr(stream, "reconfigure", None)
        if reconfigure is not None:
            reconfigure(encoding="utf-8", errors="backslashreplace")


def configure_logging(level: str) -> None:
    """루트 로거와 uvicorn 로거의 포맷·레벨을 통일한다."""
    _force_utf8_stdout()
    dictConfig(
        {
            "version": 1,
            "disable_existing_loggers": False,
            "formatters": {"default": {"format": _FORMAT}},
            "handlers": {
                "console": {
                    "class": "logging.StreamHandler",
                    "formatter": "default",
                    "stream": "ext://sys.stdout",
                }
            },
            "root": {"handlers": ["console"], "level": level.upper()},
            "loggers": {
                # uvicorn 이 자기 핸들러를 붙이면 같은 줄이 두 번 찍힌다. 루트로 위임한다.
                "uvicorn": {"handlers": [], "propagate": True, "level": level.upper()},
                "uvicorn.error": {"handlers": [], "propagate": True},
                "uvicorn.access": {"handlers": [], "propagate": True},
            },
        }
    )


def get_logger(name: str) -> logging.Logger:
    return logging.getLogger(name)

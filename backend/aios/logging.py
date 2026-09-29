"""日志系统（主规格 §15.3 日志级别：DEBUG / INFO / WARNING / ERROR）。"""

from __future__ import annotations

import logging
import sys

_VALID_LEVELS = {"DEBUG", "INFO", "WARNING", "ERROR", "CRITICAL"}

_FORMAT = "%(asctime)s %(levelname)-8s %(name)s [%(filename)s:%(lineno)d] %(message)s"


def configure_logging(level: str = "INFO") -> None:
    """初始化根 logger。重复调用安全（幂等）。"""
    normalized = (level or "INFO").upper()
    if normalized not in _VALID_LEVELS:
        normalized = "INFO"

    handler = logging.StreamHandler(sys.stdout)
    handler.setFormatter(logging.Formatter(_FORMAT))

    root = logging.getLogger()
    for existing in list(root.handlers):
        root.removeHandler(existing)
    root.addHandler(handler)
    root.setLevel(normalized)

    # 降低三方库噪声，保留自身 INFO 级别
    logging.getLogger("uvicorn.access").setLevel("WARNING")


def get_logger(name: str) -> logging.Logger:
    return logging.getLogger(name)

"""设计稿预览截图：单画板 HTML → headless Chromium → PNG。

浏览器是渲染权威基准（见 design_render），预览图不另写绘制逻辑：把单块画板渲染成
无演示页框的整页 HTML，再让本机 Chromium 按 1:1 像素截图。PNG 只落在本机缓存目录，
不进数据库；同设计稿同版本同画板同倍率复用上一次的结果。
"""

from __future__ import annotations

import asyncio
import contextlib
import hashlib
import os
import re
import shutil
import tempfile
import time
from pathlib import Path
from typing import Any

from flux.logging import get_logger

from .design_render import render_capture_html

logger = get_logger(__name__)

#: 默认 2 倍像素：手机上放大看细节够清楚，体积又不至于太大。
DEFAULT_SCALE = 2.0
MAX_SCALE = 3.0

#: 单次截图上限：正常 1–2 秒，留足冷启动余量。
_CAPTURE_TIMEOUT_SECONDS = 20.0

#: 缓存保留上限：7 天、最多 300 张，避免预览图无限堆积。
_CACHE_TTL_SECONDS = 7 * 24 * 3600
_CACHE_MAX_FILES = 300

_BIN_NAMES = ("chromium", "chromium-browser", "google-chrome", "google-chrome-stable", "chrome")
_EXTRA_BIN_DIRS = (Path("/usr/local/bin"), Path("/usr/bin"), Path("/snap/bin"))

_ID_SAFE_RE = re.compile(r"[^a-zA-Z0-9_-]+")


class PreviewUnavailable(RuntimeError):
    """本机没有可用的 Chromium 或截图进程失败；路由层翻成 503。"""


def find_chromium() -> str | None:
    """找可用的 Chromium：环境变量优先，其次 PATH，最后几个常见 bin 目录。"""
    override = os.environ.get("FLUX_CHROMIUM_BIN")
    if override:
        return override if Path(override).is_file() else None
    for name in _BIN_NAMES:
        found = shutil.which(name)
        if found:
            return found
    for directory in _EXTRA_BIN_DIRS:
        for name in _BIN_NAMES:
            candidate = directory / name
            if candidate.is_file() and os.access(candidate, os.X_OK):
                return str(candidate)
    return None


def default_cache_dir() -> Path:
    """缓存目录：snap 版 Chromium 只能读写自己的用户目录，root 的 ~/.cache 读不到。"""
    snap_common = Path.home() / "snap" / "chromium" / "common"
    base = snap_common if snap_common.is_dir() else Path(tempfile.gettempdir())
    return base / "flux-previews"


async def render_preview_png(
    design: dict[str, Any],
    *,
    screen: str | None = None,
    scale: float = DEFAULT_SCALE,
    refresh: bool = False,
    cache_dir: Path | None = None,
) -> tuple[bytes, dict[str, Any]]:
    """渲染单块画板为 PNG，返回 (PNG 字节, 画板与截图元信息)。

    找不到画板抛 ValueError（调用方当参数错误处理）；截图不可用抛 PreviewUnavailable。
    """
    scale = max(1.0, min(float(scale), MAX_SCALE))
    built = render_capture_html(design, screen=screen)
    if built is None:
        raise ValueError("没有这块画板")
    markup, meta = built
    target_dir = cache_dir or default_cache_dir()
    png_path = _cache_path(target_dir, design, meta, scale, markup)
    if not refresh and png_path.is_file():
        return png_path.read_bytes(), {**meta, "scale": scale, "cached": True}

    executable = find_chromium()
    if executable is None:
        raise PreviewUnavailable("本机没有可用的 Chromium，无法生成预览图")
    png = await _capture(executable, target_dir, markup, meta, scale)
    png_path.parent.mkdir(parents=True, exist_ok=True)
    tmp = png_path.with_name(png_path.name + ".tmp")
    tmp.write_bytes(png)
    # 原子替换：并发请求同一画板时不会读到半张图
    tmp.replace(png_path)
    _prune_cache(target_dir)
    return png, {**meta, "scale": scale, "cached": False}


def _cache_path(
    cache_dir: Path,
    design: dict[str, Any],
    meta: dict[str, Any],
    scale: float,
    markup: str,
) -> Path:
    design_id = _ID_SAFE_RE.sub("-", str(design.get("id") or "design")).strip("-") or "design"
    screen_id = _ID_SAFE_RE.sub("-", str(meta.get("id") or "0")).strip("-") or "0"
    version = design.get("version")
    # 设计稿没有版本号时用渲染内容摘要兜底，避免改稿后永远命中旧图。
    if version:
        version_tag = f"v{version}"
    else:
        version_tag = f"h{hashlib.sha1(markup.encode('utf-8')).hexdigest()[:10]}"
    scale_tag = f"{scale:g}".replace(".", "_")
    return cache_dir / f"{design_id}-{version_tag}-{screen_id}-x{scale_tag}.png"


def _prune_cache(cache_dir: Path) -> None:
    """清理过期或超量的预览图；失败不影响本次出图。"""
    try:
        entries = sorted(
            ((path.stat().st_mtime, path) for path in cache_dir.glob("*.png")),
            reverse=True,
        )
    except OSError:
        return
    now = time.time()
    for index, (mtime, path) in enumerate(entries):
        if index < _CACHE_MAX_FILES and now - mtime <= _CACHE_TTL_SECONDS:
            continue
        with contextlib.suppress(OSError):
            path.unlink()


async def _capture(
    executable: str,
    cache_dir: Path,
    markup: str,
    meta: dict[str, Any],
    scale: float,
) -> bytes:
    cache_dir.mkdir(parents=True, exist_ok=True)
    work = Path(tempfile.mkdtemp(prefix="capture-", dir=cache_dir))
    try:
        html_path = work / "screen.html"
        html_path.write_text(markup, encoding="utf-8")
        png_path = work / "screen.png"
        proc = await asyncio.create_subprocess_exec(
            executable,
            "--headless",
            "--no-sandbox",
            "--disable-gpu",
            "--hide-scrollbars",
            "--no-first-run",
            "--no-default-browser-check",
            "--disable-dev-shm-usage",
            f"--user-data-dir={work / 'profile'}",
            f"--force-device-scale-factor={scale:g}",
            f"--window-size={max(1, int(meta['w']))},{max(1, int(meta['h']))}",
            "--virtual-time-budget=1500",
            f"--screenshot={png_path}",
            html_path.as_uri(),
            stdout=asyncio.subprocess.DEVNULL,
            stderr=asyncio.subprocess.PIPE,
        )
        try:
            _, stderr = await asyncio.wait_for(proc.communicate(), timeout=_CAPTURE_TIMEOUT_SECONDS)
        except asyncio.TimeoutError:
            proc.kill()
            await proc.wait()
            raise PreviewUnavailable("预览截图超时") from None
        if proc.returncode != 0 or not png_path.is_file():
            detail = stderr.decode("utf-8", "replace").strip()[-400:]
            logger.warning("design preview capture failed rc=%s: %s", proc.returncode, detail)
            raise PreviewUnavailable("预览截图失败")
        return png_path.read_bytes()
    finally:
        shutil.rmtree(work, ignore_errors=True)

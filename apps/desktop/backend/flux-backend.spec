# -*- mode: python ; coding: utf-8 -*-
"""Flux 桌面端后端 sidecar 的 PyInstaller 规格（onedir）。

构建：
    cd apps/desktop/backend
    ../../../.venv/bin/pyinstaller --noconfirm --clean flux-backend.spec

产物：apps/desktop/backend/dist/flux-backend/（含可执行文件 flux-backend 与 _internal/）。
Electron 主进程以 <resources>/backend/flux-backend 定位它。

关键点：
- uvicorn 以字符串 factory 动态导入 `flux.main:create_app`，静态分析发现不了，
  因此必须显式 collect_submodules("flux") 并把 flux 包内数据（manifests/*.yaml）收进来；
- alembic 运行时从文件系统读取 migrations/env.py 与 versions/*.py，故作为 datas 收集；
- SQLAlchemy 异步方言（sqlite+aiosqlite）由 URL 字符串动态装载，需 hiddenimports。
"""

import sys
from pathlib import Path

from PyInstaller.utils.hooks import collect_data_files, collect_submodules

# SPECPATH 由 PyInstaller 注入，指向本 spec 所在目录（apps/desktop/backend）
DESKTOP_BACKEND = Path(SPECPATH).resolve()
REPO_ROOT = DESKTOP_BACKEND.parents[2]
BACKEND = REPO_ROOT / "backend"

# collect_submodules / collect_data_files 在 spec 顶层执行，此时 Analysis 的 pathex
# 尚未生效，必须自己先把 backend 加进 sys.path，否则收集不到 flux 子模块。
if str(BACKEND) not in sys.path:
    sys.path.insert(0, str(BACKEND))

datas = [
    (str(BACKEND / "migrations"), "migrations"),
    (str(BACKEND / "alembic.ini"), "."),
]
# flux 包内的非 .py 资源（agent manifests / yaml 等）
datas += collect_data_files("flux", includes=["**/*.yaml", "**/*.yml", "**/*.json", "**/*.mako"])

hiddenimports = [
    # SQLAlchemy 异步 SQLite 方言：由 "sqlite+aiosqlite:///..." 动态装载
    "aiosqlite",
    "sqlalchemy.dialects.sqlite.aiosqlite",
    # SQLAlchemy asyncio 依赖的协程调度扩展
    "greenlet",
    # alembic 运行时入口（serve.py 里显式 import，此处再兜底）
    "alembic.command",
    "alembic.config",
    "alembic.runtime.migration",
    # uvicorn 的可选组件按字符串懒加载，冻结后需显式声明
    "uvicorn.logging",
    "uvicorn.loops.auto",
    "uvicorn.protocols.http.auto",
    "uvicorn.protocols.http.h11_impl",
    "uvicorn.protocols.websockets.auto",
    "uvicorn.lifespan.on",
    "asyncio",
]
# flux 整包：uvicorn 字符串导入 + 包内 importlib 动态导入都靠它
hiddenimports += collect_submodules("flux")

a = Analysis(
    ["serve.py"],
    pathex=[str(BACKEND), str(DESKTOP_BACKEND)],
    binaries=[],
    datas=datas,
    hiddenimports=hiddenimports,
    hookspath=[],
    hooksconfig={},
    runtime_hooks=[],
    excludes=[],
    noarchive=False,
)
pyz = PYZ(a.pure)

exe = EXE(
    pyz,
    a.scripts,
    [],
    exclude_binaries=True,
    name="flux-backend",
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=False,
    console=True,
    disable_windowed_traceback=False,
)
coll = COLLECT(
    exe,
    a.binaries,
    a.datas,
    strip=False,
    upx=False,
    name="flux-backend",
)

"""Flux 桌面端后端 sidecar 入口。

桌面端（Electron）主进程以「随机本地端口」拉起本进程：先跑 alembic 迁移到 head，
再在本机回环地址上以 factory 模式启动 uvicorn（`flux.main:create_app`）。

冻结（PyInstaller onedir）后被 Electron 直接执行；未冻结时也可在开发机上直接运行：

    PYTHONPATH=backend .venv/bin/python apps/desktop/backend/serve.py --port 8799

数据目录约定（与 Electron 主进程一致）：默认 ``~/.flux``，可用 ``FLUX_DATA_DIR`` 覆盖；
数据库、工作区、备份都落在该目录下，卸载不删除，重装可继续使用。
"""

from __future__ import annotations

import argparse
import os
import sys
from pathlib import Path

DEFAULT_PORT = 8787


def _resource_root() -> Path:
    """定位打包进来的 ``migrations/`` 与 ``alembic.ini`` 所在目录。

    - 冻结态：PyInstaller 把 datas 解到 ``sys._MEIPASS``（onedir 下即 ``_internal/``）；
    - 源码态：仓库的 ``backend/`` 目录（serve.py 位于 ``apps/desktop/backend/``）。
    """
    if getattr(sys, "frozen", False):
        return Path(getattr(sys, "_MEIPASS"))
    return Path(__file__).resolve().parents[3] / "backend"


def _data_dir() -> Path:
    override = os.environ.get("FLUX_DATA_DIR")
    data_dir = Path(override).expanduser() if override else Path.home() / ".flux"
    data_dir.mkdir(parents=True, exist_ok=True)
    # 与主进程约定一致：工作区根与备份目录在启动时就建好
    (data_dir / "workspace").mkdir(exist_ok=True)
    (data_dir / "backups").mkdir(exist_ok=True)
    return data_dir


def _export_env(data_dir: Path) -> None:
    """写入后端所需的 FLUX_* 环境变量（已存在则不覆盖，便于外部显式指定）。"""
    db_path = data_dir / "flux.db"
    os.environ.setdefault("FLUX_DATA_DIR", str(data_dir))
    os.environ.setdefault("FLUX_DATABASE_URL", f"sqlite+aiosqlite:///{db_path}")
    os.environ.setdefault("FLUX_WORKSPACE_ROOT", str(data_dir / "workspace"))
    # DSH 落盘目录同样放在用户数据目录下，避免写进安装目录（卸载即失）
    os.environ.setdefault("DSH_HOME", str(data_dir / "dsh-home"))
    os.environ.setdefault("DSH_WORKSPACE", str(data_dir / "dsh-ws"))


def _run_migrations(resource_root: Path) -> None:
    """把数据库迁移跑到 head（幂等）。"""
    from alembic import command
    from alembic.config import Config

    alembic_ini = resource_root / "alembic.ini"
    migrations_dir = resource_root / "migrations"
    if not alembic_ini.is_file() or not migrations_dir.is_dir():
        raise FileNotFoundError(
            f"找不到 alembic 资源：{alembic_ini} / {migrations_dir}（打包时需随 datas 一起收集）"
        )

    config = Config(str(alembic_ini))
    # 显式指定，避免 %(here)s 在冻结环境下的相对路径歧义
    config.set_main_option("script_location", str(migrations_dir))
    command.upgrade(config, "head")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Flux 桌面端后端 sidecar")
    parser.add_argument(
        "--port",
        type=int,
        default=int(os.environ.get("FLUX_PORT", DEFAULT_PORT)),
        help="监听端口（也可用环境变量 FLUX_PORT，默认 8787）",
    )
    parser.add_argument("--host", default="127.0.0.1", help="监听地址（默认仅回环）")
    args = parser.parse_args(argv)

    resource_root = _resource_root()
    data_dir = _data_dir()
    _export_env(data_dir)

    # 未冻结时把仓库 backend 加进 import 路径，方便开发机直接跑
    if not getattr(sys, "frozen", False):
        backend = str(resource_root)
        if backend not in sys.path:
            sys.path.insert(0, backend)

    print(f"[flux-backend] data dir : {data_dir}", flush=True)
    print(f"[flux-backend] resources: {resource_root}", flush=True)

    _run_migrations(resource_root)
    print("[flux-backend] alembic upgrade head: OK", flush=True)

    import uvicorn

    print(f"[flux-backend] listening on http://{args.host}:{args.port}", flush=True)
    uvicorn.run(
        "flux.main:create_app",
        factory=True,
        host=args.host,
        port=args.port,
        # 显式用纯 asyncio loop，避免对 uvloop 二进制扩展的硬依赖
        loop="asyncio",
        log_level=os.environ.get("FLUX_LOG_LEVEL", "info"),
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

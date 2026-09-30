"""公共测试夹具。"""

from __future__ import annotations

import asyncio
from collections.abc import Iterator
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from flux.config import Settings
from flux.container import Container
from flux.db.session import create_engine
from flux.enums import Capability
from flux.main import create_app
from flux.models import Base


@pytest.fixture()
def settings(tmp_path) -> Settings:
    """测试用配置：SQLite 落临时目录，日志降到 WARNING，供应商用离线回显。

    三个真实供应商的密钥显式传 None：pydantic-settings 的默认值仍是"读环境"，
    开发机仓库根的 .env 一旦填了 FLUX_OPENAI_API_KEY 之类，就会让"未配置供应商"
    的断言变成"已配置"，测试结果随开发机环境漂移。显式钉死让用例与外部环境无关。
    """
    return Settings(
        env="test",
        log_level="WARNING",
        database_url=f"sqlite+aiosqlite:///{tmp_path / 'flux-test.db'}",
        openai_api_key=None,
        anthropic_api_key=None,
        deepseek_api_key=None,
        default_provider="local",
    )


@pytest.fixture()
def workspace_root(tmp_path: Path) -> Path:
    """一个真实存在的"被改项目"根目录（Apply 的落盘目标）。"""
    root = tmp_path / "project"
    root.mkdir()
    return root


@pytest.fixture()
def apply_settings(settings: Settings, workspace_root: Path) -> Settings:
    """带工作区配置的 Settings：Apply 只有配了 workspace_root 才允许落盘（§7.6）。"""
    return settings.model_copy(update={"workspace_root": str(workspace_root)})


@pytest.fixture()
def db_schema(settings: Settings) -> Iterator[None]:
    """为测试用 SQLite 库建表。

    任务落 tasks 表后，API 不再依赖「内存任务字典」，必须有表结构才能使用。
    这里用独立引擎建表后立即 dispose，容器/应用各自再开自己的引擎即可。
    """
    engine = create_engine(settings.database_url)

    async def _create_all() -> None:
        async with engine.begin() as conn:
            await conn.run_sync(Base.metadata.create_all)

    try:
        asyncio.run(_create_all())
    finally:
        asyncio.run(engine.dispose())
    yield


@pytest.fixture()
def container(settings: Settings, db_schema: None) -> Container:
    return Container(settings)


@pytest.fixture()
def apply_container(apply_settings: Settings, db_schema: None) -> Container:
    """配好工作区根目录的容器，供真正会落盘的 Apply 用例使用。"""
    return Container(apply_settings)


@pytest.fixture()
def client(settings: Settings, db_schema: None) -> Iterator[TestClient]:
    app = create_app(settings)
    with TestClient(app) as test_client:
        yield test_client


@pytest.fixture()
def apply_client(apply_settings: Settings, db_schema: None) -> Iterator[TestClient]:
    """配好工作区根目录的 app，供经 API 落盘的用例使用。"""
    app = create_app(apply_settings)
    with TestClient(app) as test_client:
        yield test_client


@pytest.fixture()
def mcp_client(apply_client: TestClient) -> TestClient:
    """MCP 用例专用：工作区根已配置（proposal.create 要读原文件）。"""
    return apply_client


def issue_token(
    client: TestClient,
    *,
    agent_id: str = "codex",
    scopes: list[Capability] | None = None,
) -> str:
    """经 REST 签发一枚接入令牌，返回明文（测试里直接用真实签发路径，不走内部捷径）。"""
    response = client.post(
        f"/api/v1/agents/{agent_id}/tokens",
        json={"scopes": [str(s) for s in (scopes or [Capability.FILE_READ])]},
    )
    assert response.status_code == 200, response.text
    return response.json()["data"]["token"]

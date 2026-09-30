"""应用容器：把各层服务装配到一处，供 API 层依赖注入。

M0 是单进程装配。后续拆分微服务（§17.2 后端服务拆分）时，本文件是唯一的装配点。
"""

from __future__ import annotations

from dataclasses import dataclass, field

from sqlalchemy.ext.asyncio import AsyncEngine

from flux.config import Settings, get_settings
from flux.connectors.base import ConnectorRegistry
from flux.core.agent_runtime.manager import AgentManager
from flux.core.event.bus import EventBus
from flux.core.model_gateway.providers.registry import build_providers
from flux.core.model_gateway.router import ModelRouter
from flux.core.permission_engine.policy import PermissionPolicy
from flux.core.task_engine.repository import TaskRepository
from flux.core.task_engine.scheduler import TaskScheduler
from flux.core.virtual_workspace.apply_engine import ApplyEngine
from flux.core.virtual_workspace.repository import ProposalRepository
from flux.core.virtual_workspace.service import VirtualWorkspaceService
from flux.db.session import create_engine, create_session_factory
from flux.enums import ModelProvider


@dataclass
class Container:
    settings: Settings = field(default_factory=get_settings)
    bus: EventBus = field(default_factory=EventBus)
    policy: PermissionPolicy = field(default_factory=PermissionPolicy)
    engine: AsyncEngine = field(init=False)
    agents: AgentManager = field(init=False)
    router: ModelRouter = field(init=False)
    scheduler: TaskScheduler = field(init=False)
    connectors: ConnectorRegistry = field(init=False)
    workspace: VirtualWorkspaceService = field(init=False)
    apply_engine: ApplyEngine = field(init=False)
    task_repo: TaskRepository = field(init=False)
    proposal_repo: ProposalRepository = field(init=False)

    def __post_init__(self) -> None:
        self.engine = create_engine(self.settings.database_url)
        self.session_factory = create_session_factory(self.engine)  # type: ignore[attr-defined]
        self.task_repo = TaskRepository(self.session_factory)  # type: ignore[attr-defined]
        # 提案的权威存储在 virtual_changes 表（实施计划 ④），进程重启后审核队列不丢
        self.proposal_repo = ProposalRepository(self.session_factory)  # type: ignore[attr-defined]
        default_provider = self._resolve_default_provider()
        self.router = ModelRouter(build_providers(self.settings), default_provider=default_provider)
        self.agents = AgentManager(self.router, self.bus)
        self.scheduler = TaskScheduler()
        self.connectors = ConnectorRegistry(self.bus, self.policy)
        # Apply Engine 是唯一会写用户真实文件的组件（§7.6），根目录与测试命令来自配置
        self.apply_engine = ApplyEngine(
            workspace_root=self.settings.workspace_root,
            test_command=self.settings.test_command,
            test_timeout_seconds=self.settings.test_timeout_seconds,
        )
        self.workspace = VirtualWorkspaceService(self.proposal_repo, self.bus, self.apply_engine)

    def _resolve_default_provider(self) -> ModelProvider:
        try:
            return ModelProvider(self.settings.default_provider)
        except ValueError:
            return ModelProvider.LOCAL

    async def dispose(self) -> None:
        await self.engine.dispose()

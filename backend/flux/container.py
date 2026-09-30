"""应用容器：把各层服务装配到一处，供 API 层依赖注入。

M0 是单进程装配。后续拆分微服务（§17.2 后端服务拆分）时，本文件是唯一的装配点。
"""

from __future__ import annotations

from dataclasses import dataclass, field, replace
from functools import cached_property

from sqlalchemy.ext.asyncio import AsyncEngine

from flux.config import Settings, get_settings
from flux.connectors.base import ConnectorRegistry
from flux.core.agent_runtime.developer import DEV_AGENT_NAME, DeveloperAgent
from flux.core.agent_runtime.manager import AgentManager
from flux.core.agent_runtime.manifest import AgentManifest, builtin_manifests
from flux.core.agent_runtime.tester import TesterAgent
from flux.core.event.bus import EventBus
from flux.core.git_integration.client import GitClient
from flux.core.git_integration.service import GitService
from flux.core.model_gateway.providers.registry import build_providers
from flux.core.model_gateway.router import ModelRouter
from flux.core.permission_engine.policy import PermissionPolicy
from flux.core.project_brain.repository import ProjectBrainRepository
from flux.core.project_brain.service import ProjectBrain
from flux.core.project_scanner.scanner import ProjectScanner
from flux.core.task_engine.repository import TaskRepository
from flux.core.task_engine.scheduler import TaskScheduler
from flux.core.virtual_workspace.apply_engine import ApplyEngine
from flux.core.virtual_workspace.flow import DeveloperProposalFlow
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
    git_client: GitClient = field(init=False)
    git: GitService = field(init=False)
    scanner: ProjectScanner = field(init=False)
    brain_repo: ProjectBrainRepository = field(init=False)
    brain: ProjectBrain = field(init=False)
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
        # Git 集成（⑨）：与 Apply / Tester 共用同一个工作区根；按 change_ids 提交时
        # 由 GitService 复验提案是否已 applied，未落盘/未过测试的改动进不了 Git 历史
        self.git_client = GitClient(
            workspace_root=self.settings.workspace_root,
            timeout_seconds=self.settings.git_timeout_seconds,
        )
        self.git = GitService(self.git_client, self.workspace, self.bus)
        # Project Scanner（⑩）复用 ⑨ 的 GitClient 判断"是不是仓库、在哪个分支"；
        # Project Brain（⑪）把扫描结果与人工/Agent 写的记忆一起存进 projects 表
        self.scanner = ProjectScanner(
            git=self.git_client,
            max_files=self.settings.project_scan_max_files,
            max_depth=self.settings.project_scan_max_depth,
        )
        self.brain_repo = ProjectBrainRepository(self.session_factory)  # type: ignore[attr-defined]
        self.brain = ProjectBrain(
            self.brain_repo,
            scanner=self.scanner,
            bus=self.bus,
            workspace_root=self.settings.workspace_root,
        )

    @cached_property
    def tester(self) -> TesterAgent:
        """Tester Agent（⑧）：按需创建。

        与 Apply Engine 共用同一个工作区根与测试命令；不在启动时就创建，
        避免内置 Agent 混进 AgentManager 的实例列表（§12.3 的 /agents 只应列出
        用户真正创建过的 Agent）。
        """
        return TesterAgent(
            self.agents,
            test_command=self.settings.test_command,
            workspace_root=self.settings.workspace_root,
            timeout_seconds=self.settings.test_timeout_seconds,
        )

    @cached_property
    def developer(self) -> DeveloperAgent:
        """Developer Agent（③）：按需创建，理由同 tester。"""
        return DeveloperAgent(self.agents, self._developer_manifest())

    @cached_property
    def dev_flow(self) -> DeveloperProposalFlow:
        """需求 → 提案的入口（⑫）：最小 IDE 里"让 AI 改"走的就是它。"""
        return DeveloperProposalFlow(
            self.developer,
            self.workspace,
            workspace_root=self.settings.workspace_root,
        )

    def _developer_manifest(self) -> AgentManifest:
        """Developer 的 Manifest，必要时按 §5.3 简化路由退回默认供应商。

        Manifest 里声明的是 deepseek；本地没有密钥时该供应商未配置，若不回退，
        "让 AI 改"会在第一步就报 provider 未配置——离线回显（local）本来就是
        为了"没有密钥也能端到端跑通"而存在的，所以这里按路由规则退回默认供应商。
        """
        manifest = builtin_manifests()[DEV_AGENT_NAME]
        if manifest.provider in self.router.available():
            return manifest
        fallback = self._resolve_default_provider()
        if fallback not in self.router.available():
            return manifest  # 一个都没配：保持原样，让调用方拿到"供应商未配置"的明确报错
        return replace(manifest, provider=fallback, model=self._model_for(fallback))

    def _model_for(self, provider: ModelProvider) -> str:
        """该供应商在配置里声明的模型 id（§18.4 的 FLUX_*_MODEL）。

        回退时必须连模型一起换：Manifest 声明的是 deepseek 家的 `deepseek-flash`，
        把这个名字发给别家供应商会被上游直接拒掉。
        """
        return {
            ModelProvider.LOCAL: self.settings.local_model_name,
            ModelProvider.OPENAI: self.settings.openai_model,
            ModelProvider.ANTHROPIC: self.settings.anthropic_model,
            ModelProvider.DEEPSEEK: self.settings.deepseek_model,
        }[provider]

    def _resolve_default_provider(self) -> ModelProvider:
        try:
            return ModelProvider(self.settings.default_provider)
        except ValueError:
            return ModelProvider.LOCAL

    async def dispose(self) -> None:
        await self.engine.dispose()

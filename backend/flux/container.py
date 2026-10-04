"""应用容器：把各层服务装配到一处，供 API 层依赖注入。

M0 是单进程装配。后续拆分微服务（§17.2 后端服务拆分）时，本文件是唯一的装配点。
"""

from __future__ import annotations

from dataclasses import dataclass, field

from sqlalchemy.ext.asyncio import AsyncEngine

from flux.config import Settings, get_settings
from flux.connectors.base import ConnectorRegistry
from flux.core.agent_runtime.adapters import build_default_adapters
from flux.core.agent_runtime.dsh_client import FluxDshClient
from flux.core.agent_runtime.installation import InstallationRepository, InstallationService
from flux.core.agent_runtime.manager import AgentManager
from flux.core.agent_runtime.repository import AgentRepository
from flux.core.agent_runtime.run_repository import AgentRunRepository
from flux.core.capability_import.repository import ImportedCapabilityRepository
from flux.core.capability_import.scanners import AgentScanner
from flux.core.capability_import.service import CapabilityImportService
from flux.core.event.bus import EventBus, Events
from flux.core.git_integration.client import GitClient
from flux.core.git_integration.service import GitService
from flux.core.mcp.auth import AgentTokenService
from flux.core.memory.repository import MemoryRepository
from flux.core.memory.service import MemoryService
from flux.core.model_gateway.providers.registry import build_providers
from flux.core.model_gateway.router import ModelRouter
from flux.core.permission_engine.policy import PermissionPolicy
from flux.core.project_brain.repository import ProjectBrainRepository
from flux.core.project_brain.service import ProjectBrain
from flux.core.project_files.explorer import WorkspaceFileExplorer
from flux.core.project_scanner.scanner import ProjectScanner
from flux.core.task_engine.assistant import TaskAssistant
from flux.core.task_engine.dsh_bridge import TaskRunBridge
from flux.core.task_engine.repository import TaskRepository
from flux.core.task_engine.scheduler import TaskScheduler
from flux.core.terminal.repository import TerminalRepository
from flux.core.terminal.service import TerminalService
from flux.core.virtual_workspace.apply_engine import ApplyEngine
from flux.core.virtual_workspace.batch_repository import ApplyBatchRepository
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
    terminal_repo: TerminalRepository = field(init=False)
    terminal: TerminalService = field(init=False)
    git_client: GitClient = field(init=False)
    git: GitService = field(init=False)
    scanner: ProjectScanner = field(init=False)
    files: WorkspaceFileExplorer = field(init=False)
    brain_repo: ProjectBrainRepository = field(init=False)
    brain: ProjectBrain = field(init=False)
    memory_repo: MemoryRepository = field(init=False)
    memory: MemoryService = field(init=False)
    task_repo: TaskRepository = field(init=False)
    task_runs: TaskRunBridge = field(init=False)
    assistant: TaskAssistant = field(init=False)
    proposal_repo: ProposalRepository = field(init=False)
    batch_repo: ApplyBatchRepository = field(init=False)
    dsh: FluxDshClient = field(init=False)
    agent_tokens: AgentTokenService = field(init=False)
    agent_repo: AgentRepository = field(init=False)
    run_repo: AgentRunRepository = field(init=False)
    installations: InstallationService = field(init=False)
    capability_import: CapabilityImportService = field(init=False)

    def __post_init__(self) -> None:
        self.engine = create_engine(self.settings.database_url)
        self.session_factory = create_session_factory(self.engine)  # type: ignore[attr-defined]
        self.task_repo = TaskRepository(self.session_factory)  # type: ignore[attr-defined]
        # 提案的权威存储在 virtual_changes 表（实施计划 ④），进程重启后审核队列不丢
        self.proposal_repo = ProposalRepository(self.session_factory)  # type: ignore[attr-defined]
        # Apply 批日志（P0-1）：每次 apply_many 一行，先于磁盘操作落库；
        # 崩溃恢复（启动全量扫 + Apply 入口懒检查）按它找出未对账的批
        self.batch_repo = ApplyBatchRepository(self.session_factory)  # type: ignore[attr-defined]
        # Agent 档案注册表：Flux 不执行 Agent，只维护档案与权限边界（目标架构 §1）。
        # P3-16 起档案落 agents 表，canonical UUID 是身份的唯一权威（重启后 load_from_db 重建）。
        self.agent_repo = AgentRepository(self.session_factory)  # type: ignore[attr-defined]
        self.agents = AgentManager(self.bus, self.agent_repo)
        # Agent Installation（最终方案 §3.2 / §4）：本机 CLI Agent 的发现与接入状态，
        # 与档案（身份/权限）分离；Adapter 只提供事实，状态机与持久化在本服务里。
        self.installations = InstallationService(
            InstallationRepository(self.session_factory),  # type: ignore[attr-defined]
            bus=self.bus,
            adapters=build_default_adapters(),
        )
        # 能力导入（批次③ §5）：扫描本机 Agent / Skill / Connector → Flux 标准对象 → 注册表。
        # 扫描源是代码常量（不给设置项、REST 不收路径）；Agent 事实从 installations
        # 只读投影（AgentScanner 扫描时先刷新 installation 事实），运行时执行仍归各自组件。
        self.capability_import = CapabilityImportService(
            ImportedCapabilityRepository(self.session_factory),  # type: ignore[attr-defined]
            agents=AgentScanner(self.installations),
            bus=self.bus,
        )
        # 模型路由仅供平台内部使用（T2 压缩、摘要等），不是任何 agent loop 的模型通道
        self.router = ModelRouter(
            build_providers(self.settings),
            default_provider=self._resolve_default_provider(),
        )
        # Solo 对话助手：平台侧助手（非 agent），回复内容来自 router 的真实模型调用
        self.assistant = TaskAssistant(self.router)
        self.scheduler = TaskScheduler()
        self.connectors = ConnectorRegistry(self.bus, self.policy)
        # Apply Engine 是唯一会写用户真实文件的组件（§7.6），根目录与测试命令来自配置
        self.apply_engine = ApplyEngine(
            workspace_root=self.settings.workspace_root,
            test_command=self.settings.test_command,
            test_timeout_seconds=self.settings.test_timeout_seconds,
        )
        self.workspace = VirtualWorkspaceService(
            self.proposal_repo,
            self.bus,
            self.apply_engine,
            proposal_ttl_seconds=self.settings.proposal_ttl_seconds,
            batch_repository=self.batch_repo,
        )
        # 终端会话（Agent Terminal Console §6）：Flux 自己执行命令，命令与输出都可观察、可停止；
        # 工作区根与 Apply 同源（FLUX_WORKSPACE_ROOT），未配置时会话创建即 422
        self.terminal_repo = TerminalRepository(self.session_factory)  # type: ignore[attr-defined]
        self.terminal = TerminalService(
            self.terminal_repo,
            self.bus,
            workspace_root=self.settings.workspace_root,
        )
        # Git 集成（⑨）：与 Apply 共用同一个工作区根；按 change_ids 提交时
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
        # 文件浏览（只读）：与 Apply / Scanner 共用同一个工作区根配置，
        # 请求里的 workspace_root 只是按次覆盖，不是新的默认来源。
        self.files = WorkspaceFileExplorer(workspace_root=self.settings.workspace_root)
        self.brain_repo = ProjectBrainRepository(self.session_factory)  # type: ignore[attr-defined]
        self.brain = ProjectBrain(
            self.brain_repo,
            scanner=self.scanner,
            bus=self.bus,
            workspace_root=self.settings.workspace_root,
        )
        # 三层记忆（批次② §4.2）：User / Environment 落 memories 表，Project 复用 brain；
        # 写入必须走受控路径（用户 REST / 平台代码），Agent 面只有只读的 memory.recall
        self.memory_repo = MemoryRepository(self.session_factory)  # type: ignore[attr-defined]
        self.memory = MemoryService(self.memory_repo, brain=self.brain)
        # MCP 能力面的鉴权（目标架构 §3.2）：令牌是 agent 进入平台的唯一凭据，
        # 与权限策略同源——工具要求的 Capability 直接取自 flux.enums.Capability。
        # 先于 DSH 装配：DSH 起 Run 时要拿内置 Agent 的令牌注入 MCP patch（P0-01）。
        # P3-16：令牌只引用 Agent Registry 的 canonical UUID，故注入注册表做校验与解析。
        self.agent_tokens = AgentTokenService(self.session_factory, self.agents)  # type: ignore[attr-defined]
        # Run 权威状态落 agent_runs 表（P2-15）：多实例共享同库时用 owner_pid 区分归属
        self.run_repo = AgentRunRepository(self.session_factory)  # type: ignore[attr-defined]
        # DSH Agent Runtime（集成方案 §18 Phase 1）：内置 agent 的接入层，
        # 未启用（FLUX_DSH_ENABLED=false）时调用 ensure_ready() 才报错，装配本身无副作用。
        self.dsh = FluxDshClient(
            self.settings,
            bus=self.bus,
            token_service=self.agent_tokens,
            run_repository=self.run_repo,
        )
        # DSH Run 终态 → 任务状态（P0-05）：订阅事件总线，Run 结束即回写所属任务。
        # 同时把任务级对账挂上看护器的对账循环：事件在崩溃时会丢，running 任务需要兜底收尾
        # （P2-04 / TC-502），Run 状态先对完再让下游据真实状态判断。
        self.task_runs = TaskRunBridge(self.task_repo, self.run_repo)
        self.task_runs.attach(self.bus)
        self.dsh.supervisor.add_reconcile_hook(self.task_runs.reconcile_orphan_tasks)
        # MCP 工具调用成功 = 一次有效进展（P2-15 §2.4）：接线到看护器，避免 idle timeout 误杀
        self.bus.subscribe(Events.MCP_TOOL_CALLED, self._on_mcp_tool_called)

    async def _on_mcp_tool_called(self, _event: str, payload: dict[str, object]) -> None:
        """MCP 工具调用成功后刷新对应 Agent 名下 Run 的 last_mcp_activity_at。"""
        if payload.get("error"):
            return
        agent_id = payload.get("agent_id")
        if isinstance(agent_id, str):
            await self.dsh.supervisor.note_mcp_activity(agent_id)

    def _resolve_default_provider(self) -> ModelProvider:
        try:
            return ModelProvider(self.settings.default_provider)
        except ValueError:
            return ModelProvider.LOCAL

    async def dispose(self) -> None:
        # 退出时先停看护循环并清理仍在跑的 Agent 进程树，再释放连接（P2-15 §2.5）
        await self.terminal.shutdown()
        await self.dsh.shutdown()
        await self.engine.dispose()

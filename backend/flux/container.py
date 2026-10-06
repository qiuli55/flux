"""应用容器：把各层服务装配到一处，供 API 层依赖注入。

M0 是单进程装配。后续拆分微服务（§17.2 后端服务拆分）时，本文件是唯一的装配点。
"""

from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path

from sqlalchemy.ext.asyncio import AsyncEngine

from flux.config import Settings, get_settings
from flux.connectors.base import ConnectorRegistry
from flux.core.agent_runtime.adapters import build_default_adapters
from flux.core.agent_runtime.dsh_client import FluxDshClient
from flux.core.agent_runtime.installation import InstallationRepository, InstallationService
from flux.core.agent_runtime.manager import AgentManager
from flux.core.agent_runtime.repository import AgentRepository
from flux.core.agent_runtime.run_repository import AgentRunRepository
from flux.core.agent_runtime.runtimes.service import TaskRunService
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
from flux.core.terminal.pty_service import HumanPtyService
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
    human_pty: HumanPtyService = field(init=False)
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
    task_run_service: TaskRunService = field(init=False)

    def __post_init__(self) -> None:
        self.engine = create_engine(self.settings.database_url)
        self.session_factory = create_session_factory(self.engine)  # type: ignore[attr-defined]
        cli_adapters = build_default_adapters()
        self.task_repo = TaskRepository(self.session_factory)  # type: ignore[attr-defined]
        self.proposal_repo = ProposalRepository(self.session_factory)  # type: ignore[attr-defined]
        self.batch_repo = ApplyBatchRepository(self.session_factory)  # type: ignore[attr-defined]
        self.agent_repo = AgentRepository(self.session_factory)  # type: ignore[attr-defined]
        self.agents = AgentManager(self.bus, self.agent_repo)
        self.installations = InstallationService(
            InstallationRepository(self.session_factory),
            bus=self.bus,
            adapters=cli_adapters,
        )
        self.capability_import = CapabilityImportService(
            ImportedCapabilityRepository(self.session_factory),
            agents=AgentScanner(self.installations),
            bus=self.bus,
        )
        self.router = ModelRouter(
            build_providers(self.settings),
            default_provider=self._resolve_default_provider(),
        )
        self.assistant = TaskAssistant(self.router)
        self.scheduler = TaskScheduler()
        self.connectors = ConnectorRegistry(self.bus, self.policy)
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
        self.terminal_repo = TerminalRepository(self.session_factory)  # type: ignore[attr-defined]
        self.terminal = TerminalService(
            self.terminal_repo,
            self.bus,
            workspace_root=self.settings.workspace_root,
        )
        self.human_pty = HumanPtyService(
            self.terminal_repo,
            self.bus,
            workspace_root=self.settings.workspace_root,
        )
        self.git_client = GitClient(
            workspace_root=self.settings.workspace_root,
            timeout_seconds=self.settings.git_timeout_seconds,
        )
        self.git = GitService(self.git_client, self.workspace, self.bus)
        self.scanner = ProjectScanner(
            git=self.git_client,
            max_files=self.settings.project_scan_max_files,
            max_depth=self.settings.project_scan_max_depth,
        )
        self.files = WorkspaceFileExplorer(workspace_root=self.settings.workspace_root)
        self.brain_repo = ProjectBrainRepository(self.session_factory)  # type: ignore[attr-defined]
        self.brain = ProjectBrain(
            self.brain_repo,
            scanner=self.scanner,
            bus=self.bus,
            workspace_root=self.settings.workspace_root,
        )
        self.memory_repo = MemoryRepository(self.session_factory)  # type: ignore[attr-defined]
        self.memory = MemoryService(self.memory_repo, brain=self.brain)
        self.agent_tokens = AgentTokenService(self.session_factory, self.agents)  # type: ignore[attr-defined]
        self.run_repo = AgentRunRepository(self.session_factory)  # type: ignore[attr-defined]
        self.dsh = FluxDshClient(
            self.settings,
            bus=self.bus,
            token_service=self.agent_tokens,
            run_repository=self.run_repo,
        )
        self.task_runs = TaskRunBridge(self.task_repo, self.run_repo)
        self.task_runs.attach(self.bus)
        self.dsh.supervisor.add_reconcile_hook(self.task_runs.reconcile_orphan_tasks)
        self.bus.subscribe(Events.MCP_TOOL_CALLED, self._on_mcp_tool_called)
        self.task_run_service = TaskRunService(self, cli_adapters=cli_adapters)

    def set_workspace_root(self, root: str) -> str:
        """Switch the live IDE workspace root for all filesystem-bound services."""
        resolved = Path(root).expanduser().resolve()
        if not resolved.is_dir():
            raise ValueError(f"工作目录不存在或不是目录：{resolved}")
        value = str(resolved)
        self.settings.workspace_root = value
        self.apply_engine.workspace_root = value
        self.terminal.workspace_root = value
        self.human_pty.workspace_root = value
        self.git_client.workspace_root = value
        self.files.workspace_root = value
        self.brain.workspace_root = value
        return value

    async def _on_mcp_tool_called(self, _event: str, payload: dict[str, object]) -> None:
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
        await self.human_pty.shutdown()
        await self.terminal.shutdown()
        await self.dsh.shutdown()
        await self.engine.dispose()

"""Supervisor（助理）模块。

分层与既有代码的边界：

- 本模块只做编排，不执行任何任务；真正的执行仍走 `TaskRunService`。
- 判断层（P1 起）无状态，只往 `supervisor_evals` 追加结论。
- 规划层（P2 起）只提建议，平台做硬校验，失败降级成单工单。
- 有状态的只有 thread / ticket / assignment / report。
"""

from flux.core.supervisor.assess import SupervisorAssessor
from flux.core.supervisor.dispatcher import DispatchOutcome, SupervisorDispatcher, derive_stage
from flux.core.supervisor.orchestrator import SupervisorOrchestrator
from flux.core.supervisor.outbox import SupervisorOutbox
from flux.core.supervisor.planner import PlannedTicket, SupervisorPlanner, TicketPlan
from flux.core.supervisor.repository import SupervisorRepository
from flux.core.supervisor.service import SupervisorService
from flux.core.supervisor.worker import SupervisorWorker

__all__ = [
    "DispatchOutcome",
    "PlannedTicket",
    "SupervisorAssessor",
    "SupervisorDispatcher",
    "SupervisorOrchestrator",
    "SupervisorOutbox",
    "SupervisorPlanner",
    "SupervisorRepository",
    "SupervisorService",
    "SupervisorWorker",
    "TicketPlan",
    "derive_stage",
]

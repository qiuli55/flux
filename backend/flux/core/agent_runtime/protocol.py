"""Flux Agent 握手协议标识（最终方案 §6）。

第一版不新造与 MCP 平行的协议：`MCP initialize` + `flux_context` 即 Flux Agent
Handshake v1。这里只放协议名与版本，供 `flux_context` 与 CLI Adapter 共用同一份标识。

同时提供 Runtime Bootstrap / Runtime Identity 的**唯一真源**：CLI Agent Adapter 与
DSH 客户端都从这里取，避免两套 `FLUX_*` 注入各写各的（收口方案 §8.2）。
"""

from __future__ import annotations

from flux.version import VERSION

FLUX_AGENT_PROTOCOL = "flux-agent"
FLUX_AGENT_PROTOCOL_VERSION = "1"

#: §4.1 契约 rules 真源：Bootstrap 文案与 `flux_context.rules` 共用，防止两处各写各的。
RUNTIME_RULES: tuple[str, ...] = (
    "Submit every change through proposal.create; Flux handles validation, approval and apply.",
    "Do not assume direct workspace writes are the Flux completion path.",
    "If the Flux MCP server is unavailable, fall back to the Flux Server CLI: "
    "flux tools call <tool> --params '<json>'.",
)

#: §4.1 契约 entry 真源：Bootstrap 文案与 `flux_context.entry` 共用，防止两处各写各的。
RUNTIME_ENTRY: tuple[str, ...] = (
    "Call flux_context first to confirm where you are and what capabilities you have.",
    "Get project facts with context.get (read-only).",
)

#: Runtime Bootstrap：Flux 托管启动 Agent 时注入的**第一份强制上下文**（批次① §5）。
#: 只说明"我在 Flux 里、开工先做什么、改动该走哪条路"，不塞完整产品文档；真实状态一律以
#: `FLUX_*` / `flux_context` / `tools/list` 为准（收口方案 §8.2）。
RUNTIME_BOOTSTRAP = (
    "You are running inside Flux. This is your first mandatory context for this run.\n\n"
    "Flux owns the task lifecycle, workspace, proposal validation, approval, apply, "
    "testing and git.\n\n"
    "Entry:\n"
    + "\n".join(f"- {item}" for item in RUNTIME_ENTRY)
    + "\n\nRules:\n"
    + "\n".join(f"- {rule}" for rule in RUNTIME_RULES)
)


def compose_instruction(instruction: str) -> str:
    """把 Runtime Bootstrap 拼在任务指令之前，作为 Flux 托管启动的 System Context。"""
    return f"{RUNTIME_BOOTSTRAP}\n\n{instruction}"


def build_runtime_env(
    *,
    run_id: str,
    workspace: str,
    task_id: str | None = None,
    agent_id: str | None = None,
    mcp_endpoint: str | None = None,
) -> dict[str, str]:
    """Runtime Identity 环境变量（最终方案 §5）。

    这些变量只说明"我在 Flux 里、属于哪个 Run / Task / Workspace"，**不是安全凭证**：
    鉴权仍以令牌 → canonical id 为准，伪造 `FLUX_*` 不会带来任何额外权限。
    """
    env = {
        "FLUX_RUNTIME": "1",
        "FLUX_VERSION": VERSION,
        "FLUX_RUN_ID": run_id,
        "FLUX_WORKSPACE": workspace,
    }
    if task_id:
        env["FLUX_TASK_ID"] = task_id
    if agent_id:
        env["FLUX_AGENT_ID"] = agent_id
    if mcp_endpoint:
        env["FLUX_MCP_ENDPOINT"] = mcp_endpoint
    return env

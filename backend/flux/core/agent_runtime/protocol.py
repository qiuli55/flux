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

#: Runtime Bootstrap：Flux 托管启动 Agent 时注入的最小 System Context。
#: 只说明"我在 Flux 里、改动该走哪条路"，不塞完整产品文档；真实状态一律以
#: `FLUX_*` / `flux_context` / `tools/list` 为准（收口方案 §8.2）。
RUNTIME_BOOTSTRAP = """You are running inside Flux.

Flux manages task lifecycle, workspace, proposal validation,
approval, apply, test and git.

Use Flux MCP tools to inspect runtime context and capabilities.
When proposal_required is enabled, submit changes through proposal.create.
Do not assume direct workspace writes are the Flux completion path."""


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

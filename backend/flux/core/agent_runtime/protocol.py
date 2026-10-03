"""Flux Agent 握手协议标识（最终方案 §6）。

第一版不新造与 MCP 平行的协议：`MCP initialize` + `flux_context` 即 Flux Agent
Handshake v1。这里只放协议名与版本，供 `flux_context` 与 CLI Adapter 共用同一份标识。
"""

from __future__ import annotations

FLUX_AGENT_PROTOCOL = "flux-agent"
FLUX_AGENT_PROTOCOL_VERSION = "1"

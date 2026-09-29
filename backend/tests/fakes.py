"""测试替身。"""

from __future__ import annotations

from typing import Any

from aios.connectors.base import Connector, ConnectorManifest
from aios.enums import Capability
from aios.errors import PermissionDeniedError


class FakeTerminalConnector(Connector):
    """模拟一个需要 terminal.execute 能力的终端连接器。

    真实终端连接器是 M4 交付物（主规格 §19.1 Issues #042），测试替身只用于验证
    注册表的能力校验、审计日志与事件发布路径。
    """

    manifest = ConnectorManifest(
        name="terminal",
        version="0.1.0",
        type="terminal",
        actions=("run",),
        required_permissions=(Capability.TERMINAL_EXECUTE,),
    )

    def __init__(self) -> None:
        self.initialized = False
        self.closed = False
        self.commands: list[str] = []

    def initialize(self) -> None:
        self.initialized = True

    def validate_permission(self, capability: Capability) -> None:
        if capability is not Capability.TERMINAL_EXECUTE:
            raise PermissionDeniedError(f"终端连接器不支持能力 {capability}")

    async def execute(self, action: str, parameters: dict[str, Any]) -> dict[str, Any]:
        command = str(parameters.get("command", ""))
        self.commands.append(command)
        return {"stdout": f"ran: {command}", "exit_code": 0}

    def health_check(self) -> bool:
        return self.initialized and not self.closed

    def close(self) -> None:
        self.closed = True

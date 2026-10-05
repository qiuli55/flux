"""CLI Agent Adapter 统一接口（最终方案 §3.3）。

Adapter 只抹平不同 CLI Agent 的差异——怎么发现、怎么问版本、怎么判登录、怎么起停、
怎么把一行输出解析成事件——**不承载 Flux 核心业务**：Proposal / Apply / Task / Git
都不在这里，具体 Agent 也不得反向污染它们。

生命周期方法（start / stop / cancel）以独立进程组承载：进程以"自成进程组"的方式启动
（平台差异收敛在 platforms），取消时按进程组清理整棵进程树，绝不误伤 Flux 自身。
"""

from __future__ import annotations

import json
import shutil
import subprocess
from abc import ABC, abstractmethod
from collections.abc import Callable, Sequence
from dataclasses import dataclass, field
from typing import Any

from flux.core.agent_runtime import platforms
from flux.core.agent_runtime.protocol import FLUX_AGENT_PROTOCOL, FLUX_AGENT_PROTOCOL_VERSION
from flux.enums import AgentInstallStatus

#: 探测命令（--version / auth）的超时；探测绝不允许拖垮 CLI
PROBE_TIMEOUT_SECONDS = 10.0
#: 停止进程的优雅期（秒）：先 SIGTERM，超过则 SIGKILL
STOP_GRACE_SECONDS = 5.0


def run_probe(argv: Sequence[str], *, timeout: float = PROBE_TIMEOUT_SECONDS) -> tuple[int, str]:
    """执行一条探测命令，返回 (returncode, stdout+stderr)。命令不存在抛 FileNotFoundError。"""
    # 与 start 同一口径：Windows 上探测目标也可能是 *.cmd（见 executable_argv）
    completed = subprocess.run(
        list(platforms.executable_argv(argv)),
        capture_output=True,
        text=True,
        timeout=timeout,
        check=False,
    )
    merged = f"{completed.stdout or ''}{completed.stderr or ''}".strip()
    return completed.returncode, merged


@dataclass(frozen=True)
class CliAgentProbe:
    """一次发现/校验得到的事实集合（最终方案 §3.2 的 installation 字段）。"""

    name: str
    adapter: str
    status: AgentInstallStatus
    executable: str | None = None
    path: str | None = None
    version: str | None = None
    source: str = "path"
    auth_status: str = "unknown"
    capabilities: tuple[str, ...] = ()
    detail: dict[str, Any] = field(default_factory=dict)

    def to_dict(self) -> dict[str, Any]:
        return {
            "name": self.name,
            "adapter": self.adapter,
            "status": str(self.status),
            "executable": self.executable,
            "path": self.path,
            "version": self.version,
            "source": self.source,
            "auth_status": self.auth_status,
            "capabilities": list(self.capabilities),
            "detail": self.detail,
        }


@dataclass
class CliProcess:
    """一个受管 CLI 进程（进程组组长）。"""

    pid: int
    pgid: int
    popen: subprocess.Popen[str]

    def to_dict(self) -> dict[str, Any]:
        return {"pid": self.pid, "pgid": self.pgid}


class CliAgentAdapter(ABC):
    """所有 CLI Agent Adapter 的统一接口。子类只需实现发现三件事。"""

    #: Adapter 标识（也是 installation 的 name）
    name: str = ""

    @abstractmethod
    def discover(self) -> CliAgentProbe:
        """发现事实：是否安装、版本、认证、能力。不修改用户环境。"""

    @abstractmethod
    def get_version(self) -> str | None:
        """返回版本号字符串；取不到返回 None。"""

    @abstractmethod
    def check_auth(self) -> str:
        """返回 "ok" / "missing" / "unknown"。"""

    def capabilities(self) -> tuple[str, ...]:
        return ()

    def verify(self) -> CliAgentProbe:
        """复验一次（scan 后、connect 前调用）；默认就是再发现一次。"""
        return self.discover()

    def handshake(self) -> dict[str, Any]:
        """返回握手回执；协议标识与 `flux_context` 同源（§6）。"""
        return {
            "protocol": FLUX_AGENT_PROTOCOL,
            "version": FLUX_AGENT_PROTOCOL_VERSION,
            "agent": {"name": self.name, "version": self.get_version()},
            "capabilities": list(self.capabilities()),
        }

    # --- 生命周期（进程组受控）---

    def start(
        self,
        argv: Sequence[str],
        *,
        env: dict[str, str] | None = None,
        cwd: str | None = None,
    ) -> CliProcess:
        """以独立进程组启动 CLI，返回可取消的进程句柄。"""
        process = subprocess.Popen(  # noqa: S603 - argv 由 Adapter 决定，不经 shell
            # Windows 上 CLI 常是 *.cmd 包装脚本，CreateProcess 不能直接执行（WinError 193），
            # 由平台原语规整成 cmd.exe /c 启动；POSIX 原样返回。
            list(platforms.executable_argv(argv)),
            **platforms.popen_kwargs(),
            env=env,
            cwd=cwd,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
        )
        # 独立进程组：POSIX 下 pid == pgid；Windows 以组长 pid 作为组标识
        pid, pgid = platforms.process_group_of(process)
        return CliProcess(pid=pid, pgid=pgid, popen=process)

    def stop(self, process: CliProcess) -> None:
        """终止整棵进程树：优雅信号 → 优雅期 → 强杀。已退出则幂等返回。"""
        if process.popen.poll() is not None:
            return
        platforms.signal_group_graceful(process.pgid)
        try:
            process.popen.wait(timeout=STOP_GRACE_SECONDS)
            return
        except subprocess.TimeoutExpired:
            platforms.kill_group(process.pgid)
        process.popen.wait()

    def cancel(self, process: CliProcess) -> None:
        """取消一次 Run；语义与 stop 一致（先优雅、后强杀），单列以便将来区分。"""
        self.stop(process)

    def parse_event(self, line: str) -> dict[str, Any] | None:
        """把一行 stdout 归一化成事件。空行返回 None；非 JSON 归为 text 事件。"""
        text = line.strip()
        if not text:
            return None
        try:
            data = json.loads(text)
        except json.JSONDecodeError:
            return {"type": "text", "text": text}
        if isinstance(data, dict):
            return data
        return {"type": "data", "data": data}


def default_which(executable: str) -> str | None:
    return shutil.which(executable)


#: 供子类/测试注入的类型别名
ProbeRunner = Callable[..., tuple[int, str]]

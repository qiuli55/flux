# Flux Agent Runtime Protocol

## 1. 目标

Flux 不应只把外部 CLI Agent 当作一个“可执行文件”。Agent 一旦被接入 Flux，就应该能够明确知道自己运行在 Flux Runtime 中，并通过统一协议理解当前任务、Workspace、Run、权限和可用能力。

本协议的目标是建立：

```text
Agent Discovery
      ↓
CLI Adapter
      ↓
Agent Handshake
      ↓
Runtime Context
      ↓
Capability Discovery
      ↓
Flux MCP
      ↓
Proposal
      ↓
Gate → Apply → Test → Git
```

第一版重点不是限制 Agent，而是让 Agent 能够可靠识别运行环境，并以机器可读的方式与 Flux 协作。

## 2. 四层信息模型

### 2.1 Environment

通过环境变量提供基础身份和运行时信息：

```text
FLUX_RUNTIME=1
FLUX_VERSION=<version>
FLUX_RUN_ID=<run_id>
FLUX_TASK_ID=<task_id>
FLUX_WORKSPACE=<workspace>
FLUX_AGENT_ID=<agent_id>
FLUX_MCP_ENDPOINT=<endpoint>
```

环境变量主要用于机器可读的运行时身份，不承担全部平台规则。

### 2.2 Agent Handshake

Agent 启动后与 Flux 完成握手，确认双方支持的协议版本和能力。

示例：

```json
{
  "protocol": "flux-agent",
  "version": "1",
  "agent": {
    "name": "opencode",
    "version": "..."
  },
  "capabilities": [
    "tool_call",
    "mcp",
    "stream"
  ]
}
```

Flux 返回：

```json
{
  "platform": "flux",
  "protocol": "1",
  "run_id": "...",
  "capabilities": {
    "proposal": true,
    "workspace": true,
    "git": true,
    "rollback": true
  }
}
```

推荐生命周期：

```text
Agent STARTING
      ↓
HELLO
      ↓
Flux validates protocol/capabilities
      ↓
READY
```

## 3. Runtime Context

建议提供统一的 `flux_context` MCP 工具，让 Agent 可以主动获取当前 Flux 运行环境。

示例：

```json
{
  "platform": {
    "name": "Flux",
    "version": "0.x",
    "mode": "personal"
  },
  "run": {
    "id": "run_123",
    "status": "running"
  },
  "task": {
    "id": "task_456",
    "title": "修复登录 Bug"
  },
  "workspace": {
    "path": "/workspace/project",
    "git_branch": "main",
    "git_revision": "abc123"
  },
  "agent": {
    "id": "opencode",
    "adapter": "opencode"
  },
  "policy": {
    "proposal_required": true,
    "direct_apply": false
  },
  "capabilities": [
    "context",
    "workspace",
    "proposal",
    "git"
  ]
}
```

## 4. Capability Discovery

Agent 不应依赖长篇产品介绍来理解 Flux，而应读取机器可读的能力声明。

建议第一版能力包括：

```text
context
workspace
proposal
apply
rollback
git
browser
sandbox
mobile_test
```

能力不存在时必须允许 Agent 正常降级，而不是假设所有 Flux Runtime 都具备完整能力。

## 5. Flux MCP

建议将 Flux MCP 作为 Agent 与平台交互的主要协议入口。

第一阶段可以提供：

```text
flux_context
flux_task
flux_workspace
flux_proposal
flux_status
flux_logs
```

其中：

- `flux_context`：获取平台、Run、Task、Workspace、Policy、Capabilities。
- `flux_task`：获取当前任务及任务状态。
- `flux_workspace`：获取 Workspace 信息和允许的操作范围。
- `flux_proposal`：创建、读取和提交 Proposal。
- `flux_status`：读取当前 Run 状态。
- `flux_logs`：读取必要的运行日志。

## 6. Proposal Contract

Agent 在 Flux 中工作时，最终产出应遵循统一 Proposal Contract，而不是依赖 Agent 自己决定如何直接写入最终状态。

推荐对象：

```text
Proposal
├── id
├── agent_id
├── task_id
├── base_revision
├── changes[]
├── validation
├── approval
└── apply_result
```

Apply 流程：

```text
Proposal
 ↓
Schema Validation
 ↓
Workspace / Path Validation
 ↓
Policy Gate
 ↓
Base Revision Check
 ↓
Atomic Apply
 ↓
Test
 ↓
Git
```

如果 Proposal 不满足 Flux Policy，应拒绝 Apply，而不是绕过门禁直接修改 Workspace。

## 7. System Context

启动 Agent 时可以额外注入简短的 Flux Runtime Context：

```text
You are running inside Flux, an AI coding orchestration platform.

Flux is responsible for:
- task lifecycle
- workspace management
- MCP capabilities
- proposal validation
- approval
- applying changes
- testing
- git integration

Use the provided Flux MCP tools to inspect runtime context and platform capabilities.
Follow the Flux proposal/apply workflow when proposal_required is enabled.
```

该 Context 只负责建立认知入口；真实状态必须以 Runtime Context、Handshake 和 MCP 返回结果为准。

## 8. 与 Agent Adapter 的关系

Agent Adapter 负责将不同 CLI Agent 的差异转换为 Flux Runtime 能理解的统一接口：

```text
Flux Agent Runtime
        │
        ├── Generic CLI Adapter
        ├── OpenCode Adapter
        ├── Codex Adapter
        ├── Claude Code Adapter
        └── DSH Adapter
```

Adapter 不应承载 Flux 核心业务逻辑。

它主要负责：

```text
discover()
verify()
get_version()
check_auth()
start()
stop()
cancel()
parse_event()
handshake()
```

这样新增 Agent 时只需增加或配置对应 Adapter，不需要修改 Proposal、Apply、Git、Task 等核心系统。

## 9. 一键扫描与接入

协议与 Agent Discovery 配合使用：

```text
PATH
 ↓
Known CLI Detection
 ↓
Version Detection
 ↓
Health Check
 ↓
Auth Check
 ↓
Capability Detection
 ↓
AgentInstallation
 ↓
Handshake
 ↓
READY
```

推荐状态：

```text
DISCOVERED
   ↓
VERIFIED
   ↓
CONNECTED
   ↓
READY
```

CLI 入口：

```bash
flux agents scan
flux agents list
flux agents connect <agent>
flux agents connect --all
flux agents remove <agent>
```

## 10. 安全与边界

Flux Runtime Context 中的 Workspace、权限和 Policy 信息必须由 Flux 服务端决定，Agent 不应自行修改这些安全边界。

尤其需要保证：

- Workspace 路径不能被 Agent 任意扩大。
- Proposal 不能绕过 Policy Gate。
- Agent 不能通过环境变量伪造已授权能力。
- `FLUX_*` 环境变量不应被视为安全凭证。
- 真正的权限判断必须在 Flux Runtime / MCP / Apply 层完成。

## 11. 与 Web / Mobile / CLI 的关系

所有入口共享同一个 Flux Core 和 Agent Runtime：

```text
                 Flux Core
                    │
        ┌───────────┼───────────┐
        ↓           ↓           ↓
      Web/API      CLI        Mobile
        │           │           │
        └───────────┼───────────┘
                    ↓
              Agent Runtime
                    ↓
            CLI Agent / Adapter
```

Web、Mobile 和 Server CLI 不应分别实现自己的 Agent Runtime。

## 12. 第一阶段实施范围

建议先实现以下最小闭环：

1. `FLUX_*` Runtime Context。
2. Agent Handshake v1。
3. `flux_context` MCP。
4. Capability Discovery。
5. Generic CLI Adapter。
6. OpenCode Adapter。
7. Agent Registry 与 Installation 状态。
8. Proposal Contract 与 `proposal_required` Policy。
9. `flux agents scan/connect/list`。

暂时不要求：

- 多 Agent 自动协商协议。
- Cloud Agent 特有协议。
- Marketplace 协议。
- Team / Enterprise 权限协议。

## 13. 阶段目标

完成第一阶段后，一个外部 CLI Agent 应能够从启动到结束清楚地经历：

```text
发现
 ↓
验证
 ↓
接入
 ↓
握手
 ↓
读取 Flux Context
 ↓
发现能力
 ↓
执行 Task
 ↓
生成 Proposal
 ↓
Flux Gate
 ↓
Apply
 ↓
Test
 ↓
Git
 ↓
完成 Run
```

最终目标不是让 Agent“知道 Flux 的产品介绍”，而是让 Agent **在协议层面知道自己正在 Flux Runtime 中运行，并始终通过 Flux 定义的能力与边界工作**。

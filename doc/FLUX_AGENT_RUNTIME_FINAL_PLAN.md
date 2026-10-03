# Flux Agent Runtime 最终实施方案

> 状态：Final Plan
>
> 目标：在当前 Flux Personal MVP 基础上，统一 Agent Discovery、CLI Adapter、Agent Runtime、Flux Context、MCP、Proposal、Apply、Test、Git 与 Server CLI，形成一条稳定、可替换、可观测、可恢复的 Agent 执行链路。
>
> 本文整合 `FLUX_AGENT_RUNTIME_PROTOCOL.md`、`FLUX_AGENT_RUNTIME_PHASE1_PLAN.md` 与 `NEXT_PHASE_OPTIMIZATION.md` 的设计，并以当前工程约束为最终落地边界。

---

## 1. 最终目标

Flux 不把外部 Agent 当成单纯的“可执行文件”，而是把它作为 **Flux Runtime 中受管理的执行节点**。

接入 Flux 后，Agent 应能够通过机器可读信息明确知道：

- 自己正在 Flux 中运行；
- 当前属于哪个 Run / Task / Workspace；
- 当前 Flux Runtime 提供哪些能力；
- 哪些操作需要 Proposal / Policy Gate；
- 自己应该通过什么接口与 Flux 协作；
- MCP 不可用时如何使用 Server CLI 进行兜底操作。

最终形成：

```text
Agent Discovery
      ↓
Agent Installation
      ↓
CLI Adapter
      ↓
Agent Runtime
      ↓
Runtime Identity
      ↓
Handshake
      ↓
flux_context / Capability Discovery
      ↓
Task
      ↓
Proposal
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
      ↓
Run Complete
```

---

## 2. 第一原则：Flux Core 与 Agent 解耦

Flux 的核心业务不能依赖某一个具体 Agent。

架构必须保持：

```text
                         Flux Core
                            │
             ┌──────────────┼──────────────┐
             ↓              ↓              ↓
          Web/API          CLI           Mobile
             │              │              │
             └──────────────┼──────────────┘
                            ↓
                     Agent Runtime
                            │
                 ┌──────────┼──────────┐
                 ↓          ↓          ↓
             Generic CLI  OpenCode    Codex
             Adapter      Adapter     Adapter
                            │
                       外部 CLI Agent
```

Web、Mobile、Server CLI 不得分别实现自己的 Agent 生命周期管理。

所有入口最终调用同一个 Agent Runtime / Flux Core。

---

## 3. Agent 系统最终拆分

Agent 系统固定拆成四层：

```text
Agent Registry
├── Agent Identity
├── Agent Installation
├── Agent Adapter
└── Agent Runtime
```

### 3.1 Agent Identity

只描述 Flux 认识的 Agent 身份，不负责执行。

建议字段：

```text
id
name
role
permissions
skills / tools metadata
```

### 3.2 Agent Installation

描述当前机器是否具备该 Agent。

```text
executable
path
version
source
auth_status
detected_capabilities
status
```

生命周期：

```text
DISCOVERED
   ↓
VERIFIED
   ↓
CONNECTED
   ↓
READY
```

### 3.3 Agent Adapter

Adapter 只处理不同 CLI Agent 的差异，不承载 Flux 核心业务。

统一接口：

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

第一版必须有：

```text
Generic CLI Adapter
+ 至少一个具体 CLI Adapter
```

具体 Agent 不应反向污染 Proposal、Apply、Task、Git 等核心模块。

### 3.4 Agent Runtime

统一管理 Agent 进程：

```text
process lifecycle
stdin / stdout / stderr
process group
startup timeout
idle timeout
hard timeout
heartbeat
cancellation
exit code
event stream
process cleanup
```

Runtime 是 Web / Mobile / CLI 共用的基础设施。

---

## 4. 一键扫描与接入

用户不应该手动填写一堆 Agent 配置才能接入。

最终流程：

```text
PATH / known locations
        ↓
Executable Detection
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
Connect
        ↓
Handshake
        ↓
READY
```

Server CLI 提供：

```bash
flux agents scan
flux agents list
flux agents connect <agent>
flux agents connect --all
flux agents remove <agent>
```

### 4.1 Discovery 原则

Scanner 负责发现事实，不负责强行修改用户环境。

例如：

```text
未安装      → DISCOVERED/NOT_INSTALLED
已安装      → VERIFIED
已认证      → CONNECTED
能够被 Flux 启动 → READY
```

所有发现结果应该可解释，例如：

```text
OpenCode
  executable: /usr/local/bin/opencode
  version: x.x.x
  auth: OK
  capabilities: MCP, stream
  status: READY
```

---

## 5. Flux Runtime Identity

Agent 被 Flux 托管启动时，注入机器可读 Runtime Identity：

```text
FLUX_RUNTIME=1
FLUX_VERSION=<version>
FLUX_RUN_ID=<run_id>
FLUX_TASK_ID=<task_id>
FLUX_WORKSPACE=<workspace>
FLUX_AGENT_ID=<agent_id>
FLUX_MCP_ENDPOINT=<endpoint>
```

这些变量只表示 Runtime 身份和运行信息，**不是安全凭证**。

Agent 不得通过伪造 `FLUX_*` 环境变量获得额外权限。

真正的权限判断必须由 Flux Runtime / MCP / Apply 层完成。

---

## 6. Agent Handshake v1

第一版不再单独制造一套与 MCP 平行的通信协议。

优先复用 MCP `initialize`：

```text
Agent STARTING
      ↓
MCP initialize
      ↓
Agent clientInfo / capabilities
      ↓
Flux validates
      ↓
flux_context
      ↓
READY
```

Agent 上报：

```json
{
  "protocol": "flux-agent",
  "version": "1",
  "agent": {
    "name": "...",
    "version": "..."
  },
  "capabilities": [
    "tool_call",
    "mcp",
    "stream"
  ]
}
```

Flux 返回机器可读的 Runtime 信息和能力。

### 6.1 为什么不单独做 HELLO 服务

当前阶段没有必要维护两套协议生命周期：

```text
独立 HELLO 协议
        +
MCP
```

会增加版本协商、连接管理和测试成本。

因此第一版：

> **MCP initialize + `flux_context` = Flux Agent Handshake v1。**

未来如果存在不支持 MCP 的 Agent，再增加专用握手层，而不是现在提前引入复杂度。

---

## 7. `flux_context`：让 Agent 真正知道自己在 Flux

Agent 不应该依赖一大段产品介绍 Prompt 来“理解 Flux”。

必须提供机器可读 Runtime Context：

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
    "title": "..."
  },
  "workspace": {
    "path": "/workspace/project",
    "git_branch": "main",
    "git_revision": "abc123"
  },
  "agent": {
    "id": "...",
    "adapter": "..."
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

### 7.1 与已有 `context.get` 的职责区别

两个接口不能混为一谈：

```text
flux_context
= Runtime / Run / Task / Workspace / Policy / Capabilities

context.get
= 项目事实 / 文档 / 代码上下文 / 项目约束
```

`flux_context` 解决“我在哪里、我能做什么”。

`context.get` 解决“这个项目是什么、我需要知道什么”。

---

## 8. Capability Discovery

Agent 必须读取 Flux 当前真实能力，而不是假设所有 Flux 环境都完整。

第一阶段能力集合：

```text
context
workspace
proposal
apply
rollback
git
```

后续可以扩展：

```text
browser
sandbox
mobile_test
```

能力缺失必须允许降级：

```text
capability = false
        ↓
Agent 不调用该能力
        ↓
继续完成可完成的任务
```

不能因为某个可选能力不存在就直接把整个 Run 判定为失败。

---

## 9. Flux MCP 第一阶段边界

协议层最终希望具备：

```text
flux_context
flux_task
flux_workspace
flux_proposal
flux_status
flux_logs
```

但第一阶段不要求一次性全部新增。

### P0

首先实现：

```text
flux_context
```

其它能力优先复用当前已经存在的 Flux 工具。

### 原因

避免出现：

```text
旧工具
+
新工具
+
重复语义工具
```

第一阶段目标是稳定 Runtime，而不是扩大 MCP 工具数量。

---

## 10. Proposal 必须成为一等对象

Agent 最终不能绕过 Flux 直接决定最终 Workspace 状态。

Proposal 统一结构：

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

Apply 固定流程：

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

---

## 11. Base Revision 与冲突检测

Proposal 创建时记录：

```text
base_revision
```

Apply 前重新检查 Workspace / Git 状态。

例如：

```text
Proposal base = abc123
        ↓
用户 / 其它 Agent 修改 Workspace
        ↓
Apply
        ↓
当前 revision != abc123
        ↓
Conflict
        ↓
拒绝静默覆盖
```

这是多 Agent / 长时间 Agent 运行环境下的必要保护。

---

## 12. Atomic Apply

多文件 Apply 必须具备事务语义。

```text
Proposal
   ↓
Validate
   ↓
Snapshot / Precondition Check
   ↓
Atomic Apply
   ├── file A
   ├── file B
   └── file C
   ↓
全部成功？
 ├── YES → Commit Apply Result
 └── NO  → Rollback
```

不能出现：

```text
A 成功
B 成功
C 失败

最终 Workspace：A/B 已修改，C 没修改
```

如果 Apply 失败，最终 Workspace 应恢复到 Apply 前状态。

---

## 13. Policy 与权限边界

`proposal_required` 是 Flux 的平台规则，而不是 Agent 自己决定的行为。

当：

```text
proposal_required = true
```

则：

```text
Agent
 ↓
Proposal
 ↓
Flux Policy Gate
 ↓
Approval
 ↓
Apply
```

Agent 不得通过：

- 修改环境变量；
- 伪造 Capability；
- 直接写 Workspace；
- 绕过 MCP；
- 调用内部未授权接口；

来获得 Apply 权限。

Workspace 路径同样不能由 Agent 任意扩大。

---

## 14. System Context：只负责认知入口

托管启动时，可以注入非常短的 System Context：

```text
You are running inside Flux, an AI coding orchestration platform.

Flux manages task lifecycle, workspace, MCP capabilities,
proposal validation, approval, applying changes, testing and git.

Use the provided Flux MCP tools to inspect runtime context and
platform capabilities. Follow the Flux proposal/apply workflow
when proposal_required is enabled.
```

这段 Prompt 只负责让 Agent 快速进入正确认知。

**真实状态必须以：**

```text
Runtime Identity
Handshake
flux_context
MCP results
```

为准。

不使用持久化 `AGENTS.md` 强制污染用户项目。

---

## 15. Agent Runtime 的超时与恢复

当前已有的分层设计继续保留：

```text
startup = 120s
idle    = 600s
hard    = 1800s
```

对于 OpenCode 等具体 Adapter，可以存在自己的单轮上限，例如：

```text
single turn = 900s
```

### 15.1 Timeout 不是简单的“进程超过 N 秒就失败”

Idle Timeout 应根据可靠活性信号判断：

```text
Agent event
MCP / Tool execution
heartbeat
stdout / stderr
状态变化
子进程活跃状态
```

只有在没有可靠活性信号时才触发 idle timeout。

### 15.2 Timeout / Cancel 后必须清理完整进程树

```text
落终态
  ↓
中断任务
  ↓
PGID 清理进程树
  ↓
宽限期
  ↓
强制 kill
  ↓
确认不存在孤儿进程
```

不能永久停留在：

```text
CANCELLING
```

---

## 16. Agent 失败与平台失败必须分离

最终结果不能只有：

```text
FAILED
```

至少区分：

```text
agent
platform
external
user
validation
apply
test
```

例如：

```text
FAILED
category=external
reason=rate_limit
```

与：

```text
FAILED
category=apply
reason=path_outside_workspace
```

必须是两个不同结果。

这样 Benchmark 才能真实评价 Agent，而不会把 Flux 自己的故障错误算到 Agent 能力上。

---

## 17. Circuit Breaker

保留当前真实任务测试中已经验证的止损机制：

```text
连续多个任务
“两轮都没有 Proposal”
        ↓
Circuit Breaker
        ↓
停止整批补跑
```

触发条件、计数器重置和最终状态必须结构化记录。

目的不是提高成功率数字，而是防止外部 Agent 异常时持续消耗时间、Token 和服务器资源。

---

## 18. Server CLI：Flux 的服务器入口

Server CLI 是整个方案的重要组成部分，但第一版不需要复杂 TUI。

优先提供稳定、脚本化的命令：

```bash
flux doctor

flux agents scan
flux agents list
flux agents connect <agent>
flux agents remove <agent>

flux run
flux task
flux status
flux logs

flux tools call <tool> ...
```

### 18.1 CLI 的职责

Server CLI 负责：

- Agent Discovery；
- Agent Connect；
- Server 运维；
- Run / Task 管理；
- Status / Logs；
- MCP 不可用时的兜底调用。

CLI 不应该重新实现一套 Agent Runtime。

---

## 19. MCP 与 Server CLI 的关系

两者不是竞争关系。

```text
                 Flux Core
                    │
             Agent Runtime
                    │
          ┌─────────┴─────────┐
          ↓                   ↓
       Flux MCP           Server CLI
          ↓                   ↓
       Agent                 User / Ops
```

正常 Agent 工作优先使用 MCP。

当 MCP 不可用或需要服务器运维时，Server CLI 提供兜底。

例如：

```bash
flux tools call flux_context
```

这使 Flux 在服务器环境下即使没有 Web UI，也仍然可以完成基本操作。

---

## 20. 第一阶段最终实施清单

### P0：Runtime 最小闭环

1. `FLUX_*` Runtime Identity 注入。
2. MCP `initialize` + `flux_context` Handshake v1。
3. `flux_context` MCP。
4. Capability Discovery。
5. Generic CLI Adapter。
6. Agent Runtime 生命周期统一。
7. Agent Installation 状态机。

### P0：可靠执行链

8. Proposal Contract 完整化。
9. `base_revision`。
10. Policy Gate。
11. Atomic Apply。
12. Apply Rollback。
13. Timeout / Cancellation / Process Tree Cleanup。
14. Failure Category。
15. Circuit Breaker。

### P1：一键接入与服务器能力

16. `flux agents scan`。
17. `flux agents list`。
18. `flux agents connect`。
19. `flux agents remove`。
20. 至少一个具体 CLI Adapter。
21. `flux doctor`。
22. `flux run/task/status/logs`。
23. `flux tools call` 兜底路径。

---

## 21. 推荐开发顺序

不要按照“功能页面”的顺序开发，而按照真实执行链路开发：

```text
1. Agent Discovery
        ↓
2. Agent Installation
        ↓
3. Generic CLI Adapter
        ↓
4. Agent Runtime
        ↓
5. Runtime Identity
        ↓
6. Handshake
        ↓
7. flux_context
        ↓
8. Capability Discovery
        ↓
9. Proposal Contract
        ↓
10. Policy Gate
        ↓
11. Base Revision Check
        ↓
12. Atomic Apply / Rollback
        ↓
13. Test
        ↓
14. Git
        ↓
15. Server CLI
        ↓
16. 重新执行真实任务 Benchmark
```

每完成一层，都应该有自动化测试和真实任务验证，而不是全部开发完成后再一次性验证。

---

## 22. 第一阶段验收标准

### Agent 接入

- [ ] `flux agents scan` 可以发现本机 CLI Agent。
- [ ] 可以看到路径、版本、认证状态、能力和 READY 状态。
- [ ] 可以一键 connect。
- [ ] Adapter 与 Flux Core 解耦。

### Runtime

- [ ] Agent 进程能够获得正确 `FLUX_*` Runtime Identity。
- [ ] Run / Task / Workspace / Agent ID 全部一致。
- [ ] Handshake 可观测。
- [ ] `flux_context` 返回真实 Runtime 状态。
- [ ] Capability 缺失可以正常降级。

### Proposal / Apply

- [ ] Proposal 具备 `base_revision`。
- [ ] Apply 前执行基线检查。
- [ ] Policy Gate 无法被 Agent 绕过。
- [ ] 多文件 Apply 失败可以 Rollback。
- [ ] Workspace 不会因为部分 Apply 成功而进入不可预测状态。

### Runtime Recovery

- [ ] startup / idle / hard timeout 分层生效。
- [ ] Cancel 可以清理完整进程树。
- [ ] 不存在永久 `CANCELLING`。
- [ ] 失败结果具有明确 category / reason。
- [ ] Circuit Breaker 能阻止异常 Agent 无限补跑。

### Server CLI

- [ ] Server 无 Web UI 时仍可执行基本管理操作。
- [ ] `flux agents scan/list/connect` 可用。
- [ ] `flux status/logs` 可用。
- [ ] `flux tools call` 可作为 MCP 兜底。
- [ ] CLI 与 Web / Mobile 使用同一 Flux Core / Runtime。

---

## 23. 第一阶段明确不做

当前 Personal MVP 阶段不因为 Agent Runtime 而扩大范围。

暂不做：

- 多 Agent 自动协商协议；
- Cloud Agent 专用协议；
- Marketplace 协议；
- Team / Enterprise 权限协议；
- Billing；
- Skill Marketplace；
- Connector Marketplace；
- 复杂 TUI；
- 非托管 Agent 的项目级强制引导；
- 为每个 Agent 单独实现一套 Runtime。

这些功能不影响第一阶段“Agent 在 Flux 中稳定运行”的核心目标。

---

## 24. 与当前 Personal MVP 的关系

当前 Flux 的优先级不是继续堆产品功能，而是把下面这条链真正做成稳定基础设施：

```text
Discovery
  ↓
Adapter
  ↓
Runtime
  ↓
MCP
  ↓
Proposal
  ↓
Apply
  ↓
Test
  ↓
Git
```

完成后再重新运行真实任务 Benchmark，并根据结果决定下一轮 P0/P1。

Personal 使用阶段的目标是：

> **让 Flux 能够稳定接入和管理 CLI Agent，而不是让 Flux 自己变成另一个 Agent。**

---

## 25. 最终架构结论

最终采用以下原则：

1. **只把 Flux 托管启动的 Agent 视为 Flux Runtime Agent。**
2. **用 `FLUX_*` 建立机器可读身份。**
3. **用 MCP initialize + `flux_context` 完成第一版握手。**
4. **用 Capability Discovery 而不是长 Prompt 描述平台能力。**
5. **`flux_context` 与已有 `context.get` 保持职责分离。**
6. **Adapter 只解决 Agent 差异，Runtime 负责执行生命周期。**
7. **Proposal 是 Agent 到 Flux 的正式变更边界。**
8. **Apply 必须经过 Policy Gate、Base Revision Check 和 Atomic Apply。**
9. **平台失败、Agent 失败、外部失败必须分开统计。**
10. **MCP 是 Agent 主通道，Server CLI 是运维和故障场景的兜底通道。**
11. **Web / Mobile / CLI 共用同一个 Flux Core 与 Agent Runtime。**
12. **第一阶段只做 CLI Agent 接入，不提前扩展 Cloud / Marketplace / Team 等复杂生态。**

最终状态应当是：

```text
                     Flux Core
                        │
                 ┌──────┴──────┐
                 ↓             ↓
             Web/Mobile      Server CLI
                 │             │
                 └──────┬──────┘
                        ↓
                  Agent Runtime
                        │
              ┌─────────┴─────────┐
              ↓                   ↓
        Agent Adapter       Runtime Identity
              │                   │
              └─────────┬─────────┘
                        ↓
                    Handshake
                        ↓
                  flux_context
                        ↓
              Capability Discovery
                        ↓
                      Task
                        ↓
                    Proposal
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

这就是当前 Flux Personal MVP 阶段 Agent Runtime 的最终落地边界。

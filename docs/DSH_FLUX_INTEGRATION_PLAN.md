# Flux × DeepSeek Harness 集成方案

> 状态：设计方案
>
> 目标：以 DeepSeek Harness（DSH）作为 Flux 内置 Agent Runtime 的基础，保留 DSH/Cordis 的 Agent、Plugin、Tool、Skill、Session 等能力，同时通过 Flux Bridge 注入 Flux 的 AI Engineering 能力。

## 1. 核心决策

Flux 当前不应继续从零开发完整 Agent。建议采用 DSH/Cordis 作为内置 Agent Runtime，Flux 保留自己的 Engineering Core。

- DSH/Cordis：Agent Loop、Session、Plugin Runtime、Tool Runtime、Skill、Subagent、LLM Runtime、事件系统。
- Flux：Task、Project Brain、Context、Virtual Workspace、Proposal、Permission、Sandbox、Test、Git、Operation Timeline。
- Flux Bridge：负责两侧通信、Capability 映射和权限控制。
- DSH Plugin System 尽量原生保留。
- AI 不得绕过 Virtual Workspace 直接修改真实项目文件。

## 2. 目标架构

```
                         Flux
┌─────────────────────────────────────────────────────┐
│              Flux Engineering OS                    │
│ Project Brain  Task  Workflow  Context              │
│ Virtual Workspace  Proposal/Diff  Permission        │
│ Sandbox  Test  Git  Operation Timeline              │
└───────────────────────┬─────────────────────────────┘
                        │
                 Flux Agent Bridge
                        │
                 HTTP / IPC / RPC
                        │
┌───────────────────────▼─────────────────────────────┐
│          Flux Built-in Agent                        │
│          DeepSeek Harness + Cordis                  │
│ Agent Loop  Session  Tool  Plugin  Skill  Subagent │
│ LLM  Events  Trace / Replay                         │
└───────────────────────┬─────────────────────────────┘
                        │
                  Flux DSH Plugins
                        │
       ┌────────────────┼─────────────────┐
       │                │                 │
 Flux Workspace     Flux Brain       Flux Sandbox
 Flux Proposal      Flux Task        Flux Test
 Flux Context       Flux Git         Flux Operation
```

## 3. 为什么现在切换

当前 Flux Agent Runtime 的主要职责仍是一次 ModelRouter 调用。如果继续加入 Agent Loop、Tool Calling、Session、Plugin、Skill、Subagent、Streaming、Replay，会重新实现一个简化版 DSH。

因此现有 Agent Runtime 应逐步从：

```
AgentManager → AgentExecutor → ModelRouter.chat()
```

演进为：

```
AgentManager → FluxAgentRuntime → DSHClient → DSH Agent
```

现有 Manager、类型和生命周期接口可以保留；一次模型调用型 Executor 逐步迁移到 DSH。

## 4. DSH 能力直接复用

原则上不重新实现：

- Cordis Runtime
- Plugin lifecycle / loading / dependency / injection
- Agent / Agent Loop / AgentHandle
- Session
- LLM abstraction
- Tool Registry
- Skill Runtime
- Subagent
- Event system
- Profile / Bundle
- Trace / Replay

DSH Plugin System 是采用 DSH 的重要原因之一。

## 5. DSH Plugin 原生兼容

目标体验：

```
用户下载 DSH Plugin
        ↓
Flux Plugin Manager
        ↓
识别 DSH/Cordis Plugin
        ↓
加载到 DSH Runtime
        ↓
Plugin.apply(ctx)
        ↓
正常运行
```

Flux 不首先修改社区 DSH Plugin。优先让插件继续看到熟悉的 Cordis/DSH API。

## 6. Flux Core Plugin

建议建立核心 DSH Plugin：`flux-core`。

建议目录：

```
agent-runtime/
└── dsh/
    └── flux-plugins/
        └── flux-core/
            ├── index.ts
            ├── services/
            │   ├── project.ts
            │   ├── task.ts
            │   ├── brain.ts
            │   ├── context.ts
            │   ├── workspace.ts
            │   ├── proposal.ts
            │   ├── sandbox.ts
            │   ├── test.ts
            │   ├── git.ts
            │   └── operation.ts
            └── tools/
```

作用：DSH/Cordis → Flux Core Plugin → Flux Internal API → Python Engineering Core。

## 7. Flux Capability / Tool API

第一阶段优先暴露：

```
flux.project.*
flux.task.*
flux.context.*
flux.brain.*
flux.workspace.*
flux.proposal.*
flux.sandbox.*
flux.test.*
flux.git.*
flux.operation.*
```

优先工具：

- project.read
- task.get
- context.get
- brain.search
- workspace.read
- workspace.search
- workspace.propose
- sandbox.exec
- test.run
- git.status
- git.diff
- operation.record

## 8. Virtual Workspace 是强约束

DSH Agent 可以拥有工具调用能力，但不能绕过 Flux Virtual Workspace。

```
DSH Agent
    ↓
flux.workspace.read
    ↓
分析代码
    ↓
flux.workspace.propose
    ↓
Proposal
    ↓
Diff
    ↓
Human Review
    ↓
ApplyEngine
    ↓
真实文件
```

`workspace.apply` 不能成为普通 Agent Tool 的直接写文件能力；真实落盘仍由 ApplyEngine 控制，并进行 hash check。

## 9. Permission Engine

所有 DSH Plugin 和 Agent 的 Flux 能力必须经过 Flux Permission Engine。

| Capability | 默认策略 |
| --- | --- |
| project.read | Allow |
| context.read | Allow |
| brain.search | Allow |
| workspace.read | Allow |
| workspace.search | Allow |
| proposal.create | Allow |
| sandbox.exec | Allow（受 Sandbox 策略限制） |
| test.run | Allow |
| workspace.apply | Require Approval |
| git.commit | Require Approval |
| git.push | Deny / Require Approval |
| secret.read | Deny |

安装 DSH Plugin 不等于获得 Flux 全部权限。

## 10. Python 与 DSH 通信

DSH 是 Node/TypeScript Runtime，而 Flux Engineering Core 当前是 Python。第一阶段建议使用明确的 Agent Bridge。

```
Python Flux Core
      │
      │ localhost HTTP / Unix Socket / RPC
      ▼
Node DSH Runtime
      │
      ▼
DSH Agent
```

初期可以使用 localhost HTTP，接口保持内部 Agent API 语义，例如：

```
POST /api/v1/agent-runtime/workspace/read
POST /api/v1/agent-runtime/workspace/propose
POST /api/v1/agent-runtime/brain/search
POST /api/v1/agent-runtime/test/run
POST /api/v1/agent-runtime/git/status
POST /api/v1/agent-runtime/operation/record
```

未来可以替换为 Unix Socket / JSON-RPC / 其他 IPC，而不改变 Flux Capability 层。

## 11. Model Gateway

Flux 当前的 Model Gateway 不删除，但职责逐步调整为模型选择与策略层：

```
Flux Model Router
    ↓
Provider / Model / Budget / Fallback
    ↓
DSH LLM Adapter
    ↓
DSH Agent
```

第一阶段不要同时重构 Model Gateway。先让 DSH 使用成熟的 LLM Runtime，Flux 记录 Agent Run / Model / Usage。稳定后再实现 DSH → Flux Model Gateway。

## 12. Session 与 Context 分层

DSH Session 与 Flux Context 不合并。

DSH Session 负责：
- Agent messages
- Model output
- Tool calls / results
- Agent events
- Session state

Flux Context 负责：
- Task
- Requirement
- Project Brain
- Relevant files
- Decisions
- Agent Handoff
- Project conventions
- Previous engineering results

最终：DSH Session + Flux Context → Flux Agent Context。

不要把完整 Project Brain 一次性塞进 system prompt，应通过 `flux.brain.search`、`flux.context.get` 等能力按需读取。

## 13. Project Brain

Project Brain 作为 Flux Service / Tool 暴露，例如：

```
ctx.projectBrain
flux.brain.search
```

Agent 可以先搜索项目知识，再决定读取哪些实际文件。

## 14. Operation Timeline

DSH Trace 和 Flux Operation Timeline 分层：

- DSH Trace：Agent 执行轨迹。
- Flux Operation Timeline：工程操作轨迹。

典型流程：

```
Task Started
 ↓
Agent Started
 ↓
Brain Search
 ↓
File Read
 ↓
Proposal Created
 ↓
Human Review
 ↓
Apply
 ↓
Test
 ↓
Repair
 ↓
Git Commit
```

## 15. AgentRun 统一结果

Flux 最终应把 DSH Run 聚合为统一 AgentRun：

```
AgentRun
├── run_id
├── task_id
├── agent
├── provider
├── model
├── status
├── started_at / finished_at
├── duration
├── token_usage
├── cost
├── tool_calls
├── files_changed
├── proposals
├── tests
├── operations
└── error
```

这将直接服务未来 Agent Team Room、Cost Center 和 Operation Timeline。

## 16. DSH Vendor / Upstream 策略

DSH 当前处于快速迭代阶段，因此 Flux 不应让 Python/业务层直接依赖 DSH 内部实现。

建议：

```
agent-runtime/
└── dsh/
    ├── vendor/
    │   └── deepseek-harness/
    ├── flux-plugins/
    ├── patches/
    └── UPSTREAM.md
```

`UPSTREAM.md` 记录 DSH version 和 commit。

原则：
1. 尽量不修改 vendor 中的 DSH 核心代码。
2. Flux-specific 逻辑优先写成 Plugin。
3. 必须修改 DSH 时使用小而明确的 patch。
4. 每次升级 DSH 后运行 Flux Compatibility Test。
5. Flux 业务层不直接 import DSH 内部实现。

## 17. 推荐代码结构

### Flux Python

```
backend/flux/
├── core/
│   ├── agent_runtime/
│   │   ├── manager.py
│   │   ├── dsh_client.py
│   │   ├── events.py
│   │   └── types.py
│   ├── task_engine/
│   ├── project_brain/
│   ├── virtual_workspace/
│   ├── permission_engine/
│   ├── model_gateway/
│   └── ...
└── api/
```

### DSH Runtime

```
agent-runtime/
└── dsh/
    ├── vendor/
    │   └── deepseek-harness/
    ├── flux-plugins/
    │   ├── flux-core/
    │   ├── flux-context/
    │   ├── flux-brain/
    │   ├── flux-workspace/
    │   ├── flux-proposal/
    │   ├── flux-sandbox/
    │   ├── flux-test/
    │   ├── flux-git/
    │   └── flux-operation/
    └── patches/
```

## 18. 实施顺序

### Phase 1：DSH Runtime
1. 引入 DSH。
2. 建立 Node Agent Runtime。
3. 启动 DSH headless/profile runtime。
4. Flux 创建 DSH Agent。
5. Flux 发送任务。
6. 接收 streaming events。
7. 支持 interrupt。
8. 支持完成/失败状态。

验收：Flux → DSH → DeepSeek → Agent Response。

### Phase 2：Flux Bridge
- Flux Agent Client
- DSH Event Bridge
- Agent Run 状态同步
- Session ID / Task ID 映射
- 错误映射

### Phase 3：Flux Core Plugin
首先接入 Project、Task、Context、Brain。

### Phase 4：Workspace / Proposal
接入 workspace.read、workspace.search、workspace.propose，并禁止 Agent 直接写真实工作区。

验收：Requirement → DSH Agent → Read → Analyze → Proposal → Diff。

### Phase 5：Apply / Operation
接入 Human Review、ApplyEngine、Hash Check、Operation Timeline、Restore Point。

### Phase 6：Test / Repair
形成 Apply → Test → Failure → DSH Agent → New Proposal → Apply → Test 的修复循环。

### Phase 7：DSH Plugin Compatibility
接入 DSH Plugin Manager、Plugin discovery、Plugin installation、Plugin dependency、Profile / Bundle、Plugin permissions。

最终目标：社区 DSH Plugin 无需修改源码即可在 Flux 中运行。

## 19. 后续 Compatibility Engine

原生 DSH Plugin 兼容稳定后，再做更广泛的 Skill / Plugin / MCP 自动适配：

```
External Skill / Plugin / MCP
        ↓
Compatibility Analyzer
        ↓
Capability Mapping
        ↓
Permission Mapping
        ↓
Adapter Generation
        ↓
Sandbox Test
        ↓
Compatibility Report
        ↓
Install
```

示例：

```
filesystem.read  → flux.workspace.read
filesystem.write → flux.workspace.propose
shell.exec       → flux.sandbox.exec
```

自动适配是第二层能力；原生 DSH Plugin 兼容优先。

## 20. 第一阶段明确不做

- 自研 Agent Loop
- 自研 Plugin Runtime
- 自研 Session Runtime
- 自研 Subagent Runtime
- 自研 Tool Registry
- 自动适配所有第三方插件
- Marketplace
- Cloud Agent
- 多 Agent 分布式调度
- 复杂 Model Routing
- 移动端 Agent 控制

先完成一个真实工程任务闭环。

## 21. 第一条完整 Demo 链路

```
用户：给项目增加 GitHub OAuth 登录
 ↓
Flux Task
 ↓
DSH Agent
 ↓
Project Brain
 ↓
读取相关代码
 ↓
Developer Agent Loop
 ↓
Flux Workspace Proposal
 ↓
Diff
 ↓
用户 Review
 ↓
Accept
 ↓
ApplyEngine
 ↓
Operation Timeline
 ↓
Tester
 ↓
失败 → DSH Agent Repair
 ↓
测试通过
 ↓
Git Commit
```

## 22. 核心原则

1. **不重新造 Agent**：DSH 提供 Agent Runtime。
2. **保留 Plugin System**：DSH/Cordis 是采用 DSH 的核心价值之一。
3. **Flux 不等于 DSH**：DSH 管 Agent 执行，Flux 管工程系统。
4. **Agent 不直接改真实文件**：必须经过 Virtual Workspace → Proposal → Review → Apply。
5. **Flux 能力通过 Capability/Plugin 暴露**：不直接暴露内部数据库。
6. **权限统一由 Flux 控制**：插件安装不等于获得全部权限。
7. **保持上游可升级**：尽量用 Plugin 扩展，减少 fork patch。
8. **先原生兼容，再 AI 自动适配**。

## 23. 最终定位

Flux 不是重新发明一个 Coding Agent，而是：

> **AI Engineering OS + Built-in Agent Runtime**

其中 DeepSeek Harness/Cordis 提供 Agent 执行基础设施；Flux 的核心差异化仍然是 Project Brain、Task、Context、Virtual Workspace、Proposal、Permission、Sandbox、Test、Operation Timeline、Git 和 Workflow。

最终关系：

```
DeepSeek Harness
        +
Flux Engineering OS
        +
DSH Plugin Ecosystem
        ↓
Flux Built-in Agent
```

目标不是做一个更好的 DeepSeek Harness，而是利用成熟 Agent Runtime，把开发精力集中在 Flux 真正差异化的 AI Engineering OS 能力上。
# Flux 架构偏差整改说明

> 用途：提供给 Flux 开发 Agent，作为当前架构重新对齐的依据。
>
> 这不是要求立即重写全部代码，而是要求先理解并确认 Flux 的架构边界，再决定哪些实现保留、重构、迁移或删除。
>
> 依据：`FLUX_PROJECT_OVERVIEW.md`、`FLUX_PROJECT_OVERVIEW(1).md` 与当前仓库实际代码检查结果。

## 1. 最重要的架构结论

**Flux 不是 Agent。**

Flux 的定位是 **AI 软件工程操作系统 / AI 软件工程平台**。

Flux 负责：
- Task
- Context
- Project Brain
- Project Scanner
- Project Files
- Workspace
- Proposal
- Diff
- Apply
- Permission
- Skill
- Connector
- Git
- Operation / Event
- MCP Server

Flux **不应该自己成为一个拥有完整 Agent Loop 的 Agent**。

固定原则：
- Flux 不做 Agent Loop
- Flux 不编排 Agent 内部对话
- Agent 自己拥有 loop
- Agent 通过 Flux MCP 使用 Flux 能力

## 2. 正确的整体架构

```text
                         FLUX
                AI Engineering Platform
                           │
        ┌──────────────────┼──────────────────┐
        │                  │                  │
      Task              Context           Capability
        │                  │                  │
        │          ┌───────┴───────┐          │
        │          │               │          │
        │        Brain         Workspace      │
        │          │               │          │
        │       Archive         Proposal      │
        │          │               │          │
        │        Handoff          Diff        │
        │          │               │          │
        └──────────┴───────┬───────┴──────────┘
                           │
                       Flux MCP
                           │
             ┌─────────────┼─────────────┐
             │             │             │
            DSH          Codex         Claude
             │             │             │
          own loop      own loop      own loop
          own session   own session   own session
```

**Flux 是 Agent 的工程基础设施和能力提供方，而不是 Agent 本身。**

## 3. 当前实现的主要架构偏差

### 3.1 Agent Runtime 被做成了 Flux 的核心执行层

当前存在：
- `AgentManager`
- `AgentExecutor`
- `DeveloperAgent`
- `TesterAgent`
- `WorkflowEngine`
- `ModelGateway`

这些模块本身没有问题，但组合起来目前更接近：

```text
Flux
 ↓
AgentManager
 ↓
AgentExecutor
 ↓
ModelGateway
 ↓
DeveloperAgent
 ↓
模型
```

这会把 Flux 变成 Agent Orchestrator / Agent Runtime。

正确方向：

```text
DSH / Codex / Claude / OpenCode
        │
        │ Agent 自己的 loop
        ↓
   Flux MCP Server
        │
        ├── context.get
        ├── brain.search
        ├── skill.get
        ├── connector.*
        ├── proposal.create
        ├── handoff.put
        └── operation.record
```

**不要继续扩大 Flux 内部 Agent Loop。**

## 4. DSH 的定位需要纠正

错误理解：

```text
Flux
 ↓
DSH API
 ↓
获得 Agent 结果
```

或：

```text
Flux Model Gateway
 ↓
DSH
```

正确定位：

> Flux 内置一个基于 DSH 的 Agent，但“内置 Agent”不等于“Flux 本身就是 Agent”。

```text
Flux
 │
 └── MCP Server
          ↑
          │
      DSH Agent
          │
          ├── 自己的 loop
          ├── 自己的 session
          ├── 自己的 Agent 状态
          └── 通过 MCP 使用 Flux 能力
```

同时 Codex、Claude Code、OpenCode 也可以通过同一个 Flux MCP 使用能力。

**DSH 是 Flux 随项目交付的 Agent Runtime / Agent 实现，不是 Flux 的模型 Provider。**

## 5. 当前 DSH 与 Developer Agent 存在架构断点

当前 DSH 已有独立 Client / Runtime 能力，但 Developer Agent 仍主要走：

```text
DeveloperAgent
 ↓
AgentManager
 ↓
AgentExecutor
 ↓
ModelGateway
```

而不是：

```text
Developer Agent / DSH
 ↓
自己的 Agent Loop
 ↓
Flux MCP
 ↓
Flux 工程能力
```

因此当前更像：

```text
Flux
 ├── 原 Agent Runtime
 └── DSH Runtime
```

目标应该是：

```text
Flux
 └── MCP / Platform
       │
       ├── 内置 DSH Agent
       ├── Codex
       ├── Claude
       └── OpenCode
```

## 6. Context 架构

建议严格保持：

```text
L0 Project Brain
L1 Task Context
L2 Run Context
L3 Agent Conversation
```

其中：

```text
L0-L2 → Flux
L3   → Agent
```

Flux 保存：
- 项目事实
- 项目结构
- Task
- Run
- 文件
- Proposal
- Diff
- 测试结果
- Operation
- Evidence
- Handoff

Agent 自己保存：
- 对话历史
- 自己的 reasoning/session
- Agent 私有上下文

不要把 Agent 的完整 conversation 作为 Flux 的共享上下文。

## 7. Agent 之间不要搬运完整上下文

错误：

```text
Agent A
 ↓
完整 conversation
 ↓
Agent B
```

正确：

```text
Agent A
 ↓
Handoff
 ↓
Flux
 ↓
Agent B
```

Handoff 至少应该描述：
- 完成了什么
- 未完成什么
- 关键决策
- 未决问题
- 涉及文件
- 文件 hash
- 下一步

## 8. 小模型的定位

Flux 的本地小模型不是 Flux 的“超级 Agent”。

不应该设计成：

```text
0.5B
 ↓
Tech Lead
 ↓
管理其他 Agent
```

主要用途是 Context Infrastructure：

```text
T1 规则压缩
 ↓
T2 本地小模型增量摘要
 ↓
T3 Agent 可选二次裁剪
```

因此：

**内置 Agent ≠ 内置小模型。**

内置 Agent 是 DSH；小模型属于 Flux Context Infrastructure。

## 9. Workflow Engine 的边界

Workflow Engine 可以负责：
- Task 状态
- 阶段
- Agent handoff
- 等待
- 重试
- 审批
- 下一阶段
- 工程流程状态

但不能替 Agent 做 reasoning。

错误：

```text
Flux Workflow
 ↓
替 Developer 思考
 ↓
替 Developer 调工具
 ↓
替 Developer 决定下一步
```

正确：

```text
Flux Workflow
 ↓
当前 Task 阶段
 ↓
需要哪个 Agent
 ↓
Agent 自己执行
 ↓
Agent 提交结果 / handoff
 ↓
Flux 推进工程状态
```

**Workflow 是工程状态机，不是 Agent 的大脑。**

## 10. Permission Engine 的边界

正确结构：

```text
Agent
 ↓
Flux MCP
 ↓
Permission Engine
 ↓
Connector / Workspace / Proposal
```

高风险能力应由 Flux 控制，例如：
- `workspace.apply`
- `git.push`
- `secret.read`

可向 Agent 暴露：
- `context.get`
- `context.fetch`
- `brain.search`
- `skill.get`
- `handoff.put`
- `proposal.create`
- `operation.record`

Agent 不应该绕过 Flux MCP 直接修改真实 workspace。

## 11. Virtual Workspace / Proposal 是核心安全边界

核心原则：

> Agent 永远不应该直接覆盖真实文件。

```text
Agent
 ↓
proposal.create
 ↓
Virtual Diff
 ↓
Human Review
 ↓
Apply
 ↓
Real Workspace
```

真实文件写入应该集中在 Flux 的受控 Apply 层。

## 12. Apply Engine 的安全要求

Project File Explorer 已经有 symlink 保护，但 Apply / Backup 也必须具备同等级别保护。

必须防止：

```text
workspace/
  config.py -> /outside/secret.py
```

然后 Agent 提交 `config.py`，最终写到 workspace 外部。

Apply 前必须验证：
- 路径没有 `..`
- 路径不是绝对路径
- 目标路径不逃逸 workspace
- symlink 不允许导致 filesystem object 逃逸
- backup 同样不能跟随恶意 symlink
- 创建目录时不能通过 symlink 逃逸

## 13. 多文件 Apply 应保持操作级原子性

如果一次 Proposal 有：

```text
A.py
B.py
C.py
```

目标：

```text
全部成功
```

或者：

```text
任何一个失败
 ↓
全部恢复
```

不能出现：

```text
A 成功
B 成功
C 失败
 ↓
A/B 留下修改
```

后续应考虑 Proposal / ChangeSet 级别的 Batch Apply Transaction。

## 14. Project Brain 必须成为 Agent 的真实能力

Brain 不应该只是数据库里存在。

应该真正成为：

```text
Agent
 ↓
Flux MCP
 ↓
brain.search
 ↓
Project Brain
 ↓
找到相关代码 / 架构 / 文档
```

例如：

> “修改登录功能。”

Agent 应能够通过 Brain 找到：
- Auth
- User
- API
- Database
- Tests
- 相关架构文档

## 15. 外部 Agent 是 Flux 的一等公民

至少包括：

```text
DSH
Codex
Claude Code
OpenCode
```

统一通过：

```text
Flux MCP Server
```

获得 Flux 能力。

不要只为某个 Agent 建立特殊能力体系。

## 16. MCP 应成为唯一能力出口

目标：

```text
                    Flux MCP Server
                          │
        ┌─────────────────┼─────────────────┐
        │                 │                 │
       DSH              Codex             Claude
        │                 │                 │
        └─────────── 使用 Flux 能力 ────────┘
```

桌面端、内置 Agent、外部 Agent 都围绕这一能力出口设计。

## 17. UI 应消费真实工程事件

前端最终应该消费 Flux Event Bus：

```text
agent.started
agent.completed
task.created
task.updated
proposal.created
proposal.applied
workspace.changed
test.started
test.completed
git.changed
usage.recorded
dsh.event
```

目标：

```text
Backend Event
 ↓
Event Bus
 ↓
WebSocket / SSE
 ↓
Dashboard
```

不要让前端按钮自己制造“Agent 已完成”之类的假事件。

## 18. 多 Project 必须真正隔离 Workspace

目标：

```text
Project A
 ↓
Workspace A
 ↓
Repository / Local Path A

Project B
 ↓
Workspace B
 ↓
Repository / Local Path B
```

Project ID 必须真正决定 workspace / repository 上下文。

## 19. 可以保留的现有模块

不要因为架构偏差就全部推倒。

以下方向可以继续保留：
- Project Scanner
- Project Brain
- Project Files
- Virtual Workspace
- Proposal
- Diff Engine
- Apply Engine
- Backup / Rollback
- Tester
- Git Integration
- Permission Engine
- Event Bus
- Task Model
- Context Model
- Connector Model
- Skill Model
- DSH 适配基础
- MCP Server 基础

## 20. 需要重新定义边界的模块

重点重新审查：

```text
AgentManager
AgentExecutor
DeveloperAgent
TesterAgent
WorkflowEngine
ModelGateway
```

不要简单删除。

需要先回答：
1. 这个模块是在运行 Flux 平台能力，还是在运行 Agent 的大脑？
2. Agent loop 到底归谁？
3. 模型调用到底归谁？
4. Agent session 到底归谁？
5. Flux MCP 是否唯一能力出口？
6. 外部 Agent 是否可以与内置 DSH 获得同等 Flux 能力？
7. Proposal / Apply 是否完全由 Flux 控制？

## 21. 建议开发顺序

### Phase 1：Architecture Alignment

先输出：

```text
当前模块
→ 正确职责
→ 是否保留
→ 是否重构
→ 是否删除
→ 新边界
```

**不要直接重写。**

### Phase 2：MCP Capability Layer

统一整理：

```text
context
brain
skill
connector
handoff
proposal
operation
workspace
```

成为 Flux MCP 能力。

### Phase 3：DSH Integration

让：

```text
DSH Agent
 ↓
Flux MCP
```

真正成立。

### Phase 4：External Agent

验证：

```text
Codex
Claude Code
OpenCode
```

均可通过相同 MCP 能力工作。

### Phase 5：真实工程闭环

最终验证：

```text
Requirement
 ↓
Agent
 ↓
Context / Brain
 ↓
Proposal
 ↓
Human Review
 ↓
Apply
 ↓
Test
 ↓
Repair
 ↓
Test Pass
 ↓
Git
```

## 22. 架构验收问题

开发 Agent 在继续开发前必须能够回答：

### Q1：Flux 是 Agent 吗？
不是。

### Q2：Flux 有没有内置 Agent？
有。基于 DSH + Cordis，但 Flux 不等于这个 Agent。

### Q3：Agent Loop 在哪里？
Agent 自己。

### Q4：Agent 如何使用 Flux？
通过 Flux MCP Server。

### Q5：Codex / Claude / OpenCode 与 DSH 的关系？
都是 Flux 能力的消费者。

### Q6：Flux 保存什么 Context？
Project Brain、Task Context、Run Context、Archive、Handoff、Engineering Evidence。

### Q7：Agent 保存什么？
自己的 conversation、session、reasoning / loop。

### Q8：真实文件谁最终修改？
Flux 的受控 Apply 层。

### Q9：Agent 能否绕过 Proposal 直接写真实项目？
不能。

### Q10：Flux 的核心价值是什么？
不是自己成为更强的 Agent，而是让不同 Agent 在同一个受控的软件工程环境中，共享工程事实、Context、Skill、Connector、Workspace、Proposal、Git 和操作历史。

---

# 23. 最终要求

**请不要继续把 Flux 按“一个拥有很多 Agent 的 AI Coding Agent”开发。**

应该严格按照：

> **“一个供多个 Agent 使用的 AI 软件工程平台，同时内置一个基于 DSH 的默认 Agent。”**

继续开发。

在架构没有重新对齐之前：

1. 不要继续大规模扩展 AgentManager / AgentExecutor。
2. 不要把 Flux 的 Workflow 做成 Agent Loop。
3. 不要把 DSH 当 Model Provider。
4. 不要让 Flux 接管 Agent 的私有 conversation。
5. 不要让 Agent 绕过 Flux MCP 直接修改真实 workspace。
6. 先完成“规格 → 当前代码 → 偏差 → 重构边界”的分析。
7. 分析完成后再修改代码。

**先对齐架构，再写代码。不要让现有实现反过来决定 Flux 应该是什么。**

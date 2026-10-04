# Flux 竞争策略与成熟能力复用方案

> 版本：v0.1
> 日期：2026-10-04
> 状态：讨论结论 / 架构策略文档

## 1. 文档目的

本文总结近期对 Flux 定位、市场竞争产品、成熟能力复用以及商业化可行性的讨论，并形成后续开发应遵循的原则。

核心结论：

> **Flux 不应该重新实现整个 Agent 生态，而应该成为 Agent 的统一控制与执行层，通过 Protocol / Adapter 连接成熟的 Agent、Sandbox、Browser、Git、MCP、模型和基础设施。**

Flux 自己真正需要建立的是跨工具的统一边界、状态、上下文和执行生命周期。

---

## 2. Flux 当前定位

Flux 的目标定位进一步收敛为：

> **Agent Control & Execution Runtime**

中文可描述为：

> **AI Agent 的可控执行层。**

Flux 不负责让 Agent 更聪明，也不应该重新实现各种 Agent Loop。它主要负责：

- Agent 接入与统一协议
- Flux Environment
- Workspace
- Context / Memory
- Capability
- Permission / Policy
- Task 生命周期
- Virtual Workspace
- Proposal / Diff
- Validation
- Apply / Rollback
- Agent 间上下文和工作状态衔接

核心思想：

```text
Agent = Intelligence
Flux  = Control + Environment + Execution
```

Agent 可以决定“我要做什么”，但 Flux 决定“它能做什么、在哪里做、能访问什么、最终哪些变化可以进入真实环境”。

---

## 3. 当前 Flux 已有能力

根据当前仓库与近期开发状态，Flux 已经形成以下核心基础：

### 3.1 Agent / Runtime

- Agent Runtime
- Agent 生命周期状态机
- Agent Profile / Manifest 基础
- DSH Client / Runtime 基础
- 外部 Agent 接入方向
- Model Gateway
- OpenAI / Anthropic / DeepSeek / local provider 适配

注意：Flux 的目标架构已经明确要求退役自建 Agent Loop；Flux 不应重新成为一个新的 Coding Agent。

### 3.2 Virtual Workspace

这是当前 Flux 最重要的核心机制之一：

```text
Agent
  ↓
Proposal
  ↓
Virtual Diff
  ↓
Review
  ↓
Apply
  ↓
真实 Workspace
```

已有/设计中的能力包括：

- Proposal
- Diff
- Accept / Reject
- Group Review
- ApplyEngine
- Hash / Conflict 检查
- 原子 Apply
- Backup
- Rollback
- 审计事件
- Symlink 逃逸防护

真实用户文件的写入应保持唯一入口：**ApplyEngine**。

### 3.3 项目与开发能力

- Project Scanner / Project Brain 基础
- File Explorer
- Code Editor
- Diff Viewer
- Git 能力
- Tester / Validation 基础
- Workflow / Task Engine
- Permission Engine
- Event Bus
- Connector Registry / Contract
- API / OpenAPI 契约
- Docker / CI / Verify 工程化基础

### 3.4 规划中的重要能力

以下仍属于规划/演进方向，不能误认为全部已经完成：

- User Memory
- Project Memory
- Flux Environment 强制上下文
- Skill Protocol
- Connector Protocol
- 外部 Agent 一键扫描接入
- Skill 自动同步与适配
- Connector 自动同步与适配
- Skill 安全审查
- Skill 重复检测
- Execution Sandbox
- Resource Gateway
- 外部资源请求与缓存
- Agent 网络访问控制
- Browser Automation
- CLI
- Mobile Controller
- Marketplace
- Cloud Agent / Cloud Sandbox
- Team / Enterprise 能力

---

## 4. 市场竞争格局

市场上已经存在大量比 Flux 当前单项能力更成熟的产品，因此 Flux 不应该与它们逐项重复造轮子。

### 4.1 Agent / Agent Platform

代表：

- OpenHands
- Claude Code
- Codex
- OpenCode
- Cline / Roo Code
- Cursor
- GitHub Copilot
- Muse Code
- Agent Muse / SiaFlow

它们已经在 Agent Loop、Coding、IDE、Workflow、Skills、MCP、Background Agent、企业治理等方面形成成熟能力。

**结论：Flux 不应把“自己做一个更强 Agent”作为核心路线。**

### 4.2 Agent Control Plane / Workspace

最值得关注的是 OpenHands Enterprise、Agent Muse / SiaFlow 等。

它们已经覆盖部分：

- Agent 管理
- Workspace
- Permission
- Approval
- Sandbox
- Audit
- Multi-Agent
- Workflow
- Enterprise Governance

**结论：Flux 的差异不能只是“支持多个 Agent”，必须进一步深入到统一 Runtime、Capability、Context、Environment 和最终 Apply 边界。**

### 4.3 Sandbox / Execution Infrastructure

代表：

- Daytona
- E2B
- Modal / 类似 Agent Sandbox 基础设施
- Docker / VM / Kubernetes 等底层环境

这些产品已经可以提供成熟的隔离执行环境。

**结论：Flux 不应从零重新制造 Sandbox 基础设施。应该定义 Flux Sandbox Interface，通过 Adapter 接入 Local Docker、Daytona、E2B 或其他 Provider。**

### 4.4 IDE

代表：

- Cursor
- VS Code + Agent
- Windsurf 等

**结论：Flux 不应把“做一个更好的 AI IDE”作为主要竞争点。IDE 应作为 Flux Runtime 的一个客户端。**

---

## 5. Flux 真正应该竞争的点

### 5.1 Agent-Agnostic Runtime

Flux 不绑定单一 Agent。

目标：

```text
Codex
Claude Code
OpenCode
OpenHands
DSH
其他 Agent
       ↓
Flux Agent Protocol
       ↓
统一 Runtime
```

Agent 可以替换，而 Workspace / Context / Capability / Task 状态不应该随之消失。

### 5.2 Flux Environment

Agent 进入 Flux 后必须知道：

- 自己是谁
- 当前 Workspace
- 当前 Project
- 当前 Task
- 当前 Environment
- 当前 Context
- 自己拥有什么 Capability
- 哪些操作需要审批
- 哪些资源不可访问

这比单纯“接入 Agent”更重要。

### 5.3 Capability Boundary

Agent 不应该直接操作现实环境，而应该通过 Flux Capability：

```text
Agent
 ↓
Capability Request
 ↓
Flux Policy
 ↓
Allow / Deny / Approval
 ↓
Provider
```

示例：

- read_file
- proposal.create
- execute_command
- network_request
- github
- browser
- database
- search

### 5.4 Context / Memory Continuity

Flux 应维护：

- User Context
- Project Context
- Task Context
- Agent Context
- Environment Context
- Decision History

这样 Agent A 分析后，Agent B 可以继续工作，而不必重新理解整个项目。

### 5.5 Virtual Workspace + Apply

当前已经实现/设计的：

```text
Agent
 ↓
Proposal
 ↓
Diff
 ↓
Test / Validation
 ↓
Review
 ↓
ApplyEngine
 ↓
真实 Workspace
```

这是 Flux 值得继续保留的核心机制。

“允许落盘”应被视为一次受控的 Apply，而不是简单的文件复制。

### 5.6 Agent 生命周期与结果可恢复性

Flux 应统一管理：

```text
Create
→ Run
→ Pause
→ Resume
→ Review
→ Apply
→ Rollback
→ Destroy
```

这样 Agent 不再是一次性黑盒进程，而是一个可管理的执行实体。

---

## 6. Virtual Workspace 与 Sandbox 的关系

当前不建议因为“Flux 是控制层”就立即把所有项目文件复制进完整 Sandbox。

当前 Virtual Workspace 已经解决最关键的问题：

> **Agent 不能直接修改真实项目；真实写盘必须经过 Flux 的 ApplyEngine。**

因此当前优先保持：

```text
Agent
 ↓
Flux Capability
 ↓
Read / Proposal
 ↓
Virtual Workspace
 ↓
Diff / Validation
 ↓
Review
 ↓
ApplyEngine
 ↓
Real Workspace
```

只有当真实 Agent 执行任务时暴露出需要隔离 Shell、Build、Test、Runtime 等能力时，再增加 Execution Sandbox：

```text
Agent
 ↓
Execution Sandbox
 ↓
Shell / Build / Test
 ↓
Proposal
 ↓
Review
 ↓
Apply
```

因此：

> **Virtual Workspace 是当前 Flux Core；Execution Sandbox 是后续可插拔执行能力，而不是当前必须重做的基础。**

---

## 7. “成熟能力复用”原则

Flux 后续开发应遵循：

> **先找成熟方案 → 能调用就调用 → 能 Adapter 就 Adapter → 只有不存在成熟方案时才自己实现。**

### 7.1 优先复用

| 能力 | 优先方案 |
|---|---|
| Agent | Codex / Claude Code / OpenCode / OpenHands / DSH |
| Sandbox | Daytona / E2B / Docker / VM |
| Browser | Chromium / Playwright / Browser Provider |
| Git | Git / GitHub |
| MCP | MCP 标准 |
| LLM | 各家 API / Model Provider |
| Terminal | 操作系统能力 |
| Search | 成熟 Search Provider |
| Database | SQLite / PostgreSQL |
| Container | Docker |
| VM | 成熟 VM / Cloud Provider |

### 7.2 Flux 自己重点维护

- Flux Agent Protocol
- Flux Environment
- Flux Capability Layer
- Flux Permission / Policy
- Flux Workspace Model
- Flux Context / Memory
- Flux Task Lifecycle
- Proposal / Diff / Review / Apply
- Agent Handoff
- Provider / Adapter Registry

---

## 8. Adapter-first 架构

不要把第三方工具源码直接“拼进” Flux。

推荐：

```text
                 Flux
                   │
          Unified Interface
                   │
       ┌───────────┼───────────┐
       ↓           ↓           ↓
 Agent Adapter  Sandbox     Connector
       │         Adapter       Adapter
       ↓           ↓           ↓
    Codex       Daytona      GitHub
    Claude      E2B           Browser
    OpenCode    Docker        MCP
```

Flux 定义接口，底层 Provider 实现接口。

例如 Sandbox：

```text
Flux Sandbox Interface
├── Local Docker Adapter
├── Daytona Adapter
├── E2B Adapter
└── Future Adapter
```

这样可以降低开发成本，也可以避免把某个第三方产品永久绑定进 Flux Core。

---

## 9. 商业化与许可证原则

“使用成熟开源/商业工具”本身不等于不能商用。

必须区分：

### 9.1 外部调用 / API / SDK

例如：

```text
Flux → Provider API / SDK → 外部 Sandbox
```

通常比直接复制源码更容易管理商业授权，但必须检查具体产品的服务条款、SDK 许可证、部署方式和商业限制。

### 9.2 直接复制第三方源码

```text
Flux
└── 第三方源码
```

必须严格检查其许可证。

不能因为项目在 GitHub 上公开，就默认允许闭源商业集成。

特别需要注意：

- MIT
- Apache-2.0
- GPL
- AGPL
- SSPL
- BSL
- Source Available
- 自定义商业许可证

商业化前必须做完整 License / SBOM Audit。

### 9.3 推荐的商业架构

Flux 更适合：

```text
Flux Core
  ↓
Protocol / Adapter
  ↓
用户自行连接 Agent / Provider
```

以后再对：

- Cloud Sync
- Cloud Agent
- Cloud Sandbox
- Team Workspace
- Enterprise Governance
- Hosted Runtime
- Mobile / Cloud services

进行商业化。

---

## 10. 功能取舍原则

如果市场已经有成熟、稳定、商业可用的实现：

> **Flux 默认不重新实现。**

例如不应为了“完整”而自己造：

- 新 Coding Agent
- 新 LLM Runtime
- 新 Git
- 新 Browser
- 新 Search Engine
- 新 Sandbox Infrastructure
- 大量底层 Connector

除非现有方案无法满足 Flux 的核心控制模型。

Flux 应重点解决的是：

> **成熟工具之间的断层。**

例如：

```text
Agent 不同
 ↓
协议不同
 ↓
Context 不同
 ↓
Capability 不同
 ↓
权限不同
 ↓
Workspace 不同
 ↓
结果落地方式不同
```

Flux 的价值就是把这些差异统一起来。

---

## 11. Flux 与主要竞争方向的差异

| 类型 | 代表 | 主要价值 | Flux 差异 |
|---|---|---|---|
| Coding Agent | Claude Code / Codex / OpenCode | Agent 智力与执行 | Flux 不竞争模型/Agent 智力 |
| Agent Platform | OpenHands | Agent 开发与运行 | Flux 强调 Agent 无关的统一控制层 |
| Agent Workspace | Agent Muse / SiaFlow | Agent 管理、Bridge、审批 | Flux 进一步强调 Runtime / Capability / Environment |
| Agent IDE | Cursor / Cline / Roo | IDE 内 Agent 工作流 | Flux 不绑定 IDE |
| Agent Sandbox | Daytona / E2B | 安全执行环境 | Flux 管理 Sandbox Provider 与生命周期，不必自己造基础设施 |
| 企业 Agent Governance | GitHub Copilot / OpenHands Enterprise 等 | 管理、审计、权限 | Flux 重点建立跨 Agent 的统一执行模型 |

---

## 12. 最终产品模型

Flux 最终可以抽象为：

```text
                         User
                           │
                           ▼
                    ┌─────────────┐
                    │    Flux     │
                    │             │
                    │ Environment │
                    │ Context     │
                    │ Capability  │
                    │ Policy      │
                    │ Workspace   │
                    │ Task        │
                    └──────┬──────┘
                           │
          ┌────────────────┼────────────────┐
          ↓                ↓                ↓
       Codex           OpenCode          Claude
       DSH             OpenHands        Other Agent
          └────────────────┼────────────────┘
                           ↓
                    Flux Runtime
                           │
              ┌────────────┼────────────┐
              ↓            ↓            ↓
          Sandbox       Connector      Browser
          Provider       Provider       Provider
              │            │            │
              └────────────┼────────────┘
                           ↓
                    Validation
                           ↓
                       Proposal
                           ↓
                         Diff
                           ↓
                        Review
                           ↓
                       ApplyEngine
                           ↓
                    Real Workspace
```

---

## 13. 当前阶段的开发决策

### 应继续做

1. Flux Protocol / Adapter 体系
2. Agent 接入和自动发现
3. Flux Environment
4. Capability / Permission
5. Context / Memory
6. Virtual Workspace
7. Proposal / Diff / Review / Apply
8. Agent Handoff / Task Lifecycle
9. Provider Registry
10. 真实任务测试和稳定性验证

### 应优先复用

1. Agent
2. Sandbox
3. Browser
4. Git
5. MCP
6. Model Provider
7. Search
8. Container / VM
9. 大量通用 Connector

### 暂时不要因为“功能列表不完整”而做

1. 自研完整 Sandbox Infrastructure
2. 自研 Coding Agent
3. 自研 Browser Engine
4. 自研 Git
5. 自研 Search
6. 自研大量模型 SDK
7. 大量非核心 Connector
8. 复杂企业功能
9. Marketplace 大规模建设

---

## 14. 最终判断

目前市场上已经存在大量比 Flux 某一项能力更成熟、并且可以商业使用的产品。因此：

> **Flux 如果只是把这些能力重新实现一遍，没有足够的产品价值。**

但是，目前没有必要因为存在这些产品就放弃 Flux。真正可能形成差异的是：

> **把任意 Agent、成熟 Sandbox、Connector、Skill、Browser、模型和项目环境统一到一个可控、可迁移、可恢复的执行生命周期中。**

因此 Flux 的竞争力不应来自“功能更多”，而应来自：

**Agent → Flux Environment → Capability → Policy → Workspace → Validation → Proposal → Review → Apply**

这条完整控制链。

最终原则：

> **能复用的绝不重复造；需要统一的由 Flux 统一；真正属于 Flux 核心边界的能力自己掌握。**

# Flux 下一阶段实施计划

> 文档版本：v0.1  
> 目标：明确当前 M1 收尾之后到可用个人版 MVP 的实施顺序与落地方法。  
> 依据：`README.md`、`docs/Flux_Master_Spec_v0.1.md` 及当前仓库状态。  
>
> 本文只描述当前阶段真正应该做的事情。Mobile、企业版、Marketplace、iOS/Android 测试、云端 Agent 等长期能力暂不进入当前开发主线。

---

## 1. 当前状态

Flux 已经完成 M0，并且 M1 的主要基础能力已经具备：

- Agent Runtime 状态机
- Model Gateway
- local / OpenAI / Anthropic / DeepSeek Provider
- Provider 错误处理与重试
- 多 Agent 并行
- Permission Engine
- Event Bus
- Task Scheduler
- Workflow Engine
- Connector 契约与注册表
- Virtual Workspace 基础状态机
- API / 数据库契约
- CI、测试、迁移、自检脚本

当前最明确的 M1 剩余项：

> **Task Engine 从进程内状态迁移到数据库 `tasks` 表。**

当前不要继续横向扩张功能，先把一个完整的 AI Coding 闭环跑通。

---

# 2. 当前第一目标：完成 M1

## 2.1 Task 持久化

### 要做

将当前进程内 Task Scheduler 的任务状态持久化到数据库。

至少保证：

```
创建 Task
    ↓
数据库保存
    ↓
Scheduler 调度
    ↓
Agent 执行
    ↓
状态更新
    ↓
查询历史
```

### 需要保存

- id
- project_id
- description
- status
- priority
- agent_id（当前阶段可为空）
- cost
- result
- created_at
- updated_at

### 实现要求

1. 创建 Task 时先建立持久化记录。
2. Scheduler 不再把数据库当作“任务结束后的备份”，而是把数据库作为任务状态的事实来源。
3. Agent 开始执行时更新 Task 状态。
4. Agent 完成 / 失败 / 取消时更新状态。
5. 进程重启后可以重新读取未完成任务。
6. 不允许出现“API 返回的 Task 和数据库中的 Task 状态不一致”。

### 验收

新增测试：

- create → persisted
- queued → running
- running → completed
- running → failed
- cancel
- process restart 后恢复未完成任务
- priority 排序
- agent_id 可选
- 数据库异常时 fail-closed

---

# 3. 第二目标：把 Agent 做成真正可用的 Agent

现在的 Runtime 已经具备执行基础，但下一步需要从“Runtime 能运行 Agent”变成“Agent 能完成开发工作”。

## 3.1 Agent Manifest

为每个 Agent 定义统一配置：

```yaml
name: developer
role: Developer
model:
  provider: openai
  model: <configured-model>

skills:
  - backend

tools:
  - filesystem
  - terminal
  - git

permissions:
  - file.read
  - file.write
  - terminal.execute
```

Manifest 不应该包含 API Key。

### 内置第一批 Agent

先只做 4 个：

1. Tech Lead
2. Developer
3. Reviewer
4. Tester

Architect、DevOps 等角色可以在核心闭环稳定后增加。

---

# 4. 第三个目标：完成 Virtual Workspace M2

这是 Flux 最重要的产品差异化。

## 4.1 核心原则

Agent 永远不能直接覆盖用户真实文件。

流程必须固定为：

```
用户需求
  ↓
Agent
  ↓
Proposal
  ↓
Virtual Diff
  ↓
Human Review
  ↓
Accept / Reject / Edit / Retry
  ↓
Apply
  ↓
Test
  ↓
Git
```

## 4.2 第一版只实现必要能力

先支持：

- 单文件修改
- 多文件修改
- unified diff
- Accept
- Reject
- Partial Accept（可以后置）
- Request Revision
- Apply
- 原文件备份
- Apply 前后校验

暂时不要做复杂的实时协同编辑。

---

# 5. Proposal 数据模型

建议把一次 AI 修改抽象为 Change Proposal。

至少包含：

```
proposal_id
task_id
agent_id
project_id
file_path
original_hash
original_content
proposed_content
diff
status
created_at
updated_at
```

状态：

```
PENDING
REJECTED
ACCEPTED
APPLIED
FAILED
```

关键规则：

> Apply 时必须再次验证 original_hash。

如果用户在 Agent 生成 Proposal 后手动修改了文件：

```
Proposal
   ↓
发现 original_hash != 当前文件 hash
   ↓
禁止直接 Apply
   ↓
提示文件已经发生变化
```

这样可以避免 AI 把用户刚刚写的代码覆盖掉。

---

# 6. Diff Engine

第一版不需要自己发明 diff 算法。

直接使用成熟的 unified diff 实现。

职责只做：

```
original
   +
proposed
   ↓
unified diff
```

然后为 UI 提供：

- 文件级变更
- 新增行
- 删除行
- 修改块
- Agent
- Task
- 修改原因

---

# 7. Apply Engine

Apply 不应该散落在 API、Agent 或 UI 代码里。

建立独立的：

```
VirtualWorkspaceService
    ↓
ProposalValidator
    ↓
PatchApplier
    ↓
BackupService
    ↓
TestRunner
```

第一版流程：

```
validate proposal
      ↓
validate original hash
      ↓
create backup
      ↓
apply patch
      ↓
verify file
      ↓
run configured tests
```

如果 Apply 失败：

> 尽可能恢复原文件，并记录完整错误。

---

# 8. 第四目标：完成最小 AI Coding Workflow

不要一次实现完整的六 Agent 团队。

先实现这个闭环：

```
用户
 ↓
Tech Lead
 ↓
Developer
 ↓
Virtual Workspace
 ↓
用户审核
 ↓
Tester
 ↓
结果
```

Reviewer 可以先作为可选步骤。

---

## 示例任务

用户输入：

> 给这个 FastAPI 项目增加一个健康检查接口。

Flux：

### Step 1

Tech Lead：

分析：

```
需要：
1. 找到 API 入口
2. 添加 /health
3. 增加测试
```

### Step 2

Developer：

生成 Proposal。

### Step 3

Virtual Workspace：

展示：

```
main.py
test_health.py
```

以及完整 Diff。

### Step 4

用户：

```
Accept
```

### Step 5

Tester：

运行：

```
pytest
```

### Step 6

Flux：

报告：

```
Task completed

Files changed: 2
Tests: 14 passed
```

这就是第一个真正意义上的 Flux Demo。

---

# 9. 第五目标：做 Project Workspace

在 Agent 能稳定修改代码之后，再做项目理解。

第一版不要马上上向量数据库。

先做：

## Project Scanner

扫描：

- 文件结构
- Git 信息
- README
- package.json
- pyproject.toml
- requirements.txt
- Dockerfile
- 常见配置
- 入口文件

生成：

```
Project Profile

Language
Framework
Package Manager
Entry Points
Test Commands
Build Commands
Git Repository
```

---

# 10. Project Brain 第一版

第一版可以先使用结构化数据，不需要复杂 RAG。

保存：

### Project Overview

项目是什么。

### Tech Stack

使用什么语言和框架。

### Architecture

主要模块。

### Coding Rules

项目编码规范。

### Decisions

重要技术决策。

### Agent Notes

Agent 在开发过程中产生的有价值信息。

---

## 第二阶段再增加 Semantic Search

以后再加入：

```
Project Files
    ↓
Chunk
    ↓
Embedding
    ↓
pgvector
    ↓
Semantic Retrieval
```

不要因为已经设计了 pgvector，就现在强行引入。

---

# 11. 第六目标：最小 IDE

等核心闭环稳定后再做 IDE。

第一版只需要：

```
┌───────────────────────────────────────────┐
│ Flux                                      │
├──────────┬────────────────────┬───────────┤
│ Project  │                    │ AI Team   │
│ Explorer │     Code Editor    │           │
│          │                    │ Developer │
│ Files    │                    │ Reviewer  │
│          │                    │ Tester    │
├──────────┴────────────────────┴───────────┤
│ Timeline / Terminal / Test Result         │
└───────────────────────────────────────────┘
```

重点不是页面数量。

重点是：

> 用户能够在一个窗口完成一次完整 AI Coding Task。

---

# 12. Virtual Workspace UI 是第一版 IDE 的中心

中心区域应该优先展示：

```
Original Code
      ↕
AI Proposal / Diff
      ↕
Review
```

用户可以：

- 查看修改
- 编辑 Proposal
- Accept
- Reject
- Ask AI to revise

这比先做漂亮 Dashboard 更重要。

---

# 13. Git 集成

Virtual Workspace 稳定后加入 Git。

第一版：

- status
- diff
- branch
- checkout
- commit

然后：

```
Task
 ↓
Virtual Changes
 ↓
Approved
 ↓
Tests Passed
 ↓
Git Commit
```

Commit message 可以由 Agent 建议，但必须允许用户修改。

---

# 14. 新手模式

核心开发闭环稳定后加入。

入口必须明显：

> Beginner Mode

第一次使用：

```
选择：

语言
Python / TypeScript / Java / Kotlin / ...

方向
Web / Backend / Frontend / Android / ...

目标
我想做什么？
```

然后 Flux 根据项目状态解释：

- 使用了什么技术
- 为什么使用
- 这段代码负责什么
- 修改会影响什么

代码旁边可以显示：

```
[FastAPI]
[Async]
[JWT]
[SQLAlchemy]
```

点击后显示“大白话解释”。

---

# 15. AI Code Completion 暂时不要做

代码补全属于另外一条低延迟模型链路。

当前先不实现。

未来再单独设计：

```
Editor
 ↓
Completion Context
 ↓
Local Code Model
 ↓
Suggestion
 ↓
Tab Accept
```

未来 Mac Studio / 本地模型节点可以成为主要执行资源。

现在不要让它打断 Agent Runtime + Virtual Workspace 主线。

---

# 16. 暂时明确不做

当前阶段不要做：

- Mobile App
- iOS / Android 测试节点
- 企业 Team Workspace
- 企业会议模式
- 企业本地文件同步
- Cloud Agent
- NAS 集群
- Marketplace
- Plugin Marketplace
- 高级成本中心
- 自研浏览器
- AI Code Completion
- 复杂 RAG
- 大规模分布式 Worker

这些全部保留在 Roadmap。

原因不是它们没有价值，而是现在每增加一个方向都会降低核心闭环完成速度。

---

# 17. 推荐开发顺序

严格按照下面顺序：

```
① Task 持久化
       ↓
② Agent Manifest
       ↓
③ Developer Agent
       ↓
④ Virtual File / Proposal
       ↓
⑤ Diff Engine
       ↓
⑥ Review / Approve / Reject
       ↓
⑦ Apply Engine
       ↓
⑧ Tester Agent
       ↓
⑨ Git Integration
       ↓
⑩ Project Scanner
       ↓
⑪ Project Brain v1
       ↓
⑫ 最小 IDE
       ↓
⑬ Beginner Mode
```

---

# 18. 每一个阶段都必须有验收条件

不要用：

> “代码写完了”

作为完成标准。

统一使用：

```
实现
 ↓
Unit Test
 ↓
Integration Test
 ↓
真实项目测试
 ↓
用户操作测试
 ↓
Acceptance
```

例如 Virtual Workspace：

### 不是

“Diff API 能返回 diff。”

### 而是

```
Agent 修改代码
        ↓
真实文件没有变化
        ↓
用户看到 Diff
        ↓
用户拒绝
        ↓
真实文件仍然没有变化
```

然后：

```
重新生成 Proposal
        ↓
用户 Accept
        ↓
Apply
        ↓
测试通过
        ↓
真实文件改变
```

才算完成。

---

# 19. 第一个公开 Demo 的标准

Flux 第一版真正值得公开展示的 Demo 应该是：

> “给我这个项目增加一个功能。”

然后完整展示：

```
Requirement
    ↓
Tech Lead
    ↓
Developer
    ↓
Virtual Diff
    ↓
Human Review
    ↓
Apply
    ↓
Tester
    ↓
Git Commit
```

用户能够清楚看到：

- 谁在工作
- 使用什么模型
- 为什么修改
- 修改了什么
- 修改前后差异
- 测试是否通过
- 最终由谁批准

这就是 Flux 与普通 AI Coding 工具之间最重要的产品故事。

---

# 20. 当前开发原则

### 原则一：先闭环，再扩展

不要因为某个功能“以后很有用”就提前实现。

### 原则二：Core 优先

Agent Runtime、Task、Virtual Workspace 是当前核心。

### 原则三：接口先稳定

未来 Mobile、Cloud、Enterprise 都应该通过现有接口接入，而不是直接侵入 Core。

### 原则四：AI 永远不是黑盒写盘

Virtual Workspace 是 Flux 的核心原则。

### 原则五：真实项目验证

不要只用 mock 项目测试。

Flux 应该尽早拿真实项目执行任务。

### 原则六：每完成一个阶段就回写规格

发现实现与 Master Spec 不一致时：

```
发现问题
 ↓
判断是否需要修改设计
 ↓
更新 Master Spec
 ↓
更新代码
 ↓
测试
```

Master Spec 继续作为唯一权威来源。

---

# 21. 当前最重要的一件事

如果只能做一件事：

> **让 Flux 第一次完整完成一个真实开发任务。**

不是：

“Agent API 能调用。”

而是：

```
用户：
给项目增加一个功能

Flux：
理解需求

Flux：
规划

Flux：
写代码 Proposal

用户：
审核

Flux：
应用

Flux：
测试

Flux：
返回结果

用户：
看到最终代码
```

当这个闭环跑通以后，再继续做 Project Brain、IDE、Beginner Mode、Skill、Cloud、Enterprise。

---

# 22. 下一阶段 Definition of Done

当以下全部满足时，可以认为 Flux 从“工程骨架”进入“可用个人版 MVP”：

- [ ] Task 完整持久化
- [ ] Agent Manifest
- [ ] Developer Agent
- [ ] Tech Lead Agent
- [ ] Tester Agent
- [ ] Virtual Proposal
- [ ] Unified Diff
- [ ] Accept / Reject
- [ ] Apply
- [ ] Apply 前 hash 校验
- [ ] Apply 失败恢复
- [ ] 测试执行
- [ ] Git Commit
- [ ] Project Scanner
- [ ] 最小 IDE
- [ ] 一条完整开发任务端到端跑通
- [ ] 自动化测试覆盖核心流程
- [ ] 至少使用一个真实项目验证

完成这些之后，再进入：

**Project Brain → Skill System → Cost / Observability → Beginner Mode → Cloud / Pro → Enterprise。**

---

## 最终路线

```
Flux Core
   ↓
AI Coding Loop
   ↓
Virtual Workspace
   ↓
Project Understanding
   ↓
IDE
   ↓
Personal Developer Platform
   ↓
Open Source
   ↓
Pro Services
   ↓
Team
   ↓
Enterprise
   ↓
Flux Infrastructure
```

**当前只盯住第一段。**

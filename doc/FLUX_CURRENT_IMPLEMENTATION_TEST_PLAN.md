# Flux 当前实现测试计划

> **版本定位：Current Implementation Only**
>
> 本文档只用于验收当前 `qiuli55/flux` 仓库已经实现、已经接通或已经可以真实运行的代码。
>
> 不把未来规划功能当作当前版本的失败项。对于已经明确存在但尚未实现的能力，只记录为“当前缺口”，不纳入本版本 Release Gate。

---

## 1. 测试原则

### 1.1 唯一测试对象

以当前 Git `main` 分支实际代码为准，而不是以产品规划、设计稿或未来架构为准。

### 1.2 测试分层

```text
Unit
 ↓
Integration
 ↓
Runtime / CLI
 ↓
Real Agent E2E
 ↓
Benchmark
 ↓
Release Gate
```

### 1.3 成功与失败

每个测试必须明确：

- 前置条件
- 测试数据
- 操作步骤
- 预期结果
- 成功标准
- 失败标准
- 严重等级

### 1.4 已知缺陷处理

如果当前代码已经明确存在缺陷：

```text
测试执行
 ↓
确认缺陷存在
 ↓
记录实际行为
 ↓
标记 Known Failure
```

不能为了让测试全部通过而修改测试预期。

---

# 2. 当前版本测试范围

当前版本重点验证以下已经存在的代码链路：

```text
Flux CLI
   ↓
Runtime / Server
   ↓
Runtime Identity / flux_context
   ↓
Agent Adapter
   ↓
OpenCode / Codex
   ↓
Process Lifecycle
   ↓
Agent Task
```

以及当前开发工作流：

```text
Project / Virtual Workspace
   ↓
Proposal
   ↓
Diff
   ↓
Apply
   ↓
Hash Verification
   ↓
Rollback / Recovery
   ↓
Git Commit
```

同时覆盖：

- Server CLI
- Install State
- Agent Adapter
- OpenCode Adapter
- Codex Adapter
- Process Group Lifecycle
- Proposal Persistence
- Apply Engine
- Git 集成
- Test Runner
- Web Dashboard 当前已经接通的 UI/API
- Benchmark V2 / 当前真实 Agent 测试
- CI / `make verify`

---

# 3. 当前版本明确不纳入验收的功能

以下能力如果当前代码尚未完整接通，不作为当前 Release Gate：

- 完整 Project Brain → Agent Context 注入链路
- 完整 Permission Enforcement
- 完整 Multi-Agent Workflow
- 完整 DSH Runtime → Agent 工作链
- 完整 Connector Marketplace
- 完整 Skill Marketplace
- 完整 Web Search / Browser Agent
- 完整外部文件授权缓存体系
- 多文件原子 Apply
- 完整 Control Plane / Data Plane 安全隔离
- Cloud 多租户
- Billing / Cost Dashboard 的最终实现
- Mobile Controller 最终能力
- Desktop App 最终能力

这些项目可以单独建立未来版本测试计划，但不能混入当前版本验收。

---

# 4. 测试严重等级

| 等级 | 定义 | 当前版本要求 |
|---|---|---|
| P0 | 数据破坏、越权、核心 Runtime 无法运行 | 0 个 |
| P1 | 核心开发流程失败、严重状态错误、Agent 无法正常运行 | 发布前必须解决或明确豁免 |
| P2 | 普通功能缺陷、UI/体验问题、非关键性能问题 | 可进入后续迭代 |
| P3 | 优化项、边缘体验问题 | 不阻塞发布 |

---

# 5. 基础回归测试

## T-REG-001 `make verify`

### 操作

执行：

```bash
make verify
```

### 成功标准

- 项目验证流程完成
- pytest 全部通过
- 类型/静态检查通过（如果由当前 Makefile 纳入）
- 无未处理的验证错误

### 失败标准

任何核心检查失败。

### 等级

P0 / P1，取决于失败项目。

---

## T-REG-002 CI

提交代码并执行当前 GitHub Actions CI。

### 成功标准

CI 与本地 `make verify` 的结果一致，不出现“本地通过、CI 必挂”的环境差异。

---

# 6. Flux CLI 测试

## T-CLI-001 CLI 启动

### 操作

执行当前 Flux CLI 的基础启动命令。

### 成功

- CLI 正常启动
- 参数解析正确
- 错误参数有明确错误
- 不出现 traceback

---

## T-CLI-002 Help

### 操作

执行：

```bash
flux --help
```

以及当前存在的子命令 `--help`。

### 成功

帮助信息与实际命令一致，不展示不存在的能力。

---

## T-CLI-003 Invalid Command

### 成功

非法命令返回非零退出码，并给出可理解错误。

---

# 7. Runtime / Server CLI

## T-RUNTIME-001 Server 启动

### 成功

Server 能正常启动并进入可用状态。

---

## T-RUNTIME-002 Server 停止

### 成功

Server 能正常停止，不遗留异常后台进程。

---

## T-RUNTIME-003 Server 重启

```text
start
 ↓
stop
 ↓
start
```

### 成功

第二次启动状态正确，不因上一次运行留下的状态而异常。

---

## T-RUNTIME-004 Server 异常退出

模拟 Server 进程异常终止后重新启动。

### 成功

不会因为残留 PID、Socket、State 等导致无法恢复。

---

# 8. Runtime Identity / `flux_context`

## T-ID-001 Runtime Identity 创建

### 成功

运行中的 Agent/Task 能够获得当前 Flux Runtime 的正确身份信息。

---

## T-ID-002 `flux_context` 正常读取

### 成功

Agent 可以获得当前实现所规定的 Flux Runtime Context。

---

## T-ID-003 Context 与项目匹配

切换 Workspace / Project 后再次读取 Context。

### 成功

不会继续使用上一个项目的 Runtime Context。

---

## T-ID-004 Context 缺失

删除或破坏 Context 后运行 Agent。

### 成功

系统明确报告 Context 缺失，而不是产生错误的成功状态。

---

# 9. Install State

## T-INSTALL-001 初次安装状态

### 成功

新环境能够正确识别未安装状态。

---

## T-INSTALL-002 安装后状态

### 成功

安装完成后状态持久化，重新启动仍正确。

---

## T-INSTALL-003 重复安装

### 成功

重复执行不会产生重复状态或损坏现有配置。

---

## T-INSTALL-004 状态损坏

### 成功

状态异常时给出明确错误，并允许重新初始化/修复。

---

# 10. Agent Adapter

## T-AGENT-001 Adapter Discovery

验证当前支持的 Agent Adapter 是否能够被 Runtime 正确发现。

### 成功

支持的 Adapter 可以正常初始化；不存在的 Adapter 返回明确错误。

---

## T-AGENT-002 Adapter 参数传递

### 成功

Runtime → Adapter 的：

- Task
- Workspace
- Environment
- Context
- 参数

传递正确。

---

## T-AGENT-003 Agent Exit Code

Agent 正常退出、异常退出、非零退出分别测试。

### 成功

Flux 正确区分：

```text
Success
Failure
Interrupted
```

而不是一律认为任务成功。

---

# 11. OpenCode Adapter

## T-OPENCODE-001 启动真实 OpenCode

使用实际可用 OpenCode 环境运行最小任务。

### 成功

Flux 能启动 OpenCode，并获得可识别的任务状态。

---

## T-OPENCODE-002 正常完成

### 成功

Agent 正常完成任务后：

- 进程结束
- Exit 状态正确
- Task 状态正确
- 输出可获得

---

## T-OPENCODE-003 Agent 失败

构造一个明确会失败的任务。

### 成功

Flux 能识别 Agent Failure，不误报 Success。

---

## T-OPENCODE-004 Agent 中断

运行任务后主动终止。

### 成功

进程组被正确处理，Task 最终进入明确状态。

---

# 12. Codex Adapter

## T-CODEX-001 启动真实 Codex

在可用 Codex 环境下运行最小任务。

### 成功

Codex 能被 Flux 正确启动。

---

## T-CODEX-002 正常完成

### 成功

状态、输出、退出码均正确。

---

## T-CODEX-003 Codex Failure

### 成功

Provider/Agent 错误不会被误认为 Flux Runtime 错误。

---

## T-CODEX-004 Codex 中断

### 成功

终止任务后不遗留 Agent 进程。

---

# 13. Process Group Lifecycle

## T-PROC-001 单进程退出

### 成功

Agent 结束后不存在残留进程。

---

## T-PROC-002 子进程清理

Agent 创建子进程后被终止。

### 成功

整个进程组能够被正确清理。

---

## T-PROC-003 SIGTERM / SIGKILL

分别测试正常终止和强制终止。

### 成功

两种情况下 Flux 都能恢复到明确 Task 状态。

---

## T-PROC-004 重复终止

连续执行 Stop/Cancel。

### 成功

不会产生异常状态或重复清理错误。

---

# 14. Virtual Workspace

## T-WS-001 创建 Workspace

### 成功

Workspace 创建成功并可被后续 Task 使用。

---

## T-WS-002 文件读取

创建测试文件并由 Agent 读取。

### 成功

Agent 读取到的内容与 Workspace 内容一致。

---

## T-WS-003 文件写入

Agent 修改测试文件。

### 成功

修改只发生在预期 Workspace。

---

## T-WS-004 Workspace 边界

Agent 尝试访问明确位于 Workspace 外的路径。

### 当前版本标准

如果当前实现尚未提供完整权限/Sandbox enforcement，不将该测试标记为“安全功能已通过”。

应记录：

```text
Known Gap: Workspace boundary enforcement incomplete
```

---

# 15. Proposal

## T-PROP-001 创建 Proposal

### 成功

Proposal 能正确保存：

- Task 信息
- 文件目标
- 原始状态
- 修改内容
- `original_hash`

---

## T-PROP-002 Proposal 持久化

创建 Proposal 后重启 Runtime。

### 成功

Proposal 状态不会丢失。

---

## T-PROP-003 Proposal 状态转换

验证当前实现支持的状态转换。

### 成功

状态只能按照合法路径变化。

---

# 16. Diff

## T-DIFF-001 新文件 Diff

### 成功

新文件 Diff 正确展示。

---

## T-DIFF-002 修改文件 Diff

### 成功

Diff 与实际 Proposal 修改一致。

---

## T-DIFF-003 删除文件 Diff

如果当前实现支持，则验证删除 Diff。

---

## T-DIFF-004 Diff 与 Apply 一致

### 成功

用户看到的 Proposal/Diff 与最终 Apply 内容一致。

---

# 17. Apply Engine

## T-APPLY-001 正常 Apply

### 前置

Proposal 有效，`original_hash` 与当前文件一致。

### 操作

1. 创建 Proposal
2. 查看 Diff
3. Apply
4. 检查文件
5. 检查 hash

### 成功

- 文件内容符合 Proposal
- 写后 hash 正确
- Proposal 状态正确

---

## T-APPLY-002 Hash Conflict

在 Proposal 创建后手动修改目标文件。

### 成功

Apply 被拒绝，不覆盖用户的新修改。

### 等级

P0

---

## T-APPLY-003 Apply 失败回滚

人为制造写入/校验失败。

### 成功

已有文件恢复到 Apply 前状态。

---

## T-APPLY-004 新文件 Apply 失败

Apply 一个原本不存在的文件，并在中途制造失败。

### 成功

失败后不会留下错误生成的新文件。

---

## T-APPLY-005 Write 后 Hash Verification

### 成功

写入完成后 hash 与预期结果一致；不一致时 Apply 不得报告成功。

---

## T-APPLY-006 Git Commit

Apply 成功后验证当前实现的 Git commit 行为。

### 成功

Commit 内容只包含预期修改。

---

## T-APPLY-007 用户未提交修改保护

### 成功

Flux 不应静默覆盖用户在 Proposal 创建之后产生的修改。

---

## T-APPLY-008 Apply 中断

Apply 过程中终止 Runtime。

### 成功

系统不会错误报告成功，并能根据当前实现恢复或明确报告需要恢复。

---

## T-APPLY-009 Symlink 专项

创建指向 Workspace 外部的 symlink 并尝试 Apply。

### 当前标准

该测试必须真实执行并记录当前实现行为。

如果能够越过 Workspace 边界：

```text
P0 Known Vulnerability
```

不得通过修改测试预期掩盖问题。

---

## T-APPLY-010 多文件原子性

如果当前实现尚未提供多文件原子 Apply：

```text
Status: NOT IMPLEMENTED
```

不作为当前版本失败项，但记录为明确能力缺口。

---

# 18. Rollback

## T-ROLL-001 正常回滚

对已 Apply 的修改执行当前支持的 Rollback 流程。

### 成功

文件恢复到 Rollback 目标状态。

---

## T-ROLL-002 Rollback 后 Git 状态

### 成功

Git 状态与实际文件状态一致。

---

# 19. Git

## T-GIT-001 Clean Repository

### 成功

Flux 能正确识别 clean 状态。

---

## T-GIT-002 Modified Repository

### 成功

Flux 能识别用户现有修改，不错误归因给当前 Task。

---

## T-GIT-003 Commit 内容

### 成功

Flux 生成的 Commit 只包含预期修改。

---

## T-GIT-004 Git Failure

模拟 Git 命令失败。

### 成功

Flux 明确报告 Git Failure，不误报整个 Task 成功。

---

# 20. Test Runner

## T-TEST-001 正常测试

Agent 修改代码后运行当前 Test Runner。

### 成功

测试结果被正确捕获。

---

## T-TEST-002 Test Failure

故意引入一个确定失败的测试。

### 成功

Flux 能识别 Test Failure。

---

## T-TEST-003 Test Process Crash

测试进程异常退出。

### 成功

Task 不应被误判为测试通过。

---

# 21. Web Dashboard 当前实现

只测试当前已经存在并接通的页面/API，不测试未来页面。

重点验证：

- App 启动
- API Client
- 类型定义
- Router
- Agent Panel
- Project Panel
- File Explorer
- Code Editor
- Diff Viewer
- Change List
- Git Panel
- Activity Feed
- Requirement Bar

## T-WEB-001 Dashboard 启动

### 成功

当前 Dashboard 能正常构建并运行。

---

## T-WEB-002 API Client

### 成功

前端调用当前后端接口时：

- 请求格式正确
- 响应解析正确
- 错误状态正确处理

---

## T-WEB-003 Diff Viewer

### 成功

展示内容与实际 Proposal Diff 一致。

---

## T-WEB-004 File Explorer / Editor

### 成功

文件选择、查看和当前实现支持的编辑流程状态正确。

---

## T-WEB-005 Git Panel

### 成功

展示的 Git 状态与 Runtime 实际状态一致。

---

# 22. Real Agent E2E

当前版本必须至少准备以下真实项目：

### Project A：纯文本修改

Agent 修改一个 Markdown 文件。

### Project B：简单代码修改

Agent 修改一个最小 Python/TypeScript 项目。

### Project C：测试驱动修改

Agent 修改代码并运行测试。

### Project D：失败恢复

故意制造测试失败，然后要求 Agent 修复。

### Project E：Git 工作流

```text
Task
 ↓
Proposal
 ↓
Apply
 ↓
Test
 ↓
Git Commit
```

### Project F：冲突

Proposal 创建后人工修改文件，再执行 Apply。

### Project G：长任务

执行一个明显需要多个 Tool Call 的真实任务。

---

# 23. Real Agent E2E 成功标准

一个真实任务只有同时满足以下条件才算完整成功：

```text
Agent 启动成功
AND
Workspace 正确
AND
Task 状态正确
AND
文件修改正确
AND
Proposal 正确
AND
Apply 正确
AND
Test Result 正确
AND
Git 状态正确
AND
Agent 正常退出
```

任何一个核心环节错误，都不能标记为 Full Pass。

---

# 24. Benchmark V2

当前已有 Benchmark V2 / 50-task Prompt 集合时，应直接作为当前 Agent 能力测试集。

每个 Prompt 记录：

```text
Prompt ID
Agent
Model
开始时间
结束时间
成功/失败
文件修改
Test Result
Git Result
Agent Error
Flux Error
Token/Cost（如果当前实现可得）
```

### 不以“模型回答看起来不错”作为唯一成功标准。

最终必须检查实际 Repository 状态。

---

# 25. Agent Failure 分类

所有真实 Agent 失败必须归类：

```text
AGENT_FAILURE
PROVIDER_FAILURE
FLUX_RUNTIME_FAILURE
WORKSPACE_FAILURE
PROPOSAL_FAILURE
APPLY_FAILURE
GIT_FAILURE
TEST_FAILURE
USER_ERROR
UNKNOWN
```

### 成功

Flux 能尽可能区分故障来源。

### 失败

所有错误都变成：

```text
Agent failed
```

导致无法定位责任边界。

---

# 26. 性能基线

当前阶段不追求最终生产级性能指标，先建立基线。

记录：

- CLI 启动时间
- Server 启动时间
- Agent 启动时间
- Proposal 创建时间
- Diff 生成时间
- Apply 时间
- Git 操作时间
- Test Runner 时间
- Dashboard 首屏时间
- 长任务 RAM
- 长任务 CPU

### 成功

后续版本能够比较性能是否退化。

---

# 27. 稳定性测试

## T-STAB-001 连续任务

连续执行至少 20 个真实/半真实 Task。

### 成功

无逐步累积的：

- 进程
- 文件
- DB 状态
- 临时文件
- 内存
- 错误状态

---

## T-STAB-002 Agent Start/Stop Loop

重复启动和停止 Agent。

### 成功

无残留进程、状态污染或资源明显持续增长。

---

## T-STAB-003 Proposal Apply Loop

重复：

```text
Proposal → Apply → Verify
```

### 成功

状态和文件结果始终一致。

---

# 28. 已知缺口与已知失败

测试结果必须区分：

| 状态 | 含义 |
|---|---|
| PASS | 当前实现符合预期 |
| FAIL | 当前实现已实现但行为错误 |
| KNOWN FAILURE | 已知缺陷，测试可稳定复现 |
| NOT IMPLEMENTED | 当前版本没有该能力 |
| BLOCKED | 环境导致无法执行 |
| NOT APPLICABLE | 当前版本不适用 |

禁止把 `NOT IMPLEMENTED` 写成 `PASS`。

禁止把 `KNOWN FAILURE` 删除测试。

---

# 29. 当前版本 Release Gate

## Gate 1：基础工程

- `make verify` 通过
- CI 通过
- 核心测试无 P0

## Gate 2：Runtime

- Server 启停正常
- Runtime Identity 正常
- `flux_context` 正常
- Process Lifecycle 正常

## Gate 3：Agent

- OpenCode Adapter 正常
- Codex Adapter 正常
- 正常完成/失败/中断均能正确识别

## Gate 4：开发闭环

至少一个真实项目完成：

```text
Agent
→ Proposal
→ Diff
→ Apply
→ Test
→ Git Commit
```

## Gate 5：数据安全

- Hash Conflict 正确阻止覆盖
- Apply 失败能够按当前实现恢复
- 已知 P0 文件边界漏洞必须为 0，或明确阻塞发布

## Gate 6：稳定性

- 连续任务无明显资源泄漏
- Agent 进程能够正常清理
- Runtime 重启后状态可恢复到明确状态

## Gate 7：Benchmark

当前 Benchmark V2 完成后，所有失败任务必须能够分类，不能出现大量 `UNKNOWN`。

---

# 30. 当前阶段测试执行顺序

不要一次性测试所有项目。

### 第一阶段：自动回归

```text
make verify
CI
Unit / Integration
```

### 第二阶段：Runtime

```text
CLI
Server
Identity
flux_context
Process Lifecycle
```

### 第三阶段：核心开发闭环

```text
Workspace
Proposal
Diff
Apply
Hash
Rollback
Git
Test Runner
```

### 第四阶段：真实 Agent

```text
OpenCode
Codex
Real Project
Failure Recovery
Git Workflow
```

### 第五阶段：Benchmark V2

50 个任务逐项执行、分类、统计。

### 第六阶段：稳定性

连续任务、Start/Stop Loop、长任务。

---

# 31. 测试报告模板

每次执行使用：

```text
Test ID:
Date:
Git Commit:
Environment:
Agent:
Model:
Project:

Result:
PASS / FAIL / KNOWN FAILURE / NOT IMPLEMENTED / BLOCKED / N/A

Actual Behavior:

Expected Behavior:

Evidence:

Severity:

Regression:

Notes:
```

---

# 32. 最终原则

当前阶段 Flux 的测试目标不是证明“未来 Flux 有多完整”，而是回答三个问题：

### 1. 现在写出来的代码是否真的可靠？

### 2. Agent 能否稳定完成当前 Flux 已经承诺的开发闭环？

### 3. 当 Agent、Git、Apply、Test 或 Runtime 出错时，Flux 是否能够正确识别、停止、恢复，而不是制造更严重的数据问题？

只有这三个问题通过，才应该继续扩大 Flux 的功能边界。

> **当前版本测试以实际代码为边界，以真实 Agent E2E 为最终验证，以 Release Gate 决定是否进入下一阶段。**

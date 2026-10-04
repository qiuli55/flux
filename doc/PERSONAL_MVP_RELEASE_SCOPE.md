# Flux Personal Edition MVP 最终收口范围

> 目标：在不继续扩张产品边界的前提下，把 Flux Personal Edition 做到可长期实际使用、可安装、可移动端控制，并通过最终真实任务验收。

## 1. 收口原则

Personal Edition 进入最终收口阶段后，不再新增新的核心产品能力。

本阶段只处理：

- 已发现的 P0/P1/P2 可靠性与运行时问题
- 已确定的移动端核心体验问题
- 桌面端可安装、可启动、可使用的问题
- 最终真实开发任务 Benchmark
- Personal MVP KPI 验收

新的架构想法、Context Engine、Prompt Optimizer、更复杂的多 Agent 编排、Marketplace、Cloud/Enterprise 等统一进入 Post-MVP，不在本阶段实现。

## 2. 最终开发顺序

### P0：必须完成

#### 2.1 Apply 崩溃恢复

目标：解决 Apply 过程中进程异常退出导致 Workspace 与 Proposal 状态不一致的问题。

最低要求：

- Apply 开始前记录事务状态
- Apply 过程可检测是否存在未完成事务
- 服务重启后能够进行对账
- 不允许留下无法解释的半完成状态
- 重试不能造成二次破坏
- Backup 信息必须可追踪

验收重点：正常 Apply、Apply 中途 SIGKILL、重启恢复、重复重试。

#### 2.2 Run / Agent 生命周期可靠性

所有 Run 必须最终进入明确终态：

- `completed`
- `failed`
- `cancelled`
- `timeout`

禁止出现真实进程已经退出但 Run 永久保持 `running` 的情况。

最低要求：

- Provider 长时间无响应可超时
- Cancel 后子进程必须最终清理
- SIGTERM/SIGKILL 升级链路可控
- 服务重启后 Run 状态可对账
- 终态与实际 Agent 进程状态一致

## 3. P1：建议在 Personal MVP 内完成

### 3.1 Delete Proposal

完整补齐 Proposal 的三种文件变更语义：

- CREATE
- MODIFY
- DELETE

要求 DELETE 与现有 Proposal → Diff → Review → Apply 流程保持一致，并通过实际文件删除测试。

### 3.2 正式 Rollback

把现有 `.flux/backups/` 从底层备份机制提升为可用的正式 Rollback 能力。

最低要求：

- 有明确的 Rollback API / 操作入口
- 可以针对最近一次 Apply 恢复
- Rollback 后 Workspace 状态正确
- Proposal / Apply / Git 状态不会产生错误记录
- Rollback 本身可测试、可重复验证

不在 Personal MVP 做：无限历史、复杂时间机器、高级版本管理 UI。

## 4. P2：本次一并完成

虽然 P2 原本可以延期，但为了让 Personal Edition 最终验收时 Agent 接入边界更加完整，本阶段一并处理以下两项。

### 4.1 Agent Runtime Contract

建立统一 Agent Runtime 抽象，避免 Flux 对单一 Agent Runtime 形成硬编码依赖。

目标结构：

```text
Task
  ↓
AgentRuntime
  ├── DSH Adapter
  ├── Codex Adapter
  └── OpenCode Adapter
```

统一处理：

- 启动
- 身份注入
- MCP 接入
- 事件流
- Tool Call
- Proposal
- 完成 / 失败 / Cancel / Timeout
- 子进程与资源清理

本阶段重点是统一契约和真实运行链路，不继续无限扩展 Agent 类型。

### 4.2 Flux 主动拉起外部 CLI Agent

Personal MVP 至少验证 Flux 可以主动启动并管理已支持的外部 CLI Agent，而不只是等待外部 Agent 主动连接 Flux MCP。

目标：

```text
Flux
 ↓
Agent Runtime
 ↓
DSH / Codex / OpenCode
 ↓
Flux MCP
 ↓
Proposal / Review / Apply / Test / Git
```

最低要求：

- Flux 能启动 Agent
- 能传递正确的平台身份和项目身份
- 能建立 MCP 连接
- 能接收事件和 Tool Call
- 能正确处理成功、失败、Cancel、Timeout
- Agent 进程结束后资源能够清理

本阶段不要求一次性接入更多 Agent。

## 5. 移动端：Personal MVP 必须可用

手机端定位不是移动 IDE，而是开发者离开电脑后对 Flux 任务进行远程查看和控制。

### 5.1 核心能力

至少支持：

- 查看任务
- 查看 Run 状态
- 查看运行进展
- 查看 Proposal / Diff
- Accept / Reject
- Cancel
- 查看错误
- 查看最终结果
- 重新进入正在运行的任务

### 5.2 移动端验收重点

数据链路：

```text
手机
 ↓
Flux
 ↓
Agent
 ↓
实时事件
 ↓
手机正确显示
```

重点验证：连接稳定、状态一致、刷新及时、后台运行后重新进入仍能获得正确状态。

不在本阶段实现完整移动 IDE。

## 6. 桌面端打包：Personal MVP 必须完成

桌面端打包属于 Personal Edition 的发布验收，而不是新的核心功能。

目标是验证普通开发者能够脱离开发环境完成：

```text
下载安装
 ↓
启动 Flux
 ↓
配置 Agent
 ↓
创建/打开项目
 ↓
Agent 工作
 ↓
Proposal
 ↓
Review
 ↓
Apply
 ↓
Test
 ↓
Git
```

最低要求：

- 可安装
- 可启动
- 配置可用
- 核心服务正常运行
- MCP 正常工作
- Agent 正常工作
- Workspace 正常工作
- Proposal / Apply / Test / Git 闭环正常
- 用户卸载/重新安装后的行为可解释

本阶段不追求复杂安装器、高级自动更新等商业发行能力。

## 7. 最终真实任务验收

上述功能完成后，进入完整 Personal MVP Benchmark。

固定使用 50 个真实开发任务：

- 10 个基础任务
- 15 个中等任务
- 10 个复杂任务
- 10 个异常任务
- 5 个真实开发任务

每个任务统一记录：

- 首次执行结果
- 最终执行结果
- 人工介入次数
- Proposal 是否正确
- MCP 调用成功率
- Apply 结果
- Test 结果
- Git 结果
- Token 消耗
- 执行时间
- 异常与恢复情况

## 8. Personal MVP 最终停止线

满足以下条件后，Personal Edition 冻结核心功能：

1. P0 问题全部解决并验证。
2. Delete Proposal 和 Rollback 完成并验证。
3. Agent Runtime Contract 完成。
4. DSH、Codex、OpenCode 的目标运行链路完成本阶段规定的验证。
5. 手机终端核心能力可用。
6. 桌面端可以完成安装 → 使用的完整闭环。
7. 50 个真实开发任务完成 Benchmark。
8. Personal MVP KPI 达标。
9. 无新的 P0/P1 Blocker。

达到停止线后：

> **不再因为新的想法继续扩张 Personal Edition。**

新功能统一进入 Post-MVP backlog。

## 9. Post-MVP 示例

以下方向在 Personal MVP 冻结后再评估：

- 更复杂的 Context Engine
- 小模型持续上下文压缩
- Prompt Optimizer
- 自动任务拆分与动态多 Agent 编排
- Skill Marketplace
- Connector Marketplace
- Cloud Agent
- Cloud Sync
- Team / Enterprise
- 更复杂的 Agent DAG / 编排可视化
- 更完整的移动 IDE

## 10. 最终目标

Personal Edition 最终不是以“功能数量”或“代码量”作为完成标准，而是证明：

> **一个普通开发者可以安装 Flux，在真实项目中连续使用它完成开发任务，并且代码修改过程可控、运行状态可靠、异常能够恢复、手机可以远程控制，整个核心闭环稳定可重复。**

这才是 Personal MVP 的最终验收标准。

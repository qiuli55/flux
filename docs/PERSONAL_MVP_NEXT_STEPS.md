# Flux Personal MVP：下一阶段完善计划

> 目标：不新增新的核心产品能力，只把当前个人版 MVP、IDE、Solo、Agent 闭环和移动端做到稳定、清晰、可长期使用。
>
> 本文基于 2026-10-02 第三轮 UI 用户级测试结果，以及此前 P0/P1/P2 问题修复与 DSH / Codex MCP 对等闭环验证整理。

---

## 1. 本阶段目标

本阶段只做四件事：

1. 修复当前测试暴露的真实缺口。
2. 完善个人版 MVP 的稳定性、恢复性和日常使用体验。
3. 完善移动端已有功能，不扩展移动端新的产品能力。
4. 完成 Release Candidate 前的安装、重启、升级和长期使用验证。

**本阶段明确不新增核心功能。**

暂缓：

- Context Engine / 上下文压缩系统
- 内置小模型 Prompt 优化
- 自动拆分多 Agent / 多对话编排
- 云服务
- Skill Marketplace
- Connector Marketplace
- 企业版能力
- 新的 Agent Loop
- 大型本地模型能力
- 新的商业化能力

这些想法统一进入 Future / Research，不进入当前 MVP 开发主线。

---

# 2. 当前版本基线

第三轮 UI 用户级测试已经覆盖并验证：

- 桌面端核心 IDE / Solo 用户流程
- 任务创建与澄清
- Agent 执行
- 决策卡
- Proposal / Diff / Apply
- Apply 后测试门禁
- Cancel
- 权限拒绝
- 冲突
- Apply 失败与回滚
- DSH 真实闭环
- Codex 经 MCP 的真实闭环
- Git 查看 / Diff / Commit
- 移动端核心闭环

因此当前阶段不是继续证明“Flux 有没有核心功能”，而是证明：

> **这些已有功能能否稳定地成为一个开发者每天可以使用的个人工具。**

---

# 3. P3 项目收口

## 3.1 P3-01：编排可视化

### 当前状态

Solo 中已经能够看到执行计划，例如“执行计划（5 步）”，但目前没有独立展示：

- 任务拆分
- Agent 分配
- 并行关系
- 步骤依赖

### 本阶段决定

**暂不实现。**

原因：它不是当前核心闭环阻塞项，属于增强型可视化能力。

### 后续处理

记录到 Future / Product Backlog，不影响 Personal MVP 完成。

---

## 3.2 P3-02：移动端审核数量

### 当前状态

移动端审核计数目前是项目级计数，容易让用户误解为当前 Task 的待审核数量。

### 处理目标

让移动端审核入口明确表达当前计数对应的范围。

### 验收

- 当前 Task 有 0 个待审核 Proposal → 显示 0。
- 当前 Task 有 1 个待审核 Proposal → 显示 1。
- 多个 Task 存在 Proposal 时，不让用户误以为数字只属于当前 Task。
- 桌面端现有计数逻辑不被破坏。

---

## 3.3 P3-03：运行中进展反馈

### 当前状态

任务运行中的反馈仍有提升空间。

### 本阶段目标

只完善已有运行状态的展示，不新增新的任务编排系统。

至少保证用户能够明确知道：

- 任务正在运行
- 最近一次状态变化
- Agent 是否仍在工作
- 任务是否已经进入终态
- 取消后是否真正结束

### 验收

覆盖：

- 正常运行
- 正常完成
- 失败
- Cancel
- Timeout
- Agent 无响应

---

# 4. 个人版稳定性收口

## 4.1 重启恢复

这是当前最重要的补充验证之一。

测试流程：

```text
启动 Flux
  ↓
创建项目
  ↓
配置 Agent
  ↓
创建任务
  ↓
产生 Proposal
  ↓
关闭 Flux
  ↓
重新启动
```

检查：

- Agent Registry 是否保留
- Project 是否保留
- Task 是否保留
- Task Message 是否保留
- Proposal 是否保持正确状态
- 已 Apply 的变更是否仍正确
- Git 状态是否一致
- 已结束 Run 是否不会重新执行
- running 状态是否不会形成永久僵尸

特别测试：

```text
Flux 在 Agent 执行过程中关闭
        ↓
重新启动
        ↓
Run / Task 状态是否正确恢复
```

---

## 4.2 长任务稳定性

不新增长任务能力，只验证已有能力。

至少测试：

- 短任务
- 中等任务
- 较长任务
- Agent 长时间无输出
- Provider 连接异常
- Timeout
- Cancel

重点检查：

- 心跳
- Run 状态
- Task 状态
- 子进程退出
- 子进程树清理
- 终态事件
- UI 状态

---

## 4.3 Cancel / Timeout / Crash Recovery

已有测试已经覆盖 Cancel，但个人版收口阶段需要做完整组合回归。

矩阵：

| 场景 | 预期 |
|---|---|
| 正常完成 | completed |
| Agent 主动失败 | failed |
| 用户取消 | cancelled |
| 超时 | timeout / failed（按当前实现约定） |
| Agent 进程退出 | 正确终态 |
| Flux 重启 | 不产生永久 running |
| 子进程异常退出 | 子进程不残留 |
| Cancel 与自然结束竞争 | 只能产生一个最终状态 |

---

# 5. Agent / MCP 回归

当前已经验证 DSH 与 Codex 可以通过同一 MCP 能力面工作。

本阶段不增加第三个 Agent，重点验证已有两个 Agent 的一致性。

## DSH

验证：

- context.get
- workspace.read
- workspace.diff
- proposal.create
- 权限拒绝
- Proposal 审核
- Apply
- Test
- Git
- Cancel
- Timeout

## Codex

验证完全相同的闭环：

```text
Codex
 ↓
Flux MCP
 ↓
context.get
 ↓
workspace.read
 ↓
workspace.diff
 ↓
proposal.create
 ↓
Human Review
 ↓
Apply
 ↓
Test
 ↓
Git
```

并继续验证身份防冒充：

- Token identity 正常
- Fake Agent ID 拒绝
- Agent A 冒充 Agent B 拒绝
- Proposal / Run / Audit attribution 一致

---

# 6. Proposal / Apply 收口

已有功能不改设计，只验证边界。

## Proposal 状态

至少覆盖：

```text
pending
 ↓
approved / rejected / expired
 ↓
applied / failed
```

检查不能出现：

- expired Proposal 被 Apply
- failed Proposal 被当作 applied
- rejected Proposal 被再次 Apply
- 已 Apply Proposal 重复执行
- UI 状态与后端状态不一致

## Apply

重点验证：

- 正常 Apply
- 多文件 Apply
- 冲突
- 测试失败
- 部分失败
- Rollback
- Apply 后 Git 状态

已有测试显示 Apply 后测试失败时可以回滚新文件，本阶段重点做回归和异常组合，而不是重新设计 Apply Engine。

---

# 7. IDE 个人版完善

## 7.1 桌面端

保持当前信息架构：

```text
IDE
 ├── Project / File Tree
 ├── Editor
 ├── Agent
 ├── Changes / Proposal
 └── Bottom Panel
```

不新增新的工作区模式。

继续检查：

- 文件树展开 / 收起
- 编辑器打开真实文件
- Diff 查看
- Proposal 查看
- Apply / Reject
- Focus Mode
- 面板恢复
- Git 状态
- 错误提示

## 7.2 窗口尺寸

继续保持第三轮已验证的四档：

- 1920×1080
- 1440×900
- 1280×800
- 1024×768

确保：

- 无横向滚动
- 编辑器仍为核心区域
- 顶栏不裁切
- Dialog 不溢出
- Activity Rail 保持可用

---

# 8. Solo 个人版完善

Solo 当前核心路径保持不变：

```text
需求
 ↓
澄清
 ↓
计划
 ↓
决策
 ↓
执行
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

重点不是增加功能，而是让每一步都清楚：

- 我现在在哪里？
- Agent 正在做什么？
- 为什么需要我决定？
- 如果我批准会发生什么？
- 如果失败了怎么办？
- 如何重新开始？

---

# 9. 移动端个人版完善

移动端定位保持为：

> **远程查看、决策、控制 Flux，而不是把桌面 IDE 搬到手机。**

## 必须稳定的能力

### 9.1 Task

- 查看任务列表
- 查看任务详情
- 查看状态
- 查看运行中进展
- 查看终态

### 9.2 Decision

- 查看需要用户决定的问题
- 查看选项
- 查看 AI 建议
- 手动选择
- 确认继续

### 9.3 Proposal

- 查看 Proposal
- 查看变更目的
- 查看 Diff
- 批准
- 拒绝

### 9.4 Run Control

- 查看运行状态
- Cancel
- 查看 Cancel 后终态
- 重新创建 / 重跑的明确入口

### 9.5 Error

至少能理解：

- 权限拒绝
- Agent 失败
- Apply 失败
- 测试失败
- 冲突
- Timeout

不要求手机端显示完整技术日志，但必须提供明确的结论和下一步。

---

# 10. 移动端回归尺寸

至少继续保持：

- 390×844
- 触摸设备
- DPR=2

重点验证：

- 无横向滚动
- Bottom Navigation 可用
- Task 卡片可点击
- Proposal / Decision 卡片可操作
- Diff 可读
- 按钮不会被遮挡
- 长文本不会破坏布局
- Toast / Error 不遮挡关键操作

---

# 11. 安装 / 打包 / 升级

个人版真正发布前需要新增一轮“安装级测试”。

## 安装

```text
全新环境
 ↓
安装 Flux
 ↓
启动
 ↓
创建项目
 ↓
配置 Agent
 ↓
完成任务
```

## 重启

```text
关闭
 ↓
再次启动
 ↓
数据是否完整
```

## 升级

```text
旧版本
 ↓
备份
 ↓
升级
 ↓
启动
 ↓
旧项目 / Agent / Task 是否仍可用
```

如果当前版本还没有正式升级机制，则至少记录当前版本的数据库 / 配置迁移假设，不要在 MVP 阶段为了升级系统引入大型基础设施。

---

# 12. Release Candidate 验收标准

只有以下条件全部满足，才进入 Personal MVP 发布候选：

### 核心闭环

- [ ] DSH 完整闭环通过
- [ ] Codex 完整闭环通过
- [ ] Proposal / Review / Apply 正常
- [ ] Apply 后测试门禁正常
- [ ] Git 状态正确

### 异常

- [ ] 权限拒绝
- [ ] 冲突
- [ ] Apply 失败
- [ ] Test 失败
- [ ] Cancel
- [ ] Timeout
- [ ] Agent Crash
- [ ] Flux Restart Recovery

### IDE

- [ ] 桌面四档尺寸
- [ ] Focus Mode
- [ ] 面板收起 / 恢复
- [ ] 文件树
- [ ] Editor
- [ ] Diff
- [ ] Git

### Solo

- [ ] 新建任务
- [ ] 澄清
- [ ] 执行计划
- [ ] 用户决策
- [ ] AI 默认决策留痕
- [ ] 运行状态
- [ ] 终态

### Mobile

- [ ] Task
- [ ] Decision
- [ ] Proposal
- [ ] Diff
- [ ] Approve / Reject
- [ ] Cancel
- [ ] Error
- [ ] 390×844
- [ ] 无横向滚动

### 数据

- [ ] 重启后 Agent 保留
- [ ] 重启后 Task 保留
- [ ] 重启后 Message 保留
- [ ] Proposal 状态一致
- [ ] Git 状态一致

---

# 13. 本阶段明确停止线

当以下条件满足后，**停止继续开发个人版核心功能**：

```text
核心闭环稳定
       ↓
移动端闭环稳定
       ↓
异常场景稳定
       ↓
重启恢复稳定
       ↓
安装 / 打包稳定
       ↓
个人连续使用
       ↓
只修真实遇到的问题
```

之后新需求统一进入 Backlog，不直接进入当前版本。

---

# 14. 后续版本候选，但不属于当前 MVP

以下内容只作为后续研究，不在本阶段实现：

- Context Engine
- 增量上下文压缩
- 小模型 Prompt 优化
- 自动任务结构化
- 动态 Agent 多对话拆分
- 更复杂的 Agent Orchestration
- 云服务
- 团队协作
- Marketplace
- 商业化

当前版本的目标不是证明 Flux 能解决所有 AI Coding 问题，而是证明：

> **一个开发者可以稳定地用 Flux 完成真实的 AI 辅助开发工作。**

---

# 15. 推荐执行顺序

```text
Step 1
P3-02 / P3-03 小修
        ↓
Step 2
重启恢复测试
        ↓
Step 3
Run / Cancel / Timeout / Crash 组合回归
        ↓
Step 4
DSH + Codex 对等回归
        ↓
Step 5
Proposal / Apply / Rollback 回归
        ↓
Step 6
移动端完整回归
        ↓
Step 7
安装 / 首次启动 / 重启
        ↓
Step 8
Release Candidate 打包
        ↓
Step 9
个人连续使用
        ↓
Step 10
只修真实问题
        ↓
Personal MVP Freeze
```

## 最终原则

**这一阶段不追求“功能更多”，只追求“已有功能更可靠、更顺手、更像一个真正可以每天使用的软件”。**

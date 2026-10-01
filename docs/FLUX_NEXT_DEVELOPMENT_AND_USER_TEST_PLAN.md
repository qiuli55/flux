# Flux 下一阶段开发计划与完整用户测试方案

> 状态：执行计划
> 更新基线：`main` / `c6a101e`（2026-09-30）
> 范围：本地版 Flux 核心能力完善、完整用户测试、稳定性与性能验证
>
> 本阶段明确**不做 Cloud**：不实现云端 Agent、云盘、云端 Credential Vault、云同步、计费、团队 SaaS、在线 Marketplace。所有云服务设计只保留未来扩展边界，不进入当前实现。

---

## 1. 当前阶段目标

Flux 当前已经完成重要的架构收口：Flux 定位为“集合 Agent 的平台”，自身不运行 Agent Loop；外部 Agent 通过 Flux MCP 能力面访问 Context、Workspace 和 Proposal 能力。最新 MCP 提交已经落地 Streamable HTTP、令牌鉴权以及 `context.get`、`workspace.read`、`workspace.diff`、`proposal.create` 四个工具。

当前阶段不以“增加功能数量”为目标，而以以下结果为完成标准：

> 一个普通用户可以从提出需求开始，经需求澄清、任务计划、Agent 执行、Flux MCP、Proposal/Diff、人工审核、Apply、测试、Git 收尾，完整完成一次真实的软件开发任务；任务中断或失败后能够恢复，并且 Agent 无法绕过 Flux 的安全边界直接修改真实工作区。

---

# 2. P0：必须先完成的核心闭环

## P0-01：打通 DSH → Flux MCP → Proposal → Apply

### 目标

建立第一条真正可用的内置 Agent 工作链：

```text
用户任务
  ↓
Solo
  ↓
需求追问
  ↓
需求确认
  ↓
任务计划
  ↓
DSH
  ↓
Flux MCP
  ├─ context.get
  ├─ workspace.read
  ├─ workspace.diff
  └─ proposal.create
  ↓
Proposal
  ↓
Diff
  ↓
人工审核
  ↓
Apply
  ↓
测试
  ↓
结果
```

### 必须验证

- DSH 能够发现并调用 Flux MCP。
- MCP 鉴权、工具发现、工具调用正常。
- DSH 不能绕过 MCP 直接修改真实工作区。
- `proposal.create` 产生的 Proposal 可以进入审核流程。
- Proposal 可以正确生成 Diff。
- 用户批准后才能进入 Apply。
- Apply 后真实工作区与 Proposal 一致。
- Agent 能获取 Apply/测试结果并继续任务。
- Agent 失败、暂停、恢复时状态不会错乱。

### 完成条件

至少用一个真实项目完成一次从任务输入到代码交付的端到端任务，并留下可审计的任务、Proposal、Apply、测试和 Git 轨迹。

---

# 3. P0：Proposal / Diff / Apply / Rollback

## P0-02：Proposal 生命周期

必须支持：

- 创建 Proposal
- 查看 Proposal
- Diff 预览
- 接受全部
- 拒绝全部
- 按文件审核
- 按变更审核
- Proposal 过期/失效
- Proposal 被修改后的状态一致性

## P0-03：多文件原子 Apply

这是当前明确的核心剩余项之一。

目标：

```text
A.ts 修改
B.ts 修改
C.ts 修改
       ↓
Apply
       ↓
全部成功 → 完成
任意失败 → 全部恢复
```

禁止出现：

```text
A.ts ✓
B.ts ✓
C.ts ✗

但 A/B 已经留在工作区
```

### 必须测试

- 单文件成功
- 多文件成功
- 中途失败
- 文件不存在
- 文件被外部修改
- 权限不足
- 磁盘写入失败
- 备份失败
- Rollback 失败
- 并发 Apply

## P0-04：路径与文件安全

继续保持 fail-closed：

- `../` 路径逃逸
- 绝对路径
- symlink 文件
- symlink 目录
- 工作区内部 symlink
- `.flux` 路径逃逸
- backup/restore 路径逃逸
- 竞态条件

任何无法确认安全的路径都应该拒绝，而不是尝试猜测。

---

# 4. P0：Solo 完整工作流

## P0-05：Solo 任务创建

Solo 支持两种基本方式：

### 单 Agent

```text
用户 → Agent → 执行
```

### AI 编排

```text
用户
 ↓
Flux 内置编排能力
 ↓
拆解任务
 ↓
选择/调用 Agent
 ↓
执行
```

本阶段重点不是增加复杂的多 Agent 编排，而是把任务生命周期做稳定。

## P0-06：AI 需求追问

用户只给出不完整需求时，AI 应主动追问，而不是立即开始修改代码。

流程：

```text
用户需求
 ↓
AI 判断信息是否完整
 ↓
需要信息 → 追问
 ↓
用户回答
 ↓
继续追问
 ↓
需求完整
 ↓
生成需求确认
```

需求确认必须至少展示：

- 目标
- 功能范围
- 技术方案
- 修改范围
- 风险
- 需要人工审核的环节

用户可以修改确认内容后再开始执行。

---

# 5. P0：执行过程中的决策模式

这是 Flux Solo 的核心交互之一。

用户可以提前选择：

### 模式 A：使用 AI 默认方案

Agent 执行中遇到可选方案时：

```text
Agent 遇到问题
 ↓
根据默认策略自行选择
 ↓
继续执行
```

不把普通决策问题打断给用户。

### 模式 B：我来决定

```text
Agent 遇到问题
 ↓
暂停
 ↓
Flux 向用户展示问题
 ↓
展示候选方案、影响和推荐依据
 ↓
用户选择
 ↓
Agent 继续
```

### 必须测试

- Agent 真正遇到决策点时是否暂停。
- 默认方案模式是否真的不打扰用户。
- 用户决定模式是否真的阻塞 Agent。
- 用户决定后 Agent 是否从正确状态继续。
- 用户长时间不响应时任务状态是否正确。
- 用户拒绝所有候选方案时是否能够重新询问。
- 手机端和桌面端对同一个待决策状态是否一致。

---

# 6. P0：Context 系统

Context 是 Flux 的基础设施，不应该只靠把大量文本塞进 Prompt。

## 必须验证

- Project Context
- Task Context
- Workspace Context
- Git Context
- Agent 操作轨迹
- MCP 操作轨迹
- Handoff
- L0/L1/L2 信息层级
- Context 裁剪
- Context 更新
- Context 失效
- 长任务 Context 膨胀
- 不同任务之间的 Context 污染

### 核心要求

Agent 的主路径应该通过 Flux MCP 获取需要的 Context；历史对话不应无条件全部进入长期存档。

### 测试重点

连续执行多个相关任务后检查：

```text
Task A
 ↓
Task B
 ↓
Task C
```

Task C 不应错误继承 Task A 的临时决策或过期信息。

---

# 7. P0：Permission / Security Boundary

必须建立明确的能力边界。

| 能力 | Agent 默认状态 |
|---|---|
| 读取 Workspace | 允许（受范围限制） |
| 查看 Context | 允许（受权限限制） |
| 创建 Proposal | 允许 |
| 直接写真实文件 | 禁止 |
| Apply Proposal | 必须经过 Flux 控制 |
| Git Push | 当前 MCP 面禁止 |
| Secret 读取 | 当前 MCP 面禁止 |
| Shell 执行 | 当前 MCP 面禁止 |
| 路径逃逸 | 禁止 |

### 安全测试

必须主动让 Agent 尝试：

- 直接修改文件
- 读取 `.env`
- 读取 Flux 内部敏感数据
- `../` 逃逸
- symlink 逃逸
- 伪造 Agent 身份
- 使用无效 Token
- 使用已撤销 Token
- 调用不存在的工具
- 构造恶意 MCP 参数

预期结果：明确拒绝，不能静默降级。

---

# 8. P1：Git 工作流

完整验证：

```text
查看状态
 ↓
创建/切换分支
 ↓
Agent 修改
 ↓
Proposal 审核
 ↓
Apply
 ↓
测试
 ↓
查看 Diff
 ↓
Commit
 ↓
Rollback / 恢复
```

重点保证：

- Flux Workspace 状态与 Git 状态一致。
- Agent 失败不会留下不可解释的 Git 状态。
- Proposal 被拒绝后不会产生意外 Git 修改。
- Apply 后 Git Diff 与用户看到的 Proposal 一致。

---

# 9. P1：Skill 系统

第一阶段目标是本地 Skill，而不是在线 Marketplace。

支持：

- 安装
- 卸载
- 启用
- 禁用
- 配置
- 权限声明
- Skill 与 MCP 协作
- Skill 与 DSH 协作

## 外部 Skill 适配

目标架构：

```text
外部 Skill
  ↓
Flux Adapter
  ↓
Flux Skill Contract
  ↓
Context / MCP / Permission
```

Flux 不应该要求所有外部 Skill 重新开发一套实现，而应该提供适配层。

必须测试至少三类情况：

1. 完全兼容 Skill
2. 部分兼容 Skill
3. 与 Flux 能力模型存在冲突的 Skill

冲突时必须明确告诉用户缺少什么能力，而不是假装兼容。

---

# 10. P1：DSH Plugin 兼容

Flux 内置 Agent 基于 DeepSeek Harness 时，优先保留 DSH 的 Plugin 生态。

目标：

```text
DSH Plugin
  ↓
Flux Plugin Adapter
  ↓
Flux Plugin Runtime
```

优先级：

1. 能否原生加载 DSH Plugin。
2. 不能原生加载时提供适配层。
3. Plugin 的权限、Context、工具调用必须经过 Flux 的安全边界。

禁止因为兼容 DSH Plugin 而重新开放 Flux MCP 已禁止的能力。

---

# 11. P1：本地 Agent Account

本阶段只做本地账号管理，不做云同步。

统一抽象：

```text
Agent Account Manager
├── Trae
│   ├── Personal
│   ├── Work
│   └── Test
├── Codex
└── Claude
```

能力：

- 添加账号
- 删除账号
- 当前账号
- 手动切换
- 登录状态检测
- Provider Adapter
- 本地凭证安全存储

注意：Flux MCP Token 与 Trae/Codex/Claude 登录凭证不是同一种凭证，必须分开建模。

对于具体 Provider，必须先验证其当前版本的实际本地认证结构，再决定是否支持状态迁移；不能假定单个 Token 就等于完整登录状态。

---

# 12. P1：Mobile Remote Control

第一阶段手机端不依赖 Cloud。

```text
Flux Mobile
   │
   │ WebSocket / SSE / 安全本地连接
   ↓
Flux Desktop
```

核心能力：

- 查看任务
- 查看 Agent 状态
- 查看执行日志
- 查看任务计划
- 查看 Diff
- Approve / Reject
- 回复 Agent 问题
- 暂停任务
- 继续任务
- 取消任务
- 查看终端
- 切换本地 Agent 账号

手机端应能处理“执行过程中需要人工决定”的关键节点。

---

# 13. 当前阶段明确不做的内容

以下内容全部延后：

```text
Cloud Agent
Cloud Workspace
Cloud Drive
Cloud Credential Vault
Cloud Token Sync
Billing
Usage Credits
Team SaaS
Online Skill Marketplace
Online Connector Marketplace
Cloud Context Sync
```

原因：当前优先验证 Flux 本地核心产品是否成立。

代码层面只保留未来可扩展的接口，不提前实现云服务业务逻辑。

---

# 14. 完整用户测试计划

用户测试不只是检查按钮是否可点击，而是模拟真实用户从第一次打开 Flux 到完成一次真实开发任务。

---

## Phase A：首次使用

### TC-001 首次启动

**操作**

1. 启动 Flux。
2. 创建或打开项目。
3. 进入 IDE。
4. 进入 Solo。
5. 查看 Agent 状态。

**验证**

- 启动是否稳定。
- 项目是否正确识别。
- Workspace 是否初始化。
- Git 是否初始化/识别。
- Agent 是否正确显示。
- 是否存在阻塞性错误。

### TC-002 新用户理解能力

给测试者唯一说明：

> “使用 Flux 给这个项目增加一个功能。”

不解释产品界面。

记录：

- 能否找到 Solo。
- 能否创建任务。
- 是否理解任务状态。
- 是否理解 Proposal。
- 是否理解 Diff。
- 是否理解 Apply。
- 是否知道什么时候需要人工审核。

目标：发现产品信息架构问题，而不是帮助测试者通过测试。

---

# 15. 功能任务测试

## TC-101 简单任务

任务：

> 给项目增加一个 `/health` API，返回当前服务状态，并增加对应测试。

检查完整链路：

```text
需求 → Agent → MCP → Proposal → Diff → Review → Apply → Test
```

记录：

- 需求理解是否正确。
- 修改文件是否正确。
- 是否产生无关修改。
- Diff 是否与实际修改一致。
- Apply 是否成功。
- 测试是否通过。

---

## TC-102 不完整需求

任务：

> 给项目增加用户登录功能。

故意不提供：

- 登录方式
- Session/JWT 选择
- 用户数据存储方式
- 是否支持注册
- 权限需求

检查 Agent 是否主动追问，而不是直接开始写代码。

---

## TC-103 需求确认

测试：

1. 回答 Agent 的问题。
2. 查看最终需求确认。
3. 修改其中一项。
4. 再开始执行。

检查：

- Agent 是否使用最终版本需求。
- 旧需求是否仍然影响执行。
- 修改需求后是否重新计算计划。
- 人工审核点是否明确标注。

---

# 16. 执行过程决策测试

## TC-201 默认方案

设置：

> 使用 AI 默认方案。

制造一个需要选择的技术决策。

检查：

- Agent 是否自行选择。
- 用户是否没有收到不必要的阻塞询问。
- 最终结果是否记录使用的方案。

## TC-202 我来决定

设置：

> 我来决定。

制造相同决策。

检查：

- Agent 是否暂停。
- 用户是否看到问题。
- 是否看到候选方案。
- 是否看到影响/风险。
- 用户选择后是否继续。
- 是否从正确的执行节点继续。

## TC-203 用户长时间不响应

暂停在待决策状态至少数分钟，关闭/重新打开界面。

检查任务状态是否仍然是：

```text
WAITING_FOR_USER_DECISION
```

而不是误判为失败或完成。

---

# 17. Proposal / Diff / Apply 测试

## TC-301 单文件修改

验证：创建 Proposal → 查看 Diff → Approve → Apply。

## TC-302 多文件修改

至少 4 个文件同时修改，检查 Diff 是否完整。

## TC-303 部分拒绝

批准部分文件，拒绝部分文件。

检查最终工作区是否与用户选择一致。

## TC-304 Apply 中途失败

制造：

```text
A ✓
B ✓
C ✗
```

预期：整个 Apply 恢复到执行前状态。

## TC-305 外部文件冲突

Proposal 创建后，在外部修改同一文件，再 Apply。

检查 Flux 是否发现冲突，而不是覆盖用户修改。

---

# 18. Agent 越权测试

## TC-401 直接写文件

让 Agent 尝试绕过 `proposal.create` 修改真实文件。

预期：拒绝。

## TC-402 Secret

让 Agent 尝试读取 `.env` 或其他敏感文件。

预期：按照 Permission Policy 拒绝。

## TC-403 Shell

让 Agent 尝试通过 MCP 获取 Shell 能力。

预期：不存在该工具或明确拒绝。

## TC-404 Git Push

让 Agent 尝试直接 Push。

预期：MCP 面没有该能力。

## TC-405 Token 撤销

撤销 Agent Token 后立即发起请求。

预期：下一次请求立即失败。

---

# 19. 崩溃与恢复测试

## TC-501 Agent 崩溃

执行过程中强制终止 Agent。

重新进入任务。

检查：

- Task 状态
- Proposal 状态
- Workspace 状态
- Agent 状态
- Context

是否一致。

## TC-502 Flux 崩溃

执行过程中直接关闭 Flux，再重新打开。

预期：未完成任务不会变成错误的“已完成”。

## TC-503 Apply 崩溃

Apply 过程中终止进程。

检查：

- 是否有备份。
- 是否能够恢复。
- 工作区是否处于可解释状态。
- 是否出现半应用状态。

---

# 20. Git 测试

## TC-601 标准开发流程

```text
创建分支
 ↓
Agent 执行
 ↓
Review
 ↓
Apply
 ↓
Test
 ↓
Commit
```

## TC-602 Rollback

完成一次修改后 Rollback。

检查：

- 文件状态
- Git Diff
- Proposal 状态
- Task 状态

## TC-603 Agent 失败后的 Git

Agent 执行失败后检查 Git 是否留下无法解释的修改。

---

# 21. 外部 Agent MCP 测试

至少测试：

- DSH
- Codex
- Claude Code

统一路径：

```text
External Agent
 ↓
Flux MCP
 ↓
Flux capability
```

每个 Agent 都执行：

1. `context.get`
2. `workspace.read`
3. `workspace.diff`
4. `proposal.create`
5. 越权操作

检查不同 Agent 得到的能力边界是否一致。

---

# 22. Skill / Plugin 测试

## TC-801 Skill 安装

安装一个外部 Skill。

检查：

- 是否识别。
- 是否正确适配。
- 是否声明依赖。
- 是否声明权限。

## TC-802 部分兼容 Skill

让 Skill 使用 Flux 不存在的能力。

预期：明确报告不兼容点。

## TC-803 DSH Plugin

加载一个实际 DSH Plugin，检查：

- Plugin 是否能被发现。
- Plugin 是否能执行。
- MCP/Permission 是否仍然生效。
- Plugin 是否可以绕过 Flux 安全边界。

---

# 23. 本地 Agent Account 测试

## TC-901 多账号

至少准备两个同 Provider 账号：

```text
Trae Personal
Trae Work
```

测试：

- 添加
- 切换
- 当前账号显示
- 登录状态检测
- 删除
- 重启 Flux 后状态保持

## TC-902 Provider 隔离

确认：

```text
Flux MCP Token
≠
Trae Login Credential
≠
Codex Login Credential
≠
Claude Login Credential
```

任何一种凭证泄露/失效都不能导致其他 Provider 的状态混乱。

---

# 24. Mobile Remote Control 测试

## TC-1001 连接

手机连接电脑 Flux。

检查：

- 在线状态
- 断线
- 重连
- 网络切换

## TC-1002 任务控制

手机执行：

- 查看任务
- 暂停
- 继续
- 取消

检查桌面端状态是否同步。

## TC-1003 审核

手机查看 Proposal/Diff 并：

- Approve
- Reject

检查桌面端结果。

## TC-1004 Agent 决策

Agent 设置为“我来决定”。

让 Agent 在电脑端执行过程中产生问题。

手机收到问题并做选择。

检查任务是否继续。

---

# 25. 长任务 / 稳定性测试

至少执行：

- 30 分钟任务
- 1 小时任务
- 多次连续任务
- 多次暂停/继续
- 多次 Proposal
- 多次 Apply

监控：

- CPU
- 内存
- SQLite/数据库
- WebSocket/SSE
- MCP 请求
- Agent 进程
- Context 大小
- 日志增长
- 文件句柄

检查是否存在：

- 内存泄漏
- 状态泄漏
- Context 无限增长
- Event 重复
- Agent 进程残留
- WebSocket 连接残留

---

# 26. 大项目测试

至少准备：

### 小项目

几十个文件。

### 中型项目

约 1,000 个文件。

### 大型项目

10,000+ 文件。

测试：

- 项目扫描
- Context 获取
- 文件搜索
- Workspace Read
- Diff
- Proposal
- Git 状态
- MCP 响应时间

记录性能变化。

---

# 27. 完整真实用户测试

这是发布前最重要的一项。

不要使用专门为测试准备的 Demo 项目，而是直接选择一个真实项目。

给 Flux 一个真实需求，例如：

> 给项目增加一个实际业务功能，并补齐测试。

测试者只允许像普通用户一样操作，不直接修改代码帮助 Agent。

记录：

```text
开始时间
结束时间
总耗时
人工干预次数
Agent 追问次数
Agent 决策次数
用户决策次数
Proposal 数量
Apply 次数
Apply 失败次数
测试失败次数
返工次数
最终 Git Diff
最终测试结果
```

同时记录所有明显的 UX 问题：

- 不知道下一步该点哪里
- 不知道 Agent 在做什么
- 不知道为什么暂停
- 不知道为什么需要审核
- Diff 看不懂
- 错误信息看不懂
- 手机无法完成操作
- 桌面/手机状态不一致

---

# 28. 用户测试结果分类

所有问题统一分级：

### P0 Blocker

阻止任务完成或造成数据/代码破坏。

例如：

- Apply 半成功导致工作区损坏
- Agent 可以绕过权限直接写文件
- Proposal 与实际写盘内容不一致
- 任务状态丢失

### P1 Critical

严重影响核心工作流，但存在绕过方式。

例如：

- DSH 无法完成 MCP 闭环
- Context 错乱
- Git 状态错误
- 手机无法处理人工决策

### P2 Major

明显影响效率或理解成本。

例如：

- Diff 不好读
- 任务状态不清晰
- Skill 适配体验差

### P3 Minor

非阻塞问题：

- UI 细节
- 文案
- 动画
- 排版

---

# 29. Release Gate

本地版 Flux 在进入 Cloud 之前必须满足：

| 模块 | 发布门槛 |
|---|---|
| 启动 | 无 P0/P1 启动问题 |
| Workspace | 稳定 |
| Solo | 完整可用 |
| DSH | 能真实完成任务 |
| MCP | 外部 Agent 可用 |
| Context | 不明显污染/失控 |
| Permission | 无明显越权 |
| Proposal | 完整 |
| Diff | 与实际修改一致 |
| Apply | 原子、可恢复 |
| Rollback | 可用 |
| Git | 状态一致 |
| Skill | 基础能力可用 |
| DSH Plugin | 基础兼容/适配能力可验证 |
| Agent Account | 本地可用 |
| Mobile | 核心远程操作可用 |
| 崩溃恢复 | 核心任务可恢复 |
| 长任务 | 稳定 |
| 大项目 | 可接受 |
| 真实用户测试 | 核心任务能够完成 |

### 进入下一阶段的必要条件

1. P0 问题清零。
2. P1 问题全部有明确处理结果。
3. 至少一次真实项目端到端任务成功。
4. DSH → MCP → Proposal → Apply → Test 闭环稳定。
5. 外部 Agent MCP 接入稳定。
6. 权限边界通过越权测试。
7. 多文件 Apply 具备原子性和恢复能力。
8. 手机端能够处理关键人工决策。
9. 文档与代码当前状态一致。

完成以上条件后，才进入 Cloud 设计与实现阶段。

---

# 30. 推荐开发顺序

```text
1. DSH ↔ MCP 真正闭环
        ↓
2. Proposal / Diff / Apply / Rollback
        ↓
3. 多文件原子 Apply
        ↓
4. Solo 完整工作流
        ↓
5. 需求追问 / 需求确认
        ↓
6. 执行中决策模式
        ↓
7. Context 稳定性
        ↓
8. Permission / Security
        ↓
9. Git 工作流
        ↓
10. Skill
        ↓
11. DSH Plugin 兼容
        ↓
12. 本地 Agent Account
        ↓
13. Mobile Remote Control
        ↓
14. 完整用户测试
        ↓
15. 根据测试结果返工
        ↓
16. 稳定性 / 性能 / 大项目测试
        ↓
17. Local Release Gate
        ↓
18. 再开始 Cloud 设计
```

---

# 31. 当前阶段的核心原则

### 原则 1：不以代码行数作为完成度指标

判断标准是“真实任务是否完成”，而不是新增了多少行代码。

### 原则 2：不提前为 Cloud 堆代码

现在只做好本地能力和清晰的抽象边界。

### 原则 3：Agent 是外部执行者，Flux 是平台

Flux 不重新制造 Agent Loop；Flux 提供 Workspace、Context、Permission、Proposal、Review、Apply、Git 和 MCP 能力。

### 原则 4：任何代码修改必须可解释

Agent 产生修改 → Proposal → Diff → Review → Apply。

### 原则 5：任何失败都必须可恢复或明确失败

不能留下无法解释的半成品状态。

### 原则 6：安全边界优先于功能兼容

Skill、Plugin、DSH、Codex、Claude 等外部能力都不能绕过 Flux 的 Permission 和 Workspace 边界。

### 原则 7：先验证产品，再扩大生态

先证明 Flux 能稳定完成真实开发任务，再扩大 Plugin、Marketplace、Cloud 和商业化能力。

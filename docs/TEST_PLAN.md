# Flux 测试清单与验证计划

> 基于当前 `main` 分支实际已有模块与测试文件整理。
>
> 核心目标不是单纯增加单元测试数量，而是验证 Flux 能否稳定完成一次真实的软件开发任务。

## 1. 测试目标

Flux 当前已经具备多项工程基础能力，包括：

- Agent Runtime
- Agent Manifest
- Model Gateway / Provider
- Permission Engine
- Project Scanner
- Project Brain
- Project File Explorer
- Virtual Workspace
- Proposal / Diff
- Apply Engine
- Tester Agent
- Git Integration
- Multi-Agent
- DSH API / Client
- Web Dashboard

下一阶段测试重点应从“模块是否存在”转向：

> **Requirement → Agent → Project Context → Proposal → Review → Apply → Test → Repair → Git**

必须能够在真实项目上重复运行。

---

# 2. P0：必须通过

## 2.1 Backend 启动

- [ ] 全新环境安装依赖成功
- [ ] 数据库初始化成功
- [ ] Alembic migration 成功
- [ ] Backend 正常启动
- [ ] Health API 正常
- [ ] Ready API 正常
- [ ] OpenAPI 正常生成
- [ ] Docker / Compose 环境可以启动

---

# 3. Project

## 3.1 Project 创建与生命周期

- [ ] 创建 Project
- [ ] 项目路径不存在时正确报错
- [ ] 项目路径不是目录时正确报错
- [ ] 非法路径被拒绝
- [ ] 重复创建行为正确
- [ ] 删除 Project
- [ ] Backend 重启后 Project 数据仍存在

## 3.2 真实项目

准备至少一个真实 Git 项目作为测试样本：

```
test-project/
├── src/
├── tests/
├── README.md
└── ...
```

验证：

- [ ] Flux 能识别项目
- [ ] Scanner 可以完成扫描
- [ ] 大量文件不会导致异常
- [ ] 深层目录可以处理
- [ ] 空项目可以处理

---

# 4. Project Scanner

测试以下项目类型：

- [ ] Python
- [ ] TypeScript / React
- [ ] Node.js
- [ ] 混合项目
- [ ] 空项目
- [ ] 大型项目

文件与目录边界：

- [ ] 二进制文件
- [ ] UTF-8 文件
- [ ] 非 UTF-8 文件
- [ ] symlink
- [ ] `.git`
- [ ] `node_modules`
- [ ] `dist`
- [ ] `build`
- [ ] `.venv`
- [ ] 深度限制
- [ ] 文件数量限制

---

# 5. Project Brain

重点不是测试 Brain 能否保存数据，而是验证它能否真正帮助 Agent 找到正确上下文。

- [ ] Scanner 后正确生成 Brain
- [ ] 识别主要语言
- [ ] 识别 framework
- [ ] 识别目录结构
- [ ] 查找指定模块
- [ ] 根据关键词找到相关文件
- [ ] 不存在模块返回合理结果
- [ ] 重新扫描后 Brain 可以更新
- [ ] 不会使用过期项目结构
- [ ] 大项目搜索性能可接受

### 关键验证

输入：

> “我要修改用户登录功能。”

检查 Brain 是否能够找到真正相关的：

- 用户模块
- Auth 模块
- API
- 数据库相关代码
- 对应测试

---

# 6. Project File Explorer

当前已有：

- `GET /projects/{id}/files`
- `GET /projects/{id}/files/content`

## 6.1 正常情况

- [ ] 正常列出文件
- [ ] 正常读取文本文件
- [ ] 空文件
- [ ] 中文文件
- [ ] 文件不存在
- [ ] 目录作为文件读取

## 6.2 安全

必须测试：

- [ ] `../`
- [ ] `../../`
- [ ] 绝对路径
- [ ] Windows 风格绝对路径
- [ ] URL encoded traversal
- [ ] symlink 指向项目外
- [ ] 项目外文件读取
- [ ] 超过文件大小限制
- [ ] 超过目录深度
- [ ] 超过文件数量

任何路径逃逸都必须拒绝。

---

# 7. Virtual Workspace

这是 Flux 的核心能力之一。

验证：

```
Real File
   ↓
Virtual Workspace
   ↓
Proposal
   ↓
Diff
```

测试：

- [ ] 创建 Proposal
- [ ] 修改已有文件
- [ ] 创建新文件
- [ ] 删除文件
- [ ] 多文件修改
- [ ] 空修改
- [ ] 同一文件多次修改
- [ ] Proposal 更新
- [ ] Accept
- [ ] Reject
- [ ] 重启后 Proposal 状态正确

---

# 8. Diff Engine

## 8.1 普通修改

验证：

- [ ] 新增行
- [ ] 删除行
- [ ] Context 行
- [ ] 行号
- [ ] 多个 hunk

## 8.2 边界

- [ ] 空文件 → 有内容
- [ ] 有内容 → 空文件
- [ ] 新文件
- [ ] 删除文件
- [ ] 中文
- [ ] emoji
- [ ] 超长行
- [ ] 文件没有结尾 newline
- [ ] CRLF
- [ ] LF
- [ ] 代码行以 `---` 开头
- [ ] 代码行以 `+++` 开头

尤其检查 Diff Parser 不会把正常代码误认为 unified diff header。

---

# 9. Apply Engine

这是 Flux 最需要进行破坏性测试的模块之一。

## 9.1 正常 Apply

- [ ] 单文件修改
- [ ] 多文件修改
- [ ] 创建新文件
- [ ] 删除文件
- [ ] Apply 后内容正确
- [ ] Apply 后 hash 正确

## 9.2 Hash Conflict

模拟：

```
AI 读取 A.py
    ↓
创建 Proposal
    ↓
用户手动修改 A.py
    ↓
Flux Apply
```

期望：

```
Apply → Reject
```

绝不能覆盖用户修改。

## 9.3 Apply 失败与回滚

模拟：

```
A.py 修改成功
B.py 修改失败
```

期望：

```
A.py rollback
B.py rollback
```

最终 workspace 必须恢复到 Apply 前状态。

## 9.4 权限

- [ ] Agent 可以读取项目
- [ ] Agent 可以创建 Proposal
- [ ] Agent 不能绕过 Proposal 直接修改真实文件
- [ ] 只有 Apply Engine 执行最终写入
- [ ] 路径越界写入被拒绝
- [ ] symlink 逃逸写入被拒绝

---

# 10. Tester Agent

测试：

```
Code
 ↓
Tester
 ↓
Execute Tests
 ↓
Result
```

必须覆盖：

- [ ] 测试成功
- [ ] 测试失败
- [ ] command 不存在
- [ ] timeout
- [ ] exit code 非 0
- [ ] stdout
- [ ] stderr
- [ ] 大量日志
- [ ] 测试进程异常退出
- [ ] 工作目录错误

---

# 11. Git Integration

验证完整生命周期：

```
Git init
 ↓
Status
 ↓
Diff
 ↓
Stage
 ↓
Commit
 ↓
Status clean
```

测试：

- [ ] 非 Git 项目
- [ ] Git 未安装
- [ ] 单文件修改
- [ ] 多文件修改
- [ ] 新文件
- [ ] 删除文件
- [ ] Git diff
- [ ] Stage
- [ ] Commit
- [ ] Commit message
- [ ] Branch
- [ ] Git command 失败

---

# 12. Agent Runtime

当前已有 Agent Runtime 测试基础。

重点测试状态机：

```
CREATED
   ↓
RUNNING
   ↓
WAITING
   ↓
RUNNING
   ↓
COMPLETED
```

异常路径：

```
RUNNING → FAILED
RUNNING → CANCELLED
```

测试：

- [ ] start
- [ ] complete
- [ ] fail
- [ ] cancel
- [ ] duplicate start
- [ ] invalid transition
- [ ] concurrent transition
- [ ] Agent exception
- [ ] restart / recovery

---

# 13. Multi-Agent

测试：

```
Tech Lead
    │
    ├── Developer A
    ├── Developer B
    └── Tester
```

必须验证：

- [ ] Agent 可以并行运行
- [ ] 一个 Agent 失败不会污染其他 Agent
- [ ] Agent ID 不冲突
- [ ] Task ID 不冲突
- [ ] 状态正确
- [ ] 同一文件并发修改不会互相覆盖
- [ ] Proposal 互相隔离
- [ ] Agent 输出可以传递给下一个 Agent

---

# 14. DSH

当前仓库已经有 DSH API / Client 相关代码和测试，但这不等同于完整 DSH Agent Runtime 已经完成。

第一阶段先测试连接与故障处理：

- [ ] DSH 配置缺失
- [ ] DSH 未启动
- [ ] DSH 启动失败
- [ ] Flux 检测 DSH 状态
- [ ] Flux 建立连接
- [ ] Request 成功
- [ ] Request timeout
- [ ] DSH crash
- [ ] malformed response
- [ ] 错误能够正确映射
- [ ] AgentRun 不会永久卡死

随后再进行：

> **DSH Agent 真正执行 Flux Task。**

---

# 15. API 全量测试

所有 API 至少覆盖：

```
正常输入
错误输入
空输入
缺少字段
非法 ID
不存在 ID
权限不足
重复请求
并发请求
超时
内部异常
```

同时检查：

- [ ] HTTP Status Code
- [ ] Error Code
- [ ] Response Schema
- [ ] Validation
- [ ] Transaction Rollback

---

# 16. Web Dashboard

不要只测试“页面能打开”。

应该按照真实用户操作测试：

```
创建项目
 ↓
选择项目
 ↓
浏览文件
 ↓
打开文件
 ↓
提交需求
 ↓
看到 Agent
 ↓
看到 Proposal
 ↓
查看 Diff
 ↓
Accept
 ↓
Test
 ↓
查看结果
 ↓
Git
```

重点检查：

- [ ] File Explorer 与 Editor 联动
- [ ] Proposal 与 Diff 联动
- [ ] Agent 状态实时更新
- [ ] Timeline 与后端事件一致
- [ ] Apply 后 UI 状态刷新
- [ ] Test 结果正确显示
- [ ] Git 状态正确显示
- [ ] API 错误不会污染其他状态
- [ ] 刷新页面后状态恢复

---

# 17. P0 Golden Path E2E

这是当前最重要的测试。

准备一个真实的小型 Git 项目：

```
test-project/

src/
  user.py
  auth.py
  database.py

tests/
  test_user.py
  test_auth.py
```

向 Flux 提交真实需求：

> **给用户系统增加邮箱修改功能，并增加对应测试。**

完整流程必须能够：

```
Requirement
    ↓
Create Task
    ↓
Tech Lead
    ↓
Project Scanner / Brain
    ↓
Developer Agent
    ↓
找到相关代码
    ↓
创建 Proposal
    ↓
生成 Diff
    ↓
Human Review
    ↓
Accept
    ↓
Apply Engine
    ↓
Tester
    ↓
测试失败
    ↓
Agent Repair
    ↓
再次 Test
    ↓
Test Pass
    ↓
Git Diff
    ↓
Git Commit
```

### Golden Path 判定标准

只有满足以下条件才算通过：

- [ ] Agent 能理解需求
- [ ] 找到正确代码
- [ ] Proposal 与需求一致
- [ ] 用户可以完整 Review
- [ ] Apply 不覆盖用户修改
- [ ] 测试能够实际执行
- [ ] 测试失败后 Agent 能获得错误信息
- [ ] Agent 能进行修复
- [ ] 最终测试通过
- [ ] Git Diff 与实际修改一致
- [ ] Git Commit 成功
- [ ] 整个过程可以在日志 / Timeline 中追踪

---

# 18. P1：完整工程闭环

Golden Path 通过之后继续增加：

- [ ] 多文件复杂修改
- [ ] 新功能
- [ ] Bug Fix
- [ ] Refactor
- [ ] Test-only Task
- [ ] Dependency Upgrade
- [ ] 配置修改
- [ ] 测试失败自动修复
- [ ] 多 Agent handoff
- [ ] Agent cancellation
- [ ] Agent retry
- [ ] Agent timeout recovery
- [ ] Git rollback
- [ ] Proposal rollback

---

# 19. P2：产品化测试

后续再覆盖：

- [ ] Cost tracking
- [ ] Token usage
- [ ] Connector
- [ ] Plugin
- [ ] Skill
- [ ] Cloud Sync
- [ ] Cloud Agent
- [ ] Enterprise
- [ ] Mobile
- [ ] Marketplace

这些不应阻塞当前核心开发闭环。

---

# 20. 测试优先级

当前建议严格按照：

```
P0
████████████████████
Golden Path E2E

P0
██████████████████
Apply / Hash / Rollback / Security

P0
████████████████
Agent Runtime

P0
██████████████
Scanner / Brain

P1
████████████
Multi-Agent

P1
████████████
DSH Runtime Integration

P1
██████████
Web Dashboard E2E

P2
██████
Cost / Connector / Plugin

P3
████
Mobile / Cloud / Enterprise
```

---

# 21. 当前测试原则

### 原则 1：不要为了测试数量而测试

1000 个 CRUD 单元测试不如一个真正跑通的：

> Requirement → Agent → Proposal → Apply → Test → Repair → Git

---

### 原则 2：真实项目优先

除了单元测试，还必须使用真实 Git 项目进行验证。

---

### 原则 3：安全边界必须优先

任何能够导致：

- 路径逃逸
- 覆盖用户修改
- Agent 绕过 Proposal
- Apply 部分失败后 workspace 损坏
- symlink 逃逸

的问题都应该作为 P0。

---

### 原则 4：测试 Agent 的行为，而不仅仅是 API

Flux 最终的产品价值来自 Agent 能否完成工程任务。

---

### 原则 5：每一个核心模块最终都必须进入 E2E

单独通过：

```
Scanner ✓
Brain ✓
Proposal ✓
Apply ✓
Tester ✓
Git ✓
```

不代表 Flux 完成。

真正的完成标准是：

```
Scanner
  ↓
Brain
  ↓
Agent
  ↓
Proposal
  ↓
Apply
  ↓
Tester
  ↓
Repair
  ↓
Git
```

能够稳定串起来。

---

# 22. 当前最重要的下一步

**停止继续扩展外围功能，优先把 Golden Path 做成自动化 E2E 测试。**

目标不是证明：

> “Flux 有很多模块。”

而是证明：

> **“Flux 能真正完成一个真实的软件开发任务，并且整个 AI 工程过程可审查、可恢复、可追踪。”**

# Flux 下一阶段开发计划

> 状态：方向性方案
> 目的：明确 Flux 接下来真正值得自己实现的能力，避免继续扩大功能边界。

## 1. 核心结论

Flux 不再以“把现成工具拼起来”作为产品方案。

市场调研的价值是帮助我们判断哪些能力已经成熟、哪些方向存在竞争，但 Flux 接下来要建立自己的完整能力体系，不依赖外部成熟产品作为核心运行时。

Flux 的目标仍然是：

> **让任意 Agent 在 Flux 定义的环境、上下文、能力和权限边界内工作，并由 Flux 控制 Agent 对真实项目产生的最终影响。**

因此后续开发重点不是无限增加功能，而是把下面这条链真正做完整：

```text
Agent
  ↓
Flux Environment
  ↓
Flux Context / Memory
  ↓
Flux Capability
  ↓
Flux Permission / Policy
  ↓
Workspace / Execution Environment
  ↓
Task
  ↓
Proposal / Diff
  ↓
Validation
  ↓
Review
  ↓
Apply
  ↓
Real Project
```

---

## 2. 当前已经存在、应继续保留的核心能力

### 2.1 Agent Runtime

继续保留 Agent 注册、生命周期、状态和外部 Agent 接入基础能力。

目标不是重新制作一个强大的 Coding Agent，而是让不同 Agent 都能进入 Flux 的统一运行环境。

### 2.2 Virtual Workspace

继续作为 Flux 最核心的安全机制：

```text
Agent 修改意图
    ↓
Proposal
    ↓
Virtual Diff
    ↓
Validation
    ↓
Human Review
    ↓
Apply
```

真实项目文件不能由 Agent 直接写入。

### 2.3 Apply Engine

Apply Engine 是真实环境的唯一受控写入入口，应继续完善：

- Diff 应用
- 冲突检测
- 原子写入
- 写入后验证
- Backup
- Rollback
- Audit

### 2.4 Permission / Capability

继续建设统一 Capability 层，让 Agent 不直接接触真实资源，而是通过 Flux 请求能力。

示例：

```text
read_file
write_proposal
execute_command
network_request
browser
search
repository
connector
```

Flux 根据 Policy 决定：允许、拒绝或要求用户授权。

---

# 3. 接下来最值得开发的能力

## P0：Flux Environment

这是后续所有 Agent 能力的基础。

Agent 每次进入 Flux 时，必须知道：

- 自己是谁
- 自己正在什么 Flux 实例中
- 当前 Workspace
- 当前 Project
- 当前 Task
- 当前 Environment
- 当前拥有的 Capability
- 当前限制
- 当前上下文入口
- 当前工作规则

Flux Environment 应成为 Agent 的第一份强制上下文，而不是依赖 Agent 自己猜测运行环境。

---

## P0：三层长期记忆

建立明确的记忆边界：

### User Memory

存在 Flux 内部，跨 Workspace 使用。

用于记录用户明确允许 Flux 长期记住的信息、偏好和工作习惯。

### Project Memory

每个 Workspace / Project 自动创建。

用于记录：

- 项目结构
- 技术栈
- 架构决策
- 重要约束
- 历史问题
- 项目规范
- Agent 之间的交接信息

### Flux Environment

不是普通项目记忆，而是 Flux 自身运行规则。

它必须优先于普通项目上下文，让 Agent 知道自己必须通过 Flux 工作。

---

## P0：Flux Agent Protocol

制定自己的 Agent 接入协议。

目标：外部 Agent 接入 Flux 后，不再以“某个具体 Agent 的特殊适配逻辑”工作，而是转换成统一的 Flux Agent。

协议至少统一：

- Agent Identity
- Lifecycle
- Input / Output
- Context
- Capability
- Permission
- Task
- Event
- Tool Request
- File Request
- Status
- Interrupt
- Error
- Result

---

## P0：Agent 一键扫描与接入

用户选择“扫描 Agent”后，Flux 自动发现本机可用 Agent。

流程：

```text
扫描
 ↓
识别 Agent
 ↓
读取 Manifest / 配置
 ↓
识别能力
 ↓
识别 Skill
 ↓
识别 Connector
 ↓
转换成 Flux 标准
 ↓
生成 Flux Agent
 ↓
用户确认
 ↓
启用
```

以后新增 Agent 应尽量走统一扫描器，而不是手动配置大量字段。

---

## P0：Skill / Connector 统一协议

Flux 自己制定 Skill Protocol 与 Connector Protocol。

外部导入的 Skill / Connector 不直接进入 Flux Runtime，而是先经过：

```text
外部能力
 ↓
Scanner
 ↓
解析
 ↓
安全检查
 ↓
Flux Adapter / Converter
 ↓
Flux Skill / Connector
 ↓
注册表
```

### 重复处理

发现重复 Skill / Connector 时：

- UI 明确提示
- 展示来源、版本、能力差异
- 用户决定保留哪个
- 不静默覆盖

### 安全检查

第一阶段不做复杂安全审计，只做轻量检查：

- 来源
- Manifest 完整性
- 权限声明
- 可执行入口
- 网络访问声明
- 文件访问范围
- 可疑命令/脚本特征
- 与声明能力是否明显不一致

---

## P1：Execution Sandbox

Sandbox 不应立即替代现有 Virtual Workspace。

二者职责不同：

### Virtual Workspace

解决：

> Agent 的修改如何进入真实项目。

### Execution Sandbox

解决：

> Agent 可以在哪里运行命令、程序、测试和构建。

目标结构：

```text
Agent
 ↓
Flux Capability
 ↓
Execution Sandbox
 ↓
执行命令 / 测试 / 构建
 ↓
产生结果
 ↓
Proposal / Diff
 ↓
Review
 ↓
Apply
```

Sandbox 应与 Virtual Workspace 解耦，避免把文件隔离、执行隔离和最终落盘混成一个系统。

---

## P1：Resource Gateway

Agent 如果需要访问项目以外的资源，不直接获得整个宿主环境访问权。

流程：

```text
Agent
 ↓
请求资源
 ↓
Flux Policy
 ↓
允许 / 拒绝 / 用户授权
 ↓
读取资源
 ↓
必要时进入 Cache
 ↓
返回给 Agent
```

资源可以包括：

- 项目外文件
- 网络资源
- Repository
- Browser 页面
- API
- 数据库
- 用户授权的本地资源

### Cache 原则

缓存必须有：

- 生命周期
- 大小限制
- TTL
- 清理机制
- 来源记录
- 权限范围

禁止无限累积。

---

## P1：Agent 网络 / 外部工具访问控制

所有外部访问统一进入 Capability / Policy 层。

例如：

```text
Agent → network.request
          ↓
       Flux Policy
          ↓
    allow / deny / ask
          ↓
       Network
```

后续 Browser、Search、GitHub、数据库等能力都应该遵循同一模型。

---

## P1：Agent Context / Handoff

让多个 Agent 在同一个 Flux Workspace 中连续工作，而不是每个 Agent 从零理解项目。

例如：

```text
Agent A
分析问题
 ↓
Project / Task Context
 ↓
Agent B
实现
 ↓
Task Context
 ↓
Agent C
测试
 ↓
Agent D
Review
```

需要统一记录：

- 决策
- 已完成工作
- 未完成工作
- 风险
- 修改范围
- 测试结果
- 下一步建议

---

## P1：CLI

提供服务器端 Flux CLI。

核心目标不是复制 Web UI，而是让 Flux 能在无图形环境中完整工作。

建议支持：

```text
flux agent list
flux agent scan
flux agent add
flux task create
flux task run
flux task status
flux workspace list
flux workspace diff
flux proposal list
flux proposal review
flux proposal apply
flux logs
flux memory
flux environment
```

CLI 应与 Web / Desktop 使用同一 Flux API 与协议，不维护第二套业务逻辑。

---

# 4. IDE 面板应该采用的最终机制

现有 Proposal → Diff → Review → Apply 机制不要推翻。

推荐继续演进为：

```text
Agent
 ↓
Flux Runtime
 ↓
Virtual Workspace / Execution Environment
 ↓
IDE Panel
 ↓
Diff / Test / Validation
 ↓
User Review
 ↓
Apply Engine
 ↓
Real Workspace
```

用户看到的是“允许落盘”，但底层不是简单复制 Sandbox 文件，而是由 Apply Engine 对经过验证的变更执行受控应用。

这样可以保证：

- Agent 不直接写真实项目
- 用户可以逐项查看 Diff
- 可以拒绝变更
- 可以进行冲突检测
- 可以 Rollback
- 所有真实写入可审计

---

# 5. 未来功能边界

以下能力可以进入长期路线，但当前不应优先：

- Browser Agent
- 多设备 Node
- 移动端完整控制
- Marketplace
- Cloud Agent
- Team / Enterprise
- 成本中心
- 高级自动化
- 多人协作
- 企业审计中心
- 云端 Sandbox 集群

原则：**只有 Core Runtime 已经稳定后，才允许继续向上扩展。**

---

# 6. 明确不再采用的开发策略

Flux 后续不以“发现一个成熟工具 → 直接把它拼进 Flux”作为主要产品路线。

外部工具可以作为研究对象、参考实现或测试对象，但 Flux 的核心能力应由自己的协议、Runtime 和数据模型定义。

尤其是以下能力，长期应由 Flux 自己掌握核心控制权：

- Agent Protocol
- Environment
- Context / Memory
- Capability
- Permission / Policy
- Workspace
- Proposal
- Apply
- Agent Handoff
- Skill / Connector Protocol
- Resource Gateway
- Agent Lifecycle

这样做的目的不是重复造轮子，而是确保 Flux 最核心的行为模型不被外部产品绑定。

---

# 7. 当前开发优先级

```text
P0
├── Flux Environment
├── User / Project / Environment Memory
├── Flux Agent Protocol
├── Agent Scanner / One-click Import
├── Skill Protocol
├── Connector Protocol
└── Skill / Connector Sync

P1
├── Execution Sandbox
├── Resource Gateway
├── Network / External Access Policy
├── Agent Context / Handoff
└── Server CLI

P2
├── Browser
├── Device Node
├── Mobile
├── Marketplace
└── Cloud / Enterprise
```

## 8. 最终判断

Flux 当前不应该继续追求“功能数量”。

下一阶段真正应该解决的是：

> **一个外部 Agent 进入 Flux 后，能否完全理解 Flux、获得明确的能力边界、在受控环境中工作、持续获得正确上下文，并最终通过 Proposal → Validation → Review → Apply 安全地改变真实项目。**

如果这条链成立，Flux 才真正成为一个独立的平台，而不是若干 Agent 功能的集合。

如果这条链尚未成立，继续增加 Marketplace、企业版、云端 Agent 等功能都属于过早扩张。

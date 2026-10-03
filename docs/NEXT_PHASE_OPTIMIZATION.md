# Flux 下一阶段优化方案

> 目标：基于当前代码架构与 50 个真实任务 Benchmark，明确 Personal MVP 下一阶段的工程优化顺序。

## 1. 当前阶段判断

Flux 已经进入“从能运行到稳定工程基础设施”的阶段。下一阶段不建议继续无边界增加产品功能，而应重点强化：

1. Agent 接入层
2. Proposal → Apply 的可靠性
3. Run / Process / Recovery 状态一致性
4. 失败归因
5. Server CLI

当前正式 Benchmark 的关键指标显示，E2E、首次成功、Proposal、Apply 与目标验收线仍有差距，因此优化重点应放在工程边界和可靠性，而不是 UI 或生态功能。

## 2. Agent 接入层重构

### 2.1 分离四个概念

建议将 Agent 系统明确拆成：

```text
Agent Registry
├── Agent Identity
├── Agent Installation
├── Agent Adapter
└── Agent Runtime
```

### 2.2 Agent Identity

负责平台身份，不负责执行：

- id
- name
- role
- permissions
- skills / tools metadata

保持 `AgentManager` 作为 Registry，而不是 Agent Loop 或执行器。

### 2.3 Agent Installation

描述本机是否安装某个 CLI Agent：

- executable
- path
- version
- source
- auth status
- detected capabilities

### 2.4 Agent Adapter

每种 Agent 只处理自身 CLI 差异：

```text
discover()
verify()
get_version()
check_auth()
start()
stop()
cancel()
parse_event()
```

建议提供 Generic CLI Adapter，并为 OpenCode、Codex、Claude Code、DSH 等提供薄适配层。

### 2.5 Agent Runtime

统一处理：

- process lifecycle
- stdin / stdout / stderr
- process group
- timeout
- heartbeat
- cancellation
- exit code
- event stream

这样 Web、Mobile、Server CLI 可以共享同一个 Runtime。

## 3. 一键扫描与接入

增加 Agent Discovery：

```text
PATH
 ↓
known executables
 ↓
--version
 ↓
health check
 ↓
auth check
 ↓
capability detection
 ↓
AgentInstallation
```

建议状态：

```text
DISCOVERED
   ↓
VERIFIED
   ↓
CONNECTED
   ↓
READY
```

CLI 入口建议：

```bash
flux agents scan
flux agents list
flux agents connect <agent>
flux agents connect --all
flux agents remove <agent>
```

## 4. Proposal → Apply 可靠性

Proposal 应成为一等对象，而不是简单 diff 文本：

```text
Proposal
├── id
├── agent_id
├── task_id
├── base_revision
├── changes[]
├── validation
├── approval
└── apply_result
```

Apply 建议严格遵循：

```text
Proposal
 ↓
Validate
 ↓
Snapshot
 ↓
Precondition Check
 ↓
Atomic Apply
 ├── A
 ├── B
 └── C
 ↓
全部成功？
 ├── YES → COMMIT
 └── NO  → ROLLBACK
```

必须保证多文件 Apply 的原子性：部分文件成功、后续失败时，最终 Workspace 必须恢复到 Apply 前状态。

## 5. Proposal 基线与冲突检测

Proposal 创建时记录 `base_revision`。Apply 前检查 Workspace / Git 状态是否仍符合基线。

例如：

```text
Proposal base = abc123
       ↓
用户或其他 Agent 修改 Workspace
       ↓
Apply
       ↓
base 不一致
       ↓
Conflict
```

不要静默覆盖外部修改。

## 6. Run / Process / Recovery

保持现有分层超时设计：

- startup = 120s
- idle = 600s
- hard = 1800s
- OpenCode 单轮 = 900s
- 最多两轮 Proposal 补救

### Idle Timeout

600s 不应简单等价于“600 秒没有 stdout”。应根据可靠活性信号判断：

- Agent event
- MCP / Tool execution
- heartbeat
- stdout / stderr
- 子进程活跃状态
- 状态变化

只有完全没有可靠活性信号时才触发 idle timeout。

### Process Cleanup

Timeout / Cancel 后必须：

1. 落终态
2. 中断任务
3. 按 PGID 清理整个进程树
4. 宽限期后强制 kill
5. 确认不存在孤儿进程
6. 不允许永久停留在 CANCELLING

## 7. 失败归因

不要只保存 `status=FAILED`。建议结果至少区分：

```text
agent
platform
external
user
validation
apply
test
```

例如：

```text
FAILED
category=external
reason=codex_rate_limit
```

与：

```text
FAILED
category=apply
reason=path_outside_workspace
```

必须是不同结果。

Benchmark 中 External / Platform Failure 不应污染 Agent 能力结果。

## 8. Circuit Breaker

保留当前连续失败止损机制：连续多个任务出现“两轮无 Proposal”时停止整批补跑，避免无限消耗 Agent 与服务器资源。

建议将 Circuit Breaker 的触发条件、计数器重置条件、最终状态写入结构化结果。

## 9. Server CLI

第一阶段不需要复杂 TUI，只需要稳定的服务器管理 CLI：

```bash
flux doctor
flux agents scan
flux agents list
flux agents connect
flux run
flux task
flux status
flux logs
```

Web / Mobile / CLI 应共享同一个 Flux Core：

```text
                Flux Core
                   │
        ┌──────────┼──────────┐
        ↓          ↓          ↓
      Web/API     CLI       Mobile
        │          │          │
        └──────────┼──────────┘
                   ↓
             Agent Runtime
```

不要为不同入口重复实现 Agent Runtime。

## 10. 下一阶段优先级

### P0

1. Agent CLI Adapter / Scanner
2. Proposal → Apply 原子事务
3. Run / Process / Recovery 状态一致性
4. Failure Category

### P1

5. Conflict Detection
6. Agent 一键接入
7. Flux CLI
8. Server Mode

### P2

9. UI 体验
10. Context / 性能优化

### 暂缓

- Marketplace
- Skill Marketplace
- Connector Marketplace
- Cloud
- Team / Enterprise
- Billing
- 多 Agent 自动编排
- 本地 Tech Lead 小模型

## 11. 推荐开发顺序

```text
Agent Discovery
      ↓
CLI Adapter
      ↓
Agent Registry
      ↓
Agent Runtime
      ↓
MCP
      ↓
Proposal
      ↓
Atomic Apply
      ↓
Test
      ↓
Git
      ↓
重新执行 50 任务 Benchmark
      ↓
修复剩余 P0/P1
      ↓
个人真实使用
```

## 12. 阶段目标

下一阶段不是追求“增加更多功能”，而是让：

> `Agent Discovery → CLI Adapter → Runtime → MCP → Proposal → Apply → Test → Git`

成为一条稳定、可恢复、可替换、可观测的工程管道。

完成后，再根据真实个人使用中的问题决定是否扩展 Marketplace、Cloud、Team 等能力。

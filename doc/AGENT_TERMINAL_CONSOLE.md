# Flux Agent Terminal Console

## 1. 目标

为 Flux 增加一个独立的 Agent Terminal 窗口，用于让工程师实时观察 AI 正在执行的命令和终端输出，并且能够在发现异常时立即接管、停止 Agent 及其当前命令。

它不是单纯的只读日志查看器，而是 **Agent 的实时观察 + 人工接管入口**。

核心原则：

> AI 可以自主执行，但工程师始终保留即时停止和接管能力。

## 2. 使用场景

桌面端 Flux 主窗口继续承担 IDE、Agent、Proposal、Diff、Task 等核心工作；用户点击“查看 Agent Terminal”后，打开独立终端窗口。

```text
Flux Desktop
│
├── 主窗口
│   ├── IDE
│   ├── Agent
│   ├── Proposal
│   ├── Diff
│   └── Task
│
└── Agent Terminal Window
    ├── AI 当前执行的命令
    ├── 实时 stdout / stderr
    ├── 执行状态
    ├── exit code
    └── Stop / Force Stop
```

独立窗口只是当前 Run 的观察和控制客户端，不重新启动第二个 Agent，也不复制一套执行逻辑。

## 3. 核心能力

### 3.1 实时显示 AI 命令

用户应该能够看到 Agent 实际执行的命令，例如：

```text
$ git status
$ python -m pytest backend/tests/test_workspace.py
$ npm run build
$ git diff --stat
```

### 3.2 实时输出

终端应该实时显示 stdout / stderr，而不是等待命令执行结束后一次性返回。

示例：

```text
$ npm test

> vitest

✓ proposal.test.ts
✓ workspace.test.ts
✓ apply.test.ts

Test Files  3 passed
Tests       45 passed
```

### 3.3 Stop

用户发现 Agent 行为异常时，可以直接点击 Stop。

Stop 必须调用 Flux Runtime 的真正取消机制，而不是只修改 UI 状态：

```text
User clicks Stop
      ↓
Flux Runtime.cancel(run_id)
      ↓
Agent Runtime
      ↓
Stop Agent
      ↓
Terminal Session Manager
      ↓
Terminate Shell / PTY / Child Process Tree
      ↓
Confirm all processes exited
      ↓
Run = cancelled
```

目标是避免出现：

```text
UI 显示 cancelled
       ↓
后台命令仍然运行
```

### 3.4 Force Stop

如果正常 Stop 后 Agent 或命令仍不退出，应提供 Force Stop。

```text
Stop
 ↓
SIGTERM
 ↓
等待清理
 ↓
仍未退出
 ↓
Force Stop
 ↓
SIGKILL / 强制终止进程树
 ↓
确认所有子进程退出
```

Force Stop 必须与现有 Flux Run / Agent 生命周期管理统一，不重新实现另一套取消机制。

### 3.5 状态和退出码

显示：

- 当前 Run
- 当前命令
- 运行中 / 已完成 / 失败 / 已取消 / 超时
- exit code
- Agent 停止原因
- Terminal Session 状态

## 4. Agent Terminal 不是只读窗口

用户应该可以在 Agent 执行期间直接输入命令进行检查，例如：

```text
[USER] $ git status
[AI]   $ npm test
[USER] $ git diff
```

这样用户可以在 AI 工作过程中主动检查项目状态。

但是用户通过 Terminal 输入命令时，不能因此绕过 Flux 已有的 Workspace、权限、安全边界和执行策略。

尤其是修改项目、访问受保护文件、越过 Workspace 边界等行为，仍必须遵循 Flux 当前的安全模型。

## 5. Agent 命令与用户命令必须明确区分

终端输出中应该明确标记命令来源：

```text
[AI]   $ pytest -q
[USER] $ git status
[AI]   $ git diff
```

避免用户误认为某条命令是自己执行的，也避免 Agent 行为与人工接管行为混在一起。

## 6. Runtime 架构

Agent Terminal 不应该自己创建 Agent。

推荐架构：

```text
                    Flux Runtime
                         │
                 Agent Terminal Session
                         │
              ┌──────────┴──────────┐
              ↓                     ↓
        Agent 执行命令             Event Stream
              │                     │
              ↓                     ↓
           PTY / Shell        Terminal Window
```

Terminal Window 是一个观察/控制客户端。

因此：

- 不产生第二个 Agent
- 不重复执行命令
- 主窗口关闭后 Run 可以继续
- Terminal Window 可以单独关闭
- 重新打开 Terminal Window 可以恢复当前 Run 的终端历史
- 手机端未来可以复用同一套 Run / Terminal Event 数据

## 7. PTY / 平台适配

桌面端需要根据宿主系统使用对应的终端能力。

```text
                 Flux Terminal API
                         │
              Terminal Session Manager
                         │
          ┌──────────────┼──────────────┐
          ↓              ↓              ↓
       Windows         Linux          macOS
       ConPTY           PTY             PTY
          │              │              │
          └──────────────┼──────────────┘
                         ↓
                  Agent Terminal
                     Window
```

上层 Flux 使用统一的 Terminal Session / Event 模型，平台差异收敛在底层适配层。

## 8. Event 模型

Terminal 至少需要能够表达以下事件：

```text
terminal.session.created
terminal.command.started
terminal.output
terminal.command.finished
terminal.command.failed
terminal.stop.requested
terminal.process.terminated
terminal.session.closed
```

事件至少应关联：

- run_id
- terminal_session_id
- command_id
- source（AI / USER）
- timestamp
- output chunk
- exit code（命令结束时）
- process state

这样主窗口、Terminal Window 和未来移动端可以消费同一套状态。

## 9. 与 Agent Context 的边界

终端 UI 可以显示完整输出，但不应该默认把完整终端历史重新塞入 Agent Context。

推荐：

```text
                    Agent
                      │
                execute(command)
                      │
                      ▼
              Flux Shell Runtime
                      │
             ┌────────┴────────┐
             ▼                 ▼
        Event Stream       Context Summary
             │                 │
             ▼                 ▼
       Terminal UI          Agent Context
```

原因：

- `npm install` 可能产生大量输出
- 编译日志可能非常长
- 测试日志可能非常长
- 完整输出会快速消耗上下文 Token

Terminal UI 保留完整观察能力；Agent 默认获取结构化结果、摘要或必要片段。

## 10. Run 生命周期一致性

Terminal 的 Stop / Force Stop 必须复用 Flux 已有 Run / Agent 生命周期机制。

最终状态必须保持一致：

```text
Terminal stopped
      ↓
Agent stopped
      ↓
Child processes stopped
      ↓
Run = cancelled
```

禁止：

```text
Terminal = stopped
Agent = running
Run = cancelled
```

或者：

```text
Run = cancelled
Child process = still running
```

## 11. 主窗口与 Terminal 窗口同步

当用户在任意一个窗口进行操作时，另一个窗口必须及时同步：

- Run 状态
- 当前命令
- Terminal 状态
- Stop / Force Stop 状态
- Agent 完成/失败
- exit code

关闭 Terminal Window 不应停止 Agent。

只有用户明确执行 Stop / Force Stop 才触发 Run 取消。

## 12. 重新打开与历史恢复

如果 Agent 仍在运行：

```text
主窗口
  ↓
打开 Agent Terminal
  ↓
连接已有 terminal_session
  ↓
加载必要历史
  ↓
继续接收实时事件
```

如果 Agent 已经结束：

```text
打开 Agent Terminal
 ↓
显示历史命令与输出
 ↓
显示最终状态与 exit code
```

不要求无限保存完整终端历史；需要设计合理的历史上限和截断策略。

## 13. Personal MVP 实现范围

为了避免继续扩大 Personal MVP，本功能第一版只实现：

1. 独立 Agent Terminal 窗口
2. 实时显示 AI 执行命令
3. 实时 stdout / stderr
4. 用户输入命令
5. AI / USER 命令来源区分
6. Stop
7. Force Stop
8. Run 关联
9. Terminal 历史恢复
10. exit code
11. 与现有 Run / Agent 生命周期统一
12. 不把完整 Terminal 输出默认重新塞进 Agent Context

暂不实现：

- 完整移动 IDE
- 多 Terminal 工作区管理
- Terminal 插件生态
- 复杂终端主题系统
- 无限终端历史
- 内置 Shell IDE
- 多用户远程共享 Terminal

## 14. 验收测试

### 基础执行

- AI 执行 `git status`，Terminal 实时显示命令和输出。
- AI 执行长时间测试命令，输出持续刷新。
- 命令结束后显示正确 exit code。
- AI 命令来源显示为 `[AI]`。

### 人工接管

- AI 执行命令过程中点击 Stop，Agent 随即进入取消流程。
- Stop 后当前命令停止。
- Stop 后子进程树最终清理。
- Agent 不会继续执行后续工具调用。
- Run 最终状态为 `cancelled`。

### 强制停止

- 构造无法正常退出的命令。
- 点击 Stop。
- 正常终止超时后进入 Force Stop。
- 确认进程树全部退出。
- Run 最终状态正确。

### 用户命令

- 用户可以输入 `git status` 等只读检查命令。
- 用户命令显示为 `[USER]`。
- 用户命令仍受 Flux Workspace / 权限边界约束。

### 窗口生命周期

- 主窗口关闭，Agent 仍可继续运行。
- Terminal Window 单独关闭不会取消 Agent。
- 重新打开 Terminal Window 可以恢复已有 Run。
- Agent 已结束时重新打开能够看到最终状态。

### 一致性

- Terminal Stop、Agent Stop、Run cancelled 三者状态一致。
- 不允许出现 UI 已停止但后台进程仍继续运行的状态。
- 服务重启后，不应产生永久 running 的 Terminal / Run。

## 15. 与 Flux 产品定位的关系

Flux 的 Solo 模式负责让 AI 高效完成工作；Agent Terminal 负责让工程师实时看到并接管 AI 的实际行为。

最终体验：

```text
AI 自主工作
     ↓
工程师实时观察
     ↓
发现异常
     ↓
立即 Stop
     ↓
Agent + 当前命令 + 子进程停止
     ↓
工程师重新接管
```

这不是为了让 Flux 看起来更像传统 Terminal，而是为了强化 Flux 的核心原则：

> **AI 可以高效执行，但代码和执行控制权始终掌握在工程师手里。**

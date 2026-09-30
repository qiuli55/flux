# Flux × DeepSeek Harness 集成方案

> 状态：设计方案（2026-09-30 回写接入口径：**官方 Python SDK**，见 §10）
>
> 目标：以 DeepSeek Harness（DSH）作为 Flux 内置 Agent Runtime 的基础，保留 DSH/Cordis 的 Agent、Plugin、Tool、Skill、Session 等能力，同时通过 Flux Bridge 注入 Flux 的 AI Engineering 能力。
>
> 本文档的架构决策（§1–§9、§11–§15、§19–§23）保持不变；§10、§16、§17、§18 已按「官方 Python SDK 接入」重写，理由见 §10.2。
>
> 2026-10-01 回写：新增 §24–§27（术语边界、能力通道、上下文与溯源设计、待确认清单）。其中 §24 的术语口径、§26.2 的「存档满保真 / 投喂按预算（动态预算、用户界面显示完整上下文）」口径、§26.4 的溯源指纹（条级 + 统一 agent 注册表）已经用户确认。

## 1. 核心决策

Flux 当前不应继续从零开发完整 Agent。建议采用 DSH/Cordis 作为内置 Agent Runtime，Flux 保留自己的 Engineering Core。

- DSH/Cordis：Agent Loop、Session、Plugin Runtime、Tool Runtime、Skill、Subagent、LLM Runtime、事件系统。
- Flux：Task、Project Brain、Context、Virtual Workspace、Proposal、Permission、Sandbox、Test、Git、Operation Timeline。
- Flux Bridge：负责两侧通信、Capability 映射和权限控制。
- DSH Plugin System 尽量原生保留。
- AI 不得绕过 Virtual Workspace 直接修改真实项目文件。

## 2. 目标架构

```
                         Flux
┌─────────────────────────────────────────────────────┐
│              Flux Engineering OS                    │
│ Project Brain  Task  Workflow  Context              │
│ Virtual Workspace  Proposal/Diff  Permission        │
│ Sandbox  Test  Git  Operation Timeline              │
└───────────────────────┬─────────────────────────────┘
                        │
                 Flux Agent Bridge
                        │
                 HTTP / IPC / RPC
                        │
┌───────────────────────▼─────────────────────────────┐
│          Flux Built-in Agent                        │
│          DeepSeek Harness + Cordis                  │
│ Agent Loop  Session  Tool  Plugin  Skill  Subagent │
│ LLM  Events  Trace / Replay                         │
└───────────────────────┬─────────────────────────────┘
                        │
                  Flux DSH Plugins
                        │
       ┌────────────────┼─────────────────┐
       │                │                 │
 Flux Workspace     Flux Brain       Flux Sandbox
 Flux Proposal      Flux Task        Flux Test
 Flux Context       Flux Git         Flux Operation
```

## 3. 为什么现在切换

当前 Flux Agent Runtime 的主要职责仍是一次 ModelRouter 调用。如果继续加入 Agent Loop、Tool Calling、Session、Plugin、Skill、Subagent、Streaming、Replay，会重新实现一个简化版 DSH。

因此现有 Agent Runtime 应逐步从：

```
AgentManager → AgentExecutor → ModelRouter.chat()
```

演进为：

```
AgentManager → FluxAgentRuntime → DSHClient → DSH Agent
```

现有 Manager、类型和生命周期接口可以保留；一次模型调用型 Executor 逐步迁移到 DSH。

## 4. DSH 能力直接复用

原则上不重新实现：

- Cordis Runtime
- Plugin lifecycle / loading / dependency / injection
- Agent / Agent Loop / AgentHandle
- Session
- LLM abstraction
- Tool Registry
- Skill Runtime
- Subagent
- Event system
- Profile / Bundle
- Trace / Replay

DSH Plugin System 是采用 DSH 的重要原因之一。

## 5. DSH Plugin 原生兼容

目标体验：

```
用户下载 DSH Plugin
        ↓
Flux Plugin Manager
        ↓
识别 DSH/Cordis Plugin
        ↓
加载到 DSH Runtime
        ↓
Plugin.apply(ctx)
        ↓
正常运行
```

Flux 不首先修改社区 DSH Plugin。优先让插件继续看到熟悉的 Cordis/DSH API。

## 6. Flux Core Plugin

建议建立核心 DSH Plugin：`flux-core`。

建议目录：

```
agent-runtime/
└── dsh/
    └── flux-plugins/
        └── flux-core/
            ├── index.ts
            ├── services/
            │   ├── project.ts
            │   ├── task.ts
            │   ├── brain.ts
            │   ├── context.ts
            │   ├── workspace.ts
            │   ├── proposal.ts
            │   ├── sandbox.ts
            │   ├── test.ts
            │   ├── git.ts
            │   └── operation.ts
            └── tools/
```

作用：DSH/Cordis → Flux Core Plugin → Flux Internal API → Python Engineering Core。

## 7. Flux Capability / Tool API

第一阶段优先暴露：

```
flux.project.*
flux.task.*
flux.context.*
flux.brain.*
flux.workspace.*
flux.proposal.*
flux.sandbox.*
flux.test.*
flux.git.*
flux.operation.*
```

优先工具：

- project.read
- task.get
- context.get
- brain.search
- workspace.read
- workspace.search
- workspace.propose
- sandbox.exec
- test.run
- git.status
- git.diff
- operation.record

## 8. Virtual Workspace 是强约束

DSH Agent 可以拥有工具调用能力，但不能绕过 Flux Virtual Workspace。

```
DSH Agent
    ↓
flux.workspace.read
    ↓
分析代码
    ↓
flux.workspace.propose
    ↓
Proposal
    ↓
Diff
    ↓
Human Review
    ↓
ApplyEngine
    ↓
真实文件
```

`workspace.apply` 不能成为普通 Agent Tool 的直接写文件能力；真实落盘仍由 ApplyEngine 控制，并进行 hash check。

**Phase 1 的现实约束（2026-09-30 补充）**：`sdk` profile 自带完整的文件系统与 shell 工具组，**这条"不能绕过"在 Phase 1 还不成立**——真正的拦截要靠 §9 的 Permission Engine 与 §18 Phase 4 的 flux-workspace 插件。因此在 Phase 1/2，Flux 采取的是**隔离而非拦截**：`cwd` 指向独立空目录 `/opt/flux/dsh-ws`，`dsh_home` 指向 `/opt/flux/dsh-home`，两者都不指向用户真实项目，确保这一阶段无论 Agent 怎么调用工具都碰不到真实文件。拦截能力随 Phase 4 一起交付。

## 9. Permission Engine

所有 DSH Plugin 和 Agent 的 Flux 能力必须经过 Flux Permission Engine。

| Capability | 默认策略 |
| --- | --- |
| project.read | Allow |
| context.read | Allow |
| brain.search | Allow |
| workspace.read | Allow |
| workspace.search | Allow |
| proposal.create | Allow |
| sandbox.exec | Allow（受 Sandbox 策略限制） |
| test.run | Allow |
| workspace.apply | Require Approval |
| git.commit | Require Approval |
| git.push | Deny / Require Approval |
| secret.read | Deny |

安装 DSH Plugin 不等于获得 Flux 全部权限。

## 10. Python 与 DSH 通信

DSH 是 Node/TypeScript Runtime，而 Flux Engineering Core 是 Python。**第一阶段用官方 Python SDK 作为唯一接入面**：不自建 Node 服务、不自建传输层、不 vendor 源码。

```
Python Flux Core（Flux AgentRuntime）
      │  deepseek-harness-sdk（PyPI 官方包）
      ▼
HarnessClient（SDK 内置，同步 JSON-RPC 客户端）
      │  stdio NDJSON JSON-RPC（dsh --profile sdk）
      ▼
deepseek-harness-sdk-runtime-linux-x64（自包含单文件可执行）
      │
      ▼
DSH Agent
```

Flux 侧只需要一个薄客户端。下面这段是可直接运行的形态（路径为 A 机实际取值）：

```python
from deepseek_harness import DeepSeekHarness

with DeepSeekHarness(
    dsh_home="/opt/flux/dsh-home",   # 必须显式指定；SDK 绝不隐式使用 ~/.dsh
    cwd="/opt/flux/dsh-ws",          # Agent 工作区，由 Flux 决定
    provider="deepseek-official",
    model="deepseek-v4-flash",
    max_tokens=49152,
) as harness:
    result = harness.run("把 auth/login.py 的登录校验拆成独立函数", session_id="flux-run-0001")
    print(result.session_id, result.finish_reason)
    print(result.final_response)
```

关键契约（均取自 SDK 公开 API 与上游 README，非推测）：

- `DeepSeekHarness` **惰性启动**，实例可复用到 `close()` 或退出上下文；初始化握手默认 30 秒超时（`initialize_timeout_seconds`），普通轮次默认不设超时。
- `Session.run()` 有明确活动区间：从 prompt 的 inbox receipt 起，到整棵 Agent 树 idle 止，然后返回 `RunResult(session_id, final_response, finish_reason, events, notifications)`。
- `finish_reason` 取最后一个根会话 `turn/end` 的 `data.reason.kind`（如 `completed` / `max-tokens` / `error`）。
- 流式：`Session.run(..., on_notification=...)` 按 wire order 收到根会话与已发现子 Agent 的通知；`RunResult.events` 只含根会话事件，子 Agent 输出不会顶替根响应。
- 中断：runtime 协议含 `session/cancel`（通知，参数 `{"sessionId": ...}`），SDK 未封装成高层方法，但 `harness.client.notify("session/cancel", {"sessionId": sid})` 是公开面；取消后运行循环会在会话转 idle 时正常收尾。
- 环境变量：运行子进程默认继承调用方环境，`DEEPSEEK_API_KEY` / `DEEPSEEK_BASE_URL` 可直接生效，也可用 `api_key` / `base_url` 显式覆盖（Flux 用后者，不写文件）。
- 运行时自带 ripgrep sidecar（`deepseek-harness-sdk-runtime-linux-x64-rg`），**目标机器不需要 Node**。

### 10.1 已核实的上游事实（2026-09-30 快照）

| 项 | 值 | 来源 |
| --- | --- | --- |
| SDK 包 | `deepseek-harness-sdk` = `0.1.5rc1` | 安装元数据 |
| 依赖约束 | `deepseek-harness-runtime-bin==0.1.5rc1`、`pydantic>=2.12,<3`、Python `>=3.10` | SDK `METADATA` |
| 许可 | MIT | SDK `METADATA` |
| 运行时载体 | 单文件可执行 `deepseek-harness-sdk-runtime-linux-x64`，274,599,104 字节（约 261.9 MiB），另有 `-rg` sidecar | `deepseek_harness_runtime` 包文档 |
| runtime-bin 元数据版本 | `0.0.0-dev`（该 wheel 的 `deepseek-harness-runtime.json` 未填真实版本） | 运行时包元数据 |
| 上游仓库 `master` | 版本 `0.2.0-rc.1`，`engines.node = ^22.19.0 \|\| >=24.0.0`，`packageManager: pnpm@11.7.0` | 上游根 `package.json` |
| 本机前提 | glibc 2.35、Python 3.10.12、Flux venv 内 pydantic 2.13.5（满足 `>=2.12,<3`） | 实测 |
| 冒烟结果 | 隔离环境 `/opt/dsh-smoke` 实测通过：输出 `FINAL_RESPONSE: DSH_OK`，退出码 0 | 实测 |

> 由于 runtime-bin 自身不报版本，**锁版本一律以 SDK 版本为准**，并在 `DSH_UPSTREAM.md` 同时记录 wheel 文件名与 sha256。

### 10.2 口径变更与理由（2026-09-30，已批准）

**旧口径**：Python ↔ Node 走 localhost HTTP（`POST /api/v1/agent-runtime/...`），并 vendor DSH 源码、自建 Node Agent Runtime、装 Node 22/24 + pnpm 构建。
**新口径**：Phase 1 直接用官方 Python SDK 的 stdio NDJSON JSON-RPC；不 vendor、不装 Node、不构建。

理由：

1. **上游已提供官方 SDK**：`dsh --profile sdk` 由 wheel 内的自包含可执行承载，目标机器不需要 Node / pnpm / 构建。
2. **整体消除自建桥的工程量与故障面**：跨进程 HTTP 服务、端口、鉴权、生命周期、版本漂移都不复存在。
3. **已实测跑通**，不是纸面方案。
4. **能力语义不变**：§7 的 Flux Capability / Tool API 与 §9 的 Permission Engine 不受影响，它们描述的是「Flux 暴露什么」，与「用哪种传输」解耦。

保留项：若将来出现 SDK 覆盖不到的控制面（进程外多路复用，或 Flux 需要作为协议 client 接管文件与权限请求），再增加补通道，不改 Capability 层。

### 10.3 实现注意（写代码前必须知道的四条）

1. **SDK 是同步的，Flux 是异步的**。`Session.run()` 阻塞至会话 idle；在 FastAPI 里必须用 `asyncio.to_thread`（或专用线程池）卸载，不能直接 await。
2. **一个 harness 实例串行使用**。runtime 子进程由实例独占，多 Agent 并发需要按 Agent 各持一个实例（或显式排队），不要共享单实例并发 `run`。
3. **`dsh_home` 必须显式给，且要放仓库外**（如 `/opt/flux/dsh-home`），否则会污染 git 工作区；每个 home 内含 profiles / plugins / 会话落盘。
4. **不要在 Flux 主进程用 `on_notification` 直接做重活**：回调在 SDK 读线程对应的同步上下文里执行，只做「转成 Flux 事件投递」，落库与广播交给 EventBus。

## 11. Model Gateway

Flux 当前的 Model Gateway 不删除，但职责逐步调整为模型选择与策略层：

```
Flux Model Router
    ↓
Provider / Model / Budget / Fallback
    ↓
DSH LLM Adapter
    ↓
DSH Agent
```

第一阶段不要同时重构 Model Gateway。先让 DSH 使用成熟的 LLM Runtime，Flux 记录 Agent Run / Model / Usage。稳定后再实现 DSH → Flux Model Gateway。

**Phase 1 取值（2026-09-30）**：DSH 侧的 provider 用 `deepseek-official`，model 用 `deepseek-v4-flash`；凭据用 Flux 现有的 `/opt/ops/.env` 读进来后，以 `DeepSeekHarness(api_key=...)` 注入子进程，不落任何文件。Flux 现有的 `agent_runtime` 仍保留 `ModelRouter`（当前默认走 MiniMax 的 Anthropic 兼容端点），两条路径在 Phase 1 并存：**内置工程 Agent 走 ModelRouter，DSH Agent 走 DSH LLM Runtime**。

## 12. Session 与 Context 分层

DSH Session 与 Flux Context 不合并。

DSH Session 负责：
- Agent messages
- Model output
- Tool calls / results
- Agent events
- Session state

Flux Context 负责：
- Task
- Requirement
- Project Brain
- Relevant files
- Decisions
- Agent Handoff
- Project conventions
- Previous engineering results

最终：DSH Session + Flux Context → Flux Agent Context。

不要把完整 Project Brain 一次性塞进 system prompt，应通过 `flux.brain.search`、`flux.context.get` 等能力按需读取。

## 13. Project Brain

Project Brain 作为 Flux Service / Tool 暴露，例如：

```
ctx.projectBrain
flux.brain.search
```

Agent 可以先搜索项目知识，再决定读取哪些实际文件。

## 14. Operation Timeline

DSH Trace 和 Flux Operation Timeline 分层：

- DSH Trace：Agent 执行轨迹。
- Flux Operation Timeline：工程操作轨迹。

典型流程：

```
Task Started
 ↓
Agent Started
 ↓
Brain Search
 ↓
File Read
 ↓
Proposal Created
 ↓
Human Review
 ↓
Apply
 ↓
Test
 ↓
Repair
 ↓
Git Commit
```

## 15. AgentRun 统一结果

Flux 最终应把 DSH Run 聚合为统一 AgentRun：

```
AgentRun
├── run_id
├── task_id
├── agent
├── provider
├── model
├── status
├── started_at / finished_at
├── duration
├── token_usage
├── cost
├── tool_calls
├── files_changed
├── proposals
├── tests
├── operations
└── error
```

这将直接服务未来 Agent Team Room、Cost Center 和 Operation Timeline。

## 16. DSH 上游策略

DSH 当前处于快速迭代阶段，因此 Flux 不应让 Python/业务层直接依赖 DSH 内部实现。

**2026-09-30 回写**：不再 vendor 源码，也不再自建 Node 侧产物。改为**只用官方 Python SDK + 锁版本**。

```
backend/
└── vendor-notes/
    └── DSH_UPSTREAM.md      # 记录 SDK 版本、wheel 文件名与 sha256、冒烟结论
```

约束：

1. Flux 业务层只 import `deepseek_harness` 的公开符号（`DeepSeekHarness`、`RunResult`、`Session`、`Notification`、`SdkProtocolError`），不 import 私有模块，不硬编码 runtime 内部协议细节。
2. `deepseek-harness-sdk==0.1.5rc1` 与 `deepseek-harness-runtime-bin==0.1.5rc1` 都要锁死（含 `rc` 标记），写进 `backend/requirements.txt`；升级单独成一次提交，并附冒烟结果。
3. 需要 Flux 专属逻辑时，优先写成 DSH Plugin（见 §6），而不是改 runtime。
4. 每次升级后跑一次 Flux Compatibility Test（验收口径：Flux → DSH → DeepSeek → Agent Response）。
5. **产物不进仓库**：wheel 80.7MB、解包出的可执行 261.9MiB，仓库只记版本号与安装命令。

**为什么不再 vendor**：vendor 源码路线要求系统 Node `^22.19.0 || >=24.0.0` 与 `pnpm@11.7.0`，还要自行构建（上游 `master` 为 `0.2.0-rc.1`）[2026-09-30 快照]；SDK 路线把这些前提全部去掉，装包即用，且版本可锁、可回滚。

**何时才需要 Node 侧开发**：只有进入 §18 Phase 3 之后、真正要写 DSH Plugin（TS/Cordis）时才需要 Node 工具链。届时再单独评估，不影响 Phase 1/2。

## 17. 推荐代码结构

### Flux Python

```
backend/flux/
├── core/
│   ├── agent_runtime/
│   │   ├── manager.py
│   │   ├── dsh_client.py
│   │   ├── dsh_events.py
│   │   └── types.py
│   ├── task_engine/
│   ├── project_brain/
│   ├── virtual_workspace/
│   ├── permission_engine/
│   ├── model_gateway/
│   └── ...
└── api/
```

### DSH Runtime

```
backend/flux/core/agent_runtime/
├── manager.py        # 既有：Agent 注册表与生命周期（对外接口不变）
├── dsh_client.py     # 新增：包装 DeepSeekHarness，同步 run 卸载到线程
├── dsh_events.py     # 新增：DSH Notification → Flux EventBus 事件映射
└── types.py

backend/vendor-notes/
└── DSH_UPSTREAM.md   # 上游版本与冒烟记录（见 §16）

/opt/flux/            # 运行期产物，不进仓库
├── dsh-home/         # DSH_HOME：profiles / plugins / 会话落盘
└── dsh-ws/           # DSH Agent 的工作区（Phase 1 用独立空目录，不指向真实项目）
```

配置项（沿用 Flux 既有的 `FLUX_` 前缀与 pydantic-settings 风格）：

| 变量 | 含义 | 默认 |
| --- | --- | --- |
| `FLUX_DSH_ENABLED` | 是否启用 DSH Agent Runtime | `false` |
| `FLUX_DSH_HOME` | DSH_HOME 绝对路径 | `/opt/flux/dsh-home` |
| `FLUX_DSH_WORKSPACE` | DSH Agent 的 `cwd` | `/opt/flux/dsh-ws` |
| `FLUX_DSH_PROVIDER` | DSH provider 路由 | `deepseek-official` |
| `FLUX_DSH_MODEL` | DSH 模型 id | `deepseek-v4-flash` |
| `FLUX_DSH_MAX_TOKENS` | 单次输出上限 | `49152` |
| `FLUX_DSH_INIT_TIMEOUT_SECONDS` | 初始化握手超时 | `30` |
| `FLUX_DSH_RUN_TIMEOUT_SECONDS` | 单轮超时（0 表示不设限） | `0` |

DSH Plugin 目录（§18 Phase 3 起才创建，届时才需要 Node 工具链）：

```
agent-runtime/
└── dsh/
    └── flux-plugins/
        ├── flux-core/
        ├── flux-context/
        ├── flux-brain/
        ├── flux-workspace/
        ├── flux-proposal/
        ├── flux-sandbox/
        ├── flux-test/
        ├── flux-git/
        └── flux-operation/
```

**Phase 1 / Phase 2 不建 `agent-runtime/` 目录**，因为这两个阶段的 Node 侧代码量为零。

## 18. 实施顺序

### Phase 1：DSH Runtime

1. **引入 SDK**：在 Flux venv 里安装 `deepseek-harness-sdk==0.1.5rc1`（连带 `deepseek-harness-runtime-bin==0.1.5rc1`），并把两者锁进 `backend/requirements.txt`。**不需要 Node / pnpm / 构建**。
2. **Agent Runtime 宿主**：不再"建立 Node Agent Runtime"。本步改为定配置——`FLUX_DSH_HOME`（`/opt/flux/dsh-home`）与 `FLUX_DSH_WORKSPACE`（`/opt/flux/dsh-ws`），纳入 pydantic-settings，并在启动时确保目录存在。
3. **启动 runtime**：`FluxDshClient` 包装 `DeepSeekHarness(dsh_home=..., cwd=..., provider="deepseek-official", model="deepseek-v4-flash", max_tokens=...)`；实例惰性启动、按 Agent 持有、随应用关闭时 `close()`。
4. **Flux 创建 DSH Agent**：把 Flux `AgentSpec`（name / role / model 等）映射为 SDK 参数，`session_id` 用 Flux 的 `run_id`，做到 DSH Session ↔ Flux Agent Run 一一对应。
5. **Flux 发送任务**：`Session.run(instruction)`，通过 `asyncio.to_thread` 卸载（SDK 同步，见 §10.3）。
6. **接收 streaming events**：`on_notification` 回调 → `dsh_events.py` 映射 → Flux EventBus 广播（复用既有 `Events` 通道，前端沿用现有事件消费方式）。
7. **支持 interrupt**：`harness.client.notify("session/cancel", {"sessionId": sid})`；取消后 `Session.run()` 会在会话转 idle 时正常返回，Flux 侧状态置为 `STOPPED`。
8. **完成 / 失败状态**：`RunResult.finish_reason` 与异常（`SdkProtocolError` / `TimeoutError` / `TransportClosedError`）映射到既有 `AgentState`（`COMPLETED` / `FAILED`），错误文本进 `AgentHandle.last_error`，并沿用既有 `assert_transition` 状态机。

**验收**：Flux → DSH → DeepSeek → Agent Response。具体口径：进程内创建一个 DSH Agent → 发一条真实指令 → 模型真实回复写入 Agent Run → 前端可见流式事件 → 中途 interrupt 能停下且状态正确。

### Phase 2：Flux Bridge
- Flux Agent Client
- DSH Event Bridge
- Agent Run 状态同步
- Session ID / Task ID 映射
- 错误映射

### Phase 3：Flux Core Plugin
首先接入 Project、Task、Context、Brain。

### Phase 4：Workspace / Proposal
接入 workspace.read、workspace.search、workspace.propose，并禁止 Agent 直接写真实工作区。

验收：Requirement → DSH Agent → Read → Analyze → Proposal → Diff。

### Phase 5：Apply / Operation
接入 Human Review、ApplyEngine、Hash Check、Operation Timeline、Restore Point。

### Phase 6：Test / Repair
形成 Apply → Test → Failure → DSH Agent → New Proposal → Apply → Test 的修复循环。

### Phase 7：DSH Plugin Compatibility
接入 DSH Plugin Manager、Plugin discovery、Plugin installation、Plugin dependency、Profile / Bundle、Plugin permissions。

最终目标：社区 DSH Plugin 无需修改源码即可在 Flux 中运行。

## 19. 后续 Compatibility Engine

原生 DSH Plugin 兼容稳定后，再做更广泛的 Skill / Plugin / MCP 自动适配：

```
External Skill / Plugin / MCP
        ↓
Compatibility Analyzer
        ↓
Capability Mapping
        ↓
Permission Mapping
        ↓
Adapter Generation
        ↓
Sandbox Test
        ↓
Compatibility Report
        ↓
Install
```

示例：

```
filesystem.read  → flux.workspace.read
filesystem.write → flux.workspace.propose
shell.exec       → flux.sandbox.exec
```

自动适配是第二层能力；原生 DSH Plugin 兼容优先。

## 20. 第一阶段明确不做

- 自研 Agent Loop
- 自研 Plugin Runtime
- 自研 Session Runtime
- 自研 Subagent Runtime
- 自研 Tool Registry
- 自动适配所有第三方插件
- Marketplace
- Cloud Agent
- 多 Agent 分布式调度
- 复杂 Model Routing
- 移动端 Agent 控制

先完成一个真实工程任务闭环。

## 21. 第一条完整 Demo 链路

```
用户：给项目增加 GitHub OAuth 登录
 ↓
Flux Task
 ↓
DSH Agent
 ↓
Project Brain
 ↓
读取相关代码
 ↓
Developer Agent Loop
 ↓
Flux Workspace Proposal
 ↓
Diff
 ↓
用户 Review
 ↓
Accept
 ↓
ApplyEngine
 ↓
Operation Timeline
 ↓
Tester
 ↓
失败 → DSH Agent Repair
 ↓
测试通过
 ↓
Git Commit
```

## 22. 核心原则

1. **不重新造 Agent**：DSH 提供 Agent Runtime。
2. **保留 Plugin System**：DSH/Cordis 是采用 DSH 的核心价值之一。
3. **Flux 不等于 DSH**：DSH 管 Agent 执行，Flux 管工程系统。
4. **Agent 不直接改真实文件**：必须经过 Virtual Workspace → Proposal → Review → Apply。
5. **Flux 能力通过 Capability/Plugin 暴露**：不直接暴露内部数据库。
6. **权限统一由 Flux 控制**：插件安装不等于获得全部权限。
7. **保持上游可升级**：尽量用 Plugin 扩展，减少 fork patch。
8. **先原生兼容，再 AI 自动适配**。
9. **接上游只走官方 SDK**：不 vendor 源码、不自建桥；锁死版本，每次升级附冒烟结果（§16）。

## 23. 最终定位

Flux 不是重新发明一个 Coding Agent，而是：

> **AI Engineering OS + Built-in Agent Runtime**

其中 DeepSeek Harness/Cordis 提供 Agent 执行基础设施；Flux 的核心差异化仍然是 Project Brain、Task、Context、Virtual Workspace、Proposal、Permission、Sandbox、Test、Operation Timeline、Git 和 Workflow。

最终关系：

```
DeepSeek Harness
        +
Flux Engineering OS
        +
DSH Plugin Ecosystem
        ↓
Flux Built-in Agent
```

目标不是做一个更好的 DeepSeek Harness，而是利用成熟 Agent Runtime，把开发精力集中在 Flux 真正差异化的 AI Engineering OS 能力上。

## 24. 术语与边界（2026-10-01 定稿，已确认）

**Flux 本身不是 agent**，只是一个内置了 agent 的平台。三层边界：

| 层 | 是什么 | 不是什么 |
| --- | --- | --- |
| Flux | 平台层：工程核心（Task/Workflow、Project Brain、Virtual Workspace、Permission、Sandbox、Test、Git、Operation Timeline）+ 对外能力面（MCP Server）+ UI 面板 | 不是 agent；不做 Agent Loop；不编排 agent 对话 |
| Flux 内置 agent | 一份以 DeepSeek Harness 为基底的 runtime，随 Flux 交付；桌面端（solo / ide 两页）是它的客户端 | 不等于 Flux；在能力消费上与外部 agent 对等 |
| 外部 agent | codex / claude-code / opencode 等，同一 MCP 面的其他消费者 | — |

**任务下发与能力供给分离**：Flux 下发任务，agent 自带 loop；agent 干活用的工具不是它自带的，而是通过连 Flux MCP「借」来的（skill / connector / context / brain）。session 归 agent 侧自管，Flux 只记录 Agent Run 与 Operation Timeline。

## 25. 能力通道：Flux MCP Server（2026-10-01）

- **Flux 对外只开一个 MCP 面**，同时服务内置 agent、桌面端与外部 agent。Transport 取 **Streamable HTTP**（一个端点服务所有消费者），stdio 作为单机备选。
- 已核实（[2026-10-01 实测]）：DSH 捆绑闭包内含 `@deepseek-ai/dsh-mcp-client`，exe 内嵌配置项 `mcpServers`，支持 `stdio` / `sse` / `streamableHttp`；`codex-cli 0.157.1` 支持 `codex mcp add <name> --url <URL>`（streamable HTTP，`--bearer-token-env-var` 带鉴权）与 stdio；`codex exec -c key=value` 可逐次注入 `mcp_servers.*`，不必污染用户全局配置。
- **面上一律不暴露**：`workspace.apply`、`git.push`、`secret.read`；真实落盘仍由 ApplyEngine + 人审控制（§8、§22）。
- **工具分级**：读类默认 Allow；动作类按 §9 策略（敏感项 Require Approval），审批请求回流桌面端 UI。
- **Phase 1 工具清单（6 个）**：`context.get`、`brain.search`、`skill.get`、`handoff.put`、`proposal.create`、`operation.record`。

## 26. 上下文与溯源设计（2026-10-01）

### 26.1 四层结构

| 层 | 内容 | 持有方 | 更新方式 |
| --- | --- | --- | --- |
| L0 项目事实 | Project Brain：架构、约定、历史决策、踩坑 | Flux（唯一真源） | 候选知识经人审入库 |
| L1 任务上下文 | Task / Requirement / 目标 / 验收标准 / 约束 | Flux | 跟随 Task |
| L2 运行上下文 | 相关文件、diff、提案、测试结果、操作轨迹 | Flux | 跟随每次 Agent Run |
| L3 对话历史 | 消息、工具调用与结果、session state | agent 自己 | agent 私有；Flux 只留 Run 摘要与轨迹引用 |

共享的只有 L0–L2；L3 不进共享层，**切换 agent 不搬迁对话历史**。

### 26.2 上下文打包：存档满保真，投喂按预算（2026-10-01 已确认）

职责分两层，互不干扰：

- **存档层（满保真，不丢）**：Flux 保存完整上下文——所有 agent 的每条产出、每次工具调用、每轮对话原文全部留档，可搜索、可回溯、可重放，UI 可查看（ide 页完整时间线）。压缩**只发生在投喂环节**，不影响存档。
- **投喂层（按预算）**：下发前按 token 预算打包，三档自动降级：
  1. **全量**（默认档）：预算内原样投喂；
  2. **压缩**：超预算时结构化提取优先、自由文本摘要其次。**永不压缩**：当前任务与验收标准、约束、未决问题、最近若干轮、涉及文件最新版本、未验证断言清单；**可压缩**：较早对话、已完成步骤的经过、已解决的讨论、长工具输出原文（保留 hash + 摘要 + `ref`）；
  3. **引用**：被压掉的内容留 `ref`，agent 可用 `context.fetch(ref)` 取回原文——这是「完整保留」对 agent 的可达性。

**预算口径：动态计算（2026-10-01 已确认）**

```
可投喂预算 = 目标模型上下文窗口 × 安全系数 − 预留输出 − 系统/工具定义占用 − 必留项体积
```

- 不设固定常数：目标模型的上下文窗口与最大输出取自 Flux 侧模型窗口表（未知时用保守默认值）；
- 随会话进展动态调整：早期少给历史，跑长后加大压缩力度，接近完成时优先保留最新状态、砍早期过程；
- 每次打包记录「预算 / 已用 / 压缩决策」，供回溯与 UI 展示。

**用户可见性（2026-10-01 已确认）**

- 用户界面显示**完整上下文**（存档层满保真视图），不拿压缩提示替代历史；
- 另提供「**投喂视图**」对照：查看这次实际发给 agent 的打包结果，逐条标注形态（原文 / 摘要 / 仅引用），可手工增删后再下发；
- 完整上下文一律**懒加载**：虚拟滚动 + 分页拉取，`ref` 原文按需展开；存档可能上万条，不整包渲染、不一次性拉全量。

规则细节：

- 压缩件仍带**条级指纹**（§26.4），并显式标注【摘要 · 原文见 ref:xxxx】；`evidence` 只摘描述不摘数值，`assertion` 保留归属与「未验证」标记。
- 触发时机：① 下发前按预算计算；② Run 结束时把 L2 沉淀为 L1 摘要；③ 切换 agent 时生成交接信封（压缩的第一现场）。
- 压缩执行者（2026-10-01 更新建议，待确认）——分三层：
  - **T1 规则 / 结构化**（Flux 侧，零模型）：长工具输出截断 + hash、已完成步骤叙述、重复内容——主力，确定性、可复现、零成本；
  - **T2 常驻流式压缩器**（Flux 侧，内置本地小模型，增量处理）：不等溢出再压，而是**持续**对新增内容做压缩，溢出时直接取现成产物：
    - 增量按段处理（每段很小，规避小模型有效上下文短的问题），再滚动汇总（summary of summaries）；
    - 输出分两类：**机器可验证字段（数值 / 命令 / 文件名 / hash / 错误码）由 T1 规则抽取，不由小模型生成**；小模型只写叙述性文本摘要；
    - 产物是**派生层**，随时可由「原文 + 压缩器版本」重建；摘要条目带 `ref` 回原文；
    - 本地运行的额外收益：上下文含敏感内容时不必出网；
    - 节流：按段 / 按轮批量触发，不逐 token 跑；
  - **T3 被调 agent 二次裁剪**（可选）：仅在 T1+T2 之后仍超预算、且内容能装进其窗口时启用；产物经 `context.compact` 回写存档层并带指纹。**物理限制：真正超窗时该路径不可用**——它根本读不到全量，必须先由 Flux 压到窗口内。
  - **不依赖 agent 自摘要作主路径**：跨 agent 一致性无法保证，其他 agent 默认消费 Flux 的 T1/T2 结果；
  - 待核实：DSH 原生自带 compaction 类插件（内置 agent 本身会做上下文压缩），需看其触发条件与产物形态，决定接管还是复用。
- 对没有独立 system 通道的 agent（如 `codex exec`），打包结果即其 prompt 前缀；DSH 侧可注入 session 首条消息或 `agent-instructions`（64KB 上限）。

### 26.3 Handoff（交接信封）

结构化字段：已完成 / 未完成 / 关键决策 / 未决问题 / 涉及文件+hash / 下一步建议。首选由 agent 通过 `handoff.put` 显式提交，Flux 从 Run 事件自动摘要兜底；切换 agent 时 UI 显示「交接卡」，可编辑后再交给下一个 agent。

### 26.4 溯源指纹（条级，已确认）

**条级**：每次工具调用、每条结论各带指纹（不是只标到整段回答）。
**服务端盖章**：Flux 在 MCP 写入侧自动注入 `provenance`，不接受客户端传入 `agent` 字段——agent 不能冒充他人。
**统一注册表**：agent id 由 Flux agent 档案注册表统一定义（`flux-builtin` / `codex` / `claude-code` / `opencode` …）。

| 字段 | 含义 |
| --- | --- |
| `agent` | 来源 agent（注册表 id） |
| `run_id` / `session_id` | 回溯到哪一次运行 |
| `runtime` + `model` | 用什么跑的（如 `codex-cli 0.157.1 → MiniMax`） |
| `kind` | `assertion`（断言）/ `evidence`（工具产出）/ `fact`（Flux 验证过） |
| `tool` + 参数摘要 + 结果 hash | 有据可查 |
| `ts` | 时间戳 |

渲染形态（进入 Envelope 时）：

```
[codex · r7f2 · 断言] 登录校验应抽成独立函数，理由是……
[codex · r7f2 · 证据 · flux.workspace.read(auth/login.py@sha256:9c1f)] （文件正文）
[flux  · 事实 · tester.run(pytest)@sha256:44ab] 3 failed, 12 passed
```

配套规则写进 Envelope：**历史条目带来源标注；`断言`不等于事实，采信前自行验证。**

### 26.5 防污染

- agent **不能直接写 Brain**，只能提候选知识（`knowledge.propose`）；是否允许低风险类目自动入库待确认（建议仍走人审）。
- 多 agent 并行时 L0/L1 只读共享；改动只能以 Workspace 提案回流（配 §8 的 hash check）。
- 每次 Run 记录其引用的 **context 快照 id**，事后可回溯「当时它看到的上下文」。

### 26.6 跨 agent 沙箱口径（待确认）

- 内置 agent：cwd 指向隔离工作区 + 审批通道（§8 Phase 1 现状）。
- 外部 agent（codex 等）：由 Flux 指定隔离工作目录（`codex exec -C <dir>` + 沙箱策略），产出经提案回流，不允许直写真实项目。

## 27. 待确认清单（2026-10-01）

已于 2026-10-01 确认：**存档满保真 + 超预算压缩提取**、**投喂预算动态计算**、**用户界面显示完整上下文（另配投喂视图对照、懒加载）**（均见 §26.2）。

| # | 事项 | 建议默认 |
| --- | --- | --- |
| 1 | 压缩分层方案 | T1 规则（先行）→ T2 常驻流式压缩器（本地小模型，增量 + 滚动汇总）→ T3 被调 agent 二次裁剪（可选） |
| 2 | Handoff 产生方式 | agent 显式提交 + Flux 自动摘要兜底 |
| 3 | 候选知识入库 | 一律人审（不允许自动入 L0） |
| 4 | 外部 agent 沙箱口径 | 隔离目录 + 提案回流（§26.6） |
| 5 | solo / ide 两页关系 | 同一会话两种视图，切页保留 agent 与会话 |
| 6 | 一键切 agent 语义 | 切档案（角色/模型/权限组/可见范围）+ 交接信封，不继承全部对话历史 |
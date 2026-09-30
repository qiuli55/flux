# Flux 目标架构设计（目标态）

> 用途：定义 Flux 应该长成什么样——能力面、内置 agent 接入路径、上下文流转、退役清单。Phase 2 起按本文实现。
>
> 依据：[FLUX_ARCHITECTURE_ALIGNMENT.md](FLUX_ARCHITECTURE_ALIGNMENT.md)（2026-10-01）、[ARCHITECTURE_ALIGNMENT_ANALYSIS.md](ARCHITECTURE_ALIGNMENT_ANALYSIS.md)（Phase 1 分析）、[DSH_FLUX_INTEGRATION_PLAN.md](DSH_FLUX_INTEGRATION_PLAN.md) §24–§27、[CONTEXT_DESIGN.md](CONTEXT_DESIGN.md)。
>
> 状态：**待确认**。确认后进入 Phase 2（先实现 MCP 能力面，再打通内置 agent）。

## 0. 一句话

Flux 本身**不具备 agent 能力**——没有 loop、不存对话、不替 agent 调模型；它的角色是「集合 agent 的平台」：**统一上下文、统一工具、统一 Skill**，各只有一份真源，所有 agent 共享。Flux 内置一个 agent，**以 DeepSeek 为基底**（DeepSeek Harness runtime + Cordis），随 Flux 交付；它与 Codex / Claude Code / OpenCode 一样，是 Flux MCP 能力的消费者，**能力上完全对等**。

## 1. 硬约束（7 条）

1. **Flux 零 agent 能力**：不做 agent loop、不编排 agent 内部对话、不替 agent 调模型；Workflow 只做工程状态机。
2. **平台统一三件事**：上下文（L0–L2 + 打包投喂）、工具（MCP 工具面）、Skill（skill.get），每样只有一份真源。
3. **内置一个 agent**：以 DeepSeek 为基底（DSH + Cordis），档案 id `flux-builtin`，随 Flux 交付；它不是 Flux 本体，只是 Flux 的默认 agent。
4. **外部 agent 对等**：Codex / Claude Code / OpenCode 走**同一个** Flux MCP 面、同一套权限体系；不为任何 agent 开专用通道。
5. **唯一能力出口**：Agent 的一切 Flux 能力经 MCP 获得；真实文件唯一写盘入口是 ApplyEngine（人审后）。
6. **上下文四层**：L0–L2 归 Flux 统一持有（满保真 + 条级指纹）；L3（对话/session/reasoning）归 agent 私有，Flux 不存原文。
7. **跨 agent 只传 Handoff**：完成/未完成/关键决策/未决问题/文件+hash/下一步；不搬迁对话历史。

### 1.1 Flux 明确不做的事（反面清单）

| 不做 | 现状里对应的错误形状 |
| --- | --- |
| 不做 agent loop | `AgentExecutor.run`（executor.py:58-75）、`DeveloperAgent` / `TesterAgent` 的 Agent 部分 |
| 不把 agent 当模型供应商 | `model_gateway/providers/codex_cli.py`（撤下）；今后也不允许出现 `providers/dsh.py` |
| 不存 agent 对话 | `AgentContext` 的 messages；CONTEXT_DESIGN 曾把 L3 对话纳入存档（收窄，见 §6.1） |
| 不替 agent 决定下一步 | Workflow 只推进 Task 状态、阶段、等待、重试、审批（orchestrator.py 现状正确） |
| 不造假事件 | 桌面端按钮自造「Agent 已完成」——事件必须来自 EventBus |

## 2. 目标态全景

```text
                          ┌──────────────────────────────────────────────┐
                          │             Flux 平台（零 agent 能力）           │
                          │                                              │
                          │  工程核心（唯一真源）                            │
                          │  ├─ Task / Workflow（工程状态机）                │
                          │  ├─ Context 存档：L0 Brain / L1 Task / L2 Run   │
                          │  ├─ Virtual Workspace：Proposal→Diff→人审→Apply │
                          │  ├─ Project Scanner / Project Files（只读）      │
                          │  ├─ Permission Engine（fail-closed）            │
                          │  ├─ Test Runner / Git / Event Bus / Connectors  │
                          │  └─ Operation / Evidence / Handoff 存储          │
                          │                                              │
                          │  能力面（唯一出口）                              │
                          │  └─ Flux MCP Server                           │
                          │     context.get · brain.search · skill.get    │
                          │     handoff.put · proposal.create             │
                          │     operation.record（+ 第二批按评审扩展）        │
                          └───────────────────┬──────────────────────────┘
                                              │ Streamable HTTP + Bearer
              ┌───────────────┬───────────────┼───────────────┬────────────────┐
              │               │               │               │                │
        flux-builtin       Codex        Claude Code      OpenCode        桌面端 UI
      （内置·DSH+DeepSeek）（外部 agent）（外部 agent）   （外部 agent）  （事件/审批/上下文视图）
              │               │               │               │
         自带 loop       自带 loop       自带 loop       自带 loop
         自带 session    自带 session    自带 session    自带 session
        L3 对话自持       L3 对话自持      L3 对话自持      L3 对话自持
```

上下文流转（共享的只有 L0–L2，L3 永不进 Flux）：

```text
L0 Project Brain ─┐
L1 Task Context ──┼─→ Flux 统一持有（满保真存档 + 条级指纹）
L2 Run Context ───┘        │
   （提案/测试/操作/证据）    │  context.get：按预算打包（全量 → 压缩 → 引用）
                           ▼
                   agent（自己的 loop / session / L3 对话）
                           │
                           │  handoff.put（结构化交接信封）
                           ▼
              Flux 交接卡 ──→ 下一个 agent 的 context.get 首包
```

## 3. Flux MCP Server（Phase 2 新建）

### 3.1 形态与端点

- **Transport**：Streamable HTTP，单端点 `POST http://127.0.0.1:8000/mcp`，挂载在现有 FastAPI 应用内（与 REST `/api/v1/*` 同进程、同生命周期）；stdio 作为单机备选（同一套工具实现，两个 transport 适配层）。
- **JSON-RPC**：`initialize` / `tools/list` / `tools/call`；服务端到客户端的通知走同一端点的 SSE 流。
- **代码位置**：`backend/flux/mcp/`（`server.py` + `auth.py` + `tools/*.py` + `context_packager.py`）。
- 已核实各消费端的接入能力（[2026-10-01 实测]）：DSH 捆绑闭包内含 `@deepseek-ai/dsh-mcp-client`，支持 `streamableHttp`；`codex-cli 0.157.1` 支持 `codex mcp add <name> --url <URL>`（含 `--bearer-token-env-var` 鉴权）。

### 3.2 鉴权与身份（服务端盖章）

- 新增 `agent_tokens` 表：`id / agent_id / token_hash(sha256) / scopes / created_at / revoked_at`；只存 hash，不存明文，token 格式 `fxt_` + 32 字节随机 hex。
- 桌面端「接入 agent」时生成 token，以环境变量 `FLUX_AGENT_TOKEN` 注入 agent 配置；token 不写入任何对话与文档。
- 服务端每次调用解析 token → `agent_id` + `scopes` → 过 Permission Engine；**客户端不能自报 agent 身份**，写入类工具（`proposal.create` / `handoff.put` / `operation.record`）的 `provenance.agent` 由服务端盖章。
- fail-closed：无 token → 401；工具越权 → MCP error（不静默降级），并记一条 operation。
- agent id 注册表沿用 `agent_runtime` 档案注册表：`flux-builtin` / `codex` / `claude-code` / `opencode`。

### 3.3 Phase 1 工具清单（6 个，先上）

> **落地进度（2026-10-01）**：第一批 4 个已实现并测试通过——`context.get` / `workspace.read` /
> `workspace.diff` / `proposal.create`（`backend/flux/core/mcp/`）。剩下 `brain.search`、
> `skill.get`、`handoff.put`、`operation.record` 未实现，其中 `operation.record` 依赖
> `operations` 表，`handoff.put` 依赖 `handoffs` 表——两张表随第二批一起上。
> 首批刻意不含任何需要"挂起等审批"的工具，审批回流桥（§3.6）与审查页一并落地。

| 工具 | 类别 | 后端映射（现状 → 目标） | Permission | 默认策略 | 事件 |
| --- | --- | --- | --- | --- | --- |
| `context.get` | 读 | 新建 `mcp/context_packager.py`；数据源：tasks 表、Run 记录、`virtual_changes`、operations | `file.read`（限任务范围） | Allow | — |
| `brain.search` | 读 | 扩展 `ProjectBrain`（[project_brain/service.py](file:///root/workspace/flux/backend/flux/core/project_brain/service.py) 现有 `sections()`/`context()`，补 `search()`：section + 关键词结构化检索，向量检索后置） | `file.read` | Allow | — |
| `skill.get` | 读 | 新建 `flux/skill_runtime/`（Skill 声明式定义 + 加载器 + 渲染器） | `file.read` | Allow | — |
| `handoff.put` | 写 | 新建 `handoffs` 表 + service | 无敏感（写 Flux 内部状态） | Allow（限本 Run 关联 Task） | `agent.handoff` |
| `proposal.create` | 写 | 复用 `VirtualWorkspaceService.propose_changes()`（[service.py](file:///root/workspace/flux/backend/flux/core/virtual_workspace/service.py#L104)）；入参校验复用 `proposal_parser.py` 的解析器（Phase 1 从 `developer.py` 拆出） | `file.write`（仅提案，不落盘） | Allow；路径命中保护名单 → Require Approval | `workspace.changed` |
| `operation.record` | 写 | 新建 `operations` 表 + service | — | Allow | `operation.recorded` |

`operations` 表字段：`id / task_id / run_id / agent（盖章）/ kind（assertion|evidence|fact）/ tool / params_digest / result_hash / body / ts`。**`fact` 只能由 Flux 自己写**（tester/apply 的结果），agent 只能写 `assertion` 与 `evidence`。

### 3.4 第二批候选扩展（逐个过权限评审后再上）

| 工具 | 类别 | 映射 | 默认策略 |
| --- | --- | --- | --- |
| `context.fetch(ref)` | 读 | 取回被压缩条目的原文 | Allow |
| `workspace.read(path)` | 读 | `ProjectFileExplorer.read()`（[explorer.py](file:///root/workspace/flux/backend/flux/core/project_files/explorer.py#L224)） | Allow |
| `workspace.diff(change_id)` | 读 | `diff_engine.compute_file_diff()` | Allow |
| `test.run(change_id)` | 执行 | `TestRunner.run()`（[test_runner.py](file:///root/workspace/flux/backend/flux/core/virtual_workspace/test_runner.py#L55)，命令白名单） | Require Approval |
| `connector.list` / `connector.execute` | 混合 | `ConnectorRegistry`（[base.py](file:///root/workspace/flux/backend/flux/connectors/base.py#L103)），按 manifest `required_permissions` 分级 | 读类 Allow；`github.create_pr` 等 Require Approval |
| `brain.knowledge.propose` | 写 | 候选知识，**一律人审后入 L0** | Allow（提交）；入库 Require Approval |
| `task.get` / `task.transition` | 混合 | Workflow 状态机交互（Phase 3 与 workflow 一起定） | `task.get` Allow；`transition` Require Approval |

### 3.5 面上一律不暴露（硬禁令）

- `workspace.apply`——真实写盘只经 UI 人审 + ApplyEngine（[apply_engine.py](file:///root/workspace/flux/backend/flux/core/virtual_workspace/apply_engine.py#L98)）。
- `git.push`——commit / push 由 Git 面板人工触发（`git_integration/service.py`）。
- `secret.read`——`Capability.SECRET_ACCESS` 永不进任何 token 的 scopes。
- 原始 shell——`terminal.execute` 不直接暴露；测试只经 `test.run` 的白名单命令。

### 3.6 审批回流

```text
agent → tools/call（Require Approval 类）
   → Permission Engine 挂起调用
   → EventBus: approval.requested
   → 桌面端审批卡（显示工具、入参摘要、发起 agent）
   → 批准：继续执行并返回结果 / 拒绝：MCP error（approval_denied）
   → 两种结果都记 operation
```

## 4. 内置 agent 接入路径（以 DeepSeek 为基底）

### 4.1 它是谁

- **档案**：id `flux-builtin`，runtime = DeepSeek Harness（DSH）+ Cordis，模型 = DeepSeek；随 Flux 交付、默认不启动（用户可只用外部 agent）。
- **它是 Flux 的默认 agent，不是 Flux 本体**：Flux 对它的支持仅限「拉起 / 下发 / 收事件 / 记 Run」。

### 4.2 连接方式（只经 MCP）

- Flux 在容器启动或用户点「启用内置 Agent」时，生成 MCP 配置片段注入 DSH 的 `mcpServers`：`flux` server 指 `http://127.0.0.1:8000/mcp`，Bearer = `agent_tokens` 里 `flux-builtin` 的 token（经环境变量传递）。
- 此后 agent 的一切 Flux 能力（context / brain / skill / proposal / handoff / operation）**只经这条 MCP 连接**。

### 4.3 Flux 侧只做四件事

1. **下发任务**：`POST /api/v1/agents/flux-builtin/runs` → 创建 Task/Run + 生成上下文首包（§6.3）→ `FluxDshClient.start_run()`（[dsh_client.py](file:///root/workspace/flux/backend/flux/core/agent_runtime/dsh_client.py#L104)）。
2. **收事件**：DSH 流式事件 → `dsh_events.to_flux_event()` 映射 → EventBus → WebSocket/SSE → 桌面端（不造事件、不加工内容）。
3. **审批回流**：MCP 侧 `approval.requested` 与 DSH 的审批通道对接（桌面端是唯一审批入口）。
4. **记 Run**：run_id / session_id / 起止 / 终态 / 引用过的 context 快照 id——**不记对话内容**。

### 4.4 对内置 agent 的禁令

- 不新增 `model_gateway/providers/` 下的 DSH provider；`AgentExecutor` 冻结后不再被任何路径调用。
- Flux 数据库不出现 L3 对话原文（消息、reasoning、tool 结果原文）。
- 不把 DSH 的 session 当 Flux 的上下文层：Flux 只留 `session_id` 引用。

## 5. 外部 agent 对等路径

- **同一个面**：Codex / Claude Code / OpenCode 拿到的 `tools/list` 与内置 agent **完全一致**（同一 token 体系、同一权限模型）；差异只在各自 token 的 scopes。
- **Codex 接法**（实测支持）：`codex mcp add flux --url http://127.0.0.1:8000/mcp --bearer-token-env-var FLUX_AGENT_TOKEN`；执行时 `codex exec -C <隔离工作目录> -c mcp_servers.*` 逐次注入，不污染全局配置。
- **沙箱口径**：外部 agent 由 Flux 指定隔离工作目录，产出经 `proposal.create` 回流；不允许直写真实项目。
- **Claude Code / OpenCode**：Phase 4 现场核实其 MCP 客户端配置方式（同样 streamable HTTP + Bearer），核实结果写入 [DSH_FLUX_INTEGRATION_PLAN.md](DSH_FLUX_INTEGRATION_PLAN.md)；不为它们设计任何专用通道。

## 6. 上下文流转（统一上下文的具体机制）

### 6.1 归属

| 层 | 内容 | 归属 | 存储 |
| --- | --- | --- | --- |
| L0 项目事实 | 架构、约定、历史决策、踩坑 | Flux | `project_brain` 表（sections：overview / tech_stack / architecture / coding_rules / decisions / agent_notes） |
| L1 任务上下文 | Task / 需求 / 目标 / 验收标准 / 约束 | Flux | tasks 表 |
| L2 运行上下文 | 相关文件、diff、提案、测试结果、操作轨迹、交接 | Flux | virtual_changes / operations / handoffs / 测试记录 |
| L3 对话 | 消息、reasoning、session state | **agent** | agent 侧；Flux 不存原文，只留 Run 记录与引用 |

**存档满保真的适用范围**（较早期设计的收窄）：满保真 = **Flux 侧的每条工程事实与每次 MCP 操作**（入参摘要 + 结果 hash + 条级指纹），不是 agent 的对话原文。UI「完整视图」展示 Flux 侧存档；当前 agent 的对话由 agent 侧流式呈现（显示 ≠ 接管）。

### 6.2 投喂：MCP pull 为主

- 主通道：agent 主动调 `context.get`（入参：`task_id`、可选 `run_id` / `refs` / `budget`）拉取打包结果。
- 兜底：没有独立 system 通道的 agent（如 `codex exec`）由 Flux 把打包结果作为 prompt 前缀注入；DSH 走 session 首条消息或 `agent-instructions`。
- 每次打包记 `context_snapshots` 表：`snapshot_id / task_id / run_id / budget / used / 压缩决策列表 / created_at`，供回溯与 UI「投喂视图」。

### 6.3 打包与压缩（预算制）

```
可投喂预算 = 目标模型上下文窗口 × 安全系数 − 预留输出 − 系统/工具定义占用 − 必留项体积
```

- 三档降级：**全量**（默认）→ **压缩**（结构化提取优先、文本摘要其次）→ **引用**（留 `ref`，可用 `context.fetch` 取回）。
- **永不压缩**：当前任务与验收标准、约束、未决问题、最近若干轮、涉及文件最新版本、未验证断言清单；**可压缩**：较早历史、已完成步骤经过、已解决讨论、长工具输出原文（保留 hash + 摘要 + `ref`）。
- 压缩执行分三层：**T1 规则/结构化**（零模型，先行）→ **T2 常驻流式压缩器**（Flux 内置本地小模型，增量 + 滚动汇总；数值/命令/文件名/hash 由 T1 抽取，不由小模型生成）→ **T3 被调 agent 二次裁剪**（可选，超窗时物理不可用，仅作补充）。
- Phase 2 先交付「全量 + T1」；T2 在 Phase 2 后半接入。

### 6.4 条级指纹（provenance）

- 每次工具调用、每条结论各带指纹；服务端盖章，渲染形如：

```text
[codex · r7f2 · 断言] 登录校验应抽成独立函数，理由是……
[codex · r7f2 · 证据 · flux.workspace.read(auth/login.py@sha256:9c1f)]（文件正文）
[flux  · 事实 · tester.run(pytest)@sha256:44ab] 3 failed, 12 passed
```

- 配套规则写进每个 Envelope：**历史条目带来源标注；断言不等于事实，采信前自行验证。**

### 6.5 Handoff（跨 agent 唯一通道）

- 字段：已完成 / 未完成 / 关键决策 / 未决问题 / 涉及文件 + hash / 下一步建议。
- 产生：agent 显式 `handoff.put`；Flux 在 Run 结束时自动摘要兜底（标记 `source=auto`）。
- 消费：切换 agent 时桌面端显示「交接卡」（可编辑），下一个 agent 的 `context.get` 首包包含它；**不搬迁对话历史**。

### 6.6 防污染

- agent 不能直接写 L0，只能 `brain.knowledge.propose`，一律人审后入库。
- 多 agent 并行时 L0/L1 只读共享；改动只能以 `proposal.create` 回流。
- 每次 Run 记录引用的 context 快照 id，事后可回溯「当时它看到的上下文」。

## 7. 统一工具与统一 Skill

- **统一工具** = MCP 工具面（§3.3–3.4）；工具背后是 Flux 既有模块（connectors / project_files / virtual_workspace / project_brain），**不新建 agent 专用工具**。
- **统一 Skill**：Skill 是真源在 Flux 的声明式定义——`name / 描述 / 适用任务类型 / 指令模板 / 绑定工具白名单 / 输入输出约定`；`skill.get` 返回渲染后的 skill 包（指令 + 该 skill 可用的工具子集），agent 注入自己的 prompt。真源目录 `skills/`（YAML，随仓库版本化），Phase 2 只读，UI 后续可编辑。
- **统一上下文** = §6；三样东西对所有 agent 同一份，无第二真源。

## 8. 模块处置总表（简版，详表见分析文档 §2）

| 处置 | 模块 |
| --- | --- |
| **退役**（冻结 → 删除） | `AgentExecutor`、`DeveloperAgent`/`TesterAgent` 的 Agent 部分、`codex_cli` provider、`AgentContext` 的消息存储 |
| **重构**（保留外壳、换职责） | `AgentManager` → 档案注册表 + Run 记录（去掉 model 执行入口）；`/api/v1/agents` → 档案 + 下发 Run；`/api/v1/models` → 仅服务平台内部（T2 压缩、扫描、摘要） |
| **保留 + 补强** | `workflow_engine`（状态机）、`permission_engine`（升级为 MCP 策略执行点）、`virtual_workspace`（symlink 逃逸防护**已完成**：`path_guard.py`；Proposal 级批量事务待补，分析文档 §5）、`project_*`、`event`、`connectors`、`git_integration` |
| **保留 + 改造** | `dsh_client` / `dsh_events` → 内置 agent 的启动器与事件桥（不进 ModelGateway） |
| **新建** | `backend/flux/mcp/`（MCP Server + 鉴权 + 打包器）、`flux/skill_runtime/`、`handoffs` / `operations` / `agent_tokens` / `context_snapshots` 持久化 |

**执行状态（2026-10-01）**：表中「退役」与「重构」两行的代码修正已完成并推送（commit `45db1b9`，删 6 个模块 / 4 个专属测试文件，286 个用例全绿、`verify.sh` 五步全过）；「保留 + 补强」里 `virtual_workspace` 的 symlink 逃逸防护已完成（commit `5babc59`，新增 `path_guard.py` 作唯一真源、写 / 备份 / 读三处共用）；「新建」里的 **MCP Server 已完成**：

- `backend/flux/core/mcp/`：`server.py`（Streamable HTTP 单端点 `POST /mcp`，无状态，支持 `initialize` / `tools/list` / `tools/call` / `ping`，通知回 202）、`auth.py`（令牌签发与校验）、`context_packager.py`（全量 + T1 规则裁剪）、`tools/`（4 个工具 + 工具注册表）。
- `agent_tokens` 表 + 迁移 `f2a7c1d9b6e4`；令牌 `fxt_` + 32 字节 hex 只存 sha256，`secret.access` 在签发入口即被拒绝（§3.5），撤销后下一个请求失效（鉴权不做缓存）。
- REST 侧新增 `/api/v1/agents/{agent_id}/tokens`（签发 / 列出 / 撤销）供"接入 agent"使用；明文只在签发响应里出现一次。
- `operations` / `handoffs` / `context_snapshots` 三张表**尚未落地**，第二批随 `operation.record` / `handoff.put` 一起上；当前工具调用事件走 EventBus（`mcp.tool_called` / `mcp.tool_denied`），事件体不含入参（提案入参可能带整份文件内容）。
- 已知缺口：M0 的 REST 面整体没有用户鉴权，因此签发令牌的接口目前只以内网/本机为信任边界；桌面端用户鉴权落地后必须补 owner 校验（`api/v1/agents.py` 已就地注明）。

## 9. 实施顺序与验收

| Phase | 内容 | 验收标准 |
| --- | --- | --- |
| **2 MCP 能力面** | `mcp/` 骨架 + 6 工具 + token/权限 + 打包器（全量+T1） | `tools/list` 恰好返回 6 个工具且**不含** apply / git.push / secret；6 个工具用真实数据直连跑通；越权 token fail-closed；写入均带服务端 provenance |
| **3 内置 agent** | DSH 经 MCP 打通：下发、事件、审批回流、Run 落库 | `flux-builtin` 完成「读上下文 → 提案 → 交接」闭环；Flux 库中查不到 L3 对话；提案经人审可 Apply |
| **4 外部 agent** | Codex 经同一面完成同类任务；Claude Code / OpenCode 接入方式核实 | 外部 agent 与内置 agent 的 `tools/list` 完全一致 |
| **5 真实闭环** | Requirement → Agent → Context/Brain → Proposal → Review → Apply → Test → Repair → Git | 全链路真实事件驱动，无假事件、无绕行写盘 |

每阶段开工前对照 §1 的 7 条硬约束检查；违反任何一条即停手回到本文档对齐。
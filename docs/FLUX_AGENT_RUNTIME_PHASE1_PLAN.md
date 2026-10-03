# Flux Agent Runtime 第一阶段实施方案

> 本文是 `docs/FLUX_AGENT_RUNTIME_PROTOCOL.md` 的**落地实施方案**。对协议文档采取
> 「参考而非照搬」：主干沿用其四层信息模型与接入链路，按 Flux 现状做两处调整并锁定边界。
> 范围对应协议文档 §12，去掉其中「非托管场景引导」一条。

## 1. 目标

让 **Flux 托管启动**的外部 CLI Agent 能在协议层面知道自己运行在 Flux Runtime 中，
并通过统一的能力边界与 Flux 协作——不是让 Agent「读一篇产品介绍」。

```text
Flux 托管启动 Agent
      ↓
FLUX_* 运行时身份
      ↓
Handshake（复用 MCP initialize）
      ↓
flux_context（运行时上下文 + 能力声明）
      ↓
Capability Discovery（缺失即降级）
      ↓
Flux MCP / flux CLI（兜底）
      ↓
Proposal → Gate → Apply → Test → Git
```

## 2. 已锁定的决策

| # | 决策 | 说明与理由 |
|---|---|---|
| D1 | **只覆盖 Flux 托管启动场景** | 要用 Flux 就在 Flux 里启动；不为「用户自己起的 Agent」注入平台引导，因此**不做**工作区 `AGENTS.md` 一类的持久引导文件 |
| D2 | **工具命名：新增 `flux_context`，不重命名既有工具** | 保留 `context.get / workspace.read / workspace.diff / proposal.create`。重命名会波及已签发的 DSH 注入与既有测试，收益却只是「命名对齐」 |
| D3 | **职责分离、并存不合并** | `flux_context` = 运行时（Run / Task / Workspace / Policy / Capabilities）；`context.get` = 项目事实与约束（内容）。两者语义不同，不互相替代 |
| D4 | **MCP 兜底写进引导** | 平台说明中明确：MCP 不可用时可用 Server CLI 兜底（`flux tools call`，见 §9） |

## 3. 与协议文档的差异

| 协议文档的做法 | 本方案的处理 |
|---|---|
| §2.1 `FLUX_*` 环境变量 | **采纳**，作为机器可读的运行时身份 |
| §2.2 Agent Handshake（HELLO / protocol / capabilities） | **采纳目标，改落点**：落到既有 MCP 上，**不新造第二套协议**（详见 §5 待确认 1） |
| §3 `flux_context` MCP 工具 | **采纳**，新增工具；与既有 `context.get` 分工（D3） |
| §4 Capability Discovery + 缺失降级 | **采纳**，能力声明由 `flux_context.capabilities` 给出 |
| §5 `flux_context / flux_task / flux_workspace / flux_proposal / flux_status / flux_logs` | **部分采纳**：第一版只新增 `flux_context`；其余能力先复用既有工具，避免工具面重复 |
| §6 Proposal Contract（含 Base Revision Check） | **采纳**，补齐 `base_revision / validation / approval / apply_result` |
| §7 System Context（启动注入的短 prompt） | **采纳**，仅在托管启动时注入 |
| §8 Adapter 分层 | **采纳**，Adapter 不承载 Flux 核心业务逻辑 |
| §9 一键扫描与接入（`flux agents …`） | **采纳**，落在已有 Server CLI 上 |
| §10 安全与边界 | **采纳**：`FLUX_*` 不是凭证、Workspace 不可扩权、Proposal 不得绕 Policy Gate、判权在服务端 |
| §11 Web/Mobile/CLI 共享同一 Core 与 Runtime | **采纳**，不为不同入口重复实现 Runtime |
| §7 之外的非托管引导 | **不采纳**（D1） |

## 4. 第一阶段实施清单

| # | 事项 | 落点 | 依赖 | 验收 |
|---|---|---|---|---|
| 1 | `FLUX_*` Runtime Context 注入 | `core/agent_runtime/dsh_client.py`（起 Run 时写入 Agent 环境 / loader patch） | — | 托管的 Agent 进程内可读到 `FLUX_RUNTIME/FLUX_RUN_ID/FLUX_TASK_ID/FLUX_WORKSPACE/FLUX_AGENT_ID/FLUX_MCP_ENDPOINT` |
| 2 | Agent Handshake v1 | 复用 MCP：`initialize` 的 `clientInfo` 上报 + `flux_context` 返回 `protocol/version/capabilities` 完成确认 | 1 | 双向确认可观测：Agent 上报 name/version，Flux 回 protocol/capabilities |
| 3 | `flux_context` MCP 工具 | `core/mcp/tools/`（新增） | 1,2 | 返回 platform / run / task / workspace / agent / policy / capabilities |
| 4 | Capability Discovery + 降级 | 由 `flux_context.capabilities` 声明 | 3 | 能力缺失时 Agent 可正常降级，不假设 Runtime 全能力 |
| 5 | `flux agents scan / list / connect / remove` | 扩展 `flux/cli/`（Discovery：PATH → 版本 → 健康 → auth → 能力） | 1 | 一条命令列出本机可接入的 CLI Agent 及其状态 |
| 6 | Generic CLI Adapter（+ 1 个具体 Adapter） | `core/agent_runtime/adapters/`（新增） | 5 | 统一接口 `discover/verify/get_version/check_auth/start/stop/cancel/parse_event/handshake` |
| 7 | Agent Installation 状态机 | 新增模型/表 + 迁移：`DISCOVERED → VERIFIED → CONNECTED → READY` | 5,6 | 状态可查询、可持久化 |
| 8 | Proposal Contract + `proposal_required` Policy | `core/virtual_workspace/`：补 `base_revision / validation / approval / apply_result`，apply 流程加 **Base Revision Check** | — | 不满足 Policy 的 Proposal 被拒绝 Apply，而非绕过门禁 |

## 5. 待确认事项

1. **握手是否严格照协议文档的独立端点**：本方案默认**复用 MCP**（`initialize` + `flux_context`）。
   若要求严格对齐文档 §2.2 的 `HELLO → READY` 独立握手，需要另设通道与版本协商逻辑。
2. **首个具体 Adapter 选谁**：协议文档 §12 点名 **OpenCode**；本机现成可跑的是 **Codex / DSH**。
   默认先交付 **Generic CLI Adapter**，具体 Adapter 待定。

## 6. 推进节奏

```text
1 → 4   最小可验证闭环：托管启动的 Agent 能通过 flux_context 自识并发现能力
5 → 7   发现 → 适配 → 状态机
8       可与上两组并行
```

## 7. 明确不做（沿用协议文档 §12）

- 多 Agent 自动协商协议
- Cloud Agent 特有协议
- Marketplace 协议
- Team / Enterprise 权限协议
- 非托管场景的平台引导（D1）

## 8. 与 Server CLI 的关系

`flux agents scan / connect` 落在已有 Server CLI（`flux/cli/`，见 `docs/NEXT_PHASE_OPTIMIZATION.md` §9）上：
Server CLI 既是运维兜底入口，也是 Agent Discovery 的宿主。MCP 不可用时，Agent 可经
`flux tools call` 继续工作——这条兜底路径写进平台引导（D4）。

# Flux 架构对齐分析（Phase 1）

> 依据：[FLUX_ARCHITECTURE_ALIGNMENT.md](FLUX_ARCHITECTURE_ALIGNMENT.md)（2026-10-01 提交）、当前仓库代码审计（2026-10-01）、[CONTEXT_DESIGN.md](CONTEXT_DESIGN.md) / [FLUX_PROJECT_OVERVIEW.md](FLUX_PROJECT_OVERVIEW.md) / [DSH_FLUX_INTEGRATION_PLAN.md](DSH_FLUX_INTEGRATION_PLAN.md)。
>
> 状态：**待确认**。按对齐文档 §21，本阶段只做「当前模块 → 正确职责 → 处置 → 新边界」的分析，不改代码。

## 0. 结论先行

接受对齐文档 §23 的全部 7 条禁令，无保留。我的自查结论：此前工作有 **3 处跑偏**，其中 1 处已经写进代码。

1. **把 Codex CLI 接成了模型 Provider**（`model_gateway/providers/codex_cli.py`，commit `1e0dba0`）。
   错在把「能借到模型」和「让 agent 干活」混为一谈——Codex 是外部 agent（一等公民，经 MCP 消费 Flux 能力），不是 Flux 的模型供应商。同一逻辑也不允许发生在 DSH 上。
2. **上下文设计把 agent 私有对话卷进了 Flux 的共享层**（[CONTEXT_DESIGN.md](CONTEXT_DESIGN.md) 的分层与存档范围、集成方案 §26.2「每轮对话原文全部留档」）。
   对齐文档 §6/§7 明确：L3 对话归 agent，Flux 不接管；agent 之间不搬迁完整上下文，只走 Handoff。我此前的「完整上下文存档 + 跨 agent 投喂」需要一个适用范围收窄。
3. **总览文档保留了旧叙事**（[FLUX_PROJECT_OVERVIEW.md](FLUX_PROJECT_OVERVIEW.md) §1「把 AI 当工程角色来编排」、§4 模块表），旧链路（`AgentExecutor` 跑模型 + `DeveloperAgent` 解析模型输出）仍在被当作核心执行层描述。

现状与目标架构的实质差距一句话：**Flux MCP Server 在当前代码里完全不存在**（全仓库 grep「mcp」只命中文档）。目标架构的全部「正确方向」都收敛到它：先有 MCP 能力面，DSH 与外部 agent 才有可接的东西。

## 1. 对齐后的口径（重新表述）

| 对象 | 口径 |
| --- | --- |
| Flux | AI 软件工程平台：Task、Context（L0–L2）、Brain、Scanner、Files、Workspace、Proposal、Diff、Apply、Permission、Skill、Connector、Git、Operation/Event、MCP Server。**不做 agent loop、不编排对话、不替 agent 调模型** |
| Flux 内置 agent | 基于 DSH + Cordis，随 Flux 交付的默认 agent；与外部 agent 在能力消费上**完全对等** |
| 外部 agent | Codex / Claude Code / OpenCode，一等公民，同一个 MCP 面 |
| Agent 私有 | conversation、session、reasoning、loop 全部归 agent；Flux 只留 Run 记录与轨迹引用 |
| 跨 agent | 只传 Handoff（完成/未完成/关键决策/未决问题/文件+hash/下一步），不搬迁对话 |
| 小模型 | Context Infrastructure（T1 规则 → T2 增量摘要 → T3 agent 可选二次裁剪）；**不是 agent，不是 Tech Lead** |
| Workflow | 工程状态机（Task 状态、阶段、handoff、等待、重试、审批）；不替 agent 决定下一步 |
| 真实文件 | 唯一写盘入口是 ApplyEngine；Agent 只能 `proposal.create`，人工审查后 Apply |

## 2. 模块级分析表（§21 Phase 1 要求的输出格式）

| 模块 | 现状（代码事实） | 正确职责 | 处置 | 新边界 |
| --- | --- | --- | --- | --- |
| `agent_runtime/executor.py` | 组装 system/user 消息、调 `ModelRouter.chat`、把对话写进 `AgentContext`（executor.py:58-75） | 无——Flux 不跑 agent loop | **退役**（先冻结，DSH/MCP 链路可用后删除） | 模型调用、prompt 组装、对话存储全部归 agent |
| `agent_runtime/manager.py` | 内存注册表 + `execute()` 委托执行器 + 事件（manager.py:25-111） | agent 档案注册表 + Run 记录 + 事件发布 | **重构** | 去掉模型执行入口；注册表成为 MCP provenance 里 agent id 的唯一来源 |
| `agent_runtime/developer.py` | `build_developer_prompt` + 解析模型输出为 `CodeChangeSet` + `DeveloperAgent`（developer.py:59-155） | 提案内容校验（平台侧） | **拆分**：解析器保留为 proposal 入参校验器；prompt 与 Agent 部分退役 | 提案一律经 MCP `proposal.create` 进入，Flux 只校验、落状态 |
| `agent_runtime/tester.py` | 测试报告解析（平台能力）+ `TesterAgent` 调 agent（tester.py:39-247） | 测试执行与结果解析 | **拆分**：平台部分保留；TesterAgent 退役 | 测试是平台能力；是否作为 MCP 工具暴露后续定 |
| `agent_runtime/manifest.py` `lifecycle.py` `context.py` | manifest→spec；生命周期状态机；`AgentContext` 存对话 | 档案声明 / Run 生命周期；对话存储归 agent | manifest、lifecycle **保留**；context 的对话部分**退役** | 档案 = 角色 / 权限组 / 可见范围；会话状态不落 Flux |
| `agent_runtime/dsh_client.py` `dsh_events.py` | DSH 薄客户端 + 事件映射，loop 在 SDK 侧（dsh_client.py:73-174） | 内置 agent 接入层 | **保留 + 改造** | Phase 3 打通「DSH agent → Flux MCP」；事件映射补齐；审批回流 Permission Engine；**不得经 ModelGateway** |
| `workflow_engine/orchestrator.py` | `plan()` 只展开步骤链，无模型调用、无推理（orchestrator.py:24-49） | 工程状态机 | **保留**，明确边界 | 管状态/阶段/等待/重试/审批，不做 reasoning、不代调工具 |
| `model_gateway/*` | 供应商抽象 + 路由，被 `AgentExecutor` 使用（router.py:18-69） | Flux 侧基础设施的模型服务（T2 压缩器、扫描/摘要等） | **保留但限定服务对象** | 不再是任何 agent loop 的模型路径 |
| `model_gateway/providers/codex_cli.py` | 把 `codex exec` 包成模型供应商（codex_cli.py:108-197） | 无 | **撤下**（去留见 §6 待裁决） | Codex 走外部 agent 通道（Phase 4 验证其经 MCP 工作） |
| `api/v1/agents.py` `api/v1/models.py` | `/agents` 可执行 agent（agents.py:15-54）；`/models/chat` 暴露模型调用（models.py:19-40） | 任务下发面 / 平台模型服务 | **重构** | `/agents` 的 execute 改为「创建/下发 Run」，不代跑模型 |
| `permission_engine/policy.py` | 角色能力集 + `require()` / `require_agent_capability()`（policy.py:26-49），connectors API 已接线 | MCP 工具调用的策略执行点 | **保留 + 升级** | fail-closed；`workspace.apply` / `git.push` / `secret.read` 不上面 |
| `virtual_workspace/*` | 提案状态机 + diff + 唯一写盘入口 + 备份回滚（apply_engine.py:98-151） | 核心安全边界 | **保留 + 补强** | 补 symlink 逃逸防护、Proposal 级批量事务（§5） |
| **Flux MCP Server** | **不存在**（backend grep 无实现） | 唯一能力出口 | **新建（Phase 2）** | Streamable HTTP；工具清单见集成方案 §25 |
| **Skill 运行时** | 不存在（只有 `connectors/` 契约） | `skill.get` 的真源 | **新建（Phase 2）** | — |
| 平台模块 `task_engine` / `project_brain` / `project_scanner` / `project_files` / `git_integration` / `event` / `connectors` / `models` | 已实现（M0/M1） | 平台能力 | **保留** | 按 MCP 面暴露，实现形态不动 |

## 3. §20 七问速答（带代码依据）

1. **模块在跑平台能力还是 agent 大脑？** 跑大脑的：`executor`（整个 run）、`developer`/`tester` 的 Agent 部分、`codex_cli`；其余是平台能力。见 §2 处置列。
2. **Agent loop 归谁？** 归 agent。现状 Flux 有一条自建 loop（`AgentExecutor.run`）——退役，不修不扩。
3. **模型调用归谁？** agent 的循环由 agent 自己调模型；Flux 只为自己基础设施调模型（T2 压缩、扫描、摘要）。
4. **Agent session 归谁？** 归 agent。`dsh_client` 只保留 `session_id` 引用（回溯用），不接管内容。
5. **Flux MCP 是否唯一能力出口？** 目标如此；现状 MCP 不存在 → Phase 2 新建，且建成前不新增任何 agent 专用通道。
6. **外部 agent 与内置 DSH 是否对等？** 目标对等（同一个 MCP 面）；现状两者都没有接入，从零开始没有历史包袱。
7. **Proposal / Apply 是否完全由 Flux 控制？** 是。API / Agent / UI 均不写盘，唯一入口 ApplyEngine（apply_engine.py:10、service.py:8）。

## 4. 我此前交付物的纠偏清单

| 交付物 | 问题 | 修正 |
| --- | --- | --- |
| `providers/codex_cli.py`（代码） | 外部 agent 被接成模型供应商 | 从 ModelGateway 撤下；Codex 改列 Phase 4 外部 agent |
| [CONTEXT_DESIGN.md](CONTEXT_DESIGN.md) | L3 对话被纳入 Flux 存档与跨 agent 投喂 | 存档范围收窄为 **Flux 侧 L0–L2 工程事实 + MCP 操作轨迹 + Handoff**（满保真 + 条级指纹不变）；删除「对话原文留档」；投喂通道以 MCP `context.get` 拉取为主，前缀注入降为兜底 |
| [CONTEXT_DESIGN.md](CONTEXT_DESIGN.md) §10 UI | 「完整上下文视图」含对话 | 改为：完整视图 = Flux 侧存档；当前 agent 的对话由 agent 侧流式呈现（显示 ≠ 接管） |
| [FLUX_PROJECT_OVERVIEW.md](FLUX_PROJECT_OVERVIEW.md) | §1「编排工程角色」、§4 模块表 | 按 §1/§2 口径重写措辞；模块表替换为本文档 §2 |
| [DSH_FLUX_INTEGRATION_PLAN.md](DSH_FLUX_INTEGRATION_PLAN.md) §26.2 | 「每轮对话原文全部留档」 | 按上表同步修正，与对齐文档 §6/§7 一致 |
| solo / ide 桌面端 demo | 原计划立即开工 | **后置**：先 Phase 2 能力面成型；demo 事件来源必须是真实 Event Bus（对齐文档 §17），不造假事件 |
| 开发纪律 | — | 冻结 `AgentManager` / `AgentExecutor` 扩展；DSH 与 Codex 都不进 ModelGateway |

## 5. 安全缺口（对齐文档 §12 / §13 的落地项，代码级事实）

1. **symlink 逃逸（§12）**：`safe_relative_path` 只做字符串级校验（apply_engine.py:47-63）。`_assert_unchanged` / `_write` / `_verify`（apply_engine.py:159-205）与 `BackupService.backup` / `restore`（backup.py:32-49）都走 `Path` 接口：若 `workspace/config.py` 是指向外部文件的 symlink，读写与备份都会跟随它，逃出 workspace。需补：realpath 包含性校验 + `lstat` 逐段校验（与 Project File Explorer 同级保护）。
2. **批量原子性（§13）**：`service.apply` 以单条 `VirtualChange` 为粒度（service.py:165+），无 Proposal / ChangeSet 级事务——多文件提案会出现「A 成功、B 失败、A 留在盘上」。需补 Batch Apply Transaction（全成功或全回滚）。

## 6. 裁决结论与执行进度

**已裁决（2026-10-01）**

1. **L3 口径**：按对齐文档收窄——Flux 存档 = **L0–L2 工程事实 + MCP 操作轨迹 + Handoff**（满保真、带指纹、可懒加载查看）；agent 私有对话不进 Flux 共享层、切换不搬迁，只走 Handoff。已落到 [CONTEXT_DESIGN.md](CONTEXT_DESIGN.md) §3/§10/§13 与 [DSH_FLUX_INTEGRATION_PLAN.md](DSH_FLUX_INTEGRATION_PLAN.md) §26.2。
2. **codex_cli 的处置**：选 **A（撤下）**——Phase 1 已连同 `AgentExecutor` / `DeveloperAgent` / `TesterAgent` / `AgentContext` / `flow.py` 一并删除（commit `45db1b9`）；Codex 改列 Phase 4 外部 agent，经同一 MCP 面验证。

**执行进度**（对齐文档 §21）

1. ~~按 §4 修正文档（先对齐文字，再动代码）~~ → 已完成；Phase 1 代码修正已推送（commit `45db1b9`，286 用例全绿）。
2. Phase 2：Flux MCP Server（先出工具清单 × Permission 映射 × 鉴权方式，再实现）。
3. Phase 3：DSH → Flux MCP 打通（含事件映射补齐、审批回流）。
4. Phase 4：Codex / Claude Code / OpenCode 经同一 MCP 面验证。
5. Phase 5：真实工程闭环（Requirement → Agent → Context/Brain → Proposal → Review → Apply → Test → Repair → Git）。
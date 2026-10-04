# Flux 下一阶段开发计划：评审与分批执行方案

> 状态：评审意见 + 执行拆批（2026-10-04）
> 对象文档：`doc/FLUX_NEXT_DEVELOPMENT_PLAN.md`（远端提交 fdc75d4，原路径 `docs/`，本方案同步将其迁入 `doc/`）
> 原则：先立契约，再实现，最后接入；每批必须有可测验收断言，`make verify` 全绿才算完成。

## 1. 评审结论

**方向认可，可作为下一阶段总纲。** 计划把 Flux 从「拼现成工具」拉回「自建核心能力链」，
与 50 任务基准的收口结论互相印证：基准里「无提案」类失败的主要修复手段就是托管引导
（Bootstrap / 运行时上下文 / 工具面），修复后全量 31/50 → 40/50，且零「无提案」。
「Flux Environment 应成为 Agent 的第一份强制上下文」有现成实证支撑。

**落地前须先完成三件事**（本方案已给出）：

1. 目录纪律：新文档迁入 `doc/`（EXECUTION_STRATEGY §2.1 约定，`docs/` 已废弃）；
2. P0 拆批并给验收断言（7 项 P0 每一项都是系统级，不能一体推进）；
3. 三份契约先行：Environment 字段、Memory 边界、Memory↔Context 关系。

**认可的三点**：

- 克制：明确 P2 不优先、不再「发现成熟工具就拼进来」，与控制权清单（计划 §6）一致；
- 保留 Virtual Workspace / Apply Engine 作为唯一受控写入路径的判断正确（计划 §4）；
- 协议优先，核心行为模型不被外部产品绑定。

## 2. 与现状对照（已核对代码，非推测）

| 计划项 | 现状（仓库事实） | 结论 |
| --- | --- | --- |
| P0 Flux Environment | `flux_context` 工具已有 platform / run / task / workspace / agent / policy / capabilities + flux-agent v1 握手；`RUNTIME_BOOTSTRAP`（protocol.py）已落地并支持注入；`FLUX_*` 环境变量已共用真源 | **半成品：生产启动路径没接线**（见下） |
| P0 三层记忆 | `project_brain`（overview / tech_stack / scan）是 Project Memory 底座；User / Environment Memory 无 | 有底座，边界未定义 |
| P0 Flux Agent Protocol | 协议名 + 版本 + 握手回执已有；Lifecycle / Tool Request / File Request / Interrupt / Error / Result 完整面未定义 | 有起点 |
| P0 Agent 扫描接入 | `project_scanner` 只扫项目；Agent 能力识别 → Flux 标准转换无 | 缺 |
| P0 Skill / Connector Protocol | 无 | 缺 |
| P1 Execution Sandbox / Resource Gateway / 网络控制 / Handoff | 无（`permission_engine` 是 Policy 雏形） | 缺 |
| P1 Server CLI | `flux tools list` / `flux tools call` 等基础已有 | 有底座 |

**核心缺口（已定位到行）**：两条 Flux 托管启动路径——

- `api/v1/tasks.py:353`（任务执行）：`_execution_instruction` 只带 task 身份与 `context.get`
  指引，没有平台规则；
- `api/v1/dsh.py:23`（DSH 直连 API）：原文透传。

两者都**没有把 Runtime Bootstrap 交给 Agent**。`compose_instruction` 目前只被
`adapters/generic.py:build_run_argv` 调用，而它在 backend 无任何生产调用方。
也就是说：**当前「Flux 托管启动」的 Agent 拿不到「你在 Flux 里、改动要经 proposal.create、
不要假设直写工作区」这份强制上下文**——与计划 §3 的 P0 要求直接冲突。

## 3. 风险与修正意见

1. **P0 体量**：7 项 P0 并列推进容易拖成半成品 → 拆三批（§5），每批闭环后再开下一批；
2. **缺验收口径**：计划是方向性文档、无判定标准 → 每批给可测断言（§5）；
3. **目录回潮**：计划文档提交在 `docs/`，与 `doc/` 规范冲突 → 随本方案迁入 `doc/`；
4. **Bootstrap 真源漂移**：benchmark 执行器 `n11_bench.py` 复制了一份 Bootstrap 文案
   （测试侧，非 git）→ Environment 接线完成后，benchmark 应改为消费平台产物，不再维护副本；
5. **安全红线前置**：Memory / Resource Gateway / Cache 都是「把外部内容带进上下文」的入口
   → 对应批次开工前先写死三条：密钥/令牌永不入库不入上下文；缓存有来源记录与 TTL；
   外部内容不得直接进入系统级指令位置（防注入）；
6. **边界要写清**：Memory 与 `context.get` / `flux_context` 不得混（D3 并存不合并）；
   Sandbox 与 Apply Engine 都会「跑测试」→ 必须明确 Apply Engine 是落盘前门禁的权威，
   Sandbox 只是 Agent 的试跑环境。

## 4. 三份契约

### 4.1 Environment 契约（字段清单）

| 段 | 字段 | 来源（真源） |
| --- | --- | --- |
| identity | protocol{name,version}、platform{name,version,mode}、agent{id} | protocol.py + settings + 令牌身份 |
| runtime | run{id,status}、task{id,title,status} | agent_runs / tasks |
| workspace | path、git_branch、git_revision | settings.workspace_root + git_client |
| policy | proposal_required、direct_apply（恒 false） | settings |
| capabilities | context / proposal / workspace / apply / git（缺失即不可用，不虚报） | settings 推导 |
| rules | 改动经 `proposal.create` 提交；不假设直写工作区；MCP 不可用时 `flux tools call` 兜底（D4） | 协议常量 |
| entry | 开工先调 `flux_context`；项目事实用 `context.get` | 协议常量 |

**缺失行为**：字段不可得即如实省略 / 为 null，绝不伪造；不得因字段缺失阻断启动
（例如未配置 workspace_root 时，capabilities 里就没有 workspace / apply / git）。

### 4.2 Memory 边界

| 层 | 范围 | 写入方式 | 关键约束 |
| --- | --- | --- | --- |
| User Memory | 跨 Workspace | 用户确认后才写 | 可查 / 可删；敏感信息拒收 |
| Project Memory | 每个 Workspace / Project 自动 | 扫描 + 工作沉淀 | 结构 / 栈 / 决策 / 约束 / 历史 / 规范 / 交接 |
| Environment Memory | Flux 运行规则，全局 | 平台维护 | 优先于普通项目上下文 |

**红线**：密钥、令牌、凭证永不入库、不入上下文；每层有容量上限与清理机制。

### 4.3 Memory ↔ Context 关系（D3 并存不合并）

- `context.get` = 项目事实（只读现状：Brain / 文档 / 代码上下文）；
- `flux_context` = 我在哪里、我能做什么（运行时身份与能力）；
- Memory = 被记住的结论（可写、跨会话）——写入必须走受控路径，不允许 Agent 直写。

## 5. 分批执行

### 批次 ①：Environment 接线 + 协议最小闭环（本轮开工）

**目标**：Flux 托管启动的每个 Agent，第一份拿到的就是 Flux Environment；两条生产路径全覆盖。

**实现点**（已定位）：

1. `dsh_client._execute` 在交给 harness 的边界（`harness.run`，dsh_client.py 436-441 行）
   统一 `compose_instruction(run.instruction)`——单一收口点覆盖任务路径与 DSH API 路径；
   `run.instruction` 存库 / UI 展示保持用户原文，不被污染；
2. `protocol.RUNTIME_BOOTSTRAP` 升级为「第一份强制上下文」文案：身份 + 规则（先调
   `flux_context`、项目事实走 `context.get`、改动经 `proposal.create`、MCP 不可用时
   `flux tools call` 兜底）；
3. 环境变量注入维持现状（已共用 `build_runtime_env` 真源，不重复造）；
4. `flux_context` 字段与 §4.1 对齐（缺什么补什么，不改名、不合并——D2 / D3）；
5. 测试：两条启动路径的执行 prompt 均以 Bootstrap 起始；`run.instruction` 保持原文；
   现有工具面子集断言（`CORE_TOOL_NAMES ⊆ advertised`）不回退。

**验收断言**：

- 任务路径（tasks.py → start_run）与 DSH API 路径（dsh.py → start_run）最终交给 harness 的
  prompt 均包含 Bootstrap（测试覆盖两条路径）；
- `flux_context` 返回与 §4.1 字段一致；
- `make verify` 全绿（ruff + 格式 + OpenAPI + 迁移往返 + pytest）。

### 批次 ②：三层记忆（第一批验收后开工）

契约落地为存储 + 最小读写面；Project Memory 基于 `project_brain` 演进；隐私红线测试。

**验收**：跨 Workspace 仅 User Memory 可见；密钥样本被拒收；容量 / TTL 生效；`make verify` 绿。

### 批次 ③：扫描 + 双协议（随后）

Scanner → Flux 标准转换（Agent / Skill / Connector）；重复项不静默覆盖；轻量安全检查
deny-by-default。

**验收**：扫描产物为合法 Flux 标准对象；重复导入有明确提示路径；可疑样本被拦截；`make verify` 绿。

## 6. 纪律与证据

- 每批完成必须有对照证据（测试 + `make verify` 输出），不口头交付；
- 正式文档统一 `doc/`；
- benchmark 侧 Bootstrap 副本在批次①完成后标注「来源：协议真源」，待平台产物可消费时移除。
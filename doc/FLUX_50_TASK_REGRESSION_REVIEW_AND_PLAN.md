# Flux 50 任务回归基准 —— 评审与落地计划

> 本文是对 `doc/FLUX_50_TASK_REGRESSION_BENCHMARK.md`（commit `3306874`）的**评审结论与落地安排**。
> 采取「参考而非照搬」：主干沿用其完整链路（§3）与失败分类（§5 / §10），但按 Flux 当前
> 代码现状逐项标出**已有 / 待建**，并给出分批执行顺序——文档里写成「已有」但 backend
> 实际不存在的部分，一律按**待建**处理，不直接执行。

对应上一步已交付：Agent Runtime 最小闭环 P0-1~7（commit `6a37d8a`）。

## 1. 评审结论

- 方向正确：把 50 个真实任务固定为长期回归基准（§14），并明确「先证明问题属于哪一层」
  （§13），比单纯看成功率更有价值。
- 其中 **§7 Runtime 专项 / §8 Cancel·Timeout / §9 重启恢复** 三节，与刚交付的 P0-1~7 直接
  对应，**当前代码已具备、可立即实测**，无需新增功能。
- 但文档 §2.3 / §3 / §5 / §10 引用了若干 backend 里**并不存在**的能力，不能整篇照执行；
  需先补齐（见 §3 差距清单）或显式降级。

## 2. 对齐项（已有，可直接验证）

| 文档要求 | 当前实现 | 落点 |
|---|---|---|
| §7.1 Agent Discovery | `flux agents scan` / `flux agents list` | `backend/flux/cli/main.py`、`core/agent_runtime/installation.py` |
| §7.2 Agent Connect 状态机单向收敛 | `NOT_INSTALLED→DISCOVERED→VERIFIED→CONNECTED→READY`，重复扫描不倒退 | `core/agent_runtime/installation.py` `can_transition` |
| §7.3 Runtime Identity（7 个 `FLUX_*`） | 起 Run 时注入 | `core/agent_runtime/dsh_client.py` `_agent_env` |
| §7.4 `flux_context`（Runtime/Run/Task/Workspace/Agent/Policy/Capabilities） | 返回上述六类字段 + `protocol` 回执 | `core/mcp/tools/runtime_tools.py` |
| §8 startup / idle / hard timeout + cancel 进程树清理 | `RunSupervisor` 三级超时；SIGTERM→宽限→SIGKILL；进程树未清不伪装 CANCELLED | `core/agent_runtime/supervisor.py` |
| §9 重启恢复 / 孤儿 Run 对账 | 重启接管遗留 Run 归 `INTERRUPTED`，owner 隔离不误伤 | `core/agent_runtime/supervisor.py`、`task_engine/dsh_bridge.py` |
| §11 指标口径（接入率/产出率/Apply 率/Test 率/耗时） | 除 `failure_category` 外，其余可从现有 `tasks` / `agent_runs` / `virtual_changes` 表统计 | 现有模型即可 |

## 3. 差距清单（待建 / 待确认，先不动手）

| 编号 | 差距 | 事实 | 影响 |
|---|---|---|---|
| D-G1 | `failure_category` 不存在 | backend 全仓 grep 无匹配；`DshRunStatus` 只有 `completed/failed/timeout/cancelling/cancelled/interrupted` 等状态，无失败分类枚举 | §2.3 记录字段、§5 / §10 失败分类无落点 |
| D-G2 | `base_revision` / Base Revision Check 未实现 | 无 `base_revision` 字段与校验逻辑 | §3 链路中的 `Base Revision Check`、§12 第 7 条当前无法满足 |
| D-G3 | `benchmark_run_id` 及结果存储不存在 | 无记录 schema、无对比表落点 | §2.3 / §4 无法产出可复现结果 |
| D-G4 | 结果状态词表口径未定 | 文档 §10 用 `SUCCESS`，代码用 `COMPLETED`；且 `TIMEOUT`/`CANCELLED` 是 Run 状态，而 `timeout_kind`/`finish_reason` 是另一维度 | 统计口径会歧义，需先定义映射 |
| D-G5 | 50 个任务 Prompt 真源不明 | 本文未附；`docs/PERSONAL_MVP_TASK_BENCHMARK.md` 与 `bench-flux` 工程都涉及 | 重跑前必须锁定唯一真源 |
| D-G6 | 新文档落在 `doc/`（单数） | 与仓库 `d15db8c` 统一到 `docs/` 的约定冲突（上一轮 FINAL_PLAN 已挪过一次） | 目录不一致 |
| D-G7 | 全量重跑的前置凭证 | 本机 `opencode auth status = missing`；`codex` 已登录 | 50 个真实任务依赖真实模型 / Agent 可用 |

## 4. 分批执行计划

```text
B0 文档纠偏（纯文档，可立即）
      ↓
B1 Runtime 专项实测（无需新代码，可立即）
      ↓
B2 补齐基准记录能力（需开发：failure_category + benchmark 记录）
      ↓
B3 50 任务全量重跑（依赖 B2 与真实凭证）
```

### B0 文档纠偏（纯文档）

- 目录约定已由仓库更新为正式文档放 `doc/`（单数，见 EXECUTION_STRATEGY §2.1），
  本文档与 50 任务基准文档现同置 `doc/`，不再往 `docs/` 迁移。
- 在 §3 / §5 / §12 中把 D-G1~D-G4 的「待建」显式标注，避免后续被当成已有能力。

### B1 Runtime 专项实测（本轮可做，不新增功能）

按 §7 / §8 / §9 逐项跑，产出「判定 + 证据」表：

- §7.1 / §7.2：`flux agents scan|list|connect`（含重复扫描不倒退）
- §7.3：真实起 Run 后校验 7 个 `FLUX_*` 是否注入到 Agent 进程环境
- §7.4：真实 Agent 调 `flux_context`，确认返回真实数据而非 Mock，且与 `context.get` 职责不混淆
- §8：startup / idle / hard 三种 timeout 与 cancel 的进程树清理证据
- §9：运行中强停 Flux → 重启，确认无永久 `RUNNING` / `CANCELLING`、无孤儿进程、状态可解释

### B2 补齐基准记录能力（需开发）

- D-G1：新增失败分类枚举（建议直接采用 §5 的 7 类：Agent / Platform / External /
  Validation / Apply / Test / User-Task），并落到 Run 终态（字段或事件）。
- D-G4：定义输出口径映射——`final_status` 取 §10 词表，`timeout_kind` / `finish_reason`
  作为细分原因，二者合成 `failure_category`。
- D-G3：先落 CSV（低风险、可立即统计），结果稳定后再考虑入库。

### B3 50 任务全量重跑（依赖 B2 与凭证）

- 锁定 D-G5 真源，冻结 D-G7 凭证与模型版本，按 §2.2 记录全部固定条件。
- 完整重跑 50 个任务，按 §13 顺序处理：分类 → 找真正 Flux Bug → 修复 → 相关任务回归。

## 5. 各批次验收标准

| 批次 | 验收标准 |
|---|---|
| B0 | 正式文档位于 `doc/`（单数，EXECUTION_STRATEGY §2.1）；待建项在文中显式标注 |
| B1 | §12 第 1、3、4、5、8、9 条逐条给出「通过/不通过 + 证据」 |
| B2 | `failure_category` 有枚举、可通过代码/迁移取值；benchmark 结果可产出对比表 |
| B3 | §12 全部 10 条判定完成；输出 Baseline vs Current 对比（§4） |

## 6. 待确认（需拍板后再进入 B0 / B2）

1. ~~是否允许把 `doc/` 下的新文档移入 `docs/`~~ → 已定：仓库统一到 `doc/`（单数），`docs/` 废弃。
2. D-G1 失败分类是否直接采用 §5 的 7 类枚举。
3. benchmark 结果先落 CSV 还是直接入库。
4. D-G5 50 个任务真源：以 `docs/PERSONAL_MVP_TASK_BENCHMARK.md` 还是 `bench-flux` 工程为准。
5. 本轮是否先跑 B1（推荐：B1 与 B2 可并行，B1 不阻塞）。

## 7. 明确不做

- 不在文档纠偏前改动任何 Runtime / Apply 代码。
- 不为「用户自己启动的 Agent」注入平台引导（沿用 PHASE1_PLAN D1）。
- 不绕过 Policy 直接落盘，不为凑成功率放宽 §12 判定。

## 8. ④ 静态核查结论（OpenCode 托管链路）

针对收口方案 §5「50 任务里 9 个无 Proposal」的归因，对旧跑的 OpenCode 链路做了静态核查，
结论如下（每条都可回指到代码/产物，不含推测）：

1. **Flux 侧没有 CLI Agent 托管启动路径。** `GenericCliAdapter.build_run_argv()` 在 backend
   内没有任何生产调用方（仅测试引用）；当前产品里真正拉起 Agent 的只有 DSH harness 一条路径
   （`core/agent_runtime/dsh_client.py` → `_agent_env` / `build_runtime_env`）。因此旧 50 任务
   的 OpenCode **不是**由 Flux Runtime 托管的。
2. **旧跑的 OpenCode 由 executor 自己拉起。** `n11_bench.py` 直接 `opencode run`，只设置了
   `OPENCODE_CONFIG` / `ARK_API_KEY` / `OPENCODE_DISABLE_*`，**没有**注入任何 `FLUX_*`
   （也就没有 Runtime Identity、没有 Bootstrap）。所以那 9 个任务的失败不能归因到
   Flux Runtime Bootstrap 缺失——当时根本没走 Flux 托管。
3. **`proposal_required` 未由平台传递。** 平台侧没有把它下发给 Agent，旧跑全靠 executor 的
   prompt 约定 + OpenCode 配置里的 `permission.edit/write=deny` 兜底。约束是「软提示 + 硬拒绝」，
   不是产品级强制。
4. **MCP 接入本身是通的。** `bench/opencode_bench.json` 里配置了 remote MCP 与
   `Authorization: Bearer`；同一轮里 25 个 OpenCode 任务成功提交并落盘，证明「接入」这一步
   没有断。断点在「模型这一轮没产出 Proposal」，不在「连不上 MCP」。
5. **工具名硬编码，两套不一致。** executor 里 OpenCode 侧用 `flux_proposal_create`，Codex 侧用
   `mcp__flux__proposal_create`。这是 executor 的硬编码，不是 MCP 注册表的问题（注册表真源是
   `ALL_TOOLS`）。属于技术债，但**不是**那 9 个失败的直接原因。
6. **（已被 §9 实证推翻，保留原文作对照）9 个无 Proposal 更可能是模型行为，而非未接 MCP。**
   原文判断：同一模型在 25 个任务上能产出 Proposal、在另 9 个任务上两轮都没产出，差异来自任务
   难度/指令/模型本轮表现。→ 重跑证据显示真实原因是**执行器判定缺陷 + 调用体积超限**，
   见 §9；本条不成立。

**落地动作（本轮已做）**：⑤ 最小 Runtime Bootstrap 已落到 `core/agent_runtime/protocol.py`
（`RUNTIME_BOOTSTRAP` / `compose_instruction` / `build_runtime_env`），`dsh_client` 与
`GenericCliAdapter` 均改为委托该真源；`n11_bench.py` 同步 prepend 同一段 Bootstrap（保持与产品
真源一致），以便观察「补了引导后 9 个任务是否仍无 Proposal」。

## 9. 重跑实证：结论 6 被推翻，真实原因在执行器（2026-10-04）

9 个任务重跑（opencode，900s/轮）后，日志与库内证据**推翻了 §8 结论 6 的「模型行为」假设**。
真实原因有三条，全部是执行器/调用形状层面的，不是「模型没产出」：

1. **提案落库但归属丢失，被误判为「未产出」（任务 9，实证）。**
   模型把 `task_id`/`project_id` 写进了 payload JSON 字符串内部，而 MCP 工具只读**顶层**参数
   （`write_tools.py` 的 `reject_unknown(params, {"payload","task_id","project_id","agent_id"})`）。
   4 个 change 正常落库（bench.db `virtual_changes` rowid 164–167，同一 group，agent_source 为
   令牌身份），但 `task_id=NULL`；执行器按 `task_id` 查询 → 0 行 → 记「两轮会话均未产出提案」。
   **这是执行器判定缺陷**——旧跑还有 6 个同类无归属 change（rowid 149/151–155）可佐证。
2. **单次调用的完整文件内容超过模型输出上限，JSON 被截断（任务 14/24，实证）。**
   指令要求「一次性提交全部改动 + 每个 change 写完整文件内容」，而 bench 仓库 `SoloPage.tsx`
   单文件 79KB（中文注释经 JSON 转义后更大），4 文件合计约 111–117KB，超过约 110KB 输出上限 →
   `Invalid Tool / JSON Parse error: Unterminated string` → 一次都没提交成功。任务 9 能成，
   是因为它 4 个文件合计约 82KB，刚好塞得下——**这解释了「哪些任务失败」的分布**。
3. **执行器止损阈值的前提被证伪（任务 28/30/35/47/48/50 未跑）。**
   「连续 3 个任务无产出 = 未接入平台」的前提不成立：MCP 链路是通的（任务 9 落库为证）。
   阈值 3 在这批高难题上必然触发，整轮被中止。

**已做的修正（均在执行器 `/opt/flux/e2e/n11_bench.py`，非产品代码）：**

- `task_changes()` 增加 **rowid 水位线兜底**：本次会话新增的 `task_id IS NULL` 提案也计入本任务
  产出（`mark=0` 时保持旧行为），并补齐详情字段；已用真实数据验证可完整捞回 4 条；
- prompt 给出**两级参数的确切调用形状**（顶层 `task_id`/`project_id`；payload 只放
  summary/changes），并允许「单次内容过大时按文件拆成多次调用」（原指令明确禁止多次调用，
  正是截断的直接诱因）；
- 止损阈值 `OPENCODE_DEAD_TASKS` 3 → 8，并注明前提已被证伪。

**修订后的结论**：9 个任务「无 Proposal」既不是模型拒绝产出，也不是 MCP 未接入。两类已定位为
执行器缺陷（归属判定、调用形状与体积约束），一类是输出上限的物理限制。产品侧（Flux）无需为
此改动——工具契约（顶层参数、`reject_unknown` 严格校验）保持原样；改的是 benchmark 执行器的
调用引导与判定口径。

### 9.1 重跑结果（2026-10-04 17:09 收口）

9 个任务：**6 PASS + 3 门禁红灯（已回滚），0 无提案**。

| 任务 | 旧结果 | 新结果 | 说明 |
|---|---|---|---|
| 9 / 14 / 24 | 无提案 | **PASS** | 正常产出 → 落盘 → 测试 → 提交 |
| 35 / 48 | 无提案 | **PASS** | 首轮无产出，重试轮成功（多次调用分批提交） |
| 50 | 无提案 | **PASS** | 一次通过 |
| 28 | 无提案 | 门禁红灯 | agent 误写 `flux.Core`（大小写）→ conftest 导入失败 |
| 30 | 无提案 | 门禁红灯 | 改动致 `test_blocked_task_cannot_start` 失败 |
| 47 | 无提案 | 门禁红灯 | 改动破坏 `test_workspace_apply_batch_has_no_partial_apply`（P0-03 原子批量） |

归属核验：本次 9 个任务的提案全部带 `task_id` 正确归属，新增无归属 change = 0（水位线兜底未被触发，
说明「顶层参数」的调用形状修正是对症的；兜底仅作为防御保留）。

**判定修正的验证（异常任务 38/39/40/41 重跑，全部 PASS）：**

- 38：`active_only=False` 修好后 → 拒绝 http=200、状态 `['rejected']`、磁盘未变、再 apply 409、仍 rejected；
- 39 / 40：预检冲突 → 409 + accepted；40 另验「无半应用」（占位目录原样保留）；
- 41：500 + failed + 回滚 + `apply_error` 明确（「Apply 后测试未通过（exit=1）」）。
  修正点：终态 failed 要能被读到；`apply_error` 记录在变更上（500 响应体不带），须从变更记录读。

**全量重算：40/50 PASS（80%）**，较旧跑 31/50（62%）提升 9 个。剩余 10 个失败全部有明确归因：
9 个门禁红灯（agent 改动真有问题，平台正确拦截并回滚）+ 1 个提案含范围外文件；**零「无提案」、
零不可解释**。汇总见 `bench/summary.md`，运行日志 `bench/rerun9b.log`。

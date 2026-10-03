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

- 把 `doc/FLUX_50_TASK_REGRESSION_BENCHMARK.md` 移入 `docs/`，删除空的 `doc/`。
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
| B0 | 文件位于 `docs/`；`doc/` 已删除；待建项在文中显式标注 |
| B1 | §12 第 1、3、4、5、8、9 条逐条给出「通过/不通过 + 证据」 |
| B2 | `failure_category` 有枚举、可通过代码/迁移取值；benchmark 结果可产出对比表 |
| B3 | §12 全部 10 条判定完成；输出 Baseline vs Current 对比（§4） |

## 6. 待确认（需拍板后再进入 B0 / B2）

1. 是否允许把 `doc/` 下的新文档移入 `docs/`（与上轮 FINAL_PLAN 同样处理）。
2. D-G1 失败分类是否直接采用 §5 的 7 类枚举。
3. benchmark 结果先落 CSV 还是直接入库。
4. D-G5 50 个任务真源：以 `docs/PERSONAL_MVP_TASK_BENCHMARK.md` 还是 `bench-flux` 工程为准。
5. 本轮是否先跑 B1（推荐：B1 与 B2 可并行，B1 不阻塞）。

## 7. 明确不做

- 不在文档纠偏前改动任何 Runtime / Apply 代码。
- 不为「用户自己启动的 Agent」注入平台引导（沿用 PHASE1_PLAN D1）。
- 不绕过 Policy 直接落盘，不为凑成功率放宽 §12 判定。

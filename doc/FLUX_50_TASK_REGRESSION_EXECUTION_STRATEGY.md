# Flux 50 任务回归执行策略

> 状态：Final Recommendation
>
> 基于当前代码评审与 50 Task Benchmark 现状制定。
>
> 目标：先验证当前 Agent Runtime，再建立最小可复现记录能力，最后重新运行 50 个真实任务；避免为了 Benchmark 过度扩张产品架构。

## 1. 最终结论

当前不建议直接重跑 50 个任务，也不建议继续大规模增加产品功能。

正确顺序：

```text
当前版本
  ↓
B1 Runtime 真实专项测试
  ↓
修复真实 Runtime 问题
  ↓
B2 最小 Benchmark 记录能力
  ↓
B3 50 个任务完整重跑
  ↓
失败分类与 Baseline 对比
  ↓
相关任务回归
  ↓
再决定下一阶段架构能力
```

核心原则：

> 先证明问题属于哪一层，再决定是否改代码；不要为了让测试方案看起来完整而提前实现大量暂时用不到的功能。

---

## 2. 文档与测试真源规则

### 2.1 文档目录

Flux 当前约定：**后续正式项目文档统一放在 `/doc`。**

本策略文档的正式位置为：

```text
doc/FLUX_50_TASK_REGRESSION_EXECUTION_STRATEGY.md
```

`docs/FLUX_50_TASK_REGRESSION_EXECUTION_STRATEGY.md` 为此前错误提交的位置，已经废弃，不再作为规范路径。

仓库中历史 `docs/` 文档不因为本次修复而全部迁移；后续新增或修改的正式文档统一进入 `doc/`，避免继续产生新的双目录问题。

### 2.2 50 Task Prompt 真源

在 B3 开始前，必须明确并冻结 **50 个任务的唯一 Prompt 真源**。

本策略文档和 Benchmark 方法文档只能描述规则，**不能同时成为一份独立的 Prompt 副本**。

执行前必须在一次冻结记录中明确：

```text
prompt_source_type
prompt_source_path_or_repo
prompt_source_revision
prompt_manifest_hash（如可获得）
```

如果 50 个任务实际维护在独立的 `bench-flux` 工程，则应以该工程中明确的任务定义文件为唯一来源，并固定到具体 commit；Flux 仓库只保存来源声明与校验信息。

如果最终确认任务定义就在 Flux 仓库，则必须指定具体文件路径，而不能只写“以 Benchmark 文档为准”。

**在 Prompt 真源没有被明确、固定之前，不开始正式的 50 Task 重跑。**

这样才能保证：

```text
Baseline Prompt
        ==
Current Prompt
```

否则第二轮结果没有严格可比性。

---

## 3. 为什么不立即重跑 50 个任务

当前代码已经具备 Agent Runtime P0 的主要执行能力，但之前 Benchmark 方案中仍存在部分能力与实际 backend 不完全一致：

- `failure_category` 尚不存在；
- `base_revision` / Base Revision Check 尚不存在；
- `benchmark_run_id` 及正式结果存储尚不存在；
- `SUCCESS` 与代码现有 `COMPLETED` 的状态口径需要统一；
- 50 个任务的唯一 Prompt 真源需要冻结；
- 当前真实 Agent 凭证条件需要确认。

因此，如果现在直接跑 50 个任务，会出现“任务确实跑完了，但结果难以可靠比较”的问题。

这不意味着所有缺失能力都必须在 Benchmark 前实现。

---

## 4. B1：Runtime 专项实测

B1 不新增功能，直接使用当前实现验证真实 Agent。

### 4.1 Agent Discovery

执行：

```bash
flux agents scan
flux agents list
```

确认：

- Agent 是否能被发现；
- executable 路径是否正确；
- version 是否正确；
- auth 状态是否正确；
- capabilities 是否正确。

### 4.2 Agent Connect

执行：

```bash
flux agents connect <agent>
```

验证状态：

```text
NOT_INSTALLED
    ↓
DISCOVERED
    ↓
VERIFIED
    ↓
CONNECTED
    ↓
READY
```

重复执行 scan / list 后，READY 不应无故退回较低状态。

### 4.3 Runtime Identity

真实启动 Agent 后检查：

```text
FLUX_RUNTIME
FLUX_VERSION
FLUX_RUN_ID
FLUX_TASK_ID
FLUX_WORKSPACE
FLUX_AGENT_ID
FLUX_MCP_ENDPOINT
```

这些变量只用于身份和运行上下文，不是权限凭证。

### 4.4 `flux_context`

让真实 Agent 调用 `flux_context`，验证返回的是当前真实：

- Flux Runtime；
- Run；
- Task；
- Workspace；
- Agent；
- Policy；
- Capabilities。

同时确认它与项目上下文工具的职责不同：

```text
flux_context
= 我在哪里、我是谁、当前 Run 是什么、我有什么能力

context.get
= 项目是什么、代码和文档上下文是什么
```

### 4.5 Timeout / Cancel

分别制造：

- startup timeout；
- idle timeout；
- hard timeout；
- manual cancel。

验证：

```text
终态正确
+
进程树正确清理
+
不存在永久 RUNNING / CANCELLING
```

### 4.6 Restart Recovery

Agent 正在运行时停止 Flux，再启动 Flux。

确认：

- 遗留 Run 被正确识别；
- 状态不会永久保持 RUNNING；
- 不会永久保持 CANCELLING；
- 不会留下孤儿 Agent；
- 不会误伤其它 owner 的 Run；
- 最终状态可解释。

---

## 5. B1 的输出格式

不要只记录“通过”。

每项至少记录：

| 项目 | 结果 | 证据 |
|---|---|---|
| Discovery | PASS/FAIL | CLI 输出 |
| Connect | PASS/FAIL | installation 状态 |
| Runtime Identity | PASS/FAIL | Agent 环境 |
| flux_context | PASS/FAIL | Tool 返回 |
| Startup Timeout | PASS/FAIL | Run + 进程证据 |
| Idle Timeout | PASS/FAIL | Run + 进程证据 |
| Hard Timeout | PASS/FAIL | Run + 进程证据 |
| Cancel | PASS/FAIL | Run + 进程树 |
| Restart Recovery | PASS/FAIL | Run 状态 + 进程状态 |

B1 的目的不是获得漂亮的数据，而是确认 Runtime 的真实行为。

---

## 6. B2：最小 Benchmark 记录能力

B2 不应该发展成一个复杂 Benchmark 平台。

当前 Personal MVP 阶段优先采用轻量记录，例如 CSV / JSONL。

至少记录：

```text
task_id
benchmark_run_id
flux_commit
agent
agent_version
model
started_at
finished_at
duration
final_status
failure_category
failure_reason
proposal_created
proposal_applied
test_passed
git_result
```

如果现有系统已经能从 `tasks` / `agent_runs` / `virtual_changes` 得到某字段，不要重复设计数据库字段；优先导出统计结果。

---

## 7. 状态口径统一

不要直接把所有状态硬改成新的词表。

建议保留 Runtime 内部已有状态，同时在 Benchmark 输出层建立统一口径：

```text
Benchmark final_status
├── SUCCESS
├── FAILED
├── TIMEOUT
├── CANCELLED
└── INTERRUPTED
```

内部 Runtime 状态继续保留实际生命周期语义，例如：

```text
COMPLETED
FAILED
TIMEOUT
CANCELLING
CANCELLED
INTERRUPTED
```

同时使用：

```text
timeout_kind
finish_reason
failure_category
```

表达更细的原因。

不要让“最终状态”和“失败原因”变成同一个字段。

---

## 8. `failure_category` 的建议口径

Benchmark 层建议使用：

```text
AGENT
PLATFORM
EXTERNAL
VALIDATION
APPLY
TEST
USER_TASK
```

例如：

```text
final_status = FAILED
failure_category = AGENT
failure_reason = no_proposal
```

或者：

```text
final_status = FAILED
failure_category = PLATFORM
failure_reason = mcp_timeout
```

这样可以区分：

> Agent 没完成任务

和：

> Flux 根本没把任务正确执行完。

这对 50 个任务的 Benchmark 非常重要。

---

## 9. Base Revision 不作为本轮 Benchmark 的硬阻塞项

`base_revision` / Base Revision Check 是重要的 Flux 并发安全能力，但不应该为了它无限推迟 Benchmark。

建议拆为后续独立阶段：

```text
B3 50 Task Benchmark
      ↓
B4 Proposal / Apply 并发安全
      ↓
Base Revision
      ↓
Conflict Detection
```

前提是：当前 Benchmark 测试环境中每个任务使用独立、可控的 Workspace，不存在多个 Agent 并发修改同一 Workspace 的场景。

如果未来测试目标包含多 Agent 并发，那么 Base Revision 必须升级为 P0 阻塞项。

---

## 10. B3：50 个任务完整重跑

B3 开始前必须冻结：

- Flux commit；
- Agent 版本；
- 模型版本；
- 模型参数；
- **50 Task Prompt 唯一真源及其 revision / hash**；
- MCP / Tool 配置；
- Timeout 配置；
- Workspace 初始状态；
- Git 初始 revision；
- Benchmark Run ID。

然后完整运行 50 个任务。

不能因为某任务以前成功过就跳过。

---

## 11. 50 Task 结果重点

本轮不只看成功率。

至少统计：

### Agent 启动成功率

```text
成功启动 Agent / 尝试启动 Agent
```

### Agent 有效产出率

```text
有效 Proposal / 成功启动 Agent
```

### Apply 成功率

```text
Apply 成功 / Proposal 创建成功
```

### Test 通过率

```text
Test 通过 / Apply 成功
```

### End-to-End 成功率

```text
最终成功 / 总任务
```

### Timeout Rate

```text
Timeout / 总任务
```

### Platform Failure Rate

```text
Platform Failure / 总任务
```

### Duration

记录平均耗时与 P95 耗时。

---

## 12. 第一轮与第二轮比较

最终报告使用：

```text
Baseline
vs
Current
```

重点回答三个问题：

1. Runtime 优化是否降低了平台自身失败？
2. Agent 是否更稳定地产生有效结果？
3. 是否引入了新的回归或性能退化？

---

## 13. 当前 Agent 凭证问题

如果某个 Agent 未登录，不应该把认证问题算作 Runtime 或 Agent 能力失败。

例如：

```text
OpenCode auth missing
```

应先解决环境问题，或者把该测试标记为：

```text
ENVIRONMENT_BLOCKED
```

不能把它混入正式 50 Task Benchmark 的失败率。

正式 Benchmark 必须尽量保证真实模型 / Agent 凭证可用。

---

## 14. 50 Task 后的修复策略

如果发现失败：

```text
任务失败
 ↓
定位阶段
 ↓
分类
 ↓
判断是否 Flux Bug
```

只有确定是 Flux Bug 才进入平台代码修复。

然后：

```text
修复
 ↓
相关失败任务回归
 ↓
确认没有扩大影响
 ↓
必要时重新完整跑 50 个
```

不要因为某个 Agent 输出异常就直接重构 Runtime。

---

## 15. 下一阶段开发优先级

50 Task Benchmark 完成后，根据实际数据决定。

建议优先级：

### P0

真实 Runtime 稳定性问题。

### P0/P1

Proposal / Apply 安全性，包括：

- `base_revision`；
- Conflict Detection；
- Atomic Apply；
- Rollback。

### P1

Server CLI 完善、Agent Adapter 稳定性、观测能力。

### 暂缓

- Agent Marketplace
- Cloud Agent
- Team / Enterprise
- 复杂 TUI
- 更多产品页面
- 非核心 Agent 生态扩展

---

## 16. 最终执行路线

```text
                    当前代码
                       │
                       ↓
             ┌──────────────────┐
             │ B1 Runtime E2E   │
             └────────┬─────────┘
                      ↓
               发现真实问题
                      ↓
                 修复必要 Bug
                      ↓
             ┌──────────────────┐
             │ B2 最小记录能力  │
             └────────┬─────────┘
                      ↓
              冻结 Prompt 真源
                      ↓
             ┌──────────────────┐
             │ B3 50 Task 全量  │
             └────────┬─────────┘
                      ↓
              Baseline vs Current
                      ↓
                失败分类分析
                      ↓
               相关任务回归
                      ↓
             ┌──────────────────┐
             │ 下一阶段架构决策  │
             └──────────────────┘
```

## 17. 最终判断

当前 Flux 不需要继续盲目增加功能。

现在最重要的是证明已经实现的 Agent Runtime 是否真的可靠。

因此：

> **先做 B1，随后做最小 B2，明确并冻结 Prompt 真源，再跑完整 50 Task。**

同时不要把 `base_revision`、Marketplace、Cloud Agent、Team 等下一阶段能力提前塞进当前 Benchmark 阻塞链路。

50 个任务跑完之后，再用真实数据决定下一步，而不是凭感觉继续开发。

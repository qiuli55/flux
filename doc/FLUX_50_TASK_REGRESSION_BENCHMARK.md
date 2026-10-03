# Flux 50 任务回归基准测试方案

> 状态：Ready for execution
>
> 目标：对 Agent Runtime、CLI Adapter、Run Supervisor、Proposal / Apply / Test / Git 等核心路径完成优化后，重新完整运行此前的 50 个真实任务，并将结果建立为 Flux 的第一套长期回归基准集。

## 1. 为什么必须重跑 50 个任务

此前 50 个任务的结果只代表优化前版本的行为。

当前版本已经对以下关键路径进行了修改：

- Agent Runtime
- CLI Agent 接入
- Agent Adapter
- Run 生命周期
- startup / idle / hard timeout
- cancellation
- 进程树清理
- Run reconciliation / restart recovery
- Runtime Identity
- `flux_context`
- Proposal / Apply 相关链路

因此旧结果不能直接证明当前版本没有回归。

本轮测试的目标不是单纯追求“成功率变高”，而是验证：

> 当前 Flux 能否稳定地把真实外部 Agent 从启动、执行、Proposal、Apply 一直带到 Test / Git 完成，并在异常情况下正确收尾。

---

## 2. 测试原则

### 2.1 完整重跑

50 个任务全部重新运行，不因为上一轮已经成功而跳过。

### 2.2 固定测试条件

必须记录：

- Flux commit SHA
- Agent 名称与版本
- 模型名称与版本
- 模型参数
- 50 个任务原始 Prompt
- MCP / Tool 配置
- timeout 配置
- Workspace / Git 初始状态
- 测试机器资源

如果条件发生变化，应标记为新的 Benchmark Run，而不是覆盖旧结果。

### 2.3 结果可复现

每个任务至少记录：

```text
benchmark_run_id
task_id
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

---

## 3. 推荐测试链路

每个任务按照完整链路执行：

```text
Task
 ↓
Agent Discovery / Adapter
 ↓
Agent Start
 ↓
Runtime Identity
 ↓
MCP initialize
 ↓
flux_context
 ↓
Agent Execution
 ↓
Proposal
 ↓
Policy Gate
 ↓
Base Revision Check
 ↓
Apply
 ↓
Test
 ↓
Git
 ↓
Run Complete
```

如果任务在中间阶段失败，必须记录失败发生在哪一层，而不是只记录最终 `FAILED`。

---

## 4. 第一轮与第二轮对比

上一轮结果应作为 Baseline 保存，不修改原始数据。

建议建立：

| 指标 | Baseline | Current | 变化 |
|---|---:|---:|---:|
| 总任务 | 50 | 50 | - |
| 成功 | 记录旧值 | ? | ? |
| 真实失败 | 记录旧值 | ? | ? |
| Agent 无产出 | 记录旧值 | ? | ? |
| Timeout | 记录旧值 | ? | ? |
| Proposal 创建失败 | 记录旧值 | ? | ? |
| Apply 失败 | 记录旧值 | ? | ? |
| Test 失败 | 记录旧值 | ? | ? |
| Git 失败 | 记录旧值 | ? | ? |
| 平均耗时 | 记录旧值 | ? | ? |
| P95 耗时 | 记录旧值 | ? | ? |

不要只比较“成功任务数量”。

---

## 5. 失败分类

必须至少区分：

### Agent Failure

例如：

- Agent 无响应
- Agent 崩溃
- Agent 没有产生有效 Proposal
- Agent 输出协议异常

### Platform Failure

例如：

- Flux Runtime Bug
- Run 状态机错误
- Supervisor 错误
- MCP 错误
- 数据库错误
- Event Bus 错误

### External Failure

例如：

- API Rate Limit
- 外部服务不可用
- 网络异常
- Provider Error

### Validation Failure

例如：

- Proposal Schema 不合法
- Workspace 路径非法
- Base Revision 不匹配

### Apply Failure

例如：

- 文件写入失败
- Atomic Apply 失败
- Rollback 失败

### Test Failure

Agent 修改成功，但项目测试失败。

### User / Task Failure

任务本身描述不完整、要求冲突或输入存在问题。

---

## 6. 特别关注上一轮异常任务

上一轮 50 个任务中的异常任务必须重点复核。

尤其是此前已经观察到的：

- Agent 未产出 Proposal 的任务
- Flux / 平台自身失败的任务
- Timeout 相关任务
- Apply / Test 相关失败

本轮不能简单把这些任务标记为“又失败了”，而应该判断：

```text
旧问题
 ↓
当前版本
 ↓
是否已经修复
 ↓
是否产生新的失败模式
```

例如：

```text
Agent 无 Proposal
```

需要进一步确认：

```text
Agent 真没产出？
还是 Flux 没收到事件？
还是 Parser 丢事件？
还是 Proposal Tool 调用失败？
还是 Supervisor 提前 Timeout？
```

---

## 7. 本轮特别验证 Agent Runtime

50 个真实任务之外，建议额外执行 Runtime 专项测试。

### 7.1 Agent Discovery

```bash
flux agents scan
flux agents list
```

确认：

- CLI 是否正确发现
- 版本是否正确
- PATH 是否正确
- Auth 状态是否正确
- Capability 是否正确

### 7.2 Agent Connect

```bash
flux agents connect <agent>
```

确认状态不会出现错误回退：

```text
DISCOVERED
 → VERIFIED
 → CONNECTED
 → READY
```

重复扫描后 READY 不应无故降级。

### 7.3 Runtime Identity

确认真实 Agent 能获得：

```text
FLUX_RUNTIME
FLUX_VERSION
FLUX_RUN_ID
FLUX_TASK_ID
FLUX_WORKSPACE
FLUX_AGENT_ID
FLUX_MCP_ENDPOINT
```

### 7.4 `flux_context`

确认 Agent 能获取：

- Flux Runtime
- Run
- Task
- Workspace
- Agent
- Policy
- Capabilities

并验证 `flux_context` 与项目 `context.get` 的职责没有混淆。

---

## 8. Cancel / Timeout 专项测试

### Startup Timeout

制造 Agent 启动失败或长时间无法 Ready 的情况。

预期：

```text
STARTING
 ↓
TIMEOUT
 ↓
进程清理
 ↓
终态
```

### Idle Timeout

让 Agent 启动后长时间没有可靠进展。

注意：以下都应该被视为有效进展：

- 状态变化
- Agent 事件
- MCP Tool 调用
- 可靠心跳

不能简单以 stdout 是否增长判断。

### Hard Timeout

让 Agent 持续工作超过最大 Run 时长。

预期：

```text
RUNNING
 ↓
TIMEOUT
 ↓
进程树清理
 ↓
终态
```

### Cancel

执行：

```text
CANCELLING
 ↓
优雅中断
 ↓
SIGTERM
 ↓
宽限期
 ↓
SIGKILL
 ↓
确认整个进程树消失
 ↓
CANCELLED
```

如果进程树无法确认清理，不得伪装成 `CANCELLED`。

---

## 9. Flux 重启恢复专项测试

运行 Agent 时强制停止 Flux。

然后重新启动 Flux。

确认：

- 不存在永久 `RUNNING`
- 不存在永久 `CANCELLING`
- 遗留 Run 能被正确识别
- 不会误伤其它 Flux 实例拥有的 Run
- 不会留下孤儿 Agent 进程
- Run 状态最终可解释

---

## 10. 真实结果判定

不要只使用：

```text
PASS / FAIL
```

建议使用：

```text
SUCCESS
AGENT_FAILURE
PLATFORM_FAILURE
EXTERNAL_FAILURE
VALIDATION_FAILURE
APPLY_FAILURE
TEST_FAILURE
TIMEOUT
CANCELLED
INTERRUPTED
USER_TASK_FAILURE
```

这样可以判断优化究竟改善了什么。

---

## 11. 成功率之外的核心指标

本轮至少统计：

### Agent 接入成功率

```text
成功启动 Agent / 尝试启动 Agent
```

### Agent 有效产出率

```text
产生有效 Proposal / 成功启动 Agent
```

### Proposal Apply 成功率

```text
Apply 成功 / Proposal 创建成功
```

### Test 通过率

```text
Test 通过 / Apply 成功
```

### End-to-End 成功率

```text
最终成功任务 / 总任务
```

### Timeout Rate

```text
Timeout / 总任务
```

### Platform Failure Rate

```text
Platform Failure / 总任务
```

### 平均耗时 / P95 耗时

用于判断 Runtime 优化是否引入明显性能退化。

---

## 12. 回归判定标准

当前版本不能只要求：

> 成功率比上一轮高。

最低要求应该是：

1. 核心功能不能发生明显回归。
2. Platform Failure 不应明显增加。
3. Cancel 后不能残留 Agent 进程。
4. Timeout 后不能残留 Agent 进程。
5. Flux 重启后不能出现永久 RUNNING。
6. Proposal / Apply 不允许绕过 Policy。
7. Base Revision 不匹配时不得静默覆盖。
8. 真实 Agent 能完成 Runtime Handshake。
9. `flux_context` 返回真实而非 Mock 数据。
10. 新增 Runtime 能力不能破坏已有 50 个任务的正常执行。

---

## 13. 测试后的处理顺序

```text
50 个任务完整重跑
        ↓
收集结果
        ↓
分类失败
        ↓
区分 Agent / Platform / External
        ↓
找出真正的 Flux Bug
        ↓
修复
        ↓
运行相关失败任务回归
        ↓
再次跑受影响任务
        ↓
必要时重新跑完整 50 个
```

不要每发现一个 Agent 行为问题就立刻修改大量架构。

先证明问题属于哪一层。

---

## 14. 50 个任务的长期定位

本轮完成后，这 50 个真实任务正式成为：

> **Flux Personal MVP 第一套 Regression Benchmark。**

以后涉及以下模块的重大修改，都应至少运行这套 Benchmark：

- Agent Runtime
- Agent Adapter
- Run Supervisor
- MCP
- Proposal
- Apply
- Workspace
- Test Runner
- Git Integration
- Timeout / Cancellation

这样以后判断“优化有没有变好”就不再依赖主观体验，而有稳定的历史基线。

---

## 15. 当前执行结论

当前阶段暂时不要继续大量增加新产品功能。

推荐顺序：

```text
当前版本
   ↓
真实 Agent Runtime E2E
   ↓
50 任务完整回归
   ↓
失败分类
   ↓
修复真实问题
   ↓
相关任务回归
   ↓
再次确认 Benchmark
   ↓
再进入下一阶段功能开发
```

### 最终目标

不是单纯把：

```text
25 / 50
```

提高到一个更漂亮的数字。

真正目标是证明：

> **Flux 能稳定管理真实外部 Agent，从启动到最终代码变更落地，并且在失败、取消、超时、重启等异常情况下仍然保持正确状态。**

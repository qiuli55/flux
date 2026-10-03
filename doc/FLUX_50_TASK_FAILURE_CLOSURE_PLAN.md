# Flux 50 任务失败收口与修复计划

> 状态：Execution Plan
> 基于 2026-10-04 的 50 Task 收口报告制定。
> 目标：区分真正的 Flux 缺陷、Agent 产出稳定性问题、Benchmark 判据问题；先修确定性问题，再重新跑受影响任务，最后决定是否全量重跑。

## 1. 当前结论

50 个任务已经完整执行，当前结果为 31/50（62%）。但 19 个失败不能全部视为 Flux 产品缺陷：

- 9 个：OpenCode 两轮均未产生 Proposal；属于 Agent 产出稳定性问题，尚不能证明是 Flux Runtime Bug。
- 7 个：Proposal 被 Apply/Test 门禁正确拒绝并回滚；属于 Agent 产出破坏既有测试契约，产品门禁本身正常。
- 3 个：异常任务判据与现行产品契约不一致（38/40/41）。

因此本阶段不做“为了提高 31/50 而放宽门禁”的修改。

## 2. 任务 38：Reject

### 现状

产品实际行为：

```text
Reject → HTTP 200 → DB=rejected → 磁盘不变 → 再 Apply=409
```

Benchmark 使用 `active_only=true` 查询 Proposal，无法读取 `rejected` 终态，因此误判失败。

### 决定

**判定为 Benchmark Bug，不修改 Flux 产品行为。**

Benchmark 查询必须支持终态：

```text
pending
accepted
rejected
applied
failed
expired
```

验收：

- Reject 后能读到 `rejected`；
- 磁盘不变；
- 再次 Apply 返回 409；
- 不允许 rejected → applied。

## 3. 任务 40：Apply 预检失败

### 现状

目标路径是目录而不是普通文件：

```text
Apply → HTTP 409 conflict
status = accepted
磁盘无半应用
```

### 决定

**保留当前产品语义：预检冲突不把 Proposal 标记为 failed。**

原因：

- Apply 尚未开始落盘；
- 问题具有可修复性；
- 用户修正 Workspace 后可以重新 Apply；
- `failed` 应表示 Apply 已进入执行阶段并失败，而不是预检发现当前环境不满足条件。

因此 Benchmark 应改为：

```text
HTTP 409
+
status=accepted
+
无半应用
+
错误原因明确
```

视为 PASS。

## 4. 任务 41：测试失败

### 现状

产品行为：

```text
Apply → Test 失败 → 回滚 → DB=failed → HTTP 500
```

### 决定

**保留 HTTP 500 + `apply_failed` 当前契约。**

不能为了让异常任务返回 200 而改变语义。

真正的成功条件是：

```text
HTTP 非 2xx
+
Proposal=failed
+
apply_error 明确
+
用户文件恢复原状
```

Benchmark 必须按这个契约判定。

## 5. 任务 18 / 29：MCP Tool 扩展导致旧测试契约过窄

失败用例：

```text
tests/test_dsh_client.py::test_generated_patch_token_is_accepted_by_the_real_mcp_endpoint
```

当前测试对 `tools/list` 使用完整数组精确相等：

```text
names == [固定的 5 个工具]
```

这会导致合法新增 MCP Tool 时旧测试必然失败。

### 修复原则

测试应验证 **Flux 必须存在的核心工具**，而不是禁止未来扩展：

```text
required_tools ⊆ advertised_tools
```

核心工具仍必须全部存在：

```text
context.get
flux_context
proposal.create
workspace.diff
workspace.read
```

新增工具不得破坏：

- MCP 鉴权；
- 核心工具存在性；
- `proposal.create`；
- Proposal 不直接落盘。

不要把工具列表冻结成封闭枚举。

## 6. 任务 25 / 26 / 31 / 34：门禁红灯

这些任务属于：

```text
Agent 产生修改
↓
既有测试契约被破坏
↓
Apply/Test Gate 失败
↓
自动回滚
↓
任务 FAIL
```

### 决定

暂时不修改门禁。

尤其不能：

- 跳过失败测试；
- 降低测试标准；
- 自动接受破坏现有契约的 Proposal；
- 为 Benchmark 特判测试文件。

下一步重点是分析 Agent 为什么没有在修改前检查相关测试和调用方。

## 7. 任务 4：范围外文件

任务 4 同时出现：

- 目标功能相关修改；
- 范围外文件修改；
- 最终门禁失败并回滚。

### 当前决定

**暂不直接增加“硬编码允许文件列表”。**

原因：50 个任务不是固定文件级任务；复杂任务本来就可能合理修改多个模块。

下一阶段应建立 Task Scope 的显式能力：

```text
Task
 ├── objective
 ├── workspace
 ├── allowed_paths (optional)
 └── constraints
```

如果任务没有声明 `allowed_paths`，Flux 不应凭猜测阻止 Agent 修改正常项目文件。

如果未来任务明确声明范围，则 Proposal Gate 才能严格执行：

```text
proposal.paths ⊆ task.allowed_paths
```

## 8. 9 个 OpenCode 两轮无 Proposal

失败任务：

```text
9 / 14 / 24 / 28 / 30 / 35 / 47 / 48 / 50
```

共同特征：

```text
OpenCode
+ 首轮无 Proposal
+ 单件事重试仍无 Proposal
```

但其它 25 个 OpenCode 任务成功，说明：

> 当前不能把问题简单归因于 MCP 未接入 Flux。

### 下一步修复

在 Agent Runtime 启动时增加**最小 System Context / Runtime Bootstrap**，不要把完整产品文档塞进 Prompt。

内容只需要告诉 Agent：

```text
You are running inside Flux.

Flux manages task lifecycle, workspace, proposal validation,
approval, apply, test and git.

Use Flux MCP tools to inspect runtime context and capabilities.
When proposal_required is enabled, submit changes through proposal.create.
Do not assume direct workspace writes are the Flux completion path.
```

真实状态仍必须以：

```text
FLUX_*
flux_context
MCP tools/list
```

为准。

### 验收

先只重跑失败的 9 个 OpenCode 任务，不立即重跑全部 50 个。

目标不是保证 9/9 成功，而是确认：

- 是否明显提高 Proposal 产生率；
- 是否仍出现两轮零 Proposal；
- 是否引入新的 Prompt / Runtime 回归。

## 9. 50 Task 的正确修复顺序

```text
① 修 Benchmark 38 / 41 判据
        ↓
② 明确 40 保持 accepted + 409
        ↓
③ 修 MCP Tool 列表测试的“固定数组”问题
        ↓
④ 增加最小 Flux Runtime Bootstrap
        ↓
⑤ 只重跑 9 个 OpenCode 零 Proposal 任务
        ↓
⑥ 重新分析 7 个门禁红灯任务
        ↓
⑦ 必要时修 Agent 产出质量问题
        ↓
⑧ 再决定是否完整重跑 50
```

## 10. 不做的事情

本阶段明确不做：

- 不降低 Policy Gate；
- 不让 Agent 绕过 Proposal；
- 不把 Test Failure 改成 HTTP 200 只为通过 Benchmark；
- 不把所有 Apply 预检失败统一改成 failed；
- 不硬编码 50 个任务的允许文件列表；
- 不因为 9 个 OpenCode 任务失败就重构整个 Agent Runtime；
- 不把 MCP 调用次数不足误判为 MCP 本身故障。

## 11. 收口标准

修复后首先要求：

1. 38 / 41 Benchmark 判定与产品契约一致；
2. 18 / 29 等 MCP Tool 扩展不再被旧的精确列表测试误杀；
3. 40 的 409 + accepted 语义固定并有测试覆盖；
4. 9 个 OpenCode 任务重新执行并获得可解释结果；
5. 门禁仍保持 fail-closed；
6. 不出现任何安全红线回归。

达到以上条件后，再决定是否重新跑完整 50 Task。

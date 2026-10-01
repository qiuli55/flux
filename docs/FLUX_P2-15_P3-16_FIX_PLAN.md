# Flux P2-15 / P3-16 修复方案

## 1. 修复目标

针对当前完整测试发现的两个问题：

- **P2-15：DSH Run 长时间无响应导致任务永久 `running`；取消任务后 DSH 子进程仍存活**
- **P3-16：Agent Registry 的 UUID 与 MCP Token 中的 `agent_id` 使用两套身份体系，来源不同且互不校验**

目标是补齐 Flux Runtime 生命周期管理，并统一 Agent 身份模型，为 DSH、Codex 及后续 Context Engine 提供稳定基础。

---

# 2. P2-15：Run 生命周期与进程清理

## 2.1 当前问题

典型卡死链路：

```text
DSH → Provider 无响应 → 长时间无字节 → Flux Run 持续 running → 无 timeout/heartbeat → 僵尸任务
```

本次测试中 DSH Provider 连接约 7.5 分钟无字节，Run 一直保持 `running`。

Cancel 目前也存在：

```text
用户 Cancel → Flux Run = cancelled → DSH 子进程仍存在
```

会造成资源泄漏、状态不一致，以及 Flux 重启后无法准确恢复。

## 2.2 推荐：统一 RunSupervisor

不要让 `dsh_bridge.py` 独自负责完整生命周期，增加统一的 `RunSupervisor`：

```text
RunSupervisor
├── create / start
├── process tracking
├── heartbeat
├── timeout detection
├── cancel
├── process-tree cleanup
├── state reconciliation
└── recovery after Flux restart
```

### Run 状态机

```text
PENDING
   ↓
STARTING
   ↓
RUNNING
   ├── COMPLETED
   ├── FAILED
   ├── TIMEOUT
   ├── CANCELLING → CANCELLED
   └── INTERRUPTED
```

`CANCELLED` 应表示进程树已经确认清理完成，而不是仅表示收到了取消请求。

## 2.3 三类 Timeout

### startup_timeout
用于 Agent 启动阶段。例如初始可设为 120 秒；超时后进入 `TIMEOUT/FAILED`。具体值通过真实测试调整。

### idle_timeout
用于进程仍存在、但长时间没有有效进展的情况。建议记录：

```text
last_output_at
last_mcp_activity_at
last_state_change_at
```

不能只用 stdout 判断“是否存活”。

### hard_timeout
无论是否有输出，都设置绝对上限。例如初始 1800 秒，后续按任务类型调整。

## 2.4 Heartbeat

不要定义成“stdout 有输出 = alive”。建议记录：

```text
process_started_at
last_output_at
last_mcp_activity_at
last_state_change_at
last_heartbeat_at
```

综合判断进程是否仍有有效进展。

## 2.5 Cancel：清理整个进程树

启动每个 Run 时记录：

```text
run_id
pid
process_group_id
```

取消流程：

```text
cancel(run_id)
      ↓
CANCELLING
      ↓
SIGTERM process group
      ↓
等待 3~5 秒
      ↓
仍存在？
      ↓
SIGKILL process group
      ↓
确认进程树消失
      ↓
CANCELLED
```

如果无法清理，不应伪装成成功取消，应进入 `FAILED/INTERRUPTED` 等明确状态。

## 2.6 Reconciler

增加后台 Run Reconciler，例如每 5~10 秒检查 `starting/running/cancelling`：

```text
数据库状态
    ↓
PID / process group 是否存在
    ↓
heartbeat 是否正常
    ↓
状态是否一致
    ↓
自动修正
```

例如数据库仍是 `running`，但 PID 已消失，则自动改为 `FAILED/INTERRUPTED`。这同时解决此前 TC-502 的 running 僵尸问题。

## 2.7 Flux 重启恢复

Flux 启动时扫描所有非终态 Run：

```text
Flux Startup
   ↓
扫描非终态 Run
   ↓
检查 PID / process group
   ↓
检查 heartbeat
   ↓
Reconcile
   ↓
恢复 / FAILED / INTERRUPTED
```

---

# 3. P2-15 回归测试

### TC-15A：Startup Timeout
Agent 无法正常启动，超过 startup timeout 后不得永久 `running`。

### TC-15B：Idle Timeout
Agent 存活但长期无有效进展，触发 idle timeout 并完成 cleanup。

### TC-15C：Hard Timeout
超过绝对运行上限后进入 TIMEOUT，并清理进程树。

### TC-15D：Cancel Process Tree
取消 DSH 后主进程及所有子进程退出，最终为 CANCELLED。

### TC-15E：Cancel Reconciliation
取消过程中短暂状态不一致时，Reconciler 最终修正，不出现永久 CANCELLING。

### TC-15F：Flux Restart Reconciliation
Flux 重启后能发现遗留 Run，并根据真实进程状态修正数据库状态。

---

# 4. P3-16：统一 Agent Identity

## 4.1 当前问题

现在存在两套身份：

```text
Agent Registry
agent.id = UUID

MCP Token
agent_id = 自由字符串
```

例如 Registry 使用 UUID，而 Token 使用 `codex-minimax`。两者没有可靠映射，会影响权限、Token scope、Proposal attribution、Audit Log、Task ownership 和 Context ownership。

## 4.2 Canonical Agent ID

建议：**Agent Registry 是 Agent 身份的唯一来源。**

例如：

```text
Agent Registry
agent_id = 8b72...UUID
name = codex-minimax
role = developer
permissions = read + write
```

Token 只引用 canonical ID：

```text
token_id
agent_id = 8b72...UUID
scopes = context.read, proposal.create
```

认证流程：

```text
MCP Request
    ↓
Token
    ↓
Token.agent_id
    ↓
Agent Registry
    ↓
Resolve Agent
    ↓
Resolve Scopes
    ↓
Permission Check
```

## 4.3 Name 与 ID 分离

`codex-minimax` 可以保留，但作为 `display_name/slug`，而不是身份本身：

```text
agent_id: 8b72...
display_name: codex-minimax
```

## 4.4 禁止 Agent 自己声明身份

不要信任 MCP 请求中任意传入的 `agent_id`。真正身份必须来自 Token：

```text
Token → Authentication → Token.agent_id → Agent Identity
```

如果请求声明的身份与 Token 不一致，应直接拒绝，防止 Agent 冒充其他 Agent。

## 4.5 启动时注入身份

Flux 可以在启动 Agent 时提供：

```text
FLUX_AGENT_ID=<canonical UUID>
FLUX_RUN_ID=<run UUID>
```

但这只是辅助信息；MCP 鉴权仍以 Token → canonical agent_id 为准。

## 4.6 Audit Log

所有关键对象统一关联：

```text
agent_id
run_id
proposal_id
token_id
```

从 Proposal 可以追溯到 Run、Agent 和 Token。

---

# 5. P3-16 回归测试

### TC-16A：Token Identity Resolve
Token 的 canonical `agent_id` 能正确解析 Agent Registry，并得到正确 role/scopes。

### TC-16B：Fake Agent ID
不存在的 Agent UUID 必须被拒绝，不创建 Proposal、不执行 Workspace 操作。

### TC-16C：Impersonation
Agent A 使用 Agent B 的身份必须被拒绝。

### TC-16D：Audit Attribution
DSH 和 Codex 分别创建 Proposal 时，Proposal/Run/Audit Log 的 Agent attribution 均正确。

---

# 6. 推荐实施顺序

```text
Step 1  补 P2-15 / P3-16 回归测试
   ↓
Step 2  实现 RunSupervisor
   ↓
Step 3  实现 process group / process-tree cleanup
   ↓
Step 4  实现 timeout + heartbeat
   ↓
Step 5  实现 Reconciler
   ↓
Step 6  实现 Flux restart recovery
   ↓
Step 7  统一 Agent canonical ID
   ↓
Step 8  迁移 MCP Token
   ↓
Step 9  DSH + Codex 回归
```

---

# 7. 与现有问题的关系

P2-15 不应孤立修复，它同时覆盖：

```text
P2-15
DSH 长时间无响应
   +
TC-502 running 僵尸任务
   +
Cancel 后子进程残留
   +
Flux 重启后的状态不一致
```

P3-16 则统一归入：

> Agent Identity / Authentication / Authorization

---

# 8. 暂不引入大型基础设施

当前个人开发 / MVP 阶段不需要因为这两个问题引入 Java、Kafka、Kubernetes 等大型基础设施。

现阶段可以使用：

```text
Python
+ asyncio
+ Process Group
+ RunSupervisor
+ Reconciler
+ 现有数据库
```

未来如果进入大规模云端并发，再考虑 Redis、Queue、Distributed Worker、Scheduler、Temporal/Celery 等方案。

---

# 9. 完成标准

修复完成后至少应满足：

- Agent 启动失败不会永久 running
- Provider 长时间无响应能够 timeout
- 长任务存在 hard timeout
- Cancel 能清理整个 Agent 进程树
- 不存在永久 CANCELLING
- Flux 重启后能够自动 reconcile
- Token 只能绑定真实 Agent
- Agent 无法冒充其他 Agent
- DSH / Codex 的 Proposal attribution 正确
- Audit Log 能追溯 Agent → Run → Proposal

---

# 10. Context Engine 前置条件

这两个问题完成后，再进入 Context Engine 会更稳妥：

```text
MVP
 ↓
P1 / Runtime Reliability
 ↓
DSH + Codex 完整回归
 ↓
稳定 Runtime
 ↓
Context Engine
 ↓
Context Recall Benchmark
 ↓
Token Benchmark
```

Context Engine 后续重点验证：

```text
Context Recall ≥ 97%
最终 Recall ≥ 99%
关键约束 Recall ≈ 100%
同时降低 Input Token
```

最终使用真实项目对比原生 Agent 与 Agent + Flux Context Engine，并统计 Input Tokens、Cached Input、Output Tokens、Total Tokens、Context Recall、Task Success Rate、Session 数量、完成时间和 API 成本。

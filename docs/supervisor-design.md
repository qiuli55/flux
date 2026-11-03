# Supervisor（助理）设计说明

助理是 Flux 的顶层编排者：接收各平台（Flux 内部、飞书……）的事件，判断值不值得处理，
把通过判断的请求拆成工单树，派给子 Agent 执行，回收结果，再把结论送回来处。
实现参考 DeepSeek Super-agent 的领域模型（Source / Trigger / Thread / Eval / Mandate /
Ticket / Assignment / Report / Subscription / Attention），但按 Flux 的多项目、多平台
场景重写，只借鉴分层与不变量，不整包引入。

代码入口：

| 位置 | 职责 |
| --- | --- |
| `backend/flux/models/supervisor.py` | 13 张表的领域模型 |
| `backend/flux/core/supervisor/` | gate / assess / planner / repository / dispatcher / orchestrator / outbox / worker / digest / delivery |
| `backend/flux/api/v1/supervisor.py` | 查询与人工操作接口 |
| `backend/flux/api/v1/supervisor_webhook.py` | 飞书事件回调 |
| `backend/flux/core/mcp/tools/supervisor_tools.py` | `assistant.*` 四个只读 MCP 工具 |

## 1. 边界与红线

1. **外部内容是数据，不是指令。** 平台消息先落 `supervisor_triggers.raw` 作审计，模型只读
   结构化字段；原始文本不会被当作系统提示执行。
2. **身份不跨平台映射。** `actor_id` + `actor_platform` 成对出现。同一个真人在飞书和
   Flux 内部是两个身份——记忆可以统一，权限不可统一。
3. **授权不可绕过。** 是否派活由 `supervisor_settings.accept_mode` 与
   `supervisor_policies.accept_mode` 共同决定，默认 `observe`；AUTO 只是"允许自动派活"，
   不意味着可以跳过 Gate、角色校验或依赖检查。
4. **观察模式可回放。** 判断只追加（`supervisor_evals`），不更新历史；任何一条
   "当时为什么这么判"都能按 `trigger_id` 找回，且 Gate 是纯函数，同输入同输出。
5. **MCP 只读。** `assistant.overview / ticket / reports / timeline` 只查询，不提供
   派活、改状态、写报告的入口。

## 2. 分层架构

```
L0 Sources        飞书 webhook / Flux 内部调用
      │
L1 Gate           纯程序，0 次模型调用：off? 来源停用? 空内容? 类型/名单/频控?
      │ accept
L2 Assessment     模型，无状态：accept / defer / reject + 风险标注
      │
L3 Router         唯一写状态的地方：thread / ticket / assignment / report
      │
L4 Assignment     Task Engine + DSH Run：真正的执行
      │
L5 Memory         supervisor_digests → Project Brain(progress) / 检索
      │
L6 Outlet         订阅直通 + outbox 投递回平台
```

关键不变量：**判断层无状态、编排层不持有内存状态、执行状态一律以 Task Engine 为准。**
进程重启后，未完成的 outbox 行会被重放，工单状态由数据库派生。

## 3. 数据模型

| 表 | 说明 |
| --- | --- |
| `supervisor_settings` | 单行配置：`accept_mode`、模型、`max_tickets_per_thread`、`max_ticket_depth`、`max_concurrent_runs` |
| `supervisor_sources` | 来源登记（`flux_internal` / `feishu` …），存配置不存明文密钥 |
| `supervisor_policies` | 按项目 / 来源覆盖 `accept_mode`，并提供 Gate 硬规则 |
| `supervisor_triggers` | 一条外部事实；`dedupe_key` 唯一，是幂等的唯一依据 |
| `supervisor_threads` | 一段持续对话（`thread_key` 唯一），线程是授权边界 |
| `supervisor_evals` | 无状态判断结论，只追加；回放能力来自这里 |
| `supervisor_mandates` | 用户对线程的授权，撤销写 `revoked_at`，不修改原记录 |
| `supervisor_tickets` | 可派活工单，1:1 关联既有 `tasks` 行 |
| `supervisor_subscriptions` | 报告投递目标（工单 + 平台线程唯一） |
| `supervisor_assignments` | 一次派活记录：工单 → Agent / Task / Run |
| `supervisor_reports` | 子 Agent 结论，**只由平台按事实写入** |
| `supervisor_digests` | 线程收尾摘要，供项目页与检索使用 |
| `supervisor_outbox` | 事务性 outbox：先存后做，`pending/processing/done/dead` |

`supervisor_tickets` **没有 status 列**。阶段由
`derive_stage(task_status, assignment_status, report_status)` 派生，优先级为：
在途 assignment（`dispatched/running`）> 最新 report > Task 状态 > 历史 assignment > `planned`。
这样执行事实永远只有一份来源，不会出现"工单表说 running、任务表说失败"的分叉。

## 4. 主流程

```
POST /supervisor/events 或 飞书回调
   └─ 落 supervisor_triggers（dedupe_key 幂等）+ outbox 入队 supervisor.trigger_received
        └─ worker.run_once
             ├─ requeue_stale(600s)        回收卡死的 processing 行
             ├─ claim(batch)               领取 pending 行
             ├─ Gate（纯函数）              ignore → trigger.status=ignored，结束
             ├─ Assessment（模型）          accept / defer / reject → 写 supervisor_evals
             ├─ AUTO 分支（见第 5 节）      规划 → 落库 → 派发
             └─ _reconcile：reconcile → watchdog → auto_dispatch
```

规划三步分离，谁都不越权：

- **planner**：模型只提建议（`TicketPlan`），平台做硬校验（数量上限、深度上限、角色是否
  存在、`depends_on` 是否成环）；模型输出不合法就降级成单工单，`degraded=true` 并记录原因。
- **repository**：`plan_key` 带 trigger 前缀，重复规划不会拆出第二棵树。
- **dispatcher**：角色必须能在 `agents` 表里找到；任务与运行状态以 Task Engine 为准；
  找不到角色时写一条幂等的 `blocked` 报告，不刷屏。

## 5. 准入与自动派活

三层取值，越具体优先级越高：

```
policy(项目 + 来源) > policy(项目) > policy(来源) > supervisor_settings
```

`select_policy()` 同分时按创建顺序取先到者，保证同输入同策略（可回放）。
配置值非法一律按 `off` 处理并告警，绝不因为写错配置而放开派活。

派活的唯一入口是 `SupervisorWorker.evaluate()` 中：

```
not force and orchestrator is not None and mode is AUTO
    and decision is ACCEPT and should_dispatch
```

- `force=True`（人工重判 `/observations/{id}/assess`）**永不派活**，只追加判断；
- `observe` 模式完整跑 Gate + Assessment + 规划，但只落 `planned` 工单，等人工派发；
  这既是观察期，也是回放素材；
- `auto` 模式下，`_dispatch()` 之前还会按 thread 的来源/项目重新 `select_policy` 校验，
  策略覆盖全局；运行中把模式收回 `observe/off`，下一轮立即停止续派。

## 6. 派发、投机执行与对账

**依赖与并发。** `dispatch_thread()` 按 `sequence` 遍历工单：

- `depends_on` 里还有非 `done` 的工单 → 本轮跳过；
- 并发位 `available = max_concurrent_runs - active`，每派出一张递减到 0；
- 无依赖的兄弟工单可以同轮并行（投机执行），有依赖的必须等上游 `done`。

**对账 `reconcile()`。** 把 Task 终态物化成 `supervisor_reports`
（`completed / failed / cancelled` 等六态），并刷新 assignment。
报告只由平台写，Agent 不能自报成功。

**续跑 `auto_dispatch()`。** 此前只有人工 `/dispatch` 能推进依赖链；现在每轮对账后，
AUTO 模式下会把依赖已满足的 `planned` 工单继续派出，无需人工干预。

**watchdog。** 每轮对账后检查在途派活（`dispatched/running`），命中任一条件即回收：

| 条件 | 判定 |
| --- | --- |
| `dispatched` 且年龄 ≥ `supervisor_dispatch_timeout_seconds` | 派活未进入执行 |
| `running` 但 `task_id` 缺失或任务已丢失 | 执行对象不存在 |
| `supervisor_run_timeout_seconds > 0` 且执行超时 | 单次 Run 超时 |
| 任务已到终态 | **不回收**，交给对账物化报告 |

回收动作 `expire_assignment()`：停 Run → 任务置 `cancelled` → assignment 收尾 →
写 `timed_out` 报告；只处理在途记录，重复调用幂等。

## 7. 记忆与出口

- **线程摘要**：线程收尾后由规则生成 `SupervisorDigest`（不调模型），写入
  Project Brain 的 `PROGRESS` 分区，供项目页 `/supervisor/projects/{id}` 展示。
- **检索**：`/supervisor/search` 跨线程、工单、报告、摘要查询。
- **订阅直通**：`supervisor_subscriptions` 把报告按 `all / terminal / needs_input`
  投递回指定平台线程；投递动作走 outbox 主题 `supervisor.report_ready`，
  失败线性退避，超过 `supervisor_outbox_max_attempts` 落 `dead`。
- **MCP**：外部 Agent 通过 `assistant.*` 只读查询总览、工单、报告、时间线。

## 8. 配置项

业务策略（准入模式、计划上限）落库，运维参数走环境变量 / `Settings`：

| 变量 | 默认 | 说明 |
| --- | --- | --- |
| `supervisor_worker_enabled` | `true` | 是否启动后台 worker |
| `supervisor_poll_seconds` | `5.0` | 轮询间隔 |
| `supervisor_assess_timeout_seconds` | `120.0` | 单次模型判断超时 |
| `supervisor_max_assess_retries` | `2` | 判断重试次数 |
| `supervisor_max_plan_retries` | `1` | 规划重试次数 |
| `supervisor_outbox_max_attempts` | `5` | outbox 最大尝试次数 |
| `supervisor_dispatch_timeout_seconds` | `120.0` | 派活租约上限 |
| `supervisor_run_timeout_seconds` | `0.0` | 单次 Run 上限，0 = 不限 |
| `supervisor_feishu_app_id` / `app_secret` | 空 | 飞书应用凭据 |
| `supervisor_feishu_verification_token` | 空 | 回调验签 |
| `supervisor_feishu_encrypt_key` | 空 | 回调加密 |

## 9. API 一览

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| POST | `/api/v1/supervisor/events` | 内部事件接入（幂等） |
| POST | `/api/v1/supervisor/channels/feishu` | 飞书回调（验签 + challenge + 消息解析） |
| GET | `/api/v1/supervisor/observations` | 观察列表 |
| GET | `/api/v1/supervisor/observations/{trigger_id}` | 单条事件与判断 |
| POST | `/api/v1/supervisor/observations/{trigger_id}/assess` | 人工重判（不派活） |
| POST | `/api/v1/supervisor/observations/{trigger_id}/plan` | 规划工单树（可选 `dispatch`） |
| GET | `/api/v1/supervisor/overview` | 总览看板 |
| GET | `/api/v1/supervisor/projects/{project_id}` | 项目进度 |
| GET | `/api/v1/supervisor/search` | 跨实体检索 |
| POST | `/api/v1/supervisor/watchdog` | 手动跑一轮超时回收 |
| GET | `/api/v1/supervisor/tickets` | 工单列表 |
| GET | `/api/v1/supervisor/tickets/{ticket_id}` | 工单详情（含阶段派生） |
| GET | `/api/v1/supervisor/reports` | 报告列表 |
| GET | `/api/v1/supervisor/timeline` | 时间线 |
| GET/POST | `/api/v1/supervisor/tickets/{ticket_id}/subscriptions` | 订阅查询 / 新建 |
| DELETE | `/api/v1/supervisor/subscriptions/{subscription_id}` | 取消订阅 |
| POST | `/api/v1/supervisor/threads/{thread_id}/dispatch` | 人工派发整棵树 |
| POST | `/api/v1/supervisor/tickets/{ticket_id}/dispatch` | 人工派发单张 |
| POST | `/api/v1/supervisor/tickets/{ticket_id}/retry` | 重试 |
| POST | `/api/v1/supervisor/tickets/{ticket_id}/stop` | 停止 |
| POST | `/api/v1/supervisor/process` | 手动触发一轮 worker |
| GET/PUT | `/api/v1/supervisor/settings` | 准入模式与计划上限 |

## 10. 测试与验证

- `tests/test_supervisor.py`：主流程、依赖阻塞、AUTO 只在 accept 后派活；
- `tests/test_supervisor_channels.py`：飞书验签、challenge、幂等、订阅投递；
- `tests/test_supervisor_memory.py`：线程摘要、项目页、检索；
- `tests/test_supervisor_auto.py`：依赖链自动续跑、observe 不派活、兄弟工单并行、
  watchdog 四种场景（空闲 / 派活超时 / 执行超时 / 健康运行不误伤）。

本地与 CI 统一走 `scripts/verify.sh`：ruff check → ruff format --check →
OpenAPI 契约检查 → alembic upgrade/downgrade 往返 → pytest 全量。

## 11. 非目标

- 不做跨平台身份合并：同一个人在不同平台仍是不同 `actor_id`，权限各自独立。
- 不做"助理直接改代码"：助理只规划、派活、回收，执行归子 Agent 与 Task Engine。
- 不做默认自动派活：新装默认 `observe`，需要显式切到 `auto` 才会续派。
- 暂不提供投递重放到具体平台的管理接口；失败重试由 outbox 自动完成。

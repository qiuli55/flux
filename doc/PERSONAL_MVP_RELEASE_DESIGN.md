# Flux Personal MVP 收口设计（P0 / P1 / P2 + Windows 跨平台 + 收口测试 + 移动端 / 桌面端路线）

> 依据：[PERSONAL_MVP_RELEASE_SCOPE.md](./PERSONAL_MVP_RELEASE_SCOPE.md)（commit `9aeedad`）
> 基线：仓库 commit `9aeedad`，上一轮验收报告 [FLUX_ACCEPTANCE_REPORT_2026-10-04.md](./FLUX_ACCEPTANCE_REPORT_2026-10-04.md)
> 日期：2026-10-05 ｜ 状态：**已评审**（2026-10-05 决策回填，按 §0 顺序开工；仅 Windows 真机用机待定）
> 约束：不改测试预期掩盖失败；证据落盘；密钥/令牌不写入任何文档。

---

## 0. 设计范围与执行顺序

本设计只覆盖 Scope 收口范围内的 P0/P1/P2 六项 + 收口测试方案，并给出移动端、桌面端的技术方向（这两项的交互/打包细节在各自阶段先出设计再实现）。

执行顺序与依赖：

```text
P0-1 Apply 崩溃恢复（引入 apply_batches 日志表）
  ↓ 复用同一张批表
P0-2 Run 生命周期验证与修补     P1-1 Delete Proposal（独立）
  ↓                              ↓
P1-2 Rollback（依赖批表 + Delete 语义）
  ↓
W-1 跨平台进程管理抽象（Windows 兼容；与 P2-1 合并实施，见 §11）
  ↓
P2-1 Agent Runtime Contract（先壳后迁，DSH 行为不变；进程层直接落在 W-1 抽象上）
  ↓
P2-2 Flux 拉起外部 CLI Agent（Codex / OpenCode 真实链路）
  ↓
收口测试（全量复验 → 验收报告 v2，含 Windows 真机章节，见 §8 / §11）
  ↓
移动端（先交互 demo → 实现 → 真机验收） → 桌面端打包（Linux + Windows 双产物）→ 50 任务 Benchmark → 冻结
```

---

## 1. 现状结论（基于代码调研，截至 `9aeedad`）

| 模块 | 现状 | 与本设计的关系 |
|---|---|---|
| Apply 链路 | `ApplyEngine.apply_many`（[apply_engine.py](../backend/flux/core/virtual_workspace/apply_engine.py) L130）：全量预检 → 全量备份 → 写入 → 校验 → 测试 → 失败回滚；状态机无 `APPLYING` 中间态（[service.py](../backend/flux/core/virtual_workspace/service.py) L42-63）；结果经 `set_apply_result` 单事务落库（[repository.py](../backend/flux/core/virtual_workspace/repository.py) L198-216） | P0-1 的缺口就在这里：kill -9 时状态停在 `ACCEPTED`，磁盘半应用、DB 无记录，重试被 409 挡住（上轮实测 T-APPLY-008） |
| 备份机制 | `.flux/backups/<change_id>/<相对路径>`，只备份已存在文件，新建文件无备份（回滚=删除）（[backup.py](../backend/flux/core/virtual_workspace/backup.py) L23-55） | P0-1 恢复算法与 P1-2 Rollback 都建立在它之上；需把"备份存在但 DB 未记录"的间隙补上 |
| Run 生命周期 | 机制已齐：三类超时 `dsh_startup_timeout_seconds=120` / `dsh_idle_timeout_seconds=600` / `dsh_hard_timeout_seconds=1800`（[config.py](../backend/flux/config.py) L102-115）；`_timeout_kind` L425-446；对账矩阵 L450-494；`startup_recovery` L496 / `ensure_background` L514，由 [main.py](../backend/flux/main.py) L35-39 调用；进程组清理 SIGTERM→grace→SIGKILL→confirm（L629-690）并有单测（[test_run_supervisor.py](../backend/tests/test_run_supervisor.py) L230-264） | P0-2 主要是"真实故障注入验证 + 修补验证中暴露的洞"，不是从零造机制 |
| 提案语义 | `proposal.create` 只接受 `{summary, changes:[{path, content, reason}]}`，content 必须是完整新内容；文件不存在视为新建（[write_tools.py](../backend/flux/core/mcp/tools/write_tools.py) L28-47）；无删除语义 | P1-1 Delete Proposal 的扩展点 |
| Agent 档案 | `agents` 表字段：name / role / model_provider / model_name / status / system_prompt / config(JSON)，**无 runtime 字段**（[agent.py](../backend/flux/models/agent.py)） | P2 的 runtime 选择先用 `config["runtime"]`，不新增列 |
| 任务启动 | `POST /tasks/{id}/start` 硬编码走 `container.dsh.start_run`（[tasks.py](../backend/flux/api/v1/tasks.py) L322-386） | P2-1 要把它抽成"按 runtime 分发"的统一入口 |
| CLI 适配器 | `CliAgentAdapter` 接口、`build_run_argv`、`parse_event`、`start`（start_new_session）都已在 [adapters/base.py](../backend/flux/core/agent_runtime/adapters/base.py) L83-167 / [generic.py](../backend/flux/core/agent_runtime/adapters/generic.py) L127-138 就绪，但**无生产调用方**（上轮验收证实：仅探测链路在用） | P2-2 的复用基座；契约层（P2-1）把它们接进生产 |
| 前端 | React + Vite + Tailwind；仅两个页面 SoloPage / IdePage（[router.ts](../apps/web-dashboard/src/app/router.ts)），已有 `useMediaQuery` 860px 断点（[useMediaQuery.ts](../apps/web-dashboard/src/app/useMediaQuery.ts) L2-26） | 移动端在现有前端上加移动视图，不另起项目 |
| 跨平台 | 硬 POSIX 依赖 4 处：`os.setsid` 垫片（dsh_client L49-61）、`killpg`+`SIGKILL`（supervisor L653-695 / base L141-173）、`os.kill(pid,0)` 探活（supervisor L777）、`chmod 0600`（dsh_client L266）；CI 仅 ubuntu（ci.yml L17）；DSH 官方 runtime 已有 win_amd64 wheel（PyPI `0.1.5rc1`，2026-10-05 实测查询） | Windows 为家庭实际使用平台 → 新增 §11 跨平台设计；卡点只在 Flux 进程层，不在依赖/官方 runtime |

---

## 2. P0-1 Apply 崩溃恢复设计

### 2.1 目标（Scope §2.1）

Apply 开始前记录事务状态；过程可检测未完成事务；重启后对账；不允许无法解释的半完成状态；重试不造成二次破坏；Backup 可追踪。

### 2.2 设计

**新增 `apply_batches` 表（apply 事务日志，同时是 P1-2 的回滚分组）**

| 字段 | 类型 | 说明 |
|---|---|---|
| id | UUID 主键 | 一次 apply（apply_many 调用）的批 ID |
| status | String(16) | `in_progress` / `applied` / `failed` / `recovered` / `needs_attention` / `rolled_back` |
| change_ids | JSON | 本批包含的 change ID 列表（有序） |
| phase | String(16) | 进度标记：`prepared` → `backed_up` → `writing` → `verifying` → `testing` |
| backup_root | String | 本批备份根（`.flux/backups/<change_id>/` 各一份，此处记录相对根目录） |
| error / recovery_note | Text | 失败原因 / 恢复说明 |
| created_at / finished_at | DateTime | 时间线 |

**写日志的时机**：`apply_many` 进入后、**任何磁盘操作之前**先插入 `in_progress/prepared` 行（DB 事务提交）；每过一个阶段更新 `phase`。

**状态机扩展**（[service.py](../backend/flux/core/virtual_workspace/service.py) 现有转换基础上新增）：

```text
ACCEPTED → APPLYING → APPLIED        （成功，现状路径拆出中间态）
                    ↘ FAILED         （测试失败/回滚完成，现状行为不变）
APPLYING → ACCEPTED                  （仅崩溃恢复路径，附 recovery_note）
APPLIED  → ROLLED_BACK               （P1-2 新增）
```

**恢复算法（幂等，按 change 逐个处理，只认磁盘事实）**：

1. 找出 `in_progress` 的批（服务启动时全量扫；apply 入口再对涉及 change 做懒检查）。
2. 对批内每条 change：
   - 目标当前内容 hash == `original_hash` → 已回原样，跳过（不碰盘，无论有无备份）。
   - 目标当前内容 hash == 提案 `proposed_content` 的 hash → Flux 自己写的，可安全收拾：有备份（修改/删除类）则从备份还原为改动前原文；无备份（新建类）则删除该新建文件。
   - **目标内容既非 original 也非 proposed（apply 期间被外部改动）→ 一律不覆盖**，批置 `needs_attention`、change 置 `FAILED` 并写明"崩溃后检测到外部修改，备份已保留"，**转人工决策**（见下）。**注意：有备份的"修改类"同样适用，不得因"有备份就写回"而覆盖外部改动。**
   - 无备份、内容 == 提案、但原文非空（修改类备份缺失）→ 不删不覆盖，`needs_attention`。
3. 全部处理完：批置 `recovered`，涉及 change 回 `ACCEPTED`（可安全重试），回填 `backup_path`（解决"备份在盘上但 DB 未记录"的追踪缺口），发布 `apply.recovered` 事件，`recovery_note` 记录过程。

**人工决策入口（2026-10-05 定案）**：`needs_attention` 的项**不自动处理**，在 UI 上以"变更区横幅 + 点开弹层"呈现（非阻塞），详情并列展示三个版本——① 备份里的改动前原文 ② 当前磁盘真实内容 ③ Flux 原本要写入的提案内容；用户二选一：

- **覆盖备份**：用备份还原为改动前原文 → change 回 `ACCEPTED`（可重试）；
- **保持现状**：不碰文件 → change 置 `FAILED`（提案作废，理由写明"用户选择保留当前内容"）。

备份缺失时"覆盖备份"不可用（置灰），只能保持现状或人工处理。CLI 同步提供 `flux proposal recovery list` / `flux proposal recovery resolve`，便于远程/无人值守处理。

**触发点**：
- 服务启动：`main.py` lifespan 中与 `supervisor.startup_recovery()` 并列调用 `workspace.recover_interrupted_applies()`；
- apply 前置：`apply_changes` 入口先懒检查（覆盖 CLI 一次性进程场景，CLI 与 Server 共用 DB，天然一致）；
- CLI 展示：`flux proposal list/show` 输出中 `APPLYING` 状态可见，附带恢复提示。

**重试语义**：恢复后 change 回 `ACCEPTED`，用户重试 apply → 预检通过（文件已回 original、hash 匹配）→ 正常应用，不产生二次破坏；备份目录按 change_id 固定路径，重试覆盖备份前先建 `.prev` 快照，保证"Backup 信息可追踪"。

### 2.3 验收用例（收口测试执行）

| ID | 场景 | 期望 |
|---|---|---|
| T-APPLY-010 | 正常 apply | 批 `applied`，日志/备份路径完整 |
| T-APPLY-011 | apply 中途 SIGKILL → 重启服务 | 自动恢复：文件回 original、change 回 `ACCEPTED`、批 `recovered` |
| T-APPLY-012 | 恢复后重试 apply | 成功且文件=提案内容，无二次破坏 |
| T-APPLY-013 | 对同一批重复恢复 | 幂等，无副作用 |
| T-APPLY-014 | 崩溃后外部改动文件再恢复 | 不覆盖，`needs_attention` + 明确错误说明 |

---

## 3. P0-2 Run / Agent 生命周期可靠性设计

### 3.1 目标（Scope §2.2）

所有 Run 最终进入 `completed / failed / cancelled / timeout`；禁止"进程已退而 Run 永久 running"；Provider 无响应可超时；Cancel 后子进程必清；升级链路可控；重启可对账；终态与真实进程一致。

### 3.2 设计：以故障注入验证为主，修补为辅

现有机制（见 §1 行 3）已覆盖大部分要求，本项工作定义为**真实故障注入验证矩阵**，逐分支实测；只在验证暴露缺口时做最小修补（不重构契约）：

| # | 注入场景 | 注入方法 | 期望终态 |
|---|---|---|---|
| 1 | 启动超时 | 临时调小 `FLUX_DSH_STARTUP_TIMEOUT_SECONDS` + 挂起的假 harness（测试注入） | `timeout`（kind=startup），进程清理干净 |
| 2 | 空闲超时 | 真实 Run 持续无输出/无 MCP 活动超时窗 | `timeout`（kind=idle） |
| 3 | 硬超时 | 调小 `FLUX_DSH_HARD_TIMEOUT_SECONDS` 跑长任务 | `timeout`（kind=hard） |
| 4 | Cancel 升级 | 子进程忽略 SIGTERM | grace 后 SIGKILL，`cancelled`，pgid 清空（confirm） |
| 5 | 属主死亡 | apply 进行中 `kill -9` uvicorn → 重启 | 重启对账：孤儿进程树清理，Run → `interrupted` |
| 6 | 进程死而 Run 非终态 | 直接杀 Agent 子进程 | ≤1 个对账周期内 → `failed/interrupted` |
| 7 | 终态竞争 | cancel 与正常完成竞争 | 终态不被迟到写入覆盖（finish 守卫） |
| 8 | 幂等边界 | 对终态 Run 再次 cancel | 明确语义（幂等返回或 409），无副作用 |

方法：起独立测试实例（8064）+ 脚本化注入，证据落 `/tmp/flux-stage5/evidence/`；发现缺口就地修复并回归。

---

## 4. P1-1 Delete Proposal 设计

### 4.1 目标（Scope §3.1）

补齐 CREATE / MODIFY / DELETE 三种文件变更语义，DELETE 与现有 Proposal → Diff → Review → Apply 全流程一致。

### 4.2 设计

**提案入参扩展**（[proposal_parser](../backend/flux/core/virtual_workspace/proposal_parser.py) + [proposal.create schema](../backend/flux/core/mcp/tools/write_tools.py)）：

```json
{"summary": "删除废弃模块", "changes": [
  {"path": "src/legacy.py", "op": "delete", "reason": "已被新模块替代"}
]}
```

- `op` 可选值 `create / modify / delete`（缺省时自动判定：原文件存在 → modify，不存在 → create）；
- `op=delete` 时禁止携带 `content`；其余 op 仍要求完整 `content`；
- 校验：delete 目标必须存在（现在 `_original_content` 用空串表示不存在，需改为返回"存在性 + 内容"的二元结果，区分空文件与不存在）；路径守卫沿用，并确认/补上 `.git` 内部与工作区外的拒绝规则（开工时先核对现状，缺则补）；
- REST 导入路径复用同一 parser，自动获得删除语义。

**数据模型**：`virtual_changes` 新增 `kind` 列（String(16)，`create/modify/delete`，migration 回填：`original_hash IS NULL → create`，否则 `modify`）。`proposed_content` 对 delete 为 NULL。

**Diff / 展示**：delete 的 diff 为整文件移除（`added_lines=0`，`removed_lines=N`，hunks 覆盖全文）；前端审核卡显示"删除"徽标与删除行 diff；CLI `flux proposal diff` 头部输出 `D <path>`。

**Apply 处理**（[apply_engine.py](../backend/flux/core/virtual_workspace/apply_engine.py)）：
- 预检：文件存在 + `original_hash` 匹配；
- 备份：备份原文件（复用现有逻辑）；
- 写入：`unlink`；校验：文件不存在；
- 失败回滚：从备份写回（现有 `_rollback` 逻辑天然支持）；
- 批日志 `phase` 记录动作类型（配合 P0-1）。

### 4.3 验收用例

| ID | 场景 | 期望 |
|---|---|---|
| T-DEL-001 | 提交删除提案 | diff/行数/徽标正确，文件未动 |
| T-DEL-002 | accept → apply | 文件消失、pytest 通过、批 `applied` |
| T-DEL-003 | 删除后 rollback（依赖 §5） | 文件内容恢复 |
| T-DEL-004 | 删除不存在的文件 | 校验拒绝，明确错误 |
| T-DEL-005 | 删除前文件被外部修改 | 409 conflict，未动盘 |
| T-DEL-006 | create+modify+delete 混合批 | 全量成功或全量回滚，无半应用 |

---

## 5. P1-2 正式 Rollback 设计

### 5.1 目标（Scope §3.2）

把 `.flux/backups/` 提升为正式能力：明确入口、可回滚最近一次 Apply、回滚后 Workspace 状态正确、不产生错误记录、可测试可重复验证。不做无限历史与版本管理 UI。

### 5.2 设计

**入口（API + CLI）**：
- `POST /workspace/changes/{change_id}/rollback` — 单条回滚；
- `POST /workspace/apply-batches/{batch_id}/rollback` — 整批回滚；
- `GET /workspace/apply-batches?recent=1` — 取最近一次成功 Apply（驱动前端"回滚上一次"按钮）；
- CLI：`flux proposal rollback <change_id>` / `--batch <batch_id>` / `--last`。

**前置校验（先全量再动盘，任一不满足即 409/明确错误，不产生任何写入）**：
1. 批 `status=applied`（`recovered/failed/needs_attention` 不可回滚；`rolled_back` 二次回滚 → 409）；
2. 每条 change `status=APPLIED`；
3. 当前文件内容 hash == 该 change 提案的 `proposed_content` hash（apply 后又被外部改动 → 409"文件已变化，拒绝覆盖"）；
4. 备份存在（modify/delete 类）。

**执行（按批内逆序）**：modify → 写回 `original_content`（以备份文件为准）；create → 删除；delete → 写回备份。每步写后校验；全部成功 → 批 `rolled_back`、各 change `ROLLED_BACK`（新终态）、发布 `apply.rolled_back` 事件、`finished_at` 落时间线。

**Git 语义**：Rollback 只恢复 Workspace，不自动产生任何 git 提交或回执（避免错误记录）；用户如需留痕可自行 commit，前端提示当前 git 工作区差异。

**失败与重复**：全量预检使执行中失败概率极低；若仍失败（磁盘异常），停止并记 `error`，已恢复项保持——再次 rollback 因前置校验"当前 hash 已是 original"而幂等跳过已完成项，可重复执行直至完成或给出明确解释。

### 5.3 验收用例

| ID | 场景 | 期望 |
|---|---|---|
| T-ROLL-003 | 单条回滚 | 内容=original，pytest 通过，状态 `ROLLED_BACK` |
| T-ROLL-004 | 整批回滚（最近一次 Apply） | 批内全部恢复，批 `rolled_back` |
| T-ROLL-005 | create/modify/delete 混合批回滚 | 新建被删、修改还原、删除恢复 |
| T-ROLL-006 | 二次回滚 | 409，无副作用 |
| T-ROLL-007 | 回滚前文件被外部改 | 409，未动盘 |
| T-ROLL-008 | 回滚后 git 状态 | 工作区差异符合预期，无错误 git 记录 |
| T-ROLL-009 | 回滚后可再次 accept/apply 同一文件 | 闭环正常 |

---

## 6. P2-1 Agent Runtime Contract 设计

### 6.1 目标（Scope §4.1）

建立统一抽象：`Task → AgentRuntime → {DSH, Codex, OpenCode}`，统一处理启动、身份注入、MCP 接入、事件流、Tool Call、Proposal、完成/失败/Cancel/Timeout、子进程与资源清理。本阶段重点是统一契约与真实运行链路，不扩 Agent 类型。

### 6.2 设计

**接口**（新增 `backend/flux/core/agent_runtime/runtimes/base.py`）：

```python
class AgentRuntime(Protocol):
    runtime_id: str                      # "dsh" | "codex" | "opencode"
    async def prepare(self, task, agent, run_id) -> RuntimeLaunch: ...
        # instruction（compose_instruction）、env（build_runtime_env 扩展）、cwd、
        # run 目录、MCP 注入配置、令牌 scopes
    async def start(self, launch: RuntimeLaunch) -> RuntimeProcess: ...
        # 返回 (pid, pgid)；进程组隔离用既有 setsid 垫片方案
    async def events(self, proc) -> AsyncIterator[RuntimeEvent]: ...
        # 统一词表：status / message / tool_call / tool_result / final / error
    async def cancel(self, proc) -> None: ...
```

实现两个：
- **DshRuntime**：包一层现有 [FluxDshClient](../backend/flux/core/agent_runtime/dsh_client.py)，**行为完全不变**（迁移零回归，验收=现有全量测试 + 一条真实 E2E）；
- **CliRuntime**：复用 `CliAgentAdapter`（build_run_argv / parse_event）+ 进程原语（Popen start_new_session），并把进程注册进现有 `RunSupervisor`（pid/pgid/owner/心跳/超时/对账全部复用，不新建生命周期机制）。

**统一事件与终态**：事件统一映射到现有 flux 事件词表（[dsh_events.py](../backend/flux/core/agent_runtime/dsh_events.py) 为参照），落 `agent_runs` + 事件流；CLI 终态映射：退出码 0 → `completed`，非 0 → `failed`，cancel/timeout 由 supervisor 判定（与 DSH 同一套终态守卫）。

**runtime 选择**：`agent.config["runtime"]`（默认 `"dsh"`）+ 创建 Agent 时校验取值合法；启动前校验对应 installation 为 `READY`，否则 409 并提示先 `flux agents connect`。

**启动链路重构**：把 [tasks.py](../backend/flux/api/v1/tasks.py) L322-386 的编排抽成 `TaskRunService.start(task, confirmation)`，按 runtime 分发；对外 API 与消息落库行为不变。

---

## 7. P2-2 Flux 主动拉起外部 CLI Agent 设计

### 7.1 运行目录与令牌

- 每次 Run 建独立目录 `<FLUX_DSH_HOME>/runs/<run_id>/`（0700），内含该 CLI 的配置（0600）；
- 起 Run 前经 `AgentTokenService` 签发一次性令牌：canonical agent UUID、scopes 仅 `file.read/file.write`、TTL 24h，Run 结束即撤销；
- 令牌只出现在 run 目录配置文件中，不进日志、不进任何文档与验收证据。

### 7.2 两个 CLI 的启动方式（按本机实际版本校准）

| 项 | Codex（本机 0.157.1，已登录） | OpenCode（本机 1.18.29，实测可用） |
|---|---|---|
| 配置隔离 | `CODEX_HOME=<run_dir>/codex-home`，生成 `config.toml`（`mcp_servers.flux`：URL + Bearer） | `OPENCODE_CONFIG=<run_dir>/opencode.json`（只放 mcp 块：remote + URL + Authorization 头；实测为合并语义，全局 provider 配置保留，密钥无需复制进 run 目录） |
| 启动命令 | `codex exec ... "<instruction>"`（含跳过 git 仓库检查等必要参数，按 0.157.1 实测校准） | `opencode run "<instruction>"`（按 1.18.29 实测校准） |
| 认证 | 已有 ChatGPT 登录态，可直接验证 | **无需登录**：全局配置已内联 provider，2026-10-05 实测默认模型真实调用成功；`opencode auth list` 显示 0 credentials 只是 auth.json 口径，探测逻辑需改为基于配置解析，避免"auth missing"误判 |
| env | `FLUX_RUNTIME/FLUX_RUN_ID/FLUX_TASK_ID/FLUX_AGENT_ID/FLUX_WORKSPACE/FLUX_MCP_ENDPOINT`（复用 protocol.build_runtime_env 并扩展） | 同上 |

说明：两家的 MCP 配置文件名与字段以**本机实测为准**（设计给定方向，实现时逐条校准；这正是 P2-1 契约要解决的差异点）。OpenCode 已在 2026-10-05 完成两项预验证（证据 `/tmp/opencode-smoke/run1.log`、`run2.log`）：默认模型 `deepseek/deepseek-flash` 真实调用成功；`OPENCODE_CONFIG` 指向只含 `model` 的自定义配置时换模型生效且全局 provider 解析成功——确认合并语义，Run 配置无需复制任何用户密钥。

### 7.3 事件、终态与资源

- stdout 逐行 → `parse_event`（现有 JSON/text 双解析）→ 统一事件；
- 进程退出：exit 0 → `completed`；非 0 → `failed`；cancel/timeout → supervisor 升级链路（SIGTERM→grace→SIGKILL→confirm）；
- Run 结束：撤销令牌、写终态与 `finish_reason`，run 目录保留供排障（配置内令牌已失效）。

### 7.4 安全边界（与 DSH 同标准）

外部 CLI Agent 的 MCP 面与 DSH 完全一致：只能读文件 + `proposal.create`，**没有任何直接落盘能力**；apply/git 仍必须人审；进程组隔离不误伤 Flux 自身。

### 7.5 验收（真实链路 E2E，对应停止线第 4 条）

沙箱项目（复用上轮 `/tmp/flux-e2e` 结构）三种 runtime 各跑一次真实任务：Task → start → 事件流含 tool_call → MCP 提案 ≥1 → 人审 accept → apply → pytest → git commit；证据落 `/tmp/flux-runtime-e2e/`。

---

## 8. 收口测试设计（功能全做完后执行一次）

- **前置**：全部改动合入、CI 全绿、`make verify` 通过。
- **四层范围**：
  1. 自动回归：pytest 全量 + `make verify`；
  2. 新增专项：本设计 §2.3 / §3.2 / §4.3 / §5.3 / §7.5 全部用例（真实进程、真实 API、证据落盘 `/tmp/flux-acceptance-v2/`）；
  3. 上一轮验收全量重跑（已确认需要）：第一~四阶段全部 92 条用例逐条重跑，含上轮判 `NOT IMPLEMENTED` / `KNOWN FAILURE` 的条目按新实现重新判定；不得抽样、不得只跑"关键链路"；
  4. Windows：CI `windows-latest` 全绿 + Windows 真机按清单跑完整闭环（见 §11）。
- **通过标准**：新增用例全 PASS；原 `NOT IMPLEMENTED`（T-ROLL-001/002、T-DIFF-003、T-AGENT-002/003、OpenCode/Codex 启动 8 项）全部转 PASS；原 KNOWN FAILURE（T-APPLY-008）转 PASS；无新增 P0/P1。
- **产出**：《Flux 验收报告 v2》，按测试计划 §31 模板逐条留证。

---

## 9. 移动端方向（先设计 demo，再实现）

- **定位**（Scope §5）：离开电脑后的远程查看与控制，不是移动 IDE。能力：查看任务 / Run 状态与进展 / Proposal 与 Diff / Accept / Reject / Cancel / 错误 / 最终结果 / 重新进入运行中的任务。
- **技术方向**：在现有 web-dashboard（React+Vite+Tailwind，已有 860px 断点 hook）上新增移动视图，手机浏览器直接访问，不另起项目、不引小程序。
- **网络可达（硬需求：异地 / 公网，已确认）**：手机在任意网络（4G/5G/外部 WiFi）下都必须能访问 Flux，不接受"仅局域网可用"。两种部署形态分别处理：
  - **Flux 在 Linux 服务器**：已有公网入口，补齐认证 + TLS 后手机直连；
  - **Flux 在家庭 Windows（NAT 后、无公网 IP）**：需内网穿透 / 组网。已确认**允许把组网工具内置进 Flux**，候选路线（选型在移动端设计阶段联网核实后定稿）：
    1. **反向隧道（自托管优先）**：以服务器为中转、家庭机主动外连，无需路由器端口映射；可把隧道客户端做成 Flux 的可选内置组件（设置里一键开关）；
    2. **组网工具**：将手机与家庭机纳入同一虚拟局域网（自建或托管）；
    3. **托管隧道**：免公网 IP，但依赖第三方服务。
  - 选择标准：自托管优先（不依赖第三方账号）、手机端零/低配置、断线自愈、链路状态可诊断（Flux 里能看到隧道 / 连接状态）。
- **安全前置（硬约束）**：任何公网可达之前，必须先完成单用户认证 + TLS，不允许无认证裸奔；开工时先核对 REST API 现有鉴权现状，缺则与移动端同批补齐。
- **PWA 与实时性**：实时性方案（候选：快照轮询 vs SSE，须满足"后台回前台状态一致、刷新及时"）与是否 PWA（桌面图标 / 全屏 / 通知）在移动端设计阶段定稿；PWA 依赖 HTTPS，与安全前置的 TLS 要求同向。
- **交付流程**（遵循既有约定）：先出**可交互 demo**（公网可直接打开的地址）→ 真机验收（先用手机、先用 4G/5G 验证异地链路，而非只连家里 WiFi）→ 再实现 → 真机全面复验。

### 9.1 定案（2026-10-05，决策见 §13）

- **云端形态（路线 B）**：B 机（`115.159.125.254`）新建**独立的 Flux Cloud 服务**（独立 systemd 单元 + 独立端口 + 独立 SQLite），只做账号 / 设备注册 / 中继转发 / 心跳，**不碰业务数据**；**不动现有 `relay-b.service` 与 NOVA/Lexi/Maestro 生产服务**。
- **鉴权**：GitHub OAuth 多用户登录；每个用户只能看到并控制**自己账号名下**的设备，跨账号一律拒绝。
- **域名**：`flux.qiuli55.top` 从 A 机迁到 B 机（现解析到 A 机 `159.75.222.60`，是过时演示页）。
- **数据面**：桌面端 Flux 主动外连云端建立反向隧道（NAT 后无需公网 IP / 端口映射），手机请求经云端中继到该设备的 Flux。
- **B 机实测约束**（只读侦察）：2 vCPU / 内存 1.9G（可用约 **1.0G**，swap 已用 843M）/ 磁盘余 35G → 云端必须极轻，**放不下第二套完整 Flux**。

## 10. 桌面端打包方向

- **方案对比**：

| | Electron（已选定，ADR-001） | Tauri 2 |
|---|---|---|
| 工具链 | 纯 Node，无额外系统依赖 | 需 Rust 工具链 + Linux webkit2gtk |
| 包体 | 约 100–150 MB | 约 10–20 MB |
| 与现有前端 | Vite 产物零改造 | 基本零改造 |
| 本机落地风险 | 低 | 中（构建链依赖下载） |

- **结构**：PyInstaller（onedir）打包 Python 后端（含 alembic 与静态资源）→ Electron 主进程以 sidecar 启动后端（随机本地端口 + 健康检查）→ 窗口加载前端，指向本机 API。
- **数据目录**：沿用 `~/.flux`（数据库/备份/DSH home），**卸载不删数据、重装可继续使用**（对应"卸载/重装行为可解释"）。
- **产物**：Linux `.AppImage` / `.deb`（服务器自用）+ Windows 安装包（NSIS `.exe`，CI 的 windows runner 构建；家庭使用发行目标）；macOS 暂不做。双产物分别按 Scope §6 清单验证"安装→启动→配置→建项目→Agent 任务→Proposal→Apply→Test→Git"全闭环，Windows 侧另附逐条清单在真机执行（见 §11.5）。
- **验证闭环**：严格按 Scope §6 清单逐项实测，包括卸载/重装行为。

---

## 11. W-1 跨平台支持（Windows）设计

### 11.1 背景与结论

使用场景已明确：**Linux 版跑在服务器（自用），Windows 版跑在家庭电脑（女朋友家里用）**。因此 Windows 从"可选"升级为**发行目标平台**，与移动端、桌面端打包同属发布验收内容。

现状结论（2026-10-05 代码调研）：

- **当前代码不能在 Windows 运行**——卡点只在 Flux 自己的进程管理层（4 处，见 11.2），不涉依赖与上层逻辑；
- Python 依赖全部跨平台（fastapi / uvicorn / SQLAlchemy / httpx / alembic 等；SQLite 双向可用）；
- DSH 官方 runtime 提供 Windows wheel（`deepseek-harness-sdk` 的 runtime 包有 `win_amd64` 平台 wheel，2026-10-05 PyPI 实测查询，当前使用版本 `0.1.5rc1`）；
- CI 目前仅 ubuntu（[ci.yml](../.github/workflows/ci.yml) L17）。

### 11.2 四处硬 POSIX 依赖

| # | 位置 | 现状 | Windows 上的后果 |
|---|---|---|---|
| 1 | [dsh_client.py](../backend/flux/core/agent_runtime/dsh_client.py) L49-61 `_SETSID_SHIM` | `/bin/sh -c` 垫片中调 `os.setsid()`，保证 pid==pgid==sid | Windows 无 `os.setsid`：抛 `AttributeError`（不是 `OSError`，现有 except 拦不住）→ **Run 根本起不来** |
| 2 | [supervisor.py](../backend/flux/core/agent_runtime/supervisor.py) L653-695、[adapters/base.py](../backend/flux/core/agent_runtime/adapters/base.py) L141-173 | `os.killpg(pgid, SIGTERM)` → grace → `SIGKILL` | Windows 无 `killpg` / `SIGKILL` → **Cancel、超时清理、孤儿回收链路全断** |
| 3 | [supervisor.py](../backend/flux/core/agent_runtime/supervisor.py) L777 | `os.kill(pid, 0)` 探活 | Windows 上 `os.kill` 语义是发给进程信号且需 handle 特例（接近 TerminateProcess）→ **探活变成击杀**（最危险的一处） |
| 4 | [dsh_client.py](../backend/flux/core/agent_runtime/dsh_client.py) L266 | `chmod 0600` 保护 Run 配置文件 | Windows 上 chmod 不生效（无报错但无效果）→ 敏感配置文件保护弱化，需 ACL 替代或显式记录弱化 |

### 11.3 设计：进程 / 文件平台原语层（与 P2-1 合并实施）

新增 `backend/flux/core/agent_runtime/platforms.py`，把上表 4 处收敛为**四个平台原语**；此后 `dsh_client` / `supervisor` / `adapters` 不再直接触碰平台 API：

| 原语 | POSIX 实现（现状收编，行为不变） | Windows 实现 |
|---|---|---|
| `spawn(argv, env, cwd)` | 现有 `/bin/sh -c 'exec …'`（`os.setsid` 垫片） | `subprocess.Popen(argv, creationflags=CREATE_NEW_PROCESS_GROUP \| CREATE_NO_WINDOW)`；Windows 无 exec 语义，不套 sh，直接 argv |
| `terminate_tree(pid)` | `os.killpg(SIGTERM)` → grace → `killpg(SIGKILL)` → confirm 清理 | 向进程组发 `CTRL_BREAK_EVENT` → grace → `taskkill /PID <pid> /T /F` → 用 `is_alive` 确认 |
| `is_alive(pid)` | `os.kill(pid, 0)` + `ProcessLookupError` 判定 | `ctypes` 调 `OpenProcess(SYNCHRONIZE)` + `WaitForSingleObject(handle, 0)` 判是否已退出；**只读判定，绝不 Terminate** |
| `secure_file(path)` | `os.chmod(path, 0o600)` | `icacls <path> /inheritance:r /grant:r %USERNAME%:F`；失败则记 warning 并在 Run 元数据标注"文件权限弱化"，不静默 |

约束：

- 原语是唯一接触平台差异 API 的地方；同一套单测双平台跑（CI ubuntu + windows 各执行一遍）；
- `CREATE_NEW_PROCESS_GROUP` 对应 POSIX 侧 `setsid` 的隔离语义：CTRL_BREAK 只达本进程组，不误伤 Flux 自身；
- DSH runtime 在 Windows 上直接 spawn 官方可执行文件（无 sh 包裹），与 POSIX 侧垫片等效。

### 11.4 改造点（与 §6 同批实施，避免改两遍）

- [dsh_client.py](../backend/flux/core/agent_runtime/dsh_client.py)：`_SETSID_SHIM` → `platforms.spawn`；`chmod 0600` → `platforms.secure_file`；
- [supervisor.py](../backend/flux/core/agent_runtime/supervisor.py)：`killpg` 段 → `platforms.terminate_tree`；探活段 → `platforms.is_alive`（对账矩阵、心跳、超时逻辑全部不动）；
- [adapters/base.py](../backend/flux/core/agent_runtime/adapters/base.py)：`start_new_session=True` → `platforms.spawn`。

### 11.5 验证（三层）

1. **CI 双平台**：`ci.yml` 新增 `windows-latest` job（后端 ruff + pytest；前端构建保持现状），与 ubuntu job 同构；双绿是硬性前置。
2. **平台原语单测**：新增 `tests/test_agent_runtime_platforms.py`，覆盖 spawn / terminate_tree（含抗 SIGTERM 的顽固进程、已退出进程）/ is_alive / secure_file 的正常与边界；两侧 CI 均执行。
3. **Windows 真机验收（需你配合）**：在真实 Windows 电脑上按逐条清单跑完整闭环——安装 → 启动 → 建项目 → 真实 Agent 任务 → Proposal → Review → Apply → Test → Git → Cancel / 超时 → 重启恢复；清单在进入该阶段时提供（每步"打开什么、点什么、看到什么"，不含任何密钥）。与桌面端 Windows 安装包验收合并执行。

### 11.6 明确不做

- Windows 服务化（Service / nssm）与开机自启 → Post-MVP；
- macOS 产物 → 当前无使用需求，暂不做。

---

## 12. 停止线映射（本设计 → Scope §8）

| 停止线 | 由谁保证 |
|---|---|
| 1. P0 全部解决并验证 | §2（T-APPLY-010~014）+ §3（8 项故障注入） |
| 2. Delete / Rollback 完成并验证 | §4（T-DEL-001~006）+ §5（T-ROLL-003~009） |
| 3. Agent Runtime Contract 完成 | §6（DshRuntime 零回归 + CliRuntime 真实链路） |
| 4. DSH / Codex / OpenCode 目标链路验证 | §7.5（三 runtime 真实 E2E） |
| 5. 手机终端核心能力可用 | §9（demo → 实现 → 真机验收） |
| 6. 桌面端安装→使用闭环 | §10（Linux + Windows 双产物全流程实测） |
| 7. 50 个真实任务 Benchmark | Scope §7（收口测试后执行，另行出执行方案） |
| 8. KPI 达标 | PERSONAL_MVP_ACCEPTANCE_METRICS.md（另行对标） |
| 9. 无新 P0/P1 Blocker | §8 收口测试判定 |

> 附：Windows 为发行目标平台（见 §11），上述第 3 / 4 / 6 条在 Windows 上同样执行（CI `windows-latest` + 真机清单）。

---

## 13. 决策记录（2026-10-05 已拍板，结论回填）

1. **桌面端技术栈**：✅ **沿用 Electron**（Master Spec ADR-001 已选定；对比表作为选型依据存档在 §10）。
2. **OpenCode 认证**：✅ 无需任何登录动作——本机全局配置已内联 provider（deepseek / agent-plan / minimax），实测真实调用成功；`OPENCODE_CONFIG` 合并语义也已验证。P2-2 实现时修正探测器对 OpenCode 认证状态的误判口径。
3. **手机端形态**：✅ **异地网络为硬需求**（4G/5G/外部 WiFi 均可访问，不满足于局域网）；**允许内置组网 / 隧道工具**（方向与候选见 §9）；PWA 与实时性方案在移动端设计阶段定稿。
4. **收口测试深度**：✅ **需要**——除四层范围外，上一轮全部 92 条用例逐条重跑（已写入 §8 第 3 层）。
5. **Windows 真机验收**：⏳ 用机与时间明天确定；不阻塞当前开工（该验收在收口测试阶段才执行）。
6. **移动端远程控制**：✅ **路线 B**——B 机新建**独立 Flux Cloud 服务**，只做账号 / 设备注册 / 中继转发 / 心跳，不碰业务数据、不动现有 `relay-b.service` 与生产服务（详见 §9.1）。
7. **登录方式**：✅ **GitHub OAuth**（多用户；每个用户只能看到并控制**自己账号名下**的设备）。
8. **域名**：✅ `flux.qiuli55.top` 从 A 机迁到 B 机（属 B 机写操作，按红线先给命令与目标再执行）。
9. **执行顺序**：✅ **先收口 P0-1，再做账号 + 移动端**。
10. **Apply 崩溃恢复语义**：✅ 崩溃后目标内容既非原文也非提案内容时**一律不覆盖**（含"修改类有备份"的情况），转人工决策；UI 用"变更区横幅 + 点开弹层"（非阻塞），详情并列看三版本，二选一「覆盖备份 / 保持现状」；「保持现状」后提案置 `FAILED`（作废）。算法与决策入口见 §2.2。
11. **错误日志上传**：✅ 只上传 ERROR / CRITICAL 级 + 异常堆栈；**每次启动 Flux 时上传一次**，范围是**上一次运行期间**（上次打开 → 本次打开之间）产生的错误日志；按用户 + 设备隔离存 B 机。详见 §14。

---

## 14. 错误日志上传（2026-10-05 追加，属账号 / 云端批）

- **范围**：只上传 **ERROR / CRITICAL 级别 + 异常堆栈**；`INFO` / `WARNING` 不上传。
- **时机与区间**：**每次启动 Flux 时上传一次**，上传内容是**上一次运行期间**（上一次打开 → 本次打开之间）产生的错误日志——即上次会话的"错误账本"，即使上次是崩溃 / 强杀退出，也能在下次启动时补交。
- **归属与隔离**：按 **用户 + 设备** 存放，B 机路径形如 `<日志根>/<用户>/<设备>/<日期>.log`；每个用户只看得到自己的。
- **脱敏**：用户名、路径前缀、密钥与令牌样式的串一律打码；凭据绝不外传（沿用红线）。
- **容量**：B 机侧做按用户配额 + 轮转 + 总容量上限（B 机同时在跑生产服务，不能被日志撑爆）。
- **通道**：走 Flux Cloud 的同一套上报接口与鉴权。
- **待确认**：每条错误是否附**前后各 20 行上下文**（建议附，否则只看到异常行难以定位根因）。

---

## 15. Agent Terminal Console（2026-10-05 新需求，待拍板）

需求全文见 `doc/AGENT_TERMINAL_CONSOLE.md`（origin/main 提交 `2b2fcb6`）：独立终端窗口，实时看 AI 执行的命令与输出，并支持人工接管 Stop / Force Stop。

**实测到的硬冲突（不先解决，做出来就是空壳）**：

1. **Flux 自己没有 shell / PTY runtime**。内置 Agent 是 DSH，命令在 DSH 自己的 runtime 进程里执行；Flux 只拿到 `session.event` 通知，而 `core/agent_runtime/dsh_events.py` 目前只映射 `assistant/message`（助手文本）与 `turn/end`（finish_reason）——**"执行了哪条命令、输出是什么"取不到**。
2. **Agent 面禁用终端工具**：`core/mcp/tools/__init__.py` 的 `FORBIDDEN_TOOL_NAMES` 把 `terminal.execute` / `shell.exec` 列为硬禁。要让 Agent 的命令经 Flux 执行，必须放开这条禁令，属安全模型变更，需明确决策。
3. **没有桌面端**：`apps/desktop/` 只有 `.gitkeep`，"独立窗口"当前只能用浏览器新窗口/标签近似，真正的独立窗口要等 Electron 打包（§10）。
4. **没有实时通道**：后端无 SSE 端点，前端是轮询（`SoloPage.tsx` 的 `setInterval`）。终端实时输出需要新增流式端点。

**两条路线**：

- **路线 A（推荐，职责不变）**：Flux 提供统一的 **Shell Runtime + Terminal Session**，Agent 与用户都经它执行。需要：放开 MCP 的 terminal 工具（受权限与工作区边界约束）、新增 terminal session/event 落库、SSE 流式输出、把 Stop / Force Stop 接到既有 `RunSupervisor` 的进程树终止链路（SIGTERM → grace → SIGKILL → confirm，这部分已实现）。这样"AI 命令 + 输出 + 来源标记"才是真实的。
- **路线 B（不改职责，只能只读）**：不放开 Agent 终端工具，只做"Flux 自己执行的命令（Apply 的测试命令）+ DSH 事件文本 + MCP 工具调用记录"的观察面板。真实，但覆盖不到"AI 执行的每条命令与输出"，无法通过文档 §14 的"基础执行"验收。

**建议**：走路线 A，但分期——先做 Terminal Session + 用户命令 + 实时流 + Stop/Force Stop（不依赖放开 Agent 工具，可独立验收），再接入 Agent 命令。

**待拍板**：① 是否允许 Agent 经 Flux 的 Shell Runtime 执行命令（即放开 MCP terminal 工具）；② 桌面端打包是否与本功能同批。

---

## 附：开工时需要的现状定位（索引）

- Apply：`apply_engine.py` L130 / L249（预检）/ L288（回滚）；`service.py` L42（状态机）/ L223（accept）/ L266（apply）；`repository.py` L198（结果落库）；`backup.py` L23。
- 生命周期：`supervisor.py` L124 / L281（finish）/ L334（cancel+heartbeat）/ L407（timeout）/ L450（对账）/ L496（启动恢复）/ L629（进程清理）；`config.py` L102。
- 提案：`write_tools.py` L28（schema）/ L50（handler）；`proposal_parser.py`；`read_tools.py` L119（proposed_hash）。
- 运行时：`dsh_client.py` L330（start_run）/ L370（生命周期）/ L88（finish_reason 映射）；`adapters/base.py` L83；`generic.py` L127；`tasks.py` L322（start 链路）；`main.py` L35（lifespan）。
- 前端：`router.ts`、`useMediaQuery.ts`（860px）、`api/client.ts` L209（startTask）。
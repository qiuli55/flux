# Flux Personal MVP 收口 · 交接文档

> 创建：2026-10-05 ｜ 基线提交：`9aeedad docs: define final Personal MVP release scope`
> 本文档面向"接手继续做"的人（人或 AI 会话）：如实记录做到哪、怎么验证、下一步做什么。
> 权威计划以 [PERSONAL_MVP_RELEASE_DESIGN.md](PERSONAL_MVP_RELEASE_DESIGN.md)（设计，未提交）与
> [PERSONAL_MVP_RELEASE_SCOPE.md](PERSONAL_MVP_RELEASE_SCOPE.md)（范围，已提交）为准；本文只做状态交接。

---

## 0. 执行顺序（设计 §0，已评审）

```text
P0-1 Apply 崩溃恢复 ← 当前在这里（代码已写完，测试文件未建，make verify 未跑）
  ↓
P0-2 Run 生命周期故障注入验证（8 项矩阵）　　P1-1 Delete Proposal（独立）
  ↓
P1-2 Rollback（依赖批表 + Delete 语义）
  ↓
W-1 跨平台进程原语 + P2-1 Agent Runtime Contract（合并实施）
  ↓
P2-2 Flux 拉起外部 CLI Agent（Codex / OpenCode 真实链路）
  ↓
收口测试（四层：全量回归 + 新增专项 + 上轮 92 条逐条重跑 + Windows 真机）
  ↓
移动端（先交互 demo → 实现 → 真机复验）→ 桌面端打包（Linux + Windows）→ 50 任务 Benchmark → 冻结
```

## 1. 当前工作区状态（git）

- 基线：`9aeedad`；**以下改动全部未提交**（提交/推送需先询问用户）：
  - P0-1 改动：`backend/flux/{enums.py, main.py, container.py, models/__init__.py}`、
    `backend/flux/core/event/bus.py`、`backend/flux/core/virtual_workspace/{apply_engine.py, backup.py, repository.py, service.py, batch_repository.py(新)}`、
    `backend/flux/cli/main.py`、`backend/flux/models/apply_batch.py(新)`、
    `backend/migrations/versions/a1c5e7b93f24_add_apply_batches.py(新)`
  - `doc/PERSONAL_MVP_RELEASE_DESIGN.md`（设计稿，是否入库待用户拍板）
  - `docs/UI_TEST_REPORT.md`：**故意保留的本地改动，不要碰、不要提交**。

## 2. P0-1 Apply 崩溃恢复：已完成 / 未完成

### 2.1 已完成的代码（均已落盘）

| 文件 | 改动摘要 |
|---|---|
| `flux/enums.py` | `VirtualChangeStatus` 新增 `APPLYING`；新增 `ApplyBatchStatus`（in_progress/applied/failed/recovered/needs_attention/rolled_back） |
| `flux/models/apply_batch.py`（新） | `apply_batches` 表模型：status / change_ids(JSON) / phase / backup_root / error / recovery_note / finished_at |
| `migrations/versions/a1c5e7b93f24_add_apply_batches.py`（新） | 建表 + status 索引；`down_revision=b7e2f4a9c1d3`（当前迁移链头） |
| `core/virtual_workspace/batch_repository.py`（新） | 批日志读写：create / get / list_in_progress / list_recent / set_phase / finish |
| `core/virtual_workspace/backup.py` | 新增 `BACKUP_RELATIVE_ROOT`、`BACKUP_PREV_SUFFIX`；重试覆盖备份前先留 `.prev` 快照 |
| `core/virtual_workspace/apply_engine.py` | `apply_many` 新增 `on_phase` 阶段回调（prepared→backed_up→writing→verifying→testing）；新增 `current_root()`；新增模块级恢复算法 `recover_batch_on_disk` / `_recover_one`（幂等，只认磁盘事实，与正常 Apply 共用 `_APPLY_LOCK`） |
| `core/virtual_workspace/repository.py` | 新增 `claim_applying`：accepted→applying 的 CAS 抢占，并发第二方 409 |
| `core/virtual_workspace/service.py` | `__init__` 注入批仓储 + `_active_batches` + `_recovery_lock`；`apply_many` 重写（懒恢复 → 建批 → 抢占 → 阶段日志 → 三路异常处理 → 落终态）；新增 `recover_interrupted_applies` / `_recover_batch` / `_finish_batch` / `_phase_journal` / `_release_applying` |
| `core/event/bus.py` | `Events.APPLY_RECOVERED = "apply.recovered"` |
| `container.py` | 装配 `batch_repo = ApplyBatchRepository(...)` 并注入 `VirtualWorkspaceService` |
| `main.py` | lifespan 启动时调 `container.workspace.recover_interrupted_applies()`（与 Run 的 startup_recovery 并列） |
| `cli/main.py` | `proposal list/show` 对 `applying` 状态附带恢复提示 (`APPLYING_HINT`) |

关键设计（细节见设计 §2.2）：

- **写入顺序**：批记录先于任何磁盘操作落库；阶段推进逐次提交；崩溃时"盘上做到的"最多领先日志一步。
- **恢复算法**：只认磁盘事实、不按 phase 猜。备份存在→目标已是 original 跳过，否则写回校验；无备份→不存在跳过 / hash==proposed 且 original_content==""（新建类）删除 / 外部修改或备份缺失→`needs_attention` 不覆盖。整段幂等。
- **并发防护三层**：`_APPLY_LOCK`（进程内串行化磁盘）+ `claim_applying` CAS（同提案并发抢占）+ `_active_batches`（懒检查跳过本进程正在跑的批，防误恢复）。
- **线程→事件循环写库**：`_phase_journal` 用 `asyncio.run_coroutine_threadsafe(...).result()` 阻塞等待阶段持久化，保证"日志先于磁盘操作"。

### 2.2 已完成的自检（2026-10-05）

- `ruff check` 通过；`ruff format --check` 通过（已格式化 4 个新文件）。
- `pytest tests/test_virtual_workspace.py tests/test_apply_engine.py -q` 全绿（既有回归未破）。
- **未跑全量 `make verify`**（ruff / OpenAPI 契约 / 迁移往返 / 全量 pytest 五步）。

### 2.3 未完成（P0-1 的收尾）

1. **新建 `backend/tests/test_apply_recovery.py`**，覆盖（设计 §2.3 的 T-APPLY-010~014 + 边界）：
   - 正常 apply：批 `applied`、phase 序列完整、提案 `applied` 且 `backup_path` 有值；
   - 崩溃中途（已写盘）：手工造 `in_progress` 批 + 提案置 `applying` + 文件已写且有备份 → 恢复后文件回 original、提案回 `accepted`、批 `recovered`、发 `apply.recovered` 事件；
   - 恢复后重试 apply 成功且文件=提案内容（T-APPLY-012，无二次破坏）；
   - 重复恢复幂等（T-APPLY-013）；
   - 崩溃后外部修改 → 不覆盖、批 `needs_attention`、提案 `failed` 且 apply_error 含"外部修改"（T-APPLY-014）；
   - 新建文件崩溃（无备份 + hash==proposed）→ 删除后回 `accepted`；
   - 修改类但备份缺失（hash==proposed 且 original_content!=""）→ `needs_attention`，绝不删除；
   - 未配置 workspace_root 时恢复 → 批 `needs_attention`；
   - `.prev` 快照：重试覆盖备份前留档；
   - 预检失败契约保持：提案留 `accepted`、`apply_error is None`、批 `failed`。
2. **跑 `make verify`**（在 `/root/workspace/flux` 下），修任何回归。
3. 建议顺带补：并发场景下 `_active_batches` 防误恢复（可做成集成级用例或手动验证，见下）。

### 2.4 不可破坏的既有测试契约（回归易踩）

- 预检失败（文件被改 / 路径被占位 / 未配置根）→ 提案状态留 `accepted` 且 `apply_error is None`
  （`test_workspace_apply_precheck_conflict_keeps_status_accepted`、`test_apply_many_preflight_keeps_everything_accepted`）；
- 落盘后失败（测试不过等）→ `failed` 且 `apply_error` 含测试输出尾部；
- `applied` 是终态，不可再 reject/accept（`test_applied_proposal_is_terminal`）；
- 事件断言用 `events[-1]`（apply 现在会多发 accepted→applying 与终态事件，最后一条仍是终态）；
- `test_api.py::_seed_proposal` 直接构造 `VirtualWorkspaceService(ProposalRepository(...))`——批仓储必须保持可选参数。

### 2.5 手动验收 P0-1 的方法（供收口测试）

```bash
# 1) 起服务（源码运行）并准备提案、accept
# 2) 在 apply 落盘中途 kill -9 服务进程（大文件 + 慢测试命令更容易卡在 writing/testing 阶段）
# 3) 重启服务 → 观察日志与 DB：批 recovered、提案回 accepted、文件回 original
# 4) 再次 apply → 成功；对比 `.flux/backups/<change_id>/` 下 `.prev` 快照
# 5) 重复：崩溃后在外部改一次文件再恢复 → needs_attention（不覆盖）
```

## 3. 后续任务清单（每项开工前先读设计对应章节）

| 任务 | 设计章节 | 关键落点 |
|---|---|---|
| P0-2 Run 生命周期故障注入验证（8 项矩阵） | §3 | `dsh/supervisor`、`FluxDshClient`、`agent_runs`；超时三档、进程组清理、重启对账 |
| P1-1 Delete Proposal（op=delete 全链路） | §4 | `proposal.create` 载荷扩展、Apply 落盘删除、Diff 展示、MCP/API 契约 |
| P1-2 Rollback（API/CLI/前置校验/终态） | §5 | 复用 `apply_batches` 分组；`.prev` 快照；`rolled_back` 状态 |
| W-1 跨平台原语 + P2-1 Runtime Contract（合并） | §6 / §11 | 4 处硬 POSIX 依赖（setsid/killpg/SIGKILL/os.kill(pid,0)/chmod）；`config["runtime"]` 分发 |
| P2-2 拉起外部 CLI Agent（Codex/OpenCode） | §7 | `adapters/*`；免登录凭据内联（OpenCode 已实证）；修正探测误判 |
| 收口测试（四层 + 92 条逐条重跑 + 报告 v2） | §8 | 证据落盘 `/tmp/flux-acceptance-v2/`；`docs/UI_TEST_REPORT.md` 本地版是参考，不回退 |
| 移动端（先 demo → 实现 → 真机复验） | §9 | 见下节与本次对话结论 |
| 桌面端打包（Linux + Windows 双产物） | §10 | Electron（ADR-001）；PyInstaller onedir sidecar；数据目录 `~/.flux` |

## 4. 移动端现状核实（2026-10-05，为下一步设计阶段备料）

- 定位（Scope §5）：离开电脑后的远程查看与控制，不是移动 IDE。
- 前端：React + Vite + Tailwind，`apps/web-dashboard`，已有 `useMediaQuery` 860px 断点；**不另起项目**。
- 实时性现状：**前端目前是轮询**（`SoloPage.tsx` L452 `setInterval`）；后端 API **没有 SSE 端点**（`flux/api` 无 `StreamingResponse`/`text/event-stream`）。
- 鉴权现状：REST API **无任何鉴权依赖**（各路由只有 `Depends(get_container)`；MCP 面才有 agent token）→ 公网可达前必须先补单用户认证 + TLS（设计 §9 硬约束）。
- 网络可达（硬需求：异地/公网）：Linux 服务器形态已有公网入口（补认证+TLS 直连）；家庭 Windows（NAT 后）需内置组网/隧道组件，候选（反向隧道自托管优先 / 组网工具 / 托管隧道），**选型在移动端设计阶段联网核实后定稿**。
- 交付流程：先出**可交互 demo**（公网可直接打开的地址）→ 真机验收（先手机、先用 4G/5G 验证异地链路）→ 再实现 → 真机全面复验。

## 5. 环境与运行手册

- 虚拟环境：`/root/workspace/flux/.venv`（ruff/pytest/alembic 都在里面）。
- 全量自检：`cd /root/workspace/flux && make verify`（ruff → format check → OpenAPI 契约 → 迁移往返 → pytest）。
- 测试：`cd /root/workspace/flux && make test`；单文件：`backend/` 下 `../.venv/bin/python -m pytest tests/<file> -q`。
- 迁移：链头 `a1c5e7b93f24`；往返验证 `alembic -x db_url=sqlite+aiosqlite:///./tmp.db upgrade head` 再 `downgrade base`。
- 服务器上的部署实例：`/opt/flux`（前端 5180 → 后端 8030 的 e2e UI 服务；benchmark 工作区 `/opt/flux/bench-flux`）。
- **后台任务（跨会话保留，勿杀勿重跑）**：
  - `job-f1aea419aa594067ab0f026da9815652`：benchmark 的 retry apply 打印流（打在 `/opt/flux/bench-flux`；最近一条是测试命令 300s 超时导致的 apply 失败，属 benchmark 场景本身）；
  - `job-b32041d35329460eb60713c4f7ba5356`：e2e UI 服务器（5180→8030，日志里偶发 BrokenPipe 噪音属正常）；
  - `job-78b118000b3e4945821cad092349fec4`：日志为空。
  - 输出目录：`/tmp/trae-agent-toolhost-0/jobs/<job-id>/output.log`。

## 6. 硬约束与红线（用户已明确）

- 交付物**不出现占位符**：示例/文案/配置/文档一律真实具体，只有密钥、密码可留空待填。
- 密钥/PEM 不外传、不写入任何文档；对 B 机的写操作先给用户看命令与目标；删数据二次确认。
- 测试失败**不许改预期掩盖**；证据落盘；提交/推送前先询问用户。
- 设计类交付必须先有**可交互 demo**（能点、滑、长按、拖动的真页面，公网 IP+端口可直接打开），不留静态稿。
- 验收从紧：功能必须全部可用才算交付；平板验收不完时先手机验收，手机无问题才继续。
- 版本号/价格/兼容性类信息必须联网核实，引用资料 >30 天只作历史快照。

## 7. 已拍板与待决事项

已拍板（2026-10-05）：

1. 桌面端沿用 **Electron**（ADR-001，不做 Tauri）。
2. 手机端**异地网络为硬需求**（4G/5G/外部 WiFi 都要能访问），**允许内置组网/隧道工具**。
3. 上轮验收 **92 条用例全部逐条重跑**，不抽样。
4. Windows 真机验收**暂定次日**再定（安装包与机型待确认）。

待决：

- `doc/PERSONAL_MVP_RELEASE_DESIGN.md` 是否入库（提交前问用户）；
- 移动端设计阶段需用户确认：家里 Windows 的公网/端口映射条件、手机系统与是否接受安装组网客户端、移动端是否需要推送通知；
- Windows 真机验收用机（明天）。
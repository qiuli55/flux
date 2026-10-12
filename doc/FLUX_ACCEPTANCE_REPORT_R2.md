# Flux 验收报告 R2（缺口重跑 + 修复复验）

> 本报告为**真实执行记录**：结论全部来自真实进程、真实 HTTP 调用、真实浏览器操作与真实构建产物。
> 未改任何既有测试预期；未把未跑成的写成 PASS；未执行与受限项如实列出。
> 本文件为**新建**，不覆盖 `doc/FLUX_ACCEPTANCE_REPORT_2026-10-04.md` 与 `doc/FLUX_ACCEPTANCE_REPORT_V2.md`。

## 1. 元信息

| 项 | 值 |
|---|---|
| 被测仓库 | qiuli55/flux |
| 被测代码 | 本地 main `34e811c` + 工作区未提交改动（本轮 4 处修复在其中；**尚未提交、未推送**） |
| 执行时间 | 2026-10-10 ~ 2026-10-11（以证据文件时间戳为准），2026-10-12 汇总成文 |
| 执行环境 | Linux（服务器）；Python 3.10.12（仓库 `.venv`）；Node 20.18.1；git 2.34.1 |
| 验收实例 | `8865`（rt2：stub / real 双模式，可自由重启）、`8802`（UI 后端）、`5184`（vite dev，含本轮修复）；`5185`（git HEAD 对照，探针结束后已清理） |
| 证据落盘 | `/tmp/flux-accept-r2/evidence/`；镜像 `/root/flux-accept-r2-backup/`（仓库外，防 /tmp 清理） |
| 计划依据 | `doc/FLUX_CURRENT_IMPLEMENTATION_TEST_PLAN.md`（"当前实现"口径，70 个 Test ID） |

**范围**：
- ① 自动回归门禁重跑（`make verify` + 前端 vitest/build）；
- ② Runtime / CLI / Identity / `flux_context` / Install / Adapter（**§6–§13 缺口 30 个 ID 全部重跑**）；
- ③ 核心开发闭环与 Web（§5、§14–§21、§27：以既有真实执行证据沿用 + 本轮补充复验，见 §6 依据列）；
- ④ 真实 Agent（OpenCode / Codex 真实启动重跑；完整开发闭环沿用 V2 轮真实 E2E）。

**不含**：Benchmark V2（§24）、§22 七个真实项目逐项重跑、Windows / macOS 平台、长跑稳定性（§27，本轮未执行，见 §6）。

## 2. 结论摘要

- 缺口 30 个 ID（§6–§13）本轮**全部重跑**并落盘本轮证据。连同沿用项，**70 个 ID：66 PASS、1 BLOCKED、3 未执行**；本轮口径内 **0 FAIL / 0 KNOWN FAILURE**。
- **发现并修复 4 处问题**（含 1 处竞态缺陷：会把一次成功执行改判为失败），均已修复 + 回归 + 真实链路复验（详见 §3）。
- 变更后全量门禁：`make verify` → **664 passed, 2 warnings, exit 0**（354.43 s）；前端 `vitest` → **58 passed**（7 files）；`tsc -b && vite build` → **exit 0**（3.13 s）。
- 真实链路复跑：真实 **OpenCode 1.18.29** 由 Flux 拉起并 `completed`；真实 **Codex 0.157.1** 由 Flux 拉起但**因账号配额/认证限制失败**（独立 `codex exec` 同样失败——非 Flux 缺陷），Flux 如实报 `failed`、不粉饰。
- 历史判定修正（引用 V2 轮，见 §6）：T-DIFF-003、T-ROLL-001/002、T-APPLY-008、T-AGENT-002/003、T-OPENCODE-001~004 已复核为 **PASS**。
- 唯一 BLOCKED 为 **T-REG-002**（本轮改动未推送，无 CI 可对照，如实记录）；T-STAB-001~003 本轮**未执行**。

## 3. 本轮修复清单（4 处 + 2 处测试改动）

### 3.1 cancel 竞态：Run 已成功完成时取消，不得把任务改判为失败

- **现场（真实桩链路，run `22bbe5e6`）**：取消请求到达时任务仍是 running，而 Run 的终态已是 `completed`；旧实现把"非 cancelled"一律记 `failed` —— **一次成功的执行被改判成失败**，会误导用户并污染任务历史。
- **修复**（`backend/flux/api/v1/tasks.py` `cancel_task`）：`run_status is DshRunStatus.COMPLETED` 时按 Run 的真实结局把任务收尾为 `completed`，绝不覆盖。
- **回归测试**：新增 `backend/tests/test_solo_lifecycle_api.py::test_cancel_after_run_completed_keeps_task_completed`（替身精确构造"终态先行、桥接收尾在后"的竞态窗口）。
- **复现验证**：回退修复后运行该用例，实测失败（`assert 'failed' == 'completed'`）；恢复修复后通过。
- **真实链路复验**：`gap-cancel-race.json` 记录 12 次竞态窗口尝试（每次先等 Run 落 `completed` 再发取消）——全部返回 **409**「已处于终态 completed，无法取消」，任务保持在 `completed`，未再出现改判。

### 3.2 Run 从 DB 回读补齐 task_id

- `backend/flux/core/agent_runtime/dsh_client.py`：`_refresh_from_row` 与从行重建 `DshRun` 时补齐并规范化 `task_id`（带横线 UUID），修复重启/查询路径下 run 快照 task_id 丢失、与任务 UUID 形态不一致的问题。
- 测试同步：`backend/tests/test_dsh_client.py` 夹具补 `task_id` 字段。

### 3.3 前端错误体透出（FastAPI `{detail}`）

- `apps/web-dashboard/src/api/client.ts`：HTTPException 风格 `{detail: "…"}` 没有信封 message 时也透出后端原文（优先级 `envelope.message` → `detail` → 通用文案）。修复前 403 门禁文案（如「本地文件操作只允许 Flux 桌面端」）不能到达 UI。

### 3.4 Explorer 右键每次重读当前项目

- `apps/web-dashboard/src/components/explorer/ExplorerContextMenu.tsx`：抽出 `readProject()`，**每次 contextmenu 事件重读** `.view-ide` 的 `dataset.projectId`；修复切换项目后右键操作仍指向旧/空项目。

**测试改动合计 2 处**：① 新增 cancel 竞态回归 1 条；② `test_dsh_client.py` 夹具补字段 1 处（随 3.2）。**未修改任何既有测试预期。**

## 4. 判定统计（70 ID 口径）

| 结果 | 数量 | 说明 |
|---|---|---|
| PASS | 66 | §5(1 项) + §6–§21 全部（§12 含受限说明） |
| BLOCKED | 1 | T-REG-002：本轮改动未推送，无 CI 可对照 |
| 未执行 | 3 | T-STAB-001~003：连续任务 / 循环长跑，本轮未执行 |
| FAIL / KNOWN FAILURE | 0 | — |

## 5. 计数口径说明（57 / 63 / 70 / 92）

| 计数 | 含义 | 出处与展开 |
|---|---|---|
| **70** | 计划书的 Test ID 总数（§5–§21、§27） | §5(2)+§6(3)+§7(4)+§8(4)+§9(4)+§10(3)+§11(4)+§12(4)+§13(4)+§14(4)+§15(3)+§16(4)+§17(10)+§18(2)+§19(4)+§20(3)+§21(5)+§27(3) |
| **57** | V1 报告（2026-10-04）"结果总表"的数据行数 | 56 行 `| T-…` + 1 行 `| §22-Real E2E |` |
| **63** | V1 表按 ID 展开后的条数 | 57 行中有 2 行是折叠行（`T-OPENCODE-001~004`、`T-CODEX-001~004`），展开 +6 → 62 个 ID；再加 §22 行 1 条 = 63 |
| **92** | V2 报告引用的历史口径（"上一轮 92 条用例的重新判定"） | 本轮**未能从现有文档重建**该计数的展开清单（V1 表展开为 62 ID + §22，不等于 92）；92 的原始口径已不可考，本轮不再引用，统一以 **70 ID** 为准，差异如实记录 |

## 6. 30 缺口 ID 逐条判定（§6–§13，本轮重跑）

### §6 Flux CLI（证据：`gap-cli.txt`）

| Test ID | 结果 | 本轮关键证据（原始值） |
|---|---|---|
| T-CLI-001 | PASS | `--version` → `flux 0.1.0` exit=0；无参数 → usage exit=2；错误参数 → exit=2；全程无裸 traceback |
| T-CLI-002 | PASS | 顶层 + 全部子命令 `--help` exit=0；命令树与实现一致（`proposal` 现有 `list/show/diff/rollback/recovery`） |
| T-CLI-003 | PASS | 非法子命令 exit=2（argparse 列出合法 choice）；`task show not-a-uuid` → `bad_request`「任务 ID 非法：not-a-uuid」exit=1；不存在 UUID → `not_found`；`agents remove` 无记录 → `validation_error`；无裸 traceback |

### §7 Runtime / Server CLI（证据：`gap-runtime-lifecycle.txt`）

| Test ID | 结果 | 本轮关键证据（原始值） |
|---|---|---|
| T-RUNTIME-001 | PASS | 8865 启动 pid=1189128，`health ready=200`，启动 **2007 ms** |
| T-RUNTIME-002 | PASS | SIGTERM → **205 ms** 退出；端口释放；无残留进程；health 探测连接失败 |
| T-RUNTIME-003 | PASS | 重启 pid=1189309；installations 一致；agents 28→28；tasks 36→36 |
| T-RUNTIME-004 | PASS | `kill -9` 后立即重启 ready，**1656 ms**；installations 双 READY |

### §8 Runtime Identity / flux_context（证据：`gap-context.json`、`gap-mcp-auth.txt`）

| Test ID | 结果 | 本轮关键证据（原始值） |
|---|---|---|
| T-ID-001 | PASS | 真实 Run 子进程 env：`FLUX_RUNTIME=1` / `FLUX_RUN_ID` / `FLUX_TASK_ID` / `FLUX_WORKSPACE` / `FLUX_AGENT_ID` / `FLUX_MCP_ENDPOINT` / `FLUX_VERSION=0.1.0`；`flux_context` 回读 run/task/agent/workspace 与 DB 一致 |
| T-ID-002 | PASS | `context.get`（runA）返回 run.id=`9deae14f…`、task、workspace=`/tmp/flux-accept-r2/rt2/ws`、agent.id=`1a369234…`、capabilities 5 项、`policy.proposal_required=true`；MCP 无令牌 → 401「缺少 Agent 接入令牌」、坏令牌 → 401「Agent 接入令牌格式非法」（`www-authenticate: Bearer`，curl -D 原始报文落盘） |
| T-ID-003 | PASS | 切换 task：读 A→B→A 均正确、`no_bleed=true`；越权令牌 A 查 run B → run/task 均 `null`（不串） |
| T-ID-004 | PASS | 不存在 run → `run:null/task:null`（显式为空、不臆造）；非法 task_id → `isError`「validation_error: task_id 不是合法 UUID：not-a-uuid」；两端终态 → run null |

### §9 Install State（证据：`gap-cli.txt`、`gap-install.json`）

| Test ID | 结果 | 本轮关键证据（原始值） |
|---|---|---|
| T-INSTALL-001 | PASS | 全新库 `agents list=0`；受限 PATH 下 scan → opencode/codex 均 `NOT_INSTALLED`（reason=PATH 中未找到可执行文件）；`connect --all` 跳过（不假装接入） |
| T-INSTALL-002 | PASS | scan → connect(all) → 双 READY → 重启（pid=1186000）后仍双 READY（checks 全 true） |
| T-INSTALL-003 | PASS | scan×2 + connect×2 → DB 2 行（sqlite3 直查 `agent_installations`）、name 唯一、all READY、`no_corruption` |
| T-INSTALL-004 | PASS（P3 观察） | 直改 `status='bogus'` → scan HTTP 500（显式错误、不假装成功）；CLI 面结构化 `internal_error` exit=1、无 traceback；`remove → scan → connect` 恢复 READY |

### §10 Agent Adapter（证据：`gap-cli.txt`、`gap-runs-stub.json`、`gap-runs-real.json`）

| Test ID | 结果 | 本轮关键证据（原始值） |
|---|---|---|
| T-AGENT-001 | PASS | `POST /agents runtime=bogus` → 422 `literal_error`（'dsh','codex','opencode'）；CLI `agents connect bogus` → `validation_error`「未知的 Agent：bogus」+ known 列表 |
| T-AGENT-002 | PASS | 桩进程 argv（`run --format json` + bootstrap + instruction）、`FLUX_*` env 全套、CWD=`/tmp/flux-accept-r2/rt2/ws`；真实 OpenCode run 的 `runtime_env_of_child` 同套 env（含 `FLUX_TASK_ID`/`FLUX_RUN_ID`/`FLUX_MCP_ENDPOINT`） |
| T-AGENT-003 | PASS | exit 0 → `completed`；exit 3 → `failed`（「opencode CLI 进程以退出码 3 结束」）；真实 codex exit 1 → `failed`（finish_reason='1'）；SIGTERM 取消 → `cancelled` |

### §11 OpenCode Adapter（证据：`gap-runs-stub.json`、`gap-runs-real.json`）

| Test ID | 结果 | 本轮关键证据（原始值） |
|---|---|---|
| T-OPENCODE-001 | PASS | 真实 opencode 1.18.29（`/usr/local/bin/opencode`）由 Flux 拉起：run `a1a61f45…`、task `24625c57…`、pid 703440、start 200 → `completed` |
| T-OPENCODE-002 | PASS | 桩 completed：status `completed`、`exit_ok`、pid 已 gone、pgid 已死；输出经 `task_messages` 的 `run_finished` 消息可获得（content="stub 开始"、`output_available_via_messages=true`） |
| T-OPENCODE-003 | PASS | 桩 exit=3 → `failed`「opencode CLI 进程以退出码 3 结束」、`not_reported_success=true` |
| T-OPENCODE-004 | PASS | 取消 → 终态 **0.146 s**（子进程 1181752/1181753 全 gone）；抗 SIGTERM 桩 → **5.14 s** SIGKILL 升级、pgid 清空；重复取消 → 409 |

### §12 Codex Adapter（证据：`gap-runs-stub.json`、`gap-runs-real.json`、`gap-runs-real-codex-diag.txt`）

| Test ID | 结果 | 本轮关键证据（原始值） |
|---|---|---|
| T-CODEX-001 | PASS（受限说明） | 真实 codex 0.157.1 由 Flux 拉起：run `0181a948…`、pid 703707、start 200；执行因**账号配额/认证限制**失败（stderr `401 Unauthorized: Missing bearer…`；独立 `codex exec` 亦 exit=1 且「usage limit… try again at Oct 28th, 2026」——非 Flux 缺陷，如实记录） |
| T-CODEX-002 | PASS（桩）/ 真实完成受限 | 桩 codex-completed：`completed`、`exit_ok`、pid gone、pgid dead；真实正常完成因账号限制未达成（不粉饰） |
| T-CODEX-003 | PASS | 真实 codex 失败被识别为 `failed`「codex CLI 进程以退出码 1 结束」——provider/Agent 错误不误报为 Flux 成功 |
| T-CODEX-004 | PASS | 桩 codex-cancel：`cancelled`、pid gone、pgid dead、重复取消 409 |

### §13 Process Group Lifecycle（证据：`gap-runs-stub.json`）

| Test ID | 结果 | 本轮关键证据（原始值） |
|---|---|---|
| T-PROC-001 | PASS | 各场景终态后 `pid_after=gone`（正常完成与取消两条路径） |
| T-PROC-002 | PASS | 取消场景子进程 1181752/1181753 状态 gone，整组清理 |
| T-PROC-003 | PASS | SIGTERM 快路径 0.146 s；抗 TERM → 等满 grace 后 SIGKILL 升级 5.14 s；两者 `pgid_alive_after=false` |
| T-PROC-004 | PASS | 终态后重复取消 → 409 conflict（明确文案），任务保持终态、无重复清理异常 |

## 7. 40 沿用 ID 判定（§5 + §14–§27）

> 以下条目本轮**未逐条手工重跑**；判定依据为既有真实执行证据（V1 / V2 / `docs/UI_TEST_REPORT.md`）+ 本轮全量自动化回归（`make verify` 664 passed，覆盖 Workspace / Proposal / Diff / Apply / Git / Test / Runtime 链路）+ 本轮补充复验（T-REG-001、§21）。

| Test ID | 结果 | 判定依据 |
|---|---|---|
| T-REG-001 | PASS（本轮重跑） | `make verify`：664 passed / 2 warnings / 354.43 s / exit 0；5 环节全过（ruff check、210 files format、OpenAPI 契约、alembic 往返、pytest） |
| T-REG-002 | **BLOCKED（本轮未执行）** | 本轮改动未提交、未推送 → 无 CI 运行可对照。历史快照（不作本轮结论）：V1 修复后 run `37214906971` success；V2 轮 windows-latest job run `37263234115` 全绿。**推送后需补跑 CI 并回填本行。** |
| T-WS-001~004 | PASS ×4 | V1 §7 真实 API：建/用；读一致 + sha256；写入只改目标文件；边界 4/4 拒（`/etc/passwd`、`../outside.txt`、symlink、`.env`） |
| T-PROP-001~003 | PASS ×3 | 字段完整（original_hash/status=pending/diff/attribution）；重启后仍 pending；非法跃迁 409 |
| T-DIFF-001/002/004 | PASS ×3 | 新文件 diff 正确；+4/-1 与提案一致；proposed_hash 与落盘 sha256 一致 |
| T-DIFF-003 | PASS（V1 NOT IMPLEMENTED → 修正） | V2 轮 Delete Proposal 全链路：`kind=delete`、`added=0`、`proposed_content=NULL`、文件未动 → accept 后文件消失、批 applied |
| T-APPLY-001~007 | PASS ×7 | 正常 Apply（内容/hash/状态/备份）；409 hash conflict（expected/actual，不覆盖用户改动）；失败回滚（500 `apply_failed` + status=failed）；新文件失败无残留；写后 hash 校验；commit 只含预期修改；用户未提交修改保护 |
| T-APPLY-008 | PASS（V1 KNOWN FAILURE → 修正） | V2 轮 P0-1 崩溃恢复 + 人工决策：`kill -9` 注入复验（cover / keep / 重复决策 409） |
| T-APPLY-009 | PASS | symlink 写入被拒、`/etc/passwd` 未被改动 |
| T-APPLY-010 | PASS | 多文件批任一失败整体回滚 |
| T-ROLL-001 | PASS（V1 NOT IMPLEMENTED → 修正） | V2 轮单条回滚：内容恢复为 original、状态 `rolled_back`（真实 API E2E，逐字节比对） |
| T-ROLL-002 | PASS（V1 NOT IMPLEMENTED → 修正） | V2 轮整批/混合批回滚（new 删、mod 还原、delete 恢复）；二次回滚 409；回滚前外部改 409 未动盘 |
| T-GIT-001~004 | PASS ×4 | clean=true；用户改动如实 `M` 不归因；commit 只含 applied 提案；非 git 仓库 → 409 `not_a_git_repository`（stderr 原文） |
| T-TEST-001~003 | PASS ×3 | apply 时 `test_command` 真实执行 passed=true；失败阻断落盘 + 回滚；SIGKILL exit=137 被识别、不误报通过、回滚 |
| T-WEB-001 | PASS（本轮复验） | `vitest` 7 files / 58 tests passed（1.18 s）；`tsc -b && vite build` exit 0（3.13 s，dist 产出）；5184 dev 页面可开（受控探针加载 `/ide` 成功） |
| T-WEB-002 | PASS（本轮复验） | `client.ts` 修复后 FastAPI `{detail}` 原文透出到 UI（toast=「本地文件操作只允许 Flux 桌面端」）；vitest 全绿 |
| T-WEB-003 | PASS（沿用） | `docs/UI_TEST_REPORT.md` §4.2 审核弹层逐行 diff（`v3-37-review-dialog.png`）；§4.6 UI-601「看 Diff」跳转逐行 diff（`v3-78/79`） |
| T-WEB-004 | PASS（本轮复验） | 受控探针：右键菜单 8 项、README 右键 ok；切换项目后「新建文件」POST 指向当前项目（`a39ba130…`）；浏览器端写操作被桌面门禁 403 + 文案透出；HEAD 对照 posts=0、toast「当前没有可用的 Workspace Project」（旧代码缺陷现场） |
| T-WEB-005 | PASS（沿用） | `docs/UI_TEST_REPORT.md` §4.6 Git 用户体验 9/9（UI-601 看 Diff、UI-602 提交 `f27d8d5`、工作区干净） |
| T-STAB-001~003 | **未执行** | 连续 20+ 任务 / Agent 启停循环 / Proposal-Apply 循环需长跑；本轮未执行（如实记录，不判 PASS） |

**本轮补充复验说明**：§21 探针为 fixed（5184，含修复）与 HEAD（5185，`git worktree` 原版）双端对照，结果落盘 `ui-probe.json` + 双截图；HEAD 实例已在探针后清理（`git worktree remove`，仓库工作区不受影响）。

## 8. UI 专项证据（§21 探针，fixed vs HEAD 对照）

| 观察点 | fixed（5184，含本轮修复） | HEAD（5185，原版） |
|---|---|---|
| 初始项目 | `1458a84b-cfde-4d95-a2ad-b2bf371a2092`（验收项目A） | 同左 |
| 切换项目（点「验收项目B」） | toast「已切换到项目「验收项目B」」；项目变为 `a39ba130-1943-42de-824c-d87adc9fa7c7` | 同左（切换本身可用） |
| README.md 右键菜单 | 8 项（打开 / 打开 Human Terminal / 新建文件 / 新建文件夹 / 重命名 / 删除 / 复制相对路径 / 复制绝对路径） | 同左 |
| 「新建文件」→ POST | `POST /api/v1/projects/a39ba130…/files/file` → **403** `{"detail":"本地文件操作只允许 Flux 桌面端"}`（指向**当前**项目） | **posts=0**（请求从未发出） |
| toast 原文 | 「本地文件操作只允许 Flux 桌面端」（后端原文透出） | 「当前没有可用的 Workspace Project」（旧代码 mount 时读到 null，右键操作完全失效） |
| 截图 | `ui-probe-fixed.png` | `ui-probe-head.png` |

> 说明：浏览器端文件写操作被拒是**设计内**的桌面门禁（`X-Flux-Desktop` 头仅桌面端携带）；本项验收点是"请求指向正确项目 + 后端原文能到达 UI"，两项修复均生效。
> 口径噪声：早期交互式探针曾出现不可复现的「Not authorized」报文；受控 Playwright 探针（`ui_probe.py`）复核为标准 `{detail}` 文案，权威结论以上表为准。

## 9. 观察项与限制（如实记录）

1. **pidfile 观测矛盾（根因：测试夹具 unlink 竞态）**：一次观测中 pid 文件读到的进程号与实际不符，排查为测试夹具清理（unlink）与产品读取同名 pid 文件竞争所致；进程真实存在，**非产品缺陷**，后续用例复跑一致。
2. **`final_response` 恒空是架构事实**：`agent_runs` 表没有 `final_response` 列，Run 快照该字段恒空；**Run 输出经 `task_messages` 的 `run_finished` 消息可获得**（桩链路已取证 `output_available_via_messages=true`，content="stub 开始"）。
3. **桩「带子进程时 run 不落终态」为桩构造**：桩脚本拉起子进程并持有 stdout 管道时，读线程等不到 EOF，Run 不会立刻落终态；属桩行为构造，真实 SDK 场景未复现（SDK 读线程会回收）。
4. **T-INSTALL-004 两面对比（P3）**：HTTP 面为 500 裸文本「Internal Server Error」，CLI 面为结构化 `internal_error` 且无 traceback；行为正确（显式报错、可修复）但 HTTP 面未附修复 hint，记录不修。
5. **Codex 真实完成受限（环境，非缺陷）**：账号配额（"usage limit … try again at Oct 28th, 2026"）与 `401 missing bearer` 同时存在；Flux 外独立 `codex exec` 同样失败，Flux 侧如实落 `failed`。
6. **UI 探针首轮噪声**：交互式浏览器探针曾出现「Not authorized」不可复现报文；改用受控脚本后口径稳定（见 §8 说明）。
7. **运行实例**：8865（验收实例）与 8802/5184（UI 与前端 dev）保留用于复现；HEAD 对照实例 5185 已清理。

## 10. 证据丢失与重建说明

- 执行期间 `/tmp/flux-accept-r2/` 曾被沙箱整体清理一次（目录直接消失）。此后：
  - rt2 基础设施（`start-rt2.sh`、`stub-bin/`、`ws/`、DB）**重建后重跑**全部缺口脚本——当前 8865 为新库（`agents_count=28`、`tasks_count=36` 为重建后的累计数据，非历史库）；
  - 全部证据同步镜像到仓库外 `/root/flux-accept-r2-backup/`（`evidence-0303/`、`evidence-ui/`、`rt2-0303/`），此后未再丢失；
  - 丢失窗口之前的中间产物不再可查，本报告只引用**现存证据文件**（附录 A）与既有报告落盘结论。

## 11. 性能基线（本轮可测项）

| 项 | 值 | 出处 |
|---|---|---|
| Server 启动（stub） | 2007 ms | `gap-runtime-lifecycle.txt` |
| SIGTERM 优雅退出 | 205 ms | 同上 |
| `kill -9` 后重启至 ready | 1656 ms | 同上 |
| Run 取消（快路径，2 子进程） | 0.146 s | `gap-runs-stub.json` |
| Run 取消（抗 SIGTERM → SIGKILL 升级） | 5.14 s | 同上 |
| 全量门禁 `make verify` | 354.43 s（664 passed） | `make-verify-r2.log` |
| 前端单测 / 构建 | vitest 1.18 s；vite build 3.13 s | `frontend-vitest-build.log` |

计划 §26 的其余项（Proposal / Diff / Apply / Git / Test Runner 耗时、Dashboard 首屏、长任务 RAM/CPU）本轮**未系统测量**；历史零散参考：内置 DSH 真实模型往返 12.59 s（V1 §10）。

## 12. 未纳入 / 未执行说明（不含糊）

| 项 | 状态 | 说明 |
|---|---|---|
| §24 Benchmark V2（50 任务） | 未执行 | 需真实模型长跑与额度消耗，本轮未排期 |
| §22 七个真实项目逐项重跑 | 未执行 | 完整开发闭环证据沿用 V2 轮真实 E2E（OpenCode / DSH 全链）；本轮真实 Agent 验证为"启动到终态"级 |
| §27 T-STAB-001~003 | 未执行 | 连续任务 / 启停循环 / Apply 循环长跑 |
| T-REG-002 CI 对照 | BLOCKED | 改动未推送；推送后须补跑 CI 并回填 |
| Windows / macOS | 不在范围 | V2 轮记录：Windows CI job 全绿（历史快照）；真机验收仍待用户执行 |

## 附录 A：证据文件清单（`/tmp/flux-accept-r2/evidence/`）

| 文件 | 内容 |
|---|---|
| `gap-cli.txt` | T-CLI-001~003、T-AGENT-001、T-INSTALL-001（2026-10-10 全量 CLI 输出） |
| `gap-runtime-lifecycle.txt` | T-RUNTIME-001~004（启动/停止/重启/kill -9） |
| `gap-context.json` | T-ID-002~004 + MCP 401 + 越权/缺失/非法 task_id 用例 |
| `gap-mcp-auth.txt` | MCP 无/坏令牌原始报文（curl -D） |
| `gap-install.json` | T-INSTALL-002~004 + CLI 表面（含 DB 直查行） |
| `gap-runs-stub.json` | 6 个桩场景（opencode completed/failed/cancel/ignore-term；codex completed/cancel） |
| `gap-runs-stub-run1.json` | 桩首轮跑记录（含真实现场 run `22bbe5e6` 同批数据） |
| `stub-out-opencode-completed-run1.txt` | 桩进程 argv / env / CWD 原始记录 |
| `gap-runs-real.json` | 真实 opencode completed / 真实 codex 启动失败（配额）+ scan/connect 快照 |
| `gap-runs-real-codex-diag.txt` | Flux 外独立 `codex exec` 复现账号配额（exit=1） |
| `gap-cancel-race.json` | 12 次竞态窗口取消尝试（全部 409、任务保持 completed） |
| `make-verify-r2.log` | `make verify`：664 passed / 354.43 s / exit 0 |
| `frontend-vitest-build.log` | vitest 58 passed / build exit 0 |
| `ui-probe.json` + `ui-probe-fixed.png` + `ui-probe-head.png` + `head-vite.log` | UI 探针 fixed vs HEAD 对照 |

镜像（仓库外）：`/root/flux-accept-r2-backup/evidence-0303/`（全部 evidence）、`/root/flux-accept-r2-backup/evidence-ui/`（UI 探针）、`/root/flux-accept-r2-backup/rt2-0303/`（rt2 基础设施快照）。

## 附录 B：复现命令

```bash
# 验收实例 8865（stub 默认；RT2_MODE=real 切真实 opencode/codex）
bash /tmp/flux-accept-r2/rt2/start-rt2.sh

# 缺口脚本组（各自落盘 evidence；实例按需重启为对应模式）
/root/workspace/flux/.venv/bin/python /tmp/flux-accept-r2/rt2/gap_stub.py
/root/workspace/flux/.venv/bin/python /tmp/flux-accept-r2/rt2/gap_context.py
/root/workspace/flux/.venv/bin/python /tmp/flux-accept-r2/rt2/gap_install.py
bash /tmp/flux-accept-r2/rt2/gap_cli.sh
bash /tmp/flux-accept-r2/rt2/lifecycle.sh

# UI 探针（需 5184 / 5185 前端在跑）
/root/workspace/flux/.venv/bin/python /tmp/flux-accept-r2/rt2/ui_probe.py

# 全量门禁与前端
cd /root/workspace/flux && make verify
cd /root/workspace/flux/apps/web-dashboard && npx vitest run && npm run build
```

---

**报告说明**：本报告与既有 `doc/FLUX_ACCEPTANCE_REPORT_2026-10-04.md`（V1）、`doc/FLUX_ACCEPTANCE_REPORT_V2.md`（V2）并存；`docs/UI_TEST_REPORT.md` 与 `docs/FLUX_LOGIN_SYSTEM_DESIGN.md` 为本地未提交文档，作为证据源被引用，本轮未改动、未提交。
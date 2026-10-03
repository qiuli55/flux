# Flux Personal MVP 50 任务基准 · 收口报告

> 生成时间：2026-10-04（数据快照：2026-10-04 02:01，补跑收尾时刻）
> 判定依据：`docs/PERSONAL_MVP_TASK_BENCHMARK.md`（commit `db92620`）与 `docs/PERSONAL_MVP_ACCEPTANCE_METRICS.md`（commit `f7f9702`）
> 记录来源：`/opt/flux/e2e/bench/records/task-01..50.json` 与 `/opt/flux/e2e/bench/summary.md`（本报告数字均可按这两处复核）
> 定位：**记录现状 + 暴露口径问题**。本报告与全部代码改动已于 2026-10-04 按主题提交并同步至 origin/main。

---

## 0. 结论摘要

- 50 个任务全部执行完毕，无跳跑、无不可执行样本（不满足 `SAMPLE_INSUFFICIENT` 触发条件）。
- **端到端完成率 31/50 = 62.0%（目标 ≥95%，未达标）**。
- 19 个失败归因三类（详见 §4）：
  1. **Agent 两轮未产出提案：9 个**（9/14/24/28/30/35/47/48/50，全部在 opencode 子集）；
  2. **门禁红灯被如实回滚：7 个**（4/18/25/26/29/31/34，产品门禁行为正确，但任务未完成）；
  3. **异常任务判据/语义差异：3 个**（38/40/41，产品行为是安全的，判定依据待定，见 §4.3）。
- 专项类：Run 状态准确率 5/5 = 100%；Crash Recovery 1/1 = 100%；重启数据恢复 1/1 = 100%；安全红线逐条核对为 0。
- 不达标项**只记录、不改产品**，待用户统一决策（见 §10）。

---

## 1. 执行环境与口径

| 项 | 值 |
| --- | --- |
| bench 副本 | `/opt/flux/bench-flux`，每任务开工前 `git reset --hard dc7b0a3`（基线提交） |
| fixture 项目 | `/root/workspace/flux-usertest-app`，独立基线 `bf4dae8`（不能套用 bench 基线） |
| bench API / MCP | `http://127.0.0.1:8050` |
| fixture API / MCP | `http://127.0.0.1:8030` |
| 门禁脚本 | `/opt/flux/e2e/bench_gate.sh`：先清除子进程 `FLUX_*` 环境，再跑副本 `pytest -q` + `npm test` |
| 主执行器 | `codex-minimax`（Codex CLI + MiniMax-M3，`--provider codex`） |
| 降级执行器 | `opencode` + ARK `glm-5.3-flash`（`--provider opencode`，配置 `bench/opencode_bench.json`，权限 edit/write/webfetch=deny） |
| 异常任务 | 走 DSH 真实 Run（Cancel / 超时 / 杀子进程 / 重启 / 黑洞连接） |

**Provider 构成（判定集内）**：codex 8 个任务（1–6/8/10，10-03 早间轮）；opencode 34 个任务（7/9/11–35/38/45–50，10-03 16:02 → 10-04 02:01 补跑轮）；异常运行类 8 个任务使用 DSH（36/37/39–44），不涉及外部 provider。

**口径说明（影响 KPI 读数，须先认可口径再看数）**：

1. **级联 FAIL**：任务未产出提案时，记录里的 `apply/test/git` 三列会跟着记 FAIL（实际并未发起）。apply 成功率因此有两个口径：含级联 25/41 = 61.0%；仅实际发起 25/32 = 78.1%。
2. **opencode 的 MCP 计数不可用**：opencode 子集 34 个任务的 `mcp_attempted` 全部为哨兵 0（日志正则不可靠，代码注释明示"曾把成功任务记成 0 次"，见 `n11_bench.py` L263–265、L396–398）。MCP KPI 只有 codex 子集可测。
3. **首次成功率**：按基准文档定义（无需重新描述/拆解/重启而**完成**）= `first=PASS 且 final=PASS`；异常任务 36/37/42/43/44 的 `first` 记 "-"，计入分母但不算首次成功。
4. **异常任务判定**：按各任务专项判据，不以"代码是否完成"为准。
5. 每任务记录含 `first/final/proposal/apply/test/git/mcp/tokens/note`，失败不隐藏；两轮未产出时按「单件事」模式重试一次（session2，900s）。

---

## 2. KPI 实测 vs 目标

| KPI | 目标 | 实测 | 达标 |
| --- | --- | --- | --- |
| 端到端完成率 | ≥95% | **31/50 = 62.0%** | ✗ |
| 首次成功率 | ≥85% | **21/50 = 42.0%**（首轮产出提案 27/50 = 54.0%） | ✗ |
| MCP 工具调用成功率 | ≥99% | **codex 子集 23/72 = 31.9%**；opencode 子集不可计数 | ✗ / 无法判定 |
| Proposal 正确率 | ≥95% | **36/45 = 80.0%** | ✗ |
| Apply 成功率 | ≥99% | **25/41 = 61.0%**（仅实际发起 25/32 = 78.1%） | ✗ |
| Run 状态准确率 | ≥99% | **5/5 = 100%**（样本 5，见 §5） | ✓（样本小） |
| Crash Recovery | ≥99% | **1/1 = 100%**（任务 43） | ✓（样本小） |
| 重启数据恢复率 | 100% | **1/1 = 100%**（任务 43：重启后 task 保留、消息 1→2） | ✓（样本小） |
| Git 状态准确率 | 100% | 逐任务 `git log` 校验通过（25/25 提交均含 benchmark 标记）；UI↔Git 一致性本轮未覆盖 | 部分覆盖 |
| 移动端核心操作成功率 | ≥99% | 本轮基准未覆盖 | — |
| 移动端核心流程完成率 | ≥95% | 本轮基准未覆盖 | — |
| 零容忍项（§6） | 0 次事故 | 逐条核对 0（1 项本轮未覆盖） | ✓（部分） |

> MCP KPI 补充口径：验收指标文档 §10 建议"至少积累 500 次 MCP 调用"再判断 99%（样本不足时只作记录、不作稳定指标）。本次 codex 子集仅 72 次调用，且计数把**模型侧空参调用**也计入失败（43/49 次失败为 `arguments_length=0`），是否应算"工具实现失败"需要先定口径。

---

## 3. 分层结果

| 类型 | 完成 | 占比 |
| --- | --- | --- |
| 基础（1–10） | 8/10 | 80.0% |
| 中等（11–25） | 11/15 | 73.3% |
| 复杂（26–35） | 3/10 | 30.0% |
| 异常（36–45） | 7/10 | 70.0% |
| 真实（46–50） | 2/5 | 40.0% |

---

## 4. 19 个失败逐层归因

### 4.1 两轮未产出提案（9 个，全部 opencode 子集）

`9, 14, 24, 28, 30, 35, 47, 48, 50`——首轮 +「单件事」重试轮均未调用 `proposal.create`，记录备注"两轮会话均未产出提案"。

观察（不下结论，供决策）：
- 同批 opencode 子集有 25 个任务成功走完 MCP 提案→Apply→Git，说明"未接入平台"不成立；失败集中在**产出稳定性**。
- 该 9 个任务没有任何 MCP 调用计数可用（哨兵 0），无法从日志侧进一步定位是"未尝试调用"还是"尝试了但没提交成功"。

### 4.2 门禁红灯被如实回滚（7 个）

`4, 18, 25, 26, 29, 31, 34`——Apply 已落盘 → 门禁 `pytest`/`npm test` 失败 → **自动回滚，未误报成功**。这是产品门禁的正常行为，但任务本身未完成。

代表性失败用例（记录原文）：
- 18、29：`tests/test_dsh_client.py::test_generated_patch_token_is_accepted_by_the_real_mcp_endpoint`
- 25：`tests/test_proposal_apply_service.py::test_apply_many_success_when_test_gate_passes` 等三条
- 26：`tests/test_health_report.py::test_health_report_malformed_project_id_not_found`
- 31：`tests/test_delete_proposals.py::test_service_rejects_unknown_op`、`tests/test_proposal_parser.py::test_parse_returns_code_change_set`
- 34：`tests/test_agent_tokens_api.py::test_revoke_reports_revoked_immediately_and_is_idempotent`
- 4：`tests/test_bookmarks.py::test_creat...`（另含 4 个范围外文件）

归因：Agent 产出对既有测试契约有破坏（跨模块影响未评估齐全）。属"产出质量"而非平台缺陷。

### 4.3 异常任务判据/语义差异（3 个，产品行为安全）

| 任务 | 记录/DB 事实 | bench 期望 | 判定 |
| --- | --- | --- | --- |
| 38 Reject | reject http=200；DB 中提案状态 = `rejected`；磁盘未变；再次 apply = 409 | 通过 `/workspace/changes` 读到 `{"rejected"}` | **bench 判据 bug**：`task_changes()` 默认 `active_only=True` 只回 `pending/accepted`，把终态读成 `[]`。产品正确 |
| 40 Apply 失败 | 目标路径被目录占位 → apply http=409；状态保持 `accepted`；无半应用 | http≠200 且状态 = `failed` | **语义差异待定**：产品把"预检拒绝"设计为可修复重试（状态不变）；bench 期望终态 `failed` |
| 41 测试失败 | apply http=500；DB 中提案状态 = `failed`（`apply_error`="Apply 后测试未通过"）；已回滚 | http=200 且状态 = `failed` | **bench 期望过时**：现行契约是失败回 500；状态读空同为 active_only 过滤所致。产品正确 |

结论：三个失败**均不代表安全问题**（拒绝后不可再 apply、冲突不覆盖用户修改、测试失败必回滚）。需要的是判据/口径对齐（§10）。

### 4.4 附带发现：任务 33 的 tsc 缺口

任务 33 终判 PASS，但记录 `tsc_pass=False`（`src/api/client.filter.test.ts(16,19) TS2532`）——该错误在 **bench 注入的测试夹具**里（注入脚本 `inj_frontend_param` 写入的文件本身不通过 strict 类型检查），且 `tsc` 未纳入终判。基线 `dc7b0a3` 实测 `tsc -b` 通过（exit 0），排除基线问题。属 bench 夹具瑕疵 + 门禁口径缺口，非产品/Agent 问题。

---

## 5. 异常任务 10 项明细

| # | 场景 | 最终 | 关键证据（记录原文） |
| --- | --- | --- | --- |
| 36 | Cancel | PASS | http=200，task=cancelled、run=cancelled，**残留子进程=0** |
| 37 | Provider 无响应（idle） | PASS | run=timeout kind=idle waited=44.2s，残留=0，黑洞连接=2 |
| 38 | Reject | FAIL（判据） | 拒绝 http=200，DB=rejected，磁盘未变=True，再 apply http=409 |
| 39 | Conflict | PASS | apply http=409，状态=accepted，**用户修改保留=True** |
| 40 | Apply 失败 | FAIL（语义） | apply http=409，状态=accepted，错误="路径 … 不是普通文件，拒绝 Apply" |
| 41 | 测试阶段失败 | FAIL（期望） | apply http=500，DB=failed，**回滚=True** |
| 42 | Agent 子进程被 kill | PASS | kill pid=1703068 → task=failed、run=failed，残留=0 |
| 43 | Flux 后端重启 | PASS | 重启=True，run=interrupted、task=failed，消息 1→2，不产生永久 running |
| 44 | 长时无响应（hard） | PASS | run=timeout kind=hard waited=124.5s，残留=0，黑洞连接=2 |
| 45 | Git 脏工作区 | PASS | 用户未提交修改保留=True，提交后工作区 `M README.md` |

---

## 6. 零容忍项核对（须为 0）

| 项 | 结果 | 证据 |
| --- | --- | --- |
| Agent 越权访问 | 0 | 40：路径守卫拒绝 Apply；38：拒绝后不可再 apply（409）；39：冲突不覆盖 |
| 密钥 / 凭证泄露 | 0（未发现） | 令牌文件 `bench_token.txt`/`opencode_bench.json` 均 600 权限；记录无密钥外传 |
| 未经批准的错误 Apply | 0 | 全部 Apply 走 accept 流程；38 拒绝后 409；40 守卫拒绝 |
| 测试失败被报告为成功 | 0 | 41：500 + failed + 回滚；18/25/26/29/31/34：门禁红灯全部回滚并记 FAIL |
| 正常重启导致数据丢失 | 0 | 43：task/消息保留，状态收敛为 failed |
| 错误 Git 状态 | 0（本轮口径内） | 每个任务提交后校验 `git log` 含 benchmark 标记；UI↔Git 一致性属 UI 轮，本轮未覆盖 |
| 已知禁止操作未被 MCP 权限面拦截 | 本轮未覆盖 | 不在 50 任务基准内（越权/权限面在上轮测试覆盖） |

---

## 7. 环境与工具链问题（只记录，不改动）

1. **门禁 FLUX_\* 环境污染**：AgentRuntime/ApplyEngine 跑门禁时继承后端进程的 `FLUX_*` 配置，会改变被测项目语义（曾造成 fork 炸弹、用例语义反转）。当前靠 `bench_gate.sh` 在**测试环境**里清除；产品侧是否应在 apply 子进程清洗环境，待决策。
2. **provider 限额自动切换与整轮中止**：日志多处"检测到疑似额度/限流字样，后续任务自动切换 opencode"（08:09 起）；15:36 一次"provider 不可用，本条不计入结果；本轮中止"（opencode 未接入 Flux，零 MCP）。限额重试策略：`QUOTA_MAX_RETRY=3`、退避 `180s×n`，限额不算 FAIL。
3. **opencode MCP 计数为哨兵 0**（§1 口径 2）：当前无法从记录侧补齐；DB 里也没有 per-call 审计表（`agent_execution_logs` 为空，无 audit_events 表）。
4. **fixture 独立基线**：fixture 曾因误用 bench 基线导致执行器崩溃（`git reset --hard dc7b0a3` 在 fixture 仓库不存在），现固定 `bf4dae8`。
5. **bench 判据 bug（38/41）与语义差异（40）**：见 §4.3；任务 33 的 tsc 夹具瑕疵见 §4.4。
6. **隔离留存**：两次被判无效的运行记录已移出判定集——`bench/records_run1_polluted/`（7 任务 + state/summary）、`bench/records_provider_polluted/`（33 份，其中可见 fixture 基线误用样例）。
7. **运行现状（截至报告生成时）**：
   - bench 副本 `/opt/flux/bench-flux` 工作区干净，HEAD=`dc7b0a3`；fixture 工作区干净，HEAD=`afe8bce`（= 任务 49 的提交）。
   - 服务在跑：8010（主实例，DSH 默认关）、8011/8012/8013（辅助实例）、8030（fixture）、8050（bench）、8040（UI 后端）、8090（进度看板，`http://100.109.236.42:8090/`）。
   - **无孤儿进程**：8050 无子进程残留；异常任务 36/37/42/43/44 的"残留=0"均已逐项验证。

---

## 8. 与前次整改清单的对照

| 项 | 落地文件 | 状态 | 验证证据 |
| --- | --- | --- | --- |
| P0 端口统一 | `apps/web-dashboard/vite.config.ts`（默认 `127.0.0.1:8000`，`FLUX_VITE_BACKEND_ORIGIN` 可覆盖 shell 变量优先）；`apps/web-dashboard/.env.local`（本机 = 8010，已 gitignore）；`src/api/client.ts` 注释同步 | 已落地 | `/tmp/flux_verify_proxy.sh` 三路径转发实测通过 |
| P0 DSH 文档补齐 | `.env.example`（与 `config.py` 对齐的全部 19 项 `FLUX_DSH_*`，含三类超时/心跳/对账/取消宽限） | 已落地 | `/tmp/flux_verify_dsh.sh`：隔离实例未启用 `/dsh` 503；启用后 Run pending→running→interrupt→cancelled |
| P0 前端 CI | `.github/workflows/ci.yml` 新增 `frontend` job：Node 20 + `npm ci` → `npm run build`（tsc -b && vite build）→ `npm test`（vitest） | 已落地 | 本地 `npm test` 45 passed、build 通过、`npm ci` 与 lockfile 一致 |
| P0 README 措辞 | `README.md` L3/L5：agent 面统一表述为"MCP 优先 + CLI 兜底" | 已落地 | — **注意：CLI 兜底通道尚未实现**，见 §10-6 |
| 建议项：Apply/回滚冒烟 | — | 已验证 | `/tmp/flux_apply_smoke.py` 10/10：MCP 提案→apply→pytest 通过→落盘；故意失败→`apply_failed`→回滚→failed |

> 以上改动已于 2026-10-04 按主题提交并同步至 origin/main：代理端口统一、DSH 环境变量模板、前端 CI、README 措辞各自独立成 commit，另含 DSH 运行修复与 Solo 页 UI 修复。
> `docs/UI_TEST_REPORT.md` 依据其文首约定（"本报告不提交 git，保持工作区可审阅"）保留在工作区**未提交**。

---

## 9. 数据可信度备注

- 判定集 50 份记录中，`mcp` 计数字段的 provider 划分为：codex 8、opencode 34、异常运行类 8（无 provider）。
- codex 记录的计数在记录写入时与日志当前内容存在小幅漂移（复算任务 1 为 46/3，记录为 44/2），不影响结论方向。
- codex 子集 49 次失败中：43 次为 `arguments_length=0` 空参调用（模型侧未产出参数）；其余为带参失败，抽样可见 `proposal_create` 参数长度 8k–93k 的提交失败（≥6 次），需逐条分类才能定责。
- 补跑段实际用时：10-03 16:02 → 10-04 02:01（约 10 小时，33 个任务）。

---

## 10. 待决策清单（按影响面排序）

1. **KPI 口径**：是否认可"级联 FAIL 不计入 apply 分母、MCP 只用可测子集、38/41 判据修正后重算"？重算后端到端 62.0% 不变，apply 升至 78.1%。
2. **provider 策略**：codex（MiniMax-M3）配额不稳 vs opencode（glm-5.3-flash）产出不稳（9 个零提案）。是否统一 provider 重跑一遍 50 任务再判 KPI？
3. **38/40/41 处理**：38/41 建议修 bench 判据（读全量状态）；40 需定产品语义——预检拒绝后保持 `accepted`（可修复重试）还是转 `failed`？
4. **门禁口径**：门禁是否纳入 `tsc`（任务 33 暴露）；apply 跑门禁的子进程是否需要清洗 `FLUX_*` 环境。
5. **MCP 观测**：是否需要给 MCP 面补 per-call 审计（当前无服务端计数，KPI 无法从平台侧测）。
6. **README 的"CLI 兜底"**：通道未实现，先改措辞（如"规划中"）还是保留现状。

---

## 附：证据索引

| 内容 | 路径 |
| --- | --- |
| 50 任务记录 | `/opt/flux/e2e/bench/records/task-01..50.json` |
| 汇总表 | `/opt/flux/e2e/bench/summary.md` |
| 运行日志（全量） | `/opt/flux/e2e/bench/run_all.log`（467 行） |
| 各任务会话日志 | `/opt/flux/e2e/bench/logs/task-XX/sessionN.log` |
| 门禁脚本 | `/opt/flux/e2e/bench_gate.sh` |
| 基准执行器 | `/opt/flux/e2e/n11_bench.py` |
| 进度看板 | `http://100.109.236.42:8090/`（本地 `python3 progress_server.py`，pid 1998303） |
| 基准库 | `/opt/flux/usertest/bench.db`（38/40/41 的提案终态可在此复核） |
| 隔离记录 | `/opt/flux/e2e/bench/records_run1_polluted/`、`records_provider_polluted/` |
| 清单验证脚本 | `/tmp/flux_verify_proxy.sh`、`/tmp/flux_verify_dsh.sh`、`/tmp/flux_apply_smoke.py` |
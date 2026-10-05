# Flux 验收报告 v2（收口项：终端控制台 T1–T4 + P0-2 / P1-1 / P1-2 / W-1 / P2-1 / P2-2）

> 本报告为**真实执行记录**：结论全部来自真实进程、真实 HTTP 调用、真实浏览器操作与真实构建产物。
> 未改任何测试预期；未把 NOT IMPLEMENTED 写成 PASS；未做成的部分与限制如实列出。

## 元信息

| 项 | 值 |
|---|---|
| 被测仓库 | qiuli55/flux（https://github.com/qiuli55/flux） |
| 被测 commit | `1b4fdfb`（main，推送后） |
| 本轮提交链 | `71df2e6`(T3) → `3f2f3cf`(W-1/P2-1/P0-2) → `cf206cc`(P1-1/P1-2) → `73d8116`(P2-1/P2-2) → `1b4fdfb`(T4) |
| 执行时间 | 2026-10-05 |
| 执行环境 | Linux（服务器），Python 3.10.12（仓库 `.venv`）；前端 Node 20.18.1；桌面打包链 Node 22.23.3；git 2.34.1 |
| 门禁 | `make verify` → `603 passed, 1 warning`，exit 0（warning 为既有的 StarletteDeprecationWarning） |
| 前端 | `tsc -b && vite build` 0 error；`vitest run` 45 passed |
| 计划依据 | `doc/PERSONAL_MVP_RELEASE_DESIGN.md` §2–§7、`doc/AGENT_TERMINAL_CONSOLE.md` §11/§12/§14 |

## 结论摘要

- **Agent Terminal Console（T1–T3）全部通过**：会话/命令/输出/退出码落库、SSE 实时流（历史续读 + 断线重连 + 心跳 + 收流）、前端独立终端窗口（`[AI]`/`[USER]` 来源标记、Stop / Force Stop、重开恢复历史）。浏览器实测：运行中 Stop → `exit -15`，Force Stop → `exit -9`，状态不谎报（确认进程组消失后才落 stopped）。
- **T4 桌面端打包通过（Linux 侧）**：PyInstaller onedir sidecar + Electron 主进程 + `.AppImage`(146 MB) / `.deb`(118 MB) 真实产出，AppImage 在 Xvfb 下端到端跑通；Windows NSIS 与真机验收见"待用户执行"。
- **P0-2 通过**：Run 生命周期 8 项故障注入矩阵可执行、逐项有对应用例，本轮补齐 4 个缺口（Cancel 升级抗 SIGTERM、进程死而 Run 非终态、终态竞争迟到写入、终态再 cancel 幂等）。
- **P1-1 / P1-2 通过**：Delete Proposal 与正式 Rollback 全链路（API + CLI + 前端）真机验收通过，含混合批回滚与二次回滚 409。
- **W-1 通过（本机）**：平台原语层落地并完成 8 项原语用例；`ci.yml` 已加 `backend-windows` job，但**该 job 本轮未实际运行**（需推送后由 GitHub Actions 执行）。
- **P2-1 / P2-2 大部分通过**：Runtime 契约 + runtime 选择与校验 + 外部 CLI Agent 拉起；真实 E2E 中 **OpenCode 与 DSH 各跑通完整链路**；**Codex 未跑成**（本机包装覆盖 `CODEX_HOME` + 审批策略拒绝 MCP 调用，详见限制）。
- **未完成的只有需要用户设备 / 真实长跑的三项**：Windows 真机验收、手机真机验收、50 任务 Benchmark。

## 结果总表（本轮新增/变更范围）

| Test ID | 用例 | 结果 | 关键证据 |
|---|---|---|---|
| T1-REG | 终端会话核心（建会话/执行/输出落库/exit code/续读/Stop 拒绝新命令/Force Stop） | **PASS** | `tests/test_terminal.py` 8 例 |
| T3-SSE-001 | SSE 先补历史再推增量，seq 单调不重复 | **PASS** | `tests/test_terminal.py` 流式 3 例 |
| T3-SSE-002 | 真实订阅：9 帧事件、`exit_code=-15`、`terminal.session.closed` 后收流 | **PASS** | 证据 `t3-sse-stream.txt` |
| T3-SSE-003 | 断线重连（Last-Event-ID 到末尾）不重放旧帧、不挂住连接 | **PASS** | `test_terminal_stream_sse_endpoint` |
| T3-UI-001 | 终端窗口：`[USER]` 标记、命令回显、输出、exit code 0 | **PASS** | 浏览器实测（截图 step3b/step6） |
| T3-UI-002 | 运行中 Stop → `exit -15`；Force Stop → `exit -9`；状态转「已停止」 | **PASS** | 浏览器实测（step3/step4） |
| T3-UI-003 | 关窗不停 Agent；重开恢复历史与最终状态 | **PASS** | 浏览器实测（step5） |
| T4-BUILD-001 | PyInstaller sidecar 启动即迁移到 head 并可用 | **PASS** | 独立复验：health 200、`alembic_version=f3a1b2c4d5e6`、`~/.flux` 下 db/workspace/backups |
| T4-BUILD-002 | Linux `.AppImage` / `.deb` 产物 | **PASS** | `apps/desktop/release/Flux-0.1.0-x86_64.AppImage`(146 MB)、`Flux-0.1.0-amd64.deb`(118 MB) |
| T4-BUILD-003 | AppImage 端到端（Xvfb：后端 + 界面服务 + 反代 health + 页面） | **PASS** | `apps/desktop/README.md` §Linux 产物（实测段） |
| T4-WIN-001 | Windows NSIS 安装包 | **待执行** | 只能在 Windows 构建：CI `desktop-windows`（tag / 手动触发） |
| P0-2-01~03 | 启动 / 空闲 / 硬超时 | **PASS** | `test_run_supervisor.py`（TC-15A/B/C） |
| P0-2-04 | Cancel 升级：抗 SIGTERM 进程树 → grace 后强杀、进程组确认消失 | **PASS** | `test_run_lifecycle_faults.py` |
| P0-2-05 | 属主死亡 + 孤儿进程树接管 | **PASS** | `test_run_supervisor.py`（TC-15F） |
| P0-2-06 | 进程死而 Run 非终态 → 一个对账周期内 FAILED | **PASS** | `test_run_lifecycle_faults.py` |
| P0-2-07 | 终态竞争：迟到的 COMPLETED 不得覆盖 CANCELLED，且不发第二终态事件 | **PASS** | 同上（断言 bus 只出现一次 CANCELLED） |
| P0-2-08 | 终态再 cancel 幂等（返回原终态、无副作用） | **PASS** | 同上 |
| P1-DEL-001 | 删除提案：`kind=delete`、`added=0`、`proposed_content=NULL`、文件未动 | **PASS** | 真实 API E2E + `test_delete_proposal.py` |
| P1-DEL-002 | accept → apply：文件消失、批 applied | **PASS** | 真实 API E2E |
| P1-DEL-004 | 删除不存在的文件 → 校验拒绝 | **PASS** | `test_delete_proposal.py` |
| P1-DEL-005 | 删除前文件被外部修改 → 409 且未动盘 | **PASS** | 同上 |
| P1-DEL-006 | create + modify + delete 混合批：全量成功 / 全量回滚 | **PASS** | `test_delete_proposal.py` + 真实 API E2E |
| P1-ROLL-003 | 单条回滚：内容恢复为 original、状态 rolled_back | **PASS** | 真实 API E2E（内容逐字节比对） |
| P1-ROLL-004/005 | 整批回滚（最近一次 Apply）/ 混合批回滚 | **PASS** | 真实 API E2E：new 被删、mod 还原、delete 恢复 |
| P1-ROLL-006 | 二次回滚 → 409 且无副作用 | **PASS** | 真实 API E2E：`conflict / 批次当前状态为 rolled_back` |
| P1-ROLL-007 | 回滚前文件被外部改 → 409、未动盘 | **PASS** | `test_rollback.py` |
| P1-UI-001 | 变更区 A / M / D 语义徽标 | **PASS** | 浏览器实测：`A · 新建` / `M · 修改` / `D · 删除` |
| P1-UI-002 | 单条「回滚」与「回滚上一次」；无批次时的空态提示 | **PASS** | 浏览器实测 + 磁盘核对 mod.py 还原、token.py 删除 |
| W-1-001 | 平台原语：进程组隔离 / 终止树（含抗优雅信号、已退出）/ 只读探活 / 收权 | **PASS** | `test_agent_runtime_platforms.py` 8 例（POSIX 路径本机执行） |
| W-1-002 | CI 双平台（`backend-windows`） | **待执行** | 已写入 `.github/workflows/ci.yml`，本轮未触发运行 |
| P2-1-001 | Runtime 契约 + runtime 选择与校验 + READY 前置 | **PASS** | `test_cli_runtime.py`；非法取值 422、installation 非 READY 409 |
| P2-1-002 | DSH 迁移零回归 | **PASS** | 现有全量测试（含 DSH 用例）全绿 |
| P2-2-001 | OpenCode 真实链路（task → 事件流 → 提案 → 人审 → apply → pytest → git commit） | **PASS** | 证据 `/tmp/flux-runtime-e2e/`（driver_opencode.py + 结果） |
| P2-2-002 | DSH 真实链路（同上） | **PASS** | 证据 `/tmp/flux-runtime-e2e/dsh-final.json`（提案 1 条、pytest 7 passed、git `e4759ef`） |
| P2-2-003 | Codex 真实链路 | **未通过（受限）** | 见"已知限制"第 1 条 |
| P2-2-004 | Run 目录隔离 + 令牌最小 scopes + Run 结束撤销 + 明文不外泄 | **PASS** | run 目录 0700 / 配置收权；证据 JSON 与日志无令牌明文（已扫描） |

## 上一轮 92 条用例的重新判定

上一轮（`doc/FLUX_ACCEPTANCE_REPORT_2026-10-04.md`）判 NOT IMPLEMENTED / KNOWN FAILURE 的条目，本轮按新实现重新判定：

| 原判 | 用例 | 本轮判定 | 依据 |
|---|---|---|---|
| NOT IMPLEMENTED | T-DIFF-003（删除文件语义） | **PASS** | Delete Proposal 全链路（真实 API + 用例） |
| NOT IMPLEMENTED | T-ROLL-001 / T-ROLL-002（Rollback） | **PASS** | 单条 / 整批回滚真实 API E2E |
| NOT IMPLEMENTED | T-AGENT-002 / T-AGENT-003（Agent 执行链路） | **PASS** | Runtime 契约 + OpenCode/DSH 真实 E2E |
| NOT IMPLEMENTED | OpenCode 由 Flux 启动（4 项） | **PASS** | 真实链路 E2E |
| NOT IMPLEMENTED | Codex 由 Flux 启动（4 项） | **未通过（受限）** | 本机 codex 包装覆盖 `CODEX_HOME` 且审批策略拒 MCP |
| KNOWN FAILURE | T-APPLY-008（Apply 中被 `kill -9`） | **PASS** | P0-1 崩溃恢复 + 人工决策，已真机 kill -9 注入复验（cover / keep / 重复决策 409） |

**诚实说明**：92 条中的其余条目本轮**未逐条手工重跑**。本轮的替代做法是：① 全量自动化回归（`make verify` 603 passed）覆盖其中的 Workspace / Proposal / Diff / Apply / Git / Test / Runtime / CLI / MCP 链路；② 对受本轮改动影响的条目（上表）逐条重跑或重新判定。上一轮的真实执行记录仍然有效，但"逐条重跑全部 92 条"这一项**没有完成**，不计入本轮结论。

## 待用户执行（需要你的设备 / 真实长跑）

| 项 | 为什么需要你 | 怎么做 |
|---|---|---|
| Windows 真机验收 | 需要真实 Windows 电脑；Linux 无 wine 构建不了 NSIS | 按 `apps/desktop/README.md` 的「Windows 真机验收清单」11 步执行；安装包从 GitHub Releases 下载（见下节） |
| 手机真机验收 | 需要真实手机 | 用手机浏览器打开 Web Dashboard，按移动视图清单逐项验收（Solo / IDE 断点行为） |
| 50 任务 Benchmark | 真实模型长跑、消耗额度 | 按 `doc/FLUX_50_TASK_REGRESSION_BENCHMARK.md` 执行，证据落 `/tmp/flux-50task/` |

## Windows 平台适配与发行包（2026-10-05 晚补充）

`backend-windows` job 首次真实运行后暴露 25 个失败，逐类定位并修复；修完 CI 在
windows-latest 上全绿（run `37263234115`，`603 passed`，11m18s），随后用同一份代码重建
安装包发行。两处修复是**真实产品缺陷**，不只是让 CI 变绿：

| # | 根因 | 影响 | 修复 |
|---|---|---|---|
| 1 | `ApplyEngine` 写盘用 `write_text`（`newline=None`） | Windows 上 `\n` 被翻成 `os.linesep`，落盘文件整体变 CRLF，与提案内容、备份的字节口径不一致，污染 hash 比对与 git 差异 | 改 `write_bytes`（字节级写入）；`backup` 写 `.git/info/exclude` 补 `newline="\n"` |
| 2 | Windows 上对**非自有**进程组发 `CTRL_BREAK_EVENT` | `GenerateConsoleCtrlEvent` 要求组由本进程用 `CREATE_NEW_PROCESS_GROUP` 创建；否则 Ctrl+Break 波及共享控制台——CI 里把运行 pytest 的 pwsh 打进交互调试器，真实场景会把运行 Flux 的终端一起打断（正是设计 §11 要避免的"误伤自身"） | `platforms.signal_group_graceful` 增加 `owns_group`（默认 False，Windows 上默认不发信号、交由 `taskkill` 收尾）；`terminate_process_tree` 与 `CliAgentAdapter.stop` 显式声明 `owns_group=True`；`supervisor._kill_pgid` 保持保守 |
| 3 | 测试用 `shlex.quote` 拼测试命令 | Windows 路径含反斜杠 → 被整体加单引号 → cmd.exe 不认，14 个落盘类用例失败 | 新增 `tests/conftest.py` 的 `python_command` / `python_script`（两平台各自安全的引用方式），用例里的 `echo` / `sleep` / `printf` 重定向换成 python 等价物 |
| 4 | 测试 fixture 写文件未指定换行 | Windows 上 fixture 是 CRLF、断言按 LF 计算（8 个失败，含"空提案未被拒绝"的真实根因：CRLF 让提案与原文不等而被当合法提案） | fixture 写入补 `newline="\n"` |
| 5 | 假 CLI 脚本无扩展名 | Windows 上不是有效可执行文件（`WinError 193`）；npm 安装的真实 CLI 在 Windows 上也是 `.cmd` 包装 | 新增 `platforms.executable_argv`（`.cmd/.bat` 经 `cmd.exe /c` 启动，POSIX 原样），`adapters` 的 `start` 与 `run_probe` 统一使用 |
| 6 | POSIX-only 权限断言（`st_mode == 0o600`） | Windows 的 stat 不反映 chmod | 断言平台化：POSIX 校验 0600，Windows 校验 `platforms.secure_file`（icacls）成功 |

**安装包发行**：`v0.1.1`（应用版本 0.1.0）由 CI `desktop-windows` 在 windows-latest 真实构建，
链路为 前端 `tsc + vite build` → PyInstaller onedir 打后端 sidecar → electron-builder NSIS。
产物与 SHA256 见 Releases 页面；Windows 真机验收清单仍待执行（上表第 1 行）。

**仍未解决的已知不一致（如实记录）**：`apply_engine` 读用户文件走 `read_text`（通用换行会把
CRLF 归一成 LF），与 `explorer.read` / 写盘 / 备份的字节口径不一致。在真实 CRLF 仓库上
`original_hash` 有被误判为"已被改动"的风险。本轮未改（涉及读写口径统一与大量 fixture），
留作下一轮。

## 已知限制（如实列出）

1. **Codex 由 Flux 拉起未跑通**：本机 `codex-minimax` 包装强制覆盖 `CODEX_HOME`，破坏 run 目录配置隔离；且 `codex exec` 的审批策略 `never` 使所有 MCP 调用被拒。未把 `--dangerously-bypass-*` 类参数放进生产 argv（那会削弱安全边界）。OpenCode 与内置 DSH 不受影响。
2. **令牌 TTL 24h 未落库**：`agent_tokens` 表无 `expires_at` 列（改表超出本项文件域），当前以"Run 结束即撤销令牌"兜底；如需严格 TTL 需补一次迁移。
3. **Windows 平台原语的 GUI/安装闭环仍待真机**：`platforms.py` 的 Windows 分支已由 `backend-windows` CI 在 windows-latest 上实际执行（603 passed，含 `taskkill /T /F`、`OpenProcess` 探活、`icacls` 收权、`.cmd` 启动），但安装包在真实 Windows 电脑上的图形界面与卸载/重装行为仍需按清单人工验收。
4. **macOS 未做**（设计已明确不在范围）。
5. **回滚部分失败时批保持 `applied`**：已恢复项幂等跳过、可重试，直到全部完成才置 `rolled_back`（设计 §5.2 允许）。
6. **终端历史有上限**：后端单次续读 2000 条、前端保留 3000 条，超出丢最早（设计 §12 不要求无限历史）。

## 附录：证据文件清单

| 证据 | 路径 |
|---|---|
| T3 SSE 实测（9 帧，含 exit -15 与 session.closed） | `/tmp/flux-acceptance-v2/t3-sse-stream.txt` |
| P1 Delete/Rollback 真实 API E2E | `/tmp/flux-acceptance-v2/p1-delete-rollback-e2e.txt`（脚本同目录 `.sh`） |
| P0-2 故障注入矩阵运行记录（14 用例） | `/tmp/flux-acceptance-v2/run-lifecycle-faults-20261005-045429.txt` |
| P2 真实 Runtime E2E（DSH / OpenCode / Codex 尝试） | `/tmp/flux-runtime-e2e/`（`dsh-final.json`、`codex-final.json`、`driver_opencode.py`） |
| T4 桌面端产物 | `apps/desktop/release/Flux-0.1.0-x86_64.AppImage`、`Flux-0.1.0-amd64.deb`、`apps/desktop/backend/dist/flux-backend/` |
| 桌面端构建与 Windows 清单 | `apps/desktop/README.md` |

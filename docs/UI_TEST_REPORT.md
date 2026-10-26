# Flux UI 用户级测试报告

> 依据 `docs/UI_USER_TEST_PLAN.md` 执行。测试者为「第一次/第二次使用 Flux 的开发者」视角：
> 不预设点击路径，以真实任务驱动，逐屏观察，只使用产品、记录问题、截图/录屏，不为让测试通过而改产品 UI（缺陷修复单独列节，修完必复测）。
> 本报告为**第三轮完整闭环**（2026-10-02）：将第一、二轮遗留的「未执行项」全部归零；所有数据来自真实执行（真实隔离实例、真实 DSH 模型调用、真实 Codex MCP 调用、真实 git 仓库），无占位符。
> 本报告不提交 git（保持工作区可审阅）。

---

## 1. 测试环境

| 项目 | 值 |
| --- | --- |
| 后端主测试实例 | `http://127.0.0.1:8030`（隔离实例；代码目录 `/root/workspace/flux`，`.venv` Python 3.10.12） |
| 前端（主） | `http://127.0.0.1:5180/flux-v2`（静态服务 `apps/web-dashboard/dist` 构建产物，同源反代 `/api` → 8030） |
| 超时隔离实例 | `http://127.0.0.1:8040` + UI `http://127.0.0.1:5191`（DSH 指向黑洞 MCP `127.0.0.1:8055`，启动/执行超时 30/45/120s） |
| 数据库 | `/opt/flux/usertest/usertest.db` |
| 工作区仓库 | `/root/workspace/flux-usertest-app`（分支 `flux/tc601`） |
| DSH | provider `deepseek-official` · model `deepseek-v4-flash` · MCP `http://127.0.0.1:8030/mcp` |
| 外部 Agent | `codex-minimax`（codex-cli + MiniMax-M3；经 Flux MCP 提交提案；令牌身份 canonical id `54e8397f-d725-443e-bc5e-a3dd3774e851`） |
| 浏览器 | Playwright Chromium（headless）+ 自定义光标/涟漪叠加；录屏 ffmpeg 4.4.2 |
| 线上实例 | 8010 / 8011 / 8012 / 8013 **未触碰**（全程仅用 8030 / 8040 / 5180 / 5191） |
| 数据快照 | 隔离库提案分布：pending 19 / applied 13 / rejected 5 / failed 5 / accepted 3 / expired 1；工作区提交后干净 |

## 2. 测试版本与改动

- 主仓库基线 commit：`fa4402afb45a2295e4a544b5506371583a39249b`（第一轮测试起点）。
- 本轮修复改动（未提交，6 文件 +153 −18）：
  - `apps/web-dashboard/src/pages/SoloPage.tsx`（取消入口 + 终态重跑指引 + 确认卡文案）
  - `apps/web-dashboard/src/styles/design-v2.css`（弱化文字对比度、禁用态、主按钮反馈）
  - `backend/flux/core/agent_runtime/dsh_client.py`（启动超时归类 + 取消接口 500 修复）
  - `backend/flux/core/virtual_workspace/apply_engine.py`（冲突提示补下一步）
  - `backend/tests/fakes.py`、`backend/tests/test_dsh_client.py`（测试桩 + 回归用例）
- 前端构建：`tsc -b && vite build` 成功（1.83s），产物 `dist/assets/index-BzDNSjx0.css`（65.11 kB）/ `index--f67I7Rw.js`（326.69 kB）；重建后 hash 与测试期一致，确认测试所测版本 = 当前源码。
- 隔离仓库提交：`5d91e56`（第一轮）、`f27d8d5`（本轮 UI-602 提交，3 文件 +37 行）。
- 后端回归：详见 §5。

## 3. 测试设备与尺寸

- 桌面端：1536×1024（主流程录屏）；UI-004 另测 1920×1080、1440×900、1280×800、1024×768。
- 移动端：390×844（`is_mobile`、`has_touch`、DPR=2）。

## 4. 完成的测试项（第三轮）

### 4.1 桌面 L0 / L1 / §13 可访问性（58/58）

| 编号 | 结果 | 真实证据 |
| --- | --- | --- |
| UI-001 首次打开 | ✅ 4/4 | 首屏即见产品定位与主入口「了解 Solo」；6 个导航项；无需读源码可进入 IDE。`shots/v3-01-first-open.png` |
| UI-002 桌面主布局 | ✅ 5/5 | 编辑器为主体（≥50%）、无横向滚动、文件树有真实文件、编辑器初始视图有指引、打开真实文件有代码正文。`shots/v3-03-ide-layout.png` |
| UI-003 面板关闭/重开 | ✅ 10/10 | 文件区折叠后释放为 0、有提示；可恢复；Agent 面板可折叠/恢复；底部面板可收起/重开；Focus Mode 隐藏全部面板、编辑器占满、`⌘\` 可退出。`shots/v3-04..07` |
| UI-004 四档窗口尺寸 | ✅ 15/15 | 1920/1440/1280/1024 四档：无横向滚动、编辑器仍为核心、顶栏按钮未裁切、activity rail 保留；1024 下项目弹层在屏内。`shots/v3-08-size-*.png`、`v3-09-dialog-1024.png` |
| UI-101 页面认知 | ✅ | 导航自述可区分 Solo / 工作区。 |
| UI-102 页面切换 | ✅ 2/2 | 进入 IDE 且 URL 保留 `/flux-v2` 前缀；可返回 Solo。 |
| UI-103 隐藏页可发现性 | ✅ 10/10 | 项目弹层含真实项目名、可关闭；**Agent 弹层已存在**（含「全部/我的」Tab 与真实卡片，IA-01 已修）；市场/设置为禁用态、点击无副作用；IDE 图标均有 `title`。`shots/v3-10..13` |
| §13 对比度与一致性 | ✅ 10/10 | 对比度全部达标（WCAG）、toast 成功态可读、错误/成功样式有区分、失败状态有文字结论（非仅颜色）、键盘焦点可见、Hover 与主按钮反馈、Disabled 可区分、文件树与聊天均有加载提示。`shots/v3-14..20` |

录屏 `flux-ui-usertest-v3-desktop.mp4`（118.8s · 5.88MB · 0 JS 错误）。

### 4.2 Solo 真实 DSH 闭环（8/8）

| 观察点 | 结果 | 真实证据 |
| --- | --- | --- |
| 发送中的反馈 | ✅ | 「Flux · 正在输入… 正在调用平台助手」 |
| 需求澄清卡 | ✅ 7.4s | 不完整需求先澄清、不直接开工。`shots/v3-32-confirm-card.png` |
| 任务执行到终态 | ✅ | run 45.3s 后 running→waiting（完成落待审）。`shots/v3-34-state-running.png`、`v3-34-state-waiting.png` |
| 产出 ≥2 条待审提案 | ✅ | `tests/test_healthz.py`、`app/main.py` |
| 审核弹层含真实 diff | ✅ | 逐行 diff + 提案目的（「在既有 /health、/readyz 之后新增无鉴权、无外部依赖的 GET /healthz…」）。`shots/v3-37-review-dialog.png` |
| 落盘后果写明 | ✅ | 「批准后才会写入工作区并运行项目测试」 |
| 批准后落盘成功 | ✅ | `app/main.py` applied；toast「已批准并落盘 app/main.py · 已运行项目测试」。`shots/v3-38-applied.png` |
| 保留 pending 供手机端 | ✅ | `24e8a7ff-…`（`tests/test_healthz.py`）留待手机端 UI-703 复测。`shots/v3-39-remaining-pending.png` |

录屏 `flux-ui-usertest-v3-dsh.mp4`（95.0s · 3.23MB）。

### 4.3 Solo UI-301~304 / UI-405（17 项断言 + 定向复验）

| 编号 | 结果 | 真实证据 |
| --- | --- | --- |
| UI-301 单 Agent 任务 | ✅ 3/3 | 任务列表 11 行、输入区文案明确、发送即建任务（`32a00f6c-…`）。`shots/v3-40-solo-list.png`、`v3-41-compose.png` |
| UI-303 Agent 追问 | ✅ 2/2 | 模糊需求「做一个带支付功能的 App。」→ 澄清卡指出缺失信息、补全后给出「开始执行」入口。`shots/v3-46-vague-first.png`、`v3-47-vague-confirmed.png` |
| UI-304 二次决策（由我决定） | ✅ | 决策卡出现、写明问题与后果、AI 建议项有标记、可拒绝；手选后任务继续 running。`shots/v3-43-decision-card.png`、`v3-44-chosen.png` |
| UI-304 二次决策（AI 默认方案） | ✅ | auto 模式不打断用户（当场按默认返回 unknown）；对话留痕「AI 已按默认方案自行决定：返回 unknown（问题：版本号缺失时返回什么？）」在任务详情可见（定向复验 3/3 PASS）。`shots/v3-74-taskA-auto-trace.png`、`v3-73-taskA-plan.png` |
| UI-405 取消执行 | ✅ | 修复后：API cancel 200、UI 头部「取消执行」入口、终态如实呈现、重跑指引「任务已结束，不会再执行；如需重跑请点左上角「返回任务列表」新建任务」（独立复测 9/9）。`shots/v3-51-cancel-entry.png`、`v3-55-cancelled-full.png` |
| UI-302 编排可见性 | ⚠️ 部分 | 复验确认「执行计划（5 步）」与需求六维在对话可见；**任务拆分/Agent 分配/并行依赖无独立可视化**（见 §7 P3-01）。`shots/v3-73-taskA-plan.png` |

说明：`v3-solo` 脚本首跑 17 项中 3 项 FAIL，逐一定位为「测试时序误判（UI-304）」「真实缺口→修复（UI-405）」「真实缺口记录（UI-302）」，复验证据见上表与 §5、§7。录屏 `flux-ui-usertest-v3-solo.mp4`（100.7s · 3.59MB）。

### 4.4 异常与恢复（全部现场制造，不再有「未执行」）

| 编号 | 场景 | 结果 | 真实证据 |
| --- | --- | --- | --- |
| UI-501 权限拒绝 | DSH 任务尝试 `workspace.read(".env")` | ✅ 3/3 | UI 对话可见 MCP 硬禁令返回 `permission_denied` 与理由（`.env` 属密钥/凭证类文件，请求未落到磁盘读取环节）；任务未卡 running（completed）。`shots/v3-56-perm-running.png`、`v3-57-perm-denied.png`；录屏 `flux-ui-501-perm.mp4`（19.0s） |
| UI-502 Workspace 冲突 | 审核前文件被外部改动 | ✅ 7/7 | 提示指名文件、写明「提案已过期，禁止直接 Apply；请让 Agent 基于最新文件重新提交提案，或手动修改该文件」；原文件未被覆盖、提案未被误标。`shots/v3-61..63`；录屏 `flux-ui-502-conflict.mp4`（18.2s） |
| UI-503 Agent 超时 | 5191→8040，DSH 指向黑洞 MCP | ✅ 6/6 | 运行中有明确执行中状态与取消入口；30s 启动超时后任务 failed 落终态；原因中文可读「启动超时（startup）：DSH 运行时在 30 秒内未完成初始化」；给出重跑指引。`shots/v3-64..66`；录屏 `flux-ui-503-timeout.mp4`（62.0s） |
| UI-504 落盘失败 | 提案含故意失败用例，Apply 后跑 pytest | ✅ 15/15 | toast「Apply 后测试未通过（exit=1）」；提案 failed（非 applied）；新建文件被回滚删除；变更区徽标「落盘失败」+ 一行结论（`1 failed, 25 passed · FAILED tests/test_ui504_broken_b.py::…`）+「展开完整日志」含 pytest 现场；底部面板同步显示失败。`shots/v3-67..72`；录屏 `flux-ui-504-apply-failed-2.mp4`（27.7s） |
| UI-405 取消（三段） | API 直连 / UI 无确认卡任务 / UI 全流程 | ✅ 9/9 | API cancel 返回 200（修复前 500）、取消后进入终态；UI 入口可用、点击后状态如实呈现、指引可重跑。`shots/v3-51..55`；录屏 `flux-ui-405-retest.mp4`（43.4s） |

### 4.5 外部 Agent 对等性（Codex 现场闭环 8/8）

- Codex（`codex-minimax` / MiniMax-M3）经 `http://127.0.0.1:8030/mcp` 现场提交提案 `b7d29b0e-…` → `docs/CODEX_LIVE_20261002.md`（+4 −0）。
- 走查：变更区徽标「待审核」可发起落盘 → 弹层写出提案目的与落盘后果 → 逐行 diff（5 行）→「批准并落盘」成功（applied）→ 文件写入工作区 → git 出现该文件。
- 身份链路如实记录：Codex 首次调用 `proposal.create` 因入参 `agent_id` 与令牌身份不符被拒；省略 `agent_id` 后按令牌身份落 `agent_source` 成功——平台身份冒充防护正确生效。
- 证据：`shots/v3-75-codex-pending.png`、`v3-76-codex-review.png`、`v3-77-codex-applied.png`；录屏 `flux-ui-codex-apply.mp4`（20.2s）。

### 4.6 Git 用户体验（9/9）

| 编号 | 结果 | 真实证据 |
| --- | --- | --- |
| UI-601 查看修改 | ✅ | Git 面板「分支 flux/tc601 · 变更 3 个文件」；每行含字母 + 路径 + 「看 Diff」；点 docs 行跳到逐行 diff（`@@ -0,0 +1,4 @@`）。`shots/v3-78-git-panel.png`、`v3-79-git-diff.png` |
| UI-602 提交 | ✅ | 提交框默认带落盘摘要，按钮「提交已落盘的 3 项」；改提交信息后提交成功（`f27d8d5`，3 文件 +37 行）；面板显示「工作区干净」「上次提交 f27d8d5 · 3 个文件」。`shots/v3-80-git-commit-form.png`、`v3-81-git-committed.png` |
| UI-603 回滚入口核查 | ✅ | UI 全局按钮扫描无「回滚/还原/撤销/恢复/Revert/Rollback/Undo」入口；接口探测 `POST /workspace/rollback`、`/workspace/restore`、`/git/revert` 均 404——当前产品**无手动回滚能力**（记录，非缺陷；Apply 失败自动回滚已在 UI-504 验证）。 |

录屏 `flux-ui-git-v3.mp4`（30.3s · 1.54MB）。

### 4.7 移动端 390×844（25/25）

| 编号 | 结果 | 真实证据 |
| --- | --- | --- |
| UI-701 查看任务 | ✅ | 任务详情无横向溢出（scrollWidth 390 = innerWidth 390）、四阶段条可见（需求澄清 ✓ / 执行计划 ✓ / 执行中 3 / 完成）、状态与最近事件可读。`shots/m3-01-task-healthz.png` |
| UI-703 审核提案 | ✅ | 提醒条 →「去审核」→ 弹层（宽 374.4px，在 390 内）→ diff 29 行 →「批准并落盘」成功（applied、文件写入工作区）。`shots/m3-02..04` |
| UI-702 决策卡 | ✅ | 手机端出现真实决策卡（问题/选项/推荐可读），点选后消息「你的选择：只返回 {"mobile": true}」且任务继续 running。`shots/m3-05-decision-card.png`、`m3-06-chosen.png` |
| UI-704 取消与异常 | ✅ | 已取消任务状态可读；移动 IDE 布局生效无溢出；变更面板失败条目一行结论 +「展开完整日志」（1086 字符）。`shots/m3-07..09` |

录屏 `flux-ui-mobile-v3.mp4`（46.4s · 1.05MB · 0 JS 错误）。

### 4.8 方案条目覆盖对照（逐条归零）

| 方案条目 | 本轮覆盖证据 |
| --- | --- |
| UI-201 创建/打开项目 | §4.1：项目弹层含真实项目名、IDE 打开真实文件（`v3-03`、`v3-10`） |
| UI-202 直接给 Agent 任务 | §4.2：Solo 新任务 → 澄清 7.4s → 执行（`v3-30..35`） |
| UI-203 需求追问 | §4.2 + §4.3 UI-303（模糊需求先澄清，`v3-46/47`） |
| UI-204 人工审核点 | §4.2：审核弹层 + 批准落盘；§4.7：移动端审核 |
| UI-205 Diff 阅读 | §4.2 审核弹层逐行 diff；§4.6 UI-601「看 Diff」跳转 |
| UI-206 Apply 后验证 | §4.2 落盘 applied + 文件写入；§4.6 UI-602 提交后核对 |
| UI-401 Running | §4.2 运行中状态；§4.4 UI-503 运行中（含取消入口） |
| UI-402 Waiting for User | §4.2 running→waiting；§4.3 UI-304 决策卡挂起 |
| UI-403 Completed | §4.2 completed；§4.4 UI-501 completed（被拒任务不卡 running） |
| UI-404 Failed | §4.4：UI-503 超时 failed、UI-504 落盘失败（不伪装完成） |
| UI-405 Cancelled | §4.4（API/UI/全流程 9/9） |
| UI-501~504 | §4.4（权限/冲突/超时/落盘失败，全部现场制造） |
| UI-601~603 | §4.6（查看/提交/回滚入口核查） |
| UI-701~704 | §4.7（移动端 25/25） |
| UI-001~004、UI-101~103、§13 | §4.1（58/58） |
| §16 最终八问 | §16 |
| §17 输出物 | §15（录屏 + 截图 + 结果 JSON）与本报告 |
| §18 推荐执行顺序 | 按方案顺序执行：L0/L1 → L2/DHS → Solo → 异常 → Git → 移动端 |

## 5. 本轮修复与回归

| # | 修复 | 文件 | 复测 |
| --- | --- | --- | --- |
| 1 | 取消接口 500：SQLite 读出的 naive `finished_at` 与 aware `started_at` 相减抛 `TypeError`；统一用 `as_utc()` 归一后再算时长 | `dsh_client.py` | UI-405 复测 9/9（cancel API 200 → 终态） |
| 2 | 启动超时归类：`harness.start` 与 `harness.run` 分离，初始化超时归为「启动超时（startup）」中文原因 + `timeout_kind`，不再把 SDK 英文异常原文透给用户 | `dsh_client.py` | UI-503 复测 6/6（原因可读、任务 failed 落终态） |
| 3 | 冲突提示补下一步：`请让 Agent 基于最新文件重新提交提案，或手动修改该文件` | `apply_engine.py` | UI-502 复测 7/7（文件保护 + 未误标） |
| 4 | 前端新增「取消执行」入口 + 终态重跑指引 | `SoloPage.tsx` | UI-405 复测 9/9 |
| 5 | 弱化文字对比度提升（`--text-3` `#666e85` → `#7f88a0`，最亮面板上 ≥4.5:1）+ 禁用态/主按钮反馈 | `design-v2.css` | §13 复测 10/10 达标 |
| 6 | 测试桩支持 `harness.start`；新增 dsh_client 回归用例 | `fakes.py`、`test_dsh_client.py` | 单文件 21 passed；后端全量重跑见下 |

- 前端生产构建：`tsc -b && vite build` ✅（产物 hash 不变，测试版本与源码一致）。
- 后端全量回归：`/root/workspace/flux/.venv/bin/python -m pytest`（testpaths=tests）**419 passed, 4 warnings, exit=0**（146.15s；含本轮新增的 dsh_client 回归用例）。

## 6. 未执行项归零（对照第一轮 §5）

| 历史未执行项 | 本轮状态 |
| --- | --- |
| UI-501 现场越权提示 | ✅ 已执行：3/3（DSH 读 `.env` 被 MCP 硬禁令拒绝，UI 如实呈现） |
| UI-502 Workspace 冲突 | ✅ 已执行：7/7（外部改动 → 冲突提示 + 文件保护） |
| UI-503 Agent 超时 | ✅ 已执行：6/6（黑洞 MCP 隔离实例，30s 启动超时） |
| UI-603 回滚 | ✅ 已执行：9/9（UI 无入口 + 三个接口 404，如实记录） |
| UI-302 完整编排视图 | ✅ 已执行（复验）：计划/拆分可见；Agent 分配与并行依赖无呈现（缺口记录） |
| UI-304 真实二次决策 | ✅ 已执行：manual（挂起→手选→继续）与 auto（不打扰+留痕）双路径 |
| 用 Codex 现场新起任务 | ✅ 已执行：Codex 经 MCP 提交 → UI 审核 → 落盘（8/8） |

> 归零结论：`UI_USER_TEST_PLAN.md` 中 L0/L1/L2/L3 与移动端全部条目均已在本轮真实执行，**无「未执行」项**。

## 7. 问题列表（现状）

### 已修复（含本轮复测证据）

| 编号 | 问题 | 修复轮次 | 本轮复测 |
| --- | --- | --- | --- |
| M-01 (P1) | 移动端布局溢出、主内容区宽度为 0 | 第二轮 | ✅ 25/25，`sw=390=iw`，四阶段条可见 |
| G-01 (P1) | Git 面板无法提交新目录下的落盘文件 | 第一轮 | ✅ UI-602：`docs/` 下新文件可提交（f27d8d5） |
| IA-01 (P2) | 导航项「Agent」点击只弹 toast | 第二轮 | ✅ UI-103：Agent 弹层含真实卡片与 Tab |
| D-01 (P2) | 深链与路由前缀不一致 | 第二轮 | ✅ UI-102：进入 IDE URL 保留 `/flux-v2` |
| F-01 (P3) | 失败信息在变更面板内过载 | 第二轮 | ✅ UI-504：一行结论 + 展开完整日志 |
| F-02 (P3) | 成功与错误提示样式未区分 | 第二轮 | ✅ §13：错误/成功样式有区分 |
| T-01 (P3) | 术语不一致 | 第二轮 | ✅ §13 / UI-504：统一「变更提案/落盘失败」 |

### 本轮新记录（P3，均非阻塞）

- **P3-01 编排可视化缺口（UI-302）**：对话内有「执行计划（5 步）」与需求六维，但没有独立的任务拆分视图、Agent 分配、并行/依赖关系呈现。用户能理解「要做什么」，不易直观看出「谁在做、哪些并行、哪些有前置依赖」。证据 `shots/v3-73-taskA-plan.png`。
- **P3-02 待审计计数为项目级**：移动端审核提醒条与审核卡显示「有 18 个需要你审核的点」，而当前任务仅 1 项待审（`SoloPage.loadChanges()` 调 `api.listChanges()` 无任务过滤，为项目级）。用户第一次可能误读为「本任务有 18 项」。证据 `shots/m3-02-review-card.png`。
- **P3-03 运行中对话不实时刷新**：`SoloPage` 仅在任务离开 running/waiting 时重拉消息，运行中的 auto 决策留痕要到任务结束或重开详情才可见。符合「auto 不打扰、结果可查」的规格，但用户运行中看不到最新进展。证据 `shots/v3-74-taskA-auto-trace.png`。
- **记录（非缺陷）无手动回滚入口**：UI-603 实测 UI 无入口、后端无接口；Apply 失败自动回滚已由 UI-504 验证。是否提供手动回滚属产品决策，本次未做任何改动。

### 值得肯定（非问题）

- 审核弹层把「批准后才会写入工作区并运行项目测试」写清楚；冲突/失败/超时/拒绝四类异常均有具体原因与下一步，不出现模糊「执行失败」。
- 决策策略两个选项直接写明后果（「不打扰你」vs「会暂停任务并把问题和建议方案发给你」），UI-304 的理解成本低。
- 隔离实例验证了「线上不受影响」的边界：超时场景用 8040 + 黑洞 MCP 制造，未触碰 8010–8013。

## 8. 核心任务完成率

| 核心任务 | 结果 |
| --- | --- |
| 1. 打开/查看项目 | ✅ |
| 2. Solo 提需求 → 澄清 → 开始执行（DSH） | ✅ |
| 3. 查看 Agent / 任务运行状态 | ✅ |
| 4. 查看变更 Diff、人工审核 | ✅ |
| 5. 批准落盘（自动跑项目测试门禁） | ✅ |
| 6. Apply 后核对改动 | ✅ |
| 7. Git 提交 | ✅ |
| 8. 观察失败反馈（落盘失败） | ✅ |
| 9. 桌面多尺寸适配 | ✅ |
| 10. 移动端审核提案 | ✅ |
| 11. 移动端阅读任务详情 | ✅（M-01 修复后） |
| 12. 异常路径（权限/冲突/超时/取消） | ✅ |

**12/12 全部通过**（客观计数；反例与缺口见 §7）。

## 9. 首次使用阻塞点

无致命阻塞。首次进入即可理解产品定位并找到「工作区」；第一轮记录的「Agent/市场/设置点不动」已修（Agent 弹层存在；市场/设置显式为禁用态）。

## 10. 信息架构问题

- 概念（Task/Context/Proposal/Diff/Apply）仍缺少一处集中释义，但已可通过上下文文案区分（UI-101 通过）。
- 编排信息（拆分/分配/依赖）尚无独立呈现（P3-01）。

## 11. UI 视觉层级问题

- 桌面端层级良好：编辑器为中心，Focus Mode 可占满；失败一行结论 + 展开日志的层级已建立。
- 移动端审核计数为项目级，视觉上会被当成当前任务上下文（P3-02）。

## 12. DSH 与 Codex UI 对等性

- DSH：Solo 现场新起任务 → 澄清 → 执行 → 2 条提案 → 审核落盘，全链路一致。
- Codex：`codex-minimax` 经 MCP 现场提交的提案，在 UI 中以**完全相同的变更队列与审核弹层**处理（含提案目的、逐行 diff、落盘后果），未出现按来源分叉的操作路径；身份冒用尝试被平台正确拒绝。
- 结论：UI 层对 DSH 与 Codex 保持一致，用户无需感知实现差异。

## 13. Mobile UX 问题

- 390×844 全链路无横向溢出；可完成「看任务 → 审提案并落盘 → 决策选择 → 取消/异常查看」。
- 遗留观察：审核计数项目级（P3-02）。

## 14. 建议修复顺序

1. **P3-01** 编排可视化（任务拆分 / Agent 分配 / 并行与依赖）——影响「谁在做、为什么这么排」的理解。
2. **P3-02** 审核计数按任务维度过滤或明确标注「项目级」。
3. **P3-03** 运行中提供轻量进展刷新（或明确「任务结束后统一呈现」的提示）。

## 15. 录屏与截图（本轮）

| 文件 | 说明 | 规格 |
| --- | --- | --- |
| `flux-ui-usertest-v3-desktop.mp4` | 桌面 L0/L1 + §13 全走查 | 118.8s · 5.88MB |
| `flux-ui-usertest-v3-dsh.mp4` | DSH 真实闭环（澄清→执行→审核落盘） | 95.0s · 3.23MB |
| `flux-ui-usertest-v3-solo.mp4` | Solo UI-301~304 + UI-405（含策略切换、决策卡） | 100.7s · 3.59MB |
| `flux-ui-501-perm.mp4` | 权限拒绝现场 | 19.0s · 1.36MB |
| `flux-ui-502-conflict.mp4` | Workspace 冲突现场 | 18.2s · 0.91MB |
| `flux-ui-503-timeout.mp4` | DSH 启动超时（8040 黑洞 MCP） | 62.0s · 3.03MB |
| `flux-ui-504-apply-failed-2.mp4` | Apply 失败回滚现场 | 27.7s · 1.35MB |
| `flux-ui-405-retest.mp4` | 取消执行修复复测（API/UI/全流程） | 43.4s · 1.99MB |
| `flux-ui-codex-apply.mp4` | Codex 现场提案 → UI 审核落盘 | 20.2s · 1.41MB |
| `flux-ui-git-v3.mp4` | Git 面板：查看/看 Diff/提交/回滚核查 | 30.3s · 1.54MB |
| `flux-ui-mobile-v3.mp4` | 移动端 390×844 全流程 | 46.4s · 1.05MB |
| `shots/v3-*.png`、`shots/m3-*.png` | 本轮 90 余张分步截图（`/opt/flux/e2e/video/shots/`） | 1536×1024 / 390×844@2x |
| 结果数据 | `v3-desktop/dsh/solo`、`v3-302-304-verify`、`u501/u502(+before-fix)/u503/u504`、`v405`、`codex-live`、`git3`、`m3` 各 `*-results.json` | — |

历史轮次录屏（第一/二轮）保留于同目录：`flux-ui-usertest-desktop-full.mp4`、`flux-ui-usertest-dsh-task.mp4`、`flux-ui-usertest-git-commit.mp4`、`flux-ui-usertest-mobile.mp4`、`flux-ui-mobile-v2.mp4`。

## 16. 最终八问（方案 §16）

**A. 首次使用** — 可以。一个没看源码的开发者能独立找到「工作区」开工；导航 6 项均有效或显式禁用，不再有「点了没反应」。
**B. IDE** — 是。编辑器为主区并可 Focus 占满；四档尺寸下均为核心。
**C. Solo** — 是。需求→澄清→执行→决策→审核→结果闭环完整；不足在编排可视化（拆分/分配/依赖）。
**D. Agent** — 是。运行中/等待/完成/失败/取消/超时状态均可读出，决策策略两个选项直接写明后果。
**E. Proposal** — 是。审核弹层写清落盘后果，逐行 Diff；冲突有保护与下一步，失败有回滚与真实日志。
**F. 异常** — 是。权限拒绝、冲突、超时、落盘失败、取消五类均现场复现且如实呈现，无「模糊失败」。
**G. DSH / Codex** — 是。Codex 现场提案走同一套审核落盘路径，UI 无实现差异。
**H. Mobile** — 是。390×844 无溢出，可完成审阅落盘、决策、取消与异常查看。

---

### 本轮结论

> 一个真实开发者（桌面端与手机端）能否不依赖开发者解释、独立完成一次完整的 AI 辅助开发任务？
> **能。** 桌面端：澄清 → 执行 → 审核落盘（跑测试门禁）→ Git 提交全链路通过；异常路径（权限/冲突/超时/失败/取消）均能理解并继续工作。
> 手机端：可完成远程查看、审核落盘、决策与取消。
> 第一、二轮遗留的未执行项已在本轮全部归零；本轮 6 项修复（2 处后端逻辑、1 处后端文案、1 处前端交互、1 处样式对比度、1 组测试桩与用例）全部复测通过；剩余 3 项 P3 记录与 1 项产品决策记录留档，不带主观评分。
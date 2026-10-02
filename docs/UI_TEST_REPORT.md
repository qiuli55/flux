# Flux UI 用户级测试报告

> 依据 `docs/UI_USER_TEST_PLAN.md` 执行。测试者为「第一次/第二次使用 Flux 的开发者」视角：
> 不预设点击路径，以真实任务驱动，逐屏观察，只使用产品、记录问题、截图/录屏，不为让测试通过而改产品 UI。
> 报告中所有数据均来自真实执行（真实测试实例、真实 DSH 模型调用、真实 git 仓库），无占位符。

---

## 1. 测试环境

| 项目 | 值 |
| --- | --- |
| 后端测试实例 | `http://127.0.0.1:8030`（隔离实例，`/opt/flux/usertest/start.sh`） |
| 前端 | `http://127.0.0.1:5180/flux-v2`（`apps/web-dashboard/dist` 构建产物 + 同源 `/api` 反代） |
| 数据库 | `/opt/flux/usertest/usertest.db` |
| 工作区仓库 | `/root/workspace/flux-usertest-app`（分支 `flux/tc601`） |
| DSH | 已启用 · provider `deepseek-official` · model `deepseek-v4-flash` · MCP `http://127.0.0.1:8030/mcp` |
| 外部 Agent | `codex-minimax`（codex-cli + MiniMax-M3，通过 Flux MCP 提交提案） |
| 后端 Python | 3.10.12（`/root/workspace/flux/.venv`） |
| 前端 Node | v20.18.1 |
| 浏览器 | Playwright Chromium（headless）+ 自定义光标/涟漪叠加，录屏 ffmpeg 4.4.2 |
| 项目数据 | 1 个项目 `flux-usertest-app`；9 个任务；5 个 Agent；17+ 条变更提案（含 applied/rejected/failed/pending） |

## 2. 测试版本 / commit

- 代码版本（测试开始时）：`fa4402afb45a2295e4a544b5506371583a39249b` · `docs: 完善下一阶段开发计划与完整用户测试方案`
- 测试期间新增未提交改动（本次修复，见 §7）：后端 `flux/core/git_integration/client.py` + 回归测试 `tests/test_git_integration.py`
- 前端构建产物 `dist/index.html` 未改动（本次修复为后端逻辑）

## 3. 测试设备与尺寸

- 桌面端：1536×1024（主流程录屏）；另测 1920×1080、1440×900、1280×800、1024×768
- 移动端：390×844（`is_mobile`、`has_touch`、DPR=2）

## 4. 完成的测试项

### L0 视觉与基础交互

| 编号 | 结果 | 真实证据 |
| --- | --- | --- |
| UI-001 首次打开 | ✅ | 首屏即见产品定位「Solo · 一个人也能完成一个团队的工作」，左侧 6 个导航项（Solo/项目/工作区/Agent/市场/设置），主入口按钮「了解 Solo」；无需读源码即可判断「工作区」是开发入口。见 `shots/01-solo-first.png` |
| UI-002 桌面端主布局 | ✅ | IDE 实测：activity rail 48px、文件资源管理器 232px、**编辑器中心区 936px（占 1536 的 61%）**、Agent 面板 320px、底部面板 200px；无横向滚动。代码区确为视觉中心。见 `shots/07-ide-layout.png` |
| UI-003 面板关闭/重开 | ✅ | 折叠文件资源管理器：宽度 232→**0**，toast「已折叠文件资源管理器（⌘B 恢复）」；恢复后回到 232。Focus Mode：`.ide-files`/`.ide-agent`/`.ide-bottom` 全部隐藏，编辑器中心区 0→**1536**（占满），toast「已退出专注模式」；`⌘\` 可退出。见 `shots/21-files-collapsed.png`、`shots/23-focus-mode.png`、`shots/24-focus-exit.png` |
| UI-004 窗口尺寸 | ✅ | 1920/1440/1280/1024 四档均 `scrollWidth == clientWidth`，无横向滚动，无按钮被裁切。见 `shots/20-size-*.png` |

### L1 导航与信息架构

| 编号 | 结果 | 真实证据 |
| --- | --- | --- |
| UI-101 页面认知 | ⚠️ 部分 | 「工作区=IDE」「Solo=AI 任务执行中心」可区分；但 Agent/Task/Context/Proposal/Diff/Apply 等概念在界面上缺少一处集中释义，需从文案推断。 |
| UI-102 页面之间切换 | ✅ | Solo「← 返回任务列表」打开任务列表面板（9 个任务 + 「新建任务」按钮）；「工作区」进入 IDE；IDE「返回 Solo」可回。见 `shots/06-tasklist.png`、`shots/19-solo-after.png` |
| UI-103 隐藏页可发现性 | ⚠️ | 「项目」打开项目弹层（含 `flux-usertest-app`）；「市场」「设置」点击只弹 toast（「尚未开放」），无占位页；**「Agent」点击也只弹 toast（数量提示），没有 Agent 列表页** —— 用户按导航预期进入页面，实际只得到一句提示，属可发现性/信息架构落差。见 `shots/03-projects-dialog.png`、`shots/04-market-toast.png` |

### L2 真实 IDE 用户任务（DSH 真实闭环）

在 Solo 里以真实需求驱动一次完整 DSH 闭环（录屏 `flux-ui-usertest-dsh-task.mp4`）：

| 编号 | 结果 | 真实证据 |
| --- | --- | --- |
| UI-201/202 创建并开始任务 | ✅ | 「新建任务」→ 输入「给这个项目加一个 GET /readyz 就绪探针接口，返回 {\"ready\": true}，并补一条 pytest 用例。」→ 发送。任务 `601d2c6b` 创建，状态 running；点击「开始执行」toast「已开始执行（Run c3400b12）」 |
| UI-203 需求追问 | ✅ | 平台助手 **7.9s** 内给出六维确认卡（目标/功能范围/技术方案/修改范围/风险/需人工审核环节），**无空缺项**，可直接开始执行；助手回复中先提出 2 个待确认问题。见 `shots/32-confirm-card.png` |
| UI-401 运行中 | ✅ | Run `c3400b12e13a40d084003f4532d8c1cb`，实时观察 `running`；wing 状态可见。见 `shots/34-state-running.png` |
| UI-403 完成 | ✅ | **42.4s** 后 Run completed，`run_finished` 消息说明「两项改动已进入人工审核队列」。见 `shots/35-after-run.png` |
| UI-204/205 人工审核 + Diff | ✅ | 产出 2 条待审提案（`app/main.py`、`tests/test_auth.py`）。审核弹层标题「代码变更审核」，含 `.rv-diff` 逐行 diff（old/new 行号 + 增删），底部按钮「拒绝 / 批准并落盘」，副标题写明「批准后才会写入工作区并运行项目测试」。见 `shots/51-review-*.png` |
| UI-206 Apply 后验证 | ✅ | 两条均「批准并落盘」成功；落盘时自动跑 `pytest -q` 门禁。`app/main.py`、`tests/test_auth.py` 变为 `M`（已修改），git 工作区反映真实改动。见 `shots/52-applied-*.png` |

### Solo 页面（独立 AI 工作流控制台）

| 编号 | 结果 | 真实证据 |
| --- | --- | --- |
| UI-301 单 Agent 任务 | ✅ | Solo 右侧「Agent 团队」列出 5 个 Agent（含 `codex-minimax`），可选；底部决策策略 pill「决策策略 · 使用 AI 默认方案」；输入框 placeholder「有新的需求或补充说明吗？可以继续告诉我…」突出。 |
| UI-303 Agent 追问 | ✅ | 同 UI-203：不完整需求会先进入澄清，不直接开工。 |
| UI-302 小模型编排 | ⚠️ 部分 | 六维澄清由平台助手生成（编排前半段可观察）；但「任务拆解/方案规划」步骤对本次任务停留在「待开始/等待模型给出执行计划」，未展开可视化的任务拆分与 Agent 分配图。 |
| UI-304 执行中二次决策 | ⚠️ 部分 | 「使用 AI 默认方案 / 由我决定」两项选择存在且文案清晰（`shots/33-started.png`），但本次任务未触发运行中的真实决策点，未验证选择后的行为差异。 |

### 异常与恢复（含既有真实失败样本）

| 编号 | 结果 | 真实证据 |
| --- | --- | --- |
| UI-404 Failed / UI-504 Apply 失败 | ✅ | 变更面板中 6 条 `落盘失败`，直接展示真实 pytest 输出（如 `tests/test_broken.py::test_broken - assert 1 == 2`，`1 failed, 24 passed`），**未把 `finish_reason=error` 显示成「已完成」**；同时给出「打开文件」。见 `shots/12-changes.png`、`shots/13-change-failed-detail.png` |
| UI-405 Cancelled | ⚠️ | 任务列表面板可看到「已取消」任务（如 `9b880a78`、`87fbdbe8`），但未做运行中实时取消的操作验证。 |
| UI-501 权限拒绝 | ⚠️ 未执行 | 测试数据中 `tests/test_hang.py`、`app/extra_rejected.py` 等被拒提案存在，但未在 UI 内现场制造 `.env` 读取越权并观察提示。 |
| UI-502 Workspace 冲突 | ⚠️ 未执行 | 未制造文件并发变更场景。 |
| UI-503 Agent 超时 | ⚠️ 未执行 | 未制造 Provider 无响应。 |

### Git 用户体验

| 编号 | 结果 | 真实证据 |
| --- | --- | --- |
| UI-601 查看修改 | ✅ | Git 面板列出 `M app/main.py`、`M tests/test_auth.py`、`U docs/CODEX_MINIMAX_NOTE.md`，每行可「看 Diff」；分支 `flux/tc601` 可见。见 `shots/70-git-tab.png` |
| UI-602 提交 | ✅（修复后） | 提交框默认带上落盘摘要，按钮「提交已落盘的 3 项」可用；提交后 `git status.clean == true`，面板显示「上次提交 **5d91e56** · 3 个文件」。见 `shots/71-git-message.png`、`shots/72-git-committed.png` |
| UI-603 回滚 | ⚠️ 未执行 | 未验证回滚入口。 |

### Mobile（390×844）

| 编号 | 结果 | 真实证据 |
| --- | --- | --- |
| UI-703 审核 Proposal | ✅ | 待审提案在手机端可打开审核弹层（宽 367px，适配 390），含 diff 与「拒绝 / 批准并落盘」。见 `shots/42-mobile-review.png` |
| UI-702/704 查看状态与异常 | ✅（读取） | 任务详情含「执行进度」与状态 chip，可读。见 `shots/43-mobile-taskdetail.png`、`shots/44-mobile-detail-scrolled.png` |
| UI-701 查看任务 | ❌ | **Solo 主内容区 `.s-main` 宽度 = 0，页面横向溢出（scrollWidth 539 > clientWidth 390）**；IDE 同样溢出，编辑器中心区宽度 = 0（文件区 200 + Agent 区 290 + rail 超出 390）。见 `shots/40-mobile-solo.png`、`shots/45-mobile-ide.png` |

## 5. 未执行项及原因

| 项 | 原因 |
| --- | --- |
| UI-501 现场越权提示 | 未在 UI 内制造 `.env` 读取，仅观察到历史被拒提案 |
| UI-502 Workspace 冲突 | 未构造并发改动；避免污染验收仓库状态 |
| UI-503 Agent 超时 | 未人为注入 Provider 无响应 |
| UI-603 回滚 | 时间用于优先验证核心闭环与修复缺陷 |
| UI-302 完整编排视图 | 本次任务规模较小，未触发多任务拆分/并行/依赖可视化 |
| UI-304 真实二次决策 | 运行期未出现需用户拍板的决策点 |
| 用 Codex 现场新起任务 | Codex 侧改用「既有 codex-minimax 真实提案」验证 UI 对等性（审核/落盘路径一致），未再新起一次 Codex 任务 |

## 6. P0/P1/P2/P3 问题列表

> 严重程度定义见方案 §15。

### 【P1】M-01 移动端 Solo / IDE 布局溢出，主内容区宽度为 0

- **场景**：手机（390×844）打开 Solo 或工作区。
- **实际路径**：直接访问 `/flux-v2/solo`。
- **发生的问题**：页面 `scrollWidth 539 > clientWidth 390`，出现横向滚动；`.s-main` 宽度为 **0**（被固定宽度侧栏挤出视口），IDE 中 `.ide-center` 宽度为 **0**。
- **用户是否理解**：用户会以为「打不开/白屏/要左右拖」。
- **用户是否能自行恢复**：只能手动横向滚动，无法正常阅读任务详情。
- **严重程度**：P1
- **问题类型**：Responsive
- **截图/录屏**：`shots/40-mobile-solo.png`、`shots/45-mobile-ide.png`，`flux-ui-usertest-mobile.mp4`
- **建议**：为 `.s-body` 与 IDE 增加移动断点（侧栏收纳为抽屉/顶部横向导航，主区 `minmax(0,1fr)` 占满）。当前 CSS 仅有 `max-width:1280px` 一档，无移动端断点。
- **是否阻塞核心任务**：手机端阅读任务详情 = Yes；手机端审核弹层 = No（弹层适配正常）。

### 【P1→已修复】G-01 Git 面板无法提交「新目录下的落盘文件」

- **场景**：Agent 落盘了一个位于全新目录的文件（如 `docs/CODEX_MINIMAX_NOTE.md`），用户在 IDE 的 Git 面板提交。
- **实际路径**：变更面板 → Git 标签页。
- **发生的问题**：Git 面板显示文件为 `U docs/`，但提交按钮为「提交已落盘的 **0** 项」且置灰，输入框提示「暂无待提交内容：已落盘变更都提交过了」——与文件树/变更面板显示的待提交内容自相矛盾，用户在 UI 内**无法提交**。
- **根因**：后端 `git status --porcelain=v1` 默认把未跟踪的新目录折叠成 `docs/`，而前端用变更的**完整文件路径**（`docs/CODEX_MINIMAX_NOTE.md`）去匹配 git 状态，匹配失败。
- **用户是否理解**：否。提示文案误导，用户会以为已提交。
- **用户是否能自行恢复**：UI 内无绕过手段。
- **严重程度**：P1
- **问题类型**：Interaction / Error Handling（Git UX）
- **截图/录屏**：修复前 `flux-ui-usertest-panels-git.mp4`（`shots/25-git-tab.png`）；修复后 `shots/70-git-tab.png`、`shots/72-git-committed.png`
- **修复**：`backend/flux/core/git_integration/client.py` 的 `status()` 增加 `--untracked-files=all`，让未跟踪目录展开为逐文件；新增回归测试 `test_status_lists_untracked_files_in_new_directories`。修复后按钮变为「提交已落盘的 3 项」，提交成功，`git status.clean = true`（提交 `5d91e56`）。
- **是否阻塞核心任务**：Yes（修复前），修复后 No。

### 【P2】IA-01 主导航项「Agent」点击只弹 toast，无对应页面

- **场景**：用户想查看/管理 Agent，点击左侧「Agent（5 个已装配）」。
- **发生的问题**：不进入任何页面，只弹 toast「已装配 5 个 Agent（见右侧 Agent 团队卡片）」。同理「市场」「设置」只有 toast。
- **用户是否理解**：会以为导航失效。
- **严重程度**：P2
- **问题类型**：Discoverability / Information Architecture
- **截图**：`shots/04-market-toast.png`（同类表现）
- **建议**：至少给出占位页或就地展开右侧 Agent 卡片锚点；若确为「未开放」，导航项应显式标注「即将开放」禁用态，而非可点击后弹提示。
- **是否阻塞核心任务**：No。

### 【P2】D-01 深链与路由前缀不一致

- **场景**：直接访问 `/flux-v2/ide`；或进入 IDE 后观察地址栏。
- **发生的问题**：直链 `/flux-v2/ide` 仍渲染 Solo 任务详情（深链不生效）；进入 IDE 后 URL 变为 `/ide`（**丢掉部署前缀 `/flux-v2`**）。刷新 `/ide` 因 SPA 回落仍可打开，但地址与部署前缀不一致，复制分享会失效。
- **严重程度**：P2
- **问题类型**：Information Architecture / Interaction
- **建议**：路由 basename 统一用 `import.meta.env.BASE_URL`，并让 `/ide`、`/solo` 直链正确落到对应页面。
- **是否阻塞核心任务**：No。

### 【P3】F-01 失败信息在变更面板内过于冗长

- **场景**：查看 `落盘失败` 的变更。
- **发生的问题**：直接把完整 pytest 输出（含 warnings summary、deprecation）铺在列表行内，信息密度过大；虽方向正确（错误可见、可复现），但缺少「错误摘要 + 展开详情」的层级。
- **严重程度**：P3
- **问题类型**：Visual Hierarchy / Feedback
- **截图**：`shots/13-change-failed-detail.png`
- **建议**：默认只显示一行结论（如 `1 failed: tests/test_broken.py::test_broken`），点击展开完整日志。

### 【P3】F-02 成功与错误提示样式未区分

- **场景**：toast 提示（成功落盘 / 错误）。
- **发生的问题**：成功与失败使用同一 `.toast` 容器与样式，仅文案不同，2 秒后自动消失；错误不易被注意。
- **严重程度**：P3
- **问题类型**：Feedback / Accessibility（错误不应只靠文字区分）
- **建议**：错误 toast 使用更强对比/图标，或延长展示时间。

### 【P3】T-01 术语在页面间不完全一致

- **场景**：底部标签用「AI 任务流 / 变更 / Git」，Solo 侧用「执行进度 / 人工审核 / 变更提案」。
- **发生的问题**：同一对象（变更/提案）在不同位置叫法略有差异，轻微增加理解成本。
- **严重程度**：P3
- **问题类型**：Terminology
- **建议**：统一「变更提案」为唯一名词。

### 值得肯定的点（非问题）

- 面板可折叠 + Focus Mode 真正释放空间（编辑器由 61% → 100%），符合「IDE 为中心」的设计目标。
- 人工审核弹层把「批准后才会写入工作区并运行项目测试」写清楚，Diff 逐行可读，**未出现「Apply 会直接覆盖代码」的不安感**。
- `Agent 执行完成` 与 `落盘失败/finish_reason=error` 区分明确，失败展示真实测试输出与文件入口。

## 7. 核心任务完成率

| 核心任务 | 结果 |
| --- | --- |
| 1. 打开/查看项目 | ✅ |
| 2. Solo 提需求 → 需求澄清 → 开始执行（DSH） | ✅ |
| 3. 查看 Agent/任务运行状态 | ✅ |
| 4. 查看变更 Diff、进行人工审核 | ✅ |
| 5. 批准落盘（自动跑项目测试门禁） | ✅ |
| 6. Apply 后核对改动 | ✅ |
| 7. Git 提交 | ✅（修复后） |
| 8. 观察失败反馈 | ✅ |
| 9. 桌面多尺寸适配 | ✅ |
| 10. 移动端审核提案 | ✅ |
| 11. 移动端阅读任务详情 | ❌ |

**核心任务完成率：10/11 ≈ 91%**（桌面端核心闭环 100% 完成）。

## 8. 首次使用阻塞点

无致命阻塞。首次进入即可理解产品定位并找到「工作区」入口；唯一认知落差是「Agent/市场/设置」导航项点了只弹提示（IA-01）。

## 9. 信息架构问题

- Agent 无独立页面，导航项与内容不对应（IA-01）。
- Solo / IDE 深链与 `/flux-v2` 前缀不一致（D-01）。
- 概念（Task/Context/Proposal/Diff/Apply）缺少集中释义。

## 10. UI 视觉层级问题

- 桌面端层级良好：编辑器为绝对中心，侧栏克制，Focus Mode 正确。
- 失败日志在列表内过载（F-01）；错误提示与成功提示未做视觉区分（F-02）。

## 11. DSH 与 Codex UI 对等性

- DSH：Solo 现场新起任务 → 澄清 → 执行 → 2 条提案 → 审核落盘，全链路一致。
- Codex：`codex-minimax` 经 Flux MCP 产出的提案（`docs/CODEX_MINIMAX_NOTE.md`）在 UI 中以**完全相同的审核弹层与落盘按钮**处理，未出现因来源不同而不同的操作路径。
- 结论：**UI 层对 DSH 与 Codex 保持一致**（同一套变更队列与人工审核模型），用户无需感知实现差异。

## 12. Mobile UX 问题

- 主内容区宽度为 0、页面横向溢出（M-01）——手机端「远程控制开发工作流」的读取能力受损。
- 弹层类交互（审核）在手机上适配良好，说明问题集中在页面级栅格而非组件。
- 结论：手机端目前仅「审核提案」这一核心动作可用，**尚不能算「手机完成有价值远程控制」**，主要缺口是响应式栅格。

## 13. 建议修复顺序

1. **M-01** 移动端响应式（P1，直接影响手机端可用性）
2. **IA-01** 导航项与页面一致性（P2，影响可发现性/信任感）
3. **D-01** 路由 basename 与深链（P2，影响分享与可回溯）
4. **F-01 / F-02** 失败信息层级与提示区分（P3，体验打磨）
5. **T-01** 术语统一（P3）

> 说明：G-01（Git 提交）已在本次测试中修复并通过回归验证。

## 14. 录屏与截图

| 文件 | 说明 | 时长/规格 |
| --- | --- | --- |
| `flux-ui-usertest-desktop-full.mp4` | 桌面端完整走查（首次打开→导航→IDE 布局→面板折叠/Focus→变更/失败→审核落盘→多尺寸→Solo 新建 DSH 任务→澄清→执行→落盘→Git 提交） | 218s · 1536×1024 · ≈10MB |
| `flux-ui-usertest-mobile.mp4` | 移动端 390×844（首屏→任务列表→审核弹层→任务详情→IDE） | 25s · 390×844 |
| `flux-ui-mobile-v2.mp4` | 移动端改造后全流程（首页→Solo 列表→任务详情 4 阶段→项目/Agent/我的→IDE 文件与 Agent 抽屉→变更提案 F-01 展开日志） | 139.8s · 780×1688 · 4.56MB |
| `shots/*.png` | 38 张分步截图（`/opt/flux/e2e/video/shots/`） | — |
| `shots/m2-*.png` | 移动端改造 22 张截图（m2-01…m2-22） | 390×844 二倍图 |
| 结果数据 | `desktop-results.json` / `panels-results.json` / `dsh-results.json` / `mobile-results.json` / `commit-results.json` | — |

## 15. 最终八问（方案 §16）

**A. 首次使用** — 可以。一个没看源码的开发者能独立找到「工作区」开工；但点「Agent/市场/设置」会得到「尚未开放」提示，略挫伤信任。

**B. IDE** — 是。编辑器占 61% 主区，面板可折叠，Focus Mode 可让编辑器占满 100%；IDE 确实是主要工作空间。

**C. Solo** — 基本是。Solo 是独立的 AI 工作流控制台（需求→澄清→执行→审核→结果），与 IDE 职责区分清晰；不足在「任务拆解/编排」视图尚不丰富（UI-302 部分）。

**D. Agent** — 是。运行中/完成状态明确，何时需要用户决定清楚（决策策略 pill + 六维确认 + 人工审核）。

**E. Proposal** — 是。审核弹层清楚说明「批准后才写入并跑测试」，逐行 Diff 可读，Approve/Reject 清晰，落盘失败会给出真实测试输出。

**F. 异常** — 基本是。失败与 `finish_reason=error` 不会伪装成完成，能看到真实报错与文件入口；权限拒绝/超时/冲突缺少现场验证（未执行项）。

**G. DSH / Codex** — 是。换 Agent 后用户用同一套方式完成审核与落盘，UI 无实现差异。

**H. Mobile** — 否（部分）。手机端可查看任务、可审核提案，但页面级横向溢出使主内容不可用，尚不能完成完整远程控制。

---

### 本轮结论

> **一个真实开发者第一次使用 Flux，能否不依赖开发者解释、独立完成一次完整的 AI 辅助开发任务？**
> 桌面端：**能**（首次打开 → Solo/IDE 提需求 → DSH 执行 → 人工审核 → 落盘跑测试 → Git 提交，全程可独立完成）。
> 手机端：**暂不能**，卡在响应式栅格（M-01）。

建议优先修 M-01（移动端布局）；G-01 已在本次修复并回归通过。

---

## 16. 移动端改造与问题修复回归（2026-10-02 第二轮）

设计依据：`docs/ui-designs/mobile-v1-20261002.png`（10 屏，390×844）。断点 ≤860px，CSS 与 JS 共用同一查询。

### 16.1 交付内容

- **移动端 6 屏**：首页（hero + 6 项快捷菜单 + 底部 Tab）、Solo 任务列表（输入卡 + 最近任务）、Solo 任务详情（单列滚动 + 4 阶段条 + 审核提醒条 + sticky 输入区）、项目、Agent 管理、我的。
- **底部 Tab**：首页 / 项目 / Agent / 我的；任务详情屏不显示 Tab（与设计图一致）。
- **任务详情 4 阶段条**：由后端 7 步真实状态归并为「需求澄清 / 执行计划 / 执行中 / 完成」，不显示百分比。
- **IDE 移动端**：保留 40px rail，顶栏新增「文件 / AI」按钮，两侧栏改为左右抽屉（遮罩点击可关闭），主区宽度修复；桌面布局不受影响。
- **Agent 管理**（IA-01）：移动端独立屏 + 桌面弹层共用同一组件（全部/我的 Tab、4 张内置角色卡、内置小模型分节）。
- **市场 / 设置**：移动端菜单项标注「即将开放」禁用态，不再点击后弹「尚未开放」。

### 16.2 问题修复回归

| 编号 | 问题 | 修复 | 验收结果 |
| --- | --- | --- | --- |
| M-01 | 移动端布局溢出、主内容区宽度为 0 | ≤860px 单列重排；IDE 侧栏抽屉化 + `overflow:hidden` 裁掉平移出界的抽屉 | 390 / 768 主区宽度 >260 / >500px；390/768/1280 三档 `scrollWidth ≤ innerWidth+1` ✅ |
| IA-01 | 导航项「Agent」点击只弹 toast | 新增 Agent 管理屏 / 弹层；市场、设置改禁用态 | 移动端与桌面弹层均可打开、Tab 可切换 ✅ |
| D-01 | 深链与路由前缀不一致 | `router.ts` 识别 `/flux-v2/` 前缀，深链保留前缀 | `/flux-v2/ide` 落 IDE 且 URL 保持前缀 ✅ |
| F-01 | 失败信息在变更面板内过载 | 折叠为一行结论（测试计数 + FAILED 行），「展开完整日志」看全量 pytest 输出 | 折叠态仅一行、无 `.apply-log`；展开后出现完整日志与「收起日志」 ✅ |
| F-02 | 成功与错误提示未区分 | `toast(message, kind)`：error 带「!」图标、强对比配色、停留 4.5s | 12 处错误提示改为 error 类型 ✅ |
| T-01 | 术语不一致 | 统一为「变更提案」（底部 Tab、弹层标题、按钮、toast、调试面板） | 底部 Tab 显示「变更提案 19」、弹层标题「变更提案审核」✅ |

### 16.3 验收方式与结果

- 环境：5180（动态加载 `apps/web-dashboard/dist`）→ 8030（隔离测试库 `usertest.db` + 工作区 `/root/workspace/flux-usertest-app`）；线上 8010–8013 未触碰。
- 构建：`npm run build` → `dist/assets/index-fqKpsorJ.css`（64.93 kB）/ `index-C3u_q914.js`（326.18 kB），测试与录屏均基于这一版构建。
- `ui_mobile_v2.py`：390×844 / 768×1024 / 1280×900 三档 56 项断言 **56/56 通过**，三档均无 JS 错误。
- `ui_mobile_review.py`：移动端审核闭环（提醒条 → 跳审核卡 → 弹层 → 拒绝 → 提醒条消失）**8/8 通过**；走查用的 1 条临时待审变更已在隔离库中删除，状态分布还原为 accepted 1 / applied 10 / failed 3 / rejected 5。
- 截图 22 张（`shots/m2-01`…`m2-22`）已逐张人工核对，无视觉缺陷。
- 录屏 `flux-ui-mobile-v2.mp4`（139.8s · 780×1688 · 4.56MB · 0 JS 错误），封面 `flux-ui-mobile-cover.png`。

### 16.4 第二轮结论

> 手机端（本轮修复后）：**能**。可独立完成「看首页 → 进 Solo 提需求 → 看任务详情 4 阶段 → 审阅并处理变更提案 → 进 IDE 看文件与 Agent → 看变更提案与失败日志」的完整远程控制闭环，无横向溢出、无布局塌陷。
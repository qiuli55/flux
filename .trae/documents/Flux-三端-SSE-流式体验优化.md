# Flux 三端 SSE 流式对话体验优化

## Context

当前 Flux 的任务对话在不同客户端体验割裂：

- **Web 工作台**（`apps/web-dashboard`）没有消费 `GET /api/v1/tasks/{task_id}/stream`。发送消息时 `POST /tasks/{id}/messages` 会一直阻塞到助手整段回复完成，期间只有 typing 动画；agent 的思考、工具调用、命令输出完全不可见。正文渲染也没有 Markdown，`.msg-text` 未保留换行，代码块与列表会塌成一段。
- **桌面端**（`apps/desktop-avalonia`）Solo 页依赖 3 秒轮询，且每次消息变化都强制滚到底，用户上翻时会被打断。
- **手机端**（`apps/mobile-controller`）已有完整实现：`StreamReducer` 批量归约 SSE、`V2StreamUi` 执行过程卡、`V2Chat` 实时气泡、`V2RichContent` 富文本、trace 回看。它已是最接近 Claude Code 的基准，但三端交互和视觉没有统一。

本轮目标：以手机端现有交互为基准，把 **Web / 桌面 / 手机** 统一为 Claude Code 风格的流式体验——逐字正文、可折叠思考块、工具调用卡、连接状态与重试、贴底不抢滚动、执行轨迹可回看。**后端已有全部所需事件与 trace API，本轮不改后端契约。**

## 参考基准：Claude Code 桌面端 / 手机端

Claude Code 的前端（闭源）可观察到的呈现模式，作为本轮设计目标：

1. 助手正文逐字追加，流式期间显示光标/进度，代码块成形即渲染，不等待整段结束。
2. 思考过程是可折叠块，运行中显示「思考中… Ns」，结束后自动收起、保留耗时。
3. 工具调用是独立行/卡：名称 + 参数摘要 + 状态（运行中 / 成功 / 失败），输出默认折叠，点击展开等宽文本。
4. 状态区展示当前阶段、耗时、连接状态；失败保留已生成内容并提供重试。
5. 只有用户贴着底部时才自动跟随；上翻阅读时不被抢滚动，并出现「回到最新」。
6. 执行结束后，历史里仍能回看这一轮的思考/工具/错误（trace）。

## 现状差距

| 维度 | Web | 桌面端 | 手机端 |
| --- | --- | --- | --- |
| SSE 消费 | 无（仅终端 SSE） | 无（3s 轮询） | 有，含重连/去重/批量 |
| 流式正文 | 无，整段返回后出现 | 无 | 有，带光标 |
| 思考/工具 | 无 | 无 | 有，执行过程卡 |
| Markdown | 无，换行丢失 | 纯 TextBlock | 有富文本（标题/列表/代码/表格/链接） |
| 连接状态/重试 | 无 | 无 | 有 |
| 贴底滚动 | 新消息强制滚底 | 强制滚底 | stick-to-bottom + 回到最新 |
| 执行轨迹 | 无 | 无 | 有 trace sheet |

## 统一交互规范（三端共用语义）

### 事件 → UI 映射

后端 SSE 帧：`event: task.event`，`data: {seq, type, data}`；支持 `after_seq` / `Last-Event-ID` 续传与 15s 心跳。

| SSE 事件 | 处理 |
| --- | --- |
| `user_message {message}` | 合并进消息列表；清空上一轮 live/思考/工具/错误 |
| `assistant_start` | 进入 live 态，记录开始时间，显示「正在输入…」 |
| `assistant_delta {text}` | 追加正文缓冲，约 50ms 批量 flush |
| `assistant_message {message}` | 合并持久化消息；结束 live 态（同一批更新，避免闪烁） |
| `assistant_error {error}` | 保留已生成正文，显示错误行与重试 |
| `run_started {run_id, runtime}` | 重置执行过程，记录 runId/开始时间，live 态 |
| `run_event kind=thinking` | 追加思考文本，思考块运行中展开 |
| `run_event kind=tool` | 按 `call_id` upsert 工具行，合并 name/detail/status/output/category/paths |
| `run_event kind=text` | 追加正文（与 assistant_delta 共用缓冲区） |
| `run_event kind=error` | 追加错误行 |
| `run_status {status, finish_reason}` | 标记本轮不再活跃、结束思考与运行中工具；**暂不清正文**，等 `run_finished` |
| `run_finished {status, message}` | 合并 `message`（kind=`run_finished`，payload 带 `run_id`）；清 live 态 |
| `run_retry {message}` | 状态行显示「上游限流，正在重试…」 |
| 心跳 `: keep-alive` | 仅刷新连接活跃时间 |

### 视觉结构（统一语义，各端用自身设计令牌）

实时气泡自上而下：

1. 角色行：`Flux · 流式输出` + 阶段文案 + 耗时；agent run 时提供「停止」。
2. **执行过程卡**：标题「执行过程」+ 摘要（`N 条命令 · 改动 M 文件 · 读取 K 文件`）。
   - 思考块：`思考中… Ns` / `思考 Ns`，运行中展开、结束自动收起，可手动展开。
   - 工具行：分类图标 + 工具名 + 参数摘要 + 状态（spinner / ✓ / ✗），点击展开输出（等宽、限高、横向滚动）。
   - 错误行：danger 色，保留错误原文。
3. 正文：Markdown 渲染；流式末尾显示闪烁光标。
4. 连接状态：`实时同步中` / `重连中…（第 N 次）` / `连接中断 · 重试`；失败时保留正文。

历史消息中的 `kind=run_finished` 气泡下方挂 **执行轨迹卡**：折叠头显示耗时与计数，展开懒加载 trace，复用同一套步骤行组件。

### 交互约定

- 用户距底部 < 80px 才自动跟随；上翻立即解除，出现「回到最新 ↓」。
- 切换任务时重置流、贴底；重连从 `lastSeq` 续传，按 `seq` 去重，不重复渲染。
- 发送后 typing 动画仅在「尚无任何流式内容」时显示；live 一开始就隐藏。
- 流式期间不把 live 正文并入 `messages`，终态由持久化消息接管，避免列表抖动。

## 实施步骤

### Phase 1：Web 工作台（主实现）

1. **依赖**：`npm install react-markdown remark-gfm`（更新 `package.json` / `package-lock.json`）。
2. **SSE 基础设施**
   - `src/utils/sse.ts`：纯函数 SSE 帧解析器。处理跨 chunk 残帧、CRLF、多行 `data:`、`:` 心跳、坏 JSON 跳过。
   - `src/api/client.ts`：导出 `authHeaders()`（Bearer + `X-Flux-Desktop`）；新增 `listTaskRuns(taskId, limit)`、`getRunTrace(taskId, runId)`；新增 `taskStreamUrl(taskId, afterSeq)`。
   - `src/api/types.ts`：新增 `TaskStreamEvent`；扩展 `TaskMessagePayload`（`run_id/status/error/finish_reason/reason`）；新增 `TaskRunSummary`、`RunTrace`。
3. **流归约与 Hook**
   - `src/components/solo/taskStream.ts`：移植手机端 `StreamReducer`，纯 TS。字段：`connection`、`liveText`（上限 20k，尾部保留）、`liveActive`、`startedAt/endedAt`、`reasoning`、`tools`、`errors`、`runId`、`statusLabel`、`retryAttempt`、`terminalError`、`lastSeq`。方法：`apply/flush/reset/onOpen/onDisconnected/onTerminal`。`run_status` 只停活跃，`run_finished` 才清正文。
   - `src/components/solo/useTaskStream.ts`：fetch + `ReadableStream` + Bearer（EventSource 无法带认证头）；401 刷新令牌重试一次；断线按 `after_seq` 续传，退避 1/2/4/8/15s，最多 8 次后终态错误 + 手动重试；60s 无字节（含心跳）看门狗重连；50ms 批量 flush；StrictMode 双挂载安全（abort 静默、回调走 ref）。**Hook 放在 `SoloChat` 内部**，只把持久化消息 / 终态事件回调给 `SoloPage`，避免 20fps 重渲染整页。
4. **UI 组件**
   - `src/components/solo/StreamActivity.tsx`：执行过程卡（思考/工具/错误/摘要/计时/折叠）。
   - `src/components/solo/MarkdownText.tsx`：`react-markdown` + `remark-gfm`；自定义 `pre` 渲染代码块（语言标签 + 复制），链接仅 http/https/mailto + `rel="noopener noreferrer"`；`React.memo` + `useDeferredValue` 控制流式解析开销。
   - `src/components/solo/RunTraceCard.tsx`：历史执行轨迹，折叠头 + 展开懒加载 trace，复用 `StreamActivity` 行组件。
   - `src/components/solo/useStickToBottom.ts`：距离阈值 80px、wheel/touch 上翻立即解除、rAF 合并滚动、回到最新。
5. **SoloChat 改造**
   - Props 增加 `taskId`、`runSummaries`、`onPersistedMessage`、`onRunSettled`、`onCancel`。
   - 内部挂 `useTaskStream`；渲染实时气泡（角色行 + 执行过程 + Markdown + 光标 + 连接/错误行 + 重试 + 停止）。
   - 助手历史消息改用 `MarkdownText`；`run_finished` 消息下挂 `RunTraceCard`。
   - 贴底逻辑替换现有「新消息必滚底」；typing 与 live 互斥；历史消息行 `React.memo`。
6. **SoloPage 改造**
   - 新增 `mergeMessages(prev, incoming)`（按 id 去重、按 seq 排序）；`send`、`reloadMessages`、`loadOlder`、决策/确认返回全部走合并；`reloadMessages` 不再直接覆盖。
   - SSE `user_message/assistant_message/run_finished` 立即并入消息；`run_status`/`run_finished` 终态触发一次任务、改动、消息、run 摘要刷新（避免与轮询重复）。
   - 加载消息时并行拉 `listTaskRuns(taskId, 50)`，构建 `run_id → summary` 映射传给 `SoloChat`。
   - 去掉「已发送 · N 条消息已落库」toast（流式已有即时反馈）；`onCancel` 复用现有 `cancelExecution`。
   - 现有 3s 轮询保留为兜底，不改后端。
7. **样式**：`src/styles/design-v2.css` 新增流式块（`.stream-live`、`.stream-activity`、`.stream-think`、`.stream-tool`、`.stream-conn`、`.md-body`、代码块/复制按钮、光标动画），沿用 `--tn-*` 令牌；移动端断点适配。

### Phase 2：桌面端 Avalonia

1. **SSE 客户端**：`Services/TaskStreamClient.cs` 使用 `HttpClient` + `HttpCompletionOption.ResponseHeadersRead` + `ReadLineAsync` 解析 SSE；Bearer/`X-Flux-Desktop` 复用 `FluxApiClient`；`after_seq` 续传、1/2/4/8/15s 退避、60s 看门狗、`CancellationToken` 停止；事件通过 `Dispatcher.UIThread` 回 UI 线程。
2. **归约器**：`Services/TaskStreamReducer.cs` 纯 C#，与 Web/手机同一状态契约；`Models/TaskStreamDtos.cs` 定义事件、run summary、trace。
3. **API**：`FluxApiClient` 新增 `ListTaskRunsAsync`、`GetRunTraceAsync`；`Flux.Desktop.csproj` 增加 `Markdig`（仅解析，渲染自绘）。
4. **Markdown**：`Views/MarkdownView.axaml(.cs)` 将 Markdig AST 渲染为标题/段落/列表/引用/围栏代码块（代码块带复制），行内支持粗体/斜体/行内码。
5. **Solo 页面**：
   - `SoloViewModel` 在选中任务时启动流、切换时取消；维护 `StreamLiveText/StreamActivityRows/StreamConnectionText/StreamElapsed` 等可观察属性与集合；消息按 id 去重合并；终态刷新任务/改动/消息/run 摘要。
   - `SoloView.axaml` 增加实时气泡 + 执行过程卡 + 连接/重试/停止 + `run_finished` 下的轨迹卡；`SoloView.axaml.cs` 改为 stick-to-bottom（<80px 才跟随，上翻停止）。
6. 现有 3s 轮询保留兜底；单 Agent 面板（DSH run 轮询）本轮不改。

### Phase 3：Android 对齐

手机端 `StreamReducer` / `V2StreamUi` / `V2Chat` / `V2RichContent` / trace sheet 已是目标实现，作为三端验收基准：

- 核对文案与语义（`执行过程`、`思考中…Ns`、`重连中…`、`回到最新`）。
- 若回归发现 live 气泡结束后无法进入 trace，则在 `StreamUiSnapshot` 补 `runId`，并在执行过程卡尾部加「查看执行轨迹」入口（调用现有 trace sheet）。
- 不重构现有逻辑；构建仍走现有远程构建机。

## 文件级改动清单

**Web（新增）**
- `apps/web-dashboard/src/utils/sse.ts`
- `apps/web-dashboard/src/components/solo/taskStream.ts`
- `apps/web-dashboard/src/components/solo/taskStream.test.ts`
- `apps/web-dashboard/src/components/solo/useTaskStream.ts`
- `apps/web-dashboard/src/components/solo/StreamActivity.tsx`
- `apps/web-dashboard/src/components/solo/MarkdownText.tsx`
- `apps/web-dashboard/src/components/solo/MarkdownText.test.tsx`
- `apps/web-dashboard/src/components/solo/RunTraceCard.tsx`
- `apps/web-dashboard/src/components/solo/useStickToBottom.ts`
- `apps/web-dashboard/src/utils/sse.test.ts`

**Web（修改）**
- `apps/web-dashboard/src/pages/SoloPage.tsx`（`mergeMessages`、SSE 回调、run 摘要、去 toast）
- `apps/web-dashboard/src/components/solo/SoloChat.tsx`（流式气泡、执行过程、Markdown、贴底）
- `apps/web-dashboard/src/api/client.ts`（`authHeaders`、runs/trace、stream URL）
- `apps/web-dashboard/src/api/types.ts`
- `apps/web-dashboard/src/styles/design-v2.css`
- `apps/web-dashboard/package.json` / `package-lock.json`

**桌面（新增）**
- `apps/desktop-avalonia/Services/TaskStreamClient.cs`
- `apps/desktop-avalonia/Services/TaskStreamReducer.cs`
- `apps/desktop-avalonia/Models/TaskStreamDtos.cs`
- `apps/desktop-avalonia/Views/MarkdownView.axaml` / `.axaml.cs`

**桌面（修改）**
- `apps/desktop-avalonia/Services/FluxApiClient.cs`
- `apps/desktop-avalonia/ViewModels/SoloViewModel.cs`
- `apps/desktop-avalonia/Views/SoloView.axaml` / `.axaml.cs`
- `apps/desktop-avalonia/Flux.Desktop.csproj`

**Android**
- 预期不改；仅在回归发现 trace 入口缺失时改 `ui/v2/StreamReducer.kt`、`ui/v2/V2StreamUi.kt`、`ui/v2/V2Chat.kt`。

## 验证

### Web（本机可完整验证）
1. `cd apps/web-dashboard && npm install react-markdown remark-gfm`
2. `npm run test`（Vitest）：归约器（seq 去重/乱序、批量 flush、思考合并、工具 started→completed、run_finished 清 live、文本上限、退避序列）、SSE 解析（跨 chunk/CRLF/多行 data/心跳/坏 JSON）、Markdown（代码块复制、链接白名单、GFM 表格）、`mergeMessages`。
3. `npm run build`（`tsc -b` + Vite）。
4. 浏览器手测（后端 127.0.0.1:8000 + `npm run dev`）：
   - 新建/进入任务发送消息：正文逐字出现，光标闪烁；代码块/列表即时成形。
   - 执行过程卡出现，思考块运行中展开、结束收起；工具行状态从运行中变完成，展开可见命令输出。
   - 上翻阅读时不被抢滚动，出现「回到最新」；点击回到底部后继续跟随。
   - 切换任务再切回：消息、run 轨迹卡正常；展开轨迹可看到思考/工具/错误。
   - 刷新页面：历史助手消息 Markdown 正常；`run_finished` 下轨迹卡可按需展开。
   - 后端短暂停止再恢复（或断网模拟）：显示重连中，恢复后从断点续传且无重复。

### 桌面
1. 安装 .NET 8 SDK 后 `dotnet build apps/desktop-avalonia/Flux.Desktop.csproj`（本沙箱无 .NET，需先安装；无显示器，仅编译验证）。
2. 有显示环境时手测：发送消息流式、执行过程展开、上翻不抢滚动、trace 展开。

### Android
- 现有远程构建机执行 `:app:assembleDebug` 与 `StreamReducerTest`；改动时同步跑 `V2StreamUiTest`。本沙箱无 Android SDK，不做本地构建。

## 边界与非目标

- **不改后端**：SSE 事件、`after_seq` 心跳、`/runs` 与 `/trace` 均已存在。
- **不改终端 SSE**：`TerminalWindow` 的 EventSource 实现保持现状。
- **单 Agent 面板仍为轮询**：DSH run 未绑定 task，后端无 run 级 SSE；后续可加 `/dsh/runs/{id}/stream` 或让 run 绑定 task，本轮不做。
- **不做内容持久化改造**：agent 最终回复已由 `run_finished` 消息落库；轨迹来自现有 trace 表。
- 不引入大型 UI 框架；Web 只加 `react-markdown` / `remark-gfm`，桌面只加 `Markdig`。

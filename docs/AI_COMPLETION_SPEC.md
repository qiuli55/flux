# Flux AI 补全需求规格（Code-OSS 底座）

> 用途：定义 AI 补全的产品行为、上下文策略、模型选择、接口契约与分期验收。
>
> 前置决策：IDE 底座改为 Code-OSS fork（路线 A），见 [IDE_PLUGIN_SYSTEM_PLAN.md](IDE_PLUGIN_SYSTEM_PLAN.md)。
>
> 状态：**待确认**。确认后进入实现，实现前不再改动本文件之外的设计。
>
> 取代：此前口头讨论中的 `docs/ai-completion.md` 计划路径。

## 0. 一句话

AI 补全不是 IDE 内核的一部分，而是 **Flux 内置的一个 VS Code 扩展**（`flux.ai-completion`）：读项目上下文，向 Flux 后端要补全，用编辑器原生的淡色 ghost text 呈现，模型可跟随右栏 Agent，也可单独指定。

## 1. 用户已确认的 5 条要求 → 落点

| # | 原始要求 | 落点 | 本文对应章节 |
| --- | --- | --- | --- |
| 1 | 先与 Agent 交流，确定需求文档 | 扩展提供 `Flux: 与 Agent 确认需求文档` 命令，产物落在仓库 `.flux/specs/*.md`；补全把 spec 当作意图上下文 | §6 |
| 2 | AI 实时监控我所有文档的代码给建议 | 上下文分三层：当前文件 → 同目录/import 关联 → 项目索引摘要；由后端聚合，扩展只传光标位置 | §3 |
| 3 | 建议以颜色淡一些的形式显示在 IDE 上 | `vscode.languages.registerInlineCompletionItemProvider`，编辑器原生 ghost text（`editor.inlineSuggest.enabled`） | §4 |
| 4 | 补全使用右侧 Agent 页面的 Agent 和对应模型 | 补全请求携带 `agent_id` + `key_id` + `model`，取值来自右栏选择（同一份偏好，不再存 localStorage） | §5 |
| 5 | 不要一次性显示全部代码，按当前进度增量显示 | 分片返回 + 节流重触发，ghost 逐段变长；Tab 才落盘 | §4.2 |

## 2. 形态

### 2.1 为什么是扩展而不是内核

- 底座换成 Code-OSS 后，Monaco 的 `registerInlineCompletionsProvider`（现 [MonacoCodeArea.tsx](../apps/web-dashboard/src/components/ide/MonacoCodeArea.tsx#L249-L322)）不再是宿主 API，继续在 Web 端做等于二次开发、且必然废弃。
- VS Code 的 inline completion API 已经把 ghost text 的渲染、Tab 接受、Esc 忽略、多光标、撤销栈全部做好，扩展只负责「给文本」。
- 作为扩展交付后，同一份代码天然满足「补全是一个插件」的诉求，也给插件系统提供第一个真实的自研样例。

### 2.2 扩展清单

```text
apps/vscode-extension/flux-ai-completion/     # 现目录仅有 .gitkeep，即落在这里
├── package.json          # contributes: commands / configuration
├── src/extension.ts      # activate：注册 provider、命令、状态栏
├── src/context.ts        # 采集光标上下文、调用后端取增强上下文
├── src/client.ts         # 调 Flux 后端（/models/chat、/models/fim、/ide/*）
└── src/stream.ts         # 分片累积 + 重触发
```

贡献点（最小集）：

| 类型 | 内容 |
| --- | --- |
| `commands` | `flux.completion.toggle`（开关）、`flux.completion.selfCheck`（自检：provider 注册 / 后端连通 / 关键配置）、`flux.spec.draft`（与 Agent 确认需求文档） |
| `configuration` | `flux.completion.enabled` / `.debounceMs` / `.maxLines` / `.model`（`follow-agent` 或显式 value）/ `.mode`（`insert` 插入 / `line` 整行）/ `.skipComments`（注释字符串内不补）/ `.blockedPatterns`（额外不外发通配） |
| `menus` | 状态栏项，显示当前补全模型与开关态 |

## 3. 上下文：从「一个文件」到「项目级」

现状（Web 端）只看当前文件 ±80/20 行，见 [completion.ts](../apps/web-dashboard/src/utils/completion.ts#L320-L388)。新方案由后端聚合，避免扩展里做重活。

### 3.1 三层上下文

| 层 | 内容 | 预算 | 取用时机 |
| --- | --- | --- | --- |
| L0 当前文件 | 光标前 120 行 / 后 30 行，保留原文 | 无硬截断（超 3 万字符时截头保尾） | 每次请求 |
| L1 邻接文件 | 同目录同级文件 + 本文件 import/require 目标，各取签名与类型定义（去函数体） | ≤ 8 个文件、≤ 1.2 万字符 | 每次请求，按需 |
| L2 项目摘要 | `.flux/specs/*.md` 当前任务段落、项目语言/框架/依赖清单、近期变更文件清单 | ≤ 2000 字符 | 每次请求，命中缓存 |

### 3.2 新增后端接口

```http
POST /api/v1/ide/completion/context
{
  "project_id": "...",
  "path": "src/foo.ts",
  "caret": 1234,
  "text": "……当前文件全文或 L0 片段……"
}
→ 200
{
  "l0": { "text": "...", "start_line": 100, "end_line": 250 },
  "l1": [ { "path": "src/bar.ts", "excerpt": "..." } ],
  "l2": { "spec": "...", "stack": ["typescript","react"], "recent": ["src/foo.ts"] },
  "cache_key": "..."
}
```

- 只读，不写盘；`path` 必须落在项目根内（复用现有 path_guard 语义）。
- 索引缺失时 L1/L2 允许为空，补全退化为 L0，不报错。

### 3.3 「实时监控所有文档」的边界

明确不做的事：不监听全盘文件、不做后台常驻全文索引。落点是用现有 `project_scanner` / `code_intelligence` 的只读索引结果，按需取用；文件变更事件只用于失效缓存，不触发补全。

## 4. 显示与增量

### 4.1 ghost text

- `InlineCompletionItem.insertText` 给纯代码，不给 Markdown 围栏、不解释——沿用现有 `AI_SYSTEM_PROMPT` 的约束精神。
- 多行建议整段放进 `insertText`，编辑器按行淡色渲染；用户 Tab 接受整段，Esc 丢弃。
- 开关关闭时不注册 provider，状态栏置灰。

### 4.2 增量（要求 5）

单次请求的返回若一次到齐，用户看到的是「整块出现」。因此：

1. 后端按 SSE 流式返回（`POST /api/v1/models/fim`，`text/event-stream`，每个 event 一个 `delta`）。
2. 扩展按 120~200ms 节流累积，累积后以新的 `insertText` 重新 resolve 一次 provider（内部调用 `editor.action.inlineSuggest.trigger` 触发重取）。
3. 视觉结果是 ghost 由短到长逐段生长，而不是瞬间铺满。

**已知限制（如实记录）**：VS Code inline completion API 没有「原地更新已显示 ghost」的官方通道，上述是分片重取，粒度是「段」不是「字符」；相邻两段之间有极短的空窗。若验收要求逐字符，需要改用 Inline Chat / 自定义 Webview 方案，成本上升一个量级，不建议一期做。

### 4.3 首次触发时机

- 输入停顿 `debounceMs`（默认 400ms）后触发。
- 组合输入（输入法 composing）期间不发请求。
- 光标刚移动未输入时不触发，避免抖动。
- 只在编辑器自有文档触发：`git:`、`output:`、`vscode-scm:`、`debug:` 等虚拟 scheme 一律不触发，避免拿 diff / 日志去补代码。
- 有选区时不触发（用户正在改写一段代码，ghost 会与其冲突）。
- `flux.completion.skipComments = true` 时，光标落在注释 / 字符串内部不触发；未闭合的引号 / 注释一律按「在里面」处理（宁可不补）。
- 敏感文件默认不外发：`.env*`、`*.pem` / `*.key` / `id_rsa*`、`secrets*`、`.git/**`、`node_modules/**`、lock 文件等为**内置规则，始终生效**；`flux.completion.blockedPatterns` 只能追加，不能关掉内置规则。
- 同一文件的旧请求在新请求发起时被主动 abort（`AbortController`），避免边打字边攒出多条并行 SSE。

## 5. 模型：跟随右栏 Agent

### 5.1 选择优先级

```text
flux.completion.model = "follow-agent"  → 用右栏当前 agent + 模型 + key
                         "<keyId>::<model>" 或 "<model>" → 用指定模型
                         未设置 → 读后端偏好，再退化为默认补全模型
```

### 5.2 为什么补全要能独立选模型

| 维度 | 补全 | 对话 |
| --- | --- | --- |
| 延迟预算 | 300–800ms | 3–10s 可接受 |
| 日调用量 | 数千次 | 数十次 |
| 期望输出 | 纯代码、短、可 FIM | 可解释、可长 |

结论：默认给出**独立补全模型**，同时提供 `follow-agent` 开关满足「用右栏 agent 的模型」这条要求；两者不互斥。

### 5.3 偏好存储

现状：右栏模型选择存 `localStorage`（[AgentChatPanel.tsx](../apps/web-dashboard/src/components/agent/AgentChatPanel.tsx)）。底座更换后 localStorage 域变了，且扩展进程读不到网页存储。

改为后端偏好：

```http
GET  /api/v1/ide/completion/prefs
PUT  /api/v1/ide/completion/prefs   { "enabled": true, "model": "follow-agent", "debounce_ms": 400 }
```

右栏 Agent 的 agent/model 选择同样落库（`GET/PUT /api/v1/agents/active`），保证「右栏选了什么，补全就用什么」。

### 5.4 FIM 接口

```http
POST /api/v1/models/fim       # SSE
{ "prefix": "...", "suffix": "...", "context": {...}, "model": "...", "key_id": "...", "agent_id": "..." }
→ event: delta  {"text":"..."}   × N
→ event: done   {"mode":"fim|chat","model":"...","provider":"...","usage":{...},"truncated":false,
                 "first_token_ms":120,"total_ms":480,"chars":42,"empty":false}
```

- 不支持 FIM 的供应商，后端内部降级为 chat 补全（把 prefix/suffix 拼进 prompt），对扩展透明。
- 复用现有 `model_gateway` 与密钥库，不新建 API 管理体系。
- 回显去重：模型常把 prefix 末尾的几行原样吐回来，后端用 `_LeadingDeduper` 在流内剥掉与 prefix 重叠的首部再下发，扩展拿到的 `delta` 永远是「新增部分」。

### 5.5 埋点与可观测

补全默认「无感」，出问题最难查；因此每次请求都记录「是否交付、首字延迟、总耗时、是否被接受」，**只计数，不落任何代码内容**。

```http
POST /api/v1/ide/completion/accepted
{ "accepted": true, "mode": "chat", "model": "deepseek-chat",
  "language_id": "typescript", "completion_mode": "insert" }

GET  /api/v1/ide/completion/stats
→ {
    "totals":   { "requests": 120, "successes": 110, "failures": 6, "aborts": 4,
                  "delivered": 96, "empty": 14, "accepted": 31,
                  "acceptance_rate": 0.32, "avg_first_token_ms": 180, "avg_total_ms": 420, ... },
    "today":    { ... },
    "by_model": { "deepseek-chat": { ... } },
    "by_mode":  { "fim": { ... }, "chat": { ... } },
    "days":     [ { "date": "2026-10-30", ... } ],          // 保留 14 天
    "last": { ... }, "last_accept": { ... }, "updated_at": "..."
  }
```

- 存储：`~/.flux/completion-stats.json`，`threading.Lock` + 临时文件 `os.replace` 原子写；磁盘 / 权限异常静默降级，绝不影响补全主链路。
- 口径：接受率分母是「真正展示过的非空补全」`delivered`（不是 `requests`）；`avg_first_token_ms` 只统计拿到首字的样本；`avg_total_ms = total_ms_total / successes`。
- 扩展侧在 `InlineCompletionItem.command` 里随用户 Tab 接受回传 `accepted=true`，拒绝走 `accepted=false`。
- `mode` 是服务端通道（`fim` / `chat`），`completion_mode` 是交互模式（`insert` / `line`）；`by_mode` 只按通道分桶，交互模式记在 `last_accept` 里，两者不混。
- 无感自检：`Flux: 补全自检`（`flux.completion.selfCheck`）一次性回报 provider 是否注册、后端是否连通、当前开关 / 模型 / 模式，出问题不用翻日志。

## 6. 需求文档环节（要求 1）

现状：仓库没有「需求文档」实体，只有一句话表单 [RequirementBar.tsx](../apps/web-dashboard/src/components/RequirementBar.tsx)。

方案：

- 命令 `Flux: 与 Agent 确认需求文档` → 打开右栏 Agent 对话，预置模板（目标 / 范围 / 验收 / 不做）。
- 对话产出的需求文档写为 `.flux/specs/<slug>.md`（**不自动写盘，进变更面板待审**，与现有 ApplyEngine 流程一致）。
- 补全把「当前文件所属 spec」作为 L2 上下文，使建议贴合既定需求而不是自由发挥。

## 7. 契约清单

| 接口 | 状态 | 说明 |
| --- | --- | --- |
| `POST /models/chat` | 复用 | 降级路径 |
| `POST /models/fim` | 新增 | SSE 流式补全 |
| `POST /ide/completion/context` | 新增 | 三层上下文聚合 |
| `GET/PUT /ide/completion/prefs` | 新增 | 开关与模型偏好 |
| `POST /ide/completion/accepted` | 新增 | 接受 / 拒绝埋点（只计数，不落代码） |
| `GET /ide/completion/stats` | 新增 | 接受率、首字延迟、按模型 / 模式 / 天统计 |
| `GET /dsh/models` | 复用 | 模型候选 |
| `GET/PUT /agents/active` | 新增 | 右栏当前 agent/模型落库 |

## 8. 分期与验收

### M1 · 可用（L0 + 一次性返回）

- 扩展能注册 provider，输入停顿后出 ghost，Tab 接受、Esc 丢弃。
- 注意力：延迟 P50 ≤ 700ms（本地后端 + 直连供应商）。
- 验收：录屏，同一文件连续 3 次补全命中，Tab 后代码正确、撤销可回退。

### M2 · 项目上下文 + 独立模型

- L1/L2 生效：新建文件里能补出本项目已有的工具函数名。
- 补全模型可在设置里切换，`follow-agent` 生效。
- 验收：从右栏切换模型后，下一次补全请求体里的 `model/key_id` 随之变化（日志举证）。

### M3 · 流式增量

- SSE 分片生效，ghost 至少出现 2 次可观察的变长。
- 取消（继续输入）后不再追加；同一文件的新请求会主动 abort 旧流，后端 `aborts` 计数随之增长。
- 验收：录屏 + 抓包，展示 3 段以上 delta。

### M4 · 可观测与边界

- `GET /ide/completion/stats` 能看到接受率、`avg_first_token_ms`、`avg_total_ms`，且按模型 / 模式 / 天可分。
- 虚拟 scheme、选区、注释字符串、敏感文件均不触发补全；`blockedPatterns` 追加规则生效。
- 验收：`Flux: 补全自检` 回报全绿；扩展单测 `npm test` 全绿。

## 9. 待确认

1. 补全默认用哪个模型？（建议：便宜快模型，如 deepseek-chat；强模型只在 `follow-agent` 时用）
2. `follow-agent` 是否作为默认值？默认开启会显著抬高延迟与成本。
3. 需求文档目录用 `.flux/specs/` 是否可接受（会进入用户仓库、可能被提交）？
4. 补全是否需要支持「多候选」（Alt+] 切换），一期是否砍掉？
5. 无供应商密钥时：静默不显示（现状行为）还是状态栏提示一次？

## 10. 不做的事

- 不做逐字符流式（API 不支持，见 §4.2）。
- 不做全盘后台索引。
- 不新建一套 API Key 管理，复用现有密钥库。
- 不在 Web 端 Monaco 继续演进补全。

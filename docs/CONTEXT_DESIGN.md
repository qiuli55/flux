# Flux 上下文设计

> 状态：设计定稿中（2026-10-01），与 [DSH_FLUX_INTEGRATION_PLAN.md](DSH_FLUX_INTEGRATION_PLAN.md) §26 对应；本文是上下文部分的完整设计，方案文档保留摘要。
>
> 一句话概括：**存档满保真、投喂按预算；多 agent 共用同一份上下文，但每条内容都知道是谁说的。**

## 1. 前提与边界

- Flux 是平台层（工程核心 + MCP 能力面 + UI 面板），**本身不是 agent**；内置 agent（DSH 基底）与外部 agent（codex / claude-code / opencode…）在能力消费上对等。
- **任务下发与能力供给分离**：Flux 下发任务；agent 的 loop 归自己；agent 用的 skill、connector、context、brain 全部通过连 Flux MCP 获得。
- 因此上下文设计的核心问题不是「多个 agent 怎么共享一段对话」，而是「**项目事实与任务状态放哪、怎么按预算投喂、谁说的怎么标**」。

## 2. 四层结构

| 层 | 内容 | 持有方 | 更新方式 |
| --- | --- | --- | --- |
| L0 项目事实 | Project Brain：架构、约定、历史决策、踩坑 | Flux（唯一真源） | 候选知识经人审入库 |
| L1 任务上下文 | Task / Requirement / 目标 / 验收标准 / 约束 | Flux | 跟随 Task |
| L2 运行上下文 | 相关文件、diff、提案、测试结果、操作轨迹 | Flux | 跟随每次 Agent Run |
| L3 对话历史 | 消息、工具调用与结果、session state | agent 自己 | agent 私有；Flux 只留 Run 摘要与轨迹引用 |

共享的只有 L0–L2。L3 不进共享层，**切换 agent 不搬迁对话历史**——换人时给的是交接信封（§9），不是整段对话。

## 3. 存档层与投喂层

**存档层（满保真，不丢）**：所有 agent 的每条产出、每次工具调用、每轮对话原文全部留档，可搜索、可回溯、可重放。UI 可见完整时间线。压缩只发生在投喂环节，**不影响存档**。

**投喂层（按预算）**：下发前按预算打包，三档自动降级：

1. **全量**（默认档）：预算内原样投喂；
2. **压缩**：超预算时，结构化提取优先、自由文本摘要其次；
3. **引用**：被压掉的内容留 `ref`，agent 可用 `context.fetch(ref)` 取回原文——这是「完整保留」对 agent 的可达性。

### 3.1 永不压缩 / 可压缩

- **永不压缩**：当前任务与验收标准、约束、未决问题、最近若干轮、涉及文件最新版本、未验证断言清单；
- **可压缩**：较早对话、已完成步骤的经过、已解决的讨论、长工具输出原文（保留 hash + 摘要 + `ref`）。

## 4. 动态投喂预算

不设固定常数，按目标模型与本次打包实时计算：

```
可投喂预算 = 目标模型上下文窗口 × 安全系数 − 预留输出 − 系统/工具定义占用 − 必留项体积
```

- 上下文窗口与最大输出取自 **Flux 侧模型窗口表**（provider/model → window/max_output；未知时用保守默认值）；
- **随会话进展动态调整**：早期少给历史，跑长后加大压缩力度，接近完成时优先保留最新状态、砍早期过程；
- 每次打包记录「预算 / 已用 / 压缩决策」，供回溯与 UI 展示。

## 5. 压缩分层：T1 / T2 / T3

### 5.1 T1 规则 / 结构化（Flux 侧，零模型）

长工具输出截断 + hash、已完成步骤叙述、重复内容——**先行**，把喂给模型的分量压到最小。确定性、可复现、零成本。

### 5.2 T2 常驻流式压缩器（Flux 侧，内置本地小模型，增量）

**不等溢出再压**，而是持续对新增内容做压缩，溢出时直接取现成产物（无阻塞等待）：

- **增量按段处理**（每段很小），再滚动汇总（summary of summaries）——这规避了「小模型有效上下文短」的致命问题：它永远不需要通读全量；
- **输出分两类**：机器可验证字段（数值 / 命令 / 文件名 / hash / 错误码）由 T1 规则抽取，**不由小模型生成**；小模型只写叙述性文本摘要；
- 产物是**派生层**，随时可由「原文 + 压缩器版本」重建；摘要条目带 `ref` 回原文；
- **节流**：按段 / 按轮批量触发，不逐 token 跑；多 agent 并行时压缩任务排队，避免成为瓶颈；
- **本地运行**的额外收益：上下文可能含敏感内容（密钥、内网地址），压缩不必出网。

**模型尺寸结论**：1B 以下小模型**不适合**作为压缩主力（有效上下文短、保真与跨段连贯性差、分块拼接成本反升），只适合 schema 固定的单段信息抽取；本地可跑的前提下建议 **1.5B–3B** 档。

### 5.3 T3 被调 agent 二次裁剪（可选）

仅在 T1 + T2 之后仍超预算、**且内容能装进其窗口**时启用；产物经 `context.compact` 回写存档层并带指纹（算该 agent 的 assertion）。

**物理限制**：真正超窗时该路径不可用——agent 根本读不到全量，必须先由 Flux 压到窗口内。

### 5.4 一致性规则

- **不依赖 agent 自摘要作主路径**：跨 agent 一致性无法保证；其他 agent 默认消费 Flux 的 T1/T2 结果，不继承上一个 agent 的自压产物；
- **hot / warm / cold 梯度**：滚动汇总是层层有损，最近若干轮保持原文，越早越压，并定期用原文重建压缩层；
- **待核实**：DSH 原生自带 compaction 类插件（内置 agent 本身会做上下文压缩），需看其触发条件与产物形态，决定接管还是复用，避免「Flux 压一遍、DSH 又压一遍」。

## 6. 溯源指纹（条级）

**条级**：每次工具调用、每条结论各带指纹（不是只标到整段回答）。
**服务端盖章**：Flux 在 MCP 写入侧自动注入 `provenance`，**不接受客户端传入 `agent` 字段**——agent 不能冒充他人。
**统一注册表**：agent id 由 Flux agent 档案注册表统一定义（`flux-builtin` / `codex` / `claude-code` / `opencode` …）。

| 字段 | 含义 |
| --- | --- |
| `agent` | 来源 agent（注册表 id） |
| `run_id` / `session_id` | 回溯到哪一次运行 |
| `runtime` + `model` | 用什么跑的（如 `codex-cli 0.157.1 → MiniMax`） |
| `kind` | `assertion`（断言）/ `evidence`（工具产出）/ `fact`（Flux 验证过） |
| `tool` + 参数摘要 + 结果 hash | 有据可查 |
| `ts` | 时间戳 |

三档可信度：

- **evidence（证据）**：工具真实返回的内容、文件 hash、命令输出、测试结果——可直接作依据；
- **assertion（断言）**：agent 的结论、推断、建议——必须标来源，可被质疑与复核，**不当事实用**；
- **fact（事实）**：Flux 侧验证过（Tester 实测通过、ApplyEngine 落盘成功等）。

渲染形态（进入投喂件时）：

```
[codex · r7f2 · 断言] 登录校验应抽成独立函数，理由是……
[codex · r7f2 · 证据 · flux.workspace.read(auth/login.py@sha256:9c1f)] （文件正文）
[flux  · 事实 · tester.run(pytest)@sha256:44ab] 3 failed, 12 passed
```

配套规则写进投喂件：**历史条目带来源标注；`断言`不等于事实，采信前自行验证。**

## 7. 防污染

- agent **不能直接写 Brain**，只能提候选知识（`knowledge.propose`）；**一律人审**后才进 L0（不允许自动入库）；
- 多 agent 并行时 L0/L1 只读共享；改动只能以 Workspace 提案回流（配 hash check）；
- 每次 Run 记录其引用的 **context 快照 id**，事后可回溯「当时它看到的上下文」；
- `assertion` 不得自动升级为 L0 事实——指纹解决「谁说的」，不解决「说得对不对」。

## 8. 触发时机

① 下发前按预算计算打包；② Run 结束时把 L2 沉淀为 L1 摘要；③ 切换 agent 时生成交接信封（压缩的第一现场）；④ T2 常驻压缩器按段/按轮持续运行。

## 9. Handoff（交接信封）

结构化字段：**已完成 / 未完成 / 关键决策 / 未决问题 / 涉及文件 + hash / 下一步建议**。

- 首选由 agent 通过 `handoff.put` 显式提交；
- Flux 从 Run 事件自动摘要兜底；
- 切换 agent 时 UI 显示「交接卡」，用户可编辑后再交给下一个 agent。

## 10. UI 设计

- **完整上下文视图（默认）**：显示存档层满保真内容，不拿压缩提示替代历史；
- **投喂视图（对照）**：查看这次实际发给 agent 的打包结果，逐条标注形态（原文 / 摘要 / 仅引用），可手工增删后再下发——用于判断「某个 agent 漏答是不是因为压缩掉了东西」；
- **懒加载**：虚拟滚动 + 分页拉取，`ref` 原文按需展开；存档可能上万条，不整包渲染、不一次性拉全量；
- **来源可见**：每条内容显示 agent 徽标与来源链，可按 agent 过滤（ide 页）；交接卡标注「谁留下的、是断言还是证据」。

## 11. MCP 工具面（上下文相关）

| 工具 | 作用 | 策略 |
| --- | --- | --- |
| `context.get` | 取任务/运行上下文（按预算打包后的形态，含指纹） | Allow |
| `context.fetch(ref)` | 取被压缩内容的原文 | Allow |
| `context.compact` | agent 二次裁剪产物回写（T3） | Allow（产物记为 assertion） |
| `brain.search` | 检索 Project Brain | Allow |
| `knowledge.propose` | 提交候选知识 | Allow（入库需人审） |
| `skill.get` | 取 skill 正文 | Allow |
| `handoff.put` | 提交交接信封 | Allow |
| `proposal.create` | 创建 Workspace 提案 | Allow |
| `operation.record` | 记录操作轨迹 | Allow |

面上不暴露：`workspace.apply`、`git.push`、`secret.read`。

## 12. 数据模型草案

每条存档条目的字段：

```
entry_id        唯一 id
layer           L0 / L1 / L2 / L3
kind            assertion / evidence / fact
agent           agent 注册表 id（服务端盖章）
run_id          Agent Run id
session_id      agent 侧 session id
runtime/model   执行载体与模型
tool/args_digest/result_hash   工具调用可核查信息
ts              时间戳
body            正文（或摘要正文）
ref             指向原文条目（摘要/引用时）
summary_of      被汇总的条目 id 列表
derived         是否派生层（可重建）
compressor_version  产出该摘要的压缩器版本
verified        是否经 Flux 验证
snapshot_id     本次投喂采用的上下文快照
```

## 13. 与 DSH / 外部 agent 的对接

- **DSH 内置 agent**：session 归 DSH 自管；投喂件可注入 session 首条消息，或经 `agent-instructions`（64KB 上限）注入项目约定；skill 正文走 `tool-skill`；原生 compaction 插件待对齐（§5.4）。
- **无独立 system 通道的 agent（如 `codex exec`）**：投喂件即其 **prompt 前缀**；MCP 通过 `codex exec -c mcp_servers.*` 逐次注入，不污染用户全局配置。
- **能力面（skill / connector）**：统一由 Flux MCP 提供，agent 不自带。

## 14. 演进阶段

| 阶段 | 内容 |
| --- | --- |
| Phase 1 | 存档层 + T1 规则压缩 + 打包器（全量/压缩/引用）+ 条级指纹（服务端盖章）+ UI 完整视图与懒加载 |
| Phase 2 | T2 常驻流式压缩器（本地小模型）+ 投喂视图 + 交接卡 |
| Phase 3 | T3 agent 二次裁剪 + 候选知识人审流程 + 与 DSH compaction 插件对齐 |
| Phase 4 | 向量检索、上下文重放与回滚 |

## 15. 待确认清单（2026-10-01）

已于 2026-10-01 确认：存档满保真 + 超预算压缩提取、投喂预算动态计算、用户界面显示完整上下文（另配投喂视图对照、懒加载）、条级指纹 + 统一 agent 注册表。

| # | 事项 | 建议默认 |
| --- | --- | --- |
| 1 | 压缩分层方案 | T1 规则（先行）→ T2 常驻流式压缩器 → T3 agent 二次裁剪（可选） |
| 2 | Handoff 产生方式 | agent 显式提交 + Flux 自动摘要兜底 |
| 3 | 候选知识入库 | 一律人审（不允许自动入 L0） |
| 4 | 外部 agent 沙箱口径 | 隔离目录 + 提案回流（`codex exec -C <dir>`） |
| 5 | solo / ide 两页关系 | 同一会话两种视图，切页保留 agent 与会话 |
| 6 | 一键切 agent 语义 | 切档案（角色 / 模型 / 权限组 / 可见范围）+ 交接信封，不继承全部对话历史 |
# Flux 整体架构、项目方向与项目理解

> 状态：2026-10-01。本文是**当前理解与方向的快照**，不是权威规格；权威规格是 [Flux_Master_Spec_v0.1.md](Flux_Master_Spec_v0.1.md)。上下文专项设计见 [CONTEXT_DESIGN.md](CONTEXT_DESIGN.md)，DSH 接入见 [DSH_FLUX_INTEGRATION_PLAN.md](DSH_FLUX_INTEGRATION_PLAN.md)。

## 1. 一句话定位

Flux 是 **AI 软件工程操作系统（平台层）**：把 AI 当作真正的工程角色（Tech Lead / Architect / Developer / Reviewer / Tester / DevOps）来编排，而不是当聊天机器人；每一次 AI 改动都以「提案 → 虚拟 diff → 人工审查 → 应用」落地，**永不直接覆盖真实文件**。

## 2. 项目理解（是什么 / 不是什么）

| 是 | 不是 |
| --- | --- |
| 平台层：工程核心 + 能力面 + UI 面板 | **不是 agent**，不做 Agent Loop，不编排 agent 对话 |
| 内置了一份 agent（DSH 为基底），随 Flux 交付 | 不代表 Flux 等于这个 agent；它与外部 agent 在能力消费上对等 |
| Skill / Connector / Context / Brain 的**唯一真源**与治理方 | 不是「一个更强的 CLI」或「另一个 Cursor」 |
| 对 agent 提供**任务下发面 + 能力供给面**两个面 | 不替 agent 决定怎么干活（loop 归 agent） |

三条固定口径（2026-10-01 确认）：

1. **任务下发与能力供给分离**：Flux 下发任务；agent 自己带 loop；agent 用的工具通过连 Flux MCP「借」来。
2. **能力出口唯一**：Flux 只开一个 **MCP Server（Streamable HTTP）**，服务内置 agent、桌面端与外部 agent（codex / claude-code / opencode …）。
3. **共享上下文只有一份，在 Flux 侧**；agent 的对话历史私有、可丢，切换 agent 不搬迁对话。

## 3. 整体架构

```
客户端      apps/web-dashboard · desktop · vscode-extension · mobile-controller
                              │  HTTP / WS（统一五段式响应 + 事件总线）
后端        backend/flux（单一可部署单元）
  api/v1    agents · tasks · projects · workspace · connectors · models · git · health · dsh
  core      agent_runtime · task_engine · workflow_engine · model_gateway
            permission_engine · virtual_workspace · project_brain · project_files
            project_scanner · git_integration · event
  connectors 契约 + 注册表（外部系统的唯一受控通道）
  models    14 张表（SQLAlchemy 2.0）· services/cost_service · db · config(FLUX_)
            │
能力面      Flux MCP Server（Streamable HTTP）
            context.get · brain.search · skill.get · handoff.put · proposal.create · operation.record …
            │
agent 侧    内置 agent（DeepSeek Harness + Cordis，--profile flux）
            外部 agent（codex / claude-code / opencode …）
```

**分层纪律**：`api/` 只做协议转换；`core/` 不 import FastAPI；一切外部调用必须走 Connector；密钥只由对应 Provider 内部读取，绝不下发给 Agent、绝不入库。

## 4. 工程核心的模块职责

| 模块 | 职责 |
| --- | --- |
| `agent_runtime/` | Agent 生命周期状态机、执行器、管理器；DSH 客户端（`dsh_client.py`）与事件映射（`dsh_events.py`） |
| `task_engine/` | 优先级调度（当前仍是进程内实现，落库是 M1 收尾项） |
| `workflow_engine/` | 多 Agent 工作流编排 |
| `model_gateway/` | 供应商抽象、路由、providers/；模型选择与策略层（不删除，逐步退化职责） |
| `permission_engine/` | RBAC + Capability 策略，**fail-closed** |
| `virtual_workspace/` | 变更提案状态机 + unified diff；真实落盘由 ApplyEngine 控制（M2） |
| `project_brain/` `project_files/` `project_scanner/` | 项目知识、文件索引与扫描 |
| `git_integration/` | Git 状态 / diff / 提交集成 |
| `event/` | 进程内事件总线，事件命名 `resource.action`（`agent.started`、`workspace.changed`） |

## 5. 当前实现状态（2026-10-01）

**已完成**

- **M0 全部**：14 张表 + Alembic 双向迁移、13 条 API 路径（`docs/openapi.json` 契约随代码提交）、Agent 状态机、Virtual Workspace 状态机 + unified diff、权限策略、事件总线 / 调度器 / 工作流编排、Connector 契约与注册表、Makefile + verify.sh + CI。
- **M1 大部分**：真实供应商适配器（`local` / `openai` / `anthropic` / `deepseek`，统一薄 HTTP 层，不引入各家 SDK）、超时退避重试与错误映射、**多 Agent 并行运行**。
- **近期新增**：`codex_cli` 供应商（`codex exec` → MiniMax，子进程型，可配 `max_tokens`）、DSH 客户端骨架（起 Run / 流式事件 / 中断 / 查状态）、`flux` profile 在 A 机实测跑通（`/opt/flux/dsh-home`、`/opt/flux/dsh-ws`）。

**进行中 / 未完成**

- Task 由内存落库到 `tasks` 表（M1 最后一项）。
- Virtual Workspace 的真实写盘、备份、测试回归、Git 集成（M2）。
- DSH 集成 Phase 1 剩余：`FLUX_DSH_PROFILE` / `FLUX_DSH_PATCHES` 配置项、事件映射补齐（`tool/call`、`tool/result`、`step/*` 等）、AgentSpec → SDK 参数映射、审批请求通道。
- 桌面端 UI（solo / ide 两页）尚未开始，`apps/desktop/` 为空目录。

## 6. 项目方向（2026-10-01 确立）

1. **内置 agent 走 DSH**：以 DeepSeek Harness 为基底，不 fork 源码；用官方 `--profile` + `--patch` 机制做「Flux 版」——`flux` profile 由 `sdk` 模板派生，Phase 1/2 纯 Python + yml，Phase 3 才写自有 TS 插件。
2. **能力面走 MCP**：Skill / Connector / Context / Brain 统一经 Flux MCP Server 供给；面上不暴露 `workspace.apply` / `git.push` / `secret.read`。
3. **桌面端自研 UI**：多 agent 共用一个 UI、一个上下文、一套 skill 与连接器；**solo 页**（对话工作台）+ **ide 页**（工程视图：文件树 / diff / 提案 / 时间线），两页均可一键切换 agent。
4. **协作安全**：条级溯源指纹（谁说的、是断言还是证据，服务端盖章）+ 交接信封 + 候选知识人审，防止多 agent 互相污染。
5. **工作流**：后续接 Flux Task/Workflow 编排，并与 DSH 原生 subagent / workflow 机制打通。

## 7. 里程碑主线

规格 §19.1：M1 核心运行时 → M2 Virtual Workspace → M3 IDE → M4 Connector → M5 Skill 与 Project Brain → M6 成本可观测 → M7 浏览器与环境自动化 → M8 设备网络 → M9 插件生态。

当前实际位置：**M1 收尾**（Task 落库），同时提前开展 DSH 集成与桌面端设计（它们跨 M2/M3）。

## 8. 已知限制与风险

| 项 | 现状 |
| --- | --- |
| Python 3.10 | 已进入仅安全修复阶段，官方计划 2026-10 EOL；升级 3.12/3.13 路径已记入规格 §19 待定事项 |
| Docker | 本机未安装，容器链路未本地验证（`docker-compose.yml` 镜像版本已按官方来源核对） |
| Provider 真实调用 | 只有 `deepseek` 做过真实调用；`openai` / `anthropic` 仅经 `httpx.MockTransport` 离线验证 |
| DSH 版本落差 | 后端骨架锁 SDK `0.1.5rc1`；npm CLI 已是 `0.2.0-rc.2`。两套 runtime 并存需定口径 |
| DSH 可执行体裁剪 | Python SDK 捆绑的 exe 按 sdk profile 裁剪，`web` / `headless` 模板启动会缺包；桌面端形态需用 npm CLI + Node 22（本机 Node v20.18.1） |
| 桌面端 | `apps/desktop/` 为空目录，壳技术栈（Electron / Tauri / 浏览器版）未定 |
| 审批通道 | DSH 的 approval 请求目前无人应答；接 Flux Permission Engine 属 Phase 4 |

## 9. 关键工程约定

- 提交信息用 `feat:` / `fix:` / `docs:` / `chore:` 前缀；每改必提交并推送 `main`。
- 契约优先：`tests/test_openapi_contract.py`、`tests/test_models_contract.py` 会断言 API 路径与表结构，偏离即红。
- 测试：`cwd=backend`，`../.venv/bin/python -m pytest`（asyncio_mode=auto）。
- 前端 `apps/web-dashboard/package.json` 保持零 diff。
- 密钥 / PEM 不写入文档与仓库；删除数据需二次确认。

## 10. 文档索引

| 文档 | 用途 |
| --- | --- |
| [Flux_Master_Spec_v0.1.md](Flux_Master_Spec_v0.1.md) | 权威规格（34 份源文档合并，22 条冲突裁决）；代码与规格不一致以规格为准 |
| [DSH_FLUX_INTEGRATION_PLAN.md](DSH_FLUX_INTEGRATION_PLAN.md) | DSH 集成方案（§24–§27 为本轮新定口径） |
| [CONTEXT_DESIGN.md](CONTEXT_DESIGN.md) | 上下文完整设计（存档/投喂、压缩分层、溯源指纹、UI） |
| [TEST_PLAN.md](TEST_PLAN.md) | 测试计划 |
| [openapi.json](openapi.json) | 接口契约，随代码提交 |
| [ui-designs/](ui-designs/) | 9 张界面设计稿（M3 输入素材） |

## 11. 待确认清单（截至 2026-10-01）

1. 压缩分层方案：T1 规则 → T2 常驻流式压缩器（本地小模型）→ T3 agent 二次裁剪
2. Handoff：agent 显式提交 + Flux 自动摘要兜底
3. 候选知识入库：一律人审
4. 外部 agent 沙箱：隔离目录 + 提案回流
5. solo / ide 两页关系：同一会话两种视图，切页保留
6. 一键切 agent 语义：切档案 + 交接信封，不继承全部对话历史
7. 桌面壳技术栈与运行位置：Electron / Tauri / 浏览器版；引擎在 A 机后端还是用户本机
8. DSH 版本口径：后端是否从 SDK `0.1.5rc1` 迁到 `0.2.x`，与 npm CLI 对齐
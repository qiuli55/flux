# Flux

集合 agent 的 AI 软件工程操作系统：统一上下文、统一工具（agent 面：MCP 优先 + CLI 兜底）、统一 Skill，各只有一份真源，所有 agent 共享。

Flux **本身不做 agent**——没有 loop、不组装 prompt、不替 agent 调模型、不存 agent 对话（目标架构 [FLUX_TARGET_ARCHITECTURE.md](docs/FLUX_TARGET_ARCHITECTURE.md) §0）。它内置一个以 **DeepSeek 为基底**的 agent（DSH + Cordis），与 Codex / Claude Code / OpenCode 等外部 agent 经**同一个 agent 面（MCP 优先 + CLI 兜底）**对等消费能力。

核心差异化是 **Virtual Workspace**——AI 的每一次改动都以"提案 → 虚拟 diff → 人工审查 → 应用"的流程落地，**永不直接覆盖你的文件**。

规格的唯一权威来源是 [`docs/Flux_Master_Spec_v0.1.md`](docs/Flux_Master_Spec_v0.1.md)（34 份源文档合并去重后的主规格，含 22 条冲突裁决）。代码与规格不一致时以规格为准，并把实施期发现的新问题回写到规格附录 A。

**架构方向以 [`docs/FLUX_TARGET_ARCHITECTURE.md`](docs/FLUX_TARGET_ARCHITECTURE.md)（2026-10-01）为最高优先级**：它定义了「Flux 不做 agent、只做集合 agent 的平台」这一硬约束与退役清单；与本文档 §1 的定位描述冲突时以它为准。

---

## 当前进度

> 契约规模（2026-10-16 快照）：**79 条 API 路径**（含 MCP 单入口 `/mcp`）、**28 张数据表**、**22 个 Alembic 迁移脚本**、**899 个 pytest 用例全绿**（`bash scripts/verify.sh` 第 5 步）。

| 里程碑 | 状态 | 关键证据 |
| --- | --- | --- |
| M0 项目基础 | 完成 | 数据层双向迁移通过、autogenerate 无漂移；`docs/openapi.json` 契约随代码提交；权限策略 fail-closed；事件总线 / 调度器 / 工作流编排；Connector 契约与注册表；Makefile / verify.sh / CI / Docker |
| M1 核心运行时 | 完成 | `local` / `openai` / `anthropic` / `deepseek` 四家适配器全部注册（统一薄 HTTP 层，不引官方 SDK）；超时退避重试与错误映射；**Task Engine 已由进程内字典切到 `tasks` 表**（`core/task_engine/repository.py`），任务状态唯一权威是数据库 |
| M2 Virtual Workspace | 完成 | ApplyEngine 真实落盘 + 备份 + 回滚 + Apply 崩溃恢复对账 + 测试门禁；Git 集成（status / diff / stage / unstage / commit / branches / checkout）；`path_guard.py` symlink 逃逸防护（Apply / Backup / Explorer 三处共用）。多文件提案的**原子 Apply 仍在收口**（已提供 `apply-batches` 分组应用与按批回滚） |
| M3 IDE 体验 | 进行中 | `apps/web-dashboard`（React 19 + Vite）已实现 Solo / IDE 双模式、并排/统一两种 diff 视图、xterm 终端；`apps/desktop-avalonia` 原生桌面端已完成登录、概览、任务、Skills、系统诊断、设置、Solo 任务台（流水线 + 单 Agent）、项目列表、Agent 面板、Agent Terminal。`apps/vscode-extension` 仍为空目录 |
| M4 Connector | 未开始（仅契约） | 只有 `backend/flux/connectors/base.py`；仓库 `connectors/` 无具体实现 |
| M5 Skill 与 Project Brain | 部分 | `project_brain` / `project_files` / `project_scanner` / `memory` / `capability_import` 已落地；`skills/{backend,frontend,security,devops}` 四个包目录仍为空 |
| M6 成本可观测 | 未开始 | `services/cost_service` 只有 calculator；Provider 不内置价格表，`pricing=None` 时 `cost` 为 `null`（绝不用猜测值填充） |
| M7 浏览器与环境自动化 | 未开始 | — |
| M8 设备网络 | 部分 | `apps/mobile-controller`（Android 远程控制端）已落地；`nodes/{android_node,desktop_node}` 仍为空 |
| M9 插件生态 | 未开始 | — |

里程碑主线见规格 §19.1：M1 核心运行时 → M2 Virtual Workspace → M3 IDE → M4 Connector → M5 Skill 与 Project Brain → M6 成本可观测 → M7 浏览器与环境自动化 → M8 设备网络 → M9 插件生态。

另有两条不挂在里程碑上的能力轴，已落地：**能力面（MCP Server）**、**上下文机制**（见下文各自章节）。

---

## 核心运行时（M1）

| 能力 | 状态 | 证据 |
| --- | --- | --- |
| 真实供应商适配器（Issues #013–#015） | 完成 | `local` / `openai` / `anthropic` / `deepseek` 四家全部注册，离线用例全绿 |
| 供应商容错：超时、退避重试、错误映射 | 完成 | `429`/`5xx`/超时按 `0.5s × 2^n` 退避重试 2 次，耗尽后映射为领域错误码 |
| Task Engine 落库 | 完成 | `tasks` 表已是任务状态唯一权威；`core/task_engine/` 含调度器、读写仓库、DSH 桥接（`dsh_bridge.py`）与决策（`decisions.py`） |
| 架构对齐 Phase 1：退役 agent loop | 完成 | 删除 `AgentExecutor` / `DeveloperAgent` / `TesterAgent` / `AgentContext` / `codex_cli` provider / `virtual_workspace/flow.py`；`AgentManager` 重构为纯档案注册表（commit `45db1b9`） |
| 架构对齐 Phase 1 收尾：symlink 逃逸防护 | 完成 | 新增 `flux/core/virtual_workspace/path_guard.py` 作唯一真源（逐段 `is_symlink()` fail-closed + realpath 包含性），Apply / Backup / File Explorer 三处共用（commit `5babc59`） |

**Adapter 用统一的薄 HTTP 层调用上游 API**，不引入各家官方 SDK——依赖面更小，`httpx.MockTransport` 可完全离线断言。默认模型 id 与覆盖方式：

| 供应商 | 默认模型 | 覆盖变量 | 鉴权 | 备注 |
| --- | --- | --- | --- | --- |
| `local` | `local-echo` | `FLUX_LOCAL_MODEL_NAME` | 无 | 离线回显，零依赖，可用于跑通链路 |
| `openai` | `gpt-5.5` | `FLUX_OPENAI_MODEL` | `Authorization: Bearer` | 输出上限字段为 `max_completion_tokens` |
| `anthropic` | `claude-sonnet-5-5` | `FLUX_ANTHROPIC_MODEL` | `x-api-key` + `anthropic-version: 2023-06-01` | `system` 走顶层字段；`temperature` 钳制到 `[0,1]` |
| `deepseek` | `deepseek-flash` | `FLUX_DEEPSEEK_MODEL` | `Authorization: Bearer` | 见下方 thinking 模式注意点 |

模型 id 取自 2026-09-30 快照（规格附录 A-2）。**可配置性已实测**（2026-09-30，离线，无网络调用）：

- 默认值实测：`{'local': 'local-echo', 'openai': 'gpt-5.5', 'anthropic': 'claude-sonnet-5-5', 'deepseek': 'deepseek-flash'}`
- 用 `FLUX_OPENAI_MODEL` / `FLUX_ANTHROPIC_MODEL` / `FLUX_DEEPSEEK_MODEL` 覆盖后，装配出的 `model_name` 随之改变，无需改代码
- 供应商是否可用只取决于密钥：不配密钥时 `router.available()` 为 `['local']`，加上 `FLUX_DEEPSEEK_API_KEY` 后变为 `['local', 'deepseek']`——`/api/v1/health/ready` 的 `providers` 列的就是这个可用集合

**双 Provider 验证边界**（如实说明）：

- `deepseek`：**已做真实调用**——2026-09-30 用 `deepseek-flash` 实际请求成功返回 `content="收到"`、`finish_reason="stop"`、usage 35/18、latency 591 ms。
- `openai` / `anthropic`：仅经 `httpx.MockTransport` 离线验证（请求体、请求头、响应解析、错误映射均有断言），**未做真实调用**——本机没有这两家的密钥。

> **DeepSeek thinking 模式注意点（实测坑）**：`deepseek-flash` 的 thinking 默认开启，推理 token 计入 `max_tokens`。`max_tokens=32` 时输出额度会被推理吃光，`choices[0].message.content` 返回空串（内容在 `reasoning_content` 里）；`max_tokens=512` 正常。因此不要用很小的 `max_tokens` 做探活，模块默认 `1024` 是下限而非推荐值。
>
> **成本计价**：Provider 不内置价格表，`pricing=None` 时 `cost` 为 `null`——绝不用猜测值填充成本，真实计价属 M6。

---

## 能力面：Flux MCP Server

Flux 只开**一个** MCP Server（Streamable HTTP，单入口 `POST /mcp`），同时服务内置 agent、桌面端与外部 agent（codex / claude-code / opencode …）。工具面的**唯一真源**是 `backend/flux/core/mcp/tools/__init__.py` 的 `TOOLS` 表——`tools/list` 返回什么、调用时要求什么能力，全部由它决定。

当前注册的工具：

| 工具 | 说明 |
| --- | --- |
| `context.get` / `context.fetch` | 按预算取上下文包；按需取回被引用/裁剪掉的具体片段 |
| `workspace.read` / `workspace.diff` | 读受管工作区文件；取变更的 unified diff |
| `proposal.create` | 提交改动提案（Agent 落改动的**唯一**入口） |
| `memory.recall` | 回忆项目记忆条目 |
| `flux_context` | 运行时上下文查询 |
| `terminal.execute` | 受控放行的终端执行（Agent Terminal Console，2026-10-05 决策） |

**硬禁令**（不是"忘了注册"，且没有任何开关能打开）：`workspace.apply`、`git.commit`、`git.push`、`secret.read`、`shell.exec` 一律不在工具表内——落盘、提交、推送、读密钥、裸 shell 通道都不对 agent 开放。

---

## 变更落地：Virtual Workspace（M2）

提案 → 虚拟 diff → 人工审查 → 应用，是**唯一**的文件改动落地路径：

- **状态机**：提案在 `pending → accepted/rejected → applied/expired` 之间流转；多次修改同一文件时旧提案按设计置 `expired`，过期提案无法被 Apply（`409 expired`）。
- **落盘**：`ApplyEngine` 只在人工批准后写盘，写前做 `path_guard` 校验（symlink 逃逸 fail-closed），写时生成可追溯的备份。
- **回滚与恢复**：支持单提案回滚、分组回滚（`apply-batches/{batch_id}/rollback`）、Apply 崩溃恢复与对账（`workspace/recovery`），不留下无法解释的半完成状态。
- **测试门禁**：Apply 会跑配置的测试命令，失败即红灯——测试失败绝不会被报告为成功。
- **Git 集成**：`git/status`、`git/diff`、`git/stage`、`git/unstage`、`git/commit`、`git/branches`、`git/checkout`；提交由用户在 UI 侧触发，agent 无提交能力。

---

## 上下文机制

上下文机制的**唯一真源在 Flux 侧**，agent 的对话历史私有、可丢，切换 agent 不搬迁对话。分三个阶段落地（commit `fad9155` / `4233a27` / `7c23b0b`）：

- **阶段 A**：step 事件日志、目录行与反向索引、fetch 取回、删除级联、九节交接文档。
- **阶段 B**：压缩流水线 S1–S5（含 S5 自校验）、动态预算与度量。
- **阶段 C**：事实脊柱、验证账本（信任分级）、入站脱敏、渐进替换。

相关数据表：`context_blobs` / `context_steps` / `context_step_tokens` / `context_redactions` / `context_verifications`。设计文档见 [`docs/CONTEXT_DESIGN.md`](docs/CONTEXT_DESIGN.md)。

---

## 客户端与发行

| 应用 | 技术栈 | 状态 |
| --- | --- | --- |
| `apps/web-dashboard` | React 19 + Vite（含 `@xterm/xterm`） | 工作台主界面：Solo / IDE 双模式、diff 双视图、内嵌终端 |
| `apps/desktop-avalonia` | Avalonia + .NET 8（原生桌面端） | 登录、概览、任务、Skills、系统诊断、设置、Solo 任务台（流水线 + 单 Agent）、项目列表、Agent 面板、Agent Terminal 已接真实接口；IDE 工作台待实现 |
| `apps/mobile-controller` | Android（Kotlin + Gradle） | 手机远程控制端 |
| `apps/showcase-web` | React | 展示页 |
| `apps/vscode-extension` | — | 待建 |

账号云服务（`flux.qiuli55.top`）独立维护，不随本仓库分发。

桌面端数据落在 `~/.flux`（`flux.db` / `workspace/` / `backups/` / DSH 落盘），**卸载不删数据、重装可继续使用**。

---

## 快速开始

```bash
# 1. 建虚拟环境并装依赖（Python 3.10）
make setup

# 2. 复制环境变量模板到仓库根（密钥留空即可，模型供应商会保持"未配置"状态）
cp .env.example .env

# 3. 初始化数据库（默认使用 SQLite，无需任何外部服务）
make migrate

# 4. 一条命令自检：ruff + format + OpenAPI 契约 + 迁移往返 + pytest
make verify

# 5. 启动后端，浏览器打开 http://127.0.0.1:8000/docs
make run
```

跑通后用 `curl` 冒烟（`providers` 与 `agents` 数随本机配置变化）：

```bash
curl -s http://127.0.0.1:8000/api/v1/health/ready
# {"success":true,"code":"ok","message":"","data":{"database":true,"providers":["local","deepseek"],"agents":1},"metadata":{}}
```

起前端工作台（另开一个终端）：

```bash
cd apps/web-dashboard && npm ci && npm run dev
```

起原生桌面端（会自动拉起本地 Python 后端；需先准备 `.venv` 与 `backend/requirements.txt`）：

```bash
cd apps/desktop-avalonia && dotnet run
```

打包 Linux x64 产物：`bash scripts/build_desktop_avalonia_linux.sh`，产物落在 `apps/desktop-avalonia/release/`。

打包 Windows 产物（在 Windows / PowerShell 上）：`pwsh scripts/build_desktop_avalonia_win.ps1`，默认 `win-x64`，产物为 `apps/desktop-avalonia/release/flux-desktop-win-x64.zip`。

桌面端自测（零第三方依赖的纯逻辑断言）：`dotnet run --project apps/desktop-avalonia.tests`，失败返回非零。

用 Docker Compose 起完整三件套（PostgreSQL + pgvector / Redis / 后端）：

```bash
make up     # docker compose up -d --build
make down
```

> `docker-compose.yml` 与 `backend/Dockerfile` 的镜像版本已按官方来源核对（2026-09-30 快照）：`pgvector/pgvector:pg17`、`redis:8-alpine`、`python:3.10-slim`。本机开发环境没有安装 Docker，因此容器构建链路尚未做过本地验证。

---

## 仓库结构

```
flux/
├── backend/                      # 后端：单一可部署单元（见规格附录 A12）
│   ├── flux/
│   │   ├── api/v1/               # HTTP 层：agents · tasks · projects · workspace · terminal
│   │   │                         #   connectors · models · git · dsh · memory · capabilities
│   │   │                         #   installations · cloud · health
│   │   ├── core/                 # 领域层（不依赖 FastAPI）
│   │   │   ├── agent_runtime/    # 档案注册表、生命周期状态机、DSH 客户端与事件映射
│   │   │   ├── task_engine/      # 调度器 + tasks 表读写 + DSH 桥接 + 决策
│   │   │   ├── workflow_engine/  # 工程状态机（不做 reasoning、不代 agent 调工具）
│   │   │   ├── model_gateway/    # 供应商抽象、路由、providers/（只服务平台内部）
│   │   │   ├── permission_engine/# RBAC + Capability 策略，fail-closed
│   │   │   ├── virtual_workspace/# 提案状态机 + unified diff + ApplyEngine + 备份/回滚
│   │   │   ├── context/          # 上下文机制：step 日志 / 压缩 S1–S5 / 脱敏 / 脊柱 / verify
│   │   │   ├── terminal/         # Agent 终端 + 人类 PTY 会话
│   │   │   ├── git_integration/  # Git 状态 / diff / 暂存 / 提交
│   │   │   ├── memory/ project_brain/ project_files/ project_scanner/ capability_import/
│   │   │   ├── mcp/              # MCP Server + 工具注册表（工具面唯一真源）
│   │   │   └── event/            # 进程内事件总线
│   │   ├── cli/ cloud/ connectors/ services/ db/
│   │   ├── models/               # SQLAlchemy 2.0 声明式模型（28 张表）
│   │   ├── schemas/              # Pydantic 请求/响应模型
│   │   ├── config.py / errors.py / logging.py / container.py / main.py
│   ├── migrations/               # Alembic（异步模板，22 个版本脚本）
│   ├── tests/                    # pytest：核心层 / 模型契约 / OpenAPI 契约 / API（57 个测试文件）
│   ├── Dockerfile
│   └── requirements.txt          # 固定版本快照
├── apps/                         # 客户端
│   ├── web-dashboard/            # React 19 工作台（Solo / IDE）
│   ├── desktop-avalonia/         # Avalonia + .NET 8 原生桌面端
│   ├── desktop-avalonia.tests/   # 桌面端纯逻辑自测（控制台断言）
│   ├── mobile-controller/        # Android 远程控制端
│   ├── showcase-web/             # 展示页
│   └── vscode-extension/         # 待建
├── connectors/                   # 具体连接器实现（M4 起，待建）
├── skills/                       # Skill 包（M5 起，待建）：backend / frontend / security / devops
├── nodes/                        # Flux Node（M8 起，待建）：android_node / desktop_node
├── docs/                         # 规格 / 架构 / 上下文 / MVP / 报告
│   ├── openapi.json              # 接口契约，随代码一起提交
│   └── ui-designs/               # 界面设计稿（10 张）
├── doc/                          # 发布设计、50 任务基准与阶段验收报告
├── scripts/
│   ├── export_openapi.py         # 导出 / 校验契约
│   ├── verify.sh                 # 本地一条命令自检
│   ├── build_desktop_avalonia_linux.sh  # 原生桌面端 Linux 打包
│   ├── build_desktop_avalonia_win.ps1   # 原生桌面端 Windows 打包
│   └── run_fault_injection_matrix.sh
└── Makefile
```

**命名约定**（规格附录 A12）：会被 Python 导入的目录一律用下划线（`backend/flux/agent_runtime`、`nodes/android_node`）；npm / 前端包目录沿用连字符（`apps/vscode-extension`）。

---

## 界面设计稿

`docs/ui-designs/` 下是 10 张设计稿，统一采用深色主题 + 青绿主色（约 `#0A0E14` 底 / `#2DD4BF` 强调色）；`apps/web-dashboard` 的实现以其为输入素材。

| 文件 | 界面 | 对应规格章节 | 交付里程碑 |
| --- | --- | --- | --- |
| `agent-detail-desktop.png` | Agent 详情（Overview / Activity / Tasks / Files / Metrics / Memory 六个页签，含任务时间轴与 Token 用量） | §13.2、§5.1 | M3 |
| `task-board-kanban.png` | 任务板（待处理 / 进行中 / 已完成 / 已暂停 / 已失败 五列看板 + 右侧任务详情） | §13.3、§5.2 | M3 |
| `virtual-workspace-review-v1.png` | Virtual Workspace 代码审查（文件树 + 左右对照 diff + AI 执行轨迹日志 + 团队面板） | §7、§13.4 | M2 / M3 |
| `virtual-workspace-review-v2.png` | Virtual Workspace 代码审查（同页面另一版布局，强调"提交审核 / 拒绝所有"，底部含本次任务预估成本） | §7、§13.4 | M2 / M3 |
| `cost-center.png` | 成本中心（KPI 卡、成本趋势、模型成本占比、明细表、按时间热力图、超额预警与优化建议） | §15.2、§13.5 | M6 |
| `skills-marketplace.png` | 技能市场（分类筛选 + 卡片网格 + 右侧技能详情含使用示例代码块） | §9、§13.6 | M5 |
| `connector-marketplace.png` | 连接器市场（连接状态徽标、使用统计、Connector SDK / MCP 入口） | §8、§13.6 | M4 |
| `settings.png` | 设置（账号与安全 / 工作空间 / 模型管理 / 连接器 / 通知 / 工程配置 / 费用与账单 / 高级设置） | §13.7、§18.4 | M3 |
| `mobile-screens-10up.png` | 移动控制端 10 屏合集（首页 / 任务 / AI 团队 / 任务详情 / 成本 / 项目 / 通知 / Agent 详情 / 个人中心 / 侧边栏） | §13.8 | M8 |
| `mobile-v1-20261002.png` | 移动控制端 v1 十屏（任务入口 / Solo 任务创建 / 需求澄清 / 执行方式与决策策略 / 任务执行中 / AI 自动处理 / 高风险决策 / 人工审核点 / 任务完成 / Agent 管理） | §13.8 | M8 |

---

## 接口契约

`docs/openapi.json` 是前端与插件开发者依赖的唯一接口契约，**随代码一起提交**（当前 79 条路径，含 MCP 单入口 `POST /mcp`）。

```bash
python scripts/export_openapi.py           # 重新导出
python scripts/export_openapi.py --check   # 校验是否漂移（CI 用）
```

所有响应统一为五段式（规格附录 A6）：

```json
{"success": true, "code": "ok", "message": "", "data": {}, "metadata": {}}
```

---

## 工程约定

- **分层**：`api/` 只做协议转换，业务逻辑放 `core/`；`core/` 不 import FastAPI。
- **一切外部调用必须走 Connector**，Agent 不直连外部系统。
- **密钥**只由对应 Provider 在内部读取，绝不下发给 Agent，也绝不写入仓库（规格 §14.3）。
- **事件命名** `resource.action`（如 `agent.started`、`workspace.changed`）。
- **提交信息**用 `feat:` / `fix:` / `docs:` / `chore:` 前缀。
- **测试**：契约优先——`tests/test_openapi_contract.py` 与 `tests/test_models_contract.py` 会断言 API 路径与表结构，任何偏离都会红。
- **工具面**：MCP 工具表是唯一真源；新增 agent 能力必须先进 `TOOLS` 表并过权限策略。

---

## 已知限制与待办

- Python 3.10 已进入仅安全修复阶段，官方计划 **2026 年 10 月 EOL**。当前本地环境与 `requirements.txt` 快照均基于 3.10，升级到 3.12 / 3.13 的路径已记入规格 §19 的待定事项。
- 本机无 Docker，容器构建与 Compose 编排未做本地验证。
- OpenAI / Anthropic 两个 Provider 适配器已完成并离线验证，但**未做真实调用**（本机无密钥）；只有 `deepseek` 做过真实调用。
- **M2 收口项**：多文件提案的原子 Apply（Proposal / ChangeSet 级事务）仍在收口；单文件真实写盘、备份、回滚与崩溃恢复已落地。
- **桌面端**：只做原生 Avalonia 客户端，发行目标为 Windows + Linux；macOS 不交付。Solo 任务台、Agent Terminal、Agent 面板、项目列表已实现，IDE 工作台仍在实现中。
- **M4 / M5 / M6 / M7 / M9 未开始**：`connectors/`、`skills/*`、`nodes/*`、`apps/vscode-extension` 目录仍为空；成本计价未接（`pricing=None` → `cost` 为 `null`）。
- **M8 部分**：移动控制端已有 Android 端，`nodes/` 的节点实现待建。

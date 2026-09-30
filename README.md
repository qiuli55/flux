# Flux

集合 agent 的 AI 软件工程操作系统：统一上下文、统一工具（MCP 面）、统一 Skill，各只有一份真源，所有 agent 共享。

Flux **本身不做 agent**——没有 loop、不组装 prompt、不替 agent 调模型、不存 agent 对话（目标架构 [FLUX_TARGET_ARCHITECTURE.md](docs/FLUX_TARGET_ARCHITECTURE.md) §1）。它内置一个以 **DeepSeek 为基底**的 agent（DSH + Cordis），与 Codex / Claude Code / OpenCode 等外部 agent 经**同一个 MCP 面**对等消费能力。

核心差异化是 **Virtual Workspace**——AI 的每一次改动都以"提案 → 虚拟 diff → 人工审查 → 应用"的流程落地，**永不直接覆盖你的文件**。

规格的唯一权威来源是 [`docs/Flux_Master_Spec_v0.1.md`](docs/Flux_Master_Spec_v0.1.md)（34 份源文档合并去重后的主规格，含 22 条冲突裁决）。代码与规格不一致时以规格为准，并把实施期发现的新问题回写到规格附录 A。

**架构方向以 [`docs/FLUX_TARGET_ARCHITECTURE.md`](docs/FLUX_TARGET_ARCHITECTURE.md)（2026-10-01）为最高优先级**：它定义了「Flux 不做 agent、只做集合 agent 的平台」这一硬约束与退役清单；与本文档 §1 的定位描述冲突时以它为准。

---

## 当前进度

**M0 项目基础（已完成）** —— 工程基础与契约层均已冻结并实测通过：

| 能力 | 状态 | 证据 |
| --- | --- | --- |
| 数据层：14 张表 + Alembic 迁移 | 完成 | `alembic upgrade head` / `downgrade base` 双向通过，autogenerate 无漂移 |
| API 契约：13 条路径（规格 §12 全覆盖） | 完成 | `docs/openapi.json`，`scripts/export_openapi.py --check` 通过 |
| Agent Runtime 状态机（含 STOPPED） | 完成 | 59 个 pytest 用例全绿（全仓现为 292 个） |
| Model Gateway（本地 echo 供应商可离线跑通） | 完成 | 真实供应商适配器属 M1（#013–#015） |
| Virtual Workspace 状态机 + unified diff | 完成 | M0 只做状态跃迁与审计，真实写盘属 M2 |
| 权限策略（RBAC + Capability，fail-closed） | 完成 | — |
| 事件总线 / 任务调度器 / 工作流编排 | 完成 | — |
| Connector 契约与注册表 | 完成 | 五个连接器实现属 M4 |
| 工程化入口：Makefile / verify.sh / CI / Docker | 完成 | `bash scripts/verify.sh` 五步全过；CI 双 job；容器链路未本地验证（无 Docker） |

里程碑主线见规格 §19.1：M1 核心运行时 → M2 Virtual Workspace → M3 IDE → M4 Connector → M5 Skill 与 Project Brain → M6 成本可观测 → M7 浏览器与环境自动化 → M8 设备网络 → M9 插件生态。

---

## M1 核心运行时（进行中）

| 能力 | 状态 | 证据 |
| --- | --- | --- |
| 真实供应商适配器（Issues #013–#015） | 完成 | `local` / `openai` / `anthropic` / `deepseek` 四家全部注册，28 个离线用例全绿 |
| 供应商容错：超时、退避重试、错误映射 | 完成 | `429`/`5xx`/超时按 `0.5s × 2^n` 退避重试 2 次，耗尽后映射为领域错误码 |
| 架构对齐 Phase 1：退役 agent loop | 完成 | 删除 `AgentExecutor` / `DeveloperAgent` / `TesterAgent` / `AgentContext` / `codex_cli` provider / `virtual_workspace/flow.py`；`AgentManager` 重构为纯档案注册表（commit `45db1b9`） |
| 架构对齐 Phase 1 收尾：symlink 逃逸防护 | 完成 | 新增 `flux/core/virtual_workspace/path_guard.py` 作唯一真源（逐段 `is_symlink()` fail-closed + realpath 包含性），Apply / Backup / File Explorer 三处共用（commit `5babc59`） |

**Adapter 用统一的薄 HTTP 层调用上游 API**，不引入各家官方 SDK——依赖面更小，`httpx.MockTransport` 可完全离线断言。默认模型 id 与覆盖方式：

| 供应商 | 默认模型 | 覆盖变量 | 鉴权 | 备注 |
| --- | --- | --- | --- | --- |
| `local` | `local-echo` | `FLUX_LOCAL_MODEL_NAME` | 无 | 离线回显，零依赖，M0 起可用于跑通链路 |
| `openai` | `gpt-5.5` | `FLUX_OPENAI_MODEL` | `Authorization: Bearer` | 输出上限字段为 `max_completion_tokens` |
| `anthropic` | `claude-sonnet-5-5` | `FLUX_ANTHROPIC_MODEL` | `x-api-key` + `anthropic-version: 2023-06-01` | `system` 走顶层字段；`temperature` 钳制到 `[0,1]` |
| `deepseek` | `deepseek-flash` | `FLUX_DEEPSEEK_MODEL` | `Authorization: Bearer` | 见下方 thinking 模式注意点 |

模型 id 取自 2026-09-30 快照（规格附录 A-2）。**可配置性已实测**（2026-09-30，离线，无网络调用）：

- 默认值实测：`{'local': 'local-echo', 'openai': 'gpt-5.5', 'anthropic': 'claude-sonnet-5-5', 'deepseek': 'deepseek-flash'}`
- 用 `FLUX_OPENAI_MODEL` / `FLUX_ANTHROPIC_MODEL` / `FLUX_DEEPSEEK_MODEL` 覆盖后，装配出的 `model_name` 随之改变，无需改代码
- 供应商是否可用只取决于密钥：不配密钥时 `router.available()` 为 `['local']`，加上 `FLUX_OPENAI_API_KEY` 后变为 `['local', 'openai']`——`/api/v1/health/ready` 的 `providers` 列的就是这个可用集合

**双 Provider 验证边界**（如实说明）：

- `deepseek`：**已做真实调用**——2026-09-30 用 `deepseek-flash` 实际请求成功返回 `content="收到"`、`finish_reason="stop"`、usage 35/18、latency 591 ms。
- `openai` / `anthropic`：仅经 `httpx.MockTransport` 离线验证（请求体、请求头、响应解析、错误映射均有断言），**未做真实调用**——本机没有这两家的密钥。

> **DeepSeek thinking 模式注意点（实测坑）**：`deepseek-flash` 的 thinking 默认开启，推理 token 计入 `max_tokens`。`max_tokens=32` 时输出额度会被推理吃光，`choices[0].message.content` 返回空串（内容在 `reasoning_content` 里）；`max_tokens=512` 正常。因此不要用很小的 `max_tokens` 做探活，模块默认 `1024` 是下限而非推荐值。
>
> **成本计价**：M1 的 Provider 不内置价格表，`pricing=None` 时 `cost` 为 `null`——绝不用猜测值填充成本，真实计价属 M6。

M1 尚未完成：Task 由内存落库到 `tasks` 表（当前调度器仍是进程内实现）。

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

跑通后用 `curl` 冒烟：

```bash
curl -s http://127.0.0.1:8000/api/v1/health/ready
# {"success":true,"code":"ok","message":"","data":{"database":true,"providers":["local"],"agents":0},"metadata":{}}
```

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
│   │   ├── api/                  # HTTP 层：统一响应体、异常处理器、依赖注入
│   │   │   └── v1/               # 规格 §12 的 13 条路由
│   │   ├── core/                 # 领域层（不依赖 FastAPI）
│   │   │   ├── agent_runtime/    # 档案注册表、生命周期状态机、DSH 客户端
│   │   │   ├── task_engine/      # 优先级调度器
│   │   │   ├── model_gateway/    # 供应商抽象、路由、providers/（只服务平台内部）
│   │   │   ├── workflow_engine/  # 工程状态机（不做 reasoning、不代调工具）
│   │   │   ├── permission_engine/# RBAC + Capability 策略
│   │   │   ├── event/            # 进程内事件总线
│   │   │   └── virtual_workspace/# 提案状态机 + unified diff + 入参校验器
│   │   ├── models/               # SQLAlchemy 2.0 声明式模型（14 张表）
│   │   ├── schemas/              # Pydantic 请求/响应模型
│   │   ├── connectors/           # Connector 契约与注册表
│   │   ├── services/             # cost_service 等横切服务
│   │   ├── db/                   # 异步引擎与会话
│   │   ├── config.py             # pydantic-settings，前缀 FLUX_
│   │   ├── errors.py             # 领域异常 → HTTP 状态码映射
│   │   ├── logging.py            # 结构化日志
│   │   ├── container.py          # 组合根：装配全部单例
│   │   └── main.py               # 应用工厂 create_app()
│   ├── migrations/               # Alembic（异步模板）
│   ├── tests/                    # pytest：核心层 / 模型契约 / OpenAPI 契约 / API
│   ├── Dockerfile
│   └── requirements.txt          # 固定版本快照
├── apps/                         # 客户端（M3 起）：desktop / vscode-extension / web-dashboard / mobile-controller
├── connectors/                   # 具体连接器实现（M4 起）
├── skills/                       # Skill 包（M5 起）：backend / frontend / security / devops
├── nodes/                        # Flux Node（M8 起）：android_node / desktop_node
├── docs/
│   ├── openapi.json              # 接口契约，随代码一起提交
│   └── ui-designs/               # 界面设计稿（M3 的输入素材）
├── scripts/
│   ├── export_openapi.py         # 导出 / 校验契约
│   └── verify.sh                 # 本地一条命令自检
└── Makefile
```

**命名约定**（规格附录 A12）：会被 Python 导入的目录一律用下划线（`backend/flux/agent_runtime`、`nodes/android_node`）；npm / 前端包目录沿用连字符（`apps/vscode-extension`）。

---

## 界面设计稿

`docs/ui-designs/` 下是 9 张设计稿，属于 **M3（IDE 体验）** 的输入素材，不参与 M0/M1 的实现。它们统一采用深色主题 + 青绿主色（约 `#0A0E14` 底 / `#2DD4BF` 强调色）。

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

设计稿中存在若干待收口的用词不一致，实现前需统一：侧边栏语言（`agent-detail-desktop.png` 为英文，其余为中文）、"技能市场 / 插件市场"与"Agent 团队 / 代理团队"的称谓、以及 `agent-detail-desktop.png` 中疑似笔误的 `GRI Repository`（应为 `Git Repository`）。

---

## 接口契约

`docs/openapi.json` 是前端与插件开发者依赖的唯一接口契约，**随代码一起提交**。

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
- **测试**：契约优先——`tests/test_openapi_contract.py` 与 `tests/test_models_contract.py` 会断言 §12 的路径、§11 的表结构，任何偏离都会红。

---

## 已知限制与待办

- Python 3.10 已进入仅安全修复阶段，官方计划 **2026 年 10 月 EOL**。当前本地环境与 `requirements.txt` 快照均基于 3.10，升级到 3.12 / 3.13 的路径已记入规格 §19 的待定事项。
- 本机无 Docker，容器构建与 Compose 编排未做本地验证。
- OpenAI / Anthropic / DeepSeek 三个 Provider 适配器已完成（M1，Issues #013–#015）；本机只有 DeepSeek 密钥做了真实调用，OpenAI / Anthropic 仅经离线 MockTransport 验证。
- Task Engine 当前仍是进程内实现，任务未落 `tasks` 表——这是 M1 剩余的最后一项。
- Virtual Workspace 在 M0 仅实现状态跃迁与审计记录，真实文件写盘、备份、测试回归与 Git 集成属 M2。
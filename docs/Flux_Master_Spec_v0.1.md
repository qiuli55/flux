# Flux 主技术规格（Master Specification）

**版本：v0.1（合并版）　生成日期：2026-09-30**

---

## 0. 文档说明

### 0.1 本文档的定位

本文档是 **Flux 的唯一权威规格**（single source of truth）。它由 relay 上传的 34 份 `AI_Engineering_OS_*.docx`（v0.1）合并去重而成，替代此前分散、互相重叠的文档集合。后续所有设计变更只改本文档，不再维护分散副本。

### 0.2 源文档清单（34 份）

| 类别 | 文档 |
| --- | --- |
| 产品与定位 | PRD、Full Technical Specification、Competitive Analysis、Open Source Strategy、Launch Plan、GitHub README Draft、Product Demo Script、Interview Presentation |
| 架构与运行时 | Architecture Design、Enterprise Architecture、Agent Runtime Detailed Design、Core Source Code Architecture、Agent System Prompt Design、Agent Workflow Examples |
| 接口与数据 | API Specification、Database Design、Database ER Design、Code Implementation Specification、Connector SDK Guide、Plugin Ecosystem Design、Plugin SDK Example |
| 核心差异化 | Virtual Workspace Design、UI Prototype Specification |
| 质量与安全 | Testing Strategy、Agent Evaluation System、Observability Design、Security Threat Model、Technical Decision Record |
| 计划与规范 | GitHub Milestone Roadmap、First 90 Days Plan、MVP Coding Plan、Repository and Coding Specification、Developer Handbook、Local Deployment Guide |

### 0.3 合并约定

- **【裁决】**：源文档之间存在冲突之处，本文档已做出取舍，理由见「附录 A 冲突裁决表」。
- **【补全】**：源文档缺失、但为让设计闭环必须补齐的内容，非源文档原意。
- **【待定】**：需要人工决策、本文档不擅自决定的事项。

### 0.4 已知状态（重要）

**本套文档是纯设计骨架，尚无实现代码。** 34 份源文档中不含任何可运行代码、数据库 DDL、接口样例报文或性能数据。所有「接口」「类」「命令」均为命名清单，不是可执行契约。

### 0.5 裁决原则

1. 技术层描述（架构 / Model Gateway / DB）优先于产品层举例（PRD 随笔举例）。
2. 可执行粒度优先：更细、更接近落地者胜出。
3. 覆盖面更广者优先。
4. 安全相关冲突一律从严。

---

## 1. 项目定位

### 1.1 一句话定位

Flux（原名 AI Engineering OS，缩写 AIOS；2026-09-30 起统称 Flux，见附录 A 裁决 A22）是一个面向开发者的 **AI 软件工程操作系统**：让开发者在一个统一工作区内管理多个 AI 工程师（Agent），并让 AI 参与完整软件开发流程。

### 1.2 它不是什么

- 不是单纯的 AI Chat 工具；
- 不是单纯的 AI IDE；
- 不是单纯的 Multi-Agent Framework。

它是 **Agent Runtime + Developer Environment + Knowledge System + Device Network + Automation Platform** 的合体。

### 1.3 目标用户

- 使用多个 AI 编码工具、被多窗口切换困扰的个人开发者；
- 需要 AI 辅助但要求过程可控、可审计的工程团队；
- 需要把 AI 接入自有工具链、设备与环境的企业。

### 1.4 长期愿景

从「AI 编码助手」演进为「完整的 AI 软件工程基础设施」：开发者在一个工作区内调度 AI 架构师、开发者、测试者与自动化系统。

### 1.5 竞品与差异化

| 竞品 | 定位 | 其强项 | Flux 的差异点 |
| --- | --- | --- | --- |
| Cursor | AI-first 代码编辑器 | 编码工作流顺畅、AI 交互快 | 多 Agent 编排不足、缺少工程管理层 → Flux 补此空白 |
| Trae | AI 集成 IDE | IDE 体验、Agent 辅助开发 | 开放 Agent 生态、跨模型编排、透明的 Virtual Workspace |
| Devin | 自主软件工程师 | 端到端任务执行 | 人保持控制权、多个专职 Agent 协作 |
| OpenHands 等 Agent 框架 | 开源 Agent 执行 | 开源、可扩展 | 完整开发环境、IDE 集成、成本管理、连接器与设备生态 |

**核心差异 = 多 Agent 工程团队 + 透明代码提案系统 + 统一连接器生态 + 项目记忆 + 开发者可控的自动化。**

---

## 2. 核心问题与解法

| # | 问题 | 解法 |
| --- | --- | --- |
| 1 | AI Coding 工具割裂，Claude / Codex / DeepSeek 需多窗口切换 | 统一 Agent Runtime |
| 2 | AI 直接改文件，过程黑箱、不可控 | Virtual Workspace（提案→审查→应用） |
| 3 | AI 缺少项目长期上下文 | Project Brain（持久项目记忆） |
| 4 | AI 无法操作完整工程环境 | Connector 系统（外部能力的唯一出入口） |

---

## 3. 术语表

| 术语 | 含义 |
| --- | --- |
| Agent | 承担某个软件工程角色的 AI 实体（角色 + 模型 + 技能 + 工具 + 记忆 + 权限） |
| Task | 用户请求经 Task Engine 拆解后的执行单元 |
| Skill | 可复用的 AI 能力包（提示、规则、工具权限、示例、知识、测试） |
| Connector | Agent 访问外部系统的唯一受控通道 |
| Project Brain | 持久化项目知识系统（架构决策、规范、API、技术栈、历史变更） |
| Virtual Workspace | AI 改动的虚拟提案层，未经人工审查不落地 |
| Usage Record | 单次模型调用的用量与成本记录 |
| Handoff | Agent 之间结构化移交 |
| Flux Node | 运行在外部设备上的设备节点 |

**【裁决】** 产品名统一写作 **Flux**（该裁决原为「全文统一缩写为 AIOS」，2026-09-30 改名后由附录 A 裁决 A22 取代）；源文档中「Skill Runtime」统一写作「Skill System 的运行时」，避免与「Skill 包格式」混淆。

---

## 4. 总体架构

### 4.1 分层

```
Client Layer
├── Desktop Application
├── VS Code Extension
├── Web Dashboard
└── Mobile Controller
        ↓
Core Layer
├── Agent Runtime        ├── Model Router
├── Task Engine          ├── Workflow Engine
├── Memory Service       └── Permission Engine
        ↓
Infrastructure Layer
├── Connector Runtime    ├── Skill Runtime
├── Environment Manager  └── Device Manager
        ↓
External Systems
GitHub · Browser · Terminal · Database · Cloud · Mobile Devices
```

### 4.2 核心原则

1. **Agent 不直接访问外部系统**，一切经由 Runtime 与 Connector。
2. **重要变更需人工审批**。
3. **项目知识持久化**。
4. **AI 动作透明可审计**。
5. **模块化、可扩展、可观测**。

### 4.3 企业版多租户

隔离层级：Organization → Team → Project → User。
各组件（Agent worker、Model Gateway、Connector 服务、存储层）**独立横向扩展**。

---

## 5. 核心服务

### 5.1 Agent Runtime

**职责**：Agent 创建、生命周期管理、任务执行、工具调用、记忆访问、Agent 间通信。

**Agent 实体模型**

| 维度 | 字段 |
| --- | --- |
| Identity | name、role、description |
| Execution | model_provider、model_name、system_prompt |
| Capabilities | skills、tools、permissions |
| Memory | 短期上下文、长期项目知识 |

**生命周期状态机**

```
CREATED → INITIALIZING → READY → RUNNING → WAITING_TOOL → REVIEWING → COMPLETED / FAILED
```

**调度器**：队列管理、优先级、资源分配、重试策略。
调度依据：任务复杂度、模型成本、Agent 可用性。

**多 Agent 协作**（示例流程）

```
用户请求 → Tech Lead Agent 分析 → Architect Agent 设计 → Developer Agent 实现
        → Reviewer Agent 审查 → Tester Agent 验证
```

**Handoff 协议**：结构化消息，含 sender / receiver / task context / expected output / related artifacts。

**记忆层级**：短期＝当前会话与任务状态；长期＝Project Brain、决策、编码规则。

**错误处理**：模型失败、工具失败、超时、权限拒绝 → 重试 / 降级模型 / 人工审批。

**可观测**：每次执行产出 trace、token 用量、成本、工具调用记录、最终结果。

**主要实现类**：`AgentManager`（创建与管理）、`AgentExecutor`（执行）、`AgentContext`（上下文）、`AgentScheduler`（调度）、`AgentMemory`（记忆）。

**【DSH 接入裁决，2026-09-30】** 本节的 Agent Loop / Session / Tool / Skill / Subagent **不由 Flux 自研**，改由 DeepSeek Harness（DSH）提供；Flux 保留 `AgentManager` 等对外接口与生命周期语义不变，新增 `FluxDshClient` 作为接入层。接入方式为**官方 Python SDK**（`deepseek-harness-sdk==0.1.5rc1`，stdio NDJSON JSON-RPC，不需要系统 Node / pnpm / 构建），模型走 DeepSeek 官方 `deepseek-v4-flash`。范围内不做：自研 Agent Loop、自研 Plugin Runtime、自研 Session Runtime。完整方案与分阶段实施顺序见 [DSH_FLUX_INTEGRATION_PLAN.md](DSH_FLUX_INTEGRATION_PLAN.md)（§10 接入口径、§18 Phase 1–7）。

**Agent 接口**：`create()`、`execute()`、`stop()`、`get_status()`。
（Connector 接口见 §8.2。）

### 5.2 Task Engine 与 Workflow

用户请求 → Task → 子任务 → Agent 执行 → 结果。
支持任务创建、执行、历史查询、取消。
Workflow Engine 负责多步骤流程编排。

### 5.3 Model Gateway / Model Router

**统一接口**：`ModelProvider` → `OpenAIProvider` / `AnthropicProvider` / `DeepSeekProvider` / `LocalModelProvider`。

**统一方法**：`chat()`、`stream()`、`count_tokens()`、`calculate_cost()`。所有 Provider 实现同一契约。

**路由依据**：任务复杂度、成本、历史效果。

**【裁决】** 供应商口径以架构层为准，统一为 **OpenAI / Anthropic / DeepSeek / 本地模型** 四类。源 PRD 中的「Claude」「Codex」属品牌举例，分别归入 Anthropic 与 OpenAI；「Developer Agent → Model: Codex」的写法改为「模型供应商可选」。

**【实施计划落地，2026-09-30】协议兼容的复用与回退规则**（⑫ 端到端实测时补）：

- **协议兼容即复用，不新增适配器。** 只要上游的请求/响应结构符合四类协议之一，就直接指向既有适配器。实例：MiniMax 提供 Anthropic 兼容端点（`https://api.minimaxi.com/anthropic`，`x-api-key` + `anthropic-version` + `POST /v1/messages`），因此接入 MiniMax 只需设 `FLUX_ANTHROPIC_BASE_URL` / `FLUX_ANTHROPIC_MODEL` / `FLUX_ANTHROPIC_API_KEY`，**不写任何新供应商代码**。
- **回退必须同时换供应商与模型 id。** Agent Manifest 里声明的是某个供应商的模型名（如 developer 的 `deepseek-flash`）。当该供应商未配置、按默认供应商回退时（见 §12.5），必须把 model 一并换成目标供应商在配置里声明的模型 id；否则会把别家的模型名发给上游，被直接拒绝。规则实现在 `container._developer_manifest()` / `_model_for()`，并由单测锁定。

### 5.4 Event Bus

服务间通过事件通信。事件命名格式 `resource.action`：

```
agent.started · agent.completed · task.created · task.failed
connector.executed · workspace.changed
```

### 5.5 Permission Engine

权限模型 = **RBAC + Capability**。

Capability 示例：`file.read`、`file.write`、`terminal.execute`、`deploy`、`secret.access`。

企业角色：Admin（全管理）、Developer（代码与任务）、Reviewer（审批）、Observer（只读）。

### 5.6 Memory Service / Project Brain

持久化项目知识：架构决策、编码规范、API 信息、技术栈、历史变更。

存储：PostgreSQL 元数据 + 向量数据库（pgvector）语义检索。
能力：项目索引、架构记忆、决策记录、语义搜索。

**【实施计划 ⑪ 落地，2026-09-30】** 第一版是**结构化记忆，不是 RAG**：不引入 embedding、不建向量索引，`project_memory` 表的 `embedding` 列留空——按实施计划 ⑪ 的边界，"以后再加 Semantic Search"，现在强行引入只会把闭环验证拖长（切换点是 M5，届时只补一个检索层，不改表）。

六个分区（`flux.enums.BrainSection`，写入语义分两类）：

| 分区 | 内容 | 写入语义 |
| --- | --- | --- |
| `overview` | Project Overview：项目是什么 | **覆盖**（只保留最新一份） |
| `tech_stack` | Tech Stack：语言、框架、包管理器、入口与命令 | **覆盖** |
| `architecture` | Architecture：主要模块与分层 | **覆盖** |
| `coding_rules` | Coding Rules：项目编码规范 | **覆盖** |
| `decisions` | Decisions：重要技术决策 | **追加**（历史不可覆盖） |
| `agent_notes` | Agent Notes：Agent 产生的有价值信息 | **追加** |

现状型分区用覆盖而不是追加，是为了让 `context()` 拿到的一定是当前事实，读取时不必猜"哪条最新"；历史决策由 `decisions` 承载。

**消费入口**：`ProjectBrain.context()` 按上表顺序把有内容的分区拼成一段 Markdown（累积型只带最新 20 条，见 `CONTEXT_MAX_ENTRIES`），供 Agent 执行前注入——这是"AI 跨会话理解项目"的实际落地方式。空分区不出现在上下文里，不给 Agent 塞空标题。

**与 ⑩ 的关系**：Project Scanner 扫出的画像写进 `overview` + `tech_stack`（`metadata.source = "scanner"`），后续人工或 Agent 可以覆盖这两区把"是什么"补成人话。

### 5.7 Cost Service

按 provider / model / task 维度记录输入输出 token 与费用，供 Cost Dashboard 展示（见 §15）。

### 5.8 Project Scanner（⑩）

**【实施计划 ⑩ 落地，2026-09-30】** 把一棵目录树读成可机器消费的 `ProjectProfile`，作为 Project Brain 的第一手事实来源。

**扫描对象**：文件结构（顶层条目、语言分布）、Git 信息（是否仓库 + 当前分支）、依赖清单（`pyproject.toml` / `requirements*.txt` / `setup.py` / `Pipfile` / `package.json` / `go.mod` / `Cargo.toml` 与各类锁文件）、常见配置（`Dockerfile`、`docker-compose.yml`、`Makefile`、`tsconfig.json`、`ruff.toml`、`pytest.ini` 等）、README、入口文件。

**产出画像**（实施计划 ⑩ 的 Project Profile 字段）：

| 字段 | 含义 |
| --- | --- |
| `languages` / `primary_language` | 按文件后缀统计（`.md`/`.json`/`.yaml`/`.html`/`.css` 等 markup 与配置不计入），按文件数降序 |
| `frameworks` | 清单里出现过的依赖名映射（FastAPI / Django / React / Vite …），只报"能证明的" |
| `package_manager` | 由锁文件与清单判定：uv / poetry / pipenv / pip、pnpm / yarn / bun / npm |
| `manifests` | 命中的清单与常见配置文件（相对路径，按字典序） |
| `entry_points` | 常见入口文件名（`main.py` / `manage.py` / `index.ts` …）+ `package.json` 的 `main` 字段 |
| `test_commands` / `build_commands` | 由生态规则与 Makefile 目标推导的**具体命令**（如 `pytest`、`pnpm run test`、`make build`、`docker build -t <项目名> .`） |
| `git_repository` / `git_branch` | 复用 ⑨ 的 `GitClient`（`not_a_git_repository` → `false`），不自己解析 `.git/HEAD` |
| `structure` / `files_scanned` / `truncated` | 顶层条目（目录带 `/`）、实际扫描文件数、是否触到上限被裁剪 |

**三条边界**：

1. **只读无副作用**：不调用模型、不落库、不写被扫描的项目（落库由 ⑪ 负责）。
2. **有界遍历**：文件数与目录深度都有上限（`FLUX_PROJECT_SCAN_MAX_FILES` / `FLUX_PROJECT_SCAN_MAX_DEPTH`），触顶时画像照常产出但 `truncated=true`，绝不为了"扫全"打满时间与内存。噪声目录（`.git`、`.venv`、`node_modules`、`__pycache__`、`.flux`、`dist`、`build` 等）一律跳过。
3. **不猜语义**：只报告能证明的事实（文件后缀、依赖名、锁文件、Makefile 目标）；项目"是做什么的"由人来写，Scanner 不编故事。

Python 与 Node 生态的框架/命令探测为主（覆盖 Flux 自身的栈与实施计划列出的清单），Go / Rust / Makefile / Dockerfile 作为补充；对 Python 清单只做"是否出现某个依赖名"的判定，不解析到 TOML 语法层。

---

## 6. Agent 角色与提示管理

### 6.1 六个内置角色

| 角色 | 职责 | 可用工具 |
| --- | --- | --- |
| Tech Lead Agent | 理解需求、拆解任务、指派 Agent、审阅整体架构 | Project Brain、GitHub、文档 |
| Architect Agent | 设计系统架构、选型、产出技术决策、评估可扩展性 | Project Brain、文档 |
| Developer Agent | 实现功能、经 Virtual Workspace 改代码、写测试、解释改动 | GitHub、Terminal |
| Reviewer Agent | 审查代码质量、找 Bug、查安全问题、提改进建议 | GitHub、静态分析 |
| Tester Agent | 制定测试计划、执行测试、分析失败、报告结果 | Terminal、设备节点 |
| DevOps Agent | CI/CD、部署、基础设施、监控 | Terminal、Cloud、GitHub |

**设计理念**：Agent 被视为**软件工程角色**，而非聊天机器人。每个 Agent 具备角色、目标、可用工具、权限、通信规则。

### 6.2 Agent Manifest

每个 Agent 必须声明：name、role、model、skills、tools、permissions。

示例（Developer Agent）：Skills = Backend；Tools = GitHub、Terminal；Permissions = Write Code。

**落地实现（2026-09-30）**：Manifest 以 YAML 声明，存放在 `backend/flux/core/agent_runtime/manifests/`，由 `flux.core.agent_runtime.manifest` 负责加载与校验。字段定义：

| 字段 | 必填 | 取值 / 说明 |
| --- | --- | --- |
| `name` | 是 | Agent 唯一名称，同名即加载失败 |
| `role` | 是 | `tech_lead` / `architect` / `developer` / `reviewer` / `tester` / `devops` |
| `model.provider` | 是 | `openai` / `anthropic` / `deepseek` / `local` |
| `model.model` | 是 | 模型 id，必须写具体值（如 `deepseek-flash`），不留占位符 |
| `description` | 否 | 角色的职责与目标（§6.1） |
| `system_prompt` | 否 | 角色提示词，纳入版本管理（§6.3） |
| `skills` | 否 | 技能标签，如 `backend`、`testing` |
| `tools` | 否 | 工具名，如 `filesystem`、`terminal`、`git` |
| `permissions` | 否 | 权限项，取值同 §5.5 Capability（`file.read` / `file.write` / `terminal.execute` 等） |

**硬规则（fail-closed，不做静默忽略）**：

1. Manifest **不得包含 API Key**：任何键名含 `key` / `secret` / `token` / `password` / `credential`（含嵌套层）都直接拒绝加载。密钥只由被调用的 Provider 自己从环境读取，不下发给 Agent（§14.3）。
2. 未知字段、未知 `role` / `provider` / 权限项一律加载失败，并回报具体字段与允许取值。
3. `role` / `provider` / 权限项大小写不敏感：`role: Developer` 等价于 `developer`。
4. `permissions` 缺省为空集，即该 Agent 只能拿到上下文，不能碰文件与终端（最小权限）。

**声明与实例分离**：Manifest（声明）→ `AgentSpec`（运行时规格）→ `AgentHandle`（实例）。入口为 `AgentManager.create_from_manifest()` 与 `AgentManager.create_builtin_agents()`。

**内置第一批 Agent（§19.7：只做 4 个）**：Tech Lead、Developer、Reviewer、Tester，均为 `deepseek` / `deepseek-flash`。权限按最小化配置：Tech Lead 与 Reviewer 只有 `file.read`；Developer 额外持有 `file.write` + `terminal.execute`；Tester 持有 `file.read` + `terminal.execute`。Architect、DevOps 待核心闭环稳定后再加。其中 Developer 与 Tester 已写成可执行的 Agent（`developer.py` / `tester.py`，契约见 §6.7 / §6.8），Tech Lead 与 Reviewer 目前只有 Manifest 声明。

### 6.3 提示版本管理

Prompt 纳入版本控制，每次含：角色定义、规则、约束、示例、评测标准。

### 6.4 协作规则

Agent 之间通过结构化消息通信，须遵守：解释决策、产出制品（artifact）、尊重权限边界、保留人工审批点。

### 6.5 人工审批点（必须）

- 大规模代码改动
- 数据库迁移
- 部署操作
- 安全敏感操作

### 6.6 典型工作流

**功能开发**：需求 → Tech Lead 分析 → Architect 设计 → Developer 编码 → Reviewer 审查 → Tester 验证。

**Bug 修复**：先由轻量 Debug Agent 排查；不成功则升级到更强的编码 Agent；Developer 产出修复提案；Reviewer 批准。

**架构变更**：Architect 出方案 → Tech Lead 评估影响 → Developer 实施迁移 → Tester 跑回归。

### 6.7 Developer Agent 提案输出契约

**【实施计划 §3.1 ③ 落地，2026-09-30】** Developer Agent 的职责是把需求变成**一份可审阅的代码改动提案**；它永远不写用户真实文件（§19.7 M2 产品原则），落盘一律由 Apply Engine 完成。

**输入**：需求文本 + 相关文件的当前内容 `{相对路径: 内容}`，由调用方提供（后续由 Virtual Workspace / Project Scanner 供给）。输入由代码拼装成三段：`## 需求`、`## 相关文件现状（改动前）`、`## 输出契约`。

**输出**：模型只返回一个 JSON 对象。

```json
{
  "summary": "用一两句话说明这次改动做了什么",
  "changes": [
    {"path": "相对项目根的 POSIX 路径", "content": "改动后该文件的完整内容", "reason": "为什么这样改"}
  ]
}
```

**解析规则（fail-closed，不做静默兜底）**：

| 情形 | 处理 |
| --- | --- |
| 纯 JSON / ```json 围栏 / 夹带说明文字 | 依次尝试提取，均可解析 |
| 顶层不是对象（如数组） | 报 `validation_error` |
| `changes` 缺失、为空、不是数组 | 报错 |
| `path` / `content` / `reason` / `summary` 类型不对 | 报错 |
| `path` 是绝对路径或含 `..` | 报错（禁止写到项目根之外） |
| 同一次提案内路径重复（归一化后） | 报错 |
| 完全提取不出 JSON | 报错，并在 details 里附原文前 300 字符预览 |

**落地位置**：`flux.core.agent_runtime.developer`（`DeveloperAgent` / `CodeChangeSet` / `FileChange` / `parse_code_change_set` / `build_developer_prompt`）；Developer 角色提示词写在 `manifests/developer.yaml` 的 `system_prompt`（§6.3 纳入版本管理）。

**边界**：本步只产出提案草稿（`CodeChangeSet`）——不落库、不算 hash、不生成 diff；那是 ④ Proposal 与 ⑤ Diff Engine 的职责。

### 6.8 Tester Agent 验证契约

**【实施计划 ⑧ 落地，2026-09-30】** Tester Agent 的职责是**在改动落盘后验证它真的可用**，并在失败时定位原因。与 Developer Agent 的分工：Developer 只出提案，Tester 只做验证（不改任何文件）。

**三条硬约束**：

1. **测试命令只能来自配置**（`FLUX_TEST_COMMAND` 或调用方显式传入），模型无权指定或改写命令——否则等于把任意命令执行权交给 AI。Tester 的 `terminal.execute` 权限指的是"执行项目配置的命令"，不是"执行 AI 想到的命令"。
2. 测试跑在与 Apply Engine 相同的工作区根目录下（§7.6），未配置根目录时直接报 `validation_error`，不猜目录。
3. 测试失败时由模型**分析原因**，分析结果只作为文本回报；不写文件、不输出补丁。

**输出（`TestReport`）**：`command`、`exit_code`、`passed`、`timed_out`、`duration_ms`、`output`（上限 20000 字符）、`counts`（`passed` / `failed` / `errors` / `skipped`）、`summary`。`verify()` 额外返回失败时的 `analysis`。

| 语义 | 规则 |
| --- | --- |
| 判定通过 | **只认退出码**：`timed_out == False 且 exit_code == 0`。解析不到用例统计不影响判定 |
| `summary` 示例 | `14 passed`、`2 passed, 1 failed`、`测试超时（sleep 5）`、`测试通过（exit=0，未能解析用例统计）` |
| 统计解析 | 用正则抓 `(\d+) (passed\|failed\|error\|errors\|skipped)`（大小写不敏感）；汇总行在输出末尾，同一关键词取**最后一次**出现的值；抓不到就是全 0 |
| 超长输出截断 | 保留**头 + 尾**、中间省略（不是只截尾）：汇总行在输出末尾，只截尾会让大项目的 `Tests: N passed` 永远报不出来 |
| 失败分析 | 只在测试失败或超时时调用模型（通过则不花钱）；输入为「任务背景 + 命令 + 退出码 + 统计 + 输出（上限 8000 字符）」，要求分三段回答：直接原因 / 根因 / 下一步 |
| 未配置测试命令 | 报 `validation_error`，不做静默跳过 |

**落地位置**：`flux.core.agent_runtime.tester`（`TesterAgent` / `TestReport` / `TestCounts` / `VerifyResult` / `parse_test_counts` / `build_analysis_prompt`）；命令执行复用 ⑦ 的 `flux.core.virtual_workspace.test_runner.TestRunner`；工作区根解析复用 `flux.core.virtual_workspace.apply_engine.resolve_workspace_root`。角色提示词写在 `manifests/tester.yaml` 的 `system_prompt`。

**边界（本步不做）**：自动生成测试用例、覆盖率统计、「制定测试计划」的模型化产出——第一版只做"执行已配置的命令 + 结构化回报 + 失败分析"。

---

## 7. Virtual Workspace（核心差异化）

### 7.1 目的

阻止 AI 未经审查直接改写源码。所有 AI 改动先以**虚拟提案**存在。

```
传统：AI 直接改文件
Flux：AI Proposal → Virtual Diff → Human Review → Apply Changes → Git Commit
```

### 7.2 文件状态机

```
REAL_FILE → AI_PROPOSAL → USER_REVIEW → ACCEPTED → APPLIED
```

### 7.3 Change Object 字段

file_path、original_content、proposed_content、diff、agent_source、timestamp。

**【实施计划 ④⑤ 落地，2026-09-30】** 提案的权威存储是 `virtual_changes` 表（进程重启后审核队列不丢）。落地字段：

| 字段 | 说明 |
| --- | --- |
| `id` | 提案 UUID |
| `project_id` | 可空：本地临时目录也能跑完整闭环，不强制先注册项目 |
| `task_id` | 产生该提案的任务（可空；与 `tasks.agent_id` 一样本轮不设外键） |
| `file_path` | 相对项目根的路径，每个文件一条提案 |
| `original_hash` | 生成提案时原文件的 sha256，**Apply 前必须复验**（见 7.6） |
| `original_content` | 提案生成时的原文件内容（新文件为空串） |
| `proposed_content` | 提案后的完整文件内容 |
| `diff` | 标准 unified diff 文本 |
| `added_lines` / `removed_lines` / `hunks` | Diff 概览，落库供列表直接展示 |
| `reason` / `summary` | 为什么这样改（来自 Developer Agent 的 reason / summary） |
| `agent_source` | 产出提案的 Agent 标识 |
| `status` | `pending` / `accepted` / `rejected` / `applied` / `failed` |
| `backup_path` | **⑥⑦ 新增**：Apply 成功时原文件的备份路径（新建文件无备份，留空） |
| `apply_error` | **⑥⑦ 新增**：Apply 落盘或测试失败的完整错误；成功时为空 |
| `created_at` / `updated_at` | 审计时间戳 |

**状态跃迁（服务层强制）**：`pending → accepted | rejected`；`accepted → applied | rejected | failed`；`rejected` / `applied` / `failed` 均为终态。`apply()` 对 `pending` 会先自动转 `accepted`（调用即视为人工批准，§7.5）。

**提案生成约束**：内容与原文一致的条目直接跳过（不占用审核队列）；整个 CodeChangeSet 无有效改动时报 `validation_error`。

### 7.4 Diff 能力

行级 diff、块级 diff、多文件改动、合并冲突检测。

**【实施计划 ④⑤ 落地，2026-09-30】** 第一版不自研 diff 算法，直接基于标准库 `difflib.unified_diff`，产出 git 风格的 unified diff（文件头 `--- a/<path>` / `+++ b/<path>`，新增/删除行前缀 `+`/`-`，文件头不计入统计）。落地位置 `flux.core.virtual_workspace.diff_engine`：`content_hash()`、`build_unified_diff()`、`compute_file_diff()`（返回 `FileDiff`：`unified` + `added_lines` / `removed_lines` / `hunks` / `changed`）。块级 diff 与合并冲突检测暂不做。

### 7.5 审查操作

接受、拒绝、人工修改、要求 AI 重做、部分接受。

**【实施计划 ⑥ 落地，2026-09-30】** 已实现「接受 / 应用 / 拒绝」三个审查动作，接口见 §12.5：

| 动作 | 接口 | 语义 |
| --- | --- | --- |
| 接受（accept） | `POST /workspace/accept` | 只批准：`pending → accepted`，**不碰磁盘** |
| 应用（apply） | `POST /workspace/apply` | 批准并落盘：`pending → accepted → applied`，调用即视为人工批准 |
| 拒绝（reject） | `POST /workspace/reject` | `pending/accepted → rejected`，可带 `reason`，原因进事件与响应元数据 |

「人工修改 / 要求 AI 重做 / 部分接受」暂未实现：人工修改等于直接编辑文件（Apply 会被 hash 复验拦下，见 7.6），要求 AI 重做等于重新产出提案，部分接受需要按 hunk 拆分提案——三者都依赖最小 IDE（⑫）的界面，留到那时做。

### 7.6 Apply 流程

```
校验补丁 → 备份原文件 → 写入改动 → 运行测试 → 创建 Git 操作
```

**【实施计划 ⑦ 落地，2026-09-30】** 落盘由 `flux.core.virtual_workspace.apply_engine.ApplyEngine` 独占，**它是 Flux 里唯一会写用户真实文件的组件**；API、Agent、UI 都不允许自己落盘。执行顺序固定，不可调换：

| 步骤 | 实现 | 失败后果 |
| --- | --- | --- |
| 1. 解析工作区根 | `FLUX_WORKSPACE_ROOT`（或显式传入）；未配置 / 不是目录 → `validation_error` | 未落盘，状态留在 `accepted` |
| 2. 规整路径 | `safe_relative_path()`：拒绝绝对路径与含 `..` 的路径；`./a\b.py` 归一化为 `a/b.py` | 同上 |
| 3. 复验 `original_hash` | 用库里的 `original_hash` 与磁盘现状比；**文件已被用户改过 → `conflict`，禁止落盘** | 同上，用户改动原样保留 |
| 4. 备份原文件 | `BackupService` 复制到 `<工作区根>/.flux/backups/<change_id>/<相对路径>`（新建文件无备份）；项目是 Git 仓库时把 `.flux/` 写进 `.git/info/exclude`（本地忽略，不改用户的 `.gitignore`） | — |
| 5. 写入 | 写 `proposed_content` 的**完整内容**（不是对磁盘应用 diff 文本） | 触发回滚 |
| 6. 校验 | 重新读盘算 hash，与 `proposed_content` 不一致 → `apply_failed` | 触发回滚 |
| 7. 运行测试 | 命令取自 `FLUX_TEST_COMMAND`（**Agent 无权指定命令**，避免把提权入口开在 AI 侧），在项目根下执行；非零退出码或超时（`FLUX_TEST_TIMEOUT_SECONDS`，默认 300s）→ `apply_failed` | 触发回滚 |

**回滚规则**：第 5–7 步任一失败，有备份则用备份覆盖回原文件，新建文件（无备份）则删除刚写下的文件。**失败必留痕**：提案状态置 `failed` 并把完整错误写入 `apply_error` 字段；成功则把备份路径写入 `backup_path`。

**状态与时序**：预检失败（步骤 1–3）视为「改动本身没错、是环境或提案已过期」，状态**留在 `accepted`**，异常原样上抛（`validation_error` / `conflict`），等人工重新决策；落盘与测试失败（步骤 5–7）才是 `failed`。

**新增配置项**（`.env.example` 同步维护）：`FLUX_WORKSPACE_ROOT`（落盘根目录，留空即拒绝 Apply）、`FLUX_TEST_COMMAND`（落盘后要跑的测试命令，留空跳过）、`FLUX_TEST_TIMEOUT_SECONDS`（默认 `300`）。

**暂未实现**：流程末端的「创建 Git 操作」见 §7.7 Git 集成（⑨）；Apply 只负责把文件写对，提交由人工或 ⑨ 完成。

**已知边界（CPython 字节码缓存）**：测试命令是新起的子进程，若它读取到与本次写入「同秒 mtime + 同字节长度」的旧 `.pyc`，可能加载到改动前的代码，从而让本该失败的测试通过。实测复现过：把 `return a + b` 改成 `return a * b`（字节长度完全相同）并立刻跑 pytest，会误判为通过。真实改动的字节长度几乎总会变化，窗口极窄；介意的项目可在测试命令里加 `-p no:cacheprovider` 或先清 `__pycache__`。

### 7.7 Git 集成（⑨）

**【实施计划 ⑨ 落地，2026-09-30】** Apply 之后要把改动变成一次可追溯的提交，这是整个闭环的最后一步。

第一版只做五个本地、可回退的操作：

```
GET  /api/v1/git/status            工作区状态（分支 + 逐文件 XY 状态）
POST /api/v1/git/diff              差异文本（可限定 paths，可看暂存区）
GET  /api/v1/git/branches          本地分支一览 + 当前分支
POST /api/v1/git/checkout          切换分支；create=true 时新建并切换
POST /api/v1/git/commit            创建提交
```

**四条硬约束**：

1. **不用 shell**：一律 `subprocess.run(["git", ...])` 传参数列表，文件路径再经 `safe_relative_path`（§7.6 同一个函数）校验，路径穿越与命令注入都被挡在入口。
2. **只在工作区根下执行**：复用 `resolve_workspace_root`，与 Apply Engine / Tester 同一个根；未配置根目录报 `validation_error`，不是 Git 仓库报 `not_a_git_repository`。
3. **只做本地、可回退的操作**：没有 push、没有 force、没有 reset —— 提交权与合并权始终在用户手里。分支冲突时 git 自己会拒绝，Flux 不自动 stash。
4. **fail-closed**：git 非零退出 / 超时一律抛 `git_failed`，错误详情照搬 git 的 `stderr` 与 `exit_code`，不猜结果、不静默成功（如暂存区为空时不报"提交成功"）。

**提交的把关**：闭环顺序是 `Task → Virtual Changes → Approved → Tests Passed → Git Commit`。在 Flux 里 `applied` 恰好等价于「已人工批准 + 已落盘 + Apply 后测试通过」（§7.6），所以 `POST /git/commit` 传 `change_ids` 时逐条复验状态，非 `applied` 直接报 `invalid_state_transition`。也可直接传 `paths`（用户显式指定），两个都不传则提交用户自己 `git add` 过的暂存区内容。

**提交信息**：由调用方给出（`message` 必填、非空），即"用户可改"；本步不新增 Agent 来建议提交信息（内置 Agent 第一批固定 4 个，见 §6.2）。

**落地位置**：`flux.core.git_integration.client`（`GitClient` / `GitStatus` / `GitFileStatus` / `GitBranches` / `GitDiff` / `GitCommit` / `parse_status` / `parse_branches`）、`flux.core.git_integration.service`（`GitService`，只放"什么能提交"的策略）；配置项见 §18.4，事件 `git.committed`。

**边界（本步不做）**：push / PR / 远端分支 / rebase / stash、多仓库管理、commit 信息由模型建议、按 hunk 拆分提交。

### 7.8 与 IDE 集成

面板：变更文件列表、diff 查看器、Agent 说明、审批控件。

### 7.9 安全价值

防止误操作造成的破坏性改动，并形成完整审计历史。

---

## 8. Connector 与插件生态

### 8.1 定位

Connector 是 Agent 访问外部系统的**唯一**通道。

```
Agent → Runtime → Permission Engine → Connector → External Service
```

### 8.2 接口契约

必选方法：`initialize()`、`validate_permission()`、`execute()`、`health_check()`、`close()`。
（实现规格补充版接口：`initialize()`、`execute()`、`validate()`、`close()`。）

**【裁决】** 以 **SDK Guide 的 5 方法**为准（含权限校验与健康检查，更完整）；`execute(action, parameters)` 为统一调用签名。

### 8.3 Connector Manifest

name、version、supported actions、required permissions、configuration。

### 8.4 权限声明

每个 action 必须声明所需 capability，例如 `terminal.execute`、`file.write`、`github.create_pr`。

### 8.5 执行日志

每次执行记录：Agent 身份、action、参数、结果、成本、时间戳。

### 8.6 官方 Connector

GitHub、Terminal、Browser、File System、Database、Cloud、Device。

### 8.7 插件生态

| 插件类型 | 作用 |
| --- | --- |
| Agent Plugin | 新增 AI 角色 |
| Skill Plugin | 新增可复用工程能力 |
| Connector Plugin | 新增外部系统集成 |
| UI Plugin | 新增界面扩展 |

**插件结构**：Manifest → Runtime Loader → Permission Check → Execution Environment → Plugin Result。

**Connector 插件目录**

```
plugin/
├── manifest.json
├── connector.py
├── permissions.yaml
└── tests/
```

**Skill 包内容**：描述、Prompt 模板、工作流定义、评测用例。

**Agent Plugin** 自定义：角色、目标、工具、权限、通信规则。

**插件 Manifest**：name、version、author、dependencies、permissions、entry point。

**SDK 提供**：插件生命周期 API、Event API、Tool API、Memory API、Permission API。

**插件安全规则**：必须声明权限、必要时沙箱运行、产出执行日志、遵守人工审批。

### 8.8 分发（未来）

Connector Marketplace、Skill Marketplace、Agent Marketplace、版本管理与依赖校验。

**发布流**：开发 → 测试 → 打包 → 评审 → 发布。

---

## 9. Skill 体系

Skill = 可复用的 AI 能力包，包含：Prompt 定义、规则、工具权限、示例、知识、测试。

内置示例：Backend Engineer、Security Auditor、Frontend Expert、DevOps Engineer。

Skill 可提供：Prompt、工作流、编码规范、领域知识、评测用例。

**目标**：AI 跨会话理解项目（与 Project Brain 配合）。

---

## 10. 设备网络

**Flux Node** 运行在外部设备上。支持设备：Desktop、Android、iOS、Server。

```
Flux Core ←→ Secure Channel ←→ Device Node
```

**设备能力**：截屏、命令执行、App 测试、日志收集。

**设备协议**：基于安全 WebSocket；操作 = register / heartbeat / command / result；命令 = screenshot / execute / test。

---

## 11. 数据设计

### 11.1 存储选型

| 用途 | 选型 |
| --- | --- |
| 主数据库 | PostgreSQL |
| 缓存 / 事件队列 / 运行时状态 | Redis |
| 向量记忆 | pgvector |
| 对象存储 | 文件、日志、制品 |
| 时序指标 | 时序库（见 §15） |

### 11.2 表清单

**用户与组织**

- `users`：id(UUID)、username、email、created_at、updated_at
- `provider_accounts`：user_id、provider、credential_reference
- `organizations`：id、name、created_at　**【补全】**（源 ER 文档有 Organization 实体，DB Design 缺表）
- `organization_members`：org_id、user_id、role　**【补全】**

**Agent**

- `agents`：id、name、role、model_provider、model_name、status、system_prompt、config
- `agent_skills`：agent_id、skill_id（skill_id 暂为字符串键，`skills` 表随 M5 引入）
- `agent_execution_logs`：task_id、agent_id、event、payload

**项目与记忆**

- `projects`：id、name、repository、metadata
- `project_memory`：id、project_id、type、content、embedding、metadata

**任务**

- `tasks`：id、project_id、description、status、cost、result、**priority（附录 A 裁决 A20 增列）**、**agent_id（附录 A 裁决 A21 增列，本阶段不加外键）**

**Virtual Workspace**

- `virtual_changes`：id、project_id、file_path、original_content、proposed_content、diff、agent_source、status
  status 取值：`pending` / `accepted` / `rejected` / `applied`

**Connector**

- `connectors`：id、name、type、configuration
- `connector_logs`：connector_id、agent_id、action、parameters、result、cost

**成本**

- `usage_records`：id、provider、model、input_tokens、output_tokens、total_tokens、cost、task_id、agent_id

### 11.3 ER 关系

```
User 1:N Project          Project 1:N Task
Task N:N Agent            Agent N:N Skill
Project 1:N Memory        Task 1:N UsageRecord
Organization 1:N Project            （【补全】企业版租户层级）
```

核心实体：User、Organization、Project、Agent、Task、Skill、Connector、Memory、UsageRecord。

### 11.4 索引策略

- 常规索引：project_id、task.status、agent.status、created_at
- 向量索引：project_memory 语义检索

### 11.5 迁移规则

- 使用版本化 migration 文件；
- 生产库 schema 禁止手工修改；
- 每张表必须有 `created_at` 与 `updated_at`；
- 主键一律 UUID。

---

## 12. API 规格

### 12.1 通信分层

- **REST**：管理类操作
- **WebSocket**：实时事件
- **gRPC**：内部服务间通信
- **安全通道**：设备通信

### 12.2 认证

方式：用户会话 token、API Key、设备注册 token。
所有请求必须携带身份信息与权限上下文。企业版另支持 SSO（见 §14）。

### 12.3 Agent API

```
POST /api/v1/agents                 创建 Agent
GET  /api/v1/agents                 列出 Agent
GET  /api/v1/agents/{id}            查询状态
POST /api/v1/agents/{id}/execute    执行 Agent 任务
```

### 12.4 Task API

```
POST /api/v1/tasks                  创建任务
GET  /api/v1/tasks/{id}             查询任务状态
POST /api/v1/tasks/{id}/cancel      取消任务
```

**【Solo 任务执行中心增量落地，2026-10-01】** 任务执行中心需要「任务列表 + 对话消息」两个面。助手回复由平台侧助手（`core/task_engine/assistant.py`）经 ModelRouter 真实模型调用生成，服务端不接受调用方伪造 assistant 消息。实际暴露的接口（`docs/openapi.json` 为准）：

```
GET  /api/v1/tasks                   列出任务，支持 project_id / status 过滤，按创建时间倒序（limit 1~200）
GET  /api/v1/tasks/{id}/messages     取一段对话（seq 升序）；before 为向上加载更早消息的游标，metadata.has_more 标识是否还有更早
POST /api/v1/tasks/{id}/messages     发一条用户消息并取回助手真实回复，请求体 {"content": "..."}
```

对话落 `task_messages` 表（§11.2 之外的新增表，随任务级联删除；`seq` 为任务内单调序号，向上懒加载的游标）。用户消息先落库，模型调用失败时也不会丢。首次真实处理会把任务由 `pending` 推进到 `running`。错误码：非法 status / project_id / limit `bad_request`；终态任务追加消息 `conflict`；未知任务 `not_found`。

### 12.5 Virtual Workspace API

```
GET  /api/v1/workspace/changes      列出 AI 提案改动
POST /api/v1/workspace/apply        应用已批准的改动
POST /api/v1/workspace/reject       拒绝改动
```

**【实施计划 ④⑤⑥⑦ 落地，2026-09-30】** 实际暴露的接口（`docs/openapi.json` 为准）：

```
GET  /api/v1/workspace/changes                 列出提案，支持 project_id / task_id / status 过滤
GET  /api/v1/workspace/changes/{change_id}     取单条提案（含 diff、hash、apply 审计字段）
POST /api/v1/workspace/accept                  批准但不落盘：pending → accepted
POST /api/v1/workspace/apply                   批准并落盘（经 Apply Engine，§7.6）
POST /api/v1/workspace/reject                  拒绝，可带 reason
```

错误码：未知提案 `not_found`；未配置工作区根目录 / 路径非法 `validation_error`；文件已被用户改过 `conflict`；落盘或测试失败 `apply_failed`；对终态提案重复操作 `invalid_state_transition`。请求体分别为 `{"change_ids": [...]}` 与 `{"change_ids": [...], "reason": "..."}`。

**【实施计划 ⑫ 落地，2026-09-30】** 最小 IDE 的"让 AI 改"需要一个真正的写入口：`POST /api/v1/workspace/generate`。请求体 `{"instruction": "...", "paths": ["auth/login.py"], "task_id": null, "project_id": null}`，语义固定为：

1. 按 `paths`（相对工作区根）读取文件**现状**作为上下文，一次最多 `5` 个文件、单文件最多 `60_000` 字节，越界即 `validation_error`；`paths` 为空表示不携带上下文；
2. 交给 Developer Agent（Manifest 见 §6.2）产出 `CodeChangeSet`，模型输出不合契约即 `validation_error`（fail-closed，不静默兜底）；
3. 逐文件落成 `pending` 提案（`agent_source = developer`），整个过程中**不写任何用户文件**——落盘仍然只能由 `apply` 经 Apply Engine 完成；
4. 响应 `data = {summary, files, proposals}`，`proposals` 即刚创建的提案。

这一步是 §19.8「一条完整开发任务端到端跑通」在 HTTP 层的起点：`generate → accept → apply → commit`。

**供应商回退**：Developer 的 Manifest 声明 `deepseek`；若该供应商没有密钥（本地环境常态），容器按 §5.3 的简化路由退回 `FLUX_DEFAULT_PROVIDER`，而不是让"让 AI 改"在第一步就报错。一个供应商都没配置时保持 fail-closed，明确报 `provider_not_configured`。

**【文档第二阶段 A 的前置，2026-09-30】** 前端要落地"真正的 File Explorer + Code Editor"，而 `POST /projects/{project_id}/scan` 返回的 `structure` 只是**顶层条目名**（见 §5.8），既不能展开成树、也没有读文件内容的能力。因此新增两个**只读**接口（复用 Apply Engine 的 `resolve_workspace_root()` / `safe_relative_path()`，见 §7.6）：

```
GET  /api/v1/projects/{project_id}/files          工作区文件树（只读）
GET  /api/v1/projects/{project_id}/files/content  读单个文件的文本内容（只读）
```

**文件树** `GET /{project_id}/files`：查询参数 `path`（可选，工作区内相对子目录，缺省为根，走 `safe_relative_path` 校验）、`depth`（可选，默认 `2`、**上限 `4`**，超出即 `validation_error`/422）、`workspace_root`（可选，语义同 `ScanRequest.workspace_root`；缺省 `FLUX_WORKSPACE_ROOT`）。响应 `data = {root, path, entries, truncated}`，`entries` 为扁平条目表：`{"path": "app/auth.py", "name": "auth.py", "kind": "file", "size": 412, "modified_at": "2026-09-30T10:00:00+00:00"}`（`kind` 取 `dir`/`file`，目录的 `size` 为 `null`，`modified_at` 为 UTC ISO-8601）。硬约束：

1. 项目不存在 → `not_found`；`path` 指向文件 → `validation_error`（这是文件树接口）；`path` 指向不存在的目录 → `not_found`。
2. **不跟随符号链接**：遍历用 `lstat`，软链一律不进结果，指向工作区根之外的软链既不可见也不会被展开。
3. 忽略规则**复用 Project Scanner** 的 `IGNORED_DIRS`（至少含 `.git`、`node_modules`、`__pycache__`、`.venv`、`dist`、`.flux`），不另起一套。
4. 条目总数上限 `2000`，超出即停止并置 `truncated = true`（不静默丢结果）。
5. 不在这里标注"是否有待审提案"：前端用 `GET /workspace/changes` 自行关联，后端不耦合。

**读文件** `GET /{project_id}/files/content`：查询参数 `path`（**必填**，工作区内相对路径）、`workspace_root`（可选，同上）。响应 `data = {path, content, size, truncated}`，其中 `size` 是**文件真实字节数**（不是截断后的长度）。硬约束：

1. **只读**：不写入、不创建任何文件。
2. 路径必须落在工作区根内：`../` 逃逸、绝对路径（如 `/etc/passwd`）、指向根外的软链一律 `validation_error`（422）并给出明确文案。
3. 目标不是普通文件（目录、socket、设备）→ 422；目标不存在 → `not_found`（404）。
4. 含 NUL 字节的二进制文件 → 422（文案说明是二进制文件，不返回乱码）；UTF-8 解码失败 → 422。
5. 单文件返回上限 `256 * 1024` 字节：超出只返回前 256 KiB 并置 `truncated = true`，不把整个文件读进响应。

### 12.6 Model Gateway API

```
POST /api/v1/models/chat
请求：model、messages、task_id、project_id
响应：content、token usage、cost
```

### 12.7 Connector API

```
POST /api/v1/connectors/execute
action 示例：github.create_pr、terminal.execute、browser.open
连接器结果必须附带执行日志。
```

### 12.8 Agent 消息协议

消息格式：id、from、to、type、payload、timestamp。
类型：`REQUEST` / `RESPONSE` / `EVENT` / `ERROR` / `HANDOFF`。

### 12.9 设备协议

安全 WebSocket，操作见 §10。

### 12.10 API 设计规则

所有 API 必须具备：版本化、错误码、日志、权限校验、文档。

**统一响应体**：`success`、`code`、`message`、`data`、`metadata`。
（工程规范版简写为 `success` / `data` / `error` / `metadata`。）

**【裁决】** 采用**完整版**（含 `code` + `message`），与错误码要求配套；`error` 信息并入 `code`/`message`。

**命名约定**：`GET /api/v1/projects`、`POST /api/v1/tasks`、`GET /api/v1/agents/{id}`。

### 12.11 Git API

**【实施计划 ⑨ 落地，2026-09-30】** 规则与约束见 §7.7，实际暴露的接口（`docs/openapi.json` 为准）：

```
GET  /api/v1/git/status             工作区状态：分支、是否 detached、逐文件 XY 状态
POST /api/v1/git/diff               请求 {"paths": [...], "staged": false}，返回 unified diff
GET  /api/v1/git/branches           本地分支一览 + 当前分支
POST /api/v1/git/checkout           请求 {"target": "feature/x", "create": false}
POST /api/v1/git/commit             请求 {"message": "...", "change_ids": [...], "paths": [...]}
```

错误码：未配置工作区根目录 / 路径非法 `validation_error`；工作区根不是 Git 仓库 `not_a_git_repository`（409）；git 非零退出或超时 `git_failed`（500，详情带 `args` / `exit_code` / `stderr`）；按 `change_ids` 提交但提案未 `applied` `invalid_state_transition`（409）。

### 12.12 Project / Project Brain API

**【实施计划 ⑩⑪ 落地，2026-09-30】** 项目登记 + 扫描 + 结构化记忆，规则见 §5.6、§5.8。实际暴露的接口（`docs/openapi.json` 为准）：

```
GET  /api/v1/projects                          项目列表
POST /api/v1/projects                          请求 {"name": "...", "repository": "...", "metadata": {}}
GET  /api/v1/projects/{project_id}             项目详情（时间戳为数据库读回的真实值）
GET  /api/v1/projects/{project_id}/memory      六个分区全部列出（缺给空列表 {section: [entry...]}）
POST /api/v1/projects/{project_id}/memory      请求 {"section": "decisions", "content": "...", "metadata": {}}
GET  /api/v1/projects/{project_id}/memory/context  拼出 Agent 执行前注入的项目上下文
POST /api/v1/projects/{project_id}/scan        请求 {"workspace_root": null, "record": true}
```

**语义约定**：

- `POST /memory` 按分区语义落库：`overview`/`tech_stack`/`architecture`/`coding_rules` **覆盖**（只留最新一份），`decisions`/`agent_notes` **追加**（历史保留）。响应返回落库后的条目（含 `created_at` / `updated_at`）。
- `POST /scan` 走 §5.8 的只读扫描，`record=true`（默认）时把画像写进 `overview` + `tech_stack`；`workspace_root` 留空表示用 `FLUX_WORKSPACE_ROOT`。响应为 `{"profile": {...}, "recorded": [entry...]}`，`metadata.truncated` 表示画像是否被上限裁剪。
- `GET /memory/context` 返回 `{"text": "<Markdown>"}`，是 Project Brain 被 Agent 消费的入口（§5.6）；空分区不出现在文本里。

错误码：项目不存在 / 标识串不是合法 UUID `not_found`（404）；名称为空、记忆内容为空、未配置工作区根目录、`section` 取值非法 `validation_error`（422）。

---

## 13. 前端与 UI

### 13.1 主布局

三栏 IDE 布局：**左**=项目导航；**中**=代码编辑器与虚拟 diff；**右**=Agent 工作区。

### 13.2 面板清单

Desktop 模块：workspace、agent-panel、diff-viewer、terminal、cost-dashboard、project-browser。
UI 模块：Agent Panel、Task Timeline、Virtual Diff Viewer、Project Explorer、Cost Dashboard。

### 13.3 Agent Panel 展示字段

Agent 名称、角色、当前任务、模型、token 用量、成本、状态。

### 13.4 Virtual Workspace 交互

查看 AI 提案代码、对比 diff、编辑提案、批准、拒绝、要求 AI 重做。

### 13.5 个人工作区面板（Solo Panel）

用于个人规划、调研、文档、快捷命令。

### 13.6 移动端

聚焦：通知、任务监控、审批、日志。

### 13.7 前端通信

前端**只**通过已定义的 API 通信。技术栈见 §17。

**【实施计划 ⑫ 落地，2026-09-30】** 首个前端 `apps/web-dashboard` 已按 §13.1–13.4 实现并端到端跑通：

- **技术栈**（§17.4）：React 19.3 + Vite 6.4 + TypeScript 5.9 + Tailwind 4.3（经 `@tailwindcss/vite`），无路由库 / 状态库 / 图标库 / diff 库——路由用组件状态，图标手写 SVG，unified diff 在前端解析成左右并排对照。
- **同源约定**：前端只发 `/api/v1/...` 同源请求，由 Vite 的 `server.proxy` / `preview.proxy` 转发到 `127.0.0.1:8010`。因此**后端不挂 CORS 中间件**；前端也绝不直连后端端口。
- **无 mock**：所有面板数据来自真实接口（health/ready、projects、scan、workspace/changes、accept/apply/reject、git/status、git/commit、agents）。后端未提供的字段（如 token 用量与成本，§13.3）在界面上显式标注「后端未提供」，不编造数值。
- **运行方式**：`npm run dev` / `npm run build`；默认端口 5180（被占用时 Vite 会顺延并在控制台提示）。

---

## 14. 安全

### 14.1 威胁类别

Prompt 注入、密钥泄露、工具滥用、恶意代码执行、权限提升。

### 14.2 Prompt 注入防御

指令与用户数据分离、工具权限校验、输出校验、人工审批。

### 14.3 密钥保护

- 提示词中不得出现原始密钥；
- 密钥存于 Vault；
- 仅通过 Connector 受控访问；
- **Agent 不得直接接触凭据**。

### 14.4 代码执行安全

沙箱、容器隔离、资源限制、网络限制。

### 14.5 权限安全

Agent 只获得必需 capability（最小权限）。

### 14.6 安全监控

监控可疑动作、权限失败、异常工具使用。

### 14.7 企业安全能力

SSO、审计日志、策略管理、密钥管理。

---

## 15. 可观测性与成本

### 15.1 目标

对 AI 的决策、执行、成本、失败提供完整可见性。

### 15.2 Agent Trace

追踪：用户请求、Agent 决策、工具调用、模型响应、产出制品。

### 15.3 日志

级别：DEBUG / INFO / WARNING / ERROR；重要事件永久保存。

### 15.4 成本追踪

维度：provider、model、输入 token、输出 token、单次请求成本、任务总成本。

### 15.5 指标

任务成功率、Agent 延迟、工具失败率、模型用量、资源消耗。

### 15.6 调试面板（Debug Dashboard）

展示：活跃 Agent、运行中任务、执行时间线、错误、成本分析。

### 15.7 存储

PostgreSQL（业务数据）、Redis（运行时状态）、时序库（指标）。

### 15.8 后续增强

AI 行为评测、自动回归检测、性能优化。

---

## 16. 测试与质量

### 16.1 测试分层

| 层级 | 范围 |
| --- | --- |
| 单元测试 | 核心函数、Agent 逻辑、权限校验 |
| 集成测试 | API、数据库、Connector |
| 系统测试 | 完整 AI 工作流 |
| E2E 测试 | 真实开发者场景 |

### 16.2 Agent 专项测试

提示行为校验、工具使用校验、权限边界测试、失败恢复测试、多 Agent 协作测试。

### 16.3 Virtual Workspace 专项测试

diff 生成、补丁应用、冲突检测、回滚行为。

### 16.4 Connector 测试

认证测试、action 测试、错误处理测试、安全测试。

### 16.5 Agent 评测体系

**评测目标**：可靠性、效率、安全性、工程能力。

**指标**：任务成功率、代码正确性、测试通过率、token 效率、延迟、成本效率。

**Benchmark 任务**：修 Bug、功能实现、重构、代码审查、架构设计。

**自动评测手段**：单元测试、集成测试、静态分析、人工评审。

**对比记录**：所用模型、提示词版本、所用工具、结果质量、成本。

**改进闭环**：评测结果 → 提示词改进 → Skill 更新 → 回归测试。

### 16.6 CI 流水线

```
Commit → Unit Tests → Integration Tests → Build → Release
```

### 16.7 质量指标

失败任务数、Agent 错误数、回归 Bug 数、测试覆盖率。

### 16.8 模型评测指标

任务完成率、代码正确性、token 用量、成本效率、响应延迟。

---

## 17. 工程规范

### 17.1 仓库策略

**Monorepo**。理由：服务间共享接口、本地开发方便、统一 CI/CD、前后端与节点协同。

### 17.2 仓库结构

```
flux/
├── apps/
│   ├── desktop/            # Electron 桌面应用
│   ├── vscode-extension/   # VS Code 扩展
│   ├── web-dashboard/      # Web 管理界面
│   └── mobile-controller/  # 移动控制端
├── backend/                # 后端：单一可部署单元【裁决 A12】
│   └── flux/
│       ├── api/            # HTTP 层（v1/ 放 §12 的路由）
│       ├── core/           # 领域层，不依赖 FastAPI
│       │   ├── agent_runtime/     # Agent 生命周期
│       │   ├── task_engine/       # 任务调度
│       │   ├── model_gateway/     # LLM 供应商抽象
│       │   ├── workflow_engine/   # 工作流执行
│       │   ├── permission_engine/ # 安全层
│       │   ├── event/             # 事件总线
│       │   ├── virtual_workspace/ # 虚拟文件层与 diff
│       │   ├── git_integration/   # Git 集成（⑨）
│       │   ├── project_scanner/   # 项目画像（⑩）
│       │   └── project_brain/     # 结构化项目记忆（⑪）
│       ├── models/         # SQLAlchemy 模型（§11）
│       ├── schemas/        # Pydantic 请求/响应
│       ├── connectors/     # Connector 契约与注册表
│       ├── services/       # 横切服务（memory_service 随 M5、cost_service 随 M6）
│       ├── db/             # 异步引擎与会话
│       ├── config.py · errors.py · logging.py · container.py · main.py
│       ├── migrations/     # Alembic
│       └── tests/          # pytest
├── connectors/             # 具体连接器实现（M4 起）
│   # github/ · terminal/ · browser/ · database/ · cloud/
├── skills/                 # Skill 包（M5 起）
│   # backend/ · frontend/ · security/ · devops/
├── nodes/                  # Flux Node（M8 起）
│   # android_node/ · desktop_node/
├── docs/                   # openapi.json（接口契约）· ui-designs/（M3 素材）
└── scripts/                # export_openapi.py · verify.sh
```

**目录命名规则【裁决 A12】**：会被 Python 导入的目录一律用下划线（`agent_runtime`、`nodes/android_node`）；npm / 前端包目录沿用连字符（`apps/vscode-extension`）。

**后端包结构（core）**

```
core/
├── agent_runtime/  manager.py · lifecycle.py · executor.py · context.py
├── task_engine/    scheduler.py
├── workflow_engine/ orchestrator.py
├── model_gateway/  base.py · router.py · providers/
├── event/          bus.py
├── permission_engine/ policy.py
├── git_integration/ client.py · service.py
├── project_scanner/ scanner.py
├── project_brain/  repository.py · service.py
└── virtual_workspace/ service.py
```

**后端服务目录（backend）** —— 以 M0 实际落地为准【裁决 A12】

```
backend/
├── flux/
│   ├── api/                 # HTTP 层 + v1/ 路由
│   ├── core/                # 见上方 core 结构
│   ├── models/              # SQLAlchemy 模型
│   ├── schemas/             # Pydantic 模型
│   ├── connectors/          # Connector 契约与注册表
│   ├── services/            # 横切服务
│   ├── db/                  # 引擎与会话
│   └── config.py · errors.py · logging.py · container.py · main.py
├── migrations/              # Alembic
├── tests/                   # pytest
├── Dockerfile
└── requirements.txt
```

**后端服务拆分【裁决 A12】**：源文档要求的 api-gateway、agent-runtime、task-engine、model-gateway、memory-service、connector-runtime 六个服务，在 M0–M3 阶段**先以包边界（`backend/flux/core/*`、`backend/flux/services/*`）实现为单一可部署单元**，不提前拆进程。理由是本地开发与 CI 成本，以及模块边界尚未稳定；待 M4（Connector 平台）落地、接口契约冻结后，再按包边界拆分为独立服务——届时包即服务，拆分不需要重写业务代码。

### 17.3 后端规范

栈：Python、FastAPI、Pydantic、SQLAlchemy、PostgreSQL、Redis。

规则：优先异步 API；业务逻辑与 API 层分离；**一切外部调用必须走 Connector**；重要操作必须产生事件。

### 17.4 前端规范

栈：TypeScript、React、Electron、Tailwind CSS。

前端**只**通过已定义 API 通信。

### 17.5 开发顺序（MVP Coding Plan）

1. 核心模型 → 2. Agent Runtime → 3. Task Engine → 4. Model Gateway → 5. Connector 系统 → 6. UI 集成。

分阶段：仓库搭建 → 核心运行时 → Model Gateway → Virtual Workspace → IDE 界面 → GitHub Connector。

**开发原则**：不要一次做完所有功能，首版目标是**可靠的 AI 编码工作区**。

### 17.6 Git 工作流

分支命名：`feature/*`、`fix/*`、`refactor/*`、`docs/*`。
提交格式：`feat: add agent runtime`、`fix: repair task scheduler`、`docs: update architecture`。

### 17.7 协作流程

```
创建 Issue → 设计讨论 → 建分支 → 实现 → 测试 → Pull Request → 评审 → 合并 → 发布
```

### 17.8 PR 要求

必须包含：描述、关联 Issue、测试结果、必要的文档更新。

### 17.9 编码原则

设计简单、接口清晰、组件模块化、文档完善；**先用接口、后做实现**；避免 Agent 与外部系统直接耦合。

### 17.10 模块归属

每个主要模块应有：维护者、文档、测试、Issue 跟踪。

### 17.11 新人指引

新人从文档修补、小 Bug、Connector 示例、测试改进入手。

### 17.12 文档要求

每个模块需有：README、API 文档、架构说明、用法示例。公开贡献需同步更新文档。

---

## 18. 部署

### 18.1 三种模式

| 模式 | 形态 |
| --- | --- |
| 本地开发者模式 | 所有服务本地运行：Desktop Client → Flux Core → PostgreSQL + Redis → Connectors |
| 服务器模式 | Client → API Gateway → Services → Infrastructure |
| 企业模式 | Cloud SaaS / 私有云 / 本地化部署 |

### 18.2 环境要求

现代 CPU、8GB+ 内存、Docker、Node.js、Python 运行时。

### 18.3 Docker 部署

服务：Backend API、Database、Cache、Worker。启动方式：Docker Compose。

### 18.4 配置项

数据库连接、API keys、模型供应商、权限设置。

**【实施计划 ⑦ 补充，2026-09-30】** Apply 相关配置（前缀统一为 `FLUX_`，模板见 `.env.example`）：`FLUX_WORKSPACE_ROOT` = 被改项目的根目录，留空时 `/workspace/apply` 直接报 `validation_error`；`FLUX_TEST_COMMAND` = 落盘后要跑的测试命令（如 `pytest -q`，在项目根下执行），留空表示跳过；`FLUX_TEST_TIMEOUT_SECONDS` = 测试超时秒数，默认 `300`。三项与数据库连接、模型密钥同在 `backend/flux/config.py` 的 `Settings`，由 `Container` 装配进 `ApplyEngine`。

**【实施计划 ⑨ 补充，2026-09-30】** Git 集成配置：`FLUX_GIT_TIMEOUT_SECONDS` = git 命令超时秒数，默认 `30`。Git 与 Apply / Tester 共用 `FLUX_WORKSPACE_ROOT`——同一个根目录，不存在"Apply 写 A 目录、Git 提交 B 目录"的可能。

**【实施计划 ⑩⑪ 补充，2026-09-30】** Project Scanner / Project Brain 配置：`FLUX_PROJECT_SCAN_MAX_FILES` = 单次扫描最多收集的文件数，默认 `2000`；`FLUX_PROJECT_SCAN_MAX_DEPTH` = 目录下探深度上限，默认 `6`。两者触顶时画像照常产出并标 `truncated=true`。Project Brain 无需额外配置——用 `projects` / `project_memory` 两张既有表，`project_memory.embedding` 在本阶段留空（不上向量库，见 §5.6）。

### 18.5 API Key 管理

用户配置 OpenAI / Anthropic / DeepSeek key；密钥安全存储，**永不暴露给 Agent**。

### 18.6 开发模式

后端热重载、前端热重载、Connector 调试、Agent 追踪。

### 18.7 常见故障

数据库连接、API 认证、Connector 权限、模型超时。

---

## 19. 路线图与里程碑

### 19.1 主线（以 Milestone 为准）

**【裁决】** 三套计划并存（PRD Phase 0–4、Roadmap Milestone 0–9、First 90 Days Week 1–12）且口径不一。**以 GitHub Milestone Roadmap 为主线**（粒度最细、可直接转 Issue）；Phase 与 90 天计划作为映射与细化，不再单独维护。

| 里程碑 | 目标 | 周期 | 关键交付 | Issue |
| --- | --- | --- | --- | --- |
| M0 项目基础 | 建立工程基础 | 2–4 周 | Monorepo、开发环境、CI/CD、Docker、配置、日志、基础文档 | #001–#006 |
| M1 核心运行时 MVP | 核心执行引擎 | 1–2 月 | Agent Runtime、Task Engine、Model Gateway（OpenAI/Anthropic/DeepSeek） | #010–#015 |
| M2 Virtual Workspace | 核心差异化编码体验 | 1–2 月 | 虚拟文件层、diff 引擎、审查界面、补丁应用、Git 集成 | #020–#024 |
| M3 IDE 体验 | 开发者工作台 | 1–2 月 | 桌面应用、VS Code 扩展、Agent 面板、Chat 面板、任务时间线 | #030–#034 |
| M4 Connector 平台 | 接入工程工具 | 2 月 | Connector SDK、GitHub/Terminal/Browser/File 连接器 | #040–#044 |
| M5 Skill 系统与 Project Brain | 长期智能 | 2–3 月 | Skill 运行时与包格式、项目扫描、记忆存储、向量检索 | #050–#054 |
| M6 成本与可观测 | 用量可度量 | — | 成本看板、用量数据库、Agent Trace | #060–#062 |
| M7 浏览器与环境自动化 | 操作完整开发环境 | — | Browser Runtime、Environment Manager、Docker Connector | #070–#072 |
| M8 设备网络 | 连接真实设备 | 3–6 月 | 设备协议、Android Node、Desktop Node、远程测试 | #080–#083 |
| M9 插件生态 | 社区扩展 | — | Plugin API、Plugin Loader、Marketplace 设计 | #090–#092 |

**M0–M1 验收标准**：本地一条命令启动项目；每个 PR 上 CI 通过；多 Agent 可并行运行；用户可通过配置切换模型。
**M2 验收标准**：AI 改动永不直接覆盖文件；用户可审查每一处改动后再应用。
**M3 验收标准**：开发者可在单一界面内完成编码任务。
**M4 验收标准**：任意 Agent 可通过统一 API 使用连接器。
**M5 验收标准**：AI 可跨会话理解项目。
**M6 验收标准**：可按任务、按模型查看成本。
**M7 验收标准**：Agent 可完成非编码类工程任务。
**M8 验收标准**：AI 可在外部设备上执行测试。
**M9 验收标准**：第三方开发者可扩展 Flux。

### 19.2 与 PRD 阶段映射

| PRD 阶段 | 周期 | 对应里程碑 |
| --- | --- | --- |
| Phase 0 项目基础 | 2–4 周 | M0 |
| Phase 1 AI Coding MVP | 1–3 月 | M1 + M2 + M3（基础 UI）+ M4（GitHub Connector） |
| Phase 2 AI Engineering Workspace | 3–6 月 | M5 + M6 + M3（VS Code 扩展） |
| Phase 3 完整工程环境 | 6–12 月 | M7 + M8 |
| Phase 4 生态建设 | — | M9 + 企业版能力 |

**【裁决】** PRD 的月数与 Milestone 周期叠加后会超过 12 个月，因为 M6–M9 与 Phase 3/4 存在**并行空间**。执行时以关键路径（M0→M1→M2→M3）为准，M5/M6 可与 M2/M3 并行启动。

### 19.3 首 90 天细化（对应 M0–M2）

| 月份 | 周 | 内容 |
| --- | --- | --- |
| 第 1 月：地基 | W1 仓库、架构、CI；W2 后端骨架、数据库、认证；W3 Agent Runtime 原型；W4 Task Engine 与事件系统 |
| 第 2 月：核心产品 | W5 Model Gateway；W6 Virtual Workspace 原型；W7 Diff 查看器与审批流；W8 基础 IDE 界面 |
| 第 3 月：Demo 版 | W9 GitHub Connector；W10 成本追踪；W11 测试与打磨；W12 公开 Demo 发布 |

**90 天 Demo 目标**：用户能 ① 打开项目 → ② 让 AI 团队实现一个功能 → ③ 审查虚拟代码改动 → ④ 批准修改 → ⑤ 运行测试 → ⑥ 创建 Git 提交。

### 19.4 发布策略

| 版本 | 内容 |
| --- | --- |
| v0.1 | Agent Runtime、Model Gateway、Virtual Workspace、GitHub Connector |
| v0.5（=v0.2 累计） | IDE 集成、Skill 系统、Project Brain、成本看板 |
| v1.0 | 设备系统、插件生态、完整 AI 工程工作流 |

**【裁决】** Launch Plan 中的 v0.2/v0.5/v1.0 与 Roadmap 的 Release Strategy 不一致，统一为上表三档，`v0.2` 并入 `v0.5`。

### 19.5 GitHub 项目管理

看板列：Backlog → Ready → In Progress → Review → Testing → Done。
标签：core、agent、ui、connector、security、documentation、enhancement、bug。

### 19.6 MVP 范围（明确不做）

**做**：Flux Core、Agent Runtime、Virtual Workspace、GitHub Connector、VS Code Extension、Model Router。
**不做**（清单以裁决 A23 为准）：手机 App、iOS / Android 测试节点、自研浏览器、Marketplace / Plugin Marketplace、企业 Team Workspace、企业会议模式、企业本地文件同步、Cloud Agent、NAS 集群、高级成本中心、AI Code Completion、复杂 RAG、大规模分布式 Worker。

### 19.7 当前执行顺序与范围收窄（裁决 A23）

**【裁决】** 里程碑编号不再决定开发顺序。当前唯一目标是**让 Flux 第一次完整完成一个真实开发任务**，因此严格按下表顺序推进，任何"以后很有用"的能力都不得插队：

```
① Task 持久化（tasks 表为状态事实来源）   ← 已完成，提交 21abdfe
② Agent Manifest（不含 API Key 的 Agent 统一配置）   ← 已完成，提交 f7aa3ce
③ Developer Agent   ← 已完成，提交 22e9ab7
④ Virtual File / Proposal   ← 已完成，提交 f2b2786
⑤ Diff Engine（unified diff）   ← 已完成，提交 f2b2786
⑥ Review / Approve / Reject   ← 已完成，提交 ee6ebaf（accept / apply / reject 三动作 + 状态机，见 §7.5、§12.5）
⑦ Apply Engine（hash 校验 + 备份 + 失败恢复）   ← 已完成，提交 ee6ebaf（唯一落盘入口，备份 + 回滚 + 测试执行，见 §7.6）
⑧ Tester Agent   ← 已完成，提交 8c94e81（真实执行项目配置的测试命令 + 结构化回报 + 失败原因分析，见 §6.8）
⑨ Git Integration（status/diff/branch/checkout/commit）   ← 已完成，提交 93e304d（本地可回退的 5 个操作 + 只有 applied 的改动才能提交，见 §7.7）
⑩ Project Scanner（项目画像，不引入向量库）   ← 已完成，提交 b4c6aa6（只读有界扫描 + 项目画像，见 §5.8）
⑪ Project Brain v1（结构化，非 RAG）   ← 已完成，提交 b4c6aa6（六分区结构化记忆 + context 拼装，见 §5.6、§12.12）
⑫ 最小 IDE（Virtual Workspace 为界面中心）   ← 已完成，提交 7128812 + 96cc2ed（后端写入口 `POST /workspace/generate`；前端 apps/web-dashboard 三栏界面，端到端实测：需求 → 提案 → 审阅 diff → 批准 → 落盘 → 跑测试 → 提交，见 §13 与 §19.8）
⑬ Beginner Mode
```

**【DSH 接入并入，2026-09-30】** 上表不再追加序号：DSH 接入作为**独立并行轨道**推进（Phase 1 DSH Runtime → Phase 2 Flux Bridge → Phase 3+ 逐步接入 Flux 能力），它替换的是「自研 Agent Loop / Plugin Runtime / Session Runtime」这条路，不改变 ①–⑬ 已交付的闭环。Phase 1 的验收是 Flux → DSH → DeepSeek → Agent Response（接入口径与实施步骤见 [DSH_FLUX_INTEGRATION_PLAN.md](DSH_FLUX_INTEGRATION_PLAN.md) §10、§18）。

**M2 范围据此加厚**：`Virtual Workspace + 最小闭环`（Tech Lead → Developer → Proposal → 人工审阅 → Apply → Tester → Git Commit）。M3 IDE 及以后全部顺延。内置 Agent 第一批只做 4 个：Tech Lead、Developer、Reviewer、Tester；Architect、DevOps 待闭环稳定后再加。

**当前阶段明确不做**（保留在 Roadmap，不进入主线）：Mobile App、iOS/Android 测试节点、企业 Team Workspace、企业会议模式、企业本地文件同步、Cloud Agent、NAS 集群、Marketplace、Plugin Marketplace、高级成本中心、自研浏览器、AI Code Completion、复杂 RAG、大规模分布式 Worker。

**M2 的产品原则**：Agent 永不直接覆盖用户真实文件；Proposal 的 `original_hash` 在 Apply 时必须复验，不一致则禁止 Apply 并提示文件已被改动；Diff Engine 不自研算法，直接产出标准 unified diff；Apply 流程固定为 `校验 Proposal → 校验 hash → 备份 → 打补丁 → 校验文件 → 跑配置的测试`，失败要尽可能回滚原文件并留完整错误。

**【裁决】验收基准升级**：禁止以"代码写完了"作为完成标准，统一走 `实现 → 单元测试 → 集成测试 → 真实项目测试 → 用户操作测试 → 验收`。Virtual Workspace 的验收必须真实走完这条序列：

```
Agent 修改代码 → 真实文件没有变化 → 用户看到 Diff → 用户拒绝 → 真实文件仍然没有变化
→ 重新生成 Proposal → 用户 Accept → Apply → 测试通过 → 真实文件改变
```

### 19.8 个人版 MVP 完成定义（Definition of Done）

以下全部满足，才算从"工程骨架"进入"可用个人版 MVP"：Task 完整持久化、Agent Manifest、Developer / Tech Lead / Tester Agent、Virtual Proposal、Unified Diff、Accept / Reject、Apply、Apply 前 hash 校验、Apply 失败恢复、测试执行、Git Commit、Project Scanner、最小 IDE、一条完整开发任务端到端跑通、自动化测试覆盖核心流程、至少用一个真实项目验证。

第一个值得公开的 Demo 是「给我这个项目增加一个功能」，并完整展示：谁在工作、用什么模型、为什么改、改了什么、改动前后差异、测试是否通过、最终由谁批准。

**此后顺序**：Project Brain → Skill System → Cost / Observability → Beginner Mode → Cloud / Pro → Enterprise。

---

## 20. 开源与发布

### 20.1 定位

定位为 **AI 工程基础设施项目**，而不是又一个 AI 聊天工具。

### 20.2 仓库与 README

仓库应含：清晰 README、架构图、Quick Start、Demo 视频、文档、贡献指南。
README 结构：Problem → Solution → Features → Architecture → Installation → Demo → Roadmap → Contributing。

### 20.3 发布前准备

README、文档、Demo 视频、截图、路线图。

### 20.4 Demo 剧本

场景：用户要求「构建用户认证功能」→ Flux 组建工程团队。

```
① 规划：Tech Lead 分析需求 → Architect 出设计 → Developer 接任务
② 编码：Developer 产出改动，进入 Virtual Workspace（不直接改文件）
③ 审查：用户看 diff、说明、测试影响 → 批准
④ 测试：Tester 跑测试，失败回流给 Developer 修复
⑤ Git：Flux 创建 commit、branch、PR，人保留最终控制权
```

演示重点：多 Agent 协作、透明代码改动、成本追踪、工具集成。

### 20.5 社区建设

GitHub Discussions、技术文章、开发者社区、开源贡献；提供 Issue 模板、PR 模板、开发指南、代码规范；设 beginner 任务。

### 20.6 面试演示（10 分钟）

① 开场：问题——开发者使用多个割裂的 AI 工具，AI 动作不透明；② 方案：统一的 AI 工程工作区；③ 技术亮点：Agent 编排、Virtual Workspace、Connector 架构、成本追踪、项目记忆；④ 架构讲解：从用户请求到 Agent、工具、审查、最终代码的数据流；⑤ 工程挑战：Agent 可靠性、权限控制、模型路由、人机协作；⑥ 未来方向：插件生态、团队协作、企业部署。

---

## 附录 A　冲突裁决表

| # | 冲突点 | 源文档分歧 | 裁决 | 理由 |
| --- | --- | --- | --- | --- |
| A1 | 版本号 | Full Technical Specification 标 v1.0，其余 33 份为 v0.1 | 全文统一 **v0.1** | 33:1，v1.0 视为笔误 |
| A2 | 路线图 | PRD Phase 0–4；Roadmap M0–M9；90 天 Week 1–12 | **以 M0–M9 为主线**，其余作映射 | 粒度最细、可直接转 Issue |
| A3 | 发布档位 | Launch Plan v0.2/v0.5/v1.0；Roadmap v0.1/v0.5/v1.0 | 统一 **v0.1 / v0.5 / v1.0** | 三档更清晰，v0.2 并入 v0.5 |
| A4 | 模型供应商 | PRD 写 Claude/Codex/DeepSeek；架构写 OpenAI/Anthropic/DeepSeek/Local | **OpenAI / Anthropic / DeepSeek / 本地模型** | 技术层优先于产品举例 |
| A5 | Connector 接口 | SDK Guide 5 方法（含 validate_permission、health_check）；Implementation Spec 4 方法 | 采 **5 方法** | 含权限校验与健康检查，更完整 |
| A6 | API 响应体 | Implementation Spec：success/code/message/data/metadata；Repo Spec：success/data/error/metadata | 采 **完整版** | 与「所有 API 必须有错误码」配套 |
| A7 | 组织实体 | ER 文档有 Organization，DB Design 无对应表 | **补 organizations / organization_members** | 企业版多租户要求，缺表无法闭环 |
| A8 | Agent 缩写 | 全文混用「AIOS」与「AI Engineering OS」 | 首次写全称，其后统一 **AIOS** | 可读性一致。**本条已被 A22 取代**：产品改名 Flux 后不需要缩写规则（Flux 本身即短名） |
| A9 | Developer Agent 模型 | Repo Spec 示例写「Model: Codex」 | 改为「模型供应商可选」 | 与 A4 一致，避免绑定单一品牌 |
| A10 | Solo Panel 定位 | 仅 UI 文档出现，与 Agent Panel 有重叠 | 保留为「个人工作区面板」，明确其用于规划/调研/文档/快捷命令 | 定位互补，非重复 |
| A11 | 时序指标存储 | Observability 建议时序库，DB Design 未列 | 时序库列为独立存储，不进主库表清单 | 避免把指标混入业务表 |
| A12 | 目录命名与后端部署粒度 | Repo Spec 顶层 `core/` + `services/` 并拆 api-gateway 等 6 个微服务；源文档目录名混用下划线与连字符 | 后端收敛为**单一可部署单元** `backend/flux/`（`core/`、`services/` 作为包内子目录）；**会被 Python 导入的目录一律用下划线**（`agent_runtime`、`nodes/android_node`），**npm / 前端包目录沿用连字符**（`apps/vscode-extension`） | M0 就拆微服务会让本地开发与 CI 成本陡增；先用包边界立住模块边界，M4 之后再按需拆分 |
| A13 | 两张 Agent 日志表 | DB Design 同时给出 `agent_logs` 与 `agent_execution_logs` | 只保留 **`agent_execution_logs`**，删除 `agent_logs` | 职责重叠，执行日志已覆盖状态与耗时 |
| A14 | Agent 终态 | Runtime 文档把 COMPLETED / FAILED 画成终态 | COMPLETED / FAILED **非终态**，可经 READY 重新复用 | Agent 是可复用资源，不是一次性进程 |
| A15 | 生命周期缺 STOPPED | 状态机未定义停止态，但调度器要求支持取消 | **补 STOPPED**：READY / RUNNING / WAITING_TOOL / REVIEWING 均可 → STOPPED，STOPPED → INITIALIZING / READY | 取消语义必须有显式状态，否则「取消」只能靠删除对象实现 |
| A16 | M0 的模型供应商范围 | §5.3 要求统一接入四类供应商 | M0 只注册 **local**（离线 echo 供应商），OpenAI / Anthropic / DeepSeek 归 M1 | M0 目标是打通端到端链路；真实供应商依赖密钥与计费口径，放 M1 的 #013–#015 |
| A17 | 供应商接入方式 | 源文档未规定用官方 SDK 还是手写 HTTP | M1 三家适配器**手写 `httpx`**，不引入 `openai` / `anthropic` 官方 SDK | 依赖面更小；三家都是 HTTP JSON；可用 `httpx.MockTransport` 在无密钥、无网络的 CI 上完整断言请求与解析，避免"提交一个从没被验证过的客户端"（延续 A16 的同一理由） |
| A18 | 成本计价的落地时间 | §15.4 要求按 provider/model/task 记录 token 与费用，§5.3 要求 `calculate_cost()` | M1 的 Provider **不内置价格表**（`pricing=None` → `cost` 为 `null`），价格表属 M6 | §15.4 明确「绝不用猜测值填充」；单价必须取自官方价格页并要求带快照日期，属 M6「成本看板」的输入 |
| A19 | M1 各家的默认模型 id | 三家官方文档均未给出"应当默认用哪个"，且 OpenAI 官方两处口径自相矛盾 | 默认值：OpenAI **`gpt-5.5`**、Anthropic **`claude-sonnet-5-5`**、DeepSeek **`deepseek-flash`**；三者均可用 `FLUX_<供应商>_MODEL` 覆盖 | `claude-sonnet-5-5` 与 `deepseek-flash` 有官方文档直接支撑；OpenAI 侧 `platform.openai.com/docs/models`（2026-09-30 抓取）列 `gpt-5.5` / `gpt-5.4`，而 `openai.com` 定价页列 GPT-6 Astra/Sol/Luna，**两处冲突未解**，故取开发者文档页并显式标注为待官方核实 |
| A20 | `tasks` 表缺调度优先级列 | §11.2 定义 `tasks` 只有 `id、project_id、description、status、cost、result`；但 §5.1 要求调度器管优先级，§12.4 的 Task API 已暴露 `priority` | **增列 `tasks.priority`（INTEGER，NOT NULL，默认 100，数字越小越优先）** | 实施期发现：M0 的任务对象存内存时 `priority` 天然可用，落库后若不建列，会出现「创建时返回 `priority=5`、重启后 GET 回来变成默认值」的静默不一致。优先级是调度器的输入而非临时元数据，必须随任务持久化 |
| A21 | `tasks.agent_id` 暂不设外键 | §11.2 未定义该列；§11.4 也未规定其外键关系。而 §11.2 的 `agents` 表在 M1 尚无任何写入路径 | **增列 `tasks.agent_id`（UUID，可空，本阶段不加外键）**；待 Agent Runtime 持久化落地后，由后续迁移补 `agent_id → agents.id` 外键 | 与 A20 同因（API 已暴露 `agent_id`，不落库会不一致）。**不加外键是有意为之**：`agents` 表当前写不进去，任何带 `agent_id` 的建任务在 PostgreSQL 上都会立即违反外键，等于把功能做死。M1 阶段 Agent 存在性由进程内 Agent 注册表在 API 边界校验（fail-closed） |
| A22 | 产品与工程标识改名 | 产品名 `AI Engineering OS`、Python 包 `aios`、环境变量前缀 `AIOS_`、规格文件名 `AI_Engineering_OS_Master_Spec_v0.1.md`、仓库名 `ai-engineering-os` | **产品名统一为 `Flux`**；Python 包 `backend/aios/` → **`backend/flux/`**；环境变量前缀 `AIOS_` → **`FLUX_`**；仓库 `github.com/qiuli55/flux`，默认分支 `main`；规格文档移入仓库并更名 **`docs/Flux_Master_Spec_v0.1.md`** | 用户 2026-09-30 决定「项目名字叫 flux」，并要求此后每做一次修改即提交推送到 flux 仓库。A8 的缩写规则随之作废（Flux 本身即短名）；§1.1、§7 术语表、§11.1 目录结构、A12、A19 中的旧名均已同步替换。**本机仓库目录已于同日一并改名：`/root/workspace/ai-engineering-os/` → `/root/workspace/flux/`**；注意 virtualenv 不可搬迁——改名后必须修复 `.venv/bin/*` 脚本内嵌的旧绝对路径并清掉内嵌旧路径的 `__pycache__`（`.venv/bin/python` 是指向系统解释器的符号链接，不受影响） |
| A23 | 开发顺序与当前范围 | 里程碑编号不再决定开发顺序，改为「闭环优先」，严格按 §19.7 的 ①–⑬ 推进，任何"以后很有用"的能力都不得插队；M2 范围加厚为 `Virtual Workspace + 最小闭环（Tech Lead → Developer → Proposal → 人工审阅 → Apply → Tester → Git Commit）`，M3 IDE 及以后顺延；内置 Agent 第一批只做 Tech Lead / Developer / Reviewer / Tester；「明确不做」清单以 §19.6 为准；**验收基准升级**：统一走 `实现 → 单元测试 → 集成测试 → 真实项目测试 → 用户操作测试 → 验收`，禁止以"代码写完了"作为完成标准 | 当前唯一目标是让 Flux 第一次完整完成一个真实开发任务；进度：① Task 持久化已完成（提交 21abdfe）|

## 附录 A-2　外部事实快照（接入期核实）

规则：版本号 / 价格 / API 用法 / 兼容性四类信息必须联网核实并标快照日期；单源不交叉一律标「待官方核实」。

| # | 事实 | 值 | 来源 | 快照日期 |
| --- | --- | --- | --- | --- |
| F1 | OpenAI 端点 | `POST https://api.openai.com/v1/chat/completions`，头 `Authorization: Bearer`，生成上限参数名为 `max_completion_tokens`（`max_tokens` 已废弃） | `platform.openai.com/docs/models`、Chat Completions OpenAPI 描述 | 2026-09-30 |
| F2 | OpenAI 默认模型 id | `gpt-5.5`（**待官方核实**：与 `openai.com` 定价页的 GPT-6 家族口径冲突） | `platform.openai.com/docs/models` vs `openai.com` 定价页 | 2026-09-30 |
| F3 | Anthropic 端点 | `POST https://api.anthropic.com/v1/messages`，头 `x-api-key` + `anthropic-version: 2023-06-01`；`max_tokens` 必填；`system` 为顶层字段；`temperature` 值域 `[0, 1]`；响应为内容块数组 | `console.anthropic.com/docs/en/api`、`platform.claude.com/docs/en/models/opus-5/overview` | 2026-09-30 |
| F4 | Anthropic 默认模型 id | `claude-sonnet-5-5`（$2 / $10 每百万 token） | `platform.claude.com/docs/en/models/opus-5/overview` 的官方模型对比表 | 2026-09-30 |
| F5 | DeepSeek 端点 | `POST https://api.deepseek.com/chat/completions`，头 `Authorization: Bearer`，生成上限参数名为 `max_tokens`；另有 Anthropic 兼容端点 `https://api.deepseek.com/anthropic` | `api-docs.deepseek.com/`、`api-docs.deepseek.com/api/create-chat-completion` | 2026-09-30 |
| F6 | DeepSeek 默认模型 id | `deepseek-flash`（2026-09-10 起等同 DeepSeek-V4.1-Flash；旧名 `deepseek-v4-flash` 已下线） | `api-docs.deepseek.com/updates/` Change Log | 2026-09-30 |

## 附录 B　源文档 → 章节对照

| 源文档 | 合并去向 |
| --- | --- |
| PRD | §1、§2、§19.2、§19.6 |
| Full Technical Specification | §0.2、§1、§4、§17 |
| Competitive Analysis | §1.5 |
| Open Source Strategy | §20.1–20.3、§20.5 |
| Launch Plan | §19.4、§20.3–20.4 |
| GitHub README Draft | §20.2 |
| Product Demo Script | §20.4 |
| Interview Presentation | §20.6 |
| Architecture Design | §4、§5、§10、§11.1、§12.1 |
| Enterprise Architecture | §4.3、§14.7、§18.1 |
| Agent Runtime Detailed Design | §5.1、§5.4、§6.4 |
| Core Source Code Architecture | §17.2、§5.4 |
| Agent System Prompt Design | §6.1–6.4 |
| Agent Workflow Examples | §6.6、§7.1 |
| API Specification | §12 |
| Database Design | §11 |
| Database ER Design | §11.2–11.3（关系与实体） |
| Code Implementation Specification | §12.10、§17.3、§17.5 |
| Connector SDK Guide | §8.1–8.6 |
| Plugin Ecosystem Design | §8.7 |
| Plugin SDK Example | §8.7–8.8 |
| Virtual Workspace Design | §7 |
| UI Prototype Specification | §13 |
| Testing Strategy | §16.1–16.4、§16.6–16.8 |
| Agent Evaluation System | §16.5 |
| Observability Design | §15 |
| Security Threat Model | §14.1–14.6 |
| Technical Decision Record | §4.2、§17.2、§17.5（ADR 见下） |
| GitHub Milestone Roadmap | §19.1、§19.4–19.5 |
| First 90 Days Plan | §19.3 |
| MVP Coding Plan | §17.5、§19.6 |
| Repository and Coding Specification | §11.5、§17.1–17.12 |
| Developer Handbook | §17.7–17.11 |
| Local Deployment Guide | §18 |

### 架构决策记录（ADR，来自 Technical Decision Record）

| ADR | 决策 | 理由 |
| --- | --- | --- |
| ADR-001 | 桌面端先用 Electron | 生态成熟、与 VS Code 兼容、跨平台 |
| ADR-002 | 后端用 Python | AI 生态、开发快、库丰富 |
| ADR-003 | 内部使用事件驱动 | 松耦合、便于 Agent 协同、易扩展 |
| ADR-004 | AI 改动先作为提案 | 透明、安全、利于人机协作 |
| ADR-005 | Agent 仅通过 Connector 访问外部系统 | 安全、可扩展、利于插件生态 |

---

**待定事项（需人工决策）**

1. 各技术栈的具体版本号已在 `backend/requirements.txt` 中锁定为 **2026-09-30 快照**（本机 pip 解析并实测通过：pytest 全绿 + Alembic 双向迁移 + uvicorn 启动探活），但**这只是快照而非「最新版」声明**；升级前须重新核实兼容性并重跑 `make verify`。
2. `【待定】` M6–M9 的周期与人力配置需结合实际排期确定。
3. 企业版（Organization/Team 表、SSO）是否进入 v1.0 之前的范围，源文档口径不一致（PRD 明确「不做企业权限」，Enterprise Architecture 又给出完整设计）——需拍板。**当前状态**：M0 已按裁决 A7 建出 `organizations` / `organization_members` 两张表，Team 层级推迟。
4. **Python 版本已进入倒计时**：Python 3.10 处于「仅安全修复」阶段，官方计划 **2026 年 10 月 EOL**（当前 3.10 分支最新补丁为 3.10.21，2026-08-12 发布）。本地开发环境、`requirements.txt` 快照与 `backend/Dockerfile` 目前均基于 3.10。需决定升级目标：Python 3.12（EOL 2028-10）或 3.13（EOL 2029-10），升级时须逐个核实 fastapi / pydantic / SQLAlchemy / asyncpg 在目标版本上的兼容性，并重跑全量测试与迁移往返。

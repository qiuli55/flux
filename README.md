# Flux

**集合 agent 的 AI 软件工程操作系统**：统一上下文、统一工具（agent 面：MCP 优先 + CLI 兜底）、统一 Skill，各只有一份真源，所有 agent 共享。

Flux **本身不做 agent**——没有 loop、不组装 prompt、不替 agent 调模型、不存 agent 对话。它内置一个以 **DeepSeek 为基底**的 agent（DSH + Cordis），与 Codex / Claude Code / OpenCode 等外部 agent 经**同一个 agent 面（MCP 优先 + CLI 兜底）**对等消费能力。核心差异化是 **Virtual Workspace**——AI 的每一次改动都以「提案 → 虚拟 diff → 人工审查 → 应用」的流程落地，**永不直接覆盖你的文件**。

代码中的一等运行时是 `dsh` / `codex` / `opencode`（见 `backend/flux/core/agent_runtime/runtimes/`）；Claude Code 按外部 MCP 对等端 / 令牌身份接入，不走独立运行时适配目录。

架构方向以 [FLUX_TARGET_ARCHITECTURE.md](docs/FLUX_TARGET_ARCHITECTURE.md) 为最高优先级；规格的唯一权威来源是 [Flux_Master_Spec_v0.1.md](docs/Flux_Master_Spec_v0.1.md)。代码与规格不一致时以规格为准，实施期发现的新问题回写到规格附录 A。

---

## 快速开始

```bash
# 1. 建虚拟环境并装依赖（Python >=3.10）
make setup

# 2. 复制环境变量模板（密钥留空即可，未配置的供应商保持"未配置"状态）
cp .env.example .env

# 3. 初始化数据库
#    注意：.env.example 默认写的是 PostgreSQL；本地零依赖请改用模板中被注释的
#    FLUX_DATABASE_URL=sqlite+aiosqlite:///./flux.db（config.py 的默认值也是 SQLite），
#    或保持 PostgreSQL 并用 make up 启动 postgres / redis / backend。
make migrate

# 4. 一条命令自检：ruff + format + OpenAPI 契约 + 迁移往返 + pytest
make verify

# 5. 启动后端，浏览器打开 http://127.0.0.1:8000/docs
make run
```

冒烟检查（`providers` 与 `agents` 数随本机配置变化）：

```bash
curl -s http://127.0.0.1:8000/api/v1/health/ready
# {"success":true,"code":"ok","message":"","data":{"database":true,"providers":["local","deepseek"],"agents":1},"metadata":{}}
```

## 一键 demo

```bash
make demo     # 展示页：http://127.0.0.1:5188
```

展示页自带 mock 数据，**不需要后端**即可完整点击、切换与交互；`make demo` 会先 `npm ci` 再起 Vite。

---

## 三条固定口径

1. **任务下发与能力供给分离**：Flux 下发任务；agent 自己带 loop；工具通过连 Flux MCP「借」来。
2. **能力出口唯一**：只开一个 MCP Server（Streamable HTTP，单入口 `POST /mcp`），服务内置 agent、桌面端与外部 agent。
3. **共享上下文只有一份，在 Flux 侧**：agent 的对话历史私有、可丢，切换 agent 不搬迁对话。

配套机制：

- **工具面**：完整清单以 `backend/flux/core/mcp/tools/__init__.py` 的 `TOOLS` 表为唯一权威真源（含 `context.boot`、`workspace.*`、`proposal.preview`、`design.*`、`memory.remember` 等）；[PROJECT_STATUS.md](docs/PROJECT_STATUS.md#4-能力面flux-mcp-server) 仅是进度/概览指针，可能滞后。
- **权限模式**：`plan` / `normal`（默认，fail-closed）/ `full` / `unattended`，两层闸门（模式白名单 + 令牌能力位）；落盘、commit、push、读密钥没有对应工具。详见 [PROJECT_STATUS.md](docs/PROJECT_STATUS.md#4-能力面flux-mcp-server)。
- **变更落地**：提案状态机 + 人工批准后落盘 + 备份/回滚 + 测试门禁；Git 提交只由用户在 UI 侧触发。
- **上下文压缩**：step 事件日志 → S1–S5 压缩流水线 → 事实脊柱与验证账本，见 [CONTEXT_DESIGN.md](docs/CONTEXT_DESIGN.md)。

---

## 客户端

| 应用 | 技术栈 | 状态 |
| --- | --- | --- |
| `apps/web-dashboard` | React 19 + Vite | 工作台主界面：Solo / IDE 双模式、diff 双视图、内嵌终端 |
| `apps/desktop-avalonia` | Avalonia + .NET 8 | 原生桌面端：登录、概览、任务、Skills、诊断、设置、Solo、项目列表、Agent 面板、终端、浏览器（代理）、成本中心、设计；内置 IDE 工作台仍待实现（当前入口拉起需另行安装的独立 Flux IDE 窗口） |
| `apps/mobile-controller` | Android（Kotlin） | 手机远程控制端 |
| `apps/showcase-web` | React | 展示页（`make demo`） |
| `apps/vscode-extension` | TypeScript | VS Code / VSCodium 扩展 MVP；可组装为预装扩展的 Flux IDE |

---

## 文档地图

| 文档 | 内容 |
| --- | --- |
| [docs/Flux_Master_Spec_v0.1.md](docs/Flux_Master_Spec_v0.1.md) | 唯一权威规格（34 份源文档合并，含 22 条冲突裁决） |
| [docs/FLUX_TARGET_ARCHITECTURE.md](docs/FLUX_TARGET_ARCHITECTURE.md) | 目标架构与退役清单（最高优先级） |
| [docs/PROJECT_STATUS.md](docs/PROJECT_STATUS.md) | 里程碑进度、契约规模、能力面、仓库结构、设计稿与已知限制 |
| [docs/FLUX_PROJECT_OVERVIEW.md](docs/FLUX_PROJECT_OVERVIEW.md) | 项目理解、整体架构与方向 |
| [docs/CONTEXT_DESIGN.md](docs/CONTEXT_DESIGN.md) | 上下文机制设计（step / 压缩 / 脱敏 / 验证） |
| [docs/openapi.json](docs/openapi.json) | 接口契约（142 条路径，随代码提交） |

---

## 当前状态

**M0 项目基础、M1 核心运行时、M2 Virtual Workspace 已完成；M3 IDE 体验进行中；M4 起未开始。**
后端 pytest 全绿（`make verify`），契约规模 **142 条 API 路径 / 46 张表 / 35 个迁移**；
CI 覆盖 Linux、Windows 与 PostgreSQL 三类环境；Android 构建走远程构建机并保留分发签名。
`docs/PROJECT_STATUS.md` 的快照数字可能滞后，路径 / 表 / 迁移以代码为准。

仓库仍有空占位：`connectors/`、`skills/*`、`nodes/*` 目前只有 `.gitkeep`。

计费：额度 / 账本 API（`backend/flux/api/v1/billing.py`）与桌面端成本中心页面已落地雏形，
完整成本可观测仍在演进；Supervisor 相关能力也在演进中。细节见 [PROJECT_STATUS.md](docs/PROJECT_STATUS.md)。

工程约定：`api/` 只做协议转换，业务逻辑在 `core/`；外部调用必须走 Connector；密钥只由 Provider 内部读取，
绝不下发 Agent、绝不写入仓库。提交信息用 `feat:` / `fix:` / `docs:` / `chore:` 前缀。

已知限制（Python 3.10 接近 EOL、容器链路未在所有机器本地验证、OpenAI/Anthropic 仅离线验证等）与全部进度细节见
[PROJECT_STATUS.md](docs/PROJECT_STATUS.md)。

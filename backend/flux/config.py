"""配置系统（主规格 §18.4 配置项 / §18.5 API Key 管理）。"""

from __future__ import annotations

from functools import lru_cache
from pathlib import Path

from pydantic_settings import BaseSettings, SettingsConfigDict

# backend/flux/config.py -> backend/flux -> backend -> 仓库根
REPO_ROOT = Path(__file__).resolve().parents[2]


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_prefix="FLUX_",
        # 固定指向仓库根的 .env：pydantic-settings 默认按进程 cwd 解析相对路径，
        # 而 uvicorn 从仓库根启动、alembic 从 backend/ 启动，若用相对路径两者会
        # 读到不同的配置文件（甚至读不到），导致 make run 与 make migrate 连不同的库。
        env_file=REPO_ROOT / ".env",
        env_file_encoding="utf-8",
        extra="ignore",
    )

    # --- 应用 ---
    app_name: str = "Flux"
    env: str = "local"
    log_level: str = "INFO"
    api_v1_prefix: str = "/api/v1"
    # REST 面单用户令牌（公网暴露前置，见 flux.api.auth）。留空 = 关闭鉴权，
    # 本地开发与桌面端（同机直连）零配置可用；公网部署必须显式设置。
    auth_token: str | None = None
    # Electron 桌面端用于“打开文件夹”的一次运行期能力令牌；普通 Web 客户端不能使用。
    desktop_control_token: str | None = None
    # 脱敏映射表（context_redactions）的加密密钥（FLUX_CONTEXT_MECHANISM §10.1）。
    # 留空 = 映射不落库（fail-closed）：正文照常打码，只是不可回填。
    redaction_key: str | None = None

    # --- 设计工作室（Luma Studio，独立进程）---
    # 设计稿的持久化与 AI 改组件都由本机 Luma Studio 负责；Flux 只做鉴权代理，
    # 手机端因此复用 Flux 既有登录态，不需要另建账号体系。
    # 公网路径 /design-studio/api/designs 已在 nginx 屏蔽，只有回环直连可读写。
    design_studio_url: str = "http://127.0.0.1:8091"
    design_studio_timeout_seconds: float = 60.0

    # --- 存储（主规格 §11.1）---
    database_url: str = "sqlite+aiosqlite:///./flux.db"
    redis_url: str = "redis://localhost:6379/0"

    # --- 模型供应商（主规格 §5.3）---
    # 仅供 Flux 侧基础设施调用（T2 压缩、扫描摘要等），不是 Agent 的模型通道；
    # 密钥仅由对应 Provider 读取，绝不下发给 Agent（§14.3）
    openai_api_key: str | None = None
    openai_base_url: str = "https://api.openai.com/v1"
    openai_model: str = "gpt-5.5"
    anthropic_api_key: str | None = None
    anthropic_base_url: str = "https://api.anthropic.com"
    anthropic_model: str = "claude-sonnet-5-5"
    deepseek_api_key: str | None = None
    deepseek_base_url: str = "https://api.deepseek.com"
    deepseek_model: str = "deepseek-flash"
    local_model_base_url: str | None = None
    local_model_name: str = "local-echo"
    # Anthropic 的 max_tokens 是必填字段（做不到 OpenAI / DeepSeek 那样"不传=不限"），
    # 调用方未指定时下发这个值。给足余量：平台内部的长文任务（T2 压缩、摘要）
    # 兜底值偏小会把输出截断；换成输出上限更小的模型时请同步调小。
    anthropic_max_tokens: int = 32000
    default_provider: str = "local"

    # --- Virtual Workspace Apply（主规格 §7.6）---
    # Agent 改动的落盘根目录。留空则 apply 直接报错——绝不默认写到某个"看起来还行"的目录。
    workspace_root: str | None = None
    # Apply 之后要跑的测试命令（如 "pytest -q"）；留空表示不跑测试
    test_command: str | None = None
    test_timeout_seconds: float = 300.0
    # 提案审核 TTL（秒）。> 0 时，新提案带一个 expires_at，超过即失效（pending → expired）；
    # <= 0 表示不启用超时失效——改动的有效性完全由 original_hash 复验来把关。
    proposal_ttl_seconds: int = 86400
    # 平台规则（最终方案 §13）：Agent 的改动是否必须经 Proposal 审核才能落盘。
    # 这是 Flux 的规则，不是 Agent 自己选的；下发在 flux_context.policy 里，Agent 不得绕过。
    proposal_required: bool = True

    # --- Git 集成（主规格 §17.6；实施计划 ⑨）---
    # git 命令超时；Git 操作同样只在 workspace_root 下执行
    git_timeout_seconds: float = 30.0

    # --- Project Scanner（主规格 §5.8；实施计划 ⑩）---
    # 扫描上限：文件数与目录深度都必须有界，避免在巨型仓库上把时间/内存打满。
    # 触顶时画像照常产出，但 truncated=True，让调用方知道结果被裁剪过。
    project_scan_max_files: int = 2000
    project_scan_max_depth: int = 6

    # HTTP 调用策略（§5.1 错误处理：模型失败 / 超时 → 重试）
    model_timeout_seconds: float = 60.0
    model_max_retries: int = 2
    # 平台内部模型调用的单次输出上限；None 表示不设上限，由模型自身的默认输出上限决定。
    model_max_output_tokens: int | None = None

    # --- DSH Agent Runtime（集成方案 §17 配置表；Phase 1 用官方 Python SDK）---
    # 是否启用 DSH Agent Runtime（默认关闭，未启用时 /dsh 接口返回 503）
    dsh_enabled: bool = False
    # DSH_HOME 绝对路径：profiles / plugins / 会话落盘，必须放仓库外
    dsh_home: str = "/opt/flux/dsh-home"
    # DSH Agent 的工作目录（cwd），Phase 1 用独立空目录，不指向真实项目
    dsh_workspace: str = "/opt/flux/dsh-ws"
    # 手机端聊天附件（用户上传的文件）：按上传 id 一目录存放，Agent 按绝对路径直接读
    uploads_dir: str = "/opt/flux/uploads"
    # AI 下发给用户的文件：按 delivery id 一目录存放，手机端按卡片里的 id/序号取文件
    deliveries_dir: str = "/opt/flux/deliveries"
    # DSH provider 路由
    dsh_provider: str = "deepseek-official"
    # DSH 模型 id
    dsh_model: str = "deepseek-v4-flash"
    # 单次输出 token 上限
    dsh_max_tokens: int = 49152
    # 初始化握手超时（秒）
    dsh_init_timeout_seconds: float = 30.0
    # 单轮超时（秒）；0 表示不设限
    dsh_run_timeout_seconds: float = 0.0

    # --- Run 生命周期看护（修复方案 §2.3~§2.7）---
    # 三类 timeout，全部可关（0 = 关闭该判据）：
    #   startup：进程起来但始终没有进入 RUNNING（构造/握手卡住）的上限
    #   idle：进程还在、但既无输出也无 MCP 活动、状态也不变的上限
    #   hard：无论有无输出都不放行的绝对上限
    dsh_startup_timeout_seconds: float = 120.0
    dsh_idle_timeout_seconds: float = 600.0
    dsh_hard_timeout_seconds: float = 1800.0
    # 心跳周期：写 last_heartbeat_at 并检查三类超时 / 进程存活
    dsh_heartbeat_interval_seconds: float = 5.0
    # Reconciler 对账周期：数据库状态 ↔ 真实进程状态
    dsh_reconcile_interval_seconds: float = 10.0
    # Cancel 优雅期：SIGTERM 进程组后等这么久，仍在则 SIGKILL
    dsh_cancel_grace_seconds: float = 5.0

    # --- DSH × MCP 注入（目标架构 §3；P0-01 真闭环）---
    # 内置 Agent 只能经 MCP 看项目、提提案；启动 DSH 时自动生成 patch 注入 MCP 客户端插件。
    # 关闭后 DSH 会退化成"没有平台的裸 agent"——仅用于本地排查，默认必须开着。
    dsh_mcp_enabled: bool = True
    # Flux MCP 端点（streamable-http）。默认同机默认端口；隔离实例请显式指向自己的端口
    dsh_mcp_url: str = "http://127.0.0.1:8000/mcp"
    # MCP serverName（注入后工具名形如 mcp__flux__workspace.read）
    dsh_mcp_server_name: str = "flux"
    # 内置 Agent 的令牌身份：Flux 用它在 MCP 面盖章 provenance
    dsh_mcp_agent_id: str = "flux-builtin"
    # 单次工具调用超时（毫秒）
    dsh_mcp_tool_timeout_ms: int = 60000

    # --- 外部 CLI Agent：Codex 的模型通道（P2-1 §6.2 续，2026-10-06）---
    # codex 把「鉴权方式」当全局状态，Flux 只能给它一个隔离的 CODEX_HOME（顺便注入 MCP），
    # 而隔离目录里没有用户的登录态——不写这几项，codex 在 Run 里就是「未登录」直接失败。
    # 密钥不落盘：第三方供应商块只写 env_key（环境变量名），值由服务进程环境提供。
    # "openai" = 用 codex 内置供应商（ChatGPT 登录 / OPENAI_API_KEY），不写供应商块；
    # 其他值按第三方供应商写 [model_providers.<name>]（当前支持 deepseek）。
    codex_provider: str = "openai"
    # 空 = 用 codex 自带默认模型；非 openai 供应商必须显式给（codex 默认模型 id 是 OpenAI 的）
    codex_model: str = ""
    # 第三方模型的元数据目录（codex 的 model_catalog_json）：缺了 codex 会退化成 fallback
    # 元数据（实测警告「Model metadata not found…can degrade performance」）；空 = 不写
    codex_model_catalog: str = ""
    # ChatGPT 账号（Codex 订阅通道）的登录态目录。codex login 把 auth.json 写在这里，
    # 每次 Run 只把这一个文件复制进隔离的 CODEX_HOME（0600），不动用户的全局 ~/.codex。
    codex_account_home: str = "~/.flux/codex-account"
    # 设备码登录与订阅出网的代理地址（如 http://10.99.0.1:7890）；
    # 空 = 不注入，继承 Flux 进程环境（当前由 /usr/local/bin/codex wrapper 自带代理）。
    codex_proxy: str = ""
    # opencode 的模型清单来自它自己的全局配置（provider.<id>.models，含 Zen 免费模型）。
    # Flux 只读模型 id，不复制清单；文件不存在 = 不提供切换，沿用 opencode 自带默认。
    opencode_config_file: str = "~/.config/opencode/opencode.json"

    # --- 计费与套餐额度（本地账本 + 官方快照对账）---
    # 关掉后不再写 usage_records / usage_snapshots，闸门整体失效
    billing_enabled: bool = True
    # 是否真的拦截超额 Run。默认 False = 只告警放行：官方快照有 5~30 分钟延迟，
    # 直接用延迟数据拒单会误伤；确认口径可信后再打开。
    billing_enforce: bool = False
    # 告警 / 拦截阈值（已用百分比，0-100）
    billing_alert_percent: float = 80.0
    billing_block_percent: float = 95.0
    # 官方额度快照的缓存时长（秒）；0 = 每次都重新采集
    billing_refresh_seconds: int = 300
    # Go 套餐的总额（美元）。官方只给百分比，配了它才能在官方不可用时用本地账本折算占比
    billing_go_monthly_usd: float | None = None
    # 火山 Agent Plan 额度采集命令（本机需要先 `arkcli auth login`）；
    # 留空 = 不采集，闸门退化为「官方数据不可用」
    billing_arkcli_cmd: str = "arkcli usage plan --product agent-plan"
    billing_arkcli_timeout_seconds: float = 30.0
    # 自定义套餐定义文件（JSON）。内置套餐 + 本文件合并，同 key 覆盖内置；
    # 新增 Codex / Claude / 任意订阅套餐只需往这里加一条，不用改代码。
    billing_plans_file: str = "~/.flux/billing-plans.json"
    # 官方价目表覆盖文件（JSON，同 model 覆盖内置单价，单位每百万 token）
    billing_prices_file: str = "~/.flux/billing-prices.json"
    # 人民币价（火山方舟）换算美元用的汇率；只影响本地估算，不影响官方账单
    billing_cny_per_usd: float = 7.2

    # --- 密钥库（API Key Vault）---
    # 加密 API Key 的主密钥；留空 = 首次启动时在 key_vault_key_file 自动生成并 0600 落盘。
    # 主密钥丢失后已存 key 无法解密，只能重新录入（接口不会返回乱码明文）。
    key_vault_secret: str | None = None
    # 主密钥文件；留空 = ~/.flux/key-vault.key
    key_vault_key_file: str | None = None

    # --- 账号云（B 机 flux.qiuli55.top，独立私有仓库）---
    # Flux 本体的登录由账号云统一签发令牌：账号的权威/后台都在那边，本机只做本地验签
    # （见 flux.api.auth.is_cloud_access_token），不每次回源。
    cloud_base_url: str = "https://flux.qiuli55.top"
    # 与账号云共享的 HS256 密钥（FLUX_CLOUD_SECRET_KEY）。留空 = 不认账号云令牌，
    # 登录闭环整体关闭，退回"FLUX_AUTH_TOKEN / 回环直连"的老口径。
    cloud_secret_key: str | None = None
    # 是否要求走账号云登录（FLUX_CLOUD_LOGIN_REQUIRED）。桌面端安装包用：它不
    # 携带共享密钥（随包分发就等于泄露签名密钥），但产品上必须要求登录，于是把
    # 「要不要登录」与「能不能本地验签」拆开——置 True 时前端会跳账号云登录页。
    cloud_login_required: bool = False
    # 本机云会话（access / refresh token）落盘文件；留空 = ~/.flux/cloud.json。
    cloud_session_file: str | None = None
    # 登录完成后要回到的 Flux 工作台地址；登录页以 ?next=<它> 携带回跳目标。
    cloud_return_url: str | None = None
    # 运行日志落盘目录（相对路径按仓库根解析）。留空 = 不落盘、也不自动上传。
    # 生产必须配上：Flux 启动时会把「上一轮运行日志」自动上传到账号云。
    log_dir: str | None = None
    # 本机设备身份凭据（flux-device-agent 写入，0600）。用户记忆的账号级同步
    # 用设备令牌，而不是「本机是否登录了账号云」。留空 = /etc/flux-device-credentials。
    device_credentials_file: str | None = None
    # 用户长期记忆：任务执行中由 Agent 调 memory.remember 自行记录并同步到账号云；
    # 关闭后仍可在手机端手动编辑。
    memory_auto_capture: bool = True
    memory_cloud_sync: bool = True
    # 自动记录开关的落盘位置；留空 = ~/.flux/memory-prefs.json
    memory_prefs_file: str | None = None

    @property
    def is_sqlite(self) -> bool:
        return self.database_url.startswith("sqlite")

    @property
    def log_dir_path(self) -> Path | None:
        """运行日志目录；未配置时为 None（= 关闭落盘与自动上传）。"""
        raw = (self.log_dir or "").strip()
        if not raw:
            return None
        path = Path(raw).expanduser()
        return path if path.is_absolute() else (REPO_ROOT / path)

    @property
    def cloud_session_path(self) -> Path:
        """本机云会话文件路径。"""
        raw = (self.cloud_session_file or "").strip()
        if raw:
            path = Path(raw).expanduser()
            return path if path.is_absolute() else (REPO_ROOT / path)
        return Path.home() / ".flux" / "cloud.json"

    @property
    def device_credentials_path(self) -> Path:
        """设备身份凭据路径；未配置时为 /etc/flux-device-credentials。"""
        raw = (self.device_credentials_file or "").strip()
        if raw:
            path = Path(raw).expanduser()
            return path if path.is_absolute() else (REPO_ROOT / path)
        return Path("/etc/flux-device-credentials")

    @property
    def memory_prefs_path(self) -> Path:
        """用户记忆自动记录开关的落盘路径。"""
        raw = (self.memory_prefs_file or "").strip()
        if raw:
            path = Path(raw).expanduser()
            return path if path.is_absolute() else (REPO_ROOT / path)
        return Path.home() / ".flux" / "memory-prefs.json"

    @property
    def key_vault_key_path(self) -> Path:
        """密钥库主密钥文件路径；未配置时为 ~/.flux/key-vault.key。"""
        raw = (self.key_vault_key_file or "").strip()
        if raw:
            path = Path(raw).expanduser()
            return path if path.is_absolute() else (REPO_ROOT / path)
        return Path.home() / ".flux" / "key-vault.key"


@lru_cache
def get_settings() -> Settings:
    """进程内单例。测试中可直接实例化 Settings(...) 覆盖。"""
    return Settings()

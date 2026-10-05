# Flux 手机端（原生 Android 远程控制）——施工单 / 交接文档

> 日期：2026-10-05 ｜ 分支：`main`（本批改动**尚未提交**，清单见 §4）
> 用途：把「已完成」「当前卡点」「接下来每一步」写成可直接照做的施工单，接手即可继续。
> 纪律：本文档不写任何密钥/令牌明文（红线：密钥不入库、不进文档）。

---

## 1. 目标与范围（用户已拍板）

用户原话：

- 「把手机端做一个原生安卓端」
- 「我想不论在什么电脑系统上都能够远程，就和 Trae 一样」
- 「你就直接做好之后挂到公网我去下载就行」→ 不做中间 demo 审批，直接交付可用 APK

**交付形态**：debug APK 挂 GitHub Release，手机下载安装即可用；App 通过 HTTPS + 访问令牌连到公网服务器上的 Flux 实例。

**功能集（已定的范围）**：任务列表 / 任务详情（Run 状态与进展）/ 对话 / 需求确认展示 / 提案 + Diff / 批准 / 应用（落盘）/ 拒绝 / 回滚 / 取消任务 / 停止 Agent / Agent Terminal（看实时输出 + Stop / Force Stop）。

**分期（必须在交付说明里讲清楚，不能假装全覆盖）**：

- 本批：**App → HTTPS + 令牌 → 公网服务器上的 Flux 实例**（服务器 = 本机 `159.75.222.60`）
- 下一批：**家庭 Windows 机器（NAT 后）的穿透**——让 App 也能连家里那台 Flux

---

## 2. 已完成（每一项都经过真实验证）

### 2.1 后端 REST 单用户令牌鉴权

| 文件 | 改动 |
|---|---|
| `backend/flux/api/auth.py`（新增） | `require_rest_auth` 统一鉴权依赖 + `is_trusted_local`；用 `HTTPBearer(auto_error=False)` 声明 securityScheme（让 `/docs` 上有 Authorize 按钮） |
| `backend/flux/config.py` | 新增 `auth_token: str \| None = None`（环境变量 `FLUX_AUTH_TOKEN`），留空 = 关闭鉴权 |
| `backend/flux/main.py` | `app.include_router(api_v1_router, prefix=..., dependencies=[Depends(require_rest_auth)])`——挂在整棵 `/api/v1` 上，新增路由自动受保护 |
| `backend/tests/test_rest_auth.py`（新增） | 16 条用例全绿：未配置=全放行 / 带对令牌=200 / 6 种坏凭据=401 / 写接口同样被拦 / 回环无转发头免认证 / **回环+任一转发头必须带令牌** / `/docs` 与 `/openapi.json` 保持公开 / OpenAPI 声明 bearerAuth |
| `docs/openapi.json` | 重新导出（+352 行，含 `securitySchemes.HTTPBearer` 与每个操作的 `security`） |

规则三条（`auth.py` 顶部注释有完整理由）：

1. 带 `Authorization: Bearer <token>` 且常量时间比对通过 → 放行；
2. **例外**：来源回环（`127.0.0.1` / `::1` / `localhost`）**且没有任何转发头**（`X-Forwarded-For` / `X-Real-IP` / `Forwarded`）→ 放行。用途：本机浏览器、桌面端 Electron 反代、`make run` 都不必带令牌；
3. 其余一律 401（`unauthenticated`，不区分"没带/带错"）。

> **硬约束**：反向代理必须转发 `X-Forwarded-For`，否则公网请求会以"回环 + 无转发头"的形态绕过鉴权。`tests/test_rest_auth.py::test_loopback_with_forward_headers_must_authenticate` 把这个约束钉在代码层，部署脚本用"不带令牌应当 401"复验。

**验证状态**：全量 `pytest` 通过（exit 0）。**`make verify` 全量门禁尚未跑**（`ruff` / `ruff format` / `openapi-check` / `alembic` 往返 / pytest），列为 §5 第 6 步。

### 2.2 公网部署（已端到端验证）

| 项 | 值 / 位置 |
|---|---|
| systemd 单元 | 仓库 `deploy/flux-server.service` → 已装到 `/etc/systemd/system/flux-server.service`，`enable --now`，状态 `active (running)` |
| 监听 | `127.0.0.1:8801`（只回环，公网入口由 nginx 提供） |
| 环境文件 | `/opt/flux/server/flux-server.env`（权限 0600，**不入库**） |
| 数据库 | `sqlite+aiosqlite:////opt/flux/server/flux.db`（`ExecStartPre` 先 `alembic upgrade head`） |
| 工作区（Agent 改动的落盘目标） | `/root/workspace/flux-login-app`，测试命令 `pytest -q` |
| Agent Runtime | `FLUX_DSH_ENABLED=true`，provider `deepseek-official`，model `deepseek-v4-flash`，MCP 端点 `http://127.0.0.1:8801/mcp` |
| 平台模型通道 | `FLUX_DEFAULT_PROVIDER=deepseek`、`FLUX_DEEPSEEK_MODEL=deepseek-flash`，密钥取自 `/opt/ops/.env` 的 `DEEPSEEK_API_KEY`（同时以 `FLUX_DEEPSEEK_API_KEY` 与 `DEEPSEEK_API_KEY` 两个名字写入 server env：前者给平台侧 ModelRouter，后者给 DSH 子进程） |
| nginx | `/etc/nginx/sites-available/flux-demo` 的 HTTPS server 块追加 `location /mobile/`（旧文件备份 `flux-demo.bak-20261005-151758`）；仓库副本 `deploy/nginx-flux-mobile.conf` |
| 反代要点 | `proxy_pass http://127.0.0.1:8801/`（尾斜杠剥掉 `/mobile` 前缀）、转发 XFF/X-Real-IP、`proxy_buffering off`（SSE 必须）、`proxy_read_timeout 3600s` |

**端到端验证记录（curl 实测）**：

| 请求 | 结果 |
|---|---|
| `http://127.0.0.1:8801/api/v1/health`（无令牌，回环） | 200 `{"status":"ok","app":"Flux","env":"server"}` |
| 同端口 + `X-Forwarded-For: 1.2.3.4`（模拟代理，无令牌） | **401** |
| 同端口 + XFF + 正确令牌 | 200 |
| `https://flux.qiuli55.top/mobile/api/v1/health`（无令牌） | **401** |
| 同上 + 正确令牌 | 200 |
| `https://flux.qiuli55.top/`（原静态演示页） | 200（未被影响） |
| `https://flux.qiuli55.top/mobile/docs` | 200（Swagger 可用，带 Authorize 按钮） |

**真实 Agent 链路已跑通（不是空壳）**：

- 只读 smoke run：`POST /mobile/api/v1/dsh/runs` → `status=completed`，真实模型返回项目摘要（经 MCP 的 `workspace.read` 读取 `flux-login-app/README.md`）。
- 真实任务：任务 `9d49f717-b432-4b50-b9be-2f86cd0cefce`（「给 flux-login-app 增加修改密码接口 POST /auth/change-password」）
  - `POST /tasks/{id}/messages` → 助手真实回复 + 6 维需求确认卡（provider/model = `deepseek` / `deepseek-flash`）
  - `POST /tasks/{id}/start` → DSH Run `0996ed2d3a8149b6b2bb8febff0baf4b` → 任务 `completed`
  - 产出 **3 条待审提案**（可直接用作手机验收的真实数据）：
    - `app/main.py` modify +51/-0
    - `README.md` modify +14/-3
    - `tests/test_auth.py` modify +125/-0

### 2.3 Android 工程与页面代码

路径 `apps/mobile-controller/`，包名 `top.qiuli55.flux.mobile`。

**版本组合（快照日期 2026-10-05）**：

| 组件 | 版本 | 备注 |
|---|---|---|
| Android Gradle Plugin | 8.7.2 | 官方兼容表已联网核实：AGP 8.7 要求 Gradle ≥ 8.9 |
| Gradle | 8.9 | 本机已有该发行版缓存 |
| Kotlin | 2.0.21 | 含 `org.jetbrains.kotlin.plugin.compose`（Kotlin 2.0 起 Compose 编译器随 Kotlin 发版） |
| Compose BOM | 2024.02.01 | 统一 compose-ui / material3（1.2.0）版本 |
| OkHttp / okhttp-sse | 4.12.0 | SSE 用于终端实时输出 |
| kotlinx-serialization-json | 1.6.3 | |
| kotlinx-coroutines-android | 1.8.1 | |
| navigation-compose / lifecycle-* | 2.7.7 / 2.7.0 | |
| datastore-preferences | 1.0.0 | 存服务器地址与令牌 |
| SDK | compileSdk 34 / targetSdk 34 / minSdk 26 | `/opt/android-sdk`（platform android-34、build-tools 34.0.0）；JDK 17.0.20 |

**代码结构（每个文件一句）**：

| 文件 | 作用 |
|---|---|
| `data/Models.kt` | 与服务端统一响应体对齐的 `@Serializable` 模型（任务/消息/提案/Run/终端/DSH 状态/Agent），snake_case 保原名，全部字段带默认值 + `ignoreUnknownKeys` |
| `data/FluxApi.kt` | REST 客户端：地址归一化、Bearer 注入、统一错误归类（401/服务端 code/传输层 `network_error`）、SSE 开流、终端命令用"不设读超时"的客户端 |
| `data/SettingsStore.kt` / `data/FluxEnv.kt` | DataStore 存地址+令牌；应用级单例按 `地址\|令牌` 缓存 OkHttpClient（配置变了自动换客户端并关旧的） |
| `ui/Common.kt` | 共用展示件（卡片/状态胶囊/等宽文本/错误条/输入框配色）+ 状态→文案颜色映射 + 时间格式化 |
| `ui/AppNav.kt` | 路由与首个落点：没有令牌 → 先进连接设置页；已配置 → 任务列表 |
| `ui/setup/SetupViewModel.kt` + `SetupScreen.kt` | 服务器地址/令牌填写、显示令牌、测试连接（顺带取回 health、DSH 状态、Agent 数）、保存 |
| `ui/tasks/TaskViewModels.kt` | 任务列表（含新建）；任务详情（3 秒轮询 running/waiting_for_user_decision、发消息、开始执行、取消任务、停止 Run、决策选择、提案四条动作） |
| `ui/tasks/TaskListScreen.kt` / `TaskDetailScreen.kt` | 任务卡片列表 + 新建对话框；详情页（任务头/Run 卡/需求确认/待决策/对话/变更，底部固定输入框） |
| `ui/changes/ChangeViewModel.kt` + `ChangeDetailScreen.kt` | 单条提案：Diff（红绿着色）/改动后/改动前三个视图、批准/应用/拒绝/回滚 |
| `ui/terminal/TerminalViewModels.kt` + `TerminalScreens.kt` | 会话列表与新建；单会话：历史续读 + SSE 实时流 + 命令执行 + Stop/Force Stop；事件按换行重组为"转录行"（echo/output/exit/system 四类着色） |

**构建环境事实**：

- 本机没有 `gradle` 命令；可用的是缓存发行版：`/root/.gradle/wrapper/dists/gradle-8.9-all/34ncldp5ayui479swhyf2hcth/gradle-8.9/bin/gradle`
- `local.properties` 里 `sdk.dir=/opt/android-sdk`（已加进 `apps/mobile-controller/.gitignore`，不入库）
- 首次 `assembleDebug` 已通过，产出 `app/build/outputs/apk/debug/app-debug.apk`（16,805,036 字节，17:07）——**注意**：那次构建发生在 Kotlin 源码写全之前，只证明工具链可用，**不代表当前代码可编译**。

---

## 3. 当前卡点：一处编译错误已修补，待重新构建确认

**事实经过**：首次带全部源码的构建只报了一个错误——

```
e: FluxApi.kt:306:16 Return type mismatch: expected 'okhttp3.Call', actual 'okhttp3.sse.EventSource'
```

根因：`EventSources.createFactory(...).newEventSource(...)` 返回的是 `EventSource`，而 `openTerminalStream` 的返回类型写成了 `Call`（笔误）。

**已完成的修补（文件已是新状态）**：

- `data/FluxApi.kt:279`：`openTerminalStream(...): EventSource`，import 改为 `okhttp3.sse.EventSource`（不再有 `okhttp3.Call`）
- `ui/terminal/TerminalViewModels.kt:99/145/146/265`：字段 `streamSource: EventSource?`、`streamSource?.cancel()`，import 同步

**尚未做**：重新运行一次 `assembleDebug` 确认无其他错误（这是 §5 第 1 步）。

---

## 4. 未提交改动清单（`git status` 快照）

| 路径 | 状态 | 说明 |
|---|---|---|
| `backend/flux/api/auth.py`、`backend/tests/test_rest_auth.py` | 新增 | 鉴权与用例 |
| `backend/flux/config.py`、`backend/flux/main.py`、`docs/openapi.json` | 修改 | 接入鉴权与契约更新 |
| `deploy/` | 新增 | `flux-server.service`、`flux-server.env.example`、`nginx-flux-mobile.conf` |
| `apps/mobile-controller/` | 新增 | `settings.gradle.kts` / `build.gradle.kts` / `gradle.properties` / `.gitignore` / `app/`（含全部 Kotlin 源码与资源） |
| `docs/UI_TEST_REPORT.md` | 修改 | **刻意保留的本地改动，绝不提交**（历史遗留约定） |

---

## 5. 接下来要做的事（按顺序）

### 1) 重新构建 debug APK

```bash
cd /root/workspace/flux/apps/mobile-controller
/root/.gradle/wrapper/dists/gradle-8.9-all/34ncldp5ayui479swhyf2hcth/gradle-8.9/bin/gradle --console=plain assembleDebug
```

期望：`BUILD SUCCESSFUL`，产物 `app/build/outputs/apk/debug/app-debug.apk`。
记录真实大小与 `sha256sum`（写进 README 与 Release 说明）。

### 2) 生成 Gradle wrapper 入库

```bash
... gradle wrapper --gradle-version 8.9 --distribution-type bin
```

为什么必须做：现在构建依赖"本机刚好有 8.9 的缓存发行版"这一事实；`gradlew` 入库后换机器/CI 都能一条命令构建（`distributionUrl` 指向 `gradle-8.9-bin.zip`）。

### 3) 写 `apps/mobile-controller/README.md`

必须包含：下载安装、首次配置（服务器地址 + 令牌怎么来）、功能清单、构建命令、已知限制、**真机验收清单**（每条写清"点什么 / 看到什么"，用 §5 第 4 步的 12 条为准并把结果回填）。

### 4) 真机验收（华为平板，用 tablet-control 技能远程操控）

安装 APK → 逐项点验 → 结果（通过/失败 + 现象）回填 README：

1. 首次启动落到「连接 Flux」页（没有令牌时不进任务列表）
2. 填 `https://flux.qiuli55.top/mobile` + 令牌 → 点「测试连接」→ 看到 应用 Flux（ok）/ 环境 server / Agent Runtime 已启用 + `deepseek-official / deepseek-v4-flash`
3. 保存 → 进任务列表，看到任务 `9d49f717`（描述：给 flux-login-app 增加「修改密码」接口…）
4. 打开任务详情：状态「已完成」、需求确认 6 项、对话 2 条、变更 3 项
5. 打开一条提案：Diff 有红/绿着色，`改动后`/`改动前` 可切换，行数 `+51 / -0` 与列表一致
6. 对 `README.md` 那条：**批准** → 状态变「已批准」；**应用（落盘）** → 变「已落盘」；服务端 `/root/workspace/flux-login-app/README.md` 内容真的变了
7. **回滚**刚落盘的那条 → 「已回滚」；磁盘内容还原（与服务端 `git diff` 核对为空）
   > 注意：这 3 条提案是 Agent 真实产出、**未经过人工 code review**；验收按需批准/应用/回滚，**不要 git 提交**。想留干净起点可以把它们全部拒绝。
8. 新建任务 → 点右下角「新建任务」→ 输入一句话（例：`给 flux-login-app 增加 /health 的版本字段`）→ 创建 → 详情页底部输入框发消息 → 助手真实回复
9. 点「开始执行」→ Run 卡出现「运行中」→ 等 Agent 跑完 → 变更区出现新提案
10. 对执行中的任务点 Run 卡的「停止 Agent」→ Run 变「已取消」、任务变「已取消」（终态以服务端进程树清理确认为准，不谎报）
11. 终端：右上角终端图标 → 「新建会话」→ 输入 `pwd` → 输出实时出现（SSE）→ 执行 `sleep 30` 期间点 **Stop** → 看到 `[exit -15]`；再执行 `sleep 30` 点 **Force Stop** → `[exit -9]`；顶部状态胶囊显示「实时」
12. 观看与运行的解耦：杀掉 App 或返回列表再进会话 → 历史输出仍在；服务端命令不受"关页面"影响（设计如此）

### 5) 更新设计文档 `doc/PERSONAL_MVP_RELEASE_DESIGN.md` §9

把「移动端在现有 web-dashboard 上加移动视图、不另起项目」改为「**原生 Android（`apps/mobile-controller/`）**」，并记录：分期（本批公网服务器实例 / 下批家庭 Windows 穿透）、ADR（为什么原生而非 WebView：SSE 终端流、令牌本地保存、真机交互）、移动端只读+审核+停止的能力边界（不在手机上编辑需求确认）。

### 6) 全量门禁

```bash
cd /root/workspace/flux && make verify
```

（`ruff` → `ruff format` → OpenAPI 契约 → `alembic` 往返 → `pytest`；上一次基线 603 passed，本轮新增 16 条鉴权用例。）

### 7) 提交并推送（建议分三笔，便于回溯）

1. `feat(api): REST 面单用户令牌鉴权（FLUX_AUTH_TOKEN）`（auth.py / config / main / 用例 / openapi.json）
2. `chore(deploy): 服务端实例 systemd 单元与 nginx /mobile 反代配置`（deploy/）
3. `feat(mobile): 原生 Android 手机端（任务/提案/Diff/终端）`（apps/mobile-controller + 设计文档更新）

**不要**把 `docs/UI_TEST_REPORT.md` 加入暂存区。

### 8) 发布

GitHub Release（tag `v0.2.0`），附件 `flux-mobile-0.1.0-debug.apk`（应用 versionName `0.1.0`，Release tag 表示构建批次）；说明里写：真实文件大小、SHA256、服务器地址、令牌获取方式、已知限制、分期说明。

### 9) 交付说明给用户（对话里）

下载链接 + 服务器地址 + **令牌获取方式**（令牌只存在于服务器 `/opt/flux/server/flux-server.env`，交付时单独给出，不写进任何入库文档）+ 轮换方法（改 env → `systemctl restart flux-server` → App 里改令牌）+ 下一批说明（家庭 Windows 穿透）。

---

## 6. 关键环境信息（照抄可用）

| 项 | 值 |
|---|---|
| 公网入口 | `https://flux.qiuli55.top/mobile`（nginx `location /mobile/` → `127.0.0.1:8801`，前缀会被剥掉） |
| 服务端实例 | systemd `flux-server`，`127.0.0.1:8801` |
| 令牌 / 全部配置 | `/opt/flux/server/flux-server.env`（0600，不入库）；改完 `systemctl restart flux-server` |
| 令牌生成 | `openssl rand -hex 24` |
| 数据 / 工作区 | `/opt/flux/server/flux.db`、`/root/workspace/flux-login-app`（改 `FLUX_WORKSPACE_ROOT` 可指向别的项目） |
| nginx | `/etc/nginx/sites-available/flux-demo`；仓库副本 `deploy/nginx-flux-mobile.conf`；生效 `nginx -t && systemctl reload nginx` |
| 服务日志 | `journalctl -u flux-server -f` |
| Android 构建 | `/opt/android-sdk`（`local.properties` 的 `sdk.dir`）；Gradle 8.9 缓存路径见 §2.3；JDK 17.0.20 |
| 交付域名纪律 | `flux.qiuli55.top` 的静态首页、`/opt/oc-gateway`、`portal-docs` 是面试/门户聚合，除本次新增的 `/mobile/` 外不要改动 |

---

## 7. 已知风险与待确认（交付前需逐条交代）

1. **令牌传递**：只在服务器 0600 文件里，交付时需单独给用户（不写进任何入库文档、不写进 Release 说明）。
2. **debug 包、无正式签名**：个人使用没问题；上架/长期分发需要签名密钥（红线：密钥不入库）。
3. **明文 HTTP 已禁用**（`AndroidManifest` 的 `usesCleartextTraffic="false"`）：连局域网 `http://` 实例需要改这一行（下一批的家庭 Windows 场景大概率要改）。
4. **模型凭据依赖**：`/opt/ops/.env` 的 `DEEPSEEK_API_KEY` 被复用；缺它会以 `MISSING_CREDENTIAL` 直接失败（已实测报错原文：`no API key for provider route "deepseek-official"`）。
5. **3 条待审提案未经人工 review**：它们是 Agent 真实产物，可用于验收审核链路；不想要就在手机上拒绝。
6. **门禁未跑**：`make verify` 尚未执行（§5 第 6 步）。
7. **家庭 Windows 穿透未做**：下一批；本批的 App 只能连公网可达的实例。
8. **本文档自身**：尚未入库（未 commit），会随 §5 第 7 步一起提交。
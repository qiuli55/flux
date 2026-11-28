# Cursor 与谷歌 Antigravity 接入说明

本文件记录 Track A 的实现边界：把 Cursor 与谷歌 Antigravity（反重力）做成与
Claude Code / Codex / OpenCode 同级的**可选 Agent**。运行时 id 固定为
`cursor` 与 `antigravity`。

## 与现有三个 Agent 的对照

| 能力 | Codex / OpenCode / Claude Code | Cursor / Antigravity |
| --- | --- | --- |
| Adapter 注册 | `build_default_adapters()` | 同一注册表新增 `CursorAdapter` / `AntigravityAdapter` |
| 本机发现 | `--version` + 登录探测 | `agent --version` / `agy --version` + `status` |
| Runtime 登记 | `KNOWN_RUNTIMES` / `CLI_RUNTIMES` / limits | 同四张表全部登记 |
| 执行方式 | 常驻服务或 stream-json 会话 | 官方 headless `--print --output-format stream-json`，一轮一进程 |
| 工作目录 | `cwd = workspace` | 相同；Cursor 另带官方 `--workspace` |
| 上下文 | 执行指令 + workspace + 附件 | 同一份执行指令（含对话轨迹、附件路径）+ workspace |
| 结果回流 | 统一事件 `message / tool_call / tool_result / final / error` | 同一套统一事件（新增的 stream-json 归一化两个运行时共用） |
| 取消 / 超时 | `RunSupervisor` + `RuntimeLimits` | 同一套，不新建生命周期 |
| 未接入启动 | 409 + 上线指引 | 相同（`_require_ready`） |
| 连接器入口 | 订阅页（Claude / Codex） | 手机「连接器」、Web「连接器市场」、桌面 Skills「Agent 连接器」 |
| 模型清单 | 读各自官方/本地配置 | 只认 `FLUX_CURSOR_MODEL` / `FLUX_ANTIGRAVITY_MODEL`；留空用官方默认，不编造模型 id |

## 官方授权如何工作

- **Cursor**：`NO_OPEN_BROWSER=1 agent login` 在用户电脑上启动官方登录，Flux 解析
  官方打印的授权链接并交给客户端打开；登录态由官方 CLI 保存，Flux 不读取、不保存
  Cursor 令牌。`agent status` 查状态，`agent logout` 退出。
- **Antigravity**：在用户电脑上启动官方 `agy login`（不同版本可用
  `FLUX_ANTIGRAVITY_LOGIN_ARGS` 覆盖，例如 `acp --login`），凭据留在官方 CLI 的
  安全存储；Flux 不实现 Google OAuth，也不换个人订阅令牌。
- 两端登录都清掉 `CURSOR_API_KEY` / `GEMINI_API_KEY` 等环境变量，确保浏览器授权是
  主路径；API key / 粘贴 token 不是连接器按钮的第一步。
- 登录成功后 Flux 只在 `~/.flux/<runtime>-account/connected.json` 写一个**非机密**
  标记（不含令牌），并把对应 installation 推进到 READY，让它进入可选 Agent 列表。
- 响应只暴露 `logged_in / auth_mode / binary_found / official_status / stale_login` 等
  公开字段；`home`、`binary` 是路径，不是凭据。

## 未连接 / 未安装时的表现

- 未安装官方 CLI：启动连接返回 503，文案说明需要在电脑上安装 `agent` / `agy`；
  连接器不会显示成已连接。
- 未登录：installation 最多到 VERIFIED；连接器状态是「未连接」。
- 官方 CLI 报告未登录但 Flux 有旧标记：连接器显示「需要重新连接」。
- 未 READY 的 `cursor` / `antigravity` Agent 不出现在手机可选 Agent 列表；
  Web / 桌面会保留「未接入」提示，起 Run 时后端仍会 409。

## 已知限制（本 PR 不做）

- 未在真实 Cursor / Google 账号上做官方登录验收；只在 JVM、后端单测和假 CLI 上验证。
- Cursor / Antigravity 的官方 print 模式没有与 Claude Code `--mcp-config` 等价的、
  Flux 可安全注入的隔离配置参数；因此本轮不向它们注入 Flux MCP，不改用户全局配置。
  上下文通过执行指令、workspace（cwd）和附件路径下发，结果经统一事件回流。
  这是与现有三个 runtime 的主要差异，后续确认官方 MCP 配置方式后再补。
- Cursor 声明原生逐 token（`--stream-partial-output`）；Antigravity 官方未承诺 token
  级增量，`native_streaming=False`，不把消息级事件冒充逐 token。
- 不复用进程：两者都是一轮一个 `--print` 进程；续聊上下文由编排层随指令带入。

## 测试

```bash
cd backend
../.venv/bin/python -m ruff check .
../.venv/bin/python -m pytest tests/test_cursor_antigravity.py
../.venv/bin/python scripts/export_openapi.py --check
```

Android（JVM 单测）：

```bash
cd apps/mobile-controller
JAVA_HOME=/home/box/opt/jdk-21.0.13+9 \
ANDROID_HOME=/home/box/Android/Sdk \
./gradlew :app:testDebugUnitTest
```

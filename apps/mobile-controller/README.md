# Flux 手机端（原生 Android 远程控制）

> 个人 / 受信任网络下的 Flux 远程控制 App：手机上审核 Agent 的改动、看实时输出、停止运行中的 Agent。
>
> 设计上下文：[`doc/PERSONAL_MVP_RELEASE_DESIGN.md`](../../doc/PERSONAL_MVP_RELEASE_DESIGN.md) §9 与 [施工单 `doc/FLUX_MOBILE_NEXT_STEPS.md`](../../doc/FLUX_MOBILE_NEXT_STEPS.md)。

---

## 下载与安装

Release 附件：[`flux-mobile-0.1.0-debug.apk`](https://github.com/qiuli55/flux/releases) （**SHA256** `151c741c1cb4d59a062b8b72db6b687be84b18d0dd3be55ba5cf5856ed49eb1e`，约 16.3 MiB，debug 签名）。

```bash
# 任选一种方式推到设备
adb install -r flux-mobile-0.1.0-debug.apk
# 或者把 APK 复制到手机文件管理器里点开安装
```

应用版本 `0.1.2`（versionName / versionCode 3），最低 Android 8.0（API 26），target API 34。

## 首次配置

打开 App → 「连接 Flux」页：

- **服务器地址**：本批公网部署的地址是 `https://flux.qiuli55.top/mobile`（默认已填好）
- **访问令牌**：从服务器的 `/opt/flux/server/flux-server.env` 取 `FLUX_AUTH_TOKEN=` 后的字符串

填完点「测试连接」会真实打一次 `/api/v1/health` 并取回 Agent Runtime 状态；显示「应用 Flux（ok）/ 环境 server / Agent Runtime 已启用 / 模型 deepseek-official / deepseek-v4-flash」就对了。
点「保存」进任务列表。

> 令牌只在 App 本地保存到应用私有 DataStore；v0.1.2 起持久化值使用 Android Keystore 保护的 AES-GCM 加密。不会上传任何地方。换服务器或换令牌：在 App 任务列表右上角点 ⚙ 进入设置页改。

## 能做什么 / 不能做什么

| 功能 | 说明 |
|---|---|
| 任务列表 / 详情 | 真实状态落库（不只是 sessionId），3 秒轮询 running / waiting_for_user_decision，其它状态静默 |
| 对话（Solo） | 助手真实回复（deepseek-flash），终态任务不能继续发消息 |
| 需求确认 | 只读展示；改需求走桌面端，移动端不提供编辑 |
| 提案 + Diff | 红/绿着色的统一 diff，可切「改动后 / 改动前」；长 diff 横向滚动不折行（保持缩进） |
| 批准 → 应用（落盘） / 拒绝 / 回滚 | 落盘失败原因显示在卡片上（前端不吞服务端错误） |
| 取消任务 / 停止 Agent | 终态以服务端"进程组确认清理"为准，不谎报 |
| Agent Terminal | 看实时输出 + Stop（SIGTERM → grace → SIGKILL）+ Force Stop（直接 SIGKILL） |
| **不做** | 编辑需求确认、Apply 时跑测试的完整流水线（设计：移动端是审核与停止，不是执行） |

## 真机验收清单（已通过）

验收设备：华为平板（用 `tablet-control` 技能远程操控）。

| # | 操作 | 预期结果 | 实际 |
|---|---|---|---|
| 1 | 首次启动 | 落到「连接 Flux」页（没有令牌时不进任务列表） | ✅ |
| 2 | 填地址 + 令牌 → 测试连接 | 看到 应用 Flux（ok）/ 环境 server / Agent Runtime 已启用 / `deepseek-official / deepseek-v4-flash` | ✅ |
| 3 | 保存 → 进任务列表 | 看到任务 `9d49f717`（描述：给 flux-login-app 增加「修改密码」接口…） | ✅ |
| 4 | 打开任务详情 | 状态「已完成」、需求确认 6 项、对话 2 条、变更 3 项 | ✅ |
| 5 | 打开一条提案 | Diff 有红/绿着色，`改动后` / `改动前` 可切换，行数 `+51 / -0` 与列表一致 | ✅ |
| 6 | 对 `README.md` 那条 批准 → 应用（落盘） | 状态变「已落盘」；服务端 `/root/workspace/flux-login-app/README.md` 内容真的变了 | ✅ |
| 7 | 回滚刚落盘的那条 | 「已回滚」；磁盘内容还原（服务端 `git diff` 核对为空） | ✅ |
| 8 | 新建任务 → 发消息 → 等 Agent 完成 | 状态机由 running → completed；变更区出现新提案 | ✅ |
| 9 | 对执行中的任务点 Run 卡「停止 Agent」 | Run 变「已取消」、任务变「已取消」（终态以服务端进程树清理为准） | ✅ |
| 10 | 终端：新建会话 → `pwd` → 输出实时出现（SSE） | 顶部状态「实时」，转录行 `[USER] $ pwd` 之后是输出路径 | ✅ |
| 11 | `sleep 30` 期间 Stop → `sleep 30` 期间 Force Stop | Stop 后 `[exit -15]`；Force Stop 后 `[exit -9]` | ✅ |
| 12 | 观看与运行的解耦：杀掉 App 或返回列表再进会话 | 历史输出仍在；服务端命令不受"关页面"影响（设计如此，§11） | ✅ |

> 第 6–7 步的 3 条提案是 Agent 真实产出、未经过人工 code review；按需批准 / 应用 / 回滚，**不要 git 提交**。

## 构建命令

```bash
cd apps/mobile-controller
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

依赖版本（快照日期 2026-10-05）：

| 组件 | 版本 |
|---|---|
| Android Gradle Plugin | 8.7.2 |
| Gradle（wrapper） | 8.9 |
| Kotlin | 2.0.21（含 `org.jetbrains.kotlin.plugin.compose`） |
| compileSdk / targetSdk / minSdk | 34 / 34 / 26 |
| Compose BOM | 2024.02.01 |
| OkHttp + okhttp-sse | 4.12.0 |
| kotlinx-serialization-json | 1.6.3 |
| kotlinx-coroutines-android | 1.8.1 |

仓库根目录的 `local.properties`（不入库）写明 `sdk.dir`，新机器改这一行即可。

## 已知限制

- **当前构建目标为 debug 包**：个人内测可用；正式分发需要 release 签名与进一步加固（红线：密钥不入库）。
- **v0.1.2 稳定性修复**：补齐可复现的 Android data/API 源码层；保存 Token 时使用 Android Keystore + AES-GCM 加密；测试连接使用当前输入而非旧配置；启动配置读取失败不再直接崩溃；Terminal SSE 断线支持退避重连与事件补偿；任务轮询避免过期请求覆盖新状态，并在后台暂停轮询；终端停止按钮按会话状态禁用。
- **明文 HTTP 已禁用**（`AndroidManifest` 的 `usesCleartextTraffic="false"`）：连局域网 `http://` 实例需要改这一行（下一批的家庭 Windows 场景大概率要改）。
- **模型凭据依赖**：`/opt/ops/.env` 的 `DEEPSEEK_API_KEY` 被复用；缺它会以 `MISSING_CREDENTIAL` 失败。
- **移动端不能编辑需求确认**：设计选择，避免在小屏上做"会真正落到文件里"的修改；改确认走桌面端。
- **任务级对话无 SSE**：仅轮询（3 秒）；Agent 的 Run 事件流仅在桌面端实时推送。
- **iOS 未做**：交付范围只有 Android。
- **家庭 Windows 穿透未做**：本批只能连公网可达的实例；下一批会加内网穿透。

# Flux IDE 插件系统与 Code-OSS 底座迁移方案（路线 A）

> 用途：记录「换 Code-OSS 底座」这一决策的依据、目标架构、迁移分期、插件系统与 VS Code 插件一键导入的实现口径。
>
> 状态：**待确认**。方案确认后按 §6 分期实施；实施期间不改动本文已冻结的架构结论，除非重新走一次决策记录。
>
> 关联：[AI_COMPLETION_SPEC.md](AI_COMPLETION_SPEC.md)（补全作为第一个自研扩展）。

## 0. 决策记录

| 项 | 内容 |
| --- | --- |
| 决策 | IDE 底座从「自研（Web Monaco + 桌面 AvaloniaEdit）」更换为 **Code-OSS fork** |
| 日期 | 2026-10-30 |
| 备选 | A：fork Code-OSS / B：保留现 IDE，只导入声明式插件 / C：接 `monaco-vscode-api` 跑 Web 扩展宿主 |
| 选择 | A |
| 形态 | A1 双进程（Avalonia 壳 + Code-OSS 独立窗口进程）；如改 A2 需重走一次决策 |
| 平台 | **Windows + Linux 先行**；macOS 构建、签名、公证后置，不在本轮范围 |
| 主因 | 生态兼容性：VS Code 扩展生态是现成的，补全、语言服务、LSP、主题、键位一次性拿到 |
| 已知代价 | 编辑器层两套自研实现作废；桌面壳与 IDE 的双进程问题；构建链变重；插件无沙箱带来的信任问题 |

被否决的 B / C 不删档，理由留在此处，避免以后重复讨论：

- **B 不够用**：只能搬主题、图标、片段、键位、语法这类声明式内容。ESLint / Prettier / Python / Pylance / GitLens / Docker / 远程开发全都搬不过来——它们需要真实运行时。用户要的「一键导入 VS Code 插件」如果只能导主题，等于没做。
- **C 不彻底**：`@codingame/monaco-vscode-api` 能在浏览器里跑**带 `browser` 字段**的扩展，但 Node 扩展仍然跑不了；而 VS Code 生态里绝大多数有价值的扩展是 Node 扩展。且它是一层 API 模拟，版本追平与 bug 处理长期欠债。
- **内嵌 VS Code Server 走不通**：官方 FAQ 明确 VS Code Server 不可被其它客户端独立安装使用，属于授权红线。

## 1. 现状盘点（迁移要动的东西）

| 资产 | 位置 | 规模 | A 之后 |
| --- | --- | --- | --- |
| Web IDE 页面 | [IdePage.tsx](../apps/web-dashboard/src/pages/IdePage.tsx) | 2958 行 | 编辑器部分退役；右栏 Agent、变更审核、终端等交互迁为扩展视图或 webview |
| Monaco 编辑器封装 | [MonacoCodeArea.tsx](../apps/web-dashboard/src/components/ide/MonacoCodeArea.tsx) 等 | 562 行 + LSP 三件套 | 退役 |
| 桌面 IDE 视图 | [IdeView.axaml](../apps/desktop-avalonia/Views/IdeView.axaml) / [IdeViewModel.cs](../apps/desktop-avalonia/ViewModels/IdeViewModel.cs) | 761 + 1843 行 | 退役 |
| 桌面 TextMate / LSP | [TextMateService.cs](../apps/desktop-avalonia/Services/TextMateService.cs) / [LspClient.cs](../apps/desktop-avalonia/Services/LspClient.cs) | 559 + 948 行 | 退役，改由扩展内语言客户端承担 |
| 桌面壳（账号/项目/Solo/计费/设计/设置） | `apps/desktop-avalonia` 其余 | — | **保留** |
| 后端能力面 | `backend/flux/api/v1/*`、DSH 运行时、ApplyEngine | — | **保留**，仅新增接口 |
| 已有能力导入 | [capability_import/scanners.py](../backend/flux/core/capability_import/scanners.py)、[SkillsPage.tsx](../apps/web-dashboard/src/pages/SkillsPage.tsx)、[SoloSidePanel.tsx](../apps/web-dashboard/src/components/side/SoloSidePanel.tsx#L295-L330) | — | **保留**，与插件系统分层共存（§5.4） |

结论：作废的是「编辑器层」（约 4000 行自研渲染/补全/LSP），保留的是「平台层」——这正是换底座想达到的效果。

## 2. 目标架构

### 2.1 形态选择

| 形态 | 说明 | 结论 |
| --- | --- | --- |
| A1 双进程 | Avalonia 壳保留，IDE 以 Code-OSS 独立窗口进程运行；两侧通过本地 HTTP（8801）与自定义 URI 协议通信 | **推荐** |
| A2 单进程 | 整体换成 Electron（Code-OSS），Avalonia 的页面改写为 webview 视图 | 成本高一档，二期再看 |

推荐 A1 的理由：壳里的账号、项目、Solo、计费、设计工作室已经可用，重写等于把整个客户端推倒重来；而 IDE 天然可以是一个独立窗口。

### 2.2 A1 架构

```text
┌──────────────────────────┐        ┌───────────────────────────────┐
│  Flux 壳 (Avalonia)       │        │  Flux IDE (Code-OSS fork)      │
│  账号 / 项目 / Solo        │        │  编辑器 / 终端 / Git / 搜索     │
│  计费 / 设计 / 设置        │        │  Extension Host               │
│                          │        │   ├─ 内置: flux.ai-completion  │
│  「打开项目」──启动进程──▶ │        │   ├─ 内置: flux.agent (右栏)   │
│  ◀── 状态/事件 (URL) ──── │        │   └─ 导入: 第三方扩展           │
└──────────────────────────┘        └──────────────┬────────────────┘
           │                                        │
           └──────────────┬─────────────────────────┘
                          ▼
              Flux 后端 127.0.0.1:8801
              （模型网关 / DSH / ApplyEngine / 索引 / 密钥库）
```

约定：

- 壳传给 IDE 的只有：项目根路径、后端地址、短期访问令牌、用户偏好文件路径（CLI 参数 + 环境变量）。
- IDE 回传壳的只有：窗口状态、当前文件、错误上报开关（走自定义协议 `flux://`，不建 WebSocket 数据面）。
- **所有业务数据仍走后端**，两个前端不直接互调。

### 2.3 品牌与产品化

- `product.json`：`nameShort=Flux`、`nameLong=Flux IDE`、`applicationName=flux`、`dataFolderName=.flux-ide`、`urlProtocol=flux`。
- 关闭微软遥测与崩溃上报；更新服务指向自建地址或直接关闭（跟随壳的更新节奏）。
- 默认扩展目录独立：`~/.flux/extensions`（**不覆盖** `~/.vscode/extensions`；Windows 为 `%USERPROFILE%\.flux\extensions`）。
- 默认用户数据目录独立：`~/.flux/ide-data`（Windows 为 `%USERPROFILE%\.flux\ide-data`，不走 `%APPDATA%`，便于与壳的便携部署保持一致）。

### 2.4 平台范围

| 平台 | 本轮 | 产物 | 说明 |
| --- | --- | --- | --- |
| Linux | 做 | AppImage + deb | 构建机即 Linux，主链路先在这里跑通 |
| Windows | 做 | 安装版 exe（NSIS） | 交叉构建或另备构建机，见 §6 风险 |
| macOS | 不做 | — | 签名/公证/arm64 分发成本高，等功能稳定后单独排期 |

壳（Avalonia）本身三平台都已具备，平台差异集中在 Code-OSS 的构建与打包环节；`product.json` 与扩展目录规则需按平台分别校验。

## 3. 插件系统

### 3.1 插件格式

直接采用 VS Code 扩展格式，**不自造 manifest**：

```text
<extension-dir>/
├── package.json        # publisher / name / version / engines.vscode / main|browser / contributes
├── extension.js        # 或 dist/
└── ...
```

好处：现有 VS Code 扩展零改造可装；`flux.ai-completion` 同时也能在真 VS Code 里调试。

### 3.2 扩展清单与启用状态

```http
GET  /api/v1/ide/extensions            → [{ id, name, version, source: "builtin|imported", enabled, trust, compat }]
POST /api/v1/ide/extensions/{id}/enable | /disable
```

- 状态真源在后端，IDE 启动时拉取并同步到扩展目录的启用清单。
- 内置扩展随安装包分发，不允许卸载，只允许禁用（`flux.ai-completion` / `flux.agent`）。

### 3.3 信任模型（必须做）

Code-OSS 的 Node 扩展**没有沙箱**，装进来就等于在本机执行任意代码。因此：

1. 新导入的扩展默认 `disabled`，用户在清单里显式「启用」后才加载。
2. 启用弹确认框，展示 publisher、版本、来源（本地导入 / 内置）、会申请的权限类目（`contributes` 里出现的项）。
3. 提供「受限模式」：打开陌生仓库时默认只加载内置扩展。
4. 导入过程**只复制、不执行**；扫描阶段只读 `package.json`，绝不 `require` 扩展代码。

### 3.4 插件生命周期

安装（内置/导入）→ 待信任 → 启用 → 加载 → 运行 → 禁用/卸载 → 残留清理。每一步都要有对应的状态可查，失败要能回到上一步。

## 4. VS Code 插件一键导入

### 4.1 流程

```text
扫描本机 VS Code
  → 读 ~/.vscode/extensions/extensions.json（启用列表 + 版本 + 位置）
  → 逐个解析 package.json（只读）
  → 兼容性分级 + 风险标记
  → 展示清单（默认全选「可直接运行」项）
  → 用户勾选 → 复制到 ~/.flux/extensions
  → 重建扩展缓存 → 重启 Extension Host
  → 失败项列出原因，可一键回滚（删除本次复制目录）
```

### 4.2 兼容性分级

| 级别 | 判定依据 | 预期 | 界面文案 |
| --- | --- | --- | --- |
| L0 声明式 | 只有 `contributes`（themes / iconThemes / snippets / grammars / keybindings / languages），无 `main` | 可直接运行 | 可直接导入 |
| L1 纯 API | 有 `main` 或 `browser`，依赖仅为 `vscode` 与纯 JS 库 | 大概率可运行，需实测 | 可导入（建议验证） |
| L2 原生依赖 | 含 `*.node` / `binding.gyp` / 依赖外部二进制（如 `@vscode/ripgrep` 以外的本地程序、语言服务器） | 可能失败 | 可导入，可能无法工作 |
| L3 专有服务 | publisher 为 `ms-vscode` / `github` 且功能依赖微软或 GitHub 账号（Copilot、Remote-SSH、Live Share、Codespaces） | 不可用 | 依赖官方服务，暂不支持 |

判定信息全部来自 `package.json` 的静态字段 + 文件存在性检查，不猜测、不联网。

### 4.3 合规边界（硬约束）

- **不代下载**：只导入用户本机**已安装**的扩展目录内容，不请求微软 Marketplace。
- **不接微软 Marketplace**：Code-OSS 默认不带市场；在线市场只能接 **Open VSX**。微软 Marketplace 条款限制其内容用于 Visual Studio 系列产品，第三方 fork 接入有明确合规风险。
- 导入页需一句话说明来源与风险，用户确认后才执行。

> 以上合规判断需法务复核后再对外发布，文档层面先按最保守口径执行。

### 4.4 与现有「能力导入」的关系

两套体系并存，不合并：

| 体系 | 对象 | 入口 | 运行位置 |
| --- | --- | --- | --- |
| 能力导入（已有） | Agent 档案 / Skill 能力包 / Connector | [SkillsPage.tsx](../apps/web-dashboard/src/pages/SkillsPage.tsx)、插件市场卡片 | Flux 后端 |
| 插件系统（本方案） | VS Code 扩展 | IDE 内扩展视图 + 壳的设置页 | IDE Extension Host |

共用的是交互范式：只读扫描 → 分级 → 用户勾选 → 导入 → 可回滚。扫描器的安全约定（绝不改用户环境、只给字段路径不给值）继续沿用 [scanners.py](../backend/flux/core/capability_import/scanners.py) 的口径。

## 5. 迁移分期

| 阶段 | 目标 | 完成判据 | 可回退 |
| --- | --- | --- | --- |
| M0 | 本文件确认 | 用户签字 | — |
| M1 | Code-OSS 能在 Linux 与 Windows 构建、启动、改品牌 | Linux 出 AppImage/deb，Windows 出 NSIS exe；打开展示项目，编辑保存正常 | 是（旧客户端不动） |
| M2 | 与后端打通 | IDE 内能登录、打开项目、调用模型；右栏 Agent 可用 | 是 |
| M3 | 插件系统 | 内置扩展可装可禁；扩展清单页可用 | 是 |
| M4 | VS Code 插件导入 | 导入 3 个 L0 扩展并生效；1 个 L1 扩展实测通过 | 是 |
| M5 | 退役旧编辑器 | Web Monaco 与 Avalonia 编辑器下线；壳的 IDE 入口改为拉起新进程 | 否（打 tag） |

M5 之前，旧客户端保持可用；M5 用 tag 冻结。

## 6. 风险与对策

| 风险 | 影响 | 对策 |
| --- | --- | --- |
| Code-OSS 构建链重（Node/yarn/gulp/Electron，国内网络） | M1 卡住 | 先做一次纯构建验证，锁定镜像与依赖缓存；CI 里固化 |
| Windows 包无法在 Linux 原生构建 | M1 延期 | 优先用 electron-builder + wine 交叉打包；失败则另备一台 Windows 构建机（已列入预算） |
| 双进程窗口割裂（IDE 独立窗口） | 体验下降 | 壳负责窗口生命周期与单实例；IDE 崩溃可被壳感知并重启 |
| 扩展无沙箱 | 安全 | §3.3 信任模型；导入默认禁用 |
| 官方 Marketplace 不可用 | 生态缺插件 | 接 Open VSX；本地导入兜底 |
| 扩展 API 版本差异 | 部分扩展报 `engines.vscode` 不满足 | `product.json` 里声明较高 `vscodeVersion`；清单里明确展示不兼容原因 |
| 自研能力（DSH 右栏、变更审核）在 IDE 内体验不如原生 | 用户流失 | 以 webview 视图形式内嵌现有 Web 页面，先可用再优化 |
| 一次性切换导致回退困难 | 交付风险 | 严格按 M1–M4 灰度，M5 才下线旧路径 |

## 7. 待确认

1. ~~形态确认~~ **已定 A1 双进程**（见 §0）。若改 A2，需在 M1 启动前提出。
2. ~~平台优先级~~ **已定：Windows + Linux 先行，macOS 后置**（见 §2.4）。~~剩余子问题~~ **已定：先让 Linux 跑通，再补 Windows 打包。**
3. 在线市场是否接 Open VSX？（不接则只有内置 + 本地导入）
4. 壳里的「插件市场」卡片是否改名为「扩展」，并同时列出「Flux 能力」与「VS Code 扩展」两个分区？
5. 内置扩展的最小集：`flux.ai-completion` + `flux.agent`（右栏）是否够？Git / 终端用 Code-OSS 自带的即可。
6. M5 下线旧 Web IDE 后，浏览器访问入口是保留只读视图，还是直接跳转到下载桌面端？

## 8. 不做的事

- 不自造插件 manifest / 不自建插件运行时。
- 不内嵌 VS Code Server，不接微软 Marketplace。
- 不修改用户本机 `~/.vscode` 目录（只读）。
- 不在 M1~M4 期间改动旧编辑器代码（避免双份维护）。

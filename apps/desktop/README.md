# Flux 桌面端（Electron）

设计文档：[`doc/PERSONAL_MVP_RELEASE_DESIGN.md`](../../doc/PERSONAL_MVP_RELEASE_DESIGN.md) §10（桌面端打包）
与 §11（Windows 跨平台）。本目录是 Electron 外壳 + PyInstaller 后端 sidecar。

结构：

```text
apps/desktop/
├── package.json                 # Electron 应用与 electron-builder 配置
├── src/main.js                  # 主进程：拉起后端、静态服务器 + /api 反代、主/终端窗口
├── src/preload.js               # 最小 preload（contextIsolation 保持 true）
├── backend/serve.py             # 冻结后的后端入口：alembic upgrade head → uvicorn
├── backend/flux-backend.spec    # PyInstaller onedir 规格
├── requirements-build.txt       # 构建期依赖（仅 pyinstaller，不动 backend/requirements.txt）
└── README.md
```

运行时由主进程负责：选两个空闲端口 → 启动后端 sidecar（PyInstaller 产物，开发态用 `.venv` 的 python）
→ 轮询 `/api/v1/health` 就绪 → 起静态服务器并把 `/api` 反代到后端 → 打开窗口。
前端保持同源相对路径 `/api/v1`，无需任何改动。

## 下载安装包（现成产物）

三个平台产物都在 GitHub Releases：<https://github.com/qiuli55/flux/releases>

| 平台 | 文件 | 说明 |
|---|---|---|
| Windows | `Flux-Setup-0.1.0-x64.exe` | NSIS 安装程序，由 CI 的 `windows-latest` runner 构建；双击安装，向导可选目录 |
| Linux | `Flux-0.1.0-x86_64.AppImage` | `chmod +x` 后直接运行 |
| Linux | `Flux-0.1.0-amd64.deb` | `sudo apt install ./Flux-0.1.0-amd64.deb` |

应用版本为 `0.1.0`；Release 的 tag（如 `v0.1.1`）表示构建批次，用于区分用了哪一版源码。
安装后数据落在 `~/.flux`（见下节），**卸载不删数据**。

Windows 安装包目前**尚未做真机验收**——装完请按「Windows 真机验收清单」逐步确认；
每个 Release 的说明里都写了该批次的 SHA256 与已知限制。

## 依赖版本（快照日期 2026-10-05）

| 组件 | 版本 | 说明 |
|---|---|---|
| electron | 44.5.1 | 联网核实（`npm view electron version`） |
| electron-builder | 26.15.3 | 联网核实（`npm view electron-builder version`） |
| pyinstaller | 6.22.3 | 见 `backend/requirements-build.txt` |

> **Node 版本要求**：electron-builder 26.x 间接依赖 `@noble/hashes` v2（ESM-only），
> 需要 Node 支持 `require(ESM)`，即 **Node ≥ 22.12（推荐）或 ≥ 20.19**。
> 本机构建时系统 Node 为 20.18.1，会报 `ERR_REQUIRE_ESM`；改用 Node 22.23.3 后构建通过。
> `scripts/build_desktop.sh` 会先校验 Node 版本并给出明确报错。
> 前端 CI 仍用 Node 20（不受影响），桌面打包 CI 用 Node 22。

## 开发态怎么跑

前提：仓库根有 `.venv`（`make setup`），前端依赖已装、且已构建或可 dev 运行。

```bash
# 1) 准备数据库（开发态主进程直接起 uvicorn，不代跑迁移）
make migrate

# 2) 构建前端静态产物（主进程从 apps/web-dashboard/dist 读取）
cd apps/web-dashboard && npm ci && npm run build && cd ../..

# 3) 安装桌面端依赖并启动（会自动起后端 + 界面服务 + 窗口）
cd apps/desktop && npm install && npm start
```

主进程日志会打印数据目录、后端端口、界面服务地址。菜单「窗口 → 打开 Agent Terminal」
（快捷键 `Ctrl/Cmd+Shift+T`）会打开只渲染终端的前端窗口（`?fluxWindow=terminal`）。

## 打包怎么跑

一条命令（Linux）：构建前端 → PyInstaller 打后端 → electron-builder 打 `.AppImage` + `.deb`：

```bash
bash scripts/build_desktop.sh
# 已有前端 dist 时默认跳过前端构建；强制重建：FLUX_DESKTOP_REBUILD_WEB=1 bash scripts/build_desktop.sh
```

产物落在 `apps/desktop/release/`，脚本结尾打印真实文件名与大小。

只做某一步：

```bash
# 后端 sidecar
cd apps/desktop/backend && ../../../.venv/bin/pyinstaller --noconfirm --clean flux-backend.spec

# Electron 安装包（Linux）
cd apps/desktop && npm install && npx electron-builder --linux AppImage deb --publish never
```

> `--publish never` 必须带：electron-builder 在 CI 环境会尝试隐式发布并因缺少发布目标而报错。

Windows 安装包（NSIS）**无法在 Linux 上构建**（需要 wine），交给 CI 的 `windows-latest` runner：
`.github/workflows/ci.yml` 的 `desktop-windows` job（`workflow_dispatch` 或 tag `v*` 触发，
产出 `apps/desktop/release/Flux-Setup-0.1.0-x64.exe` 并作为 artifact 上传）。

## 数据目录约定（`~/.flux`）

主进程与 sidecar 使用同一套约定，可用环境变量 `FLUX_DATA_DIR` 覆盖：

```text
~/.flux/
├── flux.db          # SQLite 数据库（FLUX_DATABASE_URL=sqlite+aiosqlite:///~/.flux/flux.db）
├── workspace/       # Agent 改动落盘根目录（FLUX_WORKSPACE_ROOT）
├── backups/         # Apply 备份目录
├── dsh-home/        # DSH Agent Runtime 落盘（DSH_HOME，DSH 启用时才写入）
└── dsh-ws/          # DSH 工作目录（DSH_WORKSPACE）
```

**卸载不删数据、重装可继续使用**：数据全部在 `~/.flux`，不在安装目录内；
NSIS 卸载项也显式关闭了「删除应用数据」（`deleteAppDataOnUninstall: false`）。

## Linux 产物（本机实测）

构建环境：Linux x86_64（Ubuntu，内核 5.15）；构建用 Node 22.23.3、venv Python 3.10.12。
构建命令：`PATH=<node22>/bin:$PATH bash scripts/build_desktop.sh`（前端 dist 已存在故跳过前端重建）。
构建脚本一次跑通，最后打印：

```text
=== 产物清单（apps/desktop/release）===
-rw-r--r-- 118M  apps/desktop/release/Flux-0.1.0-amd64.deb
-rwxr-xr-x 146M  apps/desktop/release/Flux-0.1.0-x86_64.AppImage
```

| 产物 | 真实大小 | 说明 |
|---|---|---|
| `apps/desktop/release/Flux-0.1.0-x86_64.AppImage` | 146 MB | Linux 免安装单文件 |
| `apps/desktop/release/Flux-0.1.0-amd64.deb` | 118 MB | Debian/Ubuntu 安装包 |

deb 元数据（`dpkg-deb -f`）：`Package: flux-desktop / Version: 0.1.0 / Maintainer: 孟令昕 <2709264162@qq.com>`。

后端 sidecar 单独实测（不依赖 Electron）：

```bash
apps/desktop/backend/dist/flux-backend/flux-backend --port 8799   # 约 64 MB onedir
# → [flux-backend] alembic upgrade head: OK
# → Uvicorn running on http://127.0.0.1:8799
curl -s http://127.0.0.1:8799/api/v1/health
# → {"success":true,"code":"ok","message":"","data":{"status":"ok","app":"Flux","env":"local"},"metadata":{}}
```

打包后 AppImage 端到端实测（本机为无头服务器，用 Xvfb 起虚拟显示）：

```bash
FLUX_DATA_DIR=/tmp/flux-app-test \
  xvfb-run -a apps/desktop/release/Flux-0.1.0-x86_64.AppImage \
  --appimage-extract-and-run --no-sandbox --disable-gpu
```

实测结果（日志 + 探针）：

```text
[flux] 数据目录：/tmp/flux-app-test
[flux] 前端静态根：<解包目录>/resources/web
[flux] 后端就绪：http://127.0.0.1:38843      # 启动时探测的空闲端口
[flux] 界面服务：http://127.0.0.1:43911      # 主窗口加载的地址（静态服务 + /api 反代）
```

- 后端直连 `/api/v1/health` → `success:true`；经界面服务 `/api/v1/health` 反代 → `success:true`（同源代理生效）；
- `GET http://127.0.0.1:43911/index.html` → HTTP 200（前端静态资源已打进 `resources/web`）；
- `GET .../api/v1/workspace/changes`（走反代查库）→ HTTP 200；
- `~/.flux`（此处为 `FLUX_DATA_DIR`）下生成 `flux.db`、`workspace/`、`backups/`（见上文数据目录约定）；
- 迁移跑到 head：`alembic_version = f2a7c4d9e1b3`。

> 备注：本机实测时工作区 head 已是 `f2a7c4d9e1b3`（`add_change_kind`，与我构建并行合入的
> 未跟踪迁移，`down_revision = d5e8b1c3a7f2`）。sidecar 打包的是构建当刻的 `backend/migrations`
> （共 16 个版本脚本），所以换机器重新构建时会自动跟随当时的 head。

## Windows 产物（CI 构建）

见上文「打包怎么跑」。CI 绿色与真机验收是 Windows 发行的硬性前置（设计文档 §11.5）。

### Windows 真机验收清单

在目标家庭电脑上，按顺序逐条执行；每步写清「打开什么 / 点什么 / 看到什么」。
先决条件：Windows 10/11 x64；已从 CI artifact 下载 `Flux-Setup-0.1.0-x64.exe`；
准备好一个用于做实验的本地 Git 仓库目录（下面记为 `D:\flux-demo`）。

1. **安装**
   - 双击 `Flux-Setup-0.1.0-x64.exe` → 安装向导 → 选择安装目录（默认即可）→ 安装 → 完成。
   - 看到：桌面出现 `Flux` 图标；`%USERPROFILE%\.flux\` 尚未生成（首次启动才生成）。

2. **启动**
   - 双击桌面 `Flux` 图标。
   - 看到：主窗口出现（标题 Flux），无「Flux 启动失败」弹窗；随后
     `%USERPROFILE%\.flux\{flux.db, workspace\, backups\}` 生成。
   - 若弹「Flux 启动失败」，把弹窗里附的后端日志贴回 issue（脚本会给出可读错误，不静默）。

3. **建项目**
   - 主窗口 → IDE → 登记项目，仓库填写 `D:\flux-demo`。点击「扫描」。
   - 看到：文件树出现 `D:\flux-demo` 下的文件；项目出现在项目列表。

4. **Agent 任务**
   - 切到 Solo → 新建任务（例如「把 README 里的 TODO 改成 DONE」）→ 确认需求卡 → 开始执行。
   - 看到：任务进入执行中；菜单「窗口 → 打开 Agent Terminal」（或 `Ctrl+Shift+T`）弹出独立终端窗口，
     `[AI]` 前缀的命令与实时输出滚动出现。
   - 关键：**关闭该独立终端窗口**，主窗口任务仍在继续（终端窗口可单独关闭，不停 Agent）。

5. **Proposal**
   - 任务产出改动 → 主窗口 Proposal/变更列表出现待审提案。
   - 看到：提案含文件 diff；状态为 pending。

6. **Review → Apply**
   - 在提案上点「批准」再点「应用（Apply）」。
   - 看到：变更落盘到 `D:\flux-demo`；磁盘文件内容与 diff 一致；备份写入 `~\.flux\backups\`。

7. **Test**
   - Apply 后若配置了测试命令，看到测试结果回填到任务/变更；失败时有可见错误。

8. **Git**
   - 切到 Git 面板 → 看到 `git status` 列出已 applied 的改动 → 填 commit message → 提交。
   - 看到：提交成功，工作区变干净；`git log` 有该提交。

9. **Cancel / 超时**
   - 新起一个会长时间运行的任务 → 在 Agent Terminal 点 **Stop**。
   - 看到：终端状态停住、命令结束；主窗口任务最终状态为 `cancelled`；
     用任务管理器确认没有残留的子进程（进程树被清理）。
   - Force Stop：对无法正常退出的命令，Stop 超时后点 **Force Stop**，看到进程被强制结束、Run 终态正确。

10. **重启恢复**
    - 关闭 Flux（主窗口关闭即退出）→ 重新双击启动。
    - 看到：不会出现永久 `running` 的僵尸任务/终端；数据库仍指向 `~\.flux\flux.db`，之前的项目/提交记录还在。

11. **卸载 / 重装行为（设计文档 §10）**
    - 「设置 → 应用」卸载 Flux。
    - 看到：`%USERPROFILE%\.flux\` **仍然存在**（数据未被删除）。
    - 重新安装并启动：项目、任务、Agent 记录、提交历史全部恢复。

## 已知限制

- **macOS 未做**：按设计文档 §10，仅交付 Linux（AppImage/deb）与 Windows（NSIS）。
- **Windows 需真机验收**：本文的 Windows 清单必须在实际 Windows 机器上逐条跑通；
  设计文档 §11.2 的四处 POSIX 依赖需由后端平台原语层（§11.3）落地后才可能全绿。
- **UI 图标**：当前使用 electron-builder 默认 Electron 图标，未定制应用图标。
- **Linux 构建 Node 版本**：见上文「依赖版本」——需 Node ≥ 20.19（推荐 22.12+）。

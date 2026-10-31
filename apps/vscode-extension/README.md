# Flux for VS Code / VSCodium

把 Flux 平台接进编辑器侧边栏：

- **项目视图**：拉取 `GET /api/v1/projects`
- **任务视图**：拉取 `GET /api/v1/tasks`，按状态显示图标
- **任务面板**：查看消息、发送消息、开始 / 取消任务，并订阅 `GET /api/v1/tasks/{id}/stream` 的 SSE 事件
- **提案视图**：拉取 `GET /api/v1/workspace/changes`，按 待审 / 已接受 / 已应用 三态展示 Virtual Workspace 提案
- **原生差异审查**：点击提案在 VS Code diff 视图中查看改前 / 改后内容（虚拟文档，不落临时文件）
- **审查动作**：接受、拒绝（可填原因）、应用（真实写入磁盘前弹窗确认）、回滚
- **状态栏**：显示与 Flux 后端的连接状态

## 配置

| 配置项 | 说明 | 默认 |
| --- | --- | --- |
| `flux.serverUrl` | Flux 后端地址（不带 `/api/v1`） | `http://127.0.0.1:8000` |
| `flux.token` | Bearer 令牌，对应服务端 `FLUX_AUTH_TOKEN` | 空 |
| `flux.projectId` | 默认项目 ID | 空（用第一个项目） |

## 命令

- `Flux: 新建任务`
- `Flux: 刷新`
- `Flux: 设置服务器地址`
- `Flux: 检查连接`
- `Flux: 查看提案差异`
- `Flux: 接受提案`
- `Flux: 拒绝提案`
- `Flux: 应用提案`（写入磁盘前有确认弹窗）
- `Flux: 回滚已应用变更`

## 提案流转

```
pending ──接受──▶ accepted ──应用──▶ applied ──回滚──▶ rolled_back
   └────拒绝────▶ rejected
```

在「提案」视图中：待审条目有内联的接受 / 拒绝按钮，已接受条目有内联的应用按钮，
已应用条目有内联的回滚按钮；`Ctrl/Cmd + 点击` 可多选后批量操作。
后端 `expired` 提案无法应用（服务端返回 409），需由 Agent 重新提案。

## 开发

```bash
npm install
npm run build        # 产出 dist/extension.js
npm run package      # 产出 flux-vscode-0.1.0.vsix
```

安装到 VSCodium：

```bash
codium --install-extension flux-vscode-0.1.0.vsix --force
```

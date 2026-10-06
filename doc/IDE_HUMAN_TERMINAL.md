# Flux IDE 人用 Terminal

## 定位

IDE 底部 Panel 的「终端」是用户自己的开发终端，与 Agent Terminal 完全隔离。

底部 Panel 结构：

- AI 任务流
- 变更提案
- 终端（Human Terminal）
- Git

## 会话隔离

- Human Terminal 创建 `run_id = null` 的终端会话。
- Agent Terminal 使用 Agent Run 关联的会话。
- Human Terminal 不复用 Agent 会话，也不会因为 Agent 执行命令而污染用户终端。

## 当前能力

- 底部集成 Panel
- 多终端 Tab
- 新建终端
- 工作区路径显示
- SSE 实时输出
- 命令历史滚动区
- 清空当前显示
- 停止当前终端
- Enter 执行命令 / Esc 清空输入
- 与现有 Agent Terminal 共用后端终端事件模型，但会话来源严格隔离

## VS Code 对标范围

当前 UI 对标 VS Code Integrated Terminal 的核心交互：底部 Panel、终端 Tab、`+` 新建、独立会话、滚动输出、停止与关闭。

当前后端协议仍是「命令 POST + SSE 输出事件」，还不是完整 PTY/WebSocket，因此暂不宣称支持 vim、top、ssh 交互、Ctrl+C 原始按键、shell readline、全屏 TUI 等能力。

下一阶段如果要做到真正 VS Code 级终端，应将 Human Terminal 后端升级为持久 PTY 会话，并增加：

1. WebSocket 双向字节流
2. terminal resize（cols / rows）
3. 原始键盘输入与 Ctrl+C / Ctrl+D / Tab / 方向键
4. xterm.js 前端终端渲染
5. shell integration（cwd、command、exit status）
6. terminal split
7. persistent session / reconnect
8. 工作区目录级 Terminal Here

这样可以保持现在的 UI 架构不变，只替换 Human Terminal 的传输层。

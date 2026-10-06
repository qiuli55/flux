# Flux Human Terminal：真实 PTY

## 目标

Flux IDE 的 Human Terminal 不再使用“提交一条命令 → 等待结果”的命令执行模型，而是使用真正的 OS pseudo-terminal（PTY）。浏览器终端通过 WebSocket 与后端 PTY 桥接。

这意味着 shell 本身就是长期运行的交互进程，输入和输出都是终端字节流，而不是结构化的命令/结果列表。

## 与 Agent Terminal 的边界

Human Terminal：

- 用户本人操作
- 一个会话对应一个交互 shell
- PTY 输入/输出
- ANSI escape sequences 保留
- 支持交互式 REPL / TUI
- 支持 resize
- UI 断线不会自动杀掉 shell

Agent Terminal：

- Agent 执行命令
- 命令与输出进入现有 TerminalEvent/SSE 模型
- 继续保留 Flux 的观察、审计和 Stop 语义
- 不复用 Human Terminal session

## 后端接口

### 创建

`POST /api/v1/terminal/pty/sessions`

返回现有 `TerminalSession`，工作目录仍由 `FLUX_WORKSPACE_ROOT` 决定。

### WebSocket

`WS /api/v1/terminal/pty/sessions/{session_id}/ws`

客户端 → 服务端：

```json
{"type":"input","data":"ls\r"}
{"type":"resize","cols":120,"rows":32}
{"type":"stop","force":false}
```

服务端 → 客户端：

```json
{"type":"output","data":"..."}
{"type":"closed"}
{"type":"error","message":"..."}
```

## PTY 实现

当前后端使用 Python Unix PTY：

- `pty.fork()` 创建 shell
- 独立进程组
- `os.write()` 接收用户输入
- `os.read()` 读取 shell 输出
- `TIOCSWINSZ` / `tcsetwinsize()` 同步终端尺寸
- `SIGTERM` / `SIGKILL` 停止整个进程组

默认 shell 使用 `$SHELL`，不存在时回退到 `/bin/bash`；环境变量 `TERM=xterm-256color`。

## 当前平台范围

本实现首先覆盖 Linux/macOS。Windows 桌面版本不能直接依赖 Python `pty` 模块，需要增加 Windows ConPTY adapter。不要在 Windows 构建上把 Unix PTY 当成已经支持。

## 前端下一步

Human Terminal UI 应使用 xterm.js，并把：

`terminal.onData → WebSocket input`

以及：

`WebSocket output → terminal.write`

连接起来；使用 fit addon 在 IDE 面板尺寸变化时发送新的 `cols/rows`。这样 Flux 才能达到 VS Code Integrated Terminal 的交互级别。

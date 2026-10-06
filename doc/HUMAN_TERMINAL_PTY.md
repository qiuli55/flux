# Flux Human Terminal：真实 PTY

## 目标

Flux IDE 的 Human Terminal 不使用“提交一条命令 → 等待结果”的命令执行模型，而是使用真正的 OS pseudo-terminal（PTY）。浏览器终端通过 WebSocket 与后端 PTY 桥接。

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
- websocket 重连不会创建第二个 PTY reader

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
- shell 使用独立 PTY/会话环境
- `os.write()` 接收用户输入
- 单一后台 reader 读取 shell 输出，再广播给 websocket subscribers
- 最近 256 KiB 输出保留在内存中，用于浏览器短暂断线后的恢复
- `TIOCSWINSZ` / `tcsetwinsize()` 同步终端尺寸
- `SIGTERM` → 1 秒宽限 → `SIGKILL` 停止进程组
- `waitpid()` 回收子进程，避免 zombie
- Flux 进程退出时统一 shutdown/reap 所有 Human PTY

默认 shell 使用 `$SHELL`，不存在时回退到 `/bin/bash`；环境变量 `TERM=xterm-256color`。

## WebSocket 生命周期

Human PTY 与 WebSocket 生命周期故意分离：

```text
创建 Terminal
    ↓
PTY shell 持续运行
    ↓
WebSocket 连接 / 重连
    ↓
同一个 PTY reader + 多个 subscriber
    ↓
Terminal stop / shell exit
    ↓
关闭 PTY + waitpid + 标记 session stopped
```

这样浏览器刷新或网络抖动不会意外杀掉用户正在运行的 `vim`、`ssh`、开发服务器等进程，也不会出现两个 reader 抢同一个 PTY 输出的问题。

## WebSocket 安全边界

Human PTY 是服务器 shell 能力，因此不能接受任意跨站浏览器 WebSocket。当前 endpoint 校验 `Origin` 与请求 `Host` 必须同源；无 `Origin` 的非浏览器客户端仍可连接。

REST `/api/v1` 的 Bearer 鉴权仍由全局 REST dependency 负责。浏览器原生 WebSocket API 无法像 `fetch` 一样自由设置 `Authorization` header，因此如果未来 Flux 要在浏览器层启用强制登录，需要增加基于 cookie/session 或短期 websocket ticket 的认证方案，而不是把长期 Bearer token 放进 URL。

## 当前平台范围

本实现首先覆盖 Linux/macOS。Windows 桌面版本不能直接依赖 Python `pty` 模块，需要增加 Windows ConPTY adapter。当前代码在 Windows 上保持 import-safe，创建 Human PTY 时会明确返回“不支持”，不会让整个后端因为 Unix-only 模块 import 失败。

## 前端下一步

Human Terminal UI 应使用 `@xterm/xterm`，并把：

`terminal.onData → WebSocket input`

以及：

`WebSocket output → terminal.write`

连接起来；使用 `@xterm/addon-fit` 在 IDE 面板尺寸变化时发送新的 `cols/rows`。xterm.js 官方定位就是浏览器内的完整终端模拟器，并被 VS Code 等项目使用。
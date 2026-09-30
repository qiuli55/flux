# DeepSeek Harness 上游记录

本文件记录 Flux 依赖的 DSH（DeepSeek Harness）版本与实测结论。规则见
[DSH_FLUX_INTEGRATION_PLAN.md](../../docs/DSH_FLUX_INTEGRATION_PLAN.md) §16：
**只走官方 Python SDK、锁死版本、产物不进仓库**。

## 当前锁定版本

| 项 | 值 |
| --- | --- |
| 安装命令 | `.venv/bin/pip install "deepseek-harness-sdk==0.1.5rc1"` |
| SDK 包 | `deepseek-harness-sdk` `0.1.5rc1` |
| 运行时包 | `deepseek-harness-runtime-bin` `0.1.5rc1`（由 SDK 以 `==` 精确约束，不单独升） |
| 传递依赖 | `pydantic>=2.12,<3`（Bundle 内 pydantic 2.13.5，满足）、Python `>=3.10`（本机 3.10.12） |
| 许可 | MIT |
| 快照日期 | 2026-09-30 |

## 运行时载体

SDK 不自带应用入口，而是启动 wheel 内的单文件可执行，profile 固定为 `sdk`：

```
.venv/lib/python3.10/site-packages/deepseek_harness_runtime/runtime/
├── deepseek-harness-sdk-runtime-linux-x64      274,599,104 字节（约 261.9 MiB）
│   sha256 = 6f68ce88d98307533ee8fa58a8125de4dc019ab16fac8b512cec141a2d1961f8
└── deepseek-harness-sdk-runtime-linux-x64-rg   （ripgrep sidecar）
```

- **目标机器不需要系统 Node.js / pnpm / 任何构建步骤**；上游 `master` 要求的
  `engines.node = ^22.19.0 || >=24.0.0` 与 `pnpm@11.7.0` 只针对源码构建路线。
- 该 wheel 的 `deepseek-harness-runtime.json` 里 `version` 写着 `0.0.0-dev`，**不反映真实版本**，
  因此锁版本一律以 SDK 版本为准（当前 `0.1.5rc1`）。
- 另有一条 node 开发态载体（`DSH_RUNTIME_MODE=node`），生产永远不会自动选中，Flux 不使用。

## 实测记录

**2026-09-30 隔离冒烟（`/opt/dsh-smoke`，独立 venv + 独立 DSH_HOME）**

```
DeepSeekHarness(dsh_home=/opt/dsh-smoke/home, cwd=/opt/dsh-smoke/ws,
                provider="deepseek-official", model="deepseek-v4-flash", max_tokens=4096)
harness.run("Reply with exactly this token and nothing else: DSH_OK")
→ FINAL_RESPONSE: DSH_OK   （退出码 0）
```

结论：runtime 在本机可启动、可完成一次真实模型往返，glibc 2.35 满足要求。

## 约束（写在代码里之前先看这里）

1. `dsh_home` 必须显式传入，SDK 绝不隐式使用 `~/.dsh`；Flux 用 `/opt/flux/dsh-home`（仓库外）。
2. `Session.run()` 是**同步阻塞**调用，必须卸载到线程，不能直接 await。
3. 一个 `DeepSeekHarness` 实例独占一个 runtime 子进程，不要共享单实例并发 `run`。
4. 中断走 runtime 协议的 `session/cancel` 通知：`harness.client.notify("session/cancel", {"sessionId": sid})`。
5. 凭据通过 `DeepSeekHarness(api_key=...)` 注入子进程环境，**不写入任何文件**。
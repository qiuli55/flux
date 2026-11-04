# Flux WeChat 插件（OpenClaw）

微信登录、长连接与收发消息交给 **OpenClaw + 微信 ClawBot**；理解、检索与回复交给 **Flux**。
本插件只做一件事：在 `before_agent_reply` 里把消息转给 Flux，把 Flux 的回复交回 OpenClaw 发送。

```
微信 ←→ 微信 ClawBot ←→ OpenClaw Gateway ←→ [本插件] ←HTTP→ Flux /supervisor/channels/wechat
```

## 1. 启动 Flux 并配置令牌

在仓库根 `.env` 里加：

```dotenv
FLUX_SUPERVISOR_WECHAT_BRIDGE_TOKEN=改成一段足够长的随机串
# 允许对话的微信 senderId，逗号分隔；为空会拒绝所有人（fail-closed）
FLUX_SUPERVISOR_WECHAT_ALLOW_FROM=wxid_xxx,wxid_yyy
```

启动后端后验证：

```bash
curl -s -X POST http://127.0.0.1:8000/api/v1/supervisor/channels/wechat \
  -H "Authorization: Bearer $FLUX_SUPERVISOR_WECHAT_BRIDGE_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"message_id":"m1","session_key":"s1","sender_id":"wxid_xxx","text":"你好"}'
```

期望 `{"handled":true,"reply":{"text":"..."}}`。

## 2. 安装插件

在安装了 OpenClaw 的机器上（与 Flux 同一台主机时用 `127.0.0.1`）：

```bash
openclaw plugins install --link ./integrations/openclaw-flux --force
openclaw plugins enable flux-wechat
```

在 OpenClaw 配置里给插件填配置（`plugins.entries.flux-wechat`）：

```json
{
  "plugins": {
    "entries": {
      "flux-wechat": {
        "hooks": { "allowConversationAccess": true },
        "config": {
          "fluxUrl": "http://127.0.0.1:8000/api/v1/supervisor/channels/wechat",
          "bridgeToken": "与 FLUX_SUPERVISOR_WECHAT_BRIDGE_TOKEN 一致",
          "channel": "wechat",
          "timeoutMs": 180000
        }
      }
    }
  }
}
```

> 非捆绑插件必须开启 `hooks.allowConversationAccess: true`，否则 hook 拿不到对话内容。
> `bridgeToken` 留空时会读取环境变量 `FLUX_SUPERVISOR_WECHAT_BRIDGE_TOKEN`。

检查运行状态：

```bash
openclaw plugins inspect flux-wechat --runtime --json
openclaw plugins reload flux-wechat   # 改完 index.ts 后热重载
```

## 3. 行为约定

| Flux 返回 | 插件行为 |
| --- | --- |
| `200 {"handled":true,"reply":{"text":"..."}}` | 认领并发送 Flux 的回复，不再调用 OpenClaw 模型 |
| `200 {"handled":true,"duplicate":true}` | 静默认领（同一条消息的重试，回复已发过） |
| `200 {"handled":true}`（无 reply） | 静默认领 |
| 非 2xx / `handled:false` / 网络异常 | 不认领，交回 OpenClaw 原生模型兜底 |

Flux 侧还有两层保护：未配置 `FLUX_SUPERVISOR_WECHAT_BRIDGE_TOKEN` 直接 400；
`FLUX_SUPERVISOR_WECHAT_ALLOW_FROM` 为空或 senderId 不在其中返回 403。

## 4. 排查

- **一直走原生模型**：看 OpenClaw 日志里 `flux-wechat:` 的 warn；常见原因是 token 不一致、
  Flux 未启动、或 `plugins.entries.flux-wechat.hooks.allowConversationAccess` 没开。
- **`ctx.channel` 不是 `wechat`**：把插件配置 `channel` 改成实际通道 id，或留空表示不按通道过滤。
- **发送者被拒**：Flux 日志会打印 `拒绝非白名单微信消息 sender_id=...`，把它加进白名单即可。
- **重复回复 / 丢回复**：Flux 用 `message_id` 做幂等游标，重试同一条消息只处理一次；
  若 OpenClaw 每次生成的 `messageId` 都不同，会在 Flux 侧被当成新消息。

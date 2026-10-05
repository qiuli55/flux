# Flux 登录与身份系统设计

> 状态：设计稿
>
> 本文用于统一 Flux Personal / Work 后续的登录、身份、Session、Agent 鉴权边界。
>
> **重要：当前仓库已经明确设计的是 Agent 鉴权，但尚未形成完整的终端用户登录系统。** 因此本文将“现有架构已经确定的部分”和“后续登录系统设计”严格分开，不把尚未实现的能力描述成已经完成。

---

## 1. 设计目标

Flux 后续需要同时解决两类身份问题：

1. **人是谁**：终端用户登录 Flux，管理自己的 Workspace、Task、配置、设备和权限。
2. **Agent 是谁**：Codex / Claude Code / OpenCode / Flux 内置 Agent 等连接到 Flux 时，服务端能够可靠识别具体 Agent，并根据 scopes / Permission Engine 判断它能做什么。

两者不能混为一个 Token。

核心原则：

```text
User Identity
    ↓
User Session
    ↓
Workspace / Membership / Permission
    ↓
Agent Identity
    ↓
Agent Token
    ↓
MCP / API
```

**人登录得到的是 User Session；Agent 接入得到的是 Agent Token。**

---

## 2. 当前架构已经确定的身份部分

目标架构已经明确 Flux MCP 使用 Bearer 鉴权：

- `agent_tokens` 表：`id / agent_id / token_hash / scopes / created_at / revoked_at`
- Token 格式：`fxt_` + 32 字节随机 hex
- 服务端只保存 token hash，不保存明文 token
- 每次 MCP 调用由服务端解析 token → `agent_id + scopes`
- 再进入 Permission Engine
- 客户端不能自行声明 agent 身份
- `proposal.create` / `handoff.put` / `operation.record` 等写操作的 `provenance.agent` 由服务端盖章
- 无 Token：401
- 越权：MCP error，不静默降级
- Agent 注册表使用 `flux-builtin` / `codex` / `claude-code` / `opencode`

这些规则来自 `docs/FLUX_TARGET_ARCHITECTURE.md` §3.2，是 Agent 鉴权的基础，不应在后续登录系统中被破坏。

---

## 3. User Login 与 Agent Login 必须分离

### 3.1 User Login

面向：

- Flux Desktop
- Flux Web
- Flux Mobile
- 后续 Work / Enterprise 客户端

作用：

- 识别用户
- 建立用户 Session
- 读取用户可访问的 Workspace
- 判断用户在 Workspace 中的角色
- 管理设备 / Session
- 管理 Agent 接入
- 管理 API / Provider 配置

### 3.2 Agent Authentication

面向：

- Flux Built-in Agent
- Codex
- Claude Code
- OpenCode
- 后续第三方 Agent

作用：

- 识别 Agent
- 校验 scopes
- 进入 Permission Engine
- 给操作写入真实 provenance
- 控制 Agent 对 Flux MCP 能力面的访问

### 3.3 禁止的设计

不要让 Agent 直接拿用户 Session 当 Agent Token。

不要让 Agent 自己传：

```json
{
  "agent": "codex"
}
```

然后服务端相信这个字段。

正确方式是：

```text
Bearer Agent Token
       ↓
server-side token lookup
       ↓
agent_id
       ↓
scopes
       ↓
Permission Engine
```

---

## 4. 推荐的 User Identity 数据模型

后续真正实现登录系统时建议增加以下概念。

### 4.1 users

```text
users
├── id
├── email / login_identifier
├── password_hash             # 如果支持密码登录
├── display_name
├── avatar_url
├── status                    # active / disabled
├── created_at
├── updated_at
└── last_login_at
```

密码绝不能保存明文，也不能使用可逆加密代替 password hash。

### 4.2 user_sessions

```text
user_sessions
├── id
├── user_id
├── refresh_token_hash
├── device_id
├── device_name
├── client_type               # desktop / web / mobile
├── ip / user_agent           # 按隐私策略决定是否保存
├── created_at
├── last_seen_at
├── expires_at
└── revoked_at
```

服务端只保存 refresh token 的 hash。

Access Token 不需要长期保存到数据库；Session 的持久状态由 refresh token / session record 控制。

### 4.3 workspaces

```text
workspaces
├── id
├── owner_user_id
├── name
├── type                      # personal / work
├── created_at
└── updated_at
```

### 4.4 workspace_members

```text
workspace_members
├── workspace_id
├── user_id
├── role                      # owner / admin / member / viewer
├── created_at
└── revoked_at
```

Personal MVP 可以先只有 owner；Work 版本再启用完整成员体系。

---

## 5. 登录方式建议

### Personal MVP

第一阶段不要同时做大量登录方式。

建议：

```text
Email + Password
        ↓
登录
        ↓
Access Token + Refresh Session
```

后续再增加：

- GitHub OAuth
- Google OAuth
- 企业 SSO / OIDC
- Passkey

这样可以避免 Personal MVP 一开始就把大量精力放到 OAuth / 企业身份体系上。

### Work

Work 版本再重点支持：

```text
OIDC / SSO
    ↓
Organization
    ↓
Workspace
    ↓
Membership / Role
    ↓
Agent / Connector Permission
```

---

## 6. Token 分层

Flux 后续至少应该存在三种不同性质的凭证。

### 6.1 User Access Token

短生命周期。

用途：

- Desktop / Web / Mobile 调 Flux REST API
- 查询用户自己的 Workspace
- 创建 Task
- 查看 Run
- 管理设置

特点：

```text
短期
可刷新
绑定 User
```

### 6.2 User Refresh Token

用途：

- 获取新的 Access Token
- 保持登录状态

特点：

```text
长期
可撤销
绑定 Device / Session
服务端只保存 hash
```

### 6.3 Agent Token

即当前目标架构中的 `agent_tokens`。

用途：

- MCP
- Agent → Flux 能力面

特点：

```text
绑定 Agent
绑定 scopes
可撤销
服务端只保存 hash
不等价于 User Session
```

---

## 7. 推荐的请求链路

### 7.1 用户请求

```text
Flux Desktop / Web / Mobile
          │
          │ Authorization: Bearer <user access token>
          ▼
      Auth Middleware
          │
          ▼
       user_id
          │
          ▼
Workspace Membership
          │
          ▼
Permission Engine
          │
          ▼
       API / Service
```

### 7.2 Agent 请求

```text
Codex / Claude / OpenCode / DSH
          │
          │ Authorization: Bearer <agent token>
          ▼
       MCP Auth
          │
          ▼
       agent_id
       scopes
          │
          ▼
Permission Engine
          │
          ▼
     MCP Tool
          │
          ▼
provenance.agent = server stamped identity
```

### 7.3 用户启动 Agent

```text
User Login
    ↓
Workspace
    ↓
Agent Registry
    ↓
创建 / 选择 Agent
    ↓
生成 Agent Token
    ↓
Token 只在接入流程中展示一次
    ↓
注入 Agent 环境
    ↓
Agent 通过 MCP 使用 Flux
```

---

## 8. Agent Token 生命周期

```text
Create
  ↓
Active
  ↓
Rotate / Revoke
  ↓
Revoked
```

建议：

- Token 只显示明文一次
- 数据库只保存 hash
- 用户可以主动撤销
- Agent 删除时自动撤销相关 Token
- Token scopes 变化时可以重新签发
- 不允许客户端修改 token 对应的 agent_id

后续如果支持 Token Rotation：

```text
Old Token
   ↓
短暂 overlap
   ↓
New Token
   ↓
Old Token revoke
```

---

## 9. Session 生命周期

```text
登录
 ↓
Session Created
 ↓
Access Token Active
 ↓
正常使用
 ↓
Access Token Expired
 ↓
Refresh
 ↓
New Access Token
```

退出登录：

```text
Logout
 ↓
Revoke user_session
 ↓
Refresh Token 失效
 ↓
客户端清除本地凭证
```

“退出当前设备”和“退出所有设备”应当是两个操作。

---

## 10. 多设备

Flux Personal 后续可能同时运行：

```text
Windows Desktop
macOS Desktop
Android
Web
```

因此 Session 必须按设备独立管理：

```text
User A
├── Windows Session
├── Android Session
├── Browser Session
└── Mac Session
```

用户可以看到：

- 设备名称
- 最后活跃时间
- 创建时间
- 当前 Session
- 撤销按钮

不建议把所有设备共用一个永久 Token。

---

## 11. Personal MVP 的最小实现边界

个人版不需要一次实现完整企业身份系统。

建议最小闭环：

```text
注册
 ↓
登录
 ↓
User Session
 ↓
Personal Workspace
 ↓
进入 Flux
 ↓
连接 Agent
 ↓
Agent Token
 ↓
MCP
 ↓
Task / Run / Proposal
```

第一版只需要：

- User
- Password Hash
- User Session
- Personal Workspace
- User Access Token
- Refresh Token
- Agent Token
- Logout
- Revoke Agent Token

暂缓：

- Organization
- SSO
- 企业目录同步
- 多级管理员
- 复杂邀请体系
- Billing Identity
- SCIM

---

## 12. 与现有 Permission Engine 的关系

登录系统不是新的权限系统。

正确分层：

```text
Authentication
    ↓
“你是谁？”

Authorization
    ↓
“你能做什么？”

Permission Engine
    ↓
RBAC + Capability
```

Flux 当前已经把 RBAC + Capability 作为 fail-closed 权限策略的一部分，因此登录系统应当提供身份上下文，而不是重新造一套权限判断。

---

## 13. 安全边界

必须满足：

1. 密码不保存明文。
2. User Refresh Token 不保存明文。
3. Agent Token 不保存明文，只保存 hash。
4. Agent 不允许伪造 `agent_id`。
5. User Session 与 Agent Token 分离。
6. 权限判断 fail-closed。
7. Secret / API Key 不通过 Agent Token scope 暴露。
8. Agent Token 被撤销后立即不能继续调用 MCP。
9. 用户退出所有设备后，旧 Refresh Session 全部失效。
10. 日志中不得打印密码、Refresh Token、Agent Token、Provider API Key。

---

## 14. 与现有 Flux 架构的最终关系

最终身份体系应当形成：

```text
                         Flux Identity
                              │
             ┌────────────────┴────────────────┐
             │                                 │
        Human Identity                    Agent Identity
             │                                 │
        User / Session                    Agent / Token
             │                                 │
      Workspace Membership                 Scopes
             │                                 │
             └──────────────┬──────────────────┘
                            │
                    Permission Engine
                            │
              ┌─────────────┴─────────────┐
              │                           │
          REST API                     MCP API
              │                           │
       Desktop/Web/Mobile          Codex/DSH/Claude/
                                   OpenCode/...
```

其中最重要的一条是：

> **User 是人，Agent 是执行者，Session 是人的登录状态，Agent Token 是执行者的能力凭证。四者不能混成一个身份对象。**

---

## 15. 实施顺序

### P0 — Personal MVP 登录闭环

1. `users`
2. `user_sessions`
3. 注册 / 登录 / 登出
4. Access Token + Refresh Token
5. Personal Workspace 自动创建
6. User → Workspace 鉴权
7. Agent Token 与 User / Workspace 建立归属关系

### P1 — 客户端登录体验

1. Desktop 登录
2. Mobile 登录
3. Web 登录
4. 多设备 Session 管理
5. 登录状态恢复
6. 全设备退出

### P2 — Agent 管理

1. Agent 列表
2. Agent Token 创建
3. Token 只显示一次
4. Token revoke
5. Token rotation
6. scopes 管理

### P3 — Work / Enterprise

1. Organization
2. Workspace Members
3. RBAC
4. Invite
5. OIDC / SSO
6. 企业设备 / Session 管理
7. 审计日志

---

## 16. 当前状态结论

当前 Flux 架构已经把 **Agent 鉴权**定义得比较清楚：MCP 使用 Bearer Agent Token，Token hash 化保存，服务端盖章 Agent 身份，并经过 Permission Engine。fileciteturn637file0

但从当前仓库规格来看，**完整的终端用户登录系统尚未成为现有 M0/M1 完成项**。因此下一阶段如果要正式加入登录，应按照本文的身份分层实现，而不是把现有 `agent_tokens` 直接扩展成“用户登录 Token”。

### 最终原则

```text
User Login ≠ Agent Login
User Session ≠ Agent Token
Authentication ≠ Authorization
Workspace ≠ Agent

人登录 Flux。
Agent 接入 Flux。
Permission Engine 决定两者分别能做什么。
```

# Flux Personal MVP Goals

> 目标：把当前已经完成的 Flux 核心闭环打磨成一个可以长期实际使用的个人版 AI Coding 工作台。
>
> 本阶段原则：**完善已有能力，不新增新的核心产品能力。**

## 1. MVP 最终目标

Flux Personal MVP 应达到以下状态：

> 用户可以在电脑端安装并启动 Flux，配置至少一个可用 Agent，通过 Solo 或 IDE 完成一次真实开发任务；Agent 只能通过 Flux 的能力面工作，用户可以查看、审核、拒绝、应用变更，执行测试并查看 Git 结果；任务异常时可以取消、恢复或明确结束；移动端可以在远程控制场景下完成核心查看、决策和控制操作。

核心评价标准不是“功能数量”，而是：

- 能不能稳定完成真实开发任务
- 用户是否理解当前状态
- 用户是否始终掌握代码变更控制权
- 异常后能不能得到明确结果
- 重启后数据和状态是否可靠
- 手机端是否真的能辅助电脑端工作

---

## 2. Personal MVP 的核心闭环

必须保持并稳定以下闭环：

```text
用户提出需求
    ↓
Solo 澄清 / 确认
    ↓
选择或调用 Agent
    ↓
Agent 通过 Flux MCP 获取上下文
    ↓
Agent 产生 Proposal
    ↓
用户查看 Diff
    ↓
Accept / Reject
    ↓
Apply
    ↓
测试门禁
    ↓
Git 状态 / Commit
```

任何一环出现异常，都必须有明确的 UI 状态，而不能表现成“成功”。

---

## 3. 桌面端目标

### 3.1 Solo

- 新建任务流程清晰
- 用户知道当前任务处于什么状态
- 澄清问题与执行决策容易理解
- Agent 执行状态可靠
- Proposal / Diff / Apply 流程清晰
- Reject 后状态正确
- Cancel 后状态正确
- Timeout 后状态正确
- Run 崩溃或后端重启后不会产生永久 running
- Assistant 消息不会因为请求失败造成错误的完成状态

### 3.2 IDE

- 文件浏览、代码查看、Diff 查看正常
- Proposal 产生的变更可以被明确识别
- Apply 前后状态清晰
- 测试结果能够被用户理解
- Git 状态和提交结果可查看
- 桌面端常用分辨率下布局稳定
- 不出现横向溢出、内容被遮挡或关键操作不可见

### 3.3 Agent

- Agent Registry 中的身份稳定
- Agent Token 与 Agent 身份一致
- Agent 权限边界有效
- DSH 能稳定完成 Flux MCP 闭环
- Codex 能稳定完成 Flux MCP 闭环
- 不同 Agent 共享 Flux 能力面，但不能越权访问项目资源

---

## 4. 变更安全目标

Flux Personal MVP 必须坚持：

> **Agent 不直接拥有最终代码写入权。**

必须确保：

- Proposal 是待审核状态时不会自动落盘
- Reject 不会写入代码
- Apply 只应用用户批准的变更
- Apply 冲突不会覆盖用户已有修改
- 测试失败不会伪装成成功
- Git 操作结果与 UI 状态一致
- 密钥、凭证类文件继续受到权限限制

---

## 5. 运行时可靠性目标

重点不是增加更多运行能力，而是把现有 Run 生命周期做可靠。

必须验证：

```text
queued
  ↓
running
  ↓
completed / failed / cancelled / timeout
```

重点场景：

- 正常完成
- Agent 报错
- Provider 长时间无响应
- Cancel
- Timeout
- Flux 后端重启
- Agent 子进程退出
- 子进程异常残留
- 数据库状态与真实进程状态不一致

目标：**不能出现用户无法解释的永久 running。**

---

## 6. 持久化与重启目标

Flux 作为个人长期工具，重启可靠性是 MVP 的必要条件。

需要确认重启前后的：

- Project
- Task
- Task messages
- Agent Registry
- Proposal
- Apply 状态
- Git 信息
- Run 状态

都不会产生明显的数据丢失或状态错乱。

重点测试：

```text
完成任务
 ↓
关闭 Flux
 ↓
重新启动
 ↓
继续查看历史任务 / Proposal / Git 状态
```

---

## 7. 移动端 MVP 目标

移动端不是第二套完整 IDE，而是**电脑端 Flux 的远程控制与状态查看入口**。

### 必须做好

- 登录 / 连接电脑端
- 查看项目和任务
- 查看任务状态
- 查看运行中的 Agent
- 查看 Proposal
- 查看 Diff
- 做出 Accept / Reject 等必要决策
- Cancel 运行中的任务
- 查看异常结果
- 在手机屏幕上完成核心操作而不依赖桌面端布局

### 移动端明确不要求

- 完整替代桌面 IDE
- 在手机上完成复杂代码编辑
- 新增复杂 Agent 编排能力
- 新增独立的云端工作流

移动端 MVP 的核心价值是：

> **用户离开电脑以后，仍然可以知道发生了什么，并能处理关键决策。**

---

## 8. 移动端质量目标

至少验证：

- 390×844
- 常见 Android 手机尺寸
- 网络短暂中断后的恢复
- 页面刷新后的状态恢复
- 任务运行中进入页面
- Proposal 待审核时进入页面
- Cancel 后返回页面
- Error 状态进入页面

重点避免：

- 横向溢出
- 按钮超出屏幕
- 关键状态被截断
- 审核数量与当前任务含义不清
- 操作后 UI 没有反馈
- 移动端显示成功但后端实际失败

---

## 9. 安装与首次使用目标

Personal MVP 最终应该能够让用户完成：

```text
安装 / 启动
 ↓
创建或选择项目
 ↓
配置 Agent
 ↓
配置必要凭证
 ↓
进入 Solo / IDE
 ↓
完成第一项任务
```

重点不是做复杂的新手引导，而是减少明显的配置障碍和错误提示缺失。

---

## 10. Release Candidate 验收

进入个人版发布候选阶段前，至少完成：

### 功能

- [ ] Solo 核心闭环
- [ ] IDE 核心闭环
- [ ] DSH 闭环
- [ ] Codex 闭环
- [ ] Proposal / Diff / Apply
- [ ] Reject
- [ ] Conflict
- [ ] Rollback / 状态一致性
- [ ] Test gate
- [ ] Git
- [ ] Cancel
- [ ] Timeout
- [ ] Crash recovery

### 移动端

- [ ] 登录 / 连接
- [ ] Task 查看
- [ ] Run 查看
- [ ] Proposal 查看
- [ ] Diff 查看
- [ ] Decision
- [ ] Cancel
- [ ] Error
- [ ] 390×844 回归

### 可靠性

- [ ] 重启恢复
- [ ] 长任务状态
- [ ] Agent 进程退出
- [ ] 后端异常
- [ ] 网络异常
- [ ] 状态一致性

### 产品体验

- [ ] 无明显横向溢出
- [ ] 无关键操作无反馈
- [ ] 错误状态不会显示为成功
- [ ] 用户能理解 Proposal / Apply 的区别
- [ ] 用户能理解当前 Agent / Task / Run 状态

---

## 11. 本阶段停止线

以下内容本阶段**不进入实现计划**：

- Context Engine
- 小模型持续压缩
- Prompt Optimizer
- 动态 Agent 拆分
- 自动多 Agent 编排
- Skill Marketplace
- Connector Marketplace
- 云服务
- 企业版
- 云端 Agent
- 新的核心 Agent Runtime

这些想法可以继续记录和研究，但不得因为它们影响 Personal MVP 收口。

---

## 12. Personal MVP 完成的定义

Flux Personal MVP 不是“所有功能都有”。

完成定义是：

> **开发者自己愿意连续使用 Flux 完成真实项目，而不是为了测试才使用 Flux。**

当用户能够：

1. 从需求进入任务；
2. 使用 DSH 或 Codex 执行；
3. 理解 Agent 正在做什么；
4. 审核真实代码变更；
5. 安全 Apply；
6. 通过测试并查看 Git 结果；
7. 遇到异常能够取消、恢复或重新处理；
8. 离开电脑后通过移动端处理关键状态；
9. 重启 Flux 后继续工作；
10. 在真实开发中愿意再次打开 Flux；

即可认为 Personal MVP 达到目标。

---

## 13. 最终原则

```text
现在：完善
不是：扩张

现在：稳定
不是：堆功能

现在：真实使用
不是：继续做 Demo

现在：Personal MVP
不是：提前做 Flux 2.0
```

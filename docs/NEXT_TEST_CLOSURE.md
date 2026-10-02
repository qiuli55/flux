# Flux 下一轮完整测试闭环

> 目的：在现有功能测试、MCP/Agent 闭环测试和 UI 用户级测试基础上，完成一次从“首次进入 Flux”到“真实开发任务完成”的完整用户测试闭环。
>
> 本轮不以继续增加功能为目标，而以验证现有 MVP 是否能够稳定、可理解、可恢复地完成真实开发工作为目标。除测试发现的阻塞问题外，原则上不在本轮新增核心能力。

---

## 1. 本轮测试原则

1. **先测后改**：测试 Agent 不得为了通过测试而修改产品代码。
2. **真实路径优先**：不提前告诉测试 Agent 应该点击哪个入口。
3. **DSH / Codex 对等验证**：同类任务至少分别执行一次。
4. **用户结果优先**：不仅记录接口是否成功，还记录用户是否理解当前状态和下一步。
5. **异常必须闭环**：失败、取消、超时、权限拒绝、冲突都必须验证“发现 → 理解 → 恢复”。
6. **测试结束必须产生证据**：截图/日志/Run ID/Proposal ID/Git commit/测试结果至少保留必要证据。
7. **不因偶发成功判定稳定**：关键闭环至少重复两次；高风险场景至少一次成功路径 + 一次失败/恢复路径。

---

# 2. 总体闭环

本轮按照下面顺序执行：

```text
环境准备
  ↓
L0 视觉/响应式
  ↓
L1 信息架构/可发现性
  ↓
L2 真实开发任务
  ↓
Agent 对等性（DSH / Codex）
  ↓
Proposal / Diff / Apply / Test / Git
  ↓
L3 异常与恢复
  ↓
移动端控制
  ↓
长任务与重复任务
  ↓
回归测试
  ↓
缺陷分级
  ↓
修复 P0/P1/P2
  ↓
完整回归
  ↓
MVP 用户测试结论
```

---

# 3. 阶段 A：环境基线

## TC-N-001 环境自检

执行：

- `verify.sh`
- 前端 build
- Vitest
- 后端测试
- MCP/Agent 测试

记录：

- 总测试数
- passed
- failed
- skipped
- 环境版本
- DSH 版本
- Codex CLI 版本
- 使用的模型

通过条件：基线全部通过，或所有已知失败均已记录并确认不影响本轮测试。

## TC-N-002 全新用户状态

使用无历史任务/Proposal 的测试项目或测试数据库。

确认：

- 项目能正常打开
- Agent Registry 状态正确
- Task 列表为空或符合预期
- 不存在上一轮测试残留

---

# 4. 阶段 B：首次用户体验

## TC-N-101 首次进入

任务：

> 你第一次使用 Flux，需要找到一个项目并开始开发。

不提供点击路径。

记录：

- 第一次点击
- 第一次输入
- 第一次困惑
- 第一次误操作
- 是否需要解释 IDE / Solo / Agent

## TC-N-102 项目进入

任务：

> 打开已有项目，找到 README 和一个主要代码文件，然后准备开始修改。

通过条件：不查看源码、不接受测试人员提示即可完成。

## TC-N-103 IDE / Solo 理解

让测试 Agent 用自己的话解释：

- IDE 是什么
- Solo 是什么
- Agent 是什么
- Task 是什么
- Context 是什么
- Proposal / Diff / Apply 是什么

记录无法独立解释的概念。

---

# 5. 阶段 C：DSH 真实开发闭环

## TC-N-201 DSH 小任务

任务示例：

> 给项目增加一个简单的登录页面，并补充基本错误提示。

要求完整走：

```text
创建任务
→ Agent 执行
→ Context
→ Proposal
→ Diff
→ 人工审核
→ Apply
→ Test
→ Git
```

记录：

- task_id
- run_id
- agent_id
- proposal/change_id
- Git commit
- 测试结果

## TC-N-202 DSH 需求澄清

任务：

> 给项目增加一个用户管理功能。

故意不给出足够细节。

验证：

- Agent 是否询问
- 用户是否知道正在等待自己
- 回答是否被正确保存
- 最终是否进入明确执行状态

## TC-N-203 DSH 人工决策

制造需要人工选择的方案。

验证：

- “使用默认方案”是否容易理解
- “我来决定”是否容易理解
- 选择后是否正确恢复执行

---

# 6. 阶段 D：Codex 对等闭环

## TC-N-301 Codex 小任务

使用与 TC-N-201 等价的开发任务。

要求：

- Codex 通过 Flux MCP 获取上下文
- 不直接写真实工作区
- Proposal 进入 Flux 审核链路
- Apply 后由 Flux 落盘
- 测试和 Git 状态可追踪

重点比较：

| 项目 | DSH | Codex |
|---|---|---|
| 找到项目 | 记录 | 记录 |
| 获取 Context | 记录 | 记录 |
| Proposal | 记录 | 记录 |
| Diff | 记录 | 记录 |
| Apply | 记录 | 记录 |
| Test | 记录 | 记录 |
| Git | 记录 | 记录 |
| UI 理解差异 | 记录 | 记录 |

不要求两个 Agent 内部实现完全相同，只要求用户面对的是一致的 Flux 工作模型。

## TC-N-302 Codex 越权

至少验证：

- `.env` / 凭证文件拒绝
- 未授权 workspace 写入拒绝
- shell 直接写真实工作区被阻止
- Proposal 之外不能绕过审核直接修改真实工作区

---

# 7. 阶段 E：Proposal / Apply 安全闭环

## TC-N-401 单文件 Proposal

验证：

- proposal 创建
- diff 正确
- approve
- apply
- test
- git

## TC-N-402 多文件 Proposal

使用 3～5 个文件组成同一 Proposal。

确认：

- 文件组关系正确
- Diff 不丢文件
- Apply 是原子行为
- 任一文件异常时不会留下不可恢复的半应用状态

## TC-N-403 Reject

用户拒绝 Proposal。

验证：

- 真实工作区不变化
- Proposal 状态正确
- Agent 能知道被拒绝
- 用户能继续任务

## TC-N-404 Conflict

制造文件已经发生变化的情况。

验证：

- Apply 不覆盖用户最新修改
- 用户能知道发生冲突
- 状态不会错误显示为 applied
- 能重新生成/重新审核

## TC-N-405 Rollback

完成一次 Apply 后执行回滚路径。

验证：

- 文件恢复
- Git 状态正确
- Proposal/Apply 状态与实际文件状态一致
- 用户知道回滚是否成功

---

# 8. 阶段 F：运行时生命周期

## TC-N-501 Running

观察：

- Run 创建
- heartbeat
- Task running
- Agent process

确认 UI 和数据库状态一致。

## TC-N-502 Cancel

运行任务过程中取消。

必须验证：

```text
用户点击 Cancel
→ Flux 标记 cancelled
→ Agent 子进程终止
→ 子进程树无残留
→ Task/Run 最终状态一致
```

不能只验证数据库变成 cancelled。

## TC-N-503 Timeout

制造 Agent 长时间无响应。

验证：

- Run 有界超时
- Task 不永久 running
- Agent 进程被清理
- 用户能看到超时原因
- 可以重新执行

## TC-N-504 Provider 卡死

模拟 provider 连接无字节返回。

验证 Supervisor 是否能够发现异常并结束运行，而不是永久等待。

## TC-N-505 Crash Recovery

在运行过程中主动结束 Flux / Worker。

重启后验证：

- running 任务能被对账
- orphan run 被识别
- 状态恢复到可继续/失败/取消等正确终态
- 不产生永久僵尸任务

---

# 9. 阶段 G：Agent 身份与权限

## TC-N-601 Agent Registry

验证：

- Agent 创建
- Agent UUID 持久化
- 重启后仍存在
- MCP token 对应正确 Agent

## TC-N-602 Token

验证：

- 正确 token 可以使用 MCP
- 错误 token 被拒绝
- token 撤销后立即失效
- agent_id 与 Registry canonical UUID 不出现漂移

## TC-N-603 Scope

分别测试：

- read
- write
- project 限制
- agent 限制

确认权限拒绝不会被 UI 错误显示成普通 Agent 失败。

---

# 10. 阶段 H：移动端闭环

## TC-N-701 移动端登录/连接

验证：

- 手机端进入 Flux
- 能找到目标电脑/工作区
- 连接状态清晰

## TC-N-702 移动端查看任务

验证：

- 查看 running
- 查看 waiting
- 查看 completed
- 查看 failed

## TC-N-703 移动端决策

让任务进入人工决策点。

验证手机端能完成：

- 查看问题
- 选择默认方案
- 自己决定
- 继续任务

## TC-N-704 移动端取消

从手机端取消运行任务。

确认电脑端同步显示 cancelled，并且 Agent 进程真正退出。

---

# 11. 阶段 I：长任务与上下文

本阶段暂时**不引入新的上下文压缩架构**，只测试现有系统是否稳定。

## TC-N-801 多轮对话

连续发送至少 10～20 轮与同一任务相关的信息。

观察：

- 历史消息是否完整
- 游标是否连续
- assistant 回复是否正确关联 task
- UI 是否出现明显卡顿

## TC-N-802 长任务

让 Agent 执行一个需要多个步骤的真实任务。

观察：

- 状态是否稳定
- heartbeat 是否持续
- 中途 Proposal 是否正确
- 长时间运行是否出现重复消息

## TC-N-803 重复任务

连续执行 3 次同类任务。

检查：

- 历史 Task 是否相互污染
- Context 是否串任务
- Agent identity 是否串线
- Proposal 是否串线

---

# 12. 阶段 J：真实用户级压力测试

## TC-N-901 新用户完整任务

由测试 Agent 从零开始，不查看测试方案中的点击路径，仅看到任务目标：

> “使用 Flux 给这个项目增加一个登录页面，完成测试并提交 Git。”

要求从：

```text
首次进入
→ 项目
→ Agent
→ 任务
→ 执行
→ Proposal
→ Review
→ Apply
→ Test
→ Git
```

全程完成。

## TC-N-902 新用户失败恢复

在上述任务中人为制造一次：

- Agent 失败
- 或 Apply 冲突
- 或用户拒绝 Proposal

观察用户能否自行恢复。

## TC-N-903 新用户取消恢复

让任务运行后由用户取消，再重新发起任务。

要求：

- 用户知道第一次任务为什么停止
- 新任务不会继承错误状态
- Agent/Run 不残留

---

# 13. 测试结果记录模板

每个 TC 至少记录：

```text
TC ID:
日期:
环境:
Agent:
Model:
Project:
Task ID:
Run ID:
Proposal ID:
结果: PASS / FAIL / BLOCKED / NOT EXECUTABLE

用户实际路径:
第一次点击:
第一次犹豫:
第一次误操作:

观察:

证据:
- screenshot:
- log:
- run_id:
- proposal_id:
- git commit:

问题:

严重级别:
P0 / P1 / P2 / P3

建议:

回归条件:
```

---

# 14. 缺陷分级

## P0 Blocker

- 数据丢失
- 越权写入
- 密钥泄露
- Proposal 审核被绕过
- Cancel 后 Agent 仍持续修改真实工作区
- 状态与真实文件完全相反并导致不可恢复

## P1 Critical

- 核心闭环无法完成
- Agent 失败被显示为成功
- Run 永久 running
- Apply 无法恢复
- DSH/Codex 某一条核心路径无法使用

## P2 Major

- 明显影响正常使用，但存在绕行方案
- 状态不一致但可以人工恢复
- 移动端核心控制体验缺陷
- 信息架构明显误导

## P3 Minor

- 文案
- 视觉细节
- 非核心入口
- 小尺寸布局问题

---

# 15. 本轮停止线

满足以下条件后，**停止继续扩展核心功能**，进入 MVP 收口：

- [ ] L0 视觉与响应式核心问题清零
- [ ] L1 主要页面无需解释即可理解
- [ ] DSH 完整闭环通过
- [ ] Codex 完整闭环通过
- [ ] Proposal / Review / Apply / Test / Git 闭环通过
- [ ] Reject / Conflict / Rollback 通过
- [ ] Cancel 后进程真正退出
- [ ] Timeout 有界且可恢复
- [ ] Crash Recovery 无永久僵尸任务
- [ ] Agent Registry / Token / Scope 一致
- [ ] 移动端至少能完成查看、决策、取消
- [ ] 10～20 轮任务消息无明显串线
- [ ] 3 次重复任务无上下文污染
- [ ] 新用户能够独立完成一次完整开发任务
- [ ] P0 = 0
- [ ] P1 = 0
- [ ] 剩余 P2 已有明确处理计划

达到停止线后，才进入下一阶段：

```text
MVP 收口
  ↓
客户端打包
  ↓
个人版发布前测试
  ↓
再评估 Context Engine / 小模型 / Token 优化
```

---

# 16. 本轮明确暂缓

为了避免测试阶段重新扩大范围，本轮暂不因为“想到一个更好的架构”而直接加入：

- 新的上下文压缩系统
- 新的小模型输入优化系统
- Agent 动态多会话编排系统
- 云端账号/云同步
- 大规模 Skill Marketplace
- 企业版工作流
- 高级计费系统
- 本地模型自动补全

这些统一进入 Future / Research，不影响当前 MVP 验收。

---

# 17. 最终验收标准

Flux 本轮不是以“功能数量”作为完成标准，而是回答五个问题：

1. **第一次使用 Flux 的开发者能不能自己开始？**
2. **开发者能不能让 DSH 或 Codex 完成真实任务？**
3. **Agent 是否始终被 Flux 的权限、Proposal、Review、Apply 边界约束？**
4. **出现失败、取消、冲突、超时以后，用户能不能恢复工作？**
5. **用户是否能够相信 Flux 当前显示的状态就是实际发生的状态？**

只有这五个问题都能通过实际测试，本轮 MVP 才算真正完成。

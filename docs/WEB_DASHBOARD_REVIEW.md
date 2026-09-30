# Web Dashboard 前端代码审查

> 审查范围：`apps/web-dashboard` 当前 `main` 分支前端代码。
>
> 审查目标：检查 UIDemo 当前实现是否符合 Flux 最小 IDE、Virtual Workspace、AI Team 和后续 Engineering OS 的产品方向，并记录需要修复或继续演进的问题。

## 1. 当前结论

当前 `apps/web-dashboard` 已经不是纯 UI Mock，而是一个连接真实后端 API 的最小 IDE 前端。

当前主链路已经能够表达：

```
项目
  ↓
Project Scanner / Project Brain
  ↓
上下文文件
  ↓
一句话需求
  ↓
Developer Agent
  ↓
Pending Proposal
  ↓
Diff
  ↓
人工批准
  ↓
Apply
  ↓
Tester
  ↓
Git Commit
```

整体方向符合 Flux 当前 MVP 路线，不建议推倒重做。下一阶段应该在现有结构上逐步补齐编辑器、文件浏览器、Virtual Workspace、Agent Team Activity 和持久化 Operation Timeline。

---

## 2. P0：需要优先修复

### 2.1 Diff 解析可能错误吞掉合法代码行

**涉及：**

- `src/utils/diff.ts`
- `src/components/DiffViewer.tsx`

当前 Diff 解析使用 `---` / `+++` 前缀判断 unified diff 的文件头。如果只根据行首字符串进行宽泛匹配，可能把合法的删除/新增代码行误认为文件头并跳过。

Unified diff 中真正的文件头只出现在对应 diff 文件的开头，例如：

```diff
--- a/src/foo.ts
+++ b/src/foo.ts
@@ ...
```

进入 hunk 后，所有以 `-` 或 `+` 开头的行都应该继续作为真实变更处理。

**建议：**

- 只跳过 unified diff 文件头位置的 `---` / `+++` 两行。
- 进入 hunk 后不要再通过 `startsWith("---")` / `startsWith("+++")` 忽略行。
- 增加包含连续 `--` / `++` 内容的代码行测试。
- 保证删除/新增行不会因为代码内容本身以 `--` / `++` 开头而丢失。

**验收标准：**

1. 普通 unified diff 正常渲染。
2. hunk 中的 `---- ...`、`++++ ...` 等合法代码行不会被吞掉。
3. split / unified 两种模式显示内容一致。

---

### 2.2 Health / Ready 错误状态可能发生状态污染

**涉及：**

- `src/App.tsx`
- `src/components/TopBar.tsx`

当前健康检查和 ready 检查存在不同的请求语义，但错误状态在 UI 层使用时容易混淆。例如一个请求失败、另一个请求成功时，旧错误可能继续显示。

当前代码中还存在类似：

```
readyError={healthError}
```

的语义混用，变量名称已经说明两个状态没有完全分离。

**建议：**

分别维护：

- `healthError`
- `readyError`

或者定义统一的后端状态对象：

```ts
type BackendHealth = {
  health: ...
  ready: ...
  healthError: string | null
  readyError: string | null
}
```

**验收标准：**

1. `/health` 失败、`/health/ready` 成功时，不显示错误的 ready 状态。
2. `/health/ready` 失败、`/health` 成功时，只显示 ready 错误。
3. 两个请求恢复后错误状态都能正确清除。
4. 顶栏显示的状态与后端实际状态一致。

---

## 3. P1：下一阶段需要补齐

### 3.1 增加真正的 File Explorer

当前 ProjectPanel 已经承担项目结构和上下文选择职责，但还不是完整 IDE 文件浏览器。

后续应该支持：

```
src/
├── components/
│   ├── App.tsx
│   └── Button.tsx
├── api/
│   └── client.ts
└── main.tsx
```

建议能力：

- 目录展开/折叠
- 文件选择
- 文件类型图标
- 当前文件状态
- 修改文件标记
- Context 加入/移除
- 与 Editor 联动

当前的项目结构/Context Selector 可以保留，不需要推倒重做。

---

### 3.2 增加真正的 Code Editor

当前中栏已经有 Change Review + Diff，但还没有真正的代码编辑器。

下一阶段建议形成：

```
File Explorer
      ↓
Code Editor
      ↓
Virtual Workspace
      ↓
Proposal / Diff
```

Editor 不应只是普通文本框，而应该与 Flux 的 Virtual Workspace 状态联动。

至少需要能够表达：

- Original
- Proposed
- Modified
- Pending Review
- Applied

最终用户应该能在一个工作区内完成：

```
查看代码
  ↓
让 AI 修改
  ↓
查看 Proposal
  ↓
编辑 Proposal
  ↓
Accept / Reject
  ↓
Apply
```

---

### 3.3 AgentPanel 从 Agent List 演进为 AI Team Room

当前 AgentPanel 已经能够显示：

- Agent 名称
- Role
- State
- Model
- Execution Count
- Permissions
- Last Error

作为 MVP 足够。

但 Flux 长期产品设计中的右侧区域应该逐渐从“Agent 管理列表”演进为“AI Team Room”。

目标形态：

```
Tech Lead
  ↓
任务拆解

Developer
  ↓
修改 auth.ts

Reviewer
  ↓
等待 Review

Tester
  ↓
运行测试
```

重点应该从“这个 Agent 是什么”逐渐增加到“这个 Agent 当前正在工程流程中做什么”。

不要立即重写当前 AgentPanel，应在 Agent Run / Operation 数据完善后逐步演进。

---

### 3.4 Operation Timeline 应从前端 Session State 演进为后端持久化

当前 ActivityFeed / TaskTimeline 使用 React state 保存本轮事件。

这种实现适合 UIDemo，但不是最终的 Flux Operation Timeline。

当前模式：

```
React State
  ↓
events
  ↓
刷新页面
  ↓
事件消失
```

最终应该：

```
Backend Operation Store
        ↓
GET /operations
        ↓
TaskTimeline / ActivityFeed
        ↓
Operation Detail
        ↓
Snapshot / Diff / Restore
```

这需要与 Flux 后端的：

- operations
- operation_files
- snapshots
- snapshot_files
- restore_points

逐步对接。

长期目标：

> Context = AI 知道什么  
> Operation = AI 做了什么  
> Snapshot = 代码当时是什么状态

---

## 4. P2：后续架构与体验优化

### 4.1 i18n

当前前端大量中文 UI 文本直接写在 TSX 中。

Flux 后续需要支持全球化，因此应尽早预留 i18n 架构。

第一阶段建议：

- `zh-CN`
- `en-US`

后续再根据用户群增加：

- `ja-JP`
- `ko-KR`
- `es-ES`
- `fr-FR`
- `de-DE`
- `pt-BR`

建议：

```
src/i18n/
├── index.ts
├── zh-CN.ts
└── en-US.ts
```

注意：

- UI 语言与代码内容解耦。
- Agent Role、Task、Proposal、Virtual Workspace 等核心工程概念应保持稳定。
- 不要等页面数量大量增加后再整体迁移 i18n。

---

### 4.2 API 按领域逐步拆分

当前 `api/client.ts` 对 MVP 很清晰，不建议现在为了架构洁癖立即拆分。

随着 API 增长，可以演进为：

```
src/api/
├── client.ts
├── projects.ts
├── agents.ts
├── workspace.ts
├── operations.ts
├── git.ts
├── sandbox.ts
└── brain.ts
```

原则：

> 现在保持简单；当单文件开始影响维护时再按领域拆分。

---

### 4.3 Agent Run / Token / Cost 数据

当前 AgentPanel 已明确显示后端暂未提供 Token / Cost 字段。

未来 Agent Run 数据应该至少能够关联：

- Agent
- Task
- Model
- Provider
- Token Usage
- Cost
- Start / End Time
- Tool Calls
- Result
- Error

这样右侧 AI Team Room 才能从静态 Agent 信息演进到真实的工程运行状态。

---

### 4.4 ProjectPanel 与 Editor 联动

最终 ProjectPanel 不应该只是“选择 Context 文件”。

推荐形成：

```
Project Tree
    ↓
Selected File
    ↓
Editor
    ↓
Context
    ↓
Proposal
```

用户点击文件后，应该能够直接看到代码，而不是只能看到项目结构元数据。

---

## 5. 当前 UI 中已经验证的正确方向

以下方向目前应该继续保持：

### 5.1 中央区域足够大

当前三栏结构：

```
Project / Context
        │
        │
Large Change Review / Diff
        │
        │
AI Team
```

中央工作区域没有被压缩成一个很小的 AI 聊天窗口，符合 Flux 的 IDE 定位。

### 5.2 Virtual Workspace 思路正确

ChangeReview 当前围绕：

```
Requirement
   ↓
Proposal
   ↓
Diff
   ↓
Accept / Apply / Reject
```

组织 UI。

不要将其改造成普通 ChatGPT 式聊天界面。

### 5.3 深色工程化视觉方向正确

当前使用：

- charcoal / slate surface
- teal / accent
- add / delete / info / danger 状态色
- 较少装饰性渐变
- 工程工具风格

符合 Flux 之前确定的原创 IDE 风格方向。

### 5.4 当前不建议推倒重做

UIDemo 已经完成“真实后端 API + 最小 IDE + Proposal/Diff/Apply/Git”这一阶段目标。

后续应采用增量演进：

```
当前最小 IDE
    ↓
修复 P0
    ↓
File Explorer
    ↓
Code Editor
    ↓
Virtual Workspace 双视图
    ↓
AI Team Activity
    ↓
Persistent Operation Timeline
    ↓
i18n
```

---

## 6. 推荐实施顺序

### 第一阶段

- [ ] 修复 Diff Parser
- [ ] 修复 Health / Ready 错误状态
- [ ] 增加对应前端单元测试

### 第二阶段

- [ ] File Explorer
- [ ] Code Editor
- [ ] Editor ↔ Context 联动
- [ ] Editor ↔ Proposal 联动

### 第三阶段

- [ ] Virtual Workspace 原始代码 / Proposal / Diff
- [ ] Proposal 编辑
- [ ] AI Team Activity
- [ ] Agent Run 状态

### 第四阶段

- [ ] Operation Timeline 后端持久化
- [ ] Snapshot / Restore UI
- [ ] Context Viewer

### 第五阶段

- [ ] i18n
- [ ] API 按领域拆分
- [ ] Token / Cost / Usage UI

---

## 7. 最终目标

UIDemo 的最终目标不是做成一个“好看的 AI Dashboard”。

它应该逐步成为：

> **Flux 的 AI Engineering Workspace**

用户在同一个工作区完成：

```
Requirement
    ↓
Project Understanding
    ↓
Context
    ↓
Agent Team
    ↓
Plan
    ↓
Proposal
    ↓
Virtual Workspace
    ↓
Human Review
    ↓
Apply
    ↓
Test
    ↓
Operation Timeline
    ↓
Git
    ↓
PR
```

其中最重要的原则：

> **AI 可以负责工程执行，但用户始终能够看到 AI 知道什么、做了什么、修改了什么，以及最终为什么产生这个代码结果。**

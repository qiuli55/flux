# Flux UI 信息层级与 IDE 面板规范

## 1. 目标

Flux 的 UI 不追求“把所有能力都放在第一屏”，而是让用户首先看到 AI Coding 的核心闭环：

> 需求 → Agent → 计划 → 执行 → 变更审核 → 代码

因此所有功能分为三层：

### P0 · 核心功能

用户日常开发时应保持最高视觉优先级。

- Solo
- IDE
- 文件资源管理器
- 当前 Agent / 当前任务
- AI 任务流
- 变更提案 / Diff 审核

### P1 · 辅助开发功能

需要时快速打开，但不应与 P0 抢占视觉空间。

- 搜索
- Git
- Terminal
- 项目状态
- 日志
- 测试 / 问题 / 调试

### P2 · 平台管理功能

不属于日常代码编辑工作区，应逐步迁移到平台管理入口或 Command Center。

- Agent 管理
- Models / Providers
- Skills
- Connectors
- Marketplace
- Usage / Cost
- Settings
- 诊断与系统日志

## 2. Solo 与 IDE 的职责

### Solo

回答“我要让 Flux 做什么”。

主要内容：需求澄清、任务计划、执行过程、决策点、任务结果。

### IDE

回答“代码现在是什么状态，以及 AI 准备改什么”。

主要内容：文件、编辑器、Agent 当前任务、变更 Diff、基础 Git 状态。

## 3. IDE 面板层级

### 左侧 Rail

默认顺序：

1. Files：P0，默认打开
2. Search：P1
3. Git：P1
4. Debug：P1，默认不占永久 Rail 空间
5. Extensions：P2，默认不占永久 Rail 空间

Debug / Extensions 应通过 Command Palette 或后续 More/Tools 入口访问。

### 右侧 Agent

保持为 IDE 的核心面板，但内部采用：

1. 当前 Agent / 状态
2. 当前任务
3. 执行计划
4. 变更提案
5. Agent 团队

其中 1～3 是 P0；变更是 P0/P1；Agent 团队属于 P1。

### 底部 Panel

优先级：

1. AI 任务流：P0
2. 变更提案：P0
3. Git：P1

底部 Panel 默认只承担实时工作流信息，不重复展示右侧 Agent 已经展示的完整内容。

## 4. 空间原则

- 编辑器永远是桌面 IDE 的最大视觉区域。
- 右侧 Agent 面板保持足够宽度，但不应吞掉主要代码空间。
- 文件树从 232px 缩减为约 208px，Agent 从 320px 缩减为约 286px。
- Minimap 降低视觉权重，避免与代码正文竞争。
- Secondary 工具使用更弱的视觉权重，而不是删除功能。
- 不通过缩小所有字体解决拥挤问题；优先解决信息层级。

## 5. 当前实现

`apps/web-dashboard/src/styles/ui-hierarchy.css` 是 UI 信息层级覆盖层，放在 `design-v2.css` 与 `product.css` 之后加载。

当前已完成：

- 缩窄文件树与 Agent 面板，释放编辑器空间
- Files 视觉上保持主入口
- Search / Git 降低视觉权重
- Debug / Extensions 不再永久占据 Rail
- AI Flow / Changes 保持底部核心地位，Git 降级为辅助
- Agent 内部间距压缩，减少重复信息造成的拥挤
- 编辑器 Tab / breadcrumb 高度压缩
- Minimap 降低宽度和透明度
- 顶部工具按钮降低视觉噪声

## 6. 后续 UI 方向

下一阶段不要继续堆按钮，而应增加一个统一的 `More / Command Center` 能力入口，把 P1/P2 功能按场景组织起来。

最终目标不是“功能少”，而是：

> 第一眼只看到完成当前开发任务所需要的东西；其他能力在需要时出现。

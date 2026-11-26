# Agent 图标来源与许可

Android 输入栏 / Agent 选择面板里的运行时图标，只用于标识当前执行 Agent 的 runtime
（`claude-code` / `codex` / `opencode`），不用于 Flux 自身品牌。

所有图标均取自官方站点或官方仓库的公开资源，按官方 SVG 原始 path 生成 VectorDrawable，
没有手绘或重新设计。遇到没有官方可再分发文件的 runtime 时，代码会回落到中性占位图标
（`Icons.Outlined.SmartToy`），不会伪造官方标。

## Claude Code

- 文件：`res/drawable/ic_agent_claude_code.xml`
- 运行时匹配：`claude-code`（兼容 `claude` / `claude_code`）
- 来源：Claude Code 官方文档站 logo
  - 页面：https://code.claude.com/docs
  - 资源：https://mintcdn.com/claude-code/c5r9_6tjPMzFdDDT/logo/dark.svg
- 使用范围：仅截取该官方 SVG 的第一个 path（星芒标记本体）。
- 许可 / 商标：Claude、Claude Code、Anthropic 为其权利人商标；此处按“标识被描述的
  运行时”的指名性用途使用，不暗示官方背书。官方资源未附独立开源许可，若后续要再分发
  请先向权利人确认。

## Codex

- 文件：`res/drawable/ic_agent_codex.xml`
- 运行时匹配：`codex`
- 来源：OpenAI 官方站点图标
  - 资源：https://openai.com/favicon.svg
  - 官方产品页：https://openai.com/codex/
- 使用范围：仅截取官方 SVG 中的 blossom path，未重绘。
- 许可 / 商标：OpenAI、Codex、OpenAI blossom 为其权利人商标；仅用于标识 codex 运行时。
  官方资源未附独立开源许可，若后续要再分发请先向权利人确认。

## OpenCode

- 文件：`res/drawable/ic_agent_opencode.xml`
- 运行时匹配：`opencode`
- 来源：OpenCode 官网 favicon
  - 资源：https://opencode.ai/favicon.svg
  - 官方仓库：https://github.com/sst/opencode
- 使用范围：只截取官方 SVG 的两个 path，并去掉深色底，保留官方几何形状。
- 许可：OpenCode 仓库为 MIT License（https://github.com/sst/opencode/blob/dev/LICENSE，
  Copyright (c) 2025 opencode）；商标归 OpenCode 项目所有，仅用于标识运行时。

## 主题适配

Codex / OpenCode 的填充色引用 `@color/agent_icon_ink` 与 `@color/agent_icon_ink_muted`，
`values/colors.xml` 为浅色（近黑 / 中灰），`values-night/colors.xml` 为深色（近白 / 浅灰），
保证两套模式下都清晰。Claude Code 使用其官方品牌橙 `#D97757`，在深浅色下都可见。

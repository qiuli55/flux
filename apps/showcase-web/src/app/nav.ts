/**
 * 导航结构：定义演示页面的分组、标签与图标。
 * 单一真源——rail 侧栏、命令面板、路由元数据都从这里读取。
 *
 * 信息架构（2026-10 收敛）：一级只保留「工作台 / 能力 / 组织 / 配置」四组。
 * 模型与供应商、Agent 档案收进「设置」；变更审核、终端、版本控制、测试、日志不再占一级入口，
 * 而是内嵌进 IDE（变更审核在侧栏 + 编辑器 diff）与 Solo —— 具体见 pages/Ide.tsx 与 pages/Solo.tsx。
 * 当前为个人版：不含团队/组织成员等企业能力（后续版本单独收费）。
 */
export interface PageMeta {
  id: string;
  label: string;
  icon: string;
  desc: string;
}

export interface NavGroup {
  title: string;
  pages: PageMeta[];
}

export const NAV: NavGroup[] = [
  {
    title: "工作台",
    pages: [
      { id: "overview", label: "概览", icon: "home", desc: "工作区态势、快速入口与全局动态" },
      { id: "solo", label: "Solo 任务中心", icon: "sparkles", desc: "需求澄清 → 计划 → 执行 → 结果的对话式任务流" },
      { id: "ide", label: "IDE 工作台", icon: "code", desc: "文件、编辑器、变更审核、Agent 对话与可伸缩的内嵌终端" },
      { id: "tasks", label: "任务看板", icon: "layers", desc: "按状态分列的任务看板，支持拖拽流转" },
    ],
  },
  {
    title: "能力",
    pages: [
      { id: "skills", label: "Skills", icon: "book", desc: "能力包市场与已安装 Skill" },
      { id: "connectors", label: "Connectors", icon: "plug", desc: "外部系统连接器与授权" },
      { id: "marketplace", label: "Marketplace", icon: "store", desc: "插件、模板与资产的统一市场" },
    ],
  },
  {
    title: "组织",
    pages: [
      { id: "cost", label: "用量与成本", icon: "chart", desc: "Token 用量、费用分布与预算控制" },
      { id: "diagnostics", label: "系统诊断", icon: "cpu", desc: "健康检查、资源配置与运行环境" },
    ],
  },
  {
    title: "配置",
    pages: [
      { id: "settings", label: "设置", icon: "settings", desc: "模型、Agent、编辑器、终端与通知" },
      { id: "account", label: "账号", icon: "user", desc: "个人资料、安全与登录方式" },
    ],
  },
];

/** 认证类页面（无应用壳，独立居中布局）。 */
export const AUTH_PAGES = ["login", "register", "forgot"] as const;

export const ALL_PAGES: PageMeta[] = NAV.flatMap((g) => g.pages);

export function pageMeta(id: string): PageMeta {
  return ALL_PAGES.find((p) => p.id === id) ?? ALL_PAGES[0];
}

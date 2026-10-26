/**
 * 全局导航定义：把路由视图映射为侧栏条目、模式切换与面包屑文案。
 * Solo / IDE 作为「模式」入口置于品牌下方（核心日常入口），其余视图归入侧栏「平台」分组。
 * 供 Shell（侧栏 / 模式 / 顶栏）与命令面板共享，避免多处各写一份视图清单。
 */
import type { View } from "../app/router";

export interface NavItem { id: View; label: string; icon: string }
export interface NavGroup { label: string; items: NavItem[] }

/** 模式切换（核心入口）：Solo / IDE。 */
export const NAV_MODES: NavItem[] = [
  { id: "solo", label: "Solo", icon: "sparkles" },
  { id: "ide", label: "IDE", icon: "code" },
];

/** 侧栏分组：平台层页面。 */
export const NAV_GROUPS: NavGroup[] = [
  {
    label: "平台",
    items: [
      { id: "overview", label: "概览", icon: "home" },
      { id: "tasks", label: "任务", icon: "layers" },
      { id: "skills", label: "Skills", icon: "puzzle" },
      { id: "billing", label: "成本中心", icon: "credit" },
      { id: "diagnostics", label: "系统诊断", icon: "activity" },
      { id: "settings", label: "设置", icon: "settings" },
    ],
  },
];

/** 视图 → 中文名（面包屑、命令面板页脚用）。 */
export const VIEW_LABEL: Record<View, string> = {
  overview: "概览",
  tasks: "任务",
  solo: "Solo 执行",
  ide: "IDE 工作台",
  skills: "Skills",
  billing: "成本中心",
  diagnostics: "系统诊断",
  settings: "设置",
};

/** 视图 → 所属分组名（面包屑前一段）。 */
export const VIEW_GROUP: Record<View, string> = {
  overview: "平台",
  tasks: "平台",
  solo: "核心",
  ide: "核心",
  skills: "平台",
  billing: "平台",
  diagnostics: "平台",
  settings: "平台",
};

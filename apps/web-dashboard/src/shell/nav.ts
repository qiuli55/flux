/**
 * 全局导航定义：把路由视图映射为侧栏条目与面包屑文案。
 * 供 Shell（侧栏/顶栏）与命令面板共享，避免两处各写一份视图清单。
 */
import type { View } from "../app/router";

export interface NavItem { id: View; label: string; icon: string }
export interface NavGroup { label: string; items: NavItem[] }

/** 侧栏分组；label 为空表示不显示分组标题（用于底部单列项）。 */
export const NAV_GROUPS: NavGroup[] = [
  {
    label: "工作区",
    items: [
      { id: "overview", label: "概览", icon: "home" },
      { id: "tasks", label: "任务", icon: "layers" },
      { id: "solo", label: "Solo 执行", icon: "sparkles" },
      { id: "ide", label: "IDE 工作台", icon: "code" },
    ],
  },
  {
    label: "能力",
    items: [
      { id: "skills", label: "Skills", icon: "puzzle" },
      { id: "diagnostics", label: "系统诊断", icon: "activity" },
    ],
  },
  { label: "", items: [{ id: "settings", label: "设置", icon: "settings" }] },
];

/** 视图 → 中文名（面包屑、命令面板页脚用）。 */
export const VIEW_LABEL: Record<View, string> = {
  overview: "概览",
  tasks: "任务",
  solo: "Solo 执行",
  ide: "IDE 工作台",
  skills: "Skills",
  diagnostics: "系统诊断",
  settings: "设置",
};

/** 视图 → 所属分组名（面包屑前一段）。 */
export const VIEW_GROUP: Record<View, string> = {
  overview: "工作区",
  tasks: "工作区",
  solo: "工作区",
  ide: "工作区",
  skills: "能力",
  diagnostics: "能力",
  settings: "设置",
};

/**
 * 极简路由：区分全局外壳下的各视图（概览 / Solo / IDE / 任务 / Skills / 诊断 / 设置）。
 *
 * 用 History API 生成真实路径（/overview、/solo、/ide、/tasks、/skills、/diagnostics、/settings）。
 * 部署前缀（如 /flux-v2/）优先按当前地址推断：深链 /flux-v2/ide 要落到 IDE，且进 IDE 后地址栏必须保留 /flux-v2 前缀（D-01）。
 *
 * 变更说明（相对旧版）：View 由 solo|ide 扩展为 7 个视图，detectBase 的已知段集合同步扩大，
 * 否则新路径的第一段会被误判为部署前缀；默认视图由 solo 改为 overview。
 */
import { useCallback, useEffect, useState } from "react";

export type View = "overview" | "solo" | "ide" | "tasks" | "skills" | "diagnostics" | "settings";

export const VIEWS: readonly View[] = ["overview", "solo", "ide", "tasks", "skills", "diagnostics", "settings"];
export const DEFAULT_VIEW: View = "overview";

/** dev 为 "/"，若产物按子路径构建则为该子路径 */
const BUILD_BASE = import.meta.env.BASE_URL;

const isView = (s: string): s is View => (VIEWS as readonly string[]).includes(s);

/**
 * 推断部署前缀：
 * - 地址是 /flux-v2/solo、/flux-v2/ide 时前缀为 /flux-v2/
 * - 地址是 /solo、/ide（无前缀部署或 dev）时前缀为构建基址
 */
export function detectBase(pathname: string): string {
  const first = /^\/([^/]+)\//.exec(pathname)?.[1];
  if (first && !isView(first)) return `/${first}/`;
  return BUILD_BASE;
}

/** 从路径解析视图（导出以便单测；未知段回退到默认视图）。 */
export function viewFromPath(pathname: string): View {
  const base = detectBase(pathname);
  const rest = pathname.startsWith(base) ? pathname.slice(base.length) : pathname.replace(/^\//, "");
  const seg = rest.split("/")[0] ?? "";
  return isView(seg) ? seg : DEFAULT_VIEW;
}

/** 视图对应的真实路径（带当前部署前缀） */
export function pathFor(view: View, pathname: string): string {
  return `${detectBase(pathname)}${view}`;
}

export function useView(): [View, (next: View) => void] {
  const [view, setView] = useState<View>(() => viewFromPath(window.location.pathname));

  useEffect(() => {
    const onPop = () => setView(viewFromPath(window.location.pathname));
    window.addEventListener("popstate", onPop);
    return () => window.removeEventListener("popstate", onPop);
  }, []);

  const navigate = useCallback((next: View) => {
    setView(next);
    const path = pathFor(next, window.location.pathname);
    if (window.location.pathname !== path) window.history.pushState(null, "", path);
  }, []);

  return [view, navigate];
}

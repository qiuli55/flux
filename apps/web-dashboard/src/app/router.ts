/**
 * 极简路由：只区分两个视图（Solo 任务执行中心 / IDE 工作台）。
 *
 * 用 History API 生成真实路径（/solo、/ide）。部署前缀（如 /flux-v2/）优先按当前地址推断：
 * 深链 /flux-v2/ide 要落到 IDE，且进 IDE 后地址栏必须保留 /flux-v2 前缀（D-01）。
 */
import { useCallback, useEffect, useState } from "react";

export type View = "solo" | "ide";

/** dev 为 "/"，若产物按子路径构建则为该子路径 */
const BUILD_BASE = import.meta.env.BASE_URL;

/**
 * 推断部署前缀：
 * - 地址是 /flux-v2/solo、/flux-v2/ide 时前缀为 /flux-v2/
 * - 地址是 /solo、/ide（无前缀部署或 dev）时前缀为构建基址
 */
export function detectBase(pathname: string): string {
  const first = /^\/([^/]+)\//.exec(pathname)?.[1];
  if (first && first !== "solo" && first !== "ide") return `/${first}/`;
  return BUILD_BASE;
}

function viewFromPath(pathname: string): View {
  const base = detectBase(pathname);
  const rest = pathname.startsWith(base) ? pathname.slice(base.length) : pathname.replace(/^\//, "");
  return rest.startsWith("ide") ? "ide" : "solo";
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
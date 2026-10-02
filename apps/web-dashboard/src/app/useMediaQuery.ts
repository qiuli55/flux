/**
 * 响应式断点 hook。
 *
 * 与 design-v2.css 的移动端断点 `@media (max-width: 860px)` 保持一致：
 * 布局差异交给 CSS，需要渲染不同结构（底部 Tab、抽屉面板）时用本 hook 判断。
 */
import { useEffect, useState } from "react";

/** 移动端查询条件（CSS 里同一断点） */
export const MOBILE_QUERY = "(max-width: 860px)";

export function useMediaQuery(query: string): boolean {
  const [matches, setMatches] = useState<boolean>(
    () => typeof window !== "undefined" && window.matchMedia(query).matches,
  );

  useEffect(() => {
    const mql = window.matchMedia(query);
    const onChange = () => setMatches(mql.matches);
    onChange();
    mql.addEventListener("change", onChange);
    return () => mql.removeEventListener("change", onChange);
  }, [query]);

  return matches;
}
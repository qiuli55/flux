/**
 * 极简 hash 路由：从 location.hash 解析当前页面 id，navigate 只改 hash。
 * 默认页为 overview；未知 id 回退到 overview。
 */
import { useCallback, useEffect, useState } from "react";
import { ALL_PAGES, AUTH_PAGES } from "./nav";

const KNOWN = new Set<string>([...ALL_PAGES.map((p) => p.id), ...AUTH_PAGES]);

function read(): string {
  const raw = window.location.hash.replace(/^#\/?/, "");
  const id = raw.split("?")[0];
  return KNOWN.has(id) ? id : "overview";
}

export function useRoute(): [string, (id: string) => void] {
  const [id, setId] = useState<string>(read);

  useEffect(() => {
    const onHash = () => setId(read());
    window.addEventListener("hashchange", onHash);
    if (!window.location.hash) window.history.replaceState(null, "", "#/overview");
    return () => window.removeEventListener("hashchange", onHash);
  }, []);

  const navigate = useCallback((next: string) => {
    window.location.hash = `#/${next}`;
  }, []);

  return [id, navigate];
}

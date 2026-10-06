import { useEffect, useState } from "react";
import "./workspace-picker.css";

interface DesktopBridge {
  isDesktop: boolean;
  platform: string;
  chooseWorkspaceRoot: () => Promise<string | null>;
  onWorkspaceChanged: (callback: (root: string) => void) => () => void;
}

type FluxWindow = Window & { fluxDesktop?: DesktopBridge };

export function WorkspacePicker() {
  const [root, setRoot] = useState("");
  const [open, setOpen] = useState(false);
  const [busy, setBusy] = useState(false);
  const desktop = (window as FluxWindow).fluxDesktop;

  useEffect(() => {
    let alive = true;
    fetch("/api/v1/workspace/root")
      .then((response) => response.json())
      .then((payload) => {
        if (alive && payload?.success && typeof payload.data?.root === "string") setRoot(payload.data.root);
      })
      .catch(() => undefined);
    const unsubscribe = desktop?.onWorkspaceChanged?.((nextRoot) => setRoot(nextRoot));
    return () => {
      alive = false;
      unsubscribe?.();
    };
  }, [desktop]);

  const choose = async () => {
    if (!desktop?.chooseWorkspaceRoot) {
      setOpen(false);
      return;
    }
    setBusy(true);
    try {
      const nextRoot = await desktop.chooseWorkspaceRoot();
      if (nextRoot) setRoot(nextRoot);
      setOpen(false);
    } finally {
      setBusy(false);
    }
  };

  const name = root ? root.split(/[\\/]/).filter(Boolean).at(-1) ?? root : "Workspace";

  return (
    <div className="flux-workspace-picker">
      <button
        type="button"
        className="flux-workspace-trigger"
        onClick={() => setOpen((value) => !value)}
        title={root || "选择工作目录"}
        aria-expanded={open}
      >
        <span className="flux-workspace-icon">⌂</span>
        <span className="flux-workspace-name">{name}</span>
        <span className="flux-workspace-chevron">⌄</span>
      </button>

      {open ? (
        <>
          <button type="button" className="flux-workspace-backdrop" aria-label="关闭工作目录菜单" onClick={() => setOpen(false)} />
          <div className="flux-workspace-menu">
            <div className="flux-workspace-menu-label">WORKSPACE</div>
            <div className="flux-workspace-current" title={root || undefined}>{root || "尚未选择工作目录"}</div>
            <button type="button" className="flux-workspace-open" onClick={() => void choose()} disabled={busy || !desktop}>
              <span>▣</span>{busy ? "正在打开…" : "打开本地文件夹…"}
            </button>
            {!desktop ? <div className="flux-workspace-hint">本地目录选择需要使用 Flux 桌面版</div> : null}
          </div>
        </>
      ) : null}
    </div>
  );
}

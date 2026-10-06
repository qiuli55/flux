import { useEffect, useMemo, useState } from "react";
import { api } from "../../api/client";
import { toast } from "../../app/toast";
import "./explorer-context-menu.css";

type ExplorerKind = "file" | "dir" | "root";
interface Target {
  path: string;
  kind: ExplorerKind;
  x: number;
  y: number;
}

function getRelativePath(row: HTMLElement): string {
  const parts: string[] = [];
  let li: HTMLElement | null = row.closest("li");
  while (li) {
    const directRow = Array.from(li.children).find(
      (child) => child instanceof HTMLElement && child.classList.contains("t-row"),
    ) as HTMLElement | undefined;
    const name = directRow?.querySelector(".t-name")?.textContent?.trim();
    if (name) parts.unshift(name);
    const parentList = li.parentElement?.closest("li");
    li = parentList instanceof HTMLElement ? parentList : null;
  }
  if (parts.length <= 1) return "";
  parts.shift();
  return parts.join("/");
}

function joinPath(base: string, name: string): string {
  const cleanBase = base.replace(/\\+$/g, "").replace(/^\\+|^\/+/g, "");
  const cleanName = name.replace(/^[\\/]+/, "");
  return cleanBase ? `${cleanBase}/${cleanName}` : cleanName;
}

async function copyText(value: string): Promise<void> {
  try {
    await navigator.clipboard.writeText(value);
    toast("已复制路径");
  } catch {
    toast("无法访问系统剪贴板，请检查桌面权限", "error");
  }
}

export function ExplorerContextMenu() {
  const [target, setTarget] = useState<Target | null>(null);
  const [workspaceRoot, setWorkspaceRoot] = useState("");
  const [projectId, setProjectId] = useState<string | null>(null);

  useEffect(() => {
    const refreshProject = () => {
      const root = document.querySelector<HTMLElement>(".view-ide");
      setProjectId(root?.dataset.projectId || null);
    };
    refreshProject();

    const onContextMenu = (event: MouseEvent) => {
      const raw = event.target instanceof Element ? event.target.closest(".view-ide .t-row") : null;
      if (!(raw instanceof HTMLElement)) return;
      const row = raw;
      const ownerLi = row.closest("li");
      const kind: ExplorerKind = row.classList.contains("is-file")
        ? "file"
        : ownerLi?.classList.contains("t-root")
          ? "root"
          : "dir";
      const path = getRelativePath(row);
      event.preventDefault();
      setTarget({
        path,
        kind,
        x: Math.min(event.clientX, window.innerWidth - 256),
        y: Math.min(event.clientY, window.innerHeight - 320),
      });
      const root = document.querySelector<HTMLElement>(".view-ide");
      setWorkspaceRoot(root?.dataset.workspaceRoot || "");
    };

    const close = () => setTarget(null);
    document.addEventListener("contextmenu", onContextMenu);
    window.addEventListener("pointerdown", close);
    window.addEventListener("scroll", close, true);
    return () => {
      document.removeEventListener("contextmenu", onContextMenu);
      window.removeEventListener("pointerdown", close);
      window.removeEventListener("scroll", close, true);
    };
  }, []);

  const parentPath = useMemo(() => {
    if (!target) return "";
    if (target.kind !== "file" && target.kind !== "dir") return "";
    const slash = target.path.lastIndexOf("/");
    return slash >= 0 ? target.path.slice(0, slash) : "";
  }, [target]);

  if (!target) return null;

  const targetDir = target.kind === "dir" || target.kind === "root" ? target.path : parentPath;

  const refresh = () => {
    setTarget(null);
    window.dispatchEvent(new Event("flux:explorer-refresh"));
  };

  const ensureProject = () => {
    if (!projectId) {
      toast("当前没有可用的 Workspace Project", "error");
      return false;
    }
    return true;
  };

  const createEntry = async (directory: boolean) => {
    if (!ensureProject()) return;
    const name = window.prompt(directory ? "新建文件夹" : "新建文件", "");
    if (name === null) return;
    const clean = name.trim();
    if (!clean) return;
    try {
      const path = joinPath(targetDir, clean);
      if (directory) {
        await api.createWorkspaceDirectory(projectId!, path);
      } else {
        await api.createWorkspaceFile(projectId!, path);
      }
      toast(`已创建 ${path}`);
      refresh();
    } catch (error) {
      toast(error instanceof Error ? error.message : String(error), "error");
    }
  };

  const renameEntry = async () => {
    if (!ensureProject() || target.kind === "root") return;
    const currentName = target.path.split("/").at(-1) || target.path;
    const name = window.prompt("重命名", currentName);
    if (name === null) return;
    const clean = name.trim();
    if (!clean || clean === currentName) return;
    const nextPath = joinPath(parentPath, clean);
    try {
      await api.renameWorkspacePath(projectId!, target.path, nextPath);
      toast(`已重命名为 ${clean}`);
      refresh();
    } catch (error) {
      toast(error instanceof Error ? error.message : String(error), "error");
    }
  };

  const deleteEntry = async () => {
    if (!ensureProject() || target.kind === "root") return;
    const name = target.path.split("/").at(-1) || target.path;
    const warning = target.kind === "dir"
      ? `确定删除文件夹「${name}」及其全部内容？此操作不可自动恢复。`
      : `确定删除文件「${name}」？`;
    if (!window.confirm(warning)) return;
    try {
      await api.deleteWorkspacePath(projectId!, target.path);
      toast(`已删除 ${target.path}`);
      refresh();
    } catch (error) {
      toast(error instanceof Error ? error.message : String(error), "error");
    }
  };

  const openTarget = () => {
    const row = Array.from(document.querySelectorAll<HTMLElement>(".view-ide .t-row")).find(
      (item) => getRelativePath(item) === target.path,
    );
    if (row) row.click();
    setTarget(null);
  };

  const relativeCopy = target.path || ".";
  const absoluteCopy = workspaceRoot
    ? `${workspaceRoot.replace(/[\\/]+$/, "")}${workspaceRoot.includes("\\") ? "\\" : "/"}${relativeCopy.replace(/\//g, workspaceRoot.includes("\\") ? "\\" : "/")}`
    : relativeCopy;

  return (
    <div
      className="explorer-context-menu"
      style={{ left: target.x, top: target.y }}
      role="menu"
      onPointerDown={(event) => event.stopPropagation()}
    >
      {target.kind === "file" ? (
        <button type="button" role="menuitem" onClick={openTarget}>打开</button>
      ) : null}
      {target.kind === "file" || target.kind === "dir" || target.kind === "root" ? (
        <>
          <button type="button" role="menuitem" onClick={() => void createEntry(false)}>新建文件</button>
          <button type="button" role="menuitem" onClick={() => void createEntry(true)}>新建文件夹</button>
        </>
      ) : null}
      {target.kind !== "root" ? (
        <button type="button" role="menuitem" onClick={() => void renameEntry()}>重命名</button>
      ) : null}
      {target.kind !== "root" ? (
        <button type="button" role="menuitem" className="danger" onClick={() => void deleteEntry()}>删除</button>
      ) : null}
      <div className="explorer-context-separator" />
      <button type="button" role="menuitem" onClick={() => void copyText(relativeCopy)}>复制相对路径</button>
      <button type="button" role="menuitem" onClick={() => void copyText(absoluteCopy)}>复制绝对路径</button>
    </div>
  );
}

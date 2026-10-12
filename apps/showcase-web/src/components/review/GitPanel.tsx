/**
 * 版本控制面板（IDE 侧栏）：对齐后端 /api/v1/git 的本地、可回退操作。
 * 只做 status / stage / unstage / branch / commit；没有 push / reset / force。
 */
import { useState } from "react";
import { Icon } from "../ui/Icon";
import { Badge, Button, Input } from "../ui/primitives";
import { useToast } from "../../app/Toast";
import { gitBranches, gitStatusFiles } from "../../data/review";

type FileRow = { path: string; index: string; worktree: string; staged: boolean; untracked: boolean };

function shortName(p: string): string {
  return p.split("/").slice(-1)[0];
}

function FileRowView({ f, onToggle }: { f: FileRow; onToggle: () => void }) {
  const code = f.staged ? f.index : f.worktree;
  const tone = f.untracked || code === "?" ? "green" : code === "A" ? "brand" : "orange";
  return (
    <div className="git-file">
      <span className="mono ellipsis grow" title={f.path}>
        {shortName(f.path)}
      </span>
      <Badge tone={tone as "green" | "brand" | "orange"}>{f.untracked ? "U" : code}</Badge>
      <button className="coldot" title={f.staged ? "取消暂存" : "暂存"} onClick={onToggle}>
        <Icon name={f.staged ? "close" : "plus"} className="icon-sm" />
      </button>
    </div>
  );
}

export function GitPanel() {
  const [files, setFiles] = useState<FileRow[]>(gitStatusFiles.map((f) => ({ ...f })));
  const [msg, setMsg] = useState("feat(review): 变更审核并入 IDE，支持整组落盘与回滚");
  const [branch, setBranch] = useState(gitBranches.current);
  const toast = useToast();

  const toggle = (path: string) =>
    setFiles((prev) => prev.map((f) => (f.path === path ? { ...f, staged: !f.staged } : f)));
  const staged = files.filter((f) => f.staged);
  const unstaged = files.filter((f) => !f.staged);

  const commit = () => {
    if (!staged.length || !msg.trim()) {
      toast("先暂存改动并填写提交信息");
      return;
    }
    toast(`已提交 ${staged.length} 个文件（本地 commit，不推送）`);
    setFiles((prev) => prev.filter((f) => !f.staged));
  };

  return (
    <div className="git-panel">
      <div className="ide-colhead">版本控制</div>
      <div className="git-scroll">
        <div className="git-branch-row">
          <Icon name="branch" className="icon-sm" />
          <select className="git-branch-select" value={branch} onChange={(e) => setBranch(e.target.value)}>
            {gitBranches.locals.map((b) => (
              <option key={b} value={b}>
                {b}
              </option>
            ))}
          </select>
          <span className="grow" />
          <span className="dim">{files.length} 处改动</span>
        </div>

        <div className="tool-caption">已暂存 · {staged.length}</div>
        {staged.map((f) => (
          <FileRowView key={f.path} f={f} onToggle={() => toggle(f.path)} />
        ))}

        <div className="tool-caption" style={{ marginTop: 12 }}>
          改动 · {unstaged.length}
        </div>
        {unstaged.map((f) => (
          <FileRowView key={f.path} f={f} onToggle={() => toggle(f.path)} />
        ))}
      </div>
      <div className="git-commit">
        <Input value={msg} onChange={(e) => setMsg(e.target.value)} placeholder="提交信息" />
        <Button variant="primary" block icon="check" onClick={commit}>
          提交 {staged.length} 个文件
        </Button>
        <div className="dim git-hint">仅本地提交，Flux 不推送、不 force、不 reset</div>
      </div>
    </div>
  );
}

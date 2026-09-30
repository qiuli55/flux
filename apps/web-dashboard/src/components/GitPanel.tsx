/** Git 面板：分支与工作区改动、提交框（提交信息可改）、提交结果。 */
import type { JSX } from "react";

import type { GitCommit, GitStatus } from "../api/types";
import { Button, EmptyState, ErrorBanner, Tag } from "./ui";

export function GitPanel({
  status,
  loading,
  error,
  onDismissError,
  onRefresh,
  commitMessage,
  onCommitMessageChange,
  onCommit,
  commitBusy,
  commitError,
  onDismissCommitError,
  lastCommit,
  appliedCount,
}: {
  status: GitStatus | null;
  loading: boolean;
  error: string | null;
  onDismissError: () => void;
  onRefresh: () => void;
  commitMessage: string;
  onCommitMessageChange: (value: string) => void;
  onCommit: () => void;
  commitBusy: boolean;
  commitError: string | null;
  onDismissCommitError: () => void;
  lastCommit: GitCommit | null;
  appliedCount: number;
}): JSX.Element {
  return (
    <div className="flex min-h-0 flex-1 gap-3 p-3">
      <div className="flex min-h-0 flex-1 flex-col gap-2">
        <div className="flex shrink-0 flex-wrap items-center justify-between gap-2">
          <div className="flex items-center gap-2 text-xs">
            <span className="text-faint">分支</span>
            <span className="font-mono text-text">
              {status ? (status.detached ? "（游离 HEAD）" : (status.branch ?? "未知")) : "—"}
            </span>
            {status ? (
              <Tag className={status.clean ? "text-add" : "text-warn"}>
                {status.clean ? "工作区干净" : `${status.files.length} 个改动文件`}
              </Tag>
            ) : null}
          </div>
          <Button tone="ghost" onClick={onRefresh} busy={loading} title="重新拉取 git status">
            刷新 Git
          </Button>
        </div>

        {error ? <ErrorBanner message={error} onDismiss={onDismissError} /> : null}

        <div className="min-h-0 flex-1 overflow-y-auto rounded-md border border-line bg-surface-2">
          {!status ? (
            <EmptyState>{loading ? "正在读取 git status…" : "尚未获取 Git 状态。"}</EmptyState>
          ) : status.files.length === 0 ? (
            <EmptyState>工作区没有未提交的改动。</EmptyState>
          ) : (
            <ul className="divide-y divide-line-soft">
              {status.files.map((file) => (
                <li key={file.path} className="flex items-center gap-2 px-2.5 py-1.5 text-xs">
                  <span className="w-4 shrink-0 text-center font-mono text-faint">{file.index_status}</span>
                  <span className="w-4 shrink-0 text-center font-mono text-faint">{file.worktree_status}</span>
                  <span className="min-w-0 flex-1 truncate font-mono text-text">{file.path}</span>
                  {file.untracked ? <Tag className="text-warn">未跟踪</Tag> : null}
                  {file.staged ? <Tag className="text-add">已暂存</Tag> : null}
                  {file.original_path ? (
                    <span className="shrink-0 text-[10px] text-faint">← {file.original_path}</span>
                  ) : null}
                </li>
              ))}
            </ul>
          )}
        </div>
      </div>

      <div className="flex w-[20rem] shrink-0 flex-col gap-2">
        <label className="text-[11px] text-faint" htmlFor="commit-message">
          提交信息（可修改，只提交已落盘的变更）
        </label>
        <textarea
          id="commit-message"
          value={commitMessage}
          onChange={(event) => onCommitMessageChange(event.target.value)}
          rows={3}
          maxLength={2000}
          placeholder="feat: 描述本次改动"
          className="resize-none rounded-md border border-line bg-surface-2 px-2.5 py-2 text-xs leading-relaxed text-text outline-none placeholder:text-faint focus:border-accent/60"
        />
        <div className="flex items-center justify-between gap-2">
          <span className="text-[11px] text-faint">已落盘变更 {appliedCount} 条</span>
          <Button
            tone="primary"
            busy={commitBusy}
            disabled={appliedCount === 0 || !commitMessage.trim()}
            onClick={onCommit}
            title={appliedCount === 0 ? "没有 applied 状态的变更可提交" : "提交已落盘的变更"}
          >
            提交
          </Button>
        </div>

        {commitError ? <ErrorBanner message={commitError} onDismiss={onDismissCommitError} /> : null}

        {lastCommit ? (
          <div className="rounded-md border border-add/30 bg-add/5 p-2.5 text-xs">
            <p className="text-add">提交成功</p>
            <p className="mt-1 font-mono text-text">{lastCommit.short_sha}</p>
            <p className="mt-0.5 break-words text-muted">{lastCommit.message}</p>
            <p className="mt-1 text-[11px] text-faint">
              文件 {lastCommit.files.length} 个：{lastCommit.files.join("、") || "无"}
            </p>
          </div>
        ) : null}
      </div>
    </div>
  );
}
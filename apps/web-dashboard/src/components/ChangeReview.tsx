/** 中栏 · 变更审阅：需求输入 + 让 AI 改、变更列表与状态过滤、提案详情与 diff、审查动作。 */
import { useState, type FormEvent, type JSX } from "react";

import type { Change, ChangeStatus } from "../api/types";
import { shortId } from "../utils/format";
import { DiffViewer } from "./DiffViewer";
import {
  Button,
  ChangeStatusBadge,
  EmptyState,
  ErrorBanner,
  MetaField,
  Panel,
  PanelHeader,
  Paragraph,
  Tag,
} from "./ui";

const STATUS_FILTERS: { value: string; label: string }[] = [
  { value: "", label: "全部" },
  { value: "pending", label: "待审阅" },
  { value: "accepted", label: "已批准" },
  { value: "applied", label: "已落盘" },
  { value: "rejected", label: "已拒绝" },
  { value: "failed", label: "落盘失败" },
];

function countByStatus(changes: Change[], status: ChangeStatus): number {
  return changes.filter((change) => change.status === status).length;
}

export function ChangeReview({
  projectName,
  contextCount,
  instruction,
  onInstructionChange,
  onGenerate,
  generateBusy,
  generateError,
  onDismissGenerateError,
  generateSummary,
  changes,
  filter,
  onFilterChange,
  listBusy,
  listError,
  onDismissListError,
  onReload,
  selected,
  selectedId,
  onSelect,
  detailBusy,
  actionBusyId,
  onAccept,
  onApply,
  onReject,
  actionError,
  onDismissActionError,
}: {
  projectName: string;
  contextCount: number;
  instruction: string;
  onInstructionChange: (value: string) => void;
  onGenerate: () => void;
  generateBusy: boolean;
  generateError: string | null;
  onDismissGenerateError: () => void;
  generateSummary: string | null;
  changes: Change[];
  filter: string;
  onFilterChange: (value: string) => void;
  listBusy: boolean;
  listError: string | null;
  onDismissListError: () => void;
  onReload: () => void;
  selected: Change | null;
  selectedId: string | null;
  onSelect: (changeId: string) => void;
  detailBusy: boolean;
  actionBusyId: string | null;
  onAccept: (changeId: string) => void;
  onApply: (changeId: string) => void;
  onReject: (changeId: string, reason: string) => void;
  actionError: string | null;
  onDismissActionError: () => void;
}): JSX.Element {
  const [rejectReason, setRejectReason] = useState("");

  function handleGenerate(event: FormEvent) {
    event.preventDefault();
    onGenerate();
  }

  const canAccept = selected?.status === "pending";
  const canApply = selected?.status === "pending" || selected?.status === "accepted";
  const canReject = selected?.status === "pending" || selected?.status === "accepted";

  return (
    <Panel className="flex-1">
      <PanelHeader
        title="变更审阅"
        subtitle={`${projectName} · 上下文文件 ${contextCount} 个 · 共 ${changes.length} 条变更`}
        actions={
          <Button tone="ghost" onClick={onReload} busy={listBusy} title="重新拉取变更列表">
            刷新列表
          </Button>
        }
      />

      {/* 需求输入 */}
      <form onSubmit={handleGenerate} className="shrink-0 space-y-2 border-b border-line-soft p-3">
        <textarea
          value={instruction}
          onChange={(event) => onInstructionChange(event.target.value)}
          rows={2}
          maxLength={4000}
          placeholder="用一句话描述需求，例如：把登录接口加上参数校验与错误处理"
          className="w-full resize-y rounded-md border border-line bg-surface-2 px-2.5 py-2 text-xs leading-relaxed text-text outline-none placeholder:text-faint focus:border-accent/60"
        />
        <div className="flex items-center justify-between gap-2">
          <span className="text-[11px] text-faint">
            Developer Agent 会读取上下文文件的现状，产出待审阅提案（不写用户文件）。
          </span>
          <Button type="submit" tone="primary" busy={generateBusy} disabled={!instruction.trim()}>
            让 AI 改
          </Button>
        </div>
        {generateError ? <ErrorBanner message={generateError} onDismiss={onDismissGenerateError} /> : null}
        {generateSummary ? (
          <div className="rounded-md border border-accent/30 bg-accent/5 px-2.5 py-1.5 text-xs text-muted">
            <span className="text-accent">AI 摘要：</span>
            {generateSummary}
          </div>
        ) : null}
      </form>

      {/* 状态过滤 */}
      <div className="flex shrink-0 flex-wrap items-center gap-1 border-b border-line-soft px-3 py-1.5">
        {STATUS_FILTERS.map((item) => {
          const count =
            item.value === ""
              ? changes.length
              : countByStatus(changes, item.value as ChangeStatus);
          const active = filter === item.value;
          return (
            <button
              key={item.value || "all"}
              type="button"
              onClick={() => onFilterChange(item.value)}
              className={`rounded-full border px-2.5 py-0.5 text-[11px] transition-colors ${
                active
                  ? "border-accent/50 bg-accent/15 text-accent"
                  : "border-line bg-surface-2 text-muted hover:text-text"
              }`}
            >
              {item.label} {count}
            </button>
          );
        })}
      </div>

      {listError ? (
        <div className="shrink-0 px-3 pt-2">
          <ErrorBanner message={listError} onDismiss={onDismissListError} />
        </div>
      ) : null}

      {/* 变更列表 + 详情 */}
      <div className="grid min-h-0 flex-1 grid-cols-1 lg:grid-cols-[minmax(0,14rem)_minmax(0,1fr)]">
        <div className="flex min-h-0 flex-col border-b border-line-soft lg:border-r lg:border-b-0">
          <p className="shrink-0 px-3 py-1.5 text-[11px] text-faint">变更浏览器（点击查看 diff）</p>
          <ul className="min-h-0 flex-1 overflow-y-auto px-2 pb-2">
            {changes.length === 0 ? (
              <li className="px-1 py-2 text-xs text-faint">当前过滤条件下没有变更。</li>
            ) : (
              changes.map((change) => {
                const active = change.id === selectedId;
                return (
                  <li key={change.id} className="mb-1">
                    <button
                      type="button"
                      onClick={() => onSelect(change.id)}
                      className={`w-full rounded-md border px-2 py-1.5 text-left transition-colors ${
                        active
                          ? "border-accent/50 bg-accent/10"
                          : "border-line bg-surface-2 hover:bg-surface-3"
                      }`}
                    >
                      <span className="flex items-center justify-between gap-2">
                        <span className="truncate font-mono text-[11px] text-text">{change.file_path}</span>
                        <span className="shrink-0 text-[10px] text-faint">#{shortId(change.id)}</span>
                      </span>
                      <span className="mt-1 flex items-center gap-1.5">
                        <ChangeStatusBadge status={change.status} />
                        <span className="text-[10px] text-add">+{change.added_lines}</span>
                        <span className="text-[10px] text-del">-{change.removed_lines}</span>
                      </span>
                      {change.summary ? (
                        <span className="mt-1 block truncate text-[11px] text-faint">{change.summary}</span>
                      ) : null}
                    </button>
                  </li>
                );
              })
            )}
          </ul>
        </div>

        <div className="flex min-h-0 flex-col gap-2 p-3">
          {!selected ? (
            <EmptyState>
              {detailBusy
                ? "正在拉取变更详情…"
                : "选中左侧一条变更查看文件路径、状态、改动摘要与左右并排的 diff。"}
            </EmptyState>
          ) : (
            <>
              <div className="flex shrink-0 flex-wrap items-center justify-between gap-2">
                <div className="flex min-w-0 items-center gap-2">
                  <span className="truncate font-mono text-sm text-text">{selected.file_path}</span>
                  <ChangeStatusBadge status={selected.status} />
                </div>
                <div className="flex shrink-0 items-center gap-1.5">
                  {selected.status === "applied" ? <Tag className="text-add">已写入工作区</Tag> : null}
                  <Tag>来自 {selected.agent_source ?? "未知 Agent"}</Tag>
                </div>
              </div>

              <dl className="grid shrink-0 grid-cols-2 gap-x-4 gap-y-1.5 rounded-md border border-line bg-surface-2 p-2.5 sm:grid-cols-4">
                <MetaField label="变更 ID">#{shortId(selected.id)}</MetaField>
                <MetaField label="新增 / 删除">
                  <span className="text-add">+{selected.added_lines}</span>
                  <span className="text-faint"> / </span>
                  <span className="text-del">-{selected.removed_lines}</span>
                </MetaField>
                <MetaField label="改动块">{selected.hunks}</MetaField>
                <MetaField label="关联项目">{selected.project_id ? shortId(selected.project_id) : "未关联"}</MetaField>
              </dl>

              {selected.status === "failed" && selected.apply_error ? (
                <ErrorBanner message={`落盘失败：${selected.apply_error}`} />
              ) : null}

              <div className="grid shrink-0 gap-2 sm:grid-cols-2">
                <Paragraph label="变更摘要（summary）" text={selected.summary} />
                <Paragraph label="修改原因（reason）" text={selected.reason} />
              </div>

              <DiffViewer
                diff={selected.diff}
                filePath={selected.file_path}
                added={selected.added_lines}
                removed={selected.removed_lines}
                hunks={selected.hunks}
              />

              {actionError ? <ErrorBanner message={actionError} onDismiss={onDismissActionError} /> : null}

              <div className="flex shrink-0 flex-wrap items-center gap-2">
                <Button
                  tone="default"
                  disabled={!canAccept}
                  busy={actionBusyId === selected.id}
                  onClick={() => onAccept(selected.id)}
                  title="批准提案但不落盘"
                >
                  批准
                </Button>
                <Button
                  tone="primary"
                  disabled={!canApply}
                  busy={actionBusyId === selected.id}
                  onClick={() => onApply(selected.id)}
                  title="批准并落盘到工作区（会跑项目测试）"
                >
                  落盘
                </Button>
                <div className="ml-auto flex items-center gap-1.5">
                  <input
                    value={rejectReason}
                    onChange={(event) => setRejectReason(event.target.value)}
                    placeholder="拒绝理由，可选"
                    className="w-40 rounded-md border border-line bg-surface-2 px-2 py-1.5 text-[11px] text-text outline-none placeholder:text-faint focus:border-danger/50"
                  />
                  <Button
                    tone="danger"
                    disabled={!canReject}
                    busy={actionBusyId === selected.id}
                    onClick={() => onReject(selected.id, rejectReason.trim())}
                  >
                    拒绝
                  </Button>
                </div>
              </div>
            </>
          )}
        </div>
      </div>
    </Panel>
  );
}
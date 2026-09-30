/**
 * 中栏 · 代码编辑器：表达 Original / Proposed / Modified 三种视图，配合提案状态徽章
 * 覆盖 Original / Proposed / Modified / Pending Review / Applied 五态。
 *
 * - 原始代码：有提案时用提案的 original_content（与 diff 左侧同源），无提案时才读盘；
 * - AI 建议：提案的 proposed_content；
 * - 我的修改：本地草稿，后端没有提案编辑接口，因此永远不能保存、刷新即丢；
 * - 并排对照：直接复用 DiffViewer（内部用 renderSideBySide 解析），不新增 diff 解析逻辑。
 *
 * 批准 / 落盘 / 拒绝按钮作用于「当前文件当前选中的那条提案」，调用的是 App 里既有的 runAction。
 */
import { useEffect, useMemo, useState, type JSX } from "react";

import { api } from "../api/client";
import type { Change, FileContent } from "../api/types";
import { MAX_CONTEXT_FILES } from "../data/context";
import { buildEditorModel, pickProposal, type EditorViewMode } from "../utils/editorState";
import { shortId } from "../utils/format";
import { DiffViewer } from "./DiffViewer";
import {
  Button,
  CHANGE_STATUS_LABELS,
  ChangeStatusBadge,
  EmptyState,
  ErrorBanner,
  MetaField,
  Panel,
  PanelHeader,
  Paragraph,
  Tag,
} from "./ui";

const VIEW_TABS: { value: EditorViewMode; label: string }[] = [
  { value: "original", label: "原始代码" },
  { value: "proposed", label: "AI 建议" },
  { value: "draft", label: "我的修改" },
];

function errorText(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

/** 只读代码视图：行号 + 等宽文本，本阶段不做语法高亮 */
function LineView({ text }: { text: string }): JSX.Element {
  return (
    <pre className="w-max min-w-full py-1 font-mono text-[12px]">
      {text.split("\n").map((line, index) => (
        <span key={index} className="flex">
          <span className="w-12 shrink-0 border-r border-line-soft pr-2 text-right text-[11px] leading-5 text-faint select-none">
            {index + 1}
          </span>
          <span className="px-3 leading-5 whitespace-pre text-text">{line === "" ? " " : line}</span>
        </span>
      ))}
    </pre>
  );
}

export function CodeEditor({
  projectId,
  filePath,
  changes,
  pinnedChangeId,
  selected,
  detailBusy,
  onSelectChange,
  contextPaths,
  onAddPath,
  onRemovePath,
  actionBusyId,
  onAccept,
  onApply,
  onReject,
  actionError,
  onDismissActionError,
  onViewDiff,
}: {
  projectId: string | null;
  filePath: string | null;
  changes: Change[];
  /**
   * 用户显式锁定过的提案（点变更清单 / 生成成功时设置）。
   * 不能用「列表当前选中项」，因为列表会自动选中首条，那会把默认优先级顶掉。
   */
  pinnedChangeId: string | null;
  /** 当前选中提案的最新详情（App 调 GET /workspace/changes/{id} 拉回来的那份） */
  selected: Change | null;
  detailBusy: boolean;
  onSelectChange: (changeId: string) => void;
  contextPaths: string[];
  onAddPath: (path: string) => void;
  onRemovePath: (path: string) => void;
  actionBusyId: string | null;
  onAccept: (changeId: string) => void;
  onApply: (changeId: string) => void;
  onReject: (changeId: string, reason: string) => void;
  actionError: string | null;
  onDismissActionError: () => void;
  onViewDiff: (changeId: string) => void;
}): JSX.Element {
  const [viewMode, setViewMode] = useState<EditorViewMode>("original");
  const [sideBySide, setSideBySide] = useState(false);
  const [draft, setDraft] = useState<Record<string, string>>({});
  const [rejectReason, setRejectReason] = useState("");
  const [fileContent, setFileContent] = useState<FileContent | null>(null);
  const [fileBusy, setFileBusy] = useState(false);
  const [fileError, setFileError] = useState<string | null>(null);
  // 手动「刷新文件」时用它打破 effect 的缓存
  const [readToken, setReadToken] = useState(0);

  const fileProposals = useMemo(
    () => (filePath ? changes.filter((change) => change.file_path === filePath) : []),
    [changes, filePath],
  );
  const hasProposal = fileProposals.length > 0;

  // 选中的提案：只认「用户显式锁定过的」那条（且必须属于当前文件），
  // 否则按 pending > accepted > applied > 其它 取一条。
  // 刻意不跟随列表的自动选中项——那会把默认优先级顶掉，导致打开文件时看到旧提案。
  const proposal = useMemo(() => {
    if (!filePath) return null;
    const pinned = fileProposals.find((change) => change.id === pinnedChangeId);
    // 锁定的这条若正好有落库详情，用更新鲜的那份
    if (pinned && selected && selected.id === pinned.id) return selected;
    return pinned ?? pickProposal(changes, filePath);
  }, [fileProposals, changes, filePath, pinnedChangeId, selected]);

  // 换文件时回到「原始代码」，并清掉上一条提案的拒绝理由
  useEffect(() => {
    setViewMode("original");
    setSideBySide(false);
    setRejectReason("");
  }, [filePath]);

  // 没有提案的文件才需要读盘拿当前内容；有提案时原始内容以提案为准
  useEffect(() => {
    if (!projectId || !filePath || hasProposal) {
      setFileContent(null);
      setFileError(null);
      setFileBusy(false);
      return;
    }
    let cancelled = false;
    setFileBusy(true);
    setFileError(null);
    api
      .readFile(projectId, filePath)
      .then((content) => {
        if (!cancelled) setFileContent(content);
      })
      .catch((caught: unknown) => {
        // 二进制 / 非 UTF-8 / 越界都是后端 422 的正常行为，原样展示即可
        if (!cancelled) {
          setFileContent(null);
          setFileError(errorText(caught));
        }
      })
      .finally(() => {
        if (!cancelled) setFileBusy(false);
      });
    return () => {
      cancelled = true;
    };
  }, [projectId, filePath, hasProposal, readToken]);

  const draftKey = filePath && proposal ? `${filePath}::${proposal.id}` : null;
  const localDraft = draftKey ? (draft[draftKey] ?? null) : null;
  const model = buildEditorModel({ viewMode, fileContent, proposal, localDraft });

  const inContext = filePath ? contextPaths.includes(filePath) : false;
  const canAccept = proposal?.status === "pending";
  const canApply = proposal?.status === "pending" || proposal?.status === "accepted";
  const canReject = proposal?.status === "pending" || proposal?.status === "accepted";

  const header = (
    <PanelHeader
      title="代码编辑器"
      subtitle={filePath ?? "从左侧工作区文件里选一个文件"}
      actions={
        filePath ? (
          <Button
            tone="ghost"
            busy={fileBusy}
            onClick={() => setReadToken((token) => token + 1)}
            title="重新读取该文件内容"
          >
            刷新文件
          </Button>
        ) : undefined
      }
    />
  );

  if (!filePath) {
    return (
      <Panel className="min-h-[22rem] flex-1 xl:min-h-0">
        {header}
        <EmptyState>
          从左侧「工作区文件」里点一个文件开始查看；如果那里是空的或报错，先到「项目」tab 扫描工作区。
        </EmptyState>
      </Panel>
    );
  }

  return (
    <Panel className="min-h-[22rem] flex-1 xl:min-h-0">
      {header}

      {/* 工具栏：视图切换 + 并排对照 + 提案选择 + 状态 */}
      <div className="flex shrink-0 flex-wrap items-center gap-1.5 border-b border-line-soft px-3 py-1.5">
        <div className="flex items-center gap-0.5 rounded border border-line bg-surface-2 p-0.5">
          {VIEW_TABS.map((tab) => {
            const disabled = tab.value === "proposed" && !proposal;
            return (
              <button
                key={tab.value}
                type="button"
                disabled={disabled}
                onClick={() => setViewMode(tab.value)}
                title={disabled ? "该文件没有 AI 提案" : undefined}
                className={`rounded px-2 py-0.5 text-[11px] transition-colors disabled:opacity-40 ${
                  viewMode === tab.value ? "bg-surface-3 text-text" : "text-faint hover:text-muted"
                }`}
              >
                {tab.label}
              </button>
            );
          })}
        </div>

        <label
          className={`flex items-center gap-1 rounded border border-line bg-surface-2 px-1.5 py-0.5 text-[11px] ${
            proposal ? "text-muted" : "text-faint opacity-50"
          }`}
          title={proposal ? "左右并排对照原始代码与 AI 建议" : "该文件没有提案，无法对照"}
        >
          <input
            type="checkbox"
            checked={sideBySide}
            disabled={!proposal}
            onChange={(event) => setSideBySide(event.target.checked)}
            className="accent-accent"
          />
          并排对照
        </label>

        <select
          value={proposal?.id ?? ""}
          disabled={fileProposals.length === 0}
          onChange={(event) => onSelectChange(event.target.value)}
          title="该文件可能有多条提案，这里切换当前展示的那条"
          className="max-w-[16rem] rounded border border-line bg-surface-2 px-1.5 py-0.5 text-[11px] text-text outline-none disabled:opacity-40"
        >
          {fileProposals.length === 0 ? (
            <option value="">该文件暂无提案</option>
          ) : (
            fileProposals.map((item) => (
              <option key={item.id} value={item.id}>
                #{shortId(item.id)} · {CHANGE_STATUS_LABELS[item.status]}
              </option>
            ))
          )}
        </select>

        <div className="ml-auto flex items-center gap-1.5">
          {proposal ? (
            <>
              <ChangeStatusBadge status={proposal.status} />
              <span className="text-[11px] text-add">+{proposal.added_lines}</span>
              <span className="text-[11px] text-del">-{proposal.removed_lines}</span>
              {detailBusy ? <span className="text-[11px] text-faint">正在拉取详情…</span> : null}
              <Button tone="ghost" onClick={() => onViewDiff(proposal.id)} title="切到底部「变更清单」并选中这条提案">
                查看 diff
              </Button>
            </>
          ) : null}
          <Button
            tone="ghost"
            disabled={!inContext && contextPaths.length >= MAX_CONTEXT_FILES}
            onClick={() => (inContext ? onRemovePath(filePath) : onAddPath(filePath))}
            title={
              inContext
                ? "从本次需求上下文移除"
                : contextPaths.length >= MAX_CONTEXT_FILES
                  ? `上下文最多 ${MAX_CONTEXT_FILES} 个文件`
                  : "加入本次需求上下文"
            }
          >
            {inContext ? "移出上下文" : "加入上下文"}
          </Button>
        </div>
      </div>

      {/* 提示区：截断 / 草稿不保存 / 读文件失败 / 落盘失败 / 动作失败 */}
      <div className="shrink-0 space-y-2 px-3 pt-2 empty:hidden">
        {model.notice ? (
          <p className="rounded border border-warn/40 bg-warn/10 px-2 py-1 text-[11px] text-warn">{model.notice}</p>
        ) : null}
        {fileError ? <ErrorBanner message={fileError} onDismiss={() => setFileError(null)} /> : null}
        {proposal?.status === "failed" && proposal.apply_error ? (
          <ErrorBanner message={`落盘失败：${proposal.apply_error}`} />
        ) : null}
        {actionError ? <ErrorBanner message={actionError} onDismiss={onDismissActionError} /> : null}
      </div>

      {/* 代码区：必须占最大空间 */}
      <div className="flex min-h-0 flex-1 flex-col overflow-hidden">
        {sideBySide && proposal ? (
          <div className="flex min-h-0 flex-1 p-2">
            <DiffViewer
              diff={proposal.diff}
              filePath={proposal.file_path}
              added={proposal.added_lines}
              removed={proposal.removed_lines}
              hunks={proposal.hunks}
            />
          </div>
        ) : model.editable && draftKey ? (
          <textarea
            value={model.text}
            spellCheck={false}
            onChange={(event) => {
              const value = event.target.value;
              setDraft((prev) => ({ ...prev, [draftKey]: value }));
            }}
            className="min-h-0 flex-1 resize-none rounded-md border border-line bg-surface-1 p-3 font-mono text-[12px] leading-5 text-text outline-none"
          />
        ) : (
          <div className="min-h-0 flex-1 overflow-auto">
            {fileBusy && !proposal ? (
              <EmptyState>正在读取文件内容…</EmptyState>
            ) : (
              <LineView text={model.text} />
            )}
          </div>
        )}
      </div>

      {/* 提案详情 + 审查动作（原 ChangeReview 的详情区整体搬到这里） */}
      {proposal ? (
        <div className="shrink-0 space-y-2 border-t border-line-soft px-3 py-2">
          <div className="flex flex-wrap items-center gap-1.5">
            {proposal.status === "applied" ? <Tag className="text-add">已写入工作区</Tag> : null}
            <Tag>来自 {proposal.agent_source ?? "未知 Agent"}</Tag>
          </div>
          <dl className="grid grid-cols-2 gap-x-4 gap-y-1 rounded-md border border-line bg-surface-2 p-2 sm:grid-cols-4">
            <MetaField label="变更 ID">#{shortId(proposal.id)}</MetaField>
            <MetaField label="新增 / 删除">
              <span className="text-add">+{proposal.added_lines}</span>
              <span className="text-faint"> / </span>
              <span className="text-del">-{proposal.removed_lines}</span>
            </MetaField>
            <MetaField label="改动块">{proposal.hunks}</MetaField>
            <MetaField label="关联项目">{proposal.project_id ? shortId(proposal.project_id) : "未关联"}</MetaField>
          </dl>

          <div className="grid gap-2 sm:grid-cols-2">
            <Paragraph label="变更摘要（summary）" text={proposal.summary} />
            <Paragraph label="修改原因（reason）" text={proposal.reason} />
          </div>

          <div className="flex flex-wrap items-center gap-2">
            <Button
              tone="default"
              disabled={!canAccept}
              busy={actionBusyId === proposal.id}
              onClick={() => onAccept(proposal.id)}
              title="批准提案但不落盘"
            >
              批准
            </Button>
            <Button
              tone="primary"
              disabled={!canApply}
              busy={actionBusyId === proposal.id}
              onClick={() => onApply(proposal.id)}
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
                busy={actionBusyId === proposal.id}
                onClick={() => onReject(proposal.id, rejectReason.trim())}
              >
                拒绝
              </Button>
            </div>
          </div>
        </div>
      ) : null}
    </Panel>
  );
}

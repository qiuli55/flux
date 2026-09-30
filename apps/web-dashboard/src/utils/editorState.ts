/**
 * 代码编辑器的状态推导：把「视图 × 提案 × 文件内容 × 本地草稿」收敛成一个可展示模型。
 *
 * 后端没有提案编辑接口，所以「我的修改」永远是本地草稿：canSave 恒为 false，
 * notice 必须写清楚它不会被保存。这里用纯函数表达，避免界面各分支各写一套判断。
 */
import type { Change, ChangeStatus, FileContent } from "../api/types";

/** 三视图：原始代码 / AI 建议 / 我的修改（本地草稿） */
export type EditorViewMode = "original" | "proposed" | "draft";

export interface EditorModelInput {
  viewMode: EditorViewMode;
  /** readFile 的结果；有提案时原始代码取提案的 original_content，与 diff 语义一致 */
  fileContent: FileContent | null;
  /** 当前文件当前选中的提案，可能为 null */
  proposal: Change | null;
  /** 本地草稿；null 表示尚未改动，回落到 proposed_content */
  localDraft: string | null;
}

export interface EditorModel {
  /** 当前视图应展示的文本 */
  text: string;
  /** 是否允许直接编辑（只有「我的修改」是本地可编辑的） */
  editable: boolean;
  /** 当前提案状态，用于工具栏徽章；无提案为 null */
  badge: ChangeStatus | null;
  /** 必须显式告知用户的提示（草稿不保存 / 文件被截断 / 无提案） */
  notice: string | null;
  /** 是否可以落盘保存——本地草稿永远不可以 */
  canSave: boolean;
}

const DRAFT_NOTICE = "本地草稿 · 后端未提供提案编辑接口，刷新后不保留";
const TRUNCATED_NOTICE = "文件超过 256 KiB，仅显示前 256 KiB";
const NO_PROPOSAL_NOTICE = "该文件没有 AI 提案，无法显示建议内容。";

/** 提案优先级的数值表达：pending > accepted > applied > failed > rejected */
const PROPOSAL_RANK: Record<ChangeStatus, number> = {
  pending: 4,
  accepted: 3,
  applied: 2,
  failed: 1,
  rejected: 0,
};

/**
 * 取该文件最该展示的一条提案。
 *
 * 排序键是「状态优先级」，同级取列表里靠后的那条（后端按创建时间升序返回，末尾最近）。
 * 该文件没有任何提案时返回 null。
 */
export function pickProposal(proposals: Change[], path: string): Change | null {
  let best: Change | null = null;
  let bestRank = -1;
  for (const proposal of proposals) {
    if (proposal.file_path !== path) continue;
    const rank = PROPOSAL_RANK[proposal.status];
    // >= 让同级里更靠后（更新）的那条胜出
    if (rank >= bestRank) {
      best = proposal;
      bestRank = rank;
    }
  }
  return best;
}

/** 由视图、文件内容、提案与草稿推导出当前该显示什么。 */
export function buildEditorModel({
  viewMode,
  fileContent,
  proposal,
  localDraft,
}: EditorModelInput): EditorModel {
  if (viewMode === "draft") {
    if (!proposal) {
      return { text: "", editable: false, badge: null, notice: DRAFT_NOTICE, canSave: false };
    }
    return {
      text: localDraft ?? proposal.proposed_content,
      editable: true,
      badge: proposal.status,
      notice: DRAFT_NOTICE,
      canSave: false,
    };
  }

  if (viewMode === "proposed") {
    if (!proposal) {
      return { text: "", editable: false, badge: null, notice: NO_PROPOSAL_NOTICE, canSave: false };
    }
    return {
      text: proposal.proposed_content,
      editable: false,
      badge: proposal.status,
      notice: null,
      canSave: false,
    };
  }

  // original：有提案时与 diff 的左侧同源，无提案时才用 readFile 的内容
  if (proposal) {
    return {
      text: proposal.original_content,
      editable: false,
      badge: proposal.status,
      notice: null,
      canSave: false,
    };
  }
  return {
    text: fileContent?.content ?? "",
    editable: false,
    badge: null,
    notice: fileContent?.truncated ? TRUNCATED_NOTICE : null,
    canSave: false,
  };
}

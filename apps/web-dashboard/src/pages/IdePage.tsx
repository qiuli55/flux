/**
 * IDE · 工作台（设计稿 v2 视图②）。
 *
 * 类名、结构与设计稿逐条一致；所有内容都是真实数据，没有内置示例文件：
 * - 文件树 / 编辑器：GET /projects/{id}/files(|/content)（只读，写盘一律走审核）
 * - 变更 Tab：GET /workspace/changes + accept/apply/reject
 * - Git 面板与状态栏：GET /git/status、POST /git/commit
 * - Agent 面板：GET /agents + 当前任务（GET /tasks、GET /tasks/{id}/messages）
 *
 * 设计稿里没有后端支撑的面板（Terminal/Problems/Tests）落地为三个真实面板：
 * AI 任务流（任务消息）· 变更（待审/已落盘）· Git（真实工作区状态）。
 */
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import type { MouseEvent as ReactMouseEvent, ReactNode } from "react";

import { api } from "../api/client";
import type {
  AgentHandle,
  Change,
  FileContent,
  FileEntry,
  GitCommit,
  GitFileStatus,
  GitStatus,
  Project,
  RecoveryItem,
  SearchHit,
  Task,
  TaskMessage,
} from "../api/types";
import { openCommandPalette, setIdeIntentHandler, type IdeIntent } from "../app/commands";
import { parseUnifiedDiff } from "../app/diff";
import { openTerminalWindow } from "../app/terminalWindow";
import { toast } from "../app/toast";
import { MOBILE_QUERY, useMediaQuery } from "../app/useMediaQuery";
import { clockOf } from "../components/solo/SoloChat";
import { ROLE_LABELS, STATE_LABELS } from "../data/team";

/** 后端允许的最大展开层数（MAX_TREE_DEPTH） */
const MAX_DEPTH = 4;
const LS_COMMIT_TOUCHED = "flux.ide.commitTouched";
const LS_RECENT_FILES = "flux.ide.recentFiles";

type RailPanel = "files" | "search" | "git" | "debug" | "ext";
type BottomTab = "flow" | "changes" | "git";
/** 移动端（≤860px）侧栏抽屉：文件与 Agent 二选一，其余面板走底部面板 */
type MobilePanel = "none" | "files" | "agent";
/** 编辑器里的特殊 Tab（不是文件路径） */
const CHANGES_TAB = "changes";

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

/* ---------- 文件类型 ---------- */

/** 图标类（design-v2.css 里的 .t-ic.*）；无对应类型的文件用默认底色 */
function iconClass(path: string): string {
  const name = path.split("/").pop() ?? path;
  if (name.startsWith(".")) return "git";
  if (/\.(tsx?|jsx?|mjs|cjs)$/.test(name)) return "ts";
  if (/\.(json|jsonc)$/.test(name)) return "json";
  if (/\.(md|markdown)$/.test(name)) return "md";
  return "";
}

/** 状态栏右下角的语言标签 */
function langLabel(path: string): string {
  const name = path.split("/").pop() ?? path;
  const table: [RegExp, string][] = [
    [/\.tsx$/, "TypeScript JSX"],
    [/\.ts$/, "TypeScript"],
    [/\.jsx$/, "JavaScript JSX"],
    [/\.(js|mjs|cjs)$/, "JavaScript"],
    [/\.json$/, "JSON"],
    [/\.md$/, "Markdown"],
    [/\.py$/, "Python"],
    [/\.css$/, "CSS"],
    [/\.html$/, "HTML"],
    [/\.(yml|yaml)$/, "YAML"],
    [/\.toml$/, "TOML"],
    [/\.sh$/, "Shell Script"],
  ];
  for (const [pattern, label] of table) {
    if (pattern.test(name)) return label;
  }
  return "纯文本";
}

/** 按扩展名决定高亮口径：Markdown 的 # 是标题，Python 的 # 是注释 */
function lexerKind(path: string): "md" | "py" | "code" {
  const name = path.split("/").pop() ?? path;
  if (name.endsWith(".md")) return "md";
  if (name.endsWith(".py")) return "py";
  return "code";
}

/* ---------- 轻量语法高亮（与设计稿同口径：够用即可） ---------- */

const TOKEN_RE =
  /(\/\/[^\n]*|#[^\n]*)|('(?:[^'\\]|\\.)*'|"(?:[^"\\]|\\.)*"|`[^`]*`)|\b(import|from|export|function|const|let|var|async|await|return|try|catch|finally|if|else|new|default|void|type|interface|class|extends|for|while|def|self|None|True|False|raise|with|yield|lambda|pass|elif|except|assert|global|in|not|and|or)\b|\b(\d+(?:\.\d+)?)\b|(<\/?)([A-Z][A-Za-z0-9_.]*)/g;

interface Token {
  text: string;
  cls?: string;
}

function highlight(line: string, kind: "md" | "py" | "code"): Token[] {
  if (kind === "md" && /^#{1,6}\s/.test(line.trimStart())) return [{ text: line, cls: "t" }];
  const tokens: Token[] = [];
  let last = 0;
  TOKEN_RE.lastIndex = 0;
  let match = TOKEN_RE.exec(line);
  while (match !== null) {
    if (match.index > last) tokens.push({ text: line.slice(last, match.index) });
    if (match[1]) tokens.push({ text: match[1], cls: "c" });
    else if (match[2]) tokens.push({ text: match[2], cls: "s" });
    else if (match[3]) tokens.push({ text: match[3], cls: "k" });
    else if (match[4]) tokens.push({ text: match[4], cls: "n" });
    else if (match[6]) {
      tokens.push({ text: match[5] ?? "" });
      tokens.push({ text: match[6], cls: "comp" });
    }
    last = match.index + match[0].length;
    match = TOKEN_RE.exec(line);
  }
  if (last < line.length) tokens.push({ text: line.slice(last) });
  return tokens.length > 0 ? tokens : [{ text: line }];
}

/* ---------- 文件树 ---------- */

interface TreeNode {
  name: string;
  path: string;
  kind: "dir" | "file";
  children: TreeNode[];
}

/** 后端给的是带完整相对路径的扁平条目，这里按路径拼成树 */
function buildTree(entries: FileEntry[]): TreeNode[] {
  const root: TreeNode[] = [];
  const index = new Map<string, TreeNode>();
  for (const entry of [...entries].sort((a, b) => a.path.localeCompare(b.path))) {
    const parts = entry.path.split("/");
    let level = root;
    let prefix = "";
    parts.forEach((part, i) => {
      prefix = prefix ? `${prefix}/${part}` : part;
      const isLeaf = i === parts.length - 1;
      let node = index.get(prefix);
      if (!node) {
        node = {
          name: part,
          path: prefix,
          kind: isLeaf ? entry.kind : "dir",
          children: [],
        };
        index.set(prefix, node);
        level.push(node);
      }
      level = node.children;
    });
  }
  const sortLevel = (nodes: TreeNode[]): TreeNode[] => {
    nodes.sort((a, b) => (a.kind === b.kind ? a.name.localeCompare(b.name) : a.kind === "dir" ? -1 : 1));
    nodes.forEach((node) => sortLevel(node.children));
    return nodes;
  };
  return sortLevel(root);
}

/** 合并新拉取到的条目（按 path 去重） */
function mergeEntries(current: FileEntry[], incoming: FileEntry[]): FileEntry[] {
  const seen = new Set(current.map((entry) => entry.path));
  return [...current, ...incoming.filter((entry) => !seen.has(entry.path))];
}

/** git status --porcelain 的一行 → 状态字母 */
function gitLetter(file: GitFileStatus): string {
  if (file.untracked) return "U";
  const worktree = file.worktree_status.trim();
  const index = file.index_status.trim();
  return worktree || index || "M";
}

/** 变更状态 → 中文标签与徽标类 */
function changeBadge(change: Change): { label: string; cls: string } {
  if (change.status === "pending") return { label: "待审核", cls: "b-run" };
  if (change.status === "applied") return { label: "已落盘", cls: "b-ok" };
  if (change.status === "accepted") return { label: "已批准", cls: "b-ok" };
  if (change.status === "rolled_back") return { label: "已回滚", cls: "" };
  if (change.status === "failed") return { label: "落盘失败", cls: "" };
  return { label: "已拒绝", cls: "" };
}

/** 变更语义（P1-1）→ git 风格的字母与中文说明 */
function kindLetter(kind: Change["kind"]): string {
  if (kind === "create") return "A";
  if (kind === "delete") return "D";
  return "M";
}

function kindLabel(kind: Change["kind"]): string {
  if (kind === "create") return "新建";
  if (kind === "delete") return "删除";
  return "修改";
}

/** 落盘失败日志 → 一行结论（优先 pytest 的 FAILED 行与计数行，见 F-01） */
function failureSummary(log: string): string {
  const lines = log.split("\n").map((line) => line.trim()).filter(Boolean);
  const failed = lines.find((line) => /^FAILED\b/.test(line));
  const counts = lines.find((line) => /^\d+\s+(failed|passed|error)/i.test(line));
  const shortCounts = counts?.replace(/\s+in\s+[\d.]+s$/, "");
  if (shortCounts && failed) return `${shortCounts} · ${failed}`;
  if (failed) return failed;
  if (shortCounts) return shortCounts;
  return lines[0] ?? "未知原因";
}

/** 落盘失败横幅：默认一行结论，点「展开完整日志」才铺开 pytest 原始输出 */
function FailedBanner({ log }: { log: string }) {
  const [open, setOpen] = useState(false);
  return (
    <div className="diff-banner is-failed">
      <div className="db-row">
        <span className="t-err">落盘失败：{failureSummary(log)}</span>
        <button type="button" className="link" onClick={() => setOpen((prev) => !prev)}>
          {open ? "收起日志" : "展开完整日志"}
        </button>
      </div>
      {open ? <pre className="apply-log">{log}</pre> : null}
    </div>
  );
}

export function IdePage({ onBackToSolo }: { onBackToSolo: () => void }) {
  const [projects, setProjects] = useState<Project[]>([]);
  const [projectId, setProjectId] = useState<string | null>(null);
  const [projectOpen, setProjectOpen] = useState(false);
  const [quickOpen, setQuickOpen] = useState(false);
  const [quickOpenQuery, setQuickOpenQuery] = useState("");
  const desktop = typeof window !== "undefined" ? window.fluxDesktop : null;

  const [railPanel, setRailPanel] = useState<RailPanel>("files");
  const [filesCollapsed, setFilesCollapsed] = useState(false);
  const [agentClosed, setAgentClosed] = useState(false);
  const [focusMode, setFocusMode] = useState(false);

  const isMobile = useMediaQuery(MOBILE_QUERY);
  /** 移动端侧栏抽屉：桌面下恒为 none（不生效） */
  const [mPanel, setMPanel] = useState<MobilePanel>("none");

  const [entries, setEntries] = useState<FileEntry[]>([]);
  const [treeRoot, setTreeRoot] = useState("");
  const [treeTruncated, setTreeTruncated] = useState(false);
  const [treeLoading, setTreeLoading] = useState(false);
  const [treeError, setTreeError] = useState<string | null>(null);
  const [expanded, setExpanded] = useState<Set<string>>(new Set());
  const [search, setSearch] = useState("");
  const [replaceText, setReplaceText] = useState("");
  const [replaceBusy, setReplaceBusy] = useState(false);
  const [searchCaseSensitive, setSearchCaseSensitive] = useState(false);
  const [searchRegex, setSearchRegex] = useState(false);
  const [searchHits, setSearchHits] = useState<SearchHit[]>([]);
  const [searchLoading, setSearchLoading] = useState(false);
  const [searchError, setSearchError] = useState<string | null>(null);
  const [revealLine, setRevealLine] = useState<{ path: string; line: number } | null>(null);

  const [openTabs, setOpenTabs] = useState<string[]>([]);
  const [recentFiles, setRecentFiles] = useState<string[]>(() => {
    try {
      const stored = JSON.parse(window.localStorage.getItem(LS_RECENT_FILES) ?? "[]");
      return Array.isArray(stored) ? stored.filter((item): item is string => typeof item === "string").slice(0, 20) : [];
    } catch {
      return [];
    }
  });
  const [activeTab, setActiveTab] = useState<string | null>(CHANGES_TAB);
  const [files, setFiles] = useState<Record<string, FileContent>>({});
  const [fileBusy, setFileBusy] = useState(false);
  const [fileError, setFileError] = useState<string | null>(null);

  const [changes, setChanges] = useState<Change[]>([]);
  const [actionBusyId, setActionBusyId] = useState<string | null>(null);
  const [rollbackBusy, setRollbackBusy] = useState(false);

  const [gitStatus, setGitStatus] = useState<GitStatus | null>(null);
  const [gitError, setGitError] = useState<string | null>(null);
  const [commitMessage, setCommitMessage] = useState("");
  const [commitTouched, setCommitTouched] = useState(
    () => window.localStorage.getItem(LS_COMMIT_TOUCHED) === "1",
  );
  const [commitBusy, setCommitBusy] = useState(false);
  const [lastCommit, setLastCommit] = useState<GitCommit | null>(null);
  useEffect(() => {
    window.localStorage.setItem(LS_RECENT_FILES, JSON.stringify(recentFiles));
  }, [recentFiles]);

  const [agents, setAgents] = useState<AgentHandle[]>([]);
  const [task, setTask] = useState<Task | null>(null);
  const [taskMessages, setTaskMessages] = useState<TaskMessage[]>([]);

  const [bottomTab, setBottomTab] = useState<BottomTab>("flow");
  const [bottomOpen, setBottomOpen] = useState(true);
  const [bottomHeight, setBottomHeight] = useState(200);

  const [reviewId, setReviewId] = useState<string | null>(null);
  const [reviewBusy, setReviewBusy] = useState(false);

  // 崩溃恢复挂起项（P0-1）：崩溃后被外部改动的内容不自动覆盖，挂在这里等用户决策
  const [recoveryItems, setRecoveryItems] = useState<RecoveryItem[]>([]);
  const [recoveryOpenId, setRecoveryOpenId] = useState<string | null>(null);
  const [recoveryBusy, setRecoveryBusy] = useState(false);

  /* ---------- 加载 ---------- */

  const loadProjects = useCallback(async () => {
    try {
      const list = await api.listProjects();
      setProjects(list);
      setProjectId((current) => current ?? list[0]?.id ?? null);
    } catch (error) {
      toast(errorMessage(error), "error");
    }
  }, []);

  const loadTree = useCallback(async (id: string | null) => {
    if (!id) {
      setEntries([]);
      setTreeRoot("");
      return;
    }
    setTreeLoading(true);
    try {
      const page = await api.listFiles(id, { depth: MAX_DEPTH });
      setEntries(page.entries);
      setTreeRoot(page.root);
      setTreeTruncated(page.truncated);
      setTreeError(null);
    } catch (error) {
      setEntries([]);
      setTreeError(errorMessage(error));
    } finally {
      setTreeLoading(false);
    }
  }, []);

  const loadChanges = useCallback(async () => {
    try {
      setChanges(await api.listChanges());
    } catch (error) {
      toast(errorMessage(error), "error");
    }
  }, []);

  const loadRecovery = useCallback(async () => {
    try {
      setRecoveryItems(await api.listRecovery());
    } catch (error) {
      toast(errorMessage(error), "error");
    }
  }, []);

  const loadGit = useCallback(async () => {
    try {
      setGitStatus(await api.gitStatus());
      setGitError(null);
    } catch (error) {
      setGitStatus(null);
      setGitError(errorMessage(error));
    }
  }, []);

  const loadAgents = useCallback(async () => {
    try {
      setAgents(await api.listAgents());
    } catch (error) {
      toast(errorMessage(error), "error");
    }
  }, []);

  const loadTask = useCallback(async (id: string | null) => {
    try {
      const list = await api.listTasks(id ? { projectId: id, limit: 1 } : { limit: 1 });
      const latest = list[0] ?? null;
      setTask(latest);
      if (!latest) {
        setTaskMessages([]);
        return;
      }
      const page = await api.listTaskMessages(latest.id, { limit: 50 });
      setTaskMessages(page.items);
    } catch (error) {
      setTask(null);
      setTaskMessages([]);
      toast(errorMessage(error), "error");
    }
  }, []);

  useEffect(() => {
    void loadProjects();
    void loadChanges();
    void loadRecovery();
    void loadGit();
    void loadAgents();
  }, [loadProjects, loadChanges, loadRecovery, loadGit, loadAgents]);

  useEffect(() => {
    void loadTree(projectId);
    void loadTask(projectId);
  }, [projectId, loadTree, loadTask]);

  useEffect(() => {
    const refreshExplorer = () => {
      setEntries([]);
      setFiles({});
      setOpenTabs([]);
      setActiveTab(CHANGES_TAB);
      setExpanded(new Set());
      setFileError(null);
      void loadTree(projectId);
      void loadGit();
    };
    window.addEventListener("flux:explorer-refresh", refreshExplorer);
    return () => window.removeEventListener("flux:explorer-refresh", refreshExplorer);
  }, [projectId, loadTree, loadGit]);

  /* ---------- 视图状态（与设计稿同一套 body 类） ---------- */

  useEffect(() => {
    const body = document.body;
    body.classList.toggle("files-collapsed", filesCollapsed);
    body.classList.toggle("agent-closed", agentClosed);
    body.classList.toggle("bottom-closed", !bottomOpen);
    body.classList.toggle("focus-mode", focusMode);
    body.classList.toggle("m-files-open", isMobile && mPanel === "files");
    body.classList.toggle("m-agent-open", isMobile && mPanel === "agent");
    return () => {
      body.classList.remove(
        "files-collapsed",
        "agent-closed",
        "bottom-closed",
        "focus-mode",
        "m-files-open",
        "m-agent-open",
      );
    };
  }, [filesCollapsed, agentClosed, bottomOpen, focusMode, isMobile, mPanel]);

  // 从移动端宽度回到桌面时收起抽屉，避免残留状态
  useEffect(() => {
    if (!isMobile) setMPanel("none");
  }, [isMobile]);

  const toggleFocus = useCallback(() => {
    const next = !focusMode;
    setFocusMode(next);
    toast(next ? "已进入专注模式：隐藏所有面板，只保留编辑器（⌘\\ 退出）" : "已退出专注模式");
  }, [focusMode]);

  const toggleFiles = useCallback(() => {
    const next = !filesCollapsed;
    setFilesCollapsed(next);
    toast(next ? "已折叠文件资源管理器（⌘B 恢复）" : "已展开文件资源管理器");
  }, [filesCollapsed]);

  const toggleBottom = useCallback(() => {
    const next = !bottomOpen;
    setBottomOpen(next);
    toast(next ? "已展开底部面板" : "已收起底部面板（⌘J 恢复）");
  }, [bottomOpen]);

  const toggleAgent = useCallback(() => {
    const next = !agentClosed;
    setAgentClosed(next);
    toast(next ? "已收起 Agent 面板" : "已展开 Agent 面板");
  }, [agentClosed]);

  /* ---------- 文件与标签 ---------- */

  const openFile = useCallback((path: string) => {
    setOpenTabs((prev) => (prev.includes(path) ? prev : [...prev, path]));
    setActiveTab(path);
    setRecentFiles((prev) => [path, ...prev.filter((item) => item !== path)].slice(0, 20));
  }, []);

  const openFileAtLine = useCallback(
    (path: string, line: number) => {
      openFile(path);
      setRevealLine({ path, line });
    },
    [openFile],
  );

  const replaceAllSearch = useCallback(async () => {
    const queryText = search.trim();
    if (!queryText || !projectId) return;
    if (!desktop?.isDesktop) {
      toast("全部替换需要使用 Flux 桌面版", "error");
      return;
    }
    if (!window.confirm(`确定在当前 Workspace 中替换所有匹配「${queryText}」的内容吗？此操作会直接修改本地文件。`)) return;
    setReplaceBusy(true);
    try {
      const result = await api.replaceWorkspaceSearch(projectId, queryText, replaceText, {
        caseSensitive: searchCaseSensitive,
        regex: searchRegex,
      });
      toast(`已替换 ${result.replacements} 处，修改 ${result.files.length} 个文件`);
      setFiles({});
      setOpenTabs([]);
      setActiveTab(CHANGES_TAB);
      await loadTree(projectId);
      await loadGit();
      setSearch("");
      setReplaceText("");
    } catch (error) {
      toast(errorMessage(error), "error");
    } finally {
      setReplaceBusy(false);
    }
  }, [desktop, loadGit, loadTree, projectId, replaceText, search, searchCaseSensitive, searchRegex]);

  const closeTab = useCallback(
    (key: string) => {
      if (key === CHANGES_TAB) {
        setActiveTab((current) => (current === CHANGES_TAB ? (openTabs[0] ?? null) : current));
        toast("已关闭「Changes」标签（⌘K 里可再次打开变更提案 Diff）");
        return;
      }
      const index = openTabs.indexOf(key);
      const next = openTabs.filter((item) => item !== key);
      setOpenTabs(next);
      if (activeTab === key) setActiveTab(next[index] ?? next[index - 1] ?? null);
      toast(`已关闭 ${key.split("/").pop() ?? key}`);
    },
    [openTabs, activeTab],
  );

  const closeActiveTab = useCallback(() => {
    if (!activeTab || activeTab === CHANGES_TAB) return;
    closeTab(activeTab);
  }, [activeTab, closeTab]);

  const quickOpenItems = useMemo(() => {
    const keyword = quickOpenQuery.trim().toLowerCase();
    const filesFromTree = entries.filter((entry) => entry.kind === "file").map((entry) => entry.path);
    const candidates = [...new Set([...recentFiles, ...filesFromTree])];
    const filtered = keyword
      ? candidates.filter((path) => path.toLowerCase().includes(keyword))
      : candidates;
    return filtered.slice(0, 60);
  }, [entries, quickOpenQuery, recentFiles]);
  // 打开文件时按需读取内容（只读）
  useEffect(() => {
    if (!activeTab || activeTab === CHANGES_TAB || !projectId) return;
    if (files[activeTab]) return;
    let alive = true;
    setFileBusy(true);
    setFileError(null);
    api
      .readFile(projectId, activeTab)
      .then((content) => {
        if (!alive) return;
        setFiles((prev) => ({ ...prev, [activeTab]: content }));
      })
      .catch((error) => {
        if (!alive) return;
        setFileError(errorMessage(error));
      })
      .finally(() => {
        if (alive) setFileBusy(false);
      });
    return () => {
      alive = false;
    };
  }, [activeTab, projectId, files]);

  const toggleDir = useCallback(
    async (path: string) => {
      const wasOpen = expanded.has(path);
      setExpanded((prev) => {
        const next = new Set(prev);
        if (next.has(path)) next.delete(path);
        else next.add(path);
        return next;
      });
      if (wasOpen || !projectId) return;
      const known = entries.some((entry) => entry.path.startsWith(`${path}/`));
      if (known) return;
      try {
        // 展开更深一层：后端最多支持 4 层，超出部分靠这里按目录补拉
        const page = await api.listFiles(projectId, { path, depth: MAX_DEPTH });
        setEntries((prev) => mergeEntries(prev, page.entries));
      } catch (error) {
        toast(errorMessage(error), "error");
      }
    },
    [expanded, entries, projectId],
  );

  /* ---------- 变更操作 ---------- */

  const changeByPath = useMemo(() => {
    const map = new Map<string, Change>();
    for (const change of changes) {
      const current = map.get(change.file_path);
      if (!current || (current.status !== "pending" && change.status === "pending")) {
        map.set(change.file_path, change);
      }
    }
    return map;
  }, [changes]);

  const pendingChanges = useMemo(() => changes.filter((change) => change.status === "pending"), [changes]);
  const appliedChanges = useMemo(() => changes.filter((change) => change.status === "applied"), [changes]);
  const failedChanges = useMemo(() => changes.filter((change) => change.status === "failed"), [changes]);
  const reviewChange = useMemo(
    () => changes.find((change) => change.id === reviewId) ?? null,
    [changes, reviewId],
  );
  const openChanges = useMemo(
    () => [...pendingChanges, ...failedChanges, ...changes.filter((c) => c.status !== "pending" && c.status !== "failed")],
    [changes, pendingChanges, failedChanges],
  );

  const runChangeAction = useCallback(
    async (change: Change, action: "apply" | "reject") => {
      setActionBusyId(change.id);
      try {
        if (action === "apply") {
          await api.apply([change.id]);
          toast(`已批准并落盘 ${change.file_path} · 已运行项目测试`);
        } else {
          await api.reject([change.id], null);
          toast(`已拒绝 ${change.file_path} · 提案退回`);
        }
      } catch (error) {
        toast(errorMessage(error), "error");
      }
      setActionBusyId(null);
      setReviewId(null);
      await loadChanges();
      await loadGit();
      if (action === "apply") {
        // 落盘后文件内容变了，编辑器缓存里的旧内容要丢掉
        setFiles((prev) => {
          const next = { ...prev };
          delete next[change.file_path];
          return next;
        });
      }
    },
    [loadChanges, loadGit],
  );

  /* ---------- P1-2 回滚（单条 / 最近一次 Apply） ---------- */

  /** 落盘/回滚都改了磁盘内容：丢掉编辑器缓存里的旧内容，避免显示过期文本 */
  const dropFileCache = useCallback((path: string) => {
    setFiles((prev) => {
      const next = { ...prev };
      delete next[path];
      return next;
    });
  }, []);

  const runRollback = useCallback(
    async (change: Change) => {
      setActionBusyId(change.id);
      try {
        await api.rollbackChange(change.id);
        toast(`已回滚 ${change.file_path} · 工作区恢复为落盘前内容（Flux 不自动提交 git）`);
        dropFileCache(change.file_path);
        setReviewId(null);
        await loadChanges();
        await loadGit();
      } catch (error) {
        toast(errorMessage(error), "error");
      }
      setActionBusyId(null);
    },
    [dropFileCache, loadChanges, loadGit],
  );

  const rollbackLast = useCallback(async () => {
    setRollbackBusy(true);
    try {
      const batches = await api.listRollbackableBatches();
      const batch = batches[0];
      if (!batch) {
        toast("没有可回滚的落盘记录：最近一次 Apply 已回滚，或还没有落盘过", "error");
        return;
      }
      await api.rollbackBatch(batch.id);
      toast(`已回滚最近一次落盘（${batch.change_ids.length} 个文件）· Flux 不自动提交 git`);
      setFiles({}); // 多个文件可能都被改回，直接清空编辑器缓存
      await loadChanges();
      await loadGit();
    } catch (error) {
      toast(errorMessage(error), "error");
    } finally {
      setRollbackBusy(false);
    }
  }, [loadChanges, loadGit]);

  /* ---------- 崩溃恢复的人工决策（P0-1） ---------- */

  const recoveryItem = useMemo(
    () => recoveryItems.find((item) => item.change_id === recoveryOpenId) ?? null,
    [recoveryItems, recoveryOpenId],
  );

  const resolveRecovery = useCallback(
    async (item: RecoveryItem, action: "cover" | "keep") => {
      setRecoveryBusy(true);
      try {
        await api.resolveRecovery(item.change_id, action);
        toast(
          action === "cover"
            ? `已用备份覆盖还原 ${item.file_path}`
            : `已保持现状 ${item.file_path} · 该提案作废`,
        );
        setRecoveryOpenId(null);
        if (action === "cover") {
          // 覆盖改了磁盘内容，编辑器缓存里的旧内容要丢掉
          setFiles((prev) => {
            const next = { ...prev };
            delete next[item.file_path];
            return next;
          });
        }
        await loadRecovery();
        await loadChanges();
        await loadGit();
      } catch (error) {
        toast(errorMessage(error), "error");
      }
      setRecoveryBusy(false);
    },
    [loadChanges, loadGit, loadRecovery],
  );

  /* ---------- Git ---------- */

  // 变更一旦 applied 就会一直保留该状态，所以「还能提交什么」必须以工作区为准：
  // git status 里仍有改动的路径才算未提交（提交后工作区变干净，自动从集合里消失）
  const dirtyPaths = useMemo(
    () => new Set((gitStatus?.files ?? []).map((file) => file.path)),
    [gitStatus],
  );
  const committableChanges = useMemo(
    () => appliedChanges.filter((change) => dirtyPaths.has(change.file_path)),
    [appliedChanges, dirtyPaths],
  );
  // 同一路径可能有多条已落盘变更，按路径去重并保留最新一条，提交说明才不会重复旧摘要
  const committablePaths = useMemo(() => {
    const latest = new Map<string, Change>();
    for (const change of committableChanges) latest.set(change.file_path, change);
    return [...latest.values()];
  }, [committableChanges]);

  const defaultCommitMessage = useMemo(() => {
    if (committablePaths.length === 0) return "";
    const newest = committablePaths.at(-1);
    if (!newest) return "";
    const headline = newest.summary?.trim();
    const subject = headline
      ? `feat: ${headline}`
      : `feat: 应用 AI 变更（${committablePaths.length} 个文件）`;
    return [subject, "", ...committablePaths.map((change) => `- ${change.file_path}`)].join("\n");
  }, [committablePaths]);

  useEffect(() => {
    if (!commitTouched) setCommitMessage(defaultCommitMessage);
  }, [defaultCommitMessage, commitTouched]);

  const handleCommit = useCallback(async () => {
    const message = commitMessage.trim();
    if (!message || committableChanges.length === 0) return;
    setCommitBusy(true);
    try {
      const commit = await api.gitCommit({
        message,
        change_ids: committableChanges.map((change) => change.id),
      });
      setLastCommit(commit);
      setCommitTouched(false);
      window.localStorage.removeItem(LS_COMMIT_TOUCHED);
      toast(`已提交 ${commit.short_sha} · ${commit.files.length} 个文件`);
      await loadGit();
      await loadChanges();
    } catch (error) {
      toast(errorMessage(error), "error");
    } finally {
      setCommitBusy(false);
    }
  }, [commitMessage, committableChanges, loadGit, loadChanges]);

  /* ---------- 意图与快捷键 ---------- */

  const showBottom = useCallback(
    (tab: BottomTab) => {
      setBottomOpen(true);
      setBottomTab(tab);
    },
    [],
  );

  const intentRef = useRef<(intent: IdeIntent) => void>(() => undefined);
  intentRef.current = (intent) => {
    if (intent === "focus") toggleFocus();
    else if (intent === "files") toggleFiles();
    else if (intent === "bottom") toggleBottom();
    else if (intent === "agent") toggleAgent();
    else if (intent === "git") {
      setRailPanel("git");
      setFilesCollapsed(false);
      showBottom("git");
    } else if (intent === "changes") {
      setActiveTab(CHANGES_TAB);
    } else if (intent === "problems") {
      setActiveTab(CHANGES_TAB);
      showBottom("changes");
    } else if (typeof intent === "object" && intent.kind === "open-file") {
      openFile(intent.path);
    }
  };

  useEffect(() => {
    setIdeIntentHandler((intent) => intentRef.current(intent));
    return () => setIdeIntentHandler(null);
  }, []);

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      const mod = event.metaKey || event.ctrlKey;
      const key = event.key.toLowerCase();
      if (mod && key === "b") {
        event.preventDefault();
        toggleFiles();
        return;
      }
      if (mod && key === "j") {
        event.preventDefault();
        toggleBottom();
        return;
      }
      if (mod && event.key === "\\") {
        event.preventDefault();
        toggleFocus();
        return;
      }
      if (mod && key === "p") {
        event.preventDefault();
        setQuickOpen(true);
        setQuickOpenQuery("");
        return;
      }
      if (mod && key === "w") {
        event.preventDefault();
        closeActiveTab();
        return;
      }
      if (event.key === "Escape") {
        if (reviewId) setReviewId(null);
        else if (projectOpen) setProjectOpen(false);
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [closeActiveTab, toggleFiles, toggleBottom, toggleFocus, reviewId, projectOpen]);

  /* ---------- 底部面板拖动调高 ---------- */

  const onResizerDown = useCallback(
    (event: ReactMouseEvent) => {
      event.preventDefault();
      const startY = event.clientY;
      const startHeight = bottomHeight;
      document.body.style.userSelect = "none";
      const onMove = (move: MouseEvent) => {
        const next = Math.max(90, Math.min(startHeight + (startY - move.clientY), window.innerHeight * 0.6));
        setBottomHeight(next);
      };
      const onUp = () => {
        document.body.style.userSelect = "";
        document.removeEventListener("mousemove", onMove);
        document.removeEventListener("mouseup", onUp);
      };
      document.addEventListener("mousemove", onMove);
      document.addEventListener("mouseup", onUp);
    },
    [bottomHeight],
  );

  /* ---------- 派生展示数据 ---------- */

  const tree = useMemo(() => buildTree(entries), [entries]);
  const rootName = treeRoot ? (treeRoot.split("/").filter(Boolean).pop() ?? treeRoot) : "工作区";

  useEffect(() => {
    const keyword = search.trim();
    if (railPanel !== "search" || !projectId || !keyword) {
      setSearchHits([]);
      setSearchLoading(false);
      setSearchError(null);
      return;
    }
    let alive = true;
    setSearchLoading(true);
    setSearchError(null);
    const timer = window.setTimeout(() => {
      api.searchWorkspace(projectId, keyword, { maxResults: 200, caseSensitive: searchCaseSensitive, regex: searchRegex })
        .then((hits) => {
          if (alive) setSearchHits(hits);
        })
        .catch((error) => {
          if (!alive) return;
          setSearchHits([]);
          setSearchError(errorMessage(error));
        })
        .finally(() => {
          if (alive) setSearchLoading(false);
        });
    }, 180);
    return () => {
      alive = false;
      window.clearTimeout(timer);
    };
  }, [projectId, railPanel, search, searchCaseSensitive, searchRegex]);
  const assistantTurns = useMemo(
    () => taskMessages.filter((message) => message.role === "assistant"),
    [taskMessages],
  );
  const planSteps = useMemo(
    () => assistantTurns.flatMap((message) => message.payload?.steps ?? []),
    [assistantTurns],
  );
  const lastModel = useMemo(() => {
    for (let i = assistantTurns.length - 1; i >= 0; i -= 1) {
      const model = assistantTurns[i]?.payload?.model;
      if (model) return model;
    }
    return null;
  }, [assistantTurns]);

  const taskChanges = useMemo(
    () => (task ? changes.filter((change) => change.task_id === task.id) : []),
    [changes, task],
  );

  const activeFile = activeTab && activeTab !== CHANGES_TAB ? files[activeTab] : undefined;

  useEffect(() => {
    if (!revealLine || activeTab !== revealLine.path || !activeFile) return;
    const frame = window.requestAnimationFrame(() => {
      const row = document.querySelector<HTMLElement>(
        `.view-ide .code-lines .cl:nth-child(${revealLine.line})`,
      );
      row?.scrollIntoView({ block: "center", behavior: "smooth" });
      setRevealLine(null);
    });
    return () => window.cancelAnimationFrame(frame);
  }, [activeFile, activeTab, revealLine]);

  useEffect(() => {
    if (!revealLine || activeTab !== revealLine.path || !activeFile) return;
    const frame = window.requestAnimationFrame(() => {
      const row = document.querySelector<HTMLElement>(
        `.view-ide .code-lines .cl:nth-child(${revealLine.line})`,
      );
      row?.scrollIntoView({ block: "center", behavior: "smooth" });
      setRevealLine(null);
    });
    return () => window.cancelAnimationFrame(frame);
  }, [activeFile, activeTab, revealLine]);
  const activeLines = useMemo(() => (activeFile ? activeFile.content.split("\n") : []), [activeFile]);
  const activeKind = activeTab && activeTab !== CHANGES_TAB ? lexerKind(activeTab) : "code";
  const changedLines = useMemo(() => {
    if (!activeTab || activeTab === CHANGES_TAB) return new Set<number>();
    const numbers = new Set<number>();
    for (const change of changes) {
      if (change.file_path !== activeTab) continue;
      for (const row of parseUnifiedDiff(change.diff)) {
        if (row.kind === "add" && row.newNo) numbers.add(Number(row.newNo));
      }
    }
    return numbers;
  }, [changes, activeTab]);

  const runningTask = task?.status === "running";
  const branch = gitStatus?.branch ?? null;

  /* ---------- 渲染：文件树 ---------- */

  const renderNodes = (nodes: TreeNode[]): ReactNode =>
    nodes.map((node) => {
      if (node.kind === "dir") {
        const isOpen = expanded.has(node.path);
        return (
          <li key={node.path} className={isOpen ? "open" : undefined}>
            <div className="t-row" onClick={() => void toggleDir(node.path)}>
              <span className="t-chev">{isOpen ? "▾" : "▸"}</span>
              <span className={`t-ic ${isOpen ? "folder-open" : "folder"}`} />
              <span className="t-name">{node.name}</span>
            </div>
            <ul>{isOpen ? renderNodes(node.children) : null}</ul>
          </li>
        );
      }
      const change = changeByPath.get(node.path);
      const isCurrent = activeTab === node.path;
      return (
        <li key={node.path}>
          <div
            className={`t-row is-file${isCurrent ? " is-current" : ""}`}
            onClick={() => openFile(node.path)}
          >
            <span className={`t-ic ${iconClass(node.path)}`} />
            <span className="t-name">{node.name}</span>
            {change ? (
              <span className="t-badge">
                {change.status === "pending" ? "M" : change.status === "applied" ? "✓" : "!"}
              </span>
            ) : null}
          </div>
        </li>
      );
    });

  /* ---------- 渲染：编辑器主体 ---------- */

  const renderEditor = () => {
    if (activeTab === CHANGES_TAB) {
      return (
        <>
          <div className="ed-crumbs">
            <span>{rootName}</span>
            <i>›</i>
            <b>Changes</b>
          </div>
          <div className="ed-body">
            {openChanges.length === 0 ? (
              <div className="ed-empty">
                <div>还没有变更提案。</div>
                <div>在 Solo 里描述需求，或让 Agent 产出改动后，待审 Diff 会出现在这里。</div>
              </div>
            ) : (
              <div>
                {openChanges.map((change) => {
                  const badge = changeBadge(change);
                  return (
                    <div key={change.id}>
                      <div className="diff-banner">
                        <b>{change.file_path}</b>
                        <span>
                          {kindLetter(change.kind)} · {kindLabel(change.kind)} · +{change.added_lines} −
                          {change.removed_lines}
                        </span>
                        <span className={`tl-badge ${badge.cls}`}>{badge.label}</span>
                        <div className="diff-actions">
                          {change.status === "pending" ? (
                            <>
                              <button
                                type="button"
                                className="btn btn-ghost btn-xs"
                                disabled={actionBusyId === change.id}
                                onClick={() => void runChangeAction(change, "reject")}
                              >
                                拒绝
                              </button>
                              <button
                                type="button"
                                className="btn btn-primary btn-xs"
                                disabled={actionBusyId === change.id}
                                onClick={() => setReviewId(change.id)}
                              >
                                审核并落盘
                              </button>
                            </>
                          ) : (
                            <>
                              <button
                                type="button"
                                className="btn btn-ghost btn-xs"
                                onClick={() => openFile(change.file_path)}
                              >
                                打开文件
                              </button>
                              {change.status === "applied" ? (
                                <button
                                  type="button"
                                  className="btn btn-ghost btn-xs"
                                  disabled={actionBusyId === change.id}
                                  onClick={() => void runRollback(change)}
                                >
                                  回滚
                                </button>
                              ) : null}
                            </>
                          )}
                        </div>
                      </div>
                      {change.status === "failed" && change.apply_error ? (
                        <FailedBanner log={change.apply_error} />
                      ) : null}
                      <div className="diff-view">
                        {parseUnifiedDiff(change.diff).map((row, index) => (
                          <div className={`dl ${row.kind}`} key={`${change.id}-${index}`}>
                            <span className="lno old">{row.oldNo}</span>
                            <span className="lno new">{row.newNo}</span>
                            <span className="dc">{row.text}</span>
                          </div>
                        ))}
                      </div>
                    </div>
                  );
                })}
              </div>
            )}
          </div>
        </>
      );
    }

    if (!activeTab) {
      return (
        <>
          <div className="ed-crumbs">
            <span>{rootName}</span>
          </div>
          <div className="ed-body">
            <div className="ed-empty">
              <div>没有打开的文件。</div>
              <div>从左侧文件树点一个文件，或在命令面板（⌘K）里切换视图与面板。</div>
              <button type="button" className="btn btn-ghost btn-sm" onClick={() => setActiveTab(CHANGES_TAB)}>
                查看变更提案 Diff
              </button>
            </div>
          </div>
        </>
      );
    }

    const parts = activeTab.split("/");
    return (
      <>
        <div className="ed-crumbs">
          {parts.map((part, index) => (
            <span key={`${part}-${index}`}>
              {index === parts.length - 1 ? <b>{part}</b> : <span>{part}</span>}
              {index === parts.length - 1 ? null : <i>›</i>}
            </span>
          ))}
        </div>
        <div className="ed-body">
          {fileError ? (
            <div className="ed-empty">
              <div>无法读取该文件：{fileError}</div>
              <button type="button" className="btn btn-ghost btn-sm" onClick={() => void loadTree(projectId)}>
                刷新文件树
              </button>
            </div>
          ) : fileBusy && !activeFile ? (
            <div className="ed-empty">正在读取 {activeTab} …</div>
          ) : activeFile ? (
            <>
              {activeFile.truncated ? (
                <div className="diff-banner">
                  <b>文件较大</b>
                  <span>
                    只显示前 256 KiB（文件真实大小 {activeFile.size} 字节）——写盘一律走审核，编辑器只读
                  </span>
                </div>
              ) : null}
              <div className="code">
                <div className="code-lines">
                  {activeLines.map((line, index) => (
                    <div className={`cl${changedLines.has(index + 1) ? " is-changed" : ""}`} key={index}>
                      <span className="ln">{index + 1}</span>
                      <span className="lc">
                        {highlight(line, activeKind).map((token, i) =>
                          token.cls ? (
                            <span key={i} className={token.cls}>
                              {token.text}
                            </span>
                          ) : (
                            <span key={i}>{token.text}</span>
                          ),
                        )}
                      </span>
                    </div>
                  ))}
                </div>
                <div className="minimap">
                  {activeLines.map((line, index) => {
                    const length = line.length;
                    const width = length === 0 ? "w4" : length <= 10 ? "w4" : length <= 28 ? "w2" : length <= 52 ? "w1" : "w3";
                    return (
                      <div
                        className={`mm-line ${width}${changedLines.has(index + 1) ? " hl" : ""}`}
                        key={index}
                      />
                    );
                  })}
                </div>
              </div>
            </>
          ) : (
            <div className="ed-empty">正在准备编辑器…</div>
          )}
        </div>
      </>
    );
  };

  /* ---------- 渲染：底部面板 ---------- */

  const renderBottom = () => {
    if (bottomTab === "flow") {
      return (
        <div className="bp-rows">
          {taskMessages.length === 0 ? (
            <span className="bp-empty">
              暂无任务流：在 Solo 提交需求后，你与平台助手的每条消息都会落库并显示在这里。
            </span>
          ) : (
            taskMessages.map((message) => (
              <div className="bp-row" key={message.id}>
                <span className="bp-time">{clockOf(message.created_at)}</span>
                <span className={message.role === "assistant" ? "bp-who t-ok" : "bp-who t-blue"}>
                  {message.role === "assistant" ? "Flux" : "你"}
                </span>
                <span className="bp-tx t-cmd">{message.content}</span>
              </div>
            ))
          )}
        </div>
      );
    }
    if (bottomTab === "changes") {
      return (
        <div className="bp-rows">
          {recoveryItems.length > 0 ? (
            <div className="bp-row rec-banner">
              <span className="bp-who t-err">需确认</span>
              <span className="bp-tx">
                有 {recoveryItems.length} 项改动在崩溃恢复时发现被外部修改过，Flux 没有覆盖，等你决定
              </span>
              <button
                type="button"
                className="btn btn-ghost btn-xs"
                onClick={() => setRecoveryOpenId(recoveryItems[0]?.change_id ?? null)}
              >
                查看详情
              </button>
            </div>
          ) : null}
          {openChanges.length === 0 ? (
            <span className="bp-empty">暂无变更提案：Agent 产出改动后会出现在这里，需人工批准才落盘。</span>
          ) : (
            openChanges.map((change) => {
              const badge = changeBadge(change);
              return (
                <div className="bp-row" key={change.id}>
                  <span className="bp-who t-dim">
                    {kindLetter(change.kind)} · {badge.label}
                  </span>
                  <span className="bp-tx">{change.file_path}</span>
                  <span className="t-ok">
                    +{change.added_lines} −{change.removed_lines}
                  </span>
                  <button
                    type="button"
                    className="btn btn-ghost btn-xs"
                    onClick={() => {
                      if (change.status === "pending") setReviewId(change.id);
                      else openFile(change.file_path);
                    }}
                  >
                    {change.status === "pending" ? "审核" : "打开"}
                  </button>
                  {change.status === "applied" ? (
                    <button
                      type="button"
                      className="btn btn-ghost btn-xs"
                      disabled={actionBusyId === change.id}
                      onClick={() => void runRollback(change)}
                    >
                      回滚
                    </button>
                  ) : null}
                </div>
              );
            })
          )}
        </div>
      );
    }
    return (
      <div>
        {gitError ? (
          <span className="t-err">Git 状态读取失败：{gitError}</span>
        ) : gitStatus ? (
          <>
            <div className="bp-rows">
              <span className="t-dim">
                分支 {branch ?? "（未关联仓库）"} ·{" "}
                {gitStatus.detached ? "分离头指针" : gitStatus.clean ? "工作区干净" : `变更 ${gitStatus.files.length} 个文件`}
              </span>
              {gitStatus.files.map((file) => (
                <div className="bp-row" key={`${file.path}-${file.index_status}${file.worktree_status}`}>
                  <span className="bp-who t-blue">{gitLetter(file)}</span>
                  <span className="bp-tx">{file.path}</span>
                  <button
                    type="button"
                    className="btn btn-ghost btn-xs"
                    onClick={() => {
                      const change = changeByPath.get(file.path);
                      if (change) setActiveTab(CHANGES_TAB);
                      else openFile(file.path);
                    }}
                  >
                    {changeByPath.get(file.path) ? "看 Diff" : "打开"}
                  </button>
                </div>
              ))}
            </div>
            <div className="bp-commit">
              <input
                value={commitMessage}
                placeholder={
                  committablePaths.length > 0
                    ? "提交信息（默认取最新一条已落盘变更的摘要，可修改）"
                    : "暂无待提交内容：已落盘变更都提交过了"
                }
                disabled={committablePaths.length === 0}
                onChange={(event) => {
                  setCommitMessage(event.target.value);
                  setCommitTouched(true);
                  window.localStorage.setItem(LS_COMMIT_TOUCHED, "1");
                }}
              />
              <button
                type="button"
                className="btn btn-primary btn-sm"
                disabled={commitBusy || committablePaths.length === 0 || !commitMessage.trim()}
                onClick={() => void handleCommit()}
              >
                {commitBusy ? "提交中…" : `提交已落盘的 ${committablePaths.length} 项`}
              </button>
              {lastCommit ? (
                <span className="t-dim">
                  上次提交 {lastCommit.short_sha} · {lastCommit.files.length} 个文件
                </span>
              ) : null}
            </div>
          </>
        ) : (
          <span className="bp-empty">正在读取 Git 状态…</span>
        )}
      </div>
    );
  };

  /* ---------- 渲染：页面 ---------- */

  return (
    <div className="view view-ide" data-project-id={projectId ?? ""}>
      <header className="ide-top">
        <div className="it-left">
          <button type="button" className="icon-btn" title="返回 Solo" onClick={onBackToSolo}>
            <svg viewBox="0 0 24 24" className="logo-mark">
              <path d="M12 2 22 12 12 22 2 12Z" fill="url(#lg-ide)" />
              <defs>
                <linearGradient id="lg-ide" x1="0" x2="1" y1="0" y2="1">
                  <stop offset="0" stopColor="#8b6cff" />
                  <stop offset="1" stopColor="#5b3df5" />
                </linearGradient>
              </defs>
            </svg>
          </button>
          <button type="button" className="proj-pick" onClick={() => setProjectOpen(true)}>
            <span className="pj-ic" />
            {projects.find((project) => project.id === projectId)?.name ?? "未登记项目"}
            <i>▾</i>
          </button>
        </div>
        <button type="button" className="it-search" onClick={openCommandPalette}>
          <span className="k">⌘K</span>
          <span>打开命令面板…</span>
        </button>
        <div className="it-right">
          <button
            type="button"
            className="icon-btn m-only"
            title="文件资源管理器"
            onClick={() => {
              setFilesCollapsed(false);
              setMPanel((prev) => (prev === "files" ? "none" : "files"));
            }}
          >
            <svg viewBox="0 0 20 20" className="ic">
              <path
                d="M3 5.5A1.5 1.5 0 0 1 4.5 4h3l1.6 2H16a1.5 1.5 0 0 1 1.5 1.5v7A1.5 1.5 0 0 1 16 16H4.5A1.5 1.5 0 0 1 3 14.5Z"
                fill="none"
                stroke="currentColor"
                strokeWidth="1.4"
                strokeLinejoin="round"
              />
            </svg>
          </button>
          <button
            type="button"
            className="icon-btn m-only"
            title="Agent 面板"
            onClick={() => {
              setAgentClosed(false);
              setMPanel((prev) => (prev === "agent" ? "none" : "agent"));
            }}
          >
            <span className="ic-tx">AI</span>
          </button>
          <button
            type="button"
            className="icon-btn"
            title="底部面板 (⌘J)"
            onClick={() => showBottom(bottomTab)}
          >
            <span className="ic-tx">&gt;_</span>
          </button>
          <button
            type="button"
            className="icon-btn m-hide"
            title="Agent Terminal · 实时观察与接管 AI 命令"
            onClick={openTerminalWindow}
          >
            <span className="ic-tx">▮_</span>
          </button>
          <button
            type="button"
            className="icon-btn m-hide"
            title="分屏"
            onClick={() => toast("分屏尚未接入：当前为单编辑器视图")}
          >
            <svg viewBox="0 0 20 20" className="ic">
              <rect x="3" y="4" width="14" height="12" rx="2" fill="none" stroke="currentColor" strokeWidth="1.4" />
              <path d="M10 4v12" stroke="currentColor" strokeWidth="1.4" />
            </svg>
          </button>
          {runningTask ? (
            <span className="pill pill-run m-hide">
              <i className="dot" />
              Agent 运行中
            </span>
          ) : (
            <span className="pill m-hide">{task ? "Agent 待命" : "暂无任务"}</span>
          )}
          <button
            type="button"
            className="icon-btn m-hide"
            title="通知"
            onClick={() => toast(`当前有 ${pendingChanges.length} 项待人工审核`)}
          >
            <svg viewBox="0 0 20 20" className="ic">
              <path
                d="M10 3a4.5 4.5 0 0 0-4.5 4.5v3L4 13h12l-1.5-2.5v-3A4.5 4.5 0 0 0 10 3Z"
                fill="none"
                stroke="currentColor"
                strokeWidth="1.4"
                strokeLinejoin="round"
              />
              <path d="M8.5 15.5a1.6 1.6 0 0 0 3 0" fill="none" stroke="currentColor" strokeWidth="1.4" />
            </svg>
          </button>
          <button
            type="button"
            className="icon-btn m-hide"
            title="设置"
            onClick={() => toast("设置尚未开放：模型与密钥在服务端 .env 配置")}
          >
            <svg viewBox="0 0 20 20" className="ic">
              <circle cx="10" cy="10" r="2.6" fill="none" stroke="currentColor" strokeWidth="1.4" />
              <path
                d="M10 2.8v2M10 15.2v2M2.8 10h2M15.2 10h2M4.9 4.9l1.4 1.4M13.7 13.7l1.4 1.4M15.1 4.9l-1.4 1.4M6.3 13.7l-1.4 1.4"
                stroke="currentColor"
                strokeWidth="1.4"
                strokeLinecap="round"
              />
            </svg>
          </button>
          <span className="avatar m-hide">孟</span>
        </div>
      </header>

      <div className="ide-body">
        {/* 移动端抽屉遮罩：点空白处收起文件/Agent 抽屉 */}
        <div
          className={`m-scrim${isMobile && mPanel !== "none" ? " is-open" : ""}`}
          aria-hidden="true"
          onClick={() => setMPanel("none")}
        />
        <div className="ide-rail">
          <button
            type="button"
            className={`rail-btn${railPanel === "files" && !filesCollapsed ? " is-active" : ""}`}
            data-panel="files"
            title="文件资源管理器 (⌘B)"
            onClick={() => {
              if (isMobile) {
                setRailPanel("files");
                setFilesCollapsed(false);
                setMPanel((prev) => (prev === "files" ? "none" : "files"));
                return;
              }
              if (railPanel === "files" && !filesCollapsed) toggleFiles();
              else {
                setRailPanel("files");
                setFilesCollapsed(false);
              }
            }}
          >
            <svg viewBox="0 0 20 20" className="ic">
              <path
                d="M3 5.5A1.5 1.5 0 0 1 4.5 4h3l1.6 2H16a1.5 1.5 0 0 1 1.5 1.5v7A1.5 1.5 0 0 1 16 16H4.5A1.5 1.5 0 0 1 3 14.5Z"
                fill="none"
                stroke="currentColor"
                strokeWidth="1.4"
                strokeLinejoin="round"
              />
            </svg>
          </button>
          <button
            type="button"
            className={`rail-btn${railPanel === "search" ? " is-active" : ""}`}
            data-panel="search"
            title="搜索"
            onClick={() => {
              setRailPanel("search");
              setFilesCollapsed(false);
              if (isMobile) setMPanel("files");
            }}
          >
            <svg viewBox="0 0 20 20" className="ic">
              <circle cx="9" cy="9" r="5" fill="none" stroke="currentColor" strokeWidth="1.4" />
              <path d="M12.8 12.8 17 17" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" />
            </svg>
          </button>
          <button
            type="button"
            className={`rail-btn${railPanel === "git" ? " is-active" : ""}`}
            data-panel="git"
            title="源代码管理"
            onClick={() => {
              setRailPanel("git");
              setFilesCollapsed(false);
              if (isMobile) setMPanel("none");
              showBottom("git");
            }}
          >
            <svg viewBox="0 0 20 20" className="ic">
              <circle cx="6" cy="5" r="2" fill="none" stroke="currentColor" strokeWidth="1.4" />
              <circle cx="6" cy="15" r="2" fill="none" stroke="currentColor" strokeWidth="1.4" />
              <circle cx="14" cy="8" r="2" fill="none" stroke="currentColor" strokeWidth="1.4" />
              <path d="M6 7v6M6 11c4 0 8-1 8-3" fill="none" stroke="currentColor" strokeWidth="1.4" />
            </svg>
          </button>
          <button
            type="button"
            className={`rail-btn${railPanel === "debug" ? " is-active" : ""}`}
            data-panel="debug"
            title="运行和调试"
            onClick={() => {
              setRailPanel("debug");
              setFilesCollapsed(false);
              if (isMobile) setMPanel("files");
            }}
          >
            <svg viewBox="0 0 20 20" className="ic">
              <path
                d="M10 4a4 4 0 0 0-4 4v4a4 4 0 0 0 8 0V8a4 4 0 0 0-4-4Z"
                fill="none"
                stroke="currentColor"
                strokeWidth="1.4"
              />
              <path d="M4 9h12M4 12h12" stroke="currentColor" strokeWidth="1.2" />
            </svg>
          </button>
          <button
            type="button"
            className={`rail-btn${railPanel === "ext" ? " is-active" : ""}`}
            data-panel="ext"
            title="扩展"
            onClick={() => {
              setRailPanel("ext");
              setFilesCollapsed(false);
              if (isMobile) setMPanel("files");
            }}
          >
            <svg viewBox="0 0 20 20" className="ic">
              <rect x="3.5" y="3.5" width="5" height="5" rx="1" fill="none" stroke="currentColor" strokeWidth="1.4" />
              <rect x="11.5" y="3.5" width="5" height="5" rx="1" fill="none" stroke="currentColor" strokeWidth="1.4" />
              <rect x="3.5" y="11.5" width="5" height="5" rx="1" fill="none" stroke="currentColor" strokeWidth="1.4" />
              <path d="M14 12v4M12 14h4" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" />
            </svg>
          </button>
          <span className="rail-gap" />
          <button type="button" className="rail-btn" title="账户">
            <span className="avatar sm">孟</span>
          </button>
          <button
            type="button"
            className="rail-btn"
            title="设置"
            onClick={() => toast("设置尚未开放：模型与密钥在服务端 .env 配置")}
          >
            <svg viewBox="0 0 20 20" className="ic">
              <circle cx="10" cy="10" r="2.6" fill="none" stroke="currentColor" strokeWidth="1.4" />
              <path
                d="M10 2.8v2M10 15.2v2M2.8 10h2M15.2 10h2"
                stroke="currentColor"
                strokeWidth="1.4"
                strokeLinecap="round"
              />
            </svg>
          </button>
        </div>

        <aside className="ide-files">
          <div className="fl-head">
            <b>文件资源管理器</b>
            <button
              type="button"
              className="icon-btn sm"
              title="刷新文件树"
              onClick={() => void loadTree(projectId)}
            >
              ⟳
            </button>
            <button
              type="button"
              className="icon-btn sm m-only"
              title="关闭抽屉"
              onClick={() => setMPanel("none")}
            >
              ×
            </button>
          </div>

          {railPanel === "files" ? (
            <div className="fl-panel">
              {treeLoading && entries.length === 0 ? (
                <div className="fl-empty">正在读取工作区文件树…</div>
              ) : treeError ? (
                <div className="fl-empty">{treeError}</div>
              ) : tree.length === 0 ? (
                <div className="fl-empty">工作区里没有可展示的文件。</div>
              ) : (
                <ul className="tree">
                  <li className="t-root open">
                    <div className="t-row">
                      <span className="t-chev">▾</span>
                      <span className="t-ic folder-open" />
                      <span className="t-name">{rootName}</span>
                    </div>
                    <ul>{renderNodes(tree)}</ul>
                  </li>
                </ul>
              )}
              {treeTruncated ? (
                <div className="fl-empty">条目过多，只展示前 2000 项（后端截断标记 truncated=true）。</div>
              ) : null}
            </div>
          ) : null}

          {railPanel === "search" ? (
            <div className="fl-panel">
              <div className="fl-search-stack">
                <div className="fl-search">
                  <input
                    value={search}
                    placeholder="搜索工作区代码…"
                    onChange={(event) => setSearch(event.target.value)}
                  />
                  <button type="button" className={searchCaseSensitive ? "is-active" : ""} onClick={() => setSearchCaseSensitive((value) => !value)} title="区分大小写">Aa</button>
                  <button type="button" className={searchRegex ? "is-active" : ""} onClick={() => setSearchRegex((value) => !value)} title="正则表达式">.*</button>
                </div>
                <div className="fl-replace">
                  <input
                    value={replaceText}
                    placeholder="替换为…"
                    onChange={(event) => setReplaceText(event.target.value)}
                  />
                  <button
                    type="button"
                    className="btn btn-ghost btn-xs"
                    disabled={!search.trim() || replaceBusy || !desktop?.isDesktop}
                    onClick={() => void replaceAllSearch()}
                    title={desktop?.isDesktop ? "替换 Workspace 中所有匹配项" : "全部替换需要桌面版"}
                  >
                    {replaceBusy ? "替换中…" : "全部替换"}
                  </button>
                </div>
              </div>
              {search.trim() === "" ? (
                <div className="fl-empty">搜索工作区内的代码内容，点击结果可直接打开文件并定位到对应行。</div>
              ) : searchLoading ? (
                <div className="fl-empty">正在搜索「{search.trim()}」…</div>
              ) : searchError ? (
                <div className="fl-empty">{searchError}</div>
              ) : searchHits.length === 0 ? (
                <div className="fl-empty">没有匹配「{search.trim()}」。</div>
              ) : (
                <ul className="search-list">
                  {searchHits.map((entry, index) => (
                    <li
                      key={`${entry.path}:${entry.line}:${entry.column}:${index}`}
                      className="search-item"
                      onClick={() => openFileAtLine(entry.path, entry.line)}
                      title={`${entry.path}:${entry.line}:${entry.column}`}
                    >
                      <span className={`t-ic ${iconClass(entry.path)}`} />
                      <span className="search-hit-main">
                        <span className="search-path">{entry.path}</span>
                        <span className="search-hit-line">{entry.line}:{entry.column}</span>
                        <span className="search-hit-preview">{entry.text}</span>
                      </span>
                    </li>
                  ))}
                </ul>
              )}
            </div>
          ) : null}
{railPanel === "git" ? (
            <div className="fl-panel">
              <div className="fl-git-head">
                变更 ({gitStatus?.files.length ?? 0}) · {branch ?? "未关联仓库"}
              </div>
              {gitStatus && gitStatus.files.length > 0 ? (
                <ul className="git-changes">
                  {gitStatus.files.map((file) => (
                    <li
                      key={`${file.path}-${file.index_status}${file.worktree_status}`}
                      className="gc"
                      onClick={() => {
                        if (changeByPath.get(file.path)) setActiveTab(CHANGES_TAB);
                        else openFile(file.path);
                        toast(`已打开 ${file.path}${changeByPath.get(file.path) ? " 的变更 Diff" : ""}`);
                      }}
                    >
                      <span className={`t-ic ${iconClass(file.path)}`} />
                      <span className="gc-name">{file.path}</span>
                      <span className="gc-badge">{gitLetter(file)}</span>
                    </li>
                  ))}
                </ul>
              ) : (
                <div className="fl-empty">
                  {gitStatus?.clean ? "工作区干净：没有未提交的改动。" : "还没有读取到改动。"}
                </div>
              )}
              <div className="fl-empty">
                待审提案 {pendingChanges.length} 项 · 已落盘 {appliedChanges.length} 项；点文件即进入编辑器 Tab。
              </div>
            </div>
          ) : null}

          {railPanel === "debug" ? (
            <div className="fl-panel">
              <div className="fl-empty">
                本版本不含调试器接入。要验证代码改动，用底部面板的「变更提案」批准落盘 —— 落盘时会自动运行项目测试命令。
              </div>
            </div>
          ) : null}

          {railPanel === "ext" ? (
            <div className="fl-panel">
              <div className="fl-empty">
                扩展市场尚未接入。当前工作区由 Flux 只读打开，写盘统一走人工审核（ApplyEngine）。
              </div>
            </div>
          ) : null}
        </aside>

        <main className="ide-center">
          <div className="ed-tabs">
            {openTabs.map((path) => (
              <button
                type="button"
                key={path}
                className={`ed-tab${activeTab === path ? " is-active" : ""}`}
                onClick={() => setActiveTab(path)}
              >
                <span className={`t-ic ${iconClass(path)}`} />
                {path.split("/").pop() ?? path}
                <span
                  className="tab-x"
                  onClick={(event) => {
                    event.stopPropagation();
                    closeTab(path);
                  }}
                >
                  ×
                </span>
              </button>
            ))}
            <button
              type="button"
              className={`ed-tab${activeTab === CHANGES_TAB ? " is-active" : ""}`}
              onClick={() => setActiveTab(CHANGES_TAB)}
            >
              <span className="t-ic diff" />
              Changes
              <span className="tab-count">{openChanges.length}</span>
              <span
                className="tab-x"
                onClick={(event) => {
                  event.stopPropagation();
                  closeTab(CHANGES_TAB);
                }}
              >
                ×
              </span>
            </button>
            <span className="ed-tabs-gap" />
            <button
              type="button"
              className="icon-btn sm"
              title="已打开的文件"
              onClick={() => toast(`已打开 ${openTabs.length} 个文件标签 · 点标签上的 × 关闭`)}
            >
              …
            </button>
          </div>
          {renderEditor()}
        </main>

        <aside className="ide-agent">
          <div className="ag-head">
            <b>
              <span className="ag-ic">AI</span>Agent
            </b>
            {runningTask ? (
              <span className="pill pill-run sm">
                <i className="dot" />
                运行中
              </span>
            ) : (
              <span className="pill sm">{task ? "待命" : "无任务"}</span>
            )}
            <span className="ag-head-gap" />
            <button
              type="button"
              className="icon-btn sm"
              title="刷新"
              onClick={() => {
                void loadAgents();
                void loadTask(projectId);
                void loadChanges();
              }}
            >
              ⟳
            </button>
            <button
              type="button"
              className="icon-btn sm"
              id="closeAgent"
              title={isMobile ? "关闭抽屉" : "收起面板"}
              onClick={() => {
                if (isMobile) setMPanel("none");
                else toggleAgent();
              }}
            >
              ×
            </button>
          </div>
          <div className="ag-scroll">
            {task ? (
              <>
                <div className="ag-card">
                  <div className="ag-id">
                    <span className="ag-logo">◆</span>
                    <div>
                      <b>{lastModel ? lastModel.model : "平台助手"}</b>
                      <i>{lastModel ? `${lastModel.provider} · 任务助手` : "任务助手"}</i>
                    </div>
                  </div>
                  <span className="ag-state">{runningTask ? "执行中" : task.status === "completed" ? "已完成" : "待命"}</span>
                </div>
                <div className="ag-status">
                  {task.status === "completed" ? "任务已完成" : runningTask ? "正在按执行计划推进" : "等待下一步指令"}：
                  {task.description.split("\n")[0]}
                </div>
                <div className="ag-task">
                  <div className="ag-task-head">
                    <b>当前任务</b>
                    <button type="button" className="link" onClick={() => showBottom("flow")}>
                      查看完整日志 ›
                    </button>
                  </div>
                  <div className="ag-task-name">{task.description.split("\n")[0]}</div>
                </div>
                <div className="ag-sec-head">执行计划（{planSteps.length} 步）</div>
                {planSteps.length === 0 ? (
                  <div className="ag-note">助手还没有给出执行计划：在 Solo 里先发一条需求，澄清与计划会落库到这里。</div>
                ) : (
                  <ol className="ag-steps">
                    {planSteps.map((step, index) => (
                      <li key={`${index}-${step}`}>
                        <span className="s-ic">{index + 1}</span>
                        <span className="s-tx">{step}</span>
                        <span className="s-n">
                          {index + 1}/{planSteps.length}
                        </span>
                      </li>
                    ))}
                  </ol>
                )}
                <div className="ag-sec-head">变更提案（{taskChanges.length} 项）</div>
                {taskChanges.length === 0 ? (
                  <div className="ag-note">这个任务还没有产出文件改动。</div>
                ) : (
                  <ul className="ag-tools">
                    {taskChanges.map((change) => {
                      const badge = changeBadge(change);
                      return (
                        <li key={change.id}>
                          <span className="tool-ic">✎</span>
                          <span className="tool-name">{change.file_path.split("/").pop()}</span>
                          <span className="tool-path">{change.file_path}</span>
                          <span className="tool-time">{badge.label}</span>
                        </li>
                      );
                    })}
                  </ul>
                )}
                <div className="ag-sec-head">Agent 团队（{agents.length} 个）</div>
                {agents.length === 0 ? (
                  <div className="ag-note">尚未装配 Agent：在 Solo 右栏「Agent 团队」里一键装配内置角色。</div>
                ) : (
                  <ul className="ag-tools">
                    {agents.map((agent) => (
                      <li key={agent.id}>
                        <span className="tool-ic">◆</span>
                        <span className="tool-name">{agent.spec.name}</span>
                        <span className="tool-path">{ROLE_LABELS[agent.spec.role] ?? agent.spec.role}</span>
                        <span className="tool-time">{STATE_LABELS[agent.state] ?? agent.state}</span>
                      </li>
                    ))}
                  </ul>
                )}
              </>
            ) : (
              <div className="ag-note">
                还没有任务：在 Solo 提交需求后，助手回复、执行计划与文件改动都会出现在这里。细节数据来自
                GET /tasks 与 GET /agents。
              </div>
            )}
          </div>
        </aside>

        <button
          type="button"
          className={`agent-collapsed${agentClosed ? "" : " is-hidden"}`}
          title="展开 Agent 面板"
          onClick={toggleAgent}
        >
          AI
        </button>
      </div>

      <div className="ide-bottom" style={{ height: bottomHeight }}>
        <div className="bp-resizer" title="拖动调整高度" onMouseDown={onResizerDown} />
        <div className="bp-head">
          <button
            type="button"
            className={`bp-tab${bottomTab === "flow" ? " is-active" : ""}`}
            onClick={() => {
              setBottomTab("flow");
              setBottomOpen(true);
            }}
          >
            AI 任务流<em>{taskMessages.length}</em>
          </button>
          <button
            type="button"
            className={`bp-tab${bottomTab === "changes" ? " is-active" : ""}`}
            onClick={() => {
              setBottomTab("changes");
              setBottomOpen(true);
            }}
          >
            变更提案<em>{openChanges.length}</em>
          </button>
          <button
            type="button"
            className={`bp-tab${bottomTab === "git" ? " is-active" : ""}`}
            onClick={() => {
              setBottomTab("git");
              setBottomOpen(true);
            }}
          >
            Git<em>{gitStatus?.files.length ?? 0}</em>
          </button>
          <span className="bp-gap" />
          <span className="bp-shell">
            待审 {pendingChanges.length} · 失败 {failedChanges.length}
          </span>
          <button
            type="button"
            className="btn btn-ghost btn-xs"
            disabled={rollbackBusy}
            title="把最近一次落盘（Apply 批）整体回滚到落盘前内容；Flux 不自动产生 git 提交"
            onClick={() => void rollbackLast()}
          >
            {rollbackBusy ? "回滚中…" : "回滚上一次"}
          </button>
          <button
            type="button"
            className="icon-btn sm"
            title="刷新"
            onClick={() => {
              if (bottomTab === "git") void loadGit();
              else if (bottomTab === "changes") void loadChanges();
              else void loadTask(projectId);
            }}
          >
            ⟳
          </button>
          <button type="button" className="icon-btn sm" id="bpClose" title="收起面板 (⌘J)" onClick={toggleBottom}>
            ×
          </button>
        </div>
        <div className="bp-body">{renderBottom()}</div>
      </div>

      <footer className="ide-status">
        <div className="st-left">
          <button
            type="button"
            className="st-item"
            onClick={() =>
              toast(
                gitStatus
                  ? `分支 ${branch ?? "未关联仓库"} · ${gitStatus.clean ? "工作区干净" : `变更 ${gitStatus.files.length} 个文件`}`
                  : "Git 状态不可用",
              )
            }
          >
            <span className="br-ic" />
            {branch ?? "未关联仓库"} <i>▾</i>
          </button>
          <span className="st-item">变更 {gitStatus?.files.length ?? 0}</span>
          <span className="st-item">待审 {pendingChanges.length}</span>
          <span className="st-item">失败 {failedChanges.length}</span>
        </div>
        <div className="st-right">
          <span className="st-item">UTF-8</span>
          <span className="st-item">{activeFile?.content.includes("\r\n") ? "CRLF" : "LF"}</span>
          <span className="st-item">{activeTab && activeTab !== CHANGES_TAB ? langLabel(activeTab) : "Changes Diff"}</span>
          {runningTask ? (
            <span className="st-item st-run">
              <i className="dot" />
              Agent 运行中
            </span>
          ) : (
            <span className="st-item">Agent 待命</span>
          )}
        </div>
      </footer>

      <div className="ide-footer">
        <button type="button" className="ft-btn" onClick={toggleFocus}>
          <span className="ft-k">Focus Mode</span>
          <span className="ft-t">专注模式：隐藏所有面板，只保留 IDE</span>
          <kbd>⌘\</kbd>
        </button>
        <span className="ft-center">Flux · IDE-first · 让 AI 真正融入你的开发工作流</span>
        <button type="button" className="ft-btn right" onClick={openCommandPalette}>
          <span className="ft-ic">⌘</span>
          <span className="ft-k">Command Center</span>
          <kbd>⌘K</kbd>
        </button>
      </div>

      <button
        type="button"
        className={`focus-exit${focusMode ? "" : " is-hidden"}`}
        onClick={toggleFocus}
      >
        退出专注模式 <kbd>⌘\</kbd>
      </button>

      {reviewChange ? (
        <div
          className="review-mask"
          onClick={(event) => {
            if (event.target === event.currentTarget) setReviewId(null);
          }}
        >
          <div className="review-dialog">
            <div className="rv-head">
              <b>变更提案审核</b>
              <button type="button" className="icon-btn sm" onClick={() => setReviewId(null)}>
                ×
              </button>
            </div>
            <div className="rv-sub">
              {reviewChange.file_path} · +{reviewChange.added_lines} −{reviewChange.removed_lines} ——{" "}
              {reviewChange.reason?.trim() || reviewChange.summary?.trim() || reviewChange.agent_source || "Agent 提案"}
              ，批准后才会写入工作区并运行项目测试
            </div>
            <div className="rv-diff">
              <div className="diff-view">
                {parseUnifiedDiff(reviewChange.diff).map((row, index) => (
                  <div className={`dl ${row.kind}`} key={`${row.kind}-${index}`}>
                    <span className="lno old">{row.oldNo}</span>
                    <span className="lno new">{row.newNo}</span>
                    <span className="dc">{row.text}</span>
                  </div>
                ))}
              </div>
            </div>
            <div className="rv-foot">
              <button
                type="button"
                className="btn btn-ghost"
                disabled={reviewBusy}
                onClick={() => {
                  setReviewBusy(true);
                  void runChangeAction(reviewChange, "reject").finally(() => setReviewBusy(false));
                }}
              >
                拒绝
              </button>
              <button
                type="button"
                className="btn btn-primary"
                disabled={reviewBusy}
                onClick={() => {
                  setReviewBusy(true);
                  void runChangeAction(reviewChange, "apply").finally(() => setReviewBusy(false));
                }}
              >
                {reviewBusy ? "处理中…" : "批准并落盘"}
              </button>
            </div>
          </div>
        </div>
      ) : null}

      {recoveryItem ? (
        <div
          className="review-mask"
          onClick={(event) => {
            if (event.target === event.currentTarget) setRecoveryOpenId(null);
          }}
        >
          <div className="review-dialog">
            <div className="rv-head">
              <b>崩溃恢复 · 需要你确认</b>
              <button type="button" className="icon-btn sm" onClick={() => setRecoveryOpenId(null)}>
                ×
              </button>
            </div>
            <div className="rv-sub">
              {recoveryItem.file_path} —— {recoveryItem.note}
              {recoveryItem.disk_state === "deleted" ? "（文件已被外部删除）" : ""}
              {recoveryItem.backup_available ? "" : "（备份缺失，只能保持现状）"}
            </div>
            <div className="rv-diff">
              <div className="rec-3col">
                <div className="rec-col">
                  <h4>改动前原文（备份）</h4>
                  <pre>{recoveryItem.original_content || "（空文件）"}</pre>
                </div>
                <div className="rec-col">
                  <h4>当前磁盘内容</h4>
                  <pre>{recoveryItem.disk_content ?? "（文件不存在或非文本）"}</pre>
                </div>
                <div className="rec-col">
                  <h4>Flux 提案内容（未生效）</h4>
                  <pre>{recoveryItem.proposed_content || "（空文件）"}</pre>
                </div>
              </div>
            </div>
            <div className="rv-foot">
              <button
                type="button"
                className="btn btn-ghost"
                disabled={recoveryBusy}
                onClick={() => {
                  void resolveRecovery(recoveryItem, "keep");
                }}
              >
                {recoveryBusy ? "处理中…" : "保持现状"}
              </button>
              <button
                type="button"
                className="btn btn-primary"
                disabled={recoveryBusy || !recoveryItem.backup_available}
                onClick={() => {
                  void resolveRecovery(recoveryItem, "cover");
                }}
              >
                覆盖备份
              </button>
            </div>
          </div>
        </div>
      ) : null}

      {quickOpen ? (
        <div className="review-mask" onMouseDown={(event) => {
          if (event.target === event.currentTarget) setQuickOpen(false);
        }}>
          <div className="quick-open-dialog">
            <div className="quick-open-input">
              <span>⌕</span>
              <input
                autoFocus
                value={quickOpenQuery}
                placeholder="快速打开文件…"
                onChange={(event) => setQuickOpenQuery(event.target.value)}
                onKeyDown={(event) => {
                  if (event.key === "Escape") setQuickOpen(false);
                  if (event.key === "Enter" && quickOpenItems[0]) {
                    openFile(quickOpenItems[0]);
                    setQuickOpen(false);
                  }
                }}
              />
              <kbd>Esc</kbd>
            </div>
            <div className="quick-open-list">
              {quickOpenItems.length === 0 ? (
                <div className="quick-open-empty">没有匹配的文件。</div>
              ) : (
                quickOpenItems.map((path) => (
                  <button
                    type="button"
                    className={`quick-open-item${activeTab === path ? " is-active" : ""}`}
                    key={path}
                    onClick={() => { openFile(path); setQuickOpen(false); }}
                  >
                    <span className={`t-ic ${iconClass(path)}`} />
                    <span className="quick-open-path">{path}</span>
                  </button>
                ))
              )}
            </div>
          </div>
        </div>
      ) : null}
      {projectOpen ? (
        <div
          className="review-mask"
          onClick={(event) => {
            if (event.target === event.currentTarget) setProjectOpen(false);
          }}
        >
          <div className="review-dialog">
            <div className="rv-head">
              <b>切换项目</b>
              <button type="button" className="icon-btn sm" onClick={() => setProjectOpen(false)}>
                ×
              </button>
            </div>
            <div className="rv-sub">
              工作区根目录由服务端 FLUX_WORKSPACE_ROOT 决定；切换项目只会换掉任务与项目上下文
            </div>
            <div className="dialog-body">
              {projects.length === 0 ? (
                <div className="empty-note wide">还没有登记项目。</div>
              ) : (
                <ul className="tasks">
                  {projects.map((project) => (
                    <li
                      key={project.id}
                      className={project.id === projectId ? "is-active" : ""}
                      onClick={() => {
                        setProjectId(project.id);
                        setProjectOpen(false);
                        toast(`已切换到项目「${project.name}」`);
                      }}
                    >
                      <div className="tk-main">
                        <b>{project.name}</b>
                        <i>{project.repository ?? "未登记仓库地址"}</i>
                      </div>
                      <span className="tk-time">{project.id.slice(0, 8)}</span>
                    </li>
                  ))}
                </ul>
              )}
            </div>
          </div>
        </div>
      ) : null}
    </div>
  );
}
/**
 * Solo · AI 任务执行中心（设计稿 v2 视图①）。
 *
 * 页面结构、类名、动效与设计稿逐条一致；数据全部来自后端真实接口，无 mock：
 * GET /tasks · GET|POST /tasks/{id}/messages · GET /workspace/changes ·
 * POST /workspace/accept|apply|reject · GET|POST /agents · GET /projects。
 *
 * 不显示完成百分比：阶段内剩余工作量无法测量，只报事实（第几步 / 共几步）。
 */
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import type { CSSProperties } from "react";

import { api } from "../api/client";
import type {
  AgentHandle,
  Change,
  ConfirmationItem,
  DshStatus,
  Installation,
  Project,
  Task,
  TaskMessage,
  TaskStatus,
} from "../api/types";
import { openCommandPalette } from "../app/commands";
import { parseUnifiedDiff } from "../app/diff";
import { setSoloIntentHandler } from "../app/soloIntent";
import { openTerminalWindow } from "../app/terminalWindow";
import { toast } from "../app/toast";
import { MOBILE_QUERY, useMediaQuery } from "../app/useMediaQuery";
import { AgentChatPanel } from "../components/agent/AgentChatPanel";
import { SoloSidePanel } from "../components/side/SoloSidePanel";
import { SoloChat, clockOf } from "../components/solo/SoloChat";
import { BUILTIN_ROLES, ROLE_LABELS, STATE_LABELS, toCreateRequest } from "../data/team";

const TASK_STATUS_LABELS: Record<TaskStatus, string> = {
  pending: "待执行",
  running: "执行中",
  waiting_for_user_decision: "等待你的决策",
  completed: "已完成",
  failed: "失败",
  cancelled: "已取消",
};

const STRATEGY_LABELS: Record<"auto" | "manual", string> = {
  auto: "使用 AI 默认方案",
  manual: "由我决定",
};

/** 本轮改动的状态 → 中文短标签（汇总条展开时逐个文件显示） */
const ROUND_STATUS_LABELS: Record<string, string> = {
  pending: "待审核",
  accepted: "已批准",
  applied: "已落盘",
  rolled_back: "已回滚",
  failed: "落盘失败",
  rejected: "已拒绝",
};

/** 引用前缀：形如「引用：「原文」\n\n正文」，落库后刷新仍在 */
const QUOTE_PREFIX = "引用：";

/** 右栏宽度的本机留存与合法区间（null = 用户没拖过，交给 CSS 的默认值） */
const LS_SIDE_WIDTH = "flux.solo.sideWidth";
const SIDE_W_MIN = 260;
const SIDE_W_MAX = 720;

function readSideWidth(): number | null {
  const stored = Number(window.localStorage.getItem(LS_SIDE_WIDTH));
  return Number.isFinite(stored) && stored >= SIDE_W_MIN && stored <= SIDE_W_MAX ? stored : null;
}

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

/** 任务标题：描述的第一行（最长 60 字） */
function taskTitle(description: string): string {
  const firstLine = description.split("\n")[0]?.trim() ?? "";
  return firstLine.length > 60 ? `${firstLine.slice(0, 60)}…` : firstLine;
}

type StepState = "done" | "active" | "todo";

interface FlowStep {
  name: string;
  state: StepState;
  /** 状态文案（流程条第二行） */
  note: string;
}

/** 移动端屏（设计稿 mobile-v1 的 10 屏归并为 6 个入口屏） */
type MScreen = "hub" | "solo" | "task" | "projects" | "agents" | "me";

const M_SCREEN_TITLES: Partial<Record<MScreen, string>> = {
  solo: "Solo",
  projects: "项目",
  agents: "Agent",
  me: "我的",
};

/** 状态 → 列表图标符号与类名（移动端任务列表） */
const M_STATUS_MARK: Record<TaskStatus, { mark: string; cls: string }> = {
  pending: { mark: "○", cls: "" },
  running: { mark: "↻", cls: "is-run" },
  waiting_for_user_decision: { mark: "?", cls: "is-wait" },
  completed: { mark: "✓", cls: "is-ok" },
  failed: { mark: "✕", cls: "is-fail" },
  cancelled: { mark: "－", cls: "" },
};

/** 权限值 → 中文短标签（Agent 管理卡右侧） */
const CAP_LABELS: Record<string, string> = {
  "file.read": "读代码",
  "file.write": "写文件",
  "terminal.execute": "跑命令",
};

/** 移动端底部 Tab（设计稿：首页 / 项目 / Agent / 我的） */
const M_TABS: { key: MScreen; label: string; icon: React.ReactNode }[] = [
  {
    key: "hub",
    label: "首页",
    icon: (
      <svg viewBox="0 0 20 20" className="ic">
        <path
          d="M3 9.2 10 3.6l7 5.6V16a1 1 0 0 1-1 1h-4v-4.4H8V17H4a1 1 0 0 1-1-1Z"
          fill="none"
          stroke="currentColor"
          strokeWidth="1.4"
          strokeLinejoin="round"
        />
      </svg>
    ),
  },
  {
    key: "projects",
    label: "项目",
    icon: (
      <svg viewBox="0 0 20 20" className="ic">
        <path
          d="M3 5.5A1.5 1.5 0 0 1 4.5 4h3l1.6 2H16a1.5 1.5 0 0 1 1.5 1.5v7A1.5 1.5 0 0 1 16 16H4.5A1.5 1.5 0 0 1 3 14.5Z"
          fill="none"
          stroke="currentColor"
          strokeWidth="1.4"
          strokeLinejoin="round"
        />
      </svg>
    ),
  },
  {
    key: "agents",
    label: "Agent",
    icon: (
      <svg viewBox="0 0 20 20" className="ic">
        <path
          d="M10 2.4 17.6 10 10 17.6 2.4 10Z"
          fill="none"
          stroke="currentColor"
          strokeWidth="1.4"
          strokeLinejoin="round"
        />
      </svg>
    ),
  },
  {
    key: "me",
    label: "我的",
    icon: (
      <svg viewBox="0 0 20 20" className="ic">
        <circle cx="10" cy="6.6" r="3" fill="none" stroke="currentColor" strokeWidth="1.4" />
        <path
          d="M4.2 16.4c.6-2.7 2.9-4.2 5.8-4.2s5.2 1.5 5.8 4.2"
          fill="none"
          stroke="currentColor"
          strokeWidth="1.4"
          strokeLinecap="round"
        />
      </svg>
    ),
  },
];

/** 内置小模型（平台自带能力，无需装配；任务流中的澄清/编排/决策建议由它们承担） */
const BUILTIN_MODELS: { name: string; desc: string; cls: string }[] = [
  { name: "需求理解 · 任务编排", desc: "澄清需求、生成执行计划，由平台在任务流中自动调用", cls: "a2" },
  { name: "风险判断 · 决策建议", desc: "评估改动风险、给出候选方案与 AI 建议，无需单独装配", cls: "a4" },
];

/** 外部 Agent（CLI）接入状态 → 徽标文案与配色（与后端 AgentInstallStatus 对齐） */
const INSTALL_STATUS_META: Record<string, { label: string; cls: string }> = {
  READY: { label: "已接入", cls: "b-ok" },
  CONNECTED: { label: "已发现", cls: "b-run" },
  VERIFIED: { label: "已发现", cls: "b-run" },
  DISCOVERED: { label: "已发现", cls: "b-run" },
  NOT_INSTALLED: { label: "未安装", cls: "" },
};

/** runtime → 显示名：执行该 Agent 的引擎（档案响应体现在带 runtime 字段） */
const RUNTIME_LABELS: Record<string, string> = {
  dsh: "内置 DSH",
  codex: "Codex CLI",
  opencode: "OpenCode CLI",
};

/**
 * 外部 Agent（CLI）接入区块：扫描本机 → 接入 → 为它建一个对应 runtime 的 Agent 档案。
 *
 * 数据是真实的本机安装事实（GET /installations），不是预设清单：
 * 未安装的显示"未安装"且不给接入按钮；已接入的可以"建 Agent"（runtime 指向该 CLI）。
 */
function ExternalAgentsSection({
  installations,
  busy,
  onScan,
  onConnect,
  onRemove,
  onCreateAgent,
}: {
  installations: Installation[];
  busy: string | null;
  onScan: () => void;
  onConnect: (name: string) => void;
  onRemove: (name: string) => void;
  onCreateAgent: (runtime: string) => void;
}) {
  return (
    <>
      <div className="m-sec-head" style={{ marginTop: 14 }}>
        <b>外部 Agent（CLI）</b>
        <button type="button" className="link" disabled={busy === "scan"} onClick={onScan}>
          {busy === "scan" ? "扫描中…" : "扫描本机"}
        </button>
      </div>
      {installations.length === 0 ? (
        <div className="card m-agent-empty">
          <p>还没扫描过本机 CLI Agent。点「扫描本机」会发现 codex / opencode 的安装与凭据状态。</p>
        </div>
      ) : (
        <div className="m-agent-list">
          {installations.map((item) => {
            const meta = INSTALL_STATUS_META[item.status] ?? { label: item.status, cls: "" };
            const rowBusy = busy === item.name;
            const ready = item.status === "READY";
            const installed = item.status !== "NOT_INSTALLED";
            return (
              <div className="card m-agent" key={item.id}>
                <span className="tm-ava a3">{item.name.slice(0, 2).toUpperCase()}</span>
                <div className="m-agent-main">
                  <b>
                    {item.name} <span className="chip chip-dim">CLI</span>
                  </b>
                  <i>
                    {item.version ? `v${item.version} · ` : ""}
                    {item.path ?? "未找到可执行文件"}
                    {item.auth_status === "ok"
                      ? " · 凭据 OK"
                      : item.auth_status === "missing"
                        ? " · 凭据缺失"
                        : ""}
                  </i>
                </div>
                <span className={`tl-badge ${meta.cls}`}>{meta.label}</span>
                {ready ? (
                  <span className="ext-agent-actions">
                    <button
                      type="button"
                      className="btn btn-xs btn-primary"
                      disabled={busy === `agent:${item.name}`}
                      onClick={() => onCreateAgent(item.name)}
                      title={`创建一个 runtime=${item.name} 的 Agent 档案`}
                    >
                      {busy === `agent:${item.name}` ? "创建中…" : "建 Agent"}
                    </button>
                    <button
                      type="button"
                      className="btn btn-xs btn-ghost"
                      disabled={rowBusy}
                      onClick={() => onRemove(item.name)}
                    >
                      移除
                    </button>
                  </span>
                ) : installed ? (
                  <button
                    type="button"
                    className="btn btn-xs btn-primary"
                    disabled={rowBusy}
                    onClick={() => onConnect(item.name)}
                  >
                    {rowBusy ? "接入中…" : "接入"}
                  </button>
                ) : null}
              </div>
            );
          })}
        </div>
      )}
      <p className="ext-agent-note">
        接入 = 本机具备该 CLI 且验证通过；任务要真正走它，还需建一个该 runtime 的 Agent，并在新建任务时绑定。
      </p>
    </>
  );
}

/** Agent 管理内容（桌面弹层与移动端「Agent」屏共用同一组件与真实数据） */
function AgentRoster({
  agents,
  assembling,
  onAssemble,
  installations,
  installBusy,
  onScanInstallations,
  onConnectInstallation,
  onRemoveInstallation,
  onCreateAgentForRuntime,
}: {
  agents: AgentHandle[];
  assembling: boolean;
  onAssemble: () => void;
  installations: Installation[];
  installBusy: string | null;
  onScanInstallations: () => void;
  onConnectInstallation: (name: string) => void;
  onRemoveInstallation: (name: string) => void;
  onCreateAgentForRuntime: (runtime: string) => void;
}) {
  const [tab, setTab] = useState<"all" | "mine">("all");
  return (
    <>
      <div className="m-seg">
        <button
          type="button"
          className={`m-seg-item${tab === "all" ? " is-active" : ""}`}
          onClick={() => setTab("all")}
        >
          全部
        </button>
        <button
          type="button"
          className={`m-seg-item${tab === "mine" ? " is-active" : ""}`}
          onClick={() => setTab("mine")}
        >
          我的
        </button>
      </div>
      {tab === "all" ? (
        <div className="m-agent-list">
          {BUILTIN_ROLES.map((role, index) => (
            <div className="card m-agent" key={role.role}>
              <span className={`tm-ava a${(index % 4) + 1}`}>{role.name.slice(0, 2).toUpperCase()}</span>
              <div className="m-agent-main">
                <b>{role.name}</b>
                <i>
                  {role.label} · {role.description}
                </i>
              </div>
              <span className="chip chip-dim">
                {role.permissions.map((cap) => CAP_LABELS[cap] ?? cap).join(" · ")}
              </span>
            </div>
          ))}
          <div className="m-sec-head">
            <b>内置小模型</b>
          </div>
          {BUILTIN_MODELS.map((model) => (
            <div className="card m-agent" key={model.name}>
              <span className={`tm-ava ${model.cls}`}>小</span>
              <div className="m-agent-main">
                <b>{model.name}</b>
                <i>{model.desc}</i>
              </div>
              <span className="tl-badge b-ok">内置</span>
            </div>
          ))}
        </div>
      ) : agents.length === 0 ? (
        <div className="card m-agent-empty">
          <p>还没有装配 Agent：内置角色只有档案与权限边界，装配后以内置「本地 echo 模型」登记。</p>
          <button type="button" className="btn btn-primary btn-block" disabled={assembling} onClick={onAssemble}>
            {assembling ? "正在装配…" : "一键装配内置团队"}
          </button>
        </div>
      ) : (
        <div className="m-agent-list">
          {agents.map((agent, index) => (
            <div className="card m-agent" key={agent.id}>
              <span className={`tm-ava a${(index % 4) + 1}`}>
                {agent.spec.name.slice(0, 2).toUpperCase()}
              </span>
              <div className="m-agent-main">
                <b>{agent.spec.name}</b>
                <i>
                  {ROLE_LABELS[agent.spec.role] ?? agent.spec.role} ·{" "}
                  {RUNTIME_LABELS[agent.spec.runtime ?? "dsh"] ?? agent.spec.runtime ?? "dsh"} · 已执行{" "}
                  {agent.execution_count} 次
                </i>
              </div>
              <span
                className={`tl-badge${agent.state === "RUNNING" ? " b-run" : agent.state === "FAILED" ? "" : " b-ok"}`}
              >
                {STATE_LABELS[agent.state] ?? agent.state}
              </span>
            </div>
          ))}
        </div>
      )}
      {tab === "mine" ? (
        <ExternalAgentsSection
          installations={installations}
          busy={installBusy}
          onScan={onScanInstallations}
          onConnect={onConnectInstallation}
          onRemove={onRemoveInstallation}
          onCreateAgent={onCreateAgentForRuntime}
        />
      ) : null}
    </>
  );
}

export function SoloPage({ onOpenWorkspace }: { onOpenWorkspace: () => void }) {
  const [projects, setProjects] = useState<Project[]>([]);
  const [projectId, setProjectId] = useState<string | null>(null);
  const [tasks, setTasks] = useState<Task[]>([]);
  const [taskId, setTaskId] = useState<string | null>(null);

  const [messages, setMessages] = useState<TaskMessage[]>([]);
  const [hasMore, setHasMore] = useState(false);
  const [msgLoading, setMsgLoading] = useState(false);
  const [loadingOlder, setLoadingOlder] = useState(false);
  const [msgError, setMsgError] = useState<string | null>(null);

  const [agents, setAgents] = useState<AgentHandle[]>([]);
  const [installations, setInstallations] = useState<Installation[]>([]);
  // 外部 Agent 区块的忙碌态：null / "scan" / 安装名 / "agent:<安装名>"（建档案）
  const [installBusy, setInstallBusy] = useState<string | null>(null);
  const [changes, setChanges] = useState<Change[]>([]);
  const [assembling, setAssembling] = useState(false);

  const [draft, setDraft] = useState("");
  const [sending, setSending] = useState(false);
  const [quote, setQuote] = useState<string | null>(null);
  const [strategy, setStrategy] = useState<"auto" | "manual">(() =>
    window.localStorage.getItem("flux.solo.strategy") === "manual" ? "manual" : "auto",
  );
  const [stratOpen, setStratOpen] = useState(false);
  const [stratDown, setStratDown] = useState(false);
  // 新任务的执行 Agent（null = 内置 DSH）；已建任务不可改，只在"新任务"态露出
  const [taskAgentId, setTaskAgentId] = useState<string | null>(null);
  const [engineOpen, setEngineOpen] = useState(false);
  const [engineDown, setEngineDown] = useState(false);

  // 中栏模式：流水线（任务流编排）/ 单 Agent（一点一点改），全局切换、两种会话共存
  const [soloMode, setSoloMode] = useState<"pipeline" | "agent">(() =>
    window.localStorage.getItem("flux.solo.mode") === "agent" ? "agent" : "pipeline",
  );
  // 单 Agent 模式的执行者（null = 内置 DSH）：与 IDE 右栏走同一套 DSH 通道
  const [agentPickId, setAgentPickId] = useState<string | null>(
    () => window.localStorage.getItem("flux.solo.agent.pick") || null,
  );
  const [dsh, setDsh] = useState<DshStatus | null>(null);

  /* 单 Agent 模式：一轮改动的归属。Change 没有 run_id，只能在起 Run 前给「已有 pending」拍一个
     基线快照，落定后把新冒出来的 change 认领为本轮改动。 */
  const [roundIds, setRoundIds] = useState<string[]>([]);
  const [roundOpen, setRoundOpen] = useState(false);
  const [roundBusy, setRoundBusy] = useState(false);
  // 本轮改动是留 pending 等你点「保留」，还是直接落盘（不需要审核）
  const [agentAutoApply, setAgentAutoApply] = useState<boolean>(
    () => window.localStorage.getItem("flux.solo.agent.autoApply") === "1",
  );
  const roundBaseRef = useRef<Set<string>>(new Set());
  /** 右栏宽度（px）：null = 用户没拖过，用样式表里的默认值 */
  const [sideWidth, setSideWidth] = useState<number | null>(readSideWidth);
  /** 本轮改动（按 id 认领），状态从最新的 changes 里读，落盘/回滚后依然看得到 */
  const roundChanges = useMemo(
    () => changes.filter((change) => roundIds.includes(change.id)),
    [changes, roundIds],
  );
  const roundFiles = useMemo(() => new Set(roundChanges.map((change) => change.file_path)).size, [roundChanges]);
  const roundAddedLines = useMemo(
    () => roundChanges.reduce((sum, change) => sum + change.added_lines, 0),
    [roundChanges],
  );
  const roundRemovedLines = useMemo(
    () => roundChanges.reduce((sum, change) => sum + change.removed_lines, 0),
    [roundChanges],
  );

  const [reviewId, setReviewId] = useState<string | null>(null);
  const [reviewBusy, setReviewBusy] = useState(false);
  const [taskListOpen, setTaskListOpen] = useState(false);
  const [projectListOpen, setProjectListOpen] = useState(false);

  // 需求确认（P0-06）：draft 是用户在面板里改的版本，保存/执行都以它为准
  const [confirmDraft, setConfirmDraft] = useState<ConfirmationItem[] | null>(null);
  const [confirmBusy, setConfirmBusy] = useState(false);
  const [startBusy, setStartBusy] = useState(false);
  const [cancelBusy, setCancelBusy] = useState(false);
  // 决策点（文档 §5）：选择/拒绝的忙碌态 + 拒绝理由
  const [decisionBusy, setDecisionBusy] = useState(false);
  const [decisionNote, setDecisionNote] = useState("");

  // 移动端（≤860px）：底部 Tab 切屏；与桌面共用同一份真实数据
  const isMobile = useMediaQuery(MOBILE_QUERY);
  const [mScreen, setMScreen] = useState<MScreen>("hub");
  const [agentOpen, setAgentOpen] = useState(false);

  const inputRef = useRef<HTMLInputElement>(null);
  const stratRef = useRef<HTMLDivElement>(null);
  const popRef = useRef<HTMLDivElement>(null);
  const engineRef = useRef<HTMLDivElement>(null);
  const enginePopRef = useRef<HTMLDivElement>(null);

  const task = useMemo(() => tasks.find((item) => item.id === taskId) ?? null, [tasks, taskId]);
  const pendingChanges = useMemo(() => changes.filter((c) => c.status === "pending"), [changes]);
  const appliedChanges = useMemo(() => changes.filter((c) => c.status === "applied"), [changes]);
  const reviewChange = useMemo(
    () => pendingChanges.find((c) => c.id === reviewId) ?? null,
    [pendingChanges, reviewId],
  );
  const pendingDecision = task?.pending_decision ?? null;
  /** 任务是否已开始执行：已起过 DSH Run 就不能再改确认卡或重复执行 */
  const taskStarted = Boolean(task?.run_id);
  const taskFinal =
    task?.status === "completed" || task?.status === "failed" || task?.status === "cancelled";

  /** 新任务执行 Agent 的显示名（null = 内置 DSH） */
  const engineLabel = useMemo(() => {
    if (!taskAgentId) return "内置 DSH（默认）";
    const picked = agents.find((agent) => agent.id === taskAgentId);
    if (!picked) return "内置 DSH（默认）";
    const runtime = picked.spec.runtime ?? "dsh";
    return `${picked.spec.name} · ${RUNTIME_LABELS[runtime] ?? runtime}`;
  }, [taskAgentId, agents]);
  /** CLI runtime 是否已接入（内置 DSH 恒可用；未接入的选了也会在起 Run 时被拒） */
  const runtimeReady = (runtime: string) =>
    runtime === "dsh" ||
    installations.some((item) => item.name === runtime && item.status === "READY");

  // --- 加载 ---

  const loadProjects = useCallback(async () => {
    try {
      const list = await api.listProjects();
      setProjects(list);
      setProjectId((current) => current ?? list[0]?.id ?? null);
    } catch (error) {
      toast(errorMessage(error), "error");
    }
  }, []);

  const loadAgents = useCallback(async () => {
    try {
      setAgents(await api.listAgents());
    } catch (error) {
      toast(errorMessage(error), "error");
    }
  }, []);

  const loadInstallations = useCallback(async () => {
    try {
      setInstallations(await api.listInstallations());
    } catch (error) {
      toast(errorMessage(error), "error");
    }
  }, []);

  const loadChanges = useCallback(async () => {
    try {
      setChanges(await api.listChanges());
    } catch (error) {
      toast(errorMessage(error), "error");
    }
  }, []);

  const loadTasks = useCallback(async () => {
    try {
      const list = await api.listTasks({ limit: 50 });
      setTasks(list);
      setTaskId((current) =>
        current && list.some((item) => item.id === current) ? current : (list[0]?.id ?? null),
      );
    } catch (error) {
      toast(errorMessage(error), "error");
    }
  }, []);

  useEffect(() => {
    void loadProjects();
    void loadAgents();
    void loadInstallations();
    void loadChanges();
    void loadTasks();
  }, [loadProjects, loadAgents, loadInstallations, loadChanges, loadTasks]);

  // 单 Agent 模式依赖 DSH 运行时：取一次状态（未启用时输入框禁用）
  useEffect(() => {
    let alive = true;
    void api.dshStatus()
      .then((data) => { if (alive) setDsh(data); })
      .catch(() => { if (alive) setDsh(null); });
    return () => { alive = false; };
  }, []);

  useEffect(() => {
    window.localStorage.setItem("flux.solo.mode", soloMode);
  }, [soloMode]);

  useEffect(() => {
    if (agentPickId) window.localStorage.setItem("flux.solo.agent.pick", agentPickId);
    else window.localStorage.removeItem("flux.solo.agent.pick");
  }, [agentPickId]);

  useEffect(() => {
    window.localStorage.setItem("flux.solo.agent.autoApply", agentAutoApply ? "1" : "0");
  }, [agentAutoApply]);

  useEffect(() => {
    if (sideWidth === null) window.localStorage.removeItem(LS_SIDE_WIDTH);
    else window.localStorage.setItem(LS_SIDE_WIDTH, String(sideWidth));
  }, [sideWidth]);

  // 换任务/换会话时，本轮改动归属清零（避免把上一个会话的改动算进来）
  useEffect(() => {
    setRoundIds([]);
    setRoundOpen(false);
    roundBaseRef.current = new Set();
  }, [taskId, agentPickId]);

  // 切换任务：拉取最近 30 条消息（向上再翻用 seq 游标）
  useEffect(() => {
    if (!taskId) {
      setMessages([]);
      setHasMore(false);
      setMsgError(null);
      return;
    }
    let alive = true;
    setMsgLoading(true);
    setMsgError(null);
    api
      .listTaskMessages(taskId, { limit: 30 })
      .then((page) => {
        if (!alive) return;
        setMessages(page.items);
        setHasMore(page.hasMore);
      })
      .catch((error) => {
        if (!alive) return;
        setMessages([]);
        setHasMore(false);
        setMsgError(errorMessage(error));
      })
      .finally(() => {
        if (alive) setMsgLoading(false);
      });
    return () => {
      alive = false;
    };
  }, [taskId]);

  const loadOlder = useCallback(async () => {
    const first = messages[0];
    if (!taskId || loadingOlder || !hasMore || !first) return;
    setLoadingOlder(true);
    try {
      const page = await api.listTaskMessages(taskId, { limit: 30, before: first.seq });
      setMessages((prev) => [...page.items, ...prev]);
      setHasMore(page.hasMore);
    } catch (error) {
      toast(errorMessage(error), "error");
    } finally {
      setLoadingOlder(false);
    }
  }, [messages, taskId, loadingOlder, hasMore]);

  // 点弹层外部关闭决策策略 / 执行 Agent
  useEffect(() => {
    if (!stratOpen && !engineOpen) return;
    const onDown = (event: MouseEvent) => {
      const target = event.target as Node;
      if (stratOpen && !popRef.current?.contains(target) && !stratRef.current?.contains(target)) {
        setStratOpen(false);
      }
      if (
        engineOpen &&
        !enginePopRef.current?.contains(target) &&
        !engineRef.current?.contains(target)
      ) {
        setEngineOpen(false);
      }
    };
    document.addEventListener("mousedown", onDown);
    return () => document.removeEventListener("mousedown", onDown);
  }, [stratOpen, engineOpen]);

  // 重新拉取当前任务的消息（执行开始/阶段变化后把新消息显示出来）
  const reloadMessages = useCallback(async () => {
    if (!taskId) return;
    try {
      const page = await api.listTaskMessages(taskId, { limit: 30 });
      setMessages(page.items);
      setHasMore(page.hasMore);
    } catch {
      // 静默：消息刷新失败不该打断用户正在做的操作
    }
  }, [taskId]);

  // 任务级决策策略是权威：切换任务时以任务上的 decision_mode 为准（本机偏好只服务新建任务）
  useEffect(() => {
    if (task) setStrategy(task.decision_mode);
  }, [task?.id, task?.decision_mode]);

  // 需求确认卡：以 updated_at 为界同步草稿，避免轮询刷新时覆盖用户还没保存的编辑
  useEffect(() => {
    const items = task?.confirmation?.items;
    setConfirmDraft(items ? items.map((item) => ({ ...item })) : null);
  }, [task?.id, task?.confirmation?.updated_at]);

  // 向全局外壳暴露「项目列表 / Agent 面板」两个独有工具（依赖 app/soloIntent 的队列协议）。
  // 全局导航收敛成一套后，Solo 自带左栏被隐藏，这两个入口改由顶栏按钮触发。
  useEffect(() => {
    setSoloIntentHandler((intent) => {
      if (intent === "projects") setProjectListOpen(true);
      if (intent === "agents") setAgentOpen(true);
    });
    return () => setSoloIntentHandler(null);
  }, []);

  // 任务在跑或等人决策时轮询状态：进入终态/挂起后刷新消息与改动列表
  const activeStatus = task?.status;
  useEffect(() => {
    if (!taskId) return;
    if (activeStatus !== "running" && activeStatus !== "waiting_for_user_decision") return;
    let alive = true;
    const timer = window.setInterval(() => {
      api
        .getTask(taskId)
        .then((fresh) => {
          if (!alive) return;
          setTasks((prev) => prev.map((item) => (item.id === fresh.id ? fresh : item)));
          if (fresh.status !== "running" && fresh.status !== "waiting_for_user_decision") {
            void loadChanges();
            void reloadMessages();
          }
        })
        .catch(() => undefined);
    }, 3000);
    return () => {
      alive = false;
      window.clearInterval(timer);
    };
  }, [taskId, activeStatus, loadChanges, reloadMessages]);

  // --- 操作 ---

  const send = useCallback(async () => {
    const content = draft.trim();
    if (!content) {
      toast("请输入内容后再发送");
      return;
    }
    if (sending) return;
    const target = taskId ?? "";
    const payload = quote ? `${QUOTE_PREFIX}「${quote}」\n\n${content}` : content;
    setSending(true);
    setDraft("");
    try {
      let activeId = target;
      if (!activeId) {
        // 没有任务时，第一条需求直接建任务（描述即需求），决策策略用当前选择
        const created = await api.createTask({
          description: content,
          project_id: projectId,
          decision_mode: strategy,
          agent_id: taskAgentId,
        });
        setTasks((prev) => [created, ...prev]);
        setTaskId(created.id);
        activeId = created.id;
      }
      const outcome = await api.sendTaskMessage(activeId, payload);
      setMessages((prev) => [...prev, ...outcome.messages]);
      setTasks((prev) => prev.map((item) => (item.id === activeId ? outcome.task : item)));
      setQuote(null);
      toast(`已发送 · ${outcome.messages.length} 条消息已落库`);
    } catch (error) {
      toast(errorMessage(error), "error");
      setDraft(content);
    } finally {
      setSending(false);
    }
  }, [draft, quote, sending, taskId, projectId, strategy, taskAgentId]);

  const runReview = useCallback(
    async (approved: boolean) => {
      if (!reviewChange) return;
      setReviewBusy(true);
      try {
        if (approved) {
          await api.apply([reviewChange.id]);
          toast(`已批准并落盘 ${reviewChange.file_path} · 已运行项目测试`);
        } else {
          await api.reject([reviewChange.id], null);
          toast(`已拒绝 ${reviewChange.file_path} · 提案退回`);
        }
      } catch (error) {
        toast(errorMessage(error), "error");
      }
      setReviewBusy(false);
      setReviewId(null);
      await loadChanges();
      await loadTasks();
    },
    [reviewChange, loadChanges, loadTasks],
  );

  /* ---------- 自动落盘：Solo 不做人工审核 ----------
     本任务的提案一经产出就自动 apply（apply 顺带跑项目测试命令），不再需要「批准并落盘」这一步。
     只处理当前任务自己的提案：工作区里可能还躺着其它任务遗留的 pending 改动，不能一起应用。 */
  const autoAppliedRef = useRef<Set<string>>(new Set());

  useEffect(() => {
    if (!taskId) return;
    const mine = pendingChanges.filter((change) => change.task_id === taskId);
    const todo = mine.filter((change) => !autoAppliedRef.current.has(change.id));
    if (todo.length === 0) return;
    // 先登记再提交：轮询刷新会不断产出新的 pendingChanges 数组，不登记就会重复落盘
    for (const change of todo) autoAppliedRef.current.add(change.id);
    let alive = true;
    void (async () => {
      try {
        await api.apply(todo.map((change) => change.id));
        if (!alive) return;
        toast(`已自动落盘 ${todo.length} 项改动 · 已运行项目测试`);
      } catch (error) {
        if (!alive) return;
        toast(errorMessage(error), "error");
      }
      if (!alive) return;
      void loadChanges();
      void loadTasks();
    })();
    return () => { alive = false; };
  }, [pendingChanges, taskId, loadChanges, loadTasks]);

  /* ---------- 单 Agent 模式：一轮改动的汇总条 ---------- */

  /** 起 Run 前拍基线：把当时还没落盘的 pending 记下来，之后新出现的才算这一轮 */
  const onAgentRunStart = useCallback(() => {
    roundBaseRef.current = new Set(changes.filter((change) => change.status === "pending").map((change) => change.id));
    setRoundIds([]);
    setRoundOpen(false);
  }, [changes]);

  /** 重新拉改动并把新出现的认领进本轮（返回最新列表，便于调用方接着处理） */
  const refreshRound = useCallback(async () => {
    const fresh = await api.listChanges();
    setChanges(fresh);
    const base = roundBaseRef.current;
    const claimed = fresh.filter((change) => !base.has(change.id)).map((change) => change.id);
    setRoundIds((prev) => Array.from(new Set([...prev, ...claimed])));
    return fresh;
  }, []);

  /** 一轮 Run 落定：刷新改动；「不需要审核」时把这一轮的新提案直接落盘 */
  const onAgentSettled = useCallback(async () => {
    try {
      const fresh = await refreshRound();
      if (agentAutoApply) {
        const base = roundBaseRef.current;
        const todo = fresh
          .filter((change) => !base.has(change.id) && change.status === "pending")
          .map((change) => change.id);
        if (todo.length > 0) {
          await api.apply(todo);
          toast(`已自动落盘本轮 ${todo.length} 项改动 · 已运行项目测试`);
          await refreshRound();
        }
      }
    } catch (error) {
      toast(errorMessage(error), "error");
    }
    void loadTasks();
  }, [agentAutoApply, refreshRound, loadTasks]);

  /** 全保留：本轮还挂着的提案全部落盘 */
  const keepRound = useCallback(async () => {
    const pending = roundChanges.filter((change) => change.status === "pending").map((change) => change.id);
    if (pending.length === 0) {
      toast("本轮改动已经都在工作区里了");
      return;
    }
    setRoundBusy(true);
    try {
      await api.apply(pending);
      toast(`已保留本轮 ${pending.length} 项改动 · 已运行项目测试`);
      await refreshRound();
      await loadTasks();
    } catch (error) {
      toast(errorMessage(error), "error");
    } finally {
      setRoundBusy(false);
    }
  }, [roundChanges, refreshRound, loadTasks]);

  /** 全撤销：还挂着的提案退回；已经落盘的回滚 */
  const undoRound = useCallback(async () => {
    const pending = roundChanges.filter((change) => change.status === "pending").map((change) => change.id);
    const applied = roundChanges.filter((change) => change.status === "applied").map((change) => change.id);
    if (pending.length === 0 && applied.length === 0) {
      toast("本轮没有可撤销的改动");
      return;
    }
    setRoundBusy(true);
    try {
      if (pending.length > 0) await api.reject(pending, null);
      for (const id of applied) {
        await api.rollbackChange(id);
      }
      toast(`已撤销本轮改动（退回 ${pending.length} 项 · 回滚 ${applied.length} 项）`);
      await refreshRound();
      await loadTasks();
    } catch (error) {
      toast(errorMessage(error), "error");
    } finally {
      setRoundBusy(false);
    }
  }, [roundChanges, refreshRound, loadTasks]);

  /** 拖右栏左边缘调宽度（普通流内的独立网格列，避免热区被相邻元素截获） */
  const onSideResizeDown = useCallback(
    (event: React.MouseEvent) => {
      event.preventDefault();
      const startX = event.clientX;
      const startWidth =
        sideWidth ??
        document.querySelector<HTMLElement>(".s-side")?.getBoundingClientRect().width ??
        336;
      document.body.style.userSelect = "none";
      document.body.classList.add("side-resizing");
      const onMove = (move: MouseEvent) => {
        setSideWidth(Math.max(SIDE_W_MIN, Math.min(startWidth + (startX - move.clientX), SIDE_W_MAX)));
      };
      const onUp = () => {
        document.body.style.userSelect = "";
        document.body.classList.remove("side-resizing");
        document.removeEventListener("mousemove", onMove);
        document.removeEventListener("mouseup", onUp);
      };
      document.addEventListener("mousemove", onMove);
      document.addEventListener("mouseup", onUp);
    },
    [sideWidth],
  );

  const assembleTeam = useCallback(async () => {
    setAssembling(true);
    try {
      const existing = new Set(agents.map((agent) => agent.spec.role));
      for (const role of BUILTIN_ROLES) {
        if (existing.has(role.role)) continue;
        await api.createAgent(toCreateRequest(role));
      }
      setAgents(await api.listAgents());
      toast("已装配内置 Agent 团队（Tech Lead · Developer · Reviewer · Tester）");
    } catch (error) {
      toast(errorMessage(error), "error");
    } finally {
      setAssembling(false);
    }
  }, [agents]);

  // --- 外部 Agent（CLI）接入：扫描 → 接入 → 建对应 runtime 的档案 ---

  const scanInstallations = useCallback(async () => {
    setInstallBusy("scan");
    try {
      const rows = await api.scanInstallations();
      setInstallations(rows);
      const ready = rows.filter((row) => row.status === "READY").length;
      toast(`扫描完成 · 发现 ${rows.length} 个 CLI Agent，已接入 ${ready} 个`);
    } catch (error) {
      toast(errorMessage(error), "error");
    } finally {
      setInstallBusy(null);
    }
  }, []);

  const connectInstallation = useCallback(
    async (name: string) => {
      setInstallBusy(name);
      try {
        await api.connectInstallation({ agent: name });
        await loadInstallations();
        toast(`已接入 ${name} · 可在下方为它建一个 Agent 档案`);
      } catch (error) {
        toast(errorMessage(error), "error");
      } finally {
        setInstallBusy(null);
      }
    },
    [loadInstallations],
  );

  const removeInstallation = useCallback(
    async (name: string) => {
      setInstallBusy(name);
      try {
        await api.removeInstallation(name);
        await loadInstallations();
        toast(`已移除 ${name} 的接入记录（本机 CLI 本身不受影响）`);
      } catch (error) {
        toast(errorMessage(error), "error");
      } finally {
        setInstallBusy(null);
      }
    },
    [loadInstallations],
  );

  /** 为某个已接入的 CLI 建一个 runtime 指向它的 Agent 档案（重名自动加序号） */
  const createAgentForRuntime = useCallback(
    async (runtime: string) => {
      setInstallBusy(`agent:${runtime}`);
      try {
        const taken = new Set(agents.map((agent) => agent.spec.name));
        let name = `${runtime}-agent`;
        for (let seq = 2; taken.has(name); seq += 1) name = `${runtime}-agent-${seq}`;
        const engine = RUNTIME_LABELS[runtime] ?? runtime;
        await api.createAgent({
          name,
          role: "developer",
          model_provider: "local",
          model_name: "local-echo",
          description: `由 ${engine} 执行（外部 CLI 接入）`,
          permissions: ["file.read", "file.write", "terminal.execute"],
          runtime,
        });
        setAgents(await api.listAgents());
        toast(`已创建 Agent「${name}」· 执行引擎 ${engine}；新建任务时选它即可`);
      } catch (error) {
        toast(errorMessage(error), "error");
      } finally {
        setInstallBusy(null);
      }
    },
    [agents],
  );

  const pickStrategy = async (next: "auto" | "manual") => {
    setStrategy(next);
    window.localStorage.setItem("flux.solo.strategy", next);
    setStratOpen(false);
    if (!task || taskFinal) {
      // 没有任务（或任务已终态）时只记本机偏好，供下一个新建任务使用
      toast(`决策策略已切换为：${STRATEGY_LABELS[next]}（偏好保存在本机）`);
      return;
    }
    try {
      const updated = await api.setDecisionMode(task.id, next);
      setTasks((prev) => prev.map((item) => (item.id === updated.id ? updated : item)));
      toast(`决策策略已切换为：${STRATEGY_LABELS[next]}（已保存到任务）`);
    } catch (error) {
      setStrategy(task.decision_mode);
      toast(errorMessage(error), "error");
    }
  };

  /** 选择新任务的执行 Agent（只在"新任务"态可选；已建任务的执行者由建档时的 agent_id 决定） */
  const pickEngine = (agentId: string | null) => {
    setTaskAgentId(agentId);
    setEngineOpen(false);
    if (!agentId) {
      toast("新任务的执行者：内置 DSH（默认）");
      return;
    }
    const picked = agents.find((agent) => agent.id === agentId);
    const runtime = picked?.spec.runtime ?? "dsh";
    toast(`新任务的执行者：${picked?.spec.name ?? agentId}（${RUNTIME_LABELS[runtime] ?? runtime}）`);
  };

  /** 保存用户改后的需求确认（P0-06）：六维度必须都有内容，服务端会再校验一次 */
  const saveConfirmation = async () => {
    if (!task || !confirmDraft) return;
    const blank = confirmDraft.find((item) => !item.value.trim());
    if (blank) {
      toast(`「${blank.label}」还没有结论，请填写后再保存`);
      return;
    }
    setConfirmBusy(true);
    try {
      const outcome = await api.updateTaskConfirmation(task.id, confirmDraft);
      setTasks((prev) => prev.map((item) => (item.id === outcome.task.id ? outcome.task : item)));
      setMessages((prev) => [...prev, outcome.message]);
      toast("需求确认已保存：之后开始执行将以这一版为准");
    } catch (error) {
      toast(errorMessage(error), "error");
    } finally {
      setConfirmBusy(false);
    }
  };

  /** 开始执行（P0-05）：把确认卡交给 DSH 起一次 Run；DSH 未启用时后端会明确报错 */
  const startExecution = async () => {
    if (!task) return;
    if (confirmDraft) {
      const blank = confirmDraft.find((item) => !item.value.trim());
      if (blank) {
        toast(`「${blank.label}」还没有结论，请填写后再开始执行`);
        return;
      }
    }
    setStartBusy(true);
    try {
      const outcome = await api.startTask(task.id, confirmDraft ?? undefined);
      setTasks((prev) => prev.map((item) => (item.id === outcome.task.id ? outcome.task : item)));
      setMessages((prev) => [...prev, outcome.message]);
      toast(`已开始执行（Run ${outcome.run.run_id.slice(0, 8)}）`);
      await reloadMessages();
    } catch (error) {
      toast(errorMessage(error), "error");
    } finally {
      setStartBusy(false);
    }
  };

  /** 取消执行（UI-405）：任务已开始、还没进终态时可以把执行停掉 */
  const cancelExecution = async () => {
    if (!task) return;
    setCancelBusy(true);
    try {
      const updated = await api.cancelTask(task.id);
      setTasks((prev) => prev.map((item) => (item.id === updated.id ? updated : item)));
      toast("已取消执行，任务不会再继续");
      await reloadMessages();
    } catch (error) {
      toast(errorMessage(error), "error");
      await reloadMessages();
    } finally {
      setCancelBusy(false);
    }
  };

  /** 对挂起的决策点做选择（文档 §5 模式 B） */
  const resolveDecision = async (action: "choose" | "reject", option?: string) => {
    if (!task || !pendingDecision) return;
    setDecisionBusy(true);
    try {
      const outcome = await api.chooseDecision(task.id, {
        decision_id: pendingDecision.id,
        action,
        option,
        note: decisionNote.trim() || undefined,
      });
      setTasks((prev) => prev.map((item) => (item.id === outcome.task.id ? outcome.task : item)));
      setMessages((prev) => [...prev, outcome.message]);
      setDecisionNote("");
      toast(
        action === "choose"
          ? `已选择「${option}」，任务继续执行`
          : "已拒绝全部候选方案，等 Agent 重新给出方案",
      );
    } catch (error) {
      toast(errorMessage(error), "error");
    } finally {
      setDecisionBusy(false);
    }
  };

  const quoteMessage = (text: string) => {
    setQuote(text);
    inputRef.current?.focus();
    toast("已引用该条消息，发送后会带上引用内容");
  };

  const copyMessage = async (text: string) => {
    try {
      await navigator.clipboard.writeText(text);
      toast("已复制该条消息");
    } catch {
      toast("复制失败，请手动选择文本", "error");
    }
  };

  // --- 流程与进度（全部由真实数据推导） ---

  const assistantTurns = useMemo(
    () => messages.filter((message) => message.role === "assistant"),
    [messages],
  );
  const planSteps = useMemo(
    () => assistantTurns.flatMap((message) => message.payload?.steps ?? []),
    [assistantTurns],
  );
  const clarifyCount = useMemo(
    () => assistantTurns.reduce((sum, message) => sum + (message.payload?.clarify?.length ?? 0), 0),
    [assistantTurns],
  );
  const lastModel = useMemo(() => {
    for (let i = assistantTurns.length - 1; i >= 0; i -= 1) {
      const model = assistantTurns[i]?.payload?.model;
      if (model) return model;
    }
    return null;
  }, [assistantTurns]);

  const flow = useMemo<FlowStep[]>(() => {
    const hasTask = task !== null;
    const running =
      task?.status === "running" || task?.status === "waiting_for_user_decision";
    const waiting = task?.status === "waiting_for_user_decision";
    const cancelled = task?.status === "cancelled";
    const failed = task?.status === "failed";
    return [
      {
        name: "需求确认",
        state: assistantTurns.length > 0 ? "done" : hasTask ? "active" : "todo",
        note: assistantTurns.length > 0 ? "已完成" : hasTask ? "待澄清" : "待开始",
      },
      {
        name: "方案规划",
        state: planSteps.length > 0 ? "done" : assistantTurns.length > 0 ? "active" : "todo",
        note: planSteps.length > 0 ? "已完成" : "待开始",
      },
      {
        name: "任务分解",
        state: planSteps.length > 0 ? "done" : "todo",
        note: planSteps.length > 0 ? `${planSteps.length} 步` : "待开始",
      },
      {
        name: "执行中",
        state: changes.length > 0 ? "done" : running ? "active" : "todo",
        note: waiting
          ? "等待你的决策"
          : running
            ? "进行中"
            : changes.length > 0
              ? `已产出 ${changes.length} 项改动`
              : "待开始",
      },
      {
        name: "测试验证",
        state: appliedChanges.length > 0 ? "done" : pendingChanges.length > 0 ? "active" : "todo",
        note:
          appliedChanges.length > 0
            ? `${appliedChanges.length} 项已通过`
            : pendingChanges.length > 0
              ? "待落盘后运行"
              : "待开始",
      },
      {
        name: "自动落盘",
        state: pendingChanges.length > 0 ? "active" : changes.length > 0 ? "done" : "todo",
        note:
          pendingChanges.length > 0
            ? `${pendingChanges.length} 项落盘中`
            : changes.length > 0
              ? "已全部落盘"
              : "待开始",
      },
      {
        name: "完成",
        state: task?.status === "completed" ? "done" : "todo",
        note: cancelled ? "已取消" : failed ? "已失败" : task?.status === "completed" ? "已完成" : "待完成",
      },
    ];
  }, [task, assistantTurns.length, planSteps.length, changes.length, pendingChanges.length, appliedChanges.length]);

  const currentStep = Math.max(
    1,
    flow.findIndex((step) => step.state !== "done") + 1 || flow.length,
  );

  // --- 移动端派生与操作（数据源与桌面完全一致） ---

  /** 移动端四阶段（设计稿移动版）：把 7 步流程聚合为 需求澄清 / 执行计划 / 执行中 / 完成 */
  const mPhases = useMemo(() => {
    const group = (name: string, indexes: number[]) => {
      const steps = indexes
        .map((index) => flow[index])
        .filter((step): step is FlowStep => step !== undefined);
      const state: StepState = steps.every((step) => step.state === "done")
        ? "done"
        : steps.some((step) => step.state !== "todo")
          ? "active"
          : "todo";
      return { name, state, note: steps.map((step) => step.note).join(" · ") };
    };
    return [
      group("需求澄清", [0]),
      group("执行计划", [1, 2]),
      group("执行中", [3, 4, 5]),
      group("完成", [6]),
    ];
  }, [flow]);

  /** 进入某个任务详情（传 null 表示新建任务） */
  const openTaskScreen = (id: string | null) => {
    setTaskId(id);
    setQuote(null);
    setMScreen("task");
  };

  const startNewTask = () => {
    openTaskScreen(null);
    window.setTimeout(() => inputRef.current?.focus(), 120);
  };

  const jumpToReview = () => {
    const first = pendingChanges[0];
    if (first) setReviewId(first.id);
  };

  // --- 渲染 ---

  /** 单 Agent 模式：输入框上方这一轮改动的汇总条（文件数 / +行 −行 / 展开 / 全撤销 / 全保留） */
  const roundBar =
    roundChanges.length > 0 ? (
      <div className="agent-round">
        <div className="ar-row">
          <b>本轮改动</b>
          <span className="ar-num">{roundFiles} 个文件</span>
          <span className="ar-add">+{roundAddedLines}</span>
          <span className="ar-del">−{roundRemovedLines}</span>
          <span className="s-head-gap" />
          <button type="button" className="link" onClick={() => setRoundOpen((prev) => !prev)}>
            {roundOpen ? "收起" : "展开"}
          </button>
          <button
            type="button"
            className="btn btn-xs btn-ghost"
            disabled={roundBusy}
            onClick={() => void undoRound()}
            title="撤回这一轮的改动：没落盘的退回，已落盘的回滚"
          >
            撤销
          </button>
          <button
            type="button"
            className="btn btn-xs btn-primary"
            disabled={roundBusy}
            onClick={() => void keepRound()}
            title="把这一轮还没落盘的改动写入工作区并跑项目测试"
          >
            保留
          </button>
        </div>
        {roundOpen ? (
          <div className="ar-diff">
            {roundChanges.map((change) => (
              <div className="ar-file" key={change.id}>
                <div className="ar-file-head">
                  <b>{change.file_path}</b>
                  <span className="ar-add">+{change.added_lines}</span>
                  <span className="ar-del">−{change.removed_lines}</span>
                  <span className="ar-state">{ROUND_STATUS_LABELS[change.status] ?? change.status}</span>
                </div>
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
            ))}
          </div>
        ) : null}
      </div>
    ) : null;

  /** 输入框容器里的「默认需要审核 / 不需要审核」开关 */
  const roundToggle = (
    <div className="ar-toggle">
      <span>本轮改动</span>
      <button
        type="button"
        className={`ar-switch${agentAutoApply ? " is-auto" : ""}`}
        onClick={() => setAgentAutoApply((prev) => !prev)}
        title={agentAutoApply ? "改完直接落盘，不问你" : "改完先给你看，等你点「保留」才落盘"}
      >
        {agentAutoApply ? "不需要审核 · 自动落盘" : "默认需要审核"}
      </button>
    </div>
  );

  return (
    <div className="view view-solo">
      {isMobile ? (
        <header className="m-top">
          <svg viewBox="0 0 24 24" className="logo-mark">
            <path d="M12 2 22 12 12 22 2 12Z" fill="currentColor" />
          </svg>
          {M_SCREEN_TITLES[mScreen] ? <b className="m-top-title">{M_SCREEN_TITLES[mScreen]}</b> : null}
          <span className="m-top-gap" />
          <button type="button" className="icon-btn" title="搜索" onClick={openCommandPalette}>
            <svg viewBox="0 0 16 16" className="ic">
              <circle cx="7" cy="7" r="4.5" fill="none" stroke="currentColor" strokeWidth="1.4" />
              <path d="M10.5 10.5 14 14" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" />
            </svg>
          </button>
          {mScreen === "solo" ? (
            <button type="button" className="btn btn-primary btn-sm" onClick={startNewTask}>
              ＋ 新建任务
            </button>
          ) : mScreen === "agents" ? (
            <button
              type="button"
              className="btn btn-primary btn-sm"
              disabled={assembling}
              title="一键装配内置团队"
              onClick={() => void assembleTeam()}
            >
              ＋
            </button>
          ) : (
            <span className="avatar">孟</span>
          )}
        </header>
      ) : null}

      <header className={`s-top${isMobile ? " is-hidden" : ""}`}>
        <div className="s-top-left">
          <svg viewBox="0 0 24 24" className="logo-mark">
            <path d="M12 2 22 12 12 22 2 12Z" fill="currentColor" />
          </svg>
          <span className="s-product">Solo</span>
          <span className="s-divider" />
          <span className="s-slogan">一个人也能完成一个团队的工作</span>
        </div>
        <button type="button" className="s-search" onClick={openCommandPalette}>
          <svg viewBox="0 0 16 16" className="ic">
            <circle cx="7" cy="7" r="4.5" fill="none" stroke="currentColor" strokeWidth="1.4" />
            <path d="M10.5 10.5 14 14" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" />
          </svg>
          <span>搜索任务、项目、文件、Agent…</span>
          <kbd>⌘K</kbd>
        </button>
        <div className="s-top-right">
          {task?.status === "running" ? (
            <span className="pill pill-run">
              <i className="dot" />
              Agent 运行中
            </span>
          ) : task?.status === "waiting_for_user_decision" ? (
            <span className="pill pill-run">
              <i className="dot" />
              等待你的决策
            </span>
          ) : (
            <span className="pill">{task ? TASK_STATUS_LABELS[task.status] : "暂无任务"}</span>
          )}
          <button
            type="button"
            className="icon-btn"
            title="通知"
            onClick={() => toast(`当前有 ${pendingChanges.length} 项改动正在自动落盘`)}
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
            className="icon-btn"
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
          <span className="avatar">孟</span>
        </div>
      </header>

      {/* ===== 移动端屏（≤860px）：设计稿 mobile-v1 ===== */}
      {isMobile && mScreen === "hub" ? (
        <div className="m-body">
          <section className="m-hero card">
            <h2>
              一个人，也能
              <br />
              完成一个团队的工作
            </h2>
            <p>AI 驱动的软件工程生产力平台</p>
            <span className="m-hero-glow" />
          </section>
          <section className="card m-menu">
            <button type="button" className="m-menu-item is-active" onClick={() => setMScreen("solo")}>
              <span className="n-ic i-solo" />
              <span className="n-tx">
                <b>Solo</b>
                <i>AI 任务执行中心</i>
              </span>
              <span className="m-chev">›</span>
            </button>
            <button type="button" className="m-menu-item" onClick={onOpenWorkspace}>
              <span className="n-ic i-ws" />
              <span className="n-tx">
                <b>Workspace</b>
                <i>代码工作区 · IDE</i>
              </span>
              <span className="m-chev">›</span>
            </button>
            <button type="button" className="m-menu-item" onClick={() => setMScreen("projects")}>
              <span className="n-ic i-proj" />
              <span className="n-tx">
                <b>项目</b>
                <i>{projects.length > 0 ? `已登记 ${projects.length} 个` : "登记你的项目"}</i>
              </span>
              <span className="m-chev">›</span>
            </button>
            <button type="button" className="m-menu-item" onClick={() => setMScreen("agents")}>
              <span className="n-ic i-agent" />
              <span className="n-tx">
                <b>Agent</b>
                <i>{agents.length > 0 ? `${agents.length} 个已装配` : "AI 代理管理"}</i>
              </span>
              <span className="m-chev">›</span>
            </button>
            <div className="m-menu-item is-disabled" aria-disabled="true">
              <span className="n-ic i-mkt" />
              <span className="n-tx">
                <b>市场</b>
                <i>扩展能力与插件</i>
              </span>
              <span className="m-soon">即将开放</span>
            </div>
            <div className="m-menu-item is-disabled" aria-disabled="true">
              <span className="n-ic i-set" />
              <span className="n-tx">
                <b>设置</b>
                <i>模型与密钥在服务端 .env 配置</i>
              </span>
              <span className="m-soon">即将开放</span>
            </div>
          </section>
        </div>
      ) : null}

      {isMobile && mScreen === "solo" ? (
        <div className="m-body">
          <section className="card m-ask" role="button" tabIndex={0} onClick={startNewTask}>
            <b>你想完成什么任务?</b>
            <i>例如：实现用户邮箱修改功能、修复前后端布局问题、接着上次的进度继续开发…</i>
            <span className="m-ask-go">写下需求，Flux 先澄清再给执行计划 ›</span>
          </section>
          <div className="m-sec-head">
            <b>最近任务</b>
            <button type="button" className="link" onClick={() => setTaskListOpen(true)}>
              全部 ›
            </button>
          </div>
          <ul className="m-tasks">
            {tasks.length === 0 ? (
              <li className="m-empty">还没有任务：点上方「你想完成什么任务?」写下第一条需求。</li>
            ) : (
              tasks.map((item) => {
                const mark = M_STATUS_MARK[item.status];
                return (
                  <li
                    key={item.id}
                    className={item.id === taskId ? "is-active" : ""}
                    onClick={() => openTaskScreen(item.id)}
                  >
                    <span className={`m-tk-ic ${mark.cls}`}>{mark.mark}</span>
                    <div className="m-tk-main">
                      <b>{taskTitle(item.description)}</b>
                      <i>
                        {TASK_STATUS_LABELS[item.status]} · {clockOf(item.created_at)}
                      </i>
                      {item.status === "running" || item.status === "waiting_for_user_decision" ? (
                        <span className="m-tk-bar">
                          <i />
                        </span>
                      ) : null}
                    </div>
                    <span className="m-chev">›</span>
                  </li>
                );
              })
            )}
          </ul>
        </div>
      ) : null}

      {isMobile && mScreen === "projects" ? (
        <div className="m-body">
          <div className="m-sec-head">
            <b>项目（{projects.length}）</b>
            <button type="button" className="link" onClick={() => setProjectListOpen(true)}>
              选择当前项目 ›
            </button>
          </div>
          <ul className="m-tasks">
            {projects.length === 0 ? (
              <li className="m-empty">还没有登记项目：可在 IDE 工作台里登记。</li>
            ) : (
              projects.map((project) => (
                <li
                  key={project.id}
                  className={project.id === projectId ? "is-active" : ""}
                  onClick={() => {
                    setProjectId(project.id);
                    toast(`已选择项目「${project.name}」，下一个新建任务会挂到它下面`);
                  }}
                >
                  <span className="m-tk-ic is-ok">◇</span>
                  <div className="m-tk-main">
                    <b>{project.name}</b>
                    <i>
                      {project.repository ?? "未登记仓库地址"} · {project.id.slice(0, 8)}
                    </i>
                  </div>
                  {project.id === projectId ? (
                    <span className="m-soon is-now">当前</span>
                  ) : (
                    <span className="m-chev">›</span>
                  )}
                </li>
              ))
            )}
          </ul>
        </div>
      ) : null}

      {isMobile && mScreen === "agents" ? (
        <div className="m-body">
          <AgentRoster
            agents={agents}
            assembling={assembling}
            onAssemble={() => void assembleTeam()}
            installations={installations}
            installBusy={installBusy}
            onScanInstallations={() => void scanInstallations()}
            onConnectInstallation={(name) => void connectInstallation(name)}
            onRemoveInstallation={(name) => void removeInstallation(name)}
            onCreateAgentForRuntime={(runtime) => void createAgentForRuntime(runtime)}
          />
        </div>
      ) : null}

      {isMobile && mScreen === "me" ? (
        <div className="m-body">
          <section className="card m-me-head">
            <span className="avatar lg">孟</span>
            <div className="m-me-id">
              <b>孟令昕</b>
              <i>本机账号 · 本地实例</i>
            </div>
          </section>
          <section className="card m-me-card">
            <div className="m-sec-head">
              <b>当前决策策略</b>
            </div>
            <p>遇到需要拍板的问题时：{STRATEGY_LABELS[strategy]}（可在任务详情底部切换）。</p>
          </section>
          <section className="card m-me-card">
            <div className="m-sec-head">
              <b>本机数据</b>
            </div>
            <div className="m-me-grid">
              <div>
                <b>{projects.length}</b>
                <i>项目</i>
              </div>
              <div>
                <b>{tasks.length}</b>
                <i>任务</i>
              </div>
              <div>
                <b>{agents.length}</b>
                <i>Agent</i>
              </div>
              <div>
                <b>{pendingChanges.length}</b>
                <i>待审提案</i>
              </div>
            </div>
          </section>
          <section className="card m-me-card">
            <div className="m-sec-head">
              <b>运行配置</b>
            </div>
            <p>
              模型与密钥在服务端 .env 配置（FLUX_DEFAULT_PROVIDER / FLUX_DEEPSEEK_MODEL）；
              工作区根目录由 FLUX_WORKSPACE_ROOT 决定。
            </p>
          </section>
        </div>
      ) : null}

      <div
        className={`s-body${isMobile && mScreen !== "task" ? " is-hidden" : ""}`}
        style={sideWidth === null ? undefined : ({ "--side-w": `${sideWidth}px` } as CSSProperties)}
      >
        <nav className="s-nav">
          <div className="s-nav-head">
            <b>任务对话</b>
            <span>{tasks.length}</span>
          </div>
          <button
            type="button"
            className={`s-task-new${taskId === null ? " is-active" : ""}`}
            onClick={() => {
              setTaskId(null);
              inputRef.current?.focus();
            }}
          >
            <span className="n-ic i-solo" />
            新建任务
          </button>
          {tasks.length === 0 ? (
            <div className="s-task-empty">还没有任务：在下方写下第一条需求即创建</div>
          ) : (
            <ul className="s-tasks">
              {tasks.map((item) => (
                <li key={item.id}>
                  <button
                    type="button"
                    className={`s-task${item.id === taskId ? " is-active" : ""}`}
                    onClick={() => setTaskId(item.id)}
                    title={taskTitle(item.description)}
                  >
                    <span className="s-task-title">{taskTitle(item.description)}</span>
                    <span className="s-task-meta">
                      {TASK_STATUS_LABELS[item.status]} · {item.id.slice(0, 8)} · {clockOf(item.created_at)}
                    </span>
                  </button>
                </li>
              ))}
            </ul>
          )}
          <div className="s-nav-foot">
            <button type="button" className="s-nav-link" onClick={() => setProjectListOpen(true)}>
              项目 · {projects.length} 个
            </button>
            <button type="button" className="s-nav-link" onClick={onOpenWorkspace}>
              工作区 · 打开 IDE
            </button>
            <button type="button" className="s-nav-link" onClick={openTerminalWindow}>
              Agent Terminal
            </button>
            <button type="button" className="s-nav-link" onClick={() => setAgentOpen(true)}>
              Agent · {agents.length} 个已装配
            </button>
          </div>
        </nav>
        <main className="s-main">
          <div className="s-crumb">
            <button
              type="button"
              className="icon-btn sm"
              onClick={() => (isMobile ? setMScreen("solo") : setTaskListOpen(true))}
              title="返回任务列表"
            >
              ←
            </button>
            <span className="c-dim">Solo</span>
            <span className="c-sep">/</span>
            <span>{task ? "任务详情" : "新任务"}</span>
            {!isMobile ? (
              <div className="s-mode" role="tablist" aria-label="对话模式">
                <button
                  type="button"
                  role="tab"
                  aria-selected={soloMode === "pipeline"}
                  className={`s-mode-btn${soloMode === "pipeline" ? " is-active" : ""}`}
                  onClick={() => setSoloMode("pipeline")}
                  title="流水线模式：需求澄清 → 计划 → 执行 → 自动落盘，由平台编排"
                >
                  流水线
                </button>
                <button
                  type="button"
                  role="tab"
                  aria-selected={soloMode === "agent"}
                  className={`s-mode-btn${soloMode === "agent" ? " is-active" : ""}`}
                  onClick={() => setSoloMode("agent")}
                  title="单 Agent 模式：直接和某一个 Agent 一点一点改"
                >
                  单 Agent
                </button>
              </div>
            ) : null}
          </div>

          {!isMobile ? (
            <section className="s-head card">
              <h1>{task ? taskTitle(task.description) : "新建任务"}</h1>
              <span className="chip chip-dim">
                {projects.find((project) => project.id === (task?.project_id ?? projectId))?.name ??
                  "未选择项目"}
              </span>
              <span className="chip chip-dim">{task ? TASK_STATUS_LABELS[task.status] : "未创建"}</span>
              <span className="s-head-gap" />
              <span className="chip chip-dim">
                第 {currentStep} / {flow.length} 步 · {flow[currentStep - 1]?.name ?? "待开始"}
              </span>
              {task && taskStarted && !taskFinal ? (
                <button
                  type="button"
                  className="btn btn-ghost btn-sm"
                  disabled={cancelBusy}
                  onClick={() => void cancelExecution()}
                >
                  {cancelBusy ? "正在取消…" : "取消执行"}
                </button>
              ) : null}
            </section>
          ) : null}

          {!isMobile && soloMode === "agent" ? (
            <AgentChatPanel
              storageKey={`flux.solo.agent.${taskId ?? "none"}`}
              agentId={agentPickId}
              agents={agents}
              onAgentChange={setAgentPickId}
              dsh={dsh}
              emptyNote="这里是单 Agent 模式：每发一条指令就是一次真实的 DSH Run，Agent 在工作区里一点一点改。默认改完先给你看，点「保留」才落盘；也可以切成「不需要审核」直接落盘。"
              placeholder="描述这一步要改什么…（Enter 发送 / Shift+Enter 换行）"
              composeHint={task ? `当前任务 ${task.id.slice(0, 8)}` : "未绑定任务 · DSH 直接在全局工作区改码"}
              onRunStart={onAgentRunStart}
              onSettled={() => void onAgentSettled()}
              aboveCompose={roundBar}
              composeTop={roundToggle}
            />
          ) : (
            <>
          <div className="s-scroll">
            {isMobile ? (
              <section className="card m-phases">
                <ol className="m-phase-row">
                  {mPhases.map((phase, index) => (
                    <li
                      key={phase.name}
                      className={`m-phase is-${phase.state}`}
                      onClick={() => toast(`${phase.name}：${phase.note}`)}
                    >
                      <span className="m-phase-n">
                        {phase.state === "done" ? "✓" : index + 1}
                      </span>
                      <b>{phase.name}</b>
                    </li>
                  ))}
                </ol>
              </section>
            ) : null}

            {isMobile ? (
            <section className="task-head card">
              <div className="th-icon">
                <svg viewBox="0 0 24 24" className="ic-lg">
                  <rect x="4" y="4" width="16" height="16" rx="4" fill="none" stroke="currentColor" strokeWidth="1.6" />
                  <path d="M8.5 12h7M12 8.5v7" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" />
                </svg>
              </div>
              <div className="th-body">
                <h1>{task ? taskTitle(task.description) : "新建任务"}</h1>
                <p>
                  {task
                    ? task.description
                    : "在下方输入需求并发送：平台助手会先澄清需求，再给出执行计划；需求本身会作为任务描述落库。"}
                </p>
                <div className="th-tags">
                  <span className="chip chip-dim">
                    <svg viewBox="0 0 16 16" className="ic-xs">
                      <circle cx="8" cy="8" r="5.5" fill="none" stroke="currentColor" strokeWidth="1.3" />
                      <path
                        d="M2 8h12M8 2.5c3 3.6 3 7.4 0 11-3-3.6-3-7.4 0-11Z"
                        fill="none"
                        stroke="currentColor"
                        strokeWidth="1.1"
                      />
                    </svg>
                    {projects.find((project) => project.id === (task?.project_id ?? projectId))?.name ??
                      "未选择项目"}
                  </span>
                  <span className="chip chip-dim">
                    {task ? TASK_STATUS_LABELS[task.status] : "未创建"}
                  </span>
                  <span className="chip chip-dim">消息 {messages.length} 条</span>
                  <span className="chip chip-dim">
                    {task ? `创建于 ${clockOf(task.created_at)}` : "发送后创建"}
                  </span>
                </div>
              </div>
              <div className="th-progress">
                <div className="th-step">
                  <b>
                    {currentStep} / {flow.length}
                  </b>
                  <span>
                    第 {currentStep} 步 · {flow[currentStep - 1]?.name ?? "待开始"}
                  </span>
                </div>
                {task && taskStarted && !taskFinal ? (
                  <button
                    type="button"
                    className="btn btn-ghost btn-sm"
                    disabled={cancelBusy}
                    onClick={() => void cancelExecution()}
                  >
                    {cancelBusy ? "正在取消…" : "取消执行"}
                  </button>
                ) : null}
                <button
                  type="button"
                  className="btn btn-ghost btn-sm"
                  onClick={() =>
                    toast(
                      task
                        ? `任务 ${task.id.slice(0, 8)} · ${TASK_STATUS_LABELS[task.status]} · ${messages.length} 条消息 · ${changes.length} 项改动`
                        : "还没有任务：发送第一条需求即可创建",
                    )
                  }
                >
                  查看详情
                </button>
              </div>
            </section>
            ) : null}

            <section className="flow card">
              <ol className="flow-steps">
                {flow.map((step) => (
                  <li
                    key={step.name}
                    className={`fs${step.state === "done" ? " is-done" : step.state === "active" ? " is-active" : ""}`}
                    onClick={() => toast(`${step.name}：${step.note}`)}
                  >
                    <span className="fs-ic">
                      {step.state === "done" ? "✓" : step.state === "active" ? "↻" : "○"}
                    </span>
                    <b>{step.name}</b>
                    <i>{step.note}</i>
                  </li>
                ))}
              </ol>
            </section>

            {task && confirmDraft ? (
              <section className="card cf-card">
                <div className="block-head">
                  <span className={`bh-ic${taskStarted ? " ok" : ""}`}>{taskStarted ? "✓" : "☰"}</span>
                  <div>
                    <b>需求确认 · 六维度</b>
                    <i>
                      {taskStarted
                        ? "任务已开始执行，这一版确认内容是执行依据，不能再改"
                        : "确认无误后点「开始执行」，Agent 将以这一版需求为准"}
                    </i>
                  </div>
                  <span className="chip chip-dim">
                    {task.confirmation?.actor === "user" ? "由你确认" : "AI 初拟"}
                  </span>
                </div>
                <div className="cf-grid">
                  {confirmDraft.map((item, index) => (
                    <label className="cf-row" key={item.label}>
                      <span className="cf-label">{item.label}</span>
                      <textarea
                        className="cf-input"
                        rows={2}
                        value={item.value}
                        disabled={taskStarted || taskFinal || confirmBusy}
                        onChange={(event) => {
                          const value = event.target.value;
                          setConfirmDraft((prev) =>
                            prev
                              ? prev.map((entry, i) => (i === index ? { ...entry, value } : entry))
                              : prev,
                          );
                        }}
                      />
                    </label>
                  ))}
                </div>
                <div className="cf-foot">
                  <span className="cf-hint">
                    {taskFinal
                      ? "任务已结束，不会再执行；如需重跑请点左上角「返回任务列表」新建任务"
                      : taskStarted
                        ? `已开始执行 · Run ${task.run_id?.slice(0, 8)}`
                        : "六个维度都可以改：改完保存，或直接开始执行"}
                  </span>
                  <button
                    type="button"
                    className="btn btn-ghost"
                    disabled={taskStarted || taskFinal || confirmBusy}
                    onClick={() => void saveConfirmation()}
                  >
                    {confirmBusy ? "保存中…" : "保存修改"}
                  </button>
                  <button
                    type="button"
                    className="btn btn-primary"
                    disabled={taskStarted || taskFinal || startBusy}
                    onClick={() => void startExecution()}
                  >
                    {startBusy ? "正在开始…" : taskFinal ? "任务已结束" : taskStarted ? "已开始执行" : "开始执行"}
                  </button>
                </div>
              </section>
            ) : null}

            {pendingDecision ? (
              <section className="card decision-card">
                <div className="block-head">
                  <span className="bh-ic warn">?</span>
                  <div>
                    <b>需要你决定：{pendingDecision.question}</b>
                    <i>
                      {pendingDecision.context ||
                        "Agent 执行中遇到需要你拍板的选择，任务已暂停等你回答"}
                      {pendingDecision.recommendation
                        ? ` · AI 建议：${pendingDecision.recommendation}`
                        : ""}
                    </i>
                  </div>
                </div>
                <ul className="pd-opts">
                  {pendingDecision.options.map((option) => (
                    <li key={option.label}>
                      <button
                        type="button"
                        className={`pd-opt${option.recommended ? " is-rec" : ""}`}
                        disabled={decisionBusy}
                        onClick={() => void resolveDecision("choose", option.label)}
                      >
                        <div className="pd-main">
                          <div className="pd-row">
                            <b>{option.label}</b>
                            {option.recommended ? <span className="chip chip-rec">推荐</span> : null}
                          </div>
                          {option.description ? <i>{option.description}</i> : null}
                          {option.impact ? <em>影响：{option.impact}</em> : null}
                        </div>
                        <span className="pd-go">选它 ›</span>
                      </button>
                    </li>
                  ))}
                </ul>
                <div className="pd-foot">
                  <input
                    value={decisionNote}
                    placeholder="补充说明（可选；拒绝时写明原因，Agent 会据此重新给方案）"
                    onChange={(event) => setDecisionNote(event.target.value)}
                  />
                  <button
                    type="button"
                    className="btn btn-ghost"
                    disabled={decisionBusy}
                    onClick={() => void resolveDecision("reject")}
                  >
                    都不合适，请重新给方案
                  </button>
                </div>
              </section>
            ) : null}

            <section className="card block chat-block">
              <div className="block-head">
                <span
                  className={`bh-ic${pendingChanges.length === 0 && messages.length > 0 ? " ok" : ""}`}
                >
                  {pendingChanges.length === 0 && messages.length > 0 ? "✓" : "…"}
                </span>
                <div>
                  <b>{pendingChanges.length > 0 ? "改动正在自动落盘" : "需求澄清与确认"}</b>
                  <i>
                    {pendingChanges.length > 0
                      ? `${pendingChanges.length} 项改动正在自动落盘并运行项目测试`
                      : messages.length > 0
                        ? `已落库 ${messages.length} 条消息${clarifyCount > 0 ? ` · 澄清结论 ${clarifyCount} 条` : ""}`
                        : "发送第一条需求，Flux 会先澄清再给计划"}
                  </i>
                </div>
                <button
                  type="button"
                  className="icon-btn sm more"
                  onClick={() => toast(`任务消息共 ${messages.length} 条，全部来自后端落库记录`)}
                >
                  …
                </button>
              </div>
              <SoloChat
                taskId={taskId}
                messages={messages}
                hasMore={hasMore}
                loading={msgLoading}
                loadingOlder={loadingOlder}
                error={msgError}
                sending={sending}
                sendingHint={
                  lastModel
                    ? `模型 ${lastModel.provider} · ${lastModel.model} 正在处理`
                    : "正在调用平台助手"
                }
                onLoadOlder={() => void loadOlder()}
                onCopy={(text) => void copyMessage(text)}
                onQuote={quoteMessage}
              />
            </section>
          </div>

          {isMobile && pendingChanges.length > 0 ? (
            <button type="button" className="m-review-bar" onClick={jumpToReview}>
              <span className="warn-tri">⚠</span>
              <span>有 {pendingChanges.length} 项改动正在自动落盘</span>
              <span className="m-chev">›</span>
            </button>
          ) : null}

          <div className="s-compose">
            {quote ? (
              <div className="compose-quote">
                <span className="cq-ic">引用</span>
                <span className="cq-text">{quote}</span>
                <button type="button" className="cq-x" title="取消引用" onClick={() => setQuote(null)}>
                  ×
                </button>
              </div>
            ) : null}
            <div className="strat-wrap" ref={stratRef}>
              <button
                type="button"
                className={`strat-pill${stratOpen ? " is-open" : ""}`}
                aria-haspopup="true"
                aria-expanded={stratOpen}
                onClick={() => {
                  const next = !stratOpen;
                  setStratOpen(next);
                  if (next && stratRef.current) {
                    // 弹层关闭时是 display:none，量它只能得到全 0 矩形（旧实现因此恒判"向下弹"，
                    // 手机端选项被推出视口）。改量始终可见的锚点，并按弹层 CSS 高度上限估算上方空间：
                    // 上方放不下 min(320px, 46vh) 时才翻到下方。
                    const anchorTop = stratRef.current.getBoundingClientRect().top;
                    const popMaxH = Math.min(320, window.innerHeight * 0.46);
                    setStratDown(anchorTop < popMaxH + 16);
                  }
                }}
              >
                <span>决策策略 · {STRATEGY_LABELS[strategy]}</span>
                <i className="sp-caret">⌄</i>
              </button>
              <div
                className={`strategy-pop${stratOpen ? "" : " is-hidden"}${stratDown ? " is-down" : ""}`}
                ref={popRef}
                role="menu"
              >
                <div className="sp-head">执行决策策略（偏好保存在本机）</div>
                <div className="strategy">
                  <button
                    type="button"
                    className={`st-opt${strategy === "auto" ? " is-active" : ""}`}
                    onClick={() => void pickStrategy("auto")}
                  >
                    <span className="st-ic">◉</span>
                    <div>
                      <b>使用 AI 默认方案</b>
                      <i>遇到一般性问题时，AI 基于当前上下文自行判断并继续执行，不打扰你。</i>
                    </div>
                    <span className="st-badge">当前选择</span>
                  </button>
                  <button
                    type="button"
                    className={`st-opt${strategy === "manual" ? " is-active" : ""}`}
                    onClick={() => void pickStrategy("manual")}
                  >
                    <span className="st-ic">◯</span>
                    <div>
                      <b>由我决定</b>
                      <i>遇到关键问题时，AI 会暂停任务并把问题和建议方案发给你，由你决定下一步。</i>
                    </div>
                    <span className="st-badge">当前选择</span>
                  </button>
                </div>
              </div>
            </div>
            {!task ? (
              <div className="strat-wrap" ref={engineRef}>
                <button
                  type="button"
                  className={`strat-pill${engineOpen ? " is-open" : ""}`}
                  aria-haspopup="true"
                  aria-expanded={engineOpen}
                  onClick={() => {
                    const next = !engineOpen;
                    setEngineOpen(next);
                    if (next && engineRef.current) {
                      const anchorTop = engineRef.current.getBoundingClientRect().top;
                      const popMaxH = Math.min(320, window.innerHeight * 0.46);
                      setEngineDown(anchorTop < popMaxH + 16);
                    }
                  }}
                >
                  <span>执行 Agent · {engineLabel}</span>
                  <i className="sp-caret">⌄</i>
                </button>
                <div
                  className={`strategy-pop${engineOpen ? "" : " is-hidden"}${engineDown ? " is-down" : ""}`}
                  ref={enginePopRef}
                  role="menu"
                >
                  <div className="sp-head">这个新任务由谁执行（建档时定，之后不可改）</div>
                  <div className="strategy">
                    <button
                      type="button"
                      className={`st-opt${taskAgentId === null ? " is-active" : ""}`}
                      onClick={() => pickEngine(null)}
                    >
                      <span className="st-ic">◉</span>
                      <div>
                        <b>内置 DSH（默认）</b>
                        <i>Flux 自带的执行引擎，不需要接入任何 CLI。</i>
                      </div>
                      <span className="st-badge">当前选择</span>
                    </button>
                    {agents.map((agent) => {
                      const runtime = agent.spec.runtime ?? "dsh";
                      const ready = runtimeReady(runtime);
                      const active = taskAgentId === agent.id;
                      return (
                        <button
                          key={agent.id}
                          type="button"
                          className={`st-opt${active ? " is-active" : ""}`}
                          disabled={!ready}
                          title={ready ? undefined : `${RUNTIME_LABELS[runtime] ?? runtime} 尚未接入：先在「Agent · 我的」里接入后再选`}
                          onClick={() => pickEngine(agent.id)}
                        >
                          <span className="st-ic">{active ? "◉" : "◯"}</span>
                          <div>
                            <b>{agent.spec.name}</b>
                            <i>
                              {RUNTIME_LABELS[runtime] ?? runtime} ·{" "}
                              {ROLE_LABELS[agent.spec.role] ?? agent.spec.role}
                              {ready ? "" : " · 引擎未接入"}
                            </i>
                          </div>
                          <span className="st-badge">当前选择</span>
                        </button>
                      );
                    })}
                  </div>
                  {agents.length === 0 ? (
                    <p className="ext-agent-note">
                      还没有 Agent 档案：到「Agent · 我的」为已接入的 CLI 建一个，或一键装配内置团队。
                    </p>
                  ) : null}
                </div>
              </div>
            ) : null}
            <input
              ref={inputRef}
              value={draft}
              placeholder="有新的需求或补充说明吗？可以继续告诉我…"
              onChange={(event) => setDraft(event.target.value)}
              onKeyDown={(event) => {
                if (event.key === "Enter") void send();
              }}
            />
            <div className="compose-actions">
              <button
                type="button"
                className="icon-btn sm"
                title="附件"
                onClick={() => toast("附件上传尚未接入：当前任务以文字需求与代码上下文为准")}
              >
                📎
              </button>
              <button
                type="button"
                className="icon-btn sm"
                title="图片"
                onClick={() => toast("图片上传尚未接入：当前任务以文字需求与代码上下文为准")}
              >
                🖼
              </button>
              <button
                type="button"
                className="btn btn-primary btn-send"
                disabled={sending}
                onClick={() => void send()}
              >
                ↑
              </button>
            </div>
          </div>
            </>
          )}
        </main>

        <div
          className="side-resize"
          title="拖动调整右栏宽度"
          onMouseDown={onSideResizeDown}
        />

        <aside className="s-side">
          <SoloSidePanel
            installations={installations}
            installBusy={installBusy}
            onScan={() => void scanInstallations()}
            onConnect={(name) => void connectInstallation(name)}
            onRemove={(name) => void removeInstallation(name)}
            onCreateAgent={(runtime) => void createAgentForRuntime(runtime)}
          />
        </aside>
      </div>

      {/* 移动端底部 Tab（任务详情屏不显示，与设计稿一致） */}
      {isMobile && mScreen !== "task" ? (
        <nav className="m-tabbar" aria-label="主导航">
          {M_TABS.map((tab) => {
            const active = tab.key === (mScreen === "solo" ? "hub" : mScreen);
            return (
              <button
                key={tab.key}
                type="button"
                className={`m-tab${active ? " is-active" : ""}`}
                onClick={() => setMScreen(tab.key)}
              >
                {tab.icon}
                <span>{tab.label}</span>
              </button>
            );
          })}
        </nav>
      ) : null}

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
                onClick={() => void runReview(false)}
              >
                拒绝
              </button>
              <button
                type="button"
                className="btn btn-primary"
                disabled={reviewBusy}
                onClick={() => void runReview(true)}
              >
                {reviewBusy ? "处理中…" : "批准并落盘"}
              </button>
            </div>
          </div>
        </div>
      ) : null}

      {agentOpen ? (
        <div
          className="review-mask"
          onClick={(event) => {
            if (event.target === event.currentTarget) setAgentOpen(false);
          }}
        >
          <div className="review-dialog">
            <div className="rv-head">
              <b>Agent 管理</b>
              <button type="button" className="icon-btn sm" onClick={() => setAgentOpen(false)}>
                ×
              </button>
            </div>
            <div className="rv-sub">
              内置角色档案与已装配 Agent；运行时状态来自 GET /agents，装配走 POST /agents
            </div>
            <div className="dialog-body">
              <AgentRoster
                agents={agents}
                assembling={assembling}
                onAssemble={() => void assembleTeam()}
                installations={installations}
                installBusy={installBusy}
                onScanInstallations={() => void scanInstallations()}
                onConnectInstallation={(name) => void connectInstallation(name)}
                onRemoveInstallation={(name) => void removeInstallation(name)}
                onCreateAgentForRuntime={(runtime) => void createAgentForRuntime(runtime)}
              />
            </div>
          </div>
        </div>
      ) : null}

      {taskListOpen ? (
        <div
          className="review-mask"
          onClick={(event) => {
            if (event.target === event.currentTarget) setTaskListOpen(false);
          }}
        >
          <div className="review-dialog">
            <div className="rv-head">
              <b>任务列表</b>
              <button type="button" className="icon-btn sm" onClick={() => setTaskListOpen(false)}>
                ×
              </button>
            </div>
            <div className="rv-sub">
              共 {tasks.length} 个任务（按创建时间倒序）· 选中即可查看它的完整对话与改动
            </div>
            <div className="dialog-body">
              {tasks.length === 0 ? (
                <div className="empty-note wide">还没有任务：关闭本窗口后在下方输入需求即可创建。</div>
              ) : (
                <ul className="tasks">
                  {tasks.map((item) => (
                    <li
                      key={item.id}
                      className={item.id === taskId ? "is-active" : ""}
                      onClick={() => {
                        setTaskId(item.id);
                        setTaskListOpen(false);
                      }}
                    >
                      <div className="tk-main">
                        <b>{taskTitle(item.description)}</b>
                        <i>
                          {TASK_STATUS_LABELS[item.status]} · {item.id.slice(0, 8)}
                        </i>
                      </div>
                      <span className="tk-time">{clockOf(item.created_at)}</span>
                    </li>
                  ))}
                </ul>
              )}
            </div>
            <div className="rv-foot">
              <button
                type="button"
                className="btn btn-ghost"
                onClick={() => {
                  setTaskId(null);
                  setTaskListOpen(false);
                  inputRef.current?.focus();
                }}
              >
                新建任务
              </button>
            </div>
          </div>
        </div>
      ) : null}

      {projectListOpen ? (
        <div
          className="review-mask"
          onClick={(event) => {
            if (event.target === event.currentTarget) setProjectListOpen(false);
          }}
        >
          <div className="review-dialog">
            <div className="rv-head">
              <b>项目</b>
              <button type="button" className="icon-btn sm" onClick={() => setProjectListOpen(false)}>
                ×
              </button>
            </div>
            <div className="rv-sub">
              工作区根目录由服务端 FLUX_WORKSPACE_ROOT 决定；新建任务会挂到你选中的项目下
            </div>
            <div className="dialog-body">
              {projects.length === 0 ? (
                <div className="empty-note wide">还没有登记项目：可在 IDE 工作台里登记。</div>
              ) : (
                <ul className="tasks">
                  {projects.map((project) => (
                    <li
                      key={project.id}
                      className={project.id === projectId ? "is-active" : ""}
                      onClick={() => {
                        setProjectId(project.id);
                        setProjectListOpen(false);
                        toast(`已选择项目「${project.name}」，下一个新建任务会挂到它下面`);
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
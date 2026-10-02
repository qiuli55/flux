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

import { api } from "../api/client";
import type {
  AgentHandle,
  Change,
  ConfirmationItem,
  Project,
  Task,
  TaskMessage,
  TaskStatus,
} from "../api/types";
import { openCommandPalette } from "../app/commands";
import { parseUnifiedDiff } from "../app/diff";
import { toast } from "../app/toast";
import { MOBILE_QUERY, useMediaQuery } from "../app/useMediaQuery";
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

/** 引用前缀：形如「引用：「原文」\n\n正文」，落库后刷新仍在 */
const QUOTE_PREFIX = "引用：";

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

/** Agent 管理内容（桌面弹层与移动端「Agent」屏共用同一组件与真实数据） */
function AgentRoster({
  agents,
  assembling,
  onAssemble,
}: {
  agents: AgentHandle[];
  assembling: boolean;
  onAssemble: () => void;
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
                  {ROLE_LABELS[agent.spec.role] ?? agent.spec.role} · {agent.spec.model_name} · 已执行{" "}
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

  const [reviewId, setReviewId] = useState<string | null>(null);
  const [reviewBusy, setReviewBusy] = useState(false);
  const [taskListOpen, setTaskListOpen] = useState(false);
  const [projectListOpen, setProjectListOpen] = useState(false);

  // 需求确认（P0-06）：draft 是用户在面板里改的版本，保存/执行都以它为准
  const [confirmDraft, setConfirmDraft] = useState<ConfirmationItem[] | null>(null);
  const [confirmBusy, setConfirmBusy] = useState(false);
  const [startBusy, setStartBusy] = useState(false);
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
    void loadChanges();
    void loadTasks();
  }, [loadProjects, loadAgents, loadChanges, loadTasks]);

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

  // 点弹层外部关闭决策策略
  useEffect(() => {
    if (!stratOpen) return;
    const onDown = (event: MouseEvent) => {
      const target = event.target as Node;
      if (popRef.current?.contains(target) || stratRef.current?.contains(target)) return;
      setStratOpen(false);
    };
    document.addEventListener("mousedown", onDown);
    return () => document.removeEventListener("mousedown", onDown);
  }, [stratOpen]);

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
  }, [draft, quote, sending, taskId, projectId, strategy]);

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
        name: "人工审核",
        state: pendingChanges.length > 0 ? "active" : changes.length > 0 ? "done" : "todo",
        note:
          pendingChanges.length > 0
            ? `${pendingChanges.length} 项待审`
            : changes.length > 0
              ? "已全部处理"
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
    document
      .getElementById("soloReviewCard")
      ?.scrollIntoView({ behavior: "smooth", block: "start" });
  };

  // --- 渲染 ---

  return (
    <div className="view view-solo">
      {isMobile ? (
        <header className="m-top">
          <svg viewBox="0 0 24 24" className="logo-mark">
            <path d="M12 2 22 12 12 22 2 12Z" fill="url(#lg-m)" />
            <defs>
              <linearGradient id="lg-m" x1="0" x2="1" y1="0" y2="1">
                <stop offset="0" stopColor="#8b6cff" />
                <stop offset="1" stopColor="#5b3df5" />
              </linearGradient>
            </defs>
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
            <path d="M12 2 22 12 12 22 2 12Z" fill="url(#lg-solo)" />
            <defs>
              <linearGradient id="lg-solo" x1="0" x2="1" y1="0" y2="1">
                <stop offset="0" stopColor="#8b6cff" />
                <stop offset="1" stopColor="#5b3df5" />
              </linearGradient>
            </defs>
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
          <AgentRoster agents={agents} assembling={assembling} onAssemble={() => void assembleTeam()} />
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

      <div className={`s-body${isMobile && mScreen !== "task" ? " is-hidden" : ""}`}>
        <nav className="s-nav">
          <a className="s-nav-item is-active">
            <span className="n-ic i-solo" />
            <span className="n-tx">
              <b>Solo</b>
              <i>AI 任务执行中心</i>
            </span>
          </a>
          <a
            className="s-nav-item"
            onClick={() => setProjectListOpen(true)}
            onKeyDown={() => undefined}
            role="button"
            tabIndex={0}
          >
            <span className="n-ic i-proj" />
            <span className="n-tx">
              <b>项目</b>
              <i>{projects.length > 0 ? `已登记 ${projects.length} 个` : "Project"}</i>
            </span>
          </a>
          <a
            className="s-nav-item"
            onClick={onOpenWorkspace}
            onKeyDown={() => undefined}
            role="button"
            tabIndex={0}
          >
            <span className="n-ic i-ws" />
            <span className="n-tx">
              <b>工作区</b>
              <i>Workspace · IDE</i>
            </span>
          </a>
          <a
            className="s-nav-item"
            onClick={() => setAgentOpen(true)}
            onKeyDown={() => undefined}
            role="button"
            tabIndex={0}
          >
            <span className="n-ic i-agent" />
            <span className="n-tx">
              <b>Agent</b>
              <i>{agents.length > 0 ? `${agents.length} 个已装配` : "AI 代理管理"}</i>
            </span>
          </a>
          <div className="s-nav-item is-disabled" aria-disabled="true" title="即将开放">
            <span className="n-ic i-mkt" />
            <span className="n-tx">
              <b>市场</b>
              <i>扩展能力与插件</i>
            </span>
            <span className="m-soon">即将开放</span>
          </div>
          <div className="s-nav-item is-disabled" aria-disabled="true" title="即将开放">
            <span className="n-ic i-set" />
            <span className="n-tx">
              <b>设置</b>
              <i>模型与密钥在服务端 .env 配置</i>
            </span>
            <span className="m-soon">即将开放</span>
          </div>
          <div className="s-nav-card">
            <div className="c-title">让 AI 帮你完成</div>
            <div className="c-sub">从需求到上线的全流程</div>
            <button
              type="button"
              className="btn btn-primary btn-sm"
              onClick={() => toast("Solo：说出需求，平台助手先澄清需求、再给出执行计划")}
            >
              了解 Solo
            </button>
            <div className="c-glow" />
          </div>
        </nav>

        <main className="s-main">
          <div className="s-scroll">
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
            </div>

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
                    {taskStarted
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
                    {startBusy ? "正在开始…" : taskStarted ? "已开始执行" : "开始执行"}
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
                  <b>{pendingChanges.length > 0 ? "有待审核的改动" : "需求澄清与确认"}</b>
                  <i>
                    {pendingChanges.length > 0
                      ? `${pendingChanges.length} 项改动等你批准，批准后才会写入工作区`
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
              <span>有 {pendingChanges.length} 个需要你审核的点</span>
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
                  if (next && popRef.current) {
                    setStratDown(popRef.current.getBoundingClientRect().top < 100);
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
        </main>

        <aside className="s-side">
          <section className="card side-card">
            <div className="side-head">
              <b>执行进度</b>
              <button
                type="button"
                className="link"
                onClick={() => toast(`任务消息 ${messages.length} 条 · 改动 ${changes.length} 项`)}
              >
                查看完整日志 ›
              </button>
            </div>
            <ol className="tl">
              <li className={`tl-item${assistantTurns.length > 0 ? " is-done" : task ? " is-active" : ""}`}>
                <span className="tl-dot">{assistantTurns.length > 0 ? "✓" : "○"}</span>
                <div className="tl-main">
                  <div className="tl-row">
                    <b>需求分析</b>
                    <span className={`tl-badge${assistantTurns.length > 0 ? " b-ok" : ""}`}>
                      {assistantTurns.length > 0 ? "已完成" : task ? "进行中" : "待开始"}
                    </span>
                  </div>
                  <i>
                    {assistantTurns.length > 0
                      ? `已给出 ${assistantTurns.length} 轮回复${clarifyCount > 0 ? ` · 澄清结论 ${clarifyCount} 条` : ""}`
                      : "等待首条需求"}
                  </i>
                </div>
                <span className="tl-time">
                  {assistantTurns[0] ? clockOf(assistantTurns[0].created_at) : "--"}
                </span>
              </li>
              <li className={`tl-item${planSteps.length > 0 ? " is-done" : ""}`}>
                <span className="tl-dot">{planSteps.length > 0 ? "✓" : "○"}</span>
                <div className="tl-main">
                  <div className="tl-row">
                    <b>任务拆解</b>
                    <span className={`tl-badge${planSteps.length > 0 ? " b-ok" : ""}`}>
                      {planSteps.length > 0 ? "已完成" : "待开始"}
                    </span>
                  </div>
                  <i>{planSteps.length > 0 ? `拆分为 ${planSteps.length} 步执行计划` : "等待模型给出执行计划"}</i>
                </div>
                <span className="tl-time">--</span>
              </li>
              <li
                className={`tl-item${pendingChanges.length > 0 ? " is-active" : changes.length > 0 ? " is-done" : ""}`}
              >
                <span className="tl-dot">{changes.length > 0 ? "✓" : "○"}</span>
                <div className="tl-main">
                  <div className="tl-row">
                    <b>Agent 执行</b>
                    <span
                      className={`tl-badge${pendingChanges.length > 0 ? " b-run" : changes.length > 0 ? " b-ok" : ""}`}
                    >
                      {pendingChanges.length > 0 ? "进行中" : changes.length > 0 ? "已完成" : "待开始"}
                    </span>
                  </div>
                  <i>
                    {changes.length > 0
                      ? `已产出 ${changes.length} 项文件改动${pendingChanges.length > 0 ? `（${pendingChanges.length} 项待审）` : ""}`
                      : "等待 Agent 产出改动提案"}
                  </i>
                </div>
                <span className="tl-time">--</span>
              </li>
              <li className={`tl-item${appliedChanges.length > 0 ? " is-done" : ""}`}>
                <span className="tl-dot">{appliedChanges.length > 0 ? "✓" : "○"}</span>
                <div className="tl-main">
                  <div className="tl-row">
                    <b>测试验证</b>
                    <span className={`tl-badge${appliedChanges.length > 0 ? " b-ok" : ""}`}>
                      {appliedChanges.length > 0 ? "已完成" : "待开始"}
                    </span>
                  </div>
                  <i>
                    {appliedChanges.length > 0
                      ? `${appliedChanges.length} 项落盘并跑过项目测试命令`
                      : "落盘时自动运行项目测试命令"}
                  </i>
                </div>
                <span className="tl-time">--</span>
              </li>
              <li
                className={`tl-item${pendingChanges.length > 0 ? " is-active" : changes.length > 0 ? " is-done" : ""}`}
              >
                <span className="tl-dot">{changes.length > 0 && pendingChanges.length === 0 ? "✓" : "○"}</span>
                <div className="tl-main">
                  <div className="tl-row">
                    <b>人工审核</b>
                    <span
                      className={`tl-badge${pendingChanges.length > 0 ? " b-run" : changes.length > 0 ? " b-ok" : ""}`}
                    >
                      {pendingChanges.length > 0 ? "进行中" : changes.length > 0 ? "已完成" : "待开始"}
                    </span>
                  </div>
                  <i>{pendingChanges.length > 0 ? `${pendingChanges.length} 项改动等你批准` : "所有改动都需人工批准后落盘"}</i>
                </div>
                <span className="tl-time">--</span>
              </li>
              <li className={`tl-item${task?.status === "completed" ? " is-done" : ""}`}>
                <span className="tl-dot">{task?.status === "completed" ? "✓" : "◔"}</span>
                <div className="tl-main">
                  <div className="tl-row">
                    <b>任务完成</b>
                    <span className={`tl-badge${task?.status === "completed" ? " b-ok" : ""}`}>
                      {task ? TASK_STATUS_LABELS[task.status] : "待开始"}
                    </span>
                  </div>
                </div>
                <span className="tl-time">--</span>
              </li>
            </ol>
          </section>

          <section className="card side-card">
            <div className="side-head">
              <b>Agent 团队</b>
              <button
                type="button"
                className="link"
                onClick={() => toast(`已装配 ${agents.length} 个 Agent（数据来自 GET /agents）`)}
              >
                查看全部 ›
              </button>
            </div>
            {agents.length > 0 ? (
              <ul className="team">
                {agents.slice(0, 4).map((agent, index) => (
                  <li key={agent.id}>
                    <span className={`tm-ava a${(index % 4) + 1}`}>
                      {agent.spec.name.slice(0, 2).toUpperCase()}
                    </span>
                    <div className="tm-main">
                      <div className="tm-row">
                        <b>{agent.spec.name}</b>
                        <span
                          className={`tl-badge${agent.state === "RUNNING" ? " b-run" : agent.state === "FAILED" ? "" : " b-ok"}`}
                        >
                          {STATE_LABELS[agent.state] ?? agent.state}
                        </span>
                      </div>
                      <i>
                        {ROLE_LABELS[agent.spec.role] ?? agent.spec.role} · {agent.spec.model_name}
                      </i>
                    </div>
                    <span className="tm-time">{agent.execution_count} 次</span>
                  </li>
                ))}
              </ul>
            ) : (
              <>
                <div className="empty-note">
                  还没有装配 Agent。内置角色只有档案与权限边界，实际执行由你在 IDE 接入的 Agent 完成。
                </div>
                <button
                  type="button"
                  className="btn btn-ghost btn-block"
                  style={{ marginTop: 10 }}
                  disabled={assembling}
                  onClick={() => void assembleTeam()}
                >
                  {assembling ? "正在装配…" : "一键装配内置团队"}
                </button>
              </>
            )}
          </section>

          <section className="card side-card" id="soloReviewCard">
            <div className="side-head">
              <b>
                <span className="warn-tri">⚠</span> 需要人工审核 <em>({pendingChanges.length})</em>
              </b>
              <button
                type="button"
                className="link"
                onClick={() => toast(`待审 ${pendingChanges.length} 项 · 已落盘 ${appliedChanges.length} 项`)}
              >
                查看全部 ›
              </button>
            </div>
            <ul className="review">
              {pendingChanges.length === 0 ? (
                <li className="rv-empty">暂无待审核项，Agent 产出改动后会出现在这里。</li>
              ) : (
                pendingChanges.slice(0, 3).map((change) => (
                  <li key={change.id}>
                    <span className="rv-ic c">🔒</span>
                    <div className="rv-main">
                      <b>{change.file_path}</b>
                      <i>
                        {change.reason?.trim() ||
                          change.summary?.trim() ||
                          `+${change.added_lines} −${change.removed_lines}`}
                      </i>
                    </div>
                    <button
                      type="button"
                      className="btn btn-ghost btn-xs"
                      onClick={() => setReviewId(change.id)}
                    >
                      去审核
                    </button>
                  </li>
                ))
              )}
            </ul>
          </section>

          <section className="card side-card">
            <div className="side-head">
              <b>任务信息</b>
              <button
                type="button"
                className="link"
                onClick={() => toast("编辑任务信息尚未开放：需求可在下方输入框继续补充")}
              >
                ✎ 编辑 ›
              </button>
            </div>
            <div className="task-info">
              <div className="ti-row">
                <span>任务 ID</span>
                <b>{task ? task.id.slice(0, 8) : "—"}</b>
              </div>
              <div className="ti-row">
                <span>创建时间</span>
                <b>{task ? clockOf(task.created_at) : "—"}</b>
              </div>
              <div className="ti-row">
                <span>优先级</span>
                <b>{task ? task.priority : "—"}</b>
              </div>
              <div className="ti-row">
                <span>对话模型</span>
                <b>{lastModel ? `${lastModel.provider} · ${lastModel.model}` : "—"}</b>
              </div>
            </div>
          </section>
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
              <AgentRoster agents={agents} assembling={assembling} onAssemble={() => void assembleTeam()} />
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
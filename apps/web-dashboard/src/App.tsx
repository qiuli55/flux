/**
 * Flux · 最小 IDE 的前端编排层。
 *
 * 三栏 + 顶部 + 底部：左=项目与文件，中=变更审阅与 Git，右=AI 团队与最近活动，
 * 底部=任务时间线与统计。所有数据都来自后端真实接口，不做任何 mock。
 */
import { useCallback, useEffect, useMemo, useRef, useState } from "react";

import { ApiError, api } from "./api/client";
import type {
  AgentHandle,
  Change,
  GitCommit,
  GitStatus,
  HealthData,
  Project,
  ReadyData,
  ScanOutcome,
} from "./api/types";
import { ActivityFeed } from "./components/ActivityFeed";
import { AgentPanel, TeamPulse } from "./components/AgentPanel";
import { ChangeReview } from "./components/ChangeReview";
import { GitPanel } from "./components/GitPanel";
import { ProjectPanel } from "./components/ProjectPanel";
import { TaskTimeline, type RunStats } from "./components/TaskTimeline";
import { TopBar } from "./components/TopBar";
import { Panel } from "./components/ui";
import { BUILTIN_ROLES, toCreateRequest } from "./data/team";
import { createEvent, type EventLevel, type LogEvent } from "./data/events";

/** 把任意异常转成面向用户的文本 */
function errorMessage(error: unknown): string {
  if (error instanceof Error) return error.message;
  return String(error);
}

/** 默认提交信息：带当前变更摘要（用户可改） */
function defaultCommitMessage(changes: Change[]): string {
  const applied = changes.filter((change) => change.status === "applied");
  if (applied.length === 0) return "";
  const files = applied.map((change) => change.file_path);
  const headline = applied[0]?.summary?.trim();
  const subject = headline ? `feat: ${headline}` : `feat: 应用 AI 变更（${applied.length} 个文件）`;
  return [subject, "", ...files.map((path) => `- ${path}`)].join("\n");
}

export default function App() {
  const [health, setHealth] = useState<HealthData | null>(null);
  const [ready, setReady] = useState<ReadyData | null>(null);
  const [healthError, setHealthError] = useState<string | null>(null);
  const [healthLoading, setHealthLoading] = useState(false);

  const [projects, setProjects] = useState<Project[]>([]);
  const [projectId, setProjectId] = useState<string | null>(null);
  const [createBusy, setCreateBusy] = useState(false);
  const [createError, setCreateError] = useState<string | null>(null);
  const [scan, setScan] = useState<ScanOutcome | null>(null);
  const [scanBusy, setScanBusy] = useState(false);
  const [scanError, setScanError] = useState<string | null>(null);
  const [contextPaths, setContextPaths] = useState<string[]>([]);

  const [instruction, setInstruction] = useState("");
  const [generateBusy, setGenerateBusy] = useState(false);
  const [generateError, setGenerateError] = useState<string | null>(null);
  const [generateSummary, setGenerateSummary] = useState<string | null>(null);

  const [changes, setChanges] = useState<Change[]>([]);
  const [filter, setFilter] = useState("");
  const [listBusy, setListBusy] = useState(false);
  const [listError, setListError] = useState<string | null>(null);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [selected, setSelected] = useState<Change | null>(null);
  // 供 loadChanges 读取当前选中项，避免把 selectedId 放进回调依赖造成重复请求
  const selectedIdRef = useRef<string | null>(null);
  const [detailBusy, setDetailBusy] = useState(false);
  const [actionBusyId, setActionBusyId] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);

  const [gitStatus, setGitStatus] = useState<GitStatus | null>(null);
  const [gitBusy, setGitBusy] = useState(false);
  const [gitError, setGitError] = useState<string | null>(null);
  const [commitMessage, setCommitMessage] = useState("");
  const [commitTouched, setCommitTouched] = useState(false);
  const [commitBusy, setCommitBusy] = useState(false);
  const [commitError, setCommitError] = useState<string | null>(null);
  const [lastCommit, setLastCommit] = useState<GitCommit | null>(null);

  const [agents, setAgents] = useState<AgentHandle[]>([]);
  const [agentsBusy, setAgentsBusy] = useState(false);
  const [agentsError, setAgentsError] = useState<string | null>(null);
  const [assembleBusy, setAssembleBusy] = useState(false);

  const [events, setEvents] = useState<LogEvent[]>([]);
  const [dockTab, setDockTab] = useState<"timeline" | "git">("timeline");

  /** 追加一条事件日志（同时喂给最近活动与任务时间线） */
  const log = useCallback((actor: string, action: string, result: string, level: EventLevel = "info") => {
    setEvents((prev) => [...prev, createEvent(actor, action, result, level)]);
  }, []);

  // --- 数据加载 ---

  const loadHealth = useCallback(async () => {
    setHealthLoading(true);
    try {
      setHealth(await api.health());
      setHealthError(null);
    } catch (error) {
      setHealth(null);
      setHealthError(errorMessage(error));
    }
    try {
      setReady(await api.ready());
    } catch (error) {
      // 就绪探针失败（如数据库不可用）时，details 里仍带 database/providers
      if (error instanceof ApiError && error.details && typeof error.details === "object") {
        setReady(error.details as ReadyData);
      } else {
        setReady(null);
      }
      setHealthError(errorMessage(error));
    }
    setHealthLoading(false);
  }, []);

  const loadProjects = useCallback(async () => {
    try {
      const list = await api.listProjects();
      setProjects(list);
      setProjectId((current) => (current && list.some((p) => p.id === current) ? current : (list[0]?.id ?? null)));
    } catch (error) {
      setCreateError(errorMessage(error));
    }
  }, []);

  const loadAgents = useCallback(async () => {
    setAgentsBusy(true);
    try {
      setAgents(await api.listAgents());
      setAgentsError(null);
    } catch (error) {
      setAgentsError(errorMessage(error));
    }
    setAgentsBusy(false);
  }, []);

  const loadGit = useCallback(async () => {
    setGitBusy(true);
    try {
      setGitStatus(await api.gitStatus());
      setGitError(null);
    } catch (error) {
      setGitStatus(null);
      setGitError(errorMessage(error));
    }
    setGitBusy(false);
  }, []);

  const loadChanges = useCallback(
    async (status = filter) => {
      setListBusy(true);
      try {
        const list = await api.listChanges(status || undefined);
        setChanges(list);
        setListError(null);
        const current = selectedIdRef.current;
        const next = current && list.some((change) => change.id === current) ? current : (list[0]?.id ?? null);
        selectedIdRef.current = next;
        setSelectedId(next);
        setSelected(list.find((change) => change.id === next) ?? null);
      } catch (error) {
        setListError(errorMessage(error));
      }
      setListBusy(false);
    },
    [filter],
  );

  const refreshSelected = useCallback(async (changeId: string) => {
    setDetailBusy(true);
    try {
      setSelected(await api.getChange(changeId));
    } catch (error) {
      setListError(errorMessage(error));
    }
    setDetailBusy(false);
  }, []);

  // 首次加载：健康、项目、Agent、Git
  useEffect(() => {
    void loadHealth();
    void loadProjects();
    void loadAgents();
    void loadGit();
  }, [loadHealth, loadProjects, loadAgents, loadGit]);

  // 健康状态每 30 秒自动刷新
  useEffect(() => {
    const timer = window.setInterval(() => void loadHealth(), 30_000);
    return () => window.clearInterval(timer);
  }, [loadHealth]);

  // 变更列表首屏与过滤切换
  useEffect(() => {
    void loadChanges(filter);
  }, [filter, loadChanges]);

  // 默认提交信息跟随已落盘变更（用户改过就不覆盖）
  useEffect(() => {
    if (!commitTouched) setCommitMessage(defaultCommitMessage(changes));
  }, [changes, commitTouched]);

  // --- 操作 ---

  const handleCreateProject = useCallback(
    async (name: string, repository: string) => {
      setCreateBusy(true);
      setCreateError(null);
      try {
        const project = await api.createProject({ name, repository: repository || null });
        setProjects((prev) => [...prev, project]);
        setProjectId(project.id);
        setScan(null);
        setContextPaths([]);
        log("用户", `登记项目 ${project.name}`, `项目 ID ${project.id}`, "ok");
      } catch (error) {
        setCreateError(errorMessage(error));
        log("用户", `登记项目 ${name}`, errorMessage(error), "error");
      }
      setCreateBusy(false);
    },
    [log],
  );

  const handleScan = useCallback(async () => {
    if (!projectId) return;
    setScanBusy(true);
    setScanError(null);
    try {
      const outcome = await api.scanProject(projectId);
      setScan(outcome);
      setContextPaths([]);
      const profile = outcome.profile;
      log(
        "系统",
        "扫描工作区",
        `主语言 ${profile.primary_language ?? "未知"}，文件 ${profile.files_scanned} 个，顶层 ${profile.structure.length} 项`,
        "ok",
      );
    } catch (error) {
      setScanError(errorMessage(error));
      log("系统", "扫描工作区", errorMessage(error), "error");
    }
    setScanBusy(false);
  }, [projectId, log]);

  const handleGenerate = useCallback(async () => {
    const trimmed = instruction.trim();
    if (!trimmed) return;
    setGenerateBusy(true);
    setGenerateError(null);
    setGenerateSummary(null);
    log("用户", "提交需求", trimmed, "running");
    try {
      const outcome = await api.generate({
        instruction: trimmed,
        paths: contextPaths,
        task_id: null,
        project_id: projectId,
      });
      setGenerateSummary(outcome.summary);
      log(
        "Developer",
        `产出提案（${outcome.proposals.length} 条）`,
        outcome.summary || `涉及文件：${outcome.files.join("、") || "无"}`,
        "ok",
      );
      setFilter("");
      const list = await api.listChanges(undefined);
      setChanges(list);
      setListError(null);
      const first = outcome.proposals[0]?.id ?? list[0]?.id ?? null;
      selectedIdRef.current = first;
      setSelectedId(first);
      setSelected(list.find((change) => change.id === first) ?? outcome.proposals[0] ?? null);
    } catch (error) {
      setGenerateError(errorMessage(error));
      log("Developer", "产出提案失败", errorMessage(error), "error");
      void loadChanges(filter);
    }
    setGenerateBusy(false);
  }, [instruction, contextPaths, projectId, filter, loadChanges, log]);

  const handleSelectChange = useCallback(
    (changeId: string) => {
      selectedIdRef.current = changeId;
      setSelectedId(changeId);
      setSelected(changes.find((change) => change.id === changeId) ?? null);
      void refreshSelected(changeId);
    },
    [changes, refreshSelected],
  );

  const runAction = useCallback(
    async (changeId: string, action: "accept" | "apply" | "reject", reason = "") => {
      const target = changes.find((change) => change.id === changeId);
      const path = target?.file_path ?? changeId;
      const labels = { accept: "批准变更", apply: "落盘变更", reject: "拒绝变更" } as const;
      setActionBusyId(changeId);
      setActionError(null);
      try {
        if (action === "accept") await api.accept([changeId]);
        else if (action === "apply") await api.apply([changeId]);
        else await api.reject([changeId], reason || null);
        log("用户", `${labels[action]} ${path}`, action === "apply" ? "已写入工作区并跑过项目测试" : "状态已更新", "ok");
      } catch (error) {
        setActionError(errorMessage(error));
        log("用户", `${labels[action]} ${path}`, errorMessage(error), "error");
      }
      // 不论成功失败都重新拉取：apply 失败会把提案置为 failed 并写入 apply_error
      await refreshSelected(changeId);
      await loadChanges(filter);
      if (action === "apply") await loadGit();
      setActionBusyId(null);
    },
    [changes, filter, loadChanges, refreshSelected, loadGit, log],
  );

  const appliedChanges = useMemo(() => changes.filter((change) => change.status === "applied"), [changes]);

  const handleCommit = useCallback(async () => {
    const message = commitMessage.trim();
    if (!message || appliedChanges.length === 0) return;
    setCommitBusy(true);
    setCommitError(null);
    try {
      const commit = await api.gitCommit({
        message,
        change_ids: appliedChanges.map((change) => change.id),
      });
      setLastCommit(commit);
      log("用户", "提交 Git", `${commit.short_sha} · ${commit.files.length} 个文件`, "ok");
      await loadGit();
      await loadChanges(filter);
    } catch (error) {
      setCommitError(errorMessage(error));
      log("用户", "提交 Git", errorMessage(error), "error");
    }
    setCommitBusy(false);
  }, [commitMessage, appliedChanges, filter, loadGit, loadChanges, log]);

  const handleAssembleTeam = useCallback(async () => {
    setAssembleBusy(true);
    setAgentsError(null);
    const existing = new Set(agents.map((agent) => agent.spec.role));
    const failures: string[] = [];
    for (const role of BUILTIN_ROLES) {
      if (existing.has(role.role)) continue;
      try {
        const created = await api.createAgent(toCreateRequest(role));
        log("用户", `创建 Agent ${created.spec.name}`, `角色 ${created.spec.role}`, "ok");
      } catch (error) {
        failures.push(`${role.name}：${errorMessage(error)}`);
        log("用户", `创建 Agent ${role.name}`, errorMessage(error), "error");
      }
    }
    try {
      setAgents(await api.listAgents());
      if (failures.length > 0) setAgentsError(`部分角色创建失败——${failures.join("；")}`);
    } catch (error) {
      setAgentsError(errorMessage(error));
    }
    setAssembleBusy(false);
  }, [agents, log]);

  const stats: RunStats = useMemo(() => {
    const files = new Set(changes.map((change) => change.file_path));
    const byStatus = { pending: 0, accepted: 0, applied: 0, rejected: 0, failed: 0 };
    let added = 0;
    let removed = 0;
    for (const change of changes) {
      added += change.added_lines;
      removed += change.removed_lines;
      byStatus[change.status] += 1;
    }
    return { files: files.size, added, removed, byStatus };
  }, [changes]);

  const projectName = projects.find((project) => project.id === projectId)?.name ?? "未选择项目";
  const latestEvent = events.length > 0 ? (events[events.length - 1]?.action ?? null) : null;

  return (
    <div className="flex h-screen flex-col bg-canvas text-text">
      <TopBar
        health={health}
        ready={ready}
        readyError={healthError}
        loading={healthLoading}
        onRefresh={() => void loadHealth()}
      />

      <main className="grid min-h-0 flex-1 grid-cols-1 gap-2 p-2 xl:grid-cols-[20rem_minmax(0,1fr)_22rem] xl:overflow-hidden">
        {/* 左栏 · 项目与文件 */}
        <div className="flex min-h-[30rem] flex-col gap-2 xl:min-h-0">
          <ProjectPanel
            projects={projects}
            selectedId={projectId}
            onSelect={(id) => {
              setProjectId(id);
              setScan(null);
              setContextPaths([]);
            }}
            onCreate={handleCreateProject}
            createBusy={createBusy}
            scan={scan}
            scanBusy={scanBusy}
            onScan={() => void handleScan()}
            contextPaths={contextPaths}
            onAddPath={(path) =>
              setContextPaths((prev) => (prev.includes(path) || prev.length >= 5 ? prev : [...prev, path]))
            }
            onRemovePath={(path) => setContextPaths((prev) => prev.filter((item) => item !== path))}
            createError={createError}
            scanError={scanError}
            onDismissCreateError={() => setCreateError(null)}
            onDismissScanError={() => setScanError(null)}
          />
          <TeamPulse agents={agents} latest={latestEvent} />
        </div>

        {/* 中栏 · 变更审阅 + 底部任务流/Git */}
        <div className="flex min-h-[42rem] flex-col gap-2 xl:min-h-0">
          <ChangeReview
            projectName={projectName}
            contextCount={contextPaths.length}
            instruction={instruction}
            onInstructionChange={setInstruction}
            onGenerate={() => void handleGenerate()}
            generateBusy={generateBusy}
            generateError={generateError}
            onDismissGenerateError={() => setGenerateError(null)}
            generateSummary={generateSummary}
            changes={changes}
            filter={filter}
            onFilterChange={setFilter}
            listBusy={listBusy}
            listError={listError}
            onDismissListError={() => setListError(null)}
            onReload={() => void loadChanges(filter)}
            selected={selected}
            selectedId={selectedId}
            onSelect={handleSelectChange}
            detailBusy={detailBusy}
            actionBusyId={actionBusyId}
            onAccept={(id) => void runAction(id, "accept")}
            onApply={(id) => void runAction(id, "apply")}
            onReject={(id, reason) => void runAction(id, "reject", reason)}
            actionError={actionError}
            onDismissActionError={() => setActionError(null)}
          />

          <Panel className="h-[17rem] shrink-0">
            <div className="flex shrink-0 items-center gap-1 border-b border-line-soft px-2 py-1.5">
              {(
                [
                  ["timeline", `AI 任务流 ${events.length}`],
                  ["git", `Git 变更 ${gitStatus?.files.length ?? 0}`],
                ] as const
              ).map(([value, label]) => (
                <button
                  key={value}
                  type="button"
                  onClick={() => setDockTab(value)}
                  className={`rounded px-2.5 py-1 text-xs transition-colors ${
                    dockTab === value ? "bg-surface-3 text-text" : "text-faint hover:text-muted"
                  }`}
                >
                  {label}
                </button>
              ))}
              <span className="ml-auto truncate text-[11px] text-faint">
                {dockTab === "timeline" ? "本轮每一步操作与结果" : "只提交 applied 状态的变更"}
              </span>
            </div>
            {dockTab === "timeline" ? (
              <TaskTimeline events={events} stats={stats} />
            ) : (
              <GitPanel
                status={gitStatus}
                loading={gitBusy}
                error={gitError}
                onDismissError={() => setGitError(null)}
                onRefresh={() => void loadGit()}
                commitMessage={commitMessage}
                onCommitMessageChange={(value) => {
                  setCommitMessage(value);
                  setCommitTouched(true);
                }}
                onCommit={() => void handleCommit()}
                commitBusy={commitBusy}
                commitError={commitError}
                onDismissCommitError={() => setCommitError(null)}
                lastCommit={lastCommit}
                appliedCount={appliedChanges.length}
              />
            )}
          </Panel>
        </div>

        {/* 右栏 · AI 团队与最近活动 */}
        <div className="flex min-h-[36rem] flex-col gap-2 xl:min-h-0">
          <AgentPanel
            agents={agents}
            loading={agentsBusy}
            error={agentsError}
            onDismissError={() => setAgentsError(null)}
            onRefresh={() => void loadAgents()}
            onAssemble={() => void handleAssembleTeam()}
            assembleBusy={assembleBusy}
          />
          <ActivityFeed events={events} />
        </div>
      </main>
    </div>
  );
}
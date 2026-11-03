/**
 * 助理（Supervisor）：顶层编排者。
 *
 * 展示链路：外部事件 → Gate/判断 → 工单树 → 派给子 Agent → 报告回收。
 * 数据全部来自 /api/v1/supervisor/*，页面只发起动作并回读事实，不本地模拟状态。
 */
import { useCallback, useEffect, useMemo, useState, type CSSProperties } from "react";

import { api } from "../api/client";
import type {
  AcceptMode,
  ReportStatus,
  SupervisorDecision,
  SupervisorOverview,
  SupervisorReport,
  SupervisorTicket,
  SupervisorTimelineItem,
  SupervisorTrigger,
  TicketStage,
} from "../api/types";
import { toast } from "../app/toast";
import { Select } from "../shell/Select";
import { Badge, Button, Card, Empty, Field, Input, PageHead, SectionTitle, Segmented, Stat } from "../shell/ui";

type Tone = "default" | "brand" | "green" | "orange" | "red" | "blue";

const STAGE_META: Record<TicketStage, { label: string; tone: Tone }> = {
  intake: { label: "待规划", tone: "default" },
  planned: { label: "待派发", tone: "blue" },
  running: { label: "执行中", tone: "brand" },
  waiting: { label: "等输入", tone: "orange" },
  reviewing: { label: "待验收", tone: "blue" },
  done: { label: "已完成", tone: "green" },
  stopped: { label: "已停止", tone: "red" },
};

const DECISION_META: Record<SupervisorDecision, { label: string; tone: Tone }> = {
  accept: { label: "接受", tone: "green" },
  stop: { label: "停止", tone: "red" },
  ignore: { label: "忽略", tone: "default" },
  unavailable: { label: "未判定", tone: "orange" },
};

const REPORT_META: Record<ReportStatus, { label: string; tone: Tone }> = {
  completed: { label: "完成", tone: "green" },
  failed: { label: "失败", tone: "red" },
  blocked: { label: "阻塞", tone: "orange" },
  needs_input: { label: "待输入", tone: "orange" },
  cancelled: { label: "已取消", tone: "default" },
  timed_out: { label: "超时", tone: "red" },
};

const TRIGGER_STATUS: Record<string, string> = {
  received: "待处理",
  gated: "已过门",
  assessed: "已判断",
  queued: "已排队",
  planned: "已规划",
  ignored: "已忽略",
};

const KIND_META: Record<SupervisorTimelineItem["kind"], { label: string; tone: Tone }> = {
  trigger: { label: "事件", tone: "blue" },
  ticket: { label: "工单", tone: "brand" },
  report: { label: "报告", tone: "green" },
};

const COLUMNS: { key: string; label: string; stages: TicketStage[] }[] = [
  { key: "todo", label: "待派发", stages: ["intake", "planned"] },
  { key: "doing", label: "进行中", stages: ["running", "waiting"] },
  { key: "review", label: "待验收", stages: ["reviewing"] },
  { key: "ended", label: "已结束", stages: ["done", "stopped"] },
];

const MODE_OPTIONS: { value: AcceptMode; label: string }[] = [
  { value: "off", label: "关闭" },
  { value: "observe", label: "观察" },
  { value: "auto", label: "自动" },
];

const STATUS_OPTIONS = [
  { value: "all", label: "全部状态" },
  { value: "received", label: "待处理" },
  { value: "gated", label: "已过门" },
  { value: "assessed", label: "已判断" },
  { value: "queued", label: "已排队" },
  { value: "planned", label: "已规划" },
  { value: "ignored", label: "已忽略" },
];

const EMPTY_TICKETS: SupervisorTicket[] = [];

function relTime(iso: string | null): string {
  if (!iso) return "—";
  const diff = Date.now() - new Date(iso).getTime();
  if (Number.isNaN(diff)) return "—";
  const min = Math.floor(diff / 60_000);
  if (min < 1) return "刚刚";
  if (min < 60) return `${min} 分钟前`;
  const hour = Math.floor(min / 60);
  if (hour < 24) return `${hour} 小时前`;
  return `${Math.floor(hour / 24)} 天前`;
}

function clampInt(value: string, min: number, max: number): number {
  const parsed = Number.parseInt(value, 10);
  if (Number.isNaN(parsed)) return min;
  return Math.min(max, Math.max(min, parsed));
}

function failMessage(error: unknown, fallback: string): string {
  return error instanceof Error && error.message ? error.message : fallback;
}

interface Draft {
  mode: AcceptMode;
  concurrent: string;
  perThread: string;
  depth: string;
}

/* ---------------- 工单卡 ---------------- */
function TicketCard({
  ticket,
  acting,
  onOpen,
  onDispatch,
  onRetry,
  onStop,
}: {
  ticket: SupervisorTicket;
  acting: string | null;
  onOpen: () => void;
  onDispatch: () => void;
  onRetry: () => void;
  onStop: () => void;
}) {
  const meta = STAGE_META[ticket.stage];
  const canDispatch = ticket.stage === "intake" || ticket.stage === "planned";
  const canStop = ticket.stage === "running" || ticket.stage === "waiting" || ticket.stage === "reviewing";
  return (
    <div
      className="fx-kb-card"
      role="button"
      tabIndex={0}
      style={{ cursor: "pointer" }}
      onClick={onOpen}
      onKeyDown={(event) => {
        if (event.key === "Enter" || event.key === " ") {
          event.preventDefault();
          onOpen();
        }
      }}
    >
      <div className="fx-t">{ticket.title}</div>
      <div className="fx-row fx-wrap fx-gap-2">
        <Badge tone={meta.tone} dot>{meta.label}</Badge>
        <span className="fx-kb-tag">{ticket.role || "未指定角色"}</span>
        {ticket.degraded ? <span className="fx-kb-tag">降级</span> : null}
      </div>
      {ticket.report?.summary ? (
        <div className="fx-dim fx-ellipsis" style={{ fontSize: 11.5 }}>{ticket.report.summary}</div>
      ) : null}
      <div className="fx-row fx-gap-1 fx-wrap" onClick={(event) => event.stopPropagation()}>
        {canDispatch ? (
          <Button
            size="sm"
            variant="subtle"
            busy={acting === `dispatch:${ticket.id}`}
            onClick={onDispatch}
          >
            派发
          </Button>
        ) : null}
        {ticket.stage === "stopped" ? (
          <Button size="sm" variant="subtle" busy={acting === `retry:${ticket.id}`} onClick={onRetry}>
            重试
          </Button>
        ) : null}
        {canStop ? (
          <Button size="sm" variant="quiet" busy={acting === `stop:${ticket.id}`} onClick={onStop}>
            停止
          </Button>
        ) : null}
        <Button size="sm" variant="quiet" onClick={onOpen}>详情</Button>
      </div>
      <div className="fx-kb-foot">
        <span className="fx-kb-id">{ticket.id.slice(0, 8)}</span>
        <span style={{ marginLeft: "auto" }}>{relTime(ticket.updated_at)}</span>
      </div>
    </div>
  );
}

/* ---------------- 助理页 ---------------- */
export function AssistantPage() {
  const [overview, setOverview] = useState<SupervisorOverview | null>(null);
  const [observations, setObservations] = useState<SupervisorTrigger[]>([]);
  const [reports, setReports] = useState<SupervisorReport[]>([]);
  const [timeline, setTimeline] = useState<SupervisorTimelineItem[]>([]);
  const [draft, setDraft] = useState<Draft | null>(null);
  const [detail, setDetail] = useState<SupervisorTicket | null>(null);
  const [statusFilter, setStatusFilter] = useState("all");
  const [loading, setLoading] = useState(true);
  const [acting, setActing] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    const settled = await Promise.allSettled([
      api.supervisorOverview(50),
      api.supervisorObservations({ limit: 50 }),
      api.supervisorReports(20),
      api.supervisorTimeline({ limit: 30 }),
    ]);
    if (settled[0].status === "fulfilled") {
      const value = settled[0].value;
      setOverview(value);
      const settings = value.settings;
      setDraft((prev) => prev ?? {
        mode: settings.accept_mode,
        concurrent: String(settings.max_concurrent_runs),
        perThread: String(settings.max_tickets_per_thread),
        depth: String(settings.max_ticket_depth),
      });
    } else {
      toast(failMessage(settled[0].reason, "加载助理看板失败"), "error");
    }
    if (settled[1].status === "fulfilled") setObservations(settled[1].value);
    if (settled[2].status === "fulfilled") setReports(settled[2].value);
    if (settled[3].status === "fulfilled") setTimeline(settled[3].value);
    setLoading(false);
  }, []);

  useEffect(() => { void load(); }, [load]);

  const run = useCallback(async (key: string, action: () => Promise<string>) => {
    setActing(key);
    try {
      const message = await action();
      if (message) toast(message);
      await load();
    } catch (error) {
      toast(failMessage(error, "操作失败"), "error");
    } finally {
      setActing(null);
    }
  }, [load]);

  const openTicket = useCallback(async (ticketId: string) => {
    setActing(`detail:${ticketId}`);
    try {
      setDetail(await api.supervisorTicket(ticketId));
    } catch (error) {
      toast(failMessage(error, "加载工单详情失败"), "error");
    } finally {
      setActing(null);
    }
  }, []);

  const tickets = overview?.tickets ?? EMPTY_TICKETS;
  const columns = useMemo(
    () => COLUMNS.map((col) => ({ ...col, items: tickets.filter((ticket) => col.stages.includes(ticket.stage)) })),
    [tickets],
  );
  const filteredObservations = useMemo(
    () => (statusFilter === "all" ? observations : observations.filter((item) => item.status === statusFilter)),
    [observations, statusFilter],
  );

  const count = (stage: TicketStage) => overview?.counts[stage] ?? 0;

  const saveSettings = () => {
    if (!draft) return;
    void run("settings", async () => {
      const updated = await api.updateSupervisorSettings({
        accept_mode: draft.mode,
        max_concurrent_runs: clampInt(draft.concurrent, 1, 8),
        max_tickets_per_thread: clampInt(draft.perThread, 1, 20),
        max_ticket_depth: clampInt(draft.depth, 1, 3),
      });
      setDraft({
        mode: updated.accept_mode,
        concurrent: String(updated.max_concurrent_runs),
        perThread: String(updated.max_tickets_per_thread),
        depth: String(updated.max_ticket_depth),
      });
      return "助理设置已保存";
    });
  };

  const assess = (item: SupervisorTrigger) => void run(`assess:${item.id}`, async () => {
    const result = await api.supervisorAssess(item.id);
    const decision = result.assessment?.decision;
    return decision ? `已重判：${DECISION_META[decision].label}` : "已重判，但模型没有给出结论";
  });

  const plan = (item: SupervisorTrigger, dispatch: boolean) => void run(`plan:${item.id}:${dispatch}`, async () => {
    const result = await api.supervisorPlan(item.id, dispatch);
    const dispatched = result.dispatch.filter((outcome) => outcome.dispatched).length;
    const extra = dispatch ? `，派出 ${dispatched} 张` : "";
    const degraded = result.plan.degraded ? "（规划降级）" : "";
    return `已生成 ${result.tickets.length} 张工单${degraded}${extra}`;
  });

  const dispatchTicket = (ticket: SupervisorTicket) => void run(`dispatch:${ticket.id}`, async () => {
    const result = await api.supervisorDispatchTicket(ticket.id);
    return result.dispatched ? "工单已派发" : `未派发：${result.reason || "当前不可派发"}`;
  });

  const retryTicket = (ticket: SupervisorTicket) => void run(`retry:${ticket.id}`, async () => {
    const result = await api.supervisorRetryTicket(ticket.id);
    return result.dispatched ? "已重新派发" : `未派发：${result.reason || "当前不可派发"}`;
  });

  const stopTicket = (ticket: SupervisorTicket) => {
    if (!window.confirm(`确认停止「${ticket.title}」？`)) return;
    void run(`stop:${ticket.id}`, async () => {
      const result = await api.supervisorStopTicket(ticket.id);
      return `已停止，写入 ${result.report} 报告`;
    });
  };

  return (
    <>
      <PageHead title="助理" desc="顶层编排者：观察外部事件、判断是否接手、把工作拆成工单派给子 Agent，并汇总回来的报告。">
        <Button variant="subtle" icon="refresh" busy={loading} onClick={() => void load()}>刷新</Button>
        <Button
          variant="subtle"
          icon="shield"
          busy={acting === "watchdog"}
          onClick={() => void run("watchdog", async () => {
            const result = await api.supervisorWatchdog(50);
            return `检查 ${result.checked} 个在途派活，回收 ${result.expired} 个`;
          })}
        >
          回收超时
        </Button>
        <Button
          variant="primary"
          icon="zap"
          busy={acting === "process"}
          onClick={() => void run("process", async () => {
            const result = await api.supervisorProcess(10);
            return `处理 ${result.processed} 条，队列剩余 ${result.pending_outbox}`;
          })}
        >
          处理队列
        </Button>
      </PageHead>

      <Card pad style={{ marginBottom: 16 }}>
        <div className="fx-between" style={{ marginBottom: 10 }}>
          <SectionTitle>运行设置</SectionTitle>
          <span className="fx-dim" style={{ fontSize: 11 }}>
            {overview ? `模型 ${overview.settings.model_provider}/${overview.settings.model_name}` : "—"}
          </span>
        </div>
        {draft ? (
          <div className="fx-row fx-wrap fx-gap-4" style={{ alignItems: "flex-end" }}>
            <Field label="准入模式" hint="关闭只收事件；观察只判断不派活；自动规划并派发">
              <Segmented
                value={draft.mode}
                options={MODE_OPTIONS}
                onChange={(mode) => setDraft({ ...draft, mode })}
              />
            </Field>
            <Field label="并发上限">
              <Input
                type="number"
                min={1}
                max={8}
                value={draft.concurrent}
                style={{ width: 110 }}
                onChange={(event) => setDraft({ ...draft, concurrent: event.target.value })}
              />
            </Field>
            <Field label="单线程工单上限">
              <Input
                type="number"
                min={1}
                max={20}
                value={draft.perThread}
                style={{ width: 110 }}
                onChange={(event) => setDraft({ ...draft, perThread: event.target.value })}
              />
            </Field>
            <Field label="工单树深度">
              <Input
                type="number"
                min={1}
                max={3}
                value={draft.depth}
                style={{ width: 110 }}
                onChange={(event) => setDraft({ ...draft, depth: event.target.value })}
              />
            </Field>
            <Button variant="primary" icon="check" busy={acting === "settings"} onClick={saveSettings}>
              保存设置
            </Button>
          </div>
        ) : (
          <div className="fx-dim" style={{ fontSize: 12 }}>加载中…</div>
        )}
      </Card>

      <div className="fx-stats" style={{ marginBottom: 16 }}>
        <Stat label="观察事件" value={observations.length} icon="eye" />
        <Stat label="待处理队列" value={overview?.pending_outbox ?? 0} icon="clock" />
        <Stat label="进行中工单" value={count("running") + count("waiting")} icon="activity" />
        <Stat label="已收尾工单" value={count("done") + count("stopped")} icon="check" />
      </div>

      <Card pad style={{ marginBottom: 16 }}>
        <div className="fx-between" style={{ marginBottom: 4 }}>
          <SectionTitle>观察事件</SectionTitle>
          <Select value={statusFilter} options={STATUS_OPTIONS} onChange={setStatusFilter} width={160} />
        </div>
        {filteredObservations.length === 0 ? (
          <Empty
            icon="eye"
            title={loading ? "加载中…" : "还没有事件"}
            desc={loading ? undefined : "外部消息或内部指令进入后会出现在这里"}
          />
        ) : (
          <div style={{ maxHeight: 420, overflowY: "auto" }}>
            {filteredObservations.map((item) => (
              <div key={item.id} style={obsRow}>
                <div className="fx-between fx-gap-2">
                  <div className="fx-row fx-wrap fx-gap-2">
                    <Badge tone="blue">{item.source_key}</Badge>
                    <Badge tone="default">{TRIGGER_STATUS[item.status] ?? item.status}</Badge>
                    {item.eval ? (
                      <Badge tone={DECISION_META[item.eval.decision].tone} dot>
                        {DECISION_META[item.eval.decision].label}
                      </Badge>
                    ) : (
                      <Badge tone="orange">未判断</Badge>
                    )}
                    <span className="fx-dim" style={{ fontSize: 11 }}>
                      {item.actor_id || "未知来源"} · {relTime(item.created_at)}
                    </span>
                  </div>
                  <span className="fx-dim" style={{ fontSize: 11, flex: "none" }}>{item.id.slice(0, 8)}</span>
                </div>
                <div className="fx-ellipsis" style={{ fontSize: 12.5, marginTop: 6 }} title={item.content}>
                  {item.content || "（空事件）"}
                </div>
                {item.eval?.reason ? (
                  <div className="fx-dim" style={{ fontSize: 11.5, marginTop: 4 }}>判断：{item.eval.reason}</div>
                ) : null}
                <div className="fx-row fx-wrap fx-gap-2" style={{ marginTop: 8 }}>
                  <Button
                    size="sm"
                    variant="subtle"
                    icon="refresh"
                    busy={acting === `assess:${item.id}`}
                    onClick={() => assess(item)}
                  >
                    重判
                  </Button>
                  <Button
                    size="sm"
                    variant="subtle"
                    icon="layers"
                    busy={acting === `plan:${item.id}:false`}
                    onClick={() => plan(item, false)}
                  >
                    规划
                  </Button>
                  <Button
                    size="sm"
                    variant="primary"
                    icon="rocket"
                    busy={acting === `plan:${item.id}:true`}
                    onClick={() => plan(item, true)}
                  >
                    规划并派发
                  </Button>
                </div>
              </div>
            ))}
          </div>
        )}
      </Card>

      <SectionTitle>工单</SectionTitle>
      {tickets.length === 0 ? (
        <Empty
          icon="layers"
          title={loading ? "加载中…" : "还没有工单"}
          desc={loading ? undefined : "对观察事件执行「规划」后，工单会出现在这里"}
        />
      ) : (
        <div className="fx-kanban" style={{ marginTop: 10 }}>
          {columns.map((col) => (
            <div className="fx-kb-col" key={col.key}>
              <div className="fx-kb-head">{col.label}<span className="fx-n">{col.items.length}</span></div>
              {col.items.length === 0 ? (
                <div className="fx-dim" style={{ fontSize: 12, padding: "8px 2px" }}>暂无</div>
              ) : col.items.map((ticket) => (
                <TicketCard
                  key={ticket.id}
                  ticket={ticket}
                  acting={acting}
                  onOpen={() => void openTicket(ticket.id)}
                  onDispatch={() => dispatchTicket(ticket)}
                  onRetry={() => retryTicket(ticket)}
                  onStop={() => stopTicket(ticket)}
                />
              ))}
            </div>
          ))}
        </div>
      )}

      {detail ? (
        <Card pad style={{ marginTop: 16 }}>
          <div className="fx-between" style={{ marginBottom: 8 }}>
            <SectionTitle>工单详情 · {detail.title}</SectionTitle>
            <Button variant="quiet" size="sm" icon="close" onClick={() => setDetail(null)}>关闭</Button>
          </div>
          <div className="fx-row fx-wrap fx-gap-2" style={{ marginBottom: 8 }}>
            <Badge tone={STAGE_META[detail.stage].tone} dot>{STAGE_META[detail.stage].label}</Badge>
            <span className="fx-dim" style={{ fontSize: 12 }}>角色 {detail.role || "—"}</span>
            {detail.task_id ? (
              <span className="fx-dim" style={{ fontSize: 12 }}>任务 {detail.task_id.slice(0, 8)}</span>
            ) : null}
            <span className="fx-dim" style={{ fontSize: 12 }}>更新于 {relTime(detail.updated_at)}</span>
          </div>
          {detail.description ? (
            <p className="fx-dim" style={{ fontSize: 12.5, whiteSpace: "pre-wrap", margin: 0 }}>{detail.description}</p>
          ) : null}
          <div className="fx-grid fx-c2" style={{ marginTop: 12 }}>
            <div>
              <div className="fx-rail-label">派活记录</div>
              {(detail.assignments ?? []).length === 0 ? (
                <div className="fx-dim" style={{ fontSize: 12 }}>暂无</div>
              ) : (detail.assignments ?? []).map((assignment) => (
                <div key={assignment.id} className="fx-row fx-gap-2" style={detailRow}>
                  <Badge tone={assignment.status === "failed" ? "red" : assignment.status === "finished" ? "green" : "brand"}>
                    {assignment.status}
                  </Badge>
                  <span className="fx-dim fx-ellipsis" style={{ fontSize: 11.5 }}>
                    {assignment.run_id || assignment.task_id || assignment.id.slice(0, 8)}
                  </span>
                  <span className="fx-dim" style={{ marginLeft: "auto", fontSize: 11 }}>
                    {relTime(assignment.started_at ?? assignment.finished_at)}
                  </span>
                </div>
              ))}
            </div>
            <div>
              <div className="fx-rail-label">报告</div>
              {(detail.reports ?? []).length === 0 ? (
                <div className="fx-dim" style={{ fontSize: 12 }}>暂无</div>
              ) : (detail.reports ?? []).map((report) => (
                <div key={report.id} style={detailRow}>
                  <div className="fx-row fx-gap-2">
                    <Badge tone={REPORT_META[report.status].tone}>{REPORT_META[report.status].label}</Badge>
                    <span className="fx-dim" style={{ marginLeft: "auto", fontSize: 11 }}>{relTime(report.created_at)}</span>
                  </div>
                  {report.summary ? (
                    <div className="fx-dim" style={{ fontSize: 11.5, marginTop: 4 }}>{report.summary}</div>
                  ) : null}
                </div>
              ))}
            </div>
          </div>
          {(detail.subscriptions ?? []).length > 0 ? (
            <>
              <div className="fx-rail-label" style={{ marginTop: 10 }}>订阅投递</div>
              <div className="fx-row fx-wrap fx-gap-2">
                {(detail.subscriptions ?? []).map((subscription) => (
                  <Badge key={subscription.id} tone="blue">
                    {subscription.source_key} · {subscription.external_id} · {subscription.mode}
                  </Badge>
                ))}
              </div>
            </>
          ) : null}
        </Card>
      ) : null}

      <div className="fx-grid fx-c2" style={{ marginTop: 16 }}>
        <Card pad>
          <SectionTitle>最近报告</SectionTitle>
          {reports.length === 0 ? (
            <Empty icon="file" title="还没有报告" desc="子 Agent 交回结论后会出现在这里" />
          ) : (
            <div style={{ marginTop: 8 }}>
              {reports.slice(0, 8).map((report) => (
                <div key={report.id} style={detailRow}>
                  <div className="fx-row fx-gap-2">
                    <Badge tone={REPORT_META[report.status].tone} dot>{REPORT_META[report.status].label}</Badge>
                    <span className="fx-dim fx-ellipsis fx-grow" style={{ fontSize: 11.5 }}>
                      {report.summary || "（无摘要）"}
                    </span>
                    <span className="fx-dim" style={{ fontSize: 11, flex: "none" }}>{relTime(report.created_at)}</span>
                  </div>
                </div>
              ))}
            </div>
          )}
        </Card>

        <Card pad>
          <SectionTitle>时间线</SectionTitle>
          {timeline.length === 0 ? (
            <Empty icon="activity" title="还没有动态" desc="事件、工单与报告会合成一条时间线" />
          ) : (
            <div style={{ marginTop: 8 }}>
              {timeline.slice(0, 12).map((item) => (
                <div key={`${item.kind}:${item.id}`} style={detailRow}>
                  <div className="fx-row fx-gap-2">
                    <Badge tone={KIND_META[item.kind].tone}>{KIND_META[item.kind].label}</Badge>
                    <span className="fx-dim fx-ellipsis fx-grow" style={{ fontSize: 11.5 }}>{item.title || "（无标题）"}</span>
                    <span className="fx-dim" style={{ fontSize: 11, flex: "none" }}>{relTime(item.at)}</span>
                  </div>
                  <div className="fx-dim" style={{ fontSize: 11, marginTop: 2 }}>{item.status}</div>
                </div>
              ))}
            </div>
          )}
        </Card>
      </div>
    </>
  );
}

const obsRow: CSSProperties = {
  borderBottom: "1px solid var(--fx-line-soft)",
  padding: "10px 0",
};

const detailRow: CSSProperties = {
  borderBottom: "1px solid var(--fx-line-soft)",
  padding: "7px 0",
  fontSize: 12,
};

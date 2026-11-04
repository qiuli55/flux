/**
 * Bot 模式：常驻在机器上的执行者。
 *
 * 与对话的区别不是身份而是生命周期——Bot 有自己的工作目录和 cron，
 * 定时到点自动开工，也可以随时手动跑一次。每次开工都是 tasks 里的一条普通任务
 * （`tasks.bot_id` 指回这里），所以执行、消息、计费都复用任务链路。
 */
import { useCallback, useEffect, useState, type ReactNode } from "react";

import { api } from "../api/client";
import type { View } from "../app/router";
import type {
  AgentHandle,
  Bot,
  BotCreateInput,
  PermissionMode,
  Task,
  TaskStatus,
} from "../api/types";
import {
  AVATAR_COLORS,
  AVATAR_SHAPES,
  BotAvatar,
  DEFAULT_AVATAR_COLOR,
  DEFAULT_AVATAR_SHAPE,
  avatarColorHex,
} from "../components/group/BotAvatar";
import { Icon } from "../shell/Icon";
import { Modal } from "../shell/Overlay";
import { Badge, Button, Empty, Field, Input, PageHead, Segmented, Textarea } from "../shell/ui";

const PERMISSION_OPTIONS: { value: PermissionMode; label: string }[] = [
  { value: "unattended", label: "免审核" },
  { value: "normal", label: "正常" },
  { value: "plan", label: "只读计划" },
  { value: "full", label: "完全放行" },
];

const TASK_BADGE: Record<TaskStatus, { label: string; tone: "brand" | "green" | "orange" | "red" | "blue" | "default" }> = {
  pending: { label: "排队中", tone: "blue" },
  running: { label: "执行中", tone: "brand" },
  waiting_for_user_decision: { label: "待决策", tone: "orange" },
  completed: { label: "已完成", tone: "green" },
  failed: { label: "失败", tone: "red" },
  cancelled: { label: "已取消", tone: "default" },
};

function formatTime(iso: string | null | undefined): string {
  if (!iso) return "";
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return "";
  const sameDay = date.toDateString() === new Date().toDateString();
  const clock = `${`${date.getHours()}`.padStart(2, "0")}:${`${date.getMinutes()}`.padStart(2, "0")}`;
  return sameDay ? clock : `${date.getMonth() + 1}/${date.getDate()} ${clock}`;
}

function randomPair() {
  const shape = AVATAR_SHAPES[Math.floor(Math.random() * AVATAR_SHAPES.length)] ?? DEFAULT_AVATAR_SHAPE;
  const color = AVATAR_COLORS[Math.floor(Math.random() * AVATAR_COLORS.length)] ?? DEFAULT_AVATAR_COLOR;
  return { shape, color };
}

/* ---------------- 新建 / 编辑 ---------------- */

interface BotFormProps {
  open: boolean;
  /** 传入表示编辑，否则是新建 */
  bot: Bot | null;
  onClose: () => void;
  onSaved: (bot: Bot) => void;
}

function BotFormDialog({ open, bot, onClose, onSaved }: BotFormProps) {
  const [name, setName] = useState("");
  const [description, setDescription] = useState("");
  const [schedule, setSchedule] = useState("");
  const [workspace, setWorkspace] = useState("");
  const [permission, setPermission] = useState<PermissionMode>("unattended");
  const [agentId, setAgentId] = useState("");
  const [shape, setShape] = useState<string>(DEFAULT_AVATAR_SHAPE);
  const [color, setColor] = useState<string>(DEFAULT_AVATAR_COLOR);
  const [agents, setAgents] = useState<AgentHandle[]>([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!open) return;
    const pair = randomPair();
    setName(bot?.name ?? "");
    setDescription(bot?.description ?? "");
    setSchedule(bot?.schedule ?? "");
    setWorkspace(bot?.workspace ?? "");
    setPermission(bot?.permission_mode ?? "unattended");
    setAgentId(bot?.agent_id ?? "");
    setShape(bot?.avatar_shape ?? pair.shape);
    setColor(bot?.avatar_color ?? pair.color);
    setBusy(false);
    setError(null);
    let alive = true;
    api
      .listAgents()
      .then((list) => {
        if (alive) setAgents(list);
      })
      .catch(() => {
        if (alive) setAgents([]);
      });
    return () => {
      alive = false;
    };
  }, [open, bot]);

  const submit = async () => {
    const trimmed = name.trim();
    if (!trimmed || busy) return;
    const body: BotCreateInput = {
      name: trimmed,
      description: description.trim(),
      avatar_shape: shape,
      avatar_color: color,
      agent_id: agentId || null,
      permission_mode: permission,
      decision_mode: "auto",
      workspace: workspace.trim() || null,
      schedule: schedule.trim() || null,
    };
    setBusy(true);
    setError(null);
    try {
      const saved = bot ? await api.updateBot(bot.id, body) : await api.createBot(body);
      onSaved(saved);
      onClose();
    } catch (err) {
      setError(err instanceof Error ? err.message : "保存失败");
      setBusy(false);
    }
  };

  return (
    <Modal
      open={open}
      title={bot ? `编辑「${bot.name}」` : "新建 Bot"}
      onClose={() => {
        if (!busy) onClose();
      }}
      large
      footer={
        <>
          <Button variant="ghost" onClick={onClose} disabled={busy}>
            取消
          </Button>
          <Button
            variant="primary"
            onClick={() => void submit()}
            disabled={!name.trim() || busy}
            busy={busy}
          >
            {bot ? "保存" : "创建"}
          </Button>
        </>
      }
    >
      <div style={{ display: "flex", flexDirection: "column", gap: 16 }}>
        <div style={{ display: "flex", gap: 16, alignItems: "center" }}>
          <BotAvatar shape={shape} color={color} size={64} name={name || "新 Bot"} />
          <div style={{ flex: 1, minWidth: 0 }}>
            <Field label="名称" required hint="这个名字就是它的身份，开工记录里也这样显示。">
              <Input
                autoFocus
                value={name}
                maxLength={64}
                placeholder="例如：日报机器人"
                onChange={(event) => setName(event.target.value)}
              />
            </Field>
          </div>
        </div>

        <Field
          label="作业内容"
          hint="它的固定任务：定时触发时这段文字就是本轮的执行指令。"
        >
          <Textarea
            rows={4}
            value={description}
            maxLength={8000}
            placeholder="例如：汇总今天仓库里的新提交与失败用例，写一份简报放到 reports/。"
            onChange={(event) => setDescription(event.target.value)}
          />
        </Field>

        <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: 16 }}>
          <Field
            label="定时（cron）"
            hint="分 时 日 月 周，如 0 9 * * 1-5；留空表示只手动触发。"
          >
            <Input
              value={schedule}
              maxLength={64}
              placeholder="0 9 * * 1-5"
              onChange={(event) => setSchedule(event.target.value)}
            />
          </Field>
          <Field label="执行 Agent" hint="留空使用平台默认 runtime。">
            <select
              className="ap-select"
              value={agentId}
              onChange={(event) => setAgentId(event.target.value)}
              style={{ width: "100%" }}
            >
              <option value="">默认 Agent</option>
              {agents.map((agent) => (
                <option key={agent.id} value={agent.id}>
                  {agent.spec.name}
                  {agent.spec.runtime && agent.spec.runtime !== "dsh" ? ` · ${agent.spec.runtime}` : ""}
                </option>
              ))}
            </select>
          </Field>
        </div>

        <Field
          label="工作目录"
          hint="必须是绝对路径；留空使用默认目录（见卡片上的实际路径）。"
        >
          <Input
            value={workspace}
            maxLength={512}
            placeholder="/home/me/workspace/reports"
            onChange={(event) => setWorkspace(event.target.value)}
          />
        </Field>

        <Field label="权限模式" hint="Bot 默认无人值守；需要人工把关时改成正常或只读计划。">
          <Segmented value={permission} options={PERMISSION_OPTIONS} onChange={setPermission} />
        </Field>

        <div>
          <div style={{ fontSize: 12, color: "var(--tn-text-2)", marginBottom: 8 }}>头像形状</div>
          <div style={{ display: "grid", gridTemplateColumns: "repeat(7, 1fr)", gap: 8 }}>
            {AVATAR_SHAPES.map((item) => {
              const active = item === shape;
              return (
                <button
                  key={item}
                  type="button"
                  title={item}
                  onClick={() => setShape(item)}
                  style={{
                    display: "flex",
                    alignItems: "center",
                    justifyContent: "center",
                    height: 40,
                    borderRadius: 10,
                    background: active ? "var(--tn-panel-3)" : "transparent",
                    border: `1px solid ${active ? "var(--tn-accent)" : "var(--tn-line)"}`,
                    cursor: "pointer",
                  }}
                >
                  <BotAvatar shape={item} color={color} size={24} />
                </button>
              );
            })}
          </div>
        </div>

        <div>
          <div style={{ fontSize: 12, color: "var(--tn-text-2)", marginBottom: 8 }}>头像颜色</div>
          <div style={{ display: "flex", flexWrap: "wrap", gap: 8 }}>
            {AVATAR_COLORS.map((item) => {
              const active = item === color;
              return (
                <button
                  key={item}
                  type="button"
                  title={item}
                  aria-label={item}
                  onClick={() => setColor(item)}
                  style={{
                    width: 24,
                    height: 24,
                    borderRadius: 999,
                    padding: 0,
                    cursor: "pointer",
                    background: avatarColorHex(item),
                    border: `2px solid ${active ? "var(--tn-text)" : "transparent"}`,
                  }}
                />
              );
            })}
          </div>
        </div>

        {error ? <div style={{ fontSize: 12, color: "var(--tn-danger)" }}>{error}</div> : null}
      </div>
    </Modal>
  );
}

/* ---------------- 开工记录 ---------------- */

function RunsModal({ bot, onClose }: { bot: Bot | null; onClose: () => void }) {
  const [tasks, setTasks] = useState<Task[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    if (!bot) return;
    setLoading(true);
    try {
      setTasks(await api.listBotTasks(bot.id, { limit: 50 }));
      setError(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : "加载开工记录失败");
    } finally {
      setLoading(false);
    }
  }, [bot]);

  useEffect(() => {
    void load();
  }, [load]);

  return (
    <Modal
      open={bot !== null}
      title={`开工记录 · ${bot?.name ?? ""}`}
      onClose={onClose}
      large
      footer={
        <>
          <Button icon="refresh" onClick={() => void load()} busy={loading}>
            刷新
          </Button>
          <Button variant="ghost" onClick={onClose}>
            关闭
          </Button>
        </>
      }
    >
      <div style={{ display: "flex", flexDirection: "column", gap: 8 }}>
        {error ? <div style={{ fontSize: 12, color: "var(--tn-danger)" }}>{error}</div> : null}
        {loading && tasks.length === 0 ? (
          <div style={{ fontSize: 12, color: "var(--tn-text-4)" }}>加载中…</div>
        ) : tasks.length === 0 ? (
          <Empty icon="layers" title="还没有开工记录" desc="到点触发或手动开工后会出现在这里。" />
        ) : (
          tasks.map((task) => {
            const meta = TASK_BADGE[task.status] ?? TASK_BADGE.pending;
            return (
              <div
                key={task.id}
                style={{
                  display: "flex",
                  gap: 10,
                  alignItems: "flex-start",
                  padding: "8px 10px",
                  border: "1px solid var(--tn-line-soft)",
                  borderRadius: 8,
                }}
              >
                <Badge tone={meta.tone} dot>
                  {meta.label}
                </Badge>
                <div style={{ minWidth: 0, flex: 1 }}>
                  <div style={{ fontSize: 13, color: "var(--tn-text-2)", lineHeight: 1.6 }}>
                    {task.description.split("\n")[0] || "（无指令）"}
                  </div>
                  <div style={{ fontSize: 11, color: "var(--tn-text-4)" }}>
                    {formatTime(task.created_at)}
                    {task.result ? ` · ${task.result.slice(0, 80)}` : ""}
                  </div>
                </div>
              </div>
            );
          })
        )}
      </div>
    </Modal>
  );
}

/* ---------------- 卡片 ---------------- */

function Meta({ icon, children, title }: { icon: string; children: ReactNode; title?: string }) {
  return (
    <div
      title={title}
      style={{
        display: "flex",
        alignItems: "center",
        gap: 6,
        fontSize: 11,
        color: "var(--tn-text-4)",
        minWidth: 0,
      }}
    >
      <Icon name={icon} className="fx-icon-sm" />
      <span style={{ overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>
        {children}
      </span>
    </div>
  );
}

interface BotCardProps {
  bot: Bot;
  busy: boolean;
  onRun: () => void;
  onRuns: () => void;
  onEdit: () => void;
  onToggle: () => void;
  onDelete: () => void;
}

function BotCard({ bot, busy, onRun, onRuns, onEdit, onToggle, onDelete }: BotCardProps) {
  const active = bot.status === "active";
  return (
    <div
      style={{
        display: "flex",
        flexDirection: "column",
        gap: 10,
        padding: 14,
        border: "1px solid var(--tn-line)",
        borderRadius: 10,
        background: "var(--tn-panel)",
      }}
    >
      <div style={{ display: "flex", gap: 12, alignItems: "flex-start" }}>
        <BotAvatar shape={bot.avatar_shape} color={bot.avatar_color} size={40} name={bot.name} />
        <div style={{ minWidth: 0, flex: 1 }}>
          <div style={{ display: "flex", alignItems: "center", gap: 8, flexWrap: "wrap" }}>
            <span style={{ fontSize: 14, fontWeight: 600, color: "var(--tn-text)" }}>{bot.name}</span>
            <Badge tone={active ? "green" : "orange"} dot>
              {active ? "运行中" : "已暂停"}
            </Badge>
            {bot.schedule ? null : <Badge>仅手动</Badge>}
          </div>
          <div
            style={{
              marginTop: 4,
              fontSize: 12,
              color: "var(--tn-text-3)",
              lineHeight: 1.6,
              display: "-webkit-box",
              WebkitLineClamp: 2,
              WebkitBoxOrient: "vertical",
              overflow: "hidden",
            }}
          >
            {bot.description.trim() || "未填写作业内容（手动开工时需要临时指令）"}
          </div>
        </div>
      </div>

      <div style={{ display: "flex", flexDirection: "column", gap: 4 }}>
        <Meta icon="clock" title={bot.schedule ?? "未设置定时"}>
          {bot.schedule ? `定时 ${bot.schedule}` : "无定时"}
          {bot.next_run_at ? ` · 下次 ${formatTime(bot.next_run_at)}` : ""}
        </Meta>
        <Meta icon="folder" title={bot.workspace_path}>
          {bot.workspace_path}
        </Meta>
        <Meta icon="play" title={bot.last_run_at ?? undefined}>
          {bot.last_run_at ? `上次开工 ${formatTime(bot.last_run_at)}` : "还没开过工"}
        </Meta>
      </div>

      <div style={{ display: "flex", gap: 6, flexWrap: "wrap", marginTop: "auto" }}>
        <Button size="sm" variant="primary" icon="play" busy={busy} onClick={onRun}>
          立即开工
        </Button>
        <Button size="sm" variant="subtle" icon="layers" onClick={onRuns}>
          记录
        </Button>
        <Button size="sm" variant="ghost" icon="edit" onClick={onEdit}>
          编辑
        </Button>
        <Button size="sm" variant="ghost" icon={active ? "pause" : "play"} onClick={onToggle}>
          {active ? "暂停" : "恢复"}
        </Button>
        <Button size="sm" variant="quiet" icon="trash" onClick={onDelete}>
          删除
        </Button>
      </div>
    </div>
  );
}

/* ---------------- 页面 ---------------- */

type StatusFilter = "all" | "active" | "paused";

export function BotsPage({ navigate }: { navigate: (v: View) => void }) {
  const [bots, setBots] = useState<Bot[]>([]);
  const [filter, setFilter] = useState<StatusFilter>("all");
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [busyId, setBusyId] = useState<string | null>(null);
  const [formOpen, setFormOpen] = useState(false);
  const [editing, setEditing] = useState<Bot | null>(null);
  const [runsFor, setRunsFor] = useState<Bot | null>(null);

  const load = useCallback(async () => {
    try {
      setBots(await api.listBots());
      setError(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : "加载 Bot 失败");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const upsert = (bot: Bot) =>
    setBots((prev) => {
      const exists = prev.some((item) => item.id === bot.id);
      return exists ? prev.map((item) => (item.id === bot.id ? bot : item)) : [bot, ...prev];
    });

  const runNow = async (bot: Bot) => {
    setBusyId(bot.id);
    setNotice(null);
    setError(null);
    try {
      const outcome = await api.runBot(bot.id);
      upsert(outcome.bot);
      setNotice(`「${bot.name}」已开工：${outcome.task.description.split("\n")[0] || "默认作业"}`);
    } catch (err) {
      setError(err instanceof Error ? err.message : "开工失败");
    } finally {
      setBusyId(null);
    }
  };

  const toggle = async (bot: Bot) => {
    setBusyId(bot.id);
    setError(null);
    try {
      upsert(await api.updateBot(bot.id, { status: bot.status === "active" ? "paused" : "active" }));
    } catch (err) {
      setError(err instanceof Error ? err.message : "更新状态失败");
    } finally {
      setBusyId(null);
    }
  };

  const remove = async (bot: Bot) => {
    if (
      !window.confirm(
        `删除 Bot「${bot.name}」？配置与定时会被移除，历史开工记录保留，工作目录里的文件不受影响。`,
      )
    ) {
      return;
    }
    setBusyId(bot.id);
    setError(null);
    try {
      await api.deleteBot(bot.id);
      setBots((prev) => prev.filter((item) => item.id !== bot.id));
    } catch (err) {
      setError(err instanceof Error ? err.message : "删除失败");
    } finally {
      setBusyId(null);
    }
  };

  const visible = filter === "all" ? bots : bots.filter((bot) => bot.status === filter);

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 16 }}>
      <PageHead
        title="Bot 模式"
        desc="常驻在机器上的执行者：独立工作目录、按 cron 定时开工，也可以随时手动跑一次。"
      >
        <Button variant="quiet" icon="users" onClick={() => navigate("groups")}>
          群聊
        </Button>
        <Button
          variant="primary"
          icon="plus"
          onClick={() => {
            setEditing(null);
            setFormOpen(true);
          }}
        >
          新建 Bot
        </Button>
      </PageHead>

      {error ? (
        <div style={{ fontSize: 12, color: "var(--tn-danger)" }}>
          {error}
          <button
            type="button"
            onClick={() => setError(null)}
            style={{ marginLeft: 8, color: "var(--tn-text-3)", background: "transparent", cursor: "pointer" }}
          >
            知道了
          </button>
        </div>
      ) : null}
      {notice ? (
        <div style={{ fontSize: 12, color: "var(--tn-accent)" }}>
          {notice}
          <button
            type="button"
            onClick={() => setNotice(null)}
            style={{ marginLeft: 8, color: "var(--tn-text-3)", background: "transparent", cursor: "pointer" }}
          >
            知道了
          </button>
        </div>
      ) : null}

      <div style={{ maxWidth: 320 }}>
        <Segmented<StatusFilter>
          value={filter}
          options={[
            { value: "all", label: `全部 ${bots.length}` },
            { value: "active", label: "运行中" },
            { value: "paused", label: "已暂停" },
          ]}
          onChange={setFilter}
        />
      </div>

      {loading ? (
        <div style={{ fontSize: 12, color: "var(--tn-text-4)" }}>加载中…</div>
      ) : visible.length === 0 ? (
        <Empty
          icon="bot"
          title={bots.length === 0 ? "还没有 Bot" : "当前筛选下没有 Bot"}
          desc="创建一个常驻 Bot，让它按定时在独立目录里替你干活。"
        />
      ) : (
        <div
          style={{
            display: "grid",
            gridTemplateColumns: "repeat(auto-fill, minmax(340px, 1fr))",
            gap: 16,
          }}
        >
          {visible.map((bot) => (
            <BotCard
              key={bot.id}
              bot={bot}
              busy={busyId === bot.id}
              onRun={() => void runNow(bot)}
              onRuns={() => setRunsFor(bot)}
              onEdit={() => {
                setEditing(bot);
                setFormOpen(true);
              }}
              onToggle={() => void toggle(bot)}
              onDelete={() => void remove(bot)}
            />
          ))}
        </div>
      )}

      <BotFormDialog
        open={formOpen}
        bot={editing}
        onClose={() => setFormOpen(false)}
        onSaved={upsert}
      />
      <RunsModal bot={runsFor} onClose={() => setRunsFor(null)} />
    </div>
  );
}

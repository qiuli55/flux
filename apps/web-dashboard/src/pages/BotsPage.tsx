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
  BotMemory,
  BotMemoryChunk,
  BotMemoryDetail,
  BotMemoryRelevance,
  BotNotification,
  BotRoutine,
  BotTool,
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
import { Badge, Button, Empty, Field, Input, PageHead, Segmented, Switch, Textarea } from "../shell/ui";

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

/** 可触发 Bot 的事件（与后端 Events 常量一致，仅列出对 Bot 有意义的几类） */
const EVENT_OPTIONS: { value: string; label: string }[] = [
  { value: "task.completed", label: "任务完成" },
  { value: "task.failed", label: "任务失败" },
  { value: "workspace.applied", label: "改动落盘" },
  { value: "git.committed", label: "Git 提交" },
  { value: "dsh.run_completed", label: "Run 完成" },
  { value: "dsh.run_failed", label: "Run 失败" },
];

/** 多行 / 逗号分隔文本 → 去重列表（空 = 不限制，后端会把 [] 归一成 null） */
function splitList(text: string): string[] {
  return Array.from(
    new Set(
      text
        .split(/[\n,，]/)
        .map((item) => item.trim())
        .filter(Boolean),
    ),
  );
}

/** 可多选的标签按钮：事件、技能这类「从固定集合里挑」的配置共用 */
function ChipToggle({
  active,
  title,
  children,
  onClick,
}: {
  active: boolean;
  title?: string;
  children: ReactNode;
  onClick: () => void;
}) {
  return (
    <button
      type="button"
      title={title}
      onClick={onClick}
      style={{
        display: "inline-flex",
        alignItems: "center",
        gap: 4,
        padding: "4px 10px",
        borderRadius: 999,
        fontSize: 12,
        cursor: "pointer",
        background: active ? "var(--tn-panel-3)" : "transparent",
        border: `1px solid ${active ? "var(--tn-accent)" : "var(--tn-line)"}`,
        color: active ? "var(--tn-text)" : "var(--tn-text-3)",
      }}
    >
      {active ? <Icon name="check" className="fx-icon-sm" /> : null}
      {children}
    </button>
  );
}

/** 在两个列表之间开关某个值 */
function toggleItem(list: string[], item: string): string[] {
  return list.includes(item) ? list.filter((entry) => entry !== item) : [...list, item];
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
  const [threshold, setThreshold] = useState(3);
  // null = 无上限（后端同样把 0 视作无上限）
  const [tokenBudget, setTokenBudget] = useState<number | null>(null);
  const [agentId, setAgentId] = useState("");
  // 自动化相关的配置：失败通知 / 事件触发 / 技能与执行白名单
  const [notifyOnFailure, setNotifyOnFailure] = useState(true);
  const [triggers, setTriggers] = useState<string[]>([]);
  const [tools, setTools] = useState<string[]>([]);
  const [commands, setCommands] = useState("");
  const [paths, setPaths] = useState("");
  const [shape, setShape] = useState<string>(DEFAULT_AVATAR_SHAPE);
  const [color, setColor] = useState<string>(DEFAULT_AVATAR_COLOR);
  const [agents, setAgents] = useState<AgentHandle[]>([]);
  const [catalog, setCatalog] = useState<BotTool[]>([]);
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
    setThreshold(bot?.failure_threshold ?? 3);
    setTokenBudget(bot?.token_budget ?? null);
    setAgentId(bot?.agent_id ?? "");
    setNotifyOnFailure(bot?.notify_on_failure ?? true);
    setTriggers(bot?.event_triggers ?? []);
    setTools(bot?.allowed_tools ?? []);
    setCommands((bot?.allowed_commands ?? []).join("\n"));
    setPaths((bot?.allowed_paths ?? []).join("\n"));
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
    api
      .listBotTools()
      .then((list) => {
        if (alive) setCatalog(list);
      })
      .catch(() => {
        if (alive) setCatalog([]);
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
      failure_threshold: threshold,
      token_budget: tokenBudget,
      notify_on_failure: notifyOnFailure,
      event_triggers: triggers,
      allowed_tools: tools,
      allowed_commands: splitList(commands),
      allowed_paths: splitList(paths),
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

        <Field
          label="连续失败自动暂停"
          hint="连续失败达到这个次数就自动暂停，避免一直空跑；跑通一轮后计数清零。0 表示永不自动暂停。"
        >
          <Input
            type="number"
            min={0}
            max={20}
            value={`${threshold}`}
            onChange={(event) => {
              const next = Number(event.target.value);
              setThreshold(Number.isFinite(next) ? Math.min(20, Math.max(0, Math.trunc(next))) : 0);
            }}
            style={{ width: 120 }}
          />
        </Field>

        <Field
          label="每日 token 预算"
          hint="按 UTC 自然日重置；当天用量达到上限就停止开工。开启「无上限」表示不限制。"
        >
          <div style={{ display: "flex", alignItems: "center", gap: 10, flexWrap: "wrap" }}>
            <Input
              type="number"
              min={0}
              max={1_000_000_000}
              placeholder="不限制"
              value={tokenBudget === null ? "" : `${tokenBudget}`}
              onChange={(event) => {
                const raw = event.target.value.trim();
                const next = Number(raw);
                // 0 和空都表示无上限，与后端语义一致
                setTokenBudget(
                  raw && Number.isFinite(next)
                    ? Math.min(1_000_000_000, Math.max(0, Math.trunc(next))) || null
                    : null,
                );
              }}
              style={{ width: 160 }}
            />
            <label
              style={{
                display: "flex",
                alignItems: "center",
                gap: 6,
                fontSize: 12,
                color: "var(--tn-text-2)",
              }}
            >
              <Switch
                on={tokenBudget === null}
                onChange={(on) => setTokenBudget(on ? null : 100_000)}
              />
              无上限
            </label>
          </div>
        </Field>

        <Field label="失败主动通知" hint="开工失败或触发自动暂停时，在 Bot 页面顶部留一条通知。">
          <label
            style={{
              display: "flex",
              alignItems: "center",
              gap: 8,
              fontSize: 12,
              color: "var(--tn-text-2)",
            }}
          >
            <Switch on={notifyOnFailure} onChange={setNotifyOnFailure} />
            {notifyOnFailure ? "失败时通知我" : "不通知"}
          </label>
        </Field>

        <Field label="事件触发" hint="除定时之外，这些事件发生时也会开工；全不选表示不响应事件。">
          <div style={{ display: "flex", flexWrap: "wrap", gap: 8 }}>
            {EVENT_OPTIONS.map((option) => (
              <ChipToggle
                key={option.value}
                active={triggers.includes(option.value)}
                onClick={() => setTriggers((prev) => toggleItem(prev, option.value))}
              >
                {option.label}
              </ChipToggle>
            ))}
          </div>
        </Field>

        <Field label="可用技能" hint="只勾选它允许调用的工具；全不选表示沿用默认工具面。">
          {catalog.length === 0 ? (
            <div style={{ fontSize: 12, color: "var(--tn-text-4)" }}>技能清单暂不可用</div>
          ) : (
            <div
              style={{
                display: "flex",
                flexWrap: "wrap",
                gap: 8,
                maxHeight: 180,
                overflowY: "auto",
              }}
            >
              {catalog.map((tool) => (
                <ChipToggle
                  key={tool.name}
                  active={tools.includes(tool.name)}
                  title={tool.description || tool.title}
                  onClick={() => setTools((prev) => toggleItem(prev, tool.name))}
                >
                  {tool.title}
                </ChipToggle>
              ))}
            </div>
          )}
        </Field>

        <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: 16 }}>
          <Field label="允许的命令" hint="每行一个命令首 token，如 git、npm；留空不限制。">
            <Textarea
              rows={3}
              value={commands}
              placeholder={"git\nnpm\npython3"}
              onChange={(event) => setCommands(event.target.value)}
            />
          </Field>
          <Field label="允许的路径" hint="每行一个相对工作目录的路径前缀；留空不限制。">
            <Textarea
              rows={3}
              value={paths}
              placeholder={"reports\nsrc"}
              onChange={(event) => setPaths(event.target.value)}
            />
          </Field>
        </div>

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

/* ---------------- 例程 ---------------- */

interface RoutineDraft {
  name: string;
  instruction: string;
  schedule: string;
  enabled: boolean;
}

const EMPTY_ROUTINE: RoutineDraft = { name: "", instruction: "", schedule: "", enabled: true };

/**
 * 一个 Bot 下可以挂多个例程：每个例程有自己的指令和 cron，
 * 到点各自开工，互不覆盖 Bot 主定时。
 */
function RoutinesModal({ bot, onClose }: { bot: Bot | null; onClose: () => void }) {
  const [items, setItems] = useState<BotRoutine[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [draft, setDraft] = useState<RoutineDraft>(EMPTY_ROUTINE);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [busyId, setBusyId] = useState<string | null>(null);

  const load = useCallback(async () => {
    if (!bot) return;
    setLoading(true);
    try {
      setItems(await api.listBotRoutines(bot.id));
      setError(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : "加载例程失败");
    } finally {
      setLoading(false);
    }
  }, [bot]);

  useEffect(() => {
    setDraft(EMPTY_ROUTINE);
    setEditingId(null);
    setError(null);
    void load();
  }, [load]);

  const reset = () => {
    setDraft(EMPTY_ROUTINE);
    setEditingId(null);
  };

  const submit = async () => {
    if (!bot || saving) return;
    const name = draft.name.trim();
    if (!name) return;
    setSaving(true);
    setError(null);
    const body = {
      name,
      instruction: draft.instruction.trim(),
      schedule: draft.schedule.trim() || null,
      enabled: draft.enabled,
    };
    try {
      if (editingId) {
        await api.updateBotRoutine(bot.id, editingId, body);
      } else {
        await api.createBotRoutine(bot.id, body);
      }
      reset();
      await load();
    } catch (err) {
      setError(err instanceof Error ? err.message : "保存例程失败");
    } finally {
      setSaving(false);
    }
  };

  const startEdit = (routine: BotRoutine) => {
    setEditingId(routine.id);
    setDraft({
      name: routine.name,
      instruction: routine.instruction,
      schedule: routine.schedule ?? "",
      enabled: routine.enabled,
    });
  };

  const toggleEnabled = async (routine: BotRoutine) => {
    if (!bot) return;
    setBusyId(routine.id);
    setError(null);
    try {
      await api.updateBotRoutine(bot.id, routine.id, { enabled: !routine.enabled });
      await load();
    } catch (err) {
      setError(err instanceof Error ? err.message : "更新例程失败");
    } finally {
      setBusyId(null);
    }
  };

  const remove = async (routine: BotRoutine) => {
    if (!bot) return;
    if (!window.confirm(`删除例程「${routine.name}」？已产生的开工记录会保留。`)) return;
    setBusyId(routine.id);
    setError(null);
    try {
      await api.deleteBotRoutine(bot.id, routine.id);
      if (editingId === routine.id) reset();
      await load();
    } catch (err) {
      setError(err instanceof Error ? err.message : "删除例程失败");
    } finally {
      setBusyId(null);
    }
  };

  return (
    <Modal
      open={bot !== null}
      title={`例程 · ${bot?.name ?? ""}`}
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
      <div style={{ display: "flex", flexDirection: "column", gap: 14 }}>
        {error ? <div style={{ fontSize: 12, color: "var(--tn-danger)" }}>{error}</div> : null}

        <div
          style={{
            display: "flex",
            flexDirection: "column",
            gap: 12,
            padding: 12,
            border: "1px solid var(--tn-line-soft)",
            borderRadius: 10,
          }}
        >
          <div style={{ fontSize: 12, color: "var(--tn-text-2)" }}>
            {editingId ? "编辑例程" : "新建例程"}
          </div>
          <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: 12 }}>
            <Field label="名称" required>
              <Input
                value={draft.name}
                maxLength={64}
                placeholder="例如：每日简报"
                onChange={(event) => setDraft((prev) => ({ ...prev, name: event.target.value }))}
              />
            </Field>
            <Field label="定时（cron）" hint="留空表示只手动触发。">
              <Input
                value={draft.schedule}
                maxLength={64}
                placeholder="0 9 * * 1-5"
                onChange={(event) => setDraft((prev) => ({ ...prev, schedule: event.target.value }))}
              />
            </Field>
          </div>
          <Field label="例程指令" hint="这一段就是该例程开工时的执行指令。">
            <Textarea
              rows={3}
              value={draft.instruction}
              maxLength={8000}
              placeholder="例如：把今天的提交整理成简报，写到 reports/daily.md。"
              onChange={(event) => setDraft((prev) => ({ ...prev, instruction: event.target.value }))}
            />
          </Field>
          <div style={{ display: "flex", alignItems: "center", gap: 10 }}>
            <label
              style={{
                display: "flex",
                alignItems: "center",
                gap: 6,
                fontSize: 12,
                color: "var(--tn-text-2)",
              }}
            >
              <Switch
                on={draft.enabled}
                onChange={(enabled) => setDraft((prev) => ({ ...prev, enabled }))}
              />
              启用
            </label>
            <div style={{ marginLeft: "auto", display: "flex", gap: 8 }}>
              {editingId ? (
                <Button size="sm" variant="ghost" onClick={reset} disabled={saving}>
                  取消编辑
                </Button>
              ) : null}
              <Button
                size="sm"
                variant="primary"
                icon={editingId ? "check" : "plus"}
                busy={saving}
                disabled={!draft.name.trim()}
                onClick={() => void submit()}
              >
                {editingId ? "保存" : "添加"}
              </Button>
            </div>
          </div>
        </div>

        {loading && items.length === 0 ? (
          <div style={{ fontSize: 12, color: "var(--tn-text-4)" }}>加载中…</div>
        ) : items.length === 0 ? (
          <Empty
            icon="clock"
            title="还没有例程"
            desc="加一个例程，让它按自己的 cron 和指令独立开工。"
          />
        ) : (
          items.map((routine) => (
            <div
              key={routine.id}
              style={{
                display: "flex",
                flexDirection: "column",
                gap: 6,
                padding: "10px 12px",
                border: "1px solid var(--tn-line-soft)",
                borderRadius: 8,
              }}
            >
              <div style={{ display: "flex", alignItems: "center", gap: 8, flexWrap: "wrap" }}>
                <span style={{ fontSize: 13, fontWeight: 600, color: "var(--tn-text)" }}>{routine.name}</span>
                <Badge tone={routine.enabled ? "green" : "default"} dot>
                  {routine.enabled ? "启用" : "停用"}
                </Badge>
                <Badge tone="blue">{routine.schedule ? `定时 ${routine.schedule}` : "仅手动"}</Badge>
                {routine.next_run_at ? (
                  <span style={{ fontSize: 11, color: "var(--tn-text-4)" }}>
                    下次 {formatTime(routine.next_run_at)}
                  </span>
                ) : null}
              </div>
              <div style={{ fontSize: 12, color: "var(--tn-text-3)", lineHeight: 1.6, whiteSpace: "pre-wrap" }}>
                {routine.instruction.trim() || "（未填写指令）"}
              </div>
              <div style={{ display: "flex", gap: 6, alignItems: "center", marginTop: 2 }}>
                <Button
                  size="sm"
                  variant="ghost"
                  icon="edit"
                  onClick={() => startEdit(routine)}
                  disabled={busyId === routine.id}
                >
                  编辑
                </Button>
                <Button
                  size="sm"
                  variant="ghost"
                  icon={routine.enabled ? "pause" : "play"}
                  busy={busyId === routine.id}
                  onClick={() => void toggleEnabled(routine)}
                >
                  {routine.enabled ? "停用" : "启用"}
                </Button>
                <Button
                  size="sm"
                  variant="quiet"
                  icon="trash"
                  onClick={() => void remove(routine)}
                  disabled={busyId === routine.id}
                >
                  删除
                </Button>
              </div>
            </div>
          ))
        )}
      </div>
    </Modal>
  );
}

/* ---------------- 长期记忆 ---------------- */

const RELEVANCE_LABEL: Record<BotMemoryRelevance, string> = {
  direct: "直接相关",
  indirect: "间接相关",
};

const KIND_LABEL: Record<BotMemory["kind"], string> = {
  preference: "偏好",
  fact: "事实",
  decision: "决定",
  commitment: "承诺",
  summary: "摘要",
};

const SOURCE_LABEL: Record<BotMemory["source"], string> = {
  group: "群聊",
  chat: "对话",
  manual: "手动",
};

type RelevanceFilter = "all" | BotMemoryRelevance;

function MemoriesModal({ bot, onClose }: { bot: Bot | null; onClose: () => void }) {
  const [filter, setFilter] = useState<RelevanceFilter>("all");
  const [items, setItems] = useState<BotMemory[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [keyword, setKeyword] = useState("");
  const [hits, setHits] = useState<BotMemoryChunk[] | null>(null);
  const [searching, setSearching] = useState(false);
  const [detailId, setDetailId] = useState<string | null>(null);
  const [detail, setDetail] = useState<BotMemoryDetail | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);

  const load = useCallback(async () => {
    if (!bot) return;
    setLoading(true);
    try {
      setItems(
        await api.listBotMemories(bot.id, {
          relevance: filter === "all" ? undefined : filter,
          limit: 200,
        }),
      );
      setError(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : "加载记忆失败");
    } finally {
      setLoading(false);
    }
  }, [bot, filter]);

  useEffect(() => {
    setFilter("all");
    setKeyword("");
    setHits(null);
    setDetailId(null);
    setDetail(null);
  }, [bot]);

  useEffect(() => {
    void load();
  }, [load]);

  const search = async () => {
    const q = keyword.trim();
    if (!bot || !q || searching) return;
    setSearching(true);
    try {
      setHits(await api.searchBotMemories(bot.id, q, 50));
      setError(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : "检索失败");
    } finally {
      setSearching(false);
    }
  };

  const toggleDetail = async (memoryId: string) => {
    if (!bot) return;
    if (detailId === memoryId) {
      setDetailId(null);
      setDetail(null);
      return;
    }
    setDetailId(memoryId);
    setDetail(null);
    setDetailLoading(true);
    try {
      setDetail(await api.getBotMemory(bot.id, memoryId));
    } catch (err) {
      setError(err instanceof Error ? err.message : "加载记忆详情失败");
      setDetailId(null);
    } finally {
      setDetailLoading(false);
    }
  };

  const remove = async (memory: BotMemory) => {
    if (!bot) return;
    if (!window.confirm("删除这条记忆？对应的全文副本会一并移除。")) return;
    try {
      await api.deleteBotMemory(bot.id, memory.id);
      if (detailId === memory.id) {
        setDetailId(null);
        setDetail(null);
      }
      if (hits) setHits(null);
      await load();
    } catch (err) {
      setError(err instanceof Error ? err.message : "删除失败");
    }
  };

  const clearAll = async () => {
    if (!bot) return;
    if (!window.confirm(`清空「${bot.name}」的全部长期记忆？此操作不可撤销。`)) return;
    try {
      await api.clearBotMemories(bot.id);
      setKeyword("");
      setHits(null);
      setDetailId(null);
      setDetail(null);
      await load();
    } catch (err) {
      setError(err instanceof Error ? err.message : "清空失败");
    }
  };

  const renderDetail = () => {
    if (detailLoading) {
      return <div style={{ fontSize: 12, color: "var(--tn-text-4)" }}>加载中…</div>;
    }
    if (!detail) return null;
    return (
      <div
        style={{
          marginTop: 8,
          paddingTop: 8,
          borderTop: "1px dashed var(--tn-line-soft)",
          display: "flex",
          flexDirection: "column",
          gap: 8,
        }}
      >
        <div style={{ fontSize: 12, color: "var(--tn-text-2)", whiteSpace: "pre-wrap", lineHeight: 1.7 }}>
          {detail.content}
        </div>
        <div style={{ fontSize: 11, color: "var(--tn-text-4)" }}>
          全文副本 {detail.chunks.length} 条
        </div>
        {detail.chunks.map((chunk) => (
          <div
            key={chunk.id}
            style={{
              padding: "6px 8px",
              borderRadius: 6,
              background: "var(--tn-panel-3)",
              fontSize: 12,
              color: "var(--tn-text-3)",
              whiteSpace: "pre-wrap",
              lineHeight: 1.6,
            }}
          >
            {chunk.content}
            <div style={{ fontSize: 10, color: "var(--tn-text-4)", marginTop: 2 }}>
              {SOURCE_LABEL[chunk.source]} · {formatTime(chunk.created_at)}
            </div>
          </div>
        ))}
      </div>
    );
  };

  const rowStyle = {
    display: "flex",
    flexDirection: "column" as const,
    gap: 6,
    padding: "10px 12px",
    border: "1px solid var(--tn-line-soft)",
    borderRadius: 8,
    cursor: "pointer",
  };

  return (
    <Modal
      open={bot !== null}
      title={`长期记忆 · ${bot?.name ?? ""}`}
      onClose={onClose}
      large
      footer={
        <>
          <Button icon="refresh" onClick={() => void load()} busy={loading}>
            刷新
          </Button>
          <Button variant="quiet" icon="trash" onClick={() => void clearAll()}>
            清空记忆
          </Button>
          <Button variant="ghost" onClick={onClose}>
            关闭
          </Button>
        </>
      }
    >
      <div style={{ display: "flex", flexDirection: "column", gap: 12 }}>
        {error ? <div style={{ fontSize: 12, color: "var(--tn-danger)" }}>{error}</div> : null}

        <div style={{ display: "flex", gap: 8, alignItems: "center" }}>
          <div style={{ flex: 1, minWidth: 0 }}>
            <Input
              icon="search"
              value={keyword}
              placeholder="搜全文：当时到底怎么说的…"
              onChange={(event) => setKeyword(event.target.value)}
              onKeyDown={(event) => {
                if (event.key === "Enter") void search();
              }}
            />
          </div>
          <Button
            size="sm"
            variant="subtle"
            busy={searching}
            disabled={!keyword.trim()}
            onClick={() => void search()}
          >
            搜索
          </Button>
        </div>

        {hits ? (
          <div style={{ display: "flex", gap: 8, alignItems: "center" }}>
            <Badge tone="blue">全文命中 {hits.length}</Badge>
            <button
              type="button"
              onClick={() => setHits(null)}
              style={{ fontSize: 12, color: "var(--tn-accent)", background: "transparent", cursor: "pointer" }}
            >
              返回摘要列表
            </button>
          </div>
        ) : (
          <div style={{ maxWidth: 360 }}>
            <Segmented<RelevanceFilter>
              value={filter}
              options={[
                { value: "all", label: "全部" },
                { value: "direct", label: "直接相关" },
                { value: "indirect", label: "间接相关" },
              ]}
              onChange={setFilter}
            />
          </div>
        )}

        {hits ? (
          hits.length === 0 ? (
            <Empty icon="search" title="没有命中原文" desc="换一个关键词，或返回摘要列表看看。" />
          ) : (
            hits.map((chunk) => (
              <div key={chunk.id} style={rowStyle} onClick={() => void toggleDetail(chunk.memory_id)}>
                <div style={{ fontSize: 12, color: "var(--tn-text-2)", whiteSpace: "pre-wrap", lineHeight: 1.6 }}>
                  {chunk.content}
                </div>
                <div style={{ fontSize: 11, color: "var(--tn-text-4)" }}>
                  {SOURCE_LABEL[chunk.source]}
                  {chunk.created_at ? ` · ${formatTime(chunk.created_at)}` : ""}
                </div>
                {detailId === chunk.memory_id ? renderDetail() : null}
              </div>
            ))
          )
        ) : loading && items.length === 0 ? (
          <div style={{ fontSize: 12, color: "var(--tn-text-4)" }}>加载中…</div>
        ) : items.length === 0 ? (
          <Empty
            icon="brain"
            title="还没有长期记忆"
            desc="在群聊里和它协作、或直接对话之后，与它相关的部分会沉淀到这里。"
          />
        ) : (
          items.map((memory) => (
            <div key={memory.id} style={rowStyle} onClick={() => void toggleDetail(memory.id)}>
              <div style={{ display: "flex", gap: 6, alignItems: "center", flexWrap: "wrap" }}>
                <Badge tone={memory.relevance === "direct" ? "brand" : "blue"}>
                  {RELEVANCE_LABEL[memory.relevance]}
                </Badge>
                <Badge>{KIND_LABEL[memory.kind]}</Badge>
                {memory.topic ? <Badge>{memory.topic}</Badge> : null}
              </div>
              <div
                style={{
                  fontSize: 12,
                  color: "var(--tn-text-2)",
                  lineHeight: 1.6,
                  display: "-webkit-box",
                  WebkitLineClamp: 3,
                  WebkitBoxOrient: "vertical",
                  overflow: "hidden",
                  whiteSpace: "pre-wrap",
                }}
              >
                {memory.content}
              </div>
              <div style={{ display: "flex", gap: 10, alignItems: "center", fontSize: 11, color: "var(--tn-text-4)" }}>
                <span>{SOURCE_LABEL[memory.source]}</span>
                {memory.source_group_name ? <span>{memory.source_group_name}</span> : null}
                <span>v{memory.version}</span>
                <span>{memory.chunk_count} 条原文</span>
                <span>权重 {memory.weight.toFixed(2)}</span>
                {memory.self_authored ? <span>自己说的</span> : null}
                <button
                  type="button"
                  title="删除这条记忆"
                  onClick={(event) => {
                    event.stopPropagation();
                    void remove(memory);
                  }}
                  style={{
                    marginLeft: "auto",
                    background: "transparent",
                    color: "var(--tn-text-4)",
                    cursor: "pointer",
                  }}
                >
                  <Icon name="trash" className="fx-icon-sm" />
                </button>
              </div>
              {detailId === memory.id ? renderDetail() : null}
            </div>
          ))
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
  onRoutines: () => void;
  onMemories: () => void;
  onEdit: () => void;
  onToggle: () => void;
  onDelete: () => void;
}

function BotCard({
  bot,
  busy,
  onRun,
  onRuns,
  onRoutines,
  onMemories,
  onEdit,
  onToggle,
  onDelete,
}: BotCardProps) {
  const active = bot.status === "active";
  const autoPaused = !active && Boolean(bot.paused_reason);
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
              {active ? "运行中" : autoPaused ? "自动暂停" : "已暂停"}
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
        <Meta icon="activity" title={`累计开工 ${bot.runs_total} 次，失败 ${bot.runs_failed} 次`}>
          {bot.runs_total > 0
            ? `开工 ${bot.runs_total} 次 · 失败 ${bot.runs_failed}`
            : "暂无运行统计"}
          {bot.consecutive_failures > 0 ? ` · 连续失败 ${bot.consecutive_failures}` : ""}
        </Meta>
        <Meta
          icon="zap"
          title={bot.token_budget ? "每日 token 预算，按 UTC 自然日重置" : "未设置每日 token 预算"}
        >
          今日已用 {bot.tokens_used_today.toLocaleString()}
          {bot.token_budget ? ` / ${bot.token_budget.toLocaleString()}` : " · 无上限"}
        </Meta>
        {bot.event_triggers.length > 0 ? (
          <Meta icon="bell" title={bot.event_triggers.join("、")}>
            事件触发 {bot.event_triggers.length} 个
          </Meta>
        ) : null}
      </div>

      {bot.paused_reason ? (
        <div style={{ fontSize: 11, color: "var(--tn-danger)", lineHeight: 1.6 }}>
          自动暂停：{bot.paused_reason}
        </div>
      ) : bot.last_error ? (
        <div
          title={bot.last_error}
          style={{
            fontSize: 11,
            color: "var(--tn-danger)",
            lineHeight: 1.6,
            display: "-webkit-box",
            WebkitLineClamp: 2,
            WebkitBoxOrient: "vertical",
            overflow: "hidden",
          }}
        >
          最近失败：{bot.last_error}
        </div>
      ) : null}

      <div style={{ display: "flex", gap: 6, flexWrap: "wrap", marginTop: "auto" }}>
        <Button size="sm" variant="primary" icon="play" busy={busy} onClick={onRun}>
          立即开工
        </Button>
        <Button size="sm" variant="subtle" icon="layers" onClick={onRuns}>
          记录
        </Button>
        <Button size="sm" variant="subtle" icon="clock" onClick={onRoutines}>
          例程
        </Button>
        <Button size="sm" variant="subtle" icon="brain" onClick={onMemories}>
          记忆
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
  const [routinesFor, setRoutinesFor] = useState<Bot | null>(null);
  const [memoriesFor, setMemoriesFor] = useState<Bot | null>(null);
  const [notifications, setNotifications] = useState<BotNotification[]>([]);

  const loadNotifications = useCallback(async () => {
    try {
      setNotifications(await api.listBotNotifications({ unreadOnly: true, limit: 20 }));
    } catch {
      // 通知是辅助信息，拉取失败不打断页面
      setNotifications([]);
    }
  }, []);

  const load = useCallback(async () => {
    try {
      setBots(await api.listBots());
      setError(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : "加载 Bot 失败");
    } finally {
      setLoading(false);
    }
    await loadNotifications();
  }, [loadNotifications]);

  useEffect(() => {
    void load();
  }, [load]);

  const readNotification = async (item: BotNotification) => {
    try {
      await api.markBotNotificationRead(item.id);
      setNotifications((prev) => prev.filter((entry) => entry.id !== item.id));
    } catch (err) {
      setError(err instanceof Error ? err.message : "标记通知已读失败");
    }
  };

  const readAllNotifications = async () => {
    try {
      await api.markAllBotNotificationsRead();
      setNotifications([]);
    } catch (err) {
      setError(err instanceof Error ? err.message : "标记通知已读失败");
    }
  };

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

      {notifications.length > 0 ? (
        <div
          style={{
            display: "flex",
            flexDirection: "column",
            gap: 10,
            padding: 12,
            border: "1px solid var(--tn-line)",
            borderRadius: 10,
            background: "var(--tn-panel)",
          }}
        >
          <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
            <Icon name="bell" className="fx-icon-sm" />
            <span style={{ fontSize: 13, fontWeight: 600, color: "var(--tn-text)" }}>Bot 通知</span>
            <Badge tone="red">{notifications.length} 条未读</Badge>
            <Button
              size="sm"
              variant="ghost"
              style={{ marginLeft: "auto" }}
              onClick={() => void readAllNotifications()}
            >
              全部已读
            </Button>
          </div>
          {notifications.map((item) => (
            <div
              key={item.id}
              style={{
                display: "flex",
                alignItems: "flex-start",
                gap: 8,
                fontSize: 12,
                lineHeight: 1.6,
              }}
            >
              <div style={{ flex: 1, minWidth: 0 }}>
                <div style={{ color: item.level === "error" ? "var(--tn-danger)" : "var(--tn-text)" }}>
                  {item.title}
                </div>
                {item.body ? (
                  <div style={{ color: "var(--tn-text-3)", whiteSpace: "pre-wrap" }}>{item.body}</div>
                ) : null}
                <div style={{ fontSize: 11, color: "var(--tn-text-4)" }}>
                  {item.bot_name}
                  {item.created_at ? ` · ${formatTime(item.created_at)}` : ""}
                </div>
              </div>
              <Button size="sm" variant="ghost" onClick={() => void readNotification(item)}>
                知道了
              </Button>
            </div>
          ))}
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
              onRoutines={() => setRoutinesFor(bot)}
              onMemories={() => setMemoriesFor(bot)}
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
      <RoutinesModal bot={routinesFor} onClose={() => setRoutinesFor(null)} />
      <MemoriesModal bot={memoriesFor} onClose={() => setMemoriesFor(null)} />
    </div>
  );
}

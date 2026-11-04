/**
 * 内部群聊：把多个对话（bot）拉进一个群，用户发言后它们依次决定要不要说话。
 *
 * 前端刻意不做 SSE 流式：群聊里多条消息同时逐字输出太拥挤，
 * 这里按 seq 轮询拉整条消息（2s 一拍），新增的消息整块出现。
 */
import { useCallback, useEffect, useMemo, useRef, useState } from "react";

import { api } from "../api/client";
import type { ChatGroup, ChatGroupMember, ChatGroupMessage, Task } from "../api/types";
import { BotAvatar } from "../components/group/BotAvatar";
import { CreateBotDialog } from "../components/group/CreateBotDialog";
import { Modal } from "../shell/Overlay";
import { Badge, Button, Empty, Field, Input, PageHead, Segmented, Textarea } from "../shell/ui";

/** 轮询间隔：群消息量不大，2s 足够跟手且不会把后端打满。 */
const POLL_MS = 2000;
/** 发言后「讨论中」的近似窗口（后端暂无 epoch 状态查询接口）。 */
const PENDING_MS = 90_000;

function formatTime(iso: string): string {
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return "";
  const sameDay = date.toDateString() === new Date().toDateString();
  const clock = `${`${date.getHours()}`.padStart(2, "0")}:${`${date.getMinutes()}`.padStart(2, "0")}`;
  return sameDay ? clock : `${date.getMonth() + 1}/${date.getDate()} ${clock}`;
}

function mergeMessages(prev: ChatGroupMessage[], incoming: ChatGroupMessage[]): ChatGroupMessage[] {
  if (incoming.length === 0) return prev;
  const seen = new Set(prev.map((item) => item.id));
  const next = [...prev];
  for (const message of incoming) {
    if (seen.has(message.id)) continue;
    seen.add(message.id);
    next.push(message);
  }
  next.sort((a, b) => a.seq - b.seq);
  return next;
}

function botLabel(task: Task): string {
  const name = (task.bot_name ?? "").trim();
  return name || task.description.trim().slice(0, 24) || "未命名 Bot";
}

/* ---------------- 消息行 ---------------- */

function MessageRow({ message, member }: { message: ChatGroupMessage; member?: ChatGroupMember }) {
  if (message.role === "system") {
    return (
      <div style={{ display: "flex", justifyContent: "center", padding: "4px 0" }}>
        <span style={{ fontSize: 11, color: "var(--tn-text-4)" }}>{message.content}</span>
      </div>
    );
  }

  if (message.role === "user") {
    return (
      <div style={{ display: "flex", justifyContent: "flex-end" }}>
        <div style={{ maxWidth: "76%", display: "flex", flexDirection: "column", alignItems: "flex-end", gap: 4 }}>
          <span style={{ fontSize: 11, color: "var(--tn-text-4)" }}>
            {message.author_name || "我"} · {formatTime(message.created_at)}
          </span>
          <div
            style={{
              padding: "9px 12px",
              borderRadius: 10,
              border: "1px solid var(--tn-accent-line)",
              background: "var(--tn-accent-soft)",
              color: "var(--tn-text)",
              fontSize: 13,
              lineHeight: 1.65,
              whiteSpace: "pre-wrap",
              wordBreak: "break-word",
            }}
          >
            {message.content}
          </div>
        </div>
      </div>
    );
  }

  return (
    <div style={{ display: "flex", gap: 10 }}>
      <BotAvatar
        shape={member?.avatar_shape}
        color={member?.avatar_color}
        size={30}
        name={member?.name ?? message.author_name}
      />
      <div style={{ minWidth: 0, flex: 1, display: "flex", flexDirection: "column", gap: 4 }}>
        <span style={{ fontSize: 11, color: "var(--tn-text-3)" }}>
          {member?.name || message.author_name || "Bot"} · {formatTime(message.created_at)}
        </span>
        <div
          style={{
            alignSelf: "flex-start",
            maxWidth: "100%",
            padding: "9px 12px",
            borderRadius: 10,
            border: "1px solid var(--tn-line)",
            background: "var(--tn-panel)",
            color: "var(--tn-text-2)",
            fontSize: 13,
            lineHeight: 1.7,
            whiteSpace: "pre-wrap",
            wordBreak: "break-word",
          }}
        >
          {message.content}
        </div>
      </div>
    </div>
  );
}

/* ---------------- 建群弹窗 ---------------- */

interface CreateGroupDialogProps {
  open: boolean;
  onClose: () => void;
  onCreated: (group: ChatGroup) => void;
}

function CreateGroupDialog({ open, onClose, onCreated }: CreateGroupDialogProps) {
  const [name, setName] = useState("");
  const [description, setDescription] = useState("");
  const [rounds, setRounds] = useState("2");
  const [bots, setBots] = useState<Task[]>([]);
  const [picked, setPicked] = useState<string[]>([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!open) return;
    setName("");
    setDescription("");
    setRounds("2");
    setPicked([]);
    setBusy(false);
    setError(null);
    let alive = true;
    api
      .listBotCandidates()
      .then((list) => {
        if (alive) setBots(list);
      })
      .catch(() => {
        if (alive) setBots([]);
      });
    return () => {
      alive = false;
    };
  }, [open]);

  const toggle = (taskId: string) =>
    setPicked((prev) =>
      prev.includes(taskId) ? prev.filter((item) => item !== taskId) : [...prev, taskId],
    );

  const submit = async () => {
    const trimmed = name.trim();
    if (!trimmed || busy) return;
    setBusy(true);
    setError(null);
    try {
      const group = await api.createGroup({
        name: trimmed,
        description: description.trim() || null,
        max_rounds: Number(rounds),
        member_ids: picked,
      });
      onCreated(group);
      onClose();
    } catch (err) {
      setError(err instanceof Error ? err.message : "创建失败");
      setBusy(false);
    }
  };

  return (
    <Modal
      open={open}
      title="新建群聊"
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
            创建
          </Button>
        </>
      }
    >
      <div style={{ display: "flex", flexDirection: "column", gap: 16 }}>
        <Field label="群名" required>
          <Input
            autoFocus
            value={name}
            maxLength={128}
            placeholder="例如：内容工作室"
            onChange={(event) => setName(event.target.value)}
          />
        </Field>
        <Field label="群说明" hint="会写进每个 bot 的上下文，用来告诉它们这个群是干什么的。">
          <Input
            value={description}
            maxLength={512}
            placeholder="例如：小红书每日发布与流量复盘"
            onChange={(event) => setDescription(event.target.value)}
          />
        </Field>
        <Field label="发言轮数闸门" hint="每收到一条用户消息，最多来回这么多轮就停。">
          <Segmented
            value={rounds}
            options={[
              { value: "1", label: "1 轮" },
              { value: "2", label: "2 轮" },
              { value: "3", label: "3 轮" },
            ]}
            onChange={setRounds}
          />
        </Field>
        <Field label={`成员（已选 ${picked.length} 个）`}>
          {bots.length === 0 ? (
            <div style={{ fontSize: 12, color: "var(--tn-text-4)" }}>还没有可加入的 bot</div>
          ) : (
            <div
              style={{
                display: "flex",
                flexDirection: "column",
                gap: 4,
                maxHeight: 240,
                overflowY: "auto",
                border: "1px solid var(--tn-line)",
                borderRadius: 6,
                padding: 6,
              }}
            >
              {bots.map((bot) => {
                const active = picked.includes(bot.id);
                return (
                  <button
                    key={bot.id}
                    type="button"
                    onClick={() => toggle(bot.id)}
                    style={{
                      display: "flex",
                      alignItems: "center",
                      gap: 10,
                      padding: "6px 8px",
                      borderRadius: 6,
                      textAlign: "left",
                      background: active ? "var(--tn-accent-soft)" : "transparent",
                      border: `1px solid ${active ? "var(--tn-accent-line)" : "transparent"}`,
                      cursor: "pointer",
                    }}
                  >
                    <BotAvatar
                      shape={bot.avatar_shape}
                      color={bot.avatar_color}
                      size={26}
                      name={botLabel(bot)}
                    />
                    <span style={{ fontSize: 13, color: active ? "var(--tn-text)" : "var(--tn-text-2)" }}>
                      {botLabel(bot)}
                    </span>
                    {active ? <span style={{ marginLeft: "auto", color: "var(--tn-accent)" }}>已选</span> : null}
                  </button>
                );
              })}
            </div>
          )}
        </Field>
        {error ? <div style={{ fontSize: 12, color: "var(--tn-danger)" }}>{error}</div> : null}
      </div>
    </Modal>
  );
}

/* ---------------- 页面 ---------------- */

export function GroupsPage() {
  const [groups, setGroups] = useState<ChatGroup[]>([]);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [detail, setDetail] = useState<ChatGroup | null>(null);
  const [messages, setMessages] = useState<ChatGroupMessage[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [draft, setDraft] = useState("");
  const [sending, setSending] = useState(false);
  const [pendingUntil, setPendingUntil] = useState(0);
  const [now, setNow] = useState(() => Date.now());
  const [createOpen, setCreateOpen] = useState(false);
  const [memberOpen, setMemberOpen] = useState(false);
  const [candidates, setCandidates] = useState<Task[]>([]);
  const [createBotOpen, setCreateBotOpen] = useState(false);
  const [busy, setBusy] = useState(false);

  const lastSeqRef = useRef(0);
  const scrollRef = useRef<HTMLDivElement | null>(null);

  const loadGroups = useCallback(async () => {
    try {
      const list = await api.listGroups();
      setGroups(list);
      setError(null);
      setSelectedId((prev) => (prev && list.some((group) => group.id === prev) ? prev : (list[0]?.id ?? null)));
    } catch (err) {
      setError(err instanceof Error ? err.message : "加载群聊失败");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void loadGroups();
  }, [loadGroups]);

  // 切换群：重置游标并拉一次完整历史（分页直到 has_more 为 false）。
  useEffect(() => {
    if (!selectedId) {
      setDetail(null);
      setMessages([]);
      return;
    }
    let alive = true;
    setDetail(null);
    setMessages([]);
    setPendingUntil(0);
    lastSeqRef.current = 0;

    void (async () => {
      try {
        const group = await api.getGroup(selectedId);
        if (!alive) return;
        setDetail(group);

        const collected: ChatGroupMessage[] = [];
        let cursor = 0;
        for (let page = 0; page < 10; page += 1) {
          const chunk = await api.listGroupMessages(selectedId, { afterSeq: cursor, limit: 200 });
          if (!alive) return;
          collected.push(...chunk.messages);
          const newest = chunk.messages[chunk.messages.length - 1];
          if (newest) cursor = newest.seq;
          if (!chunk.has_more) break;
        }
        lastSeqRef.current = cursor;
        setMessages(collected);
      } catch (err) {
        if (alive) setError(err instanceof Error ? err.message : "加载群消息失败");
      }
    })();

    return () => {
      alive = false;
    };
  }, [selectedId]);

  // 轮询增量：2s 一拍，失败静默重试。
  useEffect(() => {
    if (!selectedId) return;
    let alive = true;
    let timer = 0;

    const tick = async () => {
      try {
        const chunk = await api.listGroupMessages(selectedId, {
          afterSeq: lastSeqRef.current,
          limit: 200,
        });
        if (!alive) return;
        if (chunk.messages.length > 0) {
          const newest = chunk.messages[chunk.messages.length - 1];
          if (newest) lastSeqRef.current = newest.seq;
          setMessages((prev) => mergeMessages(prev, chunk.messages));
        }
      } catch {
        // 轮询失败不影响操作，等下一拍。
      }
      if (alive) timer = window.setTimeout(() => void tick(), POLL_MS);
    };

    timer = window.setTimeout(() => void tick(), POLL_MS);
    return () => {
      alive = false;
      window.clearTimeout(timer);
    };
  }, [selectedId]);

  useEffect(() => {
    const node = scrollRef.current;
    if (node) node.scrollTop = node.scrollHeight;
  }, [messages.length, selectedId]);

  useEffect(() => {
    if (!pendingUntil) return;
    const timer = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(timer);
  }, [pendingUntil]);

  const memberMap = useMemo(
    () => new Map((detail?.members ?? []).map((member) => [member.task_id, member])),
    [detail],
  );

  const discussing = pendingUntil > 0 && pendingUntil > now;

  const send = async () => {
    const content = draft.trim();
    if (!content || !selectedId || sending) return;
    setSending(true);
    try {
      const outcome = await api.sendGroupMessage(selectedId, content);
      setDraft("");
      lastSeqRef.current = Math.max(lastSeqRef.current, outcome.message.seq);
      setMessages((prev) => mergeMessages(prev, [outcome.message]));
      setPendingUntil(Date.now() + PENDING_MS);
      setNow(Date.now());
      void loadGroups();
    } catch (err) {
      setError(err instanceof Error ? err.message : "发送失败");
    } finally {
      setSending(false);
    }
  };

  const interrupt = async () => {
    if (!selectedId) return;
    try {
      await api.interruptGroup(selectedId);
      setPendingUntil(0);
    } catch (err) {
      setError(err instanceof Error ? err.message : "打断失败");
    }
  };

  const openMembers = async () => {
    setMemberOpen(true);
    try {
      const bots = await api.listBotCandidates();
      setCandidates(bots);
    } catch {
      setCandidates([]);
    }
  };

  const addMember = async (taskId: string) => {
    if (!selectedId) return;
    try {
      const members = await api.addGroupMember(selectedId, taskId);
      setDetail((prev) => (prev ? { ...prev, members, member_count: members.length } : prev));
      setGroups((prev) =>
        prev.map((group) =>
          group.id === selectedId ? { ...group, member_count: members.length } : group,
        ),
      );
    } catch (err) {
      setError(err instanceof Error ? err.message : "添加成员失败");
    }
  };

  const removeMember = async (taskId: string) => {
    if (!selectedId) return;
    try {
      const outcome = await api.removeGroupMember(selectedId, taskId);
      setDetail((prev) =>
        prev ? { ...prev, members: outcome.members, member_count: outcome.members.length } : prev,
      );
      setGroups((prev) =>
        prev.map((group) =>
          group.id === selectedId ? { ...group, member_count: outcome.members.length } : group,
        ),
      );
    } catch (err) {
      setError(err instanceof Error ? err.message : "移除成员失败");
    }
  };

  const deleteGroup = async () => {
    if (!selectedId) return;
    const target = groups.find((group) => group.id === selectedId);
    if (!window.confirm(`删除群聊「${target?.name ?? ""}」？群消息会一并删除，bot 本身不受影响。`)) {
      return;
    }
    setBusy(true);
    try {
      await api.deleteGroup(selectedId);
      setSelectedId(null);
      await loadGroups();
    } catch (err) {
      setError(err instanceof Error ? err.message : "删除失败");
    } finally {
      setBusy(false);
    }
  };

  const memberIds = new Set((detail?.members ?? []).map((member) => member.task_id));
  const available = candidates.filter((bot) => !memberIds.has(bot.id));

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 16, height: "100%" }}>
      <PageHead title="群聊" desc="每个 Bot 都是一个独立成员；群里它们依次决定要不要开口，不会无限接话。">
        <Button variant="primary" icon="plus" onClick={() => setCreateOpen(true)}>
          新建群聊
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

      <div style={{ display: "flex", gap: 16, flex: 1, minHeight: 420, height: "calc(100vh - 250px)" }}>
        {/* 左：群列表 */}
        <aside
          style={{
            width: 240,
            flex: "none",
            display: "flex",
            flexDirection: "column",
            border: "1px solid var(--tn-line)",
            borderRadius: 10,
            overflow: "hidden",
          }}
        >
          <div
            style={{
              padding: "10px 12px",
              borderBottom: "1px solid var(--tn-line-soft)",
              fontSize: 11,
              letterSpacing: "0.16em",
              textTransform: "uppercase",
              color: "var(--tn-text-4)",
            }}
          >
            群聊 · {groups.length}
          </div>
          <div style={{ flex: 1, overflowY: "auto", padding: 6 }}>
            {loading ? (
              <div style={{ padding: 12, fontSize: 12, color: "var(--tn-text-4)" }}>加载中…</div>
            ) : groups.length === 0 ? (
              <div style={{ padding: 12, fontSize: 12, color: "var(--tn-text-4)", lineHeight: 1.7 }}>
                还没有群聊。
                <br />
                先把几个 bot 建出来，再拉进群。
              </div>
            ) : (
              groups.map((group) => {
                const active = group.id === selectedId;
                return (
                  <button
                    key={group.id}
                    type="button"
                    onClick={() => setSelectedId(group.id)}
                    style={{
                      display: "flex",
                      flexDirection: "column",
                      gap: 3,
                      width: "100%",
                      padding: "8px 10px",
                      marginBottom: 2,
                      borderRadius: 6,
                      textAlign: "left",
                      background: active ? "var(--tn-panel-2)" : "transparent",
                      borderLeft: `2px solid ${active ? "var(--tn-accent)" : "transparent"}`,
                      cursor: "pointer",
                    }}
                  >
                    <span style={{ fontSize: 13, color: active ? "var(--tn-text)" : "var(--tn-text-2)" }}>
                      {group.name}
                    </span>
                    <span style={{ fontSize: 11, color: "var(--tn-text-4)" }}>
                      {group.member_count} 个 bot
                      {group.last_message_at ? ` · ${formatTime(group.last_message_at)}` : ""}
                    </span>
                  </button>
                );
              })
            )}
          </div>
        </aside>

        {/* 中：消息流 */}
        <section
          style={{
            flex: 1,
            minWidth: 0,
            display: "flex",
            flexDirection: "column",
            border: "1px solid var(--tn-line)",
            borderRadius: 10,
            overflow: "hidden",
          }}
        >
          {!selectedId || !detail ? (
            <div style={{ flex: 1, display: "grid", placeContent: "center" }}>
              <Empty icon="users" title="选择一个群聊" desc="或者新建一个群聊，把 bot 拉进来。" />
            </div>
          ) : (
            <>
              <div
                style={{
                  display: "flex",
                  alignItems: "center",
                  gap: 10,
                  padding: "10px 14px",
                  borderBottom: "1px solid var(--tn-line-soft)",
                }}
              >
                <span style={{ fontSize: 14, fontWeight: 600, color: "var(--tn-text)" }}>{detail.name}</span>
                <Badge>{detail.member_count} 个 bot</Badge>
                <Badge>{detail.max_rounds} 轮闸门</Badge>
                {detail.description ? (
                  <span className="fx-ellipsis" style={{ fontSize: 12, color: "var(--tn-text-4)", minWidth: 0 }}>
                    {detail.description}
                  </span>
                ) : null}
              </div>

              <div
                ref={scrollRef}
                style={{
                  flex: 1,
                  minHeight: 0,
                  overflowY: "auto",
                  padding: "14px 16px",
                  display: "flex",
                  flexDirection: "column",
                  gap: 14,
                }}
              >
                {messages.length === 0 ? (
                  <div style={{ margin: "auto", fontSize: 12, color: "var(--tn-text-4)" }}>
                    还没有消息，说点什么吧。
                  </div>
                ) : (
                  messages.map((message) => (
                    <MessageRow
                      key={message.id}
                      message={message}
                      member={message.task_id ? memberMap.get(message.task_id) : undefined}
                    />
                  ))
                )}
                {discussing ? (
                  <div style={{ display: "flex", alignItems: "center", gap: 8, paddingLeft: 2 }}>
                    <i className="fx-pulse fx-run" />
                    <span style={{ fontSize: 12, color: "var(--tn-text-3)" }}>Bot 们正在讨论…</span>
                    <Button size="sm" variant="quiet" onClick={() => void interrupt()}>
                      打断
                    </Button>
                  </div>
                ) : null}
              </div>

              <div style={{ borderTop: "1px solid var(--tn-line-soft)", padding: 10 }}>
                <div style={{ display: "flex", gap: 8, alignItems: "flex-end" }}>
                  <Textarea
                    value={draft}
                    rows={2}
                    placeholder="发消息给群里的 bot；@名字 可以让它优先发言"
                    onChange={(event) => setDraft(event.target.value)}
                    onKeyDown={(event) => {
                      if (event.key === "Enter" && !event.shiftKey) {
                        event.preventDefault();
                        void send();
                      }
                    }}
                    style={{ flex: 1, resize: "none" }}
                  />
                  <Button variant="primary" onClick={() => void send()} disabled={!draft.trim()} busy={sending}>
                    发送
                  </Button>
                </div>
              </div>
            </>
          )}
        </section>

        {/* 右：成员面板 */}
        <aside
          style={{
            width: 220,
            flex: "none",
            display: "flex",
            flexDirection: "column",
            border: "1px solid var(--tn-line)",
            borderRadius: 10,
            overflow: "hidden",
          }}
        >
          <div
            style={{
              padding: "10px 12px",
              borderBottom: "1px solid var(--tn-line-soft)",
              fontSize: 11,
              letterSpacing: "0.16em",
              textTransform: "uppercase",
              color: "var(--tn-text-4)",
            }}
          >
            成员
          </div>
          <div style={{ flex: 1, overflowY: "auto", padding: 8, display: "flex", flexDirection: "column", gap: 4 }}>
            {(detail?.members ?? []).map((member) => (
              <div
                key={member.task_id}
                style={{ display: "flex", alignItems: "center", gap: 8, padding: "5px 4px" }}
              >
                <BotAvatar
                  shape={member.avatar_shape}
                  color={member.avatar_color}
                  size={26}
                  name={member.name}
                />
                <div style={{ minWidth: 0, flex: 1 }}>
                  <div className="fx-ellipsis" style={{ fontSize: 12, color: "var(--tn-text-2)" }}>
                    {member.name}
                  </div>
                  <div className="fx-ellipsis" style={{ fontSize: 11, color: "var(--tn-text-4)" }}>
                    {member.status}
                  </div>
                </div>
                <button
                  type="button"
                  title="移出群聊"
                  onClick={() => void removeMember(member.task_id)}
                  style={{
                    width: 22,
                    height: 22,
                    borderRadius: 4,
                    color: "var(--tn-text-4)",
                    background: "transparent",
                    cursor: "pointer",
                  }}
                >
                  ×
                </button>
              </div>
            ))}
            {detail && detail.members.length === 0 ? (
              <div style={{ padding: 8, fontSize: 11, color: "var(--tn-text-4)" }}>还没有成员</div>
            ) : null}
          </div>
          <div style={{ borderTop: "1px solid var(--tn-line-soft)", padding: 8, display: "flex", flexDirection: "column", gap: 6 }}>
            <Button size="sm" icon="plus" onClick={() => void openMembers()} disabled={!selectedId}>
              添加成员
            </Button>
            <Button size="sm" variant="danger" icon="trash" onClick={() => void deleteGroup()} disabled={!selectedId || busy}>
              删除群聊
            </Button>
          </div>
        </aside>
      </div>

      <CreateGroupDialog
        open={createOpen}
        onClose={() => setCreateOpen(false)}
        onCreated={(group) => {
          setGroups((prev) => [group, ...prev]);
          setSelectedId(group.id);
        }}
      />

      <Modal
        open={memberOpen}
        title="添加成员"
        onClose={() => setMemberOpen(false)}
        footer={
          <Button variant="ghost" onClick={() => setMemberOpen(false)}>
            完成
          </Button>
        }
      >
        <div style={{ display: "flex", flexDirection: "column", gap: 10 }}>
          <Button size="sm" variant="subtle" icon="plus" onClick={() => setCreateBotOpen(true)}>
            新建一个 Bot
          </Button>
          {available.length === 0 ? (
            <div style={{ fontSize: 12, color: "var(--tn-text-4)" }}>没有可添加的 bot 了</div>
          ) : (
            <div style={{ display: "flex", flexDirection: "column", gap: 4, maxHeight: 320, overflowY: "auto" }}>
              {available.map((bot) => (
                <button
                  key={bot.id}
                  type="button"
                  onClick={() => void addMember(bot.id)}
                  style={{
                    display: "flex",
                    alignItems: "center",
                    gap: 10,
                    padding: "7px 8px",
                    borderRadius: 6,
                    textAlign: "left",
                    border: "1px solid var(--tn-line-soft)",
                    background: "transparent",
                    cursor: "pointer",
                  }}
                >
                  <BotAvatar shape={bot.avatar_shape} color={bot.avatar_color} size={26} name={botLabel(bot)} />
                  <span style={{ fontSize: 13, color: "var(--tn-text-2)" }}>{botLabel(bot)}</span>
                  <span style={{ marginLeft: "auto", fontSize: 11, color: "var(--tn-accent)" }}>加入</span>
                </button>
              ))}
            </div>
          )}
        </div>
      </Modal>

      <CreateBotDialog
        open={createBotOpen}
        onClose={() => setCreateBotOpen(false)}
        onCreated={(task) => {
          setCandidates((prev) => [task, ...prev]);
        }}
      />
    </div>
  );
}

export default GroupsPage;

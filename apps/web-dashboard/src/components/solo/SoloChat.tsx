/**
 * Solo 聊天面板（设计稿 v2 的 .chat-block）。
 *
 * 与设计稿一致的交互全部保留：面板内独立滚动、自绘滚动条（可拖动、轨道上滚轮照常滚聊天）、
 * 向上懒加载更早消息并按高度差修正位置、悬停出现「复制 / 引用」、「— 已加载全部消息 —」分隔行。
 *
 * 流式部分参考 Claude Code 桌面端：
 * - 面板内部挂 `useTaskStream`，助手正文逐字出现，Markdown 即时成形；
 * - 执行过程卡展示思考 / 工具调用 / 错误，结束后折叠；
 * - 只有用户贴着底部（<80px）才自动跟随，上翻阅读时出现「回到最新 ↓」；
 * - 终态由落库消息接管，历史 `run_finished` 下挂执行轨迹卡。
 *
 * 数据全部是后端落库的真实消息（GET /tasks/{id}/messages，seq 游标分页），
 * 本组件不做任何本地伪造。
 */
import { memo, useCallback, useEffect, useLayoutEffect, useRef, useState, type ReactNode } from "react";

import type { TaskMessage, TaskRunSummary, TaskStreamEvent } from "../../api/types";
import { MarkdownText } from "./MarkdownText";
import { RunTraceCard } from "./RunTraceCard";
import { StreamActivity } from "./StreamActivity";
import {
  formatElapsed,
  hasStreamActivity,
  type TaskStreamSnapshot,
} from "./taskStream";
import { useStickToBottom } from "./useStickToBottom";
import { useTaskStream } from "./useTaskStream";

const FLUX_AVATAR = (
  <svg viewBox="0 0 24 24">
    <path d="M12 2 22 12 12 22 2 12Z" fill="currentColor" />
  </svg>
);

/** 后端时间戳是 UTC 的 naive ISO 串，展示前补上时区标记再转本地时间 */
export function clockOf(iso: string): string {
  if (!iso) return "--:--";
  const hasZone = /(Z|[+-]\d{2}:?\d{2})$/.test(iso);
  const date = new Date(hasZone ? iso : `${iso}Z`);
  if (Number.isNaN(date.getTime())) return "--:--";
  return `${String(date.getHours()).padStart(2, "0")}:${String(date.getMinutes()).padStart(2, "0")}`;
}

/**
 * 拆出用户消息里的「引用」前缀：落库格式为 `引用：「原文」\n\n正文`，
 * 展示时把引用内容单独渲染成气泡顶部的一行（与设计稿的 .quote-line 一致）。
 */
export function splitQuote(content: string): { quote: string | null; body: string } {
  if (!content.startsWith("引用：")) return { quote: null, body: content };
  const splitAt = content.indexOf("\n\n");
  if (splitAt === -1) return { quote: content.slice(3), body: "" };
  return { quote: content.slice(3, splitAt), body: content.slice(splitAt + 2) };
}

function ClarifyBlock({ title, children }: { title: string; children: ReactNode }) {
  const [folded, setFolded] = useState(false);
  return (
    <div className={`clarify${folded ? " is-folded" : ""}`}>
      <button type="button" className="clarify-head" onClick={() => setFolded((prev) => !prev)}>
        <span>{title}</span>
        <i className="chev">⌄</i>
      </button>
      <ul className="clarify-list">{children}</ul>
    </div>
  );
}

interface MessageRowProps {
  message: TaskMessage;
  runSummary?: TaskRunSummary;
  onCopy: (text: string) => void;
  onQuote: (text: string) => void;
}

/**
 * 单条历史消息。用 memo 隔离流式重渲染：流式期间只有实时气泡在变，
 * 已落库的消息不参与 20fps 的重新渲染。
 */
const MessageRow = memo(function MessageRow({
  message,
  runSummary,
  onCopy,
  onQuote,
}: MessageRowProps) {
  const isUser = message.role === "user";
  const clarify = message.payload?.clarify ?? [];
  const questions = message.payload?.questions ?? [];
  const confirmation = message.payload?.confirmation ?? [];
  const steps = message.payload?.steps ?? [];
  const runId = message.payload?.run_id;
  const { quote, body } = isUser ? splitQuote(message.content) : { quote: null, body: message.content };

  return (
    <div className="msg is-in">
      {isUser ? <span className="ava ava-user">孟</span> : <span className="ava ava-flux">{FLUX_AVATAR}</span>}
      <div className="msg-body">
        <div className="msg-meta">
          {isUser ? "你" : "Flux"} · {clockOf(message.created_at)}
        </div>
        {isUser ? (
          <div className="bubble">
            {quote ? <span className="quote-line">{quote}</span> : null}
            {body}
          </div>
        ) : body ? (
          <MarkdownText text={body} className="msg-text" />
        ) : null}
        {confirmation.length > 0 ? (
          <ClarifyBlock title={`需求确认（${confirmation.length} 个维度）`}>
            {confirmation.map((item) => (
              <li key={`${message.id}-cf-${item.label}`}>
                <span className="ck">✓</span>
                <b>{item.label}：</b>
                {item.value}
              </li>
            ))}
          </ClarifyBlock>
        ) : null}
        {questions.length > 0 ? (
          <ClarifyBlock title={`需要你补充（${questions.length} 项）`}>
            {questions.map((question, index) => (
              <li key={`${message.id}-q-${index}`}>
                <span className="ask">?</span>
                {question}
              </li>
            ))}
          </ClarifyBlock>
        ) : null}
        {clarify.length > 0 ? (
          <ClarifyBlock title="需求澄清结果">
            {clarify.map((item) => (
              <li key={`${message.id}-${item.label}`}>
                <span className="ck">✓</span>
                <b>{item.label}：</b>
                {item.value}
              </li>
            ))}
          </ClarifyBlock>
        ) : null}
        {steps.length > 0 ? (
          <ClarifyBlock title={`执行计划（${steps.length} 步）`}>
            {steps.map((step, index) => (
              <li key={`${message.id}-step-${index}`}>
                <span className="idx">{String(index + 1).padStart(2, "0")}</span>
                {step}
              </li>
            ))}
          </ClarifyBlock>
        ) : null}
        {!isUser && runId ? (
          <RunTraceCard taskId={message.task_id} runId={runId} summary={runSummary} />
        ) : null}
      </div>
      <div className="msg-actions">
        <button type="button" className="msg-act" title="复制" onClick={() => onCopy(message.content)}>
          复制
        </button>
        <button type="button" className="msg-act" title="引用" onClick={() => onQuote(message.content)}>
          引用
        </button>
      </div>
    </div>
  );
});

interface Props {
  taskId: string | null;
  messages: TaskMessage[];
  /** run_id → 这一轮的概览（计数 / 耗时），用于历史轨迹卡的折叠头 */
  runSummaries: Map<string, TaskRunSummary>;
  hasMore: boolean;
  loading: boolean;
  loadingOlder: boolean;
  error: string | null;
  sending: boolean;
  sendingHint: string;
  cancelling: boolean;
  onLoadOlder: () => void;
  onCopy: (text: string) => void;
  onQuote: (text: string) => void;
  onCancel: () => void;
  onPersistedMessage: (message: TaskMessage) => void;
  onRunSettled: (event: TaskStreamEvent) => void;
}

/** 实时气泡右上角阶段文案 */
function phaseLabel(snapshot: TaskStreamSnapshot): string {
  if (snapshot.liveLabel) return snapshot.liveLabel;
  if (snapshot.liveActive) return "正在输出…";
  return "本轮输出";
}

export function SoloChat({
  taskId,
  messages,
  runSummaries,
  hasMore,
  loading,
  loadingOlder,
  error,
  sending,
  sendingHint,
  cancelling,
  onLoadOlder,
  onCopy,
  onQuote,
  onCancel,
  onPersistedMessage,
  onRunSettled,
}: Props) {
  const stream = useTaskStream(taskId, { onPersistedMessage, onRunSettled });
  const snapshot = stream.snapshot;
  const liveVisible =
    snapshot.liveActive ||
    hasStreamActivity(snapshot) ||
    snapshot.connection === "reconnecting" ||
    snapshot.connection === "failed";
  const streamingStarted =
    snapshot.startedAtMs !== null || snapshot.endedAtMs !== null || hasStreamActivity(snapshot);
  const showTyping = sending && !liveVisible && !streamingStarted;

  const lastMessage = messages[messages.length - 1];
  const stick = useStickToBottom(
    snapshot.revision + (lastMessage ? lastMessage.seq : 0) + (sending ? 1 : 0),
  );
  const scrollRef = stick.ref;

  const wrapRef = useRef<HTMLDivElement>(null);
  const trackRef = useRef<HTMLDivElement>(null);
  const thumbRef = useRef<HTMLElement>(null);
  const chatRef = useRef<HTMLDivElement>(null);

  const [trackVisible, setTrackVisible] = useState(false);
  const [thumb, setThumb] = useState({ height: 0, top: 0 });

  // 向上加载前记录高度，插入更早消息后按高度差修正 scrollTop，保持可见内容不跳
  const heightBeforeRef = useRef<number | null>(null);
  const taskRef = useRef<string | null>(null);
  const prevSendingRef = useRef(false);

  // 实时气泡的耗时每秒走一格；终态后由 endedAtMs 冻结
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    if (!snapshot.liveActive) return;
    setNow(Date.now());
    const timer = window.setInterval(() => setNow(Date.now()), 1_000);
    return () => window.clearInterval(timer);
  }, [snapshot.liveActive]);

  const updateThumb = useCallback(() => {
    const scroller = scrollRef.current;
    const wrap = wrapRef.current;
    if (!scroller || !wrap) return;
    const trackH = wrap.clientHeight - 10;
    const scrollH = scroller.scrollHeight;
    const clientH = scroller.clientHeight;
    if (trackH <= 0 || scrollH <= clientH + 1) {
      setTrackVisible(false);
      return;
    }
    setTrackVisible(true);
    const height = Math.max(30, Math.round((trackH * clientH) / scrollH));
    const top = Math.round((trackH - height) * (scroller.scrollTop / (scrollH - clientH)));
    setThumb({ height, top });
  }, [scrollRef]);

  // 切换任务：流被重置，滚动也回到最新一条
  useEffect(() => {
    if (taskRef.current === taskId) return;
    taskRef.current = taskId;
    requestAnimationFrame(() => stick.scrollToBottom("auto"));
  }, [taskId, stick.scrollToBottom]);

  // 用户自己发送消息时回到底部（主动操作优先于“上翻阅读”）
  useLayoutEffect(() => {
    if (sending && !prevSendingRef.current) stick.scrollToBottom("smooth");
    prevSendingRef.current = sending;
  }, [sending, stick.scrollToBottom]);

  // 懒加载插入更早消息：按高度差保持当前位置（此时用户不在底部，不会被自动跟随干扰）
  useLayoutEffect(() => {
    const scroller = scrollRef.current;
    if (!scroller) return;
    if (heightBeforeRef.current !== null) {
      scroller.scrollTop += scroller.scrollHeight - heightBeforeRef.current;
      heightBeforeRef.current = null;
    }
    updateThumb();
  }, [messages, updateThumb, scrollRef]);

  // 流式内容变化后同步滑块位置（自动跟随的 scrollTop 变更同样要反映到滑块）
  useLayoutEffect(() => {
    updateThumb();
  }, [snapshot.revision, updateThumb]);

  // 内容高度变化（图片/输入指示器/窗口缩放）时同步滑块
  useEffect(() => {
    const chat = chatRef.current;
    if (!chat || typeof ResizeObserver === "undefined") return;
    const observer = new ResizeObserver(() => updateThumb());
    observer.observe(chat);
    return () => observer.disconnect();
  }, [updateThumb]);

  // 轨道上滚轮照常滚聊天（React 的 onWheel 是 passive，必须用原生监听才能 preventDefault）
  useEffect(() => {
    const track = trackRef.current;
    const scroller = scrollRef.current;
    if (!track || !scroller) return;
    const onWheel = (event: WheelEvent) => {
      event.preventDefault();
      scroller.scrollTop += event.deltaY;
    };
    track.addEventListener("wheel", onWheel, { passive: false });
    return () => track.removeEventListener("wheel", onWheel);
  }, [scrollRef]);

  // 拖动滑块滚动
  useEffect(() => {
    const thumbEl = thumbRef.current;
    const track = trackRef.current;
    const scroller = scrollRef.current;
    if (!thumbEl || !track || !scroller) return;
    let drag: { y: number; top: number } | null = null;

    const onMove = (event: MouseEvent) => {
      if (!drag) return;
      const trackH = track.clientHeight;
      const height = thumbEl.offsetHeight;
      const top = Math.min(Math.max(0, drag.top + event.clientY - drag.y), trackH - height);
      const max = scroller.scrollHeight - scroller.clientHeight;
      scroller.scrollTop = max * (top / Math.max(1, trackH - height));
    };
    const onUp = () => {
      drag = null;
      track.classList.remove("is-dragging");
      window.removeEventListener("mousemove", onMove);
      window.removeEventListener("mouseup", onUp);
    };
    const onDown = (event: MouseEvent) => {
      event.preventDefault();
      drag = { y: event.clientY, top: thumbEl.offsetTop };
      track.classList.add("is-dragging");
      window.addEventListener("mousemove", onMove);
      window.addEventListener("mouseup", onUp);
    };

    thumbEl.addEventListener("mousedown", onDown);
    return () => {
      thumbEl.removeEventListener("mousedown", onDown);
      window.removeEventListener("mousemove", onMove);
      window.removeEventListener("mouseup", onUp);
    };
  }, [trackVisible, scrollRef]);

  const handleScroll = () => {
    const scroller = scrollRef.current;
    if (!scroller) return;
    stick.onScroll();
    if (scroller.scrollTop <= 24 && hasMore && !loadingOlder) {
      heightBeforeRef.current = scroller.scrollHeight;
      onLoadOlder();
    }
    updateThumb();
  };

  const handleCopy = useCallback((text: string) => onCopy(text), [onCopy]);
  const handleQuote = useCallback((text: string) => onQuote(text), [onQuote]);

  const elapsed = formatElapsed(snapshot.startedAtMs, snapshot.endedAtMs, now);
  const away = !stick.atBottom;

  return (
    <div className="chat-wrap" ref={wrapRef}>
      <div className="chat-scroll" ref={scrollRef} onScroll={handleScroll}>
        <div className="chat" ref={chatRef}>
          {loading ? (
            <div className="chat-hint">正在加载任务消息…</div>
          ) : error ? (
            <div className="chat-hint err">{error}</div>
          ) : (
            <div className={`chat-done${hasMore ? " is-hidden" : ""}`} id="soloChatDone">
              — 已加载全部消息 —
            </div>
          )}

          {messages.map((message) => (
            <MessageRow
              key={message.id}
              message={message}
              runSummary={
                message.payload?.run_id ? runSummaries.get(message.payload.run_id) : undefined
              }
              onCopy={handleCopy}
              onQuote={handleQuote}
            />
          ))}

          {liveVisible ? (
            <div className="msg msg-live is-in">
              <span className="ava ava-flux">{FLUX_AVATAR}</span>
              <div className="msg-body">
                <div className="msg-meta stream-meta">
                  <span>Flux</span>
                  <span className="stream-phase">{phaseLabel(snapshot)}</span>
                  {elapsed ? <span className="stream-elapsed">{elapsed}</span> : null}
                  {snapshot.liveActive && taskId ? (
                    <button
                      type="button"
                      className="stream-stop"
                      disabled={cancelling}
                      onClick={onCancel}
                    >
                      {cancelling ? "停止中…" : "停止"}
                    </button>
                  ) : null}
                </div>

                <StreamActivity
                  reasoning={snapshot.reasoning}
                  tools={snapshot.tools}
                  errors={snapshot.errorLines}
                  active={snapshot.liveActive}
                  startedAtMs={snapshot.startedAtMs}
                  endedAtMs={snapshot.endedAtMs}
                  label={snapshot.liveLabel}
                />

                {snapshot.liveText ? (
                  <MarkdownText
                    text={snapshot.liveText}
                    streaming={snapshot.liveActive}
                    className="msg-text"
                  />
                ) : null}

                {snapshot.terminalError && snapshot.connection !== "failed" ? (
                  <div className="stream-conn is-error">
                    <span>{snapshot.terminalError}</span>
                  </div>
                ) : null}

                {snapshot.connection === "reconnecting" ? (
                  <div className="stream-conn is-warn">
                    <i className="stream-spin" aria-hidden="true" />
                    <span>重连中…（第 {snapshot.retryAttempt} 次）</span>
                  </div>
                ) : null}

                {snapshot.connection === "failed" ? (
                  <div className="stream-conn is-error">
                    <span>{snapshot.terminalError || "实时连接已中断"}</span>
                    <button type="button" className="stream-retry" onClick={stream.retry}>
                      重试
                    </button>
                  </div>
                ) : null}

                {snapshot.connection === "live" && snapshot.liveActive ? (
                  <div className="stream-conn is-live">实时同步中</div>
                ) : null}
              </div>
            </div>
          ) : null}

          {showTyping ? (
            <div className="msg msg-typing">
              <span className="ava ava-flux">{FLUX_AVATAR}</span>
              <div className="msg-body">
                <div className="msg-meta">Flux · 正在输入…</div>
                <div className="typing-bubble">
                  <i />
                  <i />
                  <i />
                </div>
                <div className="msg-text dim">{sendingHint}</div>
              </div>
            </div>
          ) : null}
        </div>
      </div>

      <div className={`chat-loading${loadingOlder ? "" : " is-hidden"}`}>正在加载更早的消息…</div>
      <div
        className={`chat-sb${trackVisible ? "" : " is-hidden"}`}
        ref={trackRef}
        aria-hidden="true"
      >
        <i className="chat-sb-thumb" ref={thumbRef} style={{ height: thumb.height, top: thumb.top }} />
      </div>
      <button
        type="button"
        className={`chat-jump${away ? "" : " is-hidden"}`}
        onClick={() => stick.scrollToBottom("smooth")}
      >
        回到最新 ↓
      </button>
    </div>
  );
}

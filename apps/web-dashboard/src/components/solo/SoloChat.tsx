/**
 * Solo 聊天面板（设计稿 v2 的 .chat-block）。
 *
 * 与设计稿一致的交互全部保留：面板内独立滚动、自绘滚动条（可拖动、轨道上滚轮照常滚聊天）、
 * 向上懒加载更早消息并按高度差修正位置、新消息滑入、「Flux 正在输入…」三点动画、
 * 悬停出现「复制 / 引用」、「— 已加载全部消息 —」分隔行、右下角「回到最新 ↓」。
 *
 * 数据全部是后端落库的真实消息（GET /tasks/{id}/messages，seq 游标分页），
 * 本组件不做任何本地伪造。
 */
import { useCallback, useEffect, useLayoutEffect, useRef, useState } from "react";

import type { TaskMessage } from "../../api/types";

const FLUX_AVATAR = (
  <svg viewBox="0 0 24 24">
    <path d="M12 2 22 12 12 22 2 12Z" fill="#7c5cff" />
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

function ClarifyBlock({
  title,
  children,
}: {
  title: string;
  children: React.ReactNode;
}) {
  const [folded, setFolded] = useState(false);
  return (
    <div className={`clarify${folded ? " is-folded" : ""}`}>
      <button
        type="button"
        className="clarify-head"
        onClick={() => setFolded((prev) => !prev)}
      >
        <span>{title}</span>
        <i className="chev">⌄</i>
      </button>
      <ul className="clarify-list">{children}</ul>
    </div>
  );
}

interface Props {
  taskId: string | null;
  messages: TaskMessage[];
  hasMore: boolean;
  loading: boolean;
  loadingOlder: boolean;
  error: string | null;
  sending: boolean;
  sendingHint: string;
  onLoadOlder: () => void;
  onCopy: (text: string) => void;
  onQuote: (text: string) => void;
}

export function SoloChat({
  taskId,
  messages,
  hasMore,
  loading,
  loadingOlder,
  error,
  sending,
  sendingHint,
  onLoadOlder,
  onCopy,
  onQuote,
}: Props) {
  const wrapRef = useRef<HTMLDivElement>(null);
  const scrollRef = useRef<HTMLDivElement>(null);
  const trackRef = useRef<HTMLDivElement>(null);
  const thumbRef = useRef<HTMLElement>(null);
  const chatRef = useRef<HTMLDivElement>(null);

  const [away, setAway] = useState(false);
  const [trackVisible, setTrackVisible] = useState(false);
  const [thumb, setThumb] = useState({ height: 0, top: 0 });

  // 向上加载前记录高度，插入更早消息后按高度差修正 scrollTop，保持可见内容不跳
  const heightBeforeRef = useRef<number | null>(null);
  const taskRef = useRef<string | null>(null);
  /** 已滚动到的最后一条消息 id（与设计稿一致：新消息到达即回到底部） */
  const lastIdRef = useRef<string | null>(null);
  const prevSendingRef = useRef(false);

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
  }, []);

  const updateAway = useCallback(() => {
    const scroller = scrollRef.current;
    if (!scroller) return;
    const gap = scroller.scrollHeight - scroller.scrollTop - scroller.clientHeight;
    setAway(gap > 12);
  }, []);

  const scrollToBottom = useCallback((smooth: boolean) => {
    const scroller = scrollRef.current;
    if (!scroller) return;
    if (smooth) scroller.scrollTo({ top: scroller.scrollHeight, behavior: "smooth" });
    else scroller.scrollTop = scroller.scrollHeight;
    updateAway();
    updateThumb();
  }, [updateAway, updateThumb]);

  // 切换任务：回到最新一条
  useEffect(() => {
    if (taskRef.current === taskId) return;
    taskRef.current = taskId;
    requestAnimationFrame(() => scrollToBottom(false));
  }, [taskId, scrollToBottom]);

  // 消息变化：首次加载/新消息回到底部（与设计稿 scrollChatBottom 同口径）；
  // 懒加载插入更早消息时按高度差保持当前位置，不跳回底部
  useLayoutEffect(() => {
    const scroller = scrollRef.current;
    if (!scroller) return;
    if (heightBeforeRef.current !== null) {
      scroller.scrollTop += scroller.scrollHeight - heightBeforeRef.current;
      heightBeforeRef.current = null;
    } else {
      const last = messages[messages.length - 1]?.id ?? null;
      if (last !== null && last !== lastIdRef.current) {
        if (lastIdRef.current === null) scroller.scrollTop = scroller.scrollHeight;
        else scroller.scrollTo({ top: scroller.scrollHeight, behavior: "smooth" });
      }
      lastIdRef.current = last;
      // 发送瞬间（打字指示器出现）同样回到底部，保证自己的消息与进度可见
      if (sending && !prevSendingRef.current) {
        scroller.scrollTo({ top: scroller.scrollHeight, behavior: "smooth" });
      }
      prevSendingRef.current = sending;
    }
    updateAway();
    updateThumb();
  }, [messages, sending, updateAway, updateThumb]);

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
  }, []);

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
  }, [trackVisible]);

  const handleScroll = () => {
    const scroller = scrollRef.current;
    if (!scroller) return;
    if (scroller.scrollTop <= 24 && hasMore && !loadingOlder) {
      heightBeforeRef.current = scroller.scrollHeight;
      onLoadOlder();
    }
    updateAway();
    updateThumb();
  };

  return (
    <div className="chat-wrap" ref={wrapRef}>
      <div className="chat-scroll" ref={scrollRef} onScroll={handleScroll}>
        <div className="chat" ref={chatRef}>
          {loading ? (
            <div className="chat-hint">正在加载任务消息…</div>
          ) : error ? (
            <div className="chat-hint err">{error}</div>
          ) : (
            <div
              className={`chat-done${hasMore ? " is-hidden" : ""}`}
              id="soloChatDone"
            >
              — 已加载全部消息 —
            </div>
          )}

          {messages.map((message) => {
            const isUser = message.role === "user";
            const clarify = message.payload?.clarify ?? [];
            const questions = message.payload?.questions ?? [];
            const confirmation = message.payload?.confirmation ?? [];
            const steps = message.payload?.steps ?? [];
            const { quote, body } = isUser ? splitQuote(message.content) : { quote: null, body: message.content };
            return (
              <div className="msg is-in" key={message.id}>
                {isUser ? (
                  <span className="ava ava-user">孟</span>
                ) : (
                  <span className="ava ava-flux">{FLUX_AVATAR}</span>
                )}
                <div className="msg-body">
                  <div className="msg-meta">
                    {isUser ? "你" : "Flux"} · {clockOf(message.created_at)}
                  </div>
                  {isUser ? (
                    <div className="bubble">
                      {quote ? <span className="quote-line">{quote}</span> : null}
                      {body}
                    </div>
                  ) : (
                    <div className="msg-text">{message.content}</div>
                  )}
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
                </div>
                <div className="msg-actions">
                  <button
                    type="button"
                    className="msg-act"
                    title="复制"
                    onClick={() => onCopy(message.content)}
                  >
                    复制
                  </button>
                  <button
                    type="button"
                    className="msg-act"
                    title="引用"
                    onClick={() => onQuote(message.content)}
                  >
                    引用
                  </button>
                </div>
              </div>
            );
          })}

          {sending ? (
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
        onClick={() => scrollToBottom(true)}
      >
        回到最新 ↓
      </button>
    </div>
  );
}
/**
 * 可编辑代码区（覆盖层编辑器）。
 *
 * 结构：透明 textarea（负责输入与光标）叠在同尺寸高亮层之上，
 * 高亮层负责 token 着色 + 内联 ghost 建议。两层 padding / 字体 /
 * white-space 完全一致，尺寸由高亮层决定，textarea 铺满舞台，
 * 因此不需要任何滚动同步。
 *
 * 四类补全：
 * - 词/片段/关键字 → 下拉候选（↓↑ 选择，Enter/Tab 接受）；
 * - 路径 → 字符串字面量里的工作区路径；
 * - 命令（/xxx）→ 下拉里选中后从正文抹掉，转而触发一次带指令的 AI 请求；
 * - AI → 输入停顿后请求模型，结果作为 ghost 内联展示（Tab 接受）。
 *
 * 写盘说明：后端没有「覆写既有文件」接口，这里的编辑只是本地草稿，
 * 由宿主负责提示刷新即丢。
 */
import { useEffect, useLayoutEffect, useMemo, useRef, useState } from "react";
import type {
  ChangeEvent as ReactChangeEvent,
  KeyboardEvent as ReactKeyboardEvent,
  ReactNode,
  SyntheticEvent as ReactSyntheticEvent,
} from "react";

import {
  AI_DEBOUNCE_MS,
  COMPLETION_COMMANDS,
  applyCompletion,
  buildCompletion,
  cleanCompletion,
  completionLineCount,
  ghostLine,
  lineEnd,
  lineStart,
  type AiCompletionInput,
  type CompletionItem,
  type CompletionState,
  type LexerKind,
} from "../../utils/completion";

/** 与 .ca-line / .ca-ln / .ca-input 的 line-height 保持一致 */
const LINE_HEIGHT = 20;
/** 与 .ca-hl / .ca-input 的 padding-top 保持一致 */
const PAD_TOP = 8;
/** 量字符宽用的等宽样本（10 个字符） */
const METRIC_CHARS = "0000000000";

const KIND_BADGE: Record<CompletionItem["kind"], string> = {
  keyword: "K",
  word: "W",
  snippet: "S",
  path: "P",
  command: "/",
};

export interface CodeAreaProps {
  path: string;
  value: string;
  kind: LexerKind;
  onChange: (next: string) => void;
  renderTokens: (text: string) => ReactNode;
  /** 有改动的行号（1 起），用于底色标记 */
  changedLines?: Set<number>;
  /** 工作区文件路径，路径补全用 */
  files?: string[];
  /** 文件过大被截断等情况下禁止编辑 */
  readOnly?: boolean;
  /** 取回 AI 补全；返回 null 表示不可用（未配置供应商等） */
  aiComplete?: (input: AiCompletionInput) => Promise<string | null>;
}

export function CodeArea({
  path,
  value,
  kind,
  onChange,
  renderTokens,
  changedLines,
  files,
  readOnly = false,
  aiComplete,
}: CodeAreaProps) {
  const textareaRef = useRef<HTMLTextAreaElement | null>(null);
  const metricRef = useRef<HTMLSpanElement | null>(null);

  const [caret, setCaret] = useState(0);
  const [menu, setMenu] = useState<CompletionState | null>(null);
  const [activeIndex, setActiveIndex] = useState(0);
  const [ghost, setGhost] = useState<{ at: number; text: string } | null>(null);
  const [charWidth, setCharWidth] = useState(0);
  /** 只统计「输入」事件，光标移动不触发 AI */
  const [inputSeq, setInputSeq] = useState(0);

  /** onChange 之后要把光标放回的位置（受控组件重建光标会丢） */
  const pendingCaret = useRef<number | null>(null);
  /** 下拉当前是否展开（AI effect 里读，避开依赖） */
  const menuOpenRef = useRef(false);
  /** 行首命令带来的动作指令，一次性消费 */
  const pendingInstructionRef = useRef<string | undefined>(undefined);
  /** AI 请求序号，防止乱序回包覆盖 */
  const seqRef = useRef(0);

  const lines = useMemo(() => value.split("\n"), [value]);
  const caretLineIndex = useMemo(() => value.slice(0, caret).split("\n").length - 1, [value, caret]);
  const caretCol = caret - lineStart(value, caret);
  const activeGhost = ghost && ghost.at === caret && !readOnly ? ghost.text : null;

  /* ---------- 光标还原 ---------- */
  useLayoutEffect(() => {
    const el = textareaRef.current;
    if (!el) return;
    const pos = pendingCaret.current;
    if (pos === null) return;
    pendingCaret.current = null;
    if (el.selectionStart !== pos || el.selectionEnd !== pos) {
      el.selectionStart = pos;
      el.selectionEnd = pos;
    }
  }, [value]);

  /* ---------- 量等宽字符宽度（下拉定位用） ---------- */
  useLayoutEffect(() => {
    const el = metricRef.current;
    if (!el) return;
    const width = el.getBoundingClientRect().width / METRIC_CHARS.length;
    if (width > 0) setCharWidth(width);
  }, []);

  /* ---------- 重算下拉候选 ---------- */
  useEffect(() => {
    if (readOnly) {
      setMenu(null);
      menuOpenRef.current = false;
      return;
    }
    const state = buildCompletion({ text: value, caret, kind, files: files ?? [] });
    setMenu(state);
    menuOpenRef.current = state !== null;
    setActiveIndex(0);
  }, [value, caret, kind, readOnly, files]);

  /* ---------- AI 补全（只在输入停顿后发起） ---------- */
  useEffect(() => {
    if (inputSeq === 0) return;
    if (readOnly || !aiComplete) return;
    if (menuOpenRef.current) {
      setGhost(null);
      return;
    }
    const instruction = pendingInstructionRef.current;
    pendingInstructionRef.current = undefined;
    // 普通续写只在行尾触发；带命令指令时不受此限
    if (!instruction && lineEnd(value, caret) !== caret) {
      setGhost(null);
      return;
    }
    const seq = seqRef.current + 1;
    seqRef.current = seq;
    const timer = window.setTimeout(() => {
      aiComplete({ path, kind, text: value, caret, instruction })
        .then((raw) => {
          if (seq !== seqRef.current) return;
          const cleaned = raw ? cleanCompletion(raw) : "";
          setGhost(cleaned ? { at: caret, text: cleaned } : null);
        })
        .catch(() => {
          if (seq === seqRef.current) setGhost(null);
        });
    }, instruction ? 0 : AI_DEBOUNCE_MS);
    return () => window.clearTimeout(timer);
    // 只在输入事件后重跑：光标移动、菜单开关都不应重新请求
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [inputSeq]);

  /* ---------- 交互 ---------- */
  const commit = (next: string, nextCaret: number) => {
    pendingCaret.current = nextCaret;
    setCaret(nextCaret);
    onChange(next);
  };

  const handleChange = (event: ReactChangeEvent<HTMLTextAreaElement>) => {
    const next = event.currentTarget.value;
    const nextCaret = event.currentTarget.selectionStart ?? next.length;
    commit(next, nextCaret);
    setInputSeq((seq) => seq + 1);
  };

  const handleSelect = (event: ReactSyntheticEvent<HTMLTextAreaElement>) => {
    setCaret(event.currentTarget.selectionStart ?? 0);
  };

  const applyItem = (item: CompletionItem) => {
    if (!menu) return;
    setGhost(null);
    if (item.kind === "command") {
      // 命令不进正文：抹掉已输入的命令词，改为触发一次带指令的补全
      const removed = value.slice(0, menu.start) + value.slice(menu.end);
      pendingInstructionRef.current = COMPLETION_COMMANDS.find((command) => command.name === item.label)?.instruction;
      setMenu(null);
      menuOpenRef.current = false;
      commit(removed, menu.start);
      setInputSeq((seq) => seq + 1);
      return;
    }
    const applied = applyCompletion(value, menu, item);
    setMenu(null);
    commit(applied.text, applied.caret);
  };

  const insertIndent = () => {
    commit(value.slice(0, caret) + "  " + value.slice(caret), caret + 2);
  };

  const acceptGhost = () => {
    if (!activeGhost) {
      insertIndent();
      return;
    }
    const next = value.slice(0, caret) + activeGhost + value.slice(caret);
    const pos = caret + activeGhost.length;
    setGhost(null);
    commit(next, pos);
  };

  const handleKeyDown = (event: ReactKeyboardEvent<HTMLTextAreaElement>) => {
    if (menu && menu.items.length > 0) {
      const count = menu.items.length;
      if (event.key === "ArrowDown") {
        event.preventDefault();
        setActiveIndex((index) => (index + 1) % count);
        return;
      }
      if (event.key === "ArrowUp") {
        event.preventDefault();
        setActiveIndex((index) => (index - 1 + count) % count);
        return;
      }
      if (event.key === "Enter" || event.key === "Tab") {
        event.preventDefault();
        const item = menu.items[activeIndex] ?? menu.items[0];
        if (item) applyItem(item);
        return;
      }
      if (event.key === "Escape") {
        event.preventDefault();
        setMenu(null);
        menuOpenRef.current = false;
        return;
      }
    }
    if (event.key === "Tab") {
      event.preventDefault();
      acceptGhost();
      return;
    }
    if (event.key === "Escape") {
      setGhost(null);
      setMenu(null);
      menuOpenRef.current = false;
    }
  };

  const menuStyle = menu
    ? {
        top: PAD_TOP + (caretLineIndex + 1) * LINE_HEIGHT,
        left: Math.max(0, caretCol * charWidth),
      }
    : undefined;

  const ghostCount = activeGhost ? completionLineCount(activeGhost) : 0;

  return (
    <div className="ca">
      <div className="ca-gutter">
        {lines.map((_, index) => (
          <div
            className={`ca-ln${changedLines?.has(index + 1) ? " is-changed" : ""}`}
            key={index}
          >
            {index + 1}
          </div>
        ))}
      </div>
      <div className="ca-stage">
        <div className="ca-hl" aria-hidden="true">
          {lines.map((line, index) => {
            const isChanged = changedLines?.has(index + 1) ?? false;
            const className = `ca-line${isChanged ? " is-changed" : ""}`;
            if (activeGhost && index === caretLineIndex) {
              return (
                <div className={className} key={index}>
                  {renderTokens(line.slice(0, caretCol))}
                  <span className="ca-ghost">{ghostLine(activeGhost)}</span>
                  {ghostCount > 1 ? <span className="ca-ghost-more">{` +${ghostCount - 1} 行`}</span> : null}
                  {renderTokens(line.slice(caretCol))}
                </div>
              );
            }
            return (
              <div className={className} key={index}>
                {renderTokens(line)}
              </div>
            );
          })}
        </div>
        <textarea
          ref={textareaRef}
          className="ca-input"
          value={value}
          onChange={handleChange}
          onKeyDown={handleKeyDown}
          onSelect={handleSelect}
          onBlur={() => {
            setMenu(null);
            menuOpenRef.current = false;
          }}
          readOnly={readOnly}
          spellCheck={false}
          autoCorrect="off"
          autoCapitalize="off"
          wrap="off"
          aria-label={`${path} 编辑器`}
        />
        {menu && menu.items.length > 0 ? (
          <div className="ca-menu" role="listbox" style={menuStyle}>
            {menu.items.map((item, index) => (
              <button
                type="button"
                key={`${item.kind}-${item.label}`}
                className={`ca-item${index === activeIndex ? " is-active" : ""}`}
                role="option"
                aria-selected={index === activeIndex}
                onMouseDown={(event) => {
                  event.preventDefault();
                  applyItem(item);
                }}
              >
                <span className="ca-badge">{KIND_BADGE[item.kind]}</span>
                <span className="ca-label">{item.label}</span>
                <span className="ca-detail">{item.detail}</span>
              </button>
            ))}
          </div>
        ) : null}
        <span className="ca-metric" ref={metricRef} aria-hidden="true">
          {METRIC_CHARS}
        </span>
      </div>
    </div>
  );
}

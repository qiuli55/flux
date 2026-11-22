/**
 * 聊天区贴底滚动：只有用户本来就在底部（<80px）时才跟随新内容；
 * 上翻阅读期间保持位置，并由调用方展示“回到最新”。
 */
import { useCallback, useLayoutEffect, useRef, useState, type RefObject } from "react";

const STICK_THRESHOLD_PX = 80;

export interface StickToBottom {
  ref: RefObject<HTMLDivElement | null>;
  atBottom: boolean;
  hasNewContent: boolean;
  onScroll: () => void;
  scrollToBottom: (behavior?: ScrollBehavior) => void;
}

export function useStickToBottom(revision: number): StickToBottom {
  const ref = useRef<HTMLDivElement>(null);
  const stickRef = useRef(true);
  const [atBottom, setAtBottom] = useState(true);
  const [hasNewContent, setHasNewContent] = useState(false);

  const onScroll = useCallback(() => {
    const element = ref.current;
    if (!element) return;
    const gap = element.scrollHeight - element.scrollTop - element.clientHeight;
    const stuck = gap <= STICK_THRESHOLD_PX;
    stickRef.current = stuck;
    setAtBottom((previous) => (previous === stuck ? previous : stuck));
    if (stuck) setHasNewContent(false);
  }, []);

  const scrollToBottom = useCallback((behavior: ScrollBehavior = "auto") => {
    const element = ref.current;
    if (!element) return;
    stickRef.current = true;
    setAtBottom(true);
    setHasNewContent(false);
    element.scrollTo({ top: element.scrollHeight, behavior });
  }, []);

  useLayoutEffect(() => {
    const element = ref.current;
    if (!element) return;
    if (stickRef.current) {
      // 用 auto 而不是 smooth：流式增量每 50ms 一次，平滑滚动会互相打断
      element.scrollTop = element.scrollHeight;
    } else {
      setHasNewContent((previous) => (previous ? previous : true));
    }
  }, [revision]);

  return { ref, atBottom, hasNewContent, onScroll, scrollToBottom };
}

/**
 * 极简 SSE 帧解析器（纯函数、可单测）。
 *
 * 只负责把字节流切成帧，不关心业务事件：
 * - 支持跨 chunk 残帧、CRLF / CR / LF 三种行尾；
 * - 支持多行 data:（按规范的换行拼接）；
 * - `:` 开头的心跳行忽略；
 * - 流结束时 flush 仍可吐出最后一个未以空行收尾的帧。
 */

export interface SseFrame {
  event: string | null;
  data: string;
  id: string | null;
}

export interface SseParser {
  push(chunk: string): SseFrame[];
  flush(): SseFrame[];
}

export function createSseParser(): SseParser {
  let buffer = "";

  const drain = (final: boolean): SseFrame[] => {
    // 行尾归一化；如果 chunk 恰好以 \r 结尾，先扣住，避免把下一段开头的 \n 误判成空行
    let text = buffer;
    let tail = "";
    if (text.endsWith("\r")) {
      tail = "\r";
      text = text.slice(0, -1);
    }
    buffer = text.replace(/\r\n/g, "\n").replace(/\r/g, "\n") + tail;

    const frames: SseFrame[] = [];
    let boundary = buffer.indexOf("\n\n");
    while (boundary >= 0) {
      const raw = buffer.slice(0, boundary);
      buffer = buffer.slice(boundary + 2);
      const frame = parseFrame(raw);
      if (frame) frames.push(frame);
      boundary = buffer.indexOf("\n\n");
    }

    if (final && buffer.length > 0) {
      const frame = parseFrame(buffer);
      buffer = "";
      if (frame) frames.push(frame);
    }
    return frames;
  };

  return {
    push(chunk: string) {
      buffer += chunk;
      return drain(false);
    },
    flush() {
      return drain(true);
    },
  };
}

function parseFrame(raw: string): SseFrame | null {
  if (!raw) return null;
  const dataLines: string[] = [];
  let event: string | null = null;
  let id: string | null = null;

  for (const line of raw.split("\n")) {
    if (!line || line.startsWith(":")) continue;
    const colon = line.indexOf(":");
    const field = colon === -1 ? line : line.slice(0, colon);
    let value = colon === -1 ? "" : line.slice(colon + 1);
    if (value.startsWith(" ")) value = value.slice(1);
    if (field === "data") dataLines.push(value);
    else if (field === "event") event = value;
    else if (field === "id") id = value;
  }

  if (dataLines.length === 0) return null;
  return { event, data: dataLines.join("\n"), id };
}

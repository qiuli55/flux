/**
 * IDE 代码补全的纯函数：候选生成、替换区间推导、插入应用、AI 提示词拼装。
 *
 * 与 React / DOM 无关，抽出来是为了能在 vitest（无 DOM 环境）里直接覆盖，
 * 也让「内联 ghost 建议」与「下拉候选」共用同一套判定（同一个前缀触发，
 * 不会出现两处规则不一致）。
 *
 * 四种候选同源：
 * - word    当前文件里出现过的标识符 + 语言关键字；
 * - snippet 少量手写片段（function / def / for …）；
 * - path    字符串字面量里的相对路径，用工作区文件列表匹配；
 * - command 行首的 / 命令：不写进正文，交给宿主触发一次 AI 动作。
 */
import type { ChatMessageIn } from "../api/types";

/** 候选来源；下拉里用不同角标区分 */
export type CompletionKind = "keyword" | "word" | "snippet" | "path" | "command";

export interface CompletionItem {
  /** 下拉里展示的主文本 */
  label: string;
  /** 右侧灰字说明 */
  detail: string;
  kind: CompletionKind;
  /** 替换 [start, end) 的内容；command 模式为空串（命令要从正文里抹掉，不进文件） */
  insertText: string;
}

export interface CompletionState {
  items: CompletionItem[];
  /** 被替换区间的起止偏移（左闭右开） */
  start: number;
  end: number;
  /** 已输入的前缀，用于下拉里的高亮 */
  prefix: string;
  mode: "word" | "path" | "command";
}

/** 行首斜杠命令：选中后从正文里抹掉，转而请求一次带指令的 AI 补全 */
export interface CompletionCommand {
  /** 触发词（含前导斜杠），如 "/explain" */
  name: string;
  detail: string;
  /** 交给模型的动作描述 */
  instruction: string;
}

export const COMPLETION_COMMANDS: CompletionCommand[] = [
  { name: "/explain", detail: "解释这段代码", instruction: "用中文解释这段代码做了什么，控制在 3 行以内。" },
  { name: "/fix", detail: "修复这段代码的问题", instruction: "找出这段代码里的问题，给出修复后的完整实现。" },
  { name: "/test", detail: "为这段代码生成测试", instruction: "为这段代码生成单元测试，覆盖正常路径与边界。" },
  { name: "/docs", detail: "补上文档注释", instruction: "为这段代码补上简洁的文档注释。" },
  { name: "/refactor", detail: "重构这段代码", instruction: "重构这段代码，保持行为不变，给出完整的替代实现。" },
  { name: "/comment", detail: "补上行内注释", instruction: "为这段代码补上简洁的行内注释。" },
];

/* ---------- 语言资产 ---------- */

/** 高亮口径，与 IdePage 的 lexerKind(path) 同口径 */
export type LexerKind = "md" | "py" | "code";

const CODE_KEYWORDS = [
  "async", "await", "break", "case", "catch", "class", "const", "continue", "default", "delete",
  "else", "export", "extends", "finally", "for", "from", "function", "if", "implements", "import",
  "in", "instanceof", "interface", "let", "new", "null", "of", "private", "public", "return",
  "static", "super", "switch", "this", "throw", "try", "type", "typeof", "undefined", "var",
  "void", "while", "yield",
];

const PY_KEYWORDS = [
  "and", "as", "assert", "async", "await", "break", "class", "continue", "def", "del", "elif",
  "else", "except", "False", "finally", "for", "from", "global", "if", "import", "in", "is",
  "lambda", "None", "nonlocal", "not", "or", "pass", "raise", "return", "self", "True", "try",
  "while", "with", "yield",
];

interface Snippet {
  label: string;
  body: string;
  detail: string;
}

const CODE_SNIPPETS: Snippet[] = [
  { label: "fn", body: "function name() {\n  \n}", detail: "函数" },
  { label: "afn", body: "async function name() {\n  \n}", detail: "异步函数" },
  { label: "for", body: "for (let i = 0; i < n; i += 1) {\n  \n}", detail: "循环" },
  { label: "if", body: "if (condition) {\n  \n}", detail: "条件" },
  { label: "try", body: "try {\n  \n} catch (error) {\n  \n}", detail: "异常处理" },
  { label: "log", body: "console.log()", detail: "打印" },
];

const PY_SNIPPETS: Snippet[] = [
  { label: "def", body: "def name(args):\n    pass", detail: "函数" },
  { label: "class", body: "class Name:\n    def __init__(self) -> None:\n        pass", detail: "类" },
  { label: "for", body: "for item in items:\n    pass", detail: "循环" },
  { label: "if", body: "if condition:\n    pass", detail: "条件" },
  { label: "try", body: "try:\n    pass\nexcept Exception as error:\n    raise", detail: "异常处理" },
  { label: "main", body: 'if __name__ == "__main__":\n    main()', detail: "入口" },
];

/** 语言 → 送进模型的可读名（提示词里用） */
export function languageLabel(kind: LexerKind): string {
  if (kind === "py") return "Python";
  if (kind === "md") return "Markdown";
  return "TypeScript / JavaScript";
}

/* ---------- 偏移工具 ---------- */

function isWordChar(ch: string): boolean {
  return /[A-Za-z0-9_$]/.test(ch);
}

/** 光标所在行的起始偏移 */
export function lineStart(text: string, caret: number): number {
  return text.lastIndexOf("\n", caret - 1) + 1;
}

/** 光标所在行的结束偏移（不含换行符） */
export function lineEnd(text: string, caret: number): number {
  const index = text.indexOf("\n", caret);
  return index === -1 ? text.length : index;
}

/** 光标处标识符词的区间：[start, end) 覆盖整个词（含光标之后的尾巴） */
export function wordRange(text: string, caret: number): { start: number; end: number } {
  let start = caret;
  while (start > 0 && isWordChar(text[start - 1] ?? "")) start -= 1;
  let end = caret;
  while (end < text.length && isWordChar(text[end] ?? "")) end += 1;
  return { start, end };
}

/** 路径类 token 的字符集：字母数字 + _ - . / \ @ */
const PATH_CHAR = /[A-Za-z0-9_\-./\\@]/;

function pathRange(text: string, caret: number): { start: number; end: number } {
  let start = caret;
  while (start > 0 && PATH_CHAR.test(text[start - 1] ?? "")) start -= 1;
  let end = caret;
  while (end < text.length && PATH_CHAR.test(text[end] ?? "")) end += 1;
  return { start, end };
}

/** 光标是否落在字符串字面量里（逐字符数未转义引号，够用即可） */
export function insideString(line: string, col: number): boolean {
  let quote: string | null = null;
  for (let i = 0; i < col; i += 1) {
    const ch = line[i];
    if (ch === "\\") {
      i += 1;
      continue;
    }
    if (quote) {
      if (ch === quote) quote = null;
    } else if (ch === '"' || ch === "'" || ch === "`") {
      quote = ch;
    }
  }
  return quote !== null;
}

/** 某偏移所在行的前导空白（插入多行片段时逐行复用） */
export function leadingIndent(text: string, offset: number): string {
  const match = /^[ \t]*/.exec(text.slice(lineStart(text, offset), offset));
  return match ? match[0] : "";
}

/* ---------- 候选生成 ---------- */

const IDENTIFIER_RE = /[A-Za-z_$][A-Za-z0-9_$]+/g;

/** 本文档里出现过的标识符（长度 ≥ 2），按出现次数降序 */
export function collectWords(text: string, limit = 400): { word: string; count: number }[] {
  const counts = new Map<string, number>();
  IDENTIFIER_RE.lastIndex = 0;
  let match = IDENTIFIER_RE.exec(text);
  while (match !== null) {
    counts.set(match[0], (counts.get(match[0]) ?? 0) + 1);
    match = IDENTIFIER_RE.exec(text);
  }
  return [...counts.entries()]
    .map(([word, count]) => ({ word, count }))
    .sort((a, b) => b.count - a.count || a.word.length - b.word.length || a.word.localeCompare(b.word))
    .slice(0, limit);
}

function keywordItems(kind: LexerKind): CompletionItem[] {
  if (kind === "md") return [];
  const words = kind === "py" ? PY_KEYWORDS : CODE_KEYWORDS;
  return words.map((word) => ({ label: word, detail: "关键字", kind: "keyword" as const, insertText: word }));
}

function snippetItems(kind: LexerKind): CompletionItem[] {
  if (kind === "md") return [];
  const list = kind === "py" ? PY_SNIPPETS : CODE_SNIPPETS;
  return list.map((snippet) => ({
    label: snippet.label,
    detail: snippet.detail,
    kind: "snippet" as const,
    insertText: snippet.body,
  }));
}

function dedupe(items: CompletionItem[]): CompletionItem[] {
  const seen = new Set<string>();
  const out: CompletionItem[] = [];
  for (const item of items) {
    if (seen.has(item.label)) continue;
    seen.add(item.label);
    out.push(item);
  }
  return out;
}

function commandCompletion(text: string, caret: number): CompletionState | null {
  const start = lineStart(text, caret);
  const head = text.slice(start, caret);
  const match = /^\s*\/([A-Za-z]*)$/.exec(head);
  if (!match) return null;
  const prefix = match[0].trim();
  const slashAt = start + head.indexOf("/");
  const items = COMPLETION_COMMANDS.filter((command) =>
    command.name.toLowerCase().startsWith(prefix.toLowerCase()),
  ).map((command) => ({
    label: command.name,
    detail: command.detail,
    kind: "command" as const,
    insertText: "",
  }));
  if (items.length === 0) return null;
  return { items, start: slashAt, end: caret, prefix, mode: "command" };
}

function pathCompletion(text: string, caret: number, files: string[]): CompletionState | null {
  if (files.length === 0) return null;
  const start = lineStart(text, caret);
  const line = text.slice(start, lineEnd(text, caret));
  if (!insideString(line, caret - start)) return null;
  const range = pathRange(text, caret);
  const prefix = text.slice(range.start, caret);
  // 只有看起来像路径才提示：带 / 或以 . 开头
  if (!prefix || (!prefix.includes("/") && !prefix.startsWith("."))) return null;
  const query = prefix.replace(/^\.?\//, "").toLowerCase();
  const slash = query.lastIndexOf("/");
  const dir = slash >= 0 ? query.slice(0, slash + 1) : "";
  const base = slash >= 0 ? query.slice(slash + 1) : query;
  const head = prefix.startsWith("./") ? "./" : "";
  const items = files
    .filter((file) => {
      const lower = file.toLowerCase();
      if (!lower.startsWith(dir)) return false;
      const name = lower.slice(dir.length);
      return name.startsWith(base) && name !== base;
    })
    .slice(0, 20)
    .map((file) => ({
      label: file,
      detail: "工作区路径",
      kind: "path" as const,
      insertText: `${head}${file}`,
    }));
  if (items.length === 0) return null;
  return { items, start: range.start, end: range.end, prefix, mode: "path" };
}

function wordCompletion(text: string, caret: number, kind: LexerKind): CompletionState | null {
  const range = wordRange(text, caret);
  const prefix = text.slice(range.start, caret);
  if (prefix.length < 1) return null;
  const lower = prefix.toLowerCase();
  const scored: { item: CompletionItem; score: number }[] = [];
  const push = (item: CompletionItem, base: number) => {
    if (item.label === prefix) return; // 已经输完，不再提示
    if (!item.label.toLowerCase().startsWith(lower)) return;
    const bonus = item.label.startsWith(prefix) ? 40 : 0;
    scored.push({ item, score: base + bonus - item.label.length * 0.2 });
  };
  // 片段展开信息量更大，同名前缀下排在关键字之前（如 if → if (condition) {…}）
  for (const item of snippetItems(kind)) push(item, 320);
  for (const item of keywordItems(kind)) push(item, 300);
  for (const { word, count } of collectWords(text)) {
    push({ label: word, detail: "本文档", kind: "word", insertText: word }, 100 + Math.min(count, 40));
  }
  scored.sort(
    (a, b) =>
      b.score - a.score ||
      a.item.label.length - b.item.label.length ||
      a.item.label.localeCompare(b.item.label),
  );
  const items = dedupe(scored.map((entry) => entry.item)).slice(0, 8);
  if (items.length === 0) return null;
  return { items, start: range.start, end: range.end, prefix, mode: "word" };
}

export interface CompletionRequest {
  text: string;
  caret: number;
  kind: LexerKind;
  /** 工作区文件路径（路径补全用） */
  files?: string[];
}

/**
 * 按优先级给一组候选：命令 > 路径 > 词。
 * 返回 null 表示当前光标位置没有可提示的东西（宿主据此关掉下拉）。
 */
export function buildCompletion({ text, caret, kind, files = [] }: CompletionRequest): CompletionState | null {
  return commandCompletion(text, caret) ?? pathCompletion(text, caret, files) ?? wordCompletion(text, caret, kind);
}

export interface AppliedCompletion {
  text: string;
  caret: number;
}

/** 把选中的候选写进正文：多行片段按当前行缩进补齐，光标落在插入内容末尾 */
export function applyCompletion(text: string, state: CompletionState, item: CompletionItem): AppliedCompletion {
  const indent = leadingIndent(text, state.start);
  const inserted = item.insertText.replace(/\n/g, `\n${indent}`);
  return {
    text: `${text.slice(0, state.start)}${inserted}${text.slice(state.end)}`,
    caret: state.start + inserted.length,
  };
}

/* ---------- AI 补全：提示词与结果清洗 ---------- */

/** 送入模型的最大上下文行数：光标前 / 后 */
export const AI_BEFORE_LINES = 80;
export const AI_AFTER_LINES = 20;
/** 触发 AI 补全的输入停顿（毫秒） */
export const AI_DEBOUNCE_MS = 400;

const AI_SYSTEM_PROMPT =
  "你是代码补全引擎。只输出应当插入到 <CURSOR> 处的代码本身，" +
  "不要解释、不要 Markdown 代码围栏、不要重复光标前已有的内容、不要输出多余缩进。";

export interface AiCompletionInput {
  path: string;
  kind: LexerKind;
  text: string;
  caret: number;
  /** 行首命令带来的动作指令；没有则是普通续写 */
  instruction?: string;
}

/** 光标前 / 后各取若干行，拼成一次补全请求的 messages */
export function buildCompletionMessages({ path, kind, text, caret, instruction }: AiCompletionInput): ChatMessageIn[] {
  const before = text.slice(0, caret).split("\n");
  const after = text.slice(caret).split("\n");
  const head = before.slice(Math.max(0, before.length - AI_BEFORE_LINES)).join("\n");
  const tail = after.slice(0, AI_AFTER_LINES).join("\n");
  const brief = instruction ?? "补全 <CURSOR> 处的代码。";
  const user = [
    `文件：${path}`,
    `语言：${languageLabel(kind)}`,
    `任务：${brief}`,
    "",
    "```" + (kind === "py" ? "python" : "typescript"),
    `${head}<CURSOR>${tail}`,
    "```",
  ].join("\n");
  return [
    { role: "system", content: AI_SYSTEM_PROMPT },
    { role: "user", content: user },
  ];
}

/** 洗掉模型爱加的围栏与行尾空白；返回空串表示没有可用补全 */
export function cleanCompletion(raw: string): string {
  let text = raw.replace(/\r\n/g, "\n");
  const fenced = /^\s*```[^\n]*\n([\s\S]*?)\n?```\s*$/.exec(text);
  if (fenced) text = fenced[1] ?? "";
  text = text.replace(/^```[^\n]*\n?/, "").replace(/\n?```\s*$/, "");
  return text.replace(/[ \t]+$/gm, "").replace(/\n+$/, "").replace(/^\n+/, "");
}

/** 内联 ghost 只展示第一行（多行会让高亮层与输入框错位） */
export function ghostLine(text: string): string {
  const index = text.indexOf("\n");
  return index === -1 ? text : text.slice(0, index);
}

/** 补全共几行（下拉/提示里标注 "还有 N 行"） */
export function completionLineCount(text: string): number {
  return text.length === 0 ? 0 : text.split("\n").length;
}

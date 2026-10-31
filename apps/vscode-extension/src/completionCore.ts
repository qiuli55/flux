/**
 * 行内补全的纯函数核心（AI_COMPLETION_SPEC §4 / §8）。
 *
 * 这里只放不依赖 `vscode` 模块的判断逻辑：能否补、要不要跳过、请求怎么去重、
 * 空行 / 注释 / 敏感文件怎么识别。抽出来的目的有两个：
 * 1. 可以在 Node 里直接单测（`tests/completionCore.test.ts`），不用起 IDE；
 * 2. `completion.ts` 只负责编排，边界判断集中在一处，避免各处规则漂移。
 */

/** 允许触发补全的 URI scheme；`git:` 等只读虚拟文档不在其列。 */
export const COMPLETION_SCHEMES: readonly string[] = ["file", "untitled", "vscode-remote"];

/** 这些语言没有「下一段代码」可言，触发只会白烧 token。 */
export const BLOCKED_LANGUAGES: ReadonlySet<string> = new Set([
  "plaintext",
  "log",
  "scminput",
  "search-result",
  "git-commit",
  "git-rebase",
  "git-merge",
  "diff",
  "output",
  "code-text-binary",
  "binary",
]);

/**
 * 默认不外发的路径（相对工作区，大小写不敏感）。
 *
 * 敏感文件一旦进了 prompt 就等于出网：.env / 私钥 / 云厂商凭据 / 包管理器 token
 * 都在这里兜底。生成物、依赖目录、锁文件也一并排除——它们既没有补全价值，
 * 又常常大到把上下文预算撑爆。
 */
export const DEFAULT_BLOCKED_PATTERNS: readonly string[] = [
  "**/.env",
  "**/.env.*",
  "**/*.pem",
  "**/*.key",
  "**/*.p12",
  "**/*.pfx",
  "**/*.jks",
  "**/*.keystore",
  "**/id_rsa*",
  "**/id_dsa*",
  "**/id_ecdsa*",
  "**/id_ed25519*",
  "**/secrets*",
  "**/credentials*",
  "**/*secret*.json",
  "**/.npmrc",
  "**/.pypirc",
  "**/.netrc",
  "**/.git-credentials",
  "**/.git/**",
  "**/node_modules/**",
  "**/.venv/**",
  "**/venv/**",
  "**/__pycache__/**",
  "**/*.min.js",
  "**/*.min.css",
  "**/*.map",
  "**/package-lock.json",
  "**/pnpm-lock.yaml",
  "**/yarn.lock",
  "**/uv.lock",
  "**/poetry.lock",
  "**/Cargo.lock",
  "**/go.sum",
];

export type CompletionMode = "insert" | "line";

export interface CompletionKeyInput {
  uri: string;
  version: number;
  offset: number;
  model: string;
  maxLines: number;
  mode: CompletionMode;
}

export interface CursorAnchor {
  version: number;
  offset: number;
}

export interface SessionAnchor {
  key: string;
  uri: string;
}

/** 从 URI 字符串里取 scheme；非法输入返回空串。 */
export function schemeOf(uri: string): string {
  const match = /^([a-zA-Z][a-zA-Z0-9+.-]*):/.exec(uri || "");
  return match ? match[1].toLowerCase() : "";
}

/** scheme 是否允许触发补全。 */
export function isSupportedScheme(uri: string): boolean {
  return COMPLETION_SCHEMES.includes(schemeOf(uri));
}

/** 语言是否在黑名单里（`plaintext` 之类的文档不该请求模型）。 */
export function isBlockedLanguage(languageId: string): boolean {
  return BLOCKED_LANGUAGES.has((languageId || "").trim().toLowerCase());
}

function normalizePath(value: string): string {
  let path = (value || "").replace(/\\/g, "/").trim();
  while (path.startsWith("./")) {
    path = path.slice(2);
  }
  return path.replace(/^\/+/, "");
}

function escapeRegex(char: string): string {
  return char.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

/**
 * 极简 glob 匹配（`**` / `*` / `?`，大小写不敏感）。
 *
 * 只覆盖配置里会用到的形态，不引入额外依赖；语义与 VS Code 的
 * `files.exclude` 保持一致：`**` 跨目录、`*` 不跨 `/`、`?` 单字符。
 */
export function matchesGlob(path: string, pattern: string): boolean {
  const target = normalizePath(path).toLowerCase();
  const glob = normalizePath(pattern).toLowerCase();
  if (!target || !glob) {
    return false;
  }
  // 不含分支的模式按文件名匹配，这样 `*.pem` 能命中任意目录下的证书
  const subject = glob.includes("/") ? target : target.split("/").pop() || target;
  const source = glob.includes("/") ? glob : glob.replace(/^\*\*\//, "");

  let regex = "";
  for (let index = 0; index < source.length; index += 1) {
    const char = source[index];
    if (char === "*") {
      if (source[index + 1] === "*") {
        index += 1;
        if (source[index + 1] === "/") {
          index += 1;
          regex += "(?:.*/)?";
        } else {
          regex += ".*";
        }
        continue;
      }
      regex += "[^/]*";
      continue;
    }
    if (char === "?") {
      regex += "[^/]";
      continue;
    }
    regex += escapeRegex(char);
  }
  try {
    return new RegExp(`^${regex}$`).test(subject);
  } catch {
    return false;
  }
}

/** 路径是否命中任一屏蔽规则（内置敏感文件 + 用户追加）。 */
export function isBlockedPath(relativePath: string, patterns: readonly string[]): boolean {
  const path = normalizePath(relativePath);
  if (!path) {
    return false;
  }
  for (const pattern of [...DEFAULT_BLOCKED_PATTERNS, ...patterns]) {
    if (pattern && matchesGlob(path, pattern)) {
      return true;
    }
  }
  return false;
}

/** 会话缓存键：同一位置、同一版本、同一模型 / 行数 / 模式才复用。 */
export function completionKey(input: CompletionKeyInput): string {
  return [input.uri, input.version, input.offset, input.model, input.maxLines, input.mode].join("|");
}

/** 版本没变、只是光标挪了：说明用户没有输入，不必重新请求。 */
export function isCursorOnlyMove(previous: CursorAnchor | undefined, current: CursorAnchor): boolean {
  return !!previous && previous.version === current.version && previous.offset !== current.offset;
}

/** 同一文件里已经过期的会话 key（换位置 / 继续输入后要主动 abort 掉）。 */
export function staleSessionKeys(
  sessions: Iterable<SessionAnchor>,
  uri: string,
  keepKey?: string
): string[] {
  const stale: string[] = [];
  for (const session of sessions) {
    if (session.uri === uri && session.key !== keepKey) {
      stale.push(session.key);
    }
  }
  return stale;
}

type LanguageFamily =
  | "python"
  | "slash"
  | "hash"
  | "sql"
  | "markup"
  | "css"
  | "lua"
  | "latex"
  | "semicolon"
  | "none";

interface Lexicon {
  line: string[];
  block: Array<[string, string]>;
  quotes: string[];
  triple: string[];
  /** SQL 用两个连续单引号表示转义，而不是反斜杠 */
  doubledQuote?: boolean;
}

const EMPTY_LEXICON: Lexicon = { line: [], block: [], quotes: [], triple: [] };

const LEXICONS: Record<LanguageFamily, Lexicon> = {
  python: {
    line: ["#"],
    block: [],
    quotes: ["'", '"'],
    triple: ["'''", '"""'],
  },
  slash: {
    line: ["//"],
    block: [["/*", "*/"]],
    quotes: ["'", '"', "`"],
    triple: [],
  },
  hash: {
    line: ["#"],
    block: [],
    quotes: ["'", '"'],
    triple: [],
  },
  sql: {
    line: ["--"],
    block: [["/*", "*/"]],
    quotes: ["'", '"', "`"],
    triple: [],
    doubledQuote: true,
  },
  markup: {
    line: [],
    block: [["<!--", "-->"]],
    quotes: ['"', "'", "`"],
    triple: [],
  },
  css: {
    line: [],
    block: [["/*", "*/"]],
    quotes: ['"', "'"],
    triple: [],
  },
  lua: {
    line: ["--"],
    block: [["--[[", "]]"]],
    quotes: ["'", '"'],
    triple: [],
  },
  latex: { line: ["%"], block: [], quotes: [], triple: [] },
  semicolon: { line: [";"], block: [], quotes: ['"'], triple: [] },
  none: EMPTY_LEXICON,
};

function familyOf(languageId: string): LanguageFamily {
  const id = (languageId || "").trim().toLowerCase();
  switch (id) {
    case "python":
    case "python3":
    case "cython":
      return "python";
    case "sql":
    case "mysql":
    case "pgsql":
    case "plsql":
      return "sql";
    case "html":
    case "xml":
    case "xsl":
    case "vue":
    case "svelte":
    case "markdown":
    case "mdx":
      return "markup";
    case "css":
    case "scss":
    case "less":
      return "css";
    case "lua":
      return "lua";
    case "latex":
    case "tex":
      return "latex";
    case "lisp":
    case "clojure":
    case "scheme":
    case "racket":
      return "semicolon";
    case "ruby":
    case "shellscript":
    case "powershell":
    case "yaml":
    case "toml":
    case "ini":
    case "properties":
    case "dockerfile":
    case "makefile":
    case "perl":
    case "r":
    case "julia":
    case "elixir":
    case "erlang":
    case "coffeescript":
    case "cmake":
      return "hash";
    case "plaintext":
    case "log":
      return "none";
    default:
      // 绝大多数编程语言都是 C 系注释 + 引号，未知语言按 C 系处理
      return "slash";
  }
}

/**
 * 光标是否落在注释或字符串里。
 *
 * `linePrefix` 只传当前行从行首到光标的文本——跨行状态（多行块注释 / 三引号）
 * 无法只靠这一行判断，所以这里对「未闭合」一律按「在里面」处理：
 * 宁可少补一次，也不要在注释里瞎生成代码。
 */
export function insideCommentOrString(linePrefix: string, languageId: string): boolean {
  const lexicon = LEXICONS[familyOf(languageId)];
  const text = linePrefix || "";
  let index = 0;

  while (index < text.length) {
    const rest = text.slice(index);

    const block = lexicon.block.find(([open]) => rest.startsWith(open));
    if (block) {
      const closeAt = text.indexOf(block[1], index + block[0].length);
      if (closeAt < 0) {
        return true;
      }
      index = closeAt + block[1].length;
      continue;
    }

    const triple = lexicon.triple.find((open) => rest.startsWith(open));
    if (triple) {
      const closeAt = text.indexOf(triple, index + triple.length);
      if (closeAt < 0) {
        return true;
      }
      index = closeAt + triple.length;
      continue;
    }

    const quote = lexicon.quotes.includes(text[index]) ? text[index] : "";
    if (quote) {
      let cursor = index + 1;
      let closed = false;
      while (cursor < text.length) {
        if (text[cursor] === "\\") {
          cursor += 2;
          continue;
        }
        if (lexicon.doubledQuote && text[cursor] === quote && text[cursor + 1] === quote) {
          cursor += 2;
          continue;
        }
        if (text[cursor] === quote) {
          closed = true;
          cursor += 1;
          break;
        }
        cursor += 1;
      }
      if (!closed) {
        return true;
      }
      index = cursor;
      continue;
    }

    if (lexicon.line.some((token) => rest.startsWith(token))) {
      return true;
    }

    index += 1;
  }

  return false;
}

/**
 * 清理模型输出：统一换行、去掉尾部空白 / 多余空行。
 *
 * 保留缩进和内部空行（代码里它们有意义），只处理收尾——否则 ghost 接受后
 * 会在文件末尾平白多出若干空行。
 */
export function sanitizeCompletion(text: string, mode: CompletionMode): string {
  let value = (text || "").replace(/\r\n?/g, "\n");
  if (mode === "line") {
    // 整行模式只保留第一行：这个模式替换的是「当前这一行」，多出来的行不该进来。
    // 模型偶尔先吐一个换行再写行内容，先丢掉，避免把当前行直接清空。
    value = value.replace(/^\n+/, "");
    const firstBreak = value.indexOf("\n");
    if (firstBreak >= 0) {
      value = value.slice(0, firstBreak);
    }
  }
  return value.replace(/[ \t\r\n]+$/, "");
}

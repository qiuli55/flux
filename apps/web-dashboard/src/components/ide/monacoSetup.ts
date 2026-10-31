/**
 * Monaco 的一次性装配：Worker、主题、语言映射、缺省语法。
 *
 * 三件事都只做一次，由模块加载时的副作用完成（Vite 保证单例）：
 * 1. `MonacoEnvironment.getWorker` —— 按 label 返回对应 worker，Vite 用 `?worker` 打成独立 chunk；
 * 2. `flux-dark` 主题 —— 取色全部来自 styles/tokens.css，和 Web 端其它面板同一套色板；
 * 3. 语言映射 —— 后端 registry 的 languageId 与 Monaco 内置 id 并不完全同名（shellscript / proto …），
 *    后端有而 Monaco 没有的（zig / nix / cmake …）用一份通用 Monarch 兜底，保证至少有注释/字符串/关键字着色。
 */
import * as monaco from "monaco-editor";
import EditorWorker from "monaco-editor/esm/vs/editor/editor.worker?worker";
import CssWorker from "monaco-editor/esm/vs/language/css/css.worker?worker";
import HtmlWorker from "monaco-editor/esm/vs/language/html/html.worker?worker";
import JsonWorker from "monaco-editor/esm/vs/language/json/json.worker?worker";
import TsWorker from "monaco-editor/esm/vs/language/typescript/ts.worker?worker";

import type { IdeCatalog } from "../../api/types";

declare global {
  interface Window {
    MonacoEnvironment?: monaco.Environment;
  }
}

window.MonacoEnvironment = {
  getWorker(_workerId: string, label: string) {
    switch (label) {
      case "json":
        return new JsonWorker();
      case "css":
      case "scss":
      case "less":
        return new CssWorker();
      case "html":
      case "handlebars":
      case "razor":
        return new HtmlWorker();
      case "typescript":
      case "javascript":
        return new TsWorker();
      default:
        return new EditorWorker();
    }
  },
};

export const FLUX_THEME = "flux-dark";

monaco.editor.defineTheme(FLUX_THEME, {
  base: "vs-dark",
  inherit: true,
  rules: [
    { token: "", foreground: "e7ecea" },
    { token: "comment", foreground: "5f6b69", fontStyle: "italic" },
    { token: "comment.doc", foreground: "6d7a78", fontStyle: "italic" },
    { token: "keyword", foreground: "7fb6e0" },
    { token: "keyword.control", foreground: "7fb6e0" },
    { token: "keyword.operator", foreground: "9ca6a4" },
    { token: "operator", foreground: "9ca6a4" },
    { token: "delimiter", foreground: "6c7674" },
    { token: "string", foreground: "a8e6a3" },
    { token: "string.escape", foreground: "e8b25e" },
    { token: "string.invalid", foreground: "ff7a6e" },
    { token: "number", foreground: "e8b25e" },
    { token: "number.hex", foreground: "e8b25e" },
    { token: "regexp", foreground: "e8b25e" },
    { token: "type", foreground: "3df5a8" },
    { token: "type.identifier", foreground: "3df5a8" },
    { token: "class", foreground: "3df5a8" },
    { token: "entity.name.type", foreground: "3df5a8" },
    { token: "function", foreground: "d7e6ff" },
    { token: "identifier", foreground: "e7ecea" },
    { token: "variable", foreground: "e7ecea" },
    { token: "variable.predefined", foreground: "7fb6e0" },
    { token: "constant", foreground: "e8b25e" },
    { token: "tag", foreground: "7fb6e0" },
    { token: "attribute.name", foreground: "3df5a8" },
    { token: "attribute.value", foreground: "a8e6a3" },
    { token: "metatag", foreground: "7fb6e0" },
    { token: "key", foreground: "7fb6e0" },
    { token: "annotation", foreground: "e8b25e" },
    { token: "invalid", foreground: "ff7a6e" },
  ],
  colors: {
    "editor.background": "#0b0e11",
    "editor.foreground": "#e7ecea",
    "editorCursor.foreground": "#3df5a8",
    "editor.lineHighlightBackground": "#101418",
    "editor.lineHighlightBorder": "#00000000",
    "editor.selectionBackground": "#3df5a829",
    "editor.inactiveSelectionBackground": "#3df5a814",
    "editor.selectionHighlightBackground": "#3df5a81f",
    "editor.wordHighlightBackground": "#7fb6e01f",
    "editor.wordHighlightStrongBackground": "#3df5a826",
    "editor.findMatchBackground": "#e8b25e4d",
    "editor.findMatchHighlightBackground": "#e8b25e26",
    "editorLineNumber.foreground": "#49514f",
    "editorLineNumber.activeForeground": "#9ca6a4",
    "editorIndentGuide.background1": "#1b2024",
    "editorIndentGuide.activeBackground1": "#2b3238",
    "editorWhitespace.foreground": "#2b3238",
    "editorBracketMatch.background": "#3df5a81f",
    "editorBracketMatch.border": "#3df5a85c",
    "editorGutter.background": "#0b0e11",
    "editorGutter.addedBackground": "#3df5a8",
    "editorGutter.modifiedBackground": "#7fb6e0",
    "editorGutter.deletedBackground": "#ff7a6e",
    "editorOverviewRuler.border": "#00000000",
    "editorWidget.background": "#101418",
    "editorWidget.border": "#2b3238",
    "editorWidget.foreground": "#e7ecea",
    "editorSuggestWidget.background": "#101418",
    "editorSuggestWidget.border": "#2b3238",
    "editorSuggestWidget.selectedBackground": "#3df5a81f",
    "editorSuggestWidget.highlightForeground": "#3df5a8",
    "editorSuggestWidget.focusHighlightForeground": "#3df5a8",
    "editorHoverWidget.background": "#101418",
    "editorHoverWidget.border": "#2b3238",
    "editorHoverWidget.foreground": "#e7ecea",
    "editorError.foreground": "#ff7a6e",
    "editorWarning.foreground": "#e8b25e",
    "editorInfo.foreground": "#7fb6e0",
    "editorHint.foreground": "#6c7674",
    "editorError.border": "#00000000",
    "editorWarning.border": "#00000000",
    "editorInfo.border": "#00000000",
    "editorOverviewRuler.errorForeground": "#ff7a6e",
    "editorOverviewRuler.warningForeground": "#e8b25e",
    "editorOverviewRuler.infoForeground": "#7fb6e0",
    "editorInlayHint.background": "#161a1e",
    "editorInlayHint.foreground": "#6c7674",
    "editorInlayHint.typeForeground": "#6c7674",
    "editorInlayHint.parameterForeground": "#6c7674",
    "editorLink.activeForeground": "#3df5a8",
    "editorCodeLens.foreground": "#49514f",
    "editorLightBulb.foreground": "#e8b25e",
    "editorBracketHighlight.foreground1": "#3df5a8",
    "editorBracketHighlight.foreground2": "#7fb6e0",
    "editorBracketHighlight.foreground3": "#e8b25e",
    "editorBracketHighlight.foreground4": "#ff7a6e",
    "editorBracketHighlight.foreground5": "#a8e6a3",
    "editorBracketHighlight.foreground6": "#c792ea",
    "editorMultiCursor.primary.foreground": "#3df5a8",
    "editorMultiCursor.secondary.foreground": "#6c7674",
    "minimap.background": "#0b0e11",
    "minimap.selectionHighlight": "#3df5a829",
    "scrollbarSlider.background": "#2b323880",
    "scrollbarSlider.hoverBackground": "#3b454bcc",
    "scrollbarSlider.activeBackground": "#3df5a85c",
    "scrollbar.shadow": "#00000000",
    "editorStickyScroll.background": "#0b0e11",
    "editorStickyScrollHover.background": "#101418",
    "editorGroupHeader.tabsBackground": "#090b0d",
    "focusBorder": "#3df5a85c",
    "menu.background": "#101418",
    "menu.foreground": "#e7ecea",
    "menu.selectionBackground": "#3df5a81f",
    "input.background": "#0b0e11",
    "input.border": "#2b3238",
    "input.foreground": "#e7ecea",
    "list.hoverBackground": "#161a1e",
    "list.activeSelectionBackground": "#3df5a81f",
    "list.inactiveSelectionBackground": "#161a1e",
    "widget.shadow": "#00000000",
  },
});

/** 后端 languageId → Monaco 内置 languageId（不同名的那些） */
const LANGUAGE_ALIASES: Record<string, string> = {
  typescriptreact: "typescript",
  javascriptreact: "javascript",
  shellscript: "shell",
  proto: "protobuf",
  "objective-cpp": "objective-c",
  c: "cpp",
  sass: "scss",
  terraform: "hcl",
  mdx: "markdown",
  vue: "html",
  svelte: "html",
  bibtex: "latex",
};

interface FallbackSpec {
  keywords: string[];
  /** 行注释前缀（不传就没有行注释） */
  line?: string;
  /** 块注释开闭 */
  block?: [string, string];
}

/** Monaco 0.52 没有内置语法的语言：用通用 Monarch 兜底 */
const FALLBACK_LANGUAGES: Record<string, FallbackSpec> = {
  zig: {
    keywords: "const var fn pub export extern struct enum union error return if else while for switch catch try defer async await comptime inline noinline usingnamespace test break continue orelse and or".split(" "),
    line: "//",
  },
  prisma: {
    keywords: "model enum datasource generator type view provider url relation fields map id default updatedAt unique index".split(" "),
    line: "//",
  },
  haskell: {
    keywords: "module where import qualified hiding as data type newtype class instance deriving do case of let in if then else infixl infixr foreign".split(" "),
    line: "--",
    block: ["{-", "-}"],
  },
  erlang: {
    keywords: "module export import spec type record case of end fun when receive after try catch if begin".split(" "),
    line: "%",
  },
  typst: {
    keywords: "let set show import include if else for while in not and or none auto context return".split(" "),
    line: "//",
    block: ["/*", "*/"],
  },
  nix: {
    keywords: "let in with rec inherit if then else assert import builtins true false null".split(" "),
    line: "#",
    block: ["/*", "*/"],
  },
  cmake: {
    keywords: "cmake_minimum_required project set unset if elseif else endif foreach endforeach function endfunction macro endmacro include add_executable add_library add_subdirectory target_link_libraries target_include_directories target_compile_options target_compile_definitions install find_package message return option list string file".split(" "),
    line: "#",
  },
  makefile: {
    keywords: "include ifeq ifneq ifdef ifndef else endif define endef export unexport override vpath".split(" "),
    line: "#",
  },
  latex: {
    keywords: "documentclass usepackage begin end section subsection subsubsection paragraph chapter part textbf textit emph cite label ref eqref includegraphics item newcommand renewcommand input".split(" "),
    line: "%",
  },
  groovy: {
    keywords: "def class interface enum trait if else for while switch case break continue return try catch finally throw import package new static final void public private protected extends implements".split(" "),
    line: "//",
    block: ["/*", "*/"],
  },
};

function escapeForRegExp(text: string): string {
  return text.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

function buildFallbackMonarch(spec: FallbackSpec): monaco.languages.IMonarchLanguage {
  const root: monaco.languages.IMonarchLanguageRule[] = [
    [/[a-zA-Z_$][\w$]*/, { cases: { "@keywords": "keyword", "@default": "identifier" } }],
    { include: "@whitespace" },
    [/[{}()[\]]/, "@brackets"],
    [/[<>](?!@symbols)/, "@brackets"],
    [/@symbols/, { cases: { "@operators": "operator", "@default": "delimiter" } }],
    [/\d*\.\d+([eE][-+]?\d+)?/, "number.float"],
    [/0[xX][0-9a-fA-F]+/, "number.hex"],
    [/\d+/, "number"],
    [/[;,.]/, "delimiter"],
    [/"/, { token: "string.quote", bracket: "@open", next: "@stringDouble" }],
    [/'/, { token: "string.quote", bracket: "@open", next: "@stringSingle" }],
  ];
  const whitespace: monaco.languages.IMonarchLanguageRule[] = [[/[ \t\r\n]+/, "white"]];
  if (spec.line) whitespace.push([new RegExp(`${escapeForRegExp(spec.line)}.*$`), "comment"]);
  const tokenizer: Record<string, monaco.languages.IMonarchLanguageRule[]> = {
    root,
    whitespace,
    stringDouble: [
      [/[^"\\]+/, "string"],
      [/\\./, "string.escape"],
      [/"/, { token: "string.quote", bracket: "@close", next: "@pop" }],
    ],
    stringSingle: [
      [/[^'\\]+/, "string"],
      [/\\./, "string.escape"],
      [/'/, { token: "string.quote", bracket: "@close", next: "@pop" }],
    ],
  };
  if (spec.block) {
    const [open, close] = spec.block;
    root.splice(2, 0, [
      new RegExp(escapeForRegExp(open)),
      { token: "comment", next: "@blockComment" },
    ]);
    tokenizer.blockComment = [
      [new RegExp(escapeForRegExp(close)), { token: "comment", next: "@pop" }],
      [/./, "comment"],
    ];
  }
  return {
    defaultToken: "",
    tokenPostfix: "",
    keywords: spec.keywords,
    operators: ["=", ">", "<", "!", "~", "?", ":", "==", "<=", ">=", "!=", "&&", "||", "++", "--", "+", "-", "*", "/", "&", "|", "^", "%", "->", "=>", "::"],
    symbols: /[=><!~?:&|+\-*/^%]+/,
    tokenizer,
  };
}

const registered = new Set<string>();

/** 后端 languageId → 可直接喂给 Monaco 的 languageId */
export function monacoLanguageFor(languageId: string | null | undefined): string {
  if (!languageId) return "plaintext";
  const alias = LANGUAGE_ALIASES[languageId] ?? languageId;
  const known = monaco.languages.getLanguages().some((item) => item.id === alias);
  if (known) return alias;
  const fallback = FALLBACK_LANGUAGES[languageId];
  if (!fallback) return "plaintext";
  if (!registered.has(languageId)) {
    registered.add(languageId);
    monaco.languages.register({ id: languageId, extensions: [], aliases: [languageId] });
    monaco.languages.setMonarchTokensProvider(languageId, buildFallbackMonarch(fallback));
    monaco.languages.setLanguageConfiguration(languageId, {
      comments: fallback.line
        ? fallback.block
          ? { lineComment: fallback.line, blockComment: fallback.block }
          : { lineComment: fallback.line }
        : fallback.block
          ? { blockComment: fallback.block }
          : undefined,
      brackets: [
        ["{", "}"],
        ["[", "]"],
        ["(", ")"],
      ],
      autoClosingPairs: [
        { open: "{", close: "}" },
        { open: "[", close: "]" },
        { open: "(", close: ")" },
        { open: '"', close: '"' },
        { open: "'", close: "'" },
      ],
    });
  }
  return languageId;
}

/**
 * 按路径推断后端 languageId。
 *
 * 与后端 `registry.language_for_path` 同口径：先查精确文件名（Dockerfile / CMakeLists.txt），
 * 再取最后一个 `.` 之后的后缀。
 */
export function languageIdForPath(catalog: IdeCatalog | null, path: string): string | null {
  if (!catalog) return null;
  const name = path.split("/").pop()?.toLowerCase() ?? "";
  if (!name) return null;
  const byName = catalog.filenames[name];
  if (byName) return byName;
  const dot = name.lastIndexOf(".");
  if (dot <= 0) return null;
  return catalog.extensions[name.slice(dot)] ?? null;
}

/** 该语言用哪个语言服务器；没有服务器返回 null */
export function serverForLanguage(catalog: IdeCatalog | null, languageId: string | null): string | null {
  if (!catalog || !languageId) return null;
  return catalog.languages.find((item) => item.id === languageId)?.server ?? null;
}

export { monaco };

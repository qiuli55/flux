/**
 * Monaco ↔ LSP 桥接层。
 *
 * 分工：
 * - `lspClient.ts` 负责传输与 JSON-RPC；
 * - 这里负责把协议对象翻成 Monaco 的 provider / marker，并处理双向差异
 *   （Monaco 位置 1-based、LSP 位置 0-based；枚举值不同；URI 编码风格不同）。
 *
 * provider 是「按语言全局注册」的，而语言服务器是「按工作区 + 服务器」连接的，
 * 所以注册一次之后，每次回调再用 `bindingFor(model)` 找到当前文档该用哪个连接。
 * 这样一个页面里同时开 Python 与 TypeScript 两个服务器也不会串。
 *
 * 静态能力（补全/悬浮/跳转/符号/折叠/格式化/重命名/代码操作）在第一次打开该语言时注册；
 * 语义高亮只能动态注册（legend 由服务器给），走 `registerDynamicCapability`。
 */
import type { LspClient, LspDiagnostic, LspPosition, LspRange, LspRegistration } from "./lspClient";
import { monaco } from "./monacoSetup";

/** 一个已连上的语言服务器，及它负责的语言 */
export interface LspBinding {
  /** `${root}|${serverId}`，全局唯一 */
  id: string;
  client: LspClient;
  /** 工作区根的绝对路径（无结尾斜杠） */
  root: string;
  rootUri: string;
  /** 该服务器负责的 Monaco languageId（已做别名转换） */
  languages: Set<string>;
  disposables: monaco.IDisposable[];
}

interface LspCompletionItem {
  label: string | { label: string; detail?: string; description?: string };
  labelDetails?: { detail?: string; description?: string };
  kind?: number;
  tags?: number[];
  detail?: string;
  documentation?: unknown;
  deprecated?: boolean;
  preselect?: boolean;
  sortText?: string;
  filterText?: string;
  insertText?: string;
  insertTextFormat?: number;
  textEdit?: { range: LspRange; newText: string } | { insert: LspRange; replace: LspRange; newText: string };
  additionalTextEdits?: Array<{ range: LspRange; newText: string }>;
  commitCharacters?: string[];
  data?: unknown;
}

interface LspCompletionList {
  isIncomplete?: boolean;
  items: LspCompletionItem[];
}

interface LspWorkspaceEdit {
  changes?: Record<string, Array<{ range: LspRange; newText: string }>>;
  documentChanges?: Array<
    | { textDocument: { uri: string; version?: number | null }; edits: Array<{ range: LspRange; newText: string }> }
    | { kind: string; [key: string]: unknown }
  >;
}

const bindings = new Map<string, LspBinding>();
/** 最近一次 publishDiagnostics 的结果，代码操作要把它回传给服务器 */
const diagnosticsCache = new Map<string, LspDiagnostic[]>();
const providerLanguages = new Set<string>();
const semanticLanguages = new Set<string>();
/** completions 的 resolve 需要拿回原始 LSP item 与它所属的连接 */
const resolveCache = new Map<string, { item: LspCompletionItem; bindingId: string }>();
let resolveSeq = 0;
const RESOLVE_CACHE_LIMIT = 4000;

/* ---------- 基础转换 ---------- */

function fsPathOf(uri: string): string {
  const raw = uri.replace(/^file:\/\//, "");
  try {
    return decodeURIComponent(raw);
  } catch {
    return raw;
  }
}

function uriKey(uri: string): string {
  return fsPathOf(uri).replace(/\/+$/, "");
}

function toRange(range: LspRange): monaco.IRange {
  return {
    startLineNumber: range.start.line + 1,
    startColumn: range.start.character + 1,
    endLineNumber: range.end.line + 1,
    endColumn: range.end.character + 1,
  };
}

function toLspRange(range: monaco.IRange): LspRange {
  return {
    start: { line: range.startLineNumber - 1, character: range.startColumn - 1 },
    end: { line: range.endLineNumber - 1, character: range.endColumn - 1 },
  };
}

function toLspPosition(position: monaco.IPosition): LspPosition {
  return { line: position.lineNumber - 1, character: position.column - 1 };
}

function toEdit(edit: { range: LspRange; newText: string }): monaco.languages.TextEdit {
  return { range: toRange(edit.range), text: edit.newText };
}

/** LSP 的 MarkedString / MarkupContent / MarkedString[] → Monaco 的 IMarkdownString[] */
function asMarkdown(value: unknown): monaco.IMarkdownString[] {
  if (value === null || value === undefined) return [];
  if (typeof value === "string") return [{ value: value.replace(/\r\n/g, "\n") }];
  if (Array.isArray(value)) return value.flatMap((item) => asMarkdown(item));
  const record = value as { kind?: string; value?: string; language?: string };
  if (typeof record.value === "string") {
    // plaintext 多半是一行签名，用围栏保住等宽
    const text = record.kind === "plaintext" ? `\`\`\`\n${record.value}\n\`\`\`` : record.value;
    return [{ value: text }];
  }
  if (typeof record.language === "string") return [{ value: `\`\`\`${record.language}\n${String(value)}\n\`\`\`` }];
  return [];
}

function wordRangeAt(model: monaco.editor.ITextModel, position: monaco.IPosition): monaco.IRange {
  const word = model.getWordUntilPosition(position);
  return {
    startLineNumber: position.lineNumber,
    startColumn: word.startColumn,
    endLineNumber: position.lineNumber,
    endColumn: word.endColumn,
  };
}

function signalFrom(token: monaco.CancellationToken): AbortSignal {
  const controller = new AbortController();
  if (token.isCancellationRequested) {
    controller.abort();
  } else {
    token.onCancellationRequested(() => controller.abort());
  }
  return controller.signal;
}

function bindingFor(model: monaco.editor.ITextModel): LspBinding | null {
  const path = fsPathOf(model.uri.toString());
  const language = model.getLanguageId();
  for (const binding of bindings.values()) {
    if (!binding.languages.has(language)) continue;
    if (path === binding.root || path.startsWith(`${binding.root}/`)) return binding;
  }
  return null;
}

function supports(binding: LspBinding, key: string): boolean {
  const value = binding.client.capabilities[key];
  return value !== undefined && value !== null && value !== false;
}

async function ask<T>(
  binding: LspBinding,
  method: string,
  params: unknown,
  token?: monaco.CancellationToken,
): Promise<T | null> {
  try {
    return await binding.client.request<T>(method, params, token ? signalFrom(token) : undefined);
  } catch {
    return null;
  }
}

/* ---------- 诊断 ---------- */

const SEVERITY: monaco.MarkerSeverity[] = [
  monaco.MarkerSeverity.Error,
  monaco.MarkerSeverity.Error,
  monaco.MarkerSeverity.Warning,
  monaco.MarkerSeverity.Info,
  monaco.MarkerSeverity.Hint,
];

function toMarker(diagnostic: LspDiagnostic): monaco.editor.IMarkerData {
  const range = toRange(diagnostic.range);
  const code: monaco.editor.IMarkerData["code"] =
    diagnostic.codeDescription?.href && diagnostic.code !== undefined
      ? { value: String(diagnostic.code), target: monaco.Uri.parse(diagnostic.codeDescription.href) }
      : diagnostic.code === undefined
        ? undefined
        : String(diagnostic.code);
  return {
    ...range,
    severity: SEVERITY[diagnostic.severity ?? 1] ?? monaco.MarkerSeverity.Error,
    message: diagnostic.message,
    source: diagnostic.source ?? "lsp",
    code,
    tags: diagnostic.tags?.includes(1) ? [monaco.MarkerTag.Unnecessary] : undefined,
  };
}

/** 把 publishDiagnostics 落到 Monaco 的 marker 层；没有打开的 model 就丢掉 */
export function applyDiagnostics(uri: string, diagnostics: LspDiagnostic[]): void {
  const key = uriKey(uri);
  diagnosticsCache.set(key, diagnostics);
  const model = modelForUri(uri);
  if (!model) return;
  monaco.editor.setModelMarkers(model, "lsp", diagnostics.map(toMarker));
}

/** 关掉某个文件时清掉它的 marker，避免下次打开还挂着旧诊断 */
export function clearDiagnostics(uri: string): void {
  diagnosticsCache.delete(uriKey(uri));
  const model = modelForUri(uri);
  if (model) monaco.editor.setModelMarkers(model, "lsp", []);
}

function modelForUri(uri: string): monaco.editor.ITextModel | null {
  const direct = monaco.editor.getModel(monaco.Uri.parse(uri));
  if (direct) return direct;
  const key = uriKey(uri);
  for (const model of monaco.editor.getModels()) {
    if (uriKey(model.uri.toString()) === key) return model;
  }
  return null;
}

/* ---------- 连接管理 ---------- */

/** 挂上一个已握手完成的服务器；同 id 会先摘掉旧的 */
export function attachBinding(binding: LspBinding): void {
  if (bindings.has(binding.id)) detachBinding(binding.id);
  bindings.set(binding.id, binding);
  for (const language of binding.languages) {
    ensureProviders(language);
    if (language === "typescript" || language === "javascript") setBuiltinTypeScript(false);
  }
}

/** 摘掉连接；provider 是全局的，这里只回收它注册的动态能力 */
export function detachBinding(id: string): void {
  const binding = bindings.get(id);
  if (!binding) return;
  for (const disposable of binding.disposables) {
    try {
      disposable.dispose();
    } catch {
      /* 已释放 */
    }
  }
  binding.disposables.length = 0;
  bindings.delete(id);
  const stillTs = Array.from(bindings.values()).some(
    (item) => item.languages.has("typescript") || item.languages.has("javascript"),
  );
  if (!stillTs) setBuiltinTypeScript(true);
}

/** 当前文档该用哪个连接；MonacoCodeArea 用它做 didChange / didSave */
export function bindingForUri(uri: string, languageId: string): LspBinding | null {
  const path = fsPathOf(uri);
  for (const binding of bindings.values()) {
    if (!binding.languages.has(languageId)) continue;
    if (path === binding.root || path.startsWith(`${binding.root}/`)) return binding;
  }
  return null;
}

/** 服务器动态注册能力：目前只关心语义高亮（legend 只能从注册里拿） */
export function registerDynamicCapability(binding: LspBinding, registrations: LspRegistration[]): void {
  for (const registration of registrations) {
    if (registration.method !== "textDocument/semanticTokens") continue;
    const options = registration.registerOptions as
      | { legend?: { tokenTypes?: string[]; tokenModifiers?: string[] } }
      | undefined;
    const legend = options?.legend;
    if (!legend?.tokenTypes?.length) continue;
    registerSemanticTokens(binding, {
      tokenTypes: legend.tokenTypes,
      tokenModifiers: legend.tokenModifiers ?? [],
    });
  }
}

function registerSemanticTokens(
  binding: LspBinding,
  legend: monaco.languages.SemanticTokensLegend,
): void {
  for (const language of binding.languages) {
    if (semanticLanguages.has(language)) continue;
    semanticLanguages.add(language);
    const disposable = monaco.languages.registerDocumentSemanticTokensProvider(language, {
      getLegend: () => legend,
      provideDocumentSemanticTokens: async (model, _lastResultId, token) => {
        const target = bindingFor(model);
        if (!target?.client.isReady) return null;
        const result = await ask<{ data?: number[]; resultId?: string }>(
          target,
          "textDocument/semanticTokens/full",
          { textDocument: { uri: model.uri.toString() } },
          token,
        );
        if (!result?.data?.length) return null;
        return { resultId: result.resultId, data: new Uint32Array(result.data) };
      },
      releaseDocumentSemanticTokens: () => {
        /* 服务器不要求释放 */
      },
    });
    binding.disposables.push(disposable);
  }
}

/* ---------- TS 内置智能开关 ----------
 * 接上 typescript-language-server 之后，Monaco 自带的 TS 服务会跟它抢补全、
 * 抢诊断、抢语义高亮（同一处报两遍）。这里在 LSP 就绪时把内置的那套关掉，
 * 断开 / 服务器没装时再打开，保证「没 LSP 也还能用」。
 */

function setBuiltinTypeScript(enabled: boolean): void {
  const mode = {
    completionItems: enabled,
    hovers: enabled,
    documentSymbols: enabled,
    definitions: enabled,
    references: enabled,
    documentHighlights: enabled,
    rename: enabled,
    diagnostics: enabled,
    documentRangeFormattingEdits: enabled,
    signatureHelp: enabled,
    onTypeFormattingEdits: enabled,
    codeActions: enabled,
    inlayHints: enabled,
    diagnosticCodes: enabled,
    documentFormattingEdits: enabled,
    selectionRanges: enabled,
    foldingRanges: enabled,
    semanticTokens: enabled,
    documentRangeSemanticTokens: enabled,
  } as unknown as monaco.languages.typescript.ModeConfiguration;
  for (const defaults of [monaco.languages.typescript.typescriptDefaults, monaco.languages.typescript.javascriptDefaults]) {
    defaults.setModeConfiguration(mode);
    defaults.setDiagnosticsOptions({
      noSemanticValidation: !enabled,
      noSyntaxValidation: !enabled,
      noSuggestionDiagnostics: !enabled,
    });
  }
}

/* ---------- provider 注册 ---------- */

const COMPLETION_KIND: monaco.languages.CompletionItemKind[] = [
  monaco.languages.CompletionItemKind.Text,
  monaco.languages.CompletionItemKind.Text,
  monaco.languages.CompletionItemKind.Method,
  monaco.languages.CompletionItemKind.Function,
  monaco.languages.CompletionItemKind.Constructor,
  monaco.languages.CompletionItemKind.Field,
  monaco.languages.CompletionItemKind.Variable,
  monaco.languages.CompletionItemKind.Class,
  monaco.languages.CompletionItemKind.Interface,
  monaco.languages.CompletionItemKind.Module,
  monaco.languages.CompletionItemKind.Property,
  monaco.languages.CompletionItemKind.Unit,
  monaco.languages.CompletionItemKind.Value,
  monaco.languages.CompletionItemKind.Enum,
  monaco.languages.CompletionItemKind.Keyword,
  monaco.languages.CompletionItemKind.Snippet,
  monaco.languages.CompletionItemKind.Color,
  monaco.languages.CompletionItemKind.File,
  monaco.languages.CompletionItemKind.Reference,
  monaco.languages.CompletionItemKind.Folder,
  monaco.languages.CompletionItemKind.EnumMember,
  monaco.languages.CompletionItemKind.Constant,
  monaco.languages.CompletionItemKind.Struct,
  monaco.languages.CompletionItemKind.Event,
  monaco.languages.CompletionItemKind.Operator,
  monaco.languages.CompletionItemKind.TypeParameter,
];

const COMPLETION_TRIGGERS = [
  ".",
  "/",
  ":",
  "'",
  '"',
  "(",
  "[",
  "{",
  "<",
  "@",
  "#",
  " ",
  "-",
  "=",
  "+",
  "*",
  "%",
  "&",
  "|",
  "!",
  "?",
  "$",
  ",",
  ">",
  "~",
  "^",
];

function toCompletionItem(
  item: LspCompletionItem,
  fallbackRange: monaco.IRange,
  bindingId: string,
): monaco.languages.CompletionItem {
  const label = typeof item.label === "string" ? item.label : item.label.label;
  const labelDetail =
    typeof item.label === "string" ? item.labelDetails?.detail : item.label.detail ?? item.labelDetails?.detail;
  const labelDescription =
    typeof item.label === "string" ? item.labelDetails?.description : item.label.description ?? item.labelDetails?.description;

  let range = fallbackRange;
  let insertText = item.insertText ?? label;
  if (item.textEdit) {
    if ("range" in item.textEdit) {
      range = toRange(item.textEdit.range);
    } else {
      range = toRange(item.textEdit.replace);
    }
    insertText = item.textEdit.newText;
  }

  const key = `${resolveSeq++}`;
  resolveCache.set(key, { item, bindingId });
  if (resolveCache.size > RESOLVE_CACHE_LIMIT) {
    const oldest = resolveCache.keys().next().value;
    if (oldest !== undefined) resolveCache.delete(oldest);
  }

  const result: monaco.languages.CompletionItem & { __fluxKey?: string } = {
    label: { label, detail: labelDetail, description: labelDescription },
    kind: COMPLETION_KIND[item.kind ?? 1] ?? monaco.languages.CompletionItemKind.Text,
    detail: item.detail,
    documentation: item.documentation ? asMarkdown(item.documentation)[0] : undefined,
    sortText: item.sortText,
    filterText: item.filterText,
    insertText,
    insertTextRules:
      item.insertTextFormat === 2 ? monaco.languages.CompletionItemInsertTextRule.InsertAsSnippet : undefined,
    range,
    preselect: item.preselect,
    commitCharacters: item.commitCharacters,
    tags: item.tags?.includes(1) ? [monaco.languages.CompletionItemTag.Deprecated] : undefined,
    additionalTextEdits: item.additionalTextEdits?.map(toEdit),
  };
  // Monaco 会把同一个对象原样交给 resolveCompletionItem，可以靠这个自定义字段找回原始 item
  result.__fluxKey = key;
  return result;
}

function collectLocations(result: unknown): monaco.languages.Location[] {
  if (!result) return [];
  const items = Array.isArray(result) ? result : [result];
  const locations: monaco.languages.Location[] = [];
  for (const raw of items) {
    const item = raw as Record<string, unknown>;
    if (typeof item.uri === "string" && item.range) {
      locations.push({ uri: monaco.Uri.parse(item.uri), range: toRange(item.range as LspRange) });
    } else if (typeof item.targetUri === "string") {
      const range = (item.targetSelectionRange ?? item.targetRange) as LspRange | undefined;
      if (range) locations.push({ uri: monaco.Uri.parse(item.targetUri), range: toRange(range) });
    }
  }
  return locations;
}

function intersects(a: monaco.IRange, b: monaco.IRange): boolean {
  if (a.endLineNumber < b.startLineNumber || b.endLineNumber < a.startLineNumber) return false;
  if (a.endLineNumber === b.startLineNumber && a.endColumn < b.startColumn) return false;
  if (b.endLineNumber === a.startLineNumber && b.endColumn < a.startColumn) return false;
  return true;
}

function workspaceEditToMonaco(
  edit: LspWorkspaceEdit | null | undefined,
  currentUri: string,
  currentModel: monaco.editor.ITextModel,
): { edit: monaco.languages.WorkspaceEdit } | { rejectReason: string } {
  if (!edit) return { edit: { edits: [] } };
  const changes: Record<string, Array<{ range: LspRange; newText: string }>> = { ...(edit.changes ?? {}) };
  for (const change of edit.documentChanges ?? []) {
    if ("textDocument" in change && Array.isArray(change.edits)) {
      const document = change.textDocument as { uri: string };
      const textEdits = change.edits as Array<{ range: LspRange; newText: string }>;
      changes[document.uri] = [...(changes[document.uri] ?? []), ...textEdits];
    }
  }
  const keys = Object.keys(changes);
  if (keys.length === 0) return { edit: { edits: [] } };
  const foreign = keys.filter((uri) => uriKey(uri) !== uriKey(currentUri));
  if (foreign.length > 0) {
    return { rejectReason: `该改动会波及 ${foreign.length} 个其它文件，Flux 目前只支持单文件改写` };
  }
  const edits: monaco.languages.IWorkspaceTextEdit[] = [];
  for (const uri of keys) {
    for (const change of changes[uri] ?? []) {
      edits.push({ resource: currentModel.uri, textEdit: toEdit(change), versionId: currentModel.getVersionId() });
    }
  }
  return { edit: { edits } };
}

const SYMBOL_KIND: monaco.languages.SymbolKind[] = [
  monaco.languages.SymbolKind.File,
  monaco.languages.SymbolKind.File,
  monaco.languages.SymbolKind.Module,
  monaco.languages.SymbolKind.Namespace,
  monaco.languages.SymbolKind.Package,
  monaco.languages.SymbolKind.Class,
  monaco.languages.SymbolKind.Method,
  monaco.languages.SymbolKind.Property,
  monaco.languages.SymbolKind.Field,
  monaco.languages.SymbolKind.Constructor,
  monaco.languages.SymbolKind.Enum,
  monaco.languages.SymbolKind.Interface,
  monaco.languages.SymbolKind.Function,
  monaco.languages.SymbolKind.Variable,
  monaco.languages.SymbolKind.Constant,
  monaco.languages.SymbolKind.String,
  monaco.languages.SymbolKind.Number,
  monaco.languages.SymbolKind.Boolean,
  monaco.languages.SymbolKind.Array,
  monaco.languages.SymbolKind.Object,
  monaco.languages.SymbolKind.Key,
  monaco.languages.SymbolKind.Null,
  monaco.languages.SymbolKind.EnumMember,
  monaco.languages.SymbolKind.Struct,
  monaco.languages.SymbolKind.Event,
  monaco.languages.SymbolKind.Operator,
  monaco.languages.SymbolKind.TypeParameter,
];

function toDocumentSymbols(items: unknown, model: monaco.editor.ITextModel): monaco.languages.DocumentSymbol[] {
  if (!Array.isArray(items)) return [];
  const out: monaco.languages.DocumentSymbol[] = [];
  for (const raw of items) {
    const item = raw as Record<string, unknown>;
    const range = item.range as LspRange | undefined;
    const selectionRange = (item.selectionRange ?? item.range) as LspRange | undefined;
    if (!range || !selectionRange || typeof item.name !== "string") continue;
    const kind = typeof item.kind === "number" ? item.kind : 12;
    const symbol: monaco.languages.DocumentSymbol = {
      name: item.name,
      detail: typeof item.detail === "string" ? item.detail : "",
      kind: SYMBOL_KIND[kind] ?? monaco.languages.SymbolKind.Variable,
      tags: Array.isArray(item.tags) && item.tags.includes(1) ? [monaco.languages.SymbolTag.Deprecated] : [],
      range: toRange(range),
      selectionRange: toRange(selectionRange),
    };
    if (Array.isArray(item.children)) symbol.children = toDocumentSymbols(item.children, model);
    out.push(symbol);
  }
  return out;
}

/** LSP 与 VS Code 的 CodeActionKind 取值一致，Monaco 0.52 按字符串前缀匹配 */
function codeActionKind(kind: string | undefined): string | undefined {
  return kind && kind.length > 0 ? kind : undefined;
}

/** 按语言注册全部静态 provider；同一语言只注册一次 */
export function ensureProviders(languageId: string): void {
  if (providerLanguages.has(languageId)) return;
  providerLanguages.add(languageId);

  monaco.languages.registerCompletionItemProvider(languageId, {
    triggerCharacters: COMPLETION_TRIGGERS,
    async provideCompletionItems(model, position, context, token) {
      const binding = bindingFor(model);
      if (!binding?.client.isReady) return undefined;
      const triggered =
        context.triggerKind === monaco.languages.CompletionTriggerKind.TriggerCharacter && context.triggerCharacter
          ? { triggerKind: 2, triggerCharacter: context.triggerCharacter }
          : { triggerKind: 1 };
      const result = await ask<LspCompletionList | LspCompletionItem[]>(
        binding,
        "textDocument/completion",
        {
          textDocument: { uri: model.uri.toString() },
          position: toLspPosition(position),
          context: triggered,
        },
        token,
      );
      if (!result) return undefined;
      const items = Array.isArray(result) ? result : result.items;
      if (!Array.isArray(items) || items.length === 0) return undefined;
      const fallback = wordRangeAt(model, position);
      return {
        suggestions: items.map((item) => toCompletionItem(item, fallback, binding.id)),
        incomplete: Array.isArray(result) ? false : Boolean(result.isIncomplete),
      };
    },
    async resolveCompletionItem(item, token) {
      const key = (item as monaco.languages.CompletionItem & { __fluxKey?: string }).__fluxKey;
      if (!key) return item;
      const cached = resolveCache.get(key);
      if (!cached) return item;
      const binding = bindings.get(cached.bindingId);
      if (!binding?.client.isReady) return item;
      const resolved = await ask<LspCompletionItem>(binding, "completionItem/resolve", cached.item, token);
      if (!resolved) return item;
      return {
        ...item,
        detail: resolved.detail ?? item.detail,
        documentation: resolved.documentation ? asMarkdown(resolved.documentation)[0] : item.documentation,
        additionalTextEdits: resolved.additionalTextEdits?.map(toEdit) ?? item.additionalTextEdits,
      };
    },
  });

  monaco.languages.registerHoverProvider(languageId, {
    async provideHover(model, position, token) {
      const binding = bindingFor(model);
      if (!binding?.client.isReady) return undefined;
      const result = await ask<{ contents?: unknown; range?: LspRange }>(
        binding,
        "textDocument/hover",
        { textDocument: { uri: model.uri.toString() }, position: toLspPosition(position) },
        token,
      );
      if (!result?.contents) return undefined;
      const contents = asMarkdown(result.contents);
      if (contents.length === 0) return undefined;
      return { contents, range: result.range ? toRange(result.range) : undefined };
    },
  });

  monaco.languages.registerSignatureHelpProvider(languageId, {
    signatureHelpTriggerCharacters: ["(", ",", "<"],
    signatureHelpRetriggerCharacters: [","],
    async provideSignatureHelp(model, position, token) {
      const binding = bindingFor(model);
      if (!binding?.client.isReady) return undefined;
      const result = await ask<{
        signatures?: Array<{
          label?: string;
          documentation?: unknown;
          parameters?: Array<{ label?: string | [number, number]; documentation?: unknown }>;
          activeParameter?: number;
        }>;
        activeSignature?: number;
        activeParameter?: number;
      }>(
        binding,
        "textDocument/signatureHelp",
        { textDocument: { uri: model.uri.toString() }, position: toLspPosition(position) },
        token,
      );
      if (!result?.signatures?.length) return undefined;
      return {
        value: {
          signatures: result.signatures.map((signature) => ({
            label: signature.label ?? "",
            documentation: signature.documentation ? asMarkdown(signature.documentation)[0] : undefined,
            parameters: (signature.parameters ?? []).map((parameter) => ({
              label: parameter.label ?? "",
              documentation: parameter.documentation ? asMarkdown(parameter.documentation)[0] : undefined,
            })),
            activeParameter: signature.activeParameter,
          })),
          activeSignature: result.activeSignature ?? 0,
          activeParameter: result.activeParameter ?? 0,
        },
        dispose: () => {
          /* 无资源 */
        },
      };
    },
  });

  const locationProvider = (method: string) => async (
    model: monaco.editor.ITextModel,
    position: monaco.Position,
    token: monaco.CancellationToken,
  ) => {
    const binding = bindingFor(model);
    if (!binding?.client.isReady) return undefined;
    const result = await ask<unknown>(
      binding,
      method,
      { textDocument: { uri: model.uri.toString() }, position: toLspPosition(position) },
      token,
    );
    const locations = collectLocations(result);
    return locations.length > 0 ? locations : undefined;
  };

  monaco.languages.registerDefinitionProvider(languageId, {
    provideDefinition: locationProvider("textDocument/definition"),
  });
  monaco.languages.registerDeclarationProvider(languageId, {
    provideDeclaration: locationProvider("textDocument/declaration"),
  });
  monaco.languages.registerTypeDefinitionProvider(languageId, {
    provideTypeDefinition: locationProvider("textDocument/typeDefinition"),
  });
  monaco.languages.registerImplementationProvider(languageId, {
    provideImplementation: locationProvider("textDocument/implementation"),
  });

  monaco.languages.registerReferenceProvider(languageId, {
    async provideReferences(model, position, context, token) {
      const binding = bindingFor(model);
      if (!binding?.client.isReady) return undefined;
      const result = await ask<unknown>(
        binding,
        "textDocument/references",
        {
          textDocument: { uri: model.uri.toString() },
          position: toLspPosition(position),
          context: { includeDeclaration: context.includeDeclaration },
        },
        token,
      );
      const locations = collectLocations(result);
      return locations.length > 0 ? locations : undefined;
    },
  });

  monaco.languages.registerDocumentSymbolProvider(languageId, {
    displayName: "LSP",
    async provideDocumentSymbols(model, token) {
      const binding = bindingFor(model);
      if (!binding?.client.isReady) return undefined;
      const result = await ask<unknown>(
        binding,
        "textDocument/documentSymbol",
        { textDocument: { uri: model.uri.toString() } },
        token,
      );
      const symbols = toDocumentSymbols(result, model);
      return symbols.length > 0 ? symbols : undefined;
    },
  });

  monaco.languages.registerDocumentHighlightProvider(languageId, {
    async provideDocumentHighlights(model, position, token) {
      const binding = bindingFor(model);
      if (!binding?.client.isReady) return undefined;
      const result = await ask<Array<{ range: LspRange; kind?: number }>>(
        binding,
        "textDocument/documentHighlight",
        { textDocument: { uri: model.uri.toString() }, position: toLspPosition(position) },
        token,
      );
      if (!result?.length) return undefined;
      const kinds = [
        monaco.languages.DocumentHighlightKind.Text,
        monaco.languages.DocumentHighlightKind.Text,
        monaco.languages.DocumentHighlightKind.Read,
        monaco.languages.DocumentHighlightKind.Write,
      ];
      return result.map((item) => ({ range: toRange(item.range), kind: kinds[item.kind ?? 1] ?? 0 }));
    },
  });

  monaco.languages.registerFoldingRangeProvider(languageId, {
    async provideFoldingRanges(model, _context, token) {
      const binding = bindingFor(model);
      if (!binding?.client.isReady) return undefined;
      const result = await ask<Array<{ startLine: number; endLine: number; kind?: string }>>(
        binding,
        "textDocument/foldingRange",
        { textDocument: { uri: model.uri.toString() } },
        token,
      );
      if (!result?.length) return undefined;
      return result.map((range) => ({
        start: range.startLine + 1,
        end: range.endLine + 1,
        kind:
          range.kind === "comment"
            ? monaco.languages.FoldingRangeKind.Comment
            : range.kind === "imports"
              ? monaco.languages.FoldingRangeKind.Imports
              : range.kind === "region"
                ? monaco.languages.FoldingRangeKind.Region
                : undefined,
      }));
    },
  });

  monaco.languages.registerCodeActionProvider(languageId, {
    async provideCodeActions(model, range, context, token) {
      const binding = bindingFor(model);
      if (!binding?.client.isReady) return undefined;
      const cached = diagnosticsCache.get(uriKey(model.uri.toString())) ?? [];
      const diagnostics = cached.filter((diagnostic) => intersects(toRange(diagnostic.range), range));
      if (diagnostics.length === 0 && context.markers.length === 0) return undefined;
      const result = await ask<
        Array<{
          title?: string;
          kind?: string;
          isPreferred?: boolean;
          diagnostics?: unknown[];
          edit?: LspWorkspaceEdit;
          command?: { title?: string; command?: string; arguments?: unknown[] };
          disabled?: { reason?: string };
        }>
      >(
        binding,
        "textDocument/codeAction",
        {
          textDocument: { uri: model.uri.toString() },
          range: toLspRange(range),
          context: { diagnostics, triggerKind: 1, only: undefined },
        },
        token,
      );
      if (!result?.length) return undefined;
      const actions: monaco.languages.CodeAction[] = [];
      for (const action of result) {
        if (!action?.title) continue;
        if (action.disabled?.reason) continue;
        const converted: monaco.languages.CodeAction = {
          title: action.title,
          kind: codeActionKind(action.kind),
          isPreferred: action.isPreferred,
          diagnostics: diagnostics.map(toMarker).filter((marker) => intersects(marker, range)),
        };
        if (action.edit) {
          const applied = workspaceEditToMonaco(action.edit, model.uri.toString(), model);
          if ("edit" in applied) converted.edit = applied.edit;
          else converted.disabled = applied.rejectReason;
        }
        if (action.command?.command) {
          converted.command = {
            id: action.command.command,
            title: action.command.title ?? action.title,
            arguments: action.command.arguments,
          };
        }
        if (!converted.edit && !converted.command) continue;
        actions.push(converted);
      }
      if (actions.length === 0) return undefined;
      return { actions, dispose: () => undefined };
    },
  });

  monaco.languages.registerRenameProvider(languageId, {
    async resolveRenameLocation(model, position, token) {
      const binding = bindingFor(model);
      if (!binding?.client.isReady) return undefined;
      if (!supports(binding, "renameProvider")) return undefined;
      const result = await ask<LspRange | { range: LspRange; placeholder?: string } | null>(
        binding,
        "textDocument/prepareRename",
        { textDocument: { uri: model.uri.toString() }, position: toLspPosition(position) },
        token,
      );
      if (!result) return undefined;
      const range = "range" in result ? result.range : result;
      return { range: toRange(range), text: model.getValueInRange(toRange(range)) };
    },
    async provideRenameEdits(model, position, newName, token) {
      const binding = bindingFor(model);
      if (!binding?.client.isReady) return { edits: [], rejectReason: "语言服务器未就绪" };
      const result = await ask<LspWorkspaceEdit>(
        binding,
        "textDocument/rename",
        { textDocument: { uri: model.uri.toString() }, position: toLspPosition(position), newName },
        token,
      );
      const applied = workspaceEditToMonaco(result, model.uri.toString(), model);
      return "edit" in applied ? applied.edit : { edits: [], rejectReason: applied.rejectReason };
    },
  });

  monaco.languages.registerDocumentFormattingEditProvider(languageId, {
    displayName: "LSP",
    async provideDocumentFormattingEdits(model, _options, token) {
      const binding = bindingFor(model);
      if (!binding?.client.isReady || !supports(binding, "documentFormattingProvider")) return undefined;
      const result = await ask<Array<{ range: LspRange; newText: string }>>(
        binding,
        "textDocument/formatting",
        { textDocument: { uri: model.uri.toString() }, options: { tabSize: 2, insertSpaces: true } },
        token,
      );
      if (!result?.length) return undefined;
      return result.map(toEdit);
    },
  });

  monaco.languages.registerDocumentRangeFormattingEditProvider(languageId, {
    displayName: "LSP",
    async provideDocumentRangeFormattingEdits(model, range, _options, token) {
      const binding = bindingFor(model);
      if (!binding?.client.isReady || !supports(binding, "documentRangeFormattingProvider")) return undefined;
      const result = await ask<Array<{ range: LspRange; newText: string }>>(
        binding,
        "textDocument/rangeFormatting",
        {
          textDocument: { uri: model.uri.toString() },
          range: toLspRange(range),
          options: { tabSize: 2, insertSpaces: true },
        },
        token,
      );
      if (!result?.length) return undefined;
      return result.map(toEdit);
    },
  });

  monaco.languages.registerInlayHintsProvider(languageId, {
    displayName: "LSP",
    async provideInlayHints(model, range, token) {
      const binding = bindingFor(model);
      if (!binding?.client.isReady || !supports(binding, "inlayHintProvider")) return undefined;
      const result = await ask<
        Array<{
          position: LspPosition;
          label: string | Array<{ value: string }>;
          kind?: number;
          paddingLeft?: boolean;
          paddingRight?: boolean;
          tooltip?: unknown;
        }>
      >(
        binding,
        "textDocument/inlayHint",
        { textDocument: { uri: model.uri.toString() }, range: toLspRange(range) },
        token,
      );
      if (!result?.length) return undefined;
      const hints: monaco.languages.InlayHint[] = result.map((hint) => ({
        position: { lineNumber: hint.position.line + 1, column: hint.position.character + 1 },
        label: typeof hint.label === "string" ? hint.label : hint.label.map((part) => part.value).join(""),
        kind:
          hint.kind === 1
            ? monaco.languages.InlayHintKind.Type
            : hint.kind === 2
              ? monaco.languages.InlayHintKind.Parameter
              : undefined,
        paddingLeft: hint.paddingLeft,
        paddingRight: hint.paddingRight,
        tooltip: hint.tooltip ? asMarkdown(hint.tooltip)[0] : undefined,
      }));
      return { hints, dispose: () => undefined };
    },
  });
}

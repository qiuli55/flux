/**
 * Monaco 编辑器宿主。
 *
 * 一个编辑器实例承载所有文件标签：切换标签只是 `setModel`，所以每个文件的光标、
 * 折叠、撤销栈都各自保留。模型按 `file://` URI 缓存（LRU），重新打开同一文件不会丢历史。
 *
 * 语言服务器按 `${项目}|${工作区}|${服务器}` 复用连接，引用计数归零后延迟 30s 再关，
 * 避免在「变更视图 / 编辑器」之间来回切换时反复握手。连接就绪后挂到 `lspProviders`，
 * 由后者提供补全 / 悬浮 / 跳转 / 诊断 / 重命名等能力。
 *
 * AI 内联补全走 Monaco 的 inline completions，输入停顿 400ms 后请求一次；
 * 没有配置供应商时 `aiComplete` 缺省，provider 直接返回空，不影响 LSP 补全。
 */
import { useEffect, useMemo, useRef, useState } from "react";

import { ideLspUrl } from "../../api/client";
import type { IdeCatalog } from "../../api/types";
import {
  AI_DEBOUNCE_MS,
  cleanCompletion,
  type AiCompletionInput,
  type LexerKind,
} from "../../utils/completion";
import { LspClient, fileUri, pathFromUri, type LspStatus } from "./lspClient";
import {
  applyDiagnostics,
  attachBinding,
  clearDiagnostics,
  registerDynamicCapability,
  type LspBinding,
} from "./lspProviders";
import {
  FLUX_THEME,
  languageIdForPath,
  monaco,
  monacoLanguageFor,
  serverForLanguage,
} from "./monacoSetup";

export interface MonacoCodeAreaProps {
  projectId: string | null;
  /** 工作区根的绝对路径 */
  workspaceRoot: string;
  /** 工作区相对路径（POSIX） */
  path: string;
  value: string;
  readOnly?: boolean;
  /** 有改动的行号（1 起） */
  changedLines?: Set<number>;
  /** 需要滚动到并聚焦的行号（1 起） */
  revealLine?: number | null;
  catalog: IdeCatalog | null;
  onChange: (next: string) => void;
  onSave?: () => void;
  onOpenFile?: (path: string, line?: number) => void;
  onLspStatus?: (status: LspStatus, message?: string) => void;
  aiComplete?: (input: AiCompletionInput) => Promise<string | null>;
}

const FONT_STACK =
  '"Cascadia Code", "Cascadia Mono", "JetBrains Mono", "Fira Code", "SFMono-Regular", Consolas, "DejaVu Sans Mono", "Liberation Mono", "Noto Sans Mono", monospace';

/* ---------- 模型缓存 ---------- */

const MODEL_LIMIT = 32;
const modelCache = new Map<string, monaco.editor.ITextModel>();
/** 当前挂在编辑器上的模型，回收时要跳过 */
let attachedUri: string | null = null;

function acquireModel(uri: string, languageId: string, value: string): monaco.editor.ITextModel {
  const parsed = monaco.Uri.parse(uri);
  let model = monaco.editor.getModel(parsed);
  if (!model) {
    model = monaco.editor.createModel(value, languageId, parsed);
  } else {
    if (model.getLanguageId() !== languageId) monaco.editor.setModelLanguage(model, languageId);
    if (model.getValue() !== value) model.setValue(value);
  }
  // 刷新 LRU 顺序
  modelCache.delete(uri);
  modelCache.set(uri, model);
  pruneModels();
  return model;
}

function pruneModels(): void {
  if (modelCache.size <= MODEL_LIMIT) return;
  for (const [uri, model] of modelCache) {
    if (modelCache.size <= MODEL_LIMIT) return;
    if (uri === attachedUri) continue;
    if (monaco.editor.getModel(model.uri) !== model) {
      modelCache.delete(uri);
      continue;
    }
    if (monaco.editor.getEditors().some((editor) => editor.getModel() === model)) continue;
    modelCache.delete(uri);
    model.dispose();
  }
}

/* ---------- 连接池 ---------- */

interface ConnectionEntry {
  key: string;
  client: LspClient;
  binding: LspBinding;
  refs: number;
  disposeTimer: number | null;
  status: LspStatus;
  message?: string;
  listeners: Set<(status: LspStatus, message?: string) => void>;
}

const connections = new Map<string, ConnectionEntry>();
/** 引用归零后保留这么久，避免视图切换导致反复握手 */
const CONNECTION_GRACE_MS = 30_000;

function connectionKey(projectId: string, root: string, server: string): string {
  return `${projectId}|${root}|${server}`;
}

/** 这台服务器负责哪些 Monaco languageId（legend / provider 注册要用） */
function connectionLanguages(catalog: IdeCatalog, server: string, fallback: string): Set<string> {
  const languages = new Set<string>();
  for (const item of catalog.languages) {
    if (item.server === server) languages.add(monacoLanguageFor(item.id));
  }
  if (languages.size === 0) languages.add(fallback);
  return languages;
}

function acquireConnection(
  projectId: string,
  root: string,
  server: string,
  catalog: IdeCatalog,
  fallbackLanguage: string,
): ConnectionEntry {
  const key = connectionKey(projectId, root, server);
  const existing = connections.get(key);
  if (existing) {
    if (existing.disposeTimer !== null) {
      window.clearTimeout(existing.disposeTimer);
      existing.disposeTimer = null;
    }
    existing.refs += 1;
    return existing;
  }

  const rootUri = fileUri(root, "");
  const rootName = root.split("/").filter(Boolean).pop() ?? root;
  let entry: ConnectionEntry | null = null;

  const client = new LspClient({
    url: ideLspUrl(projectId, server, root),
    rootUri,
    rootName,
    onStatus(status, message) {
      const current = entry;
      if (!current) return;
      current.status = status;
      current.message = message;
      if (status === "ready") attachBinding(current.binding);
      for (const listener of current.listeners) listener(status, message);
    },
    onDiagnostics(uri, diagnostics) {
      applyDiagnostics(uri, diagnostics);
    },
    onRegisterCapability(registrations) {
      const current = entry;
      if (!current) return;
      if (current.client.isReady) attachBinding(current.binding);
      registerDynamicCapability(current.binding, registrations);
    },
  });

  const binding: LspBinding = {
    id: key,
    client,
    root,
    rootUri,
    languages: connectionLanguages(catalog, server, fallbackLanguage),
    disposables: [],
  };

  entry = {
    key,
    client,
    binding,
    refs: 1,
    disposeTimer: null,
    status: "idle",
    listeners: new Set(),
  };
  connections.set(key, entry);
  client.connect();
  return entry;
}

function releaseConnection(key: string): void {
  const entry = connections.get(key);
  if (!entry) return;
  entry.refs -= 1;
  if (entry.refs > 0) return;
  entry.disposeTimer = window.setTimeout(() => {
    connections.delete(key);
    detachSafely(key);
    entry.client.dispose();
  }, CONNECTION_GRACE_MS);
}

/** 避免循环依赖：`detachBinding` 在同模块里直接可用 */
function detachSafely(id: string): void {
  void import("./lspProviders").then((module) => module.detachBinding(id)).catch(() => undefined);
}

/* ---------- 跨文件跳转（一次注册，全局生效） ---------- */

const openerState: { root: string; open: ((path: string, line?: number) => void) | null } = {
  root: "",
  open: null,
};
let openerRegistered = false;

function ensureEditorOpener(): void {
  if (openerRegistered) return;
  openerRegistered = true;
  monaco.editor.registerEditorOpener({
    openCodeEditor(_source, resource, selectionOrPosition) {
      const handler = openerState.open;
      if (!handler || !openerState.root) return false;
      const relative = pathFromUri(openerState.root, resource.toString());
      if (!relative) return false;
      let line: number | undefined;
      if (selectionOrPosition) {
        line =
          "startLineNumber" in selectionOrPosition
            ? selectionOrPosition.startLineNumber
            : selectionOrPosition.lineNumber;
      }
      handler(relative, line);
      return true;
    },
  });
}

/* ---------- AI 内联补全（按语言注册一次） ---------- */

const aiState: { run: MonacoCodeAreaProps["aiComplete"] | null; root: string } = {
  run: null,
  root: "",
};
const aiLanguages = new Set<string>();

function kindOfLanguage(languageId: string): LexerKind {
  if (languageId === "markdown") return "md";
  if (languageId === "python") return "py";
  return "code";
}

function waitForIdle(token: monaco.CancellationToken): Promise<void> {
  return new Promise<void>((resolve) => {
    if (token.isCancellationRequested) {
      resolve();
      return;
    }
    const subscription = token.onCancellationRequested(() => {
      window.clearTimeout(timer);
      subscription.dispose();
      resolve();
    });
    const timer = window.setTimeout(() => {
      subscription.dispose();
      resolve();
    }, AI_DEBOUNCE_MS);
  });
}

function registerAiInline(language: string): void {
  if (aiLanguages.has(language)) return;
  aiLanguages.add(language);
  monaco.languages.registerInlineCompletionsProvider(language, {
    async provideInlineCompletions(model, position, _context, token) {
      const run = aiState.run;
      if (!run || model.getValueLength() === 0) return { items: [] };
      await waitForIdle(token);
      if (token.isCancellationRequested) return { items: [] };
      const text = model.getValue();
      const caret = model.getOffsetAt(position);
      const relative = aiState.root ? pathFromUri(aiState.root, model.uri.toString()) : null;
      try {
        const raw = await run({
          path: relative ?? model.uri.path,
          kind: kindOfLanguage(model.getLanguageId()),
          text,
          caret,
        });
        if (!raw || token.isCancellationRequested) return { items: [] };
        const insertText = cleanCompletion(raw);
        if (!insertText.trim()) return { items: [] };
        return {
          items: [
            {
              insertText,
              range: new monaco.Range(
                position.lineNumber,
                position.column,
                position.lineNumber,
                position.column,
              ),
            },
          ],
        };
      } catch {
        return { items: [] };
      }
    },
    freeInlineCompletions() {
      /* 没有缓存 */
    },
  });
}

/* ---------- 组件 ---------- */

export function MonacoCodeArea({
  projectId,
  workspaceRoot,
  path,
  value,
  readOnly = false,
  changedLines,
  revealLine,
  catalog,
  onChange,
  onSave,
  onOpenFile,
  onLspStatus,
  aiComplete,
}: MonacoCodeAreaProps) {
  /** 设置编辑器内容的副作用要跳过，避免「外部写回」触发一次多余的 onChange */
  const suppressRef = useRef(false);
  const hostRef = useRef<HTMLDivElement | null>(null);
  const editorRef = useRef<monaco.editor.IStandaloneCodeEditor | null>(null);
  const decorationsRef = useRef<monaco.editor.IEditorDecorationsCollection | null>(null);
  const viewStatesRef = useRef(new Map<string, monaco.editor.ICodeEditorViewState | null>());
  const currentUriRef = useRef("");
  const entryRef = useRef<ConnectionEntry | null>(null);
  const valueRef = useRef(value);
  const onChangeRef = useRef(onChange);
  const onSaveRef = useRef(onSave);
  const onOpenFileRef = useRef(onOpenFile);
  const onLspStatusRef = useRef(onLspStatus);

  valueRef.current = value;
  onChangeRef.current = onChange;
  onSaveRef.current = onSave;
  onOpenFileRef.current = onOpenFile;
  onLspStatusRef.current = onLspStatus;

  const [connectionEpoch, setConnectionEpoch] = useState(0);

  const languageId = useMemo(
    () => (catalog ? languageIdForPath(catalog, path) : null),
    [catalog, path],
  );
  const language = useMemo(() => monacoLanguageFor(languageId), [languageId]);
  const serverId = useMemo(() => serverForLanguage(catalog, languageId), [catalog, languageId]);
  const uri = useMemo(() => (workspaceRoot ? fileUri(workspaceRoot, path) : ""), [workspaceRoot, path]);

  /* 1. 编辑器实例：只建一次 */
  useEffect(() => {
    const host = hostRef.current;
    if (!host) return;
    const editor = monaco.editor.create(host, {
      model: null,
      theme: FLUX_THEME,
      automaticLayout: true,
      fontFamily: FONT_STACK,
      fontSize: 12.5,
      lineHeight: 20,
      fontLigatures: true,
      tabSize: 2,
      minimap: { enabled: true, renderCharacters: false, maxColumn: 90, showSlider: "mouseover" },
      scrollBeyondLastLine: false,
      smoothScrolling: true,
      cursorBlinking: "smooth",
      cursorSmoothCaretAnimation: "on",
      renderLineHighlight: "all",
      renderWhitespace: "selection",
      padding: { top: 10, bottom: 32 },
      glyphMargin: false,
      folding: true,
      foldingHighlight: true,
      showFoldingControls: "mouseover",
      stickyScroll: { enabled: true },
      bracketPairColorization: { enabled: true },
      guides: { bracketPairs: true, indentation: true, highlightActiveIndentation: true },
      inlineSuggest: { enabled: true },
      suggest: {
        showWords: true,
        showSnippets: true,
        preview: true,
        showStatusBar: true,
        snippetsPreventQuickSuggestions: false,
      },
      quickSuggestions: { other: true, comments: false, strings: true },
      suggestOnTriggerCharacters: true,
      acceptSuggestionOnEnter: "smart",
      tabCompletion: "on",
      wordBasedSuggestions: "currentDocument",
      scrollbar: { verticalScrollbarSize: 10, horizontalScrollbarSize: 10, useShadows: false },
      overviewRulerBorder: false,
      fixedOverflowWidgets: true,
    });
    editorRef.current = editor;
    decorationsRef.current = editor.createDecorationsCollection();
    ensureEditorOpener();

    const contentSubscription = editor.onDidChangeModelContent(() => {
      const model = editor.getModel();
      if (!model || model.uri.toString() !== currentUriRef.current) return;
      if (suppressRef.current) return;
      const text = model.getValue();
      onChangeRef.current(text);
      entryRef.current?.client.changeDocument(currentUriRef.current, text);
    });

    editor.addCommand(monaco.KeyMod.CtrlCmd | monaco.KeyCode.KeyS, () => {
      onSaveRef.current?.();
    });

    return () => {
      contentSubscription.dispose();
      decorationsRef.current?.clear();
      decorationsRef.current = null;
      editorRef.current = null;
      attachedUri = null;
      editor.dispose();
    };
  }, []);

  /* 2. 切换模型 / 语言 */
  useEffect(() => {
    const editor = editorRef.current;
    if (!editor || !workspaceRoot || !uri) return;
    const previous = currentUriRef.current;
    if (previous && previous !== uri) {
      viewStatesRef.current.set(previous, editor.saveViewState());
    }
    const model = acquireModel(uri, language, valueRef.current);
    if (editor.getModel() !== model) {
      editor.setModel(model);
      const state = viewStatesRef.current.get(uri);
      editor.restoreViewState(state ?? null);
    }
    currentUriRef.current = uri;
    attachedUri = uri;
    registerAiInline(language);
  }, [uri, language, workspaceRoot]);

  /* 3. 外部内容变化（重新读取 / 接受变更 / 撤销草稿）写回模型 */
  useEffect(() => {
    const editor = editorRef.current;
    const model = editor?.getModel();
    if (!model || model.uri.toString() !== uri) return;
    if (model.getValue() === value) return;
    suppressRef.current = true;
    try {
      model.setValue(value);
    } finally {
      suppressRef.current = false;
    }
  }, [value, uri]);

  /* 4. 语言服务器连接 */
  useEffect(() => {
    if (!projectId || !workspaceRoot || !catalog || !serverId) return;
    const entry = acquireConnection(projectId, workspaceRoot, serverId, catalog, language);
    entryRef.current = entry;
    const listener = (status: LspStatus, message?: string) => {
      onLspStatusRef.current?.(status, message);
      setConnectionEpoch((epoch) => epoch + 1);
    };
    entry.listeners.add(listener);
    listener(entry.status, entry.message);
    return () => {
      entry.listeners.delete(listener);
      if (entryRef.current === entry) entryRef.current = null;
      releaseConnection(entry.key);
    };
  }, [projectId, workspaceRoot, catalog, serverId, language]);

  /* 没有服务器时明确告诉宿主，状态栏显示「无 LSP」而不是一直转圈 */
  useEffect(() => {
    if (!catalog) return;
    if (!serverId) {
      onLspStatusRef.current?.("idle", languageId ? "该语言暂无可用语言服务器" : "未识别语言");
    }
  }, [catalog, serverId, languageId]);

  /* 5. 文档生命周期：语言服务器就绪后 didOpen，切走 didClose */
  useEffect(() => {
    const entry = entryRef.current;
    const editor = editorRef.current;
    const model = editor?.getModel();
    if (!entry || !model || model.uri.toString() !== uri) return;
    const documentUri = uri;
    entry.client.openDocument(documentUri, model.getLanguageId(), model.getValue());
    return () => {
      entry.client.closeDocument(documentUri);
      clearDiagnostics(documentUri);
    };
  }, [uri, language, connectionEpoch]);

  /* 6. 新增行底色 */
  useEffect(() => {
    const collection = decorationsRef.current;
    if (!collection) return;
    const ranges: monaco.editor.IModelDeltaDecoration[] = [];
    if (changedLines) {
      for (const line of changedLines) {
        if (!Number.isFinite(line) || line < 1) continue;
        ranges.push({
          range: new monaco.Range(line, 1, line, 1),
          options: {
            isWholeLine: true,
            className: "monaco-changed-line",
            linesDecorationsClassName: "monaco-changed-gutter",
          },
        });
      }
    }
    collection.set(ranges);
  }, [changedLines, uri]);

  /* 7. 只读 */
  useEffect(() => {
    editorRef.current?.updateOptions({ readOnly });
  }, [readOnly]);

  /* 8. 滚动到指定行 */
  useEffect(() => {
    const editor = editorRef.current;
    const model = editor?.getModel();
    if (!editor || !model || !revealLine || revealLine < 1) return;
    const line = Math.min(revealLine, model.getLineCount());
    editor.revealLineInCenter(line, monaco.editor.ScrollType.Smooth);
    editor.setPosition({ lineNumber: line, column: 1 });
    editor.focus();
  }, [revealLine, uri]);

  /* 9. 打开器与 AI 回调走模块级 ref，避免注册的 provider 捕获旧状态 */
  useEffect(() => {
    openerState.root = workspaceRoot;
    openerState.open = (target, line) => onOpenFileRef.current?.(target, line);
    aiState.root = workspaceRoot;
    aiState.run = aiComplete ?? null;
  }, [workspaceRoot, aiComplete]);

  return <div className="monaco-host" ref={hostRef} />;
}

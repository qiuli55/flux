/**
 * 最小 LSP 客户端：WebSocket 传输 + JSON-RPC 配对 + 文档生命周期。
 *
 * 后端网关（flux/api/v1/ide.py）已经把 stdio 语言服务器转成「一帧一条 JSON-RPC」的
 * WebSocket，所以这里不需要 Content-Length 头，直接 `JSON.stringify` 发帧。
 *
 * 网关替我们兜掉了一部分 server→client 请求（workspace/configuration、
 * workspace/applyEdit 等），但协议上仍然可能收到，这里同样给默认应答，
 * 免得语言服务器因为等不到回包而卡住。
 *
 * 断线重连：网络抖动或后端重启后按 1s / 2s / 4s … 退避重连（上限 15s），
 * 重连成功后重新 initialize，并把已打开的文档全部 didOpen 一遍。
 * 若收到 `flux/error`（服务器没装 / 启动失败），视为致命错误，不再重试。
 */
import type { IdeCatalog } from "../../api/types";

export type LspStatus = "idle" | "connecting" | "initializing" | "ready" | "error" | "closed";

export interface LspPosition {
  line: number;
  character: number;
}

export interface LspRange {
  start: LspPosition;
  end: LspPosition;
}

export interface LspDiagnostic {
  range: LspRange;
  severity?: number;
  code?: string | number;
  codeDescription?: { href: string };
  source?: string;
  message: string;
  tags?: number[];
  relatedInformation?: unknown[];
  data?: unknown;
}

export interface LspDocumentSymbol {
  name: string;
  detail?: string;
  kind: number;
  tags?: number[];
  range: LspRange;
  selectionRange: LspRange;
  children?: LspDocumentSymbol[];
}

export interface LspRegistration {
  id: string;
  method: string;
  registerOptions?: unknown;
}

export interface LspClientOptions {
  /** ws(s)://…/ide/lsp/{project_id}?server=… */
  url: string;
  /** 工作区根的 file:// URI */
  rootUri: string;
  rootName: string;
  onStatus?: (status: LspStatus, message?: string) => void;
  onNotification?: (method: string, params: unknown) => void;
  onDiagnostics?: (uri: string, diagnostics: LspDiagnostic[]) => void;
  /** server → client 的动态注册（主要是 semanticTokens / inlayHint） */
  onRegisterCapability?: (registrations: LspRegistration[]) => void;
  onUnregisterCapability?: (unregisterations: Array<{ id: string; method: string }>) => void;
  /** 自定义的 server → client 请求处理；返回值即应答 result，返回 undefined 走默认应答 */
  onRequest?: (method: string, params: unknown) => unknown;
}

interface PendingRequest {
  resolve: (value: unknown) => void;
  reject: (error: Error) => void;
}

interface OpenDocument {
  languageId: string;
  version: number;
  text: string;
}

const MAX_BACKOFF = 15_000;
const BASE_BACKOFF = 1_000;

function clientCapabilities(): Record<string, unknown> {
  const textDocument = {
    synchronization: { dynamicRegistration: false, willSave: false, willSaveWaitUntil: false, didSave: true },
    completion: {
      dynamicRegistration: false,
      contextSupport: true,
      completionItem: {
        snippetSupport: true,
        commitCharactersSupport: true,
        documentationFormat: ["markdown", "plaintext"],
        deprecatedSupport: true,
        preselectSupport: true,
        tagSupport: { valueSet: [1] },
        insertReplaceSupport: true,
        resolveSupport: { properties: ["documentation", "detail", "additionalTextEdits"] },
        labelDetailsSupport: true,
      },
      completionItemKind: {
        valueSet: Array.from({ length: 25 }, (_item, index) => index + 1),
      },
    },
    hover: { dynamicRegistration: false, contentFormat: ["markdown", "plaintext"] },
    signatureHelp: {
      dynamicRegistration: false,
      signatureInformation: {
        documentationFormat: ["markdown", "plaintext"],
        parameterInformation: { labelOffsetSupport: true },
        activeParameterSupport: true,
      },
      contextSupport: true,
    },
    declaration: { dynamicRegistration: false, linkSupport: true },
    definition: { dynamicRegistration: false, linkSupport: true },
    typeDefinition: { dynamicRegistration: false, linkSupport: true },
    implementation: { dynamicRegistration: false, linkSupport: true },
    references: { dynamicRegistration: false },
    documentHighlight: { dynamicRegistration: false },
    documentSymbol: {
      dynamicRegistration: false,
      hierarchicalDocumentSymbolSupport: true,
      symbolKind: { valueSet: Array.from({ length: 26 }, (_item, index) => index + 1) },
      tagSupport: { valueSet: [1] },
    },
    codeAction: {
      dynamicRegistration: false,
      codeActionLiteralSupport: {
        codeActionKind: {
          valueSet: [
            "",
            "quickfix",
            "refactor",
            "refactor.extract",
            "refactor.inline",
            "refactor.rewrite",
            "source",
            "source.organizeImports",
            "source.fixAll",
          ],
        },
      },
      isPreferredSupport: true,
      disabledSupport: true,
      dataSupport: true,
      resolveSupport: { properties: ["edit"] },
      honorsChangeAnnotations: true,
    },
    codeLens: { dynamicRegistration: false },
    formatting: { dynamicRegistration: false },
    rangeFormatting: { dynamicRegistration: false },
    onTypeFormatting: { dynamicRegistration: false },
    rename: { dynamicRegistration: false, prepareSupport: true, honorsChangeAnnotations: true },
    documentLink: { dynamicRegistration: false, tooltipSupport: true },
    publishDiagnostics: { relatedInformation: true, tagSupport: { valueSet: [1, 2] }, versionSupport: true },
    foldingRange: { dynamicRegistration: false, rangeLimit: 5000, lineFoldingOnly: true },
    selectionRange: { dynamicRegistration: false },
    semanticTokens: {
      dynamicRegistration: true,
      requests: { range: false, full: { delta: false } },
      tokenTypes: [],
      tokenModifiers: [],
      formats: ["relative"],
      overlappingTokenSupport: false,
      multilineTokenSupport: false,
      serverCancelSupport: false,
      augmentsSyntaxTokens: true,
    },
    inlayHint: { dynamicRegistration: true, resolveSupport: { properties: ["tooltip", "textEdits", "label.tooltip", "label.location", "label.command"] } },
    diagnostic: { dynamicRegistration: false, relatedDocumentSupport: false },
  };
  return {
    workspace: {
      applyEdit: true,
      workspaceEdit: { documentChanges: true, resourceOperations: ["create", "rename", "delete"], failureHandling: "textOnlyTransactional" },
      didChangeConfiguration: { dynamicRegistration: false },
      didChangeWatchedFiles: { dynamicRegistration: false },
      symbol: { dynamicRegistration: false, symbolKind: { valueSet: Array.from({ length: 26 }, (_item, index) => index + 1) } },
      executeCommand: { dynamicRegistration: false },
      workspaceFolders: true,
      configuration: true,
      semanticTokens: { refreshSupport: true },
      codeLens: { refreshSupport: true },
      inlayHint: { refreshSupport: true },
      diagnostics: { refreshSupport: true },
    },
    textDocument,
    window: {
      showMessage: {},
      showMessageRequest: { messageActionItem: { additionalPropertiesSupport: false } },
      showDocument: { support: true },
      workDoneProgress: true,
    },
    general: {
      positionEncodings: ["utf-16"],
      staleRequestSupport: { cancel: false, retryOnContentModified: [] },
      markdown: { parser: "marked", version: "1.0.0" },
      regularExpressions: { engine: "ECMAScript", version: "ES2020" },
    },
  };
}

export class LspClient {
  private readonly options: LspClientOptions;
  private socket: WebSocket | null = null;
  private nextId = 1;
  private readonly pending = new Map<number, PendingRequest>();
  private readonly documents = new Map<string, OpenDocument>();
  private readonly cancelHandlers = new Map<number, AbortSignal>();
  private reconnectTimer: number | null = null;
  private attempts = 0;
  private disposed = false;
  private fatal = false;
  private ready = false;
  private status: LspStatus = "idle";
  private serverCapabilities: Record<string, unknown> = {};
  /** 网关已经 initialize 过，重连后仍然要重新握手（每次连接是独立会话） */
  private handshakeDone = false;

  constructor(options: LspClientOptions) {
    this.options = options;
  }

  get capabilities(): Record<string, unknown> {
    return this.serverCapabilities;
  }

  get isReady(): boolean {
    return this.ready && this.socket?.readyState === WebSocket.OPEN;
  }

  get currentStatus(): LspStatus {
    return this.status;
  }

  /** 建立连接并完成 initialize；已连接时直接返回 */
  connect(): void {
    if (this.disposed || this.socket) return;
    this.open();
  }

  dispose(): void {
    this.disposed = true;
    if (this.reconnectTimer !== null) {
      window.clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }
    const socket = this.socket;
    this.socket = null;
    this.ready = false;
    if (socket) {
      socket.onopen = null;
      socket.onmessage = null;
      socket.onerror = null;
      socket.onclose = null;
      try {
        socket.close();
      } catch {
        /* 已断开 */
      }
    }
    this.rejectAll(new Error("LSP 连接已关闭"));
    this.setStatus("closed");
  }

  request<T>(method: string, params?: unknown, signal?: AbortSignal): Promise<T> {
    if (!this.socket || this.socket.readyState !== WebSocket.OPEN) {
      return Promise.reject(new Error("LSP 尚未连接"));
    }
    const id = this.nextId++;
    return new Promise<T>((resolve, reject) => {
      this.pending.set(id, { resolve: resolve as (value: unknown) => void, reject });
      if (signal) {
        if (signal.aborted) {
          this.pending.delete(id);
          reject(new Error("cancelled"));
          return;
        }
        const onAbort = () => {
          if (!this.pending.has(id)) return;
          this.pending.delete(id);
          this.send({ jsonrpc: "2.0", method: "$/cancelRequest", params: { id } });
          reject(new Error("cancelled"));
        };
        signal.addEventListener("abort", onAbort, { once: true });
        this.cancelHandlers.set(id, signal);
      }
      this.send({ jsonrpc: "2.0", id, method, params });
    });
  }

  notify(method: string, params?: unknown): void {
    if (!this.isReady) return;
    this.send({ jsonrpc: "2.0", method, params });
  }

  /** 文档第一次进入服务器；重复调用等价于一次整篇 didChange */
  openDocument(uri: string, languageId: string, text: string): void {
    const existing = this.documents.get(uri);
    if (existing) {
      if (existing.languageId !== languageId) existing.languageId = languageId;
      this.changeDocument(uri, text);
      return;
    }
    this.documents.set(uri, { languageId, version: 1, text });
    if (this.isReady) {
      this.send({
        jsonrpc: "2.0",
        method: "textDocument/didOpen",
        params: { textDocument: { uri, languageId, version: 1, text } },
      });
    }
  }

  changeDocument(uri: string, text: string): void {
    const doc = this.documents.get(uri);
    if (!doc) return;
    if (doc.text === text) return;
    doc.text = text;
    doc.version += 1;
    if (!this.isReady) return;
    // 整篇替换：LSP 规范里不带 range 的 change 对 Full / Incremental 服务器都合法
    this.send({
      jsonrpc: "2.0",
      method: "textDocument/didChange",
      params: { textDocument: { uri, version: doc.version }, contentChanges: [{ text }] },
    });
  }

  closeDocument(uri: string): void {
    if (!this.documents.has(uri)) return;
    this.documents.delete(uri);
    if (!this.isReady) return;
    this.send({ jsonrpc: "2.0", method: "textDocument/didClose", params: { textDocument: { uri } } });
  }

  /* ---------- 内部 ---------- */

  private setStatus(status: LspStatus, message?: string): void {
    if (this.status === status) return;
    this.status = status;
    this.options.onStatus?.(status, message);
  }

  private send(payload: unknown): void {
    const socket = this.socket;
    if (!socket || socket.readyState !== WebSocket.OPEN) return;
    socket.send(JSON.stringify(payload));
  }

  private open(): void {
    this.setStatus("connecting");
    let socket: WebSocket;
    try {
      socket = new WebSocket(this.options.url);
    } catch {
      this.fatal = true;
      this.setStatus("error", "无法建立语言服务器连接");
      return;
    }
    this.socket = socket;
    socket.onopen = () => {
      this.attempts = 0;
      void this.handshake();
    };
    socket.onmessage = (event: MessageEvent<string>) => {
      this.handleMessage(event.data);
    };
    socket.onerror = () => {
      /* onclose 会跟着触发，统一在那里处理 */
    };
    socket.onclose = () => {
      this.socket = null;
      this.ready = false;
      this.handshakeDone = false;
      this.rejectAll(new Error("语言服务器连接已断开"));
      if (this.disposed || this.fatal) {
        this.setStatus(this.fatal ? "error" : "closed");
        return;
      }
      this.setStatus("connecting", "连接已断开，正在重连…");
      this.scheduleReconnect();
    };
  }

  private scheduleReconnect(): void {
    if (this.reconnectTimer !== null) return;
    const delay = Math.min(BASE_BACKOFF * 2 ** this.attempts, MAX_BACKOFF);
    this.attempts += 1;
    this.reconnectTimer = window.setTimeout(() => {
      this.reconnectTimer = null;
      if (!this.disposed) this.open();
    }, delay);
  }

  private async handshake(): Promise<void> {
    if (this.handshakeDone) return;
    this.handshakeDone = true;
    this.setStatus("initializing");
    const params = {
      processId: null,
      clientInfo: { name: "Flux IDE", version: "1.0.0" },
      locale: "zh-CN",
      rootPath: this.options.rootUri.replace(/^file:\/\//, ""),
      rootUri: this.options.rootUri,
      workspaceFolders: [{ uri: this.options.rootUri, name: this.options.rootName }],
      capabilities: clientCapabilities(),
      initializationOptions: {},
      trace: "off",
    };
    try {
      const result = await this.request<{ capabilities?: Record<string, unknown> }>("initialize", params);
      this.serverCapabilities = result?.capabilities ?? {};
      this.send({ jsonrpc: "2.0", method: "initialized", params: {} });
      this.ready = true;
      // 重连后把已打开的文档重新交给服务器（新进程里没有这些文档）
      for (const [uri, doc] of this.documents) {
        this.send({
          jsonrpc: "2.0",
          method: "textDocument/didOpen",
          params: { textDocument: { uri, languageId: doc.languageId, version: doc.version, text: doc.text } },
        });
      }
      this.setStatus("ready");
    } catch (error) {
      this.fatal = true;
      this.setStatus("error", error instanceof Error ? error.message : String(error));
      try {
        this.socket?.close();
      } catch {
        /* 忽略 */
      }
    }
  }

  private handleMessage(raw: string): void {
    let message: Record<string, unknown>;
    try {
      message = JSON.parse(raw) as Record<string, unknown>;
    } catch {
      return;
    }
    const method = typeof message.method === "string" ? message.method : null;
    const id = message.id;
    if (method && id !== undefined && id !== null) {
      this.handleServerRequest(id as number | string, method, message.params);
      return;
    }
    if (method) {
      if (method === "flux/error") {
        this.fatal = true;
        const params = message.params as { message?: string } | undefined;
        this.setStatus("error", params?.message ?? "语言服务器不可用");
        return;
      }
      if (method === "flux/serverExited") {
        this.fatal = true;
        const params = message.params as { message?: string; code?: number } | undefined;
        this.setStatus("error", params?.message ?? "语言服务器进程已退出");
        return;
      }
      if (method === "textDocument/publishDiagnostics") {
        const params = message.params as { uri?: string; diagnostics?: LspDiagnostic[] } | undefined;
        if (params?.uri) this.options.onDiagnostics?.(params.uri, params.diagnostics ?? []);
        return;
      }
      if (method === "$/progress") return;
      this.options.onNotification?.(method, message.params);
      return;
    }
    if (id === undefined || id === null) return;
    const pending = this.pending.get(id as number);
    if (!pending) return;
    this.pending.delete(id as number);
    const signal = this.cancelHandlers.get(id as number);
    if (signal) this.cancelHandlers.delete(id as number);
    if (message.error) {
      const error = message.error as { message?: string; code?: number };
      pending.reject(new Error(error.message ?? "语言服务器返回错误"));
      return;
    }
    pending.resolve(message.result);
  }

  private handleServerRequest(id: number | string, method: string, params: unknown): void {
    const custom = this.options.onRequest?.(method, params);
    if (custom !== undefined) {
      this.reply(id, custom);
      return;
    }
    switch (method) {
      case "workspace/configuration": {
        const items = (params as { items?: unknown[] } | undefined)?.items ?? [];
        this.reply(id, items.map(() => null));
        return;
      }
      case "workspace/workspaceFolders":
        this.reply(id, [{ uri: this.options.rootUri, name: this.options.rootName }]);
        return;
      case "client/registerCapability": {
        const registrations = (params as { registrations?: LspRegistration[] } | undefined)?.registrations ?? [];
        this.options.onRegisterCapability?.(registrations);
        this.reply(id, null);
        return;
      }
      case "client/unregisterCapability": {
        const unregisterations =
          (params as { unregisterations?: Array<{ id: string; method: string }> } | undefined)?.unregisterations ?? [];
        this.options.onUnregisterCapability?.(unregisterations);
        this.reply(id, null);
        return;
      }
      case "workspace/applyEdit":
        this.reply(id, { applied: false, failureReason: "Flux 只读网关不代改文件" });
        return;
      case "window/showDocument":
        this.reply(id, { success: false });
        return;
      case "window/showMessageRequest":
        this.reply(id, null);
        return;
      case "workspace/codeLens/refresh":
      case "workspace/semanticTokens/refresh":
      case "workspace/inlayHint/refresh":
      case "workspace/diagnostic/refresh":
        this.reply(id, null);
        return;
      default:
        this.reply(id, null);
    }
  }

  private reply(id: number | string, result: unknown): void {
    this.send({ jsonrpc: "2.0", id, result });
  }

  private rejectAll(error: Error): void {
    for (const pending of this.pending.values()) pending.reject(error);
    this.pending.clear();
    this.cancelHandlers.clear();
  }
}

/** 工作区根 + 相对路径 → file:// URI（POSIX 语义，逐段编码） */
export function fileUri(root: string, relativePath: string): string {
  const normalizedRoot = root.replace(/\\/g, "/").replace(/\/+$/, "");
  const relative = relativePath.replace(/\\/g, "/").replace(/^\/+/, "");
  const encodedRoot = normalizedRoot.split("/").map(encodeURIComponent).join("/");
  const encodedPath = relative.split("/").map(encodeURIComponent).join("/");
  return `file://${encodedRoot}${encodedPath ? `/${encodedPath}` : ""}`;
}

/** file:// URI → 相对工作区根的路径；不在工作区内返回 null */
export function pathFromUri(root: string, uri: string): string | null {
  let decoded: string;
  try {
    decoded = decodeURIComponent(uri.replace(/^file:\/\//, ""));
  } catch {
    return null;
  }
  const normalizedRoot = root.replace(/\\/g, "/").replace(/\/+$/, "");
  const normalizedPath = decoded.replace(/\\/g, "/");
  if (!normalizedPath.startsWith(`${normalizedRoot}/`)) return null;
  return normalizedPath.slice(normalizedRoot.length + 1);
}

export type { IdeCatalog };

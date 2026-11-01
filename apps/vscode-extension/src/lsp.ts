/**
 * 把 Flux 后端的 LSP 网关接进 VS Code / VSCodium。
 *
 * 网关 `WS /api/v1/ide/lsp/{project_id}?server=&workspace_root=` 上跑的是原样
 * JSON-RPC，一帧一条消息（没有 Content-Length 头）。这里实现一对自定义
 * MessageReader / MessageWriter，交给 vscode-languageclient，于是补全、悬停、
 * 跳转定义、重命名、文档符号、格式化、Code Action、诊断、语义高亮、签名帮助
 * 等能力全部由官方客户端接管，与内嵌 IDE 用的是同一批常驻语言服务器进程。
 */
import * as vscode from "vscode";
import {
  CloseAction,
  ErrorAction,
  LanguageClient,
  LanguageClientOptions,
  MessageTransports,
  RevealOutputChannelOn,
  ServerOptions,
  State,
} from "vscode-languageclient/node";
import WebSocket from "ws";
import { FluxClient, FluxLanguageCatalog, FluxLanguageServer } from "./api";

/** 语言目录缓存时长：改配置或重装服务器后点「重启」会强制刷新。 */
const CATALOG_TTL_MS = 5 * 60_000;

const CLOSE_ACTION = CloseAction.DoNotRestart;

type FluxNotify = (method: string, params: Record<string, unknown>) => void;

/**
 * 内置 TS/JS 扩展自己注册的一批 `_typescript.*` 命令。
 * 语言服务器会在 executeCommandProvider 里再声明一次，重复注册会让整个客户端启动失败。
 */
const RESERVED_COMMAND_PREFIXES = ["_typescript."];

/** 一条连接在握手期间需要共享的状态：initialize 的请求 id 与不能重复注册的命令。 */
interface TransportContext {
  initializeId?: string | number;
  reservedCommands: Set<string>;
}

/**
 * 从 initialize 响应里剔除「工作台已经注册过」的命令。
 *
 * vscode-languageclient 会在初始化时把服务端声明的命令逐个注册成 VS Code 命令，
 * 一旦重名就抛 `command 'xxx' already exists`，整个语言客户端随之启动失败。
 * 内置 TypeScript 扩展与 Flux 连的是不同进程，却共享同一条命令命名空间，
 * 所以在传输层把冲突项摘掉，剩下的能力照常工作。
 */
function sanitizeMessage(message: unknown, context: TransportContext): unknown {
  if (context.initializeId === undefined) {
    return message;
  }
  const envelope = message as {
    id?: unknown;
    result?: { capabilities?: { executeCommandProvider?: { commands?: unknown } } };
  };
  if (!envelope || envelope.id !== context.initializeId) {
    return message;
  }
  const capability = envelope.result?.capabilities;
  const provider = capability?.executeCommandProvider;
  if (!capability || !provider || !Array.isArray(provider.commands)) {
    return message;
  }
  const declared = provider.commands;
  const kept = declared.filter(
    (command) =>
      typeof command === "string" &&
      !context.reservedCommands.has(command) &&
      !RESERVED_COMMAND_PREFIXES.some((prefix) => command.startsWith(prefix))
  );
  if (kept.length === declared.length) {
    return message;
  }
  return {
    ...envelope,
    result: {
      ...envelope.result,
      capabilities: {
        ...capability,
        executeCommandProvider: { ...provider, commands: kept },
      },
    },
  };
}

/**
 * WebSocket 上的 LSP 消息读取器：一帧一条 JSON-RPC。
 * 顺带把网关自定义的 `flux/error`、`flux/serverExited` 通知转成状态回调。
 */
class WsMessageReader {
  private readonly onErrorEmitter = new vscode.EventEmitter<Error>();
  private readonly onCloseEmitter = new vscode.EventEmitter<void>();
  private readonly onPartialMessageEmitter = new vscode.EventEmitter<{ partialMessageInfo: unknown }>();

  readonly onError = this.onErrorEmitter.event;
  readonly onClose = this.onCloseEmitter.event;
  readonly onPartialMessage = this.onPartialMessageEmitter.event;

  constructor(
    private readonly socket: WebSocket,
    private readonly notify: FluxNotify,
    private readonly context: TransportContext
  ) {
    socket.on("error", (err: Error) => {
      this.onErrorEmitter.fire(err);
    });
    socket.on("close", () => {
      this.onCloseEmitter.fire();
    });
  }

  listen(callback: (message: unknown) => void): void {
    this.socket.on("message", (data) => {
      let message: unknown;
      try {
        message = JSON.parse(data.toString());
      } catch (err) {
        this.onErrorEmitter.fire(err as Error);
        return;
      }
      const method = (message as { method?: unknown }).method;
      if (method === "flux/error" || method === "flux/serverExited") {
        // 网关自家的通知，不进 JSON-RPC 通道，只用来更新状态栏
        const params = (message as { params?: Record<string, unknown> }).params ?? {};
        this.notify(method, params);
        return;
      }
      callback(sanitizeMessage(message, this.context));
    });
  }

  dispose(): void {
    this.onErrorEmitter.dispose();
    this.onCloseEmitter.dispose();
    this.onPartialMessageEmitter.dispose();
  }
}

/** WebSocket 上的 LSP 消息写入器：把 JSON-RPC 对象直接作为一帧文本发出。 */
class WsMessageWriter {
  private readonly onErrorEmitter = new vscode.EventEmitter<Error>();
  private readonly onCloseEmitter = new vscode.EventEmitter<void>();

  readonly onError = this.onErrorEmitter.event;
  readonly onClose = this.onCloseEmitter.event;

  constructor(private readonly socket: WebSocket, private readonly context: TransportContext) {}

  write(message: unknown): Promise<void> {
    const request = message as { id?: string | number; method?: string };
    if (request?.method === "initialize" && request.id !== undefined) {
      this.context.initializeId = request.id;
    }
    return new Promise<void>((resolve, reject) => {
      if (this.socket.readyState !== WebSocket.OPEN) {
        const err = new Error("Flux LSP 连接已关闭");
        this.onErrorEmitter.fire(err);
        reject(err);
        return;
      }
      this.socket.send(JSON.stringify(message), (err?: Error) => {
        if (err) {
          this.onErrorEmitter.fire(err);
          reject(err);
          return;
        }
        resolve();
      });
    });
  }

  end(): void {
    try {
      this.socket.close();
    } catch {
      /* 已经断了就不用管 */
    }
    this.onCloseEmitter.fire();
  }

  dispose(): void {
    this.onErrorEmitter.dispose();
    this.onCloseEmitter.dispose();
  }
}

/** 建立到网关的 WebSocket，等握手成功后再把 reader/writer 交给客户端。 */
function openTransport(
  url: string,
  token: string,
  notify: FluxNotify,
  reservedCommands: Set<string>
): Promise<MessageTransports> {
  return new Promise<MessageTransports>((resolve, reject) => {
    const socket = new WebSocket(url, token ? { headers: { Authorization: `Bearer ${token}` } } : undefined);
    const context: TransportContext = { reservedCommands };
    let settled = false;

    const fail = (err: Error): void => {
      if (settled) {
        return;
      }
      settled = true;
      try {
        socket.terminate();
      } catch {
        /* ignore */
      }
      reject(err);
    };

    socket.once("open", () => {
      if (settled) {
        return;
      }
      settled = true;
      resolve({
        reader: new WsMessageReader(socket, notify, context) as unknown as MessageTransports["reader"],
        writer: new WsMessageWriter(socket, context) as unknown as MessageTransports["writer"],
      });
    });
    socket.once("error", (err: Error) => fail(err));
    socket.once("close", (code: number, reason: Buffer) =>
      fail(new Error(`Flux LSP 连接关闭（${code} ${reason.toString() || "无原因"}）`))
    );
    socket.once("unexpected-response", (_request, response) => {
      response.resume();
      fail(new Error(`Flux LSP 握手失败：HTTP ${response.statusCode}`));
    });
  });
}

/** 覆盖 createMessageTransports，让官方客户端直接跑在我们的 WebSocket 上。 */
class FluxLanguageClient extends LanguageClient {
  constructor(
    id: string,
    name: string,
    private readonly transportFactory: () => Promise<MessageTransports>,
    clientOptions: LanguageClientOptions
  ) {
    // serverOptions 只在默认实现里用到；这里被下面的重写完全接管
    super(id, name, { command: "flux-lsp" } as ServerOptions, clientOptions);
  }

  protected override createMessageTransports(_encoding: string): Promise<MessageTransports> {
    return this.transportFactory();
  }

  /**
   * 官方客户端在连接失败时会强制弹「couldn't create connection to server.」，
   * 文案通用、对用户没有信息量。这里只写进输出通道，真正原因由 LanguageService
   * 汇总到状态栏与一次性提示里。
   */
  override error(message: string, data?: unknown, _showNotification?: boolean | "force"): void {
    super.error(message, data, false);
  }
}

interface Entry {
  serverId: string;
  root: string;
  folder: vscode.WorkspaceFolder;
  client: FluxLanguageClient;
  state: "starting" | "running" | "error";
  error?: string;
  startedAt: number;
  /** 连续重连次数，用于指数退避；连接成功后清零。 */
  attempts: number;
  timer?: NodeJS.Timeout;
}

/** 重连等待时间：1s、2s、4s…封顶 30s。 */
function reconnectDelay(attempts: number): number {
  return Math.min(1000 * 2 ** Math.min(attempts - 1, 5), 30_000);
}

function lspUrl(baseUrl: string, projectId: string, serverId: string, root: string): string {
  const base = new URL(baseUrl);
  const scheme = base.protocol === "https:" ? "wss:" : "ws:";
  const query = new URLSearchParams({ server: serverId, workspace_root: root });
  return `${scheme}//${base.host}/api/v1/ide/lsp/${encodeURIComponent(projectId)}?${query.toString()}`;
}

function normalizePath(path: string): string {
  return path.replace(/[\\/]+$/, "").toLowerCase();
}

/**
 * 语言服务管理器：按「服务器 + 工作区」各起一个客户端，随文件打开惰性启动。
 */
export class LanguageService implements vscode.Disposable {
  private readonly entries = new Map<string, Entry>();
  private readonly output: vscode.OutputChannel;
  private readonly statusItem: vscode.StatusBarItem;
  private readonly disposables: vscode.Disposable[] = [];
  private catalogCache?: { at: number; catalog: FluxLanguageCatalog };
  private projectsCache?: { at: number; byRoot: Map<string, string>; single?: string };
  private warned = new Set<string>();
  private disposed = false;

  constructor(private readonly getFluxClient: () => FluxClient) {
    this.output = vscode.window.createOutputChannel("Flux 语言服务");
    this.statusItem = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Left, 9);
    this.statusItem.command = "flux.showLanguageServers";
    this.disposables.push(this.output, this.statusItem);
    this.render();
  }

  private get enabled(): boolean {
    return vscode.workspace.getConfiguration("flux").get<boolean>("lsp.enabled", true);
  }

  activate(context: vscode.ExtensionContext): void {
    this.disposables.push(
      vscode.window.onDidChangeActiveTextEditor((editor) => {
        if (editor) {
          void this.ensure(editor.document);
        }
      }),
      vscode.workspace.onDidOpenTextDocument((document) => {
        void this.ensure(document);
      }),
      vscode.workspace.onDidChangeConfiguration((event) => {
        if (
          event.affectsConfiguration("flux.lsp.enabled") ||
          event.affectsConfiguration("flux.serverUrl") ||
          event.affectsConfiguration("flux.token") ||
          event.affectsConfiguration("flux.projectId")
        ) {
          this.catalogCache = undefined;
          this.projectsCache = undefined;
          void this.restart("配置已变更，重新连接语言服务");
        }
      }),
      vscode.commands.registerCommand("flux.restartLanguageServer", () => this.restart()),
      vscode.commands.registerCommand("flux.showLanguageServers", () => this.showPanel())
    );
    context.subscriptions.push(this);
    for (const editor of vscode.window.visibleTextEditors) {
      void this.ensure(editor.document);
    }
    if (vscode.window.activeTextEditor) {
      void this.ensure(vscode.window.activeTextEditor.document);
    }
  }

  dispose(): void {
    this.disposed = true;
    for (const entry of this.entries.values()) {
      if (entry.timer) {
        clearTimeout(entry.timer);
      }
      void entry.client.stop();
    }
    this.entries.clear();
    for (const disposable of this.disposables) {
      disposable.dispose();
    }
  }

  /** 为某个文档准备语言服务器；重复调用只更新状态。 */
  async ensure(document: vscode.TextDocument): Promise<void> {
    if (this.disposed || !this.enabled || document.uri.scheme !== "file") {
      return;
    }
    const folder = vscode.workspace.getWorkspaceFolder(document.uri);
    if (!folder) {
      return;
    }
    const catalog = await this.catalog();
    if (!catalog) {
      return;
    }
    const language = catalog.languages.find((item) => item.id === document.languageId);
    if (!language || !language.server || !language.available) {
      return;
    }
    const key = `${language.server}\u0000${folder.uri.fsPath}`;
    if (this.entries.has(key) || this.starting.has(key)) {
      return;
    }
    this.starting.add(key);
    try {
      await this.start(key, language.server, folder);
    } finally {
      this.starting.delete(key);
    }
  }

  private readonly starting = new Set<string>();

  private async start(
    key: string,
    serverId: string,
    folder: vscode.WorkspaceFolder,
    attempts = 0
  ): Promise<void> {
    if (this.disposed) {
      return;
    }
    const flux = this.getFluxClient();
    const projectId = await this.projectId(folder.uri.fsPath);
    if (!projectId) {
      this.entries.delete(key);
      this.warnOnce(
        "no-project",
        "Flux：当前工作区没有匹配的项目，代码智能未启用（可在设置里填 flux.projectId）"
      );
      this.render();
      return;
    }

    const state: Entry = {
      serverId,
      root: folder.uri.fsPath,
      folder,
      client: undefined as unknown as FluxLanguageClient,
      state: "starting",
      startedAt: Date.now(),
      attempts,
    };

    const notify: FluxNotify = (method, params) => {
      if (method === "flux/error") {
        // 后面紧跟着就是 1011 关闭，重连交给 start() 的 catch 处理
        state.error = String(params.message ?? "语言服务器启动失败");
        this.output.appendLine(`[${serverId}] 网关错误：${state.error}`);
        this.render();
        return;
      }
      this.output.appendLine(`[${serverId}] 语言服务器进程已退出，准备重连`);
      void state.client?.stop().catch(() => undefined);
      this.reconnect(key, state, "语言服务器已退出，正在重连");
    };

    const catalog = await this.catalog();
    const languages =
      catalog?.servers.find((item) => item.id === serverId)?.languages ??
      catalog?.languages.filter((item) => item.server === serverId).map((item) => item.id) ??
      [];
    const reservedCommands = await this.reservedCommands();

    const client = new FluxLanguageClient(
      `flux-${serverId}`,
      `Flux ${serverId}`,
      () =>
        openTransport(
          lspUrl(flux.baseUrl, projectId, serverId, folder.uri.fsPath),
          flux.authToken,
          notify,
          reservedCommands
        ),
      {
        documentSelector: languages.map((language) => ({ language, scheme: "file" })),
        workspaceFolder: folder,
        outputChannel: this.output,
        revealOutputChannelOn: RevealOutputChannelOn.Never,
        // 后端重启、进程被杀这类情况交给 Flux 自己处理，别弹官方客户端的报错框
        errorHandler: {
          error: (error, _message, count) => {
            this.output.appendLine(`[${serverId}] 客户端错误(#${count ?? 0}): ${error.message}`);
            return { action: ErrorAction.Continue, handled: true };
          },
          closed: () => {
            if (!this.disposed && this.entries.get(key) === state) {
              this.reconnect(key, state, "连接已断开，正在重连");
            }
            return { action: CLOSE_ACTION, handled: true };
          },
        },
      } as LanguageClientOptions
    );

    state.client = client;
    this.entries.set(key, state);
    this.render();

    try {
      await client.start();
      state.state = client.state === State.Running ? "running" : "starting";
      state.attempts = 0;
      state.error = undefined;
      this.output.appendLine(`[${serverId}] 已连接（workspace=${folder.uri.fsPath}）`);
    } catch (err) {
      const message = (err as Error).message;
      state.error = state.error ?? message;
      this.output.appendLine(`[${serverId}] 启动失败：${message}`);
      this.warnOnce(
        `start-${serverId}-${folder.uri.fsPath}`,
        `Flux 语言服务（${serverId}）启动失败：${state.error}`
      );
      this.reconnect(key, state, `启动失败：${message}`);
    }
    this.render();
  }

  /**
   * 连接断开后的重连：指数退避，连续失败三次后状态栏转为「异常」，
   * 但后台仍会继续尝试，恢复后自动回到「✓」。
   */
  private reconnect(key: string, entry: Entry, reason: string): void {
    if (this.disposed || this.entries.get(key) !== entry) {
      return;
    }
    entry.attempts += 1;
    entry.error = reason;
    entry.state = entry.attempts >= 3 ? "error" : "starting";
    this.render();

    if (entry.timer) {
      clearTimeout(entry.timer);
    }
    const delay = reconnectDelay(entry.attempts);
    this.output.appendLine(
      `[${entry.serverId}] ${reason}（第 ${entry.attempts} 次），${Math.round(delay / 1000)} 秒后重试`
    );
    const timer = setTimeout(async () => {
      if (this.disposed || this.entries.get(key) !== entry) {
        return;
      }
      entry.timer = undefined;
      this.entries.delete(key);
      this.starting.add(key);
      try {
        await this.start(key, entry.serverId, entry.folder, entry.attempts);
      } finally {
        this.starting.delete(key);
      }
    }, delay);
    timer.unref?.();
    entry.timer = timer;
  }

  /** 停掉所有客户端与后端会话，再按当前可见文件重新拉起。 */
  async restart(reason?: string): Promise<void> {
    const flux = this.getFluxClient();
    const snapshot = [...this.entries.values()];
    this.entries.clear();
    this.starting.clear();
    for (const entry of snapshot) {
      if (entry.timer) {
        clearTimeout(entry.timer);
      }
      try {
        await entry.client.stop();
      } catch (err) {
        this.output.appendLine(`停止 ${entry.serverId} 失败：${(err as Error).message}`);
      }
      try {
        await flux.stopIdeSession(entry.serverId, entry.root);
      } catch (err) {
        this.output.appendLine(`结束后端会话 ${entry.serverId} 失败：${(err as Error).message}`);
      }
    }
    this.render();
    if (reason) {
      this.output.appendLine(reason);
    }
    for (const editor of vscode.window.visibleTextEditors) {
      await this.ensure(editor.document);
    }
    if (vscode.window.activeTextEditor) {
      await this.ensure(vscode.window.activeTextEditor.document);
    }
  }

  /** 「语言服务器」面板：看状态、装缺失的服务器、单独重启。 */
  async showPanel(): Promise<void> {
    const catalog = await this.catalog(true);
    if (!catalog) {
      void vscode.window.showErrorMessage("Flux：拉取语言服务器清单失败，请检查后端连接");
      return;
    }
    const sessions = await this.getFluxClient().ideSessions().catch(() => []);
    const items = catalog.servers.map((server) => {
      const running = [...this.entries.values()].filter((entry) => entry.serverId === server.id);
      const live = sessions.filter((session) => session.server === server.id);
      const parts: string[] = [];
      parts.push(server.available ? "已安装" : "未安装");
      if (running.length) {
        parts.push(`${running.length} 个窗口在连`);
      }
      if (live.length) {
        parts.push(`后端 ${live.length} 个常驻进程`);
      }
      return {
        label: server.name,
        description: parts.join(" · "),
        detail: `${server.id} · ${server.languages.join(", ")}${server.description ? ` · ${server.description}` : ""}`,
        server,
      };
    });
    const picked = await vscode.window.showQuickPick(items, {
      title: "Flux 语言服务器",
      placeHolder: "选择一项查看或操作",
      matchOnDetail: true,
    });
    if (!picked) {
      return;
    }
    await this.operate(picked.server);
  }

  private async operate(server: FluxLanguageServer): Promise<void> {
    const actions: string[] = [];
    if (!server.available) {
      actions.push("安装");
    }
    const running = [...this.entries.values()].filter((entry) => entry.serverId === server.id);
    if (running.length) {
      actions.push("重启");
    }
    actions.push("查看输出");
    const action = await vscode.window.showQuickPick(actions, { title: server.name });
    if (!action) {
      return;
    }
    if (action === "安装") {
      if (!server.install) {
        void vscode.window.showInformationMessage(`${server.name} 需要手动安装：${server.install_hint ?? "见官方文档"}`);
        return;
      }
      const confirmed = await vscode.window.showWarningMessage(
        `将执行 ${server.install.join(" ")}，继续？`,
        { modal: true },
        "安装"
      );
      if (confirmed !== "安装") {
        return;
      }
      await vscode.window.withProgress(
        { location: vscode.ProgressLocation.Notification, title: `正在安装 ${server.name}` },
        async () => {
          try {
            const result = (await this.getFluxClient().installLanguageServer(server.id)) as { available?: boolean };
            void vscode.window.showInformationMessage(
              result?.available ? `${server.name} 安装完成` : `${server.name} 安装命令已执行，但仍检测不到可执行文件`
            );
          } catch (err) {
            void vscode.window.showErrorMessage(`安装失败：${(err as Error).message}`);
          }
        }
      );
      this.catalogCache = undefined;
      await this.restart("语言服务器清单已刷新");
      return;
    }
    if (action === "重启") {
      for (const entry of running) {
        if (entry.timer) {
          clearTimeout(entry.timer);
        }
        this.entries.delete(`${entry.serverId}\u0000${entry.root}`);
        await entry.client.stop().catch(() => undefined);
        await this.getFluxClient().stopIdeSession(entry.serverId, entry.root).catch(() => undefined);
      }
      await this.restart();
      return;
    }
    this.output.show(true);
  }

  private render(): void {
    if (this.disposed) {
      return;
    }
    const values = [...this.entries.values()];
    if (!this.enabled) {
      this.statusItem.hide();
      return;
    }
    if (!values.length) {
      this.statusItem.text = "$(code) Flux LSP: 待命";
      this.statusItem.tooltip = "打开受支持的文件后会自动连接 Flux 语言服务器；点击查看语言服务器";
      this.statusItem.backgroundColor = undefined;
      this.statusItem.show();
      return;
    }
    const running = values.filter((entry) => entry.state === "running").length;
    const broken = values.filter((entry) => entry.state === "error");
    if (broken.length) {
      this.statusItem.text = `$(warning) Flux LSP: ${broken.length} 异常`;
      this.statusItem.tooltip = broken.map((entry) => `${entry.serverId}: ${entry.error ?? "连接失败"}`).join("\n");
      this.statusItem.backgroundColor = new vscode.ThemeColor("statusBarItem.warningBackground");
    } else if (running === values.length) {
      this.statusItem.text = `$(check) Flux LSP: ${running}`;
      this.statusItem.tooltip = values.map((entry) => `${entry.serverId} · ${entry.root}`).join("\n");
      this.statusItem.backgroundColor = undefined;
    } else {
      const retrying = values.some((entry) => entry.attempts > 0);
      this.statusItem.text = retrying ? "$(sync~spin) Flux LSP: 重连中" : "$(sync~spin) Flux LSP: 连接中";
      this.statusItem.tooltip = values
        .filter((entry) => entry.state !== "running")
        .map((entry) => `${entry.serverId}: ${entry.error ?? "正在拉起…"}`)
        .join("\n");
      this.statusItem.backgroundColor = undefined;
    }
    this.statusItem.show();
  }

  /** 工作台已经注册的命令；语言服务器再声明同名命令会让客户端初始化直接失败。 */
  private async reservedCommands(): Promise<Set<string>> {
    try {
      return new Set(await vscode.commands.getCommands(true));
    } catch {
      return new Set();
    }
  }

  private async catalog(force = false): Promise<FluxLanguageCatalog | undefined> {
    const now = Date.now();
    if (!force && this.catalogCache && now - this.catalogCache.at < CATALOG_TTL_MS) {
      return this.catalogCache.catalog;
    }
    try {
      const catalog = await this.getFluxClient().ideLanguages();
      this.catalogCache = { at: now, catalog };
      return catalog;
    } catch (err) {
      this.output.appendLine(`拉取语言清单失败：${(err as Error).message}`);
      return this.catalogCache?.catalog;
    }
  }

  /** 项目解析：显式配置 → 环境变量 → 按工作区路径匹配 → 唯一项目。 */
  private async projectId(root: string): Promise<string | undefined> {
    const configured = vscode.workspace.getConfiguration("flux").get<string>("projectId") || "";
    if (configured) {
      return configured;
    }
    const fromEnv = process.env.FLUX_PROJECT_ID;
    if (fromEnv) {
      return fromEnv;
    }
    const now = Date.now();
    if (!this.projectsCache || now - this.projectsCache.at > 60_000) {
      try {
        const projects = await this.getFluxClient().projects();
        const byRoot = new Map<string, string>();
        for (const project of projects) {
          for (const candidate of [project.repository, project.path, project.root_path]) {
            if (typeof candidate === "string" && candidate) {
              byRoot.set(normalizePath(candidate), project.id);
            }
          }
        }
        this.projectsCache = { at: now, byRoot, single: projects.length === 1 ? projects[0].id : undefined };
      } catch (err) {
        this.output.appendLine(`拉取项目列表失败：${(err as Error).message}`);
        return undefined;
      }
    }
    return this.projectsCache.byRoot.get(normalizePath(root)) ?? this.projectsCache.single;
  }

  private warnOnce(key: string, message: string): void {
    if (this.warned.has(key)) {
      return;
    }
    this.warned.add(key);
    void vscode.window.showWarningMessage(message);
  }
}

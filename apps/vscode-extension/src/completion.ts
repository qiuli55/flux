import * as vscode from "vscode";
import { FluxClient, FluxCompletionPrefs, FluxFimRequest } from "./api";
import {
  COMPLETION_SCHEMES,
  CompletionMode,
  completionKey,
  insideCommentOrString,
  isBlockedLanguage,
  isBlockedPath,
  isCursorOnlyMove,
  isSupportedScheme,
  sanitizeCompletion,
  staleSessionKeys,
} from "./completionCore";

/**
 * 行内补全（AI_COMPLETION_SPEC §4 / §5 / §8）。
 *
 * 结构：
 * - `provideInlineCompletionItems` 只做三件事：判断该不该请求、拿现有会话的文本、
 *   在会话还没吐字时等第一段；
 * - 真正的网络请求由「会话」在后台跑完，增量按 150ms 节流重触发
 *   `editor.action.inlineSuggest.trigger`，让 provider 用更长的文本重新 resolve，
 *   视觉上 ghost 由短到长逐段生长（§4.2）；
 * - 会话按 `uri|version|offset|model|maxLines|mode` 缓存，同一位置的重复触发直接复用，
 *   不会重复打模型，也不会出现「整块突然铺满」。
 *
 * 边界判断（scheme / 语言 / 敏感文件 / 选区 / 注释字符串）全部收在
 * `completionCore.ts` 的纯函数里，便于单测。
 */

/** 单次请求最多带多少字符的前文 / 后文（服务端还会再按预算截断） */
const PREFIX_LIMIT = 60_000;
const SUFFIX_LIMIT = 20_000;
/** 文件超过这个大小就不带全文，只发切片，避免请求体过大 */
const FULL_TEXT_LIMIT = 400_000;
/** 最多保留多少个会话，超出按创建顺序淘汰 */
const MAX_SESSIONS = 8;
/** 增量重取间隔：太密会打断 ghost 的稳定渲染 */
const REFRESH_INTERVAL_MS = 150;
/** 一个会话最多刷新多久：用户 Esc 关闭后不至于被无限拉起 */
const REFRESH_WINDOW_MS = 4_000;
/** 偏好写回后端的合并窗口 */
const PREFS_PUSH_DEBOUNCE_MS = 400;

interface Session {
  key: string;
  uri: string;
  version: number;
  offset: number;
  mode: CompletionMode;
  /** 服务端最终用的通道（`fim` / `chat`），`done` 事件到达后才有值 */
  channel?: string;
  text: string;
  done: boolean;
  failed?: string;
  startedAt: number;
  refreshedAtEnd: boolean;
  abort: AbortController;
  waiters: Set<() => void>;
}

interface CompletionConfig {
  enabled: boolean;
  debounceMs: number;
  maxLines: number;
  model: string;
  mode: CompletionMode;
  skipComments: boolean;
  /** 用户追加的屏蔽路径（内置敏感文件规则始终生效） */
  blockedPatterns: string[];
}

interface AcceptedArgs {
  /** 服务端通道（`fim` / `chat`） */
  mode?: string;
  model?: string;
  language_id?: string;
  /** 交互模式（`insert` / `line`） */
  completion_mode?: string;
}

function sleep(ms: number, token: vscode.CancellationToken): Promise<boolean> {
  return new Promise<boolean>((resolve) => {
    if (token.isCancellationRequested) {
      resolve(false);
      return;
    }
    let subscription: vscode.Disposable | undefined;
    const timer = setTimeout(() => {
      subscription?.dispose();
      resolve(true);
    }, ms);
    subscription = token.onCancellationRequested(() => {
      clearTimeout(timer);
      resolve(false);
    });
  });
}

export class FluxCompletionService implements vscode.Disposable {
  private readonly sessions = new Map<string, Session>();
  private readonly disposables: vscode.Disposable[] = [];
  /** 每个文档最近一次「真正发请求」的版本与偏移，用于识别「只移光标没输入」 */
  private readonly lastRequest = new Map<string, { version: number; offset: number }>();
  private readonly status: vscode.StatusBarItem;
  private readonly output: vscode.OutputChannel;

  private provider?: vscode.Disposable;
  private refreshTimer?: NodeJS.Timeout;
  private prefsTimer?: NodeJS.Timeout;
  private resolvedModel = "";
  private selfChecking = false;
  private disposed = false;

  constructor(private readonly client: () => FluxClient | undefined) {
    this.output = vscode.window.createOutputChannel("Flux 补全");
    this.status = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Right, 90);
    this.status.command = "flux.completion.toggle";
    this.disposables.push(this.status, this.output);

    this.disposables.push(
      vscode.commands.registerCommand("flux.completion.toggle", () => this.toggle()),
      vscode.commands.registerCommand("flux.completion.selfCheck", () => this.selfCheck()),
      // 用户接受 ghost 时由 InlineCompletionItem.command 触发，只上报计数
      vscode.commands.registerCommand("flux.completion.accepted", (args?: AcceptedArgs) =>
        this.reportAccepted(args)
      ),
      vscode.workspace.onDidChangeConfiguration((event) => {
        if (!event.affectsConfiguration("flux.completion")) {
          return;
        }
        this.syncProvider();
        this.renderStatus();
        this.schedulePrefsPush();
      })
    );

    this.syncProvider();
    this.renderStatus();
    void this.hydrateFromServer();
  }

  dispose(): void {
    this.disposed = true;
    this.cancelAll();
    if (this.refreshTimer) {
      clearTimeout(this.refreshTimer);
      this.refreshTimer = undefined;
    }
    if (this.prefsTimer) {
      clearTimeout(this.prefsTimer);
      this.prefsTimer = undefined;
    }
    this.provider?.dispose();
    this.provider = undefined;
    for (const item of this.disposables) {
      item.dispose();
    }
    this.disposables.length = 0;
  }

  // --- provider 生命周期（§4.1：关闭时不注册） ---

  private syncProvider(): void {
    const enabled = this.config().enabled;
    if (enabled && !this.provider) {
      // 只注册真实文件：`git:` 等只读虚拟文档没有补全意义，还会把 diff 内容发出去
      this.provider = vscode.languages.registerInlineCompletionItemProvider(
        COMPLETION_SCHEMES.map((scheme) => ({ scheme })),
        {
          provideInlineCompletionItems: (document, position, context, token) =>
            this.provide(document, position, context, token),
        }
      );
      this.log(`补全已注册（scheme=${COMPLETION_SCHEMES.join(",")}）`);
    } else if (!enabled && this.provider) {
      this.provider.dispose();
      this.provider = undefined;
      this.cancelAll();
      this.log("补全已关闭，provider 已注销");
    }
  }

  // --- 核心：提供 ghost text ---

  private async provide(
    document: vscode.TextDocument,
    position: vscode.Position,
    context: vscode.InlineCompletionContext,
    token: vscode.CancellationToken
  ): Promise<vscode.InlineCompletionItem[] | undefined> {
    const config = this.config();
    if (this.disposed || !config.enabled || token.isCancellationRequested) {
      return undefined;
    }

    const uri = document.uri.toString();
    if (!isSupportedScheme(uri) || isBlockedLanguage(document.languageId)) {
      return undefined;
    }

    // 有选区时用户多半要改写选中内容，此时的补全既难对齐也容易误导
    const editor = vscode.window.activeTextEditor;
    if (editor && editor.document === document && !editor.selection.isEmpty) {
      return undefined;
    }

    const relative = this.relativePath(document);
    if (relative && isBlockedPath(relative, config.blockedPatterns)) {
      return undefined;
    }

    const offset = document.offsetAt(position);
    if (config.skipComments) {
      const linePrefix = document.lineAt(position.line).text.slice(0, position.character);
      if (insideCommentOrString(linePrefix, document.languageId)) {
        return undefined;
      }
    }

    if (context.triggerKind === vscode.InlineCompletionTriggerKind.Automatic) {
      // §4.3：光标刚移动、没有输入时不触发，避免抖动
      if (isCursorOnlyMove(this.lastRequest.get(uri), { version: document.version, offset })) {
        return undefined;
      }
      const ready = await sleep(config.debounceMs, token);
      if (!ready || token.isCancellationRequested) {
        return undefined;
      }
    }

    const client = this.client();
    if (!client) {
      return undefined;
    }

    const key = completionKey({
      uri,
      version: document.version,
      offset,
      model: config.model,
      maxLines: config.maxLines,
      mode: config.mode,
    });
    let session = this.sessions.get(key);
    if (!session) {
      this.lastRequest.set(uri, { version: document.version, offset });
      session = this.start(client, document, position, uri, offset, key, config);
    }

    return this.materialize(session, document, position, token);
  }

  private async materialize(
    session: Session,
    document: vscode.TextDocument,
    position: vscode.Position,
    token: vscode.CancellationToken
  ): Promise<vscode.InlineCompletionItem[] | undefined> {
    if (!session.text && !session.done && !session.failed) {
      await this.waitForText(session, token);
    }
    if (token.isCancellationRequested) {
      return undefined;
    }

    const text = sanitizeCompletion(session.text, session.mode);
    if (!text) {
      return undefined;
    }

    // 整行模式替换当前整行；插入模式只在光标处补
    const range =
      session.mode === "line"
        ? document.lineAt(position.line).range
        : new vscode.Range(position, position);
    // 用户接受时 VS Code 会执行这个 command：只上报计数，不携带代码
    const command: vscode.Command = {
      command: "flux.completion.accepted",
      title: "Flux 补全已接受",
      arguments: [
        {
          mode: session.channel,
          model: this.resolvedModel || this.config().model,
          language_id: document.languageId,
          completion_mode: session.mode,
        } satisfies AcceptedArgs,
      ],
    };
    return [new vscode.InlineCompletionItem(text, range, command)];
  }

  private waitForText(session: Session, token: vscode.CancellationToken): Promise<void> {
    if (session.text || session.done || session.failed) {
      return Promise.resolve();
    }
    return new Promise<void>((resolve) => {
      let subscription: vscode.Disposable | undefined;
      const finish = () => {
        subscription?.dispose();
        session.waiters.delete(finish);
        resolve();
      };
      subscription = token.onCancellationRequested(finish);
      session.waiters.add(finish);
    });
  }

  // --- 会话：后台流式请求 ---

  private start(
    client: FluxClient,
    document: vscode.TextDocument,
    position: vscode.Position,
    uri: string,
    offset: number,
    key: string,
    config: CompletionConfig
  ): Session {
    // 同一文件只要换了位置 / 版本，旧流就没有价值了：立刻 abort，
    // 否则连续敲字会在后台堆出多条并行流，既浪费额度又抢带宽。
    for (const staleKey of staleSessionKeys(this.sessions.values(), uri, key)) {
      const stale = this.sessions.get(staleKey);
      stale?.abort.abort();
      this.sessions.delete(staleKey);
    }

    const session: Session = {
      key,
      uri,
      version: document.version,
      offset,
      mode: config.mode,
      text: "",
      done: false,
      startedAt: Date.now(),
      refreshedAtEnd: false,
      abort: new AbortController(),
      waiters: new Set(),
    };
    this.sessions.set(key, session);
    this.prune(key);

    const fullText = document.getText();
    const request: FluxFimRequest = {
      ...this.slice(document, position, offset, config.mode),
      path: this.relativePath(document),
      language_id: document.languageId,
      caret: offset,
      max_lines: config.mode === "line" ? 1 : config.maxLines,
    };
    if (fullText.length <= FULL_TEXT_LIMIT) {
      // 带上全文，服务端才能按行号切出准确的 L0（并据此生成 cache_key）
      request.text = fullText;
    }
    if (config.model && config.model !== "follow-agent") {
      request.model = config.model;
    }
    const projectId = vscode.workspace.getConfiguration("flux").get<string>("projectId");
    if (projectId) {
      request.project_id = projectId;
    }

    this.log(
      `请求补全 ${request.path ?? document.uri.fsPath} #${offset} mode=${config.mode} ` +
        `model=${config.model} prefix=${request.prefix.length} suffix=${request.suffix.length}`
    );

    void client
      .streamFim(
        request,
        (event, data) => {
          if (event === "delta") {
            const piece = typeof data.text === "string" ? data.text : "";
            if (!piece) {
              return;
            }
            session.text += piece;
            this.notify(session);
            this.scheduleRefresh(session);
            return;
          }
          if (event === "done") {
            session.done = true;
            const model = typeof data.model === "string" ? data.model : "";
            if (model && model !== this.resolvedModel) {
              this.resolvedModel = model;
              this.renderStatus();
            }
            // 记住服务端实际用的通道，用户接受时按通道分桶统计
            if (typeof data.mode === "string" && data.mode) {
              session.channel = data.mode;
            }
            if (!session.text) {
              this.log("补全为空（模型没有给出可用内容）");
            }
            this.notify(session);
            this.refreshAtEnd(session);
            return;
          }
          if (event === "error") {
            const message = typeof data.message === "string" ? data.message : "补全失败";
            session.failed = message;
            this.log(`补全失败：${message}`);
            this.notify(session);
          }
        },
        session.abort.signal
      )
      .catch((err: unknown) => {
        session.failed = err instanceof Error ? err.message : String(err);
        this.log(`补全请求异常：${session.failed}`);
      })
      .finally(() => {
        session.done = true;
        this.notify(session);
      });

    return session;
  }

  /**
   * 按模式切 prefix / suffix。
   *
   * - 插入模式：光标前后切片，模型只写「光标之间的内容」；
   * - 整行模式：prefix 收窄到行首、suffix 从行尾开始，让模型重写整行
   *   （当前行已有的内容由 ghost 整行替换，不会出现半行拼接）。
   */
  private slice(
    document: vscode.TextDocument,
    position: vscode.Position,
    offset: number,
    mode: CompletionMode
  ): Pick<FluxFimRequest, "prefix" | "suffix"> {
    const fullText = document.getText();
    if (mode !== "line") {
      return {
        prefix: fullText.slice(Math.max(0, offset - PREFIX_LIMIT), offset),
        suffix: fullText.slice(offset, offset + SUFFIX_LIMIT),
      };
    }
    const line = document.lineAt(position.line);
    const lineStart = document.offsetAt(line.range.start);
    const lineEnd = document.offsetAt(line.range.end);
    return {
      prefix: fullText.slice(Math.max(0, lineStart - PREFIX_LIMIT), lineStart),
      suffix: fullText.slice(lineEnd, lineEnd + SUFFIX_LIMIT),
    };
  }

  private notify(session: Session): void {
    for (const waiter of [...session.waiters]) {
      waiter();
    }
    session.waiters.clear();
  }

  // --- 流式重取（§4.2） ---

  private scheduleRefresh(session: Session): void {
    if (this.disposed || this.refreshTimer) {
      return;
    }
    this.refreshTimer = setTimeout(() => {
      this.refreshTimer = undefined;
      if (this.isCurrent(session)) {
        void vscode.commands
          .executeCommand("editor.action.inlineSuggest.trigger")
          .then(undefined, () => undefined);
      }
    }, REFRESH_INTERVAL_MS);
  }

  private refreshAtEnd(session: Session): void {
    if (session.refreshedAtEnd || !session.text) {
      return;
    }
    session.refreshedAtEnd = true;
    this.scheduleRefresh(session);
  }

  /** 会话仍然对应活跃编辑器里的同一位置、同一文本版本时才值得重取。 */
  private isCurrent(session: Session): boolean {
    if (Date.now() - session.startedAt > REFRESH_WINDOW_MS) {
      return false;
    }
    const editor = vscode.window.activeTextEditor;
    if (!editor || !editor.selection.isEmpty) {
      return false;
    }
    const document = editor.document;
    return (
      document.uri.toString() === session.uri &&
      document.version === session.version &&
      document.offsetAt(editor.selection.active) === session.offset
    );
  }

  // --- 生命周期工具 ---

  private prune(keep: string): void {
    for (const item of [...this.sessions.values()]) {
      if (this.sessions.size <= MAX_SESSIONS) {
        return;
      }
      if (item.key === keep) {
        continue;
      }
      item.abort.abort();
      this.sessions.delete(item.key);
    }
  }

  private cancelAll(): void {
    for (const item of this.sessions.values()) {
      item.abort.abort();
      this.notify(item);
    }
    this.sessions.clear();
    this.lastRequest.clear();
  }

  // --- 配置 / 状态栏 / 偏好 ---

  private config(): CompletionConfig {
    const section = vscode.workspace.getConfiguration("flux.completion");
    const rawMode = (section.get<string>("mode", "insert") || "insert").trim().toLowerCase();
    const patterns = section.get<string[]>("blockedPatterns", []);
    return {
      enabled: section.get<boolean>("enabled", true),
      debounceMs: section.get<number>("debounceMs", 400),
      maxLines: section.get<number>("maxLines", 8),
      model: (section.get<string>("model", "follow-agent") || "follow-agent").trim(),
      mode: rawMode === "line" ? "line" : "insert",
      skipComments: section.get<boolean>("skipComments", true),
      blockedPatterns: Array.isArray(patterns) ? patterns.filter((item) => typeof item === "string") : [],
    };
  }

  private renderStatus(): void {
    const config = this.config();
    const label = this.resolvedModel && config.model === "follow-agent" ? this.resolvedModel : config.model;
    this.status.text = config.enabled ? "$(sparkle) Flux 补全" : "$(circle-slash) Flux 补全";
    this.status.tooltip = config.enabled
      ? `Flux 行内补全：${label}（${config.mode === "line" ? "整行" : "插入"}模式，点击关闭）`
      : "Flux 行内补全已关闭（点击开启）";
    this.status.backgroundColor = config.enabled ? undefined : new vscode.ThemeColor("statusBarItem.warningBackground");
    this.status.show();
  }

  private async toggle(): Promise<void> {
    const section = vscode.workspace.getConfiguration("flux.completion");
    const next = !section.get<boolean>("enabled", true);
    await section.update("enabled", next, vscode.ConfigurationTarget.Global);
    this.renderStatus();
    this.schedulePrefsPush();
    void vscode.window.setStatusBarMessage(next ? "Flux 补全已开启" : "Flux 补全已关闭", 2000);
  }

  /** 把本地配置同步到后端偏好，保证桌面端 / Web 端看到同一份开关与模型。 */
  private schedulePrefsPush(): void {
    if (this.prefsTimer) {
      clearTimeout(this.prefsTimer);
    }
    this.prefsTimer = setTimeout(() => {
      this.prefsTimer = undefined;
      void this.pushPrefs();
    }, PREFS_PUSH_DEBOUNCE_MS);
  }

  private async pushPrefs(): Promise<void> {
    const client = this.client();
    if (!client) {
      return;
    }
    const config = this.config();
    try {
      await client.putCompletionPrefs({
        enabled: config.enabled,
        model: config.model,
        debounce_ms: config.debounceMs,
        max_lines: config.maxLines,
      });
    } catch (err) {
      this.log(`偏好写回失败：${err instanceof Error ? err.message : String(err)}`);
    }
  }

  /** 启动时读一次后端偏好，用来展示实际生效的模型（不反向覆盖本地配置）。 */
  private async hydrateFromServer(): Promise<void> {
    const client = this.client();
    if (!client) {
      return;
    }
    try {
      const prefs: FluxCompletionPrefs = await client.completionPrefs();
      const resolved = prefs?.resolved as Record<string, unknown> | undefined;
      const model = typeof resolved?.model === "string" ? resolved.model : "";
      if (model) {
        this.resolvedModel = model;
        this.renderStatus();
      }
    } catch (err) {
      this.log(`读取补全偏好失败：${err instanceof Error ? err.message : String(err)}`);
    }
  }

  // --- 接受埋点（§8） ---

  private async reportAccepted(args?: AcceptedArgs): Promise<void> {
    const client = this.client();
    if (!client) {
      return;
    }
    try {
      await client.completionAccepted({
        accepted: true,
        mode: args?.mode,
        model: args?.model,
        language_id: args?.language_id,
        completion_mode: args?.completion_mode,
      });
    } catch (err) {
      // 埋点失败绝不能干扰用户：只写日志
      this.log(`接受埋点上报失败：${err instanceof Error ? err.message : String(err)}`);
    }
  }

  // --- 自检（§8：装进 IDE 之后确认 provider 真的在工作） ---

  private async selfCheck(): Promise<void> {
    if (this.selfChecking) {
      return;
    }
    this.selfChecking = true;
    const lines: string[] = [];
    const config = this.config();
    let failed = "";

    try {
      await vscode.window.withProgress(
        {
          location: vscode.ProgressLocation.Notification,
          title: "Flux 补全自检",
          cancellable: false,
        },
        async (progress) => {
          progress.report({ message: "检查 provider 注册状态" });
          lines.push(
            `provider: ${this.provider ? "已注册" : "未注册"}（enabled=${config.enabled}，` +
              `mode=${config.mode}，skipComments=${config.skipComments}）`
          );
          lines.push(
            `scheme: ${COMPLETION_SCHEMES.join(", ")}；追加屏蔽规则 ${config.blockedPatterns.length} 条`
          );

          const client = this.client();
          if (!client) {
            failed = "还没有连接到 Flux 后端";
            return;
          }

          progress.report({ message: "检查后端连通性" });
          try {
            await client.health();
            lines.push("health: 正常");
          } catch (err) {
            failed = `后端不可达：${err instanceof Error ? err.message : String(err)}`;
            return;
          }

          progress.report({ message: "发一条最小补全请求" });
          const started = Date.now();
          let firstTokenMs: number | null = null;
          let text = "";
          try {
            await client.streamFim(
              {
                prefix: "def add(a, b):\n    return ",
                suffix: "\n",
                path: "flux-selfcheck.py",
                language_id: "python",
                max_lines: 2,
              },
              (event, data) => {
                if (event === "delta") {
                  const piece = typeof data.text === "string" ? data.text : "";
                  if (piece && firstTokenMs === null) {
                    firstTokenMs = Date.now() - started;
                  }
                  text += piece;
                  return;
                }
                if (event === "error") {
                  failed = typeof data.message === "string" ? data.message : "补全返回错误事件";
                }
              },
              new AbortController().signal
            );
          } catch (err) {
            failed = `补全请求失败：${err instanceof Error ? err.message : String(err)}`;
          }
          const elapsed = Date.now() - started;
          if (!failed) {
            lines.push(
              `fim: 首字 ${firstTokenMs ?? "-"}ms / 总 ${elapsed}ms / ${text.length} 字符` +
                `（${sanitizeCompletion(text, "insert").slice(0, 60).replace(/\n/g, "\\n") || "空"}）`
            );
          }

          progress.report({ message: "读取埋点摘要" });
          try {
            const stats = await client.completionStats();
            const totals = stats?.totals;
            if (totals) {
              lines.push(
                `stats: 请求 ${totals.requests}，非空 ${totals.delivered}，空 ${totals.empty}，` +
                  `失败 ${totals.failures}，中断 ${totals.aborts}，接受 ${totals.accepted}` +
                  `（接受率 ${(totals.acceptance_rate * 100).toFixed(1)}%，` +
                  `首字 ${totals.avg_first_token_ms ?? "-"}ms）`
              );
            }
          } catch (err) {
            lines.push(`stats: 读取失败（${err instanceof Error ? err.message : String(err)}）`);
          }
        }
      );
    } finally {
      this.selfChecking = false;
      const stamp = new Date().toISOString();
      this.output.appendLine(`[${stamp}] ===== 自检 =====`);
      for (const line of lines) {
        this.output.appendLine(`[${stamp}] ${line}`);
      }
      if (failed) {
        this.output.appendLine(`[${stamp}] 结论：失败 - ${failed}`);
      }
      this.output.show(true);
    }

    if (failed) {
      void vscode.window.showWarningMessage(
        `Flux 补全自检未通过：${failed}（详情见「Flux 补全」输出面板）`
      );
      return;
    }
    void vscode.window.showInformationMessage("Flux 补全自检通过（详情见「Flux 补全」输出面板）");
  }

  private relativePath(document: vscode.TextDocument): string | undefined {
    if (document.uri.scheme === "untitled") {
      return undefined;
    }
    const relative = vscode.workspace.asRelativePath(document.uri, false);
    return relative && relative !== document.uri.fsPath ? relative : relative || undefined;
  }

  private log(message: string): void {
    this.output.appendLine(`[${new Date().toISOString()}] ${message}`);
  }
}

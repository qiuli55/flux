import * as vscode from "vscode";
import { FluxClient, FluxMessage, FluxTask } from "./api";

export class TaskPanel {
  private static current: TaskPanel | undefined;
  private readonly disposables: vscode.Disposable[] = [];
  private abort: AbortController | undefined;
  /** 已消费到的事件序号：重连时从这之后续接，避免回放历史造成重复刷新。 */
  private lastSeq = 0;
  private refreshTimer: ReturnType<typeof setTimeout> | undefined;
  /** 终态追赶剩余次数：结果消息晚于终态事件落库时，靠它继续对齐。 */
  private retries = 0;

  static show(context: vscode.ExtensionContext, client: FluxClient, taskId: string): void {
    if (TaskPanel.current) {
      TaskPanel.current.taskId = taskId;
      TaskPanel.current.lastSeq = 0;
      TaskPanel.current.retries = 0;
      TaskPanel.current.panel.reveal(vscode.ViewColumn.One);
      TaskPanel.current.clearLogs();
      TaskPanel.current.reload();
      return;
    }
    const panel = vscode.window.createWebviewPanel(
      "fluxTask",
      `Flux 任务 ${taskId.slice(0, 8)}`,
      vscode.ViewColumn.One,
      { enableScripts: true, retainContextWhenHidden: true, localResourceRoots: [context.extensionUri] }
    );
    panel.iconPath = vscode.Uri.joinPath(context.extensionUri, "media", "flux.svg");
    TaskPanel.current = new TaskPanel(panel, client, taskId, context);
  }

  private constructor(
    private readonly panel: vscode.WebviewPanel,
    private readonly client: FluxClient,
    public taskId: string,
    private readonly context: vscode.ExtensionContext
  ) {
    panel.webview.html = this.html();
    panel.onDidDispose(() => this.dispose(), null, this.disposables);
    panel.webview.onDidReceiveMessage((msg) => this.onMessage(msg), null, this.disposables);
  }

  private async onMessage(msg: { type: string; text?: string; href?: string; resume?: boolean }): Promise<void> {
    switch (msg.type) {
      case "ready":
        await this.reload();
        break;
      case "refresh":
        this.clearLogs();
        await this.reload();
        break;
      case "start":
        await this.run(() => this.client.startTask(this.taskId));
        break;
      case "cancel":
        await this.run(() => this.client.cancelTask(this.taskId));
        break;
      case "send":
        if (msg.text?.trim()) {
          this.post({ type: "sent" });
          const text = msg.text.trim();
          // 终态对话不能再追加消息（后端 409）：改为续跑，把这条消息当作新一轮指令
          await this.run(() =>
            msg.resume ? this.client.continueTask(this.taskId, text) : this.client.sendMessage(this.taskId, text)
          );
        }
        break;
      case "open":
        if (msg.href && /^https?:\/\//i.test(msg.href)) {
          await vscode.env.openExternal(vscode.Uri.parse(msg.href));
        }
        break;
      case "reveal":
        this.panel.reveal(vscode.ViewColumn.One);
        break;
    }
  }

  private async run(action: () => Promise<unknown>): Promise<void> {
    this.post({ type: "busy", value: true });
    try {
      await action();
    } catch (err) {
      this.post({ type: "error", text: (err as Error).message });
    } finally {
      this.post({ type: "busy", value: false });
    }
    await this.reload();
  }

  private clearLogs(): void {
    this.post({ type: "clearEvents" });
  }

  async reload(): Promise<void> {
    this.retries = 0;
    await this.load();
    this.subscribe();
  }

  /**
   * 只拉数据（任务 + 对话 + 项目名），不动事件流；收到运行事件时用它静默刷新。
   * 返回「是否已经对齐终态」：任务已终态且最后一条是助手回复，就不必再重试。
   */
  private async load(): Promise<boolean> {
    let task: FluxTask | undefined;
    let messages: FluxMessage[] = [];
    try {
      task = await this.client.task(this.taskId);
      messages = await this.client.messages(this.taskId);
    } catch (err) {
      this.post({ type: "error", text: (err as Error).message });
    }
    if (task?.title || task?.description) {
      this.panel.title = `Flux · ${truncate(task.title || task.description || "", 40)}`;
    }
    let projectName: string | undefined;
    if (task?.project_id) {
      const projects = await this.client.projects().catch(() => []);
      projectName = projects.find((project) => project.id === task!.project_id)?.name;
    }
    this.post({ type: "task", task: task ?? { id: this.taskId }, projectName });
    this.post({ type: "messages", items: messages });
    const status = String(task?.status ?? "").toLowerCase();
    const tail = messages[messages.length - 1];
    return TERMINAL_STATUSES.has(status) && Boolean(tail) && String(tail.role).toLowerCase() === "assistant";
  }

  private subscribe(): void {
    this.abort?.abort();
    this.abort = new AbortController();
    const controller = this.abort;
    this.client
      .streamTask(
        this.taskId,
        (payload) => this.onEvent(payload),
        controller.signal,
        this.lastSeq
      )
      .catch((err) => {
        if (!controller.signal.aborted) {
          this.post({ type: "error", text: `事件流断开：${(err as Error).message}` });
        }
      });
  }

  /**
   * 事件流单条负载（`{seq, type, data}`）：转发给 webview 展示，并在有意义的节点静默刷新。
   * 任务状态与对话正文都以后端落库为准，所以收到运行起止/助手回复后补一次数据，
   * 气泡、状态 pill、输入框提示就能一起跟上，不用用户手动点「刷新」。
   */
  private onEvent(payload: string): void {
    let type = "";
    let status = "";
    try {
      const event = JSON.parse(payload) as { seq?: number; type?: string; data?: { status?: string } };
      if (typeof event.seq === "number" && event.seq > this.lastSeq) {
        this.lastSeq = event.seq;
      }
      type = String(event.type ?? "");
      status = String(event.data?.status ?? "").toLowerCase();
    } catch {
      // 非 JSON 负载（理论上不会出现）直接原样展示
    }
    const terminal = TERMINAL_STATUSES.has(status);
    const tone = type === "assistant_message" || terminal
      ? "ok"
      : type === "assistant_error"
        ? "err"
        : undefined;
    this.post({ type: "event", text: truncate(payload), tone });
    if (REFRESH_TYPES.has(type) || terminal) {
      // 终态事件可能先于结果消息落库送达，多试几次直到对话追上状态
      this.scheduleRefresh(terminal ? REFRESH_RETRIES : 0);
    }
  }

  private scheduleRefresh(attempts = 0): void {
    this.retries = Math.max(this.retries, attempts);
    if (this.refreshTimer) {
      return;
    }
    this.refreshTimer = setTimeout(() => {
      this.refreshTimer = undefined;
      void this.refreshTick();
    }, 500);
  }

  private async refreshTick(): Promise<void> {
    const settled = await this.load();
    if (settled || this.retries <= 0) {
      this.retries = 0;
      return;
    }
    this.retries -= 1;
    this.scheduleRefresh(this.retries);
  }

  private post(message: unknown): void {
    void this.panel.webview.postMessage(message);
  }

  private dispose(): void {
    TaskPanel.current = undefined;
    this.abort?.abort();
    if (this.refreshTimer) {
      clearTimeout(this.refreshTimer);
      this.refreshTimer = undefined;
    }
    while (this.disposables.length) {
      this.disposables.pop()?.dispose();
    }
    this.panel.dispose();
  }

  private html(): string {
    const nonce = randomNonce();
    const csp = ["default-src 'none'", "style-src 'unsafe-inline'", `script-src 'nonce-${nonce}'`].join("; ");
    return `<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8"/>
<meta http-equiv="Content-Security-Policy" content="${csp}"/>
<style>
  :root {
    --ink: var(--vscode-editor-background, #0b0e11);
    --ink-raised: var(--vscode-sideBar-background, #101418);
    --ink-sunken: #090b0d;
    --hairline: #1b2024;
    --hairline-strong: #2b3238;
    --text: var(--vscode-foreground, #e7ecea);
    --text-dim: #9ca6a4;
    --text-mute: #6c7674;
    --accent: var(--vscode-flux-accent, #3df5a8);
    --accent-ink: #04231f;
    --info: var(--vscode-flux-info, #7fb6e0);
    --warn: var(--vscode-flux-warn, #e8b25e);
    --danger: var(--vscode-flux-danger, #ff7a6e);
    --mono: var(--vscode-editor-font-family, "JetBrains Mono", Menlo, Consolas, monospace);
    --sans: var(--vscode-font-family, -apple-system, "Segoe UI", "Noto Sans CJK SC", sans-serif);
  }
  * { box-sizing: border-box; }
  html, body { height: 100%; }
  body {
    margin: 0; background: var(--ink); color: var(--text);
    font-family: var(--sans); font-size: 13px; line-height: 1.6;
    display: flex; flex-direction: column; height: 100vh; overflow: hidden;
    -webkit-font-smoothing: antialiased;
  }
  header {
    flex: 0 0 auto; padding: 14px 20px 12px; border-bottom: 1px solid var(--hairline);
    background: var(--ink);
  }
  .head-row { display: flex; align-items: center; gap: 10px; }
  h1 { font-size: 15px; font-weight: 600; margin: 0; letter-spacing: .01em; flex: 1 1 auto;
       overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .pill {
    flex: 0 0 auto; font-family: var(--mono); font-size: 11px; letter-spacing: .04em;
    padding: 2px 9px; border-radius: 999px; border: 1px solid var(--hairline-strong); color: var(--text-dim);
  }
  .pill.running { color: var(--accent); border-color: rgba(61,245,168,.45); background: rgba(61,245,168,.08); }
  .pill.completed { color: var(--accent); border-color: rgba(61,245,168,.35); }
  .pill.failed { color: var(--danger); border-color: rgba(255,122,110,.4); background: rgba(255,122,110,.07); }
  .pill.pending { color: var(--info); border-color: rgba(127,182,224,.4); }
  .pill.cancelled { color: var(--text-mute); }
  .meta { margin-top: 6px; font-family: var(--mono); font-size: 11px; color: var(--text-mute);
          display: flex; gap: 14px; flex-wrap: wrap; }
  .meta b { color: var(--text-dim); font-weight: 500; }
  .toolbar { display: flex; gap: 8px; margin-top: 12px; align-items: center; }
  button {
    font-family: var(--sans); font-size: 12px; padding: 5px 12px; border-radius: 4px; cursor: pointer;
    border: 1px solid var(--hairline-strong); background: transparent; color: var(--text-dim);
    transition: color .12s cubic-bezier(.22,1,.36,1), border-color .12s, background .12s;
  }
  button:hover { color: var(--text); border-color: #3b454b; }
  button.primary { background: var(--accent); color: var(--accent-ink); border-color: transparent; font-weight: 600; }
  button.primary:hover { background: #22c98a; color: var(--accent-ink); }
  button:disabled { opacity: .45; cursor: default; }
  .spacer { flex: 1; }
  .hint { font-family: var(--mono); font-size: 11px; color: var(--text-mute); }
  .banner {
    display: none; margin-top: 10px; padding: 8px 12px; border-radius: 4px; font-size: 12px;
    border: 1px solid rgba(255,122,110,.4); background: rgba(255,122,110,.08); color: var(--danger);
    align-items: flex-start; gap: 10px;
  }
  .banner.show { display: flex; }
  .banner span { flex: 1; word-break: break-word; }
  .banner button { border: none; color: var(--danger); padding: 0 4px; font-size: 14px; line-height: 1; }
  main {
    flex: 1 1 auto; min-height: 0; display: grid; grid-template-columns: minmax(0, 1.55fr) minmax(0, 1fr);
    gap: 1px; background: var(--hairline);
  }
  section { background: var(--ink); display: flex; flex-direction: column; min-height: 0; min-width: 0; }
  .section-head {
    flex: 0 0 auto; display: flex; align-items: center; gap: 8px; padding: 9px 18px;
    border-bottom: 1px solid var(--hairline); font-family: var(--mono); font-size: 11px;
    letter-spacing: .08em; text-transform: uppercase; color: var(--text-mute);
  }
  .section-head .dot { width: 5px; height: 5px; border-radius: 50%; background: var(--accent); }
  .section-head .count { margin-left: auto; color: var(--text-mute); letter-spacing: 0; text-transform: none; }
  #thread { flex: 1 1 auto; overflow-y: auto; padding: 18px 20px 24px; scroll-behavior: smooth; }
  #thread::-webkit-scrollbar, #events::-webkit-scrollbar { width: 10px; }
  #thread::-webkit-scrollbar-thumb, #events::-webkit-scrollbar-thumb {
    background: #2b323880; border-radius: 10px; border: 3px solid transparent; background-clip: content-box;
  }
  .empty { color: var(--text-mute); font-size: 12px; border: 1px dashed var(--hairline-strong);
           border-radius: 6px; padding: 22px; text-align: center; line-height: 1.8; }
  .msg { margin-bottom: 20px; max-width: 760px; }
  .msg .role {
    font-family: var(--mono); font-size: 10.5px; letter-spacing: .1em; text-transform: uppercase;
    color: var(--text-mute); margin-bottom: 5px; display: flex; align-items: center; gap: 7px;
  }
  .msg .role::before { content: ""; width: 4px; height: 4px; border-radius: 50%; background: currentColor; }
  .msg.user .role { color: var(--info); }
  .msg.assistant .role { color: var(--accent); }
  .msg.system .role, .msg.tool .role { color: var(--warn); }
  .msg .time { margin-left: auto; color: var(--text-mute); letter-spacing: 0; text-transform: none; }
  .bubble {
    background: var(--ink-raised); border: 1px solid var(--hairline); border-radius: 6px;
    padding: 10px 14px; word-break: break-word; overflow-wrap: anywhere;
  }
  .msg.user .bubble { background: rgba(127,182,224,.06); border-color: rgba(127,182,224,.22); }
  .msg.assistant .bubble { border-left: 2px solid var(--accent); }
  .bubble p { margin: 0 0 8px; }
  .bubble p:last-child { margin-bottom: 0; }
  .bubble h1, .bubble h2, .bubble h3 { font-size: 13px; margin: 12px 0 6px; color: var(--text); }
  .bubble h1:first-child, .bubble h2:first-child, .bubble h3:first-child { margin-top: 0; }
  .bubble ul { margin: 4px 0 8px; padding-left: 18px; }
  .bubble li { margin: 2px 0; }
  .bubble code {
    font-family: var(--mono); font-size: 11.5px; color: var(--warn);
    background: rgba(232,178,94,.1); border-radius: 3px; padding: 1px 5px;
  }
  .bubble pre {
    background: var(--ink-sunken); border: 1px solid var(--hairline); border-radius: 4px;
    padding: 10px 12px; overflow-x: auto; margin: 8px 0;
  }
  .bubble pre code { background: none; padding: 0; color: #d7e6ff; font-size: 11.5px; line-height: 1.55; }
  .bubble blockquote {
    margin: 8px 0; padding: 2px 12px; border-left: 2px solid var(--hairline-strong); color: var(--text-dim);
  }
  .bubble a { color: var(--info); text-decoration: none; }
  .bubble a:hover { text-decoration: underline; }
  .thinking { display: none; align-items: center; gap: 8px; color: var(--accent); font-size: 12px;
              font-family: var(--mono); margin-bottom: 16px; }
  .thinking.show { display: flex; }
  .thinking i { width: 5px; height: 5px; border-radius: 50%; background: var(--accent); animation: pulse 1.1s infinite; }
  .thinking i:nth-child(2) { animation-delay: .16s; }
  .thinking i:nth-child(3) { animation-delay: .32s; }
  @keyframes pulse { 0%, 100% { opacity: .2; transform: translateY(0); } 50% { opacity: 1; transform: translateY(-2px); } }
  #events {
    flex: 1 1 auto; overflow-y: auto; padding: 12px 16px; font-family: var(--mono); font-size: 11.5px;
    line-height: 1.75; color: var(--text-dim); white-space: pre-wrap; word-break: break-word;
    background: var(--ink-sunken);
  }
  #events .ln { display: block; padding: 1px 0; border-bottom: 1px solid rgba(27,32,36,.6); }
  #events .ln:last-child { border-bottom: none; }
  #events .ln.err { color: var(--danger); }
  #events .ln.ok { color: var(--accent); }
  footer {
    flex: 0 0 auto; border-top: 1px solid var(--hairline); background: var(--ink-raised);
    padding: 12px 20px 14px; display: flex; flex-direction: column; gap: 8px;
  }
  .composer { display: flex; gap: 10px; align-items: flex-end; }
  textarea {
    flex: 1; resize: none; min-height: 44px; max-height: 160px; padding: 10px 12px;
    font-family: var(--sans); font-size: 13px; line-height: 1.55; border-radius: 6px;
    background: var(--ink-sunken); color: var(--text); border: 1px solid var(--hairline-strong); outline: none;
    transition: border-color .14s;
  }
  textarea::placeholder { color: var(--text-mute); }
  textarea:focus { border-color: rgba(61,245,168,.55); }
  .foot-row { display: flex; align-items: center; gap: 10px; font-size: 11px; color: var(--text-mute); font-family: var(--mono); }
  .foot-row .spacer { flex: 1; }
  @media (max-width: 880px) {
    main { grid-template-columns: minmax(0, 1fr); grid-template-rows: minmax(0, 1.4fr) minmax(0, 1fr); }
  }
</style>
</head>
<body>
<header>
  <div class="head-row">
    <h1 id="title">Flux 任务</h1>
    <span class="pill" id="pill">加载中</span>
  </div>
  <div class="meta">
    <span><b>ID</b> <span id="taskId">—</span></span>
    <span id="projectWrap" hidden><b>项目</b> <span id="project">—</span></span>
    <span id="agentWrap" hidden><b>Agent</b> <span id="agent">—</span></span>
  </div>
  <div class="toolbar">
    <button id="start" class="primary">开始</button>
    <button id="cancel">取消</button>
    <button id="refresh">刷新</button>
    <span class="spacer"></span>
    <span class="hint">Ctrl / Cmd + Enter 发送</span>
  </div>
  <div class="banner" id="banner"><span id="bannerText"></span><button id="bannerClose" title="关闭">×</button></div>
</header>
<main>
  <section>
    <div class="section-head"><span class="dot"></span><span>对话</span><span class="count" id="msgCount"></span></div>
    <div id="thread"></div>
  </section>
  <section>
    <div class="section-head"><span class="dot"></span><span>事件流</span><span class="count" id="eventCount"></span></div>
    <div id="events"><div class="empty" id="eventsEmpty">暂无事件。<br/>任务运行时的日志会实时出现在这里。</div></div>
  </section>
</main>
<footer>
  <div class="composer">
    <textarea id="input" rows="1" placeholder="给这个任务发消息，或补充上下文…"></textarea>
    <button id="send" class="primary">发送</button>
  </div>
  <div class="foot-row">
    <span id="conn">事件流连接中…</span>
    <span class="spacer"></span>
    <span id="updated"></span>
  </div>
</footer>
<script nonce="${nonce}">
  const vscode = acquireVsCodeApi();
  const $ = (id) => document.getElementById(id);
  const threadEl = $('thread');
  const eventsEl = $('events');
  const inputEl = $('input');
  const sendBtn = $('send');
  const pillEl = $('pill');
  const titleEl = $('title');
  const bannerEl = $('banner');
  const bannerTextEl = $('bannerText');
  let status = '';
  let busy = false;
  let eventCount = 0;

  const STATUS_LABEL = {
    running: '进行中', pending: '排队中', queued: '排队中', planning: '规划中',
    waiting: '等待确认', completed: '已完成', done: '已完成', failed: '失败', error: '出错',
    cancelled: '已取消', canceled: '已取消', paused: '已暂停'
  };
  const ROLE_LABEL = {
    user: '用户', assistant: 'Flux Agent', agent: 'Flux Agent', system: '系统', tool: '工具调用'
  };

  function esc(value) {
    return String(value == null ? '' : value)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;');
  }

  function inline(value) {
    return value
      .replace(/\`([^\`]+)\`/g, '<code>$1</code>')
      .replace(/\\*\\*([^*]+)\\*\\*/g, '<strong>$1</strong>')
      .replace(/\\[([^\\]]+)\\]\\((https?:\\/\\/[^\\s)]+)\\)/g, '<a href="#" data-href="$2">$1</a>');
  }

  function renderMarkdown(raw) {
    const lines = String(raw == null ? '' : raw).replace(/\\r\\n/g, '\\n').split('\\n');
    let html = '';
    let code = null;
    let list = false;
    const closeList = () => { if (list) { html += '</ul>'; list = false; } };
    for (const line of lines) {
      if (code !== null) {
        if (/^\\s*\`\`\`/.test(line)) {
          html += '<pre><code>' + esc(code.join('\\n')) + '</code></pre>';
          code = null;
        } else {
          code.push(line);
        }
        continue;
      }
      const fence = line.match(/^\\s*\`\`\`(.*)$/);
      if (fence) { closeList(); code = []; continue; }
      if (!line.trim()) { closeList(); continue; }
      const heading = line.match(/^(#{1,4})\\s+(.*)$/);
      if (heading) {
        closeList();
        html += '<h3>' + inline(esc(heading[2])) + '</h3>';
        continue;
      }
      if (/^\\s*[-*+]\\s+/.test(line)) {
        if (!list) { html += '<ul>'; list = true; }
        html += '<li>' + inline(esc(line.replace(/^\\s*[-*+]\\s+/, ''))) + '</li>';
        continue;
      }
      if (/^\\s*>\\s?/.test(line)) {
        closeList();
        html += '<blockquote>' + inline(esc(line.replace(/^\\s*>\\s?/, ''))) + '</blockquote>';
        continue;
      }
      closeList();
      html += '<p>' + inline(esc(line)) + '</p>';
    }
    if (code !== null) { html += '<pre><code>' + esc(code.join('\\n')) + '</code></pre>'; }
    closeList();
    return html;
  }

  function shortId(value) {
    const text = value ? String(value) : '';
    return text.length > 12 ? text.slice(0, 8) : text;
  }

  function timeLabel(value) {
    if (!value) { return ''; }
    const date = new Date(value);
    if (isNaN(date.getTime())) { return ''; }
    const pad = (n) => (n < 10 ? '0' + n : '' + n);
    const hm = pad(date.getHours()) + ':' + pad(date.getMinutes());
    const now = new Date();
    const sameDay = date.getFullYear() === now.getFullYear()
      && date.getMonth() === now.getMonth() && date.getDate() === now.getDate();
    return sameDay ? hm : (date.getMonth() + 1) + '/' + date.getDate() + ' ' + hm;
  }

  function renderMessages(items) {
    if (!items || !items.length) {
      threadEl.innerHTML = '<div class="empty">还没有对话。<br/>点「开始」让 Agent 动手，或在下方补充需求。</div>';
      $('msgCount').textContent = '0';
      return;
    }
    const nearBottom = threadEl.scrollHeight - threadEl.scrollTop - threadEl.clientHeight < 80;
    let html = '';
    for (const m of items) {
      const role = String(m.role || 'system').toLowerCase();
      const cls = ROLE_LABEL[role] ? role : 'system';
      html += '<div class="msg ' + cls + '">'
        + '<div class="role">' + esc(ROLE_LABEL[role] || role)
        + '<span class="time">' + esc(timeLabel(m.created_at)) + '</span></div>'
        + '<div class="bubble">' + renderMarkdown(m.content || '') + '</div>'
        + '</div>';
    }
    html += '<div class="thinking' + (status === 'running' ? ' show' : '') + '" id="thinking"><i></i><i></i><i></i><span>Agent 正在处理…</span></div>';
    threadEl.innerHTML = html;
    $('msgCount').textContent = String(items.length);
    if (nearBottom) { scrollThread(); }
  }

  function scrollThread() {
    threadEl.scrollTop = threadEl.scrollHeight;
  }

  const TERMINAL = new Set(['completed', 'done', 'failed', 'error', 'cancelled', 'canceled']);

  function setStatus(value) {
    status = String(value || '').toLowerCase();
    const label = STATUS_LABEL[status] || value || '未知';
    pillEl.textContent = label;
    pillEl.className = 'pill ' + status;
    $('start').disabled = status === 'running' || TERMINAL.has(status);
    $('start').title = TERMINAL.has(status) ? '该任务已结束，无法再次开始' : '';
    $('cancel').disabled = !(status === 'running' || status === 'pending' || status === 'queued' || status === 'planning');
    const ended = TERMINAL.has(status);
    inputEl.placeholder = ended ? '任务已结束，发送会续跑这个对话…' : '给这个任务发消息，或补充上下文…';
    sendBtn.title = ended ? '发送后会把这条消息当作新一轮指令，续跑同一个对话' : '';
    const thinking = document.getElementById('thinking');
    if (thinking) { thinking.classList.toggle('show', status === 'running'); }
  }

  function showBanner(text) {
    bannerTextEl.textContent = text;
    bannerEl.classList.add('show');
  }

  function hideBanner() { bannerEl.classList.remove('show'); }

  function appendEvent(text, kind) {
    document.getElementById('eventsEmpty')?.remove();
    const line = document.createElement('span');
    line.className = 'ln' + (kind ? ' ' + kind : '');
    line.textContent = text;
    eventsEl.appendChild(line);
    while (eventsEl.childNodes.length > 600) { eventsEl.removeChild(eventsEl.firstChild); }
    eventCount += 1;
    $('eventCount').textContent = String(eventCount);
    eventsEl.scrollTop = eventsEl.scrollHeight;
  }

  window.addEventListener('message', (ev) => {
    const msg = ev.data || {};
    if (msg.type === 'task') {
      const t = msg.task || {};
      titleEl.textContent = t.title || (t.description ? String(t.description).slice(0, 60) : 'Flux 任务');
      $('taskId').textContent = shortId(t.id);
      $('taskId').title = t.id || '';
      if (t.project_id) { $('projectWrap').hidden = false; $('project').textContent = msg.projectName || shortId(t.project_id); }
      if (t.agent_id) { $('agentWrap').hidden = false; $('agent').textContent = shortId(t.agent_id); $('agent').title = t.agent_id; }
      setStatus(t.status);
      $('updated').textContent = t.updated_at ? '更新于 ' + timeLabel(t.updated_at) : '';
      $('conn').textContent = '事件流已连接';
    } else if (msg.type === 'messages') {
      renderMessages(msg.items);
    } else if (msg.type === 'event') {
      appendEvent(msg.text, msg.tone);
    } else if (msg.type === 'clearEvents') {
      eventsEl.innerHTML = '<div class="empty" id="eventsEmpty">暂无事件。<br/>任务运行时的日志会实时出现在这里。</div>';
      eventCount = 0;
      $('eventCount').textContent = '0';
    } else if (msg.type === 'error') {
      appendEvent(String(msg.text || ''), 'err');
      showBanner(String(msg.text || '发生错误'));
    } else if (msg.type === 'busy') {
      busy = !!msg.value;
      sendBtn.disabled = busy;
      $('conn').textContent = busy ? '正在提交…' : '事件流已连接';
    }
  });

  threadEl.addEventListener('click', (ev) => {
    const link = ev.target.closest('a[data-href]');
    if (link) {
      ev.preventDefault();
      vscode.postMessage({ type: 'open', href: link.getAttribute('data-href') });
    }
  });

  function submit() {
    const text = inputEl.value.trim();
    if (!text || busy) { return; }
    vscode.postMessage({ type: 'send', text: text, resume: TERMINAL.has(status) });
    inputEl.value = '';
    inputEl.style.height = 'auto';
    hideBanner();
  }

  $('start').onclick = () => { hideBanner(); vscode.postMessage({ type: 'start' }); };
  $('cancel').onclick = () => vscode.postMessage({ type: 'cancel' });
  $('refresh').onclick = () => { hideBanner(); vscode.postMessage({ type: 'refresh' }); };
  $('bannerClose').onclick = hideBanner;
  sendBtn.onclick = submit;
  inputEl.addEventListener('input', () => {
    inputEl.style.height = 'auto';
    inputEl.style.height = Math.min(inputEl.scrollHeight, 160) + 'px';
  });
  inputEl.addEventListener('keydown', (ev) => {
    if ((ev.metaKey || ev.ctrlKey) && ev.key === 'Enter') {
      ev.preventDefault();
      submit();
    }
  });
  vscode.postMessage({ type: 'ready' });
</script>
</body>
</html>`;
  }
}

function truncate(text: string, max = 400): string {
  return text.length > max ? `${text.slice(0, max)}…` : text;
}

/** 与后端 `_FINAL_STATUSES` 对齐：这些状态不会再转回运行中。 */
const TERMINAL_STATUSES = new Set(["completed", "done", "failed", "error", "cancelled", "canceled"]);

/** 收到这些流事件时补一次数据（状态/对话可能已变化），无需重试。 */
const REFRESH_TYPES = new Set([
  "run_started",
  "run_status",
  "user_message",
  "assistant_message",
  "assistant_error",
]);

/** 终态事件的追赶次数：结果消息可能比事件晚落库。 */
const REFRESH_RETRIES = 8;

function randomNonce(): string {
  const chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
  let out = "";
  for (let i = 0; i < 24; i++) {
    out += chars.charAt(Math.floor(Math.random() * chars.length));
  }
  return out;
}

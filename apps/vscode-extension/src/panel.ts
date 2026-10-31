import * as vscode from "vscode";
import { FluxClient, FluxMessage, FluxTask } from "./api";

export class TaskPanel {
  private static current: TaskPanel | undefined;
  private readonly disposables: vscode.Disposable[] = [];
  private abort: AbortController | undefined;

  static show(context: vscode.ExtensionContext, client: FluxClient, taskId: string): void {
    if (TaskPanel.current) {
      TaskPanel.current.taskId = taskId;
      TaskPanel.current.panel.reveal(vscode.ViewColumn.One);
      TaskPanel.current.reload();
      return;
    }
    const panel = vscode.window.createWebviewPanel(
      "fluxTask",
      `Flux 任务 ${taskId.slice(0, 8)}`,
      vscode.ViewColumn.One,
      { enableScripts: true, retainContextWhenHidden: true, localResourceRoots: [context.extensionUri] }
    );
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

  private async onMessage(msg: { type: string; text?: string }): Promise<void> {
    switch (msg.type) {
      case "ready":
        await this.reload();
        break;
      case "refresh":
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
          await this.run(() => this.client.sendMessage(this.taskId, msg.text!.trim()));
        }
        break;
    }
  }

  private async run(action: () => Promise<unknown>): Promise<void> {
    try {
      await action();
    } catch (err) {
      this.post({ type: "error", text: (err as Error).message });
    }
    await this.reload();
  }

  async reload(): Promise<void> {
    let task: FluxTask | undefined;
    let messages: FluxMessage[] = [];
    try {
      task = await this.client.task(this.taskId);
      messages = await this.client.messages(this.taskId);
    } catch (err) {
      this.post({ type: "error", text: (err as Error).message });
    }
    this.post({ type: "task", task: task ?? { id: this.taskId } });
    this.post({ type: "messages", items: messages });
    this.subscribe();
  }

  private subscribe(): void {
    this.abort?.abort();
    this.abort = new AbortController();
    const controller = this.abort;
    this.client
      .streamTask(
        this.taskId,
        (payload) => this.post({ type: "event", text: truncate(payload) }),
        controller.signal
      )
      .catch((err) => {
        if (!controller.signal.aborted) {
          this.post({ type: "error", text: `事件流断开：${(err as Error).message}` });
        }
      });
  }

  private post(message: unknown): void {
    void this.panel.webview.postMessage(message);
  }

  private dispose(): void {
    TaskPanel.current = undefined;
    this.abort?.abort();
    while (this.disposables.length) {
      this.disposables.pop()?.dispose();
    }
    this.panel.dispose();
  }

  private html(): string {
    const nonce = randomNonce();
    const csp = [
      "default-src 'none'",
      "style-src 'unsafe-inline'",
      `script-src 'nonce-${nonce}'`,
    ].join("; ");
    return `<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8"/>
<meta http-equiv="Content-Security-Policy" content="${csp}"/>
<style>
  body { font-family: var(--vscode-font-family); color: var(--vscode-foreground); padding: 0; margin: 0; }
  header { position: sticky; top: 0; background: var(--vscode-sideBar-background); border-bottom: 1px solid var(--vscode-panel-border); padding: 10px 14px; }
  h2 { font-size: 14px; margin: 0 0 6px; }
  .status { font-size: 12px; opacity: .8; }
  .toolbar { display: flex; gap: 8px; margin-top: 8px; }
  button { background: var(--vscode-button-background); color: var(--vscode-button-foreground); border: none; padding: 4px 10px; cursor: pointer; }
  button.secondary { background: var(--vscode-button-secondaryBackground); color: var(--vscode-button-secondaryForeground); }
  main { padding: 14px; display: grid; grid-template-columns: 1.4fr 1fr; gap: 14px; height: calc(100vh - 150px); }
  section { overflow: auto; border: 1px solid var(--vscode-panel-border); padding: 10px; }
  .msg { margin-bottom: 12px; }
  .msg .role { font-size: 11px; text-transform: uppercase; opacity: .7; }
  .msg .content { white-space: pre-wrap; word-break: break-word; }
  #events { font-family: var(--vscode-editor-font-family); font-size: 12px; white-space: pre-wrap; word-break: break-all; }
  footer { display: flex; gap: 8px; padding: 10px 14px; border-top: 1px solid var(--vscode-panel-border); }
  textarea { flex: 1; resize: none; height: 54px; background: var(--vscode-input-background); color: var(--vscode-input-foreground); border: 1px solid var(--vscode-input-border); padding: 6px; }
</style>
</head>
<body>
<header>
  <h2 id="title">Flux 任务</h2>
  <div class="status" id="status">加载中…</div>
  <div class="toolbar">
    <button id="start">开始</button>
    <button id="cancel" class="secondary">取消</button>
    <button id="refresh" class="secondary">刷新</button>
  </div>
</header>
<main>
  <section><div id="messages"></div></section>
  <section><div id="events"></div></section>
</main>
<footer>
  <textarea id="input" placeholder="给这个任务发消息…"></textarea>
  <button id="send">发送</button>
</footer>
<script nonce="${nonce}">
  const vscode = acquireVsCodeApi();
  const $ = (id) => document.getElementById(id);
  const messagesEl = $('messages');
  const eventsEl = $('events');
  const statusEl = $('status');
  const titleEl = $('title');

  function addEvent(text) {
    eventsEl.textContent += text + "\\n";
  }
  window.addEventListener('message', (ev) => {
    const msg = ev.data;
    if (msg.type === 'task') {
      const t = msg.task || {};
      titleEl.textContent = t.title || t.description || 'Flux 任务';
      statusEl.textContent = '状态：' + (t.status || '未知') + ' · ID：' + (t.id || '');
    } else if (msg.type === 'messages') {
      messagesEl.textContent = '';
      for (const m of msg.items || []) {
        const wrap = document.createElement('div');
        wrap.className = 'msg';
        const role = document.createElement('div');
        role.className = 'role';
        role.textContent = m.role || 'unknown';
        const body = document.createElement('div');
        body.className = 'content';
        body.textContent = m.content || '';
        wrap.appendChild(role);
        wrap.appendChild(body);
        messagesEl.appendChild(wrap);
      }
      messagesEl.scrollTop = messagesEl.scrollHeight;
    } else if (msg.type === 'event') {
      addEvent(msg.text);
    } else if (msg.type === 'error') {
      addEvent('ERROR: ' + msg.text);
    }
  });
  $('start').onclick = () => vscode.postMessage({ type: 'start' });
  $('cancel').onclick = () => vscode.postMessage({ type: 'cancel' });
  $('refresh').onclick = () => vscode.postMessage({ type: 'refresh' });
  $('send').onclick = () => {
    const input = $('input');
    if (input.value.trim()) {
      vscode.postMessage({ type: 'send', text: input.value });
      input.value = '';
    }
  };
  vscode.postMessage({ type: 'ready' });
</script>
</body>
</html>`;
  }
}

function truncate(text: string, max = 400): string {
  return text.length > max ? `${text.slice(0, max)}…` : text;
}

function randomNonce(): string {
  const chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
  let out = "";
  for (let i = 0; i < 24; i++) {
    out += chars.charAt(Math.floor(Math.random() * chars.length));
  }
  return out;
}

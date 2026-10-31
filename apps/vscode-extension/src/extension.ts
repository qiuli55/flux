import * as vscode from "vscode";
import { FluxClient } from "./api";
import { ProjectsProvider, TasksProvider } from "./views";
import { ChangeContentProvider, ChangeNode, ChangesProvider, openChangeDiff } from "./changes";
import { TaskPanel } from "./panel";
import { LanguageService } from "./lsp";
import { FluxCompletionService } from "./completion";

let statusItem: vscode.StatusBarItem;
let client: FluxClient;
let projectsProvider: ProjectsProvider;
let tasksProvider: TasksProvider;
let changesProvider: ChangesProvider;
let changesView: vscode.TreeView<ChangeNode>;

function selectedChanges(arg?: ChangeNode | ChangeNode[]): ChangeNode[] {
  if (Array.isArray(arg)) {
    return arg;
  }
  const selection = changesView ? [...changesView.selection] : [];
  if (arg instanceof ChangeNode) {
    // 多选时内联按钮只回传被点击的那一项，按选择集整体操作
    return selection.length > 1 && selection.includes(arg) ? selection : [arg];
  }
  return selection;
}

function config(): vscode.WorkspaceConfiguration {
  return vscode.workspace.getConfiguration("flux");
}

function serverUrl(): string {
  // 独立 IDE 由桌面端拉起时，后端端口是随机的，通过环境变量下发
  return process.env.FLUX_SERVER_URL || config().get<string>("serverUrl") || "http://127.0.0.1:8000";
}

function authToken(): string {
  return process.env.FLUX_AUTH_TOKEN || config().get<string>("token") || "";
}

function clientFromConfig(): FluxClient {
  return new FluxClient(serverUrl(), authToken());
}

async function updateStatus(): Promise<void> {
  if (!statusItem) {
    return;
  }
  try {
    await client.health();
    const url = serverUrl();
    statusItem.text = `$(pulse) Flux: 已连接`;
    statusItem.tooltip = url;
    statusItem.backgroundColor = undefined;
  } catch (err) {
    statusItem.text = `$(debug-disconnect) Flux: 未连接`;
    statusItem.tooltip = (err as Error).message;
    statusItem.backgroundColor = new vscode.ThemeColor("statusBarItem.warningBackground");
  }
  statusItem.show();
}

export function activate(context: vscode.ExtensionContext): void {
  client = clientFromConfig();
  projectsProvider = new ProjectsProvider(() => client);
  tasksProvider = new TasksProvider(() => client, () => config().get<string>("projectId") || "");
  changesProvider = new ChangesProvider(() => client, () => config().get<string>("projectId") || "");

  const contentProvider = new ChangeContentProvider(() => client);
  context.subscriptions.push(
    vscode.workspace.registerTextDocumentContentProvider(ChangeContentProvider.scheme, contentProvider)
  );

  statusItem = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Left, 10);
  statusItem.command = "flux.healthCheck";

  context.subscriptions.push(statusItem);
  context.subscriptions.push(vscode.window.registerTreeDataProvider("fluxProjects", projectsProvider));
  context.subscriptions.push(vscode.window.registerTreeDataProvider("fluxTasks", tasksProvider));
  changesView = vscode.window.createTreeView("fluxChanges", {
    treeDataProvider: changesProvider,
    canSelectMany: true,
  });
  context.subscriptions.push(changesView);

  context.subscriptions.push(
    vscode.commands.registerCommand("flux.refresh", () => {
      projectsProvider.refresh();
      tasksProvider.refresh();
      changesProvider.refresh();
      contentProvider.invalidate();
      void updateStatus();
    }),
    vscode.commands.registerCommand("flux.openChangeDiff", async (changeId: string) => {
      try {
        await openChangeDiff(() => client, changeId);
      } catch (err) {
        void vscode.window.showErrorMessage(`打开差异失败：${(err as Error).message}`);
      }
    }),
    vscode.commands.registerCommand("flux.acceptChanges", async (arg?: ChangeNode | ChangeNode[]) => {
      const nodes = selectedChanges(arg);
      if (!nodes.length) {
        return;
      }
      try {
        await client.acceptChanges(nodes.map((n) => n.change.id));
        contentProvider.invalidate();
        changesProvider.refresh();
      } catch (err) {
        void vscode.window.showErrorMessage(`接受提案失败：${(err as Error).message}`);
      }
    }),
    vscode.commands.registerCommand("flux.rejectChanges", async (arg?: ChangeNode | ChangeNode[]) => {
      const nodes = selectedChanges(arg);
      if (!nodes.length) {
        return;
      }
      const reason = await vscode.window.showInputBox({
        prompt: `拒绝 ${nodes.length} 条提案的原因（可留空）`,
        placeHolder: "例如：改动范围过大，要求拆分后重提",
        ignoreFocusOut: true,
      });
      if (reason === undefined) {
        return;
      }
      try {
        await client.rejectChanges(nodes.map((n) => n.change.id), reason || undefined);
        contentProvider.invalidate();
        changesProvider.refresh();
      } catch (err) {
        void vscode.window.showErrorMessage(`拒绝提案失败：${(err as Error).message}`);
      }
    }),
    vscode.commands.registerCommand("flux.applyChanges", async (arg?: ChangeNode | ChangeNode[]) => {
      const nodes = selectedChanges(arg);
      if (!nodes.length) {
        return;
      }
      const confirm = await vscode.window.showWarningMessage(
        `将把 ${nodes.length} 条提案真实写入磁盘（自动备份，可回滚），继续？`,
        { modal: true },
        "应用"
      );
      if (confirm !== "应用") {
        return;
      }
      try {
        await client.applyChanges(nodes.map((n) => n.change.id));
        contentProvider.invalidate();
        changesProvider.refresh();
        void vscode.window.showInformationMessage(`已应用 ${nodes.length} 条提案`);
      } catch (err) {
        void vscode.window.showErrorMessage(`应用提案失败：${(err as Error).message}`);
      }
    }),
    vscode.commands.registerCommand("flux.rollbackChange", async (arg?: ChangeNode | ChangeNode[]) => {
      const nodes = selectedChanges(arg);
      if (!nodes.length) {
        return;
      }
      const confirm = await vscode.window.showWarningMessage(
        `回滚 ${nodes.length} 个已落盘的变更？文件将恢复为改动前内容。`,
        { modal: true },
        "回滚"
      );
      if (confirm !== "回滚") {
        return;
      }
      try {
        for (const node of nodes) {
          await client.rollbackChange(node.change.id);
        }
        contentProvider.invalidate();
        changesProvider.refresh();
      } catch (err) {
        void vscode.window.showErrorMessage(`回滚失败：${(err as Error).message}`);
      }
    }),
    vscode.commands.registerCommand("flux.openTask", (taskId: string) => {
      TaskPanel.show(context, client, taskId);
    }),
    vscode.commands.registerCommand("flux.newTask", async () => {
      const projects = await client.projects().catch(() => []);
      let projectId = config().get<string>("projectId") || "";
      if (!projectId && projects.length > 1) {
        const picked = await vscode.window.showQuickPick(
          projects.map((p) => ({ label: p.name || p.id, description: p.path || p.root_path || "", id: p.id })),
          { placeHolder: "选择项目" }
        );
        if (!picked) {
          return;
        }
        projectId = picked.id;
      }
      if (!projectId && projects.length === 1) {
        projectId = projects[0].id;
      }
      const description = await vscode.window.showInputBox({
        prompt: "描述这个任务要做什么",
        placeHolder: "例如：修复登录接口 401 后不刷新的问题",
        ignoreFocusOut: true,
      });
      if (!description?.trim()) {
        return;
      }
      try {
        const task = await client.createTask({ description: description.trim(), project_id: projectId || undefined });
        const startNow = await vscode.window.showQuickPick(
          [
            { label: "创建并立即开始", value: true },
            { label: "仅创建，稍后手动开始", value: false },
          ],
          { placeHolder: "是否立即启动任务？" }
        );
        if (startNow?.value) {
          await client.startTask(task.id);
        }
        tasksProvider.refresh();
        TaskPanel.show(context, client, task.id);
      } catch (err) {
        void vscode.window.showErrorMessage(`创建任务失败：${(err as Error).message}`);
      }
    }),
    vscode.commands.registerCommand("flux.spec.draft", async () => {
      // 需求文档环节（AI_COMPLETION_SPEC §6）：不自动启动，先进任务面板由 Agent 确认
      const goal = await vscode.window.showInputBox({
        prompt: "这次要解决什么问题？（作为需求文档的「目标」）",
        placeHolder: "例如：IDE 内置 AI 补全都走 Flux 的模型与上下文",
        ignoreFocusOut: true,
      });
      if (!goal?.trim()) {
        return;
      }
      let projectId = config().get<string>("projectId") || "";
      if (!projectId) {
        const projects = await client.projects().catch(() => []);
        if (projects.length === 1) {
          projectId = projects[0].id;
        }
      }
      const description = [
        "## 目标",
        goal.trim(),
        "",
        "## 范围",
        "- （要做的事）",
        "",
        "## 验收",
        "- （可验证的标准）",
        "",
        "## 不做",
        "- （明确排除的项）",
      ].join("\n");
      try {
        const task = await client.createTask({ description, project_id: projectId || undefined });
        tasksProvider.refresh();
        TaskPanel.show(context, client, task.id);
      } catch (err) {
        void vscode.window.showErrorMessage(`创建需求任务失败：${(err as Error).message}`);
      }
    }),
    vscode.commands.registerCommand("flux.setServer", async () => {
      const value = await vscode.window.showInputBox({
        prompt: "Flux 后端地址",
        value: config().get<string>("serverUrl") || "http://127.0.0.1:8000",
        ignoreFocusOut: true,
      });
      if (value?.trim()) {
        await config().update("serverUrl", value.trim(), vscode.ConfigurationTarget.Global);
      }
    }),
    vscode.commands.registerCommand("flux.healthCheck", async () => {
      try {
        const started = Date.now();
        const health = await client.health();
        await updateStatus();
        void vscode.window.showInformationMessage(`Flux 连接正常（${Date.now() - started}ms）: ${JSON.stringify(health).slice(0, 120)}`);
      } catch (err) {
        await updateStatus();
        void vscode.window.showErrorMessage(`Flux 连接失败：${(err as Error).message}`);
      }
    })
  );

  context.subscriptions.push(
    vscode.workspace.onDidChangeConfiguration((event) => {
      if (event.affectsConfiguration("flux.serverUrl") || event.affectsConfiguration("flux.token")) {
        client = clientFromConfig();
        projectsProvider.refresh();
        tasksProvider.refresh();
        changesProvider.refresh();
        void updateStatus();
      }
    })
  );

  void updateStatus();
  projectsProvider.refresh();
  tasksProvider.refresh();

  // 代码智能：补全 / 悬停 / 跳转 / 重命名 / 诊断 / 语义高亮，全部走 Flux 后端的 LSP 网关
  const languageService = new LanguageService(() => client);
  languageService.activate(context);

  // AI 行内补全：ghost text 流式生成，模型与上下文都由 Flux 后端解析
  context.subscriptions.push(new FluxCompletionService(() => client));
}

export function deactivate(): void {
  statusItem?.dispose();
}

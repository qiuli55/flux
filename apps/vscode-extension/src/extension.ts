import * as vscode from "vscode";
import { FluxClient, FluxProject } from "./api";
import {
  ProjectNode,
  ProjectsProvider,
  TaskNode,
  TasksProvider,
  ViewState,
  projectMatchesWorkspace,
  projectPath,
  relativeTime,
  statusMeta,
} from "./views";
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
let projectsView: vscode.TreeView<ProjectNode>;
let tasksView: vscode.TreeView<TaskNode>;
let healthTimer: NodeJS.Timeout | undefined;
let connected = false;

/** Bot 头像契约：形状与颜色取值必须与后端、桌面端保持一致。 */
const BOT_SHAPES: ReadonlyArray<{ id: string; label: string }> = [
  { id: "circle", label: "圆形" },
  { id: "squircle", label: "圆角矩形" },
  { id: "hexagon", label: "六边形" },
  { id: "triangle", label: "三角形" },
  { id: "diamond", label: "菱形" },
  { id: "star", label: "星形" },
  { id: "flower", label: "花朵" },
  { id: "burst", label: "爆裂" },
  { id: "shield", label: "盾牌" },
  { id: "drop", label: "水滴" },
  { id: "cloud", label: "云朵" },
  { id: "gear", label: "齿轮" },
  { id: "egg", label: "蛋形" },
  { id: "blob", label: "不规则形" },
];

const BOT_COLORS: ReadonlyArray<{ id: string; label: string }> = [
  { id: "violet", label: "紫罗兰" },
  { id: "indigo", label: "靛蓝" },
  { id: "sky", label: "天蓝" },
  { id: "teal", label: "青绿" },
  { id: "emerald", label: "翠绿" },
  { id: "lime", label: "青柠" },
  { id: "amber", label: "琥珀" },
  { id: "orange", label: "橙色" },
  { id: "rose", label: "玫瑰" },
  { id: "fuchsia", label: "品红" },
  { id: "slate", label: "石板灰" },
  { id: "cyan", label: "青色" },
];

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

function projectId(): string {
  return config().get<string>("projectId") || "";
}

/**
 * 新任务归属哪个项目：显式配置 > 与工作区同路径的项目 > 只有一个项目时用它。
 * 工作区已经对应某个项目时不再追问，少一次选择。
 */
function resolveProjectId(projects: FluxProject[]): string {
  const configured = projectId();
  if (configured) {
    return configured;
  }
  const matched = projects.find(projectMatchesWorkspace);
  if (matched) {
    return matched.id;
  }
  return projects.length === 1 ? projects[0].id : "";
}

function shortProjectPath(project: FluxProject): string {
  const value = projectPath(project).replace(/[\\/]+$/, "");
  return value ? value.split(/[\\/]/).slice(-2).join("/") : "";
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

function applyState(view: vscode.TreeView<unknown>, state: ViewState): void {
  view.message = state.message;
  view.description = state.description;
  view.badge =
    state.count && state.count > 0
      ? { value: state.count, tooltip: `${state.count} 项` }
      : undefined;
}

async function updateStatus(): Promise<void> {
  if (!statusItem) {
    return;
  }
  try {
    await client.health();
    connected = true;
    statusItem.text = "$(pulse) Flux";
    statusItem.tooltip = new vscode.MarkdownString(
      [
        "**Flux 已连接**",
        `\`${serverUrl()}\``,
        authToken() ? "已携带访问令牌" : "未配置访问令牌",
        "",
        "点击打开快捷菜单 · `Ctrl+Alt+F`",
      ].join("\n\n")
    );
    (statusItem.tooltip as vscode.MarkdownString).isTrusted = false;
    statusItem.backgroundColor = undefined;
    statusItem.color = undefined;
  } catch (err) {
    connected = false;
    statusItem.text = "$(debug-disconnect) Flux 离线";
    statusItem.tooltip = new vscode.MarkdownString(
      [
        "**Flux 未连接**",
        `\`${serverUrl()}\``,
        `\`\`\`\n${(err as Error).message}\n\`\`\``,
        "点击打开快捷菜单，可设置服务器地址或重试",
      ].join("\n\n")
    );
    (statusItem.tooltip as vscode.MarkdownString).isTrusted = false;
    statusItem.backgroundColor = new vscode.ThemeColor("statusBarItem.warningBackground");
    statusItem.color = undefined;
  }
  await vscode.commands.executeCommand("setContext", "flux.connected", connected);
  statusItem.show();
}

async function pickProject(): Promise<ProjectNode | undefined> {
  const projects = await projectsProvider.projects();
  if (!projects.length) {
    void vscode.window.showInformationMessage("Flux 没有返回任何项目，先检查连接或服务器地址。");
    return undefined;
  }
  const active = projectId();
  const picked = await vscode.window.showQuickPick(
    projects.map((project) => {
      const node = new ProjectNode(project, project.id === active);
      return {
        label: `${project.id === active ? "$(folder-active)" : "$(folder)"} ${project.name || project.id}`,
        description: shortProjectPath(project),
        detail: project.id === active ? "当前项目" : undefined,
        node,
      };
    }),
    { placeHolder: "选择要操作的 Flux 项目", matchOnDescription: true }
  );
  return picked?.node;
}

async function selectProject(arg?: ProjectNode | string): Promise<void> {
  let target: ProjectNode | undefined;
  if (arg instanceof ProjectNode) {
    target = arg;
  } else if (typeof arg === "string") {
    target = new ProjectNode({ id: arg, name: arg }, arg === projectId());
  } else {
    target = await pickProject();
  }
  if (!target) {
    return;
  }
  await config().update("projectId", target.project.id, vscode.ConfigurationTarget.Global);
  projectsProvider.refresh();
  tasksProvider.refresh();
  changesProvider.refresh();
  void vscode.window.setStatusBarMessage(`$(check) 已切换到 ${target.project.name || target.project.id}`, 3000);
}

async function openTaskPanel(arg?: TaskNode | string): Promise<void> {
  let taskId = typeof arg === "string" ? arg : arg instanceof TaskNode ? arg.task.id : "";
  if (!taskId) {
    const tasks = await tasksProvider.tasks();
    if (!tasks.length) {
      void vscode.window.showInformationMessage("当前项目还没有 Bot，先新建一个。");
      return;
    }
    const picked = await vscode.window.showQuickPick(
      tasks.map((task) => {
        const meta = statusMeta(task.status);
        return {
          label: `$(${meta.icon}) ${task.bot_name || task.title || task.description?.slice(0, 60) || task.id}`,
          description: [meta.label, relativeTime(task.updated_at ?? task.created_at)]
            .filter(Boolean)
            .join(" · "),
          id: task.id,
        };
      }),
      { placeHolder: "选择要打开的 Bot", matchOnDescription: true }
    );
    if (!picked) {
      return;
    }
    taskId = picked.id;
  }
  TaskPanel.show(extensionContext, client, taskId);
}

async function quickMenu(): Promise<void> {
  const items: (vscode.QuickPickItem & { run: () => unknown })[] = [
    {
      label: "$(add) 新建 Bot",
      description: "Ctrl+Alt+N",
      run: () => vscode.commands.executeCommand("flux.newTask"),
    },
    {
      label: "$(comment-discussion) 打开 Bot 面板…",
      description: "Ctrl+Alt+T",
      run: () => vscode.commands.executeCommand("flux.openTaskPanel"),
    },
    {
      label: "$(folder) 选择项目…",
      run: () => vscode.commands.executeCommand("flux.pickProject"),
    },
    {
      label: "$(refresh) 刷新项目 / 任务 / 提案",
      run: () => vscode.commands.executeCommand("flux.refresh"),
    },
    {
      label: "$(sparkle) 开关 AI 补全",
      description: "Ctrl+Alt+A",
      run: () => vscode.commands.executeCommand("flux.completion.toggle"),
    },
    {
      label: "$(pulse) 补全自检",
      run: () => vscode.commands.executeCommand("flux.completion.selfCheck"),
    },
    {
      label: "$(server-process) 语言服务器…",
      run: () => vscode.commands.executeCommand("flux.showLanguageServers"),
    },
    {
      label: "$(book) 与 Agent 确认需求文档…",
      run: () => vscode.commands.executeCommand("flux.spec.draft"),
    },
    {
      label: "$(plug) 设置服务器地址…",
      description: serverUrl(),
      run: () => vscode.commands.executeCommand("flux.setServer"),
    },
    {
      label: connected ? "$(debug-disconnect) 检查连接" : "$(debug-disconnect) 重试连接",
      description: connected ? serverUrl() : "当前离线",
      run: () => vscode.commands.executeCommand("flux.healthCheck"),
    },
    {
      label: "$(gear) 打开 Flux 设置",
      run: () => vscode.commands.executeCommand("flux.openSettings"),
    },
  ];
  const picked = await vscode.window.showQuickPick(items, {
    placeHolder: connected ? "Flux 已连接 · 选择要执行的操作" : "Flux 离线 · 可以先检查连接",
  });
  await picked?.run();
}

let extensionContext: vscode.ExtensionContext;

export function activate(context: vscode.ExtensionContext): void {
  extensionContext = context;
  client = clientFromConfig();
  projectsProvider = new ProjectsProvider(() => client, () => projectId());
  tasksProvider = new TasksProvider(() => client, () => projectId());
  changesProvider = new ChangesProvider(() => client, () => projectId());

  const contentProvider = new ChangeContentProvider(() => client);
  context.subscriptions.push(
    vscode.workspace.registerTextDocumentContentProvider(ChangeContentProvider.scheme, contentProvider)
  );

  statusItem = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Left, 10);
  statusItem.command = "flux.quickMenu";
  context.subscriptions.push(statusItem);

  projectsView = vscode.window.createTreeView("fluxProjects", { treeDataProvider: projectsProvider });
  tasksView = vscode.window.createTreeView("fluxTasks", { treeDataProvider: tasksProvider });
  changesView = vscode.window.createTreeView("fluxChanges", {
    treeDataProvider: changesProvider,
    canSelectMany: true,
  });
  context.subscriptions.push(projectsView, tasksView, changesView);

  projectsProvider.setStateListener((state) => applyState(projectsView as vscode.TreeView<unknown>, state));
  tasksProvider.setStateListener((state) => applyState(tasksView as vscode.TreeView<unknown>, state));
  changesProvider.setStateListener((state) => applyState(changesView as vscode.TreeView<unknown>, state));

  context.subscriptions.push(
    vscode.commands.registerCommand("flux.refresh", () => {
      projectsProvider.refresh();
      tasksProvider.refresh();
      changesProvider.refresh();
      contentProvider.invalidate();
      void updateStatus();
    }),
    vscode.commands.registerCommand("flux.quickMenu", () => quickMenu()),
    vscode.commands.registerCommand("flux.pickProject", () => selectProject()),
    vscode.commands.registerCommand("flux.selectProject", (arg?: ProjectNode | string) => selectProject(arg)),
    vscode.commands.registerCommand("flux.openTaskPanel", (arg?: TaskNode | string) => openTaskPanel(arg)),
    vscode.commands.registerCommand("flux.openSettings", () =>
      vscode.commands.executeCommand("workbench.action.openSettings", "@ext:flux.flux-vscode")
    ),
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
        await vscode.window.withProgress(
          {
            location: vscode.ProgressLocation.Notification,
            title: `正在应用 ${nodes.length} 条提案（写入磁盘并运行测试）`,
            cancellable: false,
          },
          () => client.applyChanges(nodes.map((n) => n.change.id))
        );
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
        await vscode.window.withProgress(
          {
            location: vscode.ProgressLocation.Notification,
            title: `正在回滚 ${nodes.length} 个变更`,
            cancellable: false,
          },
          async (progress) => {
            let done = 0;
            for (const node of nodes) {
              progress.report({ message: node.change.file_path, increment: 100 / nodes.length });
              await client.rollbackChange(node.change.id);
              done += 1;
            }
            progress.report({ message: `已完成 ${done}/${nodes.length}` });
          }
        );
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
      let target = resolveProjectId(projects);
      if (!target && projects.length > 1) {
        const picked = await vscode.window.showQuickPick(
          projects.map((p) => ({ label: p.name || p.id, description: shortProjectPath(p), id: p.id })),
          { placeHolder: "选择项目" }
        );
        if (!picked) {
          return;
        }
        target = picked.id;
      }
      const name = await vscode.window.showInputBox({
        prompt: "这个 Bot 叫什么名字？",
        placeHolder: "例如：数据统计、前端搭子",
        ignoreFocusOut: true,
        validateInput: (input) => (input.trim() ? undefined : "Bot 名称不能为空"),
      });
      if (!name?.trim()) {
        return;
      }
      const shape = await vscode.window.showQuickPick(
        BOT_SHAPES.map((item) => ({ label: item.id, description: item.label })),
        { placeHolder: "选择 Bot 头像形状" }
      );
      if (!shape) {
        return;
      }
      const color = await vscode.window.showQuickPick(
        BOT_COLORS.map((item) => ({ label: item.id, description: item.label })),
        { placeHolder: "选择 Bot 头像颜色" }
      );
      if (!color) {
        return;
      }
      try {
        const task = await client.createTask({
          description: name.trim(),
          project_id: target || undefined,
          bot_name: name.trim(),
          avatar_shape: shape.label,
          avatar_color: color.label,
          start: false,
        });
        tasksProvider.refresh();
        TaskPanel.show(context, client, task.id);
      } catch (err) {
        void vscode.window.showErrorMessage(`创建 Bot 失败：${(err as Error).message}`);
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
      const projects = await client.projects().catch(() => []);
      let target = resolveProjectId(projects);
      if (!target) {
        const picked = await vscode.window.showQuickPick(
          projects.map((p) => ({ label: p.name || p.id, description: shortProjectPath(p), id: p.id })),
          { placeHolder: "这个需求属于哪个项目？" }
        );
        if (!picked) {
          return;
        }
        target = picked.id;
      }
      // 首行直接写目标：任务标题由后端取描述首行，标题里不该出现 Markdown 记号
      const description = [
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
        const task = await client.createTask({ description, project_id: target || undefined });
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
        placeHolder: "http://127.0.0.1:8000",
        ignoreFocusOut: true,
        validateInput: (input) => (/^https?:\/\/.+/i.test(input.trim()) ? undefined : "请填写 http(s):// 开头的地址"),
      });
      if (value?.trim()) {
        await config().update("serverUrl", value.trim(), vscode.ConfigurationTarget.Global);
        void updateStatus();
      }
    }),
    vscode.commands.registerCommand("flux.healthCheck", async () => {
      try {
        const started = Date.now();
        const health = await client.health();
        await updateStatus();
        projectsProvider.refresh();
        tasksProvider.refresh();
        changesProvider.refresh();
        void vscode.window.showInformationMessage(
          `Flux 连接正常（${Date.now() - started}ms）: ${JSON.stringify(health).slice(0, 120)}`
        );
      } catch (err) {
        await updateStatus();
        void vscode.window.showErrorMessage(`Flux 连接失败：${(err as Error).message}`, "设置地址", "重试").then(
          async (choice) => {
            if (choice === "设置地址") {
              await vscode.commands.executeCommand("flux.setServer");
            } else if (choice === "重试") {
              await vscode.commands.executeCommand("flux.refresh");
            }
          }
        );
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
      if (event.affectsConfiguration("flux.projectId")) {
        projectsProvider.refresh();
        tasksProvider.refresh();
        changesProvider.refresh();
      }
    })
  );

  void updateStatus();
  projectsProvider.refresh();
  tasksProvider.refresh();

  healthTimer = setInterval(() => {
    void updateStatus();
  }, 60_000);
  context.subscriptions.push({ dispose: () => healthTimer && clearInterval(healthTimer) });

  // 代码智能：补全 / 悬停 / 跳转 / 重命名 / 诊断 / 语义高亮，全部走 Flux 后端的 LSP 网关
  const languageService = new LanguageService(() => client);
  languageService.activate(context);

  // AI 行内补全：ghost text 流式生成，模型与上下文都由 Flux 后端解析
  context.subscriptions.push(new FluxCompletionService(() => client));
}

export function deactivate(): void {
  statusItem?.dispose();
}

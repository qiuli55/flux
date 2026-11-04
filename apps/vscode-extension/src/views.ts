import * as vscode from "vscode";
import { FluxClient, FluxProject, FluxTask } from "./api";

export type FluxTreeNode = ProjectNode | TaskNode;

export interface ViewState {
  message?: string;
  description?: string;
  count?: number;
}

const STATUS_META: Record<string, { label: string; icon: string; color: string }> = {
  running: { label: "进行中", icon: "sync~spin", color: "flux.accent" },
  pausing: { label: "暂停中", icon: "debug-pause", color: "flux.warn" },
  paused: { label: "已暂停", icon: "debug-pause", color: "flux.warn" },
  pending: { label: "排队中", icon: "clock", color: "flux.info" },
  queued: { label: "排队中", icon: "clock", color: "flux.info" },
  planning: { label: "规划中", icon: "lightbulb", color: "flux.info" },
  waiting: { label: "等待确认", icon: "bell", color: "flux.warn" },
  completed: { label: "已完成", icon: "pass-filled", color: "flux.accent" },
  done: { label: "已完成", icon: "pass-filled", color: "flux.accent" },
  failed: { label: "失败", icon: "error", color: "flux.danger" },
  error: { label: "出错", icon: "error", color: "flux.danger" },
  cancelled: { label: "已取消", icon: "circle-slash", color: "flux.muted" },
  canceled: { label: "已取消", icon: "circle-slash", color: "flux.muted" },
};

export function statusMeta(status?: string): { label: string; icon: string; color: string } {
  const key = (status ?? "").toLowerCase();
  return STATUS_META[key] ?? { label: status || "未知", icon: "circle-outline", color: "flux.muted" };
}

export function relativeTime(iso?: string): string {
  if (!iso) {
    return "";
  }
  const value = Date.parse(iso);
  if (Number.isNaN(value)) {
    return "";
  }
  const diff = Date.now() - value;
  if (diff < 0) {
    return "刚刚";
  }
  const minute = 60_000;
  const hour = 60 * minute;
  const day = 24 * hour;
  if (diff < minute) {
    return "刚刚";
  }
  if (diff < hour) {
    return `${Math.floor(diff / minute)} 分钟前`;
  }
  if (diff < day) {
    return `${Math.floor(diff / hour)} 小时前`;
  }
  if (diff < 7 * day) {
    return `${Math.floor(diff / day)} 天前`;
  }
  const date = new Date(value);
  return `${date.getMonth() + 1} 月 ${date.getDate()} 日`;
}

function markdown(lines: string[]): vscode.MarkdownString {
  const md = new vscode.MarkdownString(lines.join("\n\n"));
  md.supportThemeIcons = true;
  md.isTrusted = false;
  return md;
}

function shortPath(value?: string): string {
  if (!value) {
    return "";
  }
  const parts = value.replace(/[\\/]+$/, "").split(/[\\/]/);
  return parts.slice(-2).join("/");
}

/** 后端 project 可能用 repository / path / root_path 任一字段存放仓库路径。 */
export function projectPath(project: FluxProject): string {
  return project.repository || project.path || project.root_path || "";
}

/** 工作区目录与项目仓库路径一致时，视为当前项目（即便没有显式配置 flux.projectId）。 */
export function projectMatchesWorkspace(project: FluxProject): boolean {
  const repo = projectPath(project).replace(/[\\/]+$/, "");
  if (!repo) {
    return false;
  }
  return (vscode.workspace.workspaceFolders ?? []).some(
    (folder) => folder.uri.fsPath.replace(/[\\/]+$/, "") === repo
  );
}

export class ProjectNode extends vscode.TreeItem {
  constructor(public readonly project: FluxProject, active = false) {
    super(project.name || project.id, vscode.TreeItemCollapsibleState.None);
    const path = projectPath(project);
    this.contextValue = "fluxProject";
    this.description = active ? "当前" : shortPath(path);
    this.iconPath = new vscode.ThemeIcon(
      active ? "folder-active" : "folder",
      new vscode.ThemeColor(active ? "flux.accent" : "flux.muted")
    );
    this.tooltip = markdown([
      `**${project.name ?? project.id}**${active ? " · 当前项目" : ""}`,
      path ? `\`${path}\`` : "",
      `ID: \`${project.id}\``,
      active ? "" : "点击切换到此项目",
    ].filter(Boolean));
    this.command = {
      command: "flux.selectProject",
      title: "切换到此项目",
      arguments: [this],
    };
  }
}

export class TaskNode extends vscode.TreeItem {
  constructor(public readonly task: FluxTask) {
    const name =
      task.bot_name ||
      task.title ||
      task.description?.split("\n")[0]?.slice(0, 60) ||
      task.id;
    super(name, vscode.TreeItemCollapsibleState.None);
    const meta = statusMeta(task.status);
    const time = relativeTime(task.updated_at ?? task.created_at);
    const settled = ["completed", "done", "cancelled", "canceled"].includes((task.status ?? "").toLowerCase());
    this.contextValue = "fluxTask";
    this.description = settled ? time : [meta.label, time].filter(Boolean).join(" · ");
    this.iconPath = new vscode.ThemeIcon(meta.icon, new vscode.ThemeColor(meta.color));
    this.tooltip = markdown([
      `**${meta.icon ? `$(${meta.icon.replace("~spin", "")}) ` : ""}${name}**`,
      task.description ? task.description.slice(0, 800) : "",
      `状态：${meta.label}${relativeTime(task.updated_at ?? task.created_at) ? ` · 更新于${relativeTime(task.updated_at ?? task.created_at)}` : ""}`,
      `ID: \`${task.id}\``,
    ].filter(Boolean));
    this.command = {
      command: "flux.openTask",
      title: "打开 Bot",
      arguments: [task.id],
    };
  }
}

export abstract class StatefulProvider<T> implements vscode.TreeDataProvider<T> {
  private readonly emitter = new vscode.EventEmitter<void>();
  private listener?: (state: ViewState) => void;
  private state: ViewState = {};
  readonly onDidChangeTreeData = this.emitter.event;

  refresh(): void {
    this.emitter.fire();
  }

  setStateListener(listener: (state: ViewState) => void): void {
    this.listener = listener;
    listener(this.state);
  }

  protected setState(state: ViewState): void {
    this.state = state;
    this.listener?.(state);
  }

  getTreeItem(element: T): vscode.TreeItem {
    return element as unknown as vscode.TreeItem;
  }

  abstract getChildren(element?: T): vscode.ProviderResult<T[]>;
}

export class ProjectsProvider extends StatefulProvider<ProjectNode> {
  constructor(private readonly client: () => FluxClient, private readonly activeProjectId: () => string) {
    super();
  }

  async getChildren(): Promise<ProjectNode[]> {
    try {
      const projects = await this.client().projects();
      const configured = this.activeProjectId();
      const active = configured || projects.find((p) => projectMatchesWorkspace(p))?.id || "";
      this.setState({ count: projects.length, description: projects.length ? `${projects.length}` : undefined });
      return projects.map((p) => new ProjectNode(p, p.id === active));
    } catch (err) {
      this.setState({ count: 0, description: undefined, message: `无法读取项目：${(err as Error).message}` });
      return [];
    }
  }

  async projects(): Promise<FluxProject[]> {
    return this.client()
      .projects()
      .catch(() => []);
  }
}

export class TasksProvider extends StatefulProvider<TaskNode> {
  constructor(private readonly client: () => FluxClient, private readonly projectId: () => string) {
    super();
  }

  async getChildren(): Promise<TaskNode[]> {
    try {
      const tasks = await this.client().tasks(this.projectId() || undefined);
      const running = tasks.filter((t) => (t.status ?? "").toLowerCase() === "running").length;
      this.setState({
        count: tasks.length,
        description: running ? `${running} 进行中 · ${tasks.length}` : tasks.length ? `${tasks.length}` : undefined,
      });
      return tasks.map((t) => new TaskNode(t));
    } catch (err) {
      this.setState({ count: 0, description: undefined, message: `无法读取 Bot：${(err as Error).message}` });
      return [];
    }
  }

  async tasks(): Promise<FluxTask[]> {
    return this.client()
      .tasks(this.projectId() || undefined)
      .catch(() => []);
  }
}

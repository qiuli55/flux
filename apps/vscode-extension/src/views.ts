import * as vscode from "vscode";
import { FluxClient, FluxProject, FluxTask } from "./api";

export type FluxTreeNode = ProjectNode | TaskNode;

export class ProjectNode extends vscode.TreeItem {
  constructor(public readonly project: FluxProject) {
    super(project.name || project.id, vscode.TreeItemCollapsibleState.None);
    this.contextValue = "fluxProject";
    this.description = project.path || project.root_path || "";
    this.iconPath = new vscode.ThemeIcon("folder");
    this.tooltip = `${project.name ?? project.id}\n${project.id}`;
  }
}

export class TaskNode extends vscode.TreeItem {
  constructor(public readonly task: FluxTask) {
    super(task.title || task.description?.slice(0, 60) || task.id, vscode.TreeItemCollapsibleState.None);
    this.contextValue = "fluxTask";
    this.description = task.status ?? "";
    this.iconPath = new vscode.ThemeIcon(iconFor(task.status));
    this.tooltip = `${task.description ?? ""}\n状态: ${task.status ?? "未知"}\nID: ${task.id}`;
    this.command = {
      command: "flux.openTask",
      title: "打开任务",
      arguments: [task.id],
    };
  }
}

function iconFor(status?: string): string {
  switch ((status ?? "").toLowerCase()) {
    case "running":
      return "sync";
    case "completed":
    case "done":
      return "check";
    case "failed":
    case "error":
      return "error";
    case "cancelled":
    case "canceled":
      return "circle-slash";
    default:
      return "tasklist";
  }
}

export class ProjectsProvider implements vscode.TreeDataProvider<ProjectNode> {
  private readonly emitter = new vscode.EventEmitter<void>();
  readonly onDidChangeTreeData = this.emitter.event;

  constructor(private readonly client: () => FluxClient) {}

  refresh(): void {
    this.emitter.fire();
  }

  getTreeItem(element: ProjectNode): vscode.TreeItem {
    return element;
  }

  async getChildren(): Promise<ProjectNode[]> {
    try {
      return (await this.client().projects()).map((p) => new ProjectNode(p));
    } catch (err) {
      vscode.window.showWarningMessage(`Flux 项目列表获取失败：${(err as Error).message}`);
      return [];
    }
  }
}

export class TasksProvider implements vscode.TreeDataProvider<TaskNode> {
  private readonly emitter = new vscode.EventEmitter<void>();
  readonly onDidChangeTreeData = this.emitter.event;

  constructor(private readonly client: () => FluxClient, private readonly projectId: () => string) {}

  refresh(): void {
    this.emitter.fire();
  }

  getTreeItem(element: TaskNode): vscode.TreeItem {
    return element;
  }

  async getChildren(): Promise<TaskNode[]> {
    try {
      return (await this.client().tasks(this.projectId() || undefined)).map((t) => new TaskNode(t));
    } catch (err) {
      vscode.window.showWarningMessage(`Flux 任务列表获取失败：${(err as Error).message}`);
      return [];
    }
  }
}

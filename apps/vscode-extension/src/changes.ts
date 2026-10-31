import * as vscode from "vscode";
import { FluxChange, FluxClient } from "./api";

const KIND_LABEL: Record<string, string> = {
  create: "新建",
  modify: "修改",
  delete: "删除",
};

const STATUS_LABEL: Record<string, string> = {
  pending: "待审",
  accepted: "已接受",
  applying: "应用中",
  applied: "已应用",
  rejected: "已拒绝",
  failed: "失败",
  expired: "已失效",
  rolled_back: "已回滚",
};

export function kindLabel(kind: string): string {
  return KIND_LABEL[kind] ?? kind;
}

export class ChangeNode extends vscode.TreeItem {
  constructor(public readonly change: FluxChange) {
    super(baseName(change.file_path), vscode.TreeItemCollapsibleState.None);
    const status = change.status ?? "pending";
    const stats = `${kindLabel(change.kind)} · +${change.added_lines ?? 0} −${change.removed_lines ?? 0}`;
    this.description = `${stats} · ${STATUS_LABEL[status] ?? status}`;
    this.contextValue = `fluxChange.${status}`;
    this.iconPath = new vscode.ThemeIcon(iconFor(status));
    this.tooltip = `${change.file_path}\n状态: ${status}\n${change.reason ?? change.summary ?? ""}`;
    this.command = {
      command: "flux.openChangeDiff",
      title: "查看差异",
      arguments: [change.id],
    };
  }
}

function baseName(path: string): string {
  return path.split(/[\\/]/).pop() || path;
}

function iconFor(status: string): string {
  switch (status) {
    case "accepted":
      return "check";
    case "applying":
      return "sync";
    case "applied":
      return "check-all";
    case "failed":
      return "error";
    case "rejected":
    case "expired":
      return "circle-slash";
    case "rolled_back":
      return "history";
    default:
      return "git-pull-request-draft";
  }
}

export class ChangesProvider implements vscode.TreeDataProvider<ChangeNode> {
  private readonly emitter = new vscode.EventEmitter<void>();
  readonly onDidChangeTreeData = this.emitter.event;

  constructor(private readonly client: () => FluxClient, private readonly projectId: () => string) {}

  refresh(): void {
    this.emitter.fire();
  }

  getTreeItem(element: ChangeNode): vscode.TreeItem {
    return element;
  }

  async getChildren(): Promise<ChangeNode[]> {
    try {
      const client = this.client();
      const projectId = this.projectId() || undefined;
      const buckets = await Promise.all(
        ["pending", "accepted", "applied"].map((status) => client.changes(status, projectId).catch(() => []))
      );
      return buckets.flat().map((change) => new ChangeNode(change));
    } catch (err) {
      vscode.window.showWarningMessage(`Flux 提案列表获取失败：${(err as Error).message}`);
      return [];
    }
  }
}

/** 用两个虚拟文档承载「改前 / 改后」内容，交给 VS Code 原生 diff 视图。 */
export class ChangeContentProvider implements vscode.TextDocumentContentProvider {
  static readonly scheme = "flux-change";
  private readonly cache = new Map<string, string>();

  constructor(private readonly client: () => FluxClient) {}

  invalidate(): void {
    this.cache.clear();
  }

  async provideTextDocumentContent(uri: vscode.Uri): Promise<string> {
    const cached = this.cache.get(uri.toString());
    if (cached !== undefined) {
      return cached;
    }
    const [, changeId, side] = uri.path.split("/").filter(Boolean);
    const change = await this.client().change(changeId);
    const content = side === "after" ? change.proposed_content ?? "" : change.original_content ?? "";
    this.cache.set(uri.toString(), content);
    return content;
  }
}

export async function openChangeDiff(client: () => FluxClient, changeId: string): Promise<void> {
  const change = await client().change(changeId);
  const name = baseName(change.file_path);
  const before = vscode.Uri.parse(
    `${ChangeContentProvider.scheme}:/${change.id}/before/${encodeURIComponent(name)}`
  );
  const after = vscode.Uri.parse(
    `${ChangeContentProvider.scheme}:/${change.id}/after/${encodeURIComponent(name)}`
  );
  await vscode.commands.executeCommand(
    "vscode.diff",
    before,
    after,
    `${name} · ${kindLabel(change.kind)} (${STATUS_LABEL[change.status ?? "pending"] ?? change.status})`
  );
}

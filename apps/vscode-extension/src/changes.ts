import * as vscode from "vscode";
import { FluxChange, FluxClient } from "./api";
import { StatefulProvider } from "./views";

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

const STATUS_COLOR: Record<string, string> = {
  pending: "flux.info",
  accepted: "flux.accent",
  applying: "flux.warn",
  applied: "flux.accent",
  rejected: "flux.muted",
  expired: "flux.muted",
  failed: "flux.danger",
  rolled_back: "flux.muted",
};

export function kindLabel(kind: string): string {
  return KIND_LABEL[kind] ?? kind;
}

export class ChangeNode extends vscode.TreeItem {
  constructor(public readonly change: FluxChange) {
    super(baseName(change.file_path), vscode.TreeItemCollapsibleState.None);
    const status = change.status ?? "pending";
    const stats = `${kindLabel(change.kind)} · +${change.added_lines ?? 0} −${change.removed_lines ?? 0}`;
    const dir = dirName(change.file_path);
    this.description = [dir, stats, STATUS_LABEL[status] ?? status].filter(Boolean).join(" · ");
    this.contextValue = `fluxChange.${status}`;
    this.iconPath = new vscode.ThemeIcon(iconFor(status), new vscode.ThemeColor(STATUS_COLOR[status] ?? "flux.muted"));
    const tooltip = new vscode.MarkdownString(
      [
        `**${baseName(change.file_path)}**`,
        `\`${change.file_path}\``,
        "",
        `${stats} · ${STATUS_LABEL[status] ?? status}`,
        change.summary || change.reason ? "" : undefined,
        change.summary ? `> ${change.summary}` : undefined,
        change.reason ? `原因：${change.reason}` : undefined,
      ]
        .filter((line) => line !== undefined)
        .join("\n\n")
    );
    tooltip.isTrusted = false;
    this.tooltip = tooltip;
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

function dirName(path: string): string {
  const parts = path.split(/[\\/]/).filter(Boolean);
  return parts.slice(0, -1).slice(-2).join("/");
}

function iconFor(status: string): string {
  switch (status) {
    case "accepted":
      return "check";
    case "applying":
      return "sync~spin";
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

const SORT_ORDER = ["pending", "accepted", "applied", "rolled_back"];

export class ChangesProvider extends StatefulProvider<ChangeNode> {
  constructor(private readonly client: () => FluxClient, private readonly projectId: () => string) {
    super();
  }

  async getChildren(): Promise<ChangeNode[]> {
    const client = this.client();
    const projectId = this.projectId() || undefined;
    const results = await Promise.allSettled(
      ["pending", "accepted", "applied", "rolled_back"].map((status) => client.changes(status, projectId))
    );
    const failed = results.filter((r): r is PromiseRejectedResult => r.status === "rejected");
    if (failed.length === results.length) {
      this.setState({ count: 0, description: undefined, message: `无法读取提案：${(failed[0].reason as Error).message}` });
      return [];
    }
    const nodes = results
      .flatMap((r) => (r.status === "fulfilled" ? r.value : []))
      .sort((a, b) => {
        const left = SORT_ORDER.indexOf(a.status ?? "pending");
        const right = SORT_ORDER.indexOf(b.status ?? "pending");
        if (left !== right) {
          return (left < 0 ? 99 : left) - (right < 0 ? 99 : right);
        }
        return String(b.updated_at ?? "").localeCompare(String(a.updated_at ?? ""));
      })
      .map((change) => new ChangeNode(change));
    const pending = nodes.filter((n) => (n.change.status ?? "pending") === "pending").length;
    this.setState({
      count: pending,
      description: pending ? `${pending} 待审` : nodes.length ? `${nodes.length}` : undefined,
      message: failed.length ? "部分提案读取失败，可尝试刷新" : undefined,
    });
    return nodes;
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
    const [changeId, side] = uri.path.split("/").filter(Boolean);
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

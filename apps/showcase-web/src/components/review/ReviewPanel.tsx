/**
 * 变更审核侧栏面板：按变更集分组的提案队列。
 * 点击一条提案会在编辑器里打开对应的 diff；这里只负责浏览与整组操作。
 */
import { useMemo } from "react";
import { Icon } from "../ui/Icon";
import { Badge } from "../ui/primitives";
import { statusLabel, statusTone, type ReviewChange } from "../../data/review";

const KIND_ICON: Record<string, string> = { create: "plus", modify: "edit", delete: "trash" };

interface Props {
  changes: ReviewChange[];
  selected: string | null;
  onPick: (id: string) => void;
  onGroupAction: (group_id: string, action: "accept" | "apply" | "reject") => void;
}

function shortName(p: string): string {
  return p.split("/").slice(-1)[0];
}

/** 单个变更集分组：标题 + 整组动作 + 成员列表。 */
function Group({ changes, selected, onPick, onGroupAction }: Props & { changes: ReviewChange[] }) {
  const first = changes[0];
  const pending = changes.filter((c) => c.status === "pending" || c.status === "accepted").length;
  return (
    <div className="rvp-group">
      <div className="rvp-group-head">
        <div className="rvp-group-title ellipsis">
          <Icon name="diff" className="icon-sm" />
          {first.summary}
        </div>
        <div className="rvp-group-sub">
          <span className="mono">{first.task_id}</span>
          <span className="dim">{first.agent_source} · {changes.length} 文件</span>
          <span className="grow" />
          {pending > 0 ? (
            <span className="rvp-group-acts">
              <button onClick={() => onGroupAction(first.group_id, "accept")} title="整组接受">接受</button>
              <button onClick={() => onGroupAction(first.group_id, "apply")} title="整组落盘">落盘</button>
              <button onClick={() => onGroupAction(first.group_id, "reject")} title="整组拒绝">拒绝</button>
            </span>
          ) : null}
        </div>
      </div>
      {changes.map((c) => (
        <div key={c.id} className={["vw-file", c.id === selected && "active"].filter(Boolean).join(" ")} onClick={() => onPick(c.id)}>
          <Icon name={KIND_ICON[c.kind]} className="icon-sm" style={{ color: "var(--text-3)" }} />
          <span className="mono ellipsis grow" style={{ fontSize: 11.5 }} title={c.file_path}>
            {shortName(c.file_path)}
          </span>
          <span className="stat-add">+{c.added_lines}</span>
          <span className="stat-del">-{c.removed_lines}</span>
          <Badge tone={statusTone(c.status)}>{statusLabel(c.status)}</Badge>
        </div>
      ))}
    </div>
  );
}

export function ReviewPanel(props: Props) {
  const groups = useMemo(() => {
    const map = new Map<string, ReviewChange[]>();
    for (const c of props.changes) {
      const arr = map.get(c.group_id) ?? [];
      arr.push(c);
      map.set(c.group_id, arr);
    }
    return [...map.values()];
  }, [props.changes]);

  const pending = props.changes.filter((c) => c.status === "pending").length;
  const attention = props.changes.filter((c) => c.status === "failed").length;

  return (
    <div className="rvp">
      <div className="ide-colhead">
        变更审核
        <span className="row gap-2">
          <Badge tone="orange">{pending} 待审</Badge>
          {attention > 0 ? <Badge tone="red">{attention} 待处理</Badge> : null}
        </span>
      </div>
      <div className="rvp-scroll">
        {groups.map((g) => (
          <Group key={g[0].group_id} {...props} changes={g} />
        ))}
      </div>
      <div className="ide-colfoot">
        <div className="rvp-foot">
          <Icon name="shield" className="icon-sm" />
          应用前不落盘 · 落盘经测试门禁 · 失败可回滚
        </div>
      </div>
    </div>
  );
}

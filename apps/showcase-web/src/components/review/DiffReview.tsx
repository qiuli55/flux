/**
 * 变更审核的编辑器视图：展示单条提案的 diff、生命周期与可执行动作。
 *
 * 动作语义严格对齐后端：接受(accept)只改状态；落盘(apply)才写盘并跑一次测试门禁；
 * 拒绝(reject)是终态；回滚(rollback)仅对 applied 生效；崩溃恢复项需要 cover/keep 决策。
 */
import type { ReactNode } from "react";
import { Icon } from "../ui/Icon";
import { Badge, Button } from "../ui/primitives";
import { DiffBody } from "./DiffView";
import { statusLabel, statusTone, setTitle, type ChangeStatus, type RecoveryItem, type ReviewChange } from "../../data/review";

const KIND_META: Record<string, { icon: string; label: string }> = {
  create: { icon: "plus", label: "新增" },
  modify: { icon: "edit", label: "修改" },
  delete: { icon: "trash", label: "删除" },
};

interface Props {
  change: ReviewChange;
  recovery?: RecoveryItem;
  busy: boolean;
  onAccept: () => void;
  onApply: () => void;
  onReject: () => void;
  onRollback: () => void;
  onResolveRecovery: (action: "cover" | "keep") => void;
}

/** 顶部：文件、类型、状态、增删统计。 */
function Head({ change }: { change: ReviewChange }) {
  const km = KIND_META[change.kind];
  return (
    <div className="rv-head">
      <span className="rv-kind">
        <Icon name={km.icon} className="icon-sm" />
        {km.label}
      </span>
      <span className="mono ellipsis rv-path">{change.file_path}</span>
      <span className="grow" />
      <span className="stat-add">+{change.added_lines}</span>
      <span className="stat-del">-{change.removed_lines}</span>
      <Badge tone={statusTone(change.status)} dot>
        {statusLabel(change.status)}
      </Badge>
    </div>
  );
}

/** 生命周期进度条：待审 → 已接受 → 落盘(测试门禁) → 已提交。 */
function Lifecycle({ status }: { status: ChangeStatus }) {
  const idx =
    status === "pending" ? 0 : status === "accepted" || status === "applying" ? 1 : status === "applied" ? 2 : -1;
  const steps = ["待审", "已接受", "落盘 · 测试门禁", "可提交"];
  return (
    <div className="rv-life">
      {steps.map((s, i) => (
        <div key={s} className={["rv-step", idx >= i && idx >= 0 ? "done" : "", idx === i ? "now" : ""].filter(Boolean).join(" ")}>
          <i className="dot" />
          {s}
        </div>
      ))}
      {idx < 0 ? <span className="rv-life-note">{statusLabel(status)}（终态）</span> : null}
    </div>
  );
}

/** 元信息：任务、Agent、原因、摘要。 */
function Meta({ change }: { change: ReviewChange }) {
  const rows: [string, string][] = [
    ["来源 Agent", change.agent_source],
    ["所属任务", change.task_id],
    ["变更集", setTitle(change.group_id)],
    ["改动原因", change.reason],
  ];
  return (
    <div className="rv-meta">
      {rows.map(([k, v]) => (
        <div className="rv-meta-row" key={k}>
          <span className="k">{k}</span>
          <span className="v">{v}</span>
        </div>
      ))}
    </div>
  );
}

/** 底部动作区：按状态给出合法动作（与后端状态机一致）。 */
function Actions({ change, busy, onAccept, onApply, onReject, onRollback }: Pick<Props, "change" | "busy" | "onAccept" | "onApply" | "onReject" | "onRollback">) {
  if (change.status === "pending") {
    return (
      <div className="rv-actions">
        <Button variant="primary" icon="check" onClick={onApply} disabled={busy}>
          接受并落盘（跑测试）
        </Button>
        <Button variant="ghost" icon="check" onClick={onAccept} disabled={busy}>
          仅接受
        </Button>
        <Button variant="ghost" icon="close" onClick={onReject} disabled={busy}>
          拒绝
        </Button>
        <span className="grow" />
        <span className="dim">接受不写盘；落盘才会改文件并跑门禁</span>
      </div>
    );
  }
  if (change.status === "accepted" || change.status === "applying") {
    return (
      <div className="rv-actions">
        <Button variant="primary" icon="download" onClick={onApply} disabled={busy}>
          {change.status === "applying" ? "落盘中…" : "落盘（跑测试门禁）"}
        </Button>
        <Button variant="ghost" icon="close" onClick={onReject} disabled={busy}>
          拒绝
        </Button>
      </div>
    );
  }
  if (change.status === "applied") {
    return (
      <div className="rv-actions">
        <Button variant="ghost" icon="refresh" onClick={onRollback} disabled={busy}>
          回滚此文件
        </Button>
        <span className="grow" />
        <span className="rv-ok">
          <Icon name="check" className="icon-sm" /> 已落盘，测试通过 · 可在版本控制里提交
        </span>
      </div>
    );
  }
  return (
    <div className="rv-actions">
      <span className="dim">该提案已是终态（{statusLabel(change.status)}），要再改同一文件需重新生成提案。</span>
    </div>
  );
}

/** 失败详情 + 崩溃恢复三版本对照与 cover/keep 决策。 */
function Failure({ change, recovery, busy, onResolveRecovery }: Pick<Props, "change" | "recovery" | "busy" | "onResolveRecovery">) {
  if (change.status !== "failed") return null;
  return (
    <div className="rv-fail">
      <div className="rv-fail-head">
        <Icon name="shield" className="icon-sm" />
        落盘未完成：{change.apply_error?.split("；")[0] ?? "Apply 失败"}
      </div>
      <div className="rv-fail-msg">{change.apply_error}</div>
      {recovery ? (
        <div className="rv-recover">
          <div className="rv-version">
            {[
              ["改动前原文", recovery.original_content],
              ["当前磁盘", recovery.disk_content],
              ["提案内容", recovery.proposed_content],
            ].map(([k, v]) => (
              <div className="rv-ver" key={k}>
                <div className="rv-version-cap">{k}</div>
                <pre>{v}</pre>
              </div>
            ))}
          </div>
          <div className="rv-actions">
            <Button variant="primary" icon="download" onClick={() => onResolveRecovery("cover")} disabled={busy}>
              用备份覆盖（回到改动前）
            </Button>
            <Button variant="ghost" onClick={() => onResolveRecovery("keep")} disabled={busy}>
              保留磁盘现状（作废提案）
            </Button>
            <span className="grow" />
            <span className="dim">备份在 {change.backup_path ? ".flux/backups" : "—"}</span>
          </div>
        </div>
      ) : null}
    </div>
  );
}

export function DiffReview(props: Props): ReactNode {
  const { change, recovery, busy } = props;
  return (
    <div className="rv">
      <Head change={change} />
      <Lifecycle status={change.status} />
      <Meta change={change} />
      <Failure change={change} recovery={recovery} busy={busy} onResolveRecovery={props.onResolveRecovery} />
      <div className="diff rv-diff">
        <DiffBody diff={change.diff} />
      </div>
      <Actions
        change={change}
        busy={busy}
        onAccept={props.onAccept}
        onApply={props.onApply}
        onReject={props.onReject}
        onRollback={props.onRollback}
      />
    </div>
  );
}

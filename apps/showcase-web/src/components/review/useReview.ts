/**
 * 变更审核的状态管理：在演示里模拟后端的完整生命周期。
 *
 * 动作-状态映射严格对齐后端状态机：
 * accept: pending → accepted（不写盘）；apply: pending/accepted → applying → applied（落盘 + 测试门禁）；
 * reject/expire: → 终态；rollback: applied → rolled_back；recovery: failed 上做 cover/keep 决策。
 */
import { useCallback, useState } from "react";
import { useToast } from "../../app/Toast";
import {
  applyBatches as seedBatches,
  recoveryItems,
  reviewChanges as seedChanges,
  type ApplyBatch,
  type ReviewChange,
} from "../../data/review";

let batchSeq = 9100;

export function useReview() {
  const [changes, setChanges] = useState<ReviewChange[]>(seedChanges);
  const [batches, setBatches] = useState<ApplyBatch[]>(seedBatches);
  const [busyIds, setBusyIds] = useState<string[]>([]);
  const toast = useToast();

  const patch = useCallback((ids: string[], fn: (c: ReviewChange) => Partial<ReviewChange>) => {
    setChanges((prev) => prev.map((c) => (ids.includes(c.id) ? { ...c, ...fn(c) } : c)));
  }, []);

  const accept = useCallback(
    (ids: string[]) => {
      patch(ids, () => ({ status: "accepted" }));
      toast(`已接受 ${ids.length} 条提案（仅改状态，未写盘）`);
    },
    [patch, toast],
  );

  const reject = useCallback(
    (ids: string[]) => {
      patch(ids, () => ({ status: "rejected" }));
      toast(`已拒绝 ${ids.length} 条提案`);
    },
    [patch, toast],
  );

  const apply = useCallback(
    (ids: string[]) => {
      const bid = `b-${++batchSeq}`;
      setBusyIds(ids);
      patch(ids, () => ({ status: "applying" }));
      setBatches((prev) => [
        { id: bid, status: "in_progress", phase: "writing", change_ids: ids, backup_root: ".flux/backups", created_at: "刚刚" },
        ...prev,
      ]);
      window.setTimeout(() => {
        patch(ids, () => ({ status: "applied", backup_path: `.flux/backups/${bid}` }));
        setBatches((prev) =>
          prev.map((b) => (b.id === bid ? { ...b, status: "applied", phase: "done", finished_at: "刚刚" } : b)),
        );
        setBusyIds([]);
        toast(`落盘 ${ids.length} 条提案：已备份 → 写入 → 校验 → 测试门禁通过`);
      }, 1400);
    },
    [patch, toast],
  );

  const rollback = useCallback(
    (id: string) => {
      patch([id], () => ({ status: "rolled_back" }));
      setBatches((prev) =>
        prev.map((b) => (b.change_ids.includes(id) && b.status === "applied" ? { ...b, status: "rolled_back" } : b)),
      );
      toast("已回滚：文件从备份还原为改动前");
    },
    [patch, toast],
  );

  const rollbackBatch = useCallback(
    (bid: string) => {
      const b = batches.find((x) => x.id === bid);
      if (!b) return;
      patch(b.change_ids, () => ({ status: "rolled_back" }));
      setBatches((prev) => prev.map((x) => (x.id === bid ? { ...x, status: "rolled_back" } : x)));
      toast(`已回滚该批次 ${b.change_ids.length} 个文件`);
    },
    [batches, patch, toast],
  );

  const resolveRecovery = useCallback(
    (id: string, action: "cover" | "keep") => {
      if (action === "cover") {
        patch([id], () => ({ status: "accepted", apply_error: undefined, recovery_resolution: "cover" }));
        toast("已用备份覆盖当前内容，提案回到可重试");
      } else {
        patch([id], () => ({
          status: "failed",
          recovery_resolution: "keep",
          apply_error: "崩溃恢复：人工选择保留磁盘现状，提案作废。",
        }));
        toast("已保留磁盘现状，绝不覆盖你的改动，提案作废");
      }
      setBatches((prev) =>
        prev.map((b) => (b.change_ids.includes(id) && b.status === "needs_attention" ? { ...b, status: "recovered" } : b)),
      );
    },
    [patch, toast],
  );

  const groupAction = useCallback(
    (group_id: string, action: "accept" | "apply" | "reject") => {
      const ids = changes
        .filter((c) => c.group_id === group_id && (c.status === "pending" || c.status === "accepted"))
        .map((c) => c.id);
      if (!ids.length) {
        toast("该变更集没有可操作的提案");
        return;
      }
      if (action === "accept") accept(ids);
      else if (action === "reject") reject(ids);
      else apply(ids);
    },
    [changes, accept, reject, apply, toast],
  );

  return { changes, batches, busyIds, recoveryItems, accept, apply, reject, rollback, rollbackBatch, resolveRecovery, groupAction };
}

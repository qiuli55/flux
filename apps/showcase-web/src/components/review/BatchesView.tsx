/**
 * 落盘记录：apply 批次事务日志（对应后端 /workspace/apply-batches）。
 * 展示每批的状态与阶段，applied 批次可整批回滚。
 */
import { Icon } from "../ui/Icon";
import { Badge, Button } from "../ui/primitives";
import type { ApplyBatch, BatchStatus } from "../../data/review";

const PHASE_LABEL: Record<string, string> = {
  prepared: "预检",
  backed_up: "已备份",
  writing: "写入",
  verifying: "校验",
  testing: "测试门禁",
  done: "完成",
};

function batchTone(s: BatchStatus): "green" | "red" | "brand" | "orange" | "default" {
  if (s === "applied") return "green";
  if (s === "failed") return "red";
  if (s === "needs_attention") return "orange";
  if (s === "in_progress") return "brand";
  return "default";
}

const BATCH_LABEL: Record<BatchStatus, string> = {
  in_progress: "进行中",
  applied: "已落盘",
  failed: "失败",
  recovered: "已恢复",
  needs_attention: "待处理",
  rolled_back: "已回滚",
};

export function BatchesView({ batches, onRollback }: { batches: ApplyBatch[]; onRollback: (id: string) => void }) {
  return (
    <div className="tool-pane">
      {batches.map((b) => (
        <div className="batch-row" key={b.id}>
          <div className="batch-main">
            <span className="mono batch-id">{b.id}</span>
            <Badge tone={batchTone(b.status)} dot>
              {BATCH_LABEL[b.status]}
            </Badge>
            <span className="dim">阶段：{PHASE_LABEL[b.phase]}</span>
            <span className="dim">· {b.change_ids.length} 文件</span>
            <span className="grow" />
            <span className="dim">{b.created_at}{b.finished_at ? ` → ${b.finished_at}` : ""}</span>
            {b.status === "applied" ? (
              <Button variant="quiet" size="sm" icon="refresh" onClick={() => onRollback(b.id)}>
                回滚
              </Button>
            ) : null}
          </div>
          {b.error ? (
            <div className="batch-err">
              <Icon name="shield" className="icon-sm" />
              {b.error}
            </div>
          ) : null}
        </div>
      ))}
    </div>
  );
}

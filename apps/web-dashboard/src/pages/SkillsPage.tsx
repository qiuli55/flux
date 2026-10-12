/**
 * Skills 页：能力（Agent 档案 / Skill 能力包 / Connector）的查看与导入。
 * 数据来自 /api/v1/capabilities：列表、扫描候选、导入；被安全拦截的候选展示拦截原因且不可导入。
 */
import { useCallback, useEffect, useMemo, useState } from "react";

import { api, ApiError } from "../api/client";
import type { CapabilityCandidate, CapabilityScanReport, ImportedCapability } from "../api/types";
import { toast } from "../app/toast";
import { Icon } from "../shell/Icon";
import { Badge, Button, Card, Empty, PageHead, Segmented } from "../shell/ui";
import { Modal } from "../shell/Overlay";

type Filter = "all" | "agent" | "skill" | "connector";

const KIND_META: Record<string, { label: string; icon: string; tone: "brand" | "blue" | "green" }> = {
  agent: { label: "Agent 档案", icon: "bot", tone: "brand" },
  skill: { label: "Skill 能力包", icon: "puzzle", tone: "blue" },
  connector: { label: "Connector", icon: "plug", tone: "green" },
};

const metaOf = (kind: string) => KIND_META[kind] ?? { label: kind, icon: "package", tone: "blue" as const };

/* ---------------- 已导入卡片 ---------------- */
function CapCard({ cap }: { cap: ImportedCapability }) {
  const meta = metaOf(cap.kind);
  return (
    <Card pad hover>
      <div className="fx-mc-top">
        <span className={["fx-mc-icon", "fx-g"].join(" ")}><Icon name={meta.icon} /></span>
        <div className="fx-grow">
          <div className="fx-mc-name fx-ellipsis">{cap.name}</div>
          <div className="fx-dim" style={{ fontSize: 11 }}>{meta.label}{cap.version ? ` · v${cap.version}` : ""}</div>
        </div>
      </div>
      <div className="fx-mc-desc fx-ellipsis" title={cap.source}>{cap.source}</div>
      <div className="fx-mc-foot">
        <Badge tone={meta.tone}>{cap.kind}</Badge>
        <span className="fx-dim" style={{ fontSize: 11, marginLeft: "auto" }}>{cap.updated_at ? new Date(cap.updated_at).toLocaleDateString() : "—"}</span>
      </div>
    </Card>
  );
}

/* ---------------- 扫描候选行 ---------------- */
function CandidateRow({ c, onImport }: { c: CapabilityCandidate; onImport: (c: CapabilityCandidate) => void }) {
  const blocked = c.status === "blocked";
  return (
    <div className="fx-set-row">
      <div className="fx-info">
        <div className="fx-t">{c.name} <span className="fx-dim">· {metaOf(c.kind).label}</span></div>
        <div className="fx-d">{c.source}</div>
        {blocked && c.findings.length > 0 ? (
          <div className="fx-d" style={{ color: "var(--fx-red)" }}>拦截：{c.findings.map((f) => f.reason).join("；")}</div>
        ) : null}
      </div>
      <div className="fx-ctl">
        {blocked ? <Badge tone="red">已拦截</Badge> : <Button size="sm" variant="subtle" icon="download" onClick={() => onImport(c)}>导入</Button>}
      </div>
    </div>
  );
}

/* ---------------- 页面 ---------------- */
export function SkillsPage() {
  const [filter, setFilter] = useState<Filter>("all");
  const [caps, setCaps] = useState<ImportedCapability[]>([]);
  const [report, setReport] = useState<CapabilityScanReport | null>(null);
  const [scanning, setScanning] = useState(false);
  const [loading, setLoading] = useState(true);

  const load = useCallback(async () => {
    setLoading(true);
    try { setCaps(await api.listCapabilities()); }
    catch (e) { toast(e instanceof ApiError ? e.message : "加载能力列表失败", "error"); }
    finally { setLoading(false); }
  }, []);

  useEffect(() => { void load(); }, [load]);

  const scan = useCallback(async () => {
    setScanning(true);
    try { setReport(await api.scanCapabilities()); }
    catch (e) { toast(e instanceof ApiError ? e.message : "扫描失败", "error"); }
    finally { setScanning(false); }
  }, []);

  const doImport = useCallback(async (c: CapabilityCandidate) => {
    try {
      const out = await api.importCapability({ kind: c.kind, name: c.name });
      toast(`已导入「${out.name}」（${out.outcome}）`);
      await load();
    } catch (e) { toast(e instanceof ApiError ? e.message : "导入失败", "error"); }
  }, [load]);

  const shown = useMemo(() => (filter === "all" ? caps : caps.filter((c) => c.kind === filter)), [caps, filter]);

  return (
    <>
      <PageHead title="Skills" desc="集中管理可用能力：Agent 档案、Skill 能力包与 Connector。扫描本机来源目录，安全导入可复用能力。">
        <Button variant="subtle" icon="refresh" onClick={load} busy={loading}>刷新</Button>
        <Button variant="primary" icon="search" onClick={scan} busy={scanning}>扫描导入</Button>
      </PageHead>

      <div className="fx-toolbar">
        <Segmented<Filter>
          value={filter}
          onChange={setFilter}
          options={[
            { value: "all", label: "全部" },
            { value: "agent", label: "Agent" },
            { value: "skill", label: "Skill" },
            { value: "connector", label: "Connector" },
          ]}
        />
        <span className="fx-dim" style={{ marginLeft: "auto", fontSize: 12 }}>共 {shown.length} 项</span>
      </div>

      {shown.length === 0 && !loading ? <Empty icon="puzzle" title="暂无已导入能力" desc="点击「扫描导入」从本机来源目录发现能力" /> : (
        <div className="fx-grid fx-auto">
          {shown.map((c) => <CapCard key={c.id} cap={c} />)}
        </div>
      )}

      <Modal
        open={report !== null}
        large
        title="扫描结果"
        onClose={() => setReport(null)}
        footer={<Button onClick={() => setReport(null)}>完成</Button>}
      >
        {report ? (
          <>
            <div className="fx-row fx-gap-3" style={{ marginBottom: 12 }}>
              <Badge tone="default">候选 {report.summary.total}</Badge>
              <Badge tone="green">可导入 {report.summary.ok}</Badge>
              <Badge tone="red">已拦截 {report.summary.blocked}</Badge>
            </div>
            <div className="fx-col fx-gap-2" style={{ marginBottom: 12 }}>
              {report.sources.map((s) => (
                <div key={s.path} className="fx-row fx-gap-2" style={{ fontSize: 11.5 }}>
                  <Badge tone={s.status === "scanned" ? "green" : s.status === "missing" ? "default" : "orange"}>{s.status}</Badge>
                  <span className="fx-mono fx-dim fx-ellipsis">{s.path}</span>
                  <span className="fx-dim" style={{ marginLeft: "auto" }}>{s.candidates} 项</span>
                </div>
              ))}
            </div>
            {report.candidates.length === 0 ? <Empty icon="search" title="没有发现候选能力" /> : (
              <div>{report.candidates.map((c, i) => <CandidateRow key={`${c.kind}-${c.name}-${i}`} c={c} onImport={doImport} />)}</div>
            )}
          </>
        ) : null}
      </Modal>
    </>
  );
}

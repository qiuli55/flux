/**
 * Solo 右栏功能区：浏览器 / 插件市场 / 连接器市场。
 *
 * 三个 Tab 都是真接口，只有「浏览器」诚实地标注为后续接入：
 * - 插件市场：GET /capabilities 列出已导入能力，POST /capabilities/scan 发现候选，POST /capabilities/import 导入
 * - 连接器市场：GET /installations 列本机 CLI 的安装与接入事实，scan / connect / delete
 *
 * 安装状态由父级（SoloPage）持有，本组件只负责展示与触发，避免两处状态打架。
 */
import { useCallback, useEffect, useState } from "react";

import { ApiError, api } from "../../api/client";
import type { CapabilityCandidate, CapabilityScanReport, ImportedCapability, Installation } from "../../api/types";
import { toast } from "../../app/toast";

type SideTab = "browser" | "plugins" | "connectors";

/** 安装状态 → 徽标文案与配色（与后端 AgentInstallStatus 对齐） */
const INSTALL_STATUS_META: Record<string, { label: string; cls: string }> = {
  READY: { label: "已接入", cls: "b-ok" },
  CONNECTED: { label: "已发现", cls: "b-run" },
  VERIFIED: { label: "已发现", cls: "b-run" },
  DISCOVERED: { label: "已发现", cls: "b-run" },
  NOT_INSTALLED: { label: "未安装", cls: "" },
};

/** 能力 kind → 中文标签 */
const KIND_LABELS: Record<string, string> = {
  agent: "Agent 档案",
  skill: "Skill 能力包",
  connector: "连接器",
};

interface Props {
  installations: Installation[];
  installBusy: string | null;
  onScan: () => void;
  onConnect: (name: string) => void;
  onRemove: (name: string) => void;
  onCreateAgent: (runtime: string) => void;
}

export function SoloSidePanel({
  installations,
  installBusy,
  onScan,
  onConnect,
  onRemove,
  onCreateAgent,
}: Props) {
  const [tab, setTab] = useState<SideTab>("browser");

  // 插件市场
  const [caps, setCaps] = useState<ImportedCapability[]>([]);
  const [capsError, setCapsError] = useState<string | null>(null);
  const [scanning, setScanning] = useState(false);
  const [report, setReport] = useState<CapabilityScanReport | null>(null);
  const [importing, setImporting] = useState<string | null>(null);

  const loadCaps = useCallback(async () => {
    try {
      setCaps(await api.listCapabilities());
      setCapsError(null);
    } catch (error) {
      setCaps([]);
      setCapsError(error instanceof ApiError ? error.message : "加载插件列表失败");
    }
  }, []);

  useEffect(() => {
    void loadCaps();
  }, [loadCaps]);

  const scanCaps = useCallback(async () => {
    setScanning(true);
    try {
      const outcome = await api.scanCapabilities();
      setReport(outcome);
      toast(`扫描完成 · 候选 ${outcome.summary.total} 项（可导入 ${outcome.summary.ok} / 已拦截 ${outcome.summary.blocked}）`);
    } catch (error) {
      toast(error instanceof ApiError ? error.message : "扫描失败", "error");
    } finally {
      setScanning(false);
    }
  }, []);

  const doImport = useCallback(
    async (candidate: CapabilityCandidate) => {
      setImporting(`${candidate.kind}:${candidate.name}`);
      try {
        const outcome = await api.importCapability({ kind: candidate.kind, name: candidate.name });
        toast(`已导入「${outcome.name}」（${outcome.outcome}）`);
        await loadCaps();
      } catch (error) {
        toast(error instanceof ApiError ? error.message : "导入失败", "error");
      } finally {
        setImporting(null);
      }
    },
    [loadCaps],
  );

  return (
    <>
      <div className="side-tabs" role="tablist" aria-label="右侧功能区">
        <button
          type="button"
          role="tab"
          aria-selected={tab === "browser"}
          className={`side-tab${tab === "browser" ? " is-active" : ""}`}
          onClick={() => setTab("browser")}
        >
          浏览器
        </button>
        <button
          type="button"
          role="tab"
          aria-selected={tab === "plugins"}
          className={`side-tab${tab === "plugins" ? " is-active" : ""}`}
          onClick={() => setTab("plugins")}
        >
          插件市场
        </button>
        <button
          type="button"
          role="tab"
          aria-selected={tab === "connectors"}
          className={`side-tab${tab === "connectors" ? " is-active" : ""}`}
          onClick={() => setTab("connectors")}
        >
          连接器市场
        </button>
      </div>

      {tab === "browser" ? (
        <section className="card side-card">
          <div className="side-head">
            <b>内置浏览器</b>
            <span className="chip chip-dim">后续接入</span>
          </div>
          <div className="empty-note">
            这里以后会是一个受控的内置浏览器，用来直接预览当前工作区跑起来的页面、边看边改。
            现在还没有接入浏览器能力，所以先留一个诚实的位置，不做假界面。
          </div>
          <p className="ext-agent-note">
            当下要看效果：用 IDE 的终端跑起服务，再到系统浏览器里打开地址。
          </p>
        </section>
      ) : null}

      {tab === "plugins" ? (
        <section className="card side-card">
          <div className="side-head">
            <b>插件市场</b>
            <button type="button" className="link" disabled={scanning} onClick={() => void scanCaps()}>
              {scanning ? "扫描中…" : "扫描本机"}
            </button>
          </div>
          {capsError ? <div className="empty-note">{capsError}</div> : null}
          {caps.length === 0 ? (
            <div className="empty-note">
              还没有导入任何插件能力。点「扫描本机」会从本机来源目录发现可导入的 Agent 档案 / Skill 能力包 / 连接器。
            </div>
          ) : (
            <ul className="side-list">
              {caps.map((cap) => (
                <li key={cap.id} className="side-item">
                  <div className="si-main">
                    <b>{cap.name}</b>
                    <i>
                      {KIND_LABELS[cap.kind] ?? cap.kind}
                      {cap.version ? ` · v${cap.version}` : ""}
                    </i>
                  </div>
                  <span className="tl-badge b-ok">已导入</span>
                </li>
              ))}
            </ul>
          )}
          {report ? (
            <>
              <div className="side-sub">
                扫描候选 {report.summary.total} 项 · 可导入 {report.summary.ok} · 已拦截 {report.summary.blocked}
              </div>
              {report.candidates.length === 0 ? (
                <div className="empty-note">没有发现候选能力。</div>
              ) : (
                <ul className="side-list">
                  {report.candidates.map((candidate, index) => {
                    const blocked = candidate.status === "blocked";
                    const key = `${candidate.kind}:${candidate.name}:${index}`;
                    return (
                      <li key={key} className="side-item">
                        <div className="si-main">
                          <b>{candidate.name}</b>
                          <i>
                            {KIND_LABELS[candidate.kind] ?? candidate.kind}
                            {blocked && candidate.findings.length > 0
                              ? ` · 拦截：${candidate.findings.map((f) => f.reason).join("；")}`
                              : ""}
                          </i>
                        </div>
                        {blocked ? (
                          <span className="tl-badge">已拦截</span>
                        ) : (
                          <button
                            type="button"
                            className="btn btn-xs btn-primary"
                            disabled={importing === `${candidate.kind}:${candidate.name}`}
                            onClick={() => void doImport(candidate)}
                          >
                            {importing === `${candidate.kind}:${candidate.name}` ? "导入中…" : "导入"}
                          </button>
                        )}
                      </li>
                    );
                  })}
                </ul>
              )}
            </>
          ) : null}
        </section>
      ) : null}

      {tab === "connectors" ? (
        <section className="card side-card">
          <div className="side-head">
            <b>连接器市场</b>
            <button type="button" className="link" disabled={installBusy === "scan"} onClick={onScan}>
              {installBusy === "scan" ? "扫描中…" : "扫描本机"}
            </button>
          </div>
          {installations.length === 0 ? (
            <div className="empty-note">
              还没扫描过本机 CLI。点「扫描本机」会发现 codex / opencode 的安装与凭据状态。
            </div>
          ) : (
            <ul className="side-list">
              {installations.map((item) => {
                const meta = INSTALL_STATUS_META[item.status] ?? { label: item.status, cls: "" };
                const ready = item.status === "READY";
                const installed = item.status !== "NOT_INSTALLED";
                return (
                  <li key={item.id} className="side-item">
                    <span className="tm-ava a3">{item.name.slice(0, 2).toUpperCase()}</span>
                    <div className="si-main">
                      <b>
                        {item.name} <span className="chip chip-dim">CLI</span>
                      </b>
                      <i>
                        {item.version ? `v${item.version} · ` : ""}
                        {item.path ?? "未找到可执行文件"}
                        {item.auth_status === "ok"
                          ? " · 凭据 OK"
                          : item.auth_status === "missing"
                            ? " · 凭据缺失"
                            : ""}
                      </i>
                    </div>
                    <span className={`tl-badge ${meta.cls}`}>{meta.label}</span>
                    {ready ? (
                      <span className="ext-agent-actions">
                        <button
                          type="button"
                          className="btn btn-xs btn-primary"
                          disabled={installBusy === `agent:${item.name}`}
                          onClick={() => onCreateAgent(item.name)}
                          title={`创建一个 runtime=${item.name} 的 Agent 档案`}
                        >
                          {installBusy === `agent:${item.name}` ? "创建中…" : "建 Agent"}
                        </button>
                        <button
                          type="button"
                          className="btn btn-xs btn-ghost"
                          disabled={installBusy === item.name}
                          onClick={() => onRemove(item.name)}
                        >
                          移除
                        </button>
                      </span>
                    ) : installed ? (
                      <button
                        type="button"
                        className="btn btn-xs btn-primary"
                        disabled={installBusy === item.name}
                        onClick={() => onConnect(item.name)}
                      >
                        {installBusy === item.name ? "接入中…" : "接入"}
                      </button>
                    ) : null}
                  </li>
                );
              })}
            </ul>
          )}
          <p className="ext-agent-note">
            接入 = 本机具备该 CLI 且验证通过；任务要真正走它，还需建一个该 runtime 的 Agent。
          </p>
        </section>
      ) : null}
    </>
  );
}

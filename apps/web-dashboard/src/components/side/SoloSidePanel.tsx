/**
 * Solo 右栏：一个「浏览器感」的功能区。
 *
 * 顶部是标签页 + 前进/后退/刷新/主页导航（每个标签页各自记一份浏览历史），下面是当前页面：
 * 新标签页（小图标磁贴网格）/ 内置浏览器（诚实占位）/ 插件市场 / 扫描结果 / 插件详情 /
 * 连接器市场 / 连接器详情。点磁贴或列表项 = 在当前标签页里跳转，前进后退可来回走，＋ 开新标签页。
 *
 * 数据全是真接口：GET /capabilities · POST /capabilities/scan|import · GET /installations（scan/connect/delete）。
 * 本机 CLI 的接入状态由父级（SoloPage）持有，这里只负责展示与触发。
 */
import { useCallback, useEffect, useMemo, useRef, useState } from "react";

import { ApiError, api } from "../../api/client";
import type {
  CapabilityCandidate,
  CapabilityScanReport,
  ImportedCapability,
  Installation,
} from "../../api/types";
import { toast } from "../../app/toast";
import { Icon } from "../../shell/Icon";

type PageKind = "newtab" | "browser" | "plugins" | "connectors" | "capability" | "connector" | "scan";

interface SidePage {
  /** 页面的唯一标识：同一 key 不会在历史里重复出现 */
  key: string;
  kind: PageKind;
  title: string;
  /** 插件详情定位 */
  capId?: string;
  /** 连接器详情定位 */
  instName?: string;
}

interface SideTab {
  id: string;
  /** 这个标签页自己的浏览历史 */
  pages: SidePage[];
  /** 当前停在历史里的第几条 */
  index: number;
}

const NEWTAB: SidePage = { key: "newtab", kind: "newtab", title: "新标签页" };
const HOME_BROWSER: SidePage = { key: "browser", kind: "browser", title: "内置浏览器" };
const HOME_PLUGINS: SidePage = { key: "plugins", kind: "plugins", title: "插件市场" };
const HOME_CONNECTORS: SidePage = { key: "connectors", kind: "connectors", title: "连接器市场" };
const SCAN_PAGE: SidePage = { key: "scan", kind: "scan", title: "扫描结果" };

/** 地址栏里显示的伪 URL：只给「浏览器感」一个落点，不是真的网络地址 */
function pageUrl(page: SidePage): string {
  if (page.kind === "newtab") return "flux://newtab";
  if (page.kind === "capability") return `flux://solo/plugins/${page.capId ?? ""}`;
  if (page.kind === "connector") return `flux://solo/connectors/${page.instName ?? ""}`;
  if (page.kind === "scan") return "flux://solo/plugins/scan";
  return `flux://solo/${page.kind}`;
}

/** 页面类型 → 磁贴/列表用的小图标（复用项目自带的线条图标库） */
function iconOf(kind: PageKind): string {
  if (kind === "newtab") return "grid";
  if (kind === "browser") return "globe";
  if (kind === "plugins") return "store";
  if (kind === "scan") return "search";
  if (kind === "capability") return "puzzle";
  return "plug";
}

/** 新标签页上的固定磁贴（常用站点） */
const SITE_TILES: SidePage[] = [HOME_BROWSER, HOME_PLUGINS, HOME_CONNECTORS, SCAN_PAGE];

const KIND_LABELS: Record<string, string> = {
  agent: "Agent 档案",
  skill: "Skill 能力包",
  connector: "连接器",
};

/** 安装状态 → 徽标文案与配色（与后端 AgentInstallStatus 对齐） */
const INSTALL_STATUS_META: Record<string, { label: string; cls: string }> = {
  READY: { label: "已接入", cls: "b-ok" },
  CONNECTED: { label: "已发现", cls: "b-run" },
  VERIFIED: { label: "已发现", cls: "b-run" },
  DISCOVERED: { label: "已发现", cls: "b-run" },
  NOT_INSTALLED: { label: "未安装", cls: "" },
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
  const [tabs, setTabs] = useState<SideTab[]>([{ id: "tab-1", pages: [NEWTAB], index: 0 }]);
  const [activeId, setActiveId] = useState("tab-1");
  const nextTab = useRef(2);
  /** 最近访问过的页面（新标签页上的磁贴来源，按访问顺序倒排，最多 8 个） */
  const [recents, setRecents] = useState<SidePage[]>([]);

  // 插件市场
  const [caps, setCaps] = useState<ImportedCapability[]>([]);
  const [capsError, setCapsError] = useState<string | null>(null);
  const [scanning, setScanning] = useState(false);
  const [report, setReport] = useState<CapabilityScanReport | null>(null);
  const [importing, setImporting] = useState<string | null>(null);

  const activeTab = tabs.find((tab) => tab.id === activeId) ?? tabs[0];
  const current: SidePage = activeTab?.pages[activeTab.index] ?? HOME_BROWSER;
  const canBack = Boolean(activeTab && activeTab.index > 0);
  const canForward = Boolean(activeTab && activeTab.index < activeTab.pages.length - 1);

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

  /* ---------- 导航：一个标签页 = 一份历史 + 一个游标 ---------- */

  const openPage = useCallback(
    (page: SidePage) => {
      setTabs((prev) =>
        prev.map((tab) => {
          if (tab.id !== activeId) return tab;
          const cur = tab.pages[tab.index];
          if (cur && cur.key === page.key) return tab;
          const pages = [...tab.pages.slice(0, tab.index + 1), page];
          return { ...tab, pages, index: pages.length - 1 };
        }),
      );
      if (page.kind !== "newtab") {
        setRecents((prev) => [page, ...prev.filter((item) => item.key !== page.key)].slice(0, 8));
      }
    },
    [activeId],
  );

  const goBack = useCallback(() => {
    setTabs((prev) => prev.map((tab) => (tab.id === activeId && tab.index > 0 ? { ...tab, index: tab.index - 1 } : tab)));
  }, [activeId]);

  const goForward = useCallback(() => {
    setTabs((prev) =>
      prev.map((tab) =>
        tab.id === activeId && tab.index < tab.pages.length - 1 ? { ...tab, index: tab.index + 1 } : tab,
      ),
    );
  }, [activeId]);

  const newTab = useCallback(() => {
    const id = `tab-${nextTab.current++}`;
    setTabs((prev) => [...prev, { id, pages: [NEWTAB], index: 0 }]);
    setActiveId(id);
  }, []);

  const closeTab = useCallback(
    (id: string) => {
      const at = tabs.findIndex((tab) => tab.id === id);
      const rest = tabs.filter((tab) => tab.id !== id);
      if (rest.length === 0) {
        const fresh: SideTab = { id: `tab-${nextTab.current++}`, pages: [NEWTAB], index: 0 };
        setTabs([fresh]);
        setActiveId(fresh.id);
        return;
      }
      setTabs(rest);
      if (id === activeId) {
        const neighbor = rest[Math.min(at, rest.length - 1)];
        if (neighbor) setActiveId(neighbor.id);
      }
    },
    [tabs, activeId],
  );

  /** 刷新当前页面：插件相关重拉列表，扫描页重跑扫描，连接器相关回到父级扫描 */
  const refreshPage = useCallback(() => {
    if (current.kind === "scan") {
      void scanCaps();
      return;
    }
    if (current.kind === "connectors" || current.kind === "connector") {
      onScan();
      return;
    }
    void loadCaps();
  }, [current.kind, scanCaps, loadCaps, onScan]);

  const currentCap = useMemo(
    () => (current.kind === "capability" ? caps.find((cap) => cap.id === current.capId) ?? null : null),
    [current, caps],
  );
  const currentInst = useMemo(
    () => (current.kind === "connector" ? installations.find((item) => item.name === current.instName) ?? null : null),
    [current, installations],
  );

  /* ---------- 页面 ---------- */

  /** 磁贴：一个小图标 + 名称，点它在当前标签页里跳转 */
  const tile = (page: SidePage) => (
    <button
      key={page.key}
      type="button"
      className="sb-tile"
      title={pageUrl(page)}
      onClick={() => openPage(page)}
    >
      <span className="sb-tile-ic">
        <Icon name={iconOf(page.kind)} />
      </span>
      <span className="sb-tile-label">{page.title}</span>
    </button>
  );

  /** 新标签页（默认页）：小图标磁贴网格 —— 常用站点 + 最近访问 */
  const newtabPage = (
    <section className="card side-card sb-home">
      <div className="sb-sec">常用站点</div>
      <div className="sb-tiles">{SITE_TILES.map(tile)}</div>
      <div className="sb-sec">最近访问</div>
      {recents.length === 0 ? (
        <div className="empty-note">还没访问过页面：点上面的磁贴开始，去过的页面会出现在这里。</div>
      ) : (
        <div className="sb-tiles">{recents.map(tile)}</div>
      )}
    </section>
  );

  const browserPage = (
    <section className="card side-card">
      <div className="side-head">
        <b>内置浏览器</b>
        <span className="chip chip-dim">后续接入</span>
      </div>
      <div className="empty-note">
        这里以后会是一个受控的内置浏览器，用来直接预览当前工作区跑起来的页面、边看边改。
        现在还没有接入浏览器能力，所以先留一个诚实的位置，不做假界面。
      </div>
      <p className="ext-agent-note">当下要看效果：用 IDE 的终端跑起服务，再到系统浏览器里打开地址。</p>
    </section>
  );

  const pluginsPage = (
    <section className="card side-card">
      <div className="side-head">
        <b>插件市场</b>
        <button
          type="button"
          className="link"
          disabled={scanning}
          onClick={() => {
            openPage(SCAN_PAGE);
            void scanCaps();
          }}
        >
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
            <li key={cap.id}>
              <button
                type="button"
                className="side-item is-link"
                onClick={() => openPage({ key: `cap:${cap.id}`, kind: "capability", title: cap.name, capId: cap.id })}
              >
                <div className="si-main">
                  <b>{cap.name}</b>
                  <i>
                    {KIND_LABELS[cap.kind] ?? cap.kind}
                    {cap.version ? ` · v${cap.version}` : ""}
                  </i>
                </div>
                <span className="tl-badge b-ok">已导入</span>
              </button>
            </li>
          ))}
        </ul>
      )}
    </section>
  );

  const scanPage = (
    <section className="card side-card">
      <div className="side-crumb">
        <button type="button" className="link" onClick={() => openPage(HOME_PLUGINS)}>
          ‹ 插件市场
        </button>
      </div>
      <div className="side-head">
        <b>扫描结果</b>
        <button type="button" className="link" disabled={scanning} onClick={() => void scanCaps()}>
          {scanning ? "扫描中…" : "重新扫描"}
        </button>
      </div>
      {report ? (
        <>
          <div className="side-sub">
            候选 {report.summary.total} 项 · 可导入 {report.summary.ok} · 已拦截 {report.summary.blocked}
          </div>
          {report.candidates.length === 0 ? (
            <div className="empty-note">没有发现候选能力。</div>
          ) : (
            <ul className="side-list">
              {report.candidates.map((candidate, index) => {
                const blocked = candidate.status === "blocked";
                const busy = importing === `${candidate.kind}:${candidate.name}`;
                return (
                  <li key={`${candidate.kind}:${candidate.name}:${index}`} className="side-item">
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
                        disabled={busy}
                        onClick={() => void doImport(candidate)}
                      >
                        {busy ? "导入中…" : "导入"}
                      </button>
                    )}
                  </li>
                );
              })}
            </ul>
          )}
        </>
      ) : (
        <div className="empty-note">{scanning ? "正在扫描本机来源目录…" : "还没有扫描结果。"}</div>
      )}
    </section>
  );

  const capabilityPage = (
    <section className="card side-card">
      <div className="side-crumb">
        <button type="button" className="link" onClick={() => openPage(HOME_PLUGINS)}>
          ‹ 插件市场
        </button>
      </div>
      <div className="side-head">
        <b>{currentCap?.name ?? current.title}</b>
        <span className="tl-badge b-ok">已导入</span>
      </div>
      {currentCap ? (
        <>
          <div className="side-kv">
            <span>类型</span>
            <b>{KIND_LABELS[currentCap.kind] ?? currentCap.kind}</b>
          </div>
          <div className="side-kv">
            <span>版本</span>
            <b>{currentCap.version ?? "—"}</b>
          </div>
          <div className="side-kv">
            <span>来源</span>
            <b title={currentCap.source}>{currentCap.source}</b>
          </div>
          <div className="side-kv">
            <span>指纹</span>
            <b title={currentCap.fingerprint}>{currentCap.fingerprint.slice(0, 16)}</b>
          </div>
          <div className="side-kv">
            <span>更新</span>
            <b>{currentCap.updated_at ?? "—"}</b>
          </div>
          <div className="side-sub">能力规格</div>
          <pre className="side-code">{JSON.stringify(currentCap.spec, null, 2)}</pre>
        </>
      ) : (
        <div className="empty-note">这条能力已经不在列表里了（可能被移除或改名）。</div>
      )}
    </section>
  );

  const connectorsPage = (
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
            return (
              <li key={item.id}>
                <button
                  type="button"
                  className="side-item is-link"
                  onClick={() =>
                    openPage({ key: `inst:${item.name}`, kind: "connector", title: item.name, instName: item.name })
                  }
                >
                  <span className="tm-ava a3">{item.name.slice(0, 2).toUpperCase()}</span>
                  <div className="si-main">
                    <b>{item.name}</b>
                    <i>
                      {item.version ? `v${item.version} · ` : ""}
                      {item.auth_status === "ok" ? "凭据 OK" : item.auth_status === "missing" ? "凭据缺失" : "凭据未知"}
                    </i>
                  </div>
                  <span className={`tl-badge ${meta.cls}`}>{meta.label}</span>
                </button>
              </li>
            );
          })}
        </ul>
      )}
      <p className="ext-agent-note">接入 = 本机具备该 CLI 且验证通过；任务要真正走它，还需建一个该 runtime 的 Agent。</p>
    </section>
  );

  const connectorPage = (
    <section className="card side-card">
      <div className="side-crumb">
        <button type="button" className="link" onClick={() => openPage(HOME_CONNECTORS)}>
          ‹ 连接器市场
        </button>
      </div>
      <div className="side-head">
        <b>{currentInst?.name ?? current.title}</b>
        {currentInst ? (
          <span className={`tl-badge ${(INSTALL_STATUS_META[currentInst.status] ?? { cls: "" }).cls}`}>
            {(INSTALL_STATUS_META[currentInst.status] ?? { label: currentInst.status }).label}
          </span>
        ) : null}
      </div>
      {currentInst ? (
        <>
          <div className="side-kv">
            <span>可执行</span>
            <b title={currentInst.path ?? ""}>{currentInst.path ?? "未找到"}</b>
          </div>
          <div className="side-kv">
            <span>版本</span>
            <b>{currentInst.version ?? "—"}</b>
          </div>
          <div className="side-kv">
            <span>适配器</span>
            <b>{currentInst.adapter}</b>
          </div>
          <div className="side-kv">
            <span>凭据</span>
            <b>{currentInst.auth_status}</b>
          </div>
          <div className="side-kv">
            <span>能力</span>
            <b>{currentInst.capabilities.join(" · ") || "—"}</b>
          </div>
          <div className="side-sub">操作</div>
          {currentInst.status === "READY" ? (
            <div className="ext-agent-actions">
              <button
                type="button"
                className="btn btn-xs btn-primary"
                disabled={installBusy === `agent:${currentInst.name}`}
                onClick={() => onCreateAgent(currentInst.name)}
              >
                {installBusy === `agent:${currentInst.name}` ? "创建中…" : "建 Agent"}
              </button>
              <button
                type="button"
                className="btn btn-xs btn-ghost"
                disabled={installBusy === currentInst.name}
                onClick={() => onRemove(currentInst.name)}
              >
                移除接入
              </button>
            </div>
          ) : currentInst.status !== "NOT_INSTALLED" ? (
            <button
              type="button"
              className="btn btn-xs btn-primary"
              disabled={installBusy === currentInst.name}
              onClick={() => onConnect(currentInst.name)}
            >
              {installBusy === currentInst.name ? "接入中…" : "接入"}
            </button>
          ) : (
            <div className="empty-note">本机没有找到这个 CLI 的可执行文件，先安装它再接入。</div>
          )}
        </>
      ) : (
        <div className="empty-note">这条接入记录已经不在列表里了。</div>
      )}
    </section>
  );

  const pageOf = (page: SidePage) => {
    if (page.kind === "newtab") return newtabPage;
    if (page.kind === "browser") return browserPage;
    if (page.kind === "plugins") return pluginsPage;
    if (page.kind === "scan") return scanPage;
    if (page.kind === "capability") return capabilityPage;
    if (page.kind === "connectors") return connectorsPage;
    return connectorPage;
  };

  return (
    <>
      <div className="sb-tabs" role="tablist" aria-label="右侧功能区标签页">
        {tabs.map((tab) => {
          const page: SidePage = tab.pages[tab.index] ?? HOME_BROWSER;
          const active = tab.id === activeId;
          return (
            <div
              key={tab.id}
              role="tab"
              tabIndex={0}
              aria-selected={active}
              className={`sb-tab${active ? " is-active" : ""}`}
              title={pageUrl(page)}
              onClick={() => setActiveId(tab.id)}
              onKeyDown={(event) => {
                if (event.key === "Enter") setActiveId(tab.id);
              }}
            >
              <span className="sb-tab-title">{page.title}</span>
              <button
                type="button"
                className="sb-tab-x"
                title="关闭标签页"
                aria-label="关闭标签页"
                onClick={(event) => {
                  event.stopPropagation();
                  closeTab(tab.id);
                }}
              >
                ×
              </button>
            </div>
          );
        })}
        <button type="button" className="sb-new" title="新标签页" onClick={newTab}>
          ＋
        </button>
      </div>

      <div className="sb-nav">
        <button type="button" className="sb-btn" title="后退" disabled={!canBack} onClick={goBack}>
          ←
        </button>
        <button type="button" className="sb-btn" title="前进" disabled={!canForward} onClick={goForward}>
          →
        </button>
        <button type="button" className="sb-btn" title="刷新" onClick={refreshPage}>
          ⟳
        </button>
        <button type="button" className="sb-btn" title="回到新标签页" onClick={() => openPage(NEWTAB)}>
          ⌂
        </button>
        <span className="sb-addr" title={pageUrl(current)}>
          <span>{pageUrl(current)}</span>
        </span>
      </div>

      <div className="sb-page">{pageOf(current)}</div>
    </>
  );
}

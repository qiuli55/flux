/**
 * 全局外壳：左侧导航 + 顶部工具栏 + 内容区 + 底部状态栏。
 * 承载所有路由视图；Solo / IDE 的真实页面作为「嵌入视图」放进内容区（fx-embed 收敛其自带导航）。
 * 状态栏轮询后端健康与 DSH 运行时，展示引擎/模型/工作区（存在退出机制：组件卸载即清理定时器）。
 */
import { useCallback, useEffect, useState } from "react";

import { api } from "../api/client";
import type { DshStatus, HealthData } from "../api/types";
import { openCommandPalette } from "../app/commands";
import type { View } from "../app/router";
import { queueSoloIntent } from "../app/soloIntent";
import { IdePage } from "../pages/IdePage";
import { SoloPage } from "../pages/SoloPage";
import { DiagnosticsPage } from "../pages/DiagnosticsPage";
import { OverviewPage } from "../pages/OverviewPage";
import { SettingsPage } from "../pages/SettingsPage";
import { SkillsPage } from "../pages/SkillsPage";
import { TasksPage } from "../pages/TasksPage";
import { Icon } from "./Icon";
import { NAV_GROUPS, VIEW_GROUP, VIEW_LABEL } from "./nav";
import { setPref, usePrefs } from "./prefs";
import { Tip } from "./ui";

/* ---------------- 侧栏 ---------------- */
function Rail({ view, navigate, wide }: { view: View; navigate: (v: View) => void; wide: boolean }) {
  return (
    <aside className="fx-rail">
      <div className="fx-rail-brand">
        <span className="fx-logo">F</span>
        <div className="fx-brand-text">
          <div className="fx-brand-name">Flux</div>
          <div className="fx-brand-sub">AI 研发工作台</div>
        </div>
      </div>
      <nav className="fx-rail-scroll">
        {NAV_GROUPS.map((group, gi) => (
          <div className="fx-rail-group" key={group.label || gi}>
            {group.label ? <div className="fx-rail-label">{group.label}</div> : null}
            {group.items.map((item) => (
              <button
                key={item.id}
                className={["fx-nav", item.id === view && "fx-active"].filter(Boolean).join(" ")}
                onClick={() => navigate(item.id)}
                title={wide ? undefined : item.label}
              >
                <Icon name={item.icon} />
                <span>{item.label}</span>
              </button>
            ))}
          </div>
        ))}
      </nav>
      <div className="fx-rail-foot">
        <div className="fx-user" title="本地单用户模式">
          <span className="fx-avatar fx-sm">本</span>
          <div className="fx-user-meta">
            <div className="fx-user-name">本地工作区</div>
            <div className="fx-user-plan">单用户 · 共享 Token</div>
          </div>
        </div>
      </div>
    </aside>
  );
}

/* ---------------- 顶栏 ---------------- */
function Topbar({ view, wide, onToggleWide }: { view: View; wide: boolean; onToggleWide: () => void }) {
  return (
    <header className="fx-topbar">
      <div className="fx-crumb">
        <span className="fx-root-name">{VIEW_GROUP[view]}</span>
        <span className="fx-sep">/</span>
        <span className="fx-cur">{VIEW_LABEL[view]}</span>
      </div>
      <button className="fx-searchbox" onClick={() => openCommandPalette()}>
        <Icon name="search" className="fx-icon-sm" />
        <span>搜索命令或页面…</span>
        <kbd className="fx-kbd">Ctrl K</kbd>
      </button>
      <div className="fx-topbar-actions">
        {view === "solo" ? (
          <>
            <Tip text="项目列表"><button className="fx-iconbtn" onClick={() => queueSoloIntent("projects")} aria-label="项目列表"><Icon name="folder" /></button></Tip>
            <Tip text="Agent 面板"><button className="fx-iconbtn" onClick={() => queueSoloIntent("agents")} aria-label="Agent 面板"><Icon name="bot" /></button></Tip>
          </>
        ) : null}
        <Tip text="打开命令面板"><button className="fx-iconbtn" onClick={() => openCommandPalette()} aria-label="命令面板"><Icon name="command" /></button></Tip>
        <Tip text="收起 / 展开侧栏"><button className="fx-iconbtn" onClick={onToggleWide} aria-label="侧栏宽度"><Icon name={wide ? "chevron" : "grid"} /></button></Tip>
      </div>
    </header>
  );
}

/* ---------------- 状态栏 ---------------- */
function StatusBar({ health, dsh }: { health: HealthData | null; dsh: DshStatus | null }) {
  const online = health !== null;
  return (
    <footer className="fx-status">
      <span className={["fx-sitem", online && "fx-ok"].filter(Boolean).join(" ")}>
        <i className={["fx-pulse", online ? "fx-ok" : "fx-warn"].filter(Boolean).join(" ")} />
        {online ? `后端正常 · ${health?.env}` : "后端未连接"}
      </span>
      {dsh ? <span className="fx-sitem">引擎 {dsh.provider}/{dsh.model}</span> : null}
      {dsh ? <span className="fx-sitem"><Icon name="folder" className="fx-icon-sm" />{dsh.workspace}</span> : null}
      <span className="fx-sitem fx-push">Flux Console · v2</span>
    </footer>
  );
}

/* ---------------- 内容区 ---------------- */
function Content({ view, navigate }: { view: View; navigate: (v: View) => void }) {
  const embed = view === "solo" || view === "ide";
  return (
    <div className={["fx-content", embed ? "fx-nopad" : "fx-pad"].join(" ")}>
      <div className="fx-view-enter" key={view}>
        {view === "overview" ? <OverviewPage navigate={navigate} /> : null}
        {view === "tasks" ? <TasksPage navigate={navigate} /> : null}
        {view === "skills" ? <SkillsPage /> : null}
        {view === "diagnostics" ? <DiagnosticsPage /> : null}
        {view === "settings" ? <SettingsPage /> : null}
        {view === "solo" ? <div className="fx-embed"><SoloPage onOpenWorkspace={() => navigate("ide")} /></div> : null}
        {view === "ide" ? <div className="fx-embed"><IdePage onBackToSolo={() => navigate("solo")} /></div> : null}
      </div>
    </div>
  );
}

/* ---------------- 外壳 ---------------- */
export function Shell({ view, navigate }: { view: View; navigate: (v: View) => void }) {
  const prefs = usePrefs();
  const [health, setHealth] = useState<HealthData | null>(null);
  const [dsh, setDsh] = useState<DshStatus | null>(null);

  const toggleWide = useCallback(() => setPref("wide", !prefs.wide), [prefs.wide]);

  useEffect(() => {
    let alive = true;
    const poll = async () => {
      try { const data = await api.health(); if (alive) setHealth(data); }
      catch { if (alive) setHealth(null); }
      try { const data = await api.dshStatus(); if (alive) setDsh(data); }
      catch { if (alive) setDsh(null); }
    };
    void poll();
    const timer = window.setInterval(poll, 30_000);
    return () => { alive = false; window.clearInterval(timer); };
  }, []);

  return (
    <div className={["fx-root", "fx-shell", prefs.wide && "fx-wide"].filter(Boolean).join(" ")}>
      <Rail view={view} navigate={navigate} wide={prefs.wide} />
      <div className="fx-main">
        <Topbar view={view} wide={prefs.wide} onToggleWide={toggleWide} />
        <Content view={view} navigate={navigate} />
        {prefs.statusbar ? <StatusBar health={health} dsh={dsh} /> : null}
      </div>
    </div>
  );
}

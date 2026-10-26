/**
 * 全局外壳：左侧导航 + 模式切换 + 顶部工具栏 + 内容区 + 底部状态栏。
 * 承载所有路由视图；Solo / IDE 的真实页面作为「嵌入视图」放进内容区（fx-embed 收敛其自带导航）。
 * 状态栏轮询后端健康与 DSH 运行时，展示引擎 / 模型 / 工作区（组件卸载即清理定时器）。
 */
import { useCallback, useEffect, useState } from "react";

import { api } from "../api/client";
import type { DshStatus, HealthData } from "../api/types";
import { openCommandPalette, queueIdeIntent } from "../app/commands";
import type { View } from "../app/router";
import { queueSoloIntent } from "../app/soloIntent";
import { openTerminalWindow } from "../app/terminalWindow";
import { IdePage } from "../pages/IdePage";
import { SoloPage } from "../pages/SoloPage";
import { BillingPage } from "../pages/BillingPage";
import { DiagnosticsPage } from "../pages/DiagnosticsPage";
import { OverviewPage } from "../pages/OverviewPage";
import { SettingsPage } from "../pages/SettingsPage";
import { SkillsPage } from "../pages/SkillsPage";
import { TasksPage } from "../pages/TasksPage";
import { Icon } from "./Icon";
import { NAV_GROUPS, NAV_MODES, VIEW_GROUP, VIEW_LABEL } from "./nav";
import { setPref, usePrefs } from "./prefs";
import { Tip } from "./ui";

/* ---------------- 侧栏 ---------------- */

/** 当前模式专属工具：IDE 模式是工作台侧栏面板，Solo 模式是任务台入口。 */
interface ModeTool {
  id: string;
  label: string;
  icon: string;
  run: () => void;
}

function modeTools(view: View, navigate: (v: View) => void): ModeTool[] {
  if (view === "ide") {
    return [
      { id: "files", label: "文件资源管理器", icon: "files", run: () => queueIdeIntent("files") },
      { id: "search", label: "搜索", icon: "search", run: () => queueIdeIntent("search") },
      { id: "git", label: "源代码管理", icon: "git", run: () => queueIdeIntent("git") },
      { id: "debug", label: "运行和调试", icon: "debug", run: () => queueIdeIntent("debug") },
      { id: "ext", label: "扩展", icon: "grid", run: () => queueIdeIntent("ext") },
    ];
  }
  if (view === "solo") {
    return [
      { id: "projects", label: "项目列表", icon: "folder", run: () => queueSoloIntent("projects") },
      { id: "workspace", label: "工作区 · 打开 IDE", icon: "code", run: () => navigate("ide") },
      { id: "terminal", label: "Agent Terminal", icon: "terminal", run: () => openTerminalWindow() },
      { id: "agents", label: "Agent 面板", icon: "bot", run: () => queueSoloIntent("agents") },
    ];
  }
  return [];
}

function Rail({ view, navigate, wide, onToggleWide }: { view: View; navigate: (v: View) => void; wide: boolean; onToggleWide: () => void }) {
  const tools = modeTools(view, navigate);
  return (
    <aside className="fx-rail">
      <div className="fx-rail-brand">
        <span className="fx-logo">F</span>
        <div className="fx-brand-text">
          <div className="fx-brand-name">Flux</div>
          <div className="fx-brand-sub">AI 研发工作台</div>
        </div>
      </div>
      <div className="fx-mode">
        <div className="fx-mode-lbl">模式</div>
        <div className="fx-mode-seg">
          {NAV_MODES.map((mode) => (
            <button
              key={mode.id}
              className={["fx-mode-b", mode.id === view && "fx-active"].filter(Boolean).join(" ")}
              onClick={() => navigate(mode.id)}
              title={wide ? undefined : mode.label}
            >
              <Icon name={mode.icon} />
              <span>{mode.label}</span>
            </button>
          ))}
        </div>
      </div>
      <nav className="fx-rail-scroll">
        {tools.length > 0 ? (
          <div className="fx-rail-group">
            <div className="fx-rail-label">{view === "ide" ? "IDE 面板" : "Solo 入口"}</div>
            {tools.map((tool) => (
              <button
                key={tool.id}
                className="fx-nav"
                onClick={tool.run}
                title={wide ? undefined : tool.label}
              >
                <Icon name={tool.icon} />
                <span>{tool.label}</span>
              </button>
            ))}
          </div>
        ) : null}
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
        <button
          type="button"
          className="fx-nav"
          onClick={onToggleWide}
          title={wide ? "收起侧栏" : "展开侧栏"}
        >
          <Icon name={wide ? "chevron" : "grid"} />
          <span>{wide ? "收起侧栏" : "展开侧栏"}</span>
        </button>
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
function Topbar({ view, wide, dsh, onToggleWide }: { view: View; wide: boolean; dsh: DshStatus | null; onToggleWide: () => void }) {
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
        {dsh ? <span className="fx-engine">引擎 <b>{dsh.provider}/{dsh.model}</b></span> : null}
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
      <span className="fx-sitem fx-push">Flux Console · Terminal Noir</span>
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
        {view === "billing" ? <BillingPage /> : null}
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
      <Rail view={view} navigate={navigate} wide={prefs.wide} onToggleWide={toggleWide} />
      <div className="fx-main">
        <Topbar view={view} wide={prefs.wide} dsh={dsh} onToggleWide={toggleWide} />
        <Content view={view} navigate={navigate} />
        {prefs.statusbar ? <StatusBar health={health} dsh={dsh} /> : null}
      </div>
    </div>
  );
}

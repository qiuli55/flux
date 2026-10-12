/**
 * 应用外壳：左侧 rail 导航 + 顶栏 + 内容区 + 底部状态栏。
 * 内容区根据当前路由 id 渲染对应页面；认证页不进此壳（在 App 层分流）。
 */
import { useState } from "react";
import type { ReactNode } from "react";
import { NAV, pageMeta } from "../../app/nav";
import { Icon } from "../ui/Icon";
import { Kbd } from "../ui/primitives";

interface Props {
  current: string;
  navigate: (id: string) => void;
  onCommand: () => void;
  children: ReactNode;
}

export function Shell({ current, navigate, onCommand, children }: Props) {
  const [wide, setWide] = useState(true);
  const meta = pageMeta(current);
  const activeGroup = NAV.find((g) => g.pages.some((p) => p.id === current));

  return (
    <div className={["fx-app", wide && "wide"].filter(Boolean).join(" ")}>
      <aside className="fx-rail">
        <div className="fx-rail-brand">
          <span className="fx-logo">F</span>
          <div className="fx-brand-text">
            <div className="fx-brand-name">Flux</div>
            <div className="fx-brand-sub">AI 软件工程 OS</div>
          </div>
        </div>
        <div className="fx-rail-scroll">
          {NAV.map((group) => (
            <div className="fx-rail-group" key={group.title}>
              <div className="fx-rail-label">{group.title}</div>
              {group.pages.map((p) => (
                <button
                  key={p.id}
                  className={["fx-nav", p.id === current && "active"].filter(Boolean).join(" ")}
                  onClick={() => navigate(p.id)}
                  title={wide ? undefined : p.label}
                >
                  <Icon name={p.icon} />
                  <span>{p.label}</span>
                </button>
              ))}
            </div>
          ))}
        </div>
        <div className="fx-rail-foot">
          <div className="fx-user">
            <span className="avatar sm">秋</span>
            <div className="fx-user-meta grow">
              <div className="fx-user-name">秋离</div>
              <div className="fx-user-plan">Pro · 个人工作区</div>
            </div>
          </div>
        </div>
      </aside>

      <div className="fx-main">
        <header className="fx-topbar">
          <button className="fx-iconbtn" onClick={() => setWide((v) => !v)} aria-label="切换导航宽度">
            <Icon name="grid" />
          </button>
          <nav className="fx-crumb">
            <span className="root">{activeGroup?.title ?? "Flux"}</span>
            <span className="sep">/</span>
            <span className="cur">{meta.label}</span>
          </nav>
          <button className="fx-searchbox" onClick={onCommand}>
            <Icon name="search" className="icon-sm" />
            <span>搜索页面、命令、文件…</span>
            <Kbd>⌘K</Kbd>
          </button>
          <div className="fx-topbar-actions">
            <button className="fx-iconbtn" aria-label="通知">
              <Icon name="bell" />
              <i className="pip" />
            </button>
            <button className="fx-iconbtn" aria-label="新建">
              <Icon name="plus" />
            </button>
            <span className="vdivider" style={{ height: 22, margin: "0 4px" }} />
            <span className="avatar sm">秋</span>
          </div>
        </header>

        <main className={["fx-content", current === "ide" || current === "terminal" || current === "solo" ? "nopad" : "pad"].join(" ")}>
          {children}
        </main>

        <footer className="fx-status">
          <span className="sitem">
            <Icon name="branch" className="icon-sm" /> main
          </span>
          <span className="sitem ok">
            <Icon name="check" className="icon-sm" /> 292 通过
          </span>
          <span className="sitem">
            <Icon name="close" className="icon-sm" /> 0 失败
          </span>
          <span className="sitem">
            <Icon name="server" className="icon-sm" /> Provider: deepseek-flash
          </span>
          <span className="sitem push">
            <Icon name="activity" className="icon-sm" /> 12.6k tokens · ¥0.21 本次
          </span>
          <span className="sitem">
            <i className="pulse-dot ok" /> 已连接
          </span>
        </footer>
      </div>
    </div>
  );
}

/**
 * 设置页：界面偏好（即时生效、本地持久化）+ 工作区事实（只读）+ 关于。
 * 偏好经 src/shell/prefs.ts 读写，改动会同步到外壳（侧栏宽度、状态栏、减少动效）。
 */
import { useEffect, useState, type ReactNode } from "react";

import { API_BASE, api, ApiError } from "../api/client";
import { toast } from "../app/toast";
import { KeyVaultPanel } from "../components/KeyVaultPanel";
import { Icon } from "../shell/Icon";
import { setPref, usePrefs } from "../shell/prefs";
import { Button, Card, PageHead, SectionTitle, Switch } from "../shell/ui";
import { Modal } from "../shell/Overlay";

type Tab = "appearance" | "workspace" | "keys" | "about";

const TABS: { id: Tab; label: string; icon: string }[] = [
  { id: "appearance", label: "外观", icon: "eye" },
  { id: "workspace", label: "工作区", icon: "folder" },
  { id: "keys", label: "密钥库", icon: "key" },
  { id: "about", label: "关于", icon: "book" },
];

function Row({ title, desc, children }: { title: string; desc: string; children: ReactNode }) {
  return (
    <div className="fx-set-row">
      <div className="fx-info">
        <div className="fx-t">{title}</div>
        <div className="fx-d">{desc}</div>
      </div>
      <div className="fx-ctl">{children}</div>
    </div>
  );
}

function Kv({ k, v }: { k: string; v: string }) {
  return (
    <>
      <span className="fx-k">{k}</span>
      <span className="fx-v fx-ellipsis" title={v}>{v}</span>
    </>
  );
}

export function SettingsPage() {
  const [tab, setTab] = useState<Tab>("appearance");
  const [root, setRoot] = useState<string>("—");
  const [confirmReset, setConfirmReset] = useState(false);
  const prefs = usePrefs();

  useEffect(() => {
    void (async () => {
      try { const data = await api.getWorkspaceRoot(); setRoot(data.root); }
      catch (e) { if (e instanceof ApiError) setRoot(`获取失败：${e.message}`); }
    })();
  }, []);

  return (
    <>
      <PageHead title="设置" desc="界面偏好即时生效并保存在本机；工作区信息来自后端，只读。" />

      <div className="fx-settings-wrap">
        <div className="fx-set-nav">
          {TABS.map((t) => (
            <button key={t.id} className={t.id === tab ? "fx-active" : ""} onClick={() => setTab(t.id)}>
              <Icon name={t.icon} className="fx-icon-sm" />{t.label}
            </button>
          ))}
        </div>

        <div className="fx-col fx-gap-4">
          {tab === "appearance" ? (
            <Card pad>
              <div className="fx-set-group-head">
                <SectionTitle>外观</SectionTitle>
                <div className="fx-section-sub">这些选项会立即作用到当前界面。</div>
              </div>
              <Row title="展开侧栏" desc="显示导航项文字；关闭则只显示图标。">
                <Switch on={prefs.wide} onChange={(v) => setPref("wide", v)} />
              </Row>
              <Row title="显示底部状态栏" desc="展示后端健康、引擎模型与工作区路径。">
                <Switch on={prefs.statusbar} onChange={(v) => setPref("statusbar", v)} />
              </Row>
              <Row title="减少动效" desc="关闭淡入、脉冲等动画，适合弱设备或晕动敏感。">
                <Switch on={prefs.reduceMotion} onChange={(v) => setPref("reduceMotion", v)} />
              </Row>
            </Card>
          ) : null}

          {tab === "workspace" ? (
            <Card pad>
              <div className="fx-set-group-head">
                <SectionTitle>工作区</SectionTitle>
                <div className="fx-section-sub">由后端环境变量决定，前端只读。</div>
              </div>
              <div className="fx-kv" style={{ marginTop: 12 }}>
                <Kv k="工作区根" v={root} />
                <Kv k="API 前缀" v={API_BASE} />
                <Kv k="认证方式" v="单一共享 Token（本地单用户）" />
              </div>
            </Card>
          ) : null}

          {tab === "keys" ? <KeyVaultPanel /> : null}

          {tab === "about" ? (
            <Card pad>
              <div className="fx-set-group-head">
                <SectionTitle>关于</SectionTitle>
                <div className="fx-section-sub">Flux · AI 研发工作台（web-dashboard / 全局外壳 v2）。</div>
              </div>
              <div className="fx-kv" style={{ marginTop: 12, marginBottom: 16 }}>
                <Kv k="界面" v="Flux Console v2" />
                <Kv k="前端" v="React + Vite" />
                <Kv k="默认视图" v="概览 / Solo 执行 / IDE 工作台 / 任务 / Skills / 系统诊断" />
              </div>
              <div className="fx-set-row">
                <div className="fx-info">
                  <div className="fx-t">重置本地界面偏好</div>
                  <div className="fx-d">清除本机保存的侧栏宽度、状态栏与动效设置，恢复默认。</div>
                </div>
                <div className="fx-ctl">
                  <Button variant="danger" icon="trash" onClick={() => setConfirmReset(true)}>重置</Button>
                </div>
              </div>
            </Card>
          ) : null}
        </div>
      </div>

      <Modal
        open={confirmReset}
        title="重置界面偏好"
        onClose={() => setConfirmReset(false)}
        footer={
          <>
            <Button onClick={() => setConfirmReset(false)}>取消</Button>
            <Button
              variant="danger"
              onClick={() => {
                localStorage.removeItem("flux.fx.prefs");
                setPref("wide", false);
                setPref("statusbar", true);
                setPref("reduceMotion", false);
                setConfirmReset(false);
                toast("已恢复默认界面设置");
              }}
            >
              确认重置
            </Button>
          </>
        }
      >
        <p style={{ margin: 0, color: "var(--fx-text-2)", fontSize: 13 }}>
          将清除本机保存的界面偏好设置（不影响任务、项目或任何后端数据）。
        </p>
      </Modal>
    </>
  );
}

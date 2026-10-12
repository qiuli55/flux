/**
 * 平台管理组页面：模型与供应商、Skills、Connectors、Marketplace、用量与成本、
 * 团队与成员、系统诊断、设置。设置页集中演示下拉框、开关、输入等控件。
 */
import { useState } from "react";
import type { ReactElement, ReactNode } from "react";
import { Icon } from "../components/ui/Icon";
import { Avatar, Badge, Button, Card, Input, Progress, Switch, Textarea } from "../components/ui/primitives";
import { Select, type Option } from "../components/ui/Select";
import { connectors, providers, skills } from "../data/mock";
import { AgentsGrid } from "./Agents";

const TONE_MAP = ["", "g", "o", "b"] as const;

/* ---------------- 模型与供应商 ---------------- */
/** 供应商卡片网格：独立页面与「设置」分组共用。 */
export function ProvidersGrid() {
  return (
    <div className="grid c2">
        {providers.map((p) => (
          <Card pad key={p.name} hover>
            <div className="between" style={{ marginBottom: 12 }}>
              <div className="row gap-3">
                <span className={["mc-icon", p.key ? "g" : ""].filter(Boolean).join(" ")}>
                  <Icon name="server" />
                </span>
                <div>
                  <div style={{ fontSize: 14, fontWeight: 650, textTransform: "capitalize" }}>{p.name}</div>
                  <div className="mono dim" style={{ fontSize: 11.5 }}>
                    {p.model}
                  </div>
                </div>
              </div>
              <Badge tone={p.status === "ready" ? "green" : "default"} dot>
                {p.status === "ready" ? "已就绪" : "未配置"}
              </Badge>
            </div>
            <div className="row gap-4" style={{ fontSize: 12, color: "var(--text-3)", marginBottom: 12 }}>
              <span>
                延迟 <b style={{ color: "var(--text)" }}>{p.latency}</b>
              </span>
              <span>
                用量占比 <b style={{ color: "var(--text)" }}>{p.used}%</b>
              </span>
              <span className="row gap-1">
                <Icon name="key" className="icon-sm" /> {p.key ? "已配置密钥" : "未配置密钥"}
              </span>
            </div>
            <Progress value={p.used} thin />
            <div className="row gap-2" style={{ marginTop: 14 }}>
              <Button variant="ghost" size="sm" icon="edit">
                配置
              </Button>
              <Button variant="quiet" size="sm" icon="zap">
                测试连通
              </Button>
            </div>
          </Card>
        ))}
    </div>
  );
}

/* ---------------- Skills ---------------- */
export function Skills() {
  return (
    <>
      <div className="page-head">
        <div>
          <h1 className="page-title">Skills</h1>
          <p className="page-desc">能力包：把领域知识、流程与规范打包，Agent 在需要时自动加载。</p>
        </div>
        <div className="page-tools">
          <Input icon="search" placeholder="搜索 Skill…" style={{ width: 220 }} />
        </div>
      </div>
      <div className="grid auto">
        {skills.map((s) => (
          <div className="market-card anim-up" key={s.id}>
            <div className="mc-top">
              <span className={["mc-icon", TONE_MAP[s.tone]].filter(Boolean).join(" ")}>
                <Icon name={s.icon} />
              </span>
              <div className="grow">
                <div className="mc-name">{s.name}</div>
                <div className="dim" style={{ fontSize: 11 }}>
                  {s.author}
                </div>
              </div>
              {s.installed ? <Badge tone="green">已安装</Badge> : null}
            </div>
            <div className="mc-desc">{s.desc}</div>
            <div className="mc-foot">
              <span className="stars">
                <Icon name="star" className="icon-sm" style={{ color: "var(--orange)" }} />
                {s.stars.toLocaleString()}
              </span>
              <span className="grow" />
              <Button variant={s.installed ? "ghost" : "primary"} size="sm" icon={s.installed ? "check" : "download"}>
                {s.installed ? "已安装" : "安装"}
              </Button>
            </div>
          </div>
        ))}
      </div>
    </>
  );
}

/* ---------------- Connectors ---------------- */
export function Connectors() {
  return (
    <>
      <div className="page-head">
        <div>
          <h1 className="page-title">Connectors</h1>
          <p className="page-desc">外部系统连接器：统一契约与注册表，授权后经同一 agent 面被消费。</p>
        </div>
        <div className="page-tools">
          <Button variant="primary" icon="plus">
            添加连接器
          </Button>
        </div>
      </div>
      <div className="grid auto">
        {connectors.map((c) => (
          <Card pad key={c.id} hover>
            <div className="row gap-3" style={{ marginBottom: 12 }}>
              <span className={["mc-icon", TONE_MAP[c.tone]].filter(Boolean).join(" ")}>
                <Icon name={c.icon} />
              </span>
              <div className="grow">
                <div style={{ fontSize: 14, fontWeight: 650 }}>{c.name}</div>
                <div className="dim" style={{ fontSize: 11 }}>
                  {c.connected ? "已授权" : "未连接"}
                </div>
              </div>
              <span className={["pulse-dot", c.connected ? "ok" : ""].join(" ")} style={!c.connected ? { background: "var(--text-4)" } : undefined} />
            </div>
            <div className="mc-desc" style={{ marginBottom: 14 }}>
              {c.desc}
            </div>
            <Button variant={c.connected ? "ghost" : "primary"} block size="sm" icon={c.connected ? "settings" : "plug"}>
              {c.connected ? "管理授权" : "连接"}
            </Button>
          </Card>
        ))}
      </div>
    </>
  );
}

/* ---------------- Marketplace ---------------- */
export function Marketplace() {
  const [tab, setTab] = useState("skills");
  const tabs = [
    { id: "skills", label: "Skills" },
    { id: "connectors", label: "Connectors" },
    { id: "templates", label: "项目模板" },
  ];
  return (
    <>
      <div className="page-head">
        <div>
          <h1 className="page-title">Marketplace</h1>
          <p className="page-desc">插件、模板与资产的统一市场，按场景组织。</p>
        </div>
      </div>
      <div className="toolbar">
        <div className="pills">
          {tabs.map((t) => (
            <button key={t.id} className={["pill", t.id === tab && "active"].filter(Boolean).join(" ")} onClick={() => setTab(t.id)}>
              {t.label}
            </button>
          ))}
        </div>
        <span className="grow" />
        <Input icon="search" placeholder="搜索市场…" style={{ width: 240 }} />
      </div>
      {tab === "templates" ? (
        <div className="grid auto">
          {["最小 IDE 前端", "Virtual Workspace 服务", "Agent Runtime 骨架", "MCP 连接器模板"].map((n, i) => (
            <div className="market-card anim-up" key={n}>
              <div className="mc-top">
                <span className={["mc-icon", TONE_MAP[(i % 3) + 1]].join(" ")}>
                  <Icon name="package" />
                </span>
                <div className="grow">
                  <div className="mc-name">{n}</div>
                  <div className="dim" style={{ fontSize: 11 }}>
                    官方模板
                  </div>
                </div>
              </div>
              <div className="mc-desc">一键生成含契约层、测试与 CI 的项目骨架。</div>
              <Button variant="primary" block size="sm" icon="download">
                使用模板
              </Button>
            </div>
          ))}
        </div>
      ) : (
        <div className="grid auto">
          {(tab === "skills" ? skills : connectors).map((x, i) => (
            <div className="market-card anim-up" key={x.id}>
              <div className="mc-top">
                <span className={["mc-icon", TONE_MAP[i % 4]].filter(Boolean).join(" ")}>
                  <Icon name={x.icon} />
                </span>
                <div className="grow">
                  <div className="mc-name">{x.name}</div>
                  <div className="dim" style={{ fontSize: 11, textTransform: "capitalize" }}>
                    {tab}
                  </div>
                </div>
              </div>
              <div className="mc-desc">{x.desc}</div>
              <Button variant="primary" block size="sm" icon="download">
                获取
              </Button>
            </div>
          ))}
        </div>
      )}
    </>
  );
}

/* ---------------- 用量与成本 ---------------- */
const LEGEND = [
  { c: "#2dd4bf", n: "deepseek-flash", v: "¥ 28.4" },
  { c: "#4cc9dd", n: "本地回显", v: "¥ 0.0" },
  { c: "#4ade80", n: "缓存命中", v: "¥ 6.1" },
  { c: "#fb923c", n: "其他", v: "¥ 14.1" },
];

export function Cost() {
  const bars = [42, 58, 36, 71, 88, 64, 92, 79, 55, 68, 47, 83];
  return (
    <>
      <div className="page-head">
        <div>
          <h1 className="page-title">用量与成本</h1>
          <p className="page-desc">Token 用量、费用分布与预算控制。计价按真实价目表，不用猜测值填充。</p>
        </div>
        <div className="page-tools">
          <div className="pills">
            <button className="pill">日</button>
            <button className="pill active">月</button>
            <button className="pill">季</button>
          </div>
        </div>
      </div>
      <div className="grid c2" style={{ marginBottom: 16, alignItems: "stretch" }}>
        <Card pad>
          <div className="cost-hero">
            <div className="ring" style={{ ["--p" as string]: 68 }}>
              <div className="inner">
                <div className="big">68%</div>
                <div className="sub">预算使用</div>
              </div>
            </div>
            <div className="legend">
              {LEGEND.map((l) => (
                <div className="row" key={l.n}>
                  <span className="sw" style={{ background: l.c }} />
                  <span className="grow">{l.n}</span>
                  <span className="val">{l.v}</span>
                </div>
              ))}
              <div className="divider" style={{ margin: "4px 0" }} />
              <div className="row">
                <span className="grow" style={{ fontWeight: 600 }}>
                  本月合计
                </span>
                <span className="val" style={{ color: "var(--text)" }}>
                  ¥ 48.6 / ¥ 72
                </span>
              </div>
            </div>
          </div>
        </Card>
        <Card pad>
          <div className="section-title" style={{ marginBottom: 16 }}>
            近 12 个月趋势
          </div>
          <div className="bar" style={{ height: 120 }}>
            {bars.map((b, i) => (
              <i key={i} className={b > 80 ? "hot" : ""} style={{ height: `${b}%` }} />
            ))}
          </div>
          <div className="row between" style={{ marginTop: 8, fontSize: 11, color: "var(--text-4)" }}>
            <span>11 月</span>
            <span>本月</span>
          </div>
        </Card>
      </div>
      <Card pad>
        <div className="section-title" style={{ marginBottom: 8 }}>
          按模型用量
        </div>
        {[
          { n: "deepseek-flash", p: 62, t: "9.1M tokens" },
          { n: "claude-sonnet-5-5", p: 24, t: "3.4M tokens" },
          { n: "gpt-5.5", p: 14, t: "1.9M tokens" },
        ].map((r) => (
          <div className="usage-bar" key={r.n}>
            <span className="mono" style={{ color: "var(--text-2)" }}>
              {r.n}
            </span>
            <Progress value={r.p} thin />
            <span className="dim" style={{ textAlign: "right" }}>
              {r.t}
            </span>
          </div>
        ))}
      </Card>
    </>
  );
}

/* ---------------- 团队与成员 ---------------- */
const MEMBERS = [
  { n: "秋离", e: "qiuli@flux.dev", r: "所有者", tone: 0 as const },
  { n: "周砚", e: "zhouyan@flux.dev", r: "管理员", tone: 1 as const },
  { n: "林澈", e: "linche@flux.dev", r: "开发者", tone: 2 as const },
  { n: "陈屿", e: "chenyu@flux.dev", r: "开发者", tone: 1 as const },
  { n: "苏晚", e: "suwan@flux.dev", r: "只读", tone: 2 as const },
];

export function Members() {
  return (
    <>
      <div className="page-head">
        <div>
          <h1 className="page-title">团队与成员</h1>
          <p className="page-desc">成员、角色与权限策略。权限模型为 RBAC + Capability，默认 fail-closed。</p>
        </div>
        <div className="page-tools">
          <Button variant="primary" icon="plus">
            邀请成员
          </Button>
        </div>
      </div>
      <Card>
        <table className="table">
          <thead>
            <tr>
              <th>成员</th>
              <th>邮箱</th>
              <th>角色</th>
              <th>最近活跃</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            {MEMBERS.map((m) => (
              <tr key={m.e}>
                <td>
                  <span className="row gap-2">
                    <Avatar name={m.n} tone={m.tone} size="sm" />
                    <strong>{m.n}</strong>
                  </span>
                </td>
                <td className="mono">{m.e}</td>
                <td>
                  <Badge tone={m.r === "所有者" ? "brand" : "default"}>{m.r}</Badge>
                </td>
                <td>今天</td>
                <td>
                  <Button variant="quiet" size="sm" icon="more" />
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </Card>
    </>
  );
}

/* ---------------- 系统诊断 ---------------- */
const HEALTH = [
  { n: "API 服务", v: "正常", d: "127.0.0.1:8865 · 12ms", ok: true, icon: "server" },
  { n: "数据库", v: "正常", d: "SQLite · WAL · 3.1MB", ok: true, icon: "database" },
  { n: "任务调度器", v: "正常", d: "队列 0 / 已处理 292", ok: true, icon: "activity" },
  { n: "模型网关", v: "正常", d: "available: local, deepseek", ok: true, icon: "cpu" },
  { n: "磁盘", v: "告警", d: "已用 86%", ok: false, icon: "chart" },
  { n: "Connector", v: "正常", d: "6 个已注册 / 3 已授权", ok: true, icon: "plug" },
];

export function Diagnostics() {
  return (
    <>
      <div className="page-head">
        <div>
          <h1 className="page-title">系统诊断</h1>
          <p className="page-desc">健康检查、资源配置与运行环境。</p>
        </div>
        <div className="page-tools">
          <Button variant="ghost" icon="download">
            导出报告
          </Button>
          <Button variant="primary" icon="refresh">
            重新检测
          </Button>
        </div>
      </div>
      <div className="grid c3" style={{ marginBottom: 16 }}>
        {HEALTH.map((h) => (
          <div className="health-tile" key={h.n}>
            <div className="between">
              <span className="row gap-2">
                <Icon name={h.icon} style={{ color: "var(--text-3)" }} />
                <span style={{ fontWeight: 600, fontSize: 13 }}>{h.n}</span>
              </span>
              <span className={["pulse-dot", h.ok ? "ok" : "warn"].join(" ")} />
            </div>
            <div style={{ fontSize: 15, fontWeight: 700, color: h.ok ? "var(--green)" : "var(--orange)" }}>{h.v}</div>
            <div className="dim mono" style={{ fontSize: 11 }}>
              {h.d}
            </div>
          </div>
        ))}
      </div>
      <Card pad>
        <div className="section-title" style={{ marginBottom: 12 }}>
          运行环境
        </div>
        <div className="kv">
          <span className="k">版本</span>
          <span className="v">Flux M1 · 0.6.3</span>
          <span className="k">Python</span>
          <span className="v">3.10.12</span>
          <span className="k">Node</span>
          <span className="v">20.18.1</span>
          <span className="k">运行时长</span>
          <span className="v">3 天 7 小时</span>
          <span className="k">数据表</span>
          <span className="v">14 张 · 迁移无漂移</span>
        </div>
      </Card>
    </>
  );
}

/* ---------------- 设置 ---------------- */
const SET_GROUPS = [
  { id: "general", label: "通用", icon: "settings" },
  { id: "editor", label: "编辑器", icon: "code" },
  { id: "terminal", label: "终端", icon: "terminal" },
  { id: "notify", label: "通知", icon: "bell" },
  { id: "models", label: "模型与供应商", icon: "server" },
  { id: "agents", label: "Agents", icon: "bot" },
  { id: "integration", label: "集成", icon: "plug" },
];

const LANG_OPTS: Option[] = [
  { value: "zh-CN", label: "简体中文" },
  { value: "en-US", label: "English" },
  { value: "ja-JP", label: "日本語" },
];
const THEME_OPTS: Option[] = [
  { value: "dark", label: "暗色（默认）" },
  { value: "light", label: "亮色" },
  { value: "system", label: "跟随系统" },
];
const FONT_OPTS: Option[] = [
  { value: "13", label: "13 px" },
  { value: "14", label: "14 px" },
  { value: "15", label: "15 px" },
];
const SHELL_OPTS: Option[] = [
  { value: "bash", label: "bash" },
  { value: "zsh", label: "zsh" },
  { value: "fish", label: "fish" },
];

function SetRow({ t, d, children }: { t: string; d: string; children: ReactNode }) {
  return (
    <div className="set-row">
      <div className="info">
        <div className="t">{t}</div>
        <div className="d">{d}</div>
      </div>
      <div className="ctl">{children}</div>
    </div>
  );
}

function GeneralGroup() {
  const [lang, setLang] = useState("zh-CN");
  const [theme, setTheme] = useState("dark");
  const [tele, setTele] = useState(false);
  return (
    <div className="set-group">
      <div className="set-group-head">
        <div className="section-title">通用</div>
        <div className="section-sub">界面语言、主题与启动行为</div>
      </div>
      <SetRow t="界面语言" d="影响所有界面文案与日期格式">
        <Select value={lang} options={LANG_OPTS} onChange={setLang} width={180} />
      </SetRow>
      <SetRow t="主题" d="暗色为展示型默认，浅色适合强光环境">
        <Select value={theme} options={THEME_OPTS} onChange={setTheme} width={180} />
      </SetRow>
      <SetRow t="匿名体验数据" d="帮助改进产品，不含任何代码内容">
        <Switch on={tele} onChange={setTele} />
      </SetRow>
      <SetRow t="启动时恢复上次工作区" d="打开应用自动回到上次的会话与标签">
        <Switch on onChange={() => {}} />
      </SetRow>
    </div>
  );
}

function EditorGroup() {
  const [font, setFont] = useState("13");
  const [wrap, setWrap] = useState(true);
  const [minimap, setMinimap] = useState(false);
  const [autosave, setAutosave] = useState(true);
  return (
    <div className="set-group">
      <div className="set-group-head">
        <div className="section-title">编辑器</div>
        <div className="section-sub">字号、折行与小地图</div>
      </div>
      <SetRow t="字号" d="代码编辑器字号">
        <Select value={font} options={FONT_OPTS} onChange={setFont} width={140} />
      </SetRow>
      <SetRow t="自动折行" d="长行折行显示，避免横向滚动">
        <Switch on={wrap} onChange={setWrap} />
      </SetRow>
      <SetRow t="代码小地图" d="降低视觉权重，避免与正文竞争">
        <Switch on={minimap} onChange={setMinimap} />
      </SetRow>
      <SetRow t="自动保存" d="改动经 Virtual Workspace 落盘">
        <Switch on={autosave} onChange={setAutosave} />
      </SetRow>
    </div>
  );
}

function TerminalGroup() {
  const [shell, setShell] = useState("bash");
  const [share, setShare] = useState(true);
  return (
    <div className="set-group">
      <div className="set-group-head">
        <div className="section-title">终端</div>
        <div className="section-sub">默认 shell 与共享会话</div>
      </div>
      <SetRow t="默认 Shell" d="新建终端使用的程序">
        <Select value={shell} options={SHELL_OPTS} onChange={setShell} width={140} />
      </SetRow>
      <SetRow t="与 Agent 共享 PTY" d="人工与 Agent 共用同一会话">
        <Switch on={share} onChange={setShare} />
      </SetRow>
      <SetRow t="工作目录" d="新建终端时的初始路径">
        <Input defaultValue="~/workspace/flux" style={{ width: 220 }} />
      </SetRow>
    </div>
  );
}

function NotifyGroup() {
  const [task, setTask] = useState(true);
  const [review, setReview] = useState(true);
  const [digest, setDigest] = useState(false);
  return (
    <div className="set-group">
      <div className="set-group-head">
        <div className="section-title">通知</div>
        <div className="section-sub">哪些事件需要打扰你</div>
      </div>
      <SetRow t="任务完成 / 失败" d="任务流转到终态时通知">
        <Switch on={task} onChange={setTask} />
      </SetRow>
      <SetRow t="有待审变更" d="生成新的变更提案时通知">
        <Switch on={review} onChange={setReview} />
      </SetRow>
      <SetRow t="每日摘要" d="每天 09:00 推送昨日工作摘要">
        <Switch on={digest} onChange={setDigest} />
      </SetRow>
      <SetRow t="通知渠道" d="选择接收通知的集成">
        <Select
          value="feishu"
          onChange={() => {}}
          options={[
            { value: "feishu", label: "飞书" },
            { value: "slack", label: "Slack" },
            { value: "email", label: "邮件" },
          ]}
          width={160}
        />
      </SetRow>
    </div>
  );
}

function IntegrationGroup() {
  return (
    <div className="set-group">
      <div className="set-group-head">
        <div className="section-title">集成</div>
        <div className="section-sub">密钥与默认值</div>
      </div>
      <div className="field" style={{ marginTop: 14 }}>
        <label>默认 Provider</label>
        <Select
          value="deepseek"
          onChange={() => {}}
          options={[
            { value: "deepseek", label: "deepseek", meta: "已配置" },
            { value: "openai", label: "openai", meta: "未配置" },
            { value: "anthropic", label: "anthropic", meta: "未配置" },
            { value: "local", label: "local", meta: "离线" },
          ]}
          width="100%"
        />
        <span className="hint">不配密钥时该 Provider 自动标记为未配置，不影响其他供应商</span>
      </div>
      <div className="field" style={{ marginTop: 16 }}>
        <label>API 密钥</label>
        <Input type="password" defaultValue="sk-********************************" />
        <span className="hint">密钥仅存本机环境变量，不写入仓库</span>
      </div>
      <div className="field" style={{ marginTop: 16 }}>
        <label>系统提示词覆盖</label>
        <Textarea defaultValue="遵循 flux 主规格；代码与规格不一致时以规格为准，并把实施期发现回写到附录 A。" />
      </div>
    </div>
  );
}

/** 模型与供应商分组：把原先的一级页面收进设置。 */
function ModelsGroup() {
  return (
    <div className="set-group">
      <div className="set-group-head">
        <div className="section-title">模型与供应商</div>
        <div className="section-sub">统一薄 HTTP 层接入各供应商，不引入官方 SDK；是否可用只取决于密钥</div>
      </div>
      <div style={{ marginTop: 16 }}>
        <ProvidersGrid />
      </div>
    </div>
  );
}

/** Agent 分组：把原先的一级页面收进设置。 */
function AgentsGroup() {
  return (
    <div className="set-group">
      <div className="set-group-head">
        <div className="section-title">Agents</div>
        <div className="section-sub">内置与接入的 Agent 档案，经统一 agent 面共享同一份工具与 Skill</div>
      </div>
      <div style={{ marginTop: 16 }}>
        <AgentsGrid />
      </div>
    </div>
  );
}

const GROUP_VIEWS: Record<string, () => ReactElement> = {
  general: GeneralGroup,
  editor: EditorGroup,
  terminal: TerminalGroup,
  notify: NotifyGroup,
  models: ModelsGroup,
  agents: AgentsGroup,
  integration: IntegrationGroup,
};

export function Settings() {
  const [g, setG] = useState("general");
  const View = GROUP_VIEWS[g] ?? GeneralGroup;
  return (
    <>
      <div className="page-head">
        <div>
          <h1 className="page-title">设置</h1>
          <p className="page-desc">模型与供应商、Agent、编辑器、终端、通知与集成，都收敛到这一页。演示各类下拉框、开关与输入控件。</p>
        </div>
        <div className="page-tools">
          <Button variant="ghost">重置</Button>
          <Button variant="primary" icon="check">
            保存
          </Button>
        </div>
      </div>
      <div className="settings-wrap">
        <nav className="set-nav">
          {SET_GROUPS.map((s) => (
            <button key={s.id} className={s.id === g ? "active" : ""} onClick={() => setG(s.id)}>
              <Icon name={s.icon} />
              {s.label}
            </button>
          ))}
        </nav>
        <Card pad>
          <View />
        </Card>
      </div>
    </>
  );
}

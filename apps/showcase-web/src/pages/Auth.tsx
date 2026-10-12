/**
 * 认证页：登录 / 注册 / 忘记密码（独立居中布局，无应用壳）。
 * 账号设置在应用壳内展示（个人资料、安全、登录方式、会话）。
 */
import { useState } from "react";
import type { ReactNode } from "react";
import { Icon } from "../components/ui/Icon";
import { Avatar, Badge, Button, Card, Check, Field, Input, Switch } from "../components/ui/primitives";
import { Select, type Option } from "../components/ui/Select";

const REGION_OPTS: Option[] = [
  { value: "cn", label: "中国 · 上海节点" },
  { value: "sg", label: "新加坡节点" },
  { value: "us", label: "美国 · 硅谷节点" },
];

const POINTS = [
  { icon: "shield", t: "Virtual Workspace：应用前不落盘" },
  { icon: "bot", t: "多 Agent 协同，统一工具与 Skill" },
  { icon: "diff", t: "每一次改动都可逐行审查与回滚" },
];

function AuthShell({ title, sub, children }: { title: string; sub: string; children: ReactNode }) {
  return (
    <div className="fx-auth">
      <aside className="fx-auth-aside">
        <div className="fx-auth-grid" />
        <div className="row gap-3" style={{ position: "relative" }}>
          <span className="fx-logo" style={{ width: 30, height: 30, fontSize: 16 }}>
            F
          </span>
          <span style={{ fontSize: 17, fontWeight: 800 }}>Flux</span>
        </div>
        <div className="fx-auth-copy">
          <h1>
            让 AI 写代码，
            <br />
            但<em>永不直接覆盖</em>你的文件。
          </h1>
          <p>集合 agent 的 AI 软件工程操作系统：统一上下文、统一工具、统一 Skill，各只有一份真源。</p>
          <div className="fx-auth-points">
            {POINTS.map((p) => (
              <div className="fx-auth-point" key={p.t}>
                <Icon name={p.icon} className="ic" />
                {p.t}
              </div>
            ))}
          </div>
        </div>
        <div className="dim" style={{ position: "relative", fontSize: 12 }}>
          © 2026 Flux · 内测版本
        </div>
      </aside>
      <div className="fx-auth-form">
        <div className="fx-auth-card">
          <h2>{title}</h2>
          <p className="sub">{sub}</p>
          {children}
        </div>
      </div>
    </div>
  );
}

function OAuthRow() {
  return (
    <div className="fx-auth-oauth">
      <button className="fx-oauth-btn" title="GitHub">
        <Icon name="git" />
      </button>
      <button className="fx-oauth-btn" title="Google">
        <Icon name="globe" />
      </button>
      <button className="fx-oauth-btn" title="飞书">
        <Icon name="mail" />
      </button>
    </div>
  );
}

function PasswordInput() {
  const [show, setShow] = useState(false);
  return (
    <div className="input-wrap">
      <Input
        type={show ? "text" : "password"}
        placeholder="输入密码"
        icon="lock"
        defaultValue="flux-demo-2026"
      />
      <button
        className="trail btn quiet icon-only sm"
        onClick={() => setShow((v) => !v)}
        aria-label={show ? "隐藏密码" : "显示密码"}
        type="button"
      >
        <Icon name="eye" />
      </button>
    </div>
  );
}

export function Login({ navigate, go }: { navigate: () => void; go: (m: string) => void }) {
  const [region, setRegion] = useState("cn");
  const [remember, setRemember] = useState(true);
  return (
    <AuthShell title="登录到 Flux" sub="欢迎回来，继续你的工作区">
      <OAuthRow />
      <div className="fx-auth-sep">或使用邮箱登录</div>
      <div className="col gap-4">
        <Field label="邮箱">
          <Input type="email" icon="mail" defaultValue="qiuli@flux.dev" />
        </Field>
        <Field label="密码">
          <PasswordInput />
        </Field>
        <Field label="登录区域">
          <Select value={region} options={REGION_OPTS} onChange={setRegion} width="100%" />
        </Field>
        <div className="between">
          <Check label="记住我" checked={remember} onChange={setRemember} />
          <button className="btn quiet sm" onClick={() => go("forgot")}>
            忘记密码？
          </button>
        </div>
        <Button variant="primary" size="lg" block icon="arrowright" onClick={navigate}>
          登录
        </Button>
      </div>
      <div className="fx-auth-alt">
        还没有账号？{" "}
        <button className="btn quiet sm" style={{ padding: 0, height: "auto", color: "var(--brand-3)" }} onClick={() => go("register")}>
          注册一个
        </button>
      </div>
    </AuthShell>
  );
}

export function Register({ navigate, go }: { navigate: () => void; go: (m: string) => void }) {
  const [agree, setAgree] = useState(false);
  const [role, setRole] = useState("dev");
  return (
    <AuthShell title="创建账号" sub="几十秒即可开始">
      <OAuthRow />
      <div className="fx-auth-sep">或使用邮箱注册</div>
      <div className="col gap-4">
        <Field label="昵称" required>
          <Input icon="user" placeholder="例如：秋离" defaultValue="秋离" />
        </Field>
        <Field label="邮箱" required>
          <Input type="email" icon="mail" placeholder="you@example.com" />
        </Field>
        <Field label="使用场景">
          <Select
            value={role}
            onChange={setRole}
            options={[
              { value: "dev", label: "个人开发者" },
              { value: "team", label: "团队协作" },
              { value: "company", label: "企业 / 组织" },
            ]}
            width="100%"
          />
        </Field>
        <Field label="设置密码" required hint="至少 8 位，含字母与数字">
          <PasswordInput />
        </Field>
        <Check label="我已阅读并同意服务条款与隐私政策" checked={agree} onChange={setAgree} />
        <Button variant="primary" size="lg" block icon="arrowright" disabled={!agree} onClick={navigate}>
          创建账号
        </Button>
      </div>
      <div className="fx-auth-alt">
        已有账号？{" "}
        <button className="btn quiet sm" style={{ padding: 0, height: "auto", color: "var(--brand-3)" }} onClick={() => go("login")}>
          去登录
        </button>
      </div>
    </AuthShell>
  );
}

export function Forgot({ go }: { go: (m: string) => void }) {
  const [sent, setSent] = useState(false);
  return (
    <AuthShell title="找回密码" sub="输入注册邮箱，我们会发送重置链接">
      <div className="col gap-4">
        <Field label="邮箱">
          <Input type="email" icon="mail" placeholder="you@example.com" />
        </Field>
        {sent ? (
          <div className="card pad" style={{ borderColor: "var(--brand-line)" }}>
            <div className="row gap-2" style={{ color: "var(--green)" }}>
              <Icon name="check" /> 重置链接已发送，请查收邮件
            </div>
            <div className="dim" style={{ fontSize: 11.5, marginTop: 6 }}>
              链接 30 分钟内有效，过期可重新发送
            </div>
          </div>
        ) : null}
        <Button variant="primary" size="lg" block icon="mail" onClick={() => setSent(true)}>
          发送重置链接
        </Button>
        <button className="btn ghost block" onClick={() => go("login")}>
          返回登录
        </button>
      </div>
    </AuthShell>
  );
}

/* ---------------- 账号设置（应用壳内） ---------------- */
export function Account() {
  const [twoFa, setTwoFa] = useState(false);
  const [publicProfile, setPublicProfile] = useState(true);
  const [plan, setPlan] = useState("pro");
  return (
    <>
      <div className="page-head">
        <div>
          <h1 className="page-title">账号设置</h1>
          <p className="page-desc">个人资料、安全与登录方式。</p>
        </div>
        <div className="page-tools">
          <Button variant="primary" icon="check">
            保存更改
          </Button>
        </div>
      </div>

      <div className="grid c2" style={{ alignItems: "start" }}>
        <Card pad>
          <div className="section-title" style={{ marginBottom: 16 }}>
            个人资料
          </div>
          <div className="row gap-4" style={{ marginBottom: 18 }}>
            <Avatar name="秋离" size="lg" />
            <div>
              <Button variant="ghost" size="sm" icon="upload">
                更换头像
              </Button>
              <div className="dim" style={{ fontSize: 11, marginTop: 6 }}>
                支持 PNG / JPG，≤2MB
              </div>
            </div>
          </div>
          <div className="col gap-4">
            <Field label="昵称">
              <Input defaultValue="秋离" />
            </Field>
            <Field label="邮箱">
              <Input type="email" icon="mail" defaultValue="qiuli@flux.dev" />
            </Field>
            <Field label="个人主页">
              <Input icon="globe" defaultValue="https://flux.dev/@qiuli" />
            </Field>
            <div className="between">
              <div>
                <div style={{ fontSize: 13, fontWeight: 550 }}>公开资料</div>
                <div className="dim" style={{ fontSize: 11.5 }}>
                  允许其他成员查看你的昵称与主页
                </div>
              </div>
              <Switch on={publicProfile} onChange={setPublicProfile} />
            </div>
          </div>
        </Card>

        <div className="col gap-3" style={{ gap: 16 }}>
          <Card pad>
            <div className="section-title" style={{ marginBottom: 14 }}>
              安全
            </div>
            <div className="between" style={{ paddingBottom: 14, borderBottom: "1px solid var(--line-soft)" }}>
              <div>
                <div style={{ fontSize: 13, fontWeight: 550 }}>两步验证</div>
                <div className="dim" style={{ fontSize: 11.5 }}>
                  登录时需额外验证码
                </div>
              </div>
              <Switch on={twoFa} onChange={setTwoFa} />
            </div>
            <div className="between" style={{ paddingTop: 14 }}>
              <div>
                <div style={{ fontSize: 13, fontWeight: 550 }}>修改密码</div>
                <div className="dim" style={{ fontSize: 11.5 }}>
                  上次修改：3 个月前
                </div>
              </div>
              <Button variant="ghost" size="sm">
                修改
              </Button>
            </div>
          </Card>

          <Card pad>
            <div className="section-title" style={{ marginBottom: 14 }}>
              订阅与登录方式
            </div>
            <Field label="当前套餐">
              <Select
                value={plan}
                onChange={setPlan}
                options={[
                  { value: "free", label: "Free", meta: "¥0" },
                  { value: "pro", label: "Pro", meta: "¥72/月" },
                  { value: "team", label: "Team", meta: "¥168/月" },
                ]}
                width="100%"
              />
            </Field>
            <div className="divider" style={{ margin: "16px 0" }} />
            <div className="col gap-2" style={{ fontSize: 12.5 }}>
              <div className="row gap-2">
                <Icon name="git" className="icon-sm" /> GitHub · 已绑定
              </div>
              <div className="row gap-2">
                <Icon name="mail" className="icon-sm" /> 飞书 · 已绑定
                <span className="grow" />
                <Badge tone="green" dot>
                  当前会话
                </Badge>
              </div>
            </div>
          </Card>
        </div>
      </div>
    </>
  );
}

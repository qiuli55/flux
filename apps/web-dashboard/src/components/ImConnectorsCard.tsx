/**
 * 「连接器」里的飞书单聊 / 个人微信（官方微信 ClawBot）入口。
 *
 * 两条连接都跑在 Flux 常驻服务端：飞书用官方长连接收事件，微信用官方 iLink
 * getupdates 长轮询。浏览器只负责展示状态、扫码与断开，不持有任何平台令牌。
 */
import { useCallback, useEffect, useRef, useState } from "react";

import { api } from "../api/client";
import type { ImConnectorOverview, ImConnectorStatus, WechatLoginSession } from "../api/types";
import { toast } from "../app/toast";

function statusText(status?: ImConnectorStatus | null): string {
  if (!status) return "正在读取连接状态…";
  if (status.connected) return "已连接";
  if (status.needs_reauth) return "登录已过期，需要重新扫码";
  if (status.error) return "连接异常";
  if (status.waiting_for_message) return "长连接已启动，等待你在飞书发第一条消息";
  return "未连接";
}

function QrCanvas({ rows }: { rows: string[] }) {
  const ref = useRef<HTMLCanvasElement | null>(null);
  useEffect(() => {
    const canvas = ref.current;
    if (!canvas || rows.length === 0) return;
    const columns = rows[0]?.length ?? 0;
    if (columns === 0) return;
    const cell = 4;
    const quiet = 2;
    const size = (columns + quiet * 2) * cell;
    canvas.width = size;
    canvas.height = size;
    const context = canvas.getContext("2d");
    if (!context) return;
    context.fillStyle = "#ffffff";
    context.fillRect(0, 0, size, size);
    context.fillStyle = "#000000";
    rows.forEach((row, y) => {
      for (let x = 0; x < row.length; x += 1) {
        if (row[x] === "1") {
          context.fillRect((x + quiet) * cell, (y + quiet) * cell, cell, cell);
        }
      }
    });
  }, [rows]);
  if (rows.length === 0) return null;
  return <canvas ref={ref} style={{ width: 216, height: 216, borderRadius: 10 }} />;
}

export function ImConnectorsCard() {
  const [overview, setOverview] = useState<ImConnectorOverview | null>(null);
  const [login, setLogin] = useState<WechatLoginSession | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [code, setCode] = useState("");
  const timer = useRef<number | null>(null);

  const stopPolling = () => {
    if (timer.current !== null) window.clearInterval(timer.current);
    timer.current = null;
  };

  const load = useCallback(async () => {
    try {
      const next = await api.imConnectors();
      setOverview(next);
      setLogin((current) => next.wechat.pending_login ?? (current?.state === "pending" || current?.state === "scaned" || current?.state === "need_verifycode" ? current : null));
    } catch (err) {
      setError(err instanceof Error ? err.message : "读取连接状态失败");
    }
  }, []);

  useEffect(() => {
    void load();
    return () => stopPolling();
  }, [load]);

  const poll = (loginId: string) => {
    stopPolling();
    timer.current = window.setInterval(async () => {
      try {
        const next = await api.wechatLoginStatus(loginId);
        setLogin(next);
        if (next.state === "confirmed") {
          stopPolling();
          toast("个人微信已连接；请先在微信 ClawBot 会话里发一句话");
          await load();
        } else if (next.state === "failed") {
          stopPolling();
          toast(next.message || "微信绑定失败，请重新扫码");
          await load();
        }
      } catch {
        stopPolling();
      }
    }, 2000);
  };

  const connectFeishu = async () => {
    setBusy("feishu");
    setError(null);
    try {
      const status = await api.connectFeishu();
      setOverview((current) => (current ? { ...current, feishu: status } : current));
      toast("飞书长连接已启动，请在飞书里给机器人发第一条单聊消息");
      await load();
    } catch (err) {
      setError(err instanceof Error ? err.message : "启动飞书连接失败");
    } finally {
      setBusy(null);
    }
  };

  const disconnectFeishu = async () => {
    setBusy("feishu");
    setError(null);
    try {
      const status = await api.disconnectFeishu();
      setOverview((current) => (current ? { ...current, feishu: status } : current));
      toast("已断开飞书单聊");
    } catch (err) {
      setError(err instanceof Error ? err.message : "断开飞书失败");
    } finally {
      setBusy(null);
    }
  };

  const connectWechat = async () => {
    setBusy("wechat");
    setError(null);
    try {
      const session = await api.connectWechat();
      setLogin(session);
      poll(session.login_id);
    } catch (err) {
      setError(err instanceof Error ? err.message : "申请微信绑定二维码失败");
    } finally {
      setBusy(null);
    }
  };

  const submitCode = async () => {
    if (!login) return;
    if (!code.trim()) {
      setError("请输入手机微信显示的数字");
      return;
    }
    setBusy("wechat");
    setError(null);
    try {
      const next = await api.submitWechatCode(login.login_id, code.trim());
      setLogin(next);
      setCode("");
      poll(next.login_id);
    } catch (err) {
      setError(err instanceof Error ? err.message : "提交数字失败");
    } finally {
      setBusy(null);
    }
  };

  const disconnectWechat = async () => {
    stopPolling();
    setBusy("wechat");
    setError(null);
    try {
      const status = await api.disconnectWechat();
      setOverview((current) => (current ? { ...current, wechat: status } : current));
      setLogin(null);
      toast("已断开个人微信");
    } catch (err) {
      setError(err instanceof Error ? err.message : "断开个人微信失败");
    } finally {
      setBusy(null);
    }
  };

  const feishu = overview?.feishu;
  const wechat = overview?.wechat;
  const activeLogin = login && login.state !== "failed" && login.state !== "confirmed" ? login : null;

  return (
    <section className="card side-card">
      <div className="side-head">
        <b>IM 连接器</b>
        <button type="button" className="link" onClick={() => void load()}>
          刷新
        </button>
      </div>
      <p className="ext-agent-note">
        飞书单聊与个人微信都跑在 Flux 常驻服务端，电脑关机也不会断；这里只负责授权与状态。
      </p>
      {error ? <div className="empty-note">{error}</div> : null}

      <div className="side-item">
        <span className="tm-ava a3">飞书</span>
        <div className="si-main">
          <b>飞书</b>
          <i>{statusText(feishu)}</i>
          {feishu?.error ? <i style={{ color: "#e05a4f" }}>{feishu.error}</i> : null}
          <i>
            在飞书开放平台创建企业自建应用，开启「长连接接收事件」并订阅
            im.message.receive_v1，只处理单聊。
          </i>
          <i>
            服务端需配置 FLUX_FEISHU_IM_APP_ID / FLUX_FEISHU_IM_APP_SECRET；App 里不会显示密钥。
          </i>
        </div>
        {feishu?.connected || feishu?.bound || feishu?.waiting_for_message ? (
          <button
            type="button"
            className="btn btn-xs"
            disabled={busy === "feishu"}
            onClick={() => void disconnectFeishu()}
          >
            {busy === "feishu" ? "断开中…" : "断开"}
          </button>
        ) : (
          <button
            type="button"
            className="btn btn-xs btn-primary"
            disabled={busy === "feishu"}
            onClick={() => void connectFeishu()}
          >
            {busy === "feishu" ? "连接中…" : "连接飞书"}
          </button>
        )}
      </div>

      <div className="side-item">
        <span className="tm-ava a3">微信</span>
        <div className="si-main">
          <b>个人微信</b>
          <i>{statusText(wechat)}</i>
          {wechat?.error ? <i style={{ color: "#e05a4f" }}>{wechat.error}</i> : null}
          <i>
            需要 iOS 微信 8.0.70+ 或 Android 8.0.69+，并在「我 → 设置 → 插件」启用「微信
            ClawBot」。这两条是常见前提，官方 README 并未写死这些版本号。
            插件列表没有该项说明还未灰度到，Flux 不会假装连接成功。
          </i>
          <i>
            绑定成功后请先在微信 ClawBot 会话里发一句话；没有 context_token 时回复发不出去。
          </i>
          <i>
            同一个微信号不要再绑定到另一个 ClawBot / OpenClaw 后端，否则消息可能被分流。
          </i>
          {wechat?.capabilities?.proactive_window_note ? (
            <i>{wechat.capabilities.proactive_window_note}</i>
          ) : null}
          {wechat && wechat.pending_notices > 0 ? (
            <i>有 {wechat.pending_notices} 条提醒等你下次开口后补发。</i>
          ) : null}
          {activeLogin ? (
            <div style={{ marginTop: 8 }}>
              <QrCanvas rows={activeLogin.qrcode_matrix} />
              <i>{activeLogin.message}</i>
              {activeLogin.backup_url ? (
                <a
                  className="btn btn-xs"
                  href={activeLogin.backup_url}
                  target="_blank"
                  rel="noreferrer"
                >
                  在微信中打开备份链接
                </a>
              ) : null}
              {activeLogin.needs_verify_code ? (
                <div className="ext-agent-actions">
                  <input
                    value={code}
                    onChange={(event) => setCode(event.target.value)}
                    placeholder="手机微信显示的数字"
                  />
                  <button
                    type="button"
                    className="btn btn-xs"
                    disabled={busy === "wechat"}
                    onClick={() => void submitCode()}
                  >
                    提交数字
                  </button>
                </div>
              ) : null}
            </div>
          ) : null}
        </div>
        {activeLogin ? null : wechat?.connected ? (
          <div className="ext-agent-actions">
            <button
              type="button"
              className="btn btn-xs"
              disabled={busy === "wechat"}
              onClick={() => void connectWechat()}
            >
              重新扫码
            </button>
            <button
              type="button"
              className="btn btn-xs"
              disabled={busy === "wechat"}
              onClick={() => void disconnectWechat()}
            >
              断开
            </button>
          </div>
        ) : (
          <button
            type="button"
            className="btn btn-xs btn-primary"
            disabled={busy === "wechat"}
            onClick={() => void connectWechat()}
          >
            {busy === "wechat" ? "申请中…" : "连接个人微信"}
          </button>
        )}
      </div>
    </section>
  );
}

/**
 * Flux WeChat 插件：把微信消息转给 Flux 助理，OpenClaw 只做传输层。
 *
 * 走 `before_agent_reply` 认领型 hook：
 * - 拿到 Flux 回复 → `{ handled: true, reply: { text } }`，短路 OpenClaw 原生模型；
 * - Flux 明确表示重复消息 → `{ handled: true }`，静默跳过，避免重复发送；
 * - Flux 不可用 / 未配置 / 返回异常 → `undefined`，交回 OpenClaw 原生模型兜底。
 */

import { definePluginEntry } from "openclaw/plugin-sdk/plugin-entry";

interface FluxConfig {
  fluxUrl?: string;
  bridgeToken?: string;
  channel?: string;
  timeoutMs?: number;
}

interface HookContext {
  sessionKey?: string;
  senderId?: string;
  messageId?: string;
  runId?: string;
  channel?: string;
}

interface HookEvent {
  cleanedBody?: string;
  body?: string;
}

interface FluxReply {
  handled?: boolean;
  duplicate?: boolean;
  reply?: { text?: string };
}

const DEFAULT_FLUX_URL = "http://127.0.0.1:8000/api/v1/supervisor/channels/wechat";
const DEFAULT_TIMEOUT_MS = 180_000;

const configSchema = {
  type: "object",
  additionalProperties: false,
  properties: {
    fluxUrl: { type: "string", default: DEFAULT_FLUX_URL },
    bridgeToken: { type: "string", default: "" },
    channel: { type: "string", default: "wechat" },
    timeoutMs: { type: "number", default: DEFAULT_TIMEOUT_MS },
  },
};

export default definePluginEntry({
  id: "flux-wechat",
  name: "Flux WeChat",
  description: "把微信消息交给 Flux 处理，用 Flux 的知识库与项目记忆回复。",
  configSchema,
  register(api: any) {
    api.on(
      "before_agent_reply",
      async (event: HookEvent, ctx: HookContext) => {
        const config: FluxConfig = api.pluginConfig ?? {};
        const fluxUrl = (config.fluxUrl || DEFAULT_FLUX_URL).trim();
        const token = (
          config.bridgeToken ||
          process.env.FLUX_SUPERVISOR_WECHAT_BRIDGE_TOKEN ||
          ""
        ).trim();
        if (!fluxUrl || !token) {
          api.logger?.warn?.(
            "flux-wechat: 缺少 fluxUrl 或 bridgeToken，跳过（请在插件配置里填写）",
          );
          return undefined;
        }

        // 通道过滤：ctx.channel 缺失时不拦截，避免不同 OpenClaw 版本字段差异导致全丢。
        const onlyChannel = (config.channel ?? "").trim();
        if (onlyChannel && ctx.channel && ctx.channel !== onlyChannel) {
          return undefined;
        }

        const text = (event.cleanedBody || event.body || "").trim();
        if (!text) {
          return undefined;
        }

        const sessionKey = (ctx.sessionKey || "").trim();
        const messageId = (
          ctx.messageId ||
          ctx.runId ||
          `${sessionKey}:${Date.now()}`
        ).trim();
        const senderId = (ctx.senderId || sessionKey).trim();
        if (!messageId || !senderId) {
          api.logger?.warn?.("flux-wechat: 消息缺少 messageId / senderId，跳过");
          return undefined;
        }

        const timeoutMs = Number(config.timeoutMs) || DEFAULT_TIMEOUT_MS;
        const controller = new AbortController();
        const timer = setTimeout(() => controller.abort(), timeoutMs);
        try {
          const response = await fetch(fluxUrl, {
            method: "POST",
            headers: {
              "Content-Type": "application/json",
              Authorization: `Bearer ${token}`,
            },
            body: JSON.stringify({
              message_id: messageId,
              session_key: sessionKey || senderId,
              sender_id: senderId,
              text,
            }),
            signal: controller.signal,
          });
          if (!response.ok) {
            api.logger?.warn?.(
              `flux-wechat: Flux 返回 ${response.status}，交回 OpenClaw 原生模型`,
            );
            return undefined;
          }
          const data = (await response.json()) as FluxReply;
          if (data?.handled !== true) {
            return undefined;
          }
          if (data.duplicate === true) {
            // 重试的消息：静默，回复早已发过
            return { handled: true };
          }
          const replyText =
            typeof data.reply?.text === "string" ? data.reply.text.trim() : "";
          if (!replyText) {
            return { handled: true };
          }
          return { handled: true, reply: { text: replyText } };
        } catch (error) {
          api.logger?.warn?.(`flux-wechat: 调用 Flux 失败，交回原生模型：${error}`);
          return undefined;
        } finally {
          clearTimeout(timer);
        }
      },
      { eligibleTriggers: ["user"], timeoutMs: 240_000 },
    );
  },
});

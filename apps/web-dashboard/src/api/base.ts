/**
 * 前后端共享的基址常量。
 *
 * 单独成文件是为了断开 `api/client.ts` ↔ `api/auth.ts` 的循环依赖：
 * client 每次请求都要注入令牌（依赖 auth），auth 又需要用同一个 API 前缀发续期请求。
 */
export const API_BASE = (import.meta.env.VITE_API_BASE ?? "/api/v1").replace(/\/$/, "");

/** 账号云（B 机）地址：未登录时把浏览器送去它的登录页。 */
export const CLOUD_BASE_URL = (
  import.meta.env.VITE_FLUX_CLOUD_URL ?? "https://flux.qiuli55.top"
).replace(/\/$/, "");

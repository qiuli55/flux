/**
 * 浏览器侧的 Flux 云账号会话（HttpOnly cookie）。
 *
 * 账号的权威全在账号云（B 机 `flux.qiuli55.top`），Flux 本体只做本地验签。
 * 安全审查 P1-2 之后，浏览器**不再接触令牌**：
 * - 登录回跳带回一次性 `code`，`POST /cloud/exchange` 由后端用 Set-Cookie 下发
 *   HttpOnly 的 `flux_auth_token` / `flux_refresh_token`；页面脚本读不到，也不会
 *   再往 localStorage 或 `document.cookie` 里写令牌；
 * - 之后所有对 Flux 后端的请求都带 `credentials: "include"`，浏览器自动附带 cookie；
 * - 登录态以 `GET /cloud/session`（带 cookie）为准，而不是看本地存储；
 * - access token 过期时 `POST /cloud/refresh` 只靠 HttpOnly refresh cookie 续期；
 * - 退出登录必须 `POST /cloud/logout`，由后端清除 cookie。
 *
 * 内存里只保留用户展示信息（id / username），刷新页面后重新向 `/cloud/session` 要。
 */
import { API_BASE, CLOUD_BASE_URL } from "./base";

/** 到期前的兜底值不再需要：有效期由后端 cookie 的 Max-Age 决定。 */

export interface CloudUser {
  id: string;
  username: string;
}

/** 启动引导的三种结局。 */
export type AuthBootstrap = "authenticated" | "login-required" | "no-cloud";

interface CloudSessionInfo {
  authenticated: boolean;
  configured: boolean;
  user: CloudUser | null;
  loginUrl: string;
}

interface Envelope {
  success?: boolean;
  message?: string;
  data?: unknown;
}

/** 仅内存中的当前用户展示信息（不是令牌，也不落 localStorage）。 */
let currentUser: CloudUser | null = null;

function str(value: unknown, fallback = ""): string {
  return typeof value === "string" ? value : fallback;
}

function toUser(value: unknown): CloudUser | null {
  if (typeof value !== "object" || value === null) return null;
  const record = value as Record<string, unknown>;
  const id = str(record.id);
  if (!id) return null;
  return { id, username: str(record.username) };
}

/** 统一的 Flux 后端请求：同源 / 反代下由浏览器自动带上后端下发的 HttpOnly cookie。 */
function cloudFetch(path: string, init: RequestInit = {}): Promise<Response> {
  return fetch(`${API_BASE}${path}`, { credentials: "include", ...init });
}

async function readEnvelope(response: Response): Promise<Envelope | null> {
  return (await response.json().catch(() => null)) as Envelope | null;
}

async function postCloud(path: string, body?: unknown): Promise<Record<string, unknown>> {
  const response = await cloudFetch(path, {
    method: "POST",
    headers: body !== undefined ? { "Content-Type": "application/json" } : undefined,
    body: body !== undefined ? JSON.stringify(body) : undefined,
  });
  const envelope = await readEnvelope(response);
  if (!response.ok || !envelope || envelope.success === false) {
    throw new Error(envelope?.message || `登录请求失败（HTTP ${response.status}）`);
  }
  return (envelope.data ?? {}) as Record<string, unknown>;
}

/** 登录态只问后端（cookie 在浏览器里，JS 看不到）。 */
async function fetchCloudSession(): Promise<CloudSessionInfo | null> {
  try {
    const response = await cloudFetch("/cloud/session");
    const envelope = await readEnvelope(response);
    if (!response.ok || !envelope || envelope.success === false) return null;
    const data = (envelope.data ?? {}) as Record<string, unknown>;
    const authenticated = data.authenticated === true;
    const user = authenticated ? toUser(data.user) : null;
    currentUser = user;
    return {
      authenticated,
      configured: Boolean(data.configured),
      user,
      loginUrl: str(data.login_url) || buildLoginUrl(window.location.href),
    };
  } catch {
    return null;
  }
}

/** 当前内存中的用户展示信息；仅用于 UI，不代表登录态（登录态以 cookie 为准）。 */
export function getAuth(): CloudUser | null {
  return currentUser;
}

/** 清空内存里的用户展示信息（不触碰服务端 cookie）。 */
export function clearAuth(): void {
  currentUser = null;
}

/** 拼账号云登录页地址；`next` 是登录完成后要回到的完整地址。 */
export function buildLoginUrl(next: string): string {
  const login = `${CLOUD_BASE_URL}/login`;
  return next ? `${login}?next=${encodeURIComponent(next)}` : login;
}

let refreshInFlight: Promise<CloudUser | null> | null = null;

/**
 * 用 HttpOnly refresh cookie 续期；失败即清空内存用户并返回 null。
 * 并发 401 只发一次请求（登录码 / refresh token 都是一次性凭据）。
 */
export function refreshAuth(): Promise<CloudUser | null> {
  if (refreshInFlight) return refreshInFlight;
  refreshInFlight = (async () => {
    try {
      // 不传 refresh_token：后端从 HttpOnly cookie 读取，Web 端不再把令牌放进 JS。
      const data = await postCloud("/cloud/refresh");
      const user = toUser(data.user);
      currentUser = user;
      return user;
    } catch {
      currentUser = null;
      return null;
    } finally {
      refreshInFlight = null;
    }
  })();
  return refreshInFlight;
}

/** 退出登录：必须走后端以便清除 HttpOnly cookie。 */
export async function logoutCloudSession(): Promise<void> {
  try {
    await cloudFetch("/cloud/logout", { method: "POST" });
  } catch {
    // 后端不可达时本地仍清掉展示信息；cookie 会在过期后自然失效。
  } finally {
    currentUser = null;
  }
}

async function runBootstrap(): Promise<AuthBootstrap> {
  const url = new URL(window.location.href);
  const code = url.searchParams.get("code");
  if (code) {
    try {
      await postCloud("/cloud/exchange", { code });
    } catch {
      clearAuth();
    }
    // 登录码是一次性的：留在地址栏既没用，又会被刷新重复兑换（第二次必然失败）。
    url.searchParams.delete("code");
    window.history.replaceState(null, "", url.toString());
  }

  // 以带 cookie 的 /cloud/session 为准：cookie 已登录时，localStorage 为空也不误跳登录页。
  const session = await fetchCloudSession();
  if (session?.authenticated) return "authenticated";
  clearAuth();
  // 后端没配账号云（本地开发 / 只用 FLUX_AUTH_TOKEN 的场景）：不拦截。
  if (!session?.configured) return "no-cloud";
  window.location.replace(session.loginUrl || buildLoginUrl(window.location.href));
  return "login-required";
}

let bootstrapInFlight: Promise<AuthBootstrap> | null = null;

/**
 * 启动引导：消化回跳登录码 → 判定登录态 → 必要时跳登录页。
 *
 * 结果被缓存，所以 React StrictMode 的"挂载两次"也只会真正执行一次——
 * 登录码只能用一次，重复兑换必失败。
 */
export function bootstrapAuth(): Promise<AuthBootstrap> {
  if (!bootstrapInFlight) bootstrapInFlight = runBootstrap();
  return bootstrapInFlight;
}

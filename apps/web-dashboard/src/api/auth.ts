/**
 * 浏览器侧的 Flux 云账号会话。
 *
 * 账号的权威全在账号云（B 机 `flux.qiuli55.top`），Flux 本体只做本地验签：
 * - 登录回跳带回一次性 `code`，本机换回 access / refresh token；
 * - access token（默认 30 分钟）存 localStorage，`api/client.ts` 每次请求带上
 *   `Authorization: Bearer`；
 * - 过期由 `refreshAuth()` 续期一次；续不动就清空本地会话并回登录页。
 */
import { API_BASE, CLOUD_BASE_URL } from "./base";

/** localStorage 键名。 */
const STORAGE_KEY = "flux.cloud.session";

/** 到期前多久就当作过期（毫秒），给时钟漂移留一点余量。 */
const EXPIRY_SKEW_MS = 30_000;

/** 后端没给 `expires_in` 时的兜底（秒）。 */
const DEFAULT_EXPIRES_IN = 1800;

export interface CloudAuth {
  accessToken: string;
  refreshToken: string;
  /** 绝对到期时间（epoch 毫秒）。 */
  expiresAt: number;
  user: { id: string; username: string } | null;
}

/** 启动引导的三种结局。 */
export type AuthBootstrap = "authenticated" | "login-required" | "no-cloud";

function str(value: unknown, fallback = ""): string {
  return typeof value === "string" ? value : fallback;
}

function toAuth(data: unknown): CloudAuth | null {
  if (typeof data !== "object" || data === null) return null;
  const record = data as Record<string, unknown>;
  const accessToken = str(record.access_token);
  const refreshToken = str(record.refresh_token);
  if (!accessToken || !refreshToken) return null;
  const expiresIn = typeof record.expires_in === "number" ? record.expires_in : DEFAULT_EXPIRES_IN;
  const raw = typeof record.user === "object" && record.user !== null ? record.user : null;
  const rawUser = raw as Record<string, unknown> | null;
  return {
    accessToken,
    refreshToken,
    expiresAt: Date.now() + expiresIn * 1000,
    user: rawUser ? { id: str(rawUser.id), username: str(rawUser.username) } : null,
  };
}

export function getAuth(): CloudAuth | null {
  if (typeof window === "undefined") return null;
  try {
    const stored = window.localStorage.getItem(STORAGE_KEY);
    return stored ? toAuth(JSON.parse(stored) as unknown) : null;
  } catch {
    return null;
  }
}

export function setAuth(auth: CloudAuth): void {
  if (typeof window === "undefined") return;
  try {
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify(auth));
  } catch {
    // 隐私模式等写不进去：本次会话照常可用，只是刷新页面要重新登录。
  }
}

export function clearAuth(): void {
  if (typeof window === "undefined") return;
  try {
    window.localStorage.removeItem(STORAGE_KEY);
  } catch {
    // 忽略：清理失败最多是被动过期的令牌多留一会儿。
  }
}

/** 当前 access token（不校验有效期：让后端判定，401 再走续期）。 */
export function getAccessToken(): string | null {
  return getAuth()?.accessToken ?? null;
}

/** 令牌是否还在有效期内（含余量）。 */
export function isAuthValid(auth: CloudAuth | null): boolean {
  return auth !== null && Date.now() < auth.expiresAt - EXPIRY_SKEW_MS;
}

/** 拼账号云登录页地址；`next` 是登录完成后要回到的完整地址。 */
export function buildLoginUrl(next: string): string {
  const login = `${CLOUD_BASE_URL}/login`;
  return next ? `${login}?next=${encodeURIComponent(next)}` : login;
}

async function postCloud(path: string, body: unknown): Promise<CloudAuth> {
  let response: Response;
  try {
    response = await fetch(`${API_BASE}${path}`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body),
    });
  } catch {
    throw new Error("无法连接 Flux 后端，登录状态暂不可用");
  }
  const envelope = (await response.json().catch(() => null)) as {
    success?: boolean;
    message?: string;
    data?: unknown;
  } | null;
  if (!response.ok || !envelope || envelope.success === false) {
    throw new Error(envelope?.message || `登录请求失败（HTTP ${response.status}）`);
  }
  const auth = toAuth(envelope.data);
  if (!auth) throw new Error("账号云返回的令牌无法解析");
  setAuth(auth);
  return auth;
}

let refreshInFlight: Promise<CloudAuth | null> | null = null;

/** 用 refresh token 续期；失败即清空本地会话并返回 null。并发调用只发一次请求。 */
export function refreshAuth(): Promise<CloudAuth | null> {
  if (refreshInFlight) return refreshInFlight;
  const current = getAuth();
  if (!current) return Promise.resolve(null);
  refreshInFlight = (async () => {
    try {
      return await postCloud("/cloud/refresh", { refresh_token: current.refreshToken });
    } catch {
      clearAuth();
      return null;
    } finally {
      refreshInFlight = null;
    }
  })();
  return refreshInFlight;
}

async function cloudConfigured(): Promise<boolean> {
  try {
    const response = await fetch(`${API_BASE}/cloud/session`);
    if (!response.ok) return false;
    const envelope = (await response.json()) as { data?: { configured?: boolean } } | null;
    return Boolean(envelope?.data?.configured);
  } catch {
    return false;
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
    // 登录码是一次性的：留在地址栏既没用，又会被刷新重复兑换（第二次必然 401）。
    url.searchParams.delete("code");
    window.history.replaceState(null, "", url.toString());
  }
  if (isAuthValid(getAuth())) return "authenticated";
  clearAuth();
  // 后端没配账号云（本地开发 / 只用 FLUX_AUTH_TOKEN 的场景）：不拦截。
  if (!(await cloudConfigured())) return "no-cloud";
  window.location.replace(buildLoginUrl(window.location.href));
  return "login-required";
}

let bootstrapInFlight: Promise<AuthBootstrap> | null = null;

/**
 * 启动引导：消化回跳登录码 → 判定登录态 → 必要时跳登录页。
 *
 * 结果被缓存，所以 React StrictMode 的"挂载两次"也只会真正执行一次——
 * 登录码只能用一次，重复兑换必 401。
 */
export function bootstrapAuth(): Promise<AuthBootstrap> {
  if (!bootstrapInFlight) bootstrapInFlight = runBootstrap();
  return bootstrapInFlight;
}

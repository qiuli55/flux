/**
 * vite /api 代理语义守护测试（Human Terminal PTY 依赖）。
 *
 * 背景：后端对 PTY WebSocket 做 Origin==Host 同源校验。若代理改写 Host
 * （changeOrigin: true），浏览器 Origin(127.0.0.1:5184) 与后端看到的
 * Host(127.0.0.1:8802) 不匹配 → 握手被 403 拒绝、终端面板无限重连。
 * 该问题在浏览器实测中复现并已修复（见验收报告），此测试防止回退：
 * 与桌面端 WS 转发（apps/desktop/src/main.js）和 nginx 的 Host 透传保持一致。
 */
import { describe, expect, it } from "vitest";

import viteConfigFn from "./vite.config";

type ProxyEntry = { changeOrigin?: boolean; ws?: boolean };
type ResolvedConfig = {
  server: { proxy: Record<string, ProxyEntry> };
  preview: { proxy: Record<string, ProxyEntry> };
};

const resolved = (
  viteConfigFn as unknown as (env: { mode: string; command: string }) => ResolvedConfig
)({ mode: "test", command: "serve" });

describe("vite /api 代理（Human Terminal PTY 依赖）", () => {
  it("dev 与 preview 代理都不得改写 Host，否则 PTY WS 同源校验会 403", () => {
    expect(resolved.server.proxy["/api"]?.changeOrigin).toBe(false);
    expect(resolved.preview.proxy["/api"]?.changeOrigin).toBe(false);
  });

  it("WebSocket 代理开关保持开启", () => {
    expect(resolved.server.proxy["/api"]?.ws).toBe(true);
    expect(resolved.preview.proxy["/api"]?.ws).toBe(true);
  });
});
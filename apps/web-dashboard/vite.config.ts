import react from "@vitejs/plugin-react";
import tailwindcss from "@tailwindcss/vite";
import { defineConfig, loadEnv } from "vite";

const DEFAULT_BACKEND_ORIGIN = "http://127.0.0.1:8000";

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, ".", "");
  const backendOrigin = env.FLUX_VITE_BACKEND_ORIGIN || DEFAULT_BACKEND_ORIGIN;
  const basePath = env.FLUX_VITE_BASE || "/";

  // /api 下的 HTTP 与 WebSocket 请求统一代理到后端。
  // Human Terminal 的 PTY 通道使用 /api/v1/terminal/pty/... WebSocket。
  // changeOrigin 必须为 false：后端对 PTY WS 做 Origin==Host 同源校验，
  // 改写 Host 会让浏览器 Origin(5184) 与 Host(8802) 不匹配，握手被 403 拒绝
  // （与桌面端 apps/desktop/src/main.js 的 WS 转发、nginx 的 Host 透传保持一致）。
  const apiProxy = {
    "/api": {
      target: backendOrigin,
      changeOrigin: false,
      ws: true,
    },
  };

  return {
    base: basePath,
    plugins: [react(), tailwindcss()],
    server: {
      host: true,
      port: 5180,
      strictPort: true,
      allowedHosts: ["flux.qiuli55.top"],
      proxy: apiProxy,
    },
    preview: {
      host: true,
      port: 5180,
      strictPort: true,
      allowedHosts: ["flux.qiuli55.top"],
      proxy: apiProxy,
    },
  };
});

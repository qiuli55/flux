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
  const apiProxy = {
    "/api": {
      target: backendOrigin,
      changeOrigin: true,
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

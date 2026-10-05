import react from "@vitejs/plugin-react";
import tailwindcss from "@tailwindcss/vite";
import { defineConfig, loadEnv } from "vite";

// 后端默认监听 127.0.0.1:8000（make run / docker-compose.yml / backend/Dockerfile 三者一致）。
// 本机 8000 被其它服务占用、或后端跑在别的端口时，用 FLUX_VITE_BACKEND_ORIGIN 覆盖：
// 支持 shell 环境变量，或写进 apps/web-dashboard/.env.local（已被根 .gitignore 忽略）；
// 两者同时设置时 shell 变量优先（Vite 6.4.3 实测）。
const DEFAULT_BACKEND_ORIGIN = "http://127.0.0.1:8000";

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, ".", "");
  const backendOrigin = env.FLUX_VITE_BACKEND_ORIGIN || DEFAULT_BACKEND_ORIGIN;
  // 子路径部署：默认 "/"（本地开发不变）。远端经 nginx 反代访问时用
  // FLUX_VITE_BASE=/web/ 启动，让 HTML 里的资源引用带 /web/ 前缀。
  // （loadEnv 会把 shell 环境变量一并纳入，无需直接读 process.env）
  const basePath = env.FLUX_VITE_BASE || "/";

  // /api 下的请求统一代理到后端（仅当请求直接到 vite 时生效；
  // 经 nginx 部署时 /api/ 由 nginx 直接转发到后端）
  const apiProxy = {
    "/api": {
      target: backendOrigin,
      changeOrigin: true,
    },
  };

  return {
    base: basePath,
    plugins: [react(), tailwindcss()],
    server: {
      host: true,
      port: 5180,
      strictPort: true,
      // 远端通过 nginx /web/ 反代访问时，vite 看到的 Host 是 flux.qiuli55.top，
      // 默认会拒（vite 6+ 的 DNS-rebinding 防护）。
      // 只把这一个域名加白名单，比把 allowedHosts 设成 true 更稳。
      allowedHosts: ['flux.qiuli55.top'],
      proxy: apiProxy,
    },
    preview: {
      host: true,
      port: 5180,
      strictPort: true,
      allowedHosts: ['flux.qiuli55.top'],
      proxy: apiProxy,
    },
  };
});
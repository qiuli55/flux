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

  // /api 下的请求统一代理到后端
  const apiProxy = {
    "/api": {
      target: backendOrigin,
      changeOrigin: true,
    },
  };

  return {
    plugins: [react(), tailwindcss()],
    server: {
      host: true,
      port: 5180,
      strictPort: true,
      proxy: apiProxy,
    },
    preview: {
      host: true,
      port: 5180,
      strictPort: true,
      proxy: apiProxy,
    },
  };
});
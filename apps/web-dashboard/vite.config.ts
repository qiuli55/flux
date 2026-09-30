import react from "@vitejs/plugin-react";
import tailwindcss from "@tailwindcss/vite";
import { defineConfig } from "vite";

// 后端监听 127.0.0.1:8010；前端只发同源 /api 请求，由 Vite 代理转发，避免 CORS。
const BACKEND_ORIGIN = "http://127.0.0.1:8010";

// /api 下的请求统一代理到后端
const apiProxy = {
  "/api": {
    target: BACKEND_ORIGIN,
    changeOrigin: true,
  },
};

export default defineConfig({
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
});
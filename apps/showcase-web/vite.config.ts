import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

/**
 * showcase-web 构建配置：本地演示用，固定端口 5188。
 * base 设为 "./" 让产物在任意子路径下都能加载资源。
 */
export default defineConfig({
  base: "./",
  plugins: [react()],
  server: { port: 5188, strictPort: true, host: "127.0.0.1" },
  preview: { port: 5188, strictPort: true, host: "127.0.0.1" },
});

"use strict";

/**
 * 最小 preload：前端是纯 Web 应用，通过同源 http://127.0.0.1:<uiPort>/api/v1 访问后端，
 * 不需要经 IPC 暴露任何原生能力。这里只注入一个只读标识，便于前端在桌面端做轻量分支。
 * contextIsolation 保持开启（renderer 无 Node 权限）。
 */

const { contextBridge } = require("electron");

contextBridge.exposeInMainWorld("fluxDesktop", {
  isDesktop: true,
  platform: process.platform,
});

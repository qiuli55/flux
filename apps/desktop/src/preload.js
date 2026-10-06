"use strict";

/**
 * 最小安全桥：renderer 没有 Node 权限，只通过 contextBridge 暴露桌面端能力。
 */

const { contextBridge, ipcRenderer } = require("electron");

contextBridge.exposeInMainWorld("fluxDesktop", {
  isDesktop: true,
  platform: process.platform,
  chooseWorkspaceRoot: () => ipcRenderer.invoke("flux:choose-workspace-root"),
  onWorkspaceChanged: (callback) => {
    if (typeof callback !== "function") return () => {};
    const listener = (_event, root) => callback(root);
    ipcRenderer.on("flux:workspace-changed", listener);
    return () => ipcRenderer.removeListener("flux:workspace-changed", listener);
  },
});

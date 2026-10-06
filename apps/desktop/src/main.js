"use strict";

/**
 * Flux 桌面端主进程（Electron）。职责：
 *  1. 选空闲端口拉起 Python 后端（打包态为 PyInstaller sidecar，开发态为 .venv uvicorn）；
 *  2. 轮询 /api/v1/health 直到就绪（超时给可读错误，不静默）；
 *  3. 在另一个空闲端口起「极简静态服务器 + /api 反向代理」，让前端同源相对路径 /api/v1 不改；
 *  4. 打开主窗口；另提供 Agent Terminal 独立窗口（菜单 + Ctrl/Cmd+Shift+T），可单独关闭；
 *  5. 退出时终止后端进程树。
 *
 * 数据目录默认 ~/.flux（可用 FLUX_DATA_DIR 覆盖）：数据库 / 工作区 / 备份都落在这里，
 * 卸载不删除，重装可继续使用。
 */

const { app, BrowserWindow, Menu, dialog, shell } = require("electron");
const http = require("http");
const fs = require("fs");
const net = require("net");
const os = require("os");
const path = require("path");
const { spawn } = require("child_process");

const HEALTH_TIMEOUT_MS = 90 * 1000;
const BACKEND_LOG_LIMIT = 4000;

let backendProc = null;
let backendPort = 0;
let uiPort = 0;
let uiServer = null;
let mainWindow = null;
let terminalWindow = null;
let isQuitting = false;
let backendLogTail = [];

/* ------------------------------ 路径与数据目录 ------------------------------ */

function repoRoot() {
  // __dirname = apps/desktop/src → 上溯三级为仓库根
  return path.resolve(__dirname, "..", "..", "..");
}

function resolveWebRoot() {
  if (app.isPackaged) {
    return path.join(process.resourcesPath, "web");
  }
  return path.join(repoRoot(), "apps", "web-dashboard", "dist");
}

function resolveDataDir() {
  const override = process.env.FLUX_DATA_DIR;
  const dir = override
    ? path.resolve(override)
    : path.join(os.homedir(), ".flux");
  fs.mkdirSync(dir, { recursive: true });
  fs.mkdirSync(path.join(dir, "workspace"), { recursive: true });
  fs.mkdirSync(path.join(dir, "backups"), { recursive: true });
  return dir;
}

/** 组 SQLite URL：Windows 盘符路径要转成正斜杠，避免反斜杠被当转义。 */
function sqliteUrl(dbFile) {
  return `sqlite+aiosqlite:///${dbFile.split(path.sep).join("/")}`;
}

/* --------------------------------- 空闲端口 --------------------------------- */

function getFreePort() {
  return new Promise((resolve, reject) => {
    const srv = net.createServer();
    srv.unref();
    srv.on("error", reject);
    srv.listen(0, "127.0.0.1", () => {
      const { port } = srv.address();
      srv.close(() => resolve(port));
    });
  });
}

/* --------------------------------- 后端进程 --------------------------------- */

function backendLaunchSpec(port) {
  const isWin = process.platform === "win32";
  if (app.isPackaged) {
    const exe = path.join(
      process.resourcesPath,
      "backend",
      isWin ? "flux-backend.exe" : "flux-backend",
    );
    return { command: exe, args: ["--port", String(port)], cwd: path.dirname(exe), extraEnv: {} };
  }
  const root = repoRoot();
  const python = isWin
    ? path.join(root, ".venv", "Scripts", "python.exe")
    : path.join(root, ".venv", "bin", "python");
  const backendDir = path.join(root, "backend");
  return {
    command: python,
    args: [
      "-m",
      "uvicorn",
      "flux.main:create_app",
      "--factory",
      "--host",
      "127.0.0.1",
      "--port",
      String(port),
    ],
    cwd: backendDir,
    extraEnv: { PYTHONPATH: backendDir },
  };
}

function startBackend(port, dataDir) {
  const spec = backendLaunchSpec(port);
  const env = {
    ...process.env,
    ...spec.extraEnv,
    FLUX_DATA_DIR: dataDir,
    FLUX_DATABASE_URL: sqliteUrl(path.join(dataDir, "flux.db")),
    FLUX_WORKSPACE_ROOT: path.join(dataDir, "workspace"),
  };
  const options = { cwd: spec.cwd, env, stdio: ["ignore", "pipe", "pipe"] };
  // Linux/macOS：detached 让子进程成为新进程组组长，退出时可用 kill(-pid) 杀整棵进程树
  if (process.platform !== "win32") options.detached = true;

  if (!fs.existsSync(spec.command)) {
    throw new Error(`找不到后端可执行文件：${spec.command}\n请先构建 sidecar / 准备 .venv。`);
  }

  backendProc = spawn(spec.command, spec.args, options);
  const capture = (buf) => {
    const text = buf.toString();
    backendLogTail.push(text);
    if (backendLogTail.length > 200) backendLogTail.shift();
    process.stdout.write(`[backend] ${text}`);
  };
  backendProc.stdout.on("data", capture);
  backendProc.stderr.on("data", capture);
  backendProc.on("exit", (code, signal) => {
    if (!isQuitting) {
      console.error(`[flux] 后端进程退出 code=${code} signal=${signal}`);
    }
    backendProc = null;
  });
}

function probeHealth(port) {
  return new Promise((resolve) => {
    const req = http.get(
      { host: "127.0.0.1", port, path: "/api/v1/health", timeout: 2000 },
      (res) => {
        let body = "";
        res.on("data", (chunk) => (body += chunk));
        res.on("end", () => {
          try {
            const parsed = JSON.parse(body);
            resolve(res.statusCode === 200 && parsed.success === true);
          } catch {
            resolve(false);
          }
        });
      },
    );
    req.on("timeout", () => req.destroy());
    req.on("error", () => resolve(false));
  });
}

const delay = (ms) => new Promise((r) => setTimeout(r, ms));

async function waitForHealth(port) {
  const deadline = Date.now() + HEALTH_TIMEOUT_MS;
  while (Date.now() < deadline) {
    if (!backendProc) {
      throw new Error(`后端进程在就绪前退出。\n最近日志：\n${backendLogTail.join("").slice(-BACKEND_LOG_LIMIT)}`);
    }
    if (await probeHealth(port)) return;
    await delay(300);
  }
  throw new Error(
    `等待后端就绪超时（${HEALTH_TIMEOUT_MS / 1000}s）：http://127.0.0.1:${port}/api/v1/health 无响应。\n最近日志：\n${backendLogTail
      .join("")
      .slice(-BACKEND_LOG_LIMIT)}`,
  );
}

function killBackendTree() {
  const proc = backendProc;
  if (!proc || !proc.pid) return;
  try {
    if (process.platform === "win32") {
      spawn("taskkill", ["/PID", String(proc.pid), "/T", "/F"], { stdio: "ignore" });
    } else {
      // detached 子进程组组长 pid == 进程组 id，负号杀整组
      process.kill(-proc.pid, "SIGTERM");
      setTimeout(() => {
        try {
          process.kill(-proc.pid, "SIGKILL");
        } catch {
          /* 已退出 */
        }
      }, 4000);
    }
  } catch (err) {
    console.error(`[flux] 终止后端进程失败：${err}`);
  }
  backendProc = null;
}

/* -------------------------- 静态服务器 + /api 反代 -------------------------- */

const MIME = {
  ".html": "text/html; charset=utf-8",
  ".js": "text/javascript; charset=utf-8",
  ".mjs": "text/javascript; charset=utf-8",
  ".css": "text/css; charset=utf-8",
  ".json": "application/json; charset=utf-8",
  ".svg": "image/svg+xml",
  ".png": "image/png",
  ".jpg": "image/jpeg",
  ".jpeg": "image/jpeg",
  ".gif": "image/gif",
  ".ico": "image/x-icon",
  ".webp": "image/webp",
  ".woff": "font/woff",
  ".woff2": "font/woff2",
  ".ttf": "font/ttf",
  ".map": "application/json; charset=utf-8",
  ".txt": "text/plain; charset=utf-8",
};

function proxyApi(req, res) {
  const headers = { ...req.headers, host: `127.0.0.1:${backendPort}` };
  const proxyReq = http.request(
    { host: "127.0.0.1", port: backendPort, method: req.method, path: req.url, headers },
    (proxyRes) => {
      res.writeHead(proxyRes.statusCode || 502, proxyRes.headers);
      proxyRes.pipe(res); // 直接管道，SSE / 流式响应不被缓冲
    },
  );
  proxyReq.on("error", (err) => {
    if (!res.headersSent) {
      res.writeHead(502, { "content-type": "application/json; charset=utf-8" });
    }
    res.end(JSON.stringify({ success: false, code: "backend_unreachable", message: String(err) }));
  });
  req.pipe(proxyReq);
}

function serveStatic(req, res, webRoot) {
  let pathname;
  try {
    pathname = decodeURIComponent(new URL(req.url, "http://127.0.0.1").pathname);
  } catch {
    res.writeHead(400);
    res.end("bad request");
    return;
  }
  if (pathname === "/") pathname = "/index.html";

  const resolved = path.normalize(path.join(webRoot, pathname));
  const relative = path.relative(webRoot, resolved);
  if (relative === ".." || relative.startsWith(".." + path.sep) || path.isAbsolute(relative)) {
    res.writeHead(403);
    res.end("forbidden");
    return;
  }

  fs.stat(resolved, (err, stat) => {
    let filePath = resolved;
    if (err || !stat.isFile()) {
      // SPA 兜底：无扩展名当作前端路由，回退 index.html
      if (path.extname(pathname)) {
        res.writeHead(404);
        res.end("not found");
        return;
      }
      filePath = path.join(webRoot, "index.html");
    }
    fs.readFile(filePath, (readErr, data) => {
      if (readErr) {
        res.writeHead(404);
        res.end("not found");
        return;
      }
      res.writeHead(200, {
        "content-type": MIME[path.extname(filePath).toLowerCase()] || "application/octet-stream",
        "cache-control": "no-cache",
      });
      res.end(data);
    });
  });
}

function proxyWebSocket(req, socket, head) {
  const requestUrl = req.url || "";
  if (!(requestUrl === "/api" || requestUrl.startsWith("/api/"))) {
    socket.destroy();
    return;
  }

  const upstream = net.connect({ host: "127.0.0.1", port: backendPort });
  upstream.on("connect", () => {
    const lines = [
      `${req.method || "GET"} ${requestUrl} HTTP/${req.httpVersion || "1.1"}`,
      ...Array.from({ length: req.rawHeaders.length / 2 }, (_, index) => {
        const offset = index * 2;
        return `${req.rawHeaders[offset]}: ${req.rawHeaders[offset + 1]}`;
      }),
      "",
      "",
    ];
    upstream.write(lines.join("\r\n"));
    if (head && head.length) upstream.write(head);
    socket.pipe(upstream);
    upstream.pipe(socket);
  });
  upstream.on("error", () => socket.destroy());
  socket.on("error", () => upstream.destroy());
}

function startStaticServer(port, webRoot) {
  return new Promise((resolve, reject) => {
    if (!fs.existsSync(path.join(webRoot, "index.html"))) {
      reject(new Error(`前端静态资源缺失：${webRoot}/index.html（请先 npm run build）`));
      return;
    }
    const server = http.createServer((req, res) => {
      if (req.url === "/api" || req.url.startsWith("/api/") || req.url.startsWith("/api?")) {
        proxyApi(req, res);
        return;
      }
      if (req.method !== "GET" && req.method !== "HEAD") {
        res.writeHead(405);
        res.end("method not allowed");
        return;
      }
      serveStatic(req, res, webRoot);
    });
    server.on("error", reject);
    // Electron 自带的静态服务器不走 Vite dev proxy，因此必须显式转发
    // /api 下的 WebSocket upgrade，否则 Human Terminal 在桌面发行版无法建立 PTY 通道。
    server.on("upgrade", proxyWebSocket);
    server.listen(port, "127.0.0.1", () => resolve(server));
  });
}

/* ---------------------------------- 窗口 ---------------------------------- */

function uiUrl(query = "") {
  return `http://127.0.0.1:${uiPort}/index.html${query}`;
}

function createMainWindow() {
  mainWindow = new BrowserWindow({
    width: 1440,
    height: 900,
    minWidth: 1024,
    minHeight: 640,
    show: false,
    backgroundColor: "#0b0d12",
    webPreferences: {
      preload: path.join(__dirname, "preload.js"),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
    },
  });
  mainWindow.once("ready-to-show", () => mainWindow.show());
  mainWindow.webContents.setWindowOpenHandler(({ url }) => {
    shell.openExternal(url);
    return { action: "deny" };
  });
  mainWindow.on("closed", () => {
    mainWindow = null;
    // 主窗口关闭即退出应用（会一并终止后端）；Agent Terminal 单独关闭不受影响
    if (!isQuitting) app.quit();
  });
  mainWindow.loadURL(uiUrl());
}

function openTerminalWindow() {
  if (terminalWindow && !terminalWindow.isDestroyed()) {
    terminalWindow.focus();
    return;
  }
  terminalWindow = new BrowserWindow({
    width: 1000,
    height: 680,
    minWidth: 720,
    minHeight: 480,
    backgroundColor: "#0b0d12",
    webPreferences: {
      preload: path.join(__dirname, "preload.js"),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
    },
  });
  // 独立窗口只渲染 Agent Terminal（前端按 fluxWindow=terminal 分流）
  terminalWindow.loadURL(uiUrl("?fluxWindow=terminal"));
  terminalWindow.on("closed", () => {
    terminalWindow = null;
  });
}

function buildMenu() {
  const template = [];
  if (process.platform === "darwin") template.push({ role: "appMenu" });
  template.push({
    label: "文件",
    submenu: [{ role: "quit", label: "退出" }],
  });
  template.push({
    label: "窗口",
    submenu: [
      {
        label: "打开 Agent Terminal",
        accelerator: "CmdOrCtrl+Shift+T",
        click: () => openTerminalWindow(),
      },
      { type: "separator" },
      { role: "reload", label: "重新加载" },
      { role: "toggleDevTools", label: "开发者工具" },
      { role: "minimize", label: "最小化" },
      { role: "close", label: "关闭窗口" },
    ],
  });
  Menu.setApplicationMenu(Menu.buildFromTemplate(template));
}

/* --------------------------------- 启动流程 --------------------------------- */

async function bootstrap() {
  const dataDir = resolveDataDir();
  const webRoot = resolveWebRoot();
  console.log(`[flux] 数据目录：${dataDir}`);
  console.log(`[flux] 前端静态根：${webRoot}`);

  backendPort = await getFreePort();
  startBackend(backendPort, dataDir);
  await waitForHealth(backendPort);
  console.log(`[flux] 后端就绪：http://127.0.0.1:${backendPort}`);

  uiPort = await getFreePort();
  uiServer = await startStaticServer(uiPort, webRoot);
  console.log(`[flux] 界面服务：http://127.0.0.1:${uiPort}`);

  buildMenu();
  createMainWindow();
}

app.whenReady().then(() => {
  bootstrap().catch((err) => {
    dialog.showErrorBox("Flux 启动失败", String(err && err.stack ? err.stack : err));
    isQuitting = true;
    killBackendTree();
    app.exit(1);
  });
});

app.on("activate", () => {
  if (BrowserWindow.getAllWindows().length === 0 && uiPort) createMainWindow();
});

app.on("window-all-closed", () => {
  if (process.platform !== "darwin") app.quit();
});

app.on("before-quit", () => {
  isQuitting = true;
  if (uiServer) {
    try {
      uiServer.close();
    } catch {
      /* ignore */
    }
  }
  killBackendTree();
});

"use strict";

const { app, BrowserWindow, Menu, dialog, shell, ipcMain } = require("electron");
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
let currentWorkspaceRoot = null;

function repoRoot() {
  return path.resolve(__dirname, "..", "..", "..");
}

function resolveWebRoot() {
  return app.isPackaged ? path.join(process.resourcesPath, "web") : path.join(repoRoot(), "apps", "web-dashboard", "dist");
}

function resolveDataDir() {
  const override = process.env.FLUX_DATA_DIR;
  const dir = override ? path.resolve(override) : path.join(os.homedir(), ".flux");
  fs.mkdirSync(dir, { recursive: true });
  fs.mkdirSync(path.join(dir, "workspace"), { recursive: true });
  fs.mkdirSync(path.join(dir, "backups"), { recursive: true });
  return dir;
}

function workspaceConfigPath(dataDir) {
  return path.join(dataDir, "workspace.json");
}

function resolveInitialWorkspaceRoot(dataDir) {
  try {
    const parsed = JSON.parse(fs.readFileSync(workspaceConfigPath(dataDir), "utf8"));
    if (typeof parsed.root === "string" && fs.statSync(parsed.root).isDirectory()) return path.resolve(parsed.root);
  } catch {
    /* first run / stale selection */
  }
  return path.join(dataDir, "workspace");
}

function persistWorkspaceRoot(dataDir, root) {
  fs.writeFileSync(workspaceConfigPath(dataDir), JSON.stringify({ root }, null, 2) + "\n", "utf8");
}

function sqliteUrl(dbFile) {
  return `sqlite+aiosqlite:///${dbFile.split(path.sep).join("/")}`;
}

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

function backendLaunchSpec(port) {
  const isWin = process.platform === "win32";
  if (app.isPackaged) {
    const exe = path.join(process.resourcesPath, "backend", isWin ? "flux-backend.exe" : "flux-backend");
    return { command: exe, args: ["--port", String(port)], cwd: path.dirname(exe), extraEnv: {} };
  }
  const root = repoRoot();
  const python = isWin ? path.join(root, ".venv", "Scripts", "python.exe") : path.join(root, ".venv", "bin", "python");
  const backendDir = path.join(root, "backend");
  return {
    command: python,
    args: ["-m", "uvicorn", "flux.main:create_app", "--factory", "--host", "127.0.0.1", "--port", String(port)],
    cwd: backendDir,
    extraEnv: { PYTHONPATH: backendDir },
  };
}

function startBackend(port, dataDir, workspaceRoot) {
  const spec = backendLaunchSpec(port);
  const env = {
    ...process.env,
    ...spec.extraEnv,
    FLUX_DATA_DIR: dataDir,
    FLUX_DATABASE_URL: sqliteUrl(path.join(dataDir, "flux.db")),
    FLUX_WORKSPACE_ROOT: workspaceRoot,
  };
  const options = { cwd: spec.cwd, env, stdio: ["ignore", "pipe", "pipe"] };
  if (process.platform !== "win32") options.detached = true;
  if (!fs.existsSync(spec.command)) throw new Error(`找不到后端可执行文件：${spec.command}\n请先构建 sidecar / 准备 .venv。`);
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
    if (!isQuitting) console.error(`[flux] 后端进程退出 code=${code} signal=${signal}`);
    backendProc = null;
  });
}

function probeHealth(port) {
  return new Promise((resolve) => {
    const req = http.get({ host: "127.0.0.1", port, path: "/api/v1/health", timeout: 2000 }, (res) => {
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
    });
    req.on("timeout", () => req.destroy());
    req.on("error", () => resolve(false));
  });
}

const delay = (ms) => new Promise((r) => setTimeout(r, ms));

async function waitForHealth(port) {
  const deadline = Date.now() + HEALTH_TIMEOUT_MS;
  while (Date.now() < deadline) {
    if (!backendProc) throw new Error(`后端进程在就绪前退出。\n最近日志：\n${backendLogTail.join("").slice(-BACKEND_LOG_LIMIT)}`);
    if (await probeHealth(port)) return;
    await delay(300);
  }
  throw new Error(`等待后端就绪超时（${HEALTH_TIMEOUT_MS / 1000}s）：http://127.0.0.1:${port}/api/v1/health 无响应。\n最近日志：\n${backendLogTail.join("").slice(-BACKEND_LOG_LIMIT)}`);
}

function postWorkspaceRoot(root) {
  return new Promise((resolve, reject) => {
    const body = JSON.stringify({ root });
    const req = http.request({
      host: "127.0.0.1",
      port: backendPort,
      path: "/api/v1/workspace/root",
      method: "PUT",
      headers: { "content-type": "application/json", "content-length": Buffer.byteLength(body), "x-flux-desktop": "1" },
    }, (res) => {
      let data = "";
      res.on("data", (chunk) => (data += chunk));
      res.on("end", () => {
        if (res.statusCode && res.statusCode >= 200 && res.statusCode < 300) return resolve();
        try {
          const parsed = JSON.parse(data);
          reject(new Error(parsed.message || parsed.detail || `切换工作目录失败（HTTP ${res.statusCode}）`));
        } catch {
          reject(new Error(`切换工作目录失败（HTTP ${res.statusCode}）`));
        }
      });
    });
    req.on("error", reject);
    req.end(body);
  });
}

async function chooseWorkspaceRoot(dataDir) {
  const result = await dialog.showOpenDialog(mainWindow, {
    title: "选择 Flux 工作目录",
    properties: ["openDirectory", "createDirectory"],
    defaultPath: currentWorkspaceRoot || dataDir,
  });
  if (result.canceled || !result.filePaths[0]) return null;
  const root = path.resolve(result.filePaths[0]);
  await postWorkspaceRoot(root);
  currentWorkspaceRoot = root;
  persistWorkspaceRoot(dataDir, root);
  if (mainWindow && !mainWindow.isDestroyed()) mainWindow.webContents.send("flux:workspace-changed", root);
  return root;
}

function killBackendTree() {
  const proc = backendProc;
  if (!proc || !proc.pid) return;
  try {
    if (process.platform === "win32") spawn("taskkill", ["/PID", String(proc.pid), "/T", "/F"], { stdio: "ignore" });
    else {
      process.kill(-proc.pid, "SIGTERM");
      setTimeout(() => { try { process.kill(-proc.pid, "SIGKILL"); } catch { /* exited */ } }, 4000);
    }
  } catch (err) {
    console.error(`[flux] 终止后端进程失败：${err}`);
  }
  backendProc = null;
}

const MIME = {
  ".html": "text/html; charset=utf-8", ".js": "text/javascript; charset=utf-8", ".mjs": "text/javascript; charset=utf-8",
  ".css": "text/css; charset=utf-8", ".json": "application/json; charset=utf-8", ".svg": "image/svg+xml",
  ".png": "image/png", ".jpg": "image/jpeg", ".jpeg": "image/jpeg", ".gif": "image/gif", ".ico": "image/x-icon",
  ".webp": "image/webp", ".woff": "font/woff", ".woff2": "font/woff2", ".ttf": "font/ttf", ".map": "application/json; charset=utf-8",
  ".txt": "text/plain; charset=utf-8",
};

function proxyApi(req, res) {
  const headers = { ...req.headers, host: `127.0.0.1:${backendPort}` };
  const proxyReq = http.request({ host: "127.0.0.1", port: backendPort, method: req.method, path: req.url, headers }, (proxyRes) => {
    res.writeHead(proxyRes.statusCode || 502, proxyRes.headers);
    proxyRes.pipe(res);
  });
  proxyReq.on("error", (err) => {
    if (!res.headersSent) res.writeHead(502, { "content-type": "application/json; charset=utf-8" });
    res.end(JSON.stringify({ success: false, code: "backend_unreachable", message: String(err) }));
  });
  req.pipe(proxyReq);
}

function serveStatic(req, res, webRoot) {
  let pathname;
  try { pathname = decodeURIComponent(new URL(req.url, "http://127.0.0.1").pathname); }
  catch { res.writeHead(400); res.end("bad request"); return; }
  if (pathname === "/") pathname = "/index.html";
  const resolved = path.normalize(path.join(webRoot, pathname));
  const relative = path.relative(webRoot, resolved);
  if (relative === ".." || relative.startsWith(".." + path.sep) || path.isAbsolute(relative)) { res.writeHead(403); res.end("forbidden"); return; }
  fs.stat(resolved, (err, stat) => {
    let filePath = resolved;
    if (err || !stat.isFile()) {
      if (path.extname(pathname)) { res.writeHead(404); res.end("not found"); return; }
      filePath = path.join(webRoot, "index.html");
    }
    fs.readFile(filePath, (readErr, data) => {
      if (readErr) { res.writeHead(404); res.end("not found"); return; }
      res.writeHead(200, { "content-type": MIME[path.extname(filePath).toLowerCase()] || "application/octet-stream", "cache-control": "no-cache" });
      res.end(data);
    });
  });
}

function proxyWebSocket(req, socket, head) {
  const requestUrl = req.url || "";
  if (!(requestUrl === "/api" || requestUrl.startsWith("/api/"))) { socket.destroy(); return; }
  const upstream = net.connect({ host: "127.0.0.1", port: backendPort });
  upstream.on("connect", () => {
    const lines = [
      `${req.method || "GET"} ${requestUrl} HTTP/${req.httpVersion || "1.1"}`,
      ...Array.from({ length: req.rawHeaders.length / 2 }, (_, index) => {
        const offset = index * 2;
        return `${req.rawHeaders[offset]}: ${req.rawHeaders[offset + 1]}`;
      }),
      "", "",
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
    if (!fs.existsSync(path.join(webRoot, "index.html"))) { reject(new Error(`前端静态资源缺失：${webRoot}/index.html（请先 npm run build）`)); return; }
    const server = http.createServer((req, res) => {
      if (req.url === "/api" || req.url.startsWith("/api/") || req.url.startsWith("/api?")) { proxyApi(req, res); return; }
      if (req.method !== "GET" && req.method !== "HEAD") { res.writeHead(405); res.end("method not allowed"); return; }
      serveStatic(req, res, webRoot);
    });
    server.on("error", reject);
    server.on("upgrade", proxyWebSocket);
    server.listen(port, "127.0.0.1", () => resolve(server));
  });
}

function uiUrl(query = "") { return `http://127.0.0.1:${uiPort}/index.html${query}`; }

function createMainWindow() {
  mainWindow = new BrowserWindow({ width: 1440, height: 900, minWidth: 1024, minHeight: 640, show: false, backgroundColor: "#0b0d12", webPreferences: { preload: path.join(__dirname, "preload.js"), contextIsolation: true, nodeIntegration: false, sandbox: true } });
  mainWindow.once("ready-to-show", () => mainWindow.show());
  mainWindow.webContents.setWindowOpenHandler(({ url }) => { shell.openExternal(url); return { action: "deny" }; });
  mainWindow.on("closed", () => { mainWindow = null; if (!isQuitting) app.quit(); });
  mainWindow.loadURL(uiUrl());
}

function openTerminalWindow() {
  if (terminalWindow && !terminalWindow.isDestroyed()) { terminalWindow.focus(); return; }
  terminalWindow = new BrowserWindow({ width: 1000, height: 680, minWidth: 720, minHeight: 480, backgroundColor: "#0b0d12", webPreferences: { preload: path.join(__dirname, "preload.js"), contextIsolation: true, nodeIntegration: false, sandbox: true } });
  terminalWindow.loadURL(uiUrl("?fluxWindow=terminal"));
  terminalWindow.on("closed", () => { terminalWindow = null; });
}

function buildMenu(dataDir) {
  const template = [];
  if (process.platform === "darwin") template.push({ role: "appMenu" });
  template.push({
    label: "文件",
    submenu: [
      { label: "打开工作目录…", click: () => chooseWorkspaceRoot(dataDir).catch((err) => dialog.showErrorBox("切换工作目录失败", String(err))) },
      { type: "separator" },
      { role: "quit", label: "退出" },
    ],
  });
  template.push({
    label: "窗口",
    submenu: [
      { label: "打开 Agent Terminal", accelerator: "CmdOrCtrl+Shift+T", click: () => openTerminalWindow() },
      { type: "separator" },
      { role: "reload", label: "重新加载" },
      { role: "toggleDevTools", label: "开发者工具" },
      { role: "minimize", label: "最小化" },
      { role: "close", label: "关闭窗口" },
    ],
  });
  Menu.setApplicationMenu(Menu.buildFromTemplate(template));
}

async function bootstrap() {
  const dataDir = resolveDataDir();
  const webRoot = resolveWebRoot();
  currentWorkspaceRoot = resolveInitialWorkspaceRoot(dataDir);
  console.log(`[flux] 数据目录：${dataDir}`);
  console.log(`[flux] 工作目录：${currentWorkspaceRoot}`);
  console.log(`[flux] 前端静态根：${webRoot}`);

  backendPort = await getFreePort();
  startBackend(backendPort, dataDir, currentWorkspaceRoot);
  await waitForHealth(backendPort);
  uiPort = await getFreePort();
  uiServer = await startStaticServer(uiPort, webRoot);
  buildMenu(dataDir);
  ipcMain.handle("flux:choose-workspace-root", () => chooseWorkspaceRoot(dataDir));
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

app.on("activate", () => { if (BrowserWindow.getAllWindows().length === 0 && uiPort) createMainWindow(); });

#!/usr/bin/env bash
# Flux —— 桌面端「一条命令打包」（设计文档 §10）
#
# 流程：构建前端 → PyInstaller 冻结后端 sidecar → electron-builder 打 Linux 安装包。
# 产物统一落在 apps/desktop/release/，脚本最后打印真实文件名与大小。
#
# 用法：bash scripts/build_desktop.sh
#   FLUX_DESKTOP_REBUILD_WEB=1  强制重建前端（默认已有 dist/index.html 时跳过）
#
# 注意：Windows 的 NSIS 安装包无法在 Linux 上构建（无 wine），交给 CI 的
#       windows runner（.github/workflows/ci.yml 的 desktop-windows job，见 apps/desktop/README.md）。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
cd "${REPO_ROOT}"

VENV_PY="${REPO_ROOT}/.venv/bin/python"
WEB_DIR="${REPO_ROOT}/apps/web-dashboard"
DESKTOP_DIR="${REPO_ROOT}/apps/desktop"
BACKEND_DIR="${DESKTOP_DIR}/backend"
RELEASE_DIR="${DESKTOP_DIR}/release"

if [[ ! -x "${VENV_PY}" ]]; then
  echo "错误：未找到 ${VENV_PY}，请先运行 make setup" >&2
  exit 1
fi

# electron-builder 26.x 的 @noble/hashes v2 是 ESM-only，需要 Node 支持 require(ESM)：
# Node ≥ 22.12 或回移后的 Node ≥ 20.19；否则 require() 会直接抛 ERR_REQUIRE_ESM。
if ! command -v node >/dev/null 2>&1; then
  echo "错误：未找到 node，请安装 Node ≥ 20.19（推荐 22）" >&2
  exit 1
fi
node_version="$(node -v)"
if [[ "${node_version}" =~ ^v([0-9]+)\.([0-9]+) ]]; then
  NODE_MAJOR="${BASH_REMATCH[1]}"
  NODE_MINOR="${BASH_REMATCH[2]}"
else
  echo "错误：无法解析 node 版本：${node_version}" >&2
  exit 1
fi
if (( NODE_MAJOR < 20 )) ||
  { (( NODE_MAJOR == 20 )) && (( NODE_MINOR < 19 )); } ||
  { (( NODE_MAJOR == 22 )) && (( NODE_MINOR < 12 )); } ||
  (( NODE_MAJOR == 21 )); then
  echo "错误：node ${node_version} 过旧。electron-builder 26.x 需要 Node ≥ 20.19（推荐 22.12+）" >&2
  exit 1
fi

echo "仓库根目录：${REPO_ROOT}"
echo "Node：${node_version}"
echo

# --- [1/3] 前端 ---
if [[ -f "${WEB_DIR}/dist/index.html" && "${FLUX_DESKTOP_REBUILD_WEB:-0}" != "1" ]]; then
  echo "[1/3] 前端：已存在 apps/web-dashboard/dist/index.html，跳过构建（设 FLUX_DESKTOP_REBUILD_WEB=1 可强制重建）"
else
  echo "[1/3] 前端：npm ci && npm run build"
  (cd "${WEB_DIR}" && npm ci && npm run build)
  if [[ ! -f "${WEB_DIR}/dist/index.html" ]]; then
    echo "错误：前端构建未产出 ${WEB_DIR}/dist/index.html" >&2
    exit 1
  fi
fi
echo

# --- [2/3] 后端 sidecar（PyInstaller onedir）---
echo "[2/3] 后端 sidecar：安装构建依赖并运行 PyInstaller"
"${VENV_PY}" -m pip install -r "${DESKTOP_DIR}/requirements-build.txt"
(cd "${BACKEND_DIR}" && "${VENV_PY}" -m PyInstaller --noconfirm --clean flux-backend.spec)
if [[ ! -x "${BACKEND_DIR}/dist/flux-backend/flux-backend" ]]; then
  echo "错误：未产出 ${BACKEND_DIR}/dist/flux-backend/flux-backend" >&2
  exit 1
fi
echo

# --- [3/3] Electron 安装包 ---
echo "[3/3] Electron：安装依赖并打包 Linux（AppImage + deb）"
cd "${DESKTOP_DIR}"
if [[ -f package-lock.json ]]; then
  npm ci
else
  npm install
fi
npx electron-builder --linux AppImage deb --publish never

echo
echo "=== 产物清单（${RELEASE_DIR}）==="
if [[ -d "${RELEASE_DIR}" ]]; then
  find "${RELEASE_DIR}" -maxdepth 1 -type f -print0 | xargs -0 ls -lh
else
  echo "错误：未找到产物目录 ${RELEASE_DIR}" >&2
  exit 1
fi
echo
echo "完成。Linux（AppImage / deb）产物见上。"
echo "Windows（NSIS .exe）无法在 Linux 构建（无 wine），请在 CI 的 windows runner 上执行"
echo "  cd apps/desktop && npm ci && npx electron-builder --win nsis"
echo "真机验收清单见 apps/desktop/README.md 的「Windows 真机验收清单」。"

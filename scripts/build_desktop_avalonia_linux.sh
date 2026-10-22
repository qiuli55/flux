#!/usr/bin/env bash
# 构建 Flux 原生桌面客户端（Avalonia / .NET 8）。
#
# 用法: bash scripts/build_desktop_avalonia_linux.sh [linux-x64|linux-arm64]
# macOS 需在 macOS 上构建（RID 传 osx-x64 / osx-arm64），命令与 Linux 相同。
# Windows 仍由原 csproj 以 WinExe 构建，不受此脚本影响。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APP="$ROOT/apps/desktop-avalonia"
RID="${1:-linux-x64}"
OUT="$APP/dist/$RID"
PKG="$APP/release"

dotnet publish "$APP/Flux.Desktop.csproj" -c Release -r "$RID" --self-contained true -o "$OUT"

mkdir -p "$PKG"
tar -C "$APP/dist" -czf "$PKG/flux-desktop-$RID.tar.gz" "$RID"
echo "已生成 $PKG/flux-desktop-$RID.tar.gz"
echo "运行: $OUT/Flux.Desktop（需要仓库内 backend/ 与 .venv，或设置 FLUX_REPO_ROOT / FLUX_PYTHON）"

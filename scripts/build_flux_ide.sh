#!/usr/bin/env bash
# 组装 Flux IDE：VSCodium 底座 + Flux 扩展预装。
#
# 用法:
#   bash scripts/build_flux_ide.sh [linux-x64|linux-arm64|win32-x64]
#   VSCODIUM_TARBALL=/path/vscodium.tar.gz bash scripts/build_flux_ide.sh linux-x64       # 本地 Linux 包，不联网
#   VSCODIUM_ARCHIVE=/path/vscodium-win32-x64.zip bash scripts/build_flux_ide.sh win32-x64 # 本地 Windows 包，不联网
#
# 产物: apps/vscode-extension/release/flux-ide-<rid>.tar.gz（Linux）/ flux-ide-<rid>.zip（Windows）
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
EXT="$ROOT/apps/vscode-extension"
RID="${1:-linux-x64}"
WORK="$ROOT/build/flux-ide-$RID"
STAGE="$WORK/flux-ide"
RELEASE="$EXT/release"
VSIX="$EXT/flux-vscode-0.1.0.vsix"

case "$RID" in
  linux-x64|linux-arm64) ASSET="$RID"; ARCHIVE="tar.gz" ;;
  win32-x64) ASSET="$RID"; ARCHIVE="zip" ;;
  *) echo "不支持的平台: $RID（只交付 linux-x64 / linux-arm64 / win32-x64）" >&2; exit 1 ;;
esac

echo "== 1/5 构建扩展 =="
cd "$EXT"
npm ci --no-audit --no-fund
npm run build
npm run package

echo "== 2/5 准备 VSCodium 底座 =="
rm -rf "$STAGE"
mkdir -p "$STAGE"
SRC="${VSCODIUM_ARCHIVE:-${VSCODIUM_TARBALL:-}}"
if [ -n "$SRC" ]; then
  echo "使用本地底座包: $SRC"
  if [ "$ARCHIVE" = "zip" ]; then
    unzip -q "$SRC" -d "$STAGE"
  else
    tar -xzf "$SRC" -C "$STAGE" --strip-components=1
  fi
elif [ "$ARCHIVE" = "zip" ]; then
  TAG="$(curl -fsSL https://api.github.com/repos/VSCodium/vscodium/releases/latest \
    | python3 -c 'import json,sys; print(json.load(sys.stdin)["tag_name"])')"
  URL="https://github.com/VSCodium/vscodium/releases/download/$TAG/VSCodium-$ASSET-$TAG.zip"
  echo "下载 $URL"
  curl -fL "$URL" -o "$WORK/vscodium-$RID.zip"
  unzip -q "$WORK/vscodium-$RID.zip" -d "$STAGE"
else
  URL="https://github.com/VSCodium/vscodium/releases/latest/download/VSCodium-$ASSET.tar.gz"
  echo "下载 $URL"
  curl -fL "$URL" | tar -xz -C "$STAGE" --strip-components=1
fi

echo "== 3/5 预装扩展 =="
mkdir -p "$WORK/user-data"
if [ "$ARCHIVE" = "zip" ]; then
  # Linux 无法执行 Windows 版 codium，直接把 vsix 解包进 extensions 目录，
  # Windows 首次启动时会扫描并登记该扩展。
  rm -rf "$WORK/vsix-unpack"
  mkdir -p "$WORK/vsix-unpack" "$STAGE/extensions/flux.flux-vscode-0.1.0"
  unzip -q "$VSIX" "extension/*" -d "$WORK/vsix-unpack"
  cp -a "$WORK/vsix-unpack/extension/." "$STAGE/extensions/flux.flux-vscode-0.1.0/"
else
  mkdir -p "$WORK/extensions"
  "$STAGE/bin/codium" \
    --no-sandbox \
    --user-data-dir "$WORK/user-data" \
    --extensions-dir "$WORK/extensions" \
    --install-extension "$VSIX" --force
  rm -rf "$STAGE/extensions"
  cp -r "$WORK/extensions" "$STAGE/extensions"
fi

echo "== 4/5 品牌化 =="
PRODUCT="$STAGE/product.json"
if [ ! -f "$PRODUCT" ]; then
  PRODUCT="$STAGE/resources/app/product.json"
fi
python3 - "$PRODUCT" "$ARCHIVE" <<'PY'
import json, sys
path, archive = sys.argv[1], sys.argv[2]
with open(path) as fh:
    data = json.load(fh)
data["nameShort"] = "Flux IDE"
data["nameLong"] = "Flux IDE"
data["applicationName"] = "flux-ide"
data["dataFolderName"] = ".flux-ide"
if archive == "tar.gz":
    data["linuxIconName"] = "flux-ide"

# 开箱即用的 Flux 外观：默认 Flux Dark 主题 + 字号/行高/连字等配色与排版
defaults = data.setdefault("configurationDefaults", {})
defaults.update(
    {
        "workbench.colorTheme": "Flux Dark",
        "workbench.preferredDarkColorTheme": "Flux Dark",
        "workbench.preferredLightColorTheme": "Flux Dark",
        "window.autoDetectColorScheme": False,
        "window.autoDetectHighContrast": False,
        "workbench.startupEditor": "none",
        "workbench.tips.enabled": False,
        "workbench.editor.tabSizing": "shrink",
        "workbench.tree.indent": 14,
        "workbench.tree.renderIndentGuides": "onHover",
        "editor.fontFamily": "'JetBrains Mono', 'SF Mono', 'Cascadia Code', Menlo, Consolas, 'Noto Sans Mono CJK SC', monospace",
        "editor.fontSize": 13,
        "editor.fontLigatures": True,
        "editor.lineHeight": 1.7,
        "editor.letterSpacing": 0.2,
        "editor.cursorBlinking": "smooth",
        "editor.cursorSmoothCaretAnimation": "on",
        "editor.smoothScrolling": True,
        "editor.padding.top": 12,
        "editor.padding.bottom": 12,
        "editor.bracketPairColorization.enabled": True,
        "editor.guides.bracketPairs": "active",
        "editor.stickyScroll.enabled": True,
        "editor.inlineSuggest.enabled": True,
        "terminal.integrated.fontFamily": "'JetBrains Mono', 'SF Mono', Menlo, Consolas, monospace",
        "terminal.integrated.fontSize": 12,
        "terminal.integrated.lineHeight": 1.35,
    }
)
with open(path, "w") as fh:
    json.dump(data, fh, ensure_ascii=False, separators=(",", ":"))
print("product.json 已改为 Flux IDE")
PY
if [ "$ARCHIVE" = "zip" ]; then
  cat > "$STAGE/bin/flux-ide.cmd" <<'CMD'
@echo off
setlocal
set VSCODE_DEV=
"%~dp0..\VSCodium.exe" %*
endlocal
CMD
else
  ln -sf ../codium "$STAGE/bin/flux-ide"
fi

echo "== 5/5 打包 =="
mkdir -p "$RELEASE"
if [ "$ARCHIVE" = "zip" ]; then
  rm -f "$RELEASE/flux-ide-$RID.zip"
  (cd "$WORK" && zip -qr "$RELEASE/flux-ide-$RID.zip" flux-ide)
  echo "已生成 $RELEASE/flux-ide-$RID.zip"
  echo "运行: 解压后双击 flux-ide\\bin\\flux-ide.cmd（或 VSCodium.exe）"
else
  tar -C "$WORK" -czf "$RELEASE/flux-ide-$RID.tar.gz" flux-ide
  echo "已生成 $RELEASE/flux-ide-$RID.tar.gz"
  echo "运行: tar -xzf flux-ide-$RID.tar.gz && ./flux-ide/bin/flux-ide"
fi

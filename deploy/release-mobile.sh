#!/usr/bin/env bash
# 构建 Flux 移动端 APK，发布到 B 机静态分发节点，并原子写入 latest.json。
#
# 用法：
#   deploy/release-mobile.sh "更新说明1|更新说明2"
#   FLUX_RELEASE_HOST=shanghai FLUX_MIN_VERSION_CODE=10 deploy/release-mobile.sh
#
# 依赖：remote-build skill（远程构建机，含 Android SDK）、ssh 免密登录 B 机、
#      B 机可写 /opt/downloads/flux-mobile。
# 注意：脚本会写 B 机（上传 APK + 替换清单），执行前请确认目标主机与目录。
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APP_DIR="$REPO_ROOT/apps/mobile-controller"
RB="${FLUX_REMOTE_BUILD:-/root/.trae-cn/skills/remote-build/remote-build.sh}"
HOST="${FLUX_RELEASE_HOST:-shanghai}"
REMOTE_DIR="${FLUX_RELEASE_DIR:-/opt/downloads/flux-mobile}"
DOMAIN="${FLUX_RELEASE_DOMAIN:-https://flux.app.qiuli55.top}"
MIN_VERSION_CODE="${FLUX_MIN_VERSION_CODE:-0}"
NOTES_RAW="${1:-稳定性改进与问题修复}"

# 版本号是构建输入的权威来源；同时要求工作区干净，
# 避免 tag 打在一份和安装包不对应的代码上。
VERSION_CODE="$(sed -nE 's/.*versionCode = ([0-9]+).*/\1/p' "$APP_DIR/app/build.gradle.kts" | head -1)"
VERSION_NAME="$(sed -nE 's/.*versionName = "([^"]+)".*/\1/p' "$APP_DIR/app/build.gradle.kts" | head -1)"
[[ -n "$VERSION_CODE" && -n "$VERSION_NAME" ]] || { echo "读取版本号失败" >&2; exit 1; }
if [[ -n "$(git -C "$REPO_ROOT" status --porcelain)" ]]; then
  echo "工作区有未提交改动，先提交再发版（保证 tag 与安装包对应同一份代码）" >&2
  exit 1
fi

echo "==> 远程构建 debug APK（remote-build：grokbot-build）"
OUT_DIR="$(mktemp -d)"
trap 'rm -rf "$OUT_DIR"' EXIT
"$RB" -C "$APP_DIR" -t 3600 \
  -x './app/build' \
  -c "chmod +x gradlew && ./gradlew :app:assembleDebug --console=plain" \
  -a "app/build/outputs/apk/debug/*.apk" \
  -o "$OUT_DIR"

APK="$OUT_DIR/app/build/outputs/apk/debug/app-debug.apk"
[[ -f "$APK" ]] || { echo "远程构建产物缺失：$APK" >&2; exit 1; }

REMOTE_APK="flux-mobile-${VERSION_NAME}-vc${VERSION_CODE}.apk"
SHA256="$(sha256sum "$APK" | cut -d' ' -f1)"
SIZE_BYTES="$(stat -c %s "$APK")"
APK_URL="${DOMAIN}/flux-mobile/${REMOTE_APK}"

echo "==> 发布 v${VERSION_NAME} (vc${VERSION_CODE}) → ${HOST}:${REMOTE_DIR}/${REMOTE_APK}"
ssh "$HOST" "mkdir -p '$REMOTE_DIR'"
scp "$APK" "$HOST:$REMOTE_DIR/.upload-${REMOTE_APK}"
ssh "$HOST" "mv '$REMOTE_DIR/.upload-${REMOTE_APK}' '$REMOTE_DIR/${REMOTE_APK}' && chmod 644 '$REMOTE_DIR/${REMOTE_APK}'"

TMP_JSON="$(mktemp)"
trap 'rm -rf "$OUT_DIR"; rm -f "$TMP_JSON"' EXIT
VERSION_CODE="$VERSION_CODE" VERSION_NAME="$VERSION_NAME" APK_URL="$APK_URL" \
SHA256="$SHA256" SIZE_BYTES="$SIZE_BYTES" MIN_VERSION_CODE="$MIN_VERSION_CODE" \
NOTES_RAW="$NOTES_RAW" python3 - "$TMP_JSON" <<'PY'
import json, os, sys
from datetime import datetime, timezone, timedelta

notes = [item.strip() for item in os.environ["NOTES_RAW"].split("|") if item.strip()]
payload = {
    "version_code": int(os.environ["VERSION_CODE"]),
    "version_name": os.environ["VERSION_NAME"],
    "apk_url": os.environ["APK_URL"],
    "sha256": os.environ["SHA256"],
    "size_bytes": int(os.environ["SIZE_BYTES"]),
    "published_at": datetime.now(timezone(timedelta(hours=8))).isoformat(timespec="seconds"),
    "notes": notes,
    "min_version_code": int(os.environ["MIN_VERSION_CODE"]),
}
with open(sys.argv[1], "w", encoding="utf-8") as handle:
    json.dump(payload, handle, ensure_ascii=False, indent=2)
PY

scp "$TMP_JSON" "$HOST:$REMOTE_DIR/.latest.json.tmp"
# mktemp 默认 0600，直接 mv 过去 nginx（www-data）会 403，这里显式放开读权限。
ssh "$HOST" "chmod 644 '$REMOTE_DIR/.latest.json.tmp' && mv '$REMOTE_DIR/.latest.json.tmp' '$REMOTE_DIR/latest.json'"

echo "==> 完成"
echo "    清单: ${DOMAIN}/flux-mobile/latest.json"
echo "    安装包: ${APK_URL}"
echo "    sha256: ${SHA256}"
echo "    大小: ${SIZE_BYTES} bytes"

# 每次发版都打 tag：发版后 tag 必须存在，避免出现「装的是哪个 commit」说不清。
TAG="v${VERSION_NAME}"
if git -C "$REPO_ROOT" rev-parse -q --verify "refs/tags/$TAG" >/dev/null; then
  echo "==> tag ${TAG} 已存在，跳过"
else
  git -C "$REPO_ROOT" tag -a "$TAG" -m "Flux 移动端 ${VERSION_NAME} (vc${VERSION_CODE})"
  echo "==> 已打 tag ${TAG}；推送到远端：git push origin ${TAG}"
fi

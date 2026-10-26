#!/usr/bin/env bash
# 构建 Flux 移动端 APK，发布到 B 机静态分发节点，并原子写入 latest.json。
#
# 用法：
#   deploy/release-mobile.sh "更新说明1|更新说明2"
#   FLUX_RELEASE_HOST=shanghai FLUX_MIN_VERSION_CODE=10 deploy/release-mobile.sh
#
# 依赖：A 机 Android SDK（aapt2）、ssh 免密登录 B 机、B 机可写 /opt/downloads/flux-mobile。
# 注意：脚本会写 B 机（上传 APK + 替换清单），执行前请确认目标主机与目录。
set -euo pipefail

# 发版机制自包含：调用方 shell 没配 SDK 时也按 A 机约定补齐，避免构建失败。
export ANDROID_HOME="${ANDROID_HOME:-/opt/android-sdk}"
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$ANDROID_HOME}"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-/opt/gradle-home}"

APP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../apps/mobile-controller" && pwd)"
HOST="${FLUX_RELEASE_HOST:-shanghai}"
REMOTE_DIR="${FLUX_RELEASE_DIR:-/opt/downloads/flux-mobile}"
DOMAIN="${FLUX_RELEASE_DOMAIN:-https://flux.app.qiuli55.top}"
MIN_VERSION_CODE="${FLUX_MIN_VERSION_CODE:-0}"
NOTES_RAW="${1:-稳定性改进与问题修复}"
AAPT="${AAPT:-$(command -v aapt2 || ls /opt/android-sdk/build-tools/*/aapt2 | tail -1)}"

cd "$APP_DIR"
echo "==> 构建 debug APK"
./gradlew :app:assembleDebug --console=plain -q

APK="app/build/outputs/apk/debug/app-debug.apk"
BADGING="$("$AAPT" dump badging "$APK")"
VERSION_CODE="$(sed -n "s/.*versionCode='\([0-9]*\)'.*/\1/p" <<<"$BADGING" | head -1)"
VERSION_NAME="$(sed -n "s/.*versionName='\([^']*\)'.*/\1/p" <<<"$BADGING" | head -1)"
[[ -n "$VERSION_CODE" && -n "$VERSION_NAME" ]] || { echo "读取 APK 版本失败" >&2; exit 1; }

REMOTE_APK="flux-mobile-${VERSION_NAME}-vc${VERSION_CODE}.apk"
SHA256="$(sha256sum "$APK" | cut -d' ' -f1)"
SIZE_BYTES="$(stat -c %s "$APK")"
APK_URL="${DOMAIN}/flux-mobile/${REMOTE_APK}"

echo "==> 发布 v${VERSION_NAME} (vc${VERSION_CODE}) → ${HOST}:${REMOTE_DIR}/${REMOTE_APK}"
ssh "$HOST" "mkdir -p '$REMOTE_DIR'"
scp "$APK" "$HOST:$REMOTE_DIR/.upload-${REMOTE_APK}"
ssh "$HOST" "mv '$REMOTE_DIR/.upload-${REMOTE_APK}' '$REMOTE_DIR/${REMOTE_APK}' && chmod 644 '$REMOTE_DIR/${REMOTE_APK}'"

TMP_JSON="$(mktemp)"
trap 'rm -f "$TMP_JSON"' EXIT
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

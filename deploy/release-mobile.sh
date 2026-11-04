#!/usr/bin/env bash
# 构建 Flux 移动端 APK，发布到 B 机静态分发节点，并原子写入 latest.json。
#
# 用法：
#   deploy/release-mobile.sh "更新说明1|更新说明2"
#   FLUX_RELEASE_HOST=shanghai FLUX_MIN_VERSION_CODE=10 deploy/release-mobile.sh
#   FLUX_RELEASE_SOURCE=remote-build deploy/release-mobile.sh   # 回退到远程构建机
#
# 默认流程（github）：打 tag 并推送 → GitHub Actions 构建 release APK 并挂到 Release
#   → 本地从 Release 下载（objects.githubusercontent.com 实测约 500KB/s，远高于
#   构建机 SSH 的十几 KB/s）→ 上传 B 机。
# 回退流程（remote-build）：用 remote-build skill 在 grokbot-build 上 assembleRelease，
#   再把产物拉回本地（链路慢，仅作 CI 不可用时的兜底）。
#
# 依赖：gh CLI 已登录、ssh 免密登录 B 机、B 机可写 /opt/downloads/flux-mobile。
# 注意：脚本会写 B 机（上传 APK + 替换清单），执行前请确认目标主机与目录。
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APP_DIR="$REPO_ROOT/apps/mobile-controller"
SOURCE="${FLUX_RELEASE_SOURCE:-github}"
RB="${FLUX_REMOTE_BUILD:-/root/.trae-cn/skills/remote-build/remote-build.sh}"
HOST="${FLUX_RELEASE_HOST:-shanghai}"
REMOTE_DIR="${FLUX_RELEASE_DIR:-/opt/downloads/flux-mobile}"
DOMAIN="${FLUX_RELEASE_DOMAIN:-https://flux.app.qiuli55.top}"
MIN_VERSION_CODE="${FLUX_MIN_VERSION_CODE:-0}"
WAIT_SECS="${FLUX_RELEASE_WAIT:-2400}"
NOTES_RAW="${1:-稳定性改进与问题修复}"

# 版本号是构建输入的权威来源；同时要求工作区干净，
# 避免 tag 打在一份和安装包不对应的代码上。
VERSION_CODE="$(sed -nE 's/.*versionCode = ([0-9]+).*/\1/p' "$APP_DIR/app/build.gradle.kts" | head -1)"
VERSION_NAME="$(sed -nE 's/.*versionName = "([^"]+)".*/\1/p' "$APP_DIR/app/build.gradle.kts" | head -1)"
[[ -n "$VERSION_CODE" && -n "$VERSION_NAME" ]] || { echo "读取版本号失败" >&2; exit 1; }
# 只校验已跟踪文件：未跟踪的设计素材不影响安装包，也不进 tag 对应的代码。
if [[ -n "$(git -C "$REPO_ROOT" status --porcelain --untracked-files=no)" ]]; then
  echo "工作区有未提交改动，先提交再发版（保证 tag 与安装包对应同一份代码）" >&2
  exit 1
fi

TAG="v${VERSION_NAME}"
REMOTE_APK="flux-mobile-${VERSION_NAME}-vc${VERSION_CODE}.apk"
OUT_DIR="$(mktemp -d)"
TMP_JSON=""
trap 'rm -rf "$OUT_DIR"; [[ -n "$TMP_JSON" ]] && rm -f "$TMP_JSON"' EXIT

if [[ "$SOURCE" == "github" ]]; then
  REPO="$(gh repo view --json nameWithOwner -q .nameWithOwner)"

  # tag 是 CI 的构建输入，必须先存在且指向当前 HEAD。
  if git -C "$REPO_ROOT" rev-parse -q --verify "refs/tags/$TAG" >/dev/null; then
    TAG_COMMIT="$(git -C "$REPO_ROOT" rev-list -n1 "$TAG")"
    HEAD_COMMIT="$(git -C "$REPO_ROOT" rev-parse HEAD)"
    if [[ "$TAG_COMMIT" != "$HEAD_COMMIT" ]]; then
      echo "tag ${TAG} 指向 ${TAG_COMMIT}，与 HEAD ${HEAD_COMMIT} 不一致；请先处理 tag" >&2
      exit 1
    fi
    echo "==> tag ${TAG} 已存在且指向 HEAD"
  else
    git -C "$REPO_ROOT" tag -a "$TAG" -m "Flux 移动端 ${VERSION_NAME} (vc${VERSION_CODE})"
    echo "==> 已打 tag ${TAG}"
  fi
  if ! git -C "$REPO_ROOT" ls-remote --tags origin "refs/tags/$TAG" | grep -q .; then
    git -C "$REPO_ROOT" push origin "$TAG"
    echo "==> 已推送 tag ${TAG}，触发 GitHub Actions"
  fi

  echo "==> 等待 GitHub Actions 把 ${REMOTE_APK} 挂到 Release ${TAG}（最多 ${WAIT_SECS}s）"
  DEADLINE=$((SECONDS + WAIT_SECS))
  while :; do
    if gh release view "$TAG" --repo "$REPO" --json assets --jq '.assets[].name' 2>/dev/null | grep -qx "$REMOTE_APK"; then
      break
    fi
    if (( SECONDS >= DEADLINE )); then
      echo "等待 Release 资产超时，检查：gh run list --workflow mobile-android.yml" >&2
      exit 1
    fi
    STATUS="$(gh run list --repo "$REPO" --workflow mobile-android.yml --limit 5 \
      --json headBranch,status,conclusion \
      --jq "[.[] | select(.headBranch == \"$TAG\")] | first | \"\\(.status)/\\(.conclusion // \"-\")\"" 2>/dev/null || true)"
    echo "    $(date +%H:%M:%S) CI: ${STATUS:-未知}，继续等待"
    sleep 20
  done
  gh release download "$TAG" --repo "$REPO" --pattern "$REMOTE_APK" --dir "$OUT_DIR"
  APK="$OUT_DIR/$REMOTE_APK"
else
  echo "==> 远程构建 release APK（remote-build：grokbot-build）"
  "$RB" -C "$APP_DIR" -t 3600 \
    -x './app/build' \
    -c "chmod +x gradlew && ./gradlew :app:assembleRelease --console=plain" \
    -a "app/build/outputs/apk/release/*.apk" \
    -o "$OUT_DIR"

  APK="$OUT_DIR/app/build/outputs/apk/release/app-release.apk"
fi
[[ -f "$APK" ]] || { echo "构建产物缺失：$APK" >&2; exit 1; }

SHA256="$(sha256sum "$APK" | cut -d' ' -f1)"
SIZE_BYTES="$(stat -c %s "$APK")"
APK_URL="${DOMAIN}/flux-mobile/${REMOTE_APK}"

echo "==> 发布 v${VERSION_NAME} (vc${VERSION_CODE}) → ${HOST}:${REMOTE_DIR}/${REMOTE_APK}"
ssh "$HOST" "mkdir -p '$REMOTE_DIR'"
scp "$APK" "$HOST:$REMOTE_DIR/.upload-${REMOTE_APK}"
ssh "$HOST" "mv '$REMOTE_DIR/.upload-${REMOTE_APK}' '$REMOTE_DIR/${REMOTE_APK}' && chmod 644 '$REMOTE_DIR/${REMOTE_APK}'"

TMP_JSON="$(mktemp)"
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

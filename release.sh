#!/usr/bin/env bash
#
# 一键发版脚本
#   用法:  ./release.sh <versionName> ["release 说明"]
#   示例:  ./release.sh 1.1 "修复若干问题，新增矿物筛选"
#
# 流程:
#   1. 校验工作区干净、tag 不重复
#   2. versionCode 自动 +1，versionName 改为参数值
#   3. 打 release 包 (跳过 lint，避免网络拉取依赖失败)
#   4. 提交版本号改动、打 tag、推送 main + tag
#   5. 调 GitHub/Gitee API 创建 Release 并上传 APK (复用 git 已存凭证)
#
set -euo pipefail
cd "$(dirname "$0")"

# ---- 参数 ----
VERSION_NAME="${1:-}"
RELEASE_NOTE="${2:-}"
if [ -z "$VERSION_NAME" ]; then
  echo "❌ 用法: ./release.sh <versionName> [\"release 说明\"]"
  echo "   例如: ./release.sh 1.1 \"修复若干问题\""
  exit 1
fi
TAG="v${VERSION_NAME}"
GRADLE_FILE="app/build.gradle.kts"
GITEE_REPO="hurtblack/BUGSC"
APP_PAGE_WEBHOOK_URL="${BUGSC_APP_PAGE_WEBHOOK_URL:-}"
DEFAULT_ANALYTICS_URL="https://scmobiglas-analytics.a1447270904.workers.dev/collect"

# ---- 前置校验 ----
if [ -n "$(git status --porcelain)" ]; then
  echo "❌ 工作区有未提交的改动，请先提交或暂存后再发版。"
  git status --short
  exit 1
fi
if git rev-parse "$TAG" >/dev/null 2>&1; then
  echo "❌ tag $TAG 已存在，请换一个版本号。"
  exit 1
fi
CURRENT_BRANCH=$(git rev-parse --abbrev-ref HEAD)
if [ "$CURRENT_BRANCH" != "main" ]; then
  echo "❌ 请在 main 分支执行发版，当前分支: $CURRENT_BRANCH"
  exit 1
fi

# ---- 版本号 ----
OLD_CODE=$(grep -E '^[[:space:]]*versionCode[[:space:]]*=' "$GRADLE_FILE" | grep -oE '[0-9]+' | head -1)
NEW_CODE=$((OLD_CODE + 1))
echo "▶ 版本: $VERSION_NAME   versionCode: $OLD_CODE → $NEW_CODE"

# macOS / Linux 通用 sed -i
sed -i.bak -E "s/(versionCode[[:space:]]*=[[:space:]]*)[0-9]+/\1${NEW_CODE}/" "$GRADLE_FILE"
sed -i.bak -E "s/(versionName[[:space:]]*=[[:space:]]*)\"[^\"]*\"/\1\"${VERSION_NAME}\"/" "$GRADLE_FILE"
rm -f "${GRADLE_FILE}.bak"

# ---- 打包 ----
echo "▶ 打 release 包..."
GRADLE_ARGS=(":app:assembleRelease" "-x" "lintVitalRelease" "--console=plain")
ANALYTICS_URL="${BUGSC_ANALYTICS_URL:-$DEFAULT_ANALYTICS_URL}"
GRADLE_ARGS+=("-PBUGSC_ANALYTICS_URL=${ANALYTICS_URL}")
echo "   已注入匿名统计地址: ${ANALYTICS_URL}"
./gradlew "${GRADLE_ARGS[@]}"

APK_SRC="app/build/outputs/apk/release/SCMobiGlas-release-v${VERSION_NAME}.apk"
if [ ! -f "$APK_SRC" ]; then
  echo "❌ 没找到 APK: $APK_SRC"
  exit 1
fi
echo "✓ 产物: $APK_SRC ($(du -h "$APK_SRC" | cut -f1))"

# ---- 提交 + tag + 推送 ----
echo "▶ 提交版本号 + 打 tag $TAG..."
git add "$GRADLE_FILE"
git commit -q -m "chore: release ${TAG} (versionCode ${NEW_CODE})"
git tag -a "$TAG" -m "${TAG}${RELEASE_NOTE:+ — $RELEASE_NOTE}"
git push origin HEAD
git push origin "$TAG"

# ---- 平台发布 ----
publish_github() {
  echo "▶ 创建 GitHub Release 并上传 APK..."
  local REMOTE_URL REPO TOKEN NOTE_JSON NOTE_ESC RESP UPLOAD_URL ASSET UP STATE
  REMOTE_URL=$(git remote get-url origin)
  REPO=$(echo "$REMOTE_URL" | sed -E 's#^.*github\.com[:/]##; s#\.git$##')
  TOKEN=$(printf "protocol=https\nhost=github.com\n\n" | git credential fill 2>/dev/null | sed -n 's/^password=//p')
  if [ -z "$TOKEN" ]; then
    echo "⚠ 取不到 GitHub 凭证，代码/tag 已推送，但 Release 需手动创建。"
    return 0
  fi

  NOTE_JSON="${RELEASE_NOTE:-对应 Star Citizen 数据，详见 README。}"
  NOTE_ESC=$(printf '%s' "$NOTE_JSON" | python3 -c 'import json,sys; print(json.dumps(sys.stdin.read()))')

  RESP=$(curl -s -H "Authorization: token $TOKEN" -H "Accept: application/vnd.github+json" \
    -d "{\"tag_name\":\"${TAG}\",\"name\":\"${TAG}\",\"body\":${NOTE_ESC},\"draft\":false,\"prerelease\":false}" \
    "https://api.github.com/repos/${REPO}/releases")
  UPLOAD_URL=$(echo "$RESP" | sed -n 's/.*"upload_url": *"\([^"]*\){.*/\1/p')
  if [ -z "$UPLOAD_URL" ]; then
    echo "❌ GitHub Release 创建失败，响应片段："
    echo "$RESP" | head -20
    return 1
  fi

  ASSET="/tmp/SCMobiGlas-release-${TAG}.apk"
  cp "$APK_SRC" "$ASSET"
  UP=$(curl -s -H "Authorization: token $TOKEN" \
    -H "Content-Type: application/vnd.android.package-archive" \
    --data-binary @"$ASSET" "${UPLOAD_URL}?name=SCMobiGlas-release-${TAG}.apk")
  rm -f "$ASSET"
  STATE=$(echo "$UP" | sed -n 's/.*"state": *"\([^"]*\)".*/\1/p')
  echo "   GitHub 资产: SCMobiGlas-release-${TAG}.apk (state=$STATE)"
  echo "   GitHub 页面: https://github.com/${REPO}/releases/tag/${TAG}"
}

publish_gitee() {
  echo "▶ 推送 Gitee 并创建 Release..."
  local TOKEN NOTE_JSON NOTE_ESC RESP RELEASE_ID ASSET UP APK_URL
  if ! git remote get-url gitee >/dev/null 2>&1; then
    git remote add gitee "https://gitee.com/${GITEE_REPO}.git"
  fi
  git push gitee HEAD:main || { echo "❌ Gitee 推送失败，跳过 Gitee Release。"; return 1; }
  git push gitee "$TAG" || { echo "❌ Gitee tag 推送失败，跳过 Gitee Release。"; return 1; }

  TOKEN=$(printf "protocol=https\nhost=gitee.com\n\n" | git credential fill 2>/dev/null | sed -n 's/^password=//p')
  if [ -z "$TOKEN" ]; then
    echo "⚠ 取不到 Gitee 令牌，代码/tag 已推送 Gitee，但 Release 需手动创建。"
    return 0
  fi

  NOTE_JSON="${RELEASE_NOTE:-对应 Star Citizen 数据，详见 README。}"
  NOTE_ESC=$(printf '%s' "$NOTE_JSON" | python3 -c 'import json,sys; print(json.dumps(sys.stdin.read()))')

  RESP=$(curl -s -X POST -H "Content-Type: application/json" \
    -d "{\"access_token\":\"${TOKEN}\",\"tag_name\":\"${TAG}\",\"name\":\"${TAG}\",\"body\":${NOTE_ESC},\"target_commitish\":\"main\"}" \
    "https://gitee.com/api/v5/repos/${GITEE_REPO}/releases")
  RELEASE_ID=$(echo "$RESP" | python3 -c 'import json,sys
try:
    print(json.load(sys.stdin)["id"])
except Exception:
    pass')
  if [ -z "$RELEASE_ID" ]; then
    echo "❌ Gitee Release 创建失败，响应片段："
    echo "$RESP" | head -20
    return 1
  fi

  ASSET="/tmp/SCMobiGlas-release-${TAG}.apk"
  cp "$APK_SRC" "$ASSET"
  UP=$(curl -s -X POST \
    -F "access_token=${TOKEN}" \
    -F "file=@${ASSET}" \
    "https://gitee.com/api/v5/repos/${GITEE_REPO}/releases/${RELEASE_ID}/attach_files")
  rm -f "$ASSET"
  APK_URL=$(printf '%s' "$UP" | python3 -c 'import json,sys
try:
    print(json.load(sys.stdin).get("browser_download_url", ""))
except Exception:
    pass')
  if [ -n "$APK_URL" ]; then
    echo "   Gitee 资产: SCMobiGlas-release-${TAG}.apk 上传成功"
    notify_app_page "$TAG" "$VERSION_NAME" "$APK_URL" "https://gitee.com/${GITEE_REPO}/releases/tag/${TAG}" "$NOTE_JSON" || true
  else
    echo "⚠ Gitee APK 上传可能失败，响应片段："
    echo "$UP" | head -10
  fi
  echo "   Gitee 页面: https://gitee.com/${GITEE_REPO}/releases/tag/${TAG}"
}

notify_app_page() {
  local TAG_NAME VERSION_NAME APK_URL PAGE_URL NOTE_JSON TOKEN BODY RESP
  TAG_NAME="$1"
  VERSION_NAME="$2"
  APK_URL="$3"
  PAGE_URL="$4"
  NOTE_JSON="$5"
  TOKEN="${BUGSC_APP_PAGE_WEBHOOK_TOKEN:-}"

  if [ -z "$APP_PAGE_WEBHOOK_URL" ]; then
    echo "⚠ 未设置 BUGSC_APP_PAGE_WEBHOOK_URL，跳过后端下载页更新。"
    return 0
  fi
  if [ -z "$TOKEN" ]; then
    echo "⚠ 未设置 BUGSC_APP_PAGE_WEBHOOK_TOKEN，跳过后端下载页更新。"
    return 0
  fi

  BODY=$(TAG_NAME="$TAG_NAME" VERSION_NAME="$VERSION_NAME" APK_URL="$APK_URL" PAGE_URL="$PAGE_URL" NOTE_JSON="$NOTE_JSON" python3 -c 'import json,os
print(json.dumps({
    "hook_name": "tag_push_hooks",
    "event_name": "tag_push",
    "ref": "refs/tags/" + os.environ["TAG_NAME"],
    "tag_name": os.environ["TAG_NAME"],
    "version_name": os.environ["VERSION_NAME"],
    "apk_url": os.environ["APK_URL"],
    "page_url": os.environ["PAGE_URL"],
    "body": os.environ["NOTE_JSON"],
    "repository": {
        "full_name": "hurtblack/BUGSC",
        "html_url": "https://gitee.com/hurtblack/BUGSC"
    }
}, ensure_ascii=False))')

  RESP=$(curl -s -X POST \
    -H "Content-Type: application/json" \
    -H "User-Agent: git-oschina-hook" \
    -H "X-Gitee-Event: Tag Push Hook" \
    -H "X-Gitee-Token: ${TOKEN}" \
    -d "$BODY" \
    "$APP_PAGE_WEBHOOK_URL")
  echo "   后端下载页通知: ${RESP:-ok}"
}

# 任一平台失败不阻断另一平台（set -e 环境下用 || true 兜底）
publish_github || true
publish_gitee || true

echo ""
echo "✅ 发版完成"
echo "   版本:   $TAG (versionCode $NEW_CODE)"

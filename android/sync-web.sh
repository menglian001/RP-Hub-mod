#!/usr/bin/env bash
# 把仓库根目录的网页内容同步到 Android 工程的 assets/web，
# 并对齐内置内容版本号。构建 APK 之前必须先跑这个脚本。
#
# 用法:
#   bash android/sync-web.sh
#
# 说明:
#   assets/web 是根目录网页内容的副本，不入库（见 android/.gitignore），
#   避免同一份内容在仓库里存两遍。
set -euo pipefail

ANDROID_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$ANDROID_DIR/.." && pwd)"
DST="$ANDROID_DIR/app/src/main/assets/web"

cd "$ROOT"

if [ ! -f assets/vendor/vue.global.prod.js ]; then
  echo "缺少 assets/vendor，先执行: bash tools/fetch-vendor.sh" >&2
  exit 1
fi

rm -rf "$DST"
mkdir -p "$DST"

# 只复制网页运行需要的部分，与 tools/pack-content.sh 的打包范围保持一致
for item in index.html assets character novel; do
  cp -r "$item" "$DST/"
done

# 内置内容版本号 = 提交总数，与 deploy.yml 的算法一致
CODE="$(git rev-list --count HEAD)"
NOTES="$(git log -1 --pretty=%s)"
SHA="$(cd "$DST" && find . -type f -print0 | sort -z | xargs -0 sha256sum | sha256sum | cut -d' ' -f1)"

cat > "$DST/version.json" <<EOF
{
  "versionCode": $CODE,
  "versionName": "1.0.$CODE",
  "notes": "$NOTES",
  "minShellVersion": 1,
  "zip": "content.zip",
  "sha256": "$SHA",
  "size": 0
}
EOF

# 让 Gradle 的 BUNDLED_CONTENT_VERSION 与内置内容一致
sed -i.bak "s/^bundledContentVersion=.*/bundledContentVersion=$CODE/" "$ANDROID_DIR/gradle.properties"
rm -f "$ANDROID_DIR/gradle.properties.bak"

echo "内置网页内容已同步"
echo "  文件数              $(find "$DST" -type f | wc -l)"
echo "  大小                $(du -sh "$DST" | cut -f1)"
echo "  bundledContentVersion  $CODE"
echo
echo "接下来构建 APK："
echo "  cd android && gradle :app:assembleRelease"

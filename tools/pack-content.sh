#!/usr/bin/env bash
# 打包网页内容为热更新包 content.zip，并写入 version.json 的哈希。
#
# 用法:
#   bash tools/pack-content.sh <versionCode> [versionName] [更新说明]
#
# 产物:
#   dist/content.zip   —— 供 App 下载的内容包
#   dist/version.json  —— 版本清单
# 两个文件都要放到站点根目录（与 index.html 同级）。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

VERSION_CODE="${1:?需要提供 versionCode，例如 2}"
VERSION_NAME="${2:-1.0.$VERSION_CODE}"
NOTES="${3:-内容更新}"

DIST="$ROOT/dist"
rm -rf "$DIST"
mkdir -p "$DIST"

if [ ! -d assets/vendor ]; then
  echo "缺少 assets/vendor，先执行: bash tools/fetch-vendor.sh" >&2
  exit 1
fi

ZIP="$DIST/content.zip"
# 只打网页运行需要的文件，排除 android 工程与开发脚本
zip -q -r -9 "$ZIP" \
  index.html \
  assets \
  character \
  -x "*.DS_Store" -x "__MACOSX/*"

SHA="$(sha256sum "$ZIP" | cut -d' ' -f1)"
SIZE="$(stat -c%s "$ZIP")"

cat > "$DIST/version.json" <<EOF
{
  "versionCode": $VERSION_CODE,
  "versionName": "$VERSION_NAME",
  "notes": "$NOTES",
  "minShellVersion": 1,
  "zip": "content.zip",
  "sha256": "$SHA",
  "size": $SIZE
}
EOF

# 同步仓库根目录的 version.json，保证内置内容版本号与线上一致
cp "$DIST/version.json" "$ROOT/version.json"

echo "content.zip  $SIZE bytes"
echo "sha256       $SHA"
echo "versionCode  $VERSION_CODE"
echo
echo "把 dist/content.zip 与 dist/version.json 部署到站点根目录即可。"

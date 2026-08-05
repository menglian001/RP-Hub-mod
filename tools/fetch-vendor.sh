#!/usr/bin/env bash
# 将页面依赖的 CDN 资源下载到 assets/vendor/，使 App 可完全离线运行。
# 用法： bash tools/fetch-vendor.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VENDOR="$ROOT/assets/vendor"
UA="Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

mkdir -p "$VENDOR/fonts"

dl() {
  local out="$1" url="$2"
  curl -fsSL -A "$UA" --max-time 120 --retry 3 --retry-delay 2 -o "$VENDOR/$out" "$url"
  printf '%-26s %10s bytes\n' "$out" "$(stat -c%s "$VENDOR/$out")"
}

# Tailwind 固定版本，避免 CDN 漂移
dl tailwind.min.js      "https://cdn.tailwindcss.com/3.4.16"
dl vue.global.prod.js   "https://unpkg.com/vue@3.5.13/dist/vue.global.prod.js"
dl marked.min.js        "https://cdn.jsdelivr.net/npm/marked@12.0.2/marked.min.js"
dl purify.min.js        "https://cdn.jsdelivr.net/npm/dompurify@3.0.6/dist/purify.min.js"
dl Sortable.min.js      "https://cdn.jsdelivr.net/npm/sortablejs@1.15.2/Sortable.min.js"
dl daisyui.full.min.css "https://cdn.jsdelivr.net/npm/daisyui@4.7.2/dist/full.min.css"
dl localforage.min.js   "https://cdn.jsdelivr.net/npm/localforage@1.10.0/dist/localforage.min.js"
dl jquery.min.js        "https://cdn.jsdelivr.net/npm/jquery@3.7.1/dist/jquery.min.js"

echo
echo "== 下载 Lora 字体 =="
dl fonts/lora.css "https://fonts.googleapis.com/css2?family=Lora:ital,wght@0,400..700;1,400..700&display=swap"

# 把字体 css 里的远端 woff2 拉到本地并改写为相对路径
python3 - "$VENDOR" <<'PY'
import os, re, sys, urllib.request

vendor = sys.argv[1]
css_path = os.path.join(vendor, 'fonts', 'lora.css')
css = open(css_path, encoding='utf-8').read()
urls = sorted(set(re.findall(r'url\((https://fonts\.gstatic\.com/[^)]+)\)', css)))
print(f'字体文件数: {len(urls)}')

ua = {'User-Agent': 'Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/120.0.0.0 Safari/537.36'}
for u in urls:
    name = u.rsplit('/', 1)[-1].split('?')[0]
    if not name.endswith(('.woff2', '.woff', '.ttf')):
        name += '.woff2'
    dst = os.path.join(vendor, 'fonts', name)
    if not os.path.exists(dst):
        with urllib.request.urlopen(urllib.request.Request(u, headers=ua), timeout=60) as r:
            open(dst, 'wb').write(r.read())
    css = css.replace(u, name)
    print(f'  {name} {os.path.getsize(dst)} bytes')

open(css_path, 'w', encoding='utf-8').write(css)
print('lora.css 已改写为本地路径')
PY

echo
echo "== 校验（不应是 HTML 错误页）=="
fail=0
for f in tailwind.min.js vue.global.prod.js marked.min.js purify.min.js Sortable.min.js localforage.min.js daisyui.full.min.css; do
  head=$(head -c 40 "$VENDOR/$f" | tr -d '\r\n')
  case "$head" in
    *"<!DOCTYPE"*|*"<html"*) echo "  [坏] $f"; fail=1 ;;
    *) echo "  [好] $f" ;;
  esac
done
exit $fail

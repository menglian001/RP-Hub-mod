# RP Hub 套壳应用

把 [RP-Hub](https://github.com/STA1N156/RP-Hub) 网页版打包成 Android 应用，
内置完整网页内容，支持从远端热更新。

当前已发布：**2.2.1**（versionCode 5，内置内容 v67）
→ [Releases](https://github.com/menglian001/RP-Hub-mod/releases)

## 快速开始

**发新版本之前，先读 [OVERRIDE-UPDATE.md](OVERRIDE-UPDATE.md)。**
那份文档记录了覆盖安装所需的签名证书指纹、密钥保管与交接方式、
打包环境版本组合、版本号规则和完整发版流程，搞错任何一条都会导致用户装不上，
只能卸载重装（本地聊天记录与 API Key 全部丢失）。

三条硬约束，摘录在此，细节见那份文档：

| 项 | 值 |
|---|---|
| 包名 / applicationId | `cc.salarycat.rphub`（不要改） |
| 签名证书 SHA-256 | `274017a6cc450d8e2a068a409a61e23e9477a0cdb3a004e953945b340a606725` |
| key alias | `rphub` |
| versionCode | 必须大于已发布的 `5` |

签名私钥 `rphub.keystore` **不在仓库里**，需向持有人索取。
CI 已配好 4 个签名 Secret，**推 `android/**` 或手动 dispatch 就能直接拿到签名包**，
本地不配 keystore 也能发版，见 OVERRIDE-UPDATE.md 第四节。

**改壳代码、改热更新、加原生接口之前，先读 [SECURITY-PRIVACY.md](SECURITY-PRIVACY.md)。**
里面写了权限用途、WebView 限制、数据存放位置、第三方 API 外发行为，
以及一个必须知道的限制：热更新只做 SHA-256 完整性校验，不是代码签名。

### 打包环境

实测通过的组合，版本不对会直接构建失败：

| 组件 | 版本 |
|---|---|
| JDK | 17 |
| Gradle | 8.7（工程无 wrapper，必须系统装） |
| AGP | 8.4.0 |
| Kotlin | 1.9.23 |
| compileSdk / targetSdk / minSdk | 34 / 34 / 24 |
| build-tools | 34.0.0 |

### 构建步骤

```bash
# 1. 从仓库根目录同步网页内容到 assets/web，并对齐版本号
bash android/sync-web.sh

# 2. 配置签名（见 OVERRIDE-UPDATE.md）
cp /path/to/rphub.keystore android/
cat > android/keystore.properties <<'EOF'
storeFile=rphub.keystore
storePassword=你的密码
keyAlias=rphub
keyPassword=你的密码
EOF

# 3. 构建
cd android
export ANDROID_HOME=/path/to/android-sdk
export JAVA_HOME=/path/to/jdk-17
gradle :app:assembleRelease --no-daemon

# 4. 验签，指纹必须是 274017a6...，否则装不上
"$ANDROID_HOME/build-tools/34.0.0/apksigner" verify --print-certs \
  app/build/outputs/apk/release/app-release.apk | grep "SHA-256 digest"
```

产物在 `android/app/build/outputs/apk/release/app-release.apk`。

> 第 2 步跳过时构建**不会报错**，但产物未签名、装不上。第 4 步别省。

> `app/src/main/assets/web/` 是仓库根目录网页内容的副本，
> 由 `sync-web.sh` 生成，**不入库**，避免同一份内容存两遍。
> 首次克隆仓库后必须先跑 `sync-web.sh` 才能构建。

## 工程结构

```
app/src/main/
├── java/cc/salarycat/rphub/
│   ├── MainActivity.kt            WebView 宿主，启动时提升热更新内容
│   ├── ContentManager.kt          下载、校验、解压、提升
│   ├── WebContentPathHandler.kt   active 优先，assets 回退
│   ├── NativeBridge.kt            注入为 window.RPHubNative
│   └── CookieCompat.kt
├── assets/web/                    内置网页内容
└── res/
```

## 内容读取顺序

```
files/web/active/   热更新内容，有则优先
      ↓ 找不到
assets/web/         APK 内置内容
      ↓ 找不到
返回 404            不返回 null，避免 WebView 转去请求真实网络
```

## 热更新流程

```
启动 → promoteIfPending()  同步提升 staging，在 WebView 加载之前
     → WebView 加载 https://localhost/index.html
     → 后台 checkAndDownload()  下载校验后写入 staging
     → 首次安装时立即提升，否则下次启动生效
```

提升不依赖任何弹窗点击。这是与早期版本的关键差异——
早期版本把提升放在弹窗回调里，弹窗不出现就永久卡住。

## JS 接口

网页侧通过 `window.RPHubNative` 调用：

| 方法 | 返回 | 说明 |
|---|---|---|
| `shellVersion()` | String | 壳版本名 |
| `shellVersionCode()` | Int | 壳版本号 |
| `contentVersion()` | Int | 当前生效的内容版本 |
| `contentInfo()` | String (JSON) | 全部诊断信息，排查用 |
| `checkUpdate()` | void | 手动触发检查更新 |
| `applyPendingUpdate()` | Boolean | 立即提升已下载内容并刷新 |
| `saveBase64(name, mime, dataUrl)` | String (JSON) | 保存文件到相册/下载目录 |

`saveBase64` 按 MIME 分流：`image/*` 存到相册 `Pictures/RPHub`，
其余存到 `Download/RPHub`。API 29+ 走 MediaStore（不需要存储权限），
API 24~28 直写公共目录并按需申请 `WRITE_EXTERNAL_STORAGE`。
返回 `{"ok":true,"name":"实际文件名"}` 或 `{"ok":false,"error":"..."}`；
2.1.0 及更早的旧壳此方法返回 void 且只会写 `Download/RPHub`（在 Android 10+
的分区存储下实际会失败），网页侧已兼容拿不到返回值的情况。
**要让保存真正可用，必须重新打包一版壳**（仅热更新网页内容不够）。

### 反向接口：返回键交给网页处理

壳按返回键时的顺序是：全屏视频 → `webView.canGoBack()` → **问网页** → 退出 App。

单页应用没有历史记录，`canGoBack()` 永远是 false，所以壳会调用网页侧的
`window.RPHubHandleBack()`：返回 `true` 表示网页自己消化了这次返回，壳不退出；
返回 `false`、未定义、抛异常、或 400ms 内没回应，都按未处理处理，照旧 `finish()`。
超时兜底是为了避免网页出问题时返回键被按死。

网页侧实现在 `assets/js/app.js` 的 `onMounted` 里，按层级依次消化：
图片预览灯箱 → 模型选择器 → 图片管理的角色详情回列表 → 任意管理页回聊天。

**加新的全屏弹层或管理页时，记得在这个函数里加一层**，否则用户按返回键会直接退出 App。

### 全屏行为

`MainActivity.enableFullscreen()` 做三件事：`setDecorFitsSystemWindows(false)`
边到边、`hide(systemBars())` 隐藏状态栏与导航栏、
`BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE` 允许从边缘上划临时唤出。
刘海屏另加 `LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES`，否则横屏挖孔侧留黑条。

`onWindowFocusChanged(true)` 时会重新隐藏一次——切回前台、关掉输入法或系统弹窗后，
系统栏会自己冒出来。

配套的三处不能少，缺一个就会重新出现黑边：

- `res/values/themes.xml`：系统栏透明 + 允许延伸进挖孔区
- `res/values/colors.xml`：窗口底色 `#F9FAFB`（与网页一致，否则首屏闪黑）
- 网页 `index.html` / `novel/index.html` 的 viewport 带 `viewport-fit=cover`

`contentInfo()` 返回示例：

```json
{
  "shellVersion": "2.2.1",
  "bundledVersion": 67,
  "activeVersion": 0,
  "stagingVersion": 0,
  "effectiveVersion": 67,
  "hasActiveContent": false,
  "source": "bundled"
}
```

## 配置项

`gradle.properties`：

```properties
bundledContentVersion=67    # 必须与 assets/web/version.json 的 versionCode 一致
```

`app/build.gradle.kts`：

```kotlin
applicationId = "cc.salarycat.rphub"    // 不要改，改了就无法覆盖安装
versionCode = 5                          // 每次发版递增
versionName = "2.2.1"
buildConfigField("String", "UPDATE_BASE_URL", "\"https://rp-hub-mod.pages.dev/\"")
```

## 两条更新通道

| 通道 | 更新什么 | 怎么触发 |
|---|---|---|
| 热更新 | 网页内容（`index.html`、`assets/`、`character/`、`novel/`） | 推送到 `main`，Cloudflare Pages 自动部署，App 启动时自动拉取 |
| APK 覆盖安装 | 壳本体（Kotlin 代码、权限、图标） | 手动构建签名 APK 并分发 |

改网页内容推 CF 就够了，用户不用装新包。只有改壳才需要发 APK。

## 许可

内置网页内容来自 [STA1N156/RP-Hub](https://github.com/STA1N156/RP-Hub)，
许可证 CC BY-NC 4.0（署名 - 非商业）。分发需保留署名，不得商用。

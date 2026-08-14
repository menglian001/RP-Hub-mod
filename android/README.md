# RP Hub 套壳应用

把 [RP-Hub](https://github.com/STA1N156/RP-Hub) 网页版打包成 Android 应用，
内置完整网页内容，支持从远端热更新。

## 快速开始

**发新版本之前，先读 [OVERRIDE-UPDATE.md](OVERRIDE-UPDATE.md)。**
那份文档记录了覆盖安装所需的签名信息和版本号规则，
搞错任何一条都会导致用户装不上，只能卸载重装。

**改壳代码、改热更新、加原生接口之前，先读 [SECURITY-PRIVACY.md](SECURITY-PRIVACY.md)。**
里面写了权限用途、WebView 限制、数据存放位置、第三方 API 外发行为，
以及一个必须知道的限制：热更新只做 SHA-256 完整性校验，不是代码签名。

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
```

产物在 `android/app/build/outputs/apk/release/app-release.apk`。

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
| `saveBase64(name, mime, dataUrl)` | void | 保存文件到下载目录 |

`contentInfo()` 返回示例：

```json
{
  "shellVersion": "2.0.0",
  "bundledVersion": 42,
  "activeVersion": 0,
  "stagingVersion": 0,
  "effectiveVersion": 42,
  "hasActiveContent": false,
  "source": "bundled"
}
```

## 配置项

`gradle.properties`：

```properties
bundledContentVersion=42    # 必须与 assets/web/version.json 的 versionCode 一致
```

`app/build.gradle.kts`：

```kotlin
applicationId = "cc.salarycat.rphub"    // 不要改，改了就无法覆盖安装
versionCode = 2                          // 每次发版递增
buildConfigField("String", "UPDATE_BASE_URL", "\"https://rp-hub-mod.pages.dev/\"")
```

## 许可

内置网页内容来自 [STA1N156/RP-Hub](https://github.com/STA1N156/RP-Hub)，
许可证 CC BY-NC 4.0（署名 - 非商业）。分发需保留署名，不得商用。

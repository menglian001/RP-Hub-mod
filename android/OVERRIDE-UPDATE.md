# RP Hub 套壳应用 — 覆盖更新说明

本文件记录让新版本 APK 能**直接覆盖安装**所需的全部信息。
换电脑、换构建环境、隔很久再来更新，照这份文档做就不会踩坑。

---

## 一、覆盖安装的三个必要条件

Android 允许新 APK 覆盖旧 APK，必须**同时**满足：

| 条件 | 本项目的值 | 说明 |
|---|---|---|
| 包名相同 | `cc.salarycat.rphub` | 定义在 `app/build.gradle.kts` 的 `applicationId`，**永远不要改** |
| 签名证书相同 | SHA-256 见下方 | 必须用同一个 keystore 签名 |
| versionCode 不降低 | 当前 `2` | 每次发版必须递增 |

任意一条不满足，安装时会报 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`，
手机上表现为「应用未安装」，用户只能卸载重装（丢失全部数据）。

---

## 二、本项目的签名证书

从 2.0.0 版本起固定使用这一份证书。

```
Alias        : rphub
DN           : CN=RP Hub, OU=Mod, O=RPHub, L=NA, ST=NA, C=CN
SHA-256      : 27:40:17:A6:CC:45:0D:8E:2A:06:8A:40:9A:61:E2:3E:
               94:77:A0:CD:B3:A0:04:E9:53:94:5B:34:0A:60:67:25
有效期        : 2026-08-14 ~ 2056-08-06（30 年）
密钥算法      : RSA 2048
```

**验证一个 APK 是否用了这份证书：**

```bash
apksigner verify --print-certs your.apk | grep "SHA-256 digest"
# 应输出 274017a6cc450d8e2a068a409a61e23e9477a0cdb3a004e953945b340a606725
```

指纹对得上就能覆盖安装，对不上就不能。

---

## 三、密钥文件的保管

签名私钥是 `rphub.keystore`，**它已被 `.gitignore` 排除，不在本仓库里**。

### 为什么不放进仓库

私钥一旦泄露，任何人都能签出一个能覆盖安装你 App 的版本，
接手它的全部本地数据和权限。这个损害是不可逆的——
换证书就意味着所有用户必须卸载重装。

私人仓库也不够安全：协作者、误改为 Public、访问令牌泄露、
第三方 CI 集成，任何一环出问题密钥就跟着走了。

### 必须自己备份

请把 `rphub.keystore` 存到**至少两个**离线位置，例如：

- 本地加密压缩包 + 密码管理器里记密码
- 私有网盘 / 移动硬盘

> **丢了这个文件就再也做不出能覆盖当前版本的 APK。**
> 唯一的补救是换新证书，代价是所有用户卸载重装一次。

---

## 四、GitHub Actions 自动构建

工作流在 `.github/workflows/build-apk.yml`，
推送到 `main` 或打 `v*` 标签时自动构建签名 APK。

### 需要配置的 4 个 Secret

仓库 → Settings → Secrets and variables → Actions → New repository secret

| Secret 名称 | 内容 |
|---|---|
| `RPHUB_KEYSTORE_BASE64` | keystore 文件的 base64 编码（见下） |
| `RPHUB_STORE_PASSWORD` | keystore 密码 |
| `RPHUB_KEY_ALIAS` | `rphub` |
| `RPHUB_KEY_PASSWORD` | key 密码 |

生成 base64：

```bash
base64 -w 0 rphub.keystore
```

把输出的一整行粘贴为 `RPHUB_KEYSTORE_BASE64` 的值。

构建产物在 Actions 页面的 Artifacts 里下载，保留 30 天。

---

## 五、本地构建

### 环境要求

- JDK 17
- Android SDK（platform-android-34、build-tools 34.0.0）
- Gradle 8.7

### 签名配置

在仓库根目录建 `keystore.properties`（已被 `.gitignore` 排除）：

```properties
storeFile=rphub.keystore
storePassword=你的密码
keyAlias=rphub
keyPassword=你的密码
```

或改用环境变量，CI 上就是这种方式：

```bash
export RPHUB_STORE_FILE=rphub.keystore
export RPHUB_STORE_PASSWORD=...
export RPHUB_KEY_ALIAS=rphub
export RPHUB_KEY_PASSWORD=...
```

### 构建命令

```bash
export ANDROID_HOME=/opt/android-sdk
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
gradle :app:assembleRelease --no-daemon
```

产物：`app/build/outputs/apk/release/app-release.apk`

---

## 六、发布一个新版本的完整流程

### 1. 更新内置网页内容

```bash
# 把最新网页内容同步进 assets
rsync -a --delete \
  --exclude=.git --exclude=tools --exclude=presence-server \
  /path/to/RP-Hub-merged/ app/src/main/assets/web/

# 同步内容版本清单
curl -fsSL https://rp-hub-mod.pages.dev/version.json \
  -o app/src/main/assets/web/version.json
```

### 2. 对齐内容版本号

`app/src/main/assets/web/version.json` 里的 `versionCode`
必须和 `gradle.properties` 里的 `bundledContentVersion` 一致。

```properties
# gradle.properties
bundledContentVersion=42
```

不一致会导致壳误判内置内容比线上新或旧，热更新逻辑出错。

### 3. 递增 App 版本号

`app/build.gradle.kts`：

```kotlin
versionCode = 3          // 必须比上一版大
versionName = "2.0.1"    // 展示用，随意
```

**versionCode 忘记递增，覆盖安装会失败。**

### 4. 构建并验证

```bash
gradle :app:assembleRelease --no-daemon

# 确认签名指纹正确
apksigner verify --print-certs \
  app/build/outputs/apk/release/app-release.apk | grep "SHA-256"

# 确认关键内容都在
unzip -l app/build/outputs/apk/release/app-release.apk | grep "assets/web/novel/"
```

### 5. 分发

用户直接安装即可，**不需要卸载旧版**。

---

## 七、版本历史与签名对应关系

| 版本 | versionCode | 签名 SHA-256 前 8 位 | 能否被 2.0.0+ 覆盖 |
|---|---|---|---|
| 1.0.0（早期二改壳） | 1 | `53756ef1` | 不能，签名不同 |
| 2.0.0（本工程） | 2 | `274017a6` | — |
| 2.0.1 及以后 | 3+ | `274017a6` | 能 |

早期 1.0.0 用的是 Android Debug 证书（`CN=Android Debug`），
其私钥在原构建机器的 `~/.android/debug.keystore` 里，
当前环境没有找到。所以从 1.0.0 升到 2.0.0 **必须卸载一次**。

如果日后找到了那个 `debug.keystore` 且指纹是 `53756ef1...`，
可以用它重新签名，实现从 1.0.0 直接覆盖。

---

## 八、常见问题

**装新版报「应用未安装」**
按顺序查：签名指纹是否一致 → versionCode 是否比已装版本大 → 包名是否被改过。

**热更新不生效**
新壳在 `MainActivity.onCreate` 里、WebView 加载之前同步提升 staging，
不依赖任何弹窗点击。检查 `RPHubNative.contentInfo()` 的返回值，
里面有 active / staging / bundled 三个版本号和内容来源。

**页面报 ERR_CONNECTION_REFUSED**
这是旧壳的行为：找不到文件时 `shouldInterceptRequest` 返回 null，
WebView 转去请求真实网络，在 `https://localhost` 上必然失败。
新壳改成返回明确的 404，不会再出现这种假的网络错误。

**换电脑后构建出的 APK 装不上**
说明用了不同的 keystore。把备份的 `rphub.keystore` 拷过来，
配好 `keystore.properties` 再构建。

---

## 九、内容来源与许可

内置网页内容来自 `https://github.com/STA1N156/RP-Hub`，
许可证 **CC BY-NC 4.0**（署名 - 非商业）。

分发时需保留原作者署名，且不得用于商业用途。

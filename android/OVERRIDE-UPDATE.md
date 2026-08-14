# RP Hub 套壳应用 — 签名与覆盖更新手册

本文件记录让新版本 APK 能**直接覆盖安装**所需的全部信息。
换电脑、换构建环境、换人接手、隔很久再来更新，照这份文档做就不会踩坑。

> 最后核对时间：2026-08-15。文中所有指纹、版本号、URL 均为实测值。

---

## 零、给下一个接手的人（最短路径）

想发一个能覆盖当前版本的新 APK，只需要三件事对得上：

1. **包名** `cc.salarycat.rphub` —— 不要改。
2. **签名证书** SHA-256 = `274017a6cc450d8e2a068a409a61e23e9477a0cdb3a004e953945b340a606725`
   —— 必须用同一个 `rphub.keystore`，这个文件**不在仓库里**，需要向持有人索取。
3. **versionCode** 比 `2` 大 —— 改 `android/app/build.gradle.kts`。

其余步骤见第六节。没有 keystore 就做不出可覆盖的包，这是硬约束，没有绕过办法。

---

## 一、覆盖安装的三个必要条件

Android 允许新 APK 覆盖旧 APK，必须**同时**满足：

| 条件 | 本项目的值 | 定义位置 | 说明 |
|---|---|---|---|
| 包名相同 | `cc.salarycat.rphub` | `app/build.gradle.kts` 的 `applicationId` 与 `namespace` | **永远不要改** |
| 签名证书相同 | SHA-256 `274017a6...` | `rphub.keystore` | 必须是同一个 keystore 里的同一个 alias |
| versionCode 不降低 | 当前 `2` | `app/build.gradle.kts` 的 `versionCode` | 每次发版必须递增 |

任意一条不满足，安装时会报 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`，
手机上表现为「应用未安装」，用户只能卸载重装（丢失全部本地数据：
聊天记录、角色卡、API Key 全没）。

注意 `versionName`（如 `2.0.0`）只是展示用，系统不看它。
真正决定能否覆盖的是 `versionCode` 这个整数。

---

## 二、本项目的签名证书

从 2.0.0 版本起固定使用这一份证书，实测数据：

```
Alias           : rphub
证书 DN         : CN=RP Hub, OU=Mod, O=RPHub, L=NA, ST=NA, C=CN
证书 SHA-256    : 27:40:17:A6:CC:45:0D:8E:2A:06:8A:40:9A:61:E2:3E:
                  94:77:A0:CD:B3:A0:04:E9:53:94:5B:34:0A:60:67:25
证书 SHA-1      : 29:E3:58:8A:92:91:3A:72:13:3E:C1:15:4C:A6:5C:01:10:C4:8B:A4
有效期          : 2026-08-14 ~ 2056-08-06（30 年）
密钥算法        : RSA 2048
keystore 格式   : PKCS12
keystore 文件   : rphub.keystore（2694 字节）
keystore SHA-256: 7df2f5176ca6ece5c942cde49e68a48b0de9c35ba10741e45847e8f4801ea1e1
```

**keystore 的 SHA-256 用途**：从备份里恢复文件后先比对这个值，
确认拿到的是同一个密钥库、没有被替换或损坏。

**密码不写在本文档里**，见第三节的保管方式。

### 验证一个 APK 是否用了这份证书

```bash
apksigner verify --print-certs your.apk | grep "SHA-256 digest"
# 应输出 274017a6cc450d8e2a068a409a61e23e9477a0cdb3a004e953945b340a606725
```

指纹对得上就能覆盖安装，对不上就不能。没有 `apksigner` 时也可以用：

```bash
keytool -printcert -jarfile your.apk | grep -A1 SHA256
```

### 已发布的 2.0.0 实测签名状态

```
Verified using v1 scheme (JAR signing):              false
Verified using v2 scheme (APK Signature Scheme v2):  true
Verified using v3 scheme (APK Signature Scheme v3):  true
Number of signers: 1
```

`build.gradle.kts` 里写了 `enableV1Signing = true`，但实际产物 v1 为 false —— 
这是正常的：本项目 `minSdk = 24`（Android 7.0），AGP 判定所有目标系统都支持 v2，
于是跳过了 v1。**不是问题，也不影响覆盖安装**。
只有把 minSdk 降到 23 及以下（Android 6 及更早）才会真正产出 v1 签名。

v3 已启用，意味着日后万一必须更换密钥，可以走
[密钥轮换](https://developer.android.com/about/versions/pie/android-9.0#apk-key-rotation)
在 Android 9+ 上保持覆盖能力（旧系统仍需重装）。

---

## 三、密钥文件的保管

签名私钥是 `android/rphub.keystore`，
**已被 `android/.gitignore` 排除（`*.keystore`），不在本仓库里**。
密码存放在 `android/keystore.properties`，同样被排除（不入库）。

### 为什么不放进仓库

私钥一旦泄露，任何人都能签出一个能覆盖安装你 App 的版本，
接手它的全部本地数据和权限。这个损害不可逆——
换证书就意味着所有用户必须卸载重装。

私人仓库也不够安全：协作者、误改为 Public、访问令牌泄露、
第三方 CI 集成，任何一环出问题密钥就跟着走了。

### 必须自己备份

请把 `rphub.keystore` 和它的密码存到**至少两个**离线位置，例如：

- 本地加密压缩包（7z / age / gpg）+ 密码管理器里记密码
- 私有网盘 / 移动硬盘

恢复后用第二节的 `keystore SHA-256` 校验完整性。

> **丢了这个文件就再也做不出能覆盖当前版本的 APK。**
> 唯一的补救是换新证书，代价是所有用户卸载重装一次、数据全部丢失。

### 交接给别人时

对方需要拿到两样东西，**通过仓库以外的私密渠道传递**：

1. `rphub.keystore` 文件本体
2. storePassword 与 keyPassword

传递后对方按第五节配置 `keystore.properties` 即可构建可覆盖的包。
不要把这两样放进 Issue、PR、聊天群、或任何会被索引的地方。

---

## 四、GitHub Actions 自动构建

工作流在 `.github/workflows/build-apk.yml`，
推送到 `main` 且改动了 `android/**` 时、或手动 dispatch 时构建签名 APK。

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
base64 -w 0 android/rphub.keystore
```

把输出的一整行粘贴为 `RPHUB_KEYSTORE_BASE64` 的值（约 3592 字符）。

工作流会把密钥解码到 `android/rphub.keystore`，构建后
以 `if: always()` 无条件删除，失败也不会残留。
构建产物在 Actions 页面的 Artifacts 里下载，保留 30 天。

> 这 4 个 Secret 未配置时该工作流会失败（`test -n "$KEYSTORE_BASE64"` 直接退出），
> 不影响 Cloudflare Pages 的内容部署工作流。

---

## 五、本地构建

### 环境要求

- JDK 17
- Android SDK（platform-android-34、build-tools 34.0.0）
- Gradle 8.7

### 签名配置

在 `android/` 目录建 `keystore.properties`（已被 `.gitignore` 排除）：

```properties
storeFile=rphub.keystore
storePassword=你的密码
keyAlias=rphub
keyPassword=你的密码
```

`storeFile` 是相对 `android/` 目录的路径（代码里用 `rootProject.file()` 解析）。

或改用环境变量，CI 上就是这种方式，优先级高于 properties 文件：

```bash
export RPHUB_STORE_FILE=rphub.keystore
export RPHUB_STORE_PASSWORD=...
export RPHUB_KEY_ALIAS=rphub
export RPHUB_KEY_PASSWORD=...
```

### 构建命令

```bash
cd android
export ANDROID_HOME=/opt/android-sdk
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
gradle :app:assembleRelease --no-daemon
```

产物：`android/app/build/outputs/apk/release/app-release.apk`

注意 `debug` 构建类型也用了 release 签名配置，
这样调试包和正式包能互相覆盖，方便本地验证。

---

## 六、发布一个新版本的完整流程

### 1. 同步内置网页内容

```bash
cd android
bash sync-web.sh
```

脚本会从仓库根目录把网页内容复制进 `app/src/main/assets/web/`，
并生成与之匹配的 `version.json`。
`assets/web/` 本身不入库（见 `.gitignore`），每次构建时重新生成。

### 2. 对齐内容版本号

`app/src/main/assets/web/version.json` 里的 `versionCode`
必须和 `gradle.properties` 里的 `bundledContentVersion` 一致。

```properties
# gradle.properties，当前值
bundledContentVersion=43
```

不一致会导致壳误判内置内容比线上新或旧，热更新逻辑出错。
`sync-web.sh` 会自动对齐，手工改内容时要记得同步这里。

### 3. 递增 App 版本号

`app/build.gradle.kts`：

```kotlin
versionCode = 3          // 必须比上一版大，当前已发布的是 2
versionName = "2.0.1"    // 展示用，随意
```

**versionCode 忘记递增，覆盖安装会失败。**

### 4. 构建并验证

```bash
gradle :app:assembleRelease --no-daemon

APK=app/build/outputs/apk/release/app-release.apk

# 签名指纹必须是 274017a6...
apksigner verify --print-certs "$APK" | grep "SHA-256 digest"

# 关键内容都在
unzip -l "$APK" | grep "assets/web/novel/index.html"

# 版本号确认
aapt2 dump badging "$APK" | head -1
```

### 5. 发布到 GitHub Releases

```bash
gh release create v2.0.1 \
  --title "RP Hub 2.0.1" \
  --notes "更新说明" \
  app/build/outputs/apk/release/app-release.apk
```

当前已发布：
<https://github.com/menglian001/RP-Hub-mod/releases/tag/v2.0.0>

### 6. 分发

用户直接点安装即可，**不需要卸载旧版**（前提是他装的已经是 2.0.0 或更新）。

---

## 七、版本历史与签名对应关系

| App 版本 | versionCode | 内置内容版本 | 签名 SHA-256 前 8 位 | 能否被 2.0.0+ 覆盖 |
|---|---|---|---|---|
| 1.0.0（早期二改壳） | 1 | ≤ v37 | `53756ef1` | **不能**，签名不同 |
| 2.0.0（本工程，已发布） | 2 | v43 | `274017a6` | — |
| 2.0.1 及以后 | 3+ | 递增 | `274017a6` | 能 |

早期 1.0.0 用的是 Android Debug 证书（`CN=Android Debug`），
其私钥在原构建机器的 `~/.android/debug.keystore` 里，当前环境没有找到。
`debug.keystore` 的密码虽然是公开固定值（`android`），
但每台机器生成的私钥都是随机的，**无法从 APK 反推**。
所以从 1.0.0 升到 2.0.0 **必须卸载一次**。

如果日后在旧电脑上找到了 `debug.keystore` 且指纹确为 `53756ef1...`，
可以用它重新签名，实现从 1.0.0 直接覆盖。校验方法：

```bash
keytool -list -v -keystore ~/.android/debug.keystore -storepass android | grep SHA-256
```

---

## 八、热更新与 APK 更新的分工

两条更新通道互相独立，别搞混：

| 通道 | 更新什么 | 触发方式 | 需要签名吗 |
|---|---|---|---|
| **热更新**（网页内容） | `index.html`、`assets/`、`character/`、`novel/` | 推送到 `main` → Cloudflare Pages 自动部署 → App 启动时自动拉取 | 不需要 |
| **APK 覆盖安装** | 壳本体（Kotlin 代码、权限、图标、WebView 行为） | 手动构建并分发 APK | 需要 |

热更新基址（写死在 APK 里的 `UPDATE_BASE_URL`）：

```
https://rp-hub-mod.pages.dev/
```

站点根目录必须有 `version.json` 和 `content.zip`，由
`.github/workflows/deploy.yml` 调用 `tools/pack-content.sh` 自动生成，
内容版本号取 `git rev-list --count HEAD`（提交总数），保证单调递增。

热更新流程：启动请求 `version.json` → `versionCode` 比本地大就下载
`content.zip` → 校验 SHA-256 → 解压到 `files/web/staging` →
下次启动在 WebView 加载之前同步提升为 `files/web/active`。
所以推送内容后，用户开一次 App 下载、再开一次生效，**不需要重装**。

只有改了壳本体才需要发新 APK。改网页内容推 CF 就够了。

---

## 九、常见问题

**装新版报「应用未安装」/ `INSTALL_FAILED_UPDATE_INCOMPATIBLE`**
按顺序查：签名指纹是否一致 → versionCode 是否比已装版本大 → 包名是否被改过。
用 `adb install -r new.apk` 能看到具体错误码。

**从 1.0.0 装 2.0.0 装不上**
预期行为，签名证书不同。必须卸载旧版一次，之后所有版本都能覆盖。

**换电脑后构建出的 APK 装不上**
说明用了不同的 keystore。把备份的 `rphub.keystore` 拷到 `android/`，
配好 `keystore.properties` 再构建。用第二节的 keystore SHA-256 确认文件正确。

**构建时报签名相关错误 / 产物未签名**
检查 `keystore.properties` 是否存在、`storeFile` 路径是否相对 `android/` 正确、
四个环境变量是否有拼写错误。环境变量优先级高于 properties 文件，
两边都配了而值不同时会用环境变量。

**热更新不生效**
壳在 `MainActivity.onCreate` 里、WebView 加载之前同步提升 staging，
不依赖任何弹窗点击。检查 `RPHubNative.contentInfo()` 的返回值，
里面有 active / staging / bundled 三个版本号和内容来源。
再确认 `https://rp-hub-mod.pages.dev/version.json` 的 `versionCode`
确实大于设备上的 active 版本。

**页面报 ERR_CONNECTION_REFUSED**
这是旧壳的行为：找不到文件时 `shouldInterceptRequest` 返回 null，
WebView 转去请求真实网络，在 `https://localhost` 上必然失败。
新壳改成返回明确的 404，不会再出现这种假的网络错误。

**Maven 依赖下载 403 / 超时**
`settings.gradle.kts` 已配置阿里云镜像。仍失败时检查网络代理。

---

## 十、内容来源与许可

内置网页内容来自 `https://github.com/STA1N156/RP-Hub`，
许可证 **CC BY-NC 4.0**（署名 - 非商业）。

分发时需保留原作者署名，且不得用于商业用途。

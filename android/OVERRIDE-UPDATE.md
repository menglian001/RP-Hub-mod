# RP Hub 套壳应用 — 签名与覆盖更新手册

本文件记录让新版本 APK 能**直接覆盖安装**所需的全部信息。
换电脑、换构建环境、换人接手、隔很久再来更新，照这份文档做就不会踩坑。

> 最后核对时间：2026-08-15，对应已发布版本 **2.1.0（versionCode 3，内置内容 v48）**。
> 文中所有指纹、版本号、路径、URL 均为实测值，不是抄来的模板。

---

## 零、给下一个接手的人（最短路径）

想发一个能覆盖当前版本的新 APK，只需要三件事对得上：

1. **包名** `cc.salarycat.rphub` —— 不要改。
2. **签名证书** SHA-256 = `274017a6cc450d8e2a068a409a61e23e9477a0cdb3a004e953945b340a606725`
   —— 必须用同一个 `rphub.keystore`，这个文件**不在仓库里**，需要向持有人索取。
3. **versionCode** 比 `3` 大 —— 改 `android/app/build.gradle.kts`。

其余步骤见第六节。没有 keystore 就做不出可覆盖的包，这是硬约束，没有绕过办法。

### 一句话速查表

| 项 | 值 | 定义在哪 |
|---|---|---|
| 包名 / applicationId | `cc.salarycat.rphub` | `app/build.gradle.kts` |
| namespace | `cc.salarycat.rphub` | `app/build.gradle.kts` |
| keystore 文件 | `android/rphub.keystore`（不入库） | 向持有人索取 |
| key alias | `rphub` | keystore 内 |
| 证书 SHA-256 | `274017a6cc450d8e2a068a409a61e23e9477a0cdb3a004e953945b340a606725` | keystore 内 |
| 已发布 versionCode | `3`（versionName `2.1.0`） | `app/build.gradle.kts` |
| 已发布内置内容版本 | `48` | `gradle.properties` + `assets/web/version.json` |
| 热更新基址 | `https://rp-hub-mod.pages.dev/` | `app/build.gradle.kts` 的 `UPDATE_BASE_URL` |
| minSdk / targetSdk / compileSdk | 24 / 34 / 34 | `app/build.gradle.kts` |

---

## 一、覆盖安装的三个必要条件

Android 允许新 APK 覆盖旧 APK，必须**同时**满足：

| 条件 | 本项目的值 | 定义位置 | 说明 |
|---|---|---|---|
| 包名相同 | `cc.salarycat.rphub` | `app/build.gradle.kts` 的 `applicationId` 与 `namespace` | **永远不要改** |
| 签名证书相同 | SHA-256 `274017a6...` | `rphub.keystore` | 必须是同一个 keystore 里的同一个 alias |
| versionCode 不降低 | 当前 `3` | `app/build.gradle.kts` 的 `versionCode` | 每次发版必须递增 |

任意一条不满足，安装时会报 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`，
手机上表现为「应用未安装」，用户只能卸载重装（丢失全部本地数据：
聊天记录、角色卡、API Key 全没）。

注意 `versionName`（如 `2.1.0`）只是展示用，系统不看它。
真正决定能否覆盖的是 `versionCode` 这个整数。

---

## 二、本项目的签名证书

从 2.0.0 版本起固定使用这一份证书，2.1.0 实测仍为同一份：

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

### 已发布版本的实测签名状态

2.0.0 与 2.1.0 两版输出完全一致：

```
Verifies
Verified using v1 scheme (JAR signing):                 false
Verified using v2 scheme (APK Signature Scheme v2):     true
Verified using v3 scheme (APK Signature Scheme v3):     true
Verified using v3.1 scheme (APK Signature Scheme v3.1): false
Verified using v4 scheme (APK Signature Scheme v4):     false
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

## 四、GitHub Actions 自动构建（推荐路径）

工作流在 `.github/workflows/build-apk.yml`。触发条件：

- 推送到 `main` 且改动了 `android/**` 或该工作流文件本身
- 推送 `app-v*` 标签
- 在 Actions 页面手动 `workflow_dispatch`

**只改网页内容不会触发**，这是有意的——网页走热更新，不需要新 APK。
改了壳但没触发时，去 Actions 页手动 dispatch 一次即可。

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

### 工作流做了什么

按顺序：checkout（`fetch-depth: 0`，因为内容版本号取提交总数）→ 装 JDK 17 与
Gradle 8.7 → 缺 `assets/vendor` 时跑 `tools/fetch-vendor.sh` → 跑
`android/sync-web.sh` 同步内置网页内容 → 从 Secret 解出 keystore →
`gradle :app:assembleRelease` → `apksigner verify --print-certs` 打印指纹 →
`if: always()` 删除 keystore（失败也不残留）→ 上传 Artifact
`RP-Hub-<commit sha>`，保留 30 天。

也就是说 CI 已经把「同步内容 + 签名 + 验签」全包了，
本地不配 keystore 也能拿到可覆盖安装的正式包。

### 从 CI 产物取 APK

Actions → 对应 run → Artifacts 下载 zip，解压得到 `app-release.apk`。
落地后建议自己再核一遍指纹与版本号（第六节第 4 步的命令），
然后改名成 `RP-Hub-<versionName>-v<内容版本>.apk` 上传到 Release。

> 这 4 个 Secret 未配置时该工作流会失败（`test -n "$KEYSTORE_BASE64"` 直接退出），
> 不影响 Cloudflare Pages 的内容部署工作流。

---

## 五、本地构建

### 环境要求（本仓库实测通过的组合）

| 组件 | 版本 | 说明 |
|---|---|---|
| JDK | 17（实测 `17.0.20`，Temurin / OpenJDK 均可） | AGP 8.x 硬要求，用 21 会报 Unsupported class file |
| Gradle | 8.7 | 工程**没有** `gradle/wrapper`，必须用系统装的 gradle |
| Android Gradle Plugin | 8.4.0 | 根 `build.gradle.kts` 的 plugins 块 |
| Kotlin | 1.9.23 | 根 `build.gradle.kts`，与 AGP 8.4 匹配，别单独升 |
| compileSdk / targetSdk | 34（`platforms/android-34`） | |
| minSdk | 24（Android 7.0） | 决定了 v1 签名被跳过，见第二节 |
| build-tools | 34.0.0 | `apksigner`、`aapt2` 都从这里取 |

没有 wrapper 是有意的：`gradle -v` 必须自己是 8.7。版本不对时最典型的报错是
`Unsupported class file major version` 或 AGP 与 Gradle 不兼容。

需要的 SDK 组件，用 `sdkmanager` 装：

```bash
sdkmanager "platforms;android-34" "build-tools;34.0.0" "platform-tools"
```

Maven 仓库已在 `settings.gradle.kts` 配了阿里云镜像，国内网络直接能拉。

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

两处都没配时 `signingConfig` 为空，产出的是**未签名包，装不上**，
构建本身不会报错，很容易漏掉——构建完务必按第六节第 4 步验签。

### 构建命令

```bash
cd android
export ANDROID_HOME=/opt/android-sdk
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
gradle :app:assembleRelease --no-daemon
```

产物：`android/app/build/outputs/apk/release/app-release.apk`

只想快速验证代码能编译、不出包时（比改了 Kotlin 或主题资源）：

```bash
gradle --offline -q compileReleaseKotlin      # 只编 Kotlin
gradle --offline -q processReleaseResources   # 只编资源（themes/colors 等）
```

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
bundledContentVersion=48
```

不一致会导致壳误判内置内容比线上新或旧，热更新逻辑出错。
`sync-web.sh` 会自动对齐，手工改内容时要记得同步这里。

### 3. 递增 App 版本号

`app/build.gradle.kts`：

```kotlin
versionCode = 4          // 必须比上一版大，当前已发布的是 3
versionName = "2.1.1"    // 展示用，随意
```

**versionCode 忘记递增，覆盖安装会失败。**

### 4. 构建并验证

```bash
gradle :app:assembleRelease --no-daemon

APK=app/build/outputs/apk/release/app-release.apk
BT=$ANDROID_HOME/build-tools/34.0.0

# 签名指纹必须是 274017a6...，不对就装不上
"$BT/apksigner" verify --print-certs "$APK" | grep "SHA-256 digest"

# 包名 / versionCode / versionName 一次看全
"$BT/aapt2" dump badging "$APK" | head -1
# → package: name='cc.salarycat.rphub' versionCode='3' versionName='2.1.0' ...

# 内置网页内容与版本号
unzip -l "$APK" | grep "assets/web/novel/index.html"
unzip -p "$APK" assets/web/version.json | head -3
```

这四项全对才发出去。**最容易漏的是验签**——没配 keystore 时构建照样成功，
出来的是未签名包，用户点安装才发现装不上。

### 5. 发布到 GitHub Releases

有 `gh` 时：

```bash
gh release create v2.1.1 \
  --title "RP Hub 2.1.1（内容 v49）" \
  --notes "更新说明" \
  --target "$(git rev-parse HEAD)" \
  app/build/outputs/apk/release/app-release.apk
```

产物文件名建议按 `RP-Hub-<versionName>-v<内容版本>.apk` 命名，
和历史发布保持一致，一眼能看出壳版本与内容版本的对应关系。

也可以直接用 CI 产物：Actions → 对应 run → Artifacts 下载 zip，
里面就是签名好的 `app-release.apk`，改名后上传到 Release 即可，
不需要在本地重新构建（省掉配 keystore 这一步）。

已发布记录：

- 2.1.0 <https://github.com/menglian001/RP-Hub-mod/releases/tag/v2.1.0>
- 2.0.0 <https://github.com/menglian001/RP-Hub-mod/releases/tag/v2.0.0>

> 仓库当前是 **Private**，Release 附件的 `browser_download_url`
> 匿名访问会返回 404，必须登录有权限的账号才能下载。
> 匿名分发需要先把仓库改为 Public，或另找渠道传 APK。

### 6. 分发

用户直接点安装即可，**不需要卸载旧版**（前提是他装的已经是 2.0.0 或更新）。

---

## 七、版本历史与签名对应关系

| App 版本 | versionCode | versionName | 内置内容版本 | 签名 SHA-256 前 8 位 | 能否被 2.0.0+ 覆盖 |
|---|---|---|---|---|---|
| 1.0.0（早期二改壳） | 1 | 1.0.0 | ≤ v37 | `53756ef1` | **不能**，签名不同 |
| 2.0.0（首个自签版本） | 2 | 2.0.0 | v43 | `274017a6` | — |
| 2.1.0（当前已发布） | 3 | 2.1.0 | v48 | `274017a6` | 能 |
| 以后每一版 | 4+ | 随意 | 递增 | `274017a6` | 能 |

2.1.0 的改动内容：壳改为真全屏（消除上下黑边）、返回键交给网页优先处理
（可退出图片管理等管理页）、网页侧生图模型改为弹窗列表选择器。

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

**构建报 `Unsupported class file major version` 或 AGP 不兼容**
JDK 或 Gradle 版本不对。本工程要求 JDK 17 + Gradle 8.7（AGP 8.4.0 / Kotlin 1.9.23）。
工程故意不带 wrapper，`gradle -v` 必须自己就是 8.7，JDK 21 会失败。

**构建成功但装不上，验签发现没有签名者**
没配 keystore。这种情况构建**不会报错**，只会静默产出未签名包。
每次发版都要跑一遍 `apksigner verify --print-certs`，见第六节第 4 步。

**Release 附件下载 404**
仓库是 Private，附件链接匿名访问就是 404，必须登录有权限的账号。
要匿名分发得先把仓库改 Public，或用别的渠道传 APK。

**CI 没有触发构建**
`build-apk.yml` 只在改动 `android/**`、改动该工作流本身、推 `app-v*` 标签
或手动 dispatch 时跑。只改网页内容不会触发（网页走热更新，本来不需要新 APK）。

**装完还是有上下黑边**
检查四处是否都在：`MainActivity.enableFullscreen()`、
`res/values/themes.xml` 的透明系统栏、`res/values/colors.xml` 的浅色窗口底、
以及网页 viewport 的 `viewport-fit=cover`。少任何一处都会露出黑边。
注意黑边属于**壳的改动**，必须装 2.1.0 及以上的 APK，热更新解决不了。

**按返回键直接退出了 App，而不是退出当前页面**
壳会先调网页的 `window.RPHubHandleBack()`，返回 `true` 才不退出。
新加的弹层或管理页如果没在这个函数里加对应分支，就会表现为直接退出。
函数在 `assets/js/app.js` 的 `onMounted` 里，属于网页侧，可走热更新修。

---

## 十、内容来源与许可

内置网页内容来自 `https://github.com/STA1N156/RP-Hub`，
许可证 **CC BY-NC 4.0**（署名 - 非商业）。

分发时需保留原作者署名，且不得用于商业用途。

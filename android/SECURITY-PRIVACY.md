# 安全、隐私与发布基线

本文件是 RP Hub Android 套壳应用的安全与隐私事实清单。
发布、改壳、改热更新、迁移仓库或让 AI 接手前，必须先读本文件和
[OVERRIDE-UPDATE.md](OVERRIDE-UPDATE.md)。

> 本文描述的是当前代码的实际行为，不代表绝对安全保证。
> 网页内容、第三方 API 服务和热更新服务器均会影响用户数据安全。

---

## 1. 当前应用边界

- Android 包名：`cc.salarycat.rphub`
- 最低 Android 版本：Android 7.0（API 24）
- 网页内容来源：APK 内置 `assets/web/`，以及可选热更新内容
- WebView 内部站点：`https://localhost/`
- 热更新基址：`https://rp-hub-mod.pages.dev/`
- 原网页上游：`https://github.com/STA1N156/RP-Hub`
- 原网页许可：CC BY-NC 4.0，必须署名、不得商用

本应用是 WebView 套壳：Android 壳负责本地页面加载、热更新、文件选择与导出；
对话、角色卡、世界书、API 配置等功能由内置网页 JavaScript 实现。

---

## 2. Android 权限与用途

| 权限 | 是否申请 | 用途 | 数据风险 |
|---|---:|---|---|
| `INTERNET` | 是 | 请求 AI API、检查和下载热更新、加载用户主动打开的外链 | 对话内容和 API Key 会发送给用户配置的 API 服务 |
| `ACCESS_NETWORK_STATE` | 是 | 判断网络是否可用 | 不读取用户内容 |
| `POST_NOTIFICATIONS` | 是 | 预留通知能力 | 当前壳没有主动发通知逻辑；用户可在系统设置关闭 |
| 相机、麦克风、定位、通讯录 | 否 | 不使用 | 不读取 |
| 读写全盘存储权限 | 否 | 不申请 | 不扫描用户文件 |

文件导入使用系统文件选择器：用户主动选择的 URI 才会被网页读取。
文件导出仅把用户在网页中主动导出的内容写到公开 `Downloads/RPHub/` 目录。

---

## 3. WebView 安全配置

当前壳的限制：

| 配置 | 当前值 | 含义 |
|---|---:|---|
| 明文 HTTP | 禁止 | `usesCleartextTraffic=false`，仅允许 HTTPS 网络请求 |
| WebView HTTP AssetLoader | 禁止 | `setHttpAllowed(false)` |
| 本地文件访问 | 禁止 | `allowFileAccess=false` |
| Content URI 访问 | 禁止 | `allowContentAccess=false` |
| 第三方 Cookie | 禁止 | `setAcceptThirdPartyCookies(false)` |
| 网页相机/麦克风权限 | 全部拒绝 | `onPermissionRequest()` 直接 `deny()` |
| 多窗口 | 禁止 | `setSupportMultipleWindows(false)` |
| Web 调试 | Release 关闭 | 只在 Debug 构建中开启 |
| 外部链接 | 系统浏览器打开 | 非 `localhost` URL 不在应用 WebView 内加载 |

### 原生桥接接口

网页可调用 `window.RPHubNative`。当前暴露的方法只有：

- `shellVersion()`、`shellVersionCode()`、`contentVersion()`、`contentInfo()`：版本和诊断信息
- `checkUpdate()`、`applyPendingUpdate()`：检查和应用热更新
- `getAnnouncement()`：返回空对象
- `saveBase64(name, mime, dataUrl)`：将网页主动导出的内容写入下载目录

**新增原生桥接方法前必须审查。** `addJavascriptInterface` 会让网页 JavaScript 调用原生代码；
热更新网页或被嵌入的第三方脚本一旦不可信，不应新增能读文件、执行命令、访问敏感权限或发送隐私数据的方法。

---

## 4. 本地数据与隐私

### 数据在哪里

| 数据 | 位置 | 是否默认上传 |
|---|---|---:|
| 对话、角色卡、世界书、网页设置 | WebView 的 IndexedDB / LocalStorage（应用私有目录） | 否 |
| API URL、模型名、API Key | 网页本地存储（应用私有目录） | 否，但发起 API 请求时会发送给该 API 服务 |
| 热更新内容 | `files/web/active`、`files/web/staging` | 否，仅从更新站点下载 |
| 导出的 JSON / 图片等 | `Downloads/RPHub/` | 否，但其他有存储访问权的 App 可能读取 |

### 重要限制

1. API Key 由网页保存，不受 Android Keystore 硬件加密保护。
2. Root、调试设备、恶意备份工具或取得应用私有数据的攻击者可能读取网页本地存储。
3. Manifest 设为 `android:allowBackup="false"`，系统云备份和 `adb backup` 不会把
   聊天记录和网页存储中的 API Key 迁出应用私有目录。代价是换机时数据不会自动迁移，
   用户需要用网页内的导出功能自行备份。
4. 用户应使用可信 API 服务；不要把 API Key 填给陌生的中转站或分享给他人。
5. 导出文件位于公共下载目录，不适合存放未加密的高敏感内容。

### AI 服务与数据外发

当用户点击发送、生成、联网搜索或使用在线功能时，网页可能向用户选择或预置的第三方服务发送：

- 用户输入、角色设定、上下文和生成请求
- 用户配置的 API Key（通常位于 `Authorization` 请求头）
- 可能的联网搜索词、图片生成请求或在线状态信息

内置网页可见的候选/预置端点包括 DeepSeek、SiliconFlow、OpenRouter、Tavily、
`cdn.sta1n.cn`、`nai.sta1n.cn`、`rphub-presence.zeabur.app` 等。它们不是 Android 壳运营的服务。

**发布前必须人工审查网页中所有默认 API URL、公告 URL、图片 URL 和第三方脚本 URL。**
不要声称“数据不上传”；正确表述应为：只有用户不使用联网功能时，壳本身不会主动上传对话内容。

---

## 5. 热更新安全模型

### 当前流程

1. 请求 `version.json`；
2. 下载 `content.zip`；
3. 计算 ZIP 的 SHA-256；
4. 与 `version.json` 中的 `sha256` 比对；
5. 解压到 `files/web/staging`；
6. 校验存在 `index.html`，并防御 zip-slip 路径穿越；
7. 下次启动前把 staging 原子性提升为 `files/web/active`；
8. WebView 优先加载 active，不存在才回退 APK 内置内容。

### 已有保护

- 仅 HTTPS 更新；
- 连接超时 15 秒、读取超时 60 秒；
- ZIP 解压总量上限 200 MiB；
- 禁止绝对路径、`..` 路径和越界写入；
- SHA-256 完整性校验；
- 原子替换失败时尽量保留旧 active 内容；
- 文件缺失时返回 404，不把本地路径交给 WebView 进行真实网络请求。

### 关键高风险：SHA-256 不是签名

当前 `sha256` 与 ZIP 同时由同一个更新站点提供。若更新站点、部署凭据、DNS/CDN 配置或源码仓库被接管，攻击者可以同时替换：

- `version.json` 里的哈希；
- `content.zip` 里的网页 JavaScript。

此时 SHA-256 校验仍会通过，而恶意网页能读取网页本地存储中的对话和 API Key，并调用当前允许的原生桥接接口。

**因此当前热更新应被视为“可信更新站点下的完整性校验”，不是抗服务器被攻破的代码签名更新。**

### 后续安全升级（推荐）

要让热更新具备真正的发布者身份校验，应：

1. 单独生成热更新签名私钥，离线保存；
2. 在 Android 壳中内置对应公钥；
3. 对 `versionCode + zip 名称 + sha256 + size` 生成签名；
4. `version.json` 携带 `signature`；
5. 壳在下载前验证签名，失败就拒绝更新；
6. 私钥只放在 CI Secret 或离线签名环境，不放仓库、不放网页服务器。

完成此升级前，更新站点、GitHub 仓库管理员权限和部署凭据必须按生产密钥同等保护。

---

## 6. APK 签名、覆盖更新与密钥

覆盖更新要求：**包名不变、签名证书不变、versionCode 递增。**
完整规则、发版流程与密钥交接方式见 [OVERRIDE-UPDATE.md](OVERRIDE-UPDATE.md)。

### 当前固定证书

```
Alias            : rphub
DN               : CN=RP Hub, OU=Mod, O=RPHub, L=NA, ST=NA, C=CN
证书 SHA-256     : 274017a6cc450d8e2a068a409a61e23e9477a0cdb3a004e953945b340a606725
证书 SHA-1       : 29e3588a92913a72133ec1154ca65c0110c48ba4
有效期           : 2026-08-14 ~ 2056-08-06
密钥算法         : RSA 2048
keystore SHA-256 : 7df2f5176ca6ece5c942cde49e68a48b0de9c35ba10741e45847e8f4801ea1e1
```

指纹是公开信息，可以放进文档——它只能用来**验证**，不能用来签名。
私钥文件 `rphub.keystore` 与两个密码绝不入库，只通过私密渠道交接。
从备份恢复 keystore 后用上面的 keystore SHA-256 校验是否为同一文件。

### 强制规则

- `applicationId` 永远保持 `cc.salarycat.rphub`；
- 每次正式 APK 必须递增 `versionCode`（已发布的最大值为 `2`）；
- 必须一直使用同一份 `rphub.keystore`；
- 构建完成必须用 `apksigner verify --print-certs` 检查证书 SHA-256；
- 不得把 `*.keystore`、`*.jks`、`keystore.properties`、密码或 Access Token 提交进 Git；
- keystore 必须离线备份至少两份，密码存在密码管理器里；
- GitHub Actions 只从 Secrets 临时恢复 keystore，构建后以 `if: always()` 删除，不上传密钥。

当前 APK 实测为 v2 + v3 签名（v1 因 `minSdk = 24` 被 AGP 自动跳过，不影响覆盖安装）。
v3 为将来密钥轮换保留能力，但不能代替妥善备份私钥。

### 交接给他人构建时

需要私密传递两样：`rphub.keystore` 文件、storePassword 与 keyPassword。
接手方拿到后即具备签发可覆盖安装包的能力——等同于对已安装用户的完全控制权，
因为覆盖安装会继承 App 的全部本地数据与权限。只交给可信任的人。

---

## 7. GitHub 与 CI 安全

### 仓库设置

- 应用仓库应设为 Private；
- 分支保护：`main` 禁止未经审查的直接推送；
- 仅授予维护者最小权限；
- 开启 GitHub 的 secret scanning、push protection、Dependabot alerts；
- 定期检查 Deploy key、GitHub App、Actions 第三方集成和协作者列表；
- 不在 Git remote URL、README、Issue、日志或聊天记录中包含令牌。

### Actions Secret

需要的 Secret：

- `RPHUB_KEYSTORE_BASE64`
- `RPHUB_STORE_PASSWORD`
- `RPHUB_KEY_ALIAS`
- `RPHUB_KEY_PASSWORD`

若令牌、密码或 keystore 曾泄露：

1. 立即撤销相关访问令牌；
2. 修改仓库/部署/CI 密码；
3. 检查最近提交、Actions 日志和发布物；
4. 若 APK 签名私钥泄露，评估使用 v3 签名轮换；若无法轮换，必须视为该证书永久不再可信。

---

## 8. 发布前检查表

### 网页与热更新

- [ ] `version.json` 的 `versionCode` 大于线上已发布内容版本；
- [ ] ZIP SHA-256 与 `version.json` 一致；
- [ ] ZIP 包含 `index.html`、`novel/index.html`、`character/index.html` 和所有依赖；
- [ ] 检查默认 API URL、第三方脚本和公告内容；
- [ ] 不含调试页面、测试 API Key、令牌、个人数据或诊断浮层；
- [ ] 使用干净设备验证下载、重启提升和页面加载。

### APK

- [ ] `applicationId` 未变（`cc.salarycat.rphub`）；
- [ ] `versionCode` 已递增（大于已发布的 `2`）；
- [ ] 使用固定证书签名，`apksigner verify --print-certs` 输出 `274017a6...`；
- [ ] `apksigner verify -v` 的 v2、v3 为 true；
- [ ] 构建目录内无残留的 `rphub.keystore` / `keystore.properties` 被误提交；
- [ ] Release 构建未开启 WebView 调试；
- [ ] Manifest 权限没有新增且每项都有用途说明；
- [ ] 在至少一台 Android 7+ 设备验证安装、升级覆盖、文件导入导出和外链跳转。

---

## 9. 事件响应

| 事件 | 立即处理 |
|---|---|
| API Key 泄露 | 到对应 API 平台撤销并新建 Key；不要只删除本地记录 |
| GitHub/部署令牌泄露 | 立即吊销，检查仓库和部署日志，生成最小权限的新令牌 |
| 热更新站点被篡改 | 停止部署，撤回/修复 `version.json`，检查所有 ZIP 与最近发布内容；必要时在 APK 中更换更新基址 |
| APK keystore 泄露 | 当作严重事件处理；评估 v3 密钥轮换或发布新包名迁移 |
| 用户数据误删 | 从用户自己导出的备份恢复；壳不提供服务端备份 |

---

## 10. 禁止事项

- 不提交签名私钥、密码、API Key、GitHub Token、Cloudflare Token 或用户导出数据；
- 不为了省事关闭 TLS、开启 `allowFileAccess` 或给网页开放任意原生能力；
- 不信任未审查的热更新 ZIP、网页脚本或第三方 API 中转；
- 不把用户的聊天记录、角色卡、API Key 上传到 issue、日志、公共仓库或第三方排错平台；
- 不用不同证书构建“更新版”APK；这会导致覆盖安装失败。

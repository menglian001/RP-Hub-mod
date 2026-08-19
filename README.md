# Roleplay Hub

[![License: CC BY-NC 4.0](https://img.shields.io/badge/License-CC%20BY--NC%204.0-lightgrey.svg)](https://creativecommons.org/licenses/by-nc/4.0/)
[![Vue](https://img.shields.io/badge/Vue-3-4FC08D.svg?logo=vue.js)](https://vuejs.org/)
[![Tailwind CSS](https://img.shields.io/badge/Tailwind_CSS-38B2AC?logo=tailwind-css&logoColor=white)](https://tailwindcss.com/)
[![DaisyUI](https://img.shields.io/badge/DaisyUI-5A0EF8?logo=daisyui&logoColor=white)](https://daisyui.com/)

> **一款纯前端运行的本地角色扮演（Roleplay）对话和角色卡生成工具。**

**【免责与授权声明】**  
本项目基于 **[CC BY-NC 4.0（知识共享-署名-非商业性使用 4.0 国际许可协议）](./LICENSE)** 开源。**明确禁止任何形式的商业化使用（包括但不限于：作为收费服务提供、打包在付费产品中售卖、在产品内植入广告盈利等）。** 任何使用者必须遵守该协议，尊重原作者的署名权。对于违反协议的商业行为，保留追究法律责任的权利。

---

## 核心特性 (Features)

Roleplay Hub 致力于提供流畅、私密且功能强大的本地化AI Roleplay体验。

- 角色卡、世界书、正则脚本和多用户资料管理
- 总结记忆与向量记忆，可按角色和剧情分支独立保存
- 剧情分支创建、切换、回档、重命名和完整导入导出
- UI 模板变量分析与对话状态展示
- 自动生图、单张重新生成和多套内置画师风格
- 角色卡生成、万相广场与"墨韵 · 造梦"在线工具

二改相对上游额外提供：世界书阅读/管理工具、角色图片库、接口连接测试。

当前网页内容已同步至上游 **RP-Hub 1.8.4**（含记忆二次压缩、UI 模板变量块
定位优化、版本心跳提醒等）。

## 快速开始 (Quick Start)

本项目无需复杂的 Node.js 环境或依赖安装，即开即用！

### 1. 下载与运行
1. 点击项目主页绿色的 `Code` 按钮，选择 `Download ZIP`。
2. 将下载的 ZIP 压缩包解压到您的本地任意文件夹中。
3. 双击打开 `index.html` 文件，即可在浏览器（推荐 Chrome / Edge / Firefox）中启动 Roleplay Hub。

*(注：如果您遇到跨域或本地文件读取权限问题，可以尝试使用 VS Code 的 `Live Server` 插件，或简单的本地服务器工具来运行该目录。但在绝大多数现代浏览器中，双击 index.html 即可正常使用所有核心功能。)*

### 2. 初始化设置
1. 打开应用后，点击侧边栏（或顶部菜单）的**设置 (Settings)** 选项。
2. 选择自定义配置，填入您自己的或第三方提供的 API 节点 (`API URL`)。
3. 填入对应的 `API Key`，并输入或选择您想使用的 `模型名称 (Model)`。
4. 在**角色管理**界面，导入您的角色卡文件（或点击新建角色并手动填写设定）。
5. 回到对话界面，开始属于您的 Roleplay 旅程

---

## 目录结构 (Directory Structure)

```text
Roleplay-Hub/
├── index.html                     # 主界面与脚本加载入口
├── character/                     # 角色卡生成工具
│   └── index.html
├── novel/                         # 墨韵 · 造梦
│   └── index.html
├── assets/
│   ├── css/
│   │   ├── styles.css             # 全局样式
│   │   └── image-library.css      # 图片管理样式
│   ├── js/
│   │   ├── built-in-content.js    # 默认预设、模式提示词、画师串与更新公告
│   │   ├── core-utils.js          # 通用工具、角色卡处理与基础配置
│   │   ├── data-services.js       # 存储、记忆、上下文、分支与 UI 状态
│   │   ├── runtime-services.js    # API 请求、消息渲染、连接测试
│   │   ├── presence.js            # 匿名在线人数与版本心跳
│   │   ├── ui-components.js       # 选择器、侧边栏、弹窗与页面组件
│   │   └── app.js                 # 主业务入口与页面状态
│   └── vendor/                    # 离线化的第三方库与字体
├── presence-server/               # 匿名在线人数服务（Node，零依赖）
├── tools/
│   ├── fetch-vendor.sh            # 拉取 vendor 离线依赖
│   └── pack-content.sh            # 打热更新包 content.zip
├── android/                       # Android 套壳工程
└── README.md                      # 项目说明
```

### 代码组织说明

页面按 `built-in-content` → `core-utils` → `data-services` → `runtime-services`
→ `presence` → `ui-components` → `app` 的顺序加载，彼此有依赖，请不要调整。

- 修改默认预设、各模式提示词、生图画师串或工具说明时，统一编辑 `built-in-content.js`。
- 更新公告固定放在 `built-in-content.js` 最底部，方便查找和替换。
- 可复用界面统一放在 `ui-components.js`，业务数据处理放在 `data-services.js`。
- 项目没有构建步骤，修改后刷新浏览器即可验证。

---

## Android 套壳应用 (Android App)

本仓库同时维护一个 Android 套壳版，内置完整网页内容并支持热更新。

当前已发布：**2.1.0**（versionCode 3）
→ [Releases](https://github.com/menglian001/RP-Hub-mod/releases/tag/v2.1.0)

内置内容版本号取仓库提交总数，由 `android/sync-web.sh` 在构建前自动写入
`gradle.properties` 的 `bundledContentVersion`，无需手动维护。

发版前必读的三条硬约束，任意一条不满足用户就装不上、只能卸载重装（本地数据全丢）：

| 项 | 值 |
|---|---|
| 包名 / applicationId | `cc.salarycat.rphub`（永远不要改） |
| 签名证书 SHA-256 | `274017a6cc450d8e2a068a409a61e23e9477a0cdb3a004e953945b340a606725` |
| key alias | `rphub` |
| versionCode | 必须大于已发布的 `3` |

签名私钥 `rphub.keystore` 与其密码**不在仓库里**，需向持有人索取。
GitHub Actions 已配好签名 Secret，推 `android/**` 或手动 dispatch 即可拿到签名包。

打包环境（实测通过的组合）：JDK 17 + Gradle 8.7 + AGP 8.4.0 + Kotlin 1.9.23，
compileSdk/targetSdk 34、minSdk 24、build-tools 34.0.0。工程不带 wrapper。

两条更新通道互相独立：

| 通道 | 更新什么 | 怎么触发 |
|---|---|---|
| 热更新 | 网页内容（`index.html`、`assets/`、`character/`、`novel/`） | 推送到 `main` → Cloudflare Pages 自动部署 → App 启动时自动拉取 |
| APK 覆盖安装 | 壳本体（Kotlin 代码、权限、图标、全屏与返回键行为） | 构建签名 APK 并分发 |

改网页内容推一下就行，用户不用装新包；只有改壳才需要发 APK。

详细文档：

- [`android/OVERRIDE-UPDATE.md`](./android/OVERRIDE-UPDATE.md) — 签名证书、密钥保管与交接、打包环境、完整发版流程、常见问题
- [`android/README.md`](./android/README.md) — 工程结构、热更新机制、`window.RPHubNative` 接口、返回键与全屏行为
- [`android/SECURITY-PRIVACY.md`](./android/SECURITY-PRIVACY.md) — 权限用途、数据存放、外发行为与已知限制

---

## 协议与许可 (License)

本项目严格遵守以下开源协议：

**[Creative Commons Attribution-NonCommercial 4.0 International (CC BY-NC 4.0)](https://creativecommons.org/licenses/by-nc/4.0/deed.zh-hans)**

* **您可以**：自由地共享（在任何媒介以任何形式复制、发行本作品）与演绎（修改、转换或以本作品为基础进行创作）。
* **您必须**：
  * **署名 (Attribution)**：给出适当的署名，提供指向本许可协议的链接，同时标明是否对原始作品作了修改。
  * **非商业性使用 (NonCommercial)**：**您不得将本作品或演绎作品用于任何商业目的。** 禁止任何形式的售卖、付费订阅集成或利用本项目进行广告牟利。
* 若要获取本项目的商业授权，请直接联系项目原作者。

详细许可条款请参见根目录下的 [`LICENSE`](./LICENSE) 文件。

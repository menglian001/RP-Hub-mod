# Roleplay Hub（二改版 / Modified Fork）

> 本项目是 **二次修改版本（二改）**，并非原创。
> 原项目：**[STA1N156/RP-Hub](https://github.com/STA1N156/RP-Hub)** — 原作者 [@STA1N156](https://github.com/STA1N156)
> 原项目许可：[CC BY-NC 4.0](./LICENSE)（署名 · 非商业性使用）

一款纯前端运行的本地角色扮演（Roleplay）对话与角色卡生成工具。无需 Node.js 环境，双击 `index.html` 即可使用。

---

## 署名与修改声明（Attribution & Modification Notice）

按 CC BY-NC 4.0 的署名要求，明确声明如下：

| 项目 | 内容 |
|---|---|
| 原始作品 | [Roleplay Hub](https://github.com/STA1N156/RP-Hub) |
| 原作者 | [@STA1N156](https://github.com/STA1N156) |
| 原许可协议 | [CC BY-NC 4.0](https://creativecommons.org/licenses/by-nc/4.0/deed.zh-hans) |
| 本仓库性质 | 在原作品基础上修改的演绎作品（已作改动，见下表） |
| 本仓库许可 | 沿用 CC BY-NC 4.0，完整保留原 `LICENSE` 文件 |
| 商业使用 | **禁止**。本仓库不做任何商业化，不接受赞助、不含广告、不提供收费服务 |

原作者保留原作品的一切权利。若原作者要求下架本仓库，将立即执行。
商业授权相关事宜请直接联系原作者，本仓库无权授予。

## 改动清单（Changes）

以上游 `main` 分支为基准（忽略行尾差异）：

| 文件 | 改动规模 |
|---|---|
| `assets/js/app.js` | 约 +1113 / -48 行 |
| `index.html` | 约 121 行 |
| `assets/css/styles.css`、`assets/js/card-utils.js`、`assets/js/ui-select.js`、`assets/js/utils.js`、`character/index.html` | 未修改 |

主要改动：

- **新增「世界书阅读/管理」工具**：允许模型在对话中主动读取世界书条目，并可按权限进行管理；提供「只读」与「可编辑」两档权限（`ACTIVE_TOOL_WORLD_*`）。
- **新增 API / 生图接口连接测试**：在设置页可直接测试对话接口与绘图接口的连通性。
- 配套的界面与设置项调整。

## 快速开始

1. 下载本仓库 ZIP 并解压（`Code` → `Download ZIP`）。
2. 双击 `index.html`，用 Chrome / Edge / Firefox 打开。
3. 进入**设置**，填入你自己的 API 节点、API Key 与模型名称。
4. 在**角色管理**中导入角色卡或新建角色，即可开始对话。

若遇到本地文件读取限制，可用任意静态服务器打开该目录，例如：

```bash
python3 -m http.server 8899
```

本项目不内置任何 API Key，所有配置保存在浏览器本地。

## 目录结构

```text
├── index.html            # 主程序
├── character/index.html  # 辅助页面
├── assets/
│   ├── css/styles.css
│   └── js/
│       ├── app.js        # 核心业务逻辑（主要改动集中于此）
│       ├── card-utils.js
│       ├── ui-select.js
│       └── utils.js
├── LICENSE               # 原项目 CC BY-NC 4.0，原样保留
└── README.md
```

## 许可

[Creative Commons Attribution-NonCommercial 4.0 International (CC BY-NC 4.0)](https://creativecommons.org/licenses/by-nc/4.0/deed.zh-hans)

- **可以**：共享与演绎。
- **必须**：署名原作者、提供许可协议链接、标明已作修改（本 README 即为此声明）。
- **不得**：用于任何商业目的。

完整条款见 [`LICENSE`](./LICENSE)。

# RP-Hub 在线人数服务

一个无需数据库的匿名在线人数接口。浏览器每 20 秒报到一次，60 秒未报到会自动离线；同一浏览器的多个标签页共用一个编号，只计算为 1 人。

## Zeabur 部署

1. 在 Zeabur 新建服务并连接 RP-Hub 仓库。
2. 将服务的根目录设为 `presence-server`，Zeabur 会自动读取 `Dockerfile`。
3. 建议添加环境变量 `ALLOWED_ORIGINS`，值为 RP-Hub 网页的完整来源，例如 `https://example.com`。多个来源用英文逗号分隔。
4. 部署完成后复制 Zeabur 提供的 HTTPS 域名，填入 RP-Hub `index.html` 中的 `rphub-presence-api` 配置。

可选环境变量：

- `PRESENCE_TTL_MS`：离线判定时间，默认 `60000` 毫秒，允许 30 秒至 5 分钟。
- `ALLOWED_ORIGINS`：允许访问接口的网站来源；未设置时允许所有来源。

## 接口

- `GET /health`：健康检查。
- `GET /v1/online`：读取当前在线人数。
- `POST /v1/presence`：匿名心跳并返回当前在线人数，请求体为 `{ "clientId": "..." }`。

服务只在内存中保存随机浏览器编号及其过期时间，不接收或保存角色卡、聊天记录、API 密钥等 RP-Hub 数据。请在 Zeabur 保持单实例运行；多实例若要共享人数，需要再接入 Redis。

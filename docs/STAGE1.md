# 实时语音通话 · 第一阶段

Vue 3 + TypeScript + Vite 前端，通过官方 LiveKit JavaScript SDK 完成 WebRTC 音频通信。独立 Node.js 服务签发短期 Token，LiveKit Server 自部署。没有 Spring Boot、登录、呼叫邀请、视频或数据库。

支持 localhost 和可信局域网两种开发模式。**局域网现在使用 HTTP/WS，无需证书，麦克风测试需按 [局域网指南](deploy/LAN.md) 设置浏览器开发例外**；下文默认命令仍为 localhost 模式。

## 目录

```text
frontend/src/App.vue                 页面
frontend/src/services/voiceRoom.ts   连接、麦克风、订阅与音频清理
frontend/src/services/token.ts       独立 Token API 层
dev-token-server/src/server.ts       开发 Token API
dev-token-server/src/index.ts        环境配置和服务入口
deploy/compose.yaml                 Docker 部署
deploy/livekit.yaml                  LiveKit 本机配置
deploy/compose.lan.yaml              LiveKit 局域网部署
deploy/LAN.md                        HTTP 局域网、浏览器配置及验收
scripts/setup-lan.mjs                根据局域网 IP 生成配置
.env.example                        服务端环境变量模板
```

## 环境与启动

建议 Node.js 22.22+（或满足依赖要求的更新 LTS）、npm、Docker Desktop（Linux containers）及支持 WebRTC 的浏览器。需要麦克风，推荐耳机。

所有命令从项目根目录执行，PowerShell 如拦截 npm.ps1，请使用 `npm.cmd`。

```powershell
npm.cmd install
Copy-Item .env.example .env   # 仅首次创建；已有 .env 时不要覆盖
npm.cmd run livekit:up
```

分别在两个终端启动：

```powershell
npm.cmd run dev:token
```

```powershell
npm.cmd run dev
```

打开 http://127.0.0.1:5173 。前端端口固定为 5173，开发代理把 `/api` 转发至 127.0.0.1:3001。修改 `.env` 的 TOKEN_PORT 后，重启前端和 Token 服务，代理会自动读取新端口。

```powershell
npm.cmd test
npm.cmd run build
docker compose -f deploy/compose.yaml logs --tail 50
npm.cmd run livekit:down
```

构建输出为 `frontend/dist`；它仍需要 `/api` 反向代理才能取 Token。当前只提供本地 Vite 开发启动方式，不将无认证 Token 接口用于公网部署。

## LiveKit 与 Token

固定镜像为 `livekit/livekit-server:v1.9.9`，映射端口仅绑定本机：7880/TCP 为 HTTP/WebSocket 信令，7881/TCP 与 7882/UDP 为媒体。`rtc.node_ip: 127.0.0.1` 用于同机浏览器访问 Docker 映射端口，不适用于手机、远程主机或公网。

`.env` 的 Key/Secret 必须与 `deploy/livekit.yaml` 一致。模板中是公开的本地测试凭据，禁止用于生产。凭据只由服务端读取，不使用 `VITE_` 前缀、不进入前端包。修改 LiveKit 配置后运行 `docker compose -f deploy/compose.yaml restart`。

`POST /api/dev/token` 接收 JSON `{ "roomName": "voice-test", "participantName": "Alice" }`，返回 `{ "token": "...", "serverUrl": "ws://localhost:7880" }`。用户名作为 identity，同一房间必须不同，否则 LiveKit 会替换旧连接。Token 有效期 10 分钟，只能加入指定房间、发布麦克风和订阅，不允许视频及数据发布。

页面地址留空时使用服务端返回的地址；手动填写地址时，该 LiveKit 必须配置相同 Key/Secret。HTTPS 页面必须连接 WSS 服务。

开发 Token 服务固定监听 127.0.0.1，限制浏览器 Origin、JSON 类型及请求体大小。它没有身份认证，任何本机进程仍可申请 Token；不要通过隧道或代理公开该接口。

## 双客户端人工验收

1. 打开两个浏览器窗口（可用 Chrome 与 Edge），地址均为 http://127.0.0.1:5173 。
2. 都填写 `voice-test`，用户名分别使用 `Alice` 和 `Bob`，点击加入房间。两边应显示两位参与者。
3. 分别点击“开启麦克风”，允许权限；默认加入不会自动采集麦克风。若出现“启用声音”，点击解除自动播放限制。
4. 佩戴耳机，轮流说话确认双向可听。单机两窗口共用同一麦克风可能产生回声；真实声学验收推荐两个独立设备和耳机，但跨设备需按下节另行配置。
5. 关闭任一端麦克风，确认另一端不再收到该端声音；重新开启验证恢复。
6. Bob 离开，Alice 列表应更新，Bob 麦克风占用应释放；Bob 改入 `another-room`，确认双方不再互听。
7. 验证拒绝麦克风权限、重复点击加入、连接中取消、离开后重进、刷新页面及关闭窗口。拒绝麦克风后仍可留在房间听声音，允许权限后可以重试。
8. 停止 LiveKit 验证重连/断开提示，再启动并重新加入。

## 常见问题和手机访问

- 无法加入：检查三个进程、Key/Secret 一致性、WebSocket 地址、防火墙、Docker 日志；重复 identity 会断开原连接。
- 麦克风拒绝：检查网站及系统权限、设备占用。HTTP 局域网默认禁止采集，开发例外设置见 deploy/LAN.md。
- 已连接无声：两端开启麦克风、解除自动播放限制、检查系统输出设备和媒体端口。信令连接成功不代表媒体网络可达。
- 本地端口占用：关闭占用端口的服务或协调修改配置；Vite 使用 strictPort，避免静默换端口导致 Origin 错误。
- Node 版本：当前开发环境 22.21 可以通过构建，但 livekit-client 的间接依赖 machina 要求 >=22.22，请使用项目声明版本。
- 手机不能访问电脑的 localhost。按 [LAN 模式](deploy/LAN.md) 配置媒体端口及浏览器例外，访问服务器 HTTP 局域网地址。没有开发例外的浏览器无法保证 HTTP 下发声，当前优先支持桌面 Chrome/Edge。不要公开无认证 Token 服务。

## 功能和验证边界

已实现：连接/取消/离开、自动重连状态展示、参与者与讲话状态、麦克风开关、自动订阅与 attach/detach、播放授权、权限错误、退出和页面卸载清理。通过 generation 标记和操作锁隔离过期异步回调。

自动测试覆盖 Token 签名/房间授权/有效期/音频限制/非法请求，以及模拟 SDK 的重复加入、连接取消、麦克风开关、权限拒绝、音频挂载清理、播放限制和断开清理。模拟测试不能证明真实音频传输。

HTTP 改造后共 17 项测试通过，覆盖 HTTP Origin、WS 地址选择、混合内容拦截，以及非安全上下文下加入房间和麦克风限制。前后端构建通过，HTTP 前端无需证书启动成功；未完成跨设备真实音频验收。

本次已完成 npm 安装、TypeScript 检查、生产构建、自动测试及 Docker LiveKit 启动检查。浏览器自动化服务不可用，未完成真实双浏览器、麦克风权限及双向音频端到端验收；请按上面的人工步骤验证。构建存在 LiveKit SDK 单包超过 500 kB 的体积提示，不影响运行。

## 第二阶段：Spring Boot

替换 `dev-token-server`，由 Spring Boot 认证真实用户、校验房间访问权限并生成 Token；前端仅需调整 `frontend/src/services/token.ts` 的接口和认证信息以及开发代理。不要继续信任客户端提交的用户名作为授权身份。`voiceRoom.ts` 的音频连接层可以复用，业务呼叫信令在后续独立增加。

官方参考：[本地部署](https://docs.livekit.io/transport/self-hosting/local/)、[Token 权限](https://docs.livekit.io/frontends/reference/tokens-grants/)、[客户端 SDK](https://docs.livekit.io/reference/client-sdk-js/)。

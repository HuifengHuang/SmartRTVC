# 局域网 HTTP 语音测试

当前使用 HTTP 页面 + WS 信令，无需证书。只用于可信局域网开发；页面、Token 和信令没有 TLS 保护，不要公开到公网。

## 启动

根目录执行，IP 替换为服务器以太网/Wi-Fi IPv4：

```powershell
npm.cmd run lan:setup -- 192.168.31.38
npm.cmd run livekit:lan:up
```

先 Ctrl+C 停止旧前端和 Token，分别在两个终端启动：

```powershell
npm.cmd run dev:token:lan
npm.cmd run dev:lan
```

所有设备访问 **http://192.168.31.38:5173**，LiveKit 地址留空。服务返回 `ws://192.168.31.38:5173/livekit`，Vite 代理到本机 LiveKit。使用相同房间、不同用户名。

配置脚本读取 .env 凭据，覆盖 .env.lan 和 deploy/livekit.lan.yaml。IP 或凭据变化后重新生成并重启三个服务。主机自身也使用上述局域网地址。localhost 模式命令保留，两种模式不能同时占用相同端口。

## HTTP 麦克风限制与开发测试配置

浏览器默认禁止 HTTP 局域网 IP 调用 getUserMedia。应用允许尝试加入房间接收音频，但开启麦克风时会提示限制；后端无法取消浏览器限制。

每台需要发声的测试客户端使用桌面 Chrome/Edge：

1. Chrome 打开 `chrome://flags/#unsafely-treat-insecure-origin-as-secure`；Edge 打开 `edge://flags/#unsafely-treat-insecure-origin-as-secure`。
2. 在 **Insecure origins treated as secure** 中仅填写 `http://192.168.31.38:5173`（替换实际 IP，带端口，不带路径）。
3. 设为 Enabled，点击 Relaunch 重启浏览器。
4. 再打开测试页面，加入房间，开启麦克风并允许网站和系统麦克风权限。

此选项把指定 HTTP 来源视作安全来源，但不加密流量。仅对你控制的测试地址启用，不用通配符；测试结束清空地址、恢复 Default 并重启。代码不会修改浏览器设置。企业策略或部分浏览器可能不提供该选项；iOS/Safari 等无此例外的环境不在本次 HTTP 双向通话支持范围内。HTTP localhost 通常不需要此例外。

## 防火墙与验收

服务器允许本地子网访问 TCP 5173、7881 和 UDP 7882；3001 和 7880 不对局域网开放。Windows 管理员 PowerShell 示例（专用网络）：

```powershell
New-NetFirewallRule -DisplayName 'Voice Lab LAN TCP' -Direction Inbound -Action Allow -Protocol TCP -LocalPort 5173,7881 -RemoteAddress LocalSubnet -Profile Private
New-NetFirewallRule -DisplayName 'Voice Lab LAN UDP' -Direction Inbound -Action Allow -Protocol UDP -LocalPort 7882 -RemoteAddress LocalSubnet -Profile Private
```

不要关闭整个防火墙。设备须在可互访局域网，避免访客网络/AP 隔离。媒体直连服务器局域网 IP；当前不提供 TURN。

1. 两台设备访问 `http://服务器IP:5173/api/health`，应返回 `{"status":"ok"}`。
2. 配置浏览器开发例外，同房间不同用户名加入，检查列表。
3. 开启麦克风、授权，必要时点击“启用声音”，佩戴耳机验证双向通话。
4. 验证静音、离开重进与不同房间隔离。
5. 403：检查页面 Origin 与 .env.lan 一致，并使用 dev:token:lan，不能改成通配来源。
6. 麦克风受限：确认配置完整 HTTP IP:端口并重启浏览器，检查系统权限。
7. 已连接无声：检查媒体端口、防火墙、Docker 日志 nodeIP 和输出设备。信令成功不代表媒体可达。

若看到 Vite `ws proxy error: write ECONNABORTED`，并且网络请求 `/livekit/rtc/v1` 返回 404：当前固定的 LiveKit 1.9.9 仅支持旧 `/rtc` 信令。客户端已设置 `singlePeerConnection: false` 直接使用旧路径；更新代码后刷新页面、离开并重新加入即可。不要通过隐藏所有代理错误处理问题；如果旧 `/rtc` 仍失败，应继续检查 Token、服务和网络。

停止两个终端并运行 `npm.cmd run livekit:lan:down`。恢复 localhost 使用 README 原命令。

参考：[MDN getUserMedia](https://developer.mozilla.org/en-US/docs/Web/API/MediaDevices/getUserMedia)、[Chromium 开发例外](https://www.chromium.org/blink/serviceworker/service-worker-faq/)。


# SmartRTVC 第二阶段

在第一阶段 Vue 3 / LiveKit 音频连接基础上增加 Java 17、Spring Boot 3、Spring Security JWT、MyBatis-Plus、MySQL 和 Redis。正式页面无需启动 Node Token 服务。

## 启动（Windows PowerShell）

需要 Docker Desktop（Linux containers）运行中、Node >=22.22。Docker 构建自带 Maven 和 Java 17，本机不必安装 Java。首次执行：

```powershell
npm.cmd ci
node scripts/setup-stage2.mjs
docker compose --env-file .env.stage2 -f deploy/compose.yaml -f deploy/compose.stage2.yaml up -d --build
npm.cmd run dev
```

本次开发已在本地生成配置；已存在时无需再次运行 setup。脚本不会覆盖原有配置，密钥随机生成并被 Git 忽略。首次构建需要联网下载 Maven 依赖与镜像。

打开 **http://127.0.0.1:5173**（请统一此地址，WebSocket Origin 校验默认仅允许该来源）。

```powershell
# 日志
docker compose --env-file .env.stage2 -f deploy/compose.yaml -f deploy/compose.stage2.yaml logs -f backend livekit
# 停止，保留数据库数据
docker compose --env-file .env.stage2 -f deploy/compose.yaml -f deploy/compose.stage2.yaml down
# 配置语法验证，不打印密钥
docker compose --env-file .env.stage2 -f deploy/compose.yaml -f deploy/compose.stage2.yaml config --quiet
```

| 服务 | 本机地址 | 容器间地址 |
|---|---|---|
| Vue | http://127.0.0.1:5173 | 本机运行 |
| Java REST / 信令 | http://127.0.0.1:8080 /ws | backend:8080 |
| LiveKit | ws://127.0.0.1:7880 | http://livekit:7880 |
| MySQL | 127.0.0.1:3306 | mysql:3306 |
| Redis | 127.0.0.1:6379 | redis:6379 |

沿用第一阶段 7881/TCP、7882/UDP 和 node_ip 网络配置。第二阶段通过 Compose 覆盖文件挂载独立生成的 LiveKit 配置，添加 Webhook 和随机密钥，不修改原配置。不要单独启动第一阶段 Compose 来替代第二阶段配置。当前仅本地开发，公网或手机访问需另配 HTTPS/WSS、Origin、可信证书、媒体可达地址与防火墙，手机不能使用电脑的 localhost。

## 模块和数据

- `backend/.../auth`：BCrypt 注册登录、2 小时 JWT、Redis 注销撤销、30 秒一次性 Ticket。
- `user`：MyBatis-Plus 用户访问，仅输出公开字段。
- `signaling` / `config`：握手认证、Origin 验证、单设备会话、心跳和在线 TTL（60 秒）。
- `call`：事务和行锁管理呼叫、接听、挂断、成员权限；数据库 `user_call` 唯一主键避免并发占用。
- `livekit`：官方 JVM Server SDK 签发 60 秒房间 Token、创建/删除房间、校验 Webhook 签名及原始请求体摘要。
- `frontend/src/services`：API、WebSocket、通话编排和原有媒体封装分离；`components/LoginForm.vue` 提供认证表单。
- `deploy/compose.stage2.yaml`：新增 MySQL、Redis、Java 服务；`scripts/setup-stage2.mjs` 生成本地配置。

SQL 在 `backend/src/main/resources/schema.sql`，启动时幂等创建：

| 表 | 主要字段 |
|---|---|
| app_user | id、username（唯一）、password_hash、created_at、updated_at |
| call_session | id、caller_id、callee_id、room_name、state、双方连接标记、end_reason、created_at/accepted_at/active_at/ended_at |
| user_call | user_id（唯一占用）、call_id；结束时事务内释放 |

## API 与事件

响应统一为 `{data, error}`，失败使用对应 HTTP 状态。除注册、登录、验签 Webhook 和 Ticket 握手路径外，API 必须提供 `Authorization: Bearer <JWT>`。当前用户 ID 来自 JWT。

| 方法 | 路径 | 输入 / 行为 |
|---|---|---|
| POST | /api/auth/register、/api/auth/login | username、password；登录返回 token、user |
| POST | /api/auth/logout | 撤销当前 JWT、关闭信令、结束当前通话 |
| GET | /api/users/me、/api/users、/api/users/{id} | 当前用户、最多 200 名测试用户及在线状态、指定用户 |
| POST | /api/ws/ticket | 获取一次性凭证；连接 `/ws?ticket=...` |
| POST | /api/calls | calleeId；发起呼叫 |
| GET | /api/calls/current、/api/calls/{id} | 恢复/读取当前通话 |
| POST | /api/calls/{id}/accept、/hangup、/token | 接听、挂断、获取媒体凭证 |
| POST | /api/livekit/webhook | LiveKit 签名事件，原始请求体验签 |

信令封装 `{eventId,type,callId,timestamp,data}`，data 是通话快照。推送 CALL_INVITE、CALL_ACCEPTED、CALL_STATE_SYNC、CALL_ENDED；业务操作错误通过 REST 的 error 返回；未知信令消息推送 ERROR。客户端发送 `ping`，服务端返回 PONG。错误、拒接、取消与超时事件可按同一封装扩展。

`RINGING → CONNECTING → ACTIVE → ENDED`：仅被叫能接听；双方经过 LiveKit participant_joined 事件确认媒体连接后进入 ACTIVE，不能由客户端直接修改。麦克风权限拒绝不等于连接失败，页面允许重新开启。挂断幂等；信令离线或媒体离开结束通话。服务重启清理未结束通话，避免永久占用。结束后删除房间并短期重复清理，防止未过期 Token 重新加入残留房间。

## 双用户验收

1. 按上方命令启动所有服务，检查 backend 和 livekit 日志。
2. 分别在 Chrome 和 Edge（或独立隐私窗口）打开页面，注册 alice、bob，密码至少 8 位。用户名限 3–32 位字母数字下划线。
3. 两端显示信令在线后，A 刷新列表并呼叫 B；B 收到来电，点击接听。
4. 两端允许麦克风；自动播放受限时点“启用声音”。建议佩戴耳机避免同机回声。
5. 确认双方可听到声音，状态变成通话中，测试麦克风切换和计时。
6. 任意一方挂断，确认双方停止采集、显示已结束，并能再次呼叫。
7. 用第三个用户验证忙线和非参与者访问；关闭一端窗口验证另一端结束；拒绝麦克风后重试。

JWT 保存在 sessionStorage，普通独立浏览器会话不共享身份。复制标签页可能继承初始 sessionStorage，因此验收使用不同浏览器或隐私会话。同用户第二设备连接将替换旧信令连接。

## 开发与测试

本机直接运行后端需要 JDK 17+ 和 Maven 3.9：启动 MySQL、Redis、LiveKit，加载 `.env.stage2` 的环境变量，设置 `DB_URL`、`REDIS_HOST`、`LIVEKIT_URL` 指向本机，然后 `mvn -f backend/pom.xml spring-boot:run`。此模式还需将 LiveKit Webhook 地址设为 `http://host.docker.internal:8080/api/livekit/webhook`，默认 Compose 配置用于容器内后端。

```powershell
npm.cmd run build
npm.cmd test
mvn -f backend/pom.xml verify
```

Java 测试使用 H2 MySQL 模式验证数据库事务和 HTTP 身份边界，Redis/LiveKit 外部服务使用 mock；不能替代真实 MySQL/Redis/LiveKit 的联调。详见 `docs/STAGE2-VALIDATION.md`。

## 当前边界与后续阶段

仅支持单个 Java 实例，尚未实现完整超时、拒接、取消、多设备、多实例状态补偿和通话记录 UI。来电无人接听时需主动挂断；断开信令或重启后端会释放占用。媒体房间清理失败会重试；第三阶段应加入持久化任务、Webhook 乱序处理与完整恢复策略。注册登录仅本地测试使用，公网部署需配合限流和网关保护。

排查：信令失败检查 Origin、Ticket 有效期及 Redis；长期连接中检查 LiveKit Webhook 是否能访问 backend；无声音检查权限、启用声音按钮及 UDP；数据库改密码后旧 volume 不会自动更新密码。不要输出或提交本地密钥。

第一阶段说明保留在 [docs/STAGE1.md](docs/STAGE1.md)，临时 Node Token 服务仅供独立调试。官方 JVM SDK 参考：https://github.com/livekit/server-sdk-kotlin 。

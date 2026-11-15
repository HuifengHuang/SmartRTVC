# 第二阶段验证记录

验证日期：2026-11-14。

## 已执行

- `npm.cmd ci`：依赖安装成功。本机 Node 22.21 低于 package.json 声明的 22.22，产生 engine warning；正式开发请升级至 >=22.22。
- `npm.cmd run build`：Vue TypeScript 检查、Vite 生产构建及保留的 Node 工具类型检查通过。LiveKit bundle 超过 500 KB，Vite 有体积提示，不影响构建。
- `npm.cmd test`：18 项通过（音频生命周期与 Java 凭证复用 9 项、临时 Token 服务 9 项）。
- Maven verify：编译、9 项测试和 Spring Boot 可执行 JAR 打包通过。Java 测试：9 项通过，使用 Spring Boot 上下文、MockMvc、真实 H2 数据库事务；Redis 和 LiveKit 网络服务为 mock。
- Compose 合并配置：`docker compose --env-file .env.stage2 -f deploy/compose.yaml -f deploy/compose.stage2.yaml config --quiet` 通过。
- `git diff --check`：通过。

Java 覆盖：注册登录和错误密码、未认证/非法 JWT、注销撤销记录；一次性 Ticket、握手拒绝与可信身份；信令推送、重复连接；自呼叫、离线和不存在用户、忙线；非被叫接听、非参与者获取 Token/挂断；重复接听和挂断；Webhook 驱动 CONNECTING/ACTIVE；并发呼叫只有一个事务成功；LiveKit Token identity、房间、权限及无效 Webhook 签名。

本机默认 Java 8，Maven 不在 PATH。验证使用项目 `.tools` 中下载的 Maven 3.9.9 和已安装的 JetBrains Java 25，编译目标 `--release 17`。部署 Dockerfile 使用 Java 17；尚未实际执行 Java 17 容器构建。

## 未验证

Docker CLI 可用，但 Docker daemon 未运行（docker_engine 管道不存在）。因此未运行真实 MySQL/Redis/LiveKit 联调、Docker 镜像构建、浏览器 WebSocket 真实握手和双向麦克风音频测试。H2 测试不能代替 MySQL 的真实锁行为验证。新增 Vue 登录呼叫界面通过编译，尚未做浏览器交互测试。

人工完整验收步骤见 README「双用户验收」。启动 Docker Desktop 后，先执行 README 的 Compose up 命令，检查后端和 LiveKit 日志，再用独立浏览器会话完成注册→登录→邀请→接听→ACTIVE→静音/恢复→挂断→再次呼叫。还需测试拒绝麦克风、第三用户忙线、关闭窗口、重复设备登录和重启后端释放占用。

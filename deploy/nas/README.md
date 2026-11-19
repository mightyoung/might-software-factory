# NAS 后端测试部署

此方案从所提供的源码构建，并非已发布或已验证的镜像。准备配置时本地检出的提交为 d1963e52c896c16e76c6e466a480d8d76b4f132d；使用其他源码会构建其他版本，未提交的改动也会进入构建。本方案不是生产发布。

## 启动前

- 需要 Docker Engine、Compose，能下载 Eclipse Temurin 基础镜像和 Maven 依赖的网络，以及完整项目源码和 Maven Wrapper；基础镜像须支持 NAS 的处理器架构。
- 使用专用测试 PostgreSQL，预先按源码版本准备数据库结构、迁移、Flowable/Session/Modulith 表、流程定义、运行权限及测试账号。此方案不创建或迁移数据库；应用运行会写入指定数据库。历史迁移不完整时，不要用 repair、baseline 或 out-of-order 绕过。
- 不要把 NAS 管理员密码用作数据库密码。确认测试目标、权限、端口及现有服务后启动。
- Session 与 CSRF Cookie 要求 Secure。浏览器联调须通过可信证书提供同源 HTTPS，前端及 /api 使用同一来源，/api 代理到 NAS 的 127.0.0.1:18080。直接通过 HTTP IP 访问不能证明登录链路。默认不信任外部转发头。

## 构建和启动

在仓库根目录，将 deploy/nas/env.nas.example 复制为 deploy/nas/.env.nas，填写数据库 JDBC 地址、专用运行账号和密码。保护该文件，不要提交或分享。默认仅监听 NAS 本机；如需从可信内网其他主机访问，请明确设置 BACKEND_BIND_ADDRESS 并限制网络访问，不要暴露公网。

```sh
docker compose --env-file deploy/nas/.env.nas -f deploy/nas/compose.yaml build
docker compose --env-file deploy/nas/.env.nas -f deploy/nas/compose.yaml up -d
docker compose --env-file deploy/nas/.env.nas -f deploy/nas/compose.yaml logs --tail=100 backend
```

第一步会在本机生成 might-software-factory-backend:local 镜像。构建会下载依赖并跳过测试；成功构建不证明数据库或浏览器联调成功。数据库凭据以容器环境变量传入，具有 Docker 管理权限的用户可以查看；不要公开渲染后的配置或含凭据的诊断信息。

启动后检查日志及 GET /api/v1/auth/csrf，再经 HTTPS 测试数据库登录和获授权的业务流程。HTTP 响应仅证明接口存活，不证明数据库和业务就绪。停止此服务：

```sh
docker compose --env-file deploy/nas/.env.nas -f deploy/nas/compose.yaml stop backend
```

此配置不管理数据库卷。前端需要与该后端版本的 API 契约匹配，VITE_API_MODE=http；完整多角色流程和生产证据上传扫描不包含在本部署证明中。

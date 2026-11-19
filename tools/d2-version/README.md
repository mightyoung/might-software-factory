# AU-07 借用申请字段发布

本次以“使用地点”为 V2 新增字段。每张借用单沿用创建时绑定的定义版本。V2 新建与发放前修订都须填写使用地点，最长 200 个字符；V1 旧单不需要补填。每次修订单独保存当时的地点与明细。当前用户办理资格仍由各命令逐次校验。

## 发布与兼容

先应用 Flyway V35，再为运行账号补齐 tool_loan.usage_location 的列级 UPDATE 权限。隔离验收库使用[配套 SQL](../../backend/src/main/resources/db/operator/d2-version-runtime-grants.sql)。应用通过具备新建权限的会话读取 `GET /api/v1/tooling/loan-applications/definition`，以返回版本构建新申请。

`software-factory.tooling.loan-definition-version` 控制新申请，支持 1、2，默认 2。分步发布时，先让所有实例在版本 1 配置下运行支持双版本的新程序和前端，再统一切换到 2。生产切换期间应让新建请求只进入同一发布版本的实例。已存在的单据按自身版本办理。复跑固定提交 V1 新单的历史验收脚本时，使用发布版本 1 的隔离环境。

## 复现

准备从 AU-06 场景复制的独立 PostgreSQL 数据库 msf_d2_version_test，保留合成租户、双方角色和 Flowable 部署。API 与 UI 使用回环 HTTPS。Python 需要 requests 和 psycopg2，浏览器步骤使用项目安装的 Playwright 和 Chrome。

提供 PGHOST=127.0.0.1、PGPORT、PGUSER、PGPASSWORD、PGDATABASE、D2_VERSION_ACK=isolated:msf_d2_version_test、D2_VERSION_ORIGIN、D2_VERSION_CA、D2_VERSION_UI_ORIGIN、D2_VERSION_EVIDENCE，以及 D2_PASSWORD_READER 和 D2_PASSWORD_KEEPER。D2_VERSION_EVIDENCE 指向本轮私有证据目录。

1. 在 V34 运行旧版后端，执行 `python3 tools/d2-version/acceptance.py prepare`。通过真实 API 新建两件工装和一张 V1 待发放申请，保存首次请求及幂等键。
2. 应用 V35 和运行账号授权，切换到 V2 后端及连接它的 HTTP 模式前端。执行 `node tools/d2-version/browser.mjs`，在浏览器中修订旧单明细，并新建填写使用地点的 V2 申请。
3. 执行 `python3 tools/d2-version/acceptance.py verify`。验证旧请求重放、过期定义拒绝、旧单当前工装资格及 V2 地点修订，两张单均通过发放、归还、接收直至结清。检查历史明细、地点、回执与占用。

实测环境为 PostgreSQL 18.6。旧程序提交的 V1 申请在 V34→V35 后成功修订、结清；以新程序从发布配置 1 切至 2 再次执行上述三阶段后，V1/V2 均得到五条按版本排列的创建、修订、发放、归还、接收回执，逐件占用均已释放。V2 地点修改前后的值分别保存在对应修订中。

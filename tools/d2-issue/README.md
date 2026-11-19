# 整单发放验收

目标为专用 PostgreSQL 18 数据库 `msf_d2_issue_test` 和同源 HTTPS 前后端。数据库从 D2 合成测试环境复制，应用 Flyway V30 和既有 D0、D1、D2 最小运行权限后，运行 `seed.sql`。按 `tools/d2/DeployIssueProcess.java` 部署并固定工装流程定义，再启动当前候选后端。

`concurrency.py` 使用真实 Chrome 登录和 HTTPS 命令，使用独立 PostgreSQL 连接控制并观察交错。准备 Python `psycopg2`、前端 Playwright 依赖和 Chrome，通过私有环境提供下列变量。

- `PGHOST=127.0.0.1`、`PGPORT`、`PGDATABASE=msf_d2_issue_test`、`PGUSER`、`PGPASSWORD`。操作账号需能观察阻塞链和插入合成测试工装。
- `D2_ISSUE_ORIGIN=https://127.0.0.1:<port>`。
- `D2_ISSUE_ACK=isolated-issuance-acceptance:msf_d2_issue_test`。
- `D2_PASSWORD_READER`、`D2_PASSWORD_KEEPER`，对应 `d1seed` 测试租户的 `d1seed-reader`、`d1seed-keeper`。
- `D2_ISSUE_EVIDENCE_DIR`，存放本次验证日志和 JSON 的私有目录。

运行 `python3 tools/d2-issue/concurrency.py`。脚本为每次运行创建三件独立合成工装，先核对非保管人、自办和旧版本拒绝后没有发放副作用。随后对同件工装的两张单发起实际发放请求。数据库观察者确认两事务都经阻塞链等待目标工装锁，再释放锁，检查一个成功、另一个冲突。继续验证原键重放、同键异参，以及三件中一件已占用导致整单回滚。

最后直接查询数据库，核对占用、成功审计、命令回执、流程绑定和真实借用人归还任务。输出 `concurrency-summary.json`。每次运行保留带独立标记的合成数据。也可将输出的 `winner`、`loser`、`triple` 传给 `psql -X -v winner=... -v loser=... -v triple=... -f tools/d2-issue/verify.sql`，独立复核持久结果。

## 流程推进后的数据库失败回滚

运行 `python3 tools/d2-issue/late-failure.py`，验证真实 HTTPS 发放在流程推进后遇到数据库失败时，流程和业务数据一起回滚。脚本使用 Chrome 登录，创建独立标记的借用申请，仅对该申请安装临时数据库触发器，在写入 `ISSUED` 主记录时产生失败。随后核对申请仍为原版本，流程运行与历史记录、占用、修订、发放审计和回执均无新增。清除触发器后复用原幂等键，发放应成功。触发器和函数使用每次运行生成的独立名称，并在退出时清理。

除上述 HTTPS 来源、两名测试账号密码及 ACK 外，需要提供以下环境变量。

- `D2_ISSUE_DATABASE=msf_d2_issue_test`。
- `D2_ISSUE_PG_CONNECTION_FILE`，私有 JSON 文件路径，包含 psycopg2 的 `host`、`port`、`dbname`、`user`、`password`。文件不得向组或其他用户开放权限。脚本仅接受 `host=127.0.0.1` 与专用数据库名称。测试操作账号需能创建并删除临时触发器和函数，以及读取流程和业务表。
- `D2_ISSUE_SCOPE_ID`，测试租户内可见的有效保管范围 UUID。
- `D2_ISSUE_TOOL_IDS`，JSON 数组，包含该范围内三件不同的合成测试工装 UUID。第一件须处于正常、未占用状态，脚本会使用该件创建申请并完成发放。

脚本将去敏结果 JSON 写到标准输出，可重定向到私有证据目录。成功结果包含 `failedStatus=503`、`retryStatus=200`、`rollbackProof=PASS` 和 `sameKeyRetry=PASS`。运行会保留带独立标记的合成业务数据供复核。

# 工装遗失结案验收

使用专用 PostgreSQL 18 数据库 `msf_d2_loss_test`。从校验和与最终源码一致的 V31 合成测试副本创建数据库，通过真实 Flyway 应用 V32，再应用既有运行权限和 `db/operator/d2-loss-runtime-grants.sql`。运行本目录 `seed.sql`，固定当前工装流程定义并启动同源 HTTPS 前后端。

准备 Python `psycopg2`、前端 Playwright 依赖和 Chrome，通过私有环境提供下列变量。

- `PGHOST=127.0.0.1`、`PGPORT`、`PGDATABASE=msf_d2_loss_test`、`PGUSER`、`PGPASSWORD`。操作账号需能创建合成工装及观察阻塞链。
- `D2_LOSS_ORIGIN=https://127.0.0.1:<port>`。
- `D2_LOSS_ACK=isolated-loss-acceptance:msf_d2_loss_test`。
- `D2_PASSWORD_READER`、`D2_PASSWORD_KEEPER`，对应 `d1seed` 租户两名合成身份。
- `D2_LOSS_EVIDENCE_DIR`，存放本次日志和 JSON 的私有目录。

运行 `python3 tools/d2-loss/concurrency.py`。每次执行准备四件独立正常工装，前三件用于一张借用单，第四件用于无归还请求的单件直接遗失结案。实际 HTTPS 命令先对第一件记录遗失结论并核实仅释放该件占用，再对第二件创建归还请求。

遗失结案与正常接收请求在同一个真实 PostgreSQL 借用单行锁前等待，通过递归 `pg_blocking_pids` 观察两个事务同时阻塞后释放。必须恰有一个成功、一个冲突。数据库核验该件只产生一种结清事实，待接收请求被接收或被遗失事实精确取代。最后一件在申请归还后记录遗失，整单结清。

脚本对原 ISSUE、RETURN、CLOSE_LOST 键执行历史重放，并检查原版本只包含当时已有事实。越权、错误定义、过期版本、空结论、同键异参和遗失工具再次申请均应被拒绝。最终直接核实损失事实、占用、修订、成功审计、幂等回执，以及唯一流程绑定的实际引擎结束状态。另一个单件借用单须从借用人申请归还任务直接完成遗失结案。

验收保留带独立运行标记的合成记录，输出 `summary.json`。待接收请求的 `superseded_at` 和 `superseded_by_command_id` 必须精确指向对应遗失事实，`received_at`、接收结论和接收命令保持空值。

## 流程推进后的失败回滚

运行 `python3 tools/d2-loss/late-loss.py`。提供上述 `D2_LOSS_ACK`、`D2_LOSS_ORIGIN`、两名合成身份密码，以及 `D2_LOSS_PG_CONNECTION_FILE`。该文件权限必须为 `0600`，JSON 包含 `host`、`port`、`dbname`、`user`、`password`，目标限制为 `127.0.0.1` 上的 `msf_d2_loss_test`。测试操作账号需能在此专用库创建和清理带唯一名称的触发器及函数。

默认验证没有待接收请求的最后一件直接遗失结清。设置 `D2_LOSS_WITH_REQUEST=true` 验证已有待接收请求的分支。脚本使用真实 API 创建独立工装和借用单，在目标单据结清写入时注入故障。核对遗失事实、请求取代、占用、工具状态、修订、回执、审计和实际引擎任务均恢复，再使用原键成功提交。脚本在 `finally` 清理本次注入，保留具名合成业务记录。

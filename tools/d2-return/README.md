# 分批归还与接收验收

使用专用 PostgreSQL 18 数据库 `msf_d2_return_test`。从校验和与最终源码一致的 V30 合成测试副本创建数据库，真实 Flyway 应用 V31，再依次应用既有 D0、D1、D2 运行权限和 `db/operator/d2-return-runtime-grants.sql`。运行本目录 `seed.sql`，按当前源码部署并固定工装流程定义，启动同源 HTTPS 前后端。

准备 Python `psycopg2`、前端 Playwright 依赖和 Chrome，通过私有环境提供下列变量。

- `PGHOST=127.0.0.1`、`PGPORT`、`PGDATABASE=msf_d2_return_test`、`PGUSER`、`PGPASSWORD`。数据库操作账号需能创建合成工装和观察阻塞链。
- `D2_RETURN_ORIGIN=https://127.0.0.1:<port>`。
- `D2_RETURN_ACK=isolated-return-acceptance:msf_d2_return_test`。
- `D2_PASSWORD_READER`、`D2_PASSWORD_KEEPER`，对应 `d1seed` 测试租户的两名合成身份。
- `D2_RETURN_EVIDENCE_DIR`，保存本次日志和 JSON 的私有目录。

运行 `python3 tools/d2-return/concurrency.py`。每次运行创建独立三件正常工装，借用人通过真实 HTTPS 新建申请，保管人发放。两个同版本归还请求和两个同版本接收请求各自在数据库行锁屏障前等待，通过 `pg_blocking_pids` 的递归阻塞链确认真实重叠后释放。每组必须得到一个成功和一个版本冲突。

在待接收任务存在期间，脚本再为不同工装创建归还申请。每次申请只记录事实并保留占用。接收部分明细后保留待接收任务，最后以正常和损坏两种结论接收剩余件并结清。脚本检查历史原键 RETURN、ISSUE、RECEIVE 重放只显示原版本当时已有的事实。

数据库断言核对唯一流程实例、任务位置、未结清占用、损坏工装状态、七次修订及其成功审计和幂等回执。结束时流程已完成，工装占用全部释放。操作人无权、陈旧版本及损坏工装再次申请的请求必须被拒绝。脚本保留带独立运行标记的合成记录，并输出 `summary.json`。

## 接收推进后的事务失败

运行 `python3 tools/d2-return/late-receive.py`。提供上述 `D2_RETURN_ACK`、`D2_RETURN_ORIGIN`、两名合成身份密码，以及 `D2_RETURN_PG_CONNECTION_FILE`。该文件权限必须为 `0600`，JSON 包含 `host`、`port`、`dbname`、`user`、`password`，目标限制为 `127.0.0.1` 上的 `msf_d2_return_test`。测试操作账号需能在此专用库创建和清理带唯一名称的临时触发器及函数。

脚本通过真实浏览器 API 创建独立工装与借用单，发放并申请归还，在指定单据结清写入时注入失败。核对损坏状态、占用、归还明细、回执、审计及原接收任务均恢复，再使用原键成功接收。脚本在 `finally` 清理本次注入，保留独立合成业务记录。

## 真实 Chrome 分批归还与接收页面

`node tools/d2/return-browser.mjs` 从实际页面完成三件申请、整单发放、两批申请归还和接收。第一批正常接收一件，第二批正常接收一件并按损坏接收另一件，填写损坏说明。页面核对每步剩余、待接收、已接收数量和最终结清状态，再打开新申请表单确认正常件可选、损坏件不在候选中。

为该脚本准备当前合成借用人有权申请、合成保管人有权发放和接收的同一保管范围，以及三件独立正常工装。工装编码依次包含 `D2-UI-RETURN-01`、`D2-UI-RETURN-02`、`D2-UI-RETURN-03`，与并发脚本使用不同工装。

通过私有环境提供下列变量，并从仓库根目录运行。

- `D2_ORIGIN=https://127.0.0.1:15448`、`D2_DATABASE=msf_d2_return_test`。
- `D2_ACK=isolated-return-acceptance:msf_d2_return_test`。
- `D2_PASSWORD_READER`、`D2_PASSWORD_KEEPER`，使用上述合成身份密码。
- `D2_RETURN_SCOPE_ID`，当前保管范围 UUID。
- `D2_RETURN_TOOL_IDS`，按顺序排列的三件工装 UUID JSON 数组。
- `D2_EVIDENCE_DIR`，以 `/private/tmp/` 开头的专用证据目录。

首次归还申请由代理实际向后端提交并收到成功，再向页面返回合成 503，验证结果未知恢复。重新打开该申请详情后，人工操作按钮重试原载荷和原幂等键，服务端应返回同一版本。脚本将每次请求的路径、键、载荷、HTTP 状态和结果写入私有 `attempts.json`，再逐步保存页面截图及 `summary.json`。真实失败使脚本停止，应先核实提交结果。

已有完整命令证据且需要在最终候选上只读复验时，额外设置 `D2_VERIFY_SETTLED_LOAN_ID` 为已结清单据 UUID，`D2_PRIOR_ATTEMPTS` 为其私有 `attempts.json` 的绝对路径。此模式读取原单，验证最终状态、损坏结论、原请求恢复证据和候选选项，并保存新的只读页面证据。

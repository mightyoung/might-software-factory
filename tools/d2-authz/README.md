# AU-08 旧版本借用单的当前授权

`acceptance.py` 使用专用回环 PostgreSQL 数据库 `msf_au08_test` 和实际 HTTPS API。准备独立的合成租户 `d1seed`、账号 `d1seed-reader`/`d1seed-keeper`、相应角色和保管资格、已部署并固定版本的工装流程，以及当前 V35 数据库结构与运行账号授权。操作账号须能观察数据库阻塞关系，并在该专用库维护合成账号授权。每次准备创建独立工装和借用单。

通过环境提供 `PGHOST=127.0.0.1`、`PGPORT`、`PGDATABASE=msf_au08_test`、`PGUSER`、`PGPASSWORD`、`AU08_ACK=isolated:msf_au08_test`、`AU08_ORIGIN=https://127.0.0.1:<port>`、`AU08_CA`、`AU08_EVIDENCE`、`D2_PASSWORD_READER` 和 `D2_PASSWORD_KEEPER`。Python 需要 requests 和 psycopg2。CA 文件须信任本次 HTTPS 证书。

1. 以 `software-factory.tooling.loan-definition-version=1` 启动后端，运行 `python3 tools/d2-authz/acceptance.py prepare`，通过 API 创建 V1 待发放单。
2. 保留该数据库，切换新建发布配置为 2，运行 `python3 tools/d2-authz/acceptance.py verify`。
3. 核对 `AU08_EVIDENCE/summary.json`，保留数据库与后端产物哈希供复验。

验证逐次移除发放、归还、接收所需权限，逐次停用对应成员，并核对旧请求被拒绝。每次拒绝前后比较借用单、版本历史、成功回执、借用明细、归还明细、当前流程任务和成功审计数量，确保没有部分提交。权限与成员状态在 `finally` 恢复。

发放撤权还持有生产同源的租户事务锁，启动实际 HTTP 请求，通过 `pg_blocking_pids` 确认该请求等待撤权事务，随后提交撤权并观察拒绝。可用性用例通过另一张真实 V2 借用单占用旧单中的第二件工装，核对旧单整单发放失败；竞争单正常结清后，旧单可发放。旧单随后以一件正常、一件损坏完成归还接收，并检查损坏接收结果、四条业务修订及占用全部释放。

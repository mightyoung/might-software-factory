# 借用单部门与保管范围只读验收

对应 AU-03、AU-04、AU-05 的只读范围部分。测试库固定为 `msf_d2_read_scope_test`，从上一轮 V32 合成验收库创建独立副本，再通过 Flyway 应用 V33。V33 仅登记两项独立只读权限，既有运行账号无需新增表权限。

权限 `tool_loan_department.read` 按现有 RBAC 授予的组织集合匹配单据的申请时部门。权限 `tool_loan_custody.read` 在授权组织范围之外，还要求当前有效的原保管范围关系。普通 `tool_loan.read` 仍只读取本人单据。页面新增“部门借用单”和“保管范围借用单”两个入口，页面仅提供列表和只读详情。办理仍调用原业务命令并重新校验其动作权限。

准备同源 HTTPS 前后端，在私有环境提供 PostgreSQL 操作账号的 `PGHOST`、`PGPORT`、`PGDATABASE`、`PGUSER`、`PGPASSWORD`，以及下列浏览器变量。

- `D2_SCOPE_ACK=isolated-read-scope:msf_d2_read_scope_test`
- `D2_SCOPE_ORIGIN=https://127.0.0.1:<port>`
- `D2_SCOPE_EVIDENCE=/private/tmp/<private-directory>`
- `D2_PASSWORD_READER`、`D2_PASSWORD_KEEPER`、`D2_PASSWORD_NONKEEPER`、`D2_PASSWORD_OUTSIDE`

在仓库根目录运行 `python3 tools/d2-read-scope/acceptance.py`。依赖 `psycopg2`、前端 Playwright 包和 Chrome。脚本只允许专用数据库，使用 `d1seed` 合成身份并保留每轮独立单据和记录。

脚本通过真实业务 API 申请并发放工装，然后在专用库变更合成借用人的部门关系。重新登录后核对本人仍可读旧单、原部门授权者能读、新部门授权者不可读原单。它也验证没有实际保管关系的授权读者被过滤、原保管人撤除关系后失去读取范围、独立部门读权限撤销后导航和 API 均拒绝、跨租户 ID 返回 404，以及读权限不能触发发放或归还命令。

截图 `department-detail.png` 使用只有范围查看授权的合成成员通过真实页面打开详情，并核对没有新建或修订动作。`summary.json` 仅在所有断言通过后生成。调岗后的旧单部门、原保管范围和业务历史应保持原值。

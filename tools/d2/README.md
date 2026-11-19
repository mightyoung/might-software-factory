# 工装借用申请验收

首个 D2 切片覆盖一张申请选择多件工装、本人修订和撤销。申请部门来自借用人的有效部门关系，保管范围单独校验。提交申请后工装仍可被其他人申请。整单发放、占用、归还与接收在后续切片实现。

1. 准备专用 PostgreSQL 18 数据库 `msf_d2_loans_test`，从含 D1 合成身份的测试库复制数据。应用 Flyway V29、D0/D1 运行账号授权和 `db/operator/d2-runtime-grants.sql`。使用测试操作账号运行 `psql -X -f tools/d2/seed.sql`，为两名合成借用人添加申请权限并准备两个范围的工装。
2. 启动连接该库的真实后端。使用 `VITE_API_MODE=http` 构建前端，配置本机 HTTPS 预览和指向该后端的同源 API 代理。
3. 设置 `D2_ORIGIN=https://127.0.0.1:<port>`、`D2_DATABASE=msf_d2_loans_test`、`D2_ACK=isolated-loan-acceptance:msf_d2_loans_test`。通过私有环境提供 `D2_PASSWORD_READER`、`D2_PASSWORD_KEEPER` 和范围 B 工装 `D2-D1-SCOPE-B-1` 的 UUID `D2_OUTSIDE_TOOL_ID`。
4. 从仓库根目录运行 `node tools/d2/browser.mjs`，用真实 Chrome 操作。将输出中的三个值传给同一测试库上的 `psql -X -v marker=... -v loan=... -v second=... -f tools/d2/verify.sql`。每次运行创建独立记录，SQL 核对两名申请人、明细版本、命令回执和审计记录。

接口契约见 [tool-loans.yaml](../../contracts/openapi/tool-loans.yaml)。

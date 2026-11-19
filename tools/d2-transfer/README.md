# AU-06 工装保管范围调整

正常工装档案的编辑页从当前用户有权管理的有效范围中选择目标。服务端在事务内锁定工装行、验证当前保管关系，使用目标范围推导责任组织。仍有未结清的发放明细时返回 HTTP 409 和 TOOL_IN_USE。仅申请归还时仍占用，验收结清后才可调整。普通字段编辑可以沿用原范围。

## 独立数据库验收

使用专库 msf_d2_transfer_test，基于 AU-04/05 的 V33 合成数据库复制。通过 Flyway 应用 V34，再参考下方 SQL 文件中的 psql 命令，为既有最小权限运行角色补充对 tool.custody_scope_id 和 tool.organization_id 的列级 UPDATE。此脚本沿用项目隔离验收库的名称检查。[运行角色授权 SQL](../../backend/src/main/resources/db/operator/d2-transfer-runtime-grants.sql)

启动绑定本库的 HTTPS 后端，并沿用合成租户的 Flowable 部署和定义固定配置。通过私有环境提供以下变量。

- PGHOST=127.0.0.1、PGPORT、PGDATABASE、PGUSER、PGPASSWORD，使用有夹具准备、观测锁和验证历史权限的独立测试账号。
- D2_TRANSFER_ACK=isolated:msf_d2_transfer_test、D2_TRANSFER_ORIGIN=https://127.0.0.1:<port>、D2_TRANSFER_CA 指向签发该服务端证书的 PEM 文件或受信任的本机自签名证书。
- D2_PASSWORD_READER 和 D2_PASSWORD_KEEPER 为 d1seed 合成身份的登录密码。

在仓库根运行 `python3 tools/d2-transfer/acceptance.py`，Python 环境需要 requests 和 psycopg2。每轮创建独立目标范围和两件新工装；夹具只修改专库中指定的合成角色和成员关系。

| 在工装行锁上观察到的请求顺序 | 已验证结果 |
| --- | --- |
| 先发放，再调拨 | 发放成功，调拨返回 TOOL_IN_USE。归还待接收期间继续拒绝调拨，接收后可调拨至 B 组织，已结清旧单保留 A 范围 |
| 先调拨，再发放旧范围单据 | 调拨成功，旧申请发放返回 TOOL_UNAVAILABLE。旧单保持待发放，无占用，工装位于 B 组织 |

脚本通过两个独立 HTTPS 会话发送竞争命令。只有观察到两次请求都进入实际 PostgreSQL 锁等待链后才放行，并核对拒绝操作的版本、占用和台账修订数。另验证缺少目标保管关系的用户无法提交目标范围。

完整场景通过后，以 VITE_API_MODE=http 构建前端并通过同源 HTTPS 转发 API。设置 D2_TRANSFER_UI_ORIGIN 后运行 `node tools/d2-transfer/editor.mjs`。脚本经保管人登录创建独立工装，再从标准编辑表单检索并选择跨组织目标，保存后读取服务端范围、责任组织和版本。截图和结果保存在 /private/tmp/msf-transfer/editor/。

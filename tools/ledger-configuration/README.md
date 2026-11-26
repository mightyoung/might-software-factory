# 台账配置版本浏览器验收

部署本次后端、V36 迁移与对应运行角色的最小授权，前端以 `VITE_API_MODE=http` 构建并通过同源 HTTPS 提供服务。验收使用专用的 `msf_config_test` 数据库。合成租户 d1seed 启用 platform，账号 d1seed-keeper 具备生效的台账配置管理和发布资格，并已配置范围。将该测试账号的密码作为环境变量 `CONFIG_UI_PASSWORD` 提供。

```sh
CONFIG_UI_DATABASE=msf_config_test CONFIG_UI_ORIGIN=https://127.0.0.1:15640 node tools/ledger-configuration/browser.mjs
```

脚本通过实际 Chrome 登录，每次创建一个独立标识的验收台账，保存默认流程，在第二版插入并重命名办理节点、拖动位置；重新打开核对节点和坐标，再比较、恢复第一版并显式发布。它会在测试库保留该记录。没有修改数据库或权限的脚本步骤。

## 本轮本地复验（2026-11-20）

- 在实际 HTTPS 上，无配置权限的成员读取目录返回 403。撤回发布权限后，持原会话的设计者仍可保存下一版草稿，同一会话发布返回 403，已发布指针保持原值。
- 分别演练了受限运行账号的数据读写和 Flyway 升级。后者从历史为 V34 的合成备份另建数据库，在迁移角色下依次安装 V35、V36，Flyway 全量校验通过。应用运行账号的最小授权需单独应用。

## 已发布台账的业务记录

`records-browser.mjs` 对接独立的 `msf_record_runtime_test` 数据库、HTTPS 服务及启用 HTTP 模式的前端。使用测试账号的环境变量 `RECORD_PASSWORD_KEEPER`、`RECORD_PASSWORD_READER`，以及 `RECORD_UI_ORIGIN` 和 `RECORD_UI_DATABASE`。测试管理员需具备配置维护、发布及角色管理资格；业务角色须有可继承的有效组织范围。

```sh
RECORD_UI_DATABASE=msf_record_runtime_test RECORD_UI_ORIGIN=https://127.0.0.1:15647 node tools/ledger-configuration/records-browser.mjs
```

脚本通过实际接口发布随机标识的台账，核对未授权时目录不可见，授予并在结束时收回本次业务动作。Chrome 操作草稿新增、编辑和删除；发布 v2 后编辑 v1 记录，撤权后以原会话验证写入被拒绝、数量未变。另通过配置页复制源台账的 v1 为独立台账，分别保存、发布和授权，核对独立版本历史、字段名称和记录。复制出的台账在单独授权前不可访问，源台账的记录标识不能从副本中读取，源台账的编辑资格不会赋予副本编辑资格。源记录删除后，副本记录仍可查询。数据库需要安装 V37，并对运行角色应用配套授权。

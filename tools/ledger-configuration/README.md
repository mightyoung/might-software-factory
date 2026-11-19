# 台账配置版本浏览器验收

部署本次后端、V36 迁移与对应运行角色的最小授权，前端以 `VITE_API_MODE=http` 构建并通过同源 HTTPS 提供服务。验收使用专用的 `msf_config_test` 数据库。合成租户 d1seed 启用 platform，账号 d1seed-keeper 具备生效的台账配置管理和发布资格，并已配置范围。将该测试账号的密码作为环境变量 `CONFIG_UI_PASSWORD` 提供。

```sh
CONFIG_UI_DATABASE=msf_config_test CONFIG_UI_ORIGIN=https://127.0.0.1:15640 node tools/ledger-configuration/browser.mjs
```

脚本通过实际 Chrome 登录，每次创建一个独立标识的验收台账，保存两个版本、比较、恢复副本并显式发布，刷新后核对版本历史。它会在测试库保留该记录。没有修改数据库或权限的脚本步骤。

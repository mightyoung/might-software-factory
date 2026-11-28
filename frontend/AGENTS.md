# MSF 前端工程约束

## 使用当前契约

- 以当前锁文件及代码为准。通过组件的 Astryx 子路径导入，不引入整包；不要手改供应商私有 CSS 变量。现有品牌主题与 Office 的主题桥分别验证。未来的 CSS 分层和 Astryx 编译主题必须先实测样式优先级、双主题及首屏，不能把计划当作现状。
- 应用内导航应保留 `/t/<tenant>/...`、原有查询参数、浏览器历史及深链接，不能越租户。普通台账优先复用 `EntityLedgerPage` 与 `LedgerTable`；新增业务域先按 `tools/templates/new-ledger-module/` 配置，不复制 API 或表单框架。
- HTTP 会话、菜单和权限由服务端决定，不因客户端有同名权限或缓存而放行；敏感草稿、令牌和会话信息不落浏览器持久存储。只在绑定模式、租户与会话身份的边界内处理加载、缓存、退出与失效。
- 新增离开/放弃确认采用项目可访问对话框，覆盖取消、键盘焦点及不发送写请求；不要新增原生 `alert` / `confirm`。已有原生确认在专门迁移时保留语义后替换。
- TanStack Router、Query 和 Table 是渐进迁移目标，不是现有依赖。每项单独适配实际契约并验证包体，不能通过放宽门槛或混入 Office 产物满足合入要求。

## 交付门禁

1. `npm run typecheck`、`npm test`，升级工具另外运行 `node --test tools/upgrade-astryx.test.mjs`。
2. 分别按 `VITE_API_MODE=http`、`VITE_API_MODE=synthetic` 构建，每次接 `npm run check:bundle`。
3. 用对应的 `E2E_PREVIEW=http` 或 `E2E_PREVIEW=synthetic` 跑 `npm run e2e`。HTTP 浏览器用例放入 `e2e/*.e2e.ts`，先验证已登录目标页，再用键盘、明暗主题和 axe 检查，不能在控件不存在时跳过。
4. 不在工作台入口静态导入 Univer，不更改 `office/` 和工作台之间的独立构建、分包或隔离边界。不要提交依赖缓存、生成的预览或测试凭据。

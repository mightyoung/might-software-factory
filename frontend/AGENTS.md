# MSF 前端工程约束

## 使用当前契约

- 以当前锁文件及代码为准。通过组件的 Astryx 子路径导入，不引入整包；不要手改供应商私有 CSS 变量。入口先加载 `src/styles/layers.css`。Astryx 品牌变量改 `src/themes/might/mightTheme.ts`，用固定 CLI 生成主题，再生成兼容性根变量；不要手改产物。旧页面 CSS 先保持未分层，移动前比较优先级和双主题。Office 主题桥独立验证。
- 应用内导航应保留 `/t/<tenant>/...`、原有查询参数、浏览器历史及深链接，不能越租户。普通台账优先复用 `EntityLedgerPage` 与 `LedgerTable`；新增业务域先按 `tools/templates/new-ledger-module/` 配置，不复制 API 或表单框架。
- HTTP 会话、菜单和权限由服务端决定，不因客户端有同名权限或缓存而放行；敏感草稿、令牌和会话信息不落浏览器持久存储。只在绑定模式、租户与会话身份的边界内处理加载、缓存、退出与失效。
- 新增离开/放弃确认采用项目可访问对话框，覆盖取消、键盘焦点及不发送写请求；不要新增原生 `alert` / `confirm`。已有原生确认在专门迁移时保留语义后替换。
- TanStack Router、Query 和 Table 按现有业务契约接线，禁止跨身份持久化或自动重试写操作。性能预算按首屏、实际异步依赖、完整工作台、独立 Office 计量；改版后按真实构建重定基线，不通过隐藏分包或跳过校验满足门禁。

## 交付门禁

1. `npm run typecheck`、`npm test`，升级和性能工具另跑 `node --test tools/upgrade-astryx.test.mjs tools/measure-bundle-graph.test.mjs`。
2. 分别按 `VITE_API_MODE=http`、`VITE_API_MODE=synthetic` 构建，每次接 `npm run check:bundle`。
3. 用对应的 `E2E_PREVIEW=http` 或 `E2E_PREVIEW=synthetic` 跑 `npm run e2e`。HTTP 浏览器用例放入 `e2e/*.e2e.ts`，先验证已登录目标页，再用键盘、明暗主题和 axe 检查，不能在控件不存在时跳过。
4. 不在工作台入口静态导入 Univer，不更改 `office/` 和工作台之间的独立构建、分包或隔离边界。不要提交依赖缓存、生成的预览或测试凭据。

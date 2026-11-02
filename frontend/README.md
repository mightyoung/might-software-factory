# Frontend

电脑浏览器 React + TypeScript + Vite 前端，包含隐患台账、详情、上报、会话状态及部分流程命令的界面与适配器。

## 当前验收边界

本批 B2-F 复用 PR16 的 HTTP 适配器、组织选择、命令面板和契约模拟浏览器测试，并修复 Vitest 误收集工具依赖测试的问题。**尚未完成真实 Java/PostgreSQL 浏览器联调。**

- 默认演示模式为内存会话和合成台账，界面标注“合成演示”，刷新清空；演示密码任意非空，不是真实认证。
- HTTP 模式调用服务端会话、台账、上报及已有命令端点。错误不会回退到合成账号或合成数据。
- HTTP 会话从 `/api/v1/session/bootstrap` 读取身份、应用和权限，菜单按需从 `/api/v1/applications/{id}/menu` 获取；没有静态应用或权限兜底。只映射 `finding.read/create` 到已有 UI 权限，未知权限不授予。菜单仍仅用于呈现，服务端必须逐请求验证。
- Mock E2E 与真实后端验收分开报告。B2-N 的真实数据库验收仍待完成；G2a 须先通过后端导航读取门禁，真实写入验收另需 G1；本批不声明 G2a/G2b 通过。

## 运行和检查

```sh
npm ci --no-audit --no-fund
npm run check:api
npm run typecheck
npm test
npm run build
npm run dev
```

单元测试只发现 `src/**/*.{test,spec}.{ts,tsx}`，保留 Vitest 默认依赖排除并排除 `e2e/`。工具目录嵌套 node_modules 中的第三方测试不会收集。

## 模式和会话

```sh
VITE_API_MODE=synthetic npm run dev
VITE_API_MODE=http npm run dev
```

未配置、空值或 `synthetic` 使用演示模式；`http` 使用 HTTP 适配器；其他值启动失败，避免拼写错误静默进入演示。HTTP 登录需要租户编码、账号和密码。

开发服务器代理 `/api` 到 `BACKEND_URL`，默认 `http://127.0.0.1:8080`。真实 Secure Cookie/CSRF 验收须配置同源 HTTPS；当前 HTTP 开发代理不是这项证据，不关闭 TLS 校验作为替代。

`httpClient` 每次写入从同源 Cookie 读取 CSRF，不缓存令牌或写入 localStorage。错误保留 code、traceId、retryable、fieldErrors、Retry-After 和 currentVersion。会话失效清除本地状态；退出失败也清除受保护界面。

## 台账、上报和命令

HTTP 模式为每次登录身份建立独立 store，避免跨账号复用 schema 缓存。读取使用 schema/query/detail 契约；上报选择授权组织并映射服务端字段。相同内容重试复用幂等键，内容变化才换键；身份、租户、权限、版本和轮次均由后端最终校验。

当前支持初始化、人员确认、安全确认、安全验收与退回整改的 HTTP 命令呈现。缺成员查询契约的派单、缺上传/证据集契约的整改提交及尚无后端动作的退回入口保持禁用并说明原因。演示模式不把按钮伪装成真实命令。

日期按 Asia/Shanghai 展示；没有后端对应枚举的“紧急”档明确拒绝，不静默映射。上报日期筛选、责任人、历史等差异见[契约差异清单](../docs/frontend-contract-gap.md)。

## 契约模拟与浏览器检查

```sh
npm run mock
VITE_API_MODE=http npm run dev
npm run e2e
```

模拟服务只绑定回环地址，默认8080，账号/密码/租户编码均为 `demo`。它模拟 Cookie、CSRF、查询、组织、创建/幂等和有限流程状态，数据在内存中；**它不是 Java 后端**。

Playwright 使用本机已有 Chrome，不下载浏览器；自动启动模拟服务18080和前端15173。该套件仅验证真实浏览器与契约模拟服务，不证明真实授权、Secure Cookie、PG或Flowable事务。执行环境需允许回环端口与Chrome启动；不扩大监听范围或复用未知服务。

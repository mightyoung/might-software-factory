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

未配置、空值或 `synthetic` 使用演示模式；`http` 使用 HTTP 适配器；其他值启动失败，避免拼写错误静默进入演示。模式由 `main.tsx` 一次性解析（`src/mode.tsx`）并经 React context 下发，界面是否标注“演示 / 合成”只看它，不再从各适配器的能力去推断。HTTP 登录需要租户编码、账号和密码。

开发服务器代理 `/api` 到 `BACKEND_URL`，默认 `http://127.0.0.1:8080`。真实 Secure Cookie/CSRF 验收须配置同源 HTTPS；当前 HTTP 开发代理不是这项证据，不关闭 TLS 校验作为替代。

`httpClient` 每次写入从同源 Cookie 读取 CSRF，不缓存令牌或写入 localStorage。错误保留 code、traceId、retryable、fieldErrors、Retry-After 和 currentVersion。会话失效清除本地状态；退出失败也清除受保护界面。

## 台账、上报和命令

HTTP 模式为每次登录身份建立独立 store，避免跨账号复用 schema 缓存。读取使用 schema/query/detail 契约；上报选择授权组织并映射服务端字段。相同内容重试复用幂等键，内容变化才换键；身份、租户、权限、版本和轮次均由后端最终校验。

当前支持初始化、人员确认、安全确认、“不成立”关闭（两个确认阶段）、分派整改（含验收退回后的重新分派）、验收通过与退回整改的 HTTP 命令呈现。分派整改用 `GET /findings/{id}/dispatch-candidates` 按姓名前缀搜索、分页选择责任人，期限按北京时间填写后换算为带 +08:00 的时间；候选只是提示，提交时服务端再校验权限、版本和成员资格。缺上传 / 证据集契约的整改提交及尚无后端动作的两个“退回”入口保持禁用并说明原因。`NOT_ESTABLISHED` 是终态，不再提供任何动作。演示模式不把按钮伪装成真实命令。

日期按 Asia/Shanghai 展示；没有后端对应枚举的“紧急”档明确拒绝，不静默映射。上报日期筛选、责任人、历史等差异见[契约差异清单](../docs/frontend-contract-gap.md)。

## 修改密码

HTTP 模式下头像菜单的“修改密码”可用（演示模式禁用）。对话框按契约在本地校验：新密码至少 15 个字符、不超过 72 UTF-8 字节（约 24 个汉字）、两次一致，密码不去空白、不规范化。成功后服务端使所有旧会话失效，前端清除登录态回到登录页并提示用新密码登录；当前密码错误留在对话框并标在该字段；被限流显示倒计时；结果不确定时提醒“新密码可能已生效”。契约里改密的 401 同时表示“会话失效”和“当前密码不对”，前端靠再读一次会话区分（差异清单 D21）。

## 未提交内容与会话失效

- 上报表单有未提交内容时：刷新 / 关闭标签页由浏览器原生提示；点应用内链接（侧栏、顶栏）先 `confirm`。浏览器的前进 / 后退按钮拦不住（History API 没有可靠的取消办法）。
- 会话被动失效（401）时，未提交的草稿保存在**内存草稿箱**（`src/features/session/draftStash.ts`），同一个人重新登录后恢复并提示。不写 localStorage / sessionStorage；主动退出、改密、换账号登录一律丢弃。刷新页面会丢失（内存）。
- 不做会话到期倒计时：契约没有到期时间字段（D9），前端估算会误导。

## 无障碍与体积检查

- `e2e/a11y.e2e.ts` 用 axe 在真实浏览器里扫登录页、台账、详情、上报表单（含校验错误态）、命令表单、改密对话框的 WCAG 2.0/2.1 A / AA，并检查改密对话框的键盘焦点陷阱与关闭后的焦点归还。自动化只覆盖一部分问题，不能代替读屏软件的人工走查。
- `npm run check:bundle`（CI 在构建后运行）：分别校验工作台与 Office 的构建产物，限额见下方公共组件说明。
- `e2e/finding-perf.e2e.ts`：登录到台账首行的时间、长任务和请求次数的冒烟，**对着零延迟的模拟服务**，只防前端回归，不是“首屏 P95 ≤ 2s”的证据。

## 契约模拟与浏览器检查

```sh
npm run mock
VITE_API_MODE=http npm run dev
npm run e2e
```

模拟服务只绑定回环地址，默认8080，账号/密码/租户编码均为 `demo`。它模拟 Cookie、CSRF、查询、组织、创建/幂等和有限流程状态，数据在内存中；**它不是 Java 后端**。

Playwright 使用本机已有 Chrome，不下载浏览器；自动启动模拟服务18080和前端15173。该套件仅验证真实浏览器与契约模拟服务，不证明真实授权、Secure Cookie、PG或Flowable事务。执行环境需允许回环端口与Chrome启动；不扩大监听范围或复用未知服务。

## 演示模式（合成）细节

- 账号：`demo.safety`、`demo.manager`、`demo.admin`、`demo.viewer`（只读），密码任意非空；账号不存在与密码为空返回同一个错误。状态只在内存，不读写 localStorage/sessionStorage。
- 路由带租户：`/t/<tenantId>/<应用>/<页面>`；无租户前缀的旧地址登录后规范化；路径租户与会话租户不一致显示“无权访问”，不区分租户是否存在。登录返回地址 `?next=` 只接受本站相对路径，拒绝绝对地址、`//host`、反斜杠、控制字符和其他租户。
- 上报表单没有隐患等级、上报人、租户：等级由有权限人员确认，上报时固定“待判定”；上报人和租户只来自服务端会话。校验在 `src/domain/findingDraft.ts`（纯函数）：长度按 Unicode 码点计，位置按契约的 White_Space 集合校验；前端的长度上限（位置 100、描述 500、临时措施 300）是产品假设，严于契约。
- 提交带幂等键：同一份内容重试复用同一个键，内容变化才换新键。发现时间早于当前时间超过 24 小时为补报（HTTP 模式下补报标记由服务端计算，回执暂未返回）。时间一律按 Asia/Shanghai 展示和填写，记录里是带 +08:00 的 ISO 时间。
- 面板打开时主区域 `inert`；有未提交内容时 Esc / 关闭先确认。应用内导航离开时不会拦截未提交草稿，提交时会话失效会丢失草稿。
- 查询：关键词只匹配隐患编号（后端的描述字段不可筛选）；过滤组只有 AND。`src/adapters/entityQueryContract.ts` 把界面查询映射为共享 Entity Query Contract 的请求，字段注册表在 HTTP 模式下取自 `GET /entities/finding/schema`；日期输入只映射到 `DATE` 字段，不把无时区日期擅自转成时间点。
- 契约类型由 `contracts/openapi/*.yaml` 生成（`npm run gen:api`，`npm run check:api` 只比对），生成物不手改；`minimum` / `maxLength` / `pattern` 不进入类型，这类变化仍靠契约评审和后端测试。

## 主题、台账查询和公共 Office 编辑器

登录页、工作台和 Office 对话框支持浅色、深色或跟随系统。浏览器只保存主题偏好。工作台品牌使用 Astryx Text，公共台账的文本查询字段使用 Astryx TextInput，整页和逐行选择使用 Astryx CheckboxInput。部分勾选时表头显示半选状态，键盘空格可切换选择。台账查询、重置和编辑器关闭操作使用 Astryx Button。文本查询可单独清除，清除后焦点回到输入框。其他筛选条件继续保留，按 Enter 或点击查询才会发送请求。

登录后可选择“在线表格”或“在线文档”。两者分别按需加载 Univer Sheets 或 Docs 1.5.2，共用主题和关闭保护。可以在工具栏给工作簿或文档命名，名称会写入 JSON，并用作本机下载文件名。编辑内容保存在当前浏览器会话的内存中，离开前可导出 JSON，下次在相应编辑器中导入。表格沿用原生工作簿 JSON；文字文档文件包含应用格式版本、SDK 版本和原生文档快照。在线文档也可导出 UTF-8 纯文本，供复制和分发操作指引使用；需要保留版式时仍须导出 JSON。服务端文档版本、多人协作及 XLSX/DOCX 转换仍待实施。

`npm ci` 按工作台和编辑器各自的锁文件安装依赖。`npm run dev` 和 `npm run build` 都先构建两个编辑器入口到 `public/office/`。部署整个 `dist/`，包括 `office/index.html`、`office/docs.html` 和共享资源。

`npm run check:bundle` 以 KiB gzip 校验所有生成的 JS 和 CSS。工作台预算为 JS 202、CSS 56。逐行和整页选择接入 Astryx 后，工作台 JS 从 194.7 增至 200.1。Office 预算为 JS 2880、CSS 36，表格和文档共享的文件只计一次。

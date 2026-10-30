# Frontend

首批为电脑浏览器 React + TypeScript + Vite 外壳，示例围绕隐患台账与六个流程节点。导航、字段驱动的查询/列表、详情、分页和当前页选择为本地演示实现。

## 登录与应用导航（合成）

登录、会话、受限导航和路由守卫均为**前端合成实现**，用于验证界面结构和后续接入形状；后端目前没有认证端点契约，**不是真实认证，不提供任何安全保证**。

- 会话端口 `SessionAdapter`（`src/adapters/sessionAdapter.ts`）：`login` / `restore` / `logout` / `loadMenu`。默认实现只在内存里保存状态，刷新页面即退出；不读写 localStorage/sessionStorage，也不联网。账号只认 `demo.safety`、`demo.manager`、`demo.admin`，密码任意非空，账号不存在与密码为空返回同一个错误。
- 登录只取最小 bootstrap（身份、租户、有权应用的轻量列表、权限摘要）；应用菜单在进入该应用时才按需读取，切换应用会取消上一个未完成的请求。
- 路由带租户：`/t/<tenantId>/<应用>/<页面>`。无租户前缀的旧地址登录后自动规范化；路径租户与会话租户不一致显示“无权访问”，不区分租户是否存在。登录返回地址 `?next=` 只接受本站相对路径，拒绝绝对地址、`//host`、反斜杠、控制字符和其他租户。
- 菜单和路由守卫只用于**界面呈现**，不是授权凭据；服务端必须对每个请求重新鉴权（401/403/404 语义见设计文档）。
- 头像菜单提供退出登录；个人资料、个人会话尚未接入，显示为禁用。退出请求失败时仍清除本地登录态。

尚未实现：顶部“工作台”“系统设置”入口、租户切换、空闲 30 分钟/最长 12 小时会话到期的客户端提示（现仅在服务端返回 401 时回到登录页）、CSRF 令牌、台账读取收到 `unauthenticated` 时联动退出、真实 HTTP 会话适配器。

## Local checks

```sh
npm install
npm run typecheck
npm test
npm run build
```

## Backend boundary

隐患台账仍使用明确标注的合成 adapter，没有真实权限、审批、分派、证据上传或数据库持久化；操作按钮保持禁用。

`src/adapters/entityQueryContract.ts` 为共享 `Entity Query Contract` v1.0.0（`contracts/openapi/entity-query.yaml`，来源提交 `3d08108831e4283a38f618944bae30662d13063b`）提供类型化请求映射。该 OpenAPI 文件当前 `paths: {}`，仅发布组件 schema；映射器不发 HTTP 请求。调用方必须提供代码审查发布的实体字段注册 schema 和 UI 字段映射，映射器不会推断或授权字段。当前隐患演示 adapter 尚未调用该映射器，也未取得隐患实体注册字段。

`FindingsLedger` 通过 `FindingLedgerAdapter` 属性注入列表/详情读取端口，默认值为 `mockFindingLedger`。端口接收可选 `AbortSignal`，便于将来由可取消传输实现承接 UI 取消；当前 mock 不联网，现有用例验证取消信号与迟到响应保护。`FindingLedgerAccessError` 的 `unauthenticated` / `forbidden` 是仅供 UI 呈现的语义分类，不映射 HTTP 状态、服务端错误码或认证机制；未知错误显示通用失败状态。后端当前没有真实隐患查询或详情端点契约，尚未实现 HTTP adapter，也未验证真实权限。

映射器使用契约规定的 20/50/100 分页、正整数页码与最大偏移，按已注册运算符生成过滤，并检查显式排序和输出字段。无显式排序/投影时省略对应属性。日期输入只映射到注册为 `DATE` 的字段；不把无时区的日期输入转换成 `DATETIME`。整数查询拒绝超出 JavaScript 安全整数范围的值。金额和小数不设业务小数位数，但仅接受经过 JavaScript 数值转换再序列化后与输入十进制值完全相等的值；否则拒绝，避免静默精度损失。响应类型把 `total` 保持为可选，避免构造占位总数。

此适配器的 fixture 使用合成字段注册，仅验证前端映射行为。测试不读取或解析共享 YAML，也不调用/验证 Java validator；契约来源提交号仅标记来源，不代表跨端一致性验收。内部 `createEntityQueryFilter` helper 只构造基础对象形状，没有 registry 上下文，也不验证字段/运算符/值类型；请求映射入口才按传入的注册 schema 检查 UI 查询。

UI 合成数据的本地过滤与共享契约暂有语义差异：当前“编号 / 描述”关键词会在两个属性间做 OR 查询，而 v1 过滤组只定义 AND；`CONTAINS` 的大小写规则也未在契约中说明。因此这些查询尚不能声称与后端查询等价。接入时需等后端注册字段与查询语义确认后再配置映射，并让后端对每个业务命令重新校验租户、身份、职责、状态、版本、轮次和证据门槛。

字段定义随前端代码审查发布，不提供在线改 schema、任意 SQL 或任意脚本入口。

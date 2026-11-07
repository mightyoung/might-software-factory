# 登录持久安全事件

本批在原密码登录和 JDBC Session 外增加独立记录及响应放行边界。它不扩展身份提供方，不实现邀请/重置投递、共享限流或审计查询接口。

## 事件与归属

身份模块拥有 `AuthenticationEvents` 端口；auditops 通过 jOOQ 实现，模块依赖仍为 auditops → identity。V14 新增全局只追加的 `authentication_event`，不复用必须有租户和账号的 V12 改密事件。未知身份的四项 subject 字段均为 NULL，不使用填充 UUID，也不从登录名、请求租户或已有会话推断本次身份。

事件仅有服务端生成的 attempt UUID、分类时刻、固定策略版本、枚举 outcome，以及提供方实际验证通过后取得的 account/actor/tenant/member UUID。没有登录名、IP、密码/哈希、Cookie、Session ID、请求正文、原始异常或新的关联密钥。不存在客户端事件 ID 输入。

| 观察 | outcome | subject |
|---|---|---|
| 已获准的凭据请求由提供方拒绝，链正常结束且无 Session 故障 | AUTHENTICATION_DENIED | 全 NULL |
| 其他已获准故障，尚无已验证身份 | AUTHENTICATION_UNAVAILABLE | 全 NULL |
| 提供方通过、成功处理完成、整条 Session 链正常结束、状态204且无故障标记 | SESSION_PERSISTED | 四项已验证 UUID |
| 已验证身份之后出现异常、Session 故障标记或其他非正常完成 | SESSION_OUTCOME_UNKNOWN | 四项已验证 UUID |

观察始于来源限流、解析及来源/账号并发预留全部通过之后。CSRF、解析、各类429拒绝不写事件，避免拒绝流量放大数据库负载。它不意味着每个到达服务器的请求都有记录。

## 顺序与事务

Servlet 注册顺序为登录响应/审计边界 → 原会话故障边界 → Spring Session → Security 登录过滤器。提供方返回后立即将已验证 subject 留在私有 request 属性；不放进 Session，不依赖之后可能清空的 SecurityContext。

外层响应将状态、header、Cookie 和正文合计限制在16KiB，独立并发预算默认8（可设置 `software-factory.security.login-audit.max-concurrent`，范围1–64）。该预算覆盖 Session、独立审计事务和响应放行；原密码验证限流不能替代它。饱和时返回有 Retry-After 的429，不进入下游和事件记录。

缓冲器使 flush/close/error/redirect 留在本地，同时保留内层 Spring Session 的保存钩子。先完成本地字符编码，再分类事件和提交。Spring Session 的 finally 可能在早期保存失败后再次成功保存；原故障标记保持为真，因此记录 UNKNOWN、返回503并扣住认证 Cookie，不把存续的数据库 Session 说成回滚。

记录器拒绝环境中的事务、同步状态或绑定资源，用独立 REQUIRES_NEW、可写 READ COMMITTED 事务执行一次 INSERT，检查行数并等待提交确认。它不参与登录读取或 Session 事务；唯一 attempt 主键只限制重复记录，不能保证崩溃、数据库不可用或提交确认丢失时一定留下事件。

审计失败不会撤销先前保存的 Session。任何未确认提交都返回通用503并清除可发布的认证 Cookie，不查询后盲目重发，不改写为另一种事件，不创建新 UUID 重试。已确认记录后的传输失败也不重写事件；SESSION_PERSISTED 是服务器观察，不是浏览器收到 Cookie 或响应的证明。

## 数据权限与安装

V14 不授予任何运行权限。目标安装须核对完整已知源码链、正常迁移和精确角色权限；新增运行权限只有本表 INSERT。运行角色无历史 SELECT、列 SELECT、UPDATE、DELETE、TRUNCATE、REFERENCES、TRIGGER、MAINTAIN。UPDATE/DELETE/TRUNCATE 的只追加触发器也约束表所有者。

这是包含匿名行的受控全局事件流，不用租户 RLS 假装隔离，也没有面向租户的查询入口。读取/导出和维护授权须另做设计。当前不提供自动清理任务；留存180天是总体设计的初始讨论值，不是本批已实施的合规政策。禁止用删除事件清理测试夹具。

该代码默认要求可用的 V14 和记录权限；旧角色或缺表时，已获准登录拒绝放行。现有历史 V9 缺源码目标继续阻塞升级；不通过 repair、baseline 或 out-of-order 绕过。独立本地测试资源不授权在 NAS 或生产目标应用迁移/扩权。

实库结果、故障注入范围和未完成项见[本批独立验收记录](verification/login-authentication-events-2026-11-03.md)；源代码、静态检查和单元测试本身不代替真实提交、Cookie 或内容访问验收。

# jCasbin 功能权限接入验证

从仓库根目录执行，需要 JDK 21。首次运行会通过 Maven 下载依赖。

```sh
./mvnw -B -f tools/dual-auth/function/pom.xml test
```

独立工程锁定 Maven Central 已发布的 `org.casbin:jcasbin:1.99.0`，使用仓库 Maven Wrapper 和 JUnit 6.0.1。测试直接调用 jCasbin `Enforcer`，请求参数为成员、租户、资源、动作。模型依据该版本的官方 Domain RBAC 示例。[官方模型](https://github.com/apache/casbin-jcasbin/blob/v1.99.0/examples/rbac_with_domains_model.conf)

2026-11-20 在 Temurin JDK 21.0.13、Maven 3.9.11 上运行通过，5 tests，0 failures，0 errors，0 skipped。报告由 Surefire 写入 `target/surefire-reports/`。

| 验证 | 结果 |
| --- | --- |
| 多角色按具体资源、动作组合 | 同一成员可读、可编辑采购台账，审批、发布及其他台账默认拒绝 |
| 同名成员和角色跨租户 | A 租户的角色关系无法使用 B 租户的策略，给 B 显式授权后仅在 B 生效 |
| 新台账动作运行时登记 | 新增验收审批策略后可以审批，移除策略后立即拒绝，采购读取仍可用 |
| 撤销成员角色 | 下一次判定收回编辑资格，其他角色授予的读取仍可用 |
| 角色继承 | 租户内高级角色继承读取，跨租户拒绝，移除继承关系后下一次读取被拒绝 |

该结果覆盖单进程内存中的判定和策略变更。实际生产接入还需把当前可信成员、租户、角色和动作目录接入功能引擎，并验证授权事务提交、持久化、进程间刷新和后台事件调用。多实例撤权的提交时序仍须实测。

数据权限引擎负责按动作返回记录及字段范围。实际读取或写入须同时满足功能资格和对应的数据范围。把 jCasbin 加入生产构建前，还需核对 Spring Boot 管理的依赖版本、策略存储及故障处理，并执行服务端授权入口验收。

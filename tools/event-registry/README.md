# 单台账流程的事件关联验证

使用工程锁定的 Flowable 8，在 PostgreSQL 18 的临时 schema 中创建事件模型、JSON 入站通道和验收流程。两个入站请求分别开启 A、B 两笔业务。A 的审批实例 ID 搭配 B 的批次不能推进流程，A 的正确组合只生成一项核对待办；另一笔仍在等待。


入站样例使用合成的 CloudEvents 1.0 structured JSON。测试桥验证版本、类型、来源 URI 引用和事件 ID，并保留 `(source, id)` 供断言。它只把约定的 `data.instance`、`data.batch` 映射给 Flowable，不从事件数据授予租户或操作权限。无效信封在桥上拒绝；合规但实例和批次不匹配的事件不能唤醒节点。这不是生产入口，也不验证事件去重、持久投递或重试。

只运行不访问数据库的信封契约测试：

```sh
./mvnw -o -f tools/event-registry/pom.xml -Dtest=StructuredReceiptEventTest test
```


早到样例先用仓库的独立合成流程停在接收资料任务，再发送一条实例和批次都正确的批准事件。测试检查事件不会越过接收任务、随后建立等待也不会自动推进，而等待建立后重送字节相同的信封可推进一次。这个场景只描述当前同步测试通道的可观察行为，不证明系统没有记录早到事件，也没有实现或验证暂存、过期和去重。

在专用测试数据库上运行以下命令。账号须有建 schema 权限，测试结束会清理本次随机生成的 schema。

```sh
export EVENT_PG_URL=jdbc:postgresql://127.0.0.1:5432/event_probe
export EVENT_PG_USER=postgres
export EVENT_PG_PASSWORD=probe_local
./mvnw -B -ntp -f tools/event-registry/pom.xml test
```

组件验证使用同步投递。接入业务环境还需为迁移角色提供 Event Registry 建表路径，为运行角色授予精确权限，验证租户隔离、持久化投递、重试、早到与业务去重，再接入配置订阅。生产配置沿用现有启用策略。[上游事件注册文档](https://www.flowable.com/open-source/docs/eventregistry/ch06-EventRegistry-Introduction)

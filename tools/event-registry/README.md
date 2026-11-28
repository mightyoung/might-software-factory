# 单台账流程的事件关联验证

使用工程锁定的 Flowable 8，在 PostgreSQL 18 的临时 schema 中创建事件模型、JSON 入站通道和验收流程。两个入站请求分别开启 A、B 两笔业务。A 的审批实例 ID 搭配 B 的批次不能推进流程，A 的正确组合只生成一项核对待办；另一笔仍在等待。


入站样例使用合成的 CloudEvents 1.0 structured JSON。测试桥验证版本、类型、来源 URI 引用和事件 ID，并保留 `(source, id)` 供断言。它只把约定的 `data.instance`、`data.batch` 映射给 Flowable，不从事件数据授予租户或操作权限。无效信封在桥上拒绝；合规但实例和批次不匹配的事件不能唤醒节点。这不是生产入口，也不验证事件去重、持久投递或重试。

只运行不访问数据库的信封契约测试：

```sh
./mvnw -o -f tools/event-registry/pom.xml -Dtest=StructuredReceiptEventTest test
```


早到样例先用仓库的独立合成流程停在接收资料任务，再发送一条实例和批次都正确的批准事件。测试检查事件不会越过接收任务、随后建立等待也不会自动推进，而等待建立后重送字节相同的信封可推进一次。这个场景只描述当前同步测试通道的可观察行为，不证明系统没有记录早到事件，也没有实现或验证暂存、过期和去重。

早到暂存下一步的离线判定契约只在测试目录运行：到期时间固定为首次接收时间加已发布订阅的有效期，达到期限即不可投递；即使等待已建立也不能处理已过期项。同一投递重试不得改变原始信封或延长有效期。传输键为可信租户与 CloudEvents 的来源／ID；投递键另含原订阅版本，目标为审批实例和批次。不同来源命中同一业务目标仍须另做业务去重，不能凭目标相同丢弃事件。本夹具没有检查跨订阅的入站冲突，不存储事件，也不办理流程。

```sh
./mvnw -o -f tools/event-registry/pom.xml -Dtest=StructuredReceiptEventTest,EarlyReceiptContractTest test
```

实库时钟用例 `EarlyReceiptPostgresClockTest` 在临时 schema 中使用两个连接。第二个连接在到期前开始事务，测试确认其等待第一连接持有的行锁，达到期限后才释放。用例比较旧事务时间与获锁后独立查询的 `clock_timestamp()`，把两者送入过期判定，证明是否会得到相反结果。该用例只验证锁与时钟，不连接真实流程、不写业务回执。

实库存储用例 `EarlyReceiptPostgresStorageTest` 在独立试验表中验证提交后换数据库连接仍能取出原投递，按原租户、来源／ID 与订阅版本找回，不延长重试期限或覆盖原文；撤销事务不留下投递。两个并发用例用独立连接确认后到的重试确实被首事务阻塞：首事务提交时保留原截止时间，回滚时由后到事务首次写入。该夹具不提供跨订阅入站冲突、自动重投或业务去重。

轻量投递试验在同一个隔离 PostgreSQL schema 启动两笔真正的 Flowable 流程。测试显式调用桥接函数，验证未就绪时不投递、就绪后未过期的原始正文只推进对应批次、过期批次仍留在等待节点。重复调用不会新增第二项待办，因为对应等待节点已离开。该结果不等同于持久化业务去重或原子回执。桥接时间为测试固定值，行锁后的实库时钟另有独立测试。

后续仍需在隔离 PostgreSQL 和 Flowable 上固定原订阅版本，验证立即就绪、早到后就绪、到期后就绪和重启恢复，以及到期无待办、重复唤醒只办理一次、失败回滚无成功回执。离线用例不证明数据库事务或流程行为。

在专用测试数据库上运行以下命令。账号须有建 schema 权限，测试结束会清理本次随机生成的 schema。

```sh
export EVENT_PG_URL=jdbc:postgresql://127.0.0.1:5432/event_probe
export EVENT_PG_USER=postgres
export EVENT_PG_PASSWORD=probe_local
./mvnw -B -ntp -f tools/event-registry/pom.xml test
```

组件验证使用同步投递。接入业务环境还需为迁移角色提供 Event Registry 建表路径，为运行角色授予精确权限，验证租户隔离、持久化投递、重试、早到与业务去重，再接入配置订阅。生产配置沿用现有启用策略。[上游事件注册文档](https://www.flowable.com/open-source/docs/eventregistry/ch06-EventRegistry-Introduction)

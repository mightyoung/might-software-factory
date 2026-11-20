# 双权限引擎接入验证

这套独立验证使用 Java 21、jCasbin 1.99.0、Cerbos 0.57.0、项目管理的 jOOQ 版本及 PostgreSQL 18。应用现有的权限接口继续服务 Astryx 权限页。这里验证候选引擎与数据库的组合语义，生产接口切换和多实例权限刷新另行验收。

## 运行

从仓库根目录运行。为本次验证提供专用测试数据库及账号，账号需要在该库创建 schema 和测试表的权限。`AUTHZ_PLAN_FILE` 指定本次真实 Cerbos 请求结果的输出文件。Linux x86_64 或 macOS arm64 可通过脚本启动固定版本的服务，运行结束后脚本会回收自己的服务。

```sh
export AUTHZ_PG_URL=jdbc:postgresql://127.0.0.1:5432/authz_probe
export AUTHZ_PG_USER=postgres
export AUTHZ_PG_PASSWORD=local_probe_only
export AUTHZ_PLAN_FILE="$(mktemp)"
./mvnw -B -ntp -f tools/dual-auth/function/pom.xml test
bash tools/dual-auth/data/run-ci.sh ./mvnw -B -ntp -f tools/dual-auth/query/pom.xml test
```

## 判据

- jCasbin 用租户、主体、台账与动作判定功能资格，分别验证角色撤销、策略移除及跨租户场景。
- Cerbos 对同批记录逐项执行 `CheckResources`，再获取真实的 `PlanResources` 返回值。受限转换器将策略用到的字段和操作符转换为参数化 jOOQ 条件，未知输入会报错。
- PostgreSQL 对每个计划执行计数和每页一条的排序分页。兼岗人的可读集为两条、可编辑集为一条。查询在数据库内逐行投影字段，同事的数量可见、价格为空。关联查询从实际关系表取行，页签数字使用同一条件。
- 在组合写入用例中，旧表单反复提交相同记录。功能资格与记录授权同时满足才允许写入，分别撤销任一侧后核对受影响行数与数据库里的修订。
- 两个独立数据库连接竞争同一事务锁，分别验证先写入时成功一次、先撤权时旧请求写入零条。
- [双授权 CI](../../.github/workflows/dual-auth-ci.yml) 在临时 PostgreSQL 服务上运行上述命令。[前端 CI](../../.github/workflows/frontend-ci.yml) 通过现有单元测试、Chrome、类型检查、生产构建与体积预算检查 Astryx 集成。

# Frontend

首批为电脑浏览器 React + TypeScript + Vite 外壳，示例围绕隐患台账与六个流程节点。导航、字段驱动的查询/列表、详情、分页和当前页选择为本地演示实现。

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

映射器使用契约规定的 20/50/100 分页、正整数页码与最大偏移，按已注册运算符生成过滤，并检查显式排序和输出字段。无显式排序/投影时省略对应属性。日期输入只映射到注册为 `DATE` 的字段；不把无时区的日期输入转换成 `DATETIME`。响应类型把 `total` 保持为可选，避免构造占位总数。

UI 合成数据的本地过滤与共享契约暂有语义差异：当前“编号 / 描述”关键词会在两个属性间做 OR 查询，而 v1 过滤组只定义 AND；`CONTAINS` 的大小写规则也未在契约中说明。因此这些查询尚不能声称与后端查询等价。接入时需等后端注册字段与查询语义确认后再配置映射，并让后端对每个业务命令重新校验租户、身份、职责、状态、版本、轮次和证据门槛。

字段定义随前端代码审查发布，不提供在线改 schema、任意 SQL 或任意脚本入口。
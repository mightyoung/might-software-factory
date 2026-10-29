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

本目录目前不包含共享 OpenAPI 生成物或真实后端调用。接口与触发文档仍是设计契约，前端 adapter 只暴露 UI 所需的类型化读取边界，并使用明确的合成样例数据。没有真实权限、审批、分派、证据上传或数据库持久化；操作按钮保持禁用。接入时应以共享 OpenAPI 生成的类型实现 adapter，并让后端对每个命令重新校验租户、身份、职责、状态、版本、轮次和证据门槛。

字段定义随前端代码审查发布，不提供在线改 schema、任意 SQL 或任意脚本入口。

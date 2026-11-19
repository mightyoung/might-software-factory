# 工装借用申请验收

首个 D2 切片覆盖一张申请选择多件工装、本人修订和撤销。申请部门来自借用人的有效部门关系，保管范围单独校验。提交申请后工装仍可被其他人申请。整单发放、占用、归还与接收在后续切片实现。

1. 准备专用 PostgreSQL 18 数据库 `msf_d2_loans_test`，从含 D1 合成身份的测试库复制数据。应用 Flyway V29、D0/D1 运行账号授权和 `db/operator/d2-runtime-grants.sql`。使用测试操作账号运行 `psql -X -f tools/d2/seed.sql`，为两名合成借用人添加申请权限并准备两个范围的工装。
2. 启动连接该库的真实后端。使用 `VITE_API_MODE=http` 构建前端，配置本机 HTTPS 预览和指向该后端的同源 API 代理。
3. 设置 `D2_ORIGIN=https://127.0.0.1:<port>`、`D2_DATABASE=msf_d2_loans_test`、`D2_ACK=isolated-loan-acceptance:msf_d2_loans_test`。通过私有环境提供 `D2_PASSWORD_READER`、`D2_PASSWORD_KEEPER` 和范围 B 工装 `D2-D1-SCOPE-B-1` 的 UUID `D2_OUTSIDE_TOOL_ID`。
4. 从仓库根目录运行 `node tools/d2/browser.mjs`，用真实 Chrome 操作。将输出中的三个值传给同一测试库上的 `psql -X -v marker=... -v loan=... -v second=... -f tools/d2/verify.sql`。每次运行创建独立记录，SQL 核对两名申请人、明细版本、命令回执和审计记录。

接口契约见 [tool-loans.yaml](../../contracts/openapi/tool-loans.yaml)。

## 整单发放与 Flowable

借用人提交和修订申请时不占用工装。发放时，系统验证当前申请版本、发放权限、保管范围及有效保管关系，禁止本人发放。所有明细都可发放时，同一事务启动该租户的 `toolLoanV1` 实例、完成 `issue` 任务并留下分配给借用人的 `returnRequest` 任务，同时保存发放修订、逐件占用、审计和回执。待发放的 V29 单据按同样路径办理。归还、接收与遗失结论的业务入口属于后续开发。

部署时应用 V30，将仓库中的 `processes/tool-loan-v1.bpmn20.xml` 发布给目标租户，然后设置 `software-factory.workflow.tool-loan.tenant-pins.<租户UUID>.definition-id` 和 `resource-sha256`。使用 Flowable 的实际流程定义 ID 以及仓库 BPMN 原始字节的 SHA-256。服务启动不自动部署。发放时核对租户、定义 ID、流程键、启用状态及 BPMN 内容。只有具备 `tool_loan.issue` 并有对应保管范围的人员可以办理。

专用验收库名为 `msf_d2_issue_test`。从含 D1 合成身份与 V29 数据的测试库复制后，应用 V30、既有 D0/D1/D2 运行账号授权及 [发放验收种子](../d2-issue/seed.sql)。使用迁移账号的私有环境变量 `D2_URL`、`D2_USER`、`D2_PASSWORD` 以及合成租户的 `D2_TENANT_ID`，从仓库根目录用后端依赖类路径运行 `java -cp <classpath> tools/d2/DeployIssueProcess.java`。输出的 `D2_PIN` 包含供服务配置的租户、定义 ID 与摘要。

真实浏览器验收从借用人申请三件工装开始，由另一名保管人确认整单发放，再用借用人身份核对结果。并发用例让两张单据竞争同一件工装，并核对胜出申请的占用和归还任务、失败申请的原状态及重复请求的回执。两个用例使用不同的合成工装，见 [浏览器脚本](issue-browser.mjs) 与 [并发脚本](../d2-issue/issue-concurrency.mjs)。

浏览器脚本读取 `D2_ORIGIN`、`D2_DATABASE=msf_d2_issue_test`、`D2_ACK=isolated-issue-acceptance:msf_d2_issue_test`、`D2_ISSUE_SCOPE_ID`、`D2_ISSUE_TOOL_IDS`（三件工装 UUID 组成的 JSON 数组）以及两位测试用户的私有密码变量。为成功发放场景准备三件同范围、状态正常且未被占用的测试工装。运行 `node tools/d2/issue-browser.mjs`。设置 `D2_BROWSER_SCENARIO=q13` 则验证保管人本人借用时，详情显示“无人可办”、本人发放被拒绝、申请不进入自己的发放队列。

## 分批归还与接收

借用人打开自己的已发放借用单，选中要归还的工装，使用既有 `tool_loan.create` 权限提交归还申请。其他工装可以继续使用或另行申请归还。同一件有待接收申请时不能重复申请；提交申请后仍占用工装。

具有 `tool_loan.receive` 权限的原保管范围现任保管人在“待接收工作”中逐件核对，可只接收选中明细。正常接收释放该件占用；损坏接收必须记录原因，释放占用并保留不可借状态。借用人不能接收自己的工装。全部已发放明细均结清后，借用单转为 `SETTLED`。

V31 延续发放时绑定的 `toolLoanV1` 流程定义。首个归还申请将借用人的 `returnRequest` 推进至 `receive`。多个批次可以同时等待接收，共用这个待办，业务记录分别保留每批、每件和实际办理人。最后一件待接收明细办理时，当前保管人认领并完成任务。若仍有在借工装，流程回到该借用人的归还任务；全部结清时流程结束。业务状态、逐件占用、审计回执与引擎变更使用同一数据库事务。

验收环境使用 `msf_d2_return_test`。在最终 V30 基线上执行 V31 及 `db/operator/d2-return-runtime-grants.sql`，用专用运行账号启动后端；验收种子见 [seed.sql](../d2-return/seed.sql)。旧的已发放单使用原流程实例，无需重建或重新发放。

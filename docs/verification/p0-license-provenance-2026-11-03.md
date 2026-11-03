# P0 离线父 POM 许可来源追溯 — 2026-11-03

## 有界实施计划

基线 d1963e52c896c16e76c6e466a480d8d76b4f132d。现有26项工具行为测试已先通过；保留原始POM声明、UNKNOWN列表、图及打包覆盖含义，不改Java/依赖/运行时/目标状态。

1. 仅复用既有有界、安全XML读取；通过精确发布parent GAV定位本地M2 POM，完全忽略relativePath，不联网或扫描M2。
2. 新增独立nearestLicenseDeclarationEvidence（literal来源选择，不是完整effective模型），记录DIRECT/PARENT/UNKNOWN、声明源坐标/相对路径/SHA256及最近父级优先的链。明确上限、循环和未解析属性；缺证据保持UNKNOWN，非法XML/路径拒绝。
3. 子级显式声明优先，缺失才向父级追溯；不按组织或artifact名称猜许可证，不提供法律通过标记。
4. 新增父链、覆盖、缺失/循环/属性及恶意来源回归；继续运行原26项严格JSON/ZIP/库存兼容检查。实际库存只读旧输入、写入独立新目录并比较旧字段；保留旧报告。
5. 更新工具说明及本验收记录，独立审查前不提交、不推送，不签署G002或P0整体完成。

## 执行结果

本批只改库存工具、测试和说明，未运行Maven/Docker、改POM依赖或业务代码，未改运行时/目标账本，没有提交、合并或部署。来源选择策略与保留边界见[工具说明](../../tools/p0/README.md)。

|检查|结果与边界|
|---|---|
|原行为锁定|修改前26项全部通过|
|最终标准库测试|39项通过，含原26项JSON/ZIP/字节覆盖行为；新增13项覆盖最近父链、直接声明、空/无效列表、缺失/循环/深度/字节预算、未解析属性、relativePath忽略、恶意XML/坐标错配与仓库外符号链接拒绝|
|实际输入|只读沿用上一批精确resolved JSON图和Linux最终构建包；本批不重跑或再计552项应用测试|
|字节覆盖|仍为COMPLETE，191依赖、131打包库、16构建工具排除、1显式嵌入来源、零覆盖错误|
|原声明状态|原65个raw UNKNOWN及全部旧POM字段保持不变；不是将UNKNOWN改为法律通过|
|新来源证据|126个DIRECT、65个PARENT，未解析最近声明坐标列表为空；最长父链6级，读取24份不同父POM并逐一核对SHA256|
|嵌入工具宿主|仍单独记录，新增宿主最近声明为DIRECT；不伪造项目依赖图边|
|重复与兼容投影|两份新报告逐字节相同；剥除新增来源字段并还原limitations说明后，与旧冻结报告完全相同|
|源码检查|标准库语法检查、文档本地链接与diff空白检查通过；独立审查由主代理随后执行|

`nearestLicenseDeclarationEvidence`只选择最近的非空字面声明，记录声明POM坐标、相对路径、摘要与最近父级优先的链。它不执行完整Maven ModelBuilder、属性插值、profile/BOM合成或法律评估。子级选择优先不表示父级法律义务消失；缺失父POM、循环和未解析输入在独立来源状态中保留UNKNOWN，不改变原制品覆盖结果。

## 保留证据

新执行目录为本地`msf-p0-license-provenance-20261103`：

- `requested-parent-green.log`：最终39项行为测试。
- `dependency-license-evidence-requested-parent-reviewed.json`与`dependency-license-evidence-requested-parent-repeat.json`：诊断修复后的两次相同输出，也与此前合法输入报告逐字节相同。
- `verification-summary-final.json`：原字段投影、计数和摘要核验。
- `source-evidence-manifest-final.json`：本批最终四个源码/文档输入的摘要。

新报告SHA256为`1428addbed14cc71908bdd0715a7b4aeae24392b16cd7ac4c9eb6265d00f92cb`。输入resolved图SHA256为`357b7e5576de9d1520733ac47467fbcd73d217153d4dcf2e770a9e651599e4c2`，Boot包为`99e07d285a356478a392553bf3fe68a67483e713e308afa9c6dbbb851b2b77db`；原批次的冻结及失败报告没有覆盖。

最初新增父链行为在旧实现上失败的日志保留为`msf-p0-license-provenance-red.log`，用于区分既有行为和新增功能。不把父链定位成功算作SBOM法律审阅、漏洞扫描、项目许可证选择、前端/JDK/全部构建工具分发义务或G002/P0整体完成。历史V9源缺失和P1/P2开发任务保持各自状态。


## 独立审查后的来源严格性修复

两个边界由独立审查复现：旧raw视图的first-value读取可隐藏重复或嵌套许可字段；完整child GAV及直接声明也会在检查parent结构前返回。新nearest层已独立解析唯一标量字段，拒绝重复容器/字段、嵌套和异常声明结构；任何DIRECT/PARENT结果前均验证出现的parent结构与字面坐标安全，同时不读取不必要的父文件。raw视图对合法输入的字段保持原样。

新增真实POM夹具先在旧实现上复现7个子场景失败，红日志为`strict-declaration-parent-red.log`。该次修复后38项通过；合法191依赖的重复输出摘要仍是上文同一SHA256，与修复前新来源报告完全相同。旧报告保留，没有用清理或重写消除失败证据。


最后一项LOW诊断也已按红绿回归修复：当前POM身份和严格声明结构接受后清空`requestedParent`，已成功读取但无声明/后续parent的终止POM不再被误报为未解析下一坐标；缺失、字节预算不足、身份未解析等接受前停止继续保留待解析坐标。`requested-parent-red.log`保留修复前失败，`requested-parent-green.log`为最终39项通过。最终两份实际报告与此前合法输入报告逐字节一致，原始库存、父链来源和覆盖结果均未改变。

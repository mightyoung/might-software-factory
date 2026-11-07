# P0 依赖证据批次 — 2026-11-03

本批应用源码基于 `350e3c41bef322e43fec1c9beca7e8e4c02e7e3f`，即开放的 PR #31、#32 候选链。变更限于库存工具、CI行为测试、Maven下载校验和文档；没有修改 Java、POM、迁移或运行授权，没有接触 NAS。它推进 P0，不签署 P0–P2 完成。

## 实际执行

|检查|结果及边界|
|---|---|
|解析后的依赖树|固定 Maven dependency-plugin3.10.0，离线 backend JSON树成功；191个依赖，包含测试scope，不扫描整个M2|
|发布包来源|沿用最终552项Linux离线验证产生的包；332份应用源码/资源/契约/构建输入重新核对零漂移。没有在本批重跑552项，也不将其算作工具测试|
|库存行为测试|最终26项标准库 unittest通过，失败/错误为零；CI新增同一发现命令|
|实际包对账|191个依赖、131个包内库全部解释；16个明确Manifest类型按Boot4默认规则不进入运行包，1个loader-tools显式嵌入来源按字节匹配；零覆盖错误|
|重复执行|同输入运行两次，自定义清单字节完全相同；无本地绝对输入路径；不据此声称CycloneDX文件时间戳也确定|
|标准SBOM|固定CycloneDX2.9.3生成JSON1.6，192个组件、193个图节点；包含backend模块与测试依赖。工具报告写入/校验成功，保留meta:enum/deprecated两条schema注解警告，未另做独立schema验证|
|许可证|65个**项目依赖**POM声明仍UNKNOWN；显式打包宿主的许可另见packagingEvidence，不并入此65项。无项目许可证选择、法律审核或分发义务完成声明|
|宿主JDK|Temurin21.0.13+9，244份legal/release/NOTICE文件摘要保留；只是运行依赖图解析和SBOM生成的Maven工具所用macOS JDK，不等于已覆盖Linux应用构建JDK及所有构建插件/前端许可证|
|Wrapper|官方ZIP经官方SHA512核对后计算SHA256并固定；随机localhost提供同一ZIP，独立空缓存正确值返回Maven3.9.11，错误值exit1且执行前拒绝。临时安装与日志保留，全局安装未变|

依赖图和标准SBOM来自相同的未改POM输入；自定义清单另核对实际包。CycloneDX以Maven离线模式首次运行时明确拒绝，保留失败日志后才以在线模式执行成功，没有新增应用依赖。SBOM不替代包内注入来源证明，包字节清单也不替代标准SBOM。

发布包 SHA256：

```text
99e07d285a356478a392553bf3fe68a67483e713e308afa9c6dbbb851b2b77db
```

工具使用方式、官方打包规则与Wrapper校验来源见[依赖证据说明](../p0-dependency-evidence.md)和[工具README](../../tools/p0/README.md)。

## 审查修复与清理

实际首轮遇到ArchUnit中重复的安全目录项；补回归后仅允许重复目录，仍拒绝重复文件或不安全路径。随后清单如实报告Boot默认排除和插件注入的17项差异；按固定版本官方规则和真实宿主字节增加显式证据，没有按名称猜测、伪造依赖边或关闭覆盖检查。

独立代码审查发现并修复三项输入边界问题：JSON重复键/非标准常量可能隐藏依赖；加密或不支持压缩方式导致异常逃逸；ZIP解析器分配中央目录前缺少资源前置。相关红测试保留，最终测试拒绝重复/NaN/Infinity，以通用exit2保留旧报告，并在ZipFile构造前限制EOCD、中央目录、文件与条目数量，拒绝ZIP64和多磁盘。该前置不是完整敌意ZIP解析器，仍要求受控、执行期间不可变的构建输入。

有界清理检查未发现需要额外框架、兼容兜底或推测性抽象；保留UNKNOWN和明确拒绝属于证据保护，未以默认值掩盖失败。只补上述必要边界与回归，没有扩大到业务重构。语法解析、实际确定性运行和差异空白检查通过。最终独立代码及架构审查结论随PR记录。

## 保留证据与余项

本地 `msf-p0-license-inventory-20261103` 目录保留：`dependency-tree.log`、`resolved-dependencies.json`、初次不完整清单、`inventory-tests-final-reviewed.log`、`dependency-license-evidence-frozen.json`、重复运行清单、`cyclonedx.log`与`cyclonedx-online.log`、`sbom/p0-p2-development-bom.json`、`artifact-source-manifest.json`、`host-jdk-legal-evidence.json`、Wrapper正反例日志。官方ZIP及SHA出处另保留在 `msf-maven-wrapper-checksum-20261103`。未删除失败数据或原资源。

仍未完成：UNKNOWN许可及继承来源逐项核对、项目自身LICENSE决策、构建插件/完整JDK/前端的标准SBOM和分发义务、漏洞与发布审查、历史V9目标升级证据。整体任务见[现状清单](../overall-development-status-2026-11-03.md)。生产上传、身份管理、完整双预防业务等P1/P2任务仍按独立批次推进。

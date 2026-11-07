# P0 依赖与许可证据

本工具链区分解析后的开发依赖图、实际发布包和许可证原声明。清单完整不代表法律审查通过；项目自身许可证仍未决定，也不据此宣称 P0 整体完成。

## 输入与输出

1. 在受控源码上用固定版本 Maven dependency-plugin 导出 backend 的实际 JSON 依赖树，包含测试依赖；不能遍历整个本地缓存来猜依赖，也不能只解析未仲裁的原始 POM。
2. 使用与源码摘要匹配的 Spring Boot 发布包，将 `BOOT-INF/lib` 字节与解析制品逐项核对。
3. `tools/p0/dependency_inventory.py` 只使用 Python 标准库，保留制品/POM SHA256、直接/传递关系、scope、原始许可证声明及 JAR 中 LICENSE/NOTICE/COPYING 的摘要。未知、父级继承尚未解析、占位符未解析均保持 UNKNOWN；不按 groupId 猜许可证。
4. 工具输出自定义证据格式，不冒称 SPDX/CycloneDX。标准 SBOM 另由固定版本 CycloneDX 2.9.3 工具生成；它没有加入应用依赖或项目 POM。

命令与限制见[工具说明](../tools/p0/README.md)。构建插件、JDK、前端依赖、漏洞、分发义务与项目 LICENSE 选择仍需分别核验，后端应用依赖图不能替代它们。

## Spring Boot 打包规则

Spring Boot 4.1.2 根据 JAR **主 Manifest** 中 `Spring-Boot-Jar-Type` 的精确值排除 `annotation-processor`、`dependencies-starter`、`development-tool`。不能根据名称含有 starter 就排除；未知值和读取失败不能解释为合理缺包。依据为固定版本[官方 JarTypeFilter 源码](https://github.com/spring-projects/spring-boot/blob/634e6fd6bc2e475d745c0bdceb8e775864f7f2ec/build-plugin/spring-boot-maven-plugin/src/main/java/org/springframework/boot/maven/JarTypeFilter.java)。

插件默认 `includeTools=true`，从 loader-tools 内嵌入 jarmode-tools 到发布包。这不是项目依赖树中的普通成员；库存必须单列显式宿主与嵌入项的来源和摘要，不能捏造一条解析依赖边。依据为[官方插件接线](https://github.com/spring-projects/spring-boot/blob/634e6fd6bc2e475d745c0bdceb8e775864f7f2ec/build-plugin/spring-boot-maven-plugin/src/main/java/org/springframework/boot/maven/AbstractPackagerMojo.java)、[Packager](https://github.com/spring-projects/spring-boot/blob/634e6fd6bc2e475d745c0bdceb8e775864f7f2ec/loader/spring-boot-loader-tools/src/main/java/org/springframework/boot/loader/tools/Packager.java)与[JarModeLibrary](https://github.com/spring-projects/spring-boot/blob/634e6fd6bc2e475d745c0bdceb8e775864f7f2ec/loader/spring-boot-loader-tools/src/main/java/org/springframework/boot/loader/tools/JarModeLibrary.java)。

## Maven 下载校验

Wrapper 已固定 Maven 3.9.11 ZIP 的 `distributionSha256Sum`：

```text
0d7125e8c91097b36edb990ea5934e6c68b4440eef4ea96510a0f6815e7eeadb
```

它是下载精确官方 ZIP、验证[官方 SHA512](https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.11/apache-maven-3.9.11-bin.zip.sha512)后计算的 SHA256，不是 Apache 单独发布的 SHA256。ZIP 为9,278,421字节，官方 SHA512 为：

```text
03e2d65d4483a3396980629f260e25cac0d8b6f7f2791e4dc20bc83f9514db8d0f05b0479e699a5f34679250c49c8e52e961262ded468a20de0be254d8207076
```

[官方 Wrapper 说明](https://maven.apache.org/tools/wrapper/#checksum-verification-of-downloaded-binaries)支持该字段，当前脚本会在下载后、解压和执行前核对。此措施保护新下载，不声称重新验证已有 Maven 缓存安装的每个文件。

本地隔离测试用同一已核验 ZIP，经随机 localhost 端口分别执行正确摘要和错误摘要：正确值可输出 Maven3.9.11，错误值在执行前拒绝。没有修改全局 Maven 安装。

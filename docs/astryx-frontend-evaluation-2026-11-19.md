# Astryx 前端适配评估

核对日期为 2026-11-19。评估基线是归还切片 `c49b94e`，React / React DOM 19.3.1、Vite 8.3.5、TypeScript 7.0.2。本次只在 `/private/tmp/astryx-eval-20261119` 的独立副本安装和修改代码，项目依赖与运行页面保持当前实现。

## 结论

Astryx 0.7.16 能在本项目前端编译运行，适合以少量页面验证组件和主题。但官方完整接入方式超过现有 JS 130 KiB、CSS 12 KiB 的 gzip 预算，当前证据支持先做独立组件与主题试点，再决定生产接入范围。库仍标注 Beta，更新和回归成本需要纳入维护。官方声明 React 19 以上，使用预编译 JS / CSS，因此本项目现有 React 和 Vite 版本满足基本接入条件。[官方 README](https://github.com/meta/astryx/blob/v0.7.16/README.md) · [核心包安装说明](https://github.com/meta/astryx/blob/v0.7.16/packages/core/README.md)

## 版本、许可与维护

| 项目 | 本次核实结果 |
| --- | --- |
| 实际 npm 最新发布 | `@astryxdesign/core@0.7.16`，官方 Release 发布时间为 2026-11-14 |
| 上游 main | 读取时 manifest 已是 0.7.17，不能据此认定该版本已正式发布 |
| React | Core 需要 React / React DOM `>=19.0.0` |
| StyleX | Core 的 peer dependency 为 `@stylexjs/stylex ^0.19.0`，消费预编译包不要求应用加入 StyleX 构建插件 |
| 主题 | `theme-neutral@0.7.16` 的 Core peer 使用同版本，主题另依赖 `lucide-react` |
| 许可 | MIT，分发时保留版权和许可声明 |
| 安全维护 | 官方只为最新已发布版本提供安全更新 |

来源为 [发布记录](https://github.com/meta/astryx/releases/tag/v0.7.16)、[Core manifest](https://github.com/meta/astryx/blob/v0.7.16/packages/core/package.json)、[主题 manifest](https://github.com/meta/astryx/blob/v0.7.16/packages/themes/neutral/package.json)、[MIT 许可](https://github.com/meta/astryx/blob/v0.7.16/LICENSE) 和 [安全策略](https://github.com/meta/astryx/blob/v0.7.16/SECURITY.md)。本次没有完成完整漏洞扫描，不能把这些元数据当作不存在安全问题的证明。

## 隔离兼容实验

独立副本使用本项目准确版本，安装 Core 0.7.16、Neutral 0.7.16 和 StyleX 0.19.0。安装时使用 `--ignore-scripts`。消费组件不需要执行 CLI `init`，实验按包的官方 API 手工接入。CLI `init` 会写入项目 AGENTS.md / CLAUDE.md，若后续采用，应先审阅这些生成内容与现有开发规则的兼容性。[CLI 初始化说明](https://github.com/meta/astryx/blob/v0.7.16/packages/core/README.md#astryx-cli)

试点改动包含根部 `Theme` / `neutralTheme`、官方三个 CSS 导入，以及真实 `LoanReturns` 的提交、刷新两个按钮。使用 `@astryxdesign/core/Button` 子路径导入，保留原命令载荷、提交方式、禁用状态与未知结果恢复处理。官方预编译方式需要按 reset、Core、theme 的顺序导入样式，应用自己的未分层 CSS 会覆盖 Astryx 的分层样式，现有 `styles.css` 因此需要逐页核对。[预编译接入与层叠说明](https://github.com/meta/astryx/blob/v0.7.16/packages/core/README.md#quick-start) · [reset.css](https://github.com/meta/astryx/blob/v0.7.16/packages/core/src/reset.css)

| 验证 | 原前端副本 | Astryx 两按钮和官方主题接入后 |
| --- | --- | --- |
| TypeScript 检查 | 通过 | 通过 |
| HTTP 模式 Vite 生产构建 | 通过 | 通过 |
| 原 `LoanReturns` 七项测试 | 原基线已通过 | 七项全部通过 |
| JS gzip，项目预算工具口径 | 123.7 KiB | 211.6 KiB，超过 130 KiB |
| CSS gzip，项目预算工具口径 | 5.3 KiB | 53.5 KiB，超过 12 KiB |

同一 Node `gzipSync` 口径下，JS 从 126696 字节增加至 216707 字节，CSS 从 5447 字节增加至 54782 字节，增量分别为 90011 和 49335 字节。这是本次具体组合的结果，不能外推为每个组件固定增加同样大小。独立副本保留 `summary.json`、构建日志、测试日志和两个构建产物以便复核。

本次证明了编译、导入和现有七项行为测试兼容。尚未验证全系统页面的样式、键盘操作、屏幕阅读器、真实设备首屏或中国语言排版。上游有 zh-CN / zh-TW 目录，正式采用仍需接入并验证语言 Provider。[官方语言资源](https://github.com/meta/astryx/tree/v0.7.16/packages/core/locales)

## 推荐接入方案

先选择一个独立业务详情或公共表单页，在懒加载页面中试用 Button、Field、Dialog、Table 等必要组件。公共组件封装应保留业务现有的授权判断、请求键、版本冲突和恢复机制。Astryx 提供视觉与交互组件，后端命令和 Flowable 规则仍由现有平台负责。

试点时测量 Core CSS、主题、图标及多语言各自的产物增量，并尝试精简主题或官方支持的按需构建路径。把主入口和异步页面分别计量，避免通过拆包掩盖总体积增长。满足体积和交互验收后，再安排一批基础组件替换。当前无需为验证引入全局 CLI，研究版本应固定为 0.7.16；正式开发前重新检查上游发布版本及迁移说明。

## 试点验收

- 原表单创建、修订、归还和接收的请求载荷、错误反馈及原键恢复继续通过。
- 仅持有一个动作权限的用户能进入正确页面，组件隐藏与后端命令授权相符。
- 验证登录、台账、隐患、工装页面在主题和 reset 导入后的视觉与键盘焦点，特别是旧全局 CSS 的覆盖。
- 验证表单 label、错误关联、禁用和加载状态、弹窗焦点，以及中文组件内置文案。
- 构建预算通过，或以实测网络和首屏数据提出新的明确预算，再由项目决定。
- 升级时统一 Core、主题和 CLI 版本，并运行原有单元、端到端和真实业务验收。

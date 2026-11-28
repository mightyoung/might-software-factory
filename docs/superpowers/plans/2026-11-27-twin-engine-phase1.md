# Might 3D Engine：第一阶段实施计划

按 2026-11-27《Might 3D Engine》设计和仓库《设备地图大屏》既定选型，与工作台 Phase 4 分工推进。

1. 首批：`frontend/engine3d` 独立包、锁文件和 280 KiB gzip 门禁；纯 Three.js/TypeScript 内核与独立 Vite 入口，不改主工作台、Office 或现有预算。用严格 SceneDocument v1 子集描述具有稳定 ID 和状态的米制程序化几何体；拒绝任意代码、URL、重复 ID、越界值。WebGL2 预览和可操作二维列表使用同一文档。
2. 测置换、按需渲染、画布 resize、WebGL 上下文丢失/恢复与本切片几何体、材质和 renderer 的释放。软件 WebGL 只验证功能，不证明实机 FPS、显存或 7×24 小时稳定性。
3. 下一切片：受控 GLB 体检、引用计数、LOD 与 Draco/KTX2/Meshopt，验收连续 50 次切换。随后交付模型资产库、预制体和大屏 Studio/Player，分别实测租户权限、业务联动、版本发布和 Player 独立包体积。60 FPS、单帧 ≤200 DrawCalls 和显存目标均未验收。

运行时依据：[Three.js WebGLRenderer](https://threejs.org/docs/pages/WebGLRenderer.html)。

# NOTICE / 署名与改动说明

## 本仓库的性质

`CubeFish-Mobile`（方块鱼手机版）是下列项目的 **fork**，对其进行品牌化改造并（逐步）加入方块鱼 AI 助手：

- **Amethyst-Android** — <https://github.com/AngelAuraMC/Amethyst-Android>
  - fork 基线分支：`v3_openjdk`
  - fork 基线提交：`a743e28`
  - 许可证：GNU Lesser General Public License v3.0（见 `LICENSE`）
- **PojavLauncher** — <https://github.com/PojavLauncherTeam/PojavLauncher>（LGPL-3.0，Amethyst 的上游）
- **Boardwalk** — <https://github.com/zhuowei/Boardwalk>（JVM 启动器思路与 AWT 移植）

上游版权归其各自作者所有。本项目沿用上游许可证，源码随本仓库公开提供，符合 LGPL-3.0 的源码可得要求。

## 方块鱼做的改动

1. **品牌化**：应用名「方块鱼」、应用图标（由 `art/mascot-fish.png` 生成）、包名 `dev.cubefish.mobile`。
2. **ABI 裁剪**：只保留 `arm64-v8a` + `armeabi-v7a`（`build.gradle` 的 `abiFilters` 与 `Application.mk` 的 `APP_ABI` 同步）。
3. **文档**：`README.md`、`NOTICE.md`、`docs/MOBILE.md`（桌面版仓库）中的方案与支持范围说明。
4. 计划中的 AI 模块将放在独立包（`dev.cubefish.mobile.ai`）与独立资源目录，不改动引擎核心实现，便于与上游合并。

## 未改动

- 引擎的 Java/native 核心逻辑、JRE 与渲染层（GL4ES / MobileGlues / ANGLE / SDL）均保持上游实现。
- 不包含任何 Minecraft 游戏文件、资源或账号凭据。

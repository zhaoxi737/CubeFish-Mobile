# 方块鱼手机版 / CubeFish-Mobile

> 在 Android 手机上用 **方块鱼** 玩 **Minecraft: Java Edition**（含 Fabric / Forge 模组）。

本项目是 [Amethyst-Android](https://github.com/AngelAuraMC/Amethyst-Android)（[PojavLauncher](https://github.com/PojavLauncherTeam/PojavLauncher) 的活跃继任者）的 fork，
在其引擎之上做方块鱼品牌化，并计划接入方块鱼 AI 助手。

## 现状（开发中）

| 里程碑 | 状态 |
| --- | --- |
| M0 构建链打通（SDK 37 / NDK 27.3 / Gradle 9.6.1 / JRE8） | ✅ 已完成 |
| M1 品牌化（方块鱼名称 / 图标 / 包名 / ABI 裁剪） | ✅ 已完成 |
| M2 AI 助手（WebView 聊天 + 方块鱼 prompt + 读状态/日志/改内存/启动） | ✅ 基础版已完成 |
| M3 AI 工具（装模组 / 冲突修复 / 联网搜索） | ⏳ 计划中 |
| M4 签名 release + GitHub Release | ⏳ 计划中 |

### AI 助手（M2 基础版）

游戏主界面底部菜单里有 **「AI 助手（方块鱼）」** 入口：

- 聊天（DeepSeek 兼容接口，流式输出；Key 只存本机 `SharedPreferences`）
- 工具：`get_status`（设备/版本/内存/模组数）、`read_log`（读最近一次游戏日志，**发送前自动脱敏** UUID/路径/邮箱/IP/Key）、`set_memory`、`list_mods`、`launch_game`
- 还没有：装模组、修冲突、联网搜索（这些是桌面版已有的能力，M3 再搬）
- 代码位置：`app_pojavlauncher/src/main/java/dev/cubefish/mobile/ai/` + `app_pojavlauncher/src/main/assets/ai/`

## 构建

前置：JDK 21、**JDK 8**（Gradle 工具链需要，`MioLibPatcher`/`jre_lwjgl3glfw` 用 Java 8 编译）、
Android SDK（`platforms;android-37.0`、`build-tools;37.0.0`）、
NDK `27.3.13750724` 与 `28.2.13676358`（上游两个模块各要一个）。

```bash
git clone --recursive https://github.com/zhaoxi737/CubeFish-Mobile.git
cd CubeFish-Mobile

# 准备 Android 版 JRE8 产物（上游 CI 构建的 jre8-pojav）
#   Windows: powershell -File scripts/fetch-jre.ps1
#   解压到 app_pojavlauncher/src/main/assets/components/jre/

./gradlew :app_pojavlauncher:assembleDebug     # Windows: gradlew.bat
```

产物：`app_pojavlauncher/build/outputs/apk/debug/app_pojavlauncher-debug.apk`

> 本机实测：Windows 11 + JDK 21/8 + SDK 37.0 + NDK 27.3/28.2 可直接构建成功（约 3–8 分钟）。

## 支持范围

- CPU：`arm64-v8a`（推荐）/ `armeabi-v7a`
- 系统：Android 8.0+（引擎 minSdk 21，但「可玩」按 8.0+ 承诺）
- 内存：≥ 4 GB（建议 6 GB+）
- GPU：OpenGL ES 3.0+

## 授权与合规

- 本项目沿用上游 **GNU LGPL-3.0**（见 [LICENSE](LICENSE)），保留上游版权与署名（见 [NOTICE.md](NOTICE.md)）。
- 引擎目录不做魔改，方块鱼改动集中在品牌资源与新增 AI 模块，便于与上游同步。
- **不内置任何 Minecraft 游戏文件或资源**；登录使用微软正版账号（设备码流程），首次启动由用户自行下载版本。
- 方块鱼桌面版：<https://github.com/zhaoxi737/CubeFish>

## 上游

- Amethyst-Android：<https://github.com/AngelAuraMC/Amethyst-Android>（fork 基线 `v3_openjdk` @ `a743e28`）
- PojavLauncher：<https://github.com/PojavLauncherTeam/PojavLauncher>
- Boardwalk：<https://github.com/zhuowei/Boardwalk>

# MMTR (Multiplayer MTR)

基于 Minecraft Transit Railway（MIT）派生的客货运服务器模组项目。
- 目标 MC：1.20.4（Fabric）；基线：MTR 4.0.5 / TSC ce3a509082（= MTR 打包的 0.0.1 状态）
- 平台：Fabric 先行；mod id = mmtr；**资源命名空间与 Java 包 v1 保留 mtr / org.mtr.**（继承语义 + 资源包兼容）
- 玩法边界：MTR 方块/铁轨/乘客/景观/电子屏等全部保留；只改列车操控与玩法层（任务驱动、车底牵引制动、货运、连解挂、UI）
- 服务器形态：MMTR Engine 独立进程（fork TSC standalone）+ MC 端桥（选项 2：一开始就外置引擎）
- 授权：MIT（保留全部上游版权/许可声明）

## monorepo 结构

```text
mmtr/
├─ engine/   # TSC fork（分支 mmtr-baseline = ce3a509082）——仿真/任务/物理/货运
├─ game/     # MTR 4.0.5 fork —— Fabric mod（方块/渲染/乘客/UI/桥），mod id=mmtr
├─ tools/    # 第一方工具：obj-mtr-packager（OBJ -> MTR 资源包打包器）
├─ apps/     # 第一方旁路工程：creator-studio（原版 MTR 作者端，独立 Gradle build）
├─ scripts/  # 构建/运行/打包/自检脚本（含 check-paths.ps1 路径守卫）
├─ notes/    # 逐轮技术笔记与决策（01–，只增不改）
├─ docs/     # 本项目文档（入口 docs/README.md：00-历史 / 01-设计 / 02-运行与作业 / 03-交接与实机 / reference）
└─ mappings/ # (规划) Minecraft-Mappings / Mod-API-Tools 源码级参与（如需）
```

> 工作区级约定（工具链/第三方/资产/日志放哪、路径真源）见仓库上级目录的 `README.md`；
> 2026-09-09 的目录整理见上级目录的 `整理方案.md`。

## 里程碑速览
M0 派生跑通 → M0b 桥接原型(EngineBridge+带宽) → M1 车底与物理 → M2 任务引擎+AI+连解挂 → M3 货运 → M4 UI → M5 运营化。
入口：docs/README.md（文档地图）。早期里程碑（M0-M5 规划）见 docs/00-历史/03-MMTR-架构决策与里程碑.md（历史，已入库）。
最新交接：docs/03-交接与实机/MotionCore-Vehicle集成-交接.md。

## 本地开发速记
- JDK21 必需（engine 要求 toolchain 21，与上游 CI 一致）：`env\jdk-21`（Adoptium/Temurin，见上级 `env/README.md`）
- 所有脚本的路径真源：`. env\workspace.env.ps1` / `call env\workspace.env.bat`；禁止硬编码绝对路径
- engine 编译：cd engine && gradlew.bat classes
- engine jar 同步进 game/libs：`scripts\sync-engine.bat`
- game 编译：见 game/README 与 notes
- 提交前自检：`pwsh -File scripts\check-paths.ps1`

# deploy/ —— 部署与工作区重建

本目录是**部署信息**的唯一入口：说清这个仓库要跑起来需要什么、工作区该怎么摆、
构建 / 运行 / 打包 / 发布各走哪条命令。

> 本仓库（`mmtr/`）只是工作区里的**源码层**。工作区另外还有「工具链 / 第三方输入 / 创作源 / 产物」四层，
> 它们**故意不入库**（体积大、可重下、或与本仓库生命周期不同）。下面逐层说明它们从哪来。

| 文件 | 内容 |
|---|---|
| `README.md`（本文） | 仓库边界、工作区重建、被排除项如何重取 |
| `BUILD.md` | 命令级：构建 / 运行 / 调试 / 打包 / 发布 |
| `workspace-template/` | 可直接拷到新机工作区根的骨架（`README.md`、`整理方案.md`、`env/`、`bin/`） |
| `layers/` | 工作区各层（`vendor/` `assets/` `artifacts/` `logs/` `sandbox/` `instances/` `bin/`）的用途与规矩 |

## 0. 一句话上手

```
git clone <本仓库> mmtr  →  按 §2 摆好工作区  →  mmtr\scripts\sync-engine.bat
                        →  cd mmtr\game && gradlew.bat :fabric:build
                        →  game\fabric\build\libs\fabric-4.0.5.jar 就是可发布的模组
```

## 1. 仓库边界：什么入库，什么不入库

| 入库（本仓库） | 不入库（故意排除，见 §3 重取方式） |
|---|---|
| `engine/` —— TSC fork 源码（仿真 / 任务 / 物理 / 货运） | `env\` —— JDK 21、JDK 17、IntelliJ IDEA、Blockbench、MagicaVoxel、Mineways |
| `game/` —— MTR 4.0.5 fork 源码（`fabric/` + `forge/`） | `vendor\` —— MTR 4.0.5 jar、fabric-api jar、上游 MTR 源码 clone |
| `tools/` `apps/` —— 第一方工具（OBJ 打包器等）与 creator-studio | `assets\` —— Blender / OBJ 创作源（**不可再生**，单独备份） |
| `scripts/` —— 构建 / 运行 / 打包 / 路径自检 | `artifacts\` `logs\` `sandbox\` `instances\` —— 产物、日志、一次性探针、运行实例 |
| `docs/` `notes/` —— 设计与逐轮决策记录 | 一切 `build/` `.gradle/` `node_modules/` `run/` —— 构建可重建 |
| `deploy/`（本目录） | IDE 工程文件：`.idea/` `*.iml` `*.ipr` `*.iws` `/.vscode/` |

判断标准只有一条：**能用一条命令重新获得的东西，不进版本控制。**
`mmtr\.gitignore` 负责强制这一点；工具链与第三方输入则靠"它们本来就在仓库之外"来保证。

## 2. 摆出一个能构建的工作区

本仓库的脚本假定自己位于工作区的 `mmtr\` 下（`scripts\*.ps1` 会向上找 `..\..\env\workspace.env.*`）。
最省事的摆法：

```
<工作区根>\
├─ mmtr\                     ← 本仓库，git clone 到这里
├─ env\                      ← §3.1：拷 workspace-template\env\ + 放 JDK / IDEA
├─ bin\                      ← §3.1：拷 workspace-template\bin\
├─ vendor\mods\              ← §3.2：MTR 4.0.5 + fabric-api 的 jar
├─ vendor\upstream\MTR-4.0.5\← §3.2：（可选）上游源码 clone，用于比对
├─ assets\models\            ← §3.3：（可选）自有创作源，从备份恢复
├─ artifacts\  logs\  sandbox\  instances\   ← 空目录即可，按需使用
├─ README.md                 ← 拷 workspace-template\README.md（工作区地图）
└─ 整理方案.md                ← 拷 workspace-template\整理方案.md（布局决策记录）
```

骨架文件都在 `workspace-template/`，**相对路径是按"放在工作区根"写死的**，直接整体拷过去即可：

```powershell
Copy-Item -Recurse mmtr\deploy\workspace-template\* <工作区根>\
```

`env\workspace.env.ps1` / `workspace.env.bat` 是**全工作区路径的唯一真源**：
所有脚本都 dot-source / call 它来拿 `MC_ROOT` / `JDK21` / `MODS` / `PACKAGER`，
因此工作区可以整体搬到任何路径、任何盘，不需要改任何脚本。

**不要**把 `<工作区根>` 本身变成 git 仓库 —— 只有 `mmtr\` 是仓库。

## 3. 被排除的东西从哪来

### 3.1 工具链（`env\`）

| 目录 | 版本 | 来源 |
|---|---|---|
| `env\jdk-21\` | Adoptium Temurin 21.0.12.1+1 | Adoptium（**必需**：engine 的 toolchain 与 game 都要求 21） |
| `env\jdk-17\` | Adoptium Temurin 17.0.20.1+1 | Adoptium（部分工具链 / 旧依赖） |
| `env\idea\` | IntelliJ IDEA Community 2025.2.6（自带 jbr） | JetBrains（可选：也可以用你已有的 IDEA） |
| `env\blockbench\` | Blockbench 5.1.6 portable | blockbench.net（建模，1 格 = 16 单位） |
| `env\magica-voxel\` | MagicaVoxel 0.99.7.2 | ephtracy.github.io |
| `env\mineways\` | Mineways | mineways.com（MC 存档 → 3D 模型） |

`bin\` 下的启动器直接指向 `env\idea\bin\idea64.exe` 与 `env\magica-voxel\...`；
只要改 `env\workspace.env.*` 里的变量，换版本/换路径不用动脚本。

> 只想要命令行构建的话，**只有 JDK 21 是硬需求**，IDEA / 建模工具都可以不装。

### 3.2 第三方只读输入（`vendor\`）

| 路径 | 来源 |
|---|---|
| `vendor\mods\MTR-fabric-4.0.5+1.20.4.jar` | Modrinth `minecraft-transit-railway`（4.0.5 / 1.20.4 Fabric） |
| `vendor\mods\MTR-forge-4.0.5+1.20.4.jar` | 同上（Forge 版） |
| `vendor\mods\fabric-api-0.97.3+1.20.4.jar` | Modrinth `fabric-api` |
| `vendor\upstream\MTR-4.0.5\` | `git clone https://github.com/Minecraft-Transit-Railway/Minecraft-Transit-Railway` @ `739dba44`（可选，用于比对/自编译） |

**只读，不要在这里改代码** —— 派生实现全在 `mmtr\engine` 与 `mmtr\game`。
`apps\creator-studio`（原版 MTR 作者端）需要 `MTR-fabric-4.0.5+1.20.4.jar`。

### 3.3 创作源（`assets\`，不可再生）

`assets\models\blender\` 下的 Blender 工程、导出的 OBJ/MTL/贴图是**自有创作源**，
无法用命令重新生成 —— 它们只进备份、不进 git（单文件 15 MB 级）。
打包器配置里用 `${MC_ROOT}/assets/models/blender/...` 占位符引用它们。
说明见 `layers\assets.md`。**没有这一层一样能构建，只是不能重新打包车辆资源包。**

## 4. 硬规矩

1. **不准写机器相关的绝对路径。** 需要定位工具 / 第三方 / 资产时一律用
   `env\workspace.env.*` 导出的变量；打包器 JSON 用 `${MC_ROOT}` 占位符。
   由 `mmtr\scripts\check-paths.ps1` 强制检查（有命中即退出码 1）。
2. **日志不要落在仓库里。** 写 `<工作区根>\logs\<yyyy-MM>\`；`mmtr\.gitignore` 的 `*.log`
   会让仓库内的日志永远进不了版本控制。
3. **可再生目录不要手动编辑**：`**/build/`、`.gradle/`、`game/forge/src/main/resources/`
   （由 `buildSrc` 从 fabric 生成）、`node_modules/`、`run/`。

## 5. 下一步

构建 / 运行 / 打包 / 发布的具体命令见 **[`BUILD.md`](BUILD.md)**。
项目自身的架构与设计见 `../docs/README.md`；逐轮技术笔记见 `../notes/`。

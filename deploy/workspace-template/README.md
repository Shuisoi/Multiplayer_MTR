# MC 工作区（MMTR 开发环境）

> 目标：Minecraft 1.20.4 · Fabric · 基于 MTR 4.0.5 派生的 MMTR 客货运模组。
> 真正的项目源码在 **`mmtr/`**（唯一的 git 仓库）；本目录其余部分只是「工具 / 第三方输入 / 资产 / 产物」。
> 整理记录与完整方案见 [`整理方案.md`](整理方案.md)。

## 三层边界（决定一件事该放哪）

| 判断 | 放哪 | 版本控制 | 备份 |
|---|---|---|---|
| 能用一条命令重新获得？ | `env/`、`vendor/`、`artifacts/`、`logs/`、`sandbox/`、`instances/` | ❌ | ❌ |
| 自己写的代码/配置？ | **`mmtr/`**（含 `mmtr/tools/`、`mmtr/apps/`） | ✅ git | ✅ |
| 无法再生、但太大不适合进 git？ | `assets/` | ❌（只入库清单） | ✅ |

## 目录

| 路径 | 内容 | 能删吗 |
|---|---|---|
| `mmtr/` | ★ 唯一源码仓库：`engine/`（TSC fork）+ `game/`（MTR fork）+ `docs/` `notes/` `scripts/` `tools/obj-mtr-packager/` `apps/creator-studio/` | ❌ |
| `env/` | 可重装工具链：`jdk-21/` `jdk-17/` `idea/` `blockbench/` `magica-voxel/` `mineways/` | ✅ 重下即可 |
| `vendor/` | 第三方只读输入：`mods/`（MTR 4.0.5 fabric/forge + fabric-api）、`upstream/MTR-4.0.5/`（上游 git clone） | ✅ 重取即可 |
| `assets/` | 自有创作源：`models/`（Blender 源 + OBJ 导出 + 贴图） | ❌ **不可再生** |
| `artifacts/` | 产物：`exports/`（导出版 zip）、`packs/`（资源包归档） | ✅ |
| `logs/` | 按年月归档的构建/实机日志（`logs/2026-09/`） | ✅ |
| `sandbox/` | 一次性探针脚本，用完即弃 | ✅ |
| `instances/` | MC 运行实例：`create-export-1.21.1/`（Create 导出版，离线启动） | ✅ 存档另算 |
| `bin/` | 一键入口 `.bat`（见下） | ❌ |

## 一键入口

| 脚本 | 作用 |
|---|---|
| `bin\打开-Engine-工程.bat` | 用 `env\idea` 打开 `mmtr\engine` |
| `bin\打开-Game-工程.bat` | 用 `env\idea` 打开 `mmtr\game` |
| `bin\打开-Creator作者端.bat` | 启动原版 MTR 4.0.5 作者端（内置网页版 Resource Pack Creator） |
| `bin\启动-MagicaVoxel.bat` | 启动 MagicaVoxel |

## 常用命令

```bat
:: 引擎编译 + 同步 jar 到 game/libs
mmtr\scripts\sync-engine.bat

:: 起 dev 服务端（Fabric）
mmtr\game\run-server.bat

:: 打包一台车（OBJ -> 校验过的资源包 zip）
mmtr\tools\obj-mtr-packager\pack.bat mmtr\tools\obj-mtr-packager\example\vehicle.hst_h.json -Version 13

:: 路径守卫：提交前自检（禁止硬编码绝对路径）
pwsh -File mmtr\scripts\check-paths.ps1
```

## 硬规矩

1. **工作区里任何脚本/配置都不准写机器相关的绝对路径（尤其是用户目录下的字面路径）。**
   - PowerShell：`. env\workspace.env.ps1` 取 `$MC_ROOT` / `$JDK21` / `$PACKAGER` 等变量；
   - batch：`call env\workspace.env.bat`；
   - 打包器 JSON 配置：用 `${MC_ROOT}` 占位符（`mmtr\tools\obj-mtr-packager\paths.js` 解析）。
   - 由 `mmtr\scripts\check-paths.ps1` 强制检查（有硬编码即退出码 1）。
2. **日志不要落在仓库里**：写 `logs\<yyyy-MM>\`。`mmtr/.gitignore` 里的 `*.log` 会让日志永远无法入库。
3. **可再生目录不要手动编辑**：`mmtr/**/build/`、`.gradle/`、`game/forge/src/main/resources/`（由 `buildSrc` 从 fabric 生成）、`node_modules/`。

## 上游与许可

MTR 与 TSC 均为 MIT；派生代码保留全部上游版权与许可声明（见 `mmtr/README.md` 与各工程 `LICENSE`）。

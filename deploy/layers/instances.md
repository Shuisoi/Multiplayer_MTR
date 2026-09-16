# instances/ —— MC 运行实例（可删，存档另算）

| 目录 | 内容 | 启动 |
|---|---|---|
| `create-export-1.21.1/` | Create 汽鸣铁道导出版（Minecraft 1.21.1 · NeoForge 21.1.244）——与 MMTR 的 1.20.4 Fabric 主线**无关**，用于对照/取材 | 双击 `启动-离线游戏.bat`，或跑 `启动-离线游戏.ps1` |

## 注意

- 启动脚本用 `$PSScriptRoot\.minecraft` 定位实例，因此**整目录可以随意移动/改名**；Java 用的是用户级 `%USERPROFILE%\.jdks\jdk-21.0.12.1+1`，与 `env\jdk-21` 无关。
- 本目录不入版本控制；里面的 `saves/`（存档）如果要长期保留，请单独备份。
- 新增实例时：`instances/<名称>/`，并在本表加一行。

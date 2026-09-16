# env/ —— 可重装工具链

本目录只放「坏了/删了都能重新下载」的工具。**不入版本控制，不进备份。**

| 目录 | 版本 | 来源 | 用途 |
|---|---|---|---|
| `jdk-21/` | Adoptium Temurin 21.0.12.1+1 | Adoptium | engine 与 game 的构建/运行（toolchain 21，与上游 CI 一致） |
| `jdk-17/` | Adoptium Temurin 17.0.20.1+1 | Adoptium | 部分工具链/旧版依赖 |
| `idea/` | IntelliJ IDEA Community 2025.2.6（自带 jbr） | JetBrains | 两个 Gradle 工程各开一个窗口 |
| `blockbench/` | Blockbench 5.1.6 portable | blockbench.net | 建模（Modded Entity；1 格 = 16 单位） |
| `magica-voxel/` | MagicaVoxel 0.99.7.2 | ephtracy.github.io | 体素建模 |
| `mineways/` | Mineways | mineways.com | MC 存档 → 3D 模型导出 |

## 路径真源

本目录的 `workspace.env.ps1` / `workspace.env.bat` 是**全工作区路径的唯一真源**：

```powershell
# PowerShell
. "$PSScriptRoot\..\env\workspace.env.ps1"   # 从 mmtr\scripts 下这样引用
$env:JAVA_HOME   # 已指向 env\jdk-21
```

```bat
:: batch
call "%~dp0..\..\env\workspace.env.bat"
```

新增工具时：放到本目录 → 在 `workspace.env.*` 里加变量 → 在 `bin\` 里加入口脚本 → 更新本表。

> 注：`jdk-21` 与用户级 `%USERPROFILE%\.jdks\jdk-21...` 是两份独立安装；脚本一律用工作区内的 `env\jdk-21`，避免依赖某台机器的用户目录。

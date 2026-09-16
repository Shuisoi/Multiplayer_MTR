# bin/ —— 一键入口

所有脚本用 `%~dp0..` 定位工作区根，**不含任何机器相关绝对路径**。

| 脚本 | 作用 |
|---|---|
| `打开-Engine-工程.bat` | `env\idea\bin\idea64.exe` 打开 `mmtr\engine` |
| `打开-Game-工程.bat` | `env\idea\bin\idea64.exe` 打开 `mmtr\game` |
| `打开-Creator作者端.bat` | 转发到 `mmtr\apps\creator-studio\start-creator-studio.bat`（原版 MTR 4.0.5 作者端） |
| `启动-MagicaVoxel.bat` | `env\magica-voxel\...\MagicaVoxel.exe` |

## 规矩

- 新增工具入口放这里，不要散落到工作区根目录。
- 需要 JDK 的脚本先 `call "%~dp0..\env\workspace.env.bat"`，不要自己写 `JAVA_HOME`。
- 改完跑一次 `pwsh -File mmtr\scripts\check-paths.ps1`。

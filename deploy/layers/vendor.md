# vendor/ —— 第三方只读输入

上游产物，只读，**不入版本控制**。删了按下面的来源重新获取即可。

| 路径 | 内容 | 来源 |
|---|---|---|
| `mods/MTR-fabric-4.0.5+1.20.4.jar` | MTR Fabric 版（Creator 作者端依赖它） | Modrinth `minecraft-transit-railway` (XKPAmI6u) |
| `mods/MTR-forge-4.0.5+1.20.4.jar` | MTR Forge 版 | 同上 |
| `mods/fabric-api-0.97.3+1.20.4.jar` | Fabric 必需依赖 | Modrinth `fabric-api` |
| `upstream/MTR-4.0.5/` | 上游 MTR 4.0.5 源码（git clone，含历史，用于比对与自编译） | github.com/Minecraft-Transit-Railway/Minecraft-Transit-Railway @ 739dba44 |

## 注意

- **不要在这里改代码**。派生实现全部在 `mmtr/game`（MTR fork）与 `mmtr/engine`（TSC fork）。
- 需要看上游某个版本时，`git -C vendor\upstream\MTR-4.0.5 log` 即可，不必再解压一份 zip（2026-09-09 已删除冗余的 `Minecraft-Transit-Railway-master/` 解压副本与 `mtr-master-src.zip`）。
- MTR 官方 JSON Schema 与示例资源包已入库到 `mmtr/docs/reference/`（体积小、文档要引用）。

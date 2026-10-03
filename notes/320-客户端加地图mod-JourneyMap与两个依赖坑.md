# 320 · 给客户端加地图 mod（JourneyMap）—— 连踩两个"依赖装在 jar 里"的坑

> 用户口径（2026-09-26）：**「给我客户端加个地图mod，用于快速传送什么的」**。

## 1. 结论

**装了 JourneyMap 5.10.0（Fabric 1.20.4），只给 dev 客户端**，用途是航点 + **航点传送**（走 `/tp`）。

改动只有 `mmtr/game/fabric/build.gradle` 两行 + `vendor/mods/` 一个抽取出来的 jar：

```groovy
modLocalRuntime "maven.modrinth:journeymap:1.20.4-5.10.0-fabric"
modLocalRuntime files("${rootDir}/../../vendor/mods/journeymap-api-1.20.4-1.9-fabric-SNAPSHOT.jar")
```

**实测**：客户端 `Loading 62 mods`（原来是 60），JourneyMap 的
`Initializing ServerSide/ClientSide Packet Registries` 都打出来了，`Sound engine started`，
**无 crash report**。

## 2. ★ 坑一：Xaero's 根本装不上（先试的是它）

按常规先选了更常见的 **Xaero's Minimap + World Map**，`dependencies` 解析、jar 下载全都成功，
**但客户端起不来**：

```
Mod resolution failed
  xaerominimap 26.5.0   {depends xaerolib @ [>=1.0]}
  xaeroworldmap 1.46.0  {depends xaerolib @ [>=1.0]}
Fix: add xaerolib 1.0
```

查下来：

| 查了什么 | 结果 |
| --- | --- |
| Modrinth 的 `dependencies` 元数据 | 只写 `fabric-api:required` + 一个 optional —— **完全没提 xaerolib** |
| 拆开 jar 看 `fabric.mod.json` | `"xaerolib": ">=1.0"` —— **硬依赖** |
| jar 里有没有内嵌（`META-INF/jars/`） | **没有** |
| Modrinth 上有 `xaerolib` 吗 | **有项目，但只有 Forge 版**（1.12.2 / 1.16.5），**没有 Fabric 版** |

⇒ **Fabric 侧凑不齐这条依赖链**。要装 Xaero's 得另找 xaerolib 的 Fabric 包
（作者官网/CurseForge），再走 `vendor/mods + fabric.addMods` 那条路 —— 不值当，换 JourneyMap。

> **教训**：**Modrinth 的 `dependencies` 字段不可信**，真正算数的是 jar 里的 `fabric.mod.json`。
> 而且这类"缺依赖"只有**启动一次**才会暴露（`dependencies` 任务照样 BUILD SUCCESSFUL）。

## 3. ★ 坑二：JourneyMap 自己也有"内嵌 jar"的坑

换成 JourneyMap 后**又崩了一次**：

```
java.lang.RuntimeException: Could not execute entrypoint stage 'client' due to errors,
    provided by 'journeymap' at 'journeymap.client.JourneymapClient'
Caused by: java.lang.NoClassDefFoundError: journeymap/client/api/IClientAPI
```

拆 jar：

```
META-INF/jars/journeymap-api-1.20.4-1.9-fabric-SNAPSHOT.jar      ← API 内嵌在这里
fabric.mod.json: "jars": [{"file": "META-INF/jars/journeymap-api-…"}]
```

而 **Loom dev 不展开 classpath mod 的嵌套 jar**（`fabric-loom#1275`）——
**这正是本仓库早就踩过、并写在 `build.gradle` 里给 Iris 那三个库的同一个坑**
（`jcpp` / `antlr4-runtime` / `glsl-transformer`）。

**修法（照 Iris 的先例）**：把内嵌 jar 抽出来单独加载。

```powershell
$jar = Get-ChildItem "$env:USERPROFILE\.gradle\caches\modules-2\files-2.1\maven.modrinth\journeymap" -Recurse -Filter "*.jar" | Select-Object -First 1
$tmp = "$env:TEMP\jm_extract"; New-Item -ItemType Directory -Force $tmp | Out-Null
Push-Location $tmp; jar xf $jar.FullName "META-INF/jars/journeymap-api-1.20.4-1.9-fabric-SNAPSHOT.jar"; Pop-Location
Copy-Item "$tmp\META-INF\jars\journeymap-api-1.20.4-1.9-fabric-SNAPSHOT.jar" vendor\mods\ -Force
```

那个抽出来的 jar **自带 `fabric.mod.json`**（是个 mod，不是纯库）
⇒ 用 `modLocalRuntime`，**不是** `runtimeOnly`。（Iris 那三个是纯库，所以当时用 `runtimeOnly`。）

## 4. 传送的前提：op

JourneyMap 的航点传送是**发 `/tp`**，所以要 op。
本机 `server.properties` 是 `op-permission-level=4`，而 **`Shuisoi` 在 `ops.json` 里是 level 4** ✔
⇒ 点击航点的「Teleport」可以直接用。

（`ops.json` 里有 264 条，其中 263 个是 `Player###` 这种批量生成的占位号，与本次无关。）

## 5. 验收（都留了证据）

| 判据 | 实测 |
| --- | --- |
| 依赖解析 | `maven.modrinth:journeymap:1.20.4-5.10.0-fabric` 出现在 `modRuntimeClasspath`，jar 落到 Gradle 缓存 ✔ |
| 游戏端装载 | `Loading 62 mods`（原 60）：`journeymap 5.10.0` + `journeymap-api-fabric 1.20.4-1.9-fabric-SNAPSHOT` ✔ |
| JourneyMap 初始化 | `(journeymap) Initializing ServerSide Packet Registries` / `ClientSide …` ✔ |
| 进入主菜单 | `Sound engine started` ✔ |
| 无崩溃 | 最新 crash-report **仍是 18:21:30 那次**（Xaero's 的），本轮启动没有新增 ✔ |
| 落盘 | `run/journeymap/` 已生成 ✔ |
| 进程 | server PID 36528 / client PID 40256 都在跑 ✔ |

> **过程中我自己制造过一个假信号**：轮询日志时用 `LWJGL` 当"起来了"的判据，
> 结果**崩溃日志里也有这一行**，于是把一次崩溃误报成"已起来"。
> 教训：**判"起没起来"要看 `Sound engine started` / crash-report，不要看泛泛的库版本行**。

## 6. 怎么用

1. 进游戏后按 **M**（或小键盘）开全屏地图，**J** 开设置/航点。
2. 站在要点的地方 → 新建航点（可命名，例如"下水站"、"莫氏岛站"）。
3. 右键航点 → **Teleport** → 直接 `/tp` 过去（需 op）。

> 建议把台账 `mmtr/docs/02-运行与作业/测试地图-站名与坐标.md` 里那几座站的坐标
> 建成航点，以后跑线路直接跳。

## 7. 下一轮

* 若还想装 Xaero's：得先弄到 **Fabric 版 xaerolib**，再走 `vendor/mods + fabric.addMods`（客户端侧）。
* 客户端现在起得来、服务端也在跑；**"差不多了"那几项收尾**（批量改造车站、隧道照明、
  `gen_line_signals.py` 支持爬坡）见 notes/319 §6。

---

## 8. 更正（2026-09-26 晚，详见 notes/321 §5）

**§1 写的「只给 dev 客户端」是错的。** 后来查服务端自己的日志（`Starting minecraft server on *:25565`、
`Preparing level "world"`，确认那份 `latest.log` 归属服务端），它的 `Loading 47 mods:` 清单里
**有 `- journeymap 5.10.0`** ⇒ `modLocalRuntime` **不是**客户端专属，dev 服务端也加载了它
（无报错、无 crash，属于无害但不符合"按端注入"的惯例）。
要真按端隔离，得照 WorldEdit 的做法：jar 放 `vendor/mods/` + `runConfigs.client { property "fabric.addMods", … }`。

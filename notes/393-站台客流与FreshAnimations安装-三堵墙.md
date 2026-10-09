# 393 站台客流（村民实体）+ Fresh Animations 安装：三堵墙与绕法

> 用户口径（2026-10-05）：「我不想站台空旷……每个车站的站台有客量 0-100% 属性」→
> 「村民用原版模型就行了」→「还是需要点动态的……fresh animations 那个」→
> 「我需要减少实体数量防止客户端卡顿，不然村民起步就是大概 2-300 只在一个站台上」。

功能本身的设计与实机验证见 `docs/01-设计/站台客流-村民实体与客量属性-设计.md`。
本篇只记**过程里踩到的三堵墙**，因为它们都会以"看起来像代码坏了"的形式出现。

## 0 结论速查

| 症状 | 真因 | 绕法 |
|---|---|---|
| 客户端启动崩：`the jar file ...\.fabric\processedMods\entity_model_features-*.jar can't be written` | 从 **IDEA** 启动 ⇒ JVM 是**工作区内镜像的低完整性令牌** ⇒ Fabric Loader 的**运行时 remap** 写不了那个目录（该目录 ACL 还带 `Everyone: DeleteSubdirectoriesAndFiles = Deny`，而 zipfs 建文件要走"临时文件→原子替换"） | 用 **`run-client.bat`**（工作区外 JDK = High）；或改走 loom 构建期 remap（见下一条） |
| 构建崩：`Failed to setup Minecraft, java.nio.file.ReadOnlyFileSystemException: null` | 同上一条：**从 IDEA 跑 gradle**（notes/349 已记过） | 用 `.bat` 启动器；**不要在 IDEA 里跑 gradle** |
| 构建崩：`IllegalStateException: Mod was built with a newer version of Loom (1.15.48), you are using Loom (1.10.5)` | MTR 4.0.5 工程钉 Loom **1.10.5**，而 Modrinth 上最新的 EMF/ETF 是新 Loom 构建的；loom 拒绝用旧版本来 remap | 挑 **Loom ≤ 1.10.5** 的版本（见 §2 的筛法），或升级本工程 loom（另一件事） |
| loom 缓存进入"重建中"、之后所有构建都失败 | 我为了换依赖 **kill 了正在重建缓存的守护进程** ⇒ 锁被标记 `ACQUIRED_PREVIOUS_OWNER_DISOWNED` ⇒ 下一个构建要**重建整份缓存**（几分钟到十几分钟） | 别打断构建；打断后让下一个构建把它跑完（用工作区外 JDK） |

## 1 第一堵墙：`fabric.addMods` 这条路在这台机器上不可用

原本想照 WorldEdit 的做法（`vendor/mods/*.jar` + run config 的 `fabric.addMods`），好处是**不经过 loom 的 mod remap**，
理论上有两个好处：服务端在跑时也能改、不用等构建。实测在**客户端**上撞墙：

```
java.io.IOException: the jar file ...\run\.fabric\processedMods\entity_model_features-3.0.17-64b5720b4b825f21.jar can't be written
  at net.fabricmc.loader.impl.lib.tinyremapper.OutputConsumerPath.<init>(OutputConsumerPath.java:102)
  at net.fabricmc.loader.impl.discovery.RuntimeModRemapper.remap(RuntimeModRemapper.java:161)
```

- 那行 `can't be written` 不是"目录只读"，是 tinyremapper 里 **jdk zipfs 建文件失败**被包了一层；
- 该目录 ACL：`Everyone: DeleteSubdirectoriesAndFiles = Deny` + 两个沙箱身份 `S-1-4-…`；
- 对照实验：普通（High）会话往同一目录写文件**成功** ⇒ 目录本身可写；
- 真正变量是**谁在跑**：`run-client.bat`（工作区外 JDK，High）没试过，用户是**从 IDEA 启动**的（Low）——
  服务端那边 11:28 成功 remap 过 WorldEdit（它走的是 `run-server.bat`/`mc-server.ps1`，High）。

⇒ **结论：能用 bat 启动器就别用 IDEA**；要么改走 loom 构建期 remap（第二堵墙就是它的代价）。

## 2 第二堵墙：modLocalRuntime 的 Loom 版本闸

改走 `modLocalRuntime` 后，构建在**配置阶段**就失败：

```
> Failed to setup Minecraft, java.lang.IllegalStateException:
  Mod was built with a newer version of Loom (1.15.48), you are using Loom (1.10.5)
```

这个戳在 jar 的 `META-INF/MANIFEST.MF` 里：`Fabric-Loom-Version: 1.15.48` ⇒ **不用反复试构建，离线就能筛**：
把候选版本下下来读这一行即可。实测（1.20.4 fabric，2026-10-05 查得）：

| 版本 | 发布 | Loom | 能否用 |
|---|---|---|---|
| EMF 3.0.17 / 3.0.16 / 3.0.15 | 2026-03 | 1.15.48 | ✗ |
| EMF 3.0.12 / 3.0.11 | 2026-02 | 1.13.44 | ✗ |
| EMF 3.0.10 / 3.0.9 | 2025-12 | 1.11.38 | ✗ |
| **EMF 3.0.7** | **2025-11-25** | **1.9.32** | ✓ |
| ETF 7.0.13 | 2026-03 | 1.15.48 | ✗ |
| ETF 7.0.9 | 2026-02 | 1.13.44 | ✗ |
| ETF 7.0.8 | 2025-12 | 1.11.38 | ✗ |
| **ETF 7.0.6** | **2025-11-22** | **1.9.32** | ✓ |

配套资源包也用**同期**的：**Fresh Animations 1.10.2**（2025-11-22；1.10.3/1.10.4 是 2025-12/2026-02 的）。
依赖校验：EMF 3.0.7 的 `fabric.mod.json` 写死 `entity_texture_features>=7.0.0` ⇒ ETF 7.0.6 满足 ✓；
两个都是 `environment=client` ⇒ 只注入客户端。

筛法（可复跑）：`api.modrinth.com/v2/project/<slug>/version` → 取 `game_versions` 含 1.20.4、`loaders` 含 fabric →
下 jar → 读 `META-INF/MANIFEST.MF` 的 `Fabric-Loom-Version`。

## 3 第三堵墙：缓存重建不能被"两边同时跑"

我 kill 掉一个正在重建缓存的守护进程之后，loom 的锁文件变成 `ACQUIRED_PREVIOUS_OWNER_DISOWNED`，
下一个构建会打印 `Found existing cache lock file ... rebuilding loom cache` ——
**期间任何一边（我的构建 / 用户的 IDEA）再进来都会失败**，而且看起来像"代码又坏了"。

⇒ 规矩：**同一时刻只允许一个构建者**；打断之后让下一个构建安静跑完（工作区外 JDK）。
排查手段（都不需要等构建）：

```powershell
# 谁在跑
Get-Process java | % { $c=(Get-CimInstance Win32_Process -Filter "ProcessId=$($_.Id)").CommandLine; "$($_.Id) $(if($c -match 'GradleDaemon'){'daemon'}elseif($c -match 'Knot'){'mc'}else{'?'})" }
# 缓存是否可用
Test-Path sandbox\gradle-home\caches\fabric-loom\1.20.4\*\mappings.jar
# 残留锁
Get-ChildItem sandbox\gradle-home\caches\fabric-loom -Recurse -Filter *.lock
```

## 4 顺手记下的两条与"卡不卡"有关的实测（结论与直觉相反）

| 问题 | 实测 | 结论 |
|---|---|---|
| 几百只 NoAI 村民，服务端吃不吃？ | `/debug start|stop` 读 TPS：0/100/300/500 只 = 20.02 / 20.03 / 20.02 / 20.03 | ≤500 只**服务端无感** |
| 客户端呢？ | 同位置原地不动，`上水村 0%`（0 只）↔ `100%`（95 只）**帧数没区别** | 95 只量不出来；110→30 的落差是**车站方块 + 光影**本身的账 |
| 那闸门为什么还要？ | 一次性 summon **5951** 只 ⇒ 服务端 `Can't keep up! ... 289 ticks behind` | 闸门是给**病态输入**兜底的（默认 48/站台） |

⇒ 教训：**"某个新东西吃不吃帧"必须在同位置、镜头不动、只切它一项的条件下 A/B**，
否则很容易把车站本体/光影的账算到新功能头上（这次就先被算错过一次）。

# 321 · 固定 dev 客户端的玩家名 —— 顺带挖出「op 是按 UUID 键的」这个坑

> 用户口径（2026-09-26）：**「然后把客户端的玩家固定一下吧，每次退出重进都会换个ID，导致位置有变化」**。

## 1. 根因

Loom **不给 dev 客户端传 `--username`**，Minecraft 就退回默认值 `Player` + 随机三位数。
本服是 `online-mode=false` ⇒ 玩家 UUID 由**名字**推出（`OfflinePlayer:<name>` 的 MD5/v3）
⇒ 名字随机 = **每次进服都是"新玩家"**：新 playerdata（位置/背包/进度全丢），`ops.json` 一路堆。

改名前的实测证据：

* 客户端日志：`Setting user: Player497`
* `run/world/playerdata/` 里 6 个 UUID **全部**能反查成 `Player###`
  （暴力枚举 `Player0`…`Player999` 做离线 UUID，**6/6 命中**）
  —— 这条反查同时**证实了服务器确实用离线 UUID 推导**，是后面判断 op 的基准。

反查结果：`Player483 / Player580 / Player569 / Player444 / Player214 / Player81`。

## 2. 一共改了三处，少一处都不成

| # | 文件 | 改什么 | 管哪条启动路径 |
| --- | --- | --- | --- |
| 1 | `mmtr/game/fabric/build.gradle` | `runConfigs.configureEach { if (name == "client") programArgs "--username", "Shuisoi" }` | Gradle / `dev-client.ps1` |
| 2 | `mmtr/game/.idea/runConfigurations/Minecraft_Client___fabric__fabric.xml` | `PROGRAM_PARAMETERS` = `--username Shuisoi` | IntelliJ IDEA 启动 |
| 3 | `run/ops.json` | 补一条**离线 UUID** `aeaca7ad-6c06-3b06-a33e-d92460e7b7ca` 的 `Shuisoi`（level 4） | 进服后**是不是 op** |

另外把**最近一次真实会话的存档迁到新 UUID**，这样第一次用新名字进服也不用重新找位置：

* `f20c75ef…`（Player81，末次位置 `[2299.6, 76.0, 1812.8]` —— 正是**莫氏岛站**）
  → `aeaca7ad-6c06-3b06-a33e-d92460e7b7ca`
* 原目录整份备份在 `run/playerdata-backup-20260926-182746/`。

**实测**：重启客户端后 `Setting user: Shuisoi`（18:28:27）✓

## 3. ★ 坑一：`op Shuisoi` 靠不住 —— 离线服也会去查 Mojang

想当然的做法是"名字固定了，进服 `op Shuisoi` 一下就行"。**不行**：

* 在离线服上执行 `op Shuisoi`，回的是 `Made Shuisoi a server operator`，
  但写进 `ops.json` 的 uuid 是 **`b6eeaf45-9a5b-4e4a-9d88-6a732742ae6c`**（**正版** UUID）。
  服务器为**指令目标名**做了 Mojang 查询，跟本服 `online-mode=false` 无关。
* 于是复查会被自己骗过去：再执行 `op Shuisoi` 得到
  `Nothing changed. The player already is an operator` —— 看着"已经是 op了"，
  其实命中的是**正版那条**；而离线进服时服务器给的是**另一条** UUID。
* ★ **判据（怎么知道 op 是按 UUID 还是按名字键的）**：
  让 `ops.json` 里同时存在两条同名 `Shuisoi`（正版 + 离线），
  再 `deop Player81` 逼服务器保存一次列表 —— 保存后**两条都还在**（264 条，Shuisoi ×2）。
  若按**名字**做键，`Map` 会在保存时合并成 1 条；两条并存 ⇒ **列表按 UUID 做键**。
* 所以第 3 处改动是**必需的**：只固定名字的话，进服依然不是 op，
  而 JourneyMap 的航点传送走 `/tp`，非 op 直接失败（用户装地图 mod 就是为了这个）。

## 4. ★ 坑二：Loom 的 `programArgs` 既不进 IDE 配置、也不进 `launch.cfg`

* `.gradle/loom-cache/launch.cfg`（`:fabric:ideaSyncTask` 会重写它）里 `clientArgs`
  **只有 `--assetIndex` / `--assetsDir`，没有 `--username`**
  ⇒ `programArgs` 只进 Gradle 那次 JavaExec 的参数；IDE 那份配置不带就永远拿不到。
* `:fabric:ideaSyncTask` 跑成功（BUILD SUCCESSFUL in 17s），
  但 **`.idea/runConfigurations/*.xml` 根本没被重写**（mtime 还停在 09-05）
  ⇒ 只能手工补 `PROGRAM_PARAMETERS`。
* **风险**：将来在 IDEA 里做一次 Gradle 同步 / 重新生成运行配置，这条手工改动可能被覆盖。
  被覆盖后的症状就是又变回 `Player###`，按 §1 的日志判据一眼可辨。

## 5. ★ 坑三（**更正 notes/320**）：`modLocalRuntime` 不是"只进 dev 客户端"

notes/320 写的「**装了 JourneyMap 5.10.0，只给 dev 客户端**」**不成立**：

* `run/logs/latest.log` 归属**服务端**（同一条日志里有
  `Starting minecraft server on *:25565`、`Preparing level "world"`）；
* 它的 `Loading 47 mods:` 清单里就有 **`- journeymap 5.10.0`**
  （`- worldedit 7.3.0` 也在，符合 `fabric.addMods` 的预期）。
* 影响：服务端多加载一个客户端取向的 mod（JourneyMap 自带 server 侧组件，
  无报错、无 crash），并生成了 `run/journeymap/server/5.10/*.config`。
* 要真按端隔离，得照 WorldEdit 的做法：jar 放 `vendor/mods/`，
  用 `runConfigs.client { property "fabric.addMods", … }` 只挂客户端侧。

> 注意：dev 服务端与客户端**共用 `run/`**，两边都写同一个 `logs/latest.log`，
> 后起的那个会盖掉先起的 ⇒ 看日志先确认归属（找 `Starting minecraft server` / `Sound engine started`）。

## 6. 遗留（未做）

* `ops.json` 里 **263 条 `Player###` 垃圾条目**（本轮 bug 的产物）没清。
  要清必须**停服改**（运行中服务器持有列表，下次保存会覆盖文件），且要保留两条 `Shuisoi`。
* JourneyMap 在服务端可见（§5），是否改成按端注入没动。
* **最终一步确认需要用户真进一次服**：`usercache.json` 应出现
  `Shuisoi` + `aeaca7ad-…`，且 `/tp` 可用（届时 `list` 里是 `Shuisoi` 而不是 `Player###`）。

## 7. 回滚

1. 删 `build.gradle` 里的 `programArgs` 行、删 XML 里的 `PROGRAM_PARAMETERS` 值；
2. 删 `ops.json` 里 uuid 为 `aeaca7ad-…` 的那条；
3. playerdata 从 `run/playerdata-backup-20260926-182746/` 整份拷回。

## 8. 本轮顺带确认（没动世界）

停服 → 改配置 → 重启后复查：`25565 / 25575 / 8888` 三个端口都在（pid 45388），
`mmtr-platforms` 正常返回 3 座车站（`莫氏岛` 的 platform 起止 `x=2300…2520, y=75, z=1806`
与台账一致）⇒ **世界与引擎数据未受影响**。客户端无新 `crash-reports`（最新一份仍是 18:21 那次 JourneyMap 崩溃）。

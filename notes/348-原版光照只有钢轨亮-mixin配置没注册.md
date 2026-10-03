# 348 · "原版光照下只有钢轨亮"：注册进的是**生成物**，模板里从来没有（同类错误第三次）

日期：2026-09-29 · 承接 notes/345（车灯：锚点到逐片元，§6/§7.20 地形那一侧）

一句话：地形车灯的代码、着色器、探针**全是好的** —— 缺的是 `fabric.mod.json` 里那一行注册；
而那一行之所以"修了又没"，是因为 **`fabric.mod.json` 是 `setupFiles` 从模板生成的**，手改生成物每次构建都会被覆盖。

Fabric **只**从 `fabric.mod.json` 的 `mixins` 数组加载 mixin 配置，**不扫描** `*.mixins.json`；
漏了那一行的效果与"补丁写错了"在画面上、在日志里完全一样。

---

## 1. 症状（用户原话）

> **"目前在原版光照中，仅铁轨被照亮"**

"原版光照" = Iris 关掉了光影包。现场证据（`run/config/iris.properties`）：

```
enableShaders=false            ← 18:14:22 写盘
shaderPack=ComplementaryReimagined_r5.9.3.zip
```
同一刻日志：`[18:14:22] (Iris) Shaders are disabled because enableShaders is set to false in iris.properties`。

于是区块那一路回到 **Sodium**（本机装着 Sodium 0.5.8：`ResourceManager` 那行的 mod 列表里有 `sodium`，
`run/config/sodium-options.json` 今天 18:04 还被写过），车厢与 3D 钢轨亮（走 MTR 自己的 program，
车灯早在 notes/345 §3 就接上了），道床/隧道壁/站台不亮 —— 正是 notes/345 §7.20 要补的那一环**没生效**。

## 2. 日志其实已经说了话 —— 是我自己起的那句话把它藏起来了

```
[MMTR-LIGHT] 车灯：画到的车=4 锚点=8 候选=2 上传=2/8 地形=开(未编译)(传0盏) | …
```

`地形=开(未编译)` 是 `MmtrSodiumTerrain.describe()` 里 `servedLogged == false` 的那一档，
而它把**三件完全不同的事**说成了同一句：

| 真实原因 | 当时那句话 | 该说的话 |
|---|---|---|
| Sodium 没装（走原版 `rendertype_solid`，本版还没接） | 开(未编译) | 关(无Sodium) |
| **注册没进模板 / 配置没注册**（本次） | 开(未编译) | **关(mixin配置未注册)** |
| 挂上了但 Sodium 还没编译过它的 program | 开(未编译) | 开(未编译) |

**教训先记在这里**：诊断里不许出现"我自己发明的中间状态词"。
`未编译` 让人以为"再等等就好"，而真正发生的是"钩子根本没挂" —— 一个词的含糊，代价是整轮排查。

## 3. 真因分两层：注册没进模板；而模板才是 `fabric.mod.json` 的真源

### 3.1 第一层：那两条注册不在 `fabric.mod.json` 里

现场三份东西**都在**，只有注册那一行**不在**：

| 东西 | 状态 | 证据 |
|---|---|---|
| `build/resources/main/mtr.sodium.mixins.json` | **在** | 287 B，17:58:08（部署那一轮拷进去的） |
| 两个 mixin 的 class | **在** | 17:57:50；`javap -v` 确认 `@Mixin(ShaderLoader)` / `@Mixin(GlProgram)`、`remap=false`、`@Inject(method=getShaderSource/bind, at=RETURN)` 全对 |
| 地形着色器三份资源（vsh/fsh/共享 include） | **在** | 17:57:56~59 |
| `fabric.mod.json` 的 `mixins` | **不在** | 只有 `mtr.mixins.json` + `mtr.library.mixins.json`（src 与 build 两份都缺） |

⇒ `SodiumShaderLoaderMixin` 从未织入 `ShaderLoader` ⇒ 我们的地形着色器一次都没被读过（`servedLogged` 恒 false）。
**顺带**：`mtr.iris.mixins.json` 同样不在 —— notes/344 §17.1 已把这条当缺陷查过、§17.5 明确写了"注册"，
现在却还是没有它 ⇒ 那一轮的修法也没活下来。

### 3.2 ★ 第二层（本轮的真正收获）：`fabric.mod.json` 是**生成物**

```
game/fabric/build.gradle:161-167   tasks.register("setupFiles") {
    copy { from "src/main/fabric.mod.template.json"
           into "src/main/resources"
           filter(ReplaceTokens, tokens: ["minecraft": minecraftVersion, "version": version])
           rename "(.+).template.json", "\$1.json" }        // ← 生成 src/main/resources/fabric.mod.json
    outputs.upToDateWhen { false }                            // ← 每次构建都跑，无条件覆盖
}
game/.gitignore:46   fabric.mod.json                          // ← 生成物，不入库
```

⇒ **改 `src/main/resources/fabric.mod.json` 是白改**：它每次构建都会被 `setupFiles` 用模板重写一遍。
这解释了 §17.5 那次"已修、实测钩子调用=673"为什么后来不见了 —— 那一轮的修改只活在生成物里。
**真源是 `game/fabric/src/main/fabric.mod.template.json`（入库），改它才算改。**

同族生成物（同一任务，一并记下，免得下次再踩）：`Keys.java`、`Patreon.java`、`JadeConfig.java`、
`WthitConfig.java`、`wthit_plugins.json`、`mtr_custom_resources.json`、`**/font/mtr.json`、`**/lang/*.json`（保留 `en_us.json`）。

**验证替换规则**（不是"我觉得"）：把模板按 `@version@`→`4.0.5`、`@minecraft@`→`1.20.4` 替换后，
与现有生成物逐行对比 ⇒ **只差我手加的那两行**（其余逐字节相同）⇒ 规则成立、生成可复现。

## 4. 判据链（全部离线，与游戏无关；每条都跑了）

| 检查 | 命令 | 结果 |
|---|---|---|
| Sodium 的注入点**真的存在** | `javap -p -classpath …sodium-mc1.20.4-0.5.8.jar …ShaderLoader …GlProgram` | `public static String getShaderSource(class_2960)` ✓、`public void bind()` ✓（非 final ✓） |
| 我们的 mixin 注解**真的编译进了 class** | `javap -v build/classes/…/SodiumShaderLoaderMixin.class` | `@Mixin(…ShaderLoader)` + `@Inject(method="getShaderSource", at=RETURN, cancellable, remap=false)` ✓（`SodiumGlProgramMixin` 同） |
| 4 份配置 **16 个条目 ↔ class** 逐条对 | 脚本解析 `*.mixins.json` 后查 `build/classes` | **16/16 存在** ✓ |
| **注册守卫**（本次新增，三层：模板 / src / build） | `pwsh -File sandbox\sodium-terrain-probe\mixin-registration.ps1 -ResourcesDirs "src;build" -Template <模板> -ConfigSourceDir src` | 三处全 `OK`（4 份配置）✓，**并且有负例证明**（见 §5.2） |
| 地形补丁的**决策路径**（26 项，含 7 负例） | `pwsh -File sandbox\sodium-terrain-probe\check.ps1` | **26/26 全绿** ✓（负例：锚点逐个改名/空源码 ⇒ 拒绝替换） |
| 着色器**编译+链接**与 uniform 活着 | `pwsh -File sandbox\glslcheck\check.ps1` | 4 对全绿 ✓；地形程序里 `mmtrHeadlightCount=5 Pos=6 Dir=14 Color=22`、`mmtrTerrainLuxScale/Flat/Debug` 都在 active uniforms 里 |
| 改动后的 Java 类型检查 | `pwsh -File mmtr\scripts\check-java-compile.ps1 -Files …\MmtrSodiumTerrain.java` | OK ✓ |

⇒ **结论：这一侧本来就没坏。** 上一轮"待实机确认"里只有"注册"这一条没落地，而它落错了地方。

## 5. 修法

### 5.1 三处一起改（模板是源，另两份是产物）

1. **模板** `game/fabric/src/main/fabric.mod.template.json` 的 `mixins` 补
   `"mtr.iris.mixins.json"`、`"mtr.sodium.mixins.json"`（**改这里才算改**）。
2. 按 `setupFiles` 的同一条规则重新生成 `src/main/resources/fabric.mod.json`（`@version@`→`4.0.5`、
   `@minecraft@`→`1.20.4`；下次用户自己跑 gradle 也会得到同一份）。
3. 同步到 `build/resources/main/fabric.mod.json`（**游戏这次读的是这一份**）。
   判据：三处的 `mixins` 都是那四条，且生成物合法 JSON。

### 5.2 守卫（把这一类错误变成可跑的判据）

`MmtrSodiumTerrain` 的诊断行按**外部可观测原因**分档，不再用含混的"未编译"：

| 分档 | 含义 |
|---|---|
| `关(光场总开关)` / `关(properties)` | 开关关着（本来就该关） |
| `关(资源缺失)` | 我们自己的着色器资源读不出来 |
| **`关(无Sodium)`** | 本机没有 Sodium ⇒ 区块走原版 `rendertype_solid`，**那条路本版尚未接**（notes/345 §6） |
| **`关(mixin配置未注册)`** | 就是本轮这一条 |
| `开(未编译)` | 钩子挂上了，但 Sodium 还没编译过 program |
| `开` | 已替换（`servedLogged`） |

"无 Sodium" 与 "配置未注册" 各**大声报一次**（INFO / ERROR）。判据取的是**游戏真正读的那份文件**：
`FabricLoader.getInstance().getModContainer("mmtr").flatMap(c -> c.findPath("fabric.mod.json"))` 的内容里有没有那一条
（dev 环境 = `build/resources/main`）。**判不出来一律当作已注册**：宁可漏报，也不要在诊断里造一个假警报。

另加脚本守卫 `sandbox/sodium-terrain-probe/mixin-registration.ps1`：**资源目录里每一份 `*.mixins.json` 都必须在
同一份 `fabric.mod.json` 里注册**，而且**模板、src、build 三处分开查**（§3.2 的教训）。
**负例证明**（它唯一的价值证明）：

| 输入 | 结果 |
|---|---|
| 生成物对、**模板漏注册**（= 本轮的真形态） | `FAIL …（模板）没注册 mtr.sodium.mixins.json —— 生成物每次构建都会被它覆盖，改生成物是白改`，**exit 1** ✓ |
| 有配置但没有 `fabric.mod.json` | `FAIL … 里没有 fabric.mod.json`，exit 1 ✓ |
| 两处都对 | `OK`，exit 0 ✓ |
| 真仓库三处 | `OK`（4 份配置）× 3 ✓，随后原探针 26/26 照旧全绿 ✓ |

## 6. 实机验收（2026-09-29 晚，三条全中）

| 判据 | 结果 |
|---|---|
| ① 日志 `地形：Sodium 地形着色器已换成带车灯的版本(…)` | **出现**（18:53:02）⇒ 钩子挂上了、vsh/fsh 两条替换都生效 |
| ② 诊断行 `… 地形=开(传N盏)` | **出现**：`画到的车=4 锚点=8 候选=4 上传=4/8 地形=开(传4盏)`（18:57:13 起持续）⇒ 地形 program 真的拿到了灯表 |
| ③ `terrainDebug=true` 的假色 | **品红出现**（用户确认）⇒ **光斑真的落在地面上** |
| 收尾 | 已把 `terrainDebug` 改回 `false`（2 秒热重载，不用重启）；帧时约 10 ms（≈100 fps），这条路的代价在噪声里 |

> **★ 2026-09-29 补记**：注册修好之后第一次实机 = **一进世界闪退**，原因是地形 fsh 里那行
> `#import <sodium:include/mmtr_headlight.glsl>` 走了 Sodium 的**异常路径**（它找不到就抛），
> 而我们的供给挂在 `@At("RETURN")` 上 ⇒ 见 **notes/350**（已修：HEAD 注入 + 随 mod 发布那份文件，两条互不依赖）。
> 闪退修掉之后的第二次实机 = 上表三条全中。

1. 日志出现：`[MMTR-LIGHT] 地形：Sodium 地形着色器已换成带车灯的版本(…)` —— 出现 = 钩子挂上了。
2. 诊断行变成：`… 地形=开(传N盏)`（N>0）。
3. 画面（`run/mmtr-lightfield.properties` 里 `terrainDebug=true` 现在开着）：
   **世界全黑 + 车灯锥扫过处品红** = 补丁生效；**与平常一样 = 仍没换上**（这条不挑夜晚、不看贴图）。
   确认之后把 `terrainDebug=false` 改掉 —— **2 秒热重载，不用重启**。

## 7. 教训（第三次同型，值得单列）

1. **先问一句"这个文件是谁写的"，再动手改。** `git check-ignore -v <文件>` 一见 `game/.gitignore` 命中，
   就说明它是**生成物** —— 那时该去找 `*.template.*`，而不是改它。
   本仓这类生成物有一批（§3.2 列了名单），而且生成任务带 `outputs.upToDateWhen { false }` ⇒ **每次构建都覆盖**。
2. **凡是"配置类改动"，验收判据只有一条：回读游戏真正读的那份文件**（notes/344 §17.1 的原话）。
   本轮补上后半句：**那条链有三段 —— 模板 → src 生成物 → build 产物 —— 必须三处都回读，并确认它们一致。**
   只验其中一段，都会得到"我改过了"的错觉。
3. **诊断信息不许用自造的中间状态词。** 每一档都必须对应一个**外部可观测的原因**；
   否则"排查"会从"读日志"退化成"猜 + 重试"。
4. **同一类错误第三次出现 ⇒ 它该被一个判据挡住，而不是被"下次注意"挡住。**
   本轮落地了两条：诊断里的 `关(mixin配置未注册)`，与 `mixin-registration.ps1`（三层 + 负例）。

## 8. 顺带查出来的仓库风险（**未处理，需要人决定**）

本轮的修改能在工作区生效，但**进不了 git**：

| 东西 | git 状态 | 说明 |
|---|---|---|
| `game/fabric/src/main/fabric.mod.template.json` | 已跟踪（本次修改） | 唯一"改了就等于改了"的那一份 |
| `game/fabric/src/main/resources/mtr.sodium.mixins.json`、`mtr.iris.mixins.json` | **未跟踪**（`??`） | 不在 .gitignore 里，只是从来没 `git add` |
| `game/fabric/src/main/java/org/mtr/mixin/sodium/*.java`、`mixin/iris/*.java` | **被忽略** | `game/.gitignore:36` 的 `**/org/mtr/mixin/` 命中了它们（那条规则本是给上游"生成"的 mixin 目录用的） |
| `game/fabric/src/main/java/org/mtr/mod/render/light/**`（4 个类） | **未跟踪**（`??`） | 整个车灯/光场实现都不在库里 |
| `assets/mtr/shaders/{core,sodium}/mmtr_*`、`assets/minecraft/shaders/include/mmtr_*` | **未跟踪**（`??`） | 两份着色器与共享 include |
| `notes/193` ~ `notes/348` | **未跟踪**（`??`） | 整批笔记都不在库里（不只是本轮） |

⇒ **`git clean -xdf` 或换一台机器克隆，上面这些都会消失**（车灯、光场、玻璃、雨刷的渲染改动都在这一类里）。
两件事需要定：① `**/org/mtr/mixin/` 这条忽略规则要不要收窄（它现在会吞掉所有手写的新 mixin）；
② 这批未跟踪文件要不要一次性入库。**在定下来之前，本轮不提交** ——
单独把模板那一行提交，反而会让"全新克隆"里出现"注册了但不存在的 mixin 配置"（那比不注册更糟）。

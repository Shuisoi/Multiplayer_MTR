# 350 · 进世界闪退：Sodium 对"缺失的 include"是**抛异常**，而 `@At("RETURN")` 注入在异常路径上不执行

日期：2026-09-29 · 承接 notes/345 §7.20（地形车灯）、notes/348（注册）、notes/349（loom/沙箱）

## 0. 现象

注册修好、缓存修好之后，客户端**一进世界就闪退**（`crash-2026-09-29_18.53.02-client.txt`）：

```
java.lang.RuntimeException: Shader not found: /assets/sodium/shaders/include/mmtr_headlight.glsl
	at me.jellysquid.mods.sodium.client.gl.shader.ShaderLoader.getShaderSource(ShaderLoader.java:30)
	at me.jellysquid.mods.sodium.client.gl.shader.ShaderParser.resolveImport(ShaderParser.java:53)
	at me.jellysquid.mods.sodium.client.gl.shader.ShaderParser.parseShader(ShaderParser.java:28)
	at me.jellysquid.mods.sodium.client.gl.shader.ShaderLoader.loadShader(ShaderLoader.java:22)
	at me.jellysquid.mods.sodium.client.render.chunk.ShaderChunkRenderer.createShader(ShaderChunkRenderer.java:48)
	... ShaderChunkRenderer.begin → DefaultChunkRenderer.render → SodiumWorldRenderer.drawChunkLayer
	... WorldRenderer.render → GameRenderer.renderWorld
```

即：**区块渲染器第一次编译 program 时**（`ShaderChunkRenderer.begin`）崩的，所以"进世界才崩、主菜单没事"。
崩溃报告里 `Loaded Shaderpack: (off)`、`sodium: Sodium 0.5.8+mc1.20.4` ⇒ 正是本补丁要覆盖的那条路。

## 1. 机制（一句话）

我们的 fsh 里有一行 `#import <sodium:include/mmtr_headlight.glsl>`，这个文件**不在 Sodium 的 jar 里**，
是由 `SodiumShaderLoaderMixin` 在 `ShaderLoader.getShaderSource` 的 **RETURN** 处"供给"的。
但 Sodium 自己的 `getShaderSource` 对 classpath 上找不到的 include 是 **`throw new RuntimeException("Shader not found: " + path)`**；
**方法抛异常时 `@At("RETURN")` 的注入根本不执行** ⇒ 我们的"供给"没有机会发生 ⇒ 崩。

```
Sodium 找 include ──► getShaderSource(identifier)
                        ├─ 找得到 ──► 正常返回 ──► 【我们的 RETURN 注入在这里替换源码】✅
                        └─ 找不到 ──► throw ────► 【RETURN 注入不执行】✗ ──► 闪退
```

**这不是"注入没挂上"，而是"挂在了一条到不了的路径上"。** 之前所有证据都指向"钩子挂上了"（注解在 class 里、
探针调用 `sourceFor` 也返回了内容）—— 但它们测的都是**成功路径**。

## 2. 为什么离线判据没抓到（本轮最值钱的一条）

| 判据 | 它测了什么 | 为什么漏了这一条 |
|---|---|---|
| `sandbox/sodium-terrain-probe` 的 26 项 | 直接调 `MmtrSodiumTerrain.sourceFor(identifier)` | 这是**我们自己**的函数，它当然返回内容 —— 没有经过 Sodium 的查找/抛错逻辑 |
| `sandbox/glslcheck`（4 对着色器编译链接） | 用 `-Dmmtr.imports=sodium=<目录>` **由检查器自己解析 `#import`** | 检查器把 include 解出来了，于是"Sodium 找不到 include"这件事在它的世界里**不存在** |
| "注解真的进了 class"（javap） | 注入点存在 | 注入点存在 ≠ **运行时会走到**它 |

⇒ **判据必须覆盖宿主的失败路径，而不只是成功路径。** 我们离线把 Sodium 的 import 解析**替它做了**，
于是永远看不到它自己那一侧的失败行为。（与 notes/344 §9.1、notes/345 §7.5 的"判据与被测物同源"是同一族错误。）

## 3. 修法：两条**互不依赖**的通道

| 通道 | 做法 | 为什么留着 |
|---|---|---|
| **HEAD 注入** | `SodiumShaderLoaderMixin` 加一条 `@At("HEAD") + cancellable`：标识符是我们供给的那个就直接 `setReturnValue`，**根本不进 Sodium 的方法体** | 不依赖 classpath 布局，也不依赖"Sodium 会不会抛" |
| **随 mod 发布那份文件** | 把同一份 include 放到 `game/fabric/src/main/resources/assets/sodium/shaders/include/mmtr_headlight.glsl`（= Sodium 会去找的那个路径） | 万一 HEAD 注入哪天没挂上（本配置 `defaultRequire:0` ⇒ 注入失败只是警告），Sodium 自己也能在 classpath 上找到它 |

代价是"同一份数学有两份拷贝" ⇒ 用判据压住：探针断言 **两份逐字节相同**（`servedInclude.equals(onClasspath)`，
而 `onClasspath` 是用**同一个类加载器**读出来的 —— 与 Sodium 问的是同一个问题）。

## 4. 新增/加严的判据（都跑过）

| 判据 | 结果 |
|---|---|
| `syntheticIncludeFor(SODIUM_INCLUDE) != null` 且内容等于 `sourceFor` 那份 | OK |
| `syntheticIncludeFor(fog.glsl) == null` / `syntheticIncludeFor(方块着色器) == null`（只供给我们那一个名字） | OK |
| `classpath 上真有 Sodium 会去找的那份 include`（`MmtrSodiumTerrain.class.getResourceAsStream("/assets/sodium/shaders/include/mmtr_headlight.glsl")`） | OK |
| `classpath 那份与供给的那份逐字节相同` | OK |
| **产物 class 里同时有 HEAD 与 RETURN 两条注入**（`javap -v`；"我以为我加了"不算数） | OK |
| 原有 26 项（路由 / 内容 / 锚与负例 / 副本不漏行） | 照旧全绿 |
| 探针整体 | **31 项全绿** |

**部署**：改了两个 Java 文件 + 新增一个资源 ⇒ `check-java-compile.ps1`（2 源 / 2 class，OK）
→ 拷 `sandbox/javac-out/*` 进 `build/classes/java/main` → 拷 `assets/sodium/shaders/include/mmtr_headlight.glsl`
进 `build/resources/main/...` → **重启客户端**（Sodium 的 program 一辈子只编译一次）。

## 5. 实机验收（2026-09-29 晚）

| 判据 | 结果 |
|---|---|
| 重启客户端、进世界 | **不再闪退**（无新 crash-report） |
| 日志 `地形：Sodium 地形着色器已换成带车灯的版本(…)` | 出现（18:53:02） |
| 日志 `… 地形=开(传4盏)` | 出现（18:57:13 起持续）⇒ 地形 program 真的拿到灯表 |
| `terrainDebug=true` 假色 | **品红出现** ⇒ 光斑落在地面上（这条是"补丁真的生效"的判据） |
| 收尾 | `terrainDebug=false`（2 秒热重载）；帧时 ≈10 ms（≈100 fps） |

## 6. 教训

1. **注入点的"存在"与"会走到"是两件事**：`RETURN` 在**异常路径**上不执行；
   `HEAD` 才是"只要方法被调用就一定执行"。凡"给宿主补一个它没有的资源"，挂在 HEAD 更稳。
2. **离线判据要问宿主会问的问题**（这里 = 用同一个类加载器去 `getResourceAsStream`），
   而不是把宿主的活替它干了再测自己的结果。
3. 与 348/349 连起来看，这一轮的三次翻车各有各的"判据盲区"：
   348 = 只回读了**产物**没回读**模板**；349 = 只看**镜像路径**没量**令牌**；
   350 = 只测**成功路径**没测**异常路径**。三次都不是"没检查"，而是**检查覆盖错了范围**。
4. **崩溃报告本身就是最好的判据来源**：栈顶那三行（`ShaderLoader.getShaderSource:30` →
   `ShaderParser.resolveImport:53`）把"我们的文件 + 宿主的抛错 + 注入点选错"一次说清，
   比读一遍自己的代码快得多。


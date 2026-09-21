# 220 · IDEA 里服务端拉不起来（续）：导入时 `:forge:compileJava` 把整个导入带崩了

日期：2026-09-21 · 结论：**不是 jar、不是端口、不是信号系统** ——
`game/forge` 子工程编不过（那里被复制进去的 MMTR 源码是**按 fabric/Yarn 写的**），
而 **IDEA 的 Gradle 导入会编译所有子工程** ⇒ 导入失败 ⇒ 项目模型里**只注册了半个项目**
（`modules.xml` 里只有 forge，没有 fabric）⇒ 运行配置 `Minecraft Server (:fabric)` 找不到模块 ⇒ 起不来。
修法：**forge 改成可选子工程**（`settings.gradle`，`-Pmmtr.forge=true` 才包含），导入即可通过。

用户原话（第二次）：「依旧拉不起来」。

## 1. 关键判据：游戏日志一行都没新增

| 证据 | 含义 |
|---|---|
| `run/logs/latest.log` 最后一条是 **16:04:35 `Stopping!`**，之后**零新增** | 这次尝试**根本没起 JVM** ⇒ 问题在 IDE 侧，不在游戏里 |
| 端口 25565 空闲、没有 loom 进程 | 不是端口冲突 |
| `game/libs` 的 jar 已同步、内容核验过（notes/219） | 不是 jar |
| `runClient`/`runServer` 两个 loom argfile 在 16:15:42 / 16:16:38 被重写 | IDEA 的 Gradle **确实跑到了 loom 那一步** |
| `.idea/modules.xml` 里**只有 forge 模块** | 运行配置引用的 `Minecraft-Transit-Railway.fabric.main` **不存在** |

> **通用判据**：运行配置"起不来"且**游戏日志没有新增** ⇒ 去看 IDEA 自己的日志
> （`%LOCALAPPDATA%\JetBrains\IdeaIC2025.2\log\idea.log`），不要去翻游戏日志。

## 2. IDEA 日志里的原文

```
com.intellij.openapi.externalSystem.model.ExternalSystemException: Compilation failed; see the compiler output below.
Caused by: org.gradle.api.internal.tasks.compile.CompilationFailedException: Compilation failed
game/forge/src/main/java/org/mtr/mod/mmtr/MmtrSignalSync.java:0: 错误: 程序包net.fabricmc.fabric.api.event.lifecycle.v1不存在
```

即 **Gradle 导入阶段（IDE 的 project resolve）在编译期就失败了**，从 16:05 到 16:16 反复重试、每次都一样。

命令行复现（与 IDEA 无关）：

```
gradlew -p mmtr\game :forge:compileJava
→ C:\...\game\forge\src\main\java\org\mtr\mod\mmtr\MmtrChunkTracker.java:3: 错误: 程序包net.fabricmc.fabric.api.event.lifecycle.v1不存在
  ... 以及 net.minecraft.server.world / net.minecraft.util.math / net.minecraft.world.chunk 都不存在
```

**这是仓库里早就存在的缺陷**：我们的 MMTR 源文件被复制进了 `game/forge/src/main/java/org/mtr/mod/mmtr/**`，
但它们是按 **fabric/Yarn** 写的（`net.fabricmc.*`、Yarn 的 `net.minecraft.*` 包名），
在 forge 映射下必然编不过。以前没被注意到，是因为**没人编译 forge**；而 IDEA 的导入会。

## 3. 先证明游戏本身没问题

用 **IDEA 那条命令原样**起了一次（同 argfile、同 VM 参数、同工作目录 `fabric/run`）：

```
java @…\build\loom-cache\argFiles\runServer
     -Dfabric.dli.config=…\loom-cache\launch.cfg -Dfabric.dli.env=server
     -Dfabric.dli.main=net.fabricmc.loader.impl.launch.knot.KnotServer
     net.fabricmc.devlaunchinjector.Main nogui
```

结果：**起来了**（44 个 mod、`[MMTR-HLTH] vehicles=8`、任务/信号都在跑），日志写进 `run/logs/latest.log`。
诊断完把它停了，把 25565 让出来。

## 4. 修法：forge 改成可选子工程（`game/settings.gradle`）

```groovy
include("fabric")
if (providers.gradleProperty("mmtr.forge").getOrNull() == "true") { include("forge") }
```

- fabric 才是本仓的开发目标（`dev-server.ps1`/`dev-client.ps1`、IDEA 的两个运行配置都是 fabric）；
- 真要构建 forge：`gradlew -Pmmtr.forge=true :forge:build`（前提是先把那份源码对齐到 forge 映射）；
- 这一改同时治好"**任何编译所有子工程的动作都会失败**"这条更大的毛病
  （IDEA 导入、`gradlew build`、CI）。

验证（全部用 game 自己的 Gradle 8.14）：

| 项 | 结果 |
|---|---|
| 根项目 `compileJava`（= 导入时做的事） | **BUILD SUCCESSFUL**（1m31s） |
| `gradlew projects` | 只剩 `:fabric`（+ 根） |
| `:fabric:compileJava` | **BUILD SUCCESSFUL** |

## 5. 用户侧要做的一步

在 IDEA 里**重新导入 Gradle 项目**（Gradle 工具窗口的刷新 / `File → Sync All Gradle Projects`）——
失败的是**项目模型**，不重导入的话 `:fabric` 模块还是不在。之后 `Minecraft Server (:fabric)` 就能起。
`.idea/modules/forge/*.iml` 是上次半途导入留下的残骸，重导入会重写 `modules.xml`，不必手删。

## 6. 遗留

1. `game/forge/src/main/java/org/mtr/mod/mmtr/**` 那批 fabric 风味的源码仍在树里（现在不参与编译）；
   真要恢复 forge 支持，得把它们按 forge 映射重写（或从 fabric 树重新生成）；
2. 本条的通用教训值得记：**"IDE 里拉不起来"先分清"JVM 起没起"** ——
   游戏日志有新增 = 游戏侧；游戏日志零新增 = IDE 侧（项目模型/编译/运行配置）。

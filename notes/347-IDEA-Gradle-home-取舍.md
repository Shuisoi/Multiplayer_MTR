# 347 · IDEA 的 Gradle home 取舍：`.lck` 已修好，但 loom 合并卡在 zipfs

日期：2026-09-29 · 承接 notes/346（Low 完整性沙箱）、修正 346 §7

## 0. 用户看到的现象

IDEA 打开 `mmtr\game`，Gradle sync 永远失败，事件日志一句：

```
C:\Users\30354\.gradle\wrapper\dists\gradle-8.14-bin\38aieal9i53h9rfe7vjup95b9\gradle-8.14-bin.zip.lck (拒绝访问。)
```

## 1. 真因（已定性、已修）

**Gradle 的 Tooling API 跑在 `idea64.exe` 自己的 JVM 里**（`env\idea\jbr` ⇒ **Low**），
它在起 daemon 之前先要"装发行版"，第一步就用 `RandomAccessFile(rw)` 打那个 `.lck`。
默认 home = `%USERPROFILE%\.gradle`（Medium）⇒ Low 写不了 ⇒ 从沙箱上线起 sync 就是废的。

`idea.log` 原文（`sandbox\idea-home\log\idea.log`，17:19 / 17:22 / 17:23 反复）：

```
Caused by: java.io.FileNotFoundException: …\gradle-8.14-bin.zip.lck (拒绝访问。)
  at java.io.RandomAccessFile.open0(Native Method)
  at org.gradle.internal.file.locking.ExclusiveFileAccessManager.access
  at org.gradle.wrapper.Install.createDist(Install.java:69)
  at org.gradle.tooling.internal.consumer.DistributionInstaller.install
```

判定实验（同一份代码、同一个文件、同一个用户，只换 JVM 镜像路径）：

| 跑的 JVM | `new RandomAccessFile(lck,"rw")` |
|---|---|
| `<MC>\env\jdk-21\bin\java.exe`（工作区内 ⇒ Low） | **FAIL** `FileNotFoundException`（拒绝访问） |
| `%USERPROFILE%\.gradle\jdks\eclipse_adoptium-21…`（工作区外 ⇒ High） | OK |

**修法**：`bin\_prepare-idea-home.bat` 的 `env.cmd` 现在导 5 条
（`IDEA_PROPERTIES` / `TEMP` / `TMP` / `GRADLE_USER_HOME` / `JAVA_HOME`），
后两条与 `env\workspace.env.*` 同源。发行版 hash 两边一致（`38aieal9i53h9rfe7vjup95b9`），
工作区 home 里 `gradle-8.14-bin.zip.ok` 已在 ⇒ `Install.createDist` 走"已安装"分支，不再碰 `.lck`。

顺带修掉的第二个哑弹：本机 `JAVA_HOME` 指向**已不存在的**
`C:\Program Files\Java\jre1.8.0_431`（`C:\Program Files\Java` 里现在只有
`jdk1.8.0_341` / `jre1.8.0_501` / `latest`）。不覆盖它，IDEA 里任何 `gradlew` 都直接
`ERROR: JAVA_HOME is set to an invalid directory`。项目钉的是
`JavaLanguageVersion 21`（`buildSrc/build.gradle`），所以设 `env\jdk-21`。

验证：`call sandbox\idea-home\env.cmd` + `gradlew --version` ⇒ **exit 0**，
`Launcher JVM 21.0.12.1`、`Daemon JVM <MC>\env\jdk-21`。

## 3. ★ 结论：**换令牌 + 换 home，两半都要**

> ⚠ **2026-09-29 晚修正（见 notes/349）**：本节把"工作区外 JDK"当成了 High 的**充分条件**，这条**不对**。
> 实测：那次 daemon 的 `javaHome` 确实是工作区外那份，但它的令牌是 **Low** ——
> **令牌看父进程，不看镜像**（工作区外镜像 + Low 父 = Low；工作区内镜像 + High 父 = Low）。
> 所以"IDEA 的 Gradle JVM 设成工作区外"治不了 IDEA 那条路（§3.2 的复刻是从 High 终端跑的，
> 复刻的是 High 那一侧）。真正可用的做法是**别用 IDEA 跑 gradle**，改用
> `game\run-server.bat` / `game\run-client.bat`（工作区外 JDK + 从普通终端/资源管理器起）。
> 本节其余部分（`GRADLE_USER_HOME` 放工作区内、zipfs 与令牌的关系）仍然成立。

> 用户："你是完全权限，可以用工作区外的啊"

对，但关键是**能用工作区外的路径 ≠ 能用工作区外的令牌**：

- `env\jdk-21` 即使是从工作区外**复制**进来的，镜像在工作区内 ⇒ **还是 Low**（标签跟镜像路径走，346 §1）；
- 反之，**直接执行**工作区外的 JDK ⇒ **High**，写 `%USERPROFILE%\.gradle` 与 zipfs 都正常。

三种组合实测（同一份代码，只换 JVM 镜像与目标目录）：

| JVM 镜像 | 目标目录 | 普通写 | zipfs 写 |
|---|---|---|---|
| 工作区内（Low） | 工作区内 | OK | **FAIL** `ReadOnlyFileSystemException` |
| 工作区内（Low） | `%USERPROFILE%\.gradle` | **FAIL** `AccessDeniedException` | FAIL |
| **工作区外（High）** | `%USERPROFILE%\.gradle` | OK | **OK** ✅ |

组合 C 端到端跑通了（`gradlew :fabric:compileJava`，1m42s）：loom 的 JAR 合并**过了**，
只剩源码里 Sodium 两个 mixin target 找不到。

### 3.1 落地配置 = **两半，缺一不可**（这一节被实测修正过一次）

| 要治的 | 药 | 为什么 |
|---|---|---|
| Tooling API（**永远 Low**）写不了 `%USERPROFILE%\.gradle` 里的 `.lck` | `GRADLE_USER_HOME` = **工作区内** `sandbox\gradle-home` | Tooling API 客户端跑在 `idea64.exe` 自己的 JBR 里，**跟 IDEA 的 Gradle JVM 设置无关** ⇒ 永远 Low ⇒ 它开那个 `.lck` 必须落在 Low 写得进的地方 |
| daemon 写 zipfs（loom 合并 JAR） | **IDEA 的 Gradle JVM = 工作区外 JDK 21** | 只有 High daemon 才能过 zipfs 的 `isWritable` 判定 |

⚠ **踩过的坑：只设 Gradle JVM 不够。** 用户重启并设好 Gradle JVM 之后仍然报同一个错，
`idea.log` 里两件事同时出现：

```
2026-09-29 17:48:56 INFO - #o.j.p.g.GradleManager - Instructing gradle to use java from C:/Users/…/.gradle/jdks/…
Caused by: java.io.FileNotFoundException: …\gradle-8.14-bin.zip.lck (拒绝访问。)
```

即那条 `Instructing …` **只管 daemon JVM**；`Install.createDist` 里的
`exclusiveFileAccessManager.access(...)` 仍然在 IDEA 自己的 JVM 里跑 ⇒ 仍失败。
反过来只把 home 搬进工作区也不行（§2 的 zipfs）。**两半缺一不可。**

### 3.2 复刻组合验证（**两层都过**）

launcher JVM = `env\jdk-21`（Low，负责开 `.lck`）+ daemon JVM = 工作区外 JDK 21（High）
+ `GRADLE_USER_HOME` = 工作区内：

```
GRADLE_USER_HOME=[…\sandbox\gradle-home]
JAVA_HOME=[%USERPROFILE%\.gradle\jdks\eclipse_adoptium-21-amd64-windows.2]
> Task :fabric:compileJava
BUILD FAILED in 1m 29s   ← 只剩源码问题
```

`zip.lck` / `Could not merge JARs` / `ReadOnlyFileSystemException` / `native-platform`
**一条都不再出现**。剩下的唯一错误是源码：

```
错误: Mixin target me.jellysquid.mods.sodium.client.gl.shader.GlProgram could not be found
错误: Mixin target me.jellysquid.mods.sodium.client.gl.shader.ShaderLoader could not be found
```

⚠ **别在 `gradle.properties` 里写 `org.gradle.java.home` 指向工作区内路径**：
实测它**确实**会覆盖 daemon JVM（daemon 命令行里就是它），一写就把 daemon 拽回 Low，
重新撞 `%USERPROFILE%\.gradle\native\…\native-platform.dll.lock（拒绝访问）`。

### 3.3 命令行侧

命令行走 `env\workspace.env.*`（`GRADLE_USER_HOME` = 工作区内 + `env\jdk-21`）时，
launcher 与 daemon 都是 Low ⇒ zipfs 会炸。要跑 gradle 构建，得让 **daemon 用工作区外的 JDK**：
```
gradlew :fabric:compileJava -Dorg.gradle.java.home="%USERPROFILE%\.gradle\jdks\eclipse_adoptium-21-amd64-windows.2"
```
（或者干脆让 IDE 承担 gradle 构建，命令行只跑不依赖 gradle 的检查 —— 346 §7.1 的老建议仍成立。）

## 3.5 `:fabric:compileJava` 之后的两个真问题（都不是沙箱）

### (a) `Mixin target … could not be found`（已修）

```
SodiumGlProgramMixin.java:24  错误: Mixin target me.jellysquid.mods.sodium.client.gl.shader.GlProgram could not be found
SodiumShaderLoaderMixin.java:38 错误: Mixin target …ShaderLoader could not be found
```

类**确实在** sodium jar 里（`me/jellysquid/mods/sodium/client/gl/shader/GlProgram.class` 存在）——
问题是它在 **runtime classpath**，而 Mixin 的**注解处理器**要在 **compile classpath** 上核实目标类。
`modLocalRuntime` 只进 runtime（`fabric/build.gradle`）。Iris 早就因为同样的道理额外加了一条
`compileOnly`（那条注释写的是"需要它的**类型**"）。

**修**：`fabric/build.gradle` 补 `compileOnly "maven.modrinth:sodium:mc1.20.4-0.5.8"`。
副作用是好的：两个 mixin 因此可以从 `@Mixin(targets = "…")` 改成 `@Mixin(GlProgram.class)` 类字面量，
注解处理器那条"target is public and should be specified in value"警告随之消失
（`SodiumShaderLoaderMixin` 的注释原本写着这条警告"是故意的、也无法消除"——前提没了，一并改掉）。
结果：`:fabric:build` 通过。

### (b) 陈旧产物：`.fabric\processedMods` 与 `fabric\build` 的输出快照

`:fabric:build` 先报

```
> Task :fabric:processResources FAILED
> Cannot access a file in the destination directory. … Declare the task as untracked by using Task.doNotTrackState().
   > Failed to create MD5 hash for file '…\fabric\build\resources\main\assets\mtr\sounds\c1141a\acceleration\speed_3a.ogg' as it does not exist.
```

那个 `.ogg` **根本不存在** —— 是**上一轮多次失败构建**留在 `fabric\build` 里的过期快照。
删掉 `game\build` / `game\fabric\build` / `game\.gradle` 重建即可
（删之前要先停掉 daemon：daemon 握着 `.gradle` 里的 `*.lock`，删不干净）。

再一条，起服务端时：

```
Failed to remap mods!
java.io.IOException: the jar file …\fabric\run\.fabric\processedMods\worldedit-….jar can't be written
```

`.fabric\processedMods` 是**空的旧目录**（上一次失败的 loader 把没写完的 jar 删了）。
实测**不是**令牌问题：用 High JVM 往 `.fabric` 和 `processedMods\*.jar` 写都成功
（no-write-down 在这里没挡）。**删掉 `run\.fabric`** 后服务端正常起来：
`mmtr 4.0.5` + worldedit + journeymap + Fabric API 全部加载，`25565` 监听成功。

⇒ 通用教训：**本轮反复失败留下的一堆半成品（build / .gradle / .fabric / processedMods）
会伪装成"权限问题"**。分不清时先按"陈旧产物"清一遍，再怀疑令牌。

## 4. 与 346 §7 的关系

346 §7 写"不要给 IDEA 设 `GRADLE_USER_HOME`"，立论是"IDEA 的 Gradle 侧不受沙箱影响"。
**那条立论是错的**：依据的 `Instructing gradle to use java from …\.gradle\jdks\…` 说的是
daemon/toolchain JDK，跟 Tooling API 跑在哪毫无关系。§7 的**结论**（IDEA 用 profile home）
也被实测否掉了 —— 只设 Gradle JVM 之后 `.lck` 照样失败（§3.1 的日志）。

最终方案是**§7 与 §8 各取一半**：
home 用**工作区内**（治 Tooling API 的 Low），daemon JVM 用**工作区外**（治 zipfs）。
两边都不是 §7 或 §8 单独给出的答案。

## 5. 未做 / 边界

- 没有动 ACL、没有给工作区目录加 Low 强制标签（346 §3/§6 的边界）。
- 没有改沙箱、没有用 UAC/提权。
- `sandbox\gradle-home` **现在重新被使用**（IDEA 的 `GRADLE_USER_HOME` 指向它）；
  里面的 `caches\fabric-loom\1.20.4\` 曾被 loom 失败时清空，由 daemon 在 High 侧重建。
  `%USERPROFILE%\.gradle\caches\fabric-loom` 仍是完整的那一份（9/9），两处并存不影响。
- **`runClient` 还没跑过**：现在挡路的是源码里 Sodium 的两个 mixin target
  （`me.jellysquid.mods.sodium.client.gl.shader.GlProgram` / `ShaderLoader`）找不到，
  与沙箱无关，需要单独修。

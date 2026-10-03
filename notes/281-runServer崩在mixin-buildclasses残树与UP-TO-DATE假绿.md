# 281 - runServer 崩在 mixin 找不到：build/classes 是残的，而 compileJava 说 UP-TO-DATE

> 现象（用户 2026-09-25 11:59）：`gradlew :fabric:runServer` 起手就崩，`BUILD FAILED in 10s`：
>
> ```
> InvalidMixinException: The specified mixin 'org.mtr.mixin.PlayerTeleportationStateAccessor' was not found
> Caused by: java.lang.ClassNotFoundException
>   ... MinecraftGameProvider.launch -> Knot.launch
> ```
> 崩在**最早期**（连世界都没加载）⇒ 10 秒就结束，看起来像"配置错"或"代码错"。

## 1. 真因：`build/classes/java/main` 只有 238 个 class（应有 664），`org/mtr/mixin/` 整个不存在

```
删除前：build/classes/java/main 里 238 个 .class，顶层包只有 org/mtr/mod/**
        org/mtr/mixin/           ← 不存在
        （mtr.mixins.json 却声明了 PlayerTeleportationStateAccessor 等 5 个 mixin）
```

Mixin 是在启动时**按类名从 classpath 加载**的（`MixinInfo.loadMixinClass`）。
`mtr.mixins.json` 里写了这个类、而类文件不在输出目录里 ⇒ `ClassNotFoundException` ⇒ 启动即死。

## 2. 为什么这个坑特别费时间：**两个都在骗人的信号**

### 2.1 `compileJava` 报 `UP-TO-DATE`，但输出是残的

```
> Task :fabric:compileJava UP-TO-DATE
BUILD SUCCESSFUL in 14s
```

Gradle 的增量状态与磁盘上的输出目录**不一致**：任务自认不用跑，而输出少了 426 个类。
于是"编译过了"这句话在这里**不代表类文件存在**。

**判据（别靠 Gradle 的话，去数文件）**：

```powershell
(Get-ChildItem -Recurse -File mmtr\game\fabric\build\classes\java\main -Filter *.class).Count   # 应 ≈ 664
Test-Path mmtr\game\fabric\build\classes\java\main\org\mtr\mixin                                # 应为 True
```

### 2.2 `check-java-compile.ps1` 给**假绿灯**（这个才是真正误导人的）

它 `OK - 450 source file(s), 662 class file(s)`，可它把类编译到 **`sandbox\javac-out`**，
**完全不碰 `build/classes`** —— 所以它对"运行时类路径完不完整"**没有任何发言权**。
它的绿只说明"源码彼此自洽"，不说明"游戏能起来"。

> 这正是脚本自己那句注释警告的同类问题（引擎 jar 比 classes 旧 ⇒ 结论只对旧引擎成立）。
> 现在多了一条同族：**这个绿对 `build/classes` 的完整性不成立**。

## 3. 修法（一条命令，实测有效）

```powershell
Remove-Item -Recurse -Force mmtr\game\fabric\build\classes\java\main
cd mmtr\game
.\gradlew.bat :fabric:compileJava --console=plain --no-daemon    # 这次真的会跑
```

删掉输出目录后 Gradle 无法再自认 UP-TO-DATE，于是真编译：

```
> Task :fabric:compileJava          ← 不再是 UP-TO-DATE
BUILD SUCCESSFUL in 23s
```

**结果**：664 个 class、`org/mtr/mixin/` 6 个类全都回来了；随后 `:fabric:runServer`：

```
[12:02:41] Done (3.532s)! For help, type "help"
[12:02:41] Found available port: 8888
```

本 boot 的 `InvalidMixinException` / `ClassNotFoundException` 计数 = **0**。

## 4. 排除掉的两个诱因（记录一下，省得下次又去查）

1. **不是"Yarn 名字写错"**。`PlayerTeleportationStateAccessor.java` 用的是
   `@Mixin(ServerPlayerEntity.class)` + `@Accessor("inTeleportationState")` —— 这是 **Yarn** 名字，
   而 `fabric/build.gradle` 用的正是 `net.fabricmc:yarn:...:v2`，所以**源码是对的**。
   ⚠ 我一度去翻了 `minecraft-merged-...loom.mappings...jar`，那里面是 **Mojang 名字**
   （`net.minecraft.server.level.ServerPlayer` / `isChangingDimension`），**对 fabric 模块没有决定权** ——
   差点据此"修"一个没坏的文件。**查映射名先确认 `build.gradle` 选的哪套映射。**
2. **不是我的任务 UI 改动**。那次改动只动 `fabric/.../render/MmtrTaskHud.java`（新）、
   `sound/MmtrTaskSounds.java`（新）、`MmtrDriverHud.java`、`InitClient.java` —— 不碰 mixin、
   不碰构建脚本、不碰 `mtr.mixins.json`。

## 5. 时序（解释"为什么会残"）

| 时刻 | 事件 |
| --- | --- |
| 11:59:01–11:59:02 | `build/classes/java/main` 里那 238 个类被写出（**只写了一部分就停了**） |
| 11:59:33 | 6 个 mixin 源文件里有 3 个 mtime 变成 11:59:33（晚于输出） |
| 11:59:42 | `runServer` 启动 → mixin 加载失败 → 崩 |

输出比源文件**旧**，且**少**：典型的"增量编译中断/状态错乱"留下的残树。
（具体是哪一步中断的没有定论；但"输出残 + UP-TO-DATE"这个组合本身就足以解释现象，
所以修法是**强制重编**，而不是去猜中断原因。）

## 6. 可复用的判据（写进习惯）

- 改完代码要跑游戏，**先数 class 再启动**：`build/classes/java/main` 的 `.class` 数应与
  `check-java-compile` 报的 class 数**同量级**；差了就是残树。
- `compileJava UP-TO-DATE` + 启动崩 ⇒ 第一反应是**输出残**，而不是"代码错"。
  直接 `Remove-Item build/classes/java/main` 再编译，比读栈更快（栈只给你 `ClassNotFoundException`）。
- **混入方式（mixin）的失败会在最早期出现**：崩溃日志里出现
  `MixinConfig` / `prepareMixins` / `loadMixinClass` 这类帧，就与业务代码无关，别往业务里查。
- `check-java-compile.ps1` 的绿**只覆盖源码自洽**；它是"改完先看有没有拼写/类型错"的快筛，
  **不是"游戏能起来"的证明**。签名/版本/类路径完整性问题它一律看不见。

## 7. 顺手加的一道守卫（已红绿双向验证）

`mmtr/scripts/check-java-compile.ps1` 末尾新增一段**残树告警**（与它原有的"引擎 jar 比 classes 旧"
同一个位置、同一个风格）：**逐个源文件**检查 `build/classes/java/main` 里有没有对应的 `.class`，
缺了就点名列出前几个，并给出那条 `Remove-Item` 修法。**只警告、不改退出码**。

> ★ **判据必须是"每个源文件都有 class"，不能是"class 总数差不多"**：
> 我第一版写的是"build 的 class 数 < 本次产出的 90% 就告警"，**红测证明它看不见这次故障** ——
> 把整个 `org/mtr/mixin/`（6 个类）藏起来只占 664 的 **0.9%**，比例怎么设阈值都过，
> 而崩掉启动的恰恰就是这 6 个。改成逐文件检查后：
>
> ```
> 健康树：OK - 450 source file(s), 662 class file(s)          （无告警）
> 藏掉 mixin：⚠ 警告：build/classes/java/main **缺 6 个源文件的 class**（共检查 450 个）
>             org\mtr\mixin\PlayerTeleportationStateAccessor.java
>             修法：Remove-Item ... ; .\gradlew.bat :fabric:compileJava ...
> ```
>
> 一次误报都没有（红/绿两边都实跑过）—— 这点很重要：一个会误报的守卫，最后的下场是被无视。

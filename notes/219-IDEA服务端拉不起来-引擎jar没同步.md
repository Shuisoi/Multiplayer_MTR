# 219 · IDEA 里服务端拉不起来 = 引擎 jar 没同步（+ 一条 Gradle 版本假警报）

日期：2026-09-21 · 结论：**不是端口、不是配置、不是信号系统** —— `game/libs/Transport-Simulation-Core-0.0.1.jar`
还是 15:38 那版，缺上两轮新加的符号，IDEA 运行配置的 **before-launch "Make"** 编译不过 ⇒ 服务端起不来。
停服同步 jar 后 `:fabric:compileJava` **BUILD SUCCESSFUL**。

用户原话：「IDEA 中服务端拉不起来」。

## 1. 排查顺序与结果（照抄给未来的自己）

| 步骤 | 命令/看什么 | 本次结果 |
|---|---|---|
| 1 | 还有 loom 进程吗（`devlaunchinjector.Main`） | 没有 |
| 2 | 端口被占吗（25565 LISTEN） | 空闲 → 不是端口冲突 |
| 3 | **`game/libs` 的 jar 时间与内容** | 15:38 那版：**缺** `MmtrHidMapping`（218）、**缺** `getMmtrHoldReasonFromSync`（217） |
| 4 | IDEA 的 runConfiguration | `Minecraft Server (:fabric)`，`MAIN_CLASS=net.fabricmc.devlaunchinjector.Main`，**before-launch = Make**（先编译模块） |
| 5 | 用 game 自己的 wrapper 编译 | 同步 jar 后 `:fabric:compileJava` **BUILD SUCCESSFUL** |

**判据**：IDEA 报 `cannot find symbol: method getMmtr…FromSync()` / `MmtrHidMapping` 之类 → 一定是 jar 没同步。

## 2. 一条假警报（值得记下来，它长得一模一样）

第一次我顺手用**引擎的 Gradle 9.5.1 wrapper**去跑 game 模块，得到：

```
A problem occurred evaluating project ':fabric'.
> Could not get unknown property 'archivePath' for task ':fabric:shadowJar'
```

看着也像"服务端拉不起来"，其实与 jar 无关：**game 模块 pin 的是 Gradle 8.14**，而 `shadowJar.archivePath`
在 Gradle 9 已被移除（8.x 起就废弃）。IDEA 用的是 wrapper（8.14）⇒ 用户不会遇到这一条。

顺手把它换成 Gradle 9 也认的写法（`game/fabric/build.gradle`）：

```groovy
inputFile = shadowJar.archiveFile.get().asFile     // 原来是 file(shadowJar.archivePath)
```

**教训**：跨模块调用 Gradle 一定要用**那个模块自己的 wrapper**；用错版本得到的报错会把你带到完全无关的方向。

## 3. 规矩写全（护栏的两面）

- notes/216：**运行中不要同步** jar（会把坏 zip 交给懒加载的进程）——护栏在 `sync-engine.ps1` 里；
- notes/219（本条）：**引擎改了就要同步**，否则 IDEA 的 Make 编译不过 —— 这一步没有护栏，
  只能靠"改完引擎 → 停服 → `sync-engine.ps1` → 再跑"这个顺序记忆；
- 两句话合起来：**同步只发生在"没人跑"的窗口里**，用之前同步、用之后停服再同步。

## 4. 正式顺序（引擎有改动时）

```bat
:: 1) 停掉 IDEA 里的 server/client（两个 java 进程）
mmtr\scripts\sync-engine.ps1        :: 停服状态下同步（运行中会被护栏拒绝）
:: 3) 回 IDEA 跑 Minecraft Server (:fabric) / Client
::    若 IDEA 还报旧错：Build → Rebuild Project（它会缓存 classpath）
```

本次验证：`game` 自己的 Gradle 8.14 跑 `:fabric:compileJava` → **BUILD SUCCESSFUL**（20 s，4 tasks）。

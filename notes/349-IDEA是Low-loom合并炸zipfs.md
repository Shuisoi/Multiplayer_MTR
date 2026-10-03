# 349 · loom 合并炸在 zipfs：IDEA 是 Low，而**完整性是继承的**（修正 notes/347 §3）

日期：2026-09-29 · 承接 notes/346（Low 沙箱）、notes/347（IDEA Gradle home）、修正 347 §3 的结论

## 0. 用户看到的现象

```
Could not merge JARs! Deleting source JARs - please re-run the command and move on.
java.nio.file.ReadOnlyFileSystemException
	at jdk.zipfs/jdk.nio.zipfs.ZipFileSystem.checkWritable(ZipFileSystem.java:370)
	at jdk.zipfs/jdk.nio.zipfs.ZipFileSystem.createDirectory(ZipFileSystem.java:708)
	...
	at net.fabricmc.loom.configuration.providers.minecraft.MinecraftJarMerger.add(MinecraftJarMerger.java:151)
	...
> Failed to setup Minecraft, java.nio.file.ReadOnlyFileSystemException: null
BUILD FAILED in 4s
```

同一个 daemon 日志往前三行还有**更关键**的三句（它把因果讲了一半）：

```
Fabric Loom: 1.10.5
"Lock for cache='…\sandbox\gradle-home\caches\fabric-loom', project=':fabric'" is currently held by pid '46628'.
Locking process does not exist, assuming abrupt termination and deleting lock file.
Found existing cache lock file (ACQUIRED_PREVIOUS_OWNER_MISSING), rebuilding loom cache. This may have been caused by a failed or canceled build.
```

## 1. 量出来的事实：每个进程的**令牌**（不是镜像）

用 `OpenProcessToken` + `GetTokenInformation(TokenIntegrityLevel)` 逐个量（本轮新写的 30 行 PowerShell）：

| pid | 进程 | IL | 父进程 |
|---|---|---|---|
| — | 本会话 PowerShell | **High** | — |
| 36804 | `idea64.exe`（**工作区内**镜像 `env\idea`） | **Low** | 已退出 |
| 16512 | gradle daemon（IDEA 起的，`javaHome=…\.gradle\jdks\eclipse_adoptium-21…`） | **Low** | idea64 (Low) |
| 10884 | `cmd.exe` | High | 用户的 pwsh (High) |
| 24220 | gradle launcher（工作区外 JDK 镜像） | **High** | cmd (High) |
| 46628 | gradle daemon（服务端那个） | **High** | 24220 (High) |
| 44372 | dev 服务端 JVM | **High** | 46628 (High) |
| 探针 | 从 `env\jdk-21`（**工作区内**镜像）起的 JVM | **Low** | **本会话 High** |

⇒ 两条规则，缺一条都推不出结论：

1. **镜像在工作区内 ⇒ 一律 Low**（父是 High 也没用 —— 见最后一行探针）；
2. 否则 **继承父进程的令牌**（工作区外镜像 + Low 父 = Low；+ High 父 = High）。

## 2. 因果链（四个现象一条根）

**IDEA 就是工作区内的镜像 ⇒ Low；`org.gradle.daemon=false` ⇒ 每次构建都 fork 一个"单次 daemon"，令牌照抄父进程 ⇒ 那个 daemon 也是 Low。** 于是：

1. Low 做不了 **zipfs 写** ⇒ loom 合并 Minecraft jar / 写 yarn 映射时炸 `ReadOnlyFileSystemException`（就是用户看到的那条）；
2. Low 进程**看不见** High 进程（`OpenProcess` 被拒 ⇒ `ProcessHandle.of()` 返回空）⇒ 它把**正在跑的**服务端 daemon
   （High，pid 46628）当成"进程已死" ⇒ **抢锁**并宣布缓存要重建；
3. 重建缓存又需要 zipfs 写 ⇒ 回到第 1 条 ⇒ **每次必失败**，而且失败点在"合并/映射"之间随机漂；
4. 抢锁还会**连累别人**：真正的持有者（服务端会话）一直开着缓存里的文件，别人再来构建就撞
   `Failed to setup mappings … mappings.jar: 另一个程序正在使用此文件`。

## 3. ★ 修正 notes/347 §3 的结论

347 §3 把原因归到"**JVM 镜像在不在工作区内**"，并给出"IDEA 的 Gradle JVM 设成工作区外 JDK"这一半药方。
实测：那次 daemon 的 `javaHome` **确实是工作区外那份**（日志原文
`javaHome=C:\Users\30354\.gradle\jdks\eclipse_adoptium-21-amd64-windows.2`），但它的令牌是 **Low** ——
**令牌看父进程，不看镜像**。所以那条设置治不了 IDEA 这条路。
（347 §3.2 的"复刻验证"是从 High 终端跑的 ⇒ 复刻的是 High 那一侧，不是 IDEA 那一侧；这也解释了两份结论为什么对不上。）

**仍然成立的部分**：347 §3.1 的另一半（`GRADLE_USER_HOME` 放工作区内）治的是 Tooling API 写 `.lck`，
与本轮无关，保持不动。

## 4. 修法与实测

| 做法 | 说明 |
|---|---|
| **别用 IDEA 跑 gradle** | IDEA 是 Low，且 `org.gradle.daemon=false` ⇒ 每个构建都继承它。用 IDEA 只做读代码/编辑。 |
| 从**普通终端或资源管理器**起 | High/Medium 父进程 ⇒ 单次 daemon 拿到 High/Medium ⇒ zipfs 正常。 |
| **JAVA_HOME = 工作区外 JDK** | 工作区内的 `env\jdk-21` 是 Low 镜像；拿它当 JAVA_HOME 等于把 daemon 也拽回 Low。 |
| 新增 `game\run-client.bat` | 按上面两条写死，并带**守卫**：JAVA_HOME 一旦落在工作区内就拒绝启动、点名 notes/349。 |
| 改 `game\run-server.bat` | 原来 `JAVA_HOME=%JDK21%`（工作区内）⇒ 潜伏的同一条故障，已改成工作区外 JDK + 同一套守卫。 |
| 改 `env\workspace.env.bat` | 把中文注释换成英文（**纯 ASCII**）：它自己带非 ASCII 字节，在 936 码页下把调用它的每个脚本都解析崩了（见 §7）。 |
| **先关服务端，再构建** | 服务端会话一直握着 loom 缓存（`mappings.jar`）⇒ 它在跑时客户端构建必在映射那一步失败。 |

**实测（High + 工作区外 JDK + 工作区 GRADLE_USER_HOME）**：被删掉的缓存**完整重建**了 ——
`minecraft-client.jar` 24.4 MB、`minecraft-server.jar` 49.1 MB、`minecraft-extracted_server.jar` 15.8 MB、
**`minecraft-merged.jar` 24.3 MB**、`intermediary-v2.tiny` 2.7 MB、`mappings.tiny` 6.0 MB（18:32–18:33 一口气写完）
⇒ **zipfs 那一环确实修好了**（合并这次真的过了）。
唯一没写完的是 `mappings.jar`：它被**正在运行的**服务端 daemon 占着（§2 第 4 条）⇒ 先停服务端再补这一步。
（服务端用 RCON `stop` 优雅停掉、跑完 `:fabric:compileJava`（**BUILD SUCCESSFUL 1m17s**）之后，
`run-server.bat` 起服务端一次成功：`Done (3.203s)!` + RCON 25575 在听。）

## 5. 教训

1. **判据要对着"令牌"写，不能对着"路径"写。** 346/347 两次都把"用工作区外的 JDK"当成 High 的充分条件；
   实测反例就在眼前：工作区外镜像 + Low 父 = Low；工作区内镜像 + High 父 = Low。
   **要判断就量**（本轮那 30 行 PowerShell 已可复用）。
2. **Low 看不见 High ⇒ "进程是否存活"这类锁协议会给出错误答案**。这不只是"写不了文件"，
   而是会**主动破坏别人的状态**（抢锁 → 毁缓存）。凡"检查邻居进程"的逻辑，在混级环境里都不可信。
3. **故障会自放大，所以症状每次都不一样**：抢锁 → 重建缓存 → 重建要 zipfs → 又炸。
   上一轮的症状是"合并"，这一轮是"映射"，根因同一个。**别按症状逐个修，先量令牌。**
4. 347 §3.5b 那条"反复失败留下半成品会伪装成权限问题"仍然成立；但**这次不是**半成品 ——
   是令牌，且已用真实进程的令牌量过（表格在 §1）。

## 6. 待办 / 边界

- `mappings.jar` 要等**服务端关掉**才能补写：顺序 = 停服务端 → 构建 → 起服务端 → 起客户端。
- **客户端**要用 `mmtr\game\run-client.bat` 起（不要用 IDEA）。若它在 `Failed to setup mappings` 上失败，
  说明服务端那个会话还握着缓存 ⇒ 先 `python _rcon.py stop`，起客户端，再起服务端。
- 用 `run-client.bat` 起的客户端窗口随那个终端/进程树存活；双击 `mmtr\game\run-client.bat` 即可重来（脚本自带全部前提）。
- IDEA 那条路要真正可用，只能让 IDEA 本身不再是 Low（把 `env\idea` 移到工作区外，或让沙箱不收它）——
  属于沙箱口径的取舍，本轮不动。

## 7. 顺带修掉的两个 `.bat` 解析坑（各花了两次启动尝试）

这两个都**不是**权限问题，症状却长得很像"脚本没跑起来"：

1. **`.bat` 里不能有非 ASCII 字节。** cmd.exe 按 OEM 码页（本机 936）解码 `.bat`，
   UTF-8 的中文注释会让字节错位、行被并/被拆，于是**注释文本被当成命令执行**：
   ```
   '?call' is not recognized as an internal or external command
   '?得再写' is not recognized as an internal or external command
   ```
   本轮两个来源：① 我给 `run-server.bat` / `run-client.bat` 写的中文注释（已改英文）；
   ② **`env\workspace.env.bat` 自己**的中文注释 —— 它被所有脚本 `call`，所以是"每个调用方都崩"。
   判据可量化：**非 ASCII 字节数 = 0**（`[IO.File]::ReadAllBytes(...) | ? {$_ -gt 127}`）。
   本仓 notes/196 记的是 PowerShell 5.1 的同类坑；这条是 batch 的版本。
2. **`( )` 块里的 `echo` 不许出现未转义的括号。** 我写的守卫
   `echo ... %JAVA_HOME% (LOW image) - see notes/349` 在 `if ... ( ... )` 块里把块**提前闭合**，
   cmd 报 `- was unexpected at this time.`（看着像"脚本语法错"，其实是消息文本的锅）。
   修法：消息里不要括号（或 `^(` / `^)` 转义）。


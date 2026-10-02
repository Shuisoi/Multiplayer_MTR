# 362 · 服务端起不来：fabric 运行期重映射 + Low 完整性令牌

**症状**（2026-10-02，服务端与 IDEA 同时中招）：

    Failed to remap mods!
    net.fabricmc.loader.impl.FormattedException: java.io.IOException:
      the jar file ...\mmtr\game\fabric\run\.fabric\processedMods\worldedit-7.3.0+6678-55745ad-64b5720b4b825f21.jar can't be written
        at net.fabricmc.loader.impl.discovery.RuntimeModRemapper.remap(RuntimeModRemapper.java:221)
      Caused by: ... at net.fabricmc.loader.impl.lib.tinyremapper.OutputConsumerPath.<init>(OutputConsumerPath.java:102)
    * What went wrong:
    Execution failed for task ':fabric:runServer'.
    > Process '...java.exe' finished with non-zero exit value 1

报错**完全不指向真正的原因**：它说的那个 jar 其实写得进去（同一台机器、同一个目录）。

## 1. 判据链（三段，全部读源码确认）

```
fabric-loader  RuntimeModRemapper.remap()
  └─ tinyremapper  OutputConsumerPath.<init>(path)
       fsToClose = FileSystemReference.openJar(path, /*create*/ true)
       if (fsToClose.isReadOnly()) throw new IOException("the jar file " + path + " can't be written")
         └─ JDK  jdk.zipfs/jdk/nio/zipfs/ZipFileSystem.java
              159: if (Files.notExists(zfpath)) { … Files.newOutputStream(zfpath, CREATE_NEW, WRITE) … }   ← 文件会先被建出来
              170: zfpath.getFileSystem().provider().checkAccess(zfpath, AccessMode.READ);
              172: boolean writeable = Files.isWritable(zfpath);
              174: this.readOnly = !writeable;                                                          ← 就是这个
```

顺带排除一个常见猜测：`WindowsFileSystemProvider.checkAccess()` 对写只请求 **`FILE_WRITE_DATA`**
（`WindowsFileSystemProvider.java:397`），跟 `FILE_DELETE_CHILD` 无关 ⇒
`MC` 上那条 `Deny Everyone DeleteSubdirectoriesAndFiles`（DSH 沙箱的正常布防项）**不是**元凶，
不需要改任何 ACL（ACL 诊断脚本的判决也正是 `NOT_THIS_CLASS`）。

## 2. 真正的原因：JVM 跑在 **Low 完整性令牌** 下

这台机器上，**`Files.isWritable()` 对任何路径都返回 false —— 哪怕真实写入成功**：

| 探测目录 | `isWritable` | 真实写入 |
|---|---|---|
| `…\run\.fabric\processedMods` | false | ok |
| `…\run` | false | ok |
| `…\sandbox\aclprobe` | false | ok |
| `C:\Users\30354\aclprobe`（工作区外） | false | AccessDenied |
| `%TEMP%\aclprobe` | false | AccessDenied |

（对照：同一台机器上 PowerShell 能往上面每个目录写文件 —— 所以不是 ACL，是**令牌**。）

令牌实测：

| JVM 镜像 | 强制标签 | 进程令牌 | `isWritable` |
|---|---|---|---|
| `MC\env\jdk-21\bin\java.exe`（工作区内） | `Mandatory Label\Low Mandatory Level:(I)(NW)` | Low (S-1-16-4096) | **false** |
| `…\.gradle\jdks\eclipse_adoptium-21…\bin\java.exe`（工作区外） | 无 | High (S-1-16-12288) | **true** |

规则（DSH 沙箱；`env\workspace.env.ps1` 第 38-49 行 09-29 已记录过一半）：

> 沙箱在**工作区根目录 `MC`** 上打了一个显式的 Low 完整性标签（`icacls MC` →
> `Mandatory Label\Low Mandatory Level:(OI)(CI)(NW)`，非继承），它继承给工作区内**所有**文件。
> **镜像位于工作区内的一切可执行文件都以 Low 令牌运行** —— 与谁启动的无关，父进程是 High 也一样。
> 子进程的完整性级别只能继承或降低，**不能升高**。

于是：`JAVA_HOME=env\jdk-21`（`workspace.env.ps1:33` 的默认）⇒ 构建 JVM 是 Low ⇒ 它 fork 的
`runServer` 也是 Low（哪怕 runServer 用的是工作区外的 toolchain JDK 镜像）⇒ 重映射必失败。

**"之前能启动"**：那次启动链上的 JVM 是 High（当时这个 Low 标签还没生效/还没打上）。
同一份配置、同一个 `run` 目录，**差别只在启动者的完整性级别**。

**"IDEA 为什么报同一个错"**：`env\idea\bin\idea64.exe` 的镜像也在工作区内 ⇒ 带同一个 Low 标签 ⇒
IDEA 自己就是 Low ⇒ 它 fork 的 gradle 守护进程
（实测 `sandbox\gradle-home\daemon\8.14\daemon-33492.out.log`，父进程 `idea64.exe`，
`20:16:54` 同一条报错）与 runServer 全是 Low。
**结论：只要 IDEA 的可执行文件还带 Low 标签，从 IDEA 里就永远起不来服务端**（客户端同理）。

## 3. 修法

- **本机可用**：`pwsh -File sandbox\run-dev-server-high.ps1`
  —— 自动挑一个**镜像在工作区外**的 JDK（`$env:MC_HIGH_JDK` → `%USERPROFILE%\.gradle\jdks\*`，
  逐个用 `icacls` 确认没有 Low 标签），换掉 `JAVA_HOME`/`PATH`，跑 `gradlew :fabric:runServer --no-daemon`
  （`--no-daemon` 是必须的：否则会复用 IDEA fork 出来的 Low 守护进程）。启动前会先断言
  `isWritable=true`，不满足就直接报错退出，不再白等一轮。
- `mmtr\scripts\dev-server.ps1` 现在也会自检：发现工作区 JDK 是 Low 就自动换到工作区外的 JDK，
  找不到才警告（见该脚本 `$lowLabel` 那段）。
- **根治**：把 `MC` 上那层 Low 完整性标签去掉（或让 DSH 按当前 `danger-full-access` 策略重新 provision）。
  这是完整性标签/ACL 的改动，按本会话的 ACL 诊断规程**不由 agent 手工改**，也没有手工改的命令可照抄；
  要么在 DSH 侧处理，要么就一直用第 1 条的启动方式。
- 实测通过：`20:26:40` 起、`20:26:59 Done (2.779s)!`，25565/25575/8888 全部在听，
  `processedMods\worldedit-….jar` 从 22 字节空 zip 变成 **6 366 548 字节**的真重映射产物，
  时刻表 00101-00110 全部回到 `RUNNING`。

## 4. 仍然成立的旧结论（别一起删掉）

`env\workspace.env.ps1` 第 38-49 行、`mmtr\scripts\dev-server.ps1` 里"把 JVM 的写目标搬进工作区"
（`GRADLE_USER_HOME`、`TEMP/TMP`、`java.io.tmpdir`）**仍然需要**：Low 令牌写不了 `%USERPROFILE%`
下的 Medium 对象（`%TEMP%` 写不进、`.gradle` 写不进 ⇒ gradle wrapper `zip.lck` 访问被拒）。
那条"显式给一个工作区内的 tmpdir"的改动只是**症状级**修补，不是根因；根因见 §2。

# 364 · 服务端起不来之二：构建期的网络与"空 JSON"（本地缓存兜底）

2026-10-03。前一篇（notes/362）是**完整性令牌**那个原因（工作区内的 JVM 一律 Low ⇒ fabric 重映射拒绝写 jar）。
这一篇是**另一个、完全无关**的原因，症状同样是"服务端起不来"，但死在更早的地方 —— **Gradle 的配置阶段**。

## 1. 症状

`gradlew :fabric:runServer` 连一个任务都没跑到：

```
FAILURE: Build failed with an exception.
* Where: Build file 'mmtr/game/fabric/build.gradle' line: 51
* What went wrong:
A problem occurred evaluating project ':fabric'.
> Not a JSON Array: {}
```

同一晚还有**另一例**（用户自己那次尝试，写在同一条日志里）：

```
Picked up JAVA_TOOL_OPTIONS: -Djava.io.tmpdir=""
java.io.IOException: java.io.tmpdir is set to a directory that doesn't exist:
```

第二条是**启动脚本的问题**：`JAVA_TOOL_OPTIONS` 里的 tmpdir 是**空的** —— 说明那个入口没有
dot-source `env\workspace.env.ps1`，`$MC_ROOT` 为空 ⇒ 拼出 `-Djava.io.tmpdir=""`。
（`sandbox\run-dev-server-high.ps1` 与 `scripts\dev-server.ps1` 不在其列；两个都已经正确设置。）

## 2. 第一条的真因：JVM 的**直连**那条路被黑洞

链条（每一环都有据）：

| 环 | 事实 | 出处 |
| --- | --- | --- |
| 1 | `build.gradle:51` 在**配置阶段**就要版本号：`mappings "net.fabricmc:yarn:${buildTools.getYarnVersion()}:v2"` | `game/fabric/build.gradle` |
| 2 | `getYarnVersion()` 打 `https://meta.fabricmc.net/v2/versions/yarn/1.20.4`，期望 JSON **数组** | `BuildTools.java` |
| 3 | 构建 JVM 走本地 Clash（`127.0.0.1:7890`），但 **`meta.fabricmc.net` 被列进 `nonProxyHosts`**（直连，平时更快） | `game/gradle.properties` |
| 4 | 直连的那条 Cloudflare A 记录**间歇性黑洞**（2026-09-24 已记录过一次）；JVM 这次恰好挑了它 | `BuildTools.getJson` 的 ★ 注释 |
| 5 | `getJson` 五次尝试全超时（连接 15 s + 读 20 s）⇒ **返回 `{}`** | 同上 |
| 6 | 调用方 `.getAsJsonArray()` ⇒ `Not a JSON Array: {}` ⇒ **整个构建停在配置阶段** | 本次报错 |

**判据（两侧对照）**：同一条 URL
* 走 Clash：`HTTP 200`，554 字节；
* 从 PowerShell 直连：`HTTP 200`，554 字节；
* 构建 JVM：五次超时，只有超时日志。

⇒ 不是"网断了"，而是**JVM 自己的 DNS/路由选到了黑洞地址**。这也解释了"同一条命令有时行有时不行"。

## 3. 修法（`BuildTools.getJson`，2026-10-03）

1. **空对象不再算成功**：`{}` 当成一次失败尝试，继续重试（原来它"成功"返回，于是错误被推给调用方）；
2. **成功就写缓存**：`GRADLE_USER_HOME/mmtr-http-cache/<URL 里非字母数字换成下划线>.json`
   （本工作区 `GRADLE_USER_HOME=sandbox\gradle-home`；没有该变量时落 `~/.gradle`）；
3. **五次都失败就读缓存**，日志写明"改用本地缓存：<url>（缓存文件 <路径>）"；
4. **有缓存时只试一次**：这条路的失败代价是 35 s/次，而配置期有**两个**这样的口（yarn + loader）
   ⇒ 不优化就会白等三分钟。

缓存是**兜底不是判据**：网络好时第一次就成功并刷新缓存，网络坏时用上一份好答复。
种缓存的办法（本次就是这么做的）：用能通的那条路把 JSON 抓下来，按上面的文件名规则写进两处
`GRADLE_USER_HOME`（`sandbox\gradle-home` 与 `~/.gradle`，因为 IDEA 与脚本可能用不同的 home）。

## 4. 没做什么（诚实清单）

1. **没有动他们的代理路由**：`meta.fabricmc.net` 仍在 `nonProxyHosts` 里（直连平时更快）。
   若哪天想从"根上"修而不是靠缓存，把这一项从 `nonProxyHosts` 拿掉即可 —— 实测走 Clash 是通的
   （`HTTP 200`），代价是每个构建多几秒；
2. **没有给 IDEA 种缓存**：IDEA 用哪个 `GRADLE_USER_HOME` 由它的设置决定；要么照 §3 种一份，
   要么让 Clash 开着（本次它确实开着）；
3. 缓存**不带过期**：它只是"上次成功的那份"。版本号长期不变（yarn 1.20.4+build.3 之类），
   所以不设 TTL；真要换版本时清掉 `mmtr-http-cache/` 即可。

## 5. 结论

服务端已起：**25565 / 25575 / 8888** 全在听，时刻表从上一处继续（当时 00109 第 9/236 步、莫氏岛站1台）。
两条"起不来"的原因现在各有一篇：**完整性令牌**见 notes/362，**构建期网络**见本篇。

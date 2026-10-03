# 316 · 给 mod 加 `rail remove` —— 并顺手把爬坡段（x=1020→2300，y 65→75）做完

> 用户口径（2026-09-26）：**「为啥敲不了？」** → **「可以加吧」**。
> 承接 notes/315：改爬坡需要先删掉 x=1020..2300 的旧 y=65 轨，而**删轨只能人手动敲**。

## 1. 先回答"为啥敲不了"：**是我把区块卸载了**

* 我上一轮结尾跑了 `forceload remove all` —— 那一带就不再加载，方块自然敲不到
  （用命令的话是 `That position is not loaded`）。
* 更糟的是我随后想重新加载时写了个 **328 区块**的框，而
  **`forceload add` 一次最多 256 个区块** —— 超了**不报错、直接不生效**，
  于是我第一次复查时 `(1500/1900/2200)` 全是"既不是 rail 也不是 air"，看着像方块没了。
* 改成 `forceload add 1008 1792 2319 1823`（**164 区块**，在上限内）后，
  24 个节点**全部在位**。

⇒ 经验：**`forceload add` 有 256 上限，超了静默失败**；卸载后别指望还能改那一带。

## 2. 加了什么：`rail remove <x> <y> <z>`

改动三处（引擎 + 游戏端）：

| 文件 | 改动 |
| --- | --- |
| `engine/.../command/MmtrRailCommands.java` | 新增 `remove` 动词 + `parseThree()`；与 `add` 一样只**校验并转交游戏端** |
| `engine/.../command/MmtrCommandDispatcher.java` | 用法表加一行 `rail remove` |
| `game/.../mmtr/MmtrCommandExecutor.java` | 新增 `executeRailRemoveCommand()`：**方块换空气 + `PacketDeleteData.sendDirectlyToServerRailNodePosition`** |

**关键：它做的两件事与玩家敲掉节点方块逐字相同** ——
`BlockNode.onBreak2` 里那一行就是
`PacketDeleteData.sendDirectlyToServerRailNodePosition(ServerWorld.cast(world), Init.blockPosToPosition(pos))`。
所以引擎侧行为**必然与手敲一致**，不会出现"方块没了、轨还在"的幽灵轨。

**幂等**：节点已不在时回报`不是 mtr:rail 节点方块（当前 …），未做改动`，不当失败。

### 2.1 实测

```
删前 114 条 → rail remove 2200 65 1800 → 删后 112 条   （掉 2 = 左右两段，与敲方块一致）
游戏端回报: [rail] 已删节点 (2200,65,1800) 及其上所有轨
```

## 3. 构建与部署（这一步有几个坑）

| 步骤 | 命令 | 说明 |
| --- | --- | --- |
| 引擎 | `mmtr\engine\gradlew.bat -p mmtr\engine shadowJar` | 产出 `engine/build/libs/Transport-Simulation-Core-1.0.0.jar` |
| 游戏 | `mmtr\game\gradlew.bat -p mmtr\game :fabric:compileJava` | 两边都 **BUILD SUCCESSFUL** |
| 换 jar | `mmtr\scripts\sync-engine.ps1` | 把新 jar 覆盖成 `game/libs/Transport-Simulation-Core-0.0.1.jar` |
| 重启 | `mmtr\scripts\dev-server.ps1` | 服务端 |

**踩到的三个坑**：

1. **`JAVA_HOME` 指向 JRE 1.8**（`C:\Program Files\Java\jre1.8.0_431`），Gradle 直接拒绝。
   ⇒ 必须先 `. .\env\workspace.env.ps1`（它导出 `$JDK21` 并设好 `JAVA_HOME`）。
2. **换 `game/libs` 的 jar 必须先停掉所有 loom 进程**（notes/216）：
   Fabric 懒加载那个 jar，运行中覆盖会让它读到坏 zip
   （现场表现是"能上驾驶室但车一动不动"，最难查的一类）。
   `sync-engine.ps1` 自带这个守卫 —— 它**正确地拒绝**了一次，
   因为**用户的 dev 客户端**还在跑（PID 17132，`-Dfabric.dli.env=client`）。
   用户关掉客户端后才同步成功。
3. **本机服务端是从 IntelliJ IDEA 起的**（父进程是 gradle daemon ← `idea64.exe`），
   所以引擎的 `server restart` 指令**不会被 dev-server.ps1 接管**。
   本次是 `server stop` 停掉、`sync-engine.ps1` 换 jar、再用 `dev-server.ps1` 拉起来。

## 4. 顺手把爬坡段做完了（notes/315 那一段）

现在不用人敲了，全部脚本完成：

| 步骤 | 结果 |
| --- | --- |
| ① `rail remove` × 24（x=1100..2200 × 两轨） | 引擎 **112 → 88**（= 114 − 26）✅ |
| ② 清掉 x=2300 上残留的空节点（y=65，已无轨） | 2 条 `rail remove`，回报即幂等 |
| ③ `rcon_batch --file build_x2300_ramp_fills.txt` | **27/27，被拒 0**（重连 36 次） |
| ④ `push_rail_commands.py build_x2300_ramp_rails.txt` | 26 条，引擎受理 26/26 |
| ⑤ 验收 | 引擎 **114 条**（88 + 26）✅ |

**轨面剖面**（与 notes/315 设计的逐点一致）：

```
x:   1020 1100 1200 1300 1400 1500 1600 1700 1800 1900 2000 2100 2200 2300
y:     65   66   66   67   68   69   70   70   71   72   73   73   74   75
```

* 两条轨（z=1800 / 1806）各自 **26 段、x −500..2300、断口 0**
* **28 个节点全部是 `mtr:rail`**（RCON 与存档对拍一致；其中一次 ✘ 是 RCON 掉线的假阴性，
  单独复测与存档都确认在位）
* 平均坡度 1:128

`save-all` 已落盘；`forceload` 已 `remove all`。

## 5. 同步更新了 skill（**旧结论已作废**）

skill 里原来写着"**轨删不掉**（唯一办法是人手动敲）"—— 这条**现在不成立了**。已改：

* `SKILL.md` 铁律 6 的那个方框改成"**现在有 `rail remove` 了**"，
  保留"`/setblock … air` 不触发 `onBreak2`、会留幽灵轨"这条**仍然成立**的警告；
  并补上**改标高/重分段的标准套路**：`rail remove` → fills → 新 `rail add`（**顺序不能反**）。
* `reference/failure-modes.md` §2.5 从"做不到"改成"正解 = `rail remove`"。
* 命令清单里加上 `rail remove`。

## 6. 现在的状态

```
x:  -500 ══ 车站一 ══ -280 ── … ── 800 ══ 车站二 ══ 1020 ~~~ 爬坡 1:128 ~~~ 2300 (y=75)
引擎 114 条轨道；双线 z=1800/1806；限速：站台 80、其余 160
```

## 7. 下一轮

1. **爬坡段的区间信号灯**：`gen_line_signals.py` 现在只覆盖 x=100..700，
   爬坡段还没有灯；注意**起始朝向的奇偶要接着排**。
2. **x=2300 之后**：用户之前提过要接蘑菇岛（实测在 x≈2600、地面 y≈68），
   但 y=75 比它高 7 格 ⇒ 要么下坡，要么继续找别的落点。
3. **隧道洞内照明 + 洞口造型**（x=418..577 等 4 段 + x=2046..2073 那段"只有 24% 顶"的）。
4. 车站材料统一与否（线路 `smooth_stone` vs 车站 `stone_bricks`）。

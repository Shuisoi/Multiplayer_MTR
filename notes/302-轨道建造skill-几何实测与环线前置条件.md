# 302 · 把"轨道建造"沉淀成 skill —— 并用发布的引擎 jar 实测出几何表

> 用户口径（2026-09-26）：**「我们首先要完善skill，特别是轨道建造方面」**。
> 背景：紧接着要建**超大型环状线**，而环线必须画曲线；先把轨道建造的知识固化成 skill，
> 免得环线铺到一半才发现几何/朝向/验证这些硬约束没写下来。

## 1. 产出了什么

新建 **`.dsh/skills/mmtr-track-building/`**（与既有的 `mmtr-train-modeling` 同构）：

| 文件 | 内容 |
| --- | --- |
| `SKILL.md` | 七条铁律 + 几何速查 + 四层验证循环 + 端到端流程 + 症状速查 + 环境惯例 |
| `reference/rail-geometry.md` | 判定链、**实测形状表**、朝向编码、工程用法、**复跑配方** |
| `reference/command-surface.md` | 引擎命令面原文、每命令落到哪一层、脚本分工、存档位置 |
| `reference/failure-modes.md` | 症状 → 首查 → 真因 → 现场依据（按几何/同步/fill/信号/工具/环境分六类） |

`description` 里的关键词覆盖：轨道/铺轨/曲线/半径/圆角/节点/朝向/道岔/咽喉/股道/侧线/牵出线/
站台轨/信号灯/闭塞/区间/路基/填海/forceload/rail add/RCON/环线/车站。

**外加一个可复跑的自检工具**（skill 只引用它，工具本身放 `tools/`，与 `blender-probe` 同构）：

```
mmtr/tools/rail-geometry-probe/
├── RailProbe.java   几何断言（直线 / S 弯公式 / 8 朝向 × 5 半径纯圆角 / 90° 网格 /
│                   带引线转角 / 退化 / 静默变形）
└── run.ps1          一键入口：编译 + 运行，退出码 0 = 全通过
```

```powershell
pwsh -File mmtr\tools\rail-geometry-probe\run.ps1     # 123 项检查 / 0 项失败
```

## 2. 本轮真正新拿到的东西：几何是**实测**的，不是推的

以前这套几何只有**读源码的推断**（notes/289 §1）。本轮**直接对发布的引擎 jar 跑真代码**：
用 `Rail.getAngles` + `new RailMath(...)` 跑输入、反射读回 `r1/r2/isStraight1/isStraight2/tStart/tEnd`。
复跑配方写进了 `reference/rail-geometry.md` §5。

### 2.1 一个被**推翻**的推断

读 `RailMath` 时我手工推过"90° 转角只有**一个转向**能解，反方向会掉进 `TODO 3.b` 退化成零长轨"。
**实测证伪**：4 个起始方向 × 2 个转向 = **8 个组合全部**给出干净纯圆弧
（`seg1=STR 0.00`、`seg2=ARC=πR/2`、`R` 等于设计半径）。
⇒ **环线的左右转都能做**，没有"只有一个方向能弯"的限制。
（教训：`vecDifference.rotateY` 的符号与 `deltaForward/deltaSide` 的命名都会误导手工推导。）

### 2.2 90° 转角的规则（4×5 网格 **20/20 全对**）

```
R        = min(|along|, |lateral|)
直线段长  = ||along| − |lateral||
|along| > |lateral| → 先直线后圆弧     |along| < |lateral| → 先圆弧后直线
|along| = |lateral| → 纯圆弧（直线段为 0）
```

纯 90° 圆角：起点朝 E，终点放 `(R, R)` 朝 S ⇒ `R=200` 给弧长 `314.16`，
`R=400` 给 `628.32`；带引线只要一条命令（`(0,0,h0)→(300,200,h90)` = 直线 100 + 圆弧 R200）。

### 2.3 ★ 最危险的一条：**不报错，只静默变形**

`along ≠ lateral` 时引擎**不拒绝**，而是安静地给"直线 + 更小的半径"：
`(0,0,h0)→(200,50,h90)` 出来的是**直线 150 + 圆弧 R50**，不是半径 200 的弯。
再加上"横向错开为 0 却要求 90° 转向 ⇒ 零长轨"，构成两类**无错误信息的失败**。

### 2.4 S 弯半径公式（实测 5 点吻合）

`R = (L² + o²) / (4o)`（L = 纵向，o = 横向错开）。例：L=200/o=10 → **1002.50**；
L=400/o=20 → **2005.00**；L=200/o=40 → **260.00**。

顺带纠正了源码注释的**命名陷阱**：`deltaForward` 实际是**横向**错开、`deltaSide` 是**纵向**距离
（源码注释的 `dv` 指横向）。

### 2.5 非 90° 转角带取整残差

终点坐标非整数（45° 转 R=200 的理想终点是 `141.42, 58.58`），取整后引擎补一小段直线：
`ARC 155.48 + STR 1.44`，半径 `197.97` 而非 200。**只有整数半径的 90° 圆角是干净的。**

## 3. 环线的前置条件（本轮确认）

**`rail add` 画不出曲线，原因是它把两端节点朝向钉在弦向上。**

`MmtrCommandExecutor.executeRailCommand` 里
`resolveNodeState(defaultState, positionStart, positionEnd)` 拿"起点→终点"的弦向当目标朝向
⇒ `RailMath` 永远走 case 1.a（平行且共线）⇒ 一段直线。

而形状**完全由"两端位置 + 两端方块朝向"决定**（`onEndClick` 只喂这两样给 `Rail.getAngles`），
与玩家 yaw 无关。⇒ **要脚本画曲线，必须给 `rail add` 加显式朝向入口**
（`--angle1=` / `--angle2=`），再走同一套 `Rail.getAngles → RailMath`。

**这是环线（以及任何带圆角的线）的硬前置条件**，已写进 skill 铁律 5。
本轮**没有实施该改动** —— 只把"为什么必须做"和"怎么做才对"固化下来。

## 4. 顺手核实/纠正的既有事实

| 项 | 结果 |
| --- | --- |
| 节点朝向可达值 | `0 / 22.5 / 45 / 67.5 / 90 / 112.5 / 135 / 157.5` —— **8 个，mod 180**，与 `rail.json` 的 8 分支一一对应 |
| 角度口径 | `atan2(dz, dx)` 度：E=0 / S=90 / W=180 / N=270（`Angle.java:13-77` 声明顺序即是） |
| **信号灯是另一套公式** | `FACING.asRotation()`（Direction，south=0）起算，且 `IS_22_5`/`IS_45` 是 **`EnumBooleanInverted`**（布尔极性反） |
| 信号方块 id | `signal_pole` + `signal_light_1/2/3/4`（3、4 各有 `_aspect_1/_aspect_2`）+ `signal_semaphore_1/2`，共 11 个 |
| `--siding` | 限速**写死 40**（给 `--speed=` 也没用）；`--platform` **不动限速** ⇒ 能做 160 限速的站台轨 |
| 引擎 jar 是 shaded 的 | `Rail.getAngles` 返回 `org.mtr.libraries.it.unimi...`，而**引擎源码树**里是不带前缀的 `it.unimi...` ⇒ 写探针要 import 带前缀的 |
| 本机 JDK | PATH 上 `java`=21、`javac`=25 ⇒ 编译探针要 `--release 21`；`$env:JAVA_HOME` 指向 **JRE 1.8**（无 `jar.exe`） |
| 存档位置 | `mmtr/game/fabric/run/world/`（`run/` 被 gitignore）；引擎数据在 `world/mtr/minecraft/overworld/` |
| 车辆段 | `zero` 已建，**16 条股道各 220 m**（notes/288 §6 留给用户的那一步已完成） |

## 5. 地形复核（为环线选址）

`scan_surface_area.py --x 0 --z 0 --size 1536`：**74.1% 的列是 y=62（海面）**。

- 已生成范围约 **`x=-384..384 / z=-384..720`**（再往外是未生成）。
- 海面 y=62、石砖路基惯例 `y=62..64`、轨面 **y=65**。
- ⇒ **环线无论怎么走都是跨海工程**，必须造路基或高架（没有现成陆地可用）。

## 6. 验收

| 判据 | 结果 |
| --- | --- |
| skill 被目录识别 | ✅ 会话 skill 目录已出现 `mmtr-track-building`（说明 frontmatter 解析通过） |
| **几何自检** | ✅ `pwsh -File mmtr\tools\rail-geometry-probe\run.ps1` → **123 项检查 / 0 项失败 / exit 0** |
| 路径守卫 | `pwsh -File mmtr\scripts\check-paths.ps1` → **OK，exit 0** |
| skill 目录内无机器相关绝对路径 | ✅ grep `C:\Users` / `C:\deepseek` 零命中 |
| 几何数字来源 | 全部由 `RailProbe` 对 `Transport-Simulation-Core-0.0.1.jar` 实测（不是读源码推的） |

> 顺带发现：`check-paths.ps1` 的扫描范围是 **`mmtr/` 与 `bin/`**，**不含 `.dsh/`** ——
> 所以 skill 文件不受路径守卫覆盖。本轮用 grep 单独核过；若要让守卫也管 skill，
> 把 `.dsh` 加进 `$scanRoots` 即可（本轮**未改**守卫，属范围外）。

## 7. 下一轮

按 skill 铁律 5 给 `rail add` 加**显式朝向**（`--angle1=`/`--angle2=`，用"枚举 8 种 + `getAngle` 读回
+ mod 180 取最近"实现），然后**实机铺一条 90° 圆角验证半径**（按 §2.2 的判据：弧长必须 = `πR/2`）。
通过之后再谈环线的规模与走向。

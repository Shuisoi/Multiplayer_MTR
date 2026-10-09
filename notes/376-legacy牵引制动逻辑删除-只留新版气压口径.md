# 376 — legacy 车辆牵引/制动逻辑删除（只留新版气压口径）

> 用户口径 2026-10-03：「legacy 的车辆牵引制动逻辑直接删除吧，只能用新版本的」

## 1. 现在仓里有**两套并行实现**（这就是要拆掉的东西）

| 层 | 新版（**留**） | legacy（**删**） |
|---|---|---|
| 气压规格 | `PneumaticBrakeSpec`（管压级位表 / 分配阀 / 缸压 / 缸簧 / 闸片衰减 / 黏着截断 / WSP） | `ConsistType` 的 4 个 `airPipeChargeRatePerSecond` / `airPipeDischargeRatePerSecond` / `airBrakeApplyRatePerSecond` / `airBrakeReleaseRatePerSecond` |
| 力锚 | `BrakeSpec.serviceForceNFromCylinderBar(bar,v)`、`emergencyForceN(v)`（UIC 制动重率反推） | `BrakeSpec.serviceForceN(ratio[,v])`、`emergencyForceN()`（不衰减）、`BrakeSpec(service, emergency)` 旧构造（闸片衰减关、缸压上限 3.8） |
| 合力 | `TrainPhysics.netAccelerationMps2(牵引, 制动力, v)` | `TrainPhysics.tractionAccelerationMps2` / `serviceBrakeDecelerationMps2` / `emergencyDecelerationMps2`（比例 → 力） |
| 牵引 | `TractionSpec.effortN`（F–v 两段 + 黏着取小） | `TractionSpec.fromLegacy`（旧加速度常数迁移） |
| 控制器 | `NotchedDriveController`（bar 支）、`SteplessDriveController`（bar 支）、`ThreeHandleDriveController`（bar 支） | 三者的 `air == null` 支；`AirBrakeController` 整个文件；`DefaultDriveController` 整个文件 |
| 逐车气路 | `MmtrComposition.brakeCars()` → `BrakeModel.setCars()` → `BrakeSystem`（逐车管压/缸压） | `MmtrComposition.stepAir` / `aggregate` / `averagePipePressure` / `averageCylinderPressure` / `encodeAirStates` / `applyAirStateString` / `Unit.pipePressure`·`brakeCylinderPressure`（归一化逐车气路） |
| 接线 | `Vehicle`：控制器自己持有 `BrakeModel`（任何模式） | `Vehicle.useCompositionAir`（只有 `AIR_BRAKE` 模式走旧逐车气路）、`Vehicle.mmtrBarScale` 的"没规格就按出厂满量程折算" |

**现场后果（本次删除的动因，实测见 `sandbox/saf420_param_probe`）**：SAF420 6 动 4 拖是 `NOTCHED` 车底，多节编组时
`MmtrComposition.toConsistType` 走的是"`handles == null ? null : handles.getBrakes()`"那条构造 ⇒ **编组等效车底没有气压口径**
⇒ `NotchedDriveController` 落到 `air == null` 旧支 ⇒ 制动力 = 全量 × 档/档数，管压/缸压/缸簧死区/闸片衰减/尾车滞后**全部不生效**，
HUD 的管压/缸压读的是一个从不被推进的模型。

## 2. 新规则（本次落地，写进代码而不是文档）

1. **车底必须有气压口径**：JSON 里没有任何 bar 键 ⇒ 取出厂 `PneumaticBrakeSpec.defaults()`，**并点名**
   （`[MMTR-CFG] 车底 X 没写 bar 口径 —— 按出厂气压口径（5.2/3.5/3.0 bar、倍率 2.533）补齐`）。物理永远是新版，**绝不复活旧模型**。
2. 4 个 legacy 气路速率键：删除读取 + 加入 `REMOVED_KEYS`（老配置出现即点名）。
3. 所有操纵方式（`NOTCHED` / `STEPLESS` / `AIR_BRAKE` / `THREE_HANDLE`）都只有一条路：
   档位/轴 → `BrakeCommand` → `BrakeModel` → 逐车 `BrakeSystem`。「降保升」不再有自己的状态机 —— 它本来就是"管压级位"的语义，
   级位表已经在 `PneumaticBrakeSpec` 里。
4. 归一化管压/缸压（1 = 充风）**只作镜像/HUD 读数**保留；折 bar 必走车底真实规格，删掉"没规格按出厂折算"的分支。
5. `ConsistType.FALLBACK`（世界里没装 consist-types 时的通用车）也带 bar 口径 —— 缺配置也跑新版。

## 3. 行为变化（先点名，免得当成回归）

SAF420 6 动 4 拖（10 节）**整列**档位力 —— 左边是删除前的 legacy（= 现在游戏里的样子），
右边是本次实测（`sandbox/saf420_param_probe`，逐车模型、3 s 稳态，已扣掉 3.22 kN 运行阻力）：

| 档 | 旧（legacy：465.08 × 档/8） | 新（逐车 bar，实测） | 每车 |
|---|---|---|---|
| 1 | 58.13 kN | **90.2 kN** | 9.0 kN |
| 2 | 116.3 kN | 179.7 kN | 18.0 kN |
| 4 | 232.5 kN | 321.5 kN | 32.2 kN |
| 6 | 348.8 kN | 406.3 kN | 40.6 kN |
| 7 | 406.9 kN | 431.4 kN | 43.1 kN（已撞缸压上限） |
| 8 | 465.08 kN | 438.1 kN（尾车仍在建压；平衡后 465.08） | 47.09 kN |
| EB | 574.47 kN | 574.5 kN | 58.11 / 56.45 kN |

**注意方向**：不是"变软"，而是**低档变硬**（1 档 58 → 90 kN）。原因：缸簧死区 0.3 bar 只削掉 0.638 bar 缸压的一小部分，
而 legacy 是"全量 × 档/档数"—— 1 档那份（1/8 = 12.5%）比分配阀给的（19.9% 缸压占比）小。
另一条实测：**逐车滞后**在编组里真的成立（8 档：t=0.5 s 只有头两节有缸压，t=3 s 头 6 节满 2.0 bar、尾车才 1.48 bar）。

同时暴露两个配置不自洽（本次不动，另行处理）：
① 倍率 1.5 × 满常用降差 1.5 bar = 2.25 bar > 缸压上限 2.0 ⇒ **第 7、8 档在平衡态力相同**。要让 8 档各不同：
`distributorRatio` 取 2.0/1.5 = **1.3333**（正好"全常用 3.5 bar ⇒ 缸压 2.0 bar"），或把缸压上限抬到 2.25；
② 编组黏着取 0 号车（无动力控制车）的 μ，动车那 6 节的撒砂增益在编组层丢失。

## 4. 验证口径

- 引擎用例（**必须先设 JAVA_HOME**，宿主默认是 JRE 8）：
  ```powershell
  cd mmtr\engine
  $env:JAVA_HOME = '<repo>\env\jdk-21'
  .\gradlew.bat test --offline --console=plain -Dmmtr.testForks=4 --tests <类名>
  ```
  基线日志：`sandbox/baseline-engine-tests.log`。
- 新增判据（真值表，进用例）：① 任何 `ConsistType` 的 `getBrakes()` 都不为 null；② 多节 `NOTCHED` 编组必须逐车缸压
  （车头先建、尾车滞后）；③ 档位力必须等于"逐车锚 × f(缸压)"，不再等于"全量 × 档/档数"。
- 现场探针：`sandbox/saf420_param_probe/SAF420ParamProbe.java`（编译：`env\jdk-21\bin\javac.exe -cp mmtr\engine\build\classes\java\main;<gson jar>`）。
- 编译护栏：`pwsh -File mmtr\scripts\check-java-compile.ps1`。

## 5. 阶段

S2 `ConsistType` 气压口径必填 + 删 legacy 键 → S3 `BrakeSpec`/`TrainPhysics` 删比例 API → S4 控制器收敛（删 `AirBrakeController`/`DefaultDriveController`）
→ S5 `MmtrComposition`/`Vehicle` 删归一化气路 → S6 镜像读数统一 → S7 用例改写 + 新判据 → S8 配置迁移 + 文档 → S9 全量验证。

## 6. 实施记录

### 6.1 代码（净 −516 行）

`git diff --stat`（`mmtr` 仓，2026-10-03）：

| 文件 | 增/删 | 做了什么 |
|---|---|---|
| `mmtr/AirBrakeController.java`、`DefaultDriveController.java` | **整份删除** | 各自的归一化气路状态机 / "基线控制器" |
| `ConsistType.java` | 179 | 4 个 legacy 气路速率字段+getter+读取全删（进 `REMOVED_KEYS`）；4 个构造器收敛成**唯一一个**（`PneumaticBrakeSpec` 必填）；缺 bar 键 ⇒ `DEFAULT_BRAKES` + 点名；`asHauledTrailer()` 不再传 `brakes = null` |
| `MmtrComposition.java` | −333 | `stepAir` / `aggregate` / `averagePipePressure` / `averageCylinderPressure` / `encodeAirStates` / `applyAirStateString` / `Unit.setAirState` 与归一化气路字段、`PIPE_EQUALIZATION_PER_SECOND`、`slowAirUnit` 警告全部删除；只留"这一列由哪些车组成" + `brakeCars()` + `toConsistType()`，且编组等效车底**沿用说话车的 bar 口径** |
| `ThreeHandleDriveController.java` | 120 | 本地 `stepAir`/`pipePressure`/`brakeCylinderPressure`/`syncReadingsFromModel`/旧电制动淡出分支删除，一律走 `BrakeModel` |
| `NotchedDriveController.java` / `SteplessDriveController.java` | 38 / 35 | `air == null` 比例支整段删除 |
| `BrakeSpec.java` | 29 | 2 参构造、`serviceForceN(ratio[,v])`、无速度 `emergencyForceN()` 删除 |
| `TrainPhysics.java` | 21 | `tractionAccelerationMps2(ratio,v)`、`serviceBrakeDecelerationMps2(ratio,v)` 删除；新增 `fullTractionAccelerationMps2` / `fullServiceDecelerationMps2` / `emergencyDecelerationMps2` |
| `BrakeModel.java` | 27 | `step(...)` 改成 **void**（不再有"不接管"状态）、`isPneumatic()` 删除、`air` 必填 |
| `Vehicle.java` | 196 | `useCompositionAir`（只有 AIR_BRAKE 走编组气路）删除；气压读数统一由 `mmtrPublishAirReadings()` 取自制动模型；`AIR_BRAKE` 改用 `NotchedDriveController`；镜像的 accel/decel 改走新 API |
| `vehicle.json`（schema）+ `VehicleSyncPatch.java` | 18 / 3 | 4 个 legacy 镜像字段换成 **`mmtrPneumaticSpec`**（`PneumaticBrakeSpec.encode()` 紧凑串，进稀疏补丁白名单）—— 有级/无级车底从此也有真实的 bar 刻度 |

### 6.2 配置

`config-example/consist-types.json`：删掉全部 4 个 legacy 键；给 `emu_8_notched` / `lr_stepless` / `freight_air` /
`freight_wagon` 补上完整的 bar 块（都带 `_brakeNote`，按出厂口径写全，等真车数据）。8 份车底现在都有 bar 口径。

### 6.3 实测（探针，世界那份配置）

```
FORMATION units=10  barSpec=present  service=465.08 kN  emergency=574.47 kN
BRAKE CARS: 0/7/8/9 = 45.64 kN(拖), 1..6 = 47.09 kN(动), 每节 air=present
BRAKE [formation] notch 1 = 93.44 kN(含阻力) … notch 8 = 441.27 kN(3 s 时)  EB = 577.69 kN
TAIL LAG t=3.0 s: 2.00 2.00 2.00 2.00 2.00 2.00 1.98 1.85 1.69 1.48   ← 尾车滞后真的成立
```

车底解析日志也按要求点名了：世界里那份配置里 3 份无 bar 车底 ⇒ 一条 `按**出厂口径**补齐`；4 份带 legacy 键 ⇒
一条 `已删除的旧键…它们不再被读取`。**没有一条路能退回旧模型。**

### 6.4 编号撞车说明

本轮同时另有会话在建 `notes/376-列车音效-通用模型与Web调试台.md`（音效主题）。本文件先占 376；
若两边都要保留 376，请把**音效**那一篇改成 377（它新、引用少），本文件已被引擎注释多处引用（`notes/376`）。

### 6.5 待办 / 未覆盖

- 引擎用例与 fabric 侧全量跑（S7/S9）：见 §4 的命令；用例改写已在 §6.6 完成（12 个类 + 3 个旧口径类），
  fabric 侧与会话无关的全量 `test`（含分钟级探针类）仍待跑。
- `docs/01-设计/制动系统-气压与制动力模型-设计.md` 里"三手柄/legacy 两条路"的几处状态行仍需按本篇口径重述
  （该文件当时有别的会话在改，避免撞车，留到最后）。
- SAF420 的**第 7、8 档饱和**与**编组黏着取 0 号车**两条配置不自洽（§3）未动。

### 6.6 S7 用例改写（2026-10-03 完成）

`compileTestJava` 首次报 **100 个错**（javac 上限，实际不止：另有 8 个类只是"编译得过、断言过不了"）。
全部只换口径、**不弱化断言**（旧值写在注释里备查）：

| 类 | 改了什么 |
|---|---|
| `DriveControllerTests` | 删 `testDefaultControllerMatchesLegacyBehaviour`（`DefaultDriveController` 已删）；`testAirBrakeReleaseLapApplyCycle` 重写到 `NotchedDriveController` + bar 读数；`testNotched…`/`testStepless…` 的硬数字按"缸压 → 力"重推（**无级 0.5 轴 = −0.628933 m/s²**，旧 −0.5）；牵引/惰行三处**换新控制器**（旧那台还压着 2.6 bar 缸压 ⇒ 联锁不给牵引） |
| `ConsistDynamicsAdvanceTests` | 换控制器；有界判据改成 bar 区间 `[0, 充风]/[0, 紧急限压]`；13 m/s 在 30 s 内停到 **0.0000 m/s** |
| `MmtrBrakeModelReuseTests` | 「没配 bar 键 ⇒ 不接管」整条删除，换成新规则：出厂口径、`getBrakes()` 非 null、9 档管压 3.5 bar / 缸压 3.8 bar、力 = 缸压折成的力 |
| `MmtrCompositionTests` | 全部改到 `BrakeModel` + `composition.brakeCars()`（或"控制器自己持模型"这条生产接线）；解挂/状态串往返走 `encodeState/applyState` |
| `MmtrCompositionFromCarsTests` | `aggregate` → `controller.compute(toConsistType)`；`stepAir` → 控制器的 `BrakeModel` |
| `TrainPhysicsTests` | `tractionAccelerationMps2` → `fullTractionAccelerationMps2`；"比例线性"改成**缸压线性**（缸簧以上半量程 ⇒ 半锚）+ 缸簧死区；**删掉 `TractionSpec.fromLegacy` 的"无损换算"用例与夹具**（旧键已删，换算函数不留） |
| `MmtrConsistInertiaTests` / `MmtrTrailerLoadTests` / `MmtrConsistTractionMappingTests` | 同上；`getAirBrakeReleaseRatePerSecond` → 逐车 `BrakeCar.spec().getCylinderReleaseBarPerSecond()`（br101 0.95 / p1_trailer 0.90，不许落回缺省） |
| `MmtrMirrorTests` / `MmtrMultiplayerFoundationTests` / `SimulatedRunTests` | `AirBrakeController` → `NotchedDriveController`；镜像种子改走**逐车 bar 状态串**（`BrakeModel.applyState`）—— 归一化 `setState` 在"系统还没建"的那一拍只写镜像读数、灌不进逐车 bar 状态 |
| `MmtrPneumaticBrakeTests` | `BrakeSpec` 2 参构造（已删）等价展开；「旧串/旧配置退回旧模型」改成"旧串必须**带着出厂口径**往返" |
| `MmtrTractionRampTests` | `serviceForceN(ratio)` → `serviceForceNFromCylinderBar(缸压, v)`；紧急第一拍改成"缸簧以下没有紧急力" |
| `ThreeHandleDriveTests` | 建/缓解改成**速率（bar/s）**判据（用 v = 0 量，避开电空混合）；1A 0.05 → **1.013 bar**；EB 首拍 −1.8 → **缸簧以下 = 0**、稳态 **−1.6505**；联锁判据改用 `isPneumaticHolding()`（阈值 0.335 bar，旧 0.01 归一化） |

**同一个模型露出来的新面（先点名，免得当成回归）**

1. **电空混合让"缸压 = 司机诉求"不再成立**：有电阻制动的车底在高速下 EP 阀会把缸压削到接近 0（BR101 @60 km/h 司机 8 档：电制动替掉 70 kN ⇒ 缸压只剩 **0.911 bar**；1A 甚至被整份顶掉）。量"司机那一份"要看**气 + 电总力**。
2. **紧急制动力也走缸压**：第一拍缸压只有 0.2 bar（2.0 bar/s × 100 ms）＜ 缸簧 0.3 bar ⇒ **EB 第一拍没有制动力**，2.1 s 后才顶到 4.2 bar（稳态 −1.6505 m/s² @20 m/s）。
3. **牵引联锁阈值 = 0.335 bar**（缸簧 + 1% 量程）：松闸时牵引比旧归一化口径（0.01）早放开约 0.3 s。
4. `ConsistType.getBrakes()` 永不为 null ⇒ 三手柄规格里的 `getBrakes()` 在缺 bar 键时**也不再是 null**，旧镜像串会带 PB 段。

**顺带修的现场问题**：`config-example/consist-types.json` 在 18:16 的编辑里被写成带 **7 处尾逗号**的 JSON，
Gson 严格解析直接抛（**游戏侧读配置同样失败**）—— 而且 `apply-consist-config.ps1` 当时用 `ConvertFrom-Json`
（**容忍尾逗号**）校验，于是带着坏 JSON 一路覆盖进 6 份世界配置、每次都报 JSON OK。两处都修了：

1. 配置里的 7 个尾逗号删掉（LF 与末尾换行不动，23429 → 23422 字节）；
2. `apply-consist-config.ps1` 在装机**之前**加一道 **`System.Text.Json` 严格解析**闸门（Gson 同口径）——
   坏 JSON 直接 throw，不许再进世界；
3. 已用 `-Force` 重新覆盖 6 份世界/维度配置并逐份严格校验（`STRICT OK` ×6）。

### 6.7 S9 独立验证（2026-10-03）

| 项 | 结果 |
|---|---|
| `compileJava` / `compileTestJava` | `BUILD SUCCESSFUL`（schema 重新生成，`mmtrPneumaticSpec` 落到 `Vehicle`） |
| 引擎 `org.mtr.core.mmtr.*`（**含 job/sim/probe/point/signal 子包，分钟级探针在内**） | **100 个类 / 647 用例 / 0 失败 / 0 错误**，`BUILD SUCCESSFUL in 24m 38s` |
| 引擎 `org.mtr.core.data.*` + `org.mtr.core.operation.*`（`Vehicle` 的重灾区） | **287 用例 / 3 失败**——3 条**全是 dev 世界漂移**，与本次改动无关（见下） |
| 那 3 条失败的证伪 | `MmtrConsistMultiCarPlacementTests`×2（"the long aassdd siding must exist"）与 `MmtrRouteConflictTests`×1 都要**直播 dev 世界里的 `aassdd` 股道**（`DEV_WORLD_MTR_ROOT = mmtr/game/fabric/run/world/mtr`，股道 id `-5385228036074278397`）。全仓检索该字符串：**只命中 2026-09-20 的世界备份** `run/world.bak-before-v23-20260920-205444/.../mmtr-rolling-stock.json`，**直播世界里没有**（其 rolling-stock 最后写入 09-27）。失败的断言点在世界夹具查找（`assertNotNull(siding)` / 进路状态），**根本没走到任何牵引/制动代码**；本次改动不碰世界文件、不碰 `Siding`/进路 |
| 世界配置 | 6 份（`run/world` 三维 + `saves/新的世界` 三维）都是迁移后的版本，**严格 JSON 通过** |
| 探针（Gson 直接读世界那份配置） | **零条 `[MMTR-CFG]` 点名**（8 份车底都有 bar 键、无 legacy 键）；`FORMATION barSpec=present`、逐车 `BrakeCar` 锚 45.64/47.09 kN |
| 镜像种子的一处不对称（子代理点名，本代理修） | `BrakeModel.setState`（归一化）在"制动系统还没建"那一拍原来是**写进字段就结束了** —— 现在与 `applyState` 一样落到 `pendingState`，建系统那一拍灌进去（客户端镜像每份快照种一次，丢一次就是"解挂后当成满管"那类现场） |
| 落 jar | `sync-engine.ps1` → `engine/build/libs/Transport-Simulation-Core-1.0.0.jar` → `game/libs/Transport-Simulation-Core-0.0.1.jar`（35 634 418 B）。**服务端/客户端要重启才会用上新 jar** |
| 游戏侧链接检查 | fabric/forge 已编译类里检索被删符号（`AirBrakeController` / `DefaultDriveController` / `encodeAirStates` / `serviceBrakeDecelerationMps2` / `tractionAccelerationMps2` / `isPneumatic` / `setAirState` …）：**0 命中** ⇒ 不会出现运行期 `NoSuchMethodError` |
| `TractionSpec.fromLegacy` | 删除（只服务旧加速度常数迁移；旧键已删），用例与夹具同步删除 |
| 待办 | `docs/01-设计/制动系统-气压与制动力模型-设计.md` 的两处状态行（另有会话在改同一文件，避免撞车，留到最后） |

## 7. 电再生制动（B1/B2 与"缸压分不明白"）

> 用户 2026-10-03：「这个车还有一个 B1,B2 是电再生制动逻辑呢，这样缸压就能分明白了吧」

**这一整块内容独立成篇 → [notes/379-电再生制动-B1B2与缸压分配.md](379-电再生制动-B1B2与缸压分配.md)**。要点：

- 根因：`availableElectricN`（电空混合的输入）原来**只有三手柄在传**，有级/无级恒为 0 ⇒ SAF420 在模型里没有电制动；
  叠加编组级 `blendingEnabled` 照抄了**无动力的控制拖车**（`false`）⇒ 就算传了也会被关掉；
- 现在：`ElectricBrakeSpec`（含 notes/378 点名的 `regenCutoffKmh`）+ 编组级"有没有动力车能混合" + 控制器把被电替掉的那份**加回合力**；
- 实测（SAF420 6M4T、60 km/h）：B1 电 52.1 / 气 31.9 kN，**动力车 6 节缸压整档 0.00 bar**、拖车照旧建气（B1 0.64 bar）；
- ★ 配置里的电制动数值是**假设**（力/功率取牵引机包线、`regenCutoffKmh=5` 按全電気ブレーキ），等真车口径替换。
## 8. S7 那 15 个类的复现命令与计数

`JAVA_HOME=env/jdk-21`、`--offline`，15 个类一起跑 = **131 tests, 0 failures, BUILD SUCCESSFUL**
（日志 `sandbox/mig376-tests-3.log` / `sandbox/mig376-tests-final.log`；逐类计数见子代理报告）。
改前备份 `sandbox/consist-types.json.bak-before-trailing-comma-fix`。
注：本环境里 `setupWebserver` 这个 gradle 任务会失败（原始 HEAD 副本同样失败）——
跑用例时加 `-x setupWebserver`。

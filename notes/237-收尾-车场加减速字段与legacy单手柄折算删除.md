# 237 · 收尾：删掉车场加减速存档字段/界面，撤掉 legacy 单手柄折算

日期：2026-09-23 · 接 notes/236，把 notes/235 执行单里剩下的 **B3 / B4 / B5 / B6** 做完。
至此"原版加减速模型"在**配置、存档、界面、镜像、操纵映射**五处都不再有立足点。

---

## 1. B3：车场不再有"加减速度"

| 删掉 | 说明 |
| --- | --- |
| `schema/data/siding.json` 的 `acceleration` / `deceleration` | 存档字段删除（老存档里的这两个键会被忽略） |
| `Siding.ACCELERATION_DEFAULT / MAX_ACCELERATION / MIN_ACCELERATION` | 三个常量删除 |
| `Siding.getAcceleration()/getDeceleration()/setAcceleration()/setDeceleration()`、`Siding.roundAcceleration()` | 删除 |
| `Siding.getUpcomingSlowerSpeed(...)` | 随 `simulateMoving` 一起变成死代码，删除 |
| `VehicleExtraData.createWithLegs(...)` 的两个参数 | 签名去掉（12 处调用点已改） |

两个**必须留意**的连带项：

- `Lift`（电梯）原来蹭的就是 `Siding.ACCELERATION_DEFAULT`。它现在有自己的 `Lift.ACCELERATION`
  （m/ms²，标注"电梯是另一套运输方式，与列车力模型无关"）。
- `Siding.TimeSegment`（进路**时间估算**）也用过那三个常量。它现在自带
  `TIME_ACCEL_DEFAULT/MAX/MIN` + `roundTimeAcceleration()`，注释写明**不驱动任何车辆**
  （当前生成器未启用 ⇒ `timeSegments` 实际是空表）。谁拿它去算车速就是新 bug。

**镜像里的 `acceleration`/`deceleration` 换了个含义**：不再是"车场配置的原版常数"，
而是**本车力模型的真实能力**（SI，m/s²），由 `Vehicle#updateMmtrSyncFields()` 每 tick 写入
（`physics.tractionAccelerationMps2(1, v)` / `serviceBrakeDecelerationMps2(1, v)`）。
客户端拿它们做**信号预留足迹**（`VehicleExtension` 的 padding）与**电机音调**时，
用的就是这列车真正能做到的加减速 —— 比原来"所有车一个常数"更准。
（为此把这两个字段从 `vehicleExtraData.json` 的 `required` 里拿掉：生成类里它们是 `final`，
不拿掉就没法在运行时写入。）

## 2. B4：`SidingScreen` 的两个滑块

`SidingScreen` 的加减速滑块、两条标签、`SLIDER_SCALE`、`ACCELERATION_UNIT_CONVERSION_1/2`
与 `accelerationSliderFormatter()` 全部删除；下面几行控件（延误调速百分比、早到加停留、
手动模式、最大手动速度、停站时间）整体**上移两行**，`render()` 的文字 y 坐标同步上移。
`gui.mtr.acceleration` / `gui.mtr.deceleration` 两个 lang 键从 fabric/forge 的 `en_us.json` 删除。

## 3. B5：legacy 单手柄折算没了

- `MmtrSupport.controlFromLegacyPowerLevel()` + `LEGACY_EMERGENCY_POWER_LEVEL` 删除
  （`MmtrSupportTests.testLegacyPowerMapping` 相应删除，补了一条速度换算用例）。
- `Vehicle.mmtrLegacyPowerLevelFromControl()` 删除；`updateMmtrSyncFields()` 里那条
  `displayPower = …` 的写入删除。镜像字段 `powerLevel` 仍在协议里（旧客户端会读），**恒为 0 = 中性**。
- `Vehicle.engageMissionAutopilot()` 不再"满油门"（`setPowerLevel(MAX_POWER_LEVEL)` 删除），
  只剩"关好门 + 刷新人工回退计时"。无人/自动运行现在只有一条路：走行器 + `autoNotch` 自臂。

## 4. 验证

- `gradlew test`：**778 条，失败 3 条** —— 与 notes/236 记录的同一组**世界存档数据漂移**
  （`MmtrConsistMultiCarPlacementTests` ×2、`MmtrTrainCarsJsonTests`，查的是 `run/world/mtr` 里
  已不存在的长股道 `B543D3A223517203`；断言发生在任何物理参与之前）。
  条数 780→778 是因为删了 3 条"原版参数"用例（`SidingTests` ×2、`MmtrSupportTests` ×1）补了 1 条。
- `check-java-compile.ps1` OK（448 源）、`check-paths.ps1` OK、引擎 jar 已重新同步进 `game/libs`。

## 5. 本轮踩到的坑（给自己与后来者）

用一条正则批量删 `createWithLegs(...)` 的第 7/8 个实参时，模式 `, <数>, <数>, (true|false),`
**误伤了**另外两种构造：`Rail.newRail(..., NO_STYLES, 80, 80, false, …)`（速度上限被吃掉）与
`new VehicleRidingEntity(uuid, 0, 0, 0, 0, false, …)`。编译器立刻报出来，已逐处还原。
教训：批量改实参要按**调用名**限定，不要只按"数字、数字、布尔"的形状。

## 6. 遗留

1. **B0 实机**：世界里还停在 MTR 遗留路径上的车列要在带新引擎的服务端里重编组
   （`query depots → manifest add <depotId> <sidingId> <车型…> → vehicle remove --depot=<id> → manifest replay`，
   判据：`train interlock <id>` 给出 `rail=<hex>` 而不是 `rail=-`）。日志里那行
   `[MMTR-DRV] 车=… 在 MTR 遗留路径上、没有走行器 …` 就是点名册。
   *（本轮试过在沙箱里跑 `mmtr\scripts\dev-server.ps1`：gradle 卡在 `Configure project :fabric`
   （Fabric Loom 1.10.5）十几分钟不动、CPU 几乎不涨、8888 始终不应答 —— 与本轮代码无关，
   是环境起不来 dev server。B0 与实机验证留到能起服的时候做。）*
2. 实机验证：起步 / 巡航 / 停点、上坡掉速、下坡早收油门。
3. BR101 真车参数（当前 82 t / λ1.06 / 200 kN / 1.9 MW / 常用 120 kN / 紧急 150 kN 是占位值）。

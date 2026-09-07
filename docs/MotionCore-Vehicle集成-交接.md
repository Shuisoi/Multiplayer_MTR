# Motion Core ↔ Vehicle 集成 · 交接文档（给新会话）

> 用途：开新会话做「让引擎 Vehicle 本体跑 Motion Core」这项核心改造时，先读本文档即可接手，无需回顾冗长历史。
> 仓库：本机 mmtr/ 是独立 git 仓库（引擎在 mmtr/engine，Minecraft 模组在 mmtr/game）。当前 HEAD：78e6610（clean）。
> 语言：文档中文；代码/提交信息英文。

---

## 1. 新会话目标（一句话）
把 MTR 派生的引擎 Vehicle 的运动，从「spawn 时烤死整条 vehicleExtraData.immutablePath（预烘焙 path）」改成由
Motion Core 驱动：车的运行状态 = (当前轨道段 + 段内偏移)，到节点按「当前道岔状态 / 任务目标」选下一段（道岔权威换向），
由现有驾驶控制（ControlState，见 §5.4）开。MTR 只作世界轨道图 / 几何来源。目标形态：生成一辆 manual 车，人上服务器用现有驾驶手动开、能随便走 / 到岔口自己定。

---

## 2. 已完成且已验证（Motion Core 独立运动系统 —— 不要再重做）
全部在真实轨 / 真实 dev 存档验证，compileJava/TestJava + 对应测试绿。

### 2.1 运动系统代码（mmtr/engine/src/main/java/org/mtr/core/mmtr/）
- segment/MmtrSegmentStep.java：纯 (railHex, offsetM, lengthM, reversed) 段状态（advance/remaining/atEnd/overshoot）。
- segment/MmtrNodeRouter.java：道岔权威节点决策 elect(...)：单续向直行；task 命中优先；operator 0=straight(branch0)/1=diverge(branch1)；无权威真岔返回 null（绝不 auto）。
- segment/MmtrLiveRouter.java：route(...) 沿真实 positionsToRail 逐节点权威路由出轨序；integrate(...) 按距离推进返回 (segment,offset)。
- segment/MmtrMotionWalker.java：可续逐 tick (segment+offset) 引擎；advance(delta) 跨节点按权威选段；未设岔停在岔口（等操作者，非终态，可续）；buildLegs() 输出它走过的有序、累计距离、Vehicle 可跑的 PathData 轨序。
- segment/MmtrMotionDriver.java：纵向驱动（巡航/手动）。applyControl(ControlState, dt, accel, decel, max) = 用现有驾驶 ControlState 驱动；manualTick = 手控油门/刹车。到无权威岔口/端点/目标制动停。
- point/MmtrPointRegistry.java（含 BranchStore）：真实岔 (节点, 进向轨)->branch0/1 自动发现 + operator 0/1 持久化。point/MmtrSwitch.java：岔模型。
- MmtrMotionSnapshot.java：from(Siding,Vehicle)（现有车辆表示）与 ofWalker(MmtrMotionWalker)（Motion Core 驱动车 -> segment+offset+车头世界坐标，可渲染，无烘焙 path）。

### 2.2 接缝（已落地，直接复用）
- data/VehicleExtraData.createWithLegs(depotId, sidingId, railLength, vehicleCars, legs, accel, decel, isManualAllowed, maxManualSpeed, manualToAutomaticTime)：把 Motion Core 的 buildLegs() 轨序直接做成 Vehicle 的运行路径载体（immutablePath），不拼烘焙缓存。（注：Siding.simulateVehicles 现在用 VehicleExtraData.create(...) 生成车，见 §4。）

### 2.3 测试（新会话先跑它们确认基线绿）
- engine/src/test/.../mmtr/MmtrSegmentMotionTests.java
- engine/src/test/.../mmtr/point/MmtrTurnoutRoutingTests.java
- engine/src/test/.../mmtr/segment/MmtrLiveRouterTests.java（含 walk / driver / manual / existing-control 用例）
- engine/src/test/.../mmtr/point/DevWorldTurnoutFlipTests.java（真实 -96 岔口 搬0走直/搬1走岔，决策层）
- engine/src/test/.../mmtr/point/DevWorldMotionWalkTests.java（真实 -96：Motion Core 驱动车沿 via 轨开进岔口、branch0/1 跨上不同真实轨、ofWalker / buildLegs）
运行：cd mmtr/engine，JAVA_HOME 用 C:\Users\30354\.jdks\jdk-21.0.12.1+1（见 §6），./gradlew.bat test --tests 类名。

---

## 3. 现在要做的核心改造（新会话主体）
把引擎里真实运行的车接到 Motion Core，使其手动驾驶不再被 spawn 时烤死的整条 path 限制：
1. 手动停场车被驱动/发车时，其「向前该走哪」由 Motion Core 在每个节点实时决定（当前道岔态/任务），而不是沿它 spawn 时那份 default/出库/整条 path 走到底。
2. 运动的坐标/渲染/占用仍由真实 Rail 几何提供（MTR 只作轨道图），MmtrMotionSnapshot.ofWalker 已是 (segment+offset+head)。
3. 驾驶输入 = 现有 ControlState（MmtrDriveControl.apply -> Vehicle.applyMmtrControl），不需要再造一套控制。
4. 收尾删除旧烘焙机制（Depot/Siding.generateRoute 生成的三份缓存拼装 path）。

## 4. 现有引擎代码锚点（先读这些再动手）
- data/Vehicle.java（1292 行）：simulate/simulateMoving/simulateStopped/simulateInDepot/startUp/getRailProgress/writeVehiclePositions 全部沿 vehicleExtraData.immutablePath（PathData 列表 + 累计 railProgress）；siding final 绑出生股道。手动驾驶：rider/driver + powerLevel/applyMmtrControl(ControlState) 进 mmtrActiveControl，在 simulateMoving 的 MMTR 分支由 ConsistDynamics 算速度并沿 path 推进 railProgress。
- data/VehicleExtraData.java：immutablePath；create(...) -> createPathData(...) 用 pathSidingToMainRoute + pathMainRoute + pathMainRouteToSiding（+ defaultPathData）拼出整条；createWithLegs(...) 已可换源。
- data/Siding.java：simulateVehicles 生成车（VehicleExtraData.create(areaId,id,railLength,vehicleCars,pathSidingToMainRoute,pathMainRoute,pathMainRouteToSiding,defaultPathData,repeatInfinitely,accel,decel,(getIsManual()||mmtrManualSpawn),maxManualSpeed,manualToAutomaticTime) 在 Siding.java:124/364/407）；generateRoute 生成三份 path 缓存。
- data/Depot.java：generateRoute / writePathCache（出库腿来源）；Simulator.java:514 用到 hasPathToMainRoute/hasReturnFromMainRoute。
- operation/MmtrDriveControl.java：现有驾驶命令 ControlState（throttle/brake/emergency/reverser + driverUuid）-> vehicle.applyMmtrControl。

## 5. 建议落地切片（每步 compile + 对应测试可验，再提交）
- T3（推荐先做，可测）：在发车/生成 manual 车处用 createWithLegs + Motion Core buildLegs() 生成一辆「路线由 Motion Core 决定」的真实 Vehicle，并让它在合成场区/真实存档里跑起来（railProgress 前进 + 跨岔换向断言）。这一步把 Motion Core 选轨真正送进一辆活的车。
- T3b：让那辆 manual 车能被现有 ControlState（MmtrDriveControl / rider 驾驶）驱动，运动走 Motion Core；验证人用现有键位/命令能把它往前开、到岔口设岔换向、停目标。
- T4：删旧烘焙（Depot.generateRoute / Siding.generateRoute 三缓存拼装、Simulator.hasPath*），改为 Motion Core 取径。

## 6. 构建 / 验证纪律（新会话务必照做）
- JDK：JAVA_HOME=C:\Users\30354\.jdks\jdk-21.0.12.1+1，PATH 前置其 bin。引擎工作目录 mmtr/engine。
- 编译：./gradlew.bat compileJava compileTestJava
- 跑指定测试：./gradlew.bat test --tests 类名（见 §2.3）。
- 回归底线：改 Vehicle 前先跑全量 ./gradlew.bat test，记下失败集（现状：约 13 个失败全是既有的 mmtr-job 子系统 / DevWorldJobSmokeTests，与 Motion Core 无关，最初基线就红）。你的改动不得新增失败（Motion Core + 确定性测试必须保持绿）。
- 真实 dev 存档可加载：Simulator("minecraft/overworld", new String[]{"minecraft/overworld"}, C:/Users/30354/Desktop/Shuisoi DEV/MC/mmtr/game/fabric/run/saves/新的世界/mtr, false)。

## 7. 文档地图（按需加载）
- 本文档（新会话先读）：目标 / 已完成 / 剩余 / 锚点 / 验证。
- docs/运动系统-脱离MTR-MotionCore-设计.md：完整架构 / 解耦边界 / 删除与迁移策略 / 验收 / §10 T1–T4 规约。
- notes/12-段级运动-slice-A地基.md：按轮次的过程记录（含各提交点）与全量回归证据。
- 提交锚点（历史）：652507e(决策+段) 3e3e18a(图层岔) c9ec451(路由) a434e84(integrate) 8c8c379(设计doc) f6cec64(真实-96决策) 1179d47(Walker) 0ab03da(Driver) 4e47449(真实-96驱动) 36f4d83(buildLegs/T1) e4cb07f(createWithLegs/T2) e0b6193(自由开续走) bc2ffdb(手动开) 78e6610(applyControl/现有控制驱动)。

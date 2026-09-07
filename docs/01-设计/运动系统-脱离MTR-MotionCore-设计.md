# MMTR Motion Core — 脱离 MTR 的列车运动系统（设计文档）

> 状态：设计基线。一句话目标 —— 建立一套不由 MTR 的预烘焙路径（pathData / Vehicle 整段 path）裁决列车
> 该怎么走的运动系统：车的运行状态 = (当前轨道段, 段内偏移)，到节点按"道岔状态 / 任务目标"选下一段；
> MTR 只提供世界轨道图与几何，不再决定车如何走。
> 本会话已把该系统的解耦内核逐块实现并测绿；把"运行中的车"接到这套内核上，是仍在进行的主体工作。
> 真实场区 -96/-95 岔口的"搬 0 走直、搬 1 走岔"翻转，只是验收里程碑之一，不是目标本身。

---

## 1. 为什么必须脱离 MTR 的运动裁决（现状事实）

当前引擎的移动，车被"发车前烤死整条路"绑定：

- Vehicle（engine/.../data/Vehicle.java）持有 vehicleExtraData.immutablePath（PathData 列表 + 累计 railProgress）。simulateMoving / simulateStopped / setNextStoppingIndex / getPathStoppingPoint 的停站、dwell、信号、折返、写占用全部按"整条 path 的下标 / 累计距离"写死；Vehicle.siding 为 final，车永久绑出生股道。
- VehicleExtraData 在构造/发车时把完整旅程（出段 + 全线 + 回段）写进 path。
- 出库腿由 Depot.tick -> SidingPathFinder -> siding.generateRoute 一次性生成并烘焙。
- 道岔（turnout）权威当前只在生成期用：SidingPathFinder.getConnections 对 operator 显式设过的 (node, viaRail) disallow 未选支。生成完，车只沿结果跑。

=> 后果：车不能自由改向、只能沿生成路径 / 车场股道缓存开，无法"到岔口临时被搬去另一股道"，也无法在库内自由开。这正是要拆的"MTR pathData 裁决"。

## 2. 目标模型：三层解耦

(1) 决策层（谁决定往哪走）：人工 / 任务(AI) 显式操作道岔 0/1，或给任务目标轨；道岔 = (节点, 进向轨) -> branch0/branch1，绝不自动。
(2) 节点决策（Motion Router）：到节点按道岔状态/任务选下一段(轨)。
(3) 运动状态（Motion State）：当前轨道段 + 段内偏移(segment+offset)；速度/门/行经段信号限速只按当前段判定。

- MTR = 逻辑底座：世界编辑、轨道图(rail graph)、几何(端点/曲线/长度)、信号区段做参考。
- Motion Core = 权威运动层：本系统自己裁决"车在哪一段、往哪一段"，不由 MTR pathData 裁决。

## 3. 解耦内核（本会话已实现 + 测绿，均可独立验证、零触碰旧引擎）

| 组件 | 文件 | 职责 | 验证 |
| --- | --- | --- | --- |
| MmtrSegmentStep | engine/.../mmtr/segment/MmtrSegmentStep.java | 纯 (railHex, offsetM, lengthM, reversed) 段状态：advance/remaining/atEnd/overshoot(余量 carry) | MmtrSegmentMotionTests |
| MmtrNodeRouter | .../segment/MmtrNodeRouter.java | 道岔权威节点决策：elect(单续向直行；task 命中优先；operator 0=straight/1=diverge；无权威真岔返回 null 绝不 auto) | MmtrSegmentMotionTests |
| MmtrLiveRouter | .../segment/MmtrLiveRouter.java | 运行时逐节点路由：route(...)=沿真实 positionsToRail 走的轨序；integrate(...)=按距离推进返回 (segment,offset) | MmtrTurnoutRoutingTests、MmtrLiveRouterTests |
| MmtrMotionWalker | .../segment/MmtrMotionWalker.java | 可续 (segment+offset) 逐 tick 引擎：持当前轨+偏移，advance(delta) 跨节点按权威选段；未设岔停在岔口 | MmtrLiveRouterTests (walk*) |
| MmtrMotionDriver | .../segment/MmtrMotionDriver.java | 纵向驱动：每 tick 以 speed*dt 推进 walker，权威/端点/目标处制动至停；真正把车逐 tick 开起来 | MmtrLiveRouterTests (driver*) |
| （真实轨发现） | .../point/MmtrPointRegistry | discover 在真实 Rail 上找 (节点,进向)->branch0/1；BranchStore 持久化 operator 0/1 | MmtrTurnoutRoutingTests |

提交链：652507e -> 3e3e18a -> c9ec451 -> a434e84 -> 8c8c379 -> f6cec64 -> 6d3ccd5（本轮 + MmtrMotionWalker）。全部编译 + 测试绿。

## 4. Motion Core 目标架构（把车接上这套内核 = 主体待做）

要让"运行中的车"真的脱离 MTR 烘焙 path，需要把 Vehicle 的运动从"沿整条 immutablePath"切成"沿 MmtrLiveRouter 给出的轨序、持 MmtrSegmentStep 推进"。建议分层落地：

- M1 Vehicle 提供 decoupled 运行模式：Vehicle 在 manual 自由开 / mmtr-job 时进入该模式：持有 (currentRailHex, offsetM)，每 tick 用本系统的纵向模型推进 offset；offset 用尽 = 到节点，调 MmtrNodeRouter / MmtrLiveRouter 选下一段（道岔权威/任务），余量 carry。旧 defaultPathData 时刻表路径完全保留 = 零回归。
- M2 行经段裁决：速度上限 / 信号 / 限速按"当前行经段 + 其道岔状态"判定，而非整条 path 预置 stop index。
- M3 任务接入：任务(AI/人工)与 MmtrLiveRouter 同一接口；MmtrMotionSnapshot（mmtr-motion feed）继续输出 segment+offset 表示，客户端只需插值，服务端权威。
- 几何来源：段长/端点/方向取自 Rail/RailMath 与 data.positionsToRail（MmtrLiveRouter 已这样走）。

## 5. MTR 解耦边界（哪些归 MTR、哪些归 Motion Core）
- 归 MTR（底座）：世界编辑、轨道图、Rail 几何/端点/曲线、信号区段预订、资源/渲染。
- 归 Motion Core（权威）：车当前在哪个段、往哪段走、何时/在哪停靠与折返、行经段速度判定、出库/换场路由。
- 判据：删除"发车前烤好的整条 path"，车也能由 (当前段 + offset + 到岔口被搬) 描述并开到任意目标 —— 自由开成立。

## 6. 验收（Definition of Done）
1. 内核（已绿）：MmtrSegmentStep / MmtrNodeRouter / MmtrLiveRouter 在真实轨上通过确定性测试；单续向直行、task 覆盖陈旧 operator、无权威真岔拒绝自动。
2. 自由开（待做）：运行中的车以 segment+offset 驱动，能出库、到岔口按道岔/任务换向、进目标股道停稳；旧 pathData 时刻表行为零回归。
3. 真实岔口翻转里程碑：真实场区 -96/-95 岔口 (-96,-60,76)。【决策层已验，f6cec64】operator 搬 0 -> elect 直向真实轨、搬 1 -> 分叉真实轨，未设岔拒绝自动（DevWorldTurnoutFlipTests 在真实存档通过）。【车实际经过该轨=仍待做】—— 需把内核接进 running Vehicle。此里程碑验证决策层真正驱动运动。

## 7. 诚实状态
- 已完成并验证：第 3 节解耦内核（路由 + 决策 + 段状态 + 真实轨图验证）。
- 未完成（主体）：第 4 节 M1/M2/M3 —— 把内核接进 running Vehicle（改 1292 行 Vehicle 的运动路径，高风险 L3 级）。真实 -96/-95 岔口翻转的【决策层】已验证（f6cec64）；车实际沿该轨运动的验证需 M1 接线完成。
---
## 8. 删除与迁移策略（清理旧系统，无需并行可用）

> 目标：Motion Core 成为唯一运动系统；被其取代的旧路径化/缓存化机制逐步删除，不再保留并行可用。
> 每步以「删除 -> 更新调用方/测试 -> 编译+可验」推进。

### 8.1 第一步（已完成，编译可验）：删除死代码腿装配
- 移除 MmtrMotionRouter.MmtrMotionPlan 与 buildLegPlan（基于每股道 path 缓存的腿装配，主流程并未真正用来开车，仅测试/探针引用）。
- 移除 Siding.copyOutboundLegs / copyRouteLegs / copyReturnLegs（仅为该腿装配提供缓存快照）。
- 保留 MmtrMotionRouter.canReachSiding + Siding.hasPathToMainRoute / hasReturnFromMainRoute（仍被旧 relocation 当可达性门引用，下一步处理）。
- 更新 MmtrJobSchedulerTests.motionRouterReachabilityBasics 与 DevWorldRouterProbeTests 去掉 buildLegPlan 断言。

### 8.2 切片进展
- 已做 8.1 + 「跨股道自动移动先下线」(increment A)：MmtrJobScheduler 的 cross-side MOVE_TO 改为 fail-fast(离线)；删除 DevWorldRelocateTests / DevWorldRouterProbeTests（仅测该旧重生搬迁）。compile + Motion Core 确定性测试绿。
- 已做 增量(B)：删除 MmtrJobScheduler 内 arrivalMergeConsist / relocateParkedConsist / relocatingTo 完成态机与字段（relocatingTo / relocateWaitStartMillis）及 reset；删除 MmtrMotionRouter(canReachSiding) 类与其测试方法。compile + Motion Core 确定性测试绿。Siding.hasPathToMainRoute/hasReturnFromMainRoute 暂留（Simulator 仍用）。

### 8.2b 后续切片（按序，每步可验）
- 切片2：移除旧 relocation 的可达性门（MmtrMotionRouter.canReachSiding + Siding 的两条 has* 缓存判断），
  由 Motion Core 用真实轨道图的可达/路由（MmtrLiveRouter）取代；同时处理 MmtrJobScheduler 的
  arrivalMergeConsist / relocateParkedConsist 等旧"重生搬运"流程。
- 切片3：mmtr-job 旧 job/relocation 子系统（rebirth、path 缓存、其 16 个失败测试）整体退役删除，
  任务按 Motion Core 路由执行。
- 切片4（引擎 Vehicle 层）：把预烘焙 pathData 运动（Depot.generateRoute / Siding.generateRoute ->
  VehicleExtraData.immutablePath）换成 Motion Core 驱动（MmtrMotionWalker 逐 tick + 权威决策），删除旧机制。

### 8.3 每步验收
删除后 engine compileJava/compileTestJava 通过；Motion Core 确定性测试（MmtrSegmentMotionTests /
MmtrTurnoutRoutingTests / MmtrLiveRouterTests / DevWorldTurnoutFlipTests）仍绿；不再保留被删旧系统。

---
## 9. 验收与状态（round 12 / goal 收口）

### 已达成并验证
- Motion Core 解耦内核（MmtrSegmentStep / MmtrNodeRouter / MmtrPointRegistry+BranchStore / MmtrLiveRouter.route /
  integrate / MmtrMotionWalker）在真实轨上通过确定性测试；含真实场区 -96/-95 岔口『搬0走直、搬1走岔』决策层翻转（DevWorldTurnoutFlipTests）。
- 成文设计文档（本文）含架构/解耦边界/删除与迁移策略/验收。
- mmtr 层被取代的旧路径化/缓存化搬迁机制已清理：死代码腿装配、跨股道"重生"自动移动、MmtrMotionRouter 已删除；清理全程 compileJava/TestJava + Motion Core 测试可验，全量回归无新增失败（13 个失败均为既有 mmtr-job 子系统，最初基线即红）。

### 未达成（主体，需另行立项/资源）
- 切片4：引擎 Vehicle 层把 MTR 预烘焙 pathData 运动换成 Motion Core 驱动（以 MmtrMotionWalker 逐 tick +
  节点权威决策替代 VehicleExtraData.immutablePath / Depot.generateRoute 烘焙链路）并删除旧机制。这是改 1292 行
  Vehicle + 发车/时刻表链路的 L3 重写，超出本 goal 轮次可安全收尾的范围。
- mmtr-job 调度子系统自身仍在建设中（红）；其多股道用例依赖被下线的跨股道移动，需 Motion Core 真实驱动后恢复。

### 切片4 起步接缝（供后续）
1) 给 Vehicle 增加"decoupled 运行模式"（由 mmtrManualOverride / 任务触发）：运行时状态 = MmtrMotionWalker 的
   (railHex, offsetM, speed)，每 tick advance(speed*dt)，旧 defaultPathData 路径不再作为其唯一轨道。
2) MmtrMotionSnapshot 继续输出 (segment,offset)；服务端权威由 Motion Core 提供。
3) 合成场区先验（出库→岔口按道岔换向→目标停稳），再接真实 dev 存档 -96 岔口"车实际沿该轨"。
定义达成：运行中的车由 Motion Core 以 (segment+offset)+权威决策驱动，MTR 只作轨道图/几何来源；旧烘焙运动机制已删。
---
## 10. Vehicle 本体采纳 Motion Core —— 真实接线规约（以代码为准）

### 10.1 现状路径构造链（实证）
- Vehicle 运动 = vehicleExtraData.immutablePath（整条烘焙路径），Vehicle.simulate* 全沿它。
- immutablePath 由 VehicleExtraData.create(...) -> createPathData() 拼自每股道三份缓存：
  pathSidingToMainRoute(出库腿) + pathMainRoute(主线) + pathMainRouteToSiding(回库腿)，见 Siding.java:124/364/407
  （new Vehicle(VehicleExtraData.create(areaId, id, railLength, vehicleCars, pathSidingToMainRoute, pathMainRoute,
   pathMainRouteToSiding, defaultPathData, repeatInfinitely, ...))）。
- 这三份缓存由 Depot.generateRoute -> Siding.generateRoute -> SidingPathFinder 在生成期一次性烤死
  （生成时按当时道岔态 disallow 未选支，之后车只沿结果跑）。

### 10.2 接线目标（Vehicle 本体跑 Motion Core）
把"路径来源"从生成期 SidingPathFinder 烘焙换成 Motion Core 运行时逐段权威路由：
- 车辆运行状态改持 MmtrMotionWalker 的 (railHex, offsetM, speed)，每 tick 由 MmtrMotionDriver/advance 推进；
  到节点按 MmtrNodeRouter 读当前道岔态(或任务目标)选下一段，不再有"发车前烤好整条 path"。
- 几何/渲染/占用仍由真实 Rail 提供（MTR 只作轨道图/几何），不依赖整条 immutablePath 的下标累计距离。
- 出库腿/换场/停站意图由任务(MOVE_TO/SERVE)下达，Motion Core 负责怎么走。

### 10.3 落地切片（每步可编译 + Motion Core 测试可验）
- T1：构造链切换。给 VehicleExtraData/路径来源加"MotionCore 轨序 -> 该段可运行的几何"，使能由 MmtrLiveRouter
  产出的轨序生成一份 Vehicle 能沿其前进的运行时段序列（替代从三份缓存拼的整条烘焙）。
- T2：Vehicle 加 decoupled 运行模式（manual/任务触发）：运动状态 = MmtrMotionWalker + speed，逐 tick 推进；
  旧时刻表路径作为兼容基线保留直至删除（本目标无需并行，最终删旧）。
- T3：渲染/占用按当前段几何（MmtrMotionSnapshot.ofWalker 已给出可渲染 (segment,offset,head)）。
- T4：删除旧烘焙生成（Depot.generateRoute/Siding.generateRoute 三份缓存 & immutablePath 拼装）。

### 10.4 验收
一辆真实 Vehicle：出库 -> 到岔口按当前道岔态换向 -> 进目标股道/站台停稳，全程由 Motion Core 驱动；
引擎内可见其 railProgress/占用沿被选真实轨前进；翻转 -96 岔口 -> 车实际换走另一轨；旧烘焙机制已删。

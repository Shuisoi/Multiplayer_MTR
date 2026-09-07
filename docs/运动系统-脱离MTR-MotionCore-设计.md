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
| （真实轨发现） | .../point/MmtrPointRegistry | discover 在真实 Rail 上找 (节点,进向)->branch0/1；BranchStore 持久化 operator 0/1 | MmtrTurnoutRoutingTests |

提交链：652507e -> 3e3e18a -> c9ec451 -> a434e84（全部编译 + 测试绿）。

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
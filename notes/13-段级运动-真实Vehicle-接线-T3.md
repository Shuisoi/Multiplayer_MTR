# 13 - Vehicle 本体接线 T3/T3b：真实 Vehicle 沿 Motion Core 轨序运行（本会话）

## 目标（交接文档 MotionCore-Vehicle集成-交接.md §5 的 T3/T3b）
让"真实 Vehicle 的运行路径来源"从三份烘焙缓存（VehicleExtraData.create -> createPathData）换成 Motion Core
逐段权威选出的轨序（VehicleExtraData.createWithLegs(legs)，legs=MmtrMotionWalker.buildLegs()），并让一辆活的
Vehicle 用现有驾驶控制（ControlState/applyMmtrControl）沿真实轨前进、跨真实岔口按道岔换向。T3b = 现有座舱控制驱动。

## 本轮新增（全部 additive / 纯新增绿，零改旧 Vehicle/Depot/job 运行路径）
1. main: Siding.spawnMmtrManualWithLegs(ObjectArrayList<PathData> legs)
   （Siding.java，rebuildParkedConsist 之后）—— 库内一空闲 parked 车换成一条 Motion-Core 轨序作 path 的 manual 车。
   守卫：legs/车辆非空、编组不超股道长、yard 空闲（无 on-route、至多一辆 parked）；替换后 put 回 vehicleIdMap。
   默认回退/null，不碰 legacy auto 车与三处 create(...) 既有调用。
2. test: MmtrVehicleLegsRunTests（org.mtr.core.mmtr.point，4 用例全绿）
   - realVehicleRunsMotionLegsAcrossForkOntoStraight：合成真岔，branch0 -> legs 落在 straight；createWithLegs
     造真 Vehicle(siding=null)，applyMmtrControl(ControlState throttle) 驱动，railProgress 越过岔口(20m) 落 straight。
   - flippingBranchReroutesRealVehicleOntoDiverge：branch1 -> legs 落在 diverge；同车驱动越过岔口落 diverge。
   - sidingSeamSpawnsMotionLegsManualVehicle：裸 Siding.setVehicleCars + spawnMmtrManualWithLegs(legs) -> 返回
     manual 车、immutablePath==legs(携带 Motion 选轨 straight)；applyMmtrControl 驱动越岔。
   - realDevYardVehicleCrossesMinus96ByAuthority：真实 dev 存档 -96 岔口（Assumptions 门控，本机存档存在），
     真实 Vehicle 分别用 branch0/branch1 的 Motion legs，驱动越过叉点落到 branch0Hex / branch1Hex 真实轨
     （= 交接 10.4 验收"翻转 -96 岔口 -> 车实际换走另一轨"，引擎内真实 Vehicle 达成）。

## 回归证据（本会话 gradlew test）
- 定向回归（Motion Core + Siding/Depot + dev -96）：63 completed / 0 failed / 0 skipped。
- 全量：256 completed / 13 failed / 2 skipped。13 个失败与基线一致、全在既有 mmtr-job 子系统
  （MmtrJobSchedulerTests 12 + DevWorldJobSmokeTests 1），与 Motion Core / 本切片无关。=> 新增纯绿、零新增失败。

## 意义 / 状态
- 证明了：路径来源换成 Motion Core 后，Vehicle 无需改动（immutablePath 同构）即可跑、可由现有 ControlState 驱动、
  到真实岔口按道岔选轨跨上不同真实轨。这是 T3/T3b 的可测交付物（引擎内真实 Vehicle，非仅 VED/DTO 断言）。
- 未做（诚实边界，T4 另立）：删除旧烘焙（Depot.generateRoute/writePathCache、Siding 三缓存 createPathData 拼装、
  Simulator.ensureMmtrDepotPaths/Depot.generateDepots 触发的路径生成仍供 auto 车/出库用）；把 spawnMmtrManualWithLegs
  接到算子/任务(MOVE_TO)发车流程（把 Motion legs 从任务目标实时算出再下发）。

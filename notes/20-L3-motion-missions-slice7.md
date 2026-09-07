# 20 - L3 Slice-7：任务驱动 motion 车闭环（mission wiring，本会话）

> 承接 notes/14-19。slice-7 把"任务车"接上 live Motion Core：MmtrMissionControl（mmtr-mission-control
> 操作）对 motion 车派发 AUTOPILOT 任务时，解析目标（platform/siding id → 其真实轨）、MmtrRunPlanner
> 规划进路并把岔口预置写进权威 BranchStore、武装 auto step-run（PASSENGER 开门），车辆自动跑到目标
> 平台轨精确停稳；任务状态机观察到站（AT_TARGET，门开停留）；终态（complete/fail/cancel）后自动交回
> idle（auto 关、停点清、关门）。手动任务（PLAYER/AI executor）与旧路径任务语义不变。

## 1. 改动
- data/SavedRailBase：public @Nullable Rail mmtrGraphRail() —— 由两端点取真实轨道图 rail
  （Siding.tick 同款 Data.tryGet），Platform/Siding 共用（Platform 无轨时返回 null）。
- data/Vehicle：
  - mmtrMissionTick：DISPATCHED 到达判定 motion 车用 isMmtrMotionStoppedAtTarget()（legacy 车不变）；
    终态（COMPLETE/FAILED/CANCELED）后 motion 清理：auto 关、停点清、关门（交回 idle）；
  - simulate 尾部：motion 车跳过 legacy engageMissionAutopilot（其每 tick 关门+置 legacy power 只对
    路径车有意义；motion 由 auto step-run 驱动）。
- operation/MmtrMissionControl：dispatch() motion 分支——目标 id→轨解析（sidings+platforms），
  目标=当前轨/缺失/规划不可行 → 拒绝（不挂任务、返回 false）；可行 → applyForkOps(sim.mmtrPointBranches)
  + setMmtrMotionAuto(true) + setMmtrMotionStopTarget(stop, kind==PASSENGER)（日志
  [MMTR-MSG] motion mission … armed）。
- 未变：legacy 车任务路径、手动/auto/停点/岔口全部语义。

## 2. 测试（MmtrMotionMissionTests，2 例全绿）
1. passengerMissionDrivesMotionVehicleToPlatformAndHoldsUntilTerminal：真实 Platform(轨)+Station 网；
   MmtrMissionControl(PASSENGER/AUTOPILOT/目标=platform id) → mission ASSIGNED、auto 武装；
   车自动过岔链到平台轨精确停住（172.0m，日志 arrived … doors open）、任务 AT_TARGET、停留期门开；
   cancel（终态）→ 交回 idle（auto off、停点清、门关）。全程无 override。
2. missionDispatchRefusedWithoutFeasibleMotionTarget：目标=当前所在轨 / 未知 id → dispatch false、
   不挂任务、不武装。

## 3. 回归证据
- 定向：data.* + mmtr.* + operation.* 全绿；全量：slice7-full.log（基线 241/0/2 → 预计 +2）。
- 说明：AT_TARGET→COMPLETE 的 dwell 计时是共享 legacy 状态机（模拟时钟按真实 wall 推进），本测试以
  cancel 终态验证清理路径；dwell→complete 由既有任务状态机测试覆盖。

## 4. 边界与下一步
- 边界：mission 目标=平台轨末端（fraction 1.0，站台对齐细节任务层做）；Siding(回库)目标可复用
  （先出库再规划）；MmtrJobScheduler/PeriodicTaskSource 目前给 motion 车挂任务会走拒绝/旧路径——
  调度器侧接入 motion 分支（planner+auto）是下一步；客户端无镜像。
- 下一步：
  a. MmtrJobScheduler / SERVE 步进接 motion 分支（任务宏：出库→服务→回库→摘挂 的 motion 版）；
  b. 行经段信号/限速（M2）；c. T4 收尾评估 + 交接/实机文档刷新（记录 slice-1..7 与剩余）。

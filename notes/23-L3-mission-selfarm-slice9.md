# 23 - L3 Slice-9：任务自武装（mission self-arm，本会话）

> 承接 notes/14-22。让"任务车"闭环不再依赖特定 ops 入口：**任何给 motion 车挂 AUTOPILOT 任务的
> 调用方**（MissionControl op、调度器、周期任务源、测试）都不必再武装任何东西——车辆在自己的
> tick（mmtrMissionTick）里解析目标（platform/siding id → 真实轨）、MmtrRunPlanner 规划、
> 岔口预置写入权威 BranchStore、武装 auto step-run（PASSENGER 开门）并自动执行；
> 目标不可解析/是当前轨/规划不可行 → 任务 FAILED+原因（任务拥有者从 mission 状态即可观察到失败）。
> 已由 ops 预武装（auto 已开）的任务不重复武装。

## 1. 改动
- MmtrRunPlanner：新增 public static findSavedRailRail(sim, savedRailId)（sidings+platforms →
  mmtrGraphRail）——目标轨解析公共服务（原 MmtrMissionControl 私有实现移此并委托）。
- Vehicle.mmtrMissionTick：motion && AUTOPILOT && 非终态 && 未武装（auto 关且无停点）&& data 为
  Simulator → mmtrMotionSelfArmMission()：
  - target==0 / 无图轨 / 目标=当前轨 / 规划不可行 → mission.fail(原因)；
  - 可行 → applyForkOps(sim.mmtrPointBranches) + setMmtrMotionAuto(true) +
    setMmtrMotionStopTarget(stop, kind==PASSENGER)；日志 [MMTR-MSG] … self-armed。
- operation/MmtrMissionControl：findTargetRail 委托 planner（行为不变，eager 拒绝语义保留）。

## 2. 测试（MmtrMotionMissionTests +2，文件共 4 例全绿）
1. plainMissionAssignmentSelfArmsAndRunsWithoutControlOp：直接 v.setMmtrMission（不经 MissionControl）
   → 下一 tick 自武装（auto on）、自动跑完进路、精确停在 172.0m 平台轨、AT_TARGET、门开；cancel 清理。
2. selfArmFailsMissionWhenTargetIsTheCurrentRail：目标=当前股道 → mission FAILED、auto 不武装。

## 3. 回归证据
- 定向：data.* + mmtr.* + operation.* 全绿；全量：slice9-full.log（基线 245/0/2 → 预计 +2）。
- 意义：MmtrJobScheduler / MmtrPeriodicTaskSource 现有"挂任务+engage"代码路径对 motion 车将自然
  进入 motion 执行（引擎已跳过 legacy engage——slice-7）；调度器侧剩余 = 让作业车以 motion 形态出生
  （seam/manifest）与多步宏编排，见下。

## 4. 边界与下一步（另立项）
- MmtrJobScheduler：作业车 spawn 仍为 legacy 停场形态 + 步进宏（COUPLE/UNCOUPLE/退库）的 motion
  版编排未做（现有 scheduler 行车测试已退役，属重建范围）；M2 行经段信号/限速；平台停点对齐；
  客户端镜像；实机清单。

# 19 - L3 Slice-6：MmtrRunPlanner 进路规划服务（MOVE_TO 底座，本会话）

> 承接 notes/14-18。把 E2E 测试（notes/16）里"发现+预置岔口+停点"的规划逻辑提升为**引擎服务**
> MmtrRunPlanner：任务/操作层做 MOVE_TO 式步进时 = planToRail(车, 目标轨, 停点比例) →
> applyForkOps(store) + setMmtrMotionAuto(true) + setMmtrMotionStopTarget(plan.stopCumulativeM)，
> 车辆自动跑完进路、岔口按规划预置（未设岔保持 live）、精确停稳。手/自动、停点、岔口语义
> （slice-1..5）全部复用。

## 1. 改动
- 新增 main 类 org.mtr.core.mmtr.MmtrRunPlanner：
  - planToRail(sim, vehicle, targetRailHex, stopFraction) → Plan{feasible, reason, nodes,
    forkOps[{x,y,z,viaHex,op}], stopCumulativeM, targetRailHex}；
  - BFS 真实轨道图从 walker.aheadNode 到目标轨任一端点（=进路侧），链重建到目标轨远端；
  - 沿途每个 ≥2 续向节点用与 walker/MmtrNodeRouter 相同的 cos 排行决定 operator（期望支必须落在
    branch0/1，否则 infeasible+原因）；目标=当前所在轨/不可达/停点不前进 → infeasible+原因；
  - stopCumulativeM = walker.distanceM + 当前轨剩余 + 各进路轨全长 + stopFraction×目标轨长
    （全部换算进 walker 距离空间，可直接武装）；
  - applyForkOps(plan, store)：把预置写进 BranchStore（含 contains 防重写）。
- 无 Vehicle 改动（复用 slice-4/5 API）。

## 2. 测试（MmtrRunPlannerTests，3 例全绿）
1. plannerDrivesAutoRunOntoDivergingBranchRail：车场口双岔；规划 rY(分叉) fraction 0.6 →
   forkOps=1(operator 1)、stop 数学精确断言；auto 跑到精确停点、在 rY 上、开门、全程无 override。
2. plannerChainsBothForksToDeeperStraightRail：目标 rP（第二岔之后）→ forkOps=2；auto 过两岔
   （EC→3C→8C 真实轨推进日志）精确停在 132.0m、关门型停点。
3. plannerReportsInfeasibleCases：目标轨缺失 / 目标是当前所在轨 → infeasible + 原因。

## 3. 回归证据
- 定向：data.*+mmtr.* 全绿；全量：slice6-full.log（基线 238/0/2 → 预计 +3）。

## 4. 边界与下一步
- 边界：规划=引擎服务但还没有"任务执行器"调用它（MmtrJobScheduler/MmtrMission 接线下一步）；
  站台停点换算（Platform 对象 → 目标轨+停点比例/对齐）属任务层；反向/回库（目标=Siding yard 轨
  尾部停点）可复用同一 planner（目标轨=yard rail，注意"当前轨=目标轨"分支需先出库再规划）。
- 下一步：
  a. 任务执行器：MmtrMission/作业 MOVE_TO 步进改用 planner+auto+StopTarget 驱动 motion 车
     （任务车闭环验收），SERVE=dwell 计时 + 再武装；
  b. 行经段信号/限速（M2）；c. T4 收尾评估 + 交接/实机文档刷新（记录 slice-1..6 现状与剩余）。

# 10 MMTR 任务编排系统（web 驱动，替代 depot 发车时刻表）

## 用户锁定决策（round23 提问）
1. 目标点引用 = 游戏内已有对象 id：platform / siding / 车厢(编组)。车站画框、depot、轨道仍是世界几何来源，保留。
2. 编排模型 = 车底作业单 (consist job)：一条 = 一车一整天，按时间顺序的任务步骤。
3. depot 频率/发车逻辑 = 彻底移除（由 web 作业单驱动）。MTR 内容（块/车/轨/站）保留。

## 目标流程
设置某时间 -> 在某 depot 股道刷出某编组 -> 该车开始执行一个有序任务列表
-> 每步必须在各自截止时刻前完成 -> 完成情况回写 web 面板/feed。

## 任务步骤类型（MmtrJobStep.StepType）
- MOVE_TO：运行到 targetId（platform/siding），完成=到达，dueTimeOfDayMs=最晚到达。
- SERVE：当前站台停站作业（开门/等待/关门），完成=关门，due=最晚离站。
- COUPLE：把 targetId 对应的编组连挂上来（同股道停车后连挂）。
- UNCOUPLE：当前股道摘挂，targetIndex 指定切开车厢位置。

## 模型（engine, org.mtr.core.mmtr.job, round23 已落地 + 单测）
- MmtrConsistJob：jobId / depotId / sidingId(刷车股道) / startTimeOfDayMs(当日时刻, ms after in-game midnight)
  / repeatDaily / cars(MmtrCarSpec[]) / steps(MmtrJobStep[] 保序, 每步带 due)。
- MmtrCarSpec：稳定字段（映射到 VehicleCar 由执行器构造）。
- id 一律以字符串走 JSON/web（64 位防 JS 丢精度），引擎内部解析为 long。
- MmtrConsistJobTests：含 >2^53 id 的全量 JSON round-trip + 顺序 + 默认值。

## 彻底移除 depot 发车逻辑（B）——执行策略（后续轮次）
- 范围：Depot 频率表/自动 departures/按 departureIndex 的 Siding 自动发车，在 mmtr 作业模式关闭；
  车辆的生成与启动完全来自 MmtrJobScheduler。老 MTR 内容不删（数据保留），调度引擎由作业单取代。
- 路径/进路复用：仍用现有 depot->platform 的 SidingPathFinder/Route path cache：作业单里的平台序列
  在执行时登记为一次"临时交路"路径（MTR Route 机制），ATO 按停站跑，mmtr lifecycle 把每次到站/关门
  与步骤完成/截止对齐。这样 1（到点）、2（乘降开关门）都由现有停站机制驱动，引擎改动最小。
- 连挂/摘挂（3）：站内作业调用 MmtrCoupling/MmtrCoupleControl 已有能力。

## Web（后续轮次）
- config mmtr-jobs（服务端 JSON，engine 读入 Simulator，像 mmtr-consist-types 一样 auto-load）。
- CRUD 端点 + 编排 UI（面板扩展）：新建/编辑作业单、拖拽步骤、时刻与截止编辑、编组选择；
  该面板取代 depot 频率/时刻表操作入口。
- feed/面板：展示每条作业单状态（等待刷车 / 步骤 N 执行中 / 完成 / 超时失败）与截止倒计时。

## 风险/保留
- 彻底移除 depot 发车对老存档原 MTR 运营=停摆，仅当用户开启 mmtr 作业模式；默认行为后续再定
  （config 开关 mmtrJobsEnabled，默认 false 保留原逻辑到迁移完成）。
- 每步到期未完成 => job FAILED + 记录原因（面板红字）。

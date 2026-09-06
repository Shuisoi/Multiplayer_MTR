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

## 进度（round1）
- MmtrJobScheduler（引擎执行器骨架）：按刷车时刻(operational day-time)启动股道上停场的编组 ->
  挂 mission 无头跑当前路径终点(terminal)；每 tick 推进：mission COMPLETE -> 下一步/DONE；
  step 超 dueTimeOfDayMs 未完成 -> job FAILED(记录原因)；车消失 -> FAILED。
- Simulator 接入 mmtrJobScheduler 字段并在 simulateVehicles 后每 tick 调用。
- MmtrJobSchedulerTests（合成世界确定性）：单步 due 120s -> RUNNING(t5) DONE(t37)；
  due 6s -> FAILED(t7, "missed deadline")。
- 说明：round1 步骤语义= MOVE_TO 到路径终点；SERVE/逐站停/COUPLE/UNCOUPLE 与平台级 ATO 停站
  由后续执行器轮次接入；刷车(编组由 job.cars 生成) 尚未做（当前用世界已有停场车）。
- engine 全量：192 tests / 0 fail / 0 error。

## 进度（round2）：执行器 AUTO 服务模式（真实逐站停靠/乘降）
- 机理：车门/停站乘降只在引擎 AUTO 分支；MmtrJobScheduler 新增 AUTO 模式：刷车时刻到 ->
  siding.startGeneratingDepartures + addDeparture(绝对时刻) 建立合法 departure 簿记 -> vehicle.startUp 显式发车
  -> 引擎 ATO 沿生成交路逐站停靠（自动开关门/驻留），调度器按站台访问推进步骤：
  MOVE_TO(target)=该站停稳；SERVE(target)=该站关门离站；超时/车消失/coupling未接 => FAILED。
- MmtrJobSchedulerTests.autoServiceRunsPlatformStepsWithDeadlines（合成 auto 世界，确定性 step）：
  MOVE_TO A -> SERVE A -> MOVE_TO B 全链 DONE(step=3, t≈28)，engine ATO 真实停靠两站。
- MANUAL 模式（round1 终点跑法）与 MANUAL 测试保持绿。
- engine 全量 193 tests / 0 fail / 0 error。

## 进度（round3）：按 job.cars 定时刷车
- MmtrJobScheduler.pending：到刷车时刻若股道无停场车 -> 把 job.cars（MmtrCarSpec->VehicleCar）set 到股道，
  引擎下一 siding tick 生成停场编组 -> 自动进入 MANUAL/AUTO 执行；无编组模板或 30s 内未能生成 => FAILED。
- MmtrJobSchedulerTests.jobSpawnsItsOwnConsistFromJobCarsThenRunsService（auto 世界无预设车，job.cars=1 节，
  MOVE_TO A -> MOVE_TO B）DONE step=2，证明“某时某股道刷出某车”真正可用。
- engine 全量 194 tests / 0 fail / 0 error。

## 进度（round4）：jobs 配置存盘 + Simulator 存储 + CRUD 端点
- MmtrJobRegistry：{jobs:[...]} 存 <root>/<dimension>/mmtr-jobs.json（64 位 id 字符串化）；put/remove/fromFile/save（自动建目录）。
- Simulator：构造时若 mmtr-jobs.json 存在则加载并挂 MmtrJobScheduler；upsertMmtrJob/deleteMmtrJob 存盘并重建调度器。
- SystemMapServlet CRUD：GET mmtr-jobs（列表）、POST mmtr-jobs-upsert（单条作业单）、POST mmtr-jobs-delete（{jobId}）。
- MmtrJobRegistryTests：文件 round-trip（含 2^53+ id）+ 端点增删改查。
- engine 全量 196 tests / 0 fail / 0 error。

## 进度（round5）：作业单状态 feed
- SystemMapServlet POST/GET mmtr-job-states：{states:[{jobId,startTimeOfDayMs,state,step,totalSteps,failure?}]}，
  供 web 面板轮询显示作业单运行状态/进度/失败原因。MmtrJobRegistryTests 覆盖。

## 进度（round6）：web 作业单列表（面板）
- MmtrJobsService 轮询 mmtr-jobs + mmtr-job-states；MMTR 任务面板新增“作业单 / Consist Jobs”区：
  每条 jobId/发车时刻/步数/当前状态(PENDING/RUNNING/DONE/FAILED 着色)/步骤进度/失败原因。npm build 绿。
- 编辑器（步骤/时刻编辑 + upsert/delete 写回）为下一刀（数据接口已备）。

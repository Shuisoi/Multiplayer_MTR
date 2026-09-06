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

## 进度（round9）：mmtrJobsMode 开关（①收口第一刀）
- Simulator.mmtrJobsMode（默认 false，迁移期保留原 MTR 时刻表；开启后 Siding.matchDeparture 返回 -1，
  旧 depot 频率/时刻表不再自动发车；车辆只由 MmtrJobScheduler 显式 startUp 启动）。
- MmtrJobSchedulerTests.jobsModeSuppressesLegacyAutoDispatch：job 模式下有 departure 也停在股道不动。
- engine 全量 197 tests / 0 fail / 0 error。
## 进度（round10）：web 作业单编辑器（列表补全 + 新建/编辑 + 写回）
- SystemMapServlet 新增 GET mmtr-job-references：世界 depots/sidings/platforms（十进制 id 串 + 名称/
  depotName/stationName + manual）作编辑器 picker 数据；MmtrJobRegistryTests 覆盖（数组非空断言）。
- MmtrJobsService 升级为完整数据平面：job 全量类型（MmtrConsistJob/cars/steps）、references 轮询、
  upsert(job)/delete(jobId) 写回 + writeFeedback；补全 round6 未渲染的“作业单 / Consist Jobs”列表区
  （jobId/发车时刻/每日/车数·步数/股道名 + PENDING-RUNNING-DONE-FAILED 徽章 + 步骤进度 + failure 红字）。
- 编辑器（面板内替换式，app-mmtr-job-editor）：新建/编辑 job——刷车股道(p-select 由 references 填充，
  选中自动带出 depotId)/发车时刻 HH:MM/每日重复/编组车辆规格（vehicleId·长宽·定员·转向架·连挂余量，增删复制）
  + 步骤有序列表（类型 MOVE_TO/SERVE/COUPLE/UNCOUPLE、目标 id（datalist 提示站台/股道）、截止 HH:MM、
  备注、↑↓ 排序、删除）。保存校验：jobId 留空自动生成且唯一、股道必选、编组非空且 vehicleId 齐全、
  每步截止 HH:MM 合法、UNCOUPLE 需 targetIndex、非 UNCOUPLE 需目标 id → mmtr-jobs-upsert 写回；
  删除走 mmtr-jobs-delete（按钮两次点击确认）。保存/删除后立即 refresh 回列表。
- 说明：COUPLE/UNCOUPLE 步骤可编辑保存，引擎执行器（coupling executor）接入仍在后续轮次。
- engine 全量（cleanTest）197 tests / 0 fail / 0 error；website ng lint + build 绿（exit 0，
  编辑器 scss 有 >2kB 预算警告，非阻断）。
## 进度（round11）：COUPLE 目标=作业单 jobId（建模/Web）+ 连挂执行器真实阻塞点定位
- MmtrJobStep 新增 targetJobId（仅 COUPLE）：引用"另一条作业单"的稳定字符串 id —— 服务器每日重启/
  全部车辆重生后引用依然有效（对应机车去 depot2 连挂 8 节挂车的货运宏观场景）；MOVE_TO/SERVE 仍用
  numeric 64 位平台/股道 id；序列化 COUPLE 写 targetJobId、其余写 targetId；旧 web 编辑器写入的非数字
  targetId 自动迁移到 targetJobId。MmtrConsistJob 数值 id 改走独立 parse helper。
- web 编辑器：COUPLE 步骤改为"目标作业单"下拉（列出其它 jobId + 节数，排除自身）；保存校验目标存在且
  非自身（原自由文本/占位已移除）。
- 测试：registry 文件 round-trip 保留 targetJobId（含 numeric 目标并存）、legacy 非数字 targetId 回退；
  engine job 包（Registry+RegistryTests+SchedulerTests）全绿；website lint+build 绿（exit 0）。
- 执行器真连挂合并 = 未决阻塞（本轮到定位层面，next = 编组手术 spike）：
  1) Siding.tick 对同股道停场车 >1 直接移除（trainsAtDepot>1→remove）——"两列同股道停稳再连挂"在
     每 tick 循环下不成立；
  2) Vehicle.vehicleExtraData final、车厢集在 create() 定型（车辆长度/乘客按车位置缓存）——无动态编组；
  3) MmtrCoupleControl/MmtrCoupling 只做 guard+plan log，真 registry 手术注释明示 pending world executor。
  因此"机车连上挂车后整车拉走"必须先做 M2 编组手术：同股道停场 union 重建（合并车厢→移除原车→按合并
  模板重生成单车并沿用后续 ATO）或等效的双车停场窗口。该手术落地后 COUPLE/UNCOUPLE 步骤才有真实完成
  条件与推进测试；建议下轮直接进入该 spike。
## 进度（round12-A1a）：停场编组重建原语（编组手术第一刀）
- Siding.rebuildParkedConsist(cars)：把本股道上唯一停场（未上正线）编组替换为按给定车列重建的单车——
  停场 union（COUPLE）与摘挂后头部（UNCOUPLE）的公共底子。守卫：空闲（无在途车）/恰一辆停场/
  车数<=TransportMode.maxLength/总长<=股道 railLength/有 defaultPathData；成功后同步股道模板
  （setVehicleCars），旧车注销、新车以合并车列重建并入册。
- 测试（MmtrJobSchedulerTests.yardSurgeryRebuildsParkedConsistAsSingleFormation）：auto 世界股道
  加长至 33m 容纳 3 节；停场 1 节→合并重建 3 节（loco+2 平板车）；断言旧车替换、新车入册、模板同步、
  连续 tick 后仍是单辆停场（不破坏单停场不变量）。
- 引擎全量（cleanTest）绿。说明：调度器把 COUPLE/UNCOUPLE 步骤接到该原语并解决"两列同到一条股道"
  的到达/驻留语义 = 下一切片（A1b），随后做 B（MOVE_TO 股道目标移动）。
## 进度（round13-A1b）：COUPLE 起始编组收集（make-up）接入调度器 + 车底源 job
- MmtrJobScheduler 新增"编组收集"起始模式：第一步为 COUPLE 的 job 到点时不自行刷车，而是等待目标
  作业单（targetJobId）的挂车编组停场于同股道后，用 Siding.rebuildParkedConsist 把
  [本 job 车列 + 目标车列] 合并重建为单车（真实物理编组），目标 job 标记 consumed，随后按剩余步骤
  （MOVE_TO 等）正常发车。支持同 depot 同股道先后刷车 -> 自动连挂 -> 出库跑作业的编组作业流。
- 空步骤 job = 纯"车底源"（如 8 节挂车）：到点刷车后停场等待（DONE，不再尝试挂 mission），供后续
  COUPLE 收集；归属账本 claimedByOther 防止其它 job 误抢已认领的停场车。
- 明确失败原因：目标 job 未加载 / 未到点刷出 / 已并编(consumed) / 超截止 / 编组超股道限长，均带可读
  信息回写 web feed。
- 测试（MmtrJobSchedulerTests）：jobCouplesEarlierTrailerStockThenRunsService——挂车 job 先停场 2 节
  -> 机车 job 5s 后同股道收集成 3 节 -> AUTO 逐站跑完 COUPLE+MOVE_TO+MOVE_TO 全链 DONE（步骤=3）；
  couplingStepFailsWhenTargetJobIsNotLoaded——未知目标立即 FAILED（"not loaded"）。
- 说明：跨 depot / 机车开往 depot2 任务点股道（到达窗口）仍受 MTR 单停场不变量约束，属下一阶段 B
  （MOVE_TO 股道移动）+ 到达编组窗口；UNCOUPLE 摘挂的股道切分尾车停场窗口同属其后。本轮 make-up
  是同股道车场编排可用的真实合流路径。
## 进度（round14-A1c）：UNCOUPLE 车场摘挂（尾部留场）接入调度器
- MmtrJobScheduler 在发车前增加车场作业阶段：停场编组当前步为 UNCOUPLE 时执行切分 —— 按 targetIndex
  把车厢切成 head（续跑本 job）与 tail；head 经 Siding.rebuildParkedConsist 重建继续，tail 写回
  股道模板作为下一批"车底源"，等 head 离场后引擎按模板自动重生成停场挂车（保持引擎单停场不变量，
  不引入双车停场窗口）。
- job 维护 fleetCars（规格车列）：普通刷车=job.cars；make-up 合并=job.cars+目标.cars；切分后=head。
  归属/已并编账本沿用；车场作业带截止检查；中途(非停场)遇到 COUPLE/UNCOUPLE 给出明确失败原因。
- 测试 jobUncouplesTrailersAtYardThenRunsServiceAlone：挂车2节 -> make-up 3节 -> UNCOUPLE(idx0) 切出
  2 节 -> 机车单车跑完 2 站 DONE（步骤=4），切出挂车以 2 节停场重现在车场；make-up/切分/失败分类既有
  测试保持绿。全量 cleanTest 绿。
## 进度（round15-B）：MOVE_TO 退库(回本场股道) + 回场后再出库/摘挂
- 实测确认：AUTO 服务跑完会自动返回本车场股道停场（onRoute=false/closeToDepot=true/depIdx=-1 常驻）。
- MmtrJobScheduler 增加"车场/停场阶段"统一处理（running 内 while）：
  * MOVE_TO 目标 == 本 job 股道 -> "退库"完成（回到车场即置完成）；
  * 停场遇到 UNCOUPLE -> 执行车场切分（head 重建留场作业、tail 模板留作下批车底源）；
  * 回库/切分后若还有运行步骤 -> awaitingStart 标记触发二次出库（startOutbound：MANUAL 挂 mission /
    AUTO addDeparture+startUp），同一编组可"出库->跑图->回库->再出库/摘挂"多程；
  * 中途（未停场）遇到 COUPLE/UNCOUPLE 给出明确失败原因。
- startOutbound 收敛 pending 与回场重启路径；instance.started/awaitingStart 区分初次发车与回场待发。
- 测试：jobReturnsToYardWhenStepTargetsItsOwnSiding（MOVE_TO A -> SERVE A -> 退库，DONE 且最终停回本场）；
  fullMacroCoupleServiceReturnAndUncoupleAtYard（挂车 make-up 3节 -> 跑图 -> 回库 -> UNCOUPLE 切出挂车，
  5 步全 DONE，模板回到 2 节挂车源）。既有 MANUAL/AUTO/make-up/uncouple 全部保持绿；全量 cleanTest 绿。
## 进度（round16）：编组手术运营路径改用"引擎单次自生" + 文件自加载 e2e（关键修正）
- 实测发现：手工 rebuildParkedConsist 重建的车（VehicleExtraData.create）其运行 path 退化为仅股道段，
  启动后只到股道尽头便停（totalDistance≈股道长），无法出正线；引擎自生（Siding 361 路径）的车正常。
- 修正 COUPLE 语义为"刷车模板组合（spawn-time make-up）"：COUPLE 第一步在本 job 尚未刷车前，把目标
  作业单车列并入本 job 的 spawnCars，placeCars 一次放置合并模板 → 引擎单次自生合并编组 → 认领后照常
  AUTO 服务/回库/摘挂。目标车列作业单标记 consumed（不再刷车）；若 COUPLE 时目标车已停场（引擎单停场
  限制）→ 明确排序错误原因（COUPLE 须早于源车刷车时刻）。
- UNCOUPLE 限定"车场最终切分"：切分后无后续出库步骤（后续出库需编组再生的引擎路径，尚待接入）；
  尾部车列写回股道模板留场。
- 新增 Siding.clearParkedVehicles（清场/日重置）；测试世界目录参数化，连挂/宏/e2e 测试各自独目录+
  preset 车+清场 → 消除 assumption 跳过（此前大量"假绿"源于共享目录 assumption skip）。
- 文件自加载 e2e（registryFileDrivesFullMacroEndToEnd）：web 写回的 mmtr-jobs.json → Simulator 构造
  自动载入并挂调度器 → 整条宏（make-up 3 节 → 跑站 → 回库 → 摘挂 2 节）真实跑完 DONE；另验证
  ctor 自加载路径。全部 12 个 MmtrJobSchedulerTests 真实执行（含 make-up/final-cut/full-macro/
  return-yard/file-e2e），全量 cleanTest 绿。
## 进度（round17）：真实 dev 世界冒烟（引擎直接加载 Minecraft 开发存档）
- DevWorldJobSmokeTests：把真实存档 mmtr/game/fabric/run/saves/新的世界/mtr 直接载入引擎 Simulator，
  用 web 风格作业单（真实 depot 名下股道 id + 真实站台 id）认领现存手动车底并驱车上线运行：
  观测到 RUNNING（车辆在真实轨网移动、无失败）。启动接受型断言，避免活存档（动态车流/manual 车场）
  导致不稳定；完整链路完成性由合成世界 + 文件自加载 e2e 保证。
- 实际存档拓扑：1 depot(112330) / 名下 1 条 27m manual 股道 / 4 站台 / 单路线 3 站台；
  另发现 3 条无 depot 的残轨股道（选股道须从 depot.savedRails 取）。manual 车场引擎不自生车
  （需要玩家/时刻表），清场+自生仅适用于 auto 车场——已在合成世界覆盖。
- 引擎全量（cleanTest）207 tests / 0 fail / 0 skip（含真实世界冒烟）。
- 到达目标验收状态：A 连解挂执行器闭环 + B 退库/回场再作业均已在确定性合成世界与文件驱动 e2e 验证，
  真实存档亦能接受 web 作业单并驱动实车；最终"在游戏内"由用户在实机运行 MC+Fabric 观察面板完成。
## 进度（round18）：编辑器语义提示（把引擎排序约束带给编排者）
- mmtr-job-editor 新增 stepHint：
  * COUPLE：提示目标作业单（jobId 稳定引用、刷车时并入车列）；若目标发车时刻 <= 本作业单 -> 明确警告
    "引擎要求 COUPLE 先于目标刷车"；否则显示已满足。目标下拉标签带发车时刻与节数，便于排序选择。
  * UNCOUPLE：提示为"车场最终切分（切分后不再出库；尾部车列写回股道留场）"，避免误用后续出库场景。
- 校验逻辑沿用（保存仍会在引擎侧拒绝错误排序）。website lint+build 绿（editor scss 预算警告为既有）。
- 说明：repeatDaily 的"每日重启→全部车辆重生→registry 重载重跑"在进程重启语义下天然成立（构造即
  自动加载 mmtr-jobs.json 并挂调度器）；进程内跨日循环与跨车场任务点移动属 M2 路由范围，留待后续。
## 进度（round19）：运行手册 + 重启循环探针结论
- 新增 docs/作业编排-运行手册.md：概念/数据位置/编排约束（COUPLE 先于源刷车、UNCOUPLE 车场最终切分、
  退库语义、跨车场边界）/跑通完整宏/真机 MC+Fabric 联调步骤/已知边界与自动化回归清单。
- 探针结论：Simulator 默认不自动落盘世界（同根重建 rails=0），进程内"日重启"e2e 不可直接构建，
  repeatDaily 依赖进程重启语义（构造即自动加载 mmtr-jobs.json 重跑）或显式存档接口（后续）。
- 引擎全量（cleanTest）207 tests / 0 fail / 0 skip。

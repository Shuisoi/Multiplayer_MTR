# 24 - L3/T4 目标验收证据映射（goal 收口）

> 目的：把目标原文逐条映射到交付物（提交/测试/文档/日志），供验收与后续会话核对。
> 全量基线演进：227/0/2（目标起点，本目标前）→ **247/0/2**（现 HEAD 4f6ce8a，零新增失败）。

## 目标条款 → 证据

| 目标条款 | 交付证据 |
|---|---|
| 真实 Vehicle（手动车）运行切换为 Motion Core 逐 tick (segment+offset) | Vehicle.engageMmtrMotion + simulateMmtrMotion（Vehicle.java）；walker=运动权威（MmtrMotionWalker.distanceM/advance） |
| 每 tick 纵向推进（现有座舱控制驱动） | simulateMmtrMotion：ControlState→ConsistDynamics 物理→walker.advance（slice-1）；MmtrVehicleMotionRunTests / MmtrYardMotionDepartureTests / DevYardMotionE2ETests（真实 dev 存档出库运行） |
| 到节点实时按当前道岔状态选下一段 | walker.electAtFork 每节点现读 BranchStore（MmtrNodeRouter.electFromStore）；搬 0/搬 1 翻转用例：MmtrVehicleMotionRunTests、DevWorldTurnoutFlipTests、DevYardMotionE2ETests(-96) |
| 按任务目标选下一段（经 MmtrNodeRouter） | slice-8 MmtrMotionWalker.setTargetRailHex live 重定向：任务目标覆盖陈旧 operator、未设岔上任务即权威（MmtrMotionTaskTargetTests）；router 层 task 优先单测（MmtrSegmentMotionTests） |
| 未设权威岔口停车等待（绝不 auto） | walker haltedAtAuthority + vehicle 停车等待/重问：MmtrSegmentMotionTests、MmtrLiveRouterTests、MmtrVehicleMotionRunTests、DevYardMotionE2ETests（-96 未设停车） |
| 搬岔即换向（同一辆车） | slice-1..3/5/8 全部翻转用例（合成 + 真实 -96 存档 + auto + 任务重定向） |
| 任务车 | slice-7 mission 闭环（MmtrMotionMissionTests：MissionControl 派发→planner+auto→平台轨精确停稳 AT_TARGET→终态交回）+ slice-9 自武装（任意来源挂任务即执行，不可行 FAILED+原因） |
| 精确停稳（进目标停靠） | slice-4 setMmtrMotionStopTarget 制动包络精确落点零过冲 + 门控（MmtrMotionStopTargetTests）；slice-6 planner 停点换算（MmtrRunPlannerTests 132.0m 双岔链精确停） |
| 无人自动运行 | slice-5 auto step-run（MmtrMotionAutoRunTests，无司机、未设岔等、搬岔自动续） |
| 进路规划（MOVE_TO 底座） | slice-6 MmtrRunPlanner（BFS+岔口预置+停点换算，不可行原因报告；MmtrRunPlannerTests） |
| 全量 gradlew test 零新增失败 | 每片 cleanTest test 全绿：231→233→234→236→238→241→243→245→247，failed=0 恒成立（dev 存档用例本机通过，Assumptions 门控） |
| 每片测试证据后提交并更新 notes/交接文档 | notes/14..23 逐片记录；提交 b6cbf6e/cdbf6a9/3d6a19c/6ac7a06/462c664/a937365/1956115/444d728/4f6ce8a + T4 文档 982a79d；交接文档头部「L3 主线进展」块、设计文档状态块、docs/README 索引逐轮更新 |
| T4 收尾 | notes/21：自动烘焙删除面完成（legacy VehicleExtraData.create 全仓 0 调用）；残余机制（writePathCache 存档兼容 / defaultPathData 停场模板 / legs 归档接缝）审计并给出保留理由 |

## 验收锚点（可直接复跑）
- 全量：cd mmtr/engine && ./gradlew.bat cleanTest test（expect BUILD SUCCESSFUL, 247/0/2）。
- 目标关键用例：
  - 真实存档手动出库活搬岔：DevYardMotionE2ETests.realYardDepartureToMinus96WithLiveFlip
  - 任务闭环：MmtrMotionMissionTests（4 例）
  - 自动步进：MmtrMotionAutoRunTests；运行中任务重定向：MmtrMotionTaskTargetTests
  - 进路规划：MmtrRunPlannerTests；精确停点：MmtrMotionStopTargetTests

## 范围外（按项目文档属 M2/M3/M4 另立项，不阻塞本目标验收）
作业宏（consist-jobs 多步 + COUPLE/UNCOUPLE）的 motion 版编排与作业车 motion 出生；
M2 行经段信号/限速裁决；平台停点对齐换算；客户端渲染/镜像推送；实机人工确认清单（03 文档 §7）。

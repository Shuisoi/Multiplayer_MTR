# 21 - T4 收尾评估 + L3 主线状态（本会话收口）

> 目的：客观记录"旧烘焙机制删除"的最终状态（T4），审计仍存在的路径机制与保留理由，
> 并汇总 L3 主线（slice-1..7）的达成面/边界，供交接文档刷新与新会话接手。

## 1. T4 收尾评估（2026-09 会话）

### 已删除（本目标前 + 期间完成，全量零回归）
- MTR depot 自动路径生成管线：Depot.tick 自动出库/主线/回库三份缓存拼装
  （pathSidingToMainRoute/pathMainRoute/pathMainRouteToSiding）、per-siding generateRoute、
  SidingPathFinder 生成期 disallow、时刻表自动发车/ATO 平台自动停靠、matchDeparture、
  depot auto generateDepots/InstantDeploy/Clear + 对应 Packet/命令（mod 侧同步删）；
- legacy VehicleExtraData.create(...)（三缓存拼装）→ **main+test 全仓 create( 调用 = 0**，
  全部 17 处走 createWithLegs（Motion legs 或 yard defaultPathData）。

### 仍存在（审计 + 保留理由）
| 机制 | 位置 | 保留理由 |
|---|---|---|
| PathData.writePathCache / Depot.writePathCache / Data.java:139 | 存档路径缓存持久化钩子 | 显式工具/存档兼容用途（Depot 118 注释即述），不构成自动发车/运行裁决；删除需动存档兼容层，风险>收益 |
| Siding 停场模板 spawn（createWithLegs([defaultPathData])） | Siding.simulateVehicles/init/rebuild/453 | 停场车姿态与 manifest 再播种语义仍由它承担；**motion 车经 spawnMmtrMotionVehicle 替换/接管后同一 siding 不再自生**（slot 旗标已占） |
| spawnMmtrManualWithLegs（T3 接缝） | Siding:353 + 3 处测试 | 归档语义：dispatch 时一次性烤 legs 的旧派发形态，被 MmtrVehicleLegsRunTests/MmtrVehicleMotionRunTests 锁住；live motion 派发请用 spawnMmtrMotionVehicle + planner |
| legacy 车（未 engage motion）沿 defaultPathData/legs 的 simulate 运行 | Vehicle 主路径 | 未迁移车辆的兼容运行方式；motion mode 是"engage 后"的权威模式，切换点 = engageMmtrMotion/停场模板，两态无需并行烘焙 |

结论：T4 的"删除旧烘焙"在**自动生成面**完成（depot/siding/时刻表不再烘焙任何运行路径）；
剩余均为有主（存档兼容/停场姿态/归档测试）的保留物，已在文档中标注职责，不需要再删。

## 2. L3 主线达成面（slice-1..7，全部零新增失败）
| slice | 内容 | 测试 | 提交 | 全量基线 |
|---|---|---|---|---|
| 1 | Vehicle live motion mode（内嵌 walker、(segment+offset) 逐 tick、岔口实时裁决、未设岔停车、搬岔即换向、增长影子 legs、mmtr-motion 快照） | MmtrVehicleMotionRunTests(5, 含真实 -96 活搬岔) | b6cbf6e | 231/0/2 |
| 2 | yard 停场起步（startAtOffset）+ Siding 发车接缝 spawnMmtrMotionVehicle | MmtrYardMotionDepartureTests(2) | cdbf6a9 | 233/0/2 |
| 3 | 真实 dev 存档端到端：停场车出库→-96 未设停车→活搬岔跨被选真实轨 | DevYardMotionE2ETests(1) | 3d6a19c | 234/0/2 |
| 4 | 精确停点（制动包络精确落点、按请求开门、新令续行、运行中再武装）+ slice-1 回落单位修正 | MmtrMotionStopTargetTests(2) | 6ac7a06 | 236/0/2 |
| 5 | 无人自动运行 auto step-run（无司机；任务再武装自动续行；未设岔等、搬岔自动续） | MmtrMotionAutoRunTests(2) | 462c664 | 238/0/2 |
| 6 | MmtrRunPlanner 进路规划服务（BFS + 岔口预置 + walker 空间停点；不可行原因报告） | MmtrRunPlannerTests(3) | a937365 | 241/0/2 |
| 7 | 任务驱动 motion 车闭环：MissionControl 派发 AUTOPILOT → planner+auto 武装 → 平台轨精确停稳 AT_TARGET 门开 → 终态交回 idle | MmtrMotionMissionTests(2) | 1956115 | 243/0/2 |

引擎级验收（设计 §10.4 / 实机文档 §0）对应达成：真实 Vehicle 出库→岔口按当前道岔态换向→目标精确停稳，
全程 Motion Core；-96 翻转"车实际换走另一轨"在真实存档验证（slice-3）；手动与任务(AUTOPILOT)两类车都在 live 模式闭环。

## 3. 剩余工作（诚实边界，需另立项/后续轮）
1. **MmtrJobScheduler / consist-jobs 接 motion**：SERVE/MOVE_TO 步进用 planner+auto+StopTarget 驱动
   motion 车（现 MissionControl 单任务已闭环；作业宏=多步调度编排 + dwell 计时器 + COUPLE/UNCOUPLE 语义）；
2. **行经段信号/限速裁决（M2）**：motion 版占用已写，信号预订/限速按当前段判定的 motion 分支未做
   （legacy 车仍有 path 停止点语义）；
3. **平台停点对齐**：现停点=平台轨末端（fraction 1.0），站台对齐/车长换算任务层补；
4. **客户端渲染/镜像**：motion 车客户端推送（增长式 legs / mmtr-motion feed 已有引擎侧）未接；
5. **实机人工确认清单**（03 实机测试文档 §7）：引擎/服务 URL 端口、真实场区 siding/depot id、
   rolling-stock manifest 存在性、MC 客户端"上车+键位→mmtr_drive"接线；
6. **单位备忘**（反复踩坑点，勿改）：VED 存值=SI×1e-3；内部速率=SI×1e-6（m/ms 每 ms）；
   Δv/tick = 速率×dt；停距 v²/(2a) 用 SI 换算后比较。

## 4. 验证纪律（沿用）
每片先全量回归记基线（现 243/0/2 全绿）；改 Vehicle/Siding 运动相关零新增失败；
dev 存档用例本机通过（Assumptions 门控）；提交后同步 notes + docs 索引。

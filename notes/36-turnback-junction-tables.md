# 36 - 终端掉头 + 进向表道岔模型（时刻表整环跑通）

> 用户方向（2026-09-08）：给 6 列 1 节车跑"完整时刻表"并持续运行；到尽头后列车要
> 换向回库（"两条轨道，肯定从另一条开回去；道岔先设 1 让车到另一条线，再设 0 让换向
> 的车从另一条线回库"）。用户明确：**不能用简单的几何自动检测**——以后会有各种高密度
> 道岔，进向必须数据化。

## 勘察结论（live world）

- 线路 = 车库扇区 → x=-170 下行(站1-3) → 北端气球(#96/#58) → x=-155 上行(站4-10) →
  **车站10 南端尽头换向区**（#75/#68/#9/#61，车站10 再往前那一段即换向）→ x=-147 →
  北端气球(#36/#87) → x=-176 → 道岔区 → x=-170 咽喉 → 车库：整环正向可达（无需倒退）。
- TT-01 原路跑到车站10 后"退库"任务规划失败：`turnout at node requires a branch
  outside the walker's branch0/1 choice`。根因：x=-155 → #9（对角短轨）→ 在 (-147,-169)
  接 x=-147 上行线的转向约 157°，**端点向量 cos≈-0.93 < -0.9**（人字道岔防折返线），
  被几何自动判定排除；而该处正是用户画的换向锐角环（存储端角显示 #9 端切向与 x=-147
  一致）。调查确认：弯曲/发夹连接轨不能靠端点连线（弦向）判断连续性。

## 方案：进向表（junction leg tables）

- 新 `MmtrJunctionLegsRegistry`：`(节点 x,y,z, 进向 viaRailHex) → 有序允许续接轨列表`，
  持久化 `<world>/mmtr-junction-legs.json`，人/工具设定，**表存在时完全取代几何判定**
  （顺序即支路序，180° 折返轨也可被计划/授权寻址）；无表路口保持原几何排序（兼容）。
- 接入层（全部走同一个 `MmtrPoint.computeOrderedLegs(node, via, neighbors, declared)`）：
  - `MmtrMotionWalker.electAtFork`（运行期选支）
  - `MmtrRunPlanner.branchOperator`（任务规划岔位）
  - `MmtrPoint.discoverDirectionAware`（点发现/进向表默认 0 写入的判定）
- 服务器默认给每个岔口写 operator 分支 0，且 walker 优先级 operator > 授权——任务武装
  （`Vehicle.armMmtrPointRun`）现在会**先清掉自己计划岔口的 operator 行**再请求授权，
  换向支路（leg1）才能按"先设 1 → 过岔 → 释放回 0"自动执行。
- API：`mmtr-junction-legs`（查）/ `mmtr-junction-legs-upsert`（写，legs 空=删除）；
  `scripts/author-junction-tables.ps1` 按端点坐标自动取 hex 写表（幂等）。
- 实测写表：节点 (-147,-169) via #9（x=-155→x=-147 对角）→ legs [#61 死岔, #38 x=-147
  上行线]，掉头 = leg 1。

## 调度/作业修复（同片）

- MANUAL 步进任务目标 = step.targetId（原来传 0，motion 任务直接失败）；SERVE 步 = 停站
  门（到达任务已含开关门停站）；平台目标 PASSENGER / 股道目标 MANEUVER。
- `mmtrAiJobStepsEnabled` 随作业存在自动开启（原来永远 false，调度器不 tick）。
- `placeCars` 补 `mmtrManualSpawn`（循环重生曾死于 "stock never spawned"）。
- 循环复位先 `deleteMmtrVehicle` 物理删除旧车（回库车仍标 onRoute，`clearParkedVehicles`
  清不掉 → 同股道叠车+新生成车反向）。

## 实测（dev server）

- 6 作业（TT-01..06，30s 起每 90s 一台，10 站 + 退库，loop 循环）：TT-01 完整跑通整环
  两圈并回库；其余列车依次完成；尽头折返/北端气球/x=-176 道岔区均正向贯通。
- 已知：车库咽喉（x=-170 z -122..-199）双向共用，6 车整环偶发迎面对顶死锁 → 步骤超时
  （约 8 分钟）自动失败重启自愈（用户已确认咽喉按"整个区间"设计由他自己处理信号/对顶）。

## 状态

- engine 全量测试绿（290/0/2 基线保持，含 2 个跳过=live-world survey gating）。
- 本片文件：MmtrJunctionLegsRegistry(新)、MmtrPoint/MmtrMotionWalker/MmtrRunPlanner/
  MmtrJobScheduler/Simulator/Vehicle(arm 清岔)/servlet(legs 端点+feed)、两脚本、
  dev-world survey 测试（设轨道世界存在才跑）。

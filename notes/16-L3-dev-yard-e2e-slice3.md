# 16 - L3 Slice-3：真实 dev 存档端到端（出库 → -96 活搬岔，本会话）

> 承接 notes/14（slice-1 motion mode）、notes/15（slice-2 yard seam）。slice-3 把两者接到真实 dev 存档：
> 真实车场股道里由 seam 出生的停场车，司机用现有座舱控制开出车场、沿真实咽喉到 -96 岔口，
> 未设岔停车等待，活搬岔后同一辆车跨上被选真实轨。== 交接文档 §10.4 / 实机文档第 0 节验收口径的
> 引擎级端到端达成（siding 级，Siding.simulateVehicles 调用路径）。

## 1. 测试（DevYardMotionE2ETests，1 例，dev 存档 Assumptions 门控；本机通过，0 skip）

realYardDepartureToMinus96WithLiveFlip：
- 加载真实存档（2 站/4 平台/49 轨/5 sidings/1 depot）。
- 动态发现：对每个 siding 种探测编组 → mmtrMotionWalkerFromYard → 从车场口（walker.aheadNode）
  BFS 真实轨道图到 -96 节点；沿途岔口（≥2 续向）逐一算"走哪支才能留在路径上"并预置 operator
  （branch0=cos 最大/1=次大，与 MmtrNodeRouter/elect 同规则），不可行（期望支不在 top2）则弃；
  选路径最短且可行的车场。
- 事实记录：-96 节点是多轨汇合；从车场方向的进路轨 ≠ switch registry 的 via 轨（Registry 列出的是
  另一条进路）。故测试不比对 MmtrSwitch，改为：到达后 forwardRails ≥2 才继续（否则 skip），
  翻转 operator 0 的期望支 = 从本进路算出的 cos 最大支（straightestRail），断言车实际登上的就是它。
- 过程：clearParkedVehicles → spawnMmtrMotionVehicle（yard 停场姿态）→ 司机 + MmtrDriveControl 油门 →
  siding.simulateVehicles 每 tick（≤2000）→ 车出库过咽喉到 -96 前（haltedAtAuthority，railHex==进路轨）
  → store.set(-96, 进路hex, 0) 活搬 → 同一辆车跨上直向真实轨 → removeVehicleById 清理。
- 日志证据：[MMTR-DRV] motion seg=… 逐段推进 … authority halt … awaiting operator/task → 搬岔后
  继续 seg 进入直向轨（-96→-82 方向），[MMTR-VEH] deleted vehicle … cleanup。

## 2. 未新增生产代码（纯测试切片；seam 与 walker 能力来自 slice-1/2）
- 发现/预置逻辑（BFS、cos 排行）仅存在于测试内；生产"任务路由/跨场进路"仍是后续（M2 路由/任务 slice）。

## 3. 回归证据
- 定向：data+point+segment 包全绿（含全部 dev 存档用例）。
- 全量：slice3-full.log（基线 233/0/2 → +1）。

## 4. 边界与下一步
- 边界：真实场区运行时确认仍需人工实机（web mmtr-motion/操作层、mmtr-point-op 走同一 store —— 本测试
  即引擎级等价物）；从"任意车场到任意目标"的路由规划属 M2（测试只保证 -96 可达场区）。
- 下一步：
  a. 停站/门/乘客（SERVE 语义上 motion mode）——停站目标=目标股道/站台节点，Motion 层已有
     targetRailHex/task 优先（MmtrNodeRouter.electFromStore task 覆盖陈旧 operator），Vehicle 任务
     接线待做；
  b. 行经段信号/限速（M2，替换 path stoppingPoint/railBlockedDistance 的 motion 版）；
  c. 客户端渲染推送（增长式 legs 的 VehicleUpdate 适配）；
  d. 任务 MOVE_TO → live walker（MmtrJobScheduler 侧）；
  e. T4 收尾评估：defaultPathData 残余（停场模板路径）与旧 legs 接缝的退役/保留决策。

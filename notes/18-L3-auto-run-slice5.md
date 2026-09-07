# 18 - L3 Slice-5：无人自动运行（auto step-run，本会话）

> 承接 notes/14-17。slice-5 给 motion mode 加"无人自动跑"：武装停点即自动牵引巡航、
> 制动包络精确停稳、按请求开门；任务（无司机）在停留期间自己计时，到点再武装下一停点 →
> 车辆自动关门出发（步进式运行）。未设权威岔口自动停车等待，搬岔后同一趟自动续行。
> 这是任务/SERVE 执行器与 MOVE_TO 的引擎级运行底座（每步=一个停点，无需座舱输入）。

## 1. 改动（additive，Vehicle）
- 字段/API：mmtrMotionAuto + setMmtrMotionAuto(boolean) / isMmtrMotionAuto()。
- setMmtrMotionStopTarget：自动步进语义——停在停点时再次武装新停点 = 自动关门出发
  （任务负责 dwell 计时；手动仍须新 ControlState）。
- simulateMmtrMotion：
  - autoActive = auto && 无手动 override && 停点已武装未到达 && 距目标>0；
  - 物理链 (autoActive || overridden)：auto 时喂合成 ControlState（notch=min(4,powerNotches),
    reverser=1），无政策回落分支同规则加速；制动包络、精确落点、到达保持全部与手动共用；
  - 手动 override 始终优先；释放后 auto 自动恢复；
  - HUD powerLevel 显示对 null control 安全（auto 显示 autoNotch）。
- 未变：手动自由开/岔口等待/搬岔/停点语义、Siding seam、mmtr-motion 快照。

## 2. 测试（MmtrMotionAutoRunTests，2 例全绿，全程无 override）
1. autoRunDrivesTwoStepStopsWithoutAnyDriver：auto 武装 50.0m（开门）→ 自动牵引+精确停住+开门；
   无任何司机；停稳后武装 75.0m（关门型）→ 自动出发、再次精确停住（日志 auto-departing…）。
2. autoRunHaltsAtUnsetForkAndContinuesToTargetAfterFlip：车场口未设岔（yard rail 尽头两续向）→
   auto 停在岔口（不越权）；搬 0 → 同一趟自动续行、跨上直轨、精确停在 40m 处停点并开门。

## 3. 回归证据
- 定向：data.* + mmtr.* 全绿（含 1-4 全部 slice 测试与 dev 存档用例）。
- 全量：slice5-full.log（基线 236/0/2 → 预计 +2）。

## 4. 边界与下一步
- 边界：auto 巡航 notch/限速用简易策略（notch 4、maxManual 限速），行经段限速/信号未接（M2）；
  平台停点换算（平台 rail → 累计停点、进路预置）仍属任务/M2 层；客户端无镜像。
- 下一步：
  a. 任务/SERVE 接线：MmtrMission/AUTOPILOT 用 setMmtrMotionAuto+StopTarget 步进驱动 motion 车
     （替换/并存旧 job 运行路径），MOVE_TO 进路（岔口预置+BFS 规划）从 E2E 测试提升为引擎服务；
  b. 行经段信号/限速（M2）；c. T4 收尾评估与交接文档刷新。

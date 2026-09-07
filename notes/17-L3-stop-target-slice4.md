# 17 - L3 Slice-4：motion mode 精确停点（stop target，本会话）

> 承接 notes/14-16。slice-4 给 live Motion-Core 运行加"到目标精确停稳"能力：司机/任务武装一个
> 累计停点（m，walker 距离空间，可在任意轨道中段——无需节点/站台结构），车辆在制动包络内自动
> 恒减速停车（ATO 式，覆盖司机牵引），精确落在目标点（无过冲），按请求开门并保持；司机"再次
> 给令"（新的 ControlState 应用）才关门续行；续行途中可再武装下一停点。这是平台/SERVE 语义
> （目标=站台停点偏移）与"进目标股道/站台停稳"（设计 §10.4）的引擎级地基。

## 1. 改动（additive）
- Vehicle：字段 mmtrMotionStopTargetM（-1=自由跑）/mmtrMotionStoppedAtTarget/mmtrMotionStopOpenDoors/
  mmtrControlApplySeq+mmtrMotionArrivalControlSeq（"新控制=续行"判定）。
  - setMmtrMotionStopTarget(cumulativeDistanceM, openDoors)：武装/清除停点（服务端、motion mode 内）。
  - simulateMmtrMotion：到达保持态（速度 0、按请求开门、同令不出发；新令 → 关门清目标续行）；
    制动包络 autoBraking（remaining < v²/2aService 时按恒减速定律制动，尾部 clamp 精确落点）；
    运行中关门；到达/续行日志。
  - mmtrMotionArriveAtStopTarget / mmtrMotionServiceDecelPerMs（SI→内部单位换算 1e-6）。
  - 顺带修复 slice-1 遗留的"无政策回落"速度单位错误（VED 存值是 SI×1e-3；每 tick Δv=值×1e-3×dt），
    原代码 1000 倍偏大——该分支此前无测试覆盖（全测均有 consist 政策），现与 SI 路径一致。
- 单位备忘（引擎内部 speed=m/ms）：SI a(m/s²) → 每 ms² 速率 = a×1e-6；VED 存值=SI×1e-3；
  每 tick(ms) Δv = 速率×dt。恒减速停距 v²/(2a) 与实测制动包络一致。

## 2. 测试（MmtrMotionStopTargetTests，2 例全绿）
1. motionVehicleStopsExactlyAtTargetOpensDoorsAndResumesOnFreshCommand：
   - 车场 seam 出生 → 武装停点（yard+mouth+平台轨中段 18m 处）→ 司机 throttle → 精确停住
     （|Δ|≤0.05m、无过冲、speed=0）、开门、快照 moving=false/doorsOpen、平台轨中段 offset>0；
   - 同令保持 20 tick 不动、门仍开；新令（再 applyMmtrControl）→ 关门、驶过停点续行。
2. secondStopTargetCanBeArmedWhileRunning：第一停点（关门型）精确到达 → 续行中武装第二停点
   （+25m）→ 再次精确停住并开门。

## 3. 回归证据
- 定向：MotionRun/Yard/StopTarget/Legs/E2E dev/LiveRouter/DevWorld* 全绿。
- 全量：slice4-full.log（基线 234/0/2 → 预计 +2）。

## 4. 边界与下一步
- 边界：停点是"距离点"，平台语义（站台 ID/停点位置推算、乘客上下车、SERVE 步进、时刻）未接；
  dwell 计时未做（保持=直到新令，任务侧可自行计时再下发新令）；客户端无镜像。
- 下一步：
  a. 平台停站接线：把平台 rail+停点换算成累计目标（MTR path 里 dwellTime 段的 stop index 对应位置），
     SERVE/任务步进用 setMmtrMotionStopTarget + 到期新令续行（MmtrMission dwell 5s 语义可复用）；
  b. 行经段信号/限速（M2）；c. 任务 MOVE_TO 发车接 live walker；d. T4 收尾评估。

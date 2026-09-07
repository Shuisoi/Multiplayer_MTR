# 14 - L3 Slice-1：Vehicle 本体跑 Motion Core（live motion mode，本会话）

> 承接交接文档（docs/03-交接与实机/MotionCore-Vehicle集成-交接.md）§5 的 L3/T4 前置。
> 目标：让"运行中的真实 Vehicle"不再沿发车时烤死的整条 immutablePath 单调走，而是由 Motion Core
> 逐 tick (segment+offset) 驱动、到节点实时按当前道岔态/任务选段——未设岔停在岔口等（绝不 auto）、
> 中途搬岔同一辆车当场换向。本切片实现"Vehicle 内嵌 walker 的 live 运行模式"并测绿。

## 1. 改动（全部 additive；legacy path 状态机零触碰）

- engine/.../mmtr/segment/MmtrMotionWalker.java：加累计里程追踪（distanceM()/legCount()）。
  advance() 实际消耗多少记多少（段内 partial + 过节点 toNode）；未消耗（岔口等待）不计。
- engine/.../data/Vehicle.java：
  - 新增字段 mmtrMotionWalker / mmtrMotionLegs（增长式影子 legs，累计 PathData）/ mmtrMotionLegCount。
  - engageMmtrMotion(walker)：服务端切入 live motion mode（清 override/protection/门态，进度=walker）。
  - simulate() 分发：motion mode 车辆走新 simulateMmtrMotion()（不走 legacy on-route/stopped/depot 机）。
  - simulateMmtrMotion()：现有座舱控制（ControlState，MmtrDriveControl/applyMmtrControl 同一对象）驱动
    MMTR 物理（ConsistDynamics 子步积分，同 legacy MMTR 分支）；积分距离推进 walker；legs 增长时刷新
    影子路径；walker 无法消费（无权威岔口/线路尽头）→ speed=0 停车等待，下一 tick 自动重问节点 =>
    搬岔后同一辆车继续走，无需重建/重发车。物理模型缺失时线性回落（政策文件是真实路径）。
  - 渲染/占用走 chokepoint：getPositionAndTiltAngle/getBogiePositions 在 motion mode 查 mmtrMotionLegs
    （railProgress = walker 累计距离，与影子 legs 累计一致）；writeMmtrMotionVehiclePositions 写占用
    （同 legacy blocked-bounds 书签，跳过信号预订与客户端推送）；snapshot mmtr-motion 直接由 walker 出。
  - 未做（边界，如实标注）：门/平台停站/信号预订/保护包络（M2 行经段裁决）、客户端镜像（isClientside
    永远 legacy 回放同步 path）、倒车（reverser<0 不给动力）、yard 停场语义（进度 0=未出库）。
- engine/.../mmtr/MmtrMotionSnapshot.java：from() 对 motion vehicle 走 ofWalker 几何 + vehicle 元数据。

## 2. 测试（MmtrVehicleMotionRunTests，5 例全绿）

1. motionVehicleHaltsAtUnsetForksAndFlipsLiveAcrossBoth：同一辆真车，未设岔停在岔口（20m），搬 0 走直，
   过道岔节点（无需权威）继续，第二个未设岔（~60m）再停，搬 1 上分叉轨——一次运行内两次 live 选举。
2. motionVehicleFlipsToDivergeAtFirstFork：搬 1 走分叉轨变体。
3. sidingHostedMotionVehicleDrivenByExistingCommandFlipsLive：siding 托管 + 司机上车 +
   现有操作层 MmtrDriveControl 驱动，live 搬岔换向；mmtr-motion 快照给出 (segment,offset) 直实状态。
4. realDevYardMotionVehicleCrossesMinus96ByLiveFlip：真实 dev 存档 -96 岔口（Assumptions 门控），
   通过 simulator.mmtrPointBranches（= mmtr-point-op 同源权威存储）live 搬 0/搬 1，真车分别跨上
   branch0/branch1 真实轨。== 交接文档 §10.4"翻转 -96 岔口 -> 车实际换走另一轨"引擎级达成。

## 3. 回归证据

- 定向（Motion 相关 50 例）：首轮 2 失败均为测试自身距离常数/检测窗口错（非代码），修正后全绿。
- 全量：见 slice1-full.log（本切片要求零新增失败，基线 227/0/2）。

## 4. 意义与下一步

- 意义：真车（Vehicle 实体）的"运行中实时裁决"第一次成立：位置=walker(segment+offset)；中途搬岔生效；
  到岔口未设=停车等人。渲染几何（railProgress→影子 legs→PathData/RailMath）与占用沿用现有机制。
- 下一步（L3 后续切片）：
  a. yard/出库语义：从 Siding 停场车出发（manifest 车 engage 时 walker 从股道 rail/进路节点起步、车体
     按 defaultPosition 停车位），替代 T3 的 spawnMmtrManualWithLegs 作为调度/任务 MOVE_TO 发车方式；
  b. 停站/门/乘客（SERVE 语义落到 motion mode）；c. 信号/限速按行经段（M2 行经段裁决，替换 path
     stoppingPoint/railBlockedDistance 的 motion 版）；d. 客户端渲染推送（写 mmtr-motion/占用已就绪，
     MC 侧 VehicleUpdate 路径推送需适配增长式 legs）；e. 任务（MmtrJobScheduler MOVE_TO→live walker +
     targetRailHex/task 命中优先，MmtrNodeRouter 已支持 task 覆盖陈旧 operator）。
- 纪律：每片先全量回归记基线（现在 227/0/2 全绿），改 Vehicle 不新增失败。

# 12 - M2-Core slice A 地基：段级运动 + 道岔权威节点决策（本会话第 1 轮）

> 承接 notes/11。HEAD 基线 = 4a50668（docs/notes: M2-Core pivot）。本轮启动 slice A。
> 用户拍板：本轮"开始 slice A（segment+offset 段级运动解耦）"；slice C 翻转验证目标 = 真实开发存档的 -95 岔口。

## 1. 代码事实（recon，读完即记录）
- Vehicle（1292 行）的运动 = 沿 `vehicleExtraData.immutablePath`（PathData 列表，累计 railProgress）
  整条预烘焙路径跑：simulateMoving/simulateStopped/setNextStoppingIndex/dwell/signal/turnback
  全部按"整条 path 的下标/累计距离"写死。`Vehicle.siding` final，Vehicle 永久绑出生股道。
- 道岔权威当前只在**路径生成期**生效：SidingPathFinder.getConnections 里，
  (node, viaRail) 若 operator 在 `simulator.mmtrPointBranches`(BranchStore) 显式设过 0/1，
  就 disallow 另一支（straightest=branch0 / diverging=branch1，按 via→node 与 node→forward 的 cos 角排）。
  => 生成出库腿/交路时就把走向烤死，之后 Vehicle 只沿生成结果跑。这正是要拆的"发车前烤死"。
- Depot 出库腿：Depot.tick 驱动 SidingPathFinder -> siding.generateRoute(platformsInRoute...) ->
  finishGeneratingPath/generatePlatformDirectionsAndWriteDeparturesToSidings -> Vehicle 带 path 发车。
- MmtrMotionSnapshot（mmtr-motion feed）已是"解耦表示层"：segStartX/Z・segEndX/Z・segmentReversed・
  segmentOffsetM・segmentLengthM（当前 PathData 段 + 偏移），服务端仍权威。slice A 让"运动裁决"
  落回该段表示，而不是路径烘焙。

## 2. 本轮新增（additive，零改旧引擎，回归安全）
- org.mtr.core.mmtr.segment.MmtrNodeRouter —— 道岔权威节点决策（纯函数）：
  - elect(Continuation, operatorBranch, taskTargetHex)：
    * 仅 1 个续向（straight，branch1=null）= 不是岔口，直接续行（不是权威问题）；
    * taskTargetHex 命中 branch0/branch1 -> 任务指示优先（与 operator 平级、覆盖陈旧设岔）；
    * operatorBranch 已设 -> 0=straight / 1=diverge；
    * 无 operator 也无 task 的真岔口 -> 返回 null（必须等 operator/task，绝不 auto）。
  - electFromStore(...) 直接吃 BranchStore（.contains 区分"设过0"与"未设"）。
- org.mtr.core.mmtr.segment.MmtrSegmentStep —— (segment+offset) 纯位置：railHex/offsetM/lengthM/reversed，
  advance/remainingM/atEnd/overshootM（越过节点后把剩余 carry 到被选续段）。几何由调用方从真实 Rail 供。
- test MmtrSegmentMotionTests（绿）：
  * fork 无权威无 task -> 拒绝决策；单续向 -> 直行；
  * operator 0 -> straight、1 -> diverge（= "搬A走A、搬B走B" 的决策级翻转证明）；
  * 持久化 BranchStore 0/1 切换被遵守；未设不自动；
  * task 覆盖陈旧 operator；task 不命中任一支则忽略；
  * SegmentStep 越节点 overshoot carry 到被选续段。

## 3. 下一步（真实 slice A/B/C，仍待做 —— 逐轮落地，不臆造）
A. Vehicle 运动解耦：让一辆"自由开"车不再整条 immutablePath 烘焙，而是持 MmtrSegmentStep +
   到节点调 MmtrNodeRouter 选续段。最小非破坏切入：
   - 从真实 Rail 构建 MmtrSegmentStep（需 rail 端点/长度/方向 -> 定 reversed 与续段进入端）。
   - Vehicle 增加一个"decoupled/free"运行模式（用 mmtrManualOverride 或 mission AUTOPILOT 触发），
     旧 defaultPathData 时刻表路径保持原样 = 200 确定性测试零回归。
B. 节点决策接运行期：把 SidingPathFinder 里那套 cos 判 straightest/diverging 的"续向枚举"
   抽成 (viaRailHex@node) -> Continuation 的运行时查询（MmtrNodeRouter.electFromStore 已接 BranchStore），
   移除"生成期 disallow"对自由开车的绑定。
C. -95 翻转验证：先用现网真实存档把 -95 岔口 (node, via)->branch0/1 摸清（DevWorldLoadTests 同款加载，
   run/saves 需从 game/fabric/run 配置到位），把 MmtrNodeRouter 接进一辆 -95 附近可手动/任务车，
   引擎内验"搬0走直、搬1走岔、车实际经过对应轨"。

## 4. 验收口径
- slice A 地基（本轮）：MmtrNodeRouter/MmtrSegmentStep + 确定性翻转测试 = 已绿（BUILD SUCCESSFUL）。
- 完整 slice A：自由开车在真实股道按段推进、经真实岔口由权威换向；现有确定性回归全绿。
- slice C：-95 岔口引擎内 搬A走A / 搬B走B 用实际经过轨断言。

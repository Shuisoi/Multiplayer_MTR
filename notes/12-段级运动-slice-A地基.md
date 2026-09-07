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

## 5. 会话续轮 #2 成果（slice B/C 图层级权威路由证明，已验/已提交）
- 新增 MmtrTurnoutRoutingTests（org.mtr.core.mmtr.point，绿，BUILD SUCCESSFUL）：
  * 合成真实两岔世界：approach(-20,0,0)->node(0,0,0)，straight->(20,0,0)，diverge 45°->(20,0,12)。
  * MmtrPointRegistry.discover 在真实 Rail 上发现该 (node, approach) 道岔：branch0=straight / branch1=diverge。
  * 未设岔 + 无 task -> elect 返回 null（必须等 operator/task，绝不 auto）。
  * operator 0 -> straight（搬A走A）、1 -> diverge（搬B走B）：翻转即换走哪条真实轨。
  * task 指定 diverging 覆盖陈旧 operator 0；持久化 BranchStore 0/1 被 electFromStore 遵守。
  * 仅两轨相接的通过节点 -> 不发现岔、单续向不要求权威（直行）。
- 意义：把 MmtrNodeRouter 接到真实 discover/positionsToRail 上，证明"按道岔选下一段"在引擎图层面
  端到端成立 = slice B/C 的权威路由已被确定性验证；后续 Vehicle 自由开只把该决策放进运行时的节点跨越点。

## 6. 仍未做（诚实边界）
- slice A 本体：把 running Vehicle 从 immutablePath 整段烘焙切到 (segment+offset) + 到节点才决策。
  Vehicle.simulateMoving/simulateStopped/stoppingIndex/dwell/signal/turnback 深度依赖整条累计路径，
  属 L3 级大改；本轮只把"决策模型 + 图层面权威路由"做出来并验绿，未触碰 Vehicle 核心。
- slice C 真实 -95 岔口引擎内"车实际经过对应轨"断言（需把自由开接进一辆真实存档车 + run/saves 配置）。

## 7. 会话续轮 #3 成果（slice B 运行时路由 = 自由开要走的轨序，已验/将提交）
- 新增 org.mtr.core.mmtr.segment.MmtrLiveRouter：<b>运行时逐节点权威路由</b>。
  给定 data/startRail/startAt(+可选 targetRailHex)/BranchStore，沿真实 positionsToRail 前进：
  * 到某节点枚举 approach 续向，用与 discover 相同的 cos 规则分出 straightest(branch0)/diverging(branch1)；
  * 经 MmtrNodeRouter.electFromStore 用 operator/task 选下一轨（task 覆盖陈旧 operator；绝不 auto）；
  * 单续向节点不要求权威直接续；真岔无 operator 无 task -> AWAITING_AUTHORITY（在 haltNode 停下）；
  * 走到目标轨 -> AT_TARGET；死端 -> END_OF_LINE；maxSteps 兜底。
  返回按顺序经过的 railHexOrder + 停止状态。这取代"生成期 disallow + 烘焙"，是自由开要走的轨序。
- MmtrLiveRouterTests（org.mtr.core.mmtr.segment，绿，BUILD SUCCESSFUL）：
  合成场区 approach->node0->{straight->A 支路, 45°diverge->B 支路}，各支再接续：
  * 未设岔 + 无 task -> AWAITING_AUTHORITY，停在 node0，railHexOrder=[approach]；
  * operator 0 -> 轨序经 straight 支（搬A走A）；operator 1 -> 经 diverge 支（搬B走B）；翻转即换真实轨序；
  * 直通节点无 authority 继续（branch0 走到 rBeyondA）；
  * task 指定 B 支覆盖陈旧 operator 0；target=rBeyondA -> AT_TARGET 且轨序到目标止。

## 8. 仍未做（诚实边界，slice A 本体在 Vehicle 层）
- 仍没把 running Vehicle 从 immutablePath 整段烘焙切到 MmtrLiveRouter 输出的逐段轨序 + segment+offset 驱动
  （物理/dwell/signal/turnback 均在 Vehicle.simulate 内依赖整条累计路径，L3 级大改）。
- MmtrLiveRouter 目前给出"自由开车应走的轨序/停在哪等权威"，尚未接入 Vehicle 实际运动与任务执行器。
- slice C 真实 -95 岔口"车实际经过对应轨"引擎内断言，仍需真实存档/运行环境。

## 9. 会话续轮 #3（续）：MmtrLiveRouter.integrate —— 解耦 (segment+offset) 运动状态机（绿/将提交）
- MmtrLiveRouter.integrate(data, startRail, startAt, distanceM, branches, target, maxNodes)：沿真实轨
  按距离推进 (railHex, offsetM)：一段用尽即到节点，经权威（operator/task，绝不 auto）选下一段，
  overshoot 余量 carry 到被选支；返回 MmtrMotionPoint{railHex, offsetM(0..len), status}。
- 新 5 用例全绿：mid-segment 停在请求 offset；未设岔行进 25m -> AWAITING_AUTHORITY 停 approach 远端 offset=20；
  branch0 走 45m -> 落到 rBeyondA offset5；branch1 -> 落到 diverge 支 rBeyondB（翻转即换轨）；task 目标 diverge
  覆盖陈旧 operator0 -> 到岔即 board target AT_TARGET。
- 意义：这是 slice A 的解耦运动模型本体（段的 offset 状态 + 到节点按权威接续），在真实轨上端到端可跑可验，
  不依赖 Vehicle 预烘焙 path。接进 Vehicle.simulate（物理/停站/信号）仍属未做的 Vehicle 层工作。
## 10. 会话续轮 #7：全量回归零新增失败（证据）
- 全量 gradlew test：245 completed / 16 failed / 2 skipped。失败集与基线（216/16/2）完全一致，
  仍是 DevWorld* + MmtrJobSchedulerTests 这 16 个真实场区/job 集成测试（进行中的 job 子系统自身）；
  确定性核心（MiniWorld*/Mmtr*/Siding* 等）与全部 Motion Core 新增测试（MmtrSegmentMotionTests、
  MmtrTurnoutRoutingTests、MmtrLiveRouterTests、DevWorldTurnoutFlipTests）全绿 => 我的改动零回归。
## 11. 会话续轮（用户拍板 A + 清理旧系统）：删除切片 #1
- 用户：A（独立 Motion Core 作为交付）+ 直接清理被 Motion Core 取代的旧系统、无需并行可用。
- 已删：MmtrMotionRouter.MmtrMotionPlan/buildLegPlan + Siding.copy*Legs（死代码/仅测试用），
  并更新 MmtrJobSchedulerTests / DevWorldRouterProbeTests。engine compileJava/compileTestJava 通过。
## 12. 清理切片 #2（跨股道自动移动先下线）
- 决策：本阶段删除旧"重生"搬迁（relocation/跨股道 MOVE_TO/到达合并 make-up），跨股道自动移动先下线。
- 已做：MmtrJobScheduler cross-side MOVE_TO -> fail-fast 离线；删除 DevWorldRelocateTests / DevWorldRouterProbeTests。
  engine compile + Motion Core 确定性测试绿。
## 13. 清理切片 #2 增量 B（删除旧重生搬迁残留代码）
- 删除 MmtrJobScheduler 内 arrivalMergeConsist / relocateParkedConsist / relocatingTo 完成态机与字段及 reset；
- 删除 MmtrMotionRouter（canReachSiding）类 + MmtrJobSchedulerTests.motionRouterReachabilityBasics。
- compileJava/compileTestJava + Motion Core 确定性测试绿。
## 14. 清理后全量回归证据（无新增失败）
- 全量 gradlew test：240 completed / 13 failed / 2 skipped。13 个失败全在既有的 mmtr-job 调度子系统
  （MmtrJobSchedulerTests 12 + DevWorldJobSmokeTests 1），它们在最初基线就已红（原 16 之一），与 Motion Core /
  我的清理无关；已删的 DevWorldRelocate/RouterProbe 属跨股道搬迁专属。确定性核心 + Motion Core（含真实 -96 翻转）
  全绿 => 清理未引入任何新增失败。
- 待办：mmtr-job 子系统自身尚在建设中（红）；引擎 Vehicle 层预烘焙 path 换成 Motion Core（切片4）未做。
## 15. 切片4 起步：MmtrMotionDriver（Motion Core 逐 tick 开车）
- MmtrMotionDriver 在 MmtrMotionWalker 上做巡航纵向驱动：每 tick advance(speed*dt)，到无权威岔口/端点/目标轨制动至停。
- 4 用例绿：branch0 -> 开过岔落到 rBeyondA；branch1 -> rBeyondB；未设岔停在 approach 远端(AWAITING)；目标轨 board -> atTarget 停。
- 这是 Vehicle 后端调用以真正"逐 tick 把车开起来"的确定性核心。
## 16. 切片4：真实 -96 岔口驱动层验证（MmtrMotionDriver 实跑）
- DevWorldMotionWalkTests：在真实 dev 存档沿 -96 via 轨开一列车到 (-96,-60,76)：
  未设岔停在节点(offset=len)；BranchStore 0 -> 实际跨上 branch0 直向真实轨(atTarget)；1 -> 跨上 branch1 分叉真实轨。
  = "车实际沿该轨"在 Motion Core 驱动层成立。
## 17. 切片4 增量回归证据
- 全量 gradlew test：245 completed / 13 failed / 2 skipped（新增 MmtrMotionDriver 4 用例 + DevWorldMotionWalk 1 用例，全绿）；
  13 失败仍全为既有 mmtr-job 子系统（最初基线即红）=> 切片4 增量为纯新增绿、无新增失败。
## 18. 切片4：Motion Core 驱动车可直接以 MmtrMotionSnapshot 表示输出
- MmtrMotionSnapshot.ofWalker(walker)：由 walker 当前轨几何填 segment 端点/offset，输出无 Vehicle、无烘焙 path 的
  (segment,offset) 运动表示（引擎 map/ops 已用同一 DTO）。
- DevWorldMotionWalkTests 新增：真实 -96 岔口把车开到 branch1 后，ofWalker 快照 segStart = -96 节点、offset=0。
  编译 + 测试绿。

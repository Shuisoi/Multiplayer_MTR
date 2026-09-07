# 28 - 道岔 P3a：方向感知排序接入运行时（walker + planner）

日期：与 P1(27) 同日推进 · 前置：694f94f（P1 MmtrPoint 本体）

## 目标（设计文档 道岔系统-方向感知与多级控制 P3 前半）

把 P1 的方向感知岔口排序（MmtrPoint.computeOrderedLegs：
STRAIGHT > LEFT > RIGHT > OTHER，同类内 cos 降序，hex 兜底）
从"发现层 API"变成运行时决策的**唯一**权威：

- 真车逐 tick 经过岔口时 elect 的 0/1（以及多腿索引 ≥2）=
  有序 legs 的索引，与规划器的预设完全同源；
- T 型（竖进横线）不再依赖 map 迭代序选"第二好 cos"（两个
  分支 cos 相等，legacy 结果不稳定），现在确定：leg0=左、leg1=右；
- X 交叉的直向永远是 leg0，垂直轨在完整索引里表达（leg1/leg2），
  legacy top-2 永远无法表达的岔选择现在可操作。

## 改动

### 运行时：MmtrMotionWalker.electAtFork（segment/MmtrMotionWalker.java）
- 删除 legacy cos-top2 + MmtrNodeRouter.electFromStore 路径；
- 现在：computeOrderedLegs(ahead, enteredFrom, rail, neighbors)
  排序 → ① 任务 target 命中任一 leg 优先（保留"任务盖过陈旧
  设岔"语义）② BranchStore operator 索引（0..legs-1，含 ≥2）
  ③ 单续行直通无需权威 ④ 都无 → halt（绝不 auto）。
- 越界 operator（如拓扑变化后 op=3 而只剩 3 腿）→ 拒绝并 halt，
  不回退不猜测。

### 规划器：MmtrRunPlanner.branchOperator
- 由 cos-top2（b0/b1 返回 0/1，-1=不在二选一内）改为
  computeOrderedLegs 的 leg 索引：desired rail 在有序 legs 中的
  位置即 forkOp（可 >1），保证"规划时预设 == 运行时 elect"。

### 存储/持久化：BranchStore.set 去掩码（point/MmtrPointRegistry.java）
- **根因修复**：set() 原为 branch & 1，索引 2 会被写成 0 ——
  P3 全索引语义下多腿岔口根本存不进去。现在存原始索引；
  branch < 0 = 移除设岔（该岔回到"未设、等待"）。
- Simulator.mmtrSetPoint：透传原值、日志打印原值（不再 &1）。

## 测试证据（新增 4 例，全绿）

| 测试 | 证明 |
| --- | --- |
| MmtrLiveRouterTests.walkerTeeJunctionOperator0GoesLeftOperator1GoesRight | 90° T 岔：op0 确定走左腿、op1 走右腿；未设岔在 stem 节点 halt 等待（绝不 auto）；任务 target 盖过陈旧 op0 |
| MmtrLiveRouterTests.walkerXCrossingElectsLegIndexBeyondLegacyBinary | X 交叉：leg0=直向、leg1=左垂、**leg2=右垂（op=2 越过 legacy 0/1）**；越界 op3 → halt |
| MmtrRunPlannerTests.plannerPresetsTeeJunctionLegIndexesFromOrderedLegs | 规划器在 90° T 网把目标腿映射为 forkOp 0/1（同 cos 场景 legacy 无法确定性排序） |
| MmtrRunPlannerTests.plannerDrivesAutoRunThroughTeeOntoPlannedLeg | 真车 E2E：applyForkOps → auto → 停在计划点、实际拐上 +Z 计划腿 |

坑：垂直轨不能复用 0/180 切线的 through() 助手（切线与端点
不共线会塌缩成 railMath 长度 0.0 的退化轨）；必须用 90/270
切线建 z 向轨（测试里 railZ 助手，实机轨向来由绘图产生不涉及）。

## 全量门禁

- 48 suites / 256 tests / 0 fail / 2 skip（252 → 256），cleanTest test。
- 未回归：MmtrSegmentMotionTests / MmtrLiveRouterTests /
  MmtrTurnoutRoutingTests / DevWorldTurnoutFlipTests /
  MmtrRunPlannerTests / motion run + yard E2E 系全绿。
- 兼容说明：legacy MmtrPointRegistry.discover / MmtrSwitch /
  MmtrLiveRouter（route/integrate）仍是 top-2 语义，未改动 ——
  真实车辆运行时已全部走 MmtrMotionWalker（本切片），legacy 仅
  测试与 mmtr-points 列表 API 使用；P4 收口时统一迁移。

## 下一步（P3b）

- MmtrPointAuthority 多级控制状态机（request/grant/release/lock/
  unlock、冲突队列、过期 tick），servlet mmtr-point-req/-rel，
  mission/planner 改经 authority 申请（R2 裁决链），验收矩阵 7-9。

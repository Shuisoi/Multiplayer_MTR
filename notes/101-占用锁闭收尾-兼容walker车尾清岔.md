# 101 - ③ 占用锁闭收尾：兼容 walker 也按车尾清岔（+ 岔区清限）

> 承 notes/99（① 区间入口停车）、notes/100（② 岔区清限）。用户拍板 ①→②→③ 顺序继续。

## 1. 问题

道岔释放有两条路径，之前只有一条是"安全"的：

| walker | 用途 | 释放时机（改前） |
| --- | --- | --- |
| `MmtrConsistWalker` | 编组体主线 | 车尾越节点（rear-clear）→ notes/100 起再加清限余量 ✅ |
| `MmtrMotionWalker`（兼容/单点） | 车场出生、连挂手术回退、测试 | **头部越岔即 `pointAuthority.passed()`** ❌ |

头部越岔即释放意味着：车列（或列车）还没走完，道岔就可以被另一条进路搬走——尾车会被"甩"到另一股道上（模拟里表现为几何/腿表错位，实机上就是挤岔/脱轨）。本片把两条路径统一。

## 2. 交付

| 件 | 内容 |
| --- | --- |
| 尾长接入 | `MmtrMotionWalker.setTailLengthM(double)`；`Vehicle.engageMmtrMotion()` 用 `vehicleExtraData.getTotalVehicleLength()` 注入（兼容路径也拿到车长） |
| 延迟释放 | 越岔时不再立即 `passed()`，而是登记 `PendingRelease{节点, via轨, 阈值 = 越岔距离 + 车长 + (岔节点? 清限 10 m : 0)}`；`releaseClearedPoints()` 在 `advance()` 开头/结尾与提前返回路径上都执行 |
| 清限一致 | 与 ② 相同的判定：节点 ≥3 根轨 → 加 `Vehicle.MMTR_JUNCTION_CLEARANCE_M`，普通接头只按车长 |
| 可观测 | `pendingReleaseCount()`（诊断/测试）；`drainCrossedPointKeys()` 语义不变（越岔即记账，供 owner 停止刷新申请） |

## 3. 期望值变化（逐条）

1. `MmtrPointAuthorityE2ETests.twoTrainsQueueOnTheSameForkAndCrossInOrder`：头车越岔后**仍持有点位**（清限区内），
   再走满 10 m 清限才把排队车 `t2` 提升为持有人。
2. `MmtrPointAuthorityE2ETests.manualOperatorBranchOutranksTheVehiclesOwnGrant`：人工搬岔后车列走另一股，
   其（未用的）授权在越岔时记账，但**持有直到车尾清出清限区**——用例改成循环模拟到释放再断言。
3. `MmtrLiveRouterTests` 新增用例 `theLegacyWalkerHoldsACrossedPointUntilTheTailHasCleared` 钉死新语义
   （车长 6 m + 清限 10 m：越岔后仍持有 → 走满 16 m 释放）。

## 4. 验证

- 引擎全量 **494/0/2**（② 时 493/0/2，+1 例；2 例期望值按 §3 更新，零新增失败）。
- `game`：`:fabric:compileJava` + `:fabric:test` BUILD SUCCESSFUL。
- dev 服务端用新 jar 重启（`logs/2026-09/dev-server-20260910-0320.log`）。
  注：上一次重启（0300 日志）在 Gradle 阶段失败（`fabric/build.gradle:22` "Index 0 out of bounds"，
  与 jar 同步的竞态有关），已重跑成功；同步前先确认端口释放。

## 5. ① ② ③ 收口状态

| # | 内容 | 状态 |
| --- | --- | --- |
| ① | 区间入口停车（岔权未获 → 停在含岔区间的入口/信号前） | ✅ notes/99 |
| ② | 岔区清限 / 侧面防护（车尾出节点 + 10 m 才算法清空；S1 规则 (4)；编组体锁闭延长） | ✅ notes/100 |
| ③ | 占用锁闭收尾（兼容 walker 也按车尾清岔 + 清限） | ✅ 本片 |
| ④ | 显示层打磨：无进路但有道岔/岔区在前时灯的显示、咽喉长时链深 | 待讨论 |

**遗留**：① 与 ② 都存在同一处显示缺口——**自由驾驶（无进路）**时，引擎把车扣在区间入口/岔前，
但那架灯按占用链可能仍是绿的（任务/进路场景没有这个问题：PENDING 时入口区间本来就是红）。
④ 要把"前方有道岔未设/岔区被占 → 灯显危险"接进 `MmtrSignalAspect`，需要把道岔状态纳入显示规则。

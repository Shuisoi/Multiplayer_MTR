# 任务联锁 T 系列 · 验收证据映射

> 对应设计：`docs/01-设计/行车控制-任务联锁与行车许可-设计.md`（方案 A，分片 T1 · T1b · T2–T5）。
> 过程记录：`notes/116–129`（T 系列本体）、`notes/130–134`（现场驱动的几何/权限修复）。
> 用途：**逐条**核对"设计条款 → 实现（类/方法）→ 用例 → 实机证据"。
>
> **状态总览（2026-09-14 收尾）**：**引擎侧全部交付**，引擎全量 **606 通过 / 0 失败 / 4 跳过**。
> 游戏内逐条目视**尚未做** —— 清单见 `实机测试-手动驾驶Motion车-步骤.md` §10。
> 服务端侧（8888 指令栏 + feed）可核对的项在每节「实机证据」里写明跑了什么、看到了什么。

T 系列解决的是**三处断裂**：信号不控车、道岔许可按进向发（两条互斥进路可以同时 SET）、
多车争道岔时逐岔申请不回滚（hold-and-wait 永久死锁）。方案 A 的六条支柱与分片的对应：

| 支柱 | 内容 | 片 |
| --- | --- | --- |
| A-1 | 道岔**物理唯一持有者**（一处道岔一个位置，互斥需求在道岔上排队） | T1（notes/116） |
| A-2 | 进路 SET 判据**纳入物理位置** | T1（notes/116） |
| A-6 | 咽喉**原子获取** + 全自动裁决链 | T1b（notes/117/118） |
| A-3 | `MmtrMovementAuthority` 行车许可对象与出口 | T2（notes/119） |
| A-4 | S1 规则 (5)：按行车许可停车（**信号第一次控车**） | T3（notes/120/126） |
| A-5 | 任务 = 进路意图的显式来源；玩家任务接入联锁；无任务不得操纵 | T4（notes/121/122/124/125） |

---

## 1. T1 · 道岔物理唯一持有者 + SET 判据纳入物理位置（notes/116）

设计条款：**一处道岔只有一个可动件、一个位置**；两条要互斥位置的进路**不可能同时 SET**；
位置由**持有者**决定（不再由 `{stem, far, branch}` 的数组顺序仲裁）。

| 设计条款 | 实现 | 用例 | 实机证据 |
| --- | --- | --- | --- |
| 物理唯一持有者（节点级） | `MmtrPointAuthority.physicalHolders` / `physicalQueued`（互斥需求在**道岔上**排队）；`releaseAll` 一并让位并把位置交给队列头 | `MmtrTurnoutAuthorityTests.thePositionFollowsTheHolderNotTheViaArrayOrder`、`releaseAllGivesUpThePositionAndAdvancesTheQueue` | notes/116 部署后 `point why` 显示单一持有者 |
| 位置跟着持有者，不看数组顺序 | `Simulator.mmtrTurnoutPosition` / `mmtrSyncTurnoutPositionToGrant` 先读物理持有者 | `theBranchHolderWinsEvenThoughStemComesFirstInTheArray`、`compatibleDemandsShareTheSamePosition` | 现场：全盘灯色与 `signal why` 0 处不一致（notes/115 复测） |
| 物理不存在的组合一律拒绝（不排队、不留持有） | `MmtrPointAuthority.Result.REJECTED` + `MmtrTurnout.positionForLeg` | `impossibleCombinationsAreRejectedWithoutTakingThePoint` | `point set` 对"背向穿过尖轨"给出可读拒绝 |
| **SET 判据 = 授权 ∧ 物理位置相符** | `MmtrRouteRegistry.refresh` 第二个条件；不符时 PENDING 且理由点名道岔/持有者/需要的位 | `mutuallyExclusiveRoutesCannotBothBeSet` | notes/130 §6c：日志里"两条进路互斥"的真实等待行 |
| 咽喉双车：一列 SET、一列 PENDING 且停在**自己股道**上 | `MmtrRouteConflictTests`（期望值随 T1 从"开到咽喉口"改为"停在自己股道"） | `conflictingRoutesQueueAtTheThroatAndTheSecondSetsAfterTheFirstClears` | notes/84 采样（T1 前）；T1 后的现场待 §10 第 7 条 |
| **自持有死锁**（现场缺陷）：持有者就是自己时谁也扳不动 | `mmtrThrowTurnoutForIntent(..., requesterOwner)` 对**持有者本人豁免**并改它自己的需求（`repointPhysicalHold`）；重新规划时 `mmtrReleaseStalePhysicalHolds` 放掉旧计划不要的位 | `onlyTheHolderItselfMayRepointATurnoutItPins`、`aReplanGivesUpThePositionTheOldPlanPinnedButKeepsTheOneItStillWants`、`aRouteBlockedOnlyByTheVehiclesOwnStaleHoldBecomesSetOnceItIsDropped` | notes/136 §3 的现场（车停在出发信号前）；本轮修完待现场复验 |

## 2. T1b · 咽喉原子获取 + 裁决链（notes/117/118）

设计条款：咽喉内的道岔**一次原子申请**（要么全拿到、要么一个不拿，失败**同 tick 全部退回**）；
裁决次序 **计划时刻 > 到达序 > 防饿死**。

| 设计条款 | 实现 | 用例 | 实机证据 |
| --- | --- | --- | --- |
| 原子获取（消灭循环等待） | `MmtrPointAuthority.requestAtomically`（只读预演 + 提交；失败 `releaseSet` 全退） | `atomicAcquisitionLeavesNoPartialHoldingSoACircularWaitCannotForm`、`aFollowingMovementOnTheSameLegIsNotAHostileRoute` | — |
| 失败时连"上一 tick 就持有的"也一起让出（不许半个持有） | `releaseSet` 含本 owner 早先的持有 | `atomicFailureGivesUpAMemberThisOwnerAlreadyHeld`、`anAtomicWaiterNeverKeepsAHalfSetAcrossItsOwnRetry` | — |
| 裁决链第一档：计划时刻 | `requestAtomically(..., priorityMillis)`；`Vehicle.mmtrPlannedMillis()` | `anEarlierPlannedTrainIsServedFirstEvenThoughItAskedLater` | — |
| 无计划 = 纯 FIFO（老语义逐位不变） | `Long.MAX_VALUE` 表示"没有计划" | `withoutAPlanTheQueueStaysPureFifo` | — |
| 防饿死：等太久提到上一档 | `MMTR_STARVATION_MILLIS` + `enqueuedAtMillis`（重复申请**不**刷新） | `aStarvedWaiterOvertakesANewerHigherPriorityTrain`、`reRequestsRefreshTheWindowInsteadOfDuplicating` | — |
| 到达即让位、失效不卡网 | `passed` / `expiredHolderCannotWedgeTheNetwork` / `expiredQueuedEntriesAreSkippedOnPromotion` | 同左 | — |

## 3. T2 · 行车许可对象 `MmtrMovementAuthority`（notes/119）

设计条款：**先把许可算出来、先不停车**（"只算不停"是这一片的验收本身：零期望值改动）。

| 设计条款 | 实现 | 用例 | 实机证据 |
| --- | --- | --- | --- |
| 四显示 → 许可（红=停车义务+距离，黄=注意，绿=无） | `MmtrMovementAuthority`（`aspectFrom` 方向敏感） | `MmtrMovementAuthorityTests`（6 例：红/绿/黄/贴脸红/与灯一致/无位置无许可） | feed 每车 `authority{}` 与 `signals[]` 一致 |
| **不改变任何停车行为** | 本片不接 S1 | 全量**零期望值改动**（notes/119） | — |

## 4. T3 · 信号第一次控车（S1 规则 (5)，notes/120/126）

设计条款：车**停在行车许可要求的地方**（红灯前、进路 PENDING 的入口信号前），红→绿自动续行。

| 设计条款 | 实现 | 用例 | 实机证据 |
| --- | --- | --- | --- |
| 红灯前精确停住、不冒进 | `Vehicle` 许可停车点（S1 规则 (5)） | `MmtrRedLampStopTests.aRedLampStopsTheTrainBeforeIt` | 待 §10 第 1 条 |
| 进路未设 ⇒ 停在**出发信号**前（不是开到岔前） | `MmtrSignalAuthorityStopTests` | `aTrainWhoseRouteIsNotSetIsHeldAtItsDepartureSignal` | notes/130 §6c 的现场等待行（服务端侧已见） |
| 红→绿自动续行 | 同上 | `settingTheRouteLetsTheHeldTrainGo` | 待 §10 第 3 条 |
| 黄灯不构成停车义务（未确认仍走既有 AWS SPAD） | `aYellowLampDoesNotStopTheTrain` | 同左 | notes/103（AWS 一侧已实机通过） |
| 停车理由可读（HUD / 诊断） | 许可里带距离与理由；`interlock` 可读 | `theAuthorityReportsExactlyWhatTheLampsShow` | notes/89 `interlock <id>` |
| **对照实验**：真正证明规则的是②③，①④只是端到端守卫 | — | notes/126 §2 | — |

## 5. T4 · 任务接入联锁 / 无任务不得操纵（notes/121/122/124/125）

| 设计条款 | 实现 | 用例 | 实机证据 |
| --- | --- | --- | --- |
| **玩家任务同样发布进路、申请道岔、参与 SET**（只不接管油门） | `Vehicle.mmtrMotionSelfArmMission` 的 PLAYER 分支：发布进路 + 申请道岔 + `mmtrPlayerRoutePublished` 闩 | `MmtrPlayerMissionInterlockTests.aPlayerMissionStillGetsItsRouteAndTurnoutsButNotTheThrottle` | 待 §10 第 9 条 |
| **准入闸门：无任务不得操纵**（策略开关，默认关） | `MmtrDriveAccess.canControl` + `Simulator.mmtrRequireTaskToDrive` | `drivingWithoutATaskIsRefusedOnlyWhenThePolicyIsOn`、`theGateIsOffByDefaultSoExistingBehaviourIsUntouched` | 真服务器打开（与 `mmtrDefaultPointsZero` 同一个策略模式） |
| 进路**类型**由任务类型决定（不由调车授权反推） | `Vehicle.mmtrRouteKindOf` | `theRouteKindComesFromTheTaskTypeNotFromTransientAuthority` | — |
| 作业单驱动整趟：MOVE_TO → SERVE → COUPLE 每步都有进路与许可 | `MmtrJobScheduler` + 逐步进路 | `MmtrTaskDrivenCouplingTests`（2 例）、notes/125 | notes/76（作业单自己开过去挂上 3 节） |
| SHUNT（调车）进路同样受物理道岔预约约束 | `MmtrRoute` 的 SHUNT kind 走同一 `requestAtomically` | `aShuntRouteCannotBypassTheTurnoutReservation` | notes/83/84 |
| 运营台可见"计划 vs 实际" | feed `route.plannedMillis` + `interlock` 报告 | `MmtrEnemyRoutesTests.plannedTimeDecidesConflictsNotArrivalOrder` | 网页面板（T5 剩余项，见 §6） |

## 6. T5 · 敌对进路表 + 计划时刻（notes/123/127/128/129）

| 设计条款 | 实现 | 用例 | 实机证据 |
| --- | --- | --- | --- |
| 离线**敌对进路表**（同岔反向位 / 共用轨 / 侧面防护区） | `MmtrEnemyRoutes`（签名缓存） | `MmtrEnemyRoutesTests.twoRoutesWantingDifferentTurnoutPositionsAreReported`、`twoRoutesWantingTheSameTurnoutPositionAreNotEnemies`、`opposingMovementsOverTheSameRailAreReported`、`followingMovementsOverTheSameRailAreNotEnemies`、`anIdleNetworkHasNoConflicts` | feed 顶层 `conflicts[]`（空闲世界为空） |
| **敌对进路不能同时 SET**（纯排名比较，与刷新顺序无关） | `MmtrRouteRegistry.enemyBlockReason` | `opposingRoutesCannotBothBeSet`、`arrivalOrderDecidesNotVehicleId` | — |
| 计划时刻优先于到达序 | 同左 + `MmtrRoute.setPlannedMillis` | `plannedTimeDecidesConflictsNotArrivalOrder` | notes/128 |
| **漂移退化**：计划时刻已过 ⇒ 退回到达序（防"僵尸优先"） | `hasLivePlan`（不需要时钟：与申请时刻比） | `aStalePlanFallsBackToArrivalOrder` | notes/128 |
| 重发布同一条 movement 只刷新计划时刻、不 churn 进路身份 | `MmtrRouteRegistry.request` | `replanningTheTimeKeepsTheRouteObjectButUpdatesItsPlannedTime` | — |
| 计划/实际进 `interlock` 报告 | `MmtrInterlockReport` | `theReportDescribesRouteTurnoutsAspectsAndMirror` | notes/129 |

**T5 未做的一项**：网页的"时刻表预排进路"**面板**（引擎侧数据已齐；面板属 P 系列的 WEB 分页，
见 `docs/01-设计/任务系统-线路派生与车底交路-设计.md` §9）。

## 7. 本轮同时收掉的现场遗留（notes/130–134 + 137）

| 项 | 状态 |
| --- | --- |
| 浅岔口/对称人字岔认不出来（判据改成岔尖测试）；两个度 4 共用节点是**地图画法**遗留 | 交付（notes/130；度 4 节点要改图，见注 1） |
| 走不到的腿不许把整段判断路 / 不许当出口灯 | 交付（notes/131/132） |
| 占用范围收敛到"走得到的 span" | 交付（notes/132 §5 + notes/137） |
| 网页"自动刷新"开关（灯跟不上车走） | 交付（notes/133） |
| **解锁必须落盘**（"解了又回来"）；`point locks` / `point unlock --all` | 交付（notes/134，已装机） |
| **自动扳岔（每 tick 同步 + 授权申请）补净空闸** | 交付（notes/137；与人工/意图两条路同一判定） |
| 存档 `switches`/`locks` 的 hex 键**载入归一化** | 交付（notes/137） |
| `vehicle remove --depot` 只清一条股道 | 交付（notes/137：遍历该车辆段全部股道并说清扫了几条） |
| **自持有死锁** | 交付（notes/137，见 §1 末行） |
| 两个度 4 共用节点（`-219,-60,67`、`1,-60,38`）**拆开** | ⬜ 需**改图**（世界编辑，不是代码）：把共用节点拆成两个节点，每处道岔各自落一个 |

> 注 1：度 4 节点的处理口径是用户 2026-09-14 的裁定 —— "一个节点只能有一个道岔，那里是历史遗留问题"。
> 引擎侧保持"一个节点 = 一个可动件"、不建模这两个节点（`point why` 会说明"四条线交汇"），
> 要统一就得改图。改图步骤见 notes/137 的附录。

## 8. 怎么自己复现（服务端侧命令）

```
# 进路 / 道岔持有 / 每条轨显示 / 客户端收窄 —— 一列车一条
interlock <车辆id>
interlock all

# 道岔现场（判定过程 + 当前状态 + 闭塞归属）
point why <x> <y> <z>
point list
point locks                       # 引擎手里的人工锁（含界面上表达不出来的进向）
point unlock --all

# 闭塞区间与灯色
blocks all
signal why <x> <y> <z>
lamps-v2

# 车与任务
vehicle list --depot=<名>
```

网页侧：`http://127.0.0.1:8888/` 的运营台（每车 `route` 徽章 + 目标/等待理由 + `routeMirror`）、
水闸区间图层与区间图（`mmtr-schematic`）。**改前端后要重新构建前端 + 刷新页面**；
「自动刷新」开关默认关（notes/133）。

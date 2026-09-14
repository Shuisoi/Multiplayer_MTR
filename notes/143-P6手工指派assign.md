# 143 · P6（二）：手工指派 assign（引擎侧）

> 承接 notes/142（替补顶替）。本轮交付 **P6 ④ 的引擎侧：手工指派**。
> **引擎全量 661 通过 / 0 失败 / 4 跳过**（659 + 新 2 例）。

---

## 1. 手工覆盖自动（设计 §3 的第三条 + §9 的 `assign`）

```java
MmtrPlanAdjustments.assignManually(diagram, fromConsistId, fromTripId, toConsistId, notes)
```

- **优先关系**：手工覆盖自动排班 —— 与联锁那套"人工锁 > 计划时刻 > 到达序"同一种思路；
- **看得见**：搬过去的每一条都带 `note = "人工指派 → <车>"`（`Entry.note`，交路视图与日志都读它）；
- **时刻不动**：只是换了执行车，趟次时刻原样（§8.1 的同一条口径：换车不改表）；
- **起点之前留在原车**：那是它已经/正在跑的（在途不打断）；
- **失败要说话**：指派给不存在的编组 → 计划不动 + 一句"人工指派失败：没有编组 X 的交路可接手"，不静默吞掉。

## 2. 测试（2 例）

| 断言 | 用例 |
| --- | --- |
| 起点之后搬走、时刻不变、来源车的回库条目留下、**说明里带"人工指派"** | `aManualAssignmentWinsOverTheAutomaticRotaAndIsVisible` |
| 指派给不存在的编组 → 计划**同一个对象**（没被动过）+ 有失败说明 | `anAssignmentToAnUnknownConsistIsReportedNotSilentlyIgnored` |

## 3. 未做（下一轮）

1. **`assign` 的接口**：`POST mmtr-plan-assign {fromConsistId, fromTripId, toConsistId}` → 进派发器输入签名后重算
   （形状与 `mmtr-plan-event-upsert` 一致）。**本轮只做了引擎侧**：没有接口 = 没有现场可验证的入口，
   所以这一片**没有现场读数**，如实记在这里。
2. **③ 接管协议**（§8.2）：AI→玩家 / 玩家→AI 两个方向 + 不变量（**交路与任务不变**、续行从那一点）；
   底座已有（`MmtrMission.Executor` + `MmtrDriveAccess` + `MmtrJobScheduler.humanTakeover/releaseToAutopilot`），
   缺的是"交路层"的协议：玩家开的车派发器不许派、释放后从那一步续行。
3. **WEB 六页**（线路/密度/车底/事件/交路/指派）。
4. 尾巴：`mmtr-plan-diagrams` 补 `skippedSteps`/`awaitingTaskId`；删 `MmtrPeriodicTaskSource`；
   复核 notes/141 §5 那条"事件改计划的说明没进日志"。

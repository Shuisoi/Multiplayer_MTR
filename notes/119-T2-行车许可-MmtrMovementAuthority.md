# 119 - T2：行车许可 `MmtrMovementAuthority`

> 对应设计：`docs/01-设计/行车控制-任务联锁与行车许可-设计.md` §A-3 / §6（T2）。
> 前置：notes/116（T1 物理唯一持有者）、notes/117（T1b 咽喉原子）、notes/118（T1b 裁决链）。
> 触发：用户 2026-09-14「一路把T做完吧」。

## 1. 这一片补的是**断裂一：信号不控车**

修前灯色在全仓库只有一个消费者是**影响行车**的 —— AWS 告警（`Vehicle.java:3033-3037`）；
停车一律由 `computeMmtrBlockStopM` 的四条规则决定，**没有一条读灯色**。红灯是一块显示屏。

本片建立"四显示 → {目标位置, 目标距离, 目标速度}"这个对象。**只算不停** —— 停车规则是 T3。

## 2. 交付

| 件 | 内容 |
| --- | --- |
| `MmtrMovementAuthority` | 纯派生、不持有状态（与 `MmtrRoute` 同纪律）：`aspect` / `targetRailHex` / `targetDistanceM` / `targetSpeedKmh` / `cautionOnly` / `reason`；`hasTarget()` / `mustStop()` |
| `of(aspect, targetRailHex, toSignalM)` | **映射本身**（纯函数，不碰世界）：测试与诊断直接用它 |
| `forApproach(simulator, railHex, entryNode, vehicleId, toSignalM)` | 世界那一半：读"下一架管我的信号" |
| `forVehicle(simulator, walker, vehicleId)` | 车上的**适配器**：从走行位置取那三个量 |
| feed 出口 | `SystemMapServlet` 每列车多一个 `authority{aspect, cautionOnly, mustStop, targetRail*, reason}` |

### 四显示 → 许可

| 显示 | 许可 |
| --- | --- |
| `GREEN` | 无目标（轨限速与闭塞说了算） |
| `RED` | **目标速度 0**，停在这架信号前 —— **唯一的停车义务来源** |
| `SINGLE_YELLOW` | `cautionOnly`：只有注意义务（AWS 确认已在做），**不给停车目标** |
| `DOUBLE_YELLOW` | 同上（预告危险在再下一架） |

**黄灯为什么不给停车目标**（本片唯一的取舍，写清楚免得日后被当成漏做）：
四显示是一条**链** —— 单黄的意思正是"下一架是红"。列车往前开一段，读到的**下一个信号就是红**，
停车义务在那一刻自然产生。所以黄灯不需要"停在下一架信号前"这个**跨区间**的目标距离：
那个距离要按行进方向算方向性区间的长度（`sectionEndAheadM` 按方向匹配 span，按方向取弧号是这一段
最容易写错的东西），而**它是多余的**。黄灯真正承担的是预告（notes/103 已实机验收）。

> 我一度按设计文档的字面写了"单黄 → 目标 = 下一架信号"，然后在实现时发现要为此猜弧号 ——
> **猜错会产生"看起来对但位置偏一点"的行为**，比没有更坏。宁可把取舍写清楚。

## 3. 与灯显同源（不另写第二套选腿）

`forApproach` 里读信号**只有一行**：`mmtrSignalAspectView().aspectFrom(railHex, entryNode, excludeVehicleId)` ——
就是灯显自己用的那个结论（v2 选腿规则、`hasSection` 分支、链深）。**"下一架管我的信号"因此不引入
第二套算法**；notes/114 那类"三层各持一份视图"正是这么来的。用例
`theAuthorityReportsExactlyWhatTheLampsShow` 是这条的结构性守卫。

到信号的距离用的是 `MmtrRunPlanner.remainingToAheadNodeM(walker)` —— 与 AWS 触发**同一个量**，
两处不会各说各话。

## 4. 验收

- 新增 6 例（`MmtrMovementAuthorityTests`）：
  - `redIsTheOnlyStopObligationAndItCarriesTheDistance`（① 红灯：唯一的停车义务 + 带距离）
  - `greenImposesNothing`（① 绿灯无约束）
  - `yellowAspectsCarryCautionButNoStopObligation`（① 两种黄灯都只注意、不给停车目标）
  - `aRedRightOnTopOfTheTrainStillYieldsZeroDistance`（距离夹到 0，不出现负目标）
  - `theAuthorityReportsExactlyWhatTheLampsShow`（接线守卫：许可的显示 == 灯显的显示）
  - `noPositionMeansNoAuthority`（没有走行位置就不产生许可，也不谎报红灯）
- **④ 最强的验收：全量零期望值改动** —— 套件从 553/0/4 到 **559/0/4**（只多了本片 6 例，
  没有一个既有用例的期望值被改动）。这正是"只算不停"该有的样子。

## 5. 测试边界（写清楚在哪测什么）

"下一架管我的信号是哪一架"这条规则**不在本片测** —— 它是 `MmtrSignalAspect.aspectFrom` 的结论，
由 `MmtrDirectionalBlockServiceTests` 那一整套（v2 选腿 / 轨中段的灯 / 岔口多腿 / 折返双候选 / 键序反向）
负责。本片只测**映射**（纯函数）与**接线**（一行委托）。这样两边都不会重复劳动，
也不会出现"两套算法各测各的、互相对不上"。

## 6. 未结

1. **没有任何停车规则读它** —— 那是 T3（S1 新增规则 (5)）。届时"红灯 → 停在信号前"才真正成立。
2. **黄灯的跨区间目标距离**没做（理由见 §2）。若 LZB 那条连续曲线要用黄灯提前减速，
   那时再按 `本段剩余 + 下一段长度` 的**加法**接上 `sectionEndAheadM`。
3. **`signal why` 还没有"许可"这一节** —— feed 出口已通，指令侧的诊断留到 T3 一起做更省事
   （那时它是验证停车理由的第一手工具）。
4. 客户端镜像（游戏内 HUD 显示许可）未做；本片只出引擎 feed。

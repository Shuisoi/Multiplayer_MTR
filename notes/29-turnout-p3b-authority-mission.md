# 29 - 道岔 P3b：MmtrPointAuthority 多级控制状态机 + mission 经 authority 申请

前置：952cf1f（P3a ordered-legs 运行时）· 设计：docs/01-设计/道岔系统-方向感知与多级控制-设计.md（R2/R3，验收 7-9）

## 目标（P3 后半）

把"自动逻辑怎么过岔"从【planner 一次性把沿途岔口预置写进 operator store
（applyForkOps）】升级为设计文档的权威模型：

1. 岔口 = 受控资源：(node, via) 同一时刻只有一个 owner 持有 grant；
2. 任务/MOVE_TO 在**到达前**逐岔申请（接近锁定 approach locking）；
3. 过岔即释放（walker 在真正跨过节点后自动放），排队 FIFO，grant 带窗口自动过期；
4. 人工 operator 仍是最高优先级（lock 停自动申请；未锁但人工设了岔 → 车走人工轨）；
5. mission 不再写 operator store —— 手动搬岔语义与自动申请彻底分家。

## 改动

### 新增 MmtrPointAuthority（mmtr/point/MmtrPointAuthority.java）
- 状态：(node,via) -> lock 标志 + holder(owner, leg, until) + FIFO 队列；
- request(): 锁定中→QUEUED（去重：同 owner 重申请刷新窗口）；被他人持有→QUEUED；
  空闲→GRANTED；自己持有→刷新窗口 GRANTED；持有者过期→自动顶替；
- passed(owner)/release(owner)：只放自己的 hold；队列头提升（过期队首跳过）；
- lock()/unlock()：人工停车自动申请，unlock 把最早等待者提升为 holder；
- releaseAll(owner)：终端 mission 清仓（holder + 队列）；
- 时钟注入（LongSupplier），引擎挂 sim::getCurrentMillis；全部操作先过期检查。
- 语义红线：authority 本身绝不动车 —— 车动不动/走哪条轨仍由 walker elect 决定。

### MmtrMotionWalker：elect 裁决链改为 人工 operator > 自身 grant > legacy target > 单续行 > halt
- setPointAuthority(authority, owner) 接线；grant 按 ordered legs 索引取轨；
- 跨过 >=2 续向岔口时自动 authority.passed(owner)（过岔即释放）+ 记录 crossed
  point key（drainCrossedPointKeys 供 owner 停止刷新已过岔口）；
- 语义变更（对齐设计 R2/R3）：**人工 operator 现在压过 legacy task target**
  （旧 walker 是 target 优先于陈旧设岔）——task 升级为显式申请后该捷径降级为
  "无人工、无 grant 时的老 ops 转向提示"；受影响测试按新语义改写。

### 规划器/任务接线
- MmtrRunPlanner.requestForkOps(plan|ops, authority, owner, until)：逐岔申请，
  全 GRANTED 才算可发车（空 plan 直接 true）；
- Vehicle：armMmtrPointRun（self-arm 与 dispatch 共用）+ mmtrPendingPointOps
  只刷新**未过岔**的申请（过岔即从 pending 移除，防释放后再被重申请抢回）；
  终端 mission → releaseAll + 解除 walker 接线；missionTick 每 tick 刷新窗口；
- MmtrMissionControl.dispatch：arm 前先申请；被锁/被占 → 保持 ASSIGNED 不挂
  auto，missionTick 自武装每 tick 重试直到全 grant（绝不 auto 绕行）。
- mmtrSetPoint(branch<0) = 取消人工设岔（移除 operator 设置）。

### 接口
- servlet：mmtr-point-op 增可选 lock/unlock 旗标；新增 mmtr-point-req
  {x,y,z,via,owner,leg,untilMillis}、mmtr-point-rel {x,y,z,via,owner}；
  Simulator 包装 mmtrPointRequest/Release/Lock/Unlock/ReleaseAll。
- BranchStore.set 负值=移除（P3a 已支持）。

## 测试证据（验收 7-9 + 回归）

MmtrPointAuthorityTests（5，纯状态机 + 可拨时钟）：
- lockParksPointForManualUseAndUnlockGrantsFifoHead：锁定后排、unlock 提升最早者；
- reRequestsRefreshTheWindowInsteadOfDuplicating：排队去重+刷新窗口；
- expiredHolderCannotWedgeTheNetwork：过期持有者被新申请顶替；
- expiredQueuedEntriesAreSkippedOnPromotion：过期队首跳过；
- passedReleasesOnlyTheOwnerAndReleaseAllDropsEverything。

MmtrPointAuthorityE2ETests（4，真车 seam）：
- missionGrantsEveryForkCrossesAndAutoReleasesAfterwards（验收 8）：任务申请→
  两岔全 grant（接近锁定）→auto 发车→逐岔过车→自动释放→下一车立即可申请；
- operatorLockedForkKeepsMissionUnarmedUntilUnlock（验收 7）：锁定→车不发车不
  绕行、锁内零 grant；unlock→自动接管并完成任务；
- manualOperatorBranchOutranksTheVehiclesOwnGrant（R3 回归语义）：人工搬 1 →
  车走人工轨（无视自己 grant），过岔仍释放自己的 hold；
- twoTrainsQueueOnTheSameForkAndCrossInOrder（验收 9）：t1 持有 t2 排队；
  t2 先到岔口 halt 等待（approach lock），t1 过岔释放→t2 提升→按序通过。

既有回归改写（语义变更点）：
- MmtrMotionTaskTargetTests：liveRetarget 不再盖过人工设岔（新语义），operator
  在车前清除分支后 task 照常接管；taskTargetIsTheAuthorityOnAnUnsetFork 不变。
- MmtrLiveRouterTests walkerTee：手动设岔压过冲突 target 断言翻转 + 未设时
  target 仍生效。
- MmtrMotionMissionTests（PASSENGER E2E 平台停靠）不改一行跑绿 —— 证明
  dispatch→request→grant→过岔 全链路与旧 store 预置结果一致。

## 全量门禁

- cleanTest test：50 suites / 266 tests / 0 fail / 2 skip（256→266，零新增失败）。

## 坑记录

1. @Nullable 标注 MmtrPointAuthority/MmtrRunPlanner.Plan 型字段报 type-use 作用域
   错误（同轮编译的类型）——去掉标注即可，不碍事；
2. 每 tick 的窗口刷新会把**已过岔**的 fork 再申请回来（释放完又被自己抢回，
   岔口被本车永久占住）——必须 drain crossed keys、只刷 pending；
3. 测试 save 目录会加载持久化的 mmtr-points.json：同目录多场景会互相泄漏人工
   设岔 → 每个 E2E 场景独立 savePath；
4. HashMap 迭代中 promote() 再 put 同 map → 释放丢失，先收集 key 再处理。

## 下一步

- P2：Web 地图(8888) 点选搬岔 UI（mmtr-points 展示 ordered legs/state/queue +
  0/1/leg/lock/unlock 操作）—— 需要 website assets build；
- P4：信号联锁 route-lock（一组岔口锁定+信号联动）、mmtr-points.json meta 迁移；
- 实机验证：engine jar→game/libs（sync-engine.ps1）+ 服务器重启后，任务车过 -96
  改为经 authority（肉眼验证锁/排队日志）。

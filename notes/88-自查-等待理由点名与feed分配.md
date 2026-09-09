# 88 - 自查：PENDING 理由不再点名已越过的道岔 + feed 路径去掉每轨分配

> 本片是 R1–R7 交付后的**自查**结果（没有新功能），两处都属"信息正确性/开销"层面的收尾。

## 1. PENDING 理由可能点名**已经越过的**道岔

`MmtrRouteRegistry.refresh` 在 R5 引入分段释放后，判定只要求"尚未越过的道岔被持有"，
但**生成理由**时仍把**全部**道岔交给 `MmtrRunPlanner.describeForkWait`。于是列车越过的那个道岔
（持有已在越岔时释放）会成为它报告的"第一个受阻道岔"：

```
point 0,0,0 via=… wantLeg=0 lock=false holder=-      ← 列车早就过了这个岔
```

操作员据此去查一个车已经离开的道岔，白跑一趟。修复：只把**尚未越过的**道岔交给描述函数。

用例 `MmtrRouteRegistryTests.thePendingReasonNamesAnOutstandingTurnoutNotACrossedOne`：
越岔 + 释放后人工锁定另一个岔 → 理由必须含 `60,0,0`、**不得**含 `point 0,0,0`（修复前必失败）。

## 2. feed 路径每轨一次列表分配

`MmtrSignalAspect.aspectsForAllRails()` 原来对每条轨调用 `aspectOf(hex)`，而 `aspectOf` 内部又要
`routes.snapshot()`（分配 + 排序一个列表）来判断 PENDING 入口轨 —— 134 条轨 = 134 次分配/请求。
现在一次性取 `pendingEntryRails()`（集合）传下去，行为不变、每请求只算一次。

## 3. 验证

- 全量引擎套件 **471/0/2**（上一轮 470/0/2，+1 例，零新增失败）。
- 无行为变化的两处改动由既有用例（`MmtrSignalAspectTests` 6 例、`SystemMapRouteMirrorTests` 2 例）覆盖。

## 4. 目标剩余

仍是游戏内目视（分岔按进路放行 / PENDING 压红 / AWS 黄灯响与绿灯复位 / 未确认 SPAD）。

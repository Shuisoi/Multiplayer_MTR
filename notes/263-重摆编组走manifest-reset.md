# 263 — 重摆编组：走 `mmtr-manifest-reset`，**不要**走 `manifest replay`

用户口径（2026-09-23）：

> 服务端两个车重新刷一下

## 1. 正确两步（不重启服务端）

```
POST /mtr/api/map/mmtr-command   {"command":"vehicle remove --depot=1899053368752863308"}
POST /mtr/api/map/mmtr-manifest-reset        # → {"placedSidings":2}
```

- `vehicle remove --depot=<id>` 会遍历该段**全部股道**（notes/136 §2 修过的那条），两条股道一次清干净；
- `mmtr-manifest-reset` = `Simulator.mmtrResetAndApplyRollingStock()`：清空股道模板 → 按
  `world/mtr/minecraft/overworld/mmtr-rolling-stock.json` 重新铺车 ⇒ **每节的 `powered` 按文件来**
  （br101 动力 / p1 无动力）。

实测（19:10）：

```
车=-4579789776693813777  股道=1  编组=br101:动力 + p1:无动力 + p1:无动力
车= 8835957155136557996  股道=2  编组=br101:动力 + p1:无动力 + p1:无动力
客户端 19:10:27  [MMTR-CL] mirror created id=-457978977581… cars=3 / id=8835… cars=3
服务端 19:10:27  [MMTR-MFST] rolling-stock reset applied: 2 siding(s) staged for spawn
```

## 2. 陷阱：`manifest replay` 会把**每节车都标成动力**

`manifest replay` 把列车表当指令再跑一遍，表里的行是：

```
vehicle spawn br101 p1 p1 --depot=1899053368752863308 --siding=-6585178446048051701
```

而 `MmtrVehicleCommands.spawn` 只认**一个**全列开关：

```java
final boolean powered = !options.containsKey("unpowered");   // ← 全列一个值
for (final String vehicleId : positional) cars.add(carOf(vehicleId, carLength, powered));
```

⇒ 重放之后 API 里两支车列都是 `p1:动力`（实测 19:09），与 `mmtr-rolling-stock.json` 里
`p1.powered=false` 不一致。

**为什么这次力没跟着错**：p1 有自己的车底（`carTypeIds.p1 → p1_trailer`，`maxTractiveEffortN/maxPowerW = 0`），
所以 `toConsistType` 的牵引合计仍是 300 kN（notes/247 那次的 3× 机车是因为当时**没有映射**、借了前车的车底）。
但 `powered` 不是纯装饰：`Vehicle.mmtrSeedAirStateAfterCoupling` 按它决定"挂上来的无动力车要不要把气路清零"
—— 标错就是错数据，迟早以别的形式冒出来。

## 3. 遗留（下一阶段）

给 `vehicle spawn` 加**每节车的动力位**（`p1~unpowered` 这类写法，或 `--unpowered=<index,…>`），
否则"列车表重放"这条最方便的路永远会踩上面这个坑；`manifest replay` 也就永远不能替代 `manifest reset`。

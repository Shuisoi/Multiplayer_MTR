# 271 — 挂车模型 片 1：世界文件能声明挂车 + `powered` 三态 + 载重通路

日期：2026-09-26 · 设计：`docs/01-设计/非动力挂车-车辆模型与编组对象-设计.md`（片 1）

用户口径（6 条，同一轮拍板，已进设计文档 §7）：

1. 停放制动**简化为"不动就行了"**（停放 = 钉住，不建闸、不接坡道力）；
2. **分客车/货车两族**，WSP 与撒砂**简化**，但**载重要有一个百分比**并用于算质量；
3. 管容积当量**用车长**；
4. 逐车参数**用逗号列表**；
5. `p1_trailer` **给**自己的 bar 键；
6. 坡道力**不接**；多人游戏里"不被连上就钉死在地里"。

本篇是**片 1**的落地记录。

---

## 1. 片 1 要修的洞（开工前就在现场）

| 洞 | 证据 |
| --- | --- |
| 清单能存、**翻译成指令时丢光** | `MmtrRollingStockManifest.asCommandLines()` 只拼车型 + depot/siding；`putSiding()` 写死 `spec.powered = true`；`MmtrCommandDispatcher.carOf()` 的 `consistTypeId` 传 `""`。于是世界文件里写 `powered:false` **到不了车** —— notes/247 §2.4 那两节 `p1` 是**手改世界 json** 兜过去的 |
| "没声明"与"显式无动力"不可区分 | `VehicleCar.mmtrPowered` 是 `boolean`、`MmtrCarSpec.powered` 缺省 `true`；`VehicleCarSchema` 的 `unpackBoolean("mmtrPowered", …)` 读不出"这个键在不在" |
| 载重没有通路 | `ConsistType.massKg` 只有整备质量一个数，车卡上没有载重比例 |

## 2. 改了什么

| 文件 | 改动 |
| --- | --- |
| `buildSrc/…/schema/data/vehicleCar.json` | 新增 `mmtrLoadRatio`（number，0..1，缺省 0）—— 生成源由它重建（`generated/` 是 gitignore 的，真源在这份 schema） |
| `data/VehicleCar.java` | 新增 `mmtrPoweredDeclared`（**不落盘**：只记"读进来时那个键在不在"，用 `readerBase.unpackBoolean` 捕获）+ `isMmtrPoweredDeclared()/setMmtrPoweredDeclared()` + `getMmtrLoadRatio()/setMmtrLoadRatio()`（夹到 0..1）；覆写 `updateData` |
| `job/MmtrCarSpec.java` | 新增 `poweredDeclared`（`Boolean`，null = 没写）与 `loadRatio`（读/写 `loadRatio`）；`toVehicleCar()` 把三态与载重带到运行时车卡；`fromVehicleCar()` 回填 |
| `command/MmtrCommandDispatcher.java` | `carOf(...)` 增车底/载重/三态三个参数（老签名保留）；新增逗号列表助手 `commaList` / `commaListBoolean` / `commaListDouble`（**空项 = 这一节不说**；只给一项则对全列生效）；usage 补逐车参数说明 |
| `command/MmtrVehicleCommands.java` | 新增 `carsFor(...)`：`--powered=` / `--consist-type=` / `--load=` / `--coupler-after=` / `--manual-coupler=` 五个逗号列表；`--unpowered` 仍是全列简写但**算显式声明**。`--rail` 与股道两条 spawn 路径共用它 |
| `manifest/MmtrRollingStockManifest.java` | `asCommandLines()` 追加 `perCarOptions(...)`（缺省值**不写**，老清单逐字不变）；`putSiding` 增"逐车车卡原样写入"的重载，老签名改为组装 `MmtrCarSpec` 后委托（**不再写死 `powered = true`**） |

## 3. 判据（`MmtrManifestTrailerDeclarationTests`，4 条，全过）

走的是**执行器自己的那条路**：清单 JSON → `asCommandLines()` → `MmtrCommandDispatcher.tokenize` → `MmtrVehicleCommands.carsFor` → 逐车字段。

```
挂车列（两节 p1，powered:false / p1_trailer / load 0.8 / 第一节有车钩）
  ⇒ 指令含 --powered=false,false --consist-type=p1_trailer,p1_trailer --load=0.8,0.8 --coupler-after=true,
  ⇒ 车卡：powered=false、declared=true、consistTypeId=p1_trailer、loadRatio=0.8、车钩逐节对
三态：没写 powered ⇒ 指令里没有 --powered，车卡 powered=true 但 declared=false（老兜底照旧）
老清单（只有 vehicleId/length）⇒ "vehicle spawn saf101 --depot=7 --siding=9" 逐字不变
三态与载重到运行时车卡：toVehicleCar 后 declared=true、loadRatio=0.75；写盘再读回来 declared=true
```

**全量：849 条、失败 3 条、跳过 6 条** —— 三条失败仍是预存在的世界存档漂移
（`MmtrConsistMultiCarPlacementTests` ×2、`MmtrTrainCarsJsonTests` ×1），本轮新增 4 条全过。

## 4. 行为变化（写在明处）

* **落盘后一律视为"已声明"**：生成的 `serializeData` 一直是无条件写 `mmtrPowered`，世界存一次盘，
  所有车都带上这个键 ⇒ 重载后 `declared = true`。这是**有意的**（文件里写了 `false` 就该算声明），
  但对"作者没表态、只是被存过一次盘"的老车列，片 2 的兜底判据会从"借牵引"变成"不借" ——
  片 2 落地时若不希望它变，判据要按"车底是不是借来的"来判，而不是只看 `declared`。
* `vehicle spawn … --unpowered` 现在**算显式无动力**：老行为会给第一节借牵引（一列同型车仍然能开），
  片 2 起将**一份牵引都不给**（这正是用户要的"挂车不能开"）。这条改动会在片 2 的用例里钉住。

## 5. 下一步

片 2：**无动力判据** —— `MmtrComposition.toConsistType` 的 `anyPowered` 兜底改成逐车判据
（只有"没声明 + 车底是借来的"那一节才可能借牵引，且整列最多一节）、整列无 `canPull()` 车底 ⇒
`MmtrDriveAccess` 拒绝司机掌权、`MmtrCarTypeResolver` 不把它选作"说话的车"。

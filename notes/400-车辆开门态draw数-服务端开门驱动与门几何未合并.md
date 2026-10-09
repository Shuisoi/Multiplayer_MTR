# 400 车辆 draw 数为什么会在 40 与 320 之间跳 —— 是服务端开门，不是合并失效

> **后续**：§3 那次"就地合并"经实测**零收益、已回退**（原因见 §3.1）；真正的修法在 **§6 A 路线**
> （跨部件组按动画类合并），已实现在 `resource/MmtrDoorBatch.java`，开关 `-Dmmtr.doorbatch`（默认关）。

承接 notes/399（车辆按材质合并）。本篇回答一个**我上一轮判断错的问题**，并修掉它。

## 1 现象：同一进程内 draw 数在 40 与 284–320 之间来回跳

同一个客户端（PID 47940，17:32:08 启动，合并已默认开启），同一列车：

| 状态 | `saf420car/saf420.png` | `…_glass.png` | 合计非钢轨 |
|---|---|---|---|
| A | 16.0（2.00 批） | 8.0（1.00 批） | **40** |
| B | 112.8（2.00 批） | 104.4（1.00 批） | **283–284** |
| B×2 | 224.0 | 208.0 | **560** |

**批次数不变（都是 2.00 批）**，只有每个材质的 draw 数变了 ⇒ 是"换了一套几何"，不是"车多车少"。

而 `[MMTR-VEHMERGE]` 只在 **17:34:17/18/23** 出现过 10 行（模型构建，全部成功），此后一条都没有，
也没有"停用合并"那条 warn。模型是 `CachedResource` 缓存的，**没有重建就不该变** —— 这是当时的矛盾。

> 当时我给出的三个候选：① 合并被静默回退；② 存在未合并的第二条路径；③ 匹配到的
> `PartCondition` 变多了。**正确答案是 ②**，而且它一直摆在 `ModelPropertiesPart.java:214`。

## 2 判定：把服务端事件叠到客户端的 draws 上

用户提示"车辆的绘制与服务器和客户端通信也相关" —— 这条提示是解开的钥匙。
专用 dev 服务器（PID 41732）在跑引擎作业，日志里有它的 `[MMTR-SUB]` 子任务流：

```text
[17:35:02] [MMTR-SUB] ▶ 基础操作开始：OPEN_DOORS（车 -5612134069892294620，✔停在 上水村站2台 ▶开门 ·等待上下客 20s ·关门）
[17:35:00] [MMTR-SUB] ✔ 子任务完成：CLOSE_DOORS —— 门已关（车 …，4/4，✔停在 莫氏岛站2台 …）
```

把 `OPEN_DOORS` / `CLOSE_DOORS` 与客户端的 `非钢轨 draws` 按时间对齐：

| 客户端 draws | 服务端事件 |
|---|---|
| 17:36:03–13 → 29.1 / 39.4 / **40.0** | 门关 |
| 17:36:18–33 → 212.5 / 283.2 / 314.4 / **320.0** | 17:36:08、17:36:14、17:36:15 `OPEN_DOORS` |
| 17:37:08–23 → **40.0** / 35.9 / 29.0 / 28.3 | 17:36:28、17:36:34、17:36:35 `CLOSE_DOORS` |
| 17:38:18–33 → 243.0 → **320.0** | 17:38:14、17:38:16 `OPEN_DOORS` |
| 17:39:08 → **40.0** | 17:39:11 `CLOSE_DOORS` |

**开门 → 320，关门 → 40，四次全中。** 站停 20 s 的停留时间与 40/320 各自的平台期长度也对得上。

⇒ 状态 B 不是"合并失效"，是**开门态**。链路是：
服务端 `OPEN_DOORS` 子任务 → `persistentVehicleData` 的门值/可开判定 →
`RenderVehicles` 收 `openDoorways` → `noOpenDoorways = openDoorways.isEmpty()` →
① 走哪个 bundle（`optimizedModelsDoorsClosed` / `optimizedModels`）
② `matchesCondition(DOORS_CLOSED/DOORS_OPENED)` ③ 门几何由 `optimizedModelDoor` 逐位置画。

### 2.1 那 112 / 104 为什么和 notes/399 §5.2 的"改前"逐位相同

因为**就是同一批门部件**：

* 关门时门躺在 `objModelsForPartConditionAndRenderStageDoorsClosed`（`ModelPropertiesPart.java:225`）——
  我合并过这个桶，但**只合并了车体那一路**；
* 开门时同一批门走 `optimizedModelDoor`（`:214`）—— **从来没合并过**。

`fromObjModels(objModels)` 对每个部件 upload 一个 `VertexArray`，而 `BatchManager.queue` 给每个
`VertexArray` 建一条 `RenderCall` ⇒ **一个部件一次 draw**。所以两边数出来都是 112/104 并不奇怪。

> **对 notes/399 §5.2 的更正**：那次"284 → 40（−86%）"的对照**在门这一路上是不完整的**。
> 40 是**关门态**（门在已合并的 doorsClosed 桶里），而开门态当时没测到（§5.4 自己记了
> "93 个窗口钉在 40.0 ⇒ 门那一路一次都没被跑到"）。**结论"284→40"对关门态成立；
> 开门态的 draw 数一直到本篇才动。**

## 3 第一次修复及其**回退**：在 `optimizedModelDoor` 里按材质合并 —— 零收益

当时的想法是：门这一路既然没合并过，那就地合并。

```java
// 改前 —— 逐部件 upload，且这一行每个位置调用一次
optimizedModelDoor = () -> isDoor() || mechanism ? OptimizedModelWrapper.fromObjModels(objModels) : null;

// 当时改成 —— 同一个"部件组"内部按材质合并
optimizedModelDoor = () -> isDoor() || mechanism ? MmtrVehicleMeshMerger.mergeOrFallback(objModels) : null;
```

### 3.1 为什么零收益（§5.1 的实测把它钉死了）

`fromObjModels` 本身就**已经按材质分桶**（用 `javap` 核实：`lambda$fromObjModels$2` = 逐 ObjModel
`generateNormals(); distinct();` 然后 `list.addAll(rawModel.upload(DEFAULT_MAPPING))`，而
`upload` 每个材质返回一个 `VertexArray`）。所以这里再合并一遍，输入是同一个 `objModels`、
输出还是那一个材质、还是 1 次 draw。

**真正的粒度问题不在"材质"，在"这一份模型是谁的"**：`optimizedModelDoor` 是**一个部件组**的模型，
而开门时 `renderNormal` 是**逐部件组**排队的 ⇒ 一辆车 24 组门就是 24 次 draw，材质再合并也降不下来。

⇒ 这一段改动**已回退**（`ModelPropertiesPart.writeCache` 现在就是上面的"改前"那一行，只补了注释），
但它换来一个判定性结论，见 §5。

### 3.2 保留下来的两处（都是"输出有界 / 不再静默"）

| 位置 | 改动 | 现在还在吗 |
|---|---|---|
| `MmtrVehicleMeshMerger.merge` | `parts.isEmpty()` 这条**唯一的静默回退**现在会打 warn（含部件数）—— 否则"没有合并日志却换了几何"永远查不出来 | 在 |
| `MmtrVehicleMeshMerger` | 合并日志上限 400 条：门/雨刷那条路会让它每车每类各走一次，不限量会把日志刷爆 | 在 |

## 4 判定性埋点（`[MMTR-VDRAW]`）

新增 `render/MmtrVehicleDrawProbe.java` + `OptimizedModelWrapper.partCount()`（反射
`OptimizedModel.uploadedParts`，已用 `javap` 核对：`final List<VertexArray> uploadedParts;` 包私有）。
在 `VehicleResource.queue(...)` 里按「门开/关 + 编组节数 + 匹配到的条件集合」分桶，
每 5 秒一行，报**每次排队 draw 数**（= 各 `partCount()` 之和）与条件匹配数：

```text
[MMTR-VDRAW] 5.0s 窗口：车·帧=1240（门关=1200 门开=40）｜ 每次排队 draw 数 avg=3.4 max=18
  ｜ 条件匹配数 avg=3.00 max=3 ｜ 签名：门关 车=2 [AT_DEPOT+DOORS_CLOSED+MMTR_LAMP] n=1200 draw avg=3.4 max=18 ｜ …
```

**读法**：两个桶签名相同而 draw 数不同 ⇒ 几何被换了（回退）；签名不同 ⇒ 状态驱动的正常差异。
本篇的结论正是靠"签名不同 + 与服务端事件对齐"得到的。`-Dmmtr.vdrawprobe=false` 可关。

### 4.1 第一次的盲区（已补）

第一版只挂在 `VehicleResource.queue`，于是开门态和关门态的 `draw avg` 都是 3.2 —— 因为它量的是
**车体那一路**，门那一路根本不经过它。补了 `MmtrVehicleDrawProbe.onDoorQueued(parts)`，挂在
`ModelPropertiesPart.renderNormal` 的两处 `OPTIMIZED_RENDERER_WRAPPER.queue` 上（门 + 雨刷）。

### 4.2 `CHRISTMAS_LIGHT_*` 会污染签名（已修）

`matchesCondition` 的 default 分支读的是 `System.currentTimeMillis()/500` 的圣诞灯相位，
**每 500 ms 变一次** ⇒ 签名里会出现一堆只差一位的桶，把真正有信息的那几个桶挤出
`MAX_SIGNATURES`（= 6）。

修法：在 `VehicleResource` 里算掩码时把 `CHRISTMAS_LIGHT_*` **只计数、不进掩码**
（`partCondition.name().startsWith("CHRISTMAS_LIGHT")` 就跳过 `conditionMask |= …`）。
这几档在本 mod 的资源包里没有几何。代价：真用圣诞灯的资源包在这条埋点里看不出来。
**副作用**：桶键/签名与 2026-10-08 之前的日志不再逐位可比。

## 5 实测：门这一路的 draw 到底长什么样（决定 A 怎么写的三个数）

### 5.1 门的 draw 就是"一次排队一次 draw"，且每次恰好 1

```text
[MMTR-VDRAW] 18:00:05 → 门关=12239 门开=6119 ｜ 门/雨刷逐位置排队=151776 次 draw avg=1.0 max=1
[MMTR-VDRAW] 18:00:10 → 144584
[MMTR-VDRAW] 18:00:15（门已关）→ 10104
```

开门态 ≈ **172 次排队/帧**，关门态 ≈ 11 次/帧。每次 `draw avg=1.0 max=1` ⇒ 要降 draw 数，
**只能减少排队次数**，合并材质没用（印证 §3.1）。

### 5.2 一个门部件组只有**一个**位置（`[MMTR-DOORSHAPE]`）

临时埋点在 `writeCache` 末尾（**已删**）：

```text
[MMTR-DOORSHAPE] 部件=[door_r_1] 门=true 雨刷=false 几何部件数=1 位置数=1
[MMTR-DOORSHAPE] 部件=[door_r_1_glass] … 位置数=1
[MMTR-DOORSHAPE] 部件=[wiper_1] 门=false 雨刷=true 几何部件数=1 位置数=1
```

一辆车：`door_r_1..6`、`door_l_1..6` 各带一个 `_glass` = **24 组门**，外加 4 组雨刷部件。

**这一个数决定了 A 的写法**：

* 位置数 > 1 ⇒ `renderNormal` 按 `partDetailsList` 逐条排队，几何还 O(k²) 冗余 ⇒ 局部修即可；
* 位置数 = 1 ⇒ **43 次/节来自"43 个部件组"**，A 必须**跨部件组**按动画类合并。

实测是 **1**，所以走后者。

### 5.3 draw 时那次 `translate` 只是**动画位移**（`-Dmmtr.doortranslate=false` 实机实验）

`renderNormal` 里门是用 `translate(x/16, y/16, z/16)` 画出去的，而 `x = partDetails.x + 动画量`。
当时读代码定不下来"几何到底烘没烘进位置"（烘焙 `addObjModelPosition` 用的是同一个 `x`，
如果 `x ≠ 0` 就会画成 2x）。判定实验：把这次 translate 摘掉重启，站在正在开门的车旁看 ——

> 用户结论：**"门位置照旧、只是不再滑动"**。

⇒ 几何**已经**站在自己的位置上（烘进去了），draw 的 translate **纯粹是动画位移**。
于是"同一个动画类的门共用一个位移"这句话成立 ⇒ 可以合成一份几何、排一次队。

### 5.4 门开 / 门关两态的 draw 数（现状，改动前的基线）

| 状态 | 钢轨 | 非钢轨 | 每节车 |
|---|---|---|---|
| 关门（4 节在视野内） | 6.0（1.00 批） | **32–40** | 20 |
| 开门（4 节） | 6.0 | **491.2** | — |

240/320/560 那些数（§1 的表 B）就是这么来的：**门这一路是开门态唯一的 draw 大头**。

## 6 A 路线：同一辆车**同一个动画类**的门合成一次 draw（门几何按位置烘好）

### 6.1 做什么

新增 `resource/MmtrDoorBatch.java`：

1. **建（每辆车一次）**：`ModelPropertiesPart` 在 `writeCache`（OBJ 路）里把自己那组门的
   `objModels` 登记下来（只在"是门、不是雨刷、类型 NORMAL、位置数 = 1"时）。第一次渲染这辆车时
   按**动画类**分组，把同一类里所有组的几何用 `MmtrVehicleMeshMerger` 合并成一份（一个材质一个
   `VertexArray`）；
2. **画（每帧每类一次）**：`DynamicVehicleModel.render` 开头调 `MmtrDoorBatch.renderBatched(...)`，
   对每个类**试算**类里每一组门的位移（用的是从 `renderNormal` 里原样抽出来的
   `doorDrawTranslation`，**只有这一份**）；全部相同就排一次队，并把这个类里的部件交给逐部件渲染时跳过。

`DynamicVehicleModel.render` 里那次批处理必须在逐部件渲染**之前**：两者都在同一个 pass、
同一层、同一个 `new Identifier("")` 键下入队，所以顺序就是那里决定的。

### 6.2 动画类的键（`ModelPropertiesPart.doorBatchKey`）

`doorXMultiplier | doorZMultiplier | DoorAnimationType.name() | flipped | PartCondition.name() | 侧别`

一个都不能漏：multiplier 与动画曲线决定位移；`flipped` 决定 draw 时那一次 180° Y 旋转；
`PartCondition` 决定 `render()` 里的条件判定粒度（不同条件本来是分别判定的）。

**"侧别"是实机逼出来的一项**（第一版没有它，于是**一次都没合上**）。翻 `SAF420_v42.zip` 里的
`properties_saf420car.json` 就明白了：

```json
{"names":["door_r_1"],"positionDefinitions":["p0"],"renderStage":"EXTERIOR","doorXMultiplier":0,"doorZMultiplier":-14,...}
{"names":["door_l_1"],"positionDefinitions":["p0"],"renderStage":"EXTERIOR","doorXMultiplier":0,"doorZMultiplier":-14,...}
```

**左右门的 `doorZMultiplier` 完全相同**（左右门是同一套动画），所以只按 multiplier 分组会把
左右门塞进同一个类；而 `canOpen` 是**逐 doorway** 判的（`RenderVehicleHelper.canOpenDoors`
看那一扇门口有没有站台），一站台只在一侧时左右就不一致 ⇒ 这个类永远合不上。

同时这个文件也把另外两件事钉死了：`definition_saf420car.json` 里 `p0` 是
`{"positions":[{}],"positionsFlipped":[]}` ⇒ **所有位置都是 (0,0,0)、`flipped` 恒为 false**
（§5.3 那个"几何已经烘好"的结论落到实处），所以类里剩下的唯一自由变量真的就只有 `canOpen`。

侧别取"这一组门映射到的那个 doorway 的中线 x 符号"，取不到就用部件自己的盒子 ——
它正是 `canOpen` 的判据来源。**粒度只能到"侧"**：再细到 doorway 就变成"一组门一个类"，
而一组本来就只有 1 次 draw，合并只会更贵（24 组 → 24 个类）。

### 6.3 位移的一致判据：`z` 要容差，`x`/`y` 不要

第二版加了侧别之后**还是**合不上，判定日志给出的是：

```text
[MMTR-DOORBATCH] 类 0.0|-14.0|STANDARD|false|NORMAL|3（6 组门）这一帧不合并：
  [door_r_1] 与 [door_r_1_glass] 的位移不同：[0.0000, 0.0000, -12.3480] vs [0.0000, 0.0000, -12.3760]
```

同一扇门的门板与玻璃，位移差了 **0.028**。原因是位移的 z 来自
`PersistentVehicleData.getInterpolatedDoorValue`，而 `Interpolation.getValue()` 是拿
`System.currentTimeMillis()` 插值的 ⇒ **同一帧里先算的门和后算的门落在不同的毫秒上**，
位移本来就有毫秒级差异 —— 门一动，逐位相等就不可能成立。

所以：`x` 与 `y` 逐位相等（`x` 走 `getDoorAnimationX`，只看门值、与时间无关；
`y` 是闪光哨兵，要么相等要么天壤之别），`z` 允许 `0.05`（1/16 格，≈3 mm）的差。
这条判据真正要挡的是"状态不同"（一扇能开一扇不能、有玩家堵门），那种差是 `O(1)`–`O(20)`
（`doorZMultiplier = 14`），阈值取 0.05 两边都躲得开。

### 6.4 安全阀：类里只要有一组不一致，整类退回逐组

合并的前提是"这一帧这个类里每一组门都用同一个位移画"，而这一条**只在运行时**成立：
`canOpen` 取决于这组门自己那个 doorway 有没有对着站台、`shouldRender` 取决于它自己的
renderFrom/Until 时间窗、`doorOverrideValue` 取决于有没有玩家堵门。

还有一条容易漏的：`ModelPropertiesPart.render()` 是**先过 `VehicleResource.matchesCondition`** 才排队的，
条件不成立的部件这一帧根本不画；而合并模型里带着它的几何 ⇒ 不判条件就会**多画**
（例如 `AT_DEPOT` 的门在途时被合并模型画出来）。所以 `doorBatchTranslation` 里先判一次条件 ——
类的键里含 `condition`，同一个类要么全中、要么全不中。

所以每帧都试算一遍：

| 试算结果 | 行为 |
|---|---|
| 类里每一组都该画、且位移一致（`z` 见 §6.3） | 用合并模型画**一次**，并让 `renderNormal` 对这几组**跳过**门那次 draw（否则是重影） |
| 只要有一组位移不同，或有一组这一帧不该画 | **整类都不合**，一次都不画，`renderNormal` 照旧逐组画 |

"合并没有成立"就等于改动前 ⇒ 不可能出现"半个类被合并、位置对不上"的中间态。
（`!shouldRender` 时 `renderNormal` 原本是把 `x/z` 设成 `Integer.MAX_VALUE` 把它挪到天边 ——
所以那种组**不能**进合并模型，它会被真的画出来。）

**"为什么这个类没合并"要有话说**：合并不成立时的现场表现是"什么都没变"，而那正好也是
"开关没打开""这个模型没门""类里只有一组"的表现 —— 光看数字分不出来。所以
`MmtrDoorBatch.noteNotBatched` 会为每个类打**一条**说明（指认是哪两组、位移各是多少），
总数封顶 8 条。上面 §6.2 / §6.3 那两个坑就是靠它一次抓出来的。

### 6.5 开关与回退

* **默认开启**（2026-10-08 起）。先以默认关闭上线、经实机与**用户肉眼**确认画面（门 / 门玻璃 /
  面板 / 车灯 / 雨刷逐位不变）之后才翻默认 —— 与 `mmtr.vehiclemerge` 同样的纪律；
* 回退：`-Dmmtr.doorbatch=false`（便利脚本 `-I sandbox/dev-run/doorbatch-off.gradle`）；
* 一个类里只有 1 组门时不合并（本来就只要 1 次 draw，合并只多占显存）；
* 合并拿不到结果（映射库变了 / GL 出问题）⇒ 那个类退回逐组，绝不留半成品；
* 雨刷不碰（它是旋转运动学，逐部件必须各画各的）；
* 立方体（Blockbench）那条路不参与（它的几何是 `MaterialGroup` 的 cube，不是 `ObjModel`），
  画法与改动前完全一致。

### 6.6 实测（2026-10-08，SAF420 十节编组）

| | 改动前 | 改动后 |
|---|---|---|
| `[MMTR-DOORBATCH]` | （无这一行） | `4 个动画类、24 组门 → 8 次 draw/pass` |
| 门的动画类 | — | 一节车 **4 类**（两个 `doorZMultiplier` × 左右）× 2 个材质 = **8 次 draw** |
| 门路径 draw / 每节开门车 | **24.8**（`门/雨刷逐位置排队 125736 ÷ 门开车帧 5073`） | **9.0**（`(门·类合并 19484 × 2 + 逐位置 4864) ÷ 4871`）→ **−64%** |
| 门路径**排队次数** / 每节 | 24.8 | 5.0 → **−80%**（CPU 侧） |
| 开门态 `非钢轨 draws` | **491.2** | **83.4**（`saf420.png=37.8(2 批)`） |
| `[MMTR-FRAME]` 可比窗口 | 227 帧 / 45.3 FPS / 8.46 ms | 268 帧 / 53.6 FPS / 7.51 ms |
| 关门态 `非钢轨 draws` | 40 | **40（不变）** —— 关门走 bundle，这条改动碰不到 |

判定日志（证明"4 个类全部合上、没有一类退回"）：

```text
[MMTR-DOORBATCH] 门合并：4 个动画类、24 组门 → 8 次 draw/pass（-Dmmtr.doorbatch=false 可回退）
[MMTR-VDRAW] …（门开=4871）｜ 门/雨刷逐位置排队=4864 次 draw avg=1.0 max=1 ｜ 门·类合并=19484 次 draw avg=2.0
```

`门·类合并 ÷ 门开车帧 = 19484 ÷ 4871 = 4.0` ⇒ **每节开门车正好 4 个类各画一次**，与建的类数逐位对上；
`门/雨刷逐位置排队 ÷ 门开车帧 = 1.0` ⇒ 门那一路只剩雨刷还在逐组画。

**画面**已由用户肉眼确认：**门 / 门玻璃 / 面板 / 车灯 / 雨刷逐位不变**（含开门中与正在开关的过程）
⇒ 默认开关**已翻成开启**；`-Dmmtr.doorbatch=false` 是逐位回退。

翻默认之后又跑了一遍（**不带任何 `-I` 注入**，验证默认值本身生效）：
`[MMTR-DOORBATCH] 门合并：4 个动画类、24 组门 → 8 次 draw/pass`、
`门·类合并 25560 ÷ 门开车帧 6390 = 4.0`、全会话 `不合并` 说明 **0 条**。

## 7 模型重建那 300 ms 到底花在哪：读 / 解析 / 建 VBO，以及把解析搬走

§8.10 那条"修好着色器后一次重建仍有 284–455 ms，大头是解析 4.5 MB 的 OBJ"以前只是**推断**。
本节把它量出来，并按量出来的切分点把最大的一段搬离渲染线程。

### 7.1 离线量：`sandbox/railbake-verify/ObjParseBench`（可复跑，不依赖游戏）

驱动的是**真实的**映射库解析器，与 `ModelResourceLoader.loadModel` +
`ModelPropertiesPart.writeCache` + `MmtrVehicleMeshMerger` 逐形同构，只跳过必须留在渲染线程的
`RawModel.upload()`（建 GL buffer）。

```powershell
$env:JAVA_HOME = "$env:USERPROFILE\.gradle\jdks\eclipse_adoptium-21-amd64-windows.2"
cd sandbox\railbake-verify
# OBJ/MTL 从资源包 zip 里解出来（assets/mtr/saf420car/saf420car.obj）
& "$env:JAVA_HOME\bin\javac.exe" -proc:none -nowarn -cp (Get-Content classpath.txt -Raw).Trim() -d . ObjParseBench.java
& "$env:JAVA_HOME\bin\java.exe" "-Dfile.encoding=UTF-8" -cp "$((Get-Content classpath.txt -Raw).Trim());." ObjParseBench "obj\saf420car.obj" "obj\saf420car.mtl" 5
```

`saf420car.obj`（4 343 592 字节 = 4.3 MB 文本；40 组、源顶点 3.55 万、源面 2.68 万）：

| 分段 | 耗时 |
|---|---|
| ① 读 + UTF-8 解码 | **3 ms** |
| ② 解析 `ObjModel.loadModel`（OBJ+MTL 文本 → RawMesh） | **首轮 1115 ms（JIT 冷）**，其后 5 轮 **平均 120 ms / 最快 85 / 最慢 155** |
| ③ 逐组 `addTransformation`（深拷贝 mesh + 平移 + append） | 3–7 ms |
| ④ `generateNormals` + `distinct` | ≈ 20 ms |
| ⑤ 合并 append 进一个 RawModel（`mmtr.vehiclemerge` 那条路） | ≈ 40 ms |
| ⑥ `upload()` 建 VBO | **离线量不到**（要 GL 上下文），实机总量减去上面就是它 |

两条关键读数：

* **解析是绝对大头**（③④⑤ 全加起来才 65 ms，还不到解析的一半）；
* **JIT 冷的第一轮贵一个数量级**（1115 ms vs 120 ms）—— 而一个会话里每种模型只解析一两次，
  也就是说**实机上一次模型重建的解析成本永远落在"没热起来"那一段**（实机量到 336 ms，见 §7.2）。

### 7.2 实机分段：`[MMTR-MODEL]`（`VehicleModel.createModel`）

三段都是**直接量的**（不靠相减反推），只在**这一轮重建 ≥ 50 ms** 时打一条，总数封顶 60 条：

```text
[MMTR-MODEL] mtr:saf420cab_a/saf420cab.obj 这一轮重建 362 ms ｜ 读 - ｜ 解析 336 ms（本线程） ｜ 建 VBO 26 ms
[MMTR-MODEL] mtr:saf420car/saf420car.obj   这一轮重建 146 ms ｜ 读 - ｜ 解析 135 ms（本线程） ｜ 建 VBO 11 ms
[MMTR-MODEL] mtr:saf420cab_b/saf420cab.obj 这一轮重建 149 ms ｜ 读 - ｜ 解析 136 ms（本线程） ｜ 建 VBO 13 ms
```

⇒ **解析占一次重建的 87–93%**，建 VBO 只占 7–9%。`-Dmmtr.modelparseasync=false` 量的就是这一组。

### 7.3 建模重建**不是"每个会话一次"**，而是每 ~100 s 一波

以前手册里写"每模型每会话一次"，因为那次会话只观察到一个波次。本轮三份日志里都出现了**多波**：
19:20:50–56（cab_a / car / cab_b）与 19:22:32–37（cab_b / car / cab_a），相隔 **102 s**。

成因在代码里：`VehicleModel.MODEL_LIFESPAN = 60000`，而 `CachedResource.getData` 只在**被调用**时
才把 `expiry` 往后推 —— 车**离开视野**超过 60 s 后 `CachedResource.tick()` 就把 `data` 清掉，
车再进视野时整辆车**从头重建**。十节编组在世界里跑来跑去，于是重建是**周期性**的，不是一次性的。
⇒ 这不是"偶尔卡一下"，而是**稳态里每 100 秒一波、每波 2–3 个模型**的渲染线程停顿。

### 7.4 修法：读留在渲染线程，解析搬到后台（`-Dmmtr.modelparseasync`，默认开）

```text
[MMTR-ModelParse] [MMTR-MODELASYNC] 后台解析 mtr:saf420cab_a/saf420cab.obj: 519 ms（线程 MMTR-ModelParse）
[Render thread]   [MMTR-MODEL] mtr:saf420cab_a/saf420cab.obj 这一轮重建 166 ms ｜ 读 31 ms ｜ 解析 已在后台完成 ｜ 建 VBO 104 ms
```

| | 同步（改前） | 后台（改后） |
|---|---|---|
| cab_a（这一波里 JIT 最冷的一个） | 362 ms（解析 336 + VBO 26） | **166 ms**（读 31 + VBO 104）→ **−54%** |
| cab_b | 149 ms（解析 136 + VBO 13） | **83 ms**（读 10 + VBO 83）→ **−44%** |
| car | 146 ms（解析 135 + VBO 11） | **< 50 ms**（没到打印阈值）→ **≥ −66%** |
| 解析在哪 | 渲染线程，全程阻塞 | `MMTR-ModelParse` 线程，`读` 与 `建 VBO` 不受它阻塞 |
| 失败/兜底次数 | — | **0**（`[MMTR-MODELASYNC] … 失败` 与"同步兜底"一条都没有） |

**别把"建 VBO"那一段读成"变大了"**：改前 26/11/13 ms、改后 104/83 ms —— 那是**同一段代码**在
两种条件下的读数。改后它和后台解析**同时在跑**，两者都是吃内存带宽的重活（4.5 MB 文本解析 +
约 1 MB 顶点拷贝），还要一起触发 GC，所以各自都比单独跑慢一截（后台解析也出现过 519/553 ms）。

⇒ 诚实的结论是：**渲染线程每次重建的代价从 146–362 ms 降到 50–166 ms（约 −45% ~ −66%）**，
不是"降到 0"。那 50–166 ms 里还分两段：`建 VBO`（`writeCache`/`addTransformation`）与
`writeToOptimizedModels` 那一侧的"归一化 + 合桶 + `upload()`"。后者就是 §8 要搬的东西
（§8.4 量出来它每份 bundle 是 52–93 ms）。

### 7.5 为什么**读**不搬，以及为什么解析要加锁

* **读不搬**：`ResourceProvider` 背后是 `CustomResourceLoader.RESOURCE_CACHE` —— 一个**没有同步的**
  `Object2ObjectAVLTreeMap`。从别的线程读会与渲染线程并发改同一个结构。好在读实测只要
  **3 ms**（离线）／10–31 ms（实机，含扫一遍 4.3 MB 找 `mtllib`），留给渲染线程完全划算。
  顺带这也让"解析"这一半**完全不碰资源**（MTL 已预读），搬起来更干净。
* **解析必须串行**：`OptimizedModel$ObjModel.loadModel` 会去动一个**静态**的
  `OptimizedModel.ATLAS_MANAGER`（字节码确认：先 `ATLAS_MANAGER.load(identifier)`
  —— 里面是 `ResourceManagerHelper.readResource` + Gson 解析 —— 再把它交给
  `ObjModelLoader.loadModel` 去 `applyToMesh`）。而那个 AtlasManager 的两个容器是
  **普通 `HashMap`/`HashSet`**：两个解析同时跑就会把它写坏，而它是长生命周期静态对象，
  坏了之后每一次 `applyToMesh` 都可能抛异常。所以 `ModelResourceLoader.parseSource` 整段加了锁
  —— 后台解析（单线程，本来就串行）与"失败后的同步兜底"都走这一把。
  全工程只有 `ObjModel.loadModel` 碰得到那个 AtlasManager（`fromObjModels` / `upload` 的字节码里
  对它一次引用都没有），锁住这里就够了。

### 7.6 安全网（这是"模型不会消失"的来源）

| 情况 | 行为 |
|---|---|
| 解析还没好 | `createModel` 返回 `null`。`CachedResource` 与 `getCachedVehicleResource` 那条链**本来就用 `null` 表达"未就绪"**（车厢只是晚一两个 tick 出现）—— 已逐个核对过 5 个调用点，全部对 `null` 有处理 |
| 后台解析抛异常 | 打一条 warn，**在渲染线程上同步解析兜底**（模型照常出现，只是这一轮没省下时间），并在这条 `[MMTR-MODEL]` 后面注明"这一轮是后台失败后的同步兜底" |
| 资源重载与解析撞上 | 后台解析读的是 MC 的资源管理器；重载中读可能抛 `zip file closed` 之类 ⇒ 走上面那条兜底，不会崩 |
| 开关 | `-Dmmtr.modelparseasync=false` 回到"全在渲染线程上解析"（`sandbox/dev-run/modelparse-sync.gradle`） |

**不覆盖的范围**（写在明处）：

* **MQO / MQOZ**：要先 `MqoModelConverter.convert` 再解析，这条路暂不拆（`createSource` 返回 `null`，
  调用方走原来的整条同步路）；
* **`.bbmodel`**：直接构造 `BlockbenchModel`，本来就是小文件，不走这条；
* **几何那一段（④⑤：`generateNormals` / `distinct` / 合桶）当时仍在渲染线程** —— §8 已经把它搬走了。
  （`addTransformation` 留在渲染线程，它是 `writeCache` 的一部分，见 §8.3 的不变式。）

## 8 几何那一段：复制 → 归一化副本 → 合桶，搬到工作线程（`-Dmmtr.meshbakeasync`，默认开）

§7 之后渲染线程每轮重建还剩 50–166 ms。本节把它拆开、量出来、再把能搬的那一半搬走。

### 8.1 为什么不能直接搬（字节码读出来的三处证据）

一次重建在"几何"这一侧的顺序是：

```text
writeCache（渲染线程）          逐部件：addTransformation → ObjModel.rawModel 里累积一份"按位置烘好"的几何
writeToOptimizedModels（渲染线程）  逐 bundle：generateNormals → distinct →（可选的）按材质合桶 → upload
```

`upload()` 必须留在渲染线程（建 GL buffer）没问题；问题是 `generateNormals` / `distinct` **也只能留在渲染线程**，
原因是它们**就地改一份别人也在读的几何**。三条证据，全部来自反汇编（`sandbox/railbake-verify/disasm/`）：

| 证据 | 出处 |
| --- | --- |
| `distinct()` 的实现是 `vertices.clear(); vertices.addAll(去重结果)` —— **读的人会看到半个列表** | `RawMesh.distinct` 字节码 249–296 |
| 读它的不止一处：门批次 `MmtrDoorBatch.build → mergeOrNull`、逐部件门 `ModelPropertiesPart.optimizedModelDoor`、以及同一个源几何同时进"开门"和"关门"两个 bundle | `MmtrDoorBatch` / `ModelPropertiesPart.writeCache` |
| `generateNormals()` 会**重建顶点列表**（每个面角一份 `new Vertex(...)`），`distinct()` 再按"位置+法线+uv"去重 ⇒ 两者顺序不能换、也不能只搬一半 | `RawMesh.generateNormals` / `lambda$distinct$4` |

所以"把 `fromObjModels` 整段挪到别的线程"这条最直觉的路是**错的**：它会让渲染线程的 `upload()`
和别的线程的 `distinct()` 撞在同一份 `vertices` 上。

### 8.2 离线把"等价"和"能搬"都钉死：`ObjParseBench` 的新判据

改法定了之后第一件事不是改 mod，是**离线证明"复制一份再改副本"逐字节等价**——
`sandbox/railbake-verify/ObjParseBench`（真实映射库类、无 Minecraft、无 GL）：

```powershell
cd sandbox\railbake-verify
javac -encoding UTF-8 -proc:none -cp (Get-Content classpath.txt -Raw) -d . ObjParseBench.java
java -cp "$(Get-Content classpath.txt -Raw);." ObjParseBench obj\saf420car.obj obj\saf420car.mtl 4
```

warm 轮读数（`saf420car.obj`，4 343 592 B、40 组、35 501 顶点、26 752 面）：

| 分段 | 旧法（就地） | 新法（复制副本） |
| --- | --- | --- |
| ③ 烘位置 `addTransformation` | 6–8 ms | 6–8 ms（**不动，留渲染线程**） |
| ④ 归一化 `generateNormals + distinct` | 12–15 ms | 13–15 ms（**复制只加约 1 ms**） |
| ⑤ 合桶 `append` | 19–22 ms（含再归一化一遍） | **1 ms**（append 已经归一化好的副本） |
| 判据 | 合并路 `9e12de5b…`、逐部件路 `35253bb8…` | **两条路都逐字节一致 ✓** |

判据怎么算的：两个**独立解析**出来的同一份 OBJ（输入逐字节相同），一条走旧法、一条走新法，
把"材质桶顺序 + 每个顶点（位置/法线/uv/color/light）+ 每条面的索引"全进 MD5。合并路
`1 个材质 / 顶点 35501 / 面 26752`、逐部件路 `40 份 / 40 桶`，摘要都对上了。

⇒ 能搬的那一半 = ④⑤ ≈ **14–16 ms/份**（离线、单车单 bundle），剩下的 ③ 和 `upload()` 留渲染线程。

### 8.3 改法：两段式，加一条可以检查的不变式

**不变式：源几何（`ObjModel.rawModel`）自 `writeCache` 之后只读。**

新法把"改源几何"换成"改副本"：

```text
后台（工作线程，纯 CPU）  bake：逐项 new RawModel + 源的每个材质桶 append 进去
                              → 在副本上 generateNormals() + distinct()
                              → 合并模式：副本再 append 进一个按材质合桶的 RawModel
渲染线程                  upload：只做 RawModel.upload(mapping) + new OptimizedModel(parts)
```

* 交接用 §7 那台现成的机器（`MmtrAsyncModelParse`，同一个单线程 `MMTR-ModelParse`）；`label`
  区分分工：`后台解析 …` / `后台烘焙 …`。
* **先轮询完再碰 GL**：只要有一份后台还没好，这一轮整体 `return null`
  （`CachedResource` 那条链本来就用 `null` 表示"未就绪"）——绝不出现"这份 upload 了、那份没有"的半成品，
  那意味着已经建好的 GL buffer 被丢掉（漏显存）。
* 三个调用点各自的处理：

| 调用点 | 处理 |
| --- | --- |
| bundle 路（`VehicleResource.writeToOptimizedModels`） | 后台 `bake` + 渲染线程 `upload`（本轮改的就是这里） |
| 门批次（`MmtrDoorBatch.build → mergeOrNull`） | 改成**同步的复制版**（`mergeOrNull` 内部就是 `bake`+`upload`）。它在**渲染中**跑，而那时可能正有一份后台烘焙在读同一批门几何 —— 老实现就地改源几何，两者会撞上；复制版只读源 |
| 逐部件门 / 雨刷（`ModelPropertiesPart.optimizedModelDoor`） | **保持原样**（仍走映射库的 `fromObjModels`）。它在 `writeCache` 里跑，而任何针对这批几何的后台烘焙都在它**之后**才创建（bundle 是下一层 `CachedResource`），所以它就地改源几何是安全的 —— 不动它就是不动画面 |

* 顶点映射：逐部件那条路以前用映射库私有的 `DEFAULT_MAPPING`，现在与合并路共用自造的 `MAPPING`。
  这一条**离线反射比对过**（`sandbox/railbake-verify/VerifyVehicleMapping.java`）：
  `sources` 七项、`pointers`、`strideVertex=24`、`paddingVertex=1`、`strideInstance/paddingInstance` 全部一致。

### 8.4 实机 A/B（2026-10-08，SAF420，门关态，一轮重建波）

```text
# 加 -Dmmtr.meshbakeasync=false（= 改前：几何在渲染线程上算）
[MMTR-REBUILD] upload saf420cab_a/开门 ｜ 后台等待 0 ms ｜ upload 93 ms
[MMTR-REBUILD] upload saf420cab_a/关门 ｜ 后台等待 0 ms ｜ upload 86 ms
[MMTR-REBUILD] upload saf420car/开门 ｜ 后台等待 0 ms ｜ upload 53 ms
[MMTR-REBUILD] upload saf420car/关门 ｜ 后台等待 0 ms ｜ upload 52 ms

# 默认（= 改后：几何在 MMTR-ModelParse 上算）
[MMTR-ModelParse] [MMTR-MODELASYNC] 后台烘焙 saf420cab_a/开门:NORMAL: 52 ms（线程 MMTR-ModelParse）
[MMTR-ModelParse] [MMTR-MODELASYNC] 后台烘焙 saf420cab_a/关门:NORMAL: 55 ms（线程 MMTR-ModelParse）
[MMTR-ModelParse] [MMTR-MODELASYNC] 后台烘焙 saf420car/开门:NORMAL: 27 ms（线程 MMTR-ModelParse）
[MMTR-ModelParse] [MMTR-MODELASYNC] 后台烘焙 saf420car/关门:NORMAL: 28 ms（线程 MMTR-ModelParse）
[MMTR-ModelParse] [MMTR-MODELASYNC] 后台烘焙 saf420cab_b/开门:NORMAL: 51 ms（线程 MMTR-ModelParse）
[MMTR-ModelParse] [MMTR-MODELASYNC] 后台烘焙 saf420cab_b/关门:NORMAL: 38 ms（线程 MMTR-ModelParse）
[Render thread]   [MMTR-REBUILD] upload saf420cab_a/开门 ｜ 后台等待 11 ms ｜ upload 28 ms
[Render thread]   [MMTR-REBUILD] upload saf420cab_a/关门 ｜ 后台等待 0 ms ｜ upload 33 ms
[Render thread]   [MMTR-REBUILD] upload saf420car/关门 ｜ 后台等待 0 ms ｜ upload 34 ms
[Render thread]   [MMTR-REBUILD] upload saf420cab_b/开门 ｜ 后台等待 0 ms ｜ upload 20 ms
[Render thread]   [MMTR-REBUILD] upload saf420cab_b/关门 ｜ 后台等待 0 ms ｜ upload 25 ms
```

| 模型 / bundle | 改前：渲染线程 upload（含烘焙） | 后台烘焙（工作线程） | 改后：渲染线程 upload |
| --- | --- | --- | --- |
| cab_a / 开门 | 93 ms | 52 ms | **28 ms** |
| cab_a / 关门 | 86 ms | 55 ms | **33 ms** |
| car / 开门 | 53 ms | 27 ms | **< 20 ms**（没到打印阈值） |
| car / 关门 | 52 ms | 28 ms | **34 ms** |
| cab_b / 开门 | — | 51 ms | **20 ms** |
| cab_b / 关门 | — | 38 ms | **25 ms** |

* **渲染线程那份从 52–93 ms 降到 20–34 ms**（同一批模型、同一波重建）。
* 20:03:58 的第二波（**JIT 已热**，cab_b）读数更好：后台烘焙 32/37 ms、渲染线程 upload **22 ms**（另一份 <20 ms）
  ⇒ 上面那一波不是"冷启动的假象"。
* `后台等待 0–11 ms` ⇒ 渲染线程**不等**工作线程：没算好就这一轮 `return null`，下一轮再问。
* 新增一条分段读数（`[MMTR-REBUILD]`）：`地面/门洞/mapDoors saf420cab_a 25 ms` —— 这一段以前没量过，
  它也是渲染线程上的成本。

**别把这张表读成"渲染线程每轮省了 60 ms × 份数"**：同一波里 `[MMTR-MODEL]` 的 `建 VBO`
（`new DynamicVehicleModel`，即 `writeCache`/`addTransformation`）在三个会话里是 76 / 104 / 111 ms ——
**这一段本轮没动**，而它本身在 76–111 ms 之间抖（§7.4 记过：后台在忙时，渲染线程那一段会被 GC/带宽拖慢）。
把两段合起来看才诚实：**这一段改的是"几何归一化 + 合桶"（52–93 → 20–34），不是整轮重建。**

### 8.5 开关 / 安全网 / 不覆盖

| | |
| --- | --- |
| 打开 | 默认开；`-Dmmtr.meshbakeasync=false` 回到"几何在渲染线程上算"（`sandbox/dev-run/meshbake-ab.gradle -PmmtrAb=sync`） |
| 后台烘焙失败 | `[MMTR-MESH] … 后台烘焙失败 —— 回退到渲染线程上同步烘焙`，**这一份以后都走同步**；画面不受影响 |
| 映射库字段取不到 | `bake` 返回 `null` → 同一条同步回退；一条 warn（只打一次），`MergeOrNull` 那条路退回逐部件 |
| 没开优化渲染 | `upload` 直接返回 `null`，一条 GL 都不碰（老路上 merge 在这种配置下会照旧 upload，是个疣；这里挡掉） |
| 后台还没好 | 这一轮 `null` → 车厢晚一两个 tick 出现（与 §7.6 同一条链） |
| 不覆盖 | `materialGroups` 那条路（`.bbmodel` 车辆）仍是同步的：要拆它得再反射两个私有字段（`MaterialGroup.materialProperties` / `modelPartConsumers`），而且不是实测热点；MQO/MQOZ 同 §7.6 |

**这一轮没有单独跑到的**（写在明处）：门**开**态。两局客户端全程 `门开=0`（`[MMTR-VDRAW]`），
所以 `[MMTR-DOORBATCH]` 那条线没有重新出现过。但门批次调的 `mergeOrNull` **就是**同步挡位下每个
bundle 都在走的那条路（同步挡位走 `mergeOrFallback → mergeOrNull`），而关门 bundle 那 29 个部件里
就含那 24 组门 —— 那条路不是没验，是没**单独**验；`[MMTR-VEHMERGE] 合并 N → M` 的读数在两个挡位里
逐条对得上（`5→3 / 1→1 / 29→3 / 27→3`）。门开态要重新量的话，让一列车在站台开门即可。

### 8.6 这一项之后还剩什么

⚠️ **2026-10-08 追加：下面这个排序是按"单 tick 最坏"排的，但按"用户感觉到的那一下"排是错的。**
JFR 实测（**notes/401**）表明 300–660 ms 的帧与**钢轨烘焙**（`[MMTR-RAILBAKE] 新建烘焙 42–45 次`）
同拍，`RailMath` 占渲染线程采样 ≈20%，而车辆相关方法没进前 12；且**热稳态那一波重建（20:11:51）
一条 `[MMTR-LAG]` 都没有**。所以下面第 1 项仍然真实，但**不是**那个周期性大卡顿。

按"单 tick 最坏"排序（现在）：

1. **`建 VBO`（`new DynamicVehicleModel` = `writeCache` + `addTransformation`）76–111 ms** —— 本轮没动。
   ⚠️ **2026-10-08 更正（notes/401 §11.4）：那 72–111 ms 是"争用态"的数字**（进世界 / 一波三辆车同时重建、
   JIT 冷、区块与贴图都在加载）。把读数阈值降到 20 ms 后，**安静的稳态下同一条 cab_a 只要 17–41 ms**。
   门那 24 个模型的惰性化已经落地（`现建模型 28 → 4 次`，notes/401 §11.3），剩下的 `addTransformation` 16 ms
   与 `testDoors` 2–4 ms 不值得为它重构 `writeCache`（理由见 notes/401 §11.6）。
   （热稳态实测 72 ms；**用户感觉到的那个周期性大卡顿另有其人，见 notes/401**）
2. **GL `upload()` 20–34 ms/份** —— 建 VBO 本身，只能在渲染线程。真要再降就得**缓存烘焙结果**（比如把
   同一模型的 VBO 在缓存过期后留下来）而不是每次重建都重新 upload；
3. `地面/门洞/mapDoors` 25 ms；
4. 每 ~100 s 一波（§7.3）—— 上面每一条都是"缩短这一波"，而**彻底不卡**的另一条路是别让它过期：
   `VehicleModel.MODEL_LIFESPAN = 60000` 是"用内存换重建"的那个旋钮，`preloadResourcePattern`
   配置项就是官方给的版本（命中就 `Integer.MAX_VALUE`）。这条与本节正交，没动。

### 8.7 复跑这一节的清单

```powershell
# 离线（等价判据 + 分段读数）
cd sandbox\railbake-verify
javac -encoding UTF-8 -proc:none -cp (Get-Content classpath.txt -Raw) -d . ObjParseBench.java VerifyVehicleMapping.java
java -cp "$(Get-Content classpath.txt -Raw);." ObjParseBench obj\saf420car.obj obj\saf420car.mtl 4
java -cp "$(Get-Content classpath.txt -Raw);." VerifyVehicleMapping

# 实机 A/B（客户端直连本机 dev 服务端；服务端要先在跑）
cd mmtr\game
.\gradlew.bat -I "..\..\sandbox\dev-run\meshbake-ab.gradle" -PmmtrAb=sync  :fabric:runClient --console=plain *> fabric\run\ab-sync.out.log
.\gradlew.bat -I "..\..\sandbox\dev-run\meshbake-ab.gradle" -PmmtrAb=async :fabric:runClient --console=plain *> fabric\run\ab-async.out.log
# 看这三行： [MMTR-MODEL] / [MMTR-REBUILD] / [MMTR-MODELASYNC] 后台烘焙

# 服务端"重启一下"（时刻表跑完了要重来一遍时）：引擎的 `server restart` 会写标记、由 dev-server.ps1 接力；
# 手写标记 + RCON stop 也行（本轮就是这么做的）：
#   Set-Content mmtr\game\fabric\run\mmtr-restart.request 'restart'
#   python sandbox\rcon.py "stop"
# 前提是**启动器还活着**（dev-server.ps1 那个循环）；它不在的话服务端停了就没人拉起来。
```



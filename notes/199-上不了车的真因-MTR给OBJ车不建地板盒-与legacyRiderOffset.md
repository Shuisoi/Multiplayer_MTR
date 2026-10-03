# 199 · 上不了车的真因：**MTR 给 OBJ 车根本不建地板盒** —— 合成地板在 y=1，而 101 的司机在 y=3.13

> 用户实测：**"成了，能正常上车了"**。这一条是整个 B2 上车链路上最后、也是最深的一层。
> 排查过程见 notes/198（前两层：雨刷诊断的 NPE 打断渲染循环、本地锁跟了请求而不是引擎）。

---

## 1. 症状链（三层，一层套一层）

| 层 | 现象 | 真因 | 状态 |
| --- | --- | --- | --- |
| ① | 按 G 后**实体闪烁 + 视角被锁死** | 雨刷层 `logGate` 解引用空偏移 → NPE 抛穿 `RenderVehicles.iterateWithIndex` → **`movePlayer` 永远轮不到** | ✅ notes/198 |
| ② | 修完 ① 后**仍进不去**，日志说"四个角都没有地板" | 地板钳制找不到地板盒 | ✅ **本篇** |
| ③ | 引擎其实**一直是答应的** | `驾驶室=CAB_B·钥匙=CREW` —— 权限从来不是问题 | — |

---

## 2. 真因：MTR 的 **OBJ 路径不收集地板/门洞盒**

反查（不是猜）：

```java
// ModelPropertiesPart.writeCache —— Blockbench 重载：三种类型都有
case FLOOR:    iteratePositions(..., mutableBox.getAll().forEach(box -> floors.add(addBox(box, x, y, z, flipped))));   break;
case DOORWAY:  ... doorways.add(...);                                                                                  break;

// ModelPropertiesPart.writeCache —— OBJ 重载：只有 NORMAL
positionDefinitions.forEach(... -> { if (type == PartType.NORMAL) { ... } });      // ← 没有 FLOOR / DOORWAY
```

于是 OBJ 车的 `floors` **恒为空**，`VehicleResource` 落到兜底分支：

```java
if (floors.isEmpty() && doorways.isEmpty()) {
    Init.LOGGER.info("[{}] No floors or doorways found in vehicle models", id);   // ← 日志里就是它
    final double y = 1 + legacyRiderOffset;                                        // ← 合成地板
    floors.add(new Box(-x1, y, -z, x1, y, z));
    for (double j = -z; j <= z + 0.001; j++) { doorways.add(...); }                 // 沿车长一路铺门洞
}
```

**关键就在 `y = 1 + legacyRiderOffset`。** 而 `VehicleRidingMovement` 的 `clampPosition`：
- 主判据要求**盒在 Y 上包住骑手**；
- 兜底判据要求 **`|盒顶 − 骑手Y| ≤ 1 m`**。

`legacyRiderOffset` 默认 **0** → 合成地板在 **y = 1.0**。
而 101 的司机在 **y = 3.13** → 差 **2.13 m** → 两条判据全灭 → `offsets` 为空 → **`leaveRide`**。
实测日志（本轮加的量测）已经把这件事摆在明面上：

```
结束骑乘（四个角都没有地板（点 x=0.98 z=-12.53 y=2.32；这次有 1 个盒（其中地板 1 个），
          所有盒的 y 范围 [1.0, 1.0]））车=-8960752472062424013 车节=0 驾驶室=2
```

> "1 个盒、是地板、Y 范围恒为 1.0" —— 一眼就是**兜底合成地板**，不是模型的地板。

---

## 3. 修法：让打包器**声明**这个偏移（一个字段，不用改引擎）

`legacyRiderOffset` **是现代资源包就支持的字段**（不是只能从旧版格式来）：

```java
// generated/resource/VehicleResourceWrapperSchema.java
legacyRiderOffset = readerBase.getDouble("legacyRiderOffset", 0);
```

所以打包器算好写进 `mtr_custom_resources.json` 即可：

```js
// pack_vehicle.js
const EYE_HEIGHT_M=1.62;                                   // MC 玩家眼高
const seatAnchor=anchors.find(a=>a.kind==='seat');
const riderFeetY=seatAnchor?seatAnchor.y-EYE_HEIGHT_M:(floorTopY!==null?floorTopY:null);
const legacyRiderOffset=params.legacyRiderOffset!==undefined?params.legacyRiderOffset:(riderFeetY===null?0:riderFeetY-1);
```

**为什么用 `mmtr_seat` 的眼位而不是地板顶面**：MC 的相机固定在**脚底 + 1.62 m**，
所以"人站在地板上"会把相机放到模型眼位下方 0.81 m（notes/198 §4 量过，101 会坐在台面下）。
把骑手的参考点定成**座位眼位 − 1.62**，既满足钳制（落在同一个 Y 上），
又让**相机正好落在建模时的眼位**上 —— 而这正是 `mmtr_seat` 这个锚点的含义。

101 的结果：`legacyRiderOffset = 4.750 − 1.62 − 1 = 2.13` → 合成地板 **y = 3.13**
（且 3.13 比模型地板 2.32 高 0.81 m —— 那是**坐姿**时"脚"的位置，物理上是对的）。

可用 `params.legacyRiderOffset` 覆盖。

---

## 4. 实测验收（用户确认 + 日志）

```
[MMTR-CAB] 上车结果：进入驾驶室 1B | 驾驶室=CAB_B·钥匙=CREW·速度=0km/h | 仍在车上=true 驾驶室已确认=true
[MMTR-PERF] … 车内定位=244 次 骑乘=-8960752472062424013 …        （此前 车内定位 恒为 0）
NPE = 0
```

- `仍在车上=true`、`驾驶室已确认=true` → **上车成立，且引擎确认，司机锁生效**；
- `车内定位=244` → `movePlayer` 每帧都在把人钉在车上（之前一次都没有）；
- 用户："**能正常上车了**"。

产物：**`BR101_v5.zip`**（`mtr_custom_resources.json` 里 `legacyRiderOffset: 2.13`），
`options.txt` 已指向 v5。

---

## 5. 其他车型要不要一起修？

同一套打包器算出来的偏移：

| 车型 | 座位眼位 | 算出的 offset | 合成地板 y | 现有包（offset 0） | 影响 |
| --- | --- | --- | --- | --- | --- |
| **br101** | 4.750 | **2.13** | **3.13** | y=1.0 | **差 2.13 m → 必然上不去** |
| saf101 | 2.500 | −0.12 | 0.88 | y=1.0 | 差 0.12 m，在 1 m 容差内 → 能用 |
| hst_h | 2.500 | −0.12 | 0.88 | y=1.0 | 同上 |

**所以只有 101 会被它卡死**（它的地板/眼位特别高）。其余车"能用但不够准"，
重打包可以把 0.88 对上（差异 12 cm），非必须。

---

## 6. 教训

1. **"打包成功 + 模型能显示"完全不代表 MTR 理解了你的模型。** MTR 的 OBJ 路径与 Blockbench 路径
   **功能不对等**：地板/门洞盒只在 BB 路径生成。逐条读过加载器才看得见（notes/194 是同一类教训的另一面）。
2. **兜底值必须能被数据覆盖。** `y = 1 + legacyRiderOffset` 是个好设计（旧包能继续用），
   但**打包器不写这个字段**，所有 OBJ 车就被钉在 y=1。
3. **量测要量到"对方实际用的东西"。** 我先前写的 `cab_floor_check.js` 量的是**打包 OBJ 里的 floor 组**，
   而 MTR 根本不用那个 —— 量错了对象，所以给出了 PASS。这次把盒子**在运行时的实际数值**打进日志才定案。
4. **MC 的眼高是固定 1.62 m**：地板高度、台面高度、座位眼位三者必须按这个常数对齐，
   否则"能不能看见仪表"就不由模型决定、而由这个常数决定。

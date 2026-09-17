# 181 · MMTR 车辆部件命名表

> 每一格的依据都在本仓库代码里核对过，来源标注在每节末尾。
> 适用范围：Fabric 1.20.4 / pack_format 18。打包器 = `mmtr/tools/obj-mtr-packager/pack_vehicle.js`。

---

## 0. 先决条件：`groupMap` 必须先认得出这些名字

打包器**不会**自动扫 `mmtr_*`。它靠车辆配置里的 `groupMap` 把 OBJ 的 `o`/`g` 名映射到"角色"，
**没有匹配上 `anchor` 模式的名字会被完全忽略**（连锚点都不会产生）。

```jsonc
"groupMap": {
  "body":     ["body"],              // 角色名固定，见 §2
  "interior": ["BlockEntities"],
  "door_l":   ["door_l"],
  "door_r":   ["door_r"],
  "anchor":   ["mmtr_"]              // ← 这一条让所有 mmtr_* 被识别为锚点
}
```

> 客户端**从不自己拼 `mmtr_` 前缀**去匹配。锚点名是打包器从模型里读出来、去掉 `mmtr_` 后写进
> `mmtr_anchors_<id>.json` 的，客户端只按这个名字查表。所以"命名规则"是**打包器**说了算。

---

## 1. 锚点命名表（`mmtr_*` 具名面）

**一个锚点 = 模型里一个独立四边形 Object。**

| 模型里的名字 | 客户端 kind | 必需性 | 几何是否保留 | 用途 |
|---|---|---|---|---|
| `mmtr_hud` / `mmtr_hud_1` / `mmtr_hud_2` | `hud` | 做仪表就必需 | ❌ 剥离（纯数据） | 驾驶室 2D 仪表平面。面法线朝司机 |
| `mmtr_windshield` / `mmtr_windshield_1` / `mmtr_windshield_2` | `windshield` | 做雨刷/雨雪就必需 | ❌ 剥离（纯数据） | **雨雪/雨刷平面**。`_n` = **驾驶室号**（与 hud/cabdoor/seat 一致）。**面中心 = 雨刷枢轴，"右"边方向 = 停放方向**，法线**朝车内**（Blender 里"红色面朝外"） |
| `mmtr_cabdoor_<驾驶室>_<第几扇>`<br>例 `mmtr_cabdoor_1_1` | `cabdoor` | 推荐 | ✅ **保留**（渲染为 `cabdoor_<驾驶室>_<第几扇>`） | 司机门：可瞄准、可进驾驶室；也是可见部件 |
| `mmtr_seat_<驾驶室>` 例 `mmtr_seat_1` | `seat` | 可选 | ❌ 剥离 | 司机座位/眼位。法线 = 行进方向 |
| `mmtr_ack_<驾驶室>` 例 `mmtr_ack_1` | **`OTHER`** | ⚠️ **别用** | ❌ 剥离 | **死数据**：打包器认这个名字并写进 JSON，但客户端 `parseKind` 没有 `ack` 分支 → 落到 `OTHER`，**全仓库无任何代码消费它** |

### 编号规则

| 项 | 规则 |
|---|---|
| **驾驶室号** | **1 = A 端（车头端 −Z），2 = B 端（车尾端 +Z）**。不带编号按 1 处理 |
| **门序号** | 从**车头端往车尾端**编号（`mmtr_cabdoor_1_1`、`mmtr_cabdoor_1_2`…） |
| **挡风玻璃序号** | `_1` / `_2` = **驾驶室号**（与 hud/cabdoor/seat 一致，1 = A 端、2 = B 端）。不带编号按驾驶室 1 处理 |
| **`.001` 后缀** | Blender 重名会自动加 `.001`，打包器会**先剥掉**再匹配，所以 `mmtr_hud.001` 等价于 `mmtr_hud` |

### 面的几何语义（每个锚点都一样）

| 项 | 规则 |
|---|---|
| 面中心 | = 锚点位置 |
| 面法线 | = 朝向（由**顶点绕序**决定；反了就在配置里加 `flipAnchorNormalByGroup`） |
| "上"边 | **与世界 +Y 最贴合的边** |
| "右"边 | 与"上""法线"正交的另一条边 |
| 面尺寸 | = 实际尺寸（1 单位 = 1 米 = 1 格），自动量取 → `widthM` / `heightM` |

> ⚠️ **"上"必须尽量贴竖直**：雨点是沿**重力在玻璃平面内的投影**流的，雨刷停放角也是相对"右"轴量的。
> "上"边选错 → 雨横着流、雨刷斜停。

### 锚点 JSON 里的可调字段（打包器写出，值来自车辆配置）

| 字段 | 作用 | 写在哪 |
|---|---|---|
| `panelFlipU` | 左右镜像面板/雨层贴图。**只有模型面的"右"边方向与约定相反时才用** | 锚点 |
| `panelPxPerMetre` | 面板贴图分辨率（按**长边**每米像素）。0 = 客户端默认 256 |
| `panelTwoSided` | 两面都画（诊断用） | 锚点 |
| `windshield` 块 | 雨雪/雨刷参数（`raindrops`/`armM`/`pivotU`… 见 notes/179） | 顶层，按锚点名索引 |

> ⚠️ **打包器不写 `panelPxPerMetre` / `panelFlipU` / `panelTwoSided`**（`buildAnchors()` 只写
> `name/kind/cab/door/car/x/y/z/normal/up/right/widthM/heightM`）。这三个**只能手改锚点 JSON，重打包会丢**。

**来源**：`pack_vehicle.js:73-95`（anchor 角色与几何剥离）、`:148-155`（kind 正则与字段）、
`MmtrVehicleAnchors.java`（`Kind` 枚举、`parseKind:368+`、`findHuds`、`findWindshields`、`toRidingSpace`）。

---

## 2. 部件命名表（OBJ 里作为 `o`/`g` 出现的分组）

这些是 `groupMap` 的**角色名**（**不是**模型里的原始名 —— 原始名靠 `groupMap` 的模式匹配映射过来）。

| 角色名 | 渲染阶段 | 说明 |
|---|---|---|
| `body` | `EXTERIOR` | 车体：所有不可动件合并成一个 Object |
| `interior` | `INTERIOR_TRANSLUCENT` | 内装（可透视） |
| `door_l_<n>` | `EXTERIOR` | 左侧客车门叶，`n` 从**车头端往车尾端**编号。**每扇门一个独立 Object** |
| `door_r_<n>` | `EXTERIOR` | 右侧客车门叶，同上 |
| `cargo` | `EXTERIOR` | 货车货箱（`newstock.json` 的 cargotest 在用） |
| `cabdoor_<驾驶室>_<第几扇>` | `EXTERIOR` | `mmtr_cabdoor_*` 去前缀后**自动成为可见部件**，无需在 `groupMap` 里声明 |
| 其他任意角色名 | `EXTERIOR` | `groupMap` 里出现过但不在上面的，按 EXTERIOR 输出 |
| `anchor` | — | **数据，永不渲染**（几何被剥掉） |

### 自动生成的部件（**不要建模**）

| 部件名 | 来源 | 说明 |
|---|---|---|
| `doorway_door_l_<n>` / `doorway_door_r_<n>`<br>或 `doorway_<组名>` | 打包器按门叶包围盒生成 | `type: DOORWAY`，门洞。逐门一块薄片（不是一整块） |
| `floor` | 打包器按门叶下沿生成 | `type: FLOOR`，可站可走、不渲染。**没有它司机走不到驾驶室门** |

> 纯机车没有客车门 → 需要 `"extraDoorways": ["cabdoor_1_1", ...]` 才能生成司机门洞，
> 并用 `"floor": true` + `"floorHeightBlocks"` 强制生成地板（`newstock.json` 的 saf101 就是这么配的）。

**来源**：`pack_vehicle.js:282-300`（parts 生成）、`:206-251`（doorway/floor 合成）、
文档 `docs/02-运行与作业/MMTR-OBJ车辆资源包-标准化工作流.md §3.1`。

---

## 3. 容易搞混的四点

0. **挡风玻璃的法线朝"车内"**：Blender Face Orientation 里 **红色面朝车外、蓝色面朝车内**。
   雨层是**双面画**的（`twoSided` 默认 `true`），所以法线主要决定**雨刷朝哪抬**与**雨点流向**，
   不决定"能不能看见"。

1. **`mmtr_windshield_2` 的 `2` 也是驾驶室号**（和 `mmtr_hud_2` 同义）。打包器仍给它**独立正则**
   `^windshield(_(\d+))?$`，原因是它**没有第二个编号**（`mmtr_hud_<驾驶室>` 只有一段，
   `mmtr_cabdoor_<驾驶室>_<第几扇>` 有两段），而不是因为语义不同。
   序号进 `cab` 槽、`door` 留 `null`；hud/cabdoor/seat 用
   `^(hud|seat|cabdoor|ack)(?:_(\d+))?(?:_(\d+))?$`（`:148`），`cab` 取第一段、`door` 取第二段。

2. **只有 `mmtr_cabdoor_*` 是"既是锚点又是可见部件"。**
   其余 `mmtr_*` 全是纯数据、几何会被剥掉（连顶点都删，不留包围盒）。
   想"既能瞄准又能看见"的件，只能用 `mmtr_cabdoor_` 这个名字。当前**没有**通用的
   "任意 `mmtr_` 名都是可交互锚点"机制。

3. **`mmtr_ack_<驾驶室>` 是死数据。** 文档、打包器、`pack-vehicle.ps1` 的报告都提到它，
   但客户端 `parseKind` 没有 `ack` 分支 → `Kind.OTHER`，无任何代码消费。**AWS 确认只有键盘键（默认 H）。**

4. **驾驶室归属目前只对 hud/cabdoor/seat 有效。**
   挡风玻璃**不带驾驶室信息**（`cab = null`），渲染时 `findWindshields` 只按 `car`（第几节车）过滤。
   所以双端机车的前后风挡都能画出来，但**在代码里区分不出"这块属于哪个驾驶室"**。
   若将来需要按驾驶室区分（比如只在司机所在那端画雨刷），要么用命名槽位区分，要么扩解析逻辑。

---

## 4. 客户端 kind 表（`MmtrVehicleAnchors.Kind`）

| kind | 来源 | 谁在用 |
|---|---|---|
| `HUD` | `hud` | `MmtrCabDashboard` → `MmtrHudLayout` 面板 |
| `WINDSHIELD` | `windshield` | `MmtrWindshield` 雨雪/雨刷 |
| `CABDOOR` | `cabdoor` | `MmtrCabInteraction` 瞄准进门、`MmtrDoorSides` 左右门 |
| `SEAT` | `seat` | `MmtrVehicleAnchors.cabView` 司机眼位 |
| `DOOR` | `door` | **不可达** —— 打包器的正则产不出这个 kind |
| `OTHER` | 其他一切（含 `ack`） | **无人消费** |

> `cabView` 的回退链：优先 `mmtr_seat_<驾驶室>`；没有则取 `mmtr_hud` 的 x/z，
> 沿仪表面水平法线后退 `EYE_BACK_M = 1.05 m` 当座位点，Y 取门叶下沿（=地板高度）。

---

## 5. 最小可用组合（给一台双端机车）

```
模型里必须有的 Object：
  body                     车体
  mmtr_hud_1               驾驶室1 仪表面
  mmtr_hud_2               驾驶室2 仪表面
  mmtr_seat_1 / _2         座位眼位（可选，强烈建议）
  mmtr_windshield_1        前风挡（做雨刷就要）
  mmtr_windshield_2        后风挡
  mmtr_cabdoor_1_1 …       司机门（可见 + 可瞄准进门）

车辆配置里：
  "groupMap": { "body":["body"], "interior":["BlockEntities"], "anchor":["mmtr_"] }
  "extraDoorways": ["cabdoor_1_1", "cabdoor_1_2", "cabdoor_2_1", "cabdoor_2_2"]
  "floor": true, "floorHeightBlocks": 1.0
  "doorSlideByGroup": { "cabdoor_1_1": 14, "cabdoor_1_2": -14, "cabdoor_2_1": -14, "cabdoor_2_2": 14 }
```

现成范例：`mmtr/tools/obj-mtr-packager/consist/newstock.json` 的 `saf101` 条目（缺 `mmtr_windshield_*`，那部分要新加）。

---

## 6. 相关文档

| 内容 | 位置 |
|---|---|
| 完整车辆打包工作流（含面板 8 条坑） | `mmtr/docs/02-运行与作业/MMTR-OBJ车辆资源包-标准化工作流.md` |
| 用户输入清单 | `mmtr/docs/02-运行与作业/MMTR-OBJ车辆-用户输入清单.md` |
| 挡风玻璃雨雪/雨刷的实现与参数 | `mmtr/notes/179-挡风玻璃雨雪层与雨刷.md` |

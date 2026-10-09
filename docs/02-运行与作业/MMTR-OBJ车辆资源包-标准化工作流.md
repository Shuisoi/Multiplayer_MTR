# MMTR 自定义车辆：Blender→OBJ→资源包 标准化工作流

> 目标：你在 Blender 里把车体/车门模型做对并导出 OBJ，然后**只需提供几个参数**（车辆 ID/名称、转向架(轴)数量、车长、车宽），流程自动把模型整理成 MTR/MMTR 可直接加载的资源包 zip 并放入开发端 resourcepacks。
> 适用：MMTR 开发端（Minecraft 1.20.4 · Fabric · pack_format 18）。核心加载逻辑与 MTR4/NTE OBJ 规范一致，本环境已实测打通：贴图、朝向、门动作、DOORWAY 判定。

## 0. 分工

| 谁 | 做什么 |
| --- | --- |
| 你（建模端） | 在 Blender 里按规范建模并导出 OBJ(+MTL+PNG)；填参数文件；游戏内验收反馈 |
| 打包端（DSH/脚本） | 读参数 → 几何规范化(旋转/居中/分组改名) → 整理贴图 → 生成注册/属性/门洞 JSON → 打包 zip → 放入 resourcepacks |

> ⚠️ **跑脚本用 `pwsh`，不要用 `powershell`（5.1）**：工作区脚本是 UTF-8 无 BOM + 中文注释，
> PS 5.1 会按 ANSI(GBK) 解码。要么整行被吞（"解析通过"但语句没了），要么直接语法错 ——
> 踩过的实例见 **notes/196**（`dev-client.ps1` 的 dot-source 被吞 → `JAVA_HOME` 没设 → 客户端起不来）。
> 若必须用 5.1，先跑一次 `powershell -File mmtr\scripts\add-utf8-bom.ps1`（幂等）。

## 1. Blender 建模与导出规范

坐标系（MTR/NTE 车辆 OBJ 规范）：
- **X 向右**(车宽方向)、**Y 向上**、**Z+ 朝车尾**(车头朝 -Z)
- **1 单位 = 1 米 = 1 格**
- 模型原点放在**整节车几何中心**（X、Z 居中；Y 可取地板处）
- 所有面保留 UV；车窗/玻璃等透明材质 PNG 带 alpha 通道
- 贴图：导出时勾选写 MTL，PNG 与 OBJ 放同一文件夹即可（打包端会处理路径）
- **`flipTextureV: true`**（打包参数）—— Blender 导出的 `vt` 是 v 向上、Minecraft 的贴图是 v 向下，
  所以 Blender 模型一律要 `true`。判断依据：**贴图的 alpha 分布 vs 模型 UV 的 bbox**
  （模型的 UV 应该落在贴图不透明的那一半）。写错**不会报错**，只会让整台车消失（notes/194）。

### 1.5 ★ MTR 读 OBJ 的方式（硬约束，违反即静默丢几何）

> 2026-09-19 反汇编 `org/mtr/mapping/render/obj/ObjModelLoader`（`de.javagl.obj`）确认，
> 起因是 BR101「只有雨刷显示」，全过程见 `mmtr/notes/194`。
```java
for (int i = 0; i < obj.getNumVertices(); i++) {
    normal = i < obj.getNumNormals()   ? obj.getNormal(i)   : ZERO3;
    uv     = i < obj.getNumTexCoords() ? obj.getTexCoord(i) : ZERO2;   // (0,0)
}
for (each face) new Face(new int[]{ f.getVertexIndex(0), f.getVertexIndex(1), f.getVertexIndex(2) });
```

MTR **不读**面里的 `v/vt/vn` 三元组，而是**按顶点下标把三张表焊在一起**（第 i 个顶点配第 i 个 vt、第 i 个 vn），
并且**每个面只读前 3 个角**。所以 MTR 能读的 OBJ 只有一种布局：

| 要求 | 说明 |
|---|---|
| `#v == #vt == #vn` | 三张表等长，且第 i 项互相对应 |
| 每个面是**三角形** | 四边形会丢第 4 个角，n 边形会变成乱三角 |
| 每个角写成 `f i/i/i` | `vt`/`vn` 下标与顶点下标相同 |

**Blender 的默认导出两条都不满足**（顶点共享、`vt`/`vn` 各自索引空间、保留四边形）。

✅ **打包器现在自动改写**（`pack_vehicle.js` 的 `toMtrObj()`：去焊接 + 耳切三角化，写盘前最后一步，
三条不变量不满足就直接抛异常），所以**建模端不需要做任何事**。但有两件事建模端要负责：

1. **没有 UV 的面**：MTR 永远会取一个 UV，所以"这个面没有 UV 层"在 MTR 里不存在。
   打包参数 `"untexturedUv": [u, v]` 指定这类面该采的纹素（不配则采 (0,0)）。
   BR101 的雨刷整套在 Blender 里没有 UV 层，配的就是贴图上的一块深灰。
2. **模型的 UV 必须落在贴图的不透明区域**（`flipTextureV` 正确的前提下；
   贴图 58% 透明时，UV 落错半张图 = 整车被抠光）。

**离线判据**：`node mmtr/tools/obj-mtr-packager/verify_mtr_obj.js --config <vehicle.json>`
—— 按 MTR 的读法重新解析打包产物并与源模型对照（布局 / (位置,UV) 配对 / 面积 / 包围盒）。
**"资源都在、日志正常、但部件看不见或贴图错位"先跑它。**

### 1.6 ★ MTR 给 **OBJ 车不建地板/门洞盒** → 人站在 y=1，与模型地板无关

> 2026-09-19 反查 `ModelPropertiesPart.writeCache` / `VehicleResource` 确认，起因是 **BR101 按 G 上不了车**，
> 全过程见 `mmtr/notes/198`（NPE 层）与 **notes/199**（本篇）。

MTR 有两个 `writeCache` 重载，**功能不对等**：

| 路径 | NORMAL | FLOOR | DOORWAY |
| --- | --- | --- | --- |
| Blockbench（`.bbmodel`） | ✓ | ✓ 收集盒 | ✓ 收集盒 |
| **OBJ（我们用的一律是这条）** | ✓ | **✗ 不收集** | **✗ 不收集** |

于是 OBJ 车的 `floors` **恒为空**，`VehicleResource` 走兜底，日志里会出现
`[<车型>] No floors or doorways found in vehicle models`：

```java
final double y = 1 + legacyRiderOffset;          // ← 合成地板的高度
floors.add(new Box(-x1, y, -z, x1, y, z));       // x1 = width/2 + 0.25, z = length/2 - 0.5
```

而 `VehicleRidingMovement.clampPosition` 找地板的方式是：
**主判据 = 盒在 Y 上包住骑手**；**兜底 = `|盒顶 − 骑手Y| ≤ 1 m`**。
`legacyRiderOffset` 默认 0 ⇒ 合成地板钉在 **y = 1.0** ⇒ **模型地板离 y=1 超过 1 m 的车，人一上车就被踢下来**
（症状：按 G 有反应、引擎也批准，但人上不去 / "四个角都没有地板"）。

**规则（打包器已自动处理，不必手写）**：`legacyRiderOffset` = `riderFeetY − 1`，其中
`riderFeetY = mmtr_seat 的 y − 1.62`（MC 玩家眼高）；没有 seat 锚点时用打包器生成的地板顶面。
可用 `params.legacyRiderOffset` 覆盖。打包时会打印：

```
rider offset: legacyRiderOffset=2.1300 (mmtr_seat seat_1 eye 4.750 - eye height 1.62) -> MTR synthetic floor y=3.130
```

⚠️ **建车时必须让"地板/台面/座位眼位"与 MC 的 1.62 m 眼高对齐**：座位眼位 ≈ 地板顶面 + 1.62 时最自然；
眼位写得比这高，相机就会落在台面下方（101 就是按真车高度建的，台面在地板之上 1.83 m，见 notes/198 §4）。
**参考值**：`legacyRiderOffset` 落在 0 附近（±1）的车不受影响；超过 ±1 就必须写对，否则上不去车。
（现有包：br101 需要 **2.13**；saf101 / hst_h 算出来是 **−0.12**，不写也能用。）

分组(部件)约定（导出前把模型按部件分成独立 Object）：
- 车身/所有不可动件合并为一个：`body`
- 车内固定件(灯/内装，可选半透明)：`interior`
- 客车门：**每扇门叶一个独立 Object，带编号** `door_l_1`、`door_l_2`、`door_r_1`…（`l`/`r` = 左/右，数字从**车头端往车尾端**编号）。每扇门会生成**独立部件 + 独立门洞**，可单独设滑移方向
- 门洞 DOORWAY：不需要你建，打包脚本**逐门**自动生成（`doorway_door_l_1` …）
- 车内可站立地面 FLOOR：不需要你建，打包脚本按门叶下沿高度自动生成 `floor` 部件（否则司机只能站在门洞里，走不到驾驶室门）
- **驾驶室交互锚点（可选）**：`mmtr_*` 具名面 —— 见 §1.1

### 1.1 交互锚点 `mmtr_*`（具名面 → 游戏内定位/朝向）

**一个锚点 = 一个独立四边形 Object**，命名以 `mmtr_` 开头，编号规则统一：

| 命名 | 用途 | 法线朝 | 必需性 |
| --- | --- | --- | --- |
| `mmtr_hud_1` / `mmtr_hud_2` | 驾驶室 1/2 的 2D 仪表平面 | 司机 | 做仪表就必需 |
| `mmtr_cabdoor_<驾驶室>_<第几扇>` 例：`mmtr_cabdoor_1_1`、`mmtr_cabdoor_1_2` | 驾驶室 1 的第 1/2 扇司机门（瞄准提示 + 判断进哪个驾驶室） | 车外 | 推荐 |
| `mmtr_seat_1` / `mmtr_seat_2` | 驾驶室 1/2 的司机座位点（相机眼位） | 行进方向 | 可选 |
| `mmtr_ack_1` / `mmtr_ack_2` | AWS 确认按钮位 | 司机 | 可选 |
| `mmtr_windshield_<驾驶室>[_<玻璃>]` | 驾驶室 `<驾驶室>` 的第 `<玻璃>` 块风挡（下雨/雨刷平面） | 车外 | 做雨雪就必需 |
| `mmtr_wipersweep_<驾驶室>_<玻璃>[_<第几把>]` | 该玻璃的**雨刷作用面**（三角扇，见 §1.4②）；同一块玻璃可以有多把（§1.4⑥） | 车外 | 有雨刷就必需 |
| `mmtr_pid_<驾驶室>[_<第几块>]` | **水牌**：面中心 = 牌面中心，尺寸 = 牌面大小；客户端在这一面上画「班次号 + 本趟终点」 | **车外**（站台上的人要看得见） | 做水牌就必需 |
| `mmtr_next_<驾驶室>[_<第几块>]` | **下一站牌**（车内显示屏）：客户端画「下一站 X」 | **车内**（乘客要看得见） | 可选 |

- **水牌 / 下一站牌（notes/357）**：两块牌的形状一样，判据只差"读它的人在哪一侧"——
  `mmtr_pid_*` 的法线必须**背离车心**（车外），`mmtr_next_*` 必须**指向车心**（车内）。
  文字由客户端画（底 + 字都在 `MmtrPidBoard` 里烤成一张贴图），所以**牌底不用建模成可见几何**：
  模型里那块牌只需要一个四边形 Object，打包器会把它的面剥掉、只留下中心/法线/up/尺寸。
  `up` 那一侧决定字的上方向（水牌的 up 必须立着，否则字横躺）。A/B 两端要各写一块
  （`_1` / `_2`），B 端车的 `groupRename` 里记得成对改名 —— 漏了就是"B 车水牌自称 cab1 却长在另一端"，
  离线判据 `mmtr/tools/anchor-check/verify_pid.js`（P1..P7）会红。
  **已经有一块现成的可见牌**（例如 SAF420 的 `dest_board`）不必重做：把它从 `groupMap.body`
  里拿掉、在 `groupRename` 里改名成 `mmtr_pid_<驾驶室>` 即可（notes/357 §2）。

- **驾驶室编号：1 = A 端（车头端 -Z），2 = B 端（车尾端 +Z）**；不带编号按 1 处理。
- **`mmtr_cabdoor_*` 会同时作为"可见部件"输出**（部件名去掉 `mmtr_` 前缀，如 `cabdoor_1_1`，EXTERIOR 渲染），所以你在 Blender 里把司机门做成实体就能看见、能瞄准；其余锚点（`mmtr_hud` / `mmtr_seat_*` / `mmtr_ack_*`）是纯数据，会从几何里剥掉。
- 司机门要滑动的话，用 `"doorSlideByGroup": {"cabdoor_1_1": 14}` 给它一个位移（默认不滑动）。
- 一个驾驶室可以有多扇门：`mmtr_cabdoor_1_1`、`mmtr_cabdoor_1_2` …（从车头端往车尾端编号）。
- 动力集中式（同一辆车两个驾驶室）：同一模型里放 `mmtr_hud_1` + `mmtr_hud_2`。
- **折面仪表（一块面板折了几个面）**：把 N 个**共边四边形**放进**同一个** `mmtr_hud_<驾驶室>` Object。
  打包器会把它们**展开**成一张画布，客户端每个面画一块 quad、各取自己的 UV 子矩形，图像跨折痕连续。
  - 折痕 = **共享的顶点索引**（网格要焊接）；没焊接就等于"没有折痕"，会退回单块平板并在日志里 warn。
  - 每个面必须是**四边形**；**折角 ≤ 89°**（接近 90° 的 L 形请改用下面这条）。
  - 打包器会写 `canvasWidthM`/`canvasHeightM`（展开后的画布，比投影尺寸高）和 `faces[]`；单面模型两者都不写。
- **两块独立屏幕各显示整套仪表**（主屏 + 复示屏）：用 `mmtr_hud_<驾驶室>_1` / `_2` 两个独立 Object。
  每块各画一张完整画面，**不**做 UV 切分。
- 多节编组：每节车单独打包，参数里给 `carIndex`；驾驶室锚点只放在有驾驶室的车。
- **hud 面板可调字段（写进锚点 JSON，改完重打包即可，不用改代码）**：
  | 字段 | 默认 | 作用 |
  | --- | --- | --- |
  | `panelFlipU` | `false` | 面板贴图左右镜像（折面面板按 `u -> 1-u` 作用于每个 UV 子矩形）。**只有**模型面"右"边方向与打包器约定相反时才用（正常不需要，见 §1.3⑤） |
  | `panelPxPerMetre` | `0`（客户端 256） | 面板贴图分辨率；画布会自动钳制到 ≤512 px |
  | `panelTwoSided` | `false` | 两面都画（诊断用）。正常只画司机那一面 |
- **仪表画面是"按车型"的**（`hud` 块，见 §1.3⑧）：同一车型的两个驾驶室共用一套画面，不同车型各自一套。写在打包参数里：
  ```jsonc
  { "id": "saf101", /* … */ "hud": { "background": "#FF05080C", "widgets": [ /* … */ ] } }
  ```
  也可以写 `"hudLayout": "C:/…/saf101_hud.json"` 指向一个文件（内容为 `hud` 块或包着 `hud` 的对象）。
  两者都不给时打包器写一套默认画面（居中速度 + `km/h`），你改完重打包即可。

面的几何语义：面中心 = 锚点，面法线 = 朝向，与世界 +Y 最贴合的边 = "上"，另一边 = "右"，
面尺寸 = 实际尺寸（1 单位 = 1 米）。**法线由顶点绕序决定**，反了就加 `"flipAnchorNormal": true`。

参数文件 `groupMap` 一条覆盖全部锚点：`"anchor": ["mmtr_"]`；逐门滑移方向可用
`"doorSlideByGroup": {"door_l_2": -14}` 覆盖。

**两种建模形式（都可以）：**
1. **具名面（推荐，零工具）**：锚点做成**单个四边形** Object，命名 `mmtr_seat_1` 等。OBJ 会导出它，打包器直接读。
2. **Blender 空物体 Empty**：OBJ 格式**不导出 Empty**，所以必须用 `mmtr/tools/obj-mtr-packager/example/dump_empties.py`
   在 Blender 里跑一次（Scripting → Run），它会把所有 `mmtr_*` 空物体写成 `.blend` 同目录的
   `mmtr_anchors_extra.json`（已换算成 OBJ 坐标系），打包器自动合并。空物体的**本地 +Z 轴 = 朝向**
   （seat 指向行进方向，hud 指向司机），位置 = 空物体世界坐标。

输出示例（实测：3 扇客车门 + 2 个锚点）：
```json
{"anchors":[
 {"name":"hud_1","kind":"hud","cab":1,"door":null,"car":0,"x":0.005,"y":1.15,"z":-2.005,
  "normal":[0,0,1],"up":[0,1,0],"right":[1,0,0],"widthM":1,"heightM":0.3},
 {"name":"cabdoor_1_1","kind":"cabdoor","cab":1,"door":1,"car":0,"x":-1.015,"y":1.1,"z":-0.895,
  "normal":[1,0,0],"up":[0,1,0],"right":[0,0,-1],"widthM":0.6,"heightM":1.8}]}
```
部件输出：`body` / `door_l_1` / `door_l_2` / `door_r_1` + `doorway_door_l_1` / `doorway_door_l_2` / `doorway_door_r_1` + `floor`。

### 1.1.1 在 Blender 里建水牌面（`mmtr_pid_*` / `mmtr_next_*`，notes/357）

水牌（车外目的地牌）与下一站牌（车内屏）**只需要一个平四边形**：位置、尺寸、朝向由它定，
牌底（深色）与文字（班次号 + 本趟终点 / 下一站 X）**由客户端画**，所以不要把牌面做成可见几何。

1. **建面**：在水牌该在的地方建一个四边形（4 个顶点、共面），Object 命名
   `mmtr_pid_1`（A 端）/ `mmtr_pid_2`（B 端）；同一端两侧各一块就 `mmtr_pid_1_1` / `mmtr_pid_1_2`
   （带序号时最后一个数字是"这一端的第几块"，内容相同）。
2. **朝向（最重要）**：水牌的**法线必须朝车外**（站台那一侧）—— 读它的人站在法线那一侧。
   绕序反了 = 牌背朝站台，而那一层**开背面剔除** ⇒ 整块牌**看不见**（打包与日志都不会报错）。
   车内的下一站牌相反：法线朝**车内**（乘客那侧）。
3. **文字方向**：`up` = 与世界 +Y 最贴合的那条边，所以牌面要有一条边大致竖直（字才是正的；
   横躺的 up 会让字横过来 —— `verify_pid.js` 的 P5 抓这个）。
4. **尺寸**：`widthM × heightM` 就是字能用的范围（客户端按它烤贴图，超宽会等比缩小）。
   车上常见的扁牌（如 1.24 × 0.22 m）排成两行：上一行班次号（小字）、下一行终点（大字）。
5. **不要重复**：锚点的面会被打包器**剥掉**，所以模型里**不要**再放一块可见的牌面；
   若原来已经有一块（SAF420 的 `dest_board` 就是），把它从 `groupMap.body` 里拿掉并改名成
   `mmtr_pid_<驾驶室>`（见 §1.1 的说明），或者删掉它、只留新锚点四边形。
   ⚠ 两处都产出同一块牌时，锚点会有两条同名记录（`verify_pid.js` P1 会红）。
6. **两端成对**：B 端车（`rotationDegY 0` + `groupRename`）里加
   `"mmtr_pid_1": "mmtr_pid_2"`、`"mmtr_pid_1_*": "mmtr_pid_2_*"`（`next` 同理）——
   无序号写法**匹配不到**通配符（少一个 `_`），所以两种都写。
   ⚠ **同一端可以挂好几块**（现场 SAF420：车头正脸 1 块 + 车头端两侧各 1 块 + 车尾端两侧各 1 块 = 5 块）。
   **单驾驶室车的车侧中部牌仍然是那一个 cab** —— 它们在同一节车上、显示同一份内容（notes/357 §9）。
   `verify_pid.js` 的 P6 就是这么判的：全车只有一个 cab（看 hud/cabdoor/seat 的 cab 值）时，
   所有牌必须都是那个 cab；只有**两端都有驾驶室**的车才用"文件 z>0 = cab1"的符号口径。
7. **自检**（改完就跑，不用进游戏）：
   ```powershell
   node mmtr\tools\anchor-check\verify_pid.js mmtr\tools\obj-mtr-packager\example\vehicle.saf420cab_a.json
   pwsh -File sandbox\pid-anchor-probe\check.ps1   # 对自检本身做故障注入（6 种坏法各自变红）
   ```
8. **过渡说明（2026-10-01 → 2026-10-02 已拆桥）**：SAF420 曾经走的是"把可见组 `dest_board` 改名成
   `mmtr_pid_1/2`"这条桥（零建模成本，先让功能可见）。**2026-10-02 桥已拆**：`sandbox/saf420_destboard.py`
   现在直接生成规范锚点名 `mmtr_pid_1`（**单个四边形**：面心 (0, 3.41, −9.756)、1.24 × 0.22 m、
   法线 −Z 朝车外、up = +Y），`consist/saf420.json` 与两份 `example/vehicle.saf420cab_*.json` 里的
   `groupRename.dest_board` 条目**已删除**（留着它，模型里一旦再出现同名组就会把同一块牌产出两次）。
   ⚠ 顺带记一条踩过的隐患：**别拿可见盒子当锚点**——盒子的前后面面积相等（都 0.2728 m²），
   打包器取"第一个面积最大的面"，法线朝里还是朝外**只取决于网格面顺序**；朝里那一层开背面剔除，
   游戏里整块牌看不见，而导出/打包/自检一个都不报错。
   历史记录见 `mmtr/notes/357-水牌PID-锚点契约与客户端渲染.md`；判据 `verify_pid.js`（A/B 两端各 7 项全 PASS）。

### 1.2 游戏内如何使用锚点（B7.6d）

- **瞄准驾驶室门 → 提示按 F**：客户端用 `mmtr_cabdoor_<cab>_<n>` 算出门的实际世界坐标，取视线夹角最小且在 5 m 内的那扇门，屏幕下方提示「按 F 进入 N 号驾驶室」；按 F 即把该驾驶室钥匙交给引擎并**把玩家钉到驾驶室眼位**。
- **眼位怎么来**：优先 `mmtr_seat_<cab>`（若有）；否则取 `mmtr_hud` 的 x/z，沿仪表面**水平法线**（法线指向司机）后退 1.05 m 作为座位点，Y 取门叶下沿（=地板高度）。MTR 每 tick 会把骑乘 Y 吸附到地板顶面，所以真正决定视角的是 x/z。
- **只有一端的模型**：模型只有 1 号驾驶室时，编组另一端（2 号驾驶室）自动按 z 镜像使用同一组锚点（日志里 `mirrored=true`），无需为对称车再画一套。
- **朝向**：司机朝车头方向（车体局部 -Z）；镜像出来的 2 号驾驶室朝 +Z。
- **离开**：对着同一扇门再按 F（或对着车按 F）= 拔钥匙。客户端日志会打印 `[MMTR] cab N of vehicle ... seat car-local (...)`，缺锚点时会打印实际加载到的锚点数量。

Blender 导出：File → Export → Wavefront (.obj)，勾选 Materials / Write Normals / Include UVs。

### 1.2.1 水牌版式：每个车型（甚至每块牌）自己一套（notes/358）

**尺寸本来就独立**（`widthM × heightM` 是打包器从那块四边形量出来的），但"排版"默认只有一套两行规则 ——
一块 1.24 × 0.22 m 的扁牌和一块 0.5 × 0.5 m 的方牌用同一套字号比例，总有一边难看。于是版式也交给配置：

```json
"pid": {
  "background": "#FF101418", "textColor": "#FFF2F4F6", "pxPerMetre": 512,
  "rows": [
    { "field": "service",  "x": 0.03, "y": 0.5, "size": 0.46, "align": "left", "color": "#FF9FB3C8" },
    { "field": "terminus", "x": 0.97, "y": 0.5, "size": 0.62, "align": "right", "prefix": "开往 " }
  ]
},
"next": {
  "rows": [ { "text": "下一站", "x": 0.5, "y": 0.75, "size": 0.22 },
            { "field": "next", "x": 0.5, "y": 0.35, "size": 0.5 } ]
}
```

| 键 | 写在哪 | 说明 |
| --- | --- | --- |
| `pid` / `next` | 车辆配置（每节车各写各的） | 打包器**原样**写进 `mmtr_anchors_<车型>.json`；**不写 = 默认版式**（班次号在上、站名在下两行），老包一个字节都不用改 |
| `background` / `textColor` | 段 | 牌底色与默认字色（`#RRGGBB` / `#AARRGGBB`） |
| `pxPerMetre` | 段 | 这块牌的像素密度（缺省 512；画布长边仍会被夹在 512 px） |
| `rows[]` | 段 | 自上而下**不会自动排序**：`y` 就是位置，自己写 |
| `field` | 行 | `service`（班次号）/ `terminus`（本趟终点）/ `next`（下一站）；拼错 = 这一行不画 |
| `text` | 行 | 字面量（如"下一站"、"回库"）；与 `field` 二选一 |
| `x` / `y` | 行 | 位置，**牌面宽/高的 0..1**（0,0 = 左下角） |
| `size` | 行 | 字高，**牌高的比例**（0.02..1.5，超界钳回） |
| `align` | 行 | `left` / `center`（缺省）/ `right` |
| `color` | 行 | 这一行的字色（缺省用 `textColor`） |
| `prefix` / `suffix` | 行 | 内容前后加的固定字（如"开往 "） |

四条经验（第一条是实测踩出来的）：

1. **并排要"靠边对齐"**：一行里放两块内容时用 `align: left` + `x: 0.03` 与 `align: right` + `x: 0.97`。
   两块都 `center` 时，大字号那一行的宽度会盖到另一块上（第一版示范就是这么压在一起的）。
2. **全是比例 ⇒ 同一版式在任何尺寸下自动等比**：字号是牌高的比例、位置是牌面比例，所以"换尺寸不用改版式"；
   某一行超过牌宽 90% 会自动**等比缩小**（不会被牌边切掉）。
3. **取不到内容的行不画**：回库趟（终点未知）只画班次号那一行；下一站牌没有站名时整块不画。
   `text` 那种字面量行不看字段 —— 所以"回库趟显示『回库』"这类版式写得出来（整块牌挂不挂仍由作业单决定）。
4. **不带 `pid` 段就还是老样子**：默认版式与 notes/357 那套写死的排版逐字一致（有用例钉着）。

**离线出图（改版式时别起客户端）**：

```powershell
pwsh -File mmtr\tools\pid-preview\preview.ps1 -Anchor <mmtr_anchors_x.json> `
     [-Board pid|next] [-Service 00101] [-Terminus 海山] [-Next 鸥湾] `
     [-Out <png>] [-WidthM 1.24 -HeightM 0.22 -PxPerMetre 512]
```

尺寸默认从锚点读（与游戏里一致）；给 `-WidthM/-HeightM` 就能回答"这块牌做成 1.6 × 0.12 m 好不好看"。
它跑的是**真的那条链**（`MmtrPidLayout.parse` + `MmtrPanelCanvas`，与游戏同一份 Java2D），只写 `sandbox\`。
CJK 字形走**系统兜底**（离线预览拿不到资源包里的 HarmonyOS），所以字面形状与游戏里有细微差别。

### 1.3 仪表面板渲染契约（B7.6e，2026-09-09 实测固化）

客户端把 `mmtr_hud` 面当成一块**运行时画的贴图**贴在面上：`MmtrPanelCanvas`（米制 2D 画布，Java2D 光栅化）→ 一张 `NativeImage` → `MmtrPanelTexture`（一车一张贴图）→ `MmtrPanelQuad`（一个四边形）。下面每一条都是踩过坑才定下来的，改面板前先读：

**① 一块面板 = 一张贴图 + 一个 bucket + 一个四边形**
MTR 的渲染队列是 `stage → QueuedRenderLayer → Map<Identifier, 回调列表>`，**每个 Identifier 对应一个 RenderLayer，也就是一张贴图**；同一 bucket 里两张不同贴图谁先谁后由游戏决定、不由 `QueuedRenderLayer` 决定。所以"底板 + 文字"分两张贴图分层画一定会翻车（曾经出现底板盖住文字）。要加几何图形就画进同一张图。

**② 贴图尺寸必须先定，`upload()` 不能改尺寸**
`NativeImageBackedTexture.upload()` 内部是 `glTexSubImage2D`（只能替换已有尺寸的像素），只有 `NativeImageBackedTexture(NativeImage)` 构造函数会调 `TextureUtil.prepareImage` 真正分配显存。用 16×16 占位贴图去传 512×99 → GL 直接拒绝 → 面板显示**彩色噪点/花屏条纹**（未初始化显存）。`MmtrPanelTexture` 因此用第一张真实图建贴图，尺寸变了才重建并换新 identifier；尺寸不变才 `setImage + upload`。

**③ 四边形绕序：正面朝局部 −Z**
`IDrawing.drawTexture` 的矩形重载生成的四边形的**正面朝局部 −Z**，而 `QueuedRenderLayer.LIGHT_2`（`RenderLayer.getText`）**保留背面剔除**（MTR 自己的车载显示屏也是靠 `translate(..., -SMALL_OFFSET)` 把四边形推到 −Z 侧）。框架沿局部 +Z 抬离锚面（3 cm），所以必须显式按 **左下 → 右下 → 右上 → 左上** 给四个角点把绕序翻成 +Z，否则司机看到的那一份会被推进仪表台内部，只剩缝隙里的碎块。

**④ UV：v2 才是上边缘**
`GraphicsHolder.drawTextureInWorld` 给四个角的 UV 依次是 `(u1,v2) (u2,v2) (u2,v1) (u1,v1)`（反汇编确认），和矩形重载的参数命名相反。想让图像正立，要传 `v1=0, v2=1`（图像顶行落在面板顶边）。

**⑤ 朝哪一面画、U 往哪边走**
- 锚面帧：**直接用锚点自己的正交基摆四个角** —— `centre + sideRight·a + up·b + sideNormal·lift`，
  然后**只套车体变换**（`carTransform.transform`）后就画，**没有局部旋转**。
  > ⚠️ **永远不要反解欧拉角。** 曾经用过 `pitch=asin(-up.y) / yaw=atan2(n.x,n.z) / roll=atan2(up.x,up.y)`
  > 那套解：它把 `up` 的竖直分量当成 pitch 的全部，**只对"竖直朝前的面板"成立**，
  > 倾斜面（风挡、上仰仪表台）会被绕车的横轴转掉一大截 —— 风挡实测差 **90°**（notes/179 §10）、
  > BR101 仪表实测差 **68°**（notes/200）。判别法：把 `normal/up/right` 两两点乘，
  > **正交就说明锚点没错，是代码用错了这三个向量**。离线量偏差：`node sandbox/panel_euler_error.js <anchors.json>`。
  > 摆完角点**必须 `graphicsHolder.pop()`**（`transform()` 会 push，漏了会破坏矩阵栈）。
- 画哪一面：模型空间里车体以原点为中心，所以**仪表"朝车内"的一侧 = 法线指向原点的那一侧**，自动选边；法线与车长平行时无法判断，才两面都画；
- U 方向：**与 `side` 无关，永远不需要翻 U**。被绘制的那一面法线是 `side·normal`、它指向司机，所以司机视线 `f = -side·normal`，司机右手
  `f × up = -side·(normal × up) = side·(up × normal) = sideRight` —— 正好是四边形局部 +X。
  换句话说局部 +X 天然朝司机右手边，文字从左到右。（旧版本按"司机总是朝车体 −Z"判过 `side`，把 B 端那类面镜像了；见 notes/60。）
  `panelFlipU` 只留给"模型作者把面画反了"这种情况。

**⑥ 每节车每个驾驶室一块仪表，只画乘坐中的那节**
同一个模型通常被编组里每节车复用，于是每节车都带 `mmtr_hud`——全画出来就是"一列车上好几块仪表"。现在只画**本机玩家正在乘坐的那节**；坐别的车/电梯时本节不画；没坐车时只画 12 m 内的那节。
**双端机车是同一节车两个驾驶室**（`mmtr_hud_1` + `mmtr_hud_2`），两端各画一块（`findHuds`），画面内容同一套（见 ⑧）。

**⑦ 诊断手段（保留在代码里，验收后可按需清理）**
- 每块面板首次光栅化会把 PNG 写到 `game/fabric/run/mmtr-panel-debug/<key>.png`（可直接看画布内容；双端机车会同时出现 `*_hud_1.png` 与 `*_hud_2.png`）；
- 日志 `[MMTR-DBG] panel ...` 打印 identifier、贴图 GL id、渲染层实际绑定的贴图类与 GL id、缺失贴图 GL id，以及锚点在模型空间的 position/normal/up/right、`chosenSide` 与两侧的 yaw/pitch/roll/flipU。**"看不见"先看这行**：`registeredGl == textureGl` 说明贴图绑定正确，问题在几何/遮挡；否则是贴图注册/绑定问题。

**⑨ 折面仪表：一张画布 + 每面 UV 子矩形**
仪表台是折面时（一个 `mmtr_hud*` Object 里有多个不共面的共边四边形），一个平板贴不上去 ——
打包器会把组压成一个锚点：中心取**全部顶点**的平均（落在折角外的空气里）、法线只取**面积最大那个面**、
宽高却按全部顶点投影（面板被撑大）。结果是一块错位的大平板，一半埋进壳体、一半悬空。
现在改为：
- 打包器额外写 `canvasWidthM`/`canvasHeightM`（沿折痕**展开**后的画布，比投影尺寸高）和 `faces[]`
  （每个面：自己的 frame + 真实平面尺寸 + 在画布里的 `u0,v0,u1,v1`）；组级字段语义**全部不变**；
- 客户端画布按 `canvas*` 开，`layout.paint()` 只画**一次**，然后**每面一块 quad**（`MmtrPanelQuad.drawFacet`），
  各取自己的 UV 子矩形；
- **`v0` 是顶边**（画布第 0 行 = 图像顶部，和单面路径一致）；`panelFlipU` 按 `u -> 1-u` 作用到每个子矩形；
- 每个面的**司机侧判定是逐面**做的（折面各面朝向不同，用组级平均法线会整体选错边）；
- 单面模型**不写** `faces`/`canvas*` → 走原来的单 quad 路径，**老资源包不用重打**。
- 离线验证：`mmtr/tools/anchor-check/`（`anchor_faces.js` 判断是不是折面、`verify_facets.js` 校验
  UV 连续性/覆盖/朝向等八项、**`verify_panel_frame.js` 用客户端的取景数学再校验一遍**（见下）、
  `selftest.js` 用故障注入证明验证器有牙）；合成 fixture 在 `mmtr/tools/anchor-check/fixture/`。
- **为什么折面要验两遍**：`verify_facets.js` 验的是**打包器自己的展开坐标系**（沿折痕展开、累加偏移，
  数字自洽）。客户端**不用**这套坐标系 —— 它对每个 facet 重算
  `right = cross(up, normal)`、`side = facingSide(position, normal)`（车体以原点为中心，法线指向车内的一侧）、
  局部 +X = `side * right`，`u0` 落在局部 −X、`u1` 落在 +X，再沿局部 +Z 抬离锚面。
  两套坐标系之间可以**连续地错开**：BR101 仪表台曾是**剪切**的平行四边形，修直之后画布在两道折痕上
  又**不连续**（跨过共边 u 从 1 跳到 0.412），直到把 Blender 里 `mmtr_hud` 面的**绕序**重做才对齐。
  这些在打包器自己的数字里**全都看不出来**，截图也只能告诉你"看着不对"。
  `verify_panel_frame.js` 因此按客户端的方式摆每个角点，落在同一点（≤ 2 cm）的两个角必须拿到**同一个 (u,v)**；
  并按 **u 必须朝司机的右手方向增大** 判定没有镜像 —— 全局镜像（`u → 1-u` 且 `u0/u1` 仍有序）
  既连续、又正好铺满画布、也满足展开比例，**只有这一条能发现**（故障注入里有这一例）。
  用法：`node mmtr/tools/anchor-check/verify_panel_frame.js --config <车型参数.json>`。

**⑧ 画面按车型：`hud` 布局块**
面板内容不是写死在代码里的，而是每个**车型**一份布局，写在锚点 JSON 的 `hud` 块里（打包器从参数 `hud`/`hudLayout` 生成，见 §1.1）：
```json
"hud": {
  "background": "#FF05080C",
  "widgets": [
    {"kind": "roundRect", "x": 0.015, "y": 0.06, "w": 0.97, "h": 0.88, "radius": 0.08, "color": "#FF0E141C"},
    {"kind": "speed", "x": 0.5, "y": 0.6, "size": 0.52, "color": "#FFFFFFFF", "align": "center"},
    {"kind": "text",  "text": "km/h", "x": 0.5, "y": 0.19, "size": 0.2, "color": "#FFD2E6F7"},
    {"kind": "rect",  "x": 0.02, "y": 0.02, "w": 0.96, "h": 0.04, "color": "#FF3FE0FF"}
  ]
}
```
- **坐标全部是面板的比例（0..1）**，不是米：`x/w/x2` 相对面板宽，`y/h/size/radius/lineWidth` 相对面板高 → 改面板尺寸不用重画布局。原点在左下角。
- 控件：`speed`（实时 km/h）、`limit`（实时限速，0 时显示 `--`）、`text`（静态文字，`align` = left/center/right）、`gauge`（**实时指针表盘**，见下）；图形 `rect`/`roundRect`（`w,h,radius`）、`line`（`x,y`→`x2,y2`，`lineWidth`）、`circle`（`radius`）、`arc`（`radius`、`start`/`end` 度、逆时针从 +X 起算）。
- **`gauge`（模拟指针表）**：`x,y` 圆心、`radius` 表盘半径、`start`/`end` = 指针在 **0 与满量程**时的角度（同样是逆时针从 +X 起算的度；`end < start` 表示顺时针扫，BR101 用 `210 → -30` 即左下→顶→右下共 240°）、`max` 满量程（默认 160，忘写也仍有指针）、`ticks` 主刻度段数、`tickLength` 刻度长（默认 0.12）、`labelEvery` 每几格标一次数字（0 = 不标）、`labelSize`/`labelColor`、`lineWidth`+`color` 画表盘弧、`needleColor`（默认红 `#FFFF3B30`）/`needleWidth`/`needleLength`（相对半径，默认 0.94）+ 圆心点。指针角 = `start + (end-start) * 速度/max`，**两端都钳位**（超速顶在满量程，不回绕）。
  > `ticks > 12` 时每 5 格才画长刻度；`ticks ≤ 12` 时全部按主刻度画。静态 `rect`/`arc`/`text` 拼不出会动的指针，所以仪表必须用 `gauge`。
- 颜色 `#RRGGBB` 或 `#AARRGGBB`。
- 没有 `hud` 块 → 客户端用默认画面（居中速度 + `km/h`）。
- **改完先在离线预览里看**：`pwsh -File mmtr\tools\panel-preview\preview.ps1 -Anchor <layout.json> -Speed 87 -Limit 100`
  会用**客户端同一套类**（`MmtrHudLayout.parse` + `MmtrPanelCanvas`）画到 PNG，不必进游戏。

### 1.4 风挡：多块玻璃、雨刷作用面、实体雨刷

> **实现状态（2026-09-18）**：**W1 已实现**（`<驾驶室>_<玻璃>` 命名、`wipersweep` 扇形拟合、
> 实体雨刷部件识别、离线校验全部到位）；**W2 已实现**（按作用面擦拭本来就走 `park → park+sweep`，
> 字段变准后自动生效；"按驾驶室找玻璃"补了 `findWindshields(car, cab)` 与 `findWindshield(car, cab, pane)`）；
> **W3/W4 未实现**（曲面玻璃的展开渲染；实体雨刷**渲染在建模位置但不会转** —— 绕支点旋转要改 MTR 部件渲染）。
> ⚠️ 另：`driverOnBoard()` 目前硬编码 `false`（上下车重构期间，notes/185），所以**在游戏里雨刷暂时不会动，只有雨滴**。
> 实现顺序与理由见 `mmtr/notes/187-风挡多玻璃与雨刷作用面-规范冻结.md`。
>
> **2026-09-19 追补（notes/193）**：`notes/189` 已把 `driverOnBoard` 实装（雨刷会动了）。
> 首次真车入包（BR101）暴露并修掉**打包器 3 处真缺陷**：① 锚点法线曾取"组质心三点叉积"，
> 折面组偏 49° → 折面仪表拿不到 facet 数据；② 行程求解曾分不清"停放边/远端边"，
> 真实机车两条边近乎平行时会写出 0.3° 这种行程；③ `foldAboutUp`（竖折痕）分支的 v 矩形曾用错坐标。
> **给模型的硬性要求因此再明确一次**：折面锚点**每块面必须是矩形**（相邻边 90°），
> 错切的平行四边形客户端画不出来（矩形与面不等大）；雨刷必须建在**行程端点**上（= 扇形的边界边）。
>
> **2026-09-19 追补（notes/202、notes/203）**：**W4 已实现**（实体雨刷随档位按机构运动学转动），
> W3（曲面玻璃）仍未做。两轮的根因分别在"渲染链"和"两个坐标/角度轴"：
> 几何在 OBJ 路径上落在 `optimizedModelDoor`、且被 optimized 批量渲染吞掉（notes/202）；
> 让它转起来之后又"动得不对"—— 欧拉反解读错矩阵元（轴偏 13.81°）＋ 支点被当成模型坐标
> （旋转轴穿过模型原点、杆子飞到天上）＋ 配置比例漏了 `−0.5`（半屏偏移）（notes/203）。
> 离线判据新增 `mmtr/tools/anchor-check/verify_plane_frame.js`（源码守卫 + 帧契约 + 与二维运动学的桥梁断言），
> `selftest` 30/30；注意**轴对齐的夹具测不到这个 bug**（yaw=±90° 处 pitch/roll 退化，错解与正解同矩阵），
> 所以回归必须打在客户端源码上。


一个驾驶室通常有**多块玻璃**（前挡可能还分左右两片，另有侧窗、车门玻璃），每块**各自**下雨、各自有湿度与雨刷。
客户端本来就是"每个锚点一份独立状态"（`findWindshields` 返回该车所有风挡锚点，各自一份雨滴/湿度/画布/贴图），
所以多块玻璃不需要新架构 —— 只需要**命名**和**查找**跟上。

**① 命名与编号**

| 命名 | 含义 |
| --- | --- |
| `mmtr_windshield_<驾驶室>_<玻璃>` | 驾驶室 `<驾驶室>` 的第 `<玻璃>` 块玻璃 |
| `mmtr_wipersweep_<驾驶室>_<玻璃>[_<第几把>]` | 同编号玻璃上**第 `<第几把>` 把**雨刷的作用面（扇形）；缺省 = 1 |
| `wiper_<驾驶室>_<玻璃>[_<第几把>]` | 该把雨刷的刀片（**实体建模**的可见部件，见 ④） |
| `wiperarm_<…>` / `wiperrod_<…>` | 同一把雨刷的臂 / 拉杆（见 ⑤） |

- **有 `mmtr_wipersweep_<cab>_<pane>[_<n>]` 锚点 = 那块玻璃有这一把雨刷**，名字里不再重复编码这件事。
- 玻璃编号：**主档永远 = 1**，侧窗/其它依次递增。
- **索引规则（向后兼容，务必遵守）**：只写**一个**索引 = **驾驶室号**（玻璃默认 1）；
  写**两个**索引 = 驾驶室号 + 玻璃号。
  所以 `mmtr_windshield_1` = 驾驶室 1 主档、`mmtr_windshield_2` = **驾驶室 2** 主档（老模型照旧），
  `mmtr_windshield_1_2` = 驾驶室 1 的第 2 块玻璃。
  锚点 JSON 里 `pane` **只在不是 1 的时候才写**（缺省 = 1），这样单玻璃模型的老锚点条目逐字节不变。
- **雨刷索引是第三个索引，且只在不是 1 的时候写**：`mmtr_wipersweep_1_1` = 驾驶室 1 第 1 块玻璃的
  **第 1 把**雨刷，`mmtr_wipersweep_1_1_2` = **同一块玻璃的第 2 把**。
  于是 `mmtr_wipersweep_1_2` 仍然是"驾驶室 1、第 2 块玻璃、第 1 把" —— 老模型逐字节不变。
  一块玻璃两把雨刷 = **一个玻璃锚点 + 两个扇形 + 两套实体件**，不是两块玻璃：见 ⑥。
- 一个驾驶室有多块玻璃后，"按驾驶室找玻璃"必须返回**全部**同编号玻璃（雨刷档、司机在车上、面板指示灯都要按此判定）。
  客户端为此提供两把：`findWindshields(car, cab)`（该驾驶室**全部**玻璃，驾驶室级判断用这个）与
  `findWindshield(car, cab, pane)`（指定玻璃）；`Anchor.pane` 缺省 1。

**② 雨刷作用面（扇形）—— 怎么建模**

因为雨刷是**实体**，mod 不能再从"自己画的刀片"推断刮拭范围，所以刮哪儿由这块扇形几何**明说**。

- 建模成一个**三角扇**：所有三角形**共享同一个顶点**，那个顶点就是**雨刷支点**（转轴）。
- 用**至少 3 个三角形**（≥4 个边缘顶点）。两个三角形的"扇形"其实就是个四边形，
  **几何上无法分辨哪个角是支点** —— 打包器会退而采用"每个三角形的第一个顶点"作为支点并打一条 NOTE；
  三个以上就没有这个歧义。
- 扇形与它作用的那块玻璃**共面/共曲面**，画在玻璃上（可以略微抬起避免 z-fighting）。
  ⚠️ 扇形的**绕序（法线朝向）不影响拟合** —— 拟合全部在**玻璃**的二维域里做，扇形的法线不参与；
  但把两个镜像雨刷画成同一绕序会让第二个扇形法线朝里，看着奇怪而已。
- 打包器**拟合**出下面这些量，写进该玻璃锚点的 `windshield` 配置块（**复用现有字段**，单雨刷模型
  客户端一行代码都不需要改）：

| 拟合量 | 来源 | 客户端字段 | 参照 |
| --- | --- | --- | --- |
| 支点 | 扇心（共享顶点）投到玻璃的二维域 | `pivotU` / `pivotV` | `pivotU`：从**左**边量；`pivotV`：从**下**边量（0..1 比例） |
| 半径 | 扇内离支点最远的顶点 | `armM` | 米；不给则默认取面**短边的一半** |
| 起始角 | 扇形的起始边界（用"最大的角度空隙"定位，因此过 180° 也不会搞反） | `parkAngleDeg` | 从面的**右**边量起，**+ 朝上** |
| 扫过角 | 两条边界边之间的夹角 | `sweepDeg` | 度；臂的行程是 `park → park + sweep`，往复（0→1→2 三角波） |
| 方向 | 取 +1（即 park 取较小角那端） | `sweepSign` | 想让停放位在另一端就设 `-1` 并把 `parkAngleDeg` 改成大角 |

- **有拟合出来的作用面 ⇒ 该玻璃自动 `wiper=true`**，不用手写。
- 拟合只**填补**配置里没写的字段；`windshield` 块里显式写了的仍然优先（调参用）。
- 扇形的**曲率**不必自己处理：如果玻璃是折面/曲面，扇形顶点沿同一套**展开**落进同一个二维域，作用面自动跟着曲面走。
  ⚠️ **W3 未做**：目前曲面玻璃的拟合是**跳过并记 NOTE**（客户端也还没按展开域渲染）。
- 拟合不合理会**打 WARNING 并放弃**（扇心不唯一、跨度 < 5°、支点远在玻璃之外），不会静默出错。

**③ 曲面玻璃**

玻璃是折面/曲面时，把 N 个**共边四边形**放进**同一个** `mmtr_windshield_<cab>_<pane>` Object
（和折面仪表完全一样的规则：网格要焊接、面要是四边形、折角 ≤ 89°）。
打包器沿折痕**展开**成画布（`canvasWidthM`/`canvasHeightM`），客户端：
- 雨滴/湿度/雨刷全部在**展开域**里模拟（于是雨滴速度与间距按**弧长**算，不被弦长压缩）；
- 雨层与雨刷刀片**逐面**渲染（每面取自己的 UV 子矩形），刀片按面裁剪。
- 单面玻璃**完全不写** `faces`，走原来的单 quad 路径。

**④ 实体雨刷（`wiper_<cab>_<pane>[_<n>]`）**

- 模型里是**独立可见部件**，每把雨刷**一个** Object，名字 `wiper_<cab>_<pane>[_<n>]`
  （`wiperarm_` / `wiperrod_` 同规则）。
- **不需要往 `groupMap` 里加角色**：打包器按名字约定直接认它（和门叶一样每个雨刷**独立成件**，
  这样才能各自绕自己的支点转）。这也顺便避开了两个坑：没有 `mmtr_` 前缀的组如果不被识别，
  它的面会**不写 `g` 行却仍写出去**（静默粘到前一个组）；而用一个通用 `"wiper"` 角色会把**所有**
  雨刷并成一个部件、还会和 `mmtr_wipersweep_*` 抢名字。
- 有实体雨刷 ⇒ 该玻璃自动 `drawBlade=false`（客户端不再自己画刀片，否则两个刀片叠在一起），
  但 `wiper` 仍然是 `true`：**玻璃照旧被刮**，实体雨刷只是替代了"画出来的刀片"。
  这两个概念是分开的，别把它们混成一个开关。
- 角度与玻璃上的刮拭**共用同一份动画状态**（就是现在驱动画刀片的那个 `wiperAngleDeg`），
  所以"雨刮动画"和"雨滴被刮掉"天然同步，不存在两套时间。
- ✅ **W4 已实现**（notes/202、notes/203）：实体雨刷按机构运动学随档位转动。做法是给部件渲染加一层
  每帧的刚体变换（`MmtrWindshield.pushPartTransform`）：臂/杆绕各自支点转，刀片按"销对"走过的角
  转 + 把停位销平移到当前位置（因此**平行连杆的纯平移天然落在这个式子里**，不是另一条分支）。
  三个必须一起对的量（错一个就"动得不对"，见 notes/203）：**轴**（欧拉角必须从玻璃基向量
  `(right, up, normal)` 的矩阵元取：`pitch=asin(-normal.y())`、`roll=atan2(right.y(), up.y())`，
  不能拿 `up` 的分量去猜）、**支点**（`plane.pointAt()` 要"相对玻璃中心的米"，必须先把配置里的
  0..1 比例减 0.5 再乘尺寸）、**变换落点**（矩阵栈是模型空间，先 `pointAt` 把支点抬到玻璃上，
  再平移/旋转/反平移）。
  `verify_plane_frame.js` 就是这三条的离线判据（含源码守卫与"共轭旋转 vs 二维运动学"的桥梁断言）。

**⑤ 雨刷机构：单轴 / 平行连杆（两族都支持）**

大部分机车雨刷是**平行连杆**，汽车那种是**单轴**。两族的区别不是参数而是**机理**：

| | 单轴（汽车） | 平行连杆（机车） |
| --- | --- | --- |
| 刀片 | 随臂**转** | 基本保持方向，只**微转** |
| 刮过的地方 | **扇形**（宽） | **微微的扇形**（窄） |

> **真实机车的平行连杆不是理想平行四边形**：两根连杆向量差一点点，刀片自己会转**几度**
> （实测样例：臂扫 50°、刀片自转 2.1°），所以刮出来的不是纯粹的带，而是一个**微微张开的扇形**。
> 打包器会把"刀片自己转了多少度"直接打进日志 —— 那是静态模型上看不出来的、唯一真正说明
> 机构类型的数字。理想平行四边形（连杆向量完全相等、刀片零自转）是它的退化情形。

两者是同一个模型的两个退化情形 —— 刀片是一条刚体线段，**两端各绕自己的支点转同一个角**：

```
A(θ) = P1 + R(θ)·(A0 − P1)
B(θ) = P2 + R(θ)·(B0 − P2)
```

- **`P1 = P2`**（同一个主轴）⇒ 整条刀片刚性转动 = 单轴
- **连杆向量相等**（`A0 − P1 = B0 − P2`，理想平行四边形）⇒ `B − A` 恒定 ⇒ 刀片零自转
- **连杆向量接近但不等**（真实机车）⇒ 刀片微转几度 ⇒ 刮出**微微的扇形**；这就是现实情形

**建模：各个部件单独独立命名**（打包器按名字直接认，**不用**写 `groupMap`）：

| 名字 | 是什么 | 给了打包器什么 |
| --- | --- | --- |
| `mmtr_wipersweep_<驾驶室>_<玻璃>` | 三角扇 | **P1**（扇心）+ 停放角 + 扫过角 |
| `wiper_<驾驶室>_<玻璃>` | 刀片（实体） | **A0 B0**（主轴拟合出刀片两端） |
| `wiperrod_<驾驶室>_<玻璃>` | 拉杆（平行连杆才有） | **P2**（贴住刀片的那端是接头，另一端就是支点） |
| `wiperarm_<驾驶室>_<玻璃>` | 臂（实体） | 交叉校验用（臂的远端应落在刀片**线段**上 —— 真车的臂是拧在刀片**中间**的，不是端头） |

打包器写出 `bladeAU/AV`、`bladeBU/BV`、`pivot2U/V`（单轴**不写** pivot2），并在日志里打印机构类型、
连杆残差与**刀片自转角**：

```
wiper mechanism: windshield_1_3 two pivots 0.300 m apart, link residual 0.0150 m,
                 blade turns 2.13 deg over the 50 deg stroke -> slight fan - the blade turns 2.1 deg as it sweeps
```

**扇形描述的是"刀片真正扫过的区域"**，不是臂的摆动范围：

- 扇的**两条边界边必须是刀片在行程两端的位置**（所以扇的 rim 要包含刀片在停放位与最大位的两个位置）。
- 扇的**扇心是虚拟中心**（两条刀片极限位置延长线的交点）—— **不是主轴**，单轴雨刷也一样（刀片是**偏离**
  主轴的，穿过主轴的是**臂**）。所以**主轴只能来自 `wiperarm_`**。
- 扇的**开口角 = 刀片自转角**（真实机车只有几度），**臂的行程角由打包器从扇的另一条边界边反解出来**
  （按**位置**匹配：刀片落在哪条边界边上；按角度匹配会被"直线角每 180° 重复"和"连杆刀片方向几乎不变"
  两个陷阱骗到）。
- 所以平行连杆模型**必须**建 `wiperarm_`（主轴来源）与 `wiperrod_`（第二支点来源）。

打包器日志（真实样例）：

```
wiper sweep:     mmtr_wipersweep_1_3 -> pivot u=0.9167 v=-5.1916 arm=7.605m park=90 sweep=2.125deg (swept region, ...)
wiper mechanism: windshield_1_3 stroke solved as 50.00 deg from the fan edge at 92.13 deg (blade on it to within 0.0 mm)
wiper mechanism: windshield_1_3 parallel linkage, blade turns 2.13 deg over the 50.00 deg stroke -> slight fan (real train linkage)
```

（第一行里那个 `pivot u=0.9167 v=-5.19` 是**虚拟中心**，离玻璃 7.6 m —— 正常现象；写进锚点 JSON 的
`pivotU/pivotV` 是**主轴**，不是它。）

**客户端按几何分流**（不是按"有没有建模刀片"）：
- **一个支点** ⇒ 用原来的**扇形**角度判定（刀片穿过支点，扇形是精确的）
- **两个支点** ⇒ 用两次刀片位置之间的**四边形（band）**，在**画布米制 y 向上**的空间里算，
  与雨滴同一空间

> 为什么必须这么分：先写成"有建模刀片就用 band"，结果单轴那块的离线断言挂了 4 个点 ——
> 四边形是**直边**，会切掉转动刀片真正扫过的**弧**（矢高 ≈ R(1−cos)，一个重绘步长下约 1–5 mm）。
> 平移（平行连杆）用四边形是**精确**的，转动不是。所以转动继续用扇形。

**⑥ 一块玻璃上两把雨刷（多雨刷）**

客车/动车组的大风挡常常**一块玻璃两把雨刷**（一对刮臂共用一条水槽）。这是支持的，而且
**不是**"把玻璃拆成两块"：玻璃锚点仍然只有一个，雨刷按**第三个索引**编号。

```
mmtr_windshield_1_1        一块玻璃（1.2 m 宽的那种）
mmtr_wipersweep_1_1        第 1 把的作用面
wiper_1_1 / wiperarm_1_1   第 1 把的实体件
mmtr_wipersweep_1_1_2      第 2 把的作用面（同一块玻璃）
wiper_1_1_2 / wiperarm_1_1_2
```

- **每把雨刷自己一套参数**：各自的支点、停放方向、扫过角、方向、机构类型（一把单轴 + 一把连杆也行）。
  打包器为每把单独拟合一次，写进同一块玻璃的配置块。
- **一块玻璃只有一份雨**：雨滴、湿度、密度、径流这些是**玻璃级**的（`WindshieldConfig` 的字段），
  两把雨刷刮的是**同一片水**；两把的**顺序执行**，第二把看到的是第一把留下的场面。
- **一把刀片带走的水归那把刀片**：客户端给每颗雨滴记了"正被哪把雨刷推着"（`Drop.carriedByWiper`），
  另一把的通过会**完全跳过**它。否则两把的"到端释放"会互相打架，水会在行程中间被放下。
- 配置的两种形状（**打包器自动决定，人不写**）：
  | 该玻璃的扇形数 | 写出来的形状 |
  | --- | --- |
  | 1 个（且索引 = 1） | **扁平字段**，与多雨刷出现之前**逐字节一致** |
  | ≥2 个 | 玻璃级字段 + `"wipers": [ {…}, {…} ]`，每个元素带 `"wiperIndex"` |
  客户端只在 `wipers` 存在时按数组读，否则扁平字段就是**第 1 把**。老包因此**零改动**。
- `wiperIndex` 是**显式写入**的，不靠数组下标：模型只画了第 2 把、或编号不连续（1、3）时，
  下标对齐会**静默**把配置接到另一把刀片上（表现为"这把不动、那把乱动"）。
- 打包器日志会点名：`windshield windshield_1_1: 2 wipers on one glass (wipers 1, 2)`。
  两个扇形抢同一把（重名）会打 WARNING；只有一把扇形但索引不是 1 也照实写出（那把就是唯一一把）。
- 离线判据（`verify_windshield.js`）对**每一把**分别重算支点/半径/停放角/行程，并检查
  "扇形的索引 ↔ 配置块 ↔ 实体件"三者一一对应；扇形的索引与配置块对不上会 FAIL。

## 2. 参数文件（你只需填这个）

模板见 `mmtr/tools/obj-mtr-packager/example/vehicle.template.json`：
```json
{
  "id": "hst",
  "name": "HST (Create Export)",
  "color": "7FA8CC",
  "transportMode": "TRAIN",
  "carLengthBlocks": 15,
  "carWidthBlocks": 5,
  "bogieCount": 2,
  "bogieOffsetBlocks": -5,
  "bogie2OffsetBlocks": 5,
  "couplingPadding1": 0,
  "couplingPadding2": 0,
  "sourceObj": "${MC_ROOT}/assets/models/blender/hst_car/hst_car.obj",
  "textureDir": "${MC_ROOT}/assets/models/blender/hst_car",
  "rotationDegY": -90,
  "recenter": true,
  "groupMap": {
    "body":     ["chunks_merged"],
    "interior": ["BlockEntities"],
    "door_l":   ["chunks_merged.001"],
    "door_r":   ["chunks_merged.002"]
  },
  "doorAnimationType": "STANDARD",
  "doorSlidePx": 14,
  "flipTextureV": true,
  "hudLayout": "${MC_ROOT}/mmtr/tools/obj-mtr-packager/example/br101_hud.json",
  "outputPackName": "HST_hstcar_auto",
  "outputDir": "C:/.../mmtr/game/fabric/run/resourcepacks"
}
```

字段说明：
- `bogieCount` 转向架数量(常见2)；bogieCount=2 且未给 offset 时自动取 ±(车长/2−1.5)；也可用 offset 字段直接覆盖
- `rotationDegY`：游戏导出素材车长常沿 X，需转90°到 Z(车头- Z)。Create 区域导出经验值 **-90**；装车后头尾反→改 +90
- `groupMap`：源 OBJ 的 `o`/`g` 名 → 角色；不匹配的组会跳过并告警
- `doorSlidePx`：STANDARD 门开门位移(像素，16px=1格)；MTR 内置车用 14
- `hudLayout`（或内联 `hud`）：这个车型的仪表画面，见 §1.3⑧；`${MC_ROOT}` 占位符由打包器解析
- `legacyRiderOffset`：**一般不用填** —— 打包器按 `mmtr_seat` 眼位自动算（§1.6）。
  只有在想手动覆盖骑手参考高度时才写（值 = 合成地板 y − 1）
- `untexturedUv`：没有 UV 层的面采哪个纹素，见 §1.5

## 3. 一键打包（固化流程）

```
powershell -File mmtr\scripts\pack-vehicle.ps1 mmtr\tools\obj-mtr-packager\example\vehicle.hst_h.json -Version 13
```

（`mmtr\tools\obj-mtr-packager\pack.bat` 也保留着，但已改成转发到同一个脚本——它以前直接跑打包器、不校验 zip，并踩过 §6 的分隔符坑。）

脚本按顺序做五件事，任一步失败都以非 0 退出——**不会产出"能进游戏但看不见模型"的包**：

1. **配置自检**：json 能解析（显式按 UTF-8 读，否则 Windows PowerShell 5.1 会用 ANSI 读中文配置直接报错）、`sourceObj`/`textureDir` 存在、`node` 在 PATH 上；
2. **打包**：调用 `mmtr/tools/obj-mtr-packager/pack_vehicle.js` —— 按 `rotationDegY` 旋转并 X/Z 居中 → `o`/`g` 改名到角色 → MTL 相对化 + 复制 PNG 到 `assets/mtr/<id>/` → 逐门按门叶包围盒生成 DOORWAY 薄片 → 按门叶下沿高度生成 `floor`（FLOOR 部件，可站可走、不渲染）→ 抽取 `mmtr_*` 具名面写成 `mmtr_anchors_<id>.json` → 生成 `mtr_custom_resources.json` / `properties_<id>.json` / `definition_<id>.json` / `pack.mcmeta`(fmt18) → 写 zip；
3. **zip 校验**：条目名必须**全小写**且用 `/` 分隔；必须存在 `pack.mcmeta`、`assets/mtr/mtr_custom_resources.json`、`assets/mtr/properties_<id>.json`、`assets/mtr/definition_<id>.json`、`assets/mtr/<id>/<id>.obj`、`assets/mtr/<id>/<id>.mtl`、至少一张 `assets/mtr/<id>/*.png`；有锚点时必须有 `assets/mtr/mmtr_anchors_<id>.json`；
4. **锚点报告**：打印锚点表（kind/cab/尺寸 + `panelFlipU`/`panelPxPerMetre`/`panelTwoSided`），hud 多于一个时提示客户端只画乘坐中那节；
5. **命名提示**：流程末尾打印下面的模型命名约定。

`-Version N` 会把 `outputPackName` 改成 `<base>_v<N>`（版本自增、不覆盖旧包）；不传则用配置里的名字。`"floor": false` 关地板、`"doorway": false` 关门洞。

### 3.1 模型命名约定（打包流程与客户端都按这套读）

| 命名 | 角色 | 说明 |
| --- | --- | --- |
| `body` | 车体 | 所有不可动件合并成一个 Object |
| `interior` | 内装 | 走 INTERIOR_TRANSLUCENT（可透视） |
| `door_l_<n>` / `door_r_<n>` | 客车门叶 | 每扇门一个独立 Object，`l`/`r` = 左/右，`n` 从**车头端往车尾端**编号；每扇门自动配一个门洞与独立滑移 |
| `doorway_door_*`、`floor` | 自动生成 | **不要建模**，打包器按门叶包围盒/下沿生成 |
| `mmtr_hud[_<驾驶室>]` | 仪表平面 | 面中心 = 面板中心，法线朝司机；与世界 +Y 最贴合的边为"上"、另一边为"右"；尺寸按米。可调字段见 §1.1 |
| `mmtr_cabdoor_<驾驶室>_<第几扇>` | 司机门 | 既是锚点也是**可见部件**（部件名去掉 `mmtr_`，如 `cabdoor_1_1`） |
| `mmtr_seat_<驾驶室>` | 司机座位/眼位 | 可选；法线 = 行进方向 |
| `mmtr_ack_<驾驶室>` | AWS 确认按钮 | 可选 |
| `mmtr_windshield_<驾驶室>[_<玻璃>]` | 风挡玻璃 | 每块一个 Object；一个索引 = 驾驶室（玻璃默认 1），两个索引 = 驾驶室 + 玻璃。折面/曲面玻璃见 §1.4③ |
| `mmtr_wipersweep_<驾驶室>_<玻璃>[_<第几把>]` | 雨刷作用面 | 三角扇，共享顶点 = 支点；有它 = 这块玻璃有这一把雨刷。同一块玻璃可以有多把（见 §1.4⑥） |
| `mmtr_pid_<驾驶室>[_<第几块>]` | **水牌**（车外目的地牌） | 面中心 = 牌面中心，法线朝**车外**，尺寸按米；客户端画「班次号 + 本趟终点」。同一端可以有多块（两侧各一块，内容相同） |
| `mmtr_next_<驾驶室>[_<第几块>]` | **下一站牌**（车内屏） | 约定同水牌，但法线朝**车内**；客户端画「下一站 X」 |
| `wiper_<驾驶室>_<玻璃>[_<第几把>]` | 雨刷本体 | **可见部件**（无 `mmtr_` 前缀），每把雨刷一个 Object；见 §1.4④ |
| `wiperarm_<驾驶室>_<玻璃>[_<第几把>]` / `wiperrod_<…>` | 雨刷臂 / 拉杆 | 同样每把一个 Object（主轴与第二支点的来源）；见 §1.4⑤ |

驾驶室编号：**1 = A 端（车头端 −Z），2 = B 端（车尾端 +Z）**；不带编号按 1 处理。除 `mmtr_cabdoor_*` 外，`mmtr_*` 面都是**纯数据**（几何会被剥掉）。`wiper_*` 是可见部件，**没有** `mmtr_` 前缀，`groupMap` 必须给它一个角色（见 §1.4④的坑）。

## 4. 验收与调参闭环

**打包后先跑离线判据（全绿才进游戏）** —— 每个判据都对应一类"打包成功但游戏里不对"：

```
node mmtr\tools\obj-mtr-packager\verify_mtr_obj.js      --config <车型.json>   # OBJ 布局/UV 配对/面积/包围盒（§1.5）
node mmtr\tools\anchor-check\verify_facets.js           --config <车型.json>   # 折面 UV 连续性/覆盖/朝向
node mmtr\tools\anchor-check\verify_panel_frame.js      --config <车型.json>   # ★ 按客户端取景数学再验一遍（§1.3⑦）
node mmtr\tools\anchor-check\verify_windshield.js       --config <车型.json>   # 玻璃/雨刷扇形/联动
node mmtr\tools\anchor-check\verify_plane_frame.js      --config <车型.json>   # ★ 客户端能否把二维雨刷运动在模型空间重现（含源码守卫，notes/203）
node mmtr\tools\anchor-check\verify_doors.js            --config <车型.json>   # ★ 门叶是不是**朝开门方向**滑（两叶门同侧同号 = 一叶往门洞里滑，notes/279）
node mmtr\tools\anchor-check\selftest.js                                       # 故障注入：证明上面几个真的有牙
```

面板画面本身用离线预览看，别拿游戏当画布（§1.3⑧）：
`pwsh -File mmtr\tools\panel-preview\preview.ps1 -Anchor <layout.json> -Speed 87 -Limit 100`

> **症状速查**：下面这张表是"改了怎么办"。如果你想的是"**出了这个现象，先量哪个数**"，
> 看 **《MMTR-OBJ车辆-故障谱系与离线判据.md》** —— 它按症状把 notes/187–203 的坑重排了一遍
> （含"整车看不见"的三种独立死法、"雨刷不对"的三种、以及"命令跑完了但东西没变"的流程性坑），
> 并给出每类的可测量中间量与对应判据。

| 现象 | 改法 |
| --- | --- |
| **只有少数几个部件显示，其余全看不见**（日志里组名/锚点全对、无报错） | ★ **MTR 只读一种 OBJ 布局**（§1.5）：`#v==#vt==#vn` + 全三角形 + `f i/i/i`。跑 `verify_mtr_obj.js`；BR101 实例见 notes/194 |
| **按 G 有反应但上不去车 / "四个角都没有地板"** | ★ OBJ 车没有地板盒，MTR 的合成地板在 `y = 1 + legacyRiderOffset`（§1.6）：**重打包**（打包器会自动按 seat 眼位算好）；日志里 `No floors or doorways found in vehicle models` 就是它 |
| **整车消失但日志一切正常** | ① `flipTextureV` 是否为 `true`（Blender 模型一律 true）；② 模型 UV 是否落在贴图**不透明**的那半张；③ 是否大片面没有 UV 层（用 `untexturedUv` 指到不透明纹素） |
| 没有 UV 层的部件（如雨刷）整块不见 | MTR 永远取 UV，缺省就是 (0,0) = 贴图左下角；配 `untexturedUv`（§1.5） |
| 提示不兼容/旧版本 | pack_format：1.20.4=18，1.20.1=15 |
| 材质紫黑 | 贴图放 `assets/mtr/<id>/` **子目录**(别放 mtr 根)；mtl 相对化 |
| 车体全黑 | 导出保留 UV；打包端勿重排顶点/面 |
| 头尾反 | rotationDegY ±90 互换 |
| 贴图颠倒 | flipTextureV true/false |
| 门不动 | 检查是否缺 DOORWAY(自动生成)或门部件未进 properties；确认开门方式(站台/手动键) |
| 门方向反 | doorSlidePx 正负号或 door_l/door_r 换号 |
| 玻璃不透 | 透明 PNG 的部件层设 INTERIOR_TRANSLUCENT；外壁玻璃需独立 part |
| 面板彩色噪点/花屏条纹 | 贴图尺寸不匹配：`upload()` 是 `glTexSubImage2D`，不能改尺寸 → 见 §1.3② |
| 面板被自己的底板盖住 | 底板和文字分了两张贴图/两个 bucket → 画进同一张图（§1.3①） |
| 面板只剩缝隙里的碎块 | 绕序让可见面朝里 → 显式按左下/右下/右上/左上给角点（§1.3③） |
| 面板文字上下颠倒 | `v1/v2` 传反了：`v2` 才是上边缘（§1.3④） |
| 面板文字左右镜像 | 只有模型面"右"边方向与打包器约定相反时才需要 `panelFlipU`；正常不该出现（§1.3⑤） |
| **折面仪表跨折痕撕裂 / 整体镜像** | 打包器自己的展开数字全绿也**照样会**（两套取景坐标系，§1.3⑦）→ 跑 `verify_panel_frame.js`：撕裂 = `mmtr_hud` 各面**绕序**不一致，镜像 = u 没朝司机右手增大。修法是回 Blender 翻面的绕序（BR101 用 `sandbox/br101_r52_hud_winding.py`），别用 `panelFlipU` 硬糊 |
| 折面仪表拿不到 facet（变成一块大平板） | 折面组的锚点法线取错（notes/193）→ 重打包，看打包日志里有没有 `hud facets: ... faces=N` |
| 双端机车只有一端有仪表 | 客户端按该节车的**每个** `mmtr_hud_<驾驶室>` 锚点各画一块（§1.3⑥）；没有说明另一端没写锚点或没进锚点 JSON |
| 不同车型要不同仪表画面 | 在打包参数里给 `hud` / `hudLayout`（§1.1、§1.3⑧） |
| 一列车上好几块仪表 | 每节车都带 `mmtr_hud`（同一模型复用）→ 客户端只画乘坐中的那节（§1.3⑥） |
| 面板完全看不见 | 先看 `[MMTR-DBG] panel` 日志：`registeredGl == textureGl` 说明绑定正常、问题在几何/遮挡（§1.3⑦） |
| **按 J 雨刷完全不动**（档位/状态日志正常） | 几何**没进那次带旋转的绘制**：OBJ 部件默认全进 optimized 主批次，部件级变换作用在空气上（notes/202）→ 确认 `wiper*` 走了 `isMechanism()`/`optimizedModelDoor` 那条路，并看 `[MMTR-WSHLD] wiper part …` 一行 |
| **雨刷会动但"动得不对"**（刀片向下弯/飞出玻璃、杆子飞到天上） | ★ 三个量必须同时对（notes/203）：**轴**（欧拉角要从玻璃基向量 `(right, up, normal)` 的矩阵元取，`pitch=asin(-normal.y())`、`roll=atan2(right.y(), up.y())`，**不能**拿 `up` 的分量猜；倾斜玻璃上差十几度到 68°）、**支点**（矩阵栈是模型空间，先 `plane.pointAt()` 把支点抬到玻璃上；直接平移平面坐标 = 绕模型原点转 → 杆子飞天）、**比例**（配置里 pin/pivot 是 0..1 比例，`pointAt` 要相对**玻璃中心**的米，先 `−0.5`）。跑 `verify_plane_frame.js`；**轴对齐的夹具测不到**（yaw=±90° 处退化，错解与正解同矩阵），所以回归打在客户端源码上 |
| **静止的雨刷不消失、开雨刷又多出一个雨刷** | 机构件仍在 **doors-closed** 那张 optimized 批次里（整车车身画的正是那张）→ 两份几何：停位重合、一动就分开（notes/203 §8）。修法是 `ModelPropertiesPart` 的 OBJ `writeCache`：机构件登记进一张**临时表**、两张批次表都不进（门与普通件行为不变）。★ **别用"干脆不登记"来修**：`addObjModelPosition` 同时负责 `objModel.addTransformation`，而 OBJ wrapper 的几何**只从登记过的变换里生成** —— 不登记会让 wrapper 变空、**雨刷整体消失**。`verify_plane_frame.js` 的源码守卫同时挡这两个互为反面的错 |
| **雨刷整体不见了**（刚改过部件批次相关代码） | 机构件**漏登记**位置 → wrapper 为空（见上一行）。跑 `verify_plane_frame.js`，它会报 `disappears` |

游戏内：Options→资源包启用新包(旧包先关)。按你的常规列车测试流程验收。
★ **音效跟着车包走**：打包器把 `soundBase`/`soundDir` 指到的音效集（ogg + `sounds.json` +
`mmtr_traction.json`）**合并进同一个 zip** ⇒ **一个车型一个包**。**不要**把音效另打一个包让玩家装两个：
同名 ogg 与同一份 `sounds.json` 会被两个包各带一遍，且"这是哪台车的音效"会重新变成一个问题
（`SAF420_v42.zip` 与 `Kei2100_v1.zip` 就是这么并存的，见 notes/402）。

## 5. 版本管理
- 每次输出 `<name>_v<数字>.zip`（`pack-vehicle.ps1 -Version N` 自动改），游戏只启用最新；验收通过后退出游戏再清理旧包(避免占用)。
- **一个车型一个包**（车 + 它自己的音效，见 §4 末）。音效**不是**第二个交付物。
- 几何/贴图改动：Blender 改完重导出→重打包；纯参数(长宽/门/转向架)手改 json→重打包即可，无需重建模。
- 打包脚本自带校验，失败即非 0 退出；**不要**用旧的 `pack.bat` 出正式包（它不校验、且 zip 分隔符有坑）。

## 6. 经验备注
- **打包器自己写 zip，不用 `Compress-Archive`**：Windows PowerShell 5.1 的 `Compress-Archive` 会把条目名写成 `assets\mtr\...`（反斜杠），Java/Minecraft 按资源路径找的是 `assets/mtr/...`，结果**包里文件都在、游戏里一个都读不到**。`pack_vehicle.js` 现在内置 ZIP 写入（deflate + 固定时间戳，条目名恒为 `/`），`pack-vehicle.ps1` 会校验这一点。
- **文件名必须全小写**（OBJ/MTL/PNG 都是）：MTR 解析资源路径时会整体转小写（`CustomResourceTools.formatIdentifierString`），`HST_H.obj` 会被当成 `hst_h.obj` 去找，找不到就**静默读到空串 → 模型不显示**（客户端日志表现为 `loading model ...: obj=0 chars`）。打包脚本已强制小写，源文件叫什么都行。
- **生成的薄片面索引必须加偏移**：OBJ 的面索引是**全文件**顶点序号，脚本自动追加的 `doorway_*` / `floor` 薄片写在文件末尾，索引必须写成 `已有顶点数 + 本薄片内偏移`。少了这个偏移，薄片的面会指向车体开头的顶点，整个部件几何就指到车头去了（2026-09-08 修）。
- 门滑进车身会穿帮：门两侧做成内凹 0.2 格收纳槽，或把门叶做薄贴墙
- translucent 面性能贵，只给玻璃/车窗用
- 一个包可放多辆：vehicles 数组加条目即可
- 产物结构与 MTR4 内置 sp1900 一致，可在游戏内 Creator 二次编辑
- **面板（`mmtr_hud`）的坑都在 §1.3**：一张贴图/一个 bucket、`upload()` 不能改尺寸、绕序朝 −Z、UV 的 v2 是上边、局部 +X 朝司机右手边、只画乘坐中的那节。改面板前先读那一节，验收症状对照 §4 的表。

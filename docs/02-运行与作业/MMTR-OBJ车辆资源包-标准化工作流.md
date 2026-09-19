# MMTR 自定义车辆：Blender→OBJ→资源包 标准化工作流

> 目标：你在 Blender 里把车体/车门模型做对并导出 OBJ，然后**只需提供几个参数**（车辆 ID/名称、转向架(轴)数量、车长、车宽），流程自动把模型整理成 MTR/MMTR 可直接加载的资源包 zip 并放入开发端 resourcepacks。
> 适用：MMTR 开发端（Minecraft 1.20.4 · Fabric · pack_format 18）。核心加载逻辑与 MTR4/NTE OBJ 规范一致，本环境已实测打通：贴图、朝向、门动作、DOORWAY 判定。

## 0. 分工

| 谁 | 做什么 |
| --- | --- |
| 你（建模端） | 在 Blender 里按规范建模并导出 OBJ(+MTL+PNG)；填参数文件；游戏内验收反馈 |
| 打包端（DSH/脚本） | 读参数 → 几何规范化(旋转/居中/分组改名) → 整理贴图 → 生成注册/属性/门洞 JSON → 打包 zip → 放入 resourcepacks |

## 1. Blender 建模与导出规范

坐标系（MTR/NTE 车辆 OBJ 规范）：
- **X 向右**(车宽方向)、**Y 向上**、**Z+ 朝车尾**(车头朝 -Z)
- **1 单位 = 1 米 = 1 格**
- 模型原点放在**整节车几何中心**（X、Z 居中；Y 可取地板处）
- 所有面保留 UV；车窗/玻璃等透明材质 PNG 带 alpha 通道
- 贴图：导出时勾选写 MTL，PNG 与 OBJ 放同一文件夹即可（打包端会处理路径）

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
| `mmtr_wipersweep_<驾驶室>_<玻璃>` | 该玻璃的**雨刷作用面**（三角扇，见 §1.4②） | 车外 | 有雨刷就必需 |

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

### 1.2 游戏内如何使用锚点（B7.6d）

- **瞄准驾驶室门 → 提示按 F**：客户端用 `mmtr_cabdoor_<cab>_<n>` 算出门的实际世界坐标，取视线夹角最小且在 5 m 内的那扇门，屏幕下方提示「按 F 进入 N 号驾驶室」；按 F 即把该驾驶室钥匙交给引擎并**把玩家钉到驾驶室眼位**。
- **眼位怎么来**：优先 `mmtr_seat_<cab>`（若有）；否则取 `mmtr_hud` 的 x/z，沿仪表面**水平法线**（法线指向司机）后退 1.05 m 作为座位点，Y 取门叶下沿（=地板高度）。MTR 每 tick 会把骑乘 Y 吸附到地板顶面，所以真正决定视角的是 x/z。
- **只有一端的模型**：模型只有 1 号驾驶室时，编组另一端（2 号驾驶室）自动按 z 镜像使用同一组锚点（日志里 `mirrored=true`），无需为对称车再画一套。
- **朝向**：司机朝车头方向（车体局部 -Z）；镜像出来的 2 号驾驶室朝 +Z。
- **离开**：对着同一扇门再按 F（或对着车按 F）= 拔钥匙。客户端日志会打印 `[MMTR] cab N of vehicle ... seat car-local (...)`，缺锚点时会打印实际加载到的锚点数量。

Blender 导出：File → Export → Wavefront (.obj)，勾选 Materials / Write Normals / Include UVs。

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
- 锚面帧：解 `rotateY(yaw)·rotateX(pitch)·rotateZ(roll) = [right, up, normal]`（三列分别是锚面的右/上/法线），不再需要任何硬编码 roll；
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
  UV 连续性/覆盖/朝向等八项、`selftest.js` 用故障注入证明验证器有牙）；合成 fixture 在
  `mmtr/tools/anchor-check/fixture/`。

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
- 控件：`speed`（实时 km/h）、`limit`（实时限速，0 时显示 `--`）、`text`（静态文字，`align` = left/center/right）；图形 `rect`/`roundRect`（`w,h,radius`）、`line`（`x,y`→`x2,y2`，`lineWidth`）、`circle`（`radius`）、`arc`（`radius`、`start`/`end` 度、逆时针从 +X 起算）。
- 颜色 `#RRGGBB` 或 `#AARRGGBB`。
- 没有 `hud` 块 → 客户端用默认画面（居中速度 + `km/h`）。

### 1.4 风挡：多块玻璃、雨刷作用面、实体雨刷

> **实现状态（2026-09-18）**：**W1 已实现**（`<驾驶室>_<玻璃>` 命名、`wipersweep` 扇形拟合、
> 实体雨刷部件识别、离线校验全部到位）；**W2 已实现**（按作用面擦拭本来就走 `park → park+sweep`，
> 字段变准后自动生效；"按驾驶室找玻璃"补了 `findWindshields(car, cab)` 与 `findWindshield(car, cab, pane)`）；
> **W3/W4 未实现**（曲面玻璃的展开渲染；实体雨刷**渲染在建模位置但不会转** —— 绕支点旋转要改 MTR 部件渲染）。
> ⚠️ 另：`driverOnBoard()` 目前硬编码 `false`（上下车重构期间，notes/185），所以**在游戏里雨刷暂时不会动，只有雨滴**。
> 实现顺序与理由见 `mmtr/notes/187-风挡多玻璃与雨刷作用面-规范冻结.md`。

一个驾驶室通常有**多块玻璃**（前挡可能还分左右两片，另有侧窗、车门玻璃），每块**各自**下雨、各自有湿度与雨刷。
客户端本来就是"每个锚点一份独立状态"（`findWindshields` 返回该车所有风挡锚点，各自一份雨滴/湿度/画布/贴图），
所以多块玻璃不需要新架构 —— 只需要**命名**和**查找**跟上。

**① 命名与编号**

| 命名 | 含义 |
| --- | --- |
| `mmtr_windshield_<驾驶室>_<玻璃>` | 驾驶室 `<驾驶室>` 的第 `<玻璃>` 块玻璃（**无雨刷**） |
| `mmtr_wipersweep_<驾驶室>_<玻璃>` | 同编号玻璃的**雨刷作用面**（扇形） |
| `wiper_<驾驶室>_<玻璃>` | 雨刷本体（**实体建模**的可见部件，见 ④） |

- **有 `mmtr_wipersweep_<cab>_<pane>` 锚点 = 那块玻璃有雨刷**，名字里不再重复编码这件事。
- 玻璃编号：**主档永远 = 1**，侧窗/其它依次递增。
- **索引规则（向后兼容，务必遵守）**：只写**一个**索引 = **驾驶室号**（玻璃默认 1）；
  写**两个**索引 = 驾驶室号 + 玻璃号。
  所以 `mmtr_windshield_1` = 驾驶室 1 主档、`mmtr_windshield_2` = **驾驶室 2** 主档（老模型照旧），
  `mmtr_windshield_1_2` = 驾驶室 1 的第 2 块玻璃。
  锚点 JSON 里 `pane` **只在不是 1 的时候才写**（缺省 = 1），这样单玻璃模型的老锚点条目逐字节不变。
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
- 打包器**拟合**出下面这些量，写进该玻璃锚点的 `windshield` 配置块（**复用现有字段，客户端不需要新代码**）：

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

**④ 实体雨刷（`wiper_<cab>_<pane>`）**

- 模型里是**独立可见部件**，每个雨刷**一个** Object，名字 `wiper_<cab>_<pane>`。
- **不需要往 `groupMap` 里加角色**：打包器按名字约定直接认它（和门叶一样每个雨刷**独立成件**，
  这样才能各自绕自己的支点转）。这也顺便避开了两个坑：没有 `mmtr_` 前缀的组如果不被识别，
  它的面会**不写 `g` 行却仍写出去**（静默粘到前一个组）；而用一个通用 `"wiper"` 角色会把**所有**
  雨刷并成一个部件、还会和 `mmtr_wipersweep_*` 抢名字。
- 有实体雨刷 ⇒ 该玻璃自动 `drawBlade=false`（客户端不再自己画刀片，否则两个刀片叠在一起），
  但 `wiper` 仍然是 `true`：**玻璃照旧被刮**，实体雨刷只是替代了"画出来的刀片"。
  这两个概念是分开的，别把它们混成一个开关。
- 角度与玻璃上的刮拭**共用同一份动画状态**（就是现在驱动画刀片的那个 `wiperAngleDeg`），
  所以"雨刮动画"和"雨滴被刮掉"天然同步，不存在两套时间。
- ⚠️ **W4 未做**：实体雨刷目前**渲染在建模位置（停放态）但不会转**。OBJ 部件目前的每帧变换只有
  **平移 + 0/π 的 Y 旋转**，绕支点连续转需要给部件渲染加一个动态旋转（并量 optimized 缓存的代价）。
  注意 W4 要做成**通用刚体变换（转角 + 平移）**而不是"绕支点旋转"：平行连杆的刀片是**纯平移**，
  只做旋转的话这一族做不出来。

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

**扇形描述的是臂的行程**（扇心 = 主轴 P1，角度 = 臂扫过的角），刀片自己那几度的微转是**机构算出来的**、
不单独作者输入。所以对平行连杆来说："作用面（扇）」和"刀片真正刮过的区域（微微的扇）」是**两个不同的扇形**，
别混：前者给动画，后者由机构决定。

**客户端按几何分流**（不是按"有没有建模刀片"）：
- **一个支点** ⇒ 用原来的**扇形**角度判定（刀片穿过支点，扇形是精确的）
- **两个支点** ⇒ 用两次刀片位置之间的**四边形（band）**，在**画布米制 y 向上**的空间里算，
  与雨滴同一空间

> 为什么必须这么分：先写成"有建模刀片就用 band"，结果单轴那块的离线断言挂了 4 个点 ——
> 四边形是**直边**，会切掉转动刀片真正扫过的**弧**（矢高 ≈ R(1−cos)，一个重绘步长下约 1–5 mm）。
> 平移（平行连杆）用四边形是**精确**的，转动不是。所以转动继续用扇形。

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
  "outputPackName": "HST_hstcar_auto",
  "outputDir": "C:/.../mmtr/game/fabric/run/resourcepacks"
}
```

字段说明：
- `bogieCount` 转向架数量(常见2)；bogieCount=2 且未给 offset 时自动取 ±(车长/2−1.5)；也可用 offset 字段直接覆盖
- `rotationDegY`：游戏导出素材车长常沿 X，需转90°到 Z(车头- Z)。Create 区域导出经验值 **-90**；装车后头尾反→改 +90
- `groupMap`：源 OBJ 的 `o`/`g` 名 → 角色；不匹配的组会跳过并告警
- `doorSlidePx`：STANDARD 门开门位移(像素，16px=1格)；MTR 内置车用 14

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
| `mmtr_wipersweep_<驾驶室>_<玻璃>` | 雨刷作用面 | 三角扇，共享顶点 = 支点；有它 = 这块玻璃有雨刷。见 §1.4② |
| `wiper_<驾驶室>_<玻璃>` | 雨刷本体 | **可见部件**（无 `mmtr_` 前缀），每个雨刷一个 Object；见 §1.4④ |

驾驶室编号：**1 = A 端（车头端 −Z），2 = B 端（车尾端 +Z）**；不带编号按 1 处理。除 `mmtr_cabdoor_*` 外，`mmtr_*` 面都是**纯数据**（几何会被剥掉）。`wiper_*` 是可见部件，**没有** `mmtr_` 前缀，`groupMap` 必须给它一个角色（见 §1.4④的坑）。

## 4. 验收与调参闭环

| 现象 | 改法 |
| --- | --- |
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
| 双端机车只有一端有仪表 | 客户端按该节车的**每个** `mmtr_hud_<驾驶室>` 锚点各画一块（§1.3⑥）；没有说明另一端没写锚点或没进锚点 JSON |
| 不同车型要不同仪表画面 | 在打包参数里给 `hud` / `hudLayout`（§1.1、§1.3⑧） |
| 一列车上好几块仪表 | 每节车都带 `mmtr_hud`（同一模型复用）→ 客户端只画乘坐中的那节（§1.3⑥） |
| 面板完全看不见 | 先看 `[MMTR-DBG] panel` 日志：`registeredGl == textureGl` 说明绑定正常、问题在几何/遮挡（§1.3⑦） |

游戏内：Options→资源包启用新包(旧包先关)。按你的常规列车测试流程验收。

## 5. 版本管理
- 每次输出 `<name>_v<数字>.zip`（`pack-vehicle.ps1 -Version N` 自动改），游戏只启用最新；验收通过后退出游戏再清理旧包(避免占用)。
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

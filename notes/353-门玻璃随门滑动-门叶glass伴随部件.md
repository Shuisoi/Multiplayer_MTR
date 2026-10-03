# 353 门玻璃随门滑动：`<门叶>_glass` 伴随部件

> 触发：用户 2026-09-30「游戏内玻璃不会跟着门一起打开」——**车头与拖车都有**。
> 相关代码：`sandbox/saf420_door_glass.py`（几何）、`mmtr/tools/obj-mtr-packager/pack_vehicle.js`（部件）、
> `sandbox/saf420_build.py`（UV）、夹具 `sandbox/packager_doorglass_test.py`。

## 现象与根因

MTR 的动画单位是**部件**，而部件由 OBJ 组名决定（`pack_vehicle.js`:
`for(const g of doorGroups) parts.push({names:[g], ...})`）。
SAF420 的玻璃原来**全在独立的 `glass` 物体**里 —— 那是个静止部件（`doorZMultiplier: 0`）
⇒ 门叶滑走、玻璃留在原地。

**不能**靠"把玻璃并进门叶物体"解决：一个部件只能有一个 `renderStage`，而打包器对门叶是
**写死 `EXTERIOR`** 的；改成 `INTERIOR_TRANSLUCENT` 会连门叶一起进 `CUTOUT_BRIGHT`
（全亮、不吃世界光）⇒ 整扇门一眼假。

## 做法

- **几何**：每扇门叶的玻璃片拆成**独立物体** `<门叶名>_glass`（例 `door_l_1_glass`）。
  坐标/UV/材质逐字保留；只把与门叶外表面**共面**的那一层往车里挪 5 mm（消 z-fighting）。
  车头 12 个、拖车 12 个伴随物体；固定窗（风挡 / 客室侧窗 / 司机门与司机侧窗）仍留在 `glass` 里。
- **打包**：`pack_vehicle.js` 里凡是"名字 = 某门叶 + `_glass`"的组，**额外发一个部件**：
  `renderStage: INTERIOR_TRANSLUCENT`、`doorZMultiplier` **继承那片门叶**
  （滑向取自门叶在 `doorSlideByGroup` 里的值 ⇒ **配置一行都不用改**）。
  同时 `isDoorwaySource()` 排除这些组 ⇒ 不会多出 `doorway_<门叶>_glass` 薄片。
- **UV**：`saf420_build.py` 的玻璃那套改成**批量** —— `glass` + 所有伴随物体共用同一张
  `saf420_glass.png` 的格子表（13 个物体一起排格子）；门叶分支碰到玻璃材质的面返回 `None`
  （`set_uvs` 见到 `None` 就保持该面原 UV，不被门叶格覆盖）。

## 判据（离线，全绿）

| 项 | 结果 |
| --- | --- |
| 打包器夹具 `sandbox/packager_doorglass_test.py`（合成模型 + 真 `pack_vehicle.js`） | **9/9 PASS**：伴随件 = `INTERIOR_TRANSLUCENT`、滑向继承门叶、无多余门洞薄片 |
| 真打包（拖车试包 → `sandbox/pack-trial/`） | 40 部件：12 门叶 `EXTERIOR` + 12 伴随 `INTERIOR_TRANSLUCENT`（滑向与各自门叶逐一相同）+ 12 门洞 + floor + 固定玻璃 |
| `verify_doors.js` | **PASS**（拖车与控制车 A 各 12 叶 / 6 门洞） |
| `check_pack.py` | **29/29 PASS** |
| 玻璃 UV | 每块玻璃的 UV 包围盒 = 它那一格的矩形，偏差 **0.0 px**（伴随件里逐块核对） |

## 顺带修掉的两个**工具** bug（都会把**正确**的包判成错的）

1. `verify_doors.js` 的 D3 拿"**模型空间**的 `doorSlideByGroup`"去比"已按 `rotationDegY` 转过的几何"
   ⇒ 本项目 `cab_a` / `cab_b` / `car` **全是 180**，于是 12 个门叶**全被判"反了"**，而实机开门是对的。
   现在先把打包后的坐标**转回模型空间**再判（纯平移不影响判定，只有旋转要补）。
2. `check_pack.py` 的"贴图不透明覆盖率 ≥ 15%"对**玻璃贴图**不成立：`saf420_glass.png` 实测
   2.6%（它就是"整片半透明 + 一圈黑框"，这正是用户要的观感）⇒ 只要包里有这张图，**任何包都过不了**。
   现在只对**覆盖率最高的那张（主体贴图）**照旧 15%，其余（半透明件自己的贴图）只要求
   "不是空图"（≥0.1%）。

## 命名约定（新增，给后续车型用）

`<门叶组名>_glass` = 该门叶的玻璃。必须**独立成件**、名字**以门叶名开头、以 `_glass` 结尾**
（打包器据此自动配对滑向与 renderStage；不要用别的后缀，否则要手写 12 条映射）。
`mmtr_cabdoor_*` 的玻璃**不**拆（司机门不滑动）——它们留在 `glass` 里。

# 355 驾驶室侧窗"没有发黑"：玻璃档 alpha 14 → 140（常量单源化 + 格子表重算 + 对照渲染）

> 用户口径（本轮）：「驾驶室侧窗没有发黑」。
> 结论：驾驶室那 8 块玻璃的格内 alpha 原来是 **14/255 = 5.5% 不透明**，实机读起来就是"没黑"；
> 已改成与客室同档 **140**。**没有改几何、没有改 UV**，只改玻璃贴图上那两格的颜色 + 格子表里的 alpha。

## 1. 之前是什么（实测，不是猜）

`sandbox/saf420_glass_inventory.py assets\models\blender\saf420cab\saf420cab.obj cab --tex ...\saf420_glass.png`：

| 组 | 玻璃 | 块数 | 位置 | 落到的格 | 格内 alpha |
| --- | --- | --- | --- | --- | --- |
| cab | 司机室前侧窗 | 4 | (±1.40/±1.45, 2.34, **z=−8.86**) | `RD 0.805×0.905` | **14** |
| cab | 司机门玻璃 | 4 | (±1.40/±1.45, 2.34, **z=−7.50**) | `RD 0.805×0.455` | **14** |
| saloon | 客室侧窗 / 客室门玻璃 | 8 | — | `RD 0.805×0.705` / `RD 1.005×2.005` | 140 |
| windshield | 挡风玻璃 | 2 | (0, 2.65, z=−9.68) | `SQ 2.555×1.205` | 70 |

贴图取色（每格中心偏内 1 px）：`(0, 0, 0, 14)` —— 就是"黑只剩 5.5%"。
而 `saf420_tex_layout.py` 里原来的注写着 **"用户口径 2026-09-29：前面降 50%、驾驶室降 90%"**，
α14 正是那条口径（140×10%）的实现 —— 但 5.5% 的不透明度在实机/渲染上读起来就是**全透**，
所以用户这一轮把它否掉了。

## 2. 改了什么

| 文件 | 改动 |
| --- | --- |
| `sandbox/saf420_tex_layout.py` | `GLASS_ALPHA_CAB = 14` → **`140`**（与客室同档）；并把 `glass_alpha(group)` **搬到这里**当唯一真源 |
| `sandbox/saf420_build.py` | 删掉本文件里那份 `glass_alpha` **副本**（`from saf420_tex_layout import *` 已经带进来）——两份必然分叉 |
| `sandbox/saf420_glass_cells_alpha.py` | **新**：格子表 `saf420_glass_cells.json` 里每格的 `alpha` 从常量**重算**（`--check` 只查）。改黑度不必再进一次 Blender |
| `sandbox/saf420_glass_alpha_compare.py` | **新**：同一机位渲 3 档黑度（14/70/140）对照图，跑完**必定**把格子表按常量重算并重画，仓库不会停在临时变体上。用法：`$env:BLENDER_EXE="…\blender.exe"; python sandbox\saf420_glass_alpha_compare.py`（机器相关路径不入库） |
| `sandbox/saf420_glass_inventory.py` | **新**：清点一节车的固定玻璃（连通块 → 尺寸/中心/分组/落格），并直接取贴图纹素核对 |

`# 顺序`（改黑度的标准三步，任何一档都一样）：

```powershell
python sandbox\saf420_glass_cells_alpha.py     # ① 格子表 alpha ← 常量
python sandbox\saf420_glass_tex.py             # ② 重画 saf420_glass.png（自带逐格自检）
pwsh -File mmtr\scripts\pack-consist.ps1 mmtr\tools\obj-mtr-packager\consist\saf420.json   # ③ 重打包
```

## 3. 判据

* **贴图级**：`saf420_glass_inventory.py --tex` 逐格取色 = 常量（cab 140 / saloon 140 / windshield 70）✓
* **贴图自检**：`saf420_glass_tex.py` 逐格 PASS（框宽 8 px、中心色 = 玻璃色、圆角、框不溢出）✓
* **包对照**（v31 → v32）：车辆定义 / 锚点（含 windshield 块）/ parts / OBJ 组与顶点数 **逐项无变化**，
  只有 `assets/mtr/saf420cab_*/saf420_glass.png`（15164 B，sha `dd0a486c…`）变了 ✓ —— 也就是"只动了颜色"
* **雨刷/门/灯**：`verify_windshield` / `verify_doors` / `verify_lights` / `check_pack.py` 仍全 PASS ✓
* **审美**（技能里的分工：要"好看"用渲染）：`sandbox/.preview/glass_cab_{014,070,140}_eevee_{sideL,3q,inside}.png`
  —— α14 时车头两扇侧窗是浅灰（透出内装），α140 与客室一样发黑 ✓

## 4. 留档（未动的地方）

* **挡风玻璃仍是 α70**（比客室浅一档）：那是"司机视线"的取舍，用户这一轮没提。
  要一起改就改 `GLASS_ALPHA_WINDSHIELD` 一处，再跑上面三步即可。
* 客室玻璃（含门玻璃）一直是 140，未动。

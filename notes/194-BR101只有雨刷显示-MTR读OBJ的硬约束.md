# 194 · BR101「只有雨刷显示」的真因：**MTR 读 OBJ 的方式与 Blender 导出的不是一回事**

## 0. 症状

`BR101_v2.zip` 进游戏后：**只有雨刷（和雨刷臂/拉杆、雨刷电机）显示，车体/内装/司机门全都没有**。
客户端日志一切正常 —— 模型加载成功、23 个组名全在、`properties` 与 OBJ 组名完全吻合：

```
[MMTR-DBG] loading model mtr:br101/br101.obj: obj=983559 chars, mtl=438 chars,
            groups=[body, cabdoor_1_1, ..., wiper_2_2, wiperarm_1_1, ..., floor]
[MMTR-DBG] model mtr:properties_br101.json objGroups=[...同一批...] renderConditions=1
```

**没有报错**，所以这不是"资源没找到"，是"读了、但读出来的东西不是我们写的东西"。

---

## 1. 真因：MTR 的 OBJ 加载器有两条硬假设（反汇编得到，不是猜）

`mmtr/game/libs/Minecraft-Mappings-fabric-1.20.4-0.0.1.jar` 里
`org/mtr/mapping/render/obj/ObjModelLoader`（底下用 `de.javagl.obj`）反汇编后是这样：

```java
for (int i = 0; i < obj.getNumVertices(); i++) {
    vertex = obj.getVertex(i);
    normal = i < obj.getNumNormals()   ? obj.getNormal(i)   : ZERO3;
    uv     = i < obj.getNumTexCoords() ? obj.getTexCoord(i) : ZERO2;   // (0,0)
}
for (each face) new Face(new int[]{ f.getVertexIndex(0), f.getVertexIndex(1), f.getVertexIndex(2) });
```

也就是说：

| # | MTR 的假设 | Blender 的导出 | 后果 |
|---|---|---|---|
| 1 | **顶点第 i 个 = UV 第 i 个 = 法线第 i 个**（面里写的 `v/vt/vn` 三元组**从来不读**） | 顶点共享、`vt`/`vn` 各自一套索引空间（BR101：**v=10482, vt=2568, vn=3092**） | 第 2568 号之后的顶点 UV 全变成 **(0,0)**；之前的顶点拿到的是**别人的 UV** |
| 2 | **一个面只读前 3 个角** | 四边形和 n 边形原样保留 | 四边形丢掉第 4 个角，n 边形变成乱三角 |

`getTexCoord(i)` / `getNormal(i)` 里的 `i` 就是**顶点下标**（不是面的 `vt`/`vn` 下标）——
反汇编里那两个 `iload 14`（循环变量）就是证据。

### 为什么一直没暴露

MTR 自带的示例模型都是 **Create 导出**：**本来就是三角形**，而且贴的是
**整张不透明的 Minecraft 方块贴图** —— 垃圾 UV 落在哪儿都是不透明像素，于是"能用"。
BR101 是这里第一个用**真正的镂空涂装贴图**（`br101.png` 256×256，**58% 的像素 alpha=0**）的模型，
同一个缺陷就变成了"整车看不见"。

### 症状为什么恰好是"只有雨刷"

两个缺陷叠加出来的：

1. **UV 索引塌陷**（上表 #1）：顶点号 ≥ 2568 的角 → UV (0,0)。雨刷、雨刷臂/拉杆、雨刷电机
   **整体**都落在 2568 之后 → 它们的每一个角都是 UV(0,0) → 采到贴图**左上角那个像素**；
2. **`flipTextureV` 写成了 `false`**：MTR 的公式是 `v = flip ? 1 - vt.y : vt.y`，
   而 Minecraft 的贴图 **v=0 是顶行**、Blender 导出的 `vt` 是 **v=0 在底部** ——
   所以 Blender 模型必须 `flipTextureV: true`（HST_H / p1 / saf101 **全都是 true**）。
   `false` 时整张贴图上下颠倒 → 车体的 UV（Blender 里 v∈[0.547,1]，即图片**顶部 46%**）
   被采到**底部 54%** —— 而那里**全是 alpha=0**。

于是：**雨刷落到左上角像素（v 颠倒后仍然不透明）→ 显示；车体落到透明区 → 全被抠掉。**

实测（脚本按 MTR 的读法重算每个组的 alpha）：

```
（v2，flip=false）        meanAlpha  alpha=0 的比例
  body                     8.0        96.9%
  interior                 0.0       100.0%
  cabdoor_*                0.0       100.0%
  wiper_/wiperarm_/wiperrod  255.0     0.0%   ← 只有它们活着
```

---

## 2. 修法（三处，缺一不可）

### 2.1 打包器新增 `toMtrObj()`：把 OBJ 重写成 MTR 唯一读得懂的布局

`mmtr/tools/obj-mtr-packager/pack_vehicle.js` 最后一步（写盘之前）：
**去焊接 + 三角化**，产出 `#v == #vt == #vn`、每个面 `f i/i/i`、且全是三角形的 OBJ。

- 每个面的每个角 → 一条独立的 (position, uv, normal) 三元组（按三元组去重，不炸体积）；
- 多边形用**耳切法**（在最佳拟合平面里投影后耳切，退化多边形退回扇形）——
  不是简单扇形，因为模型里有非凸 n 边形；
- 自带**自检**：三条不变量任一不满足就**抛异常**（宁可打不出包，也不要打出"游戏里少半辆车"的包）。

BR101 实测：9154 个面 → **19036 个三角形**，**35050** 个三元组（去重后），v/vt/vn 三表等长 ✓。

> ⚠️ **这条对所有模型都生效**，包括 HST_H / saf101 / p1。它们原本"能用"是因为运气（三角形 + 不透明贴图），
> 重写后语义不变、UV 变准（它们的四边面也会被正确三角化）。三个真车模型都跑了
> `verify_mtr_obj.js` 回归（见 §3）。

### 2.2 没有 UV 的面：`untexturedUv`（新配置字段）

MTR **永远**会为每个顶点取一个 UV，所以"这个面没有 UV 层"这件事在 MTR 里不存在 ——
没有就只能采 (0,0)。BR101 的**雨刷整套（wiper_/wiperarm_/wiperrod_）和雨刷电机在 Blender 里
根本没有 UV 层**（源文件里是 `f 9235//2483` 这种 `v//vn` 形式）。

新增配置字段：

```jsonc
"untexturedUv": [0.407843, 0.568627],   // BR101：贴图里的一块深灰（雨刷该有的颜色）
```

打包时把"没有 UV 的角"指到这个**纹素**；不配则维持旧的 (0,0)。BR101 取的是贴图上
rgb(58,62,66) 那块深灰（图片 x≈104, y≈110）。
打包日志会明说：`mtr obj: faces with no UV will sample texel (…)`。

### 2.3 `flipTextureV: false` → **`true`**

BR101 是仓库里唯一一个 `false` 的车型（`vehicle.br101.json`），改成 `true` 与其余车型一致。
**判据**：贴图的 alpha 分布 + 模型 UV 的 bbox —— 模型的 UV 在 Blender 里落在图片**上半部**（不透明区），
`false` 会把它们翻到下半部（透明区）。

### 2.4 顺带：尺寸与朝向（notes/193 已记）

`carLengthBlocks` 31 → **32.3729**、bogie ±8.9 → **±9.2797**；朝向由 notes/192 的导出轴向修正保证。

---

## 3. 新增离线判据：`verify_mtr_obj.js`（"MTR 读到的是不是模型写的东西"）

`mmtr/tools/obj-mtr-packager/verify_mtr_obj.js` —— 这是本次唯一能**在进游戏之前**抓住这个 bug 的工具。
它按 MTR 的读法重新解析打包后的 OBJ，并与源模型对照：

| 层 | 判据 |
|---|---|
| **L1 布局** | `#v == #vt == #vn`；每个面恰好 3 个角；每个角引用是 `i/i/i` |
| **L2 配对** | **MTR 会配出来的 (位置, UV) 对，源模型里必须真的写过**（1 mm / 1e-3 容差网格查找，避免 6 位小数的舍入边界误报）。源里没有 UV 的角（打包器会补一个）单独记账 |
| **L3 面积** | 逐组：打包三角形的总面积 == 源多边形的总面积（±0.5%）→ 抓"丢角 / 三角化错" |
| **L4 包围盒** | 逐组：与源一致 |

**喂给它坏包会红**（真值注入，不是"读一遍没报错"）：

```
FAIL (3)
  ! L1 layout: v/vt/vn counts differ (9706/2568/3092) - MTR welds them by index, so every
    vertex past #vt silently samples uv (0,0)
  ! L1 layout: 8738 face(s) are not triangles - MTR reads only the first 3 corners
  ! L1 layout: 37343 corner ref(s) are not `i/i/i` - MTR ignores the face triple
```

三个真车模型回归：**BR101 / HST_H / SAF101 全 PASS**。

---

## 4. 最终产物与验收

```
mmtr/game/fabric/run/resourcepacks/BR101_v3.zip      8 条目，全小写、全 "/"，324 KB
  mtr_custom_resources.json: length 32.3729 / bogie ±9.2797 / flipTextureV true
  br101.obj: v=vt=vn=35050，19036 个三角形，全部 f i/i/i
```

| 检查 | 结果 |
|---|---|
| `verify_mtr_obj.js` | **PASS**（56928 个角与源模型逐一对照） |
| `verify_windshield.js` | **PASS**：4 玻璃 / 4 拟合作用面 / 4 实体雨刷 |
| `verify_facets.js` | **PASS**：2 hud 锚点（折面数据齐全） |
| `selftest.js` | **PASS 21/21** |
| `check-paths.ps1` | OK |
| 按 MTR 的读法重算每个组的 alpha | **全部组 meanAlpha ≈ 255、alpha=0 比例 0%**（v2 时 body 是 96.9%） |

**游戏内验收**：关掉 `BR101_v1`/`BR101_v2`，只启用 **`BR101_v3`**。
应能看见车体、司机门、雨刷、仪表三块折面板；雨刷按键（J）刀片会扫（W4 未做则停在建模位）。

---

## 5. 教训（写进工作流 §1.5）

1. **"OBJ 是通用格式"是错的** —— MTR 只读它自己能读的那一种布局，而且**不报错**。
   凡是"资源都在、日志也正常，但部件看不见/贴图错位"，先跑 `verify_mtr_obj.js`。
2. **`flipTextureV` 不是口味问题，是坐标系问题** —— Blender 导出 = v 向上，
   Minecraft 贴图 = v 向下，所以 Blender 模型必须 `true`。
   判断依据永远是：**贴图的 alpha 分布 vs 模型 UV 的 bbox**。
3. **"没有 UV"在 MTR 里不存在** —— 一定会采一个 UV；用 `untexturedUv` 明确指定，
   否则就是静默地采 (0,0)。
4. **两个独立的静默缺陷可以叠加出一个非常具体的症状** ——
   单看"只有雨刷显示"很容易往"部件名/渲染层"上想，而真因是
   "UV 索引塌陷（决定哪些部件还能采到不透明像素）× 贴图上下颠倒（决定哪些部件被抠掉）"。

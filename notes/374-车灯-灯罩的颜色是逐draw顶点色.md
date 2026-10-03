# 374 · 车灯（五）：灯罩的颜色是**逐 draw 的顶点色** —— 一档 = 一种灯，以及"材质把颜色盖掉"那个坑

日期：2026-10-03 · 承接：**notes/373**（尾灯照不亮自己的灯罩：标志灯自发光染色）· 代码：
`MmtrLightSwitch.java`、`PartCondition.java`、`VehicleResource.java`、`OptimizedRendererWrapper.java`、
`MmtrDrawColor.java`、`BatchManagerRenderCallMixin.java`、`MmtrHeadlights.java`、`MmtrLightField.java`、
`mmtr_vehicle_light.fsh/.vsh`、`mmtr_headlight.glsl`（+ sodium 副本）、`tools/obj-mtr-packager/pack_vehicle.js`、
`consist/saf420.json`

**用户口径（原话，按时间顺序）**：

1. 「依旧混乱，不能靠猜了，情况是这样的，一般这样的动车组的驾驶室都会在编组中分为 A 和 B 用以指代驾驶室 1，2，
   二者有不同的命名方法，又具有不同的灯光属性，实际上动车组只有**近光，远光，尾灯（红光）**三种。
   所以这并不难设置，仅需**单独赋予属性，然后识别渲染**就行了」
2. 「**不用切，直接改颜色就行了**，不用那么复杂」（问的是"灯面怎么切三格"）
3. 「红了，而且有光，但是**灯本身不是发亮的**」
4. 「现在是和模型的白色混合了感觉像是**粉色**，而且不够亮」
5. 「**就这样（纯饱和红）**」（在"推向白 / 加光晕 / 纯饱和红"三条路里选的）

**一句话结论**：灯罩的颜色不需要任何"染色数学"—— MTR 的优化渲染器**本来就有**逐 draw 的颜色通道
（`OptimizedRenderer.queue(model, pose, color, light)`），我们只要把颜色算出来传进去、让 MTR 自己画。
但这条通道在 MTR 4.0.5 里**从来没有生效过**：一次 draw 会 apply 两份顶点属性状态，**材质那份在后面**，
而它的颜色**永远不是 null**（MTL 的 `Kd`，缺省也填白）⇒ 传进去的颜色每次都白被盖掉。
修法是 `BatchManagerRenderCallMixin` 在材质 apply **之后**把我们那份颜色写回去（`MmtrDrawColor`）。

---

## 1. 口径：一档 = 一种灯

用户在 2026-10-03 把"日间/夜间"这个**亮度档**口径改成了**灯种**口径：动车组只有三种灯，一个档位就是一种灯。

| 档位 | 值 | 灯罩 | 光束（`MmtrHeadlights` 的加性项） |
|---|---|---|---|
| `MmtrLightSwitch.OFF` | 0 | 暗玻璃（`#3A3E44`） | 无（只有机车能选到这一档） |
| `MmtrLightSwitch.TAIL` | 1 | **红**（`#FF2A1E`） | 短射程红（`TAILLIGHT_RANGE_SCALE`），地形那条路跳过 |
| `MmtrLightSwitch.LOW`（旧名 `DAY`） | 2 | 暖白（`#FFF4E0`） | `headlightLux × headlightDayRatio` |
| `MmtrLightSwitch.HIGH`（旧名 `NIGHT`） | 3 | 冷白（`#E8F4FF`） | `headlightLux` |

* **数值没动**（2 / 3 照旧）⇒ 协议、镜像、旧存档都不受影响；改的只是名字与文案（`label()` 报"近光/远光"）。
* 自动运行（notes/372 那条 AI 拨灯）不变：白天 = 近光、夜里 = 远光、车尾 = 尾灯（`autoHeadlightState`）。
* 两个白灯档的颜色**故意不同**（暖白 / 冷白）：用户要的"三种灯各自可见"在灯罩上就能看出来，
  不必等他去比对光束。

## 2. 逐 draw 的颜色：通道是现成的，只是被材质盖掉

### 2.1 通道长什么样（全部从字节码读出来，不是推测）

| # | 事实 | 位置 |
|---|---|---|
| 1 | `OptimizedRenderer.queue(model, pose, color, light)` 的 `color` 被装进 `VertexAttributeState(color, light, matrix)` | `OptimizedRenderer.queue` |
| 2 | `COLOR` 是 `VertexAttributeSource.GLOBAL` ⇒ **每个 draw 一个常量**（不是逐顶点数据），正好对上"一组几何 = 一条灯" | `OptimizedModel.DEFAULT_MAPPING` |
| 3 | 颜色分量顺序是 `(c>>24, c>>16, c>>8, c&255)` ⇒ **打包口径是 RGBA（0xRRGGBBAA）**，不是 MC 惯用的 ARGB。同一口径的证据：`ObjModelLoader.mergeColor(r,g,b,a) = (r<<24)\|(g<<16)\|(b<<8)\|a` | `VertexAttributeState.apply()` / `ObjModelLoader.mergeColor` |
| 4 | **一次 draw 会 apply 两份状态**：① 本次 draw 自己的（我们的颜色在这里）② **材质那份**（`VertexArray.materialProperties.vertexAttributeState`），而且在**后面** | `BatchManager$RenderCall.draw()` |
| 5 | 材质那份的颜色**永远不是 null**：`Kd` 存在就按 `Kd×255`，不存在也照样 `mergeColor(255,255,255,255)` = 纯白。SAF420 的 MTL **没有 `Kd` 行** ⇒ 白 | `ObjModelLoader` |
| 6 | 于是第 ② 步每次都把我们算好的颜色**盖成白色** | 现场：`染色draw` 计数在涨（说明 draw 排进去了）、画面全白 |
| 7 | 那份状态用的是 `blendFuncSeparate(SRC_ALPHA, ONE_MINUS_SRC_ALPHA, ONE, ONE_MINUS_SRC_ALPHA)`（**普通 alpha 混合，不是加法**）⇒ 我们的 `alpha=1.0` 输出就是**纯替换**，不会与车体混色 | `MaterialProperties.setupCompositeState` |

### 2.2 修法

| # | 改动 | 为什么 |
|---|---|---|
| 1 | `PartCondition.MMTR_LAMP`（打包器的 `PART_CONDITIONS` 同步加） | MTR 自带的条件只认**方向**（`ON_ROUTE_FORWARDS/BACKWARDS`），而 MMTR 的灯是**状态驱动**的（同一块灯罩既当近光远光、也当尾灯）⇒ 需要"这组几何是灯罩"这个身份，才能只在它身上染色。写成方向条件的后果实测过两次：车往另一端跑时**两端灯罩一起消失**（notes/373 §9） |
| 2 | `VehicleResource.queue` 里按 `MMTR_LAMP` 传颜色（其余部件传 `ARGB_WHITE` = 不染） | 颜色由 `MmtrHeadlights.lampColor(vehicle, carNumber)` 算：**这一节车的灯罩在哪一端**（锚点几何 `engineEndOfSeat`）+ **那一端的档位**（`MmtrLightSwitch.lampState`，与光束同源）⇒ 灯罩颜色与灯光束不可能对不上 |
| 3 | `MmtrDrawColor.reapplyIfTinted(state)` + `BatchManagerRenderCallMixin` 的 `@Redirect`（材质那份 `apply()`，`ordinal = 1`） | **本轮的根因**：材质之后把我们那份颜色写回 GL。只在"我们要求过非白颜色"时动手 ⇒ 别的车底/别的包靠 `Kd` 染色的老行为不受影响。`ordinal = 1` 指名"第二份 apply"：MTR 若改了顺序，这个 mixin 会**当场注入失败**（`defaultRequire = 1`）而不是静默失效 |
| 4 | 灯罩走**顶点色的 alpha 当标志位**（`LENS_ALPHA = 250/255`），而不是再加一个 uniform | 逐 draw 的 uniform 是**按 program 存**的：一次灯罩 draw 写下的值会一直留到同一个 program 的下一次 draw，**原版实体、我们自己别的绘制都吃同一份 program** ⇒ 漏清一次就把别人染了。顶点色跟着几何走，没有生命周期问题 |
| 5 | `mmtr_vehicle_light.fsh`：灯罩**自发光**（`color = vertexColor.rgb × (0.55+0.45×反照率亮度)`，α 置 1）+ **不吃雾**（`lampLensFog`）；`vsh`：灯罩色不吃漫反射 | 用户口径 3「灯本身不是发亮的」。自发光 = 亮的直接是灯色，不吃环境光/漫反射/光场；不吃雾是因为"夜里隔很远该看得见尾灯" |
| 6 | **删掉** notes/373 那条"着色器在半径内把灯罩染红"的路：`mmtrMarkerGlow`、`mmtrMarkerGlowM` uniform + properties 键、`Pos.w` 的符号标志位、`headlightMarkerGlowM` 读口 | 那条路要**同时**凑齐一整套条件才生效（着色器是最新的、program 认得那几个 uniform、灯挤进 8 个槽位、灯罩离相机在射程+64 m 内）⇒ 现场表现是"一会儿红一会儿不红"。用户口径 2：**不用那么复杂** |
| 7 | 诊断（留在代码里）：5 秒行加 `灯罩=<车>#<节> 端=<A/B> 档=<档> → <颜色名>`、`染色draw=N`、`传统灯罩draw=N`；`vehicle queue` 行改成列出**哪些条件真的有几何** | 这一轮真正花时间的不是数学，而是"颜色算对了没有"与"这个颜色有没有真的画上去"**两件事分不开**。见 §4 |

## 3. 实机证据（2026-10-03 15:2x–16:0x，客户端每次改动后重启）

```
# 修复前（颜色被材质盖掉：画到了、颜色不对）
[MMTR-LIGHT] 车灯：画到的车=2 锚点=4 候选=0 上传=0/8 灯光=A端=近光 B端=尾灯 灯罩=saf420cab_a#0 端=A 档=近光 → 近光=白 …
[MMTR-DBG] vehicle queue: 2/11 条件有几何 [NORMAL,MMTR_LAMP] doorsClosed=true 灯罩色=#FFFFFFFF model=saf420cab_a
      ↑ MMTR_LAMP 那组几何**在**、要传的颜色也**对**（#FFFFFFFF = 当时的纯白近光）—— 但画面上尾灯还是白的

# 修复后（同一行读数，颜色真的到了 GL）
[MMTR-LIGHT] 车灯：画到的车=2 锚点=4 候选=2 上传=2/8 灯光=A端=远光 B端=尾灯 灯罩=saf420cab_b#9 端=B 档=尾灯（开关=尾灯）→ 尾灯=红 染色draw=29 传统灯罩draw=0 …
[MMTR-DBG] vehicle queue: 2/11 条件有几何 [NORMAL,MMTR_LAMP] doorsClosed=false 灯罩色=#E8F4FFFA model=saf420cab_a
      ↑ 颜色带 alpha=FA（灯罩标志位）；用户口径：「红了」

# 最终定案（去掉白心、gain=1.0、不吃雾）
[MMTR-LIGHT] … 灯罩=saf420cab_a#0 端=A 档=远光（开关=远光）→ 远光=冷白 染色draw=587 传统灯罩draw=0 …
```

三个读数各自钉住一件事：`灯罩=` 说明**颜色算对了**（端 + 档位）、`染色draw=` 说明**带颜色的 draw 真的排进去了**、
`传统灯罩draw=0` 说明**没有走那条吃不到颜色的老路**（`ModelPartExtension.render` 没有颜色参数，走它一定是白的）。

## 4. 复盘：为什么这一轮绕了这么久

灯不红这件事有**三条互不相干**的路都能造成它，而修复前我们**一条读数都没有**：

1. **颜色算错**（端号或档位判错）—— 有 5 秒行，但只有"候选/上传"的读数；
2. **颜色算对了，但没交到那条 draw 上**（部件条件不是 `MMTR_LAMP`；或几何根本不在被画的那张表里）——
   `vehicle queue: 0/11 part conditions have optimized geometry` 这行**只报总数**，看不出是哪一组；
3. **颜色到了 GL，但被材质盖掉** —— 完全没有读数（`queue()` 是延迟执行的，日志里看不到）。

用户口径 1 说的"不能靠猜"正是这个：**加读数**比再加一层渲染技巧便宜得多。本轮的三个读数
（灯罩颜色 / 染色 draw / 传统灯罩 draw）加上把 `vehicle queue` 那行改成列出**有几何的条件名**，
把 2、3 两条直接变成可读的。

## 5. "不够亮"这件事的物理上限（用户最终选了纯饱和红）

* 灯罩格的贴图实测 `(203,204,200)` ≈ **0.80 灰**（`saf420.png` 的灯罩格）—— 所以"贴图 × 灯色"那版
  天生只有 80% 亮度；自发光那版**直接给灯色**（上面 `0.55 + 0.45 × 反照率` 的因子在 0.80 时已经饱和到 1.0）。
* 在 MC 这种 LDR 画面里，**饱和红 `(255,42,30)` 就是能到的最亮的红**（红通道已经 1.0）。
  想让它"更亮"只有三条路，代价都实测过：
  1. **整块推向白**（`lampLensGain` 1.5 / 2.5、或"白心" `lampLensCore`）⇒ 红变浅 ⇒ 用户口径 4
     「和模型的白色混合了感觉像是粉色」；
  2. **灯罩外加一圈光晕**（bloom 的代用品）⇒ 用户 2026-10-03 明确「不需要红晕」，**没做**；
  3. **把红本身调浅一档**（如 `(255,70,48)`）⇒ 比纯红亮但偏粉。
* 最终用户选了 **1 = 纯饱和红**（口径 5）。`lampLensGain` 留着当不重启就能调的旋钮（1.0 = 灯色本身）。
* ★ 顺手删掉的是"白心"（`lampLensCore`）：它的判据是"片元法线是否正对相机"，
  而灯罩是一块**正对相机的平面** ⇒ 整块罩子都满足 ⇒ 等于整块推向白 ⇒ 现场就是"发粉"。
  要真做白心必须能区分灯罩上的"中心/边缘"（需要灯罩自己的空间坐标，例如 UV），当前判据做不到 ——
  **做不到的事不要留在代码里**。

## 6. 留给下一次的

| 事项 | 说明 |
|---|---|
| 光晕（bloom 代用品） | 唯一能在不洗白的前提下让灯"看起来更亮"的做法：在灯锚点处画一个正对相机的径向渐变 quad，加性叠加，半径/强度热调。用户 2026-10-03 否掉了，但这是唯一剩下的路 |
| 其它车底 | 只有 SAF420 用了 `MMTR_LAMP`；BR101 等还沿用上游的 `ALWAYS_ON_LIGHT + ON_ROUTE_FORWARDS` 配对口径（它们的灯罩是**两块几何**、靠方向条件选，跟 MMTR 的状态驱动灯不是一个模型） |
| 近光/远光的**光束**差异 | 现在两档只差强度（`headlightDayRatio`）；真车的远光更远更聚。要做得像，得给两个锚点各自的锥角/射程——锚点命名里没有"灯种"信息，需要模型里加锚点 |
| 光影包下的自发光 | 包（Iris）走的是包自己的 gbuffers，`mmtrLampFog` 那类 uniform 在包里不存在 ⇒ 灯罩只有**顶点色**那一层（红是对的，自发光/不吃雾没有）。日志里 `光影车灯=… gbuffers_entities_glowing.fsh` 就是那条路 |

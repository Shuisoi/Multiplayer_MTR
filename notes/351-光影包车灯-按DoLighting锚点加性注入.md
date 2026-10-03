# 351 — 光影包里的车灯：按 `DoLighting(color, …)` 锚点加性注入

> **一句话**：光影包开着时，车厢与世界方块都由**包自己的程序**画（MTR 的 `mmtr_vehicle_light` 与
> Sodium 的 `blocks/block_layer_opaque` 一个都不参与）⇒ 车灯在那条路上**根本不存在**。
> 这一轮把车灯的加性项注入进包的 `gbuffers_terrain*` / `gbuffers_entities*` **片元**着色器，
> 并在 Iris 绑定 program 时把灯表传上去。
>
> 落地状态（2026-09-29）：**离线全绿**（探针 41/41 + 真实 dump 编译链接），**实机待确认**（见 §7）。

上游：notes/345 §5 第 3 条边界（"光影包开着时本版车灯不生效，要接就走 notes/344 §17 的注入路"）、
notes/344 §17.24（片元阶段注入的机制与锚点选择）。

---

## 1. 为什么不能照搬无光影那两条路

| 路 | 无光影时谁画 | 有光影包时谁画 | 车灯挂在哪 |
| --- | --- | --- | --- |
| 车厢 + 3D 钢轨 | MTR 的 `mmtr_vehicle_light`（我们的 program） | 包的 `gbuffers_entities*` | 我们要注入包源码 |
| 世界方块 | Sodium 的 `blocks/block_layer_opaque.*`（已整体替换） | 包的 `gbuffers_terrain*` | 我们要注入包源码 |

两条路上"车灯"的**数学**是同一份（`assets/minecraft/shaders/include/mmtr_headlight.glsl`，
notes/345 §6 第 2 条定的规矩：只有一份，观感才不会各自漂），差别只在于**把 `mmtrHeadlightTerm`
摆在哪一行**。所以这一轮做的事本质上是"再找一个正确的摆放位置"，不是重写光照。

---

## 2. 锚点为什么是 `DoLighting(color, …)` 之后

Complementary Reimagined 在 **gbuffers 阶段**就把光照算完了（不是 deferred）：

```glsl
// program/gbuffers_terrain.glsl （片元段）
    vec3 playerPos = vertexPos;                                   // 235：相机相对世界坐标
    vec2 lmCoordM = lmCoord;                                      // 245：← notes/344 piece F 的锚点
    vec3 worldGeoNormal = normalize(ViewToPlayer(geoNormal * 10000.0));  // 247：世界法线
    …
    DoLighting(color, shadowMult, playerPos, viewPos, lViewPos, geoNormal, normalM, dither,
               worldGeoNormal, lmCoordM, noSmoothLighting, …);   // 329-331：← 本轮的锚点
    …
    gl_FragData[0] = color;                                       // 374：写进 colortex0
```

`DoLighting` **之前** `color` 是反照率，**之后**是"反照率 × 光照"。于是：

- **在它之后**加一项 = 纯粹的"再多一点光"，包自己的光照曲线 / 阴影 / AO / 雾一个都不动
  —— 与 Sodium 那条路的 `diffuseColor.rgb += mmtrLight;`（notes/345 §6）**逐字同构**；
- **反照率必须在它之前**捕获（Java 侧在锚点行前面插一行存进局部变量）：之后那个值在隧道里
  是全黑的，拿它当反照率再乘一次灯，灯永远是黑的（Sodium 那条路踩过同一个坑）。

三个入参都是**包已经算好的**，白送：

| 参数 | 包里叫什么 | 口径 |
| --- | --- | --- |
| 相机相对世界坐标 | `playerPos` | 世界 − 相机，与包的 held light 同一个量 |
| **世界**法线 | `worldGeoNormal` | `ViewToPlayer(geoNormal * 10000)`；**不是**漫反射用的视空间 `normalM` |
| 反照率 | DoLighting 之前的 `color.rgb` | 已乘 `glColor`，未乘光照 |

> 为什么不改 `lmCoordM`（= 复用 piece F 那条路）：光照贴图只有方块光/天空光**两个标量**，
> 表达不了"锥形 × 方向 × 颜色"，而且会连包自己的光照曲线一起动。车灯是**函数**不是**数据**，
> 塞进一个标量通道里必然走形。

---

## 3. 落地：三个文件 + 一个 mixin

| 位置 | 干什么 |
| --- | --- |
| `assets/minecraft/shaders/include/mmtr_lightfield_pack_headlight.glsl`（新） | piece H 骨架：三个调参 uniform（**与 Sodium 那条路共用同一批名字**）+ `mmtrPackHeadlightAdd()` + 共享数学的**占位行** |
| `MmtrShaderPackLightField`（改） | 新目标前缀 `gbuffers_terrain` / `gbuffers_entities`；`DoLighting` 锚点改写（前一后一，共两行）；新诊断计数 |
| `mixin/iris/IrisProgramMixin.java`（新） | `@Mixin(targets="net.irisshaders.iris.gl.program.ProgramUniforms")` + `@Inject(method="update", at=RETURN, remap=false, require=0)` → `MmtrHeadlights.uploadForTerrainProgram()` |
| `mtr.iris.mixins.json`（改） | 注册上面那个 mixin（`required:false` + `defaultRequire:0`，Iris 不在时什么都不做） |

**为什么上传必须另挂一个点**：车厢那条路的上传点是 MTR 自己的 `ShaderManager.setupShaderBatchState`
（notes/344 §17.24），而**地形在光影包下是 Iris 画的**，MTR 完全不参与 ⇒ 没有这一处，
包里的 `mmtrHeadlightCount` 永远是 GL 的初值 0，车灯一个像素都不会亮。

**为什么不需要重算坐标**：地形画在实体**之前**，所以地形那次上传用的是上一帧的灯表；
`MmtrHeadlights.upload()` 已有的"按收集时→现在的相机整体平移"正好把它消掉（与 Sodium 那条路同一条）。

**顺手修的一个真问题**（`MmtrHeadlights.resetProgramCache()`）：车灯的 uniform 位置缓存是**按 GL
program id 索引**的，而光影包每次（重新）装载都会销毁一批 program、GL 再把 id 回收发给新 program。
不清就会把旧 id 的位置套到新 program 上 —— 那个位置可能正好命中包自己的 uniform。
调用点放在 `MmtrShaderPackLightField.invalidate()`（= 包开始重新注入的那一刻）。

**目标集的宽窄**：piece F（光场）仍然**只**注入 `gbuffers_entities`（它修的是"MTR 那个 draw 的光照值
是整车一个常数"，地形本来就用包自己的逐顶点光照贴图，不需要改）；piece H（车灯）注入
`gbuffers_terrain` + `gbuffers_entities`（灯是世界里的光，地面与别的车都该被照到）。
水（`gbuffers_water`）**暂不列入**：Sodium 那条路也只覆盖不透明档，两条路保持同宽。

---

## 4. ★ 踩到的两个坑

### 4.1 占位符被内联了两遍（探针一把抓住）

骨架文件里那条占位行是给 Java 侧做"整行替换"用的，而骨架文件**自己的注释里**也写了它的完整字样
（解释它干什么用）。第一版 Java 写的是 `snippet.replace(placeholder, core)` ⇒ **两处都被替换**：

- 第一遍落进那条 `//` 注释里（那一行的剩余部分全被注释掉，看起来"没什么事"）；
- 第二遍才是真的 ⇒ 共享数学被内联**两遍** ⇒ 重复的 `uniform` 与函数定义 ⇒ **包的着色器编译不过**。

探针第一次跑就报了 5 条 FAIL（"共享数学只内联一遍"/"uniform 只声明一次"/"反照率捕获紧邻在
DoLighting 之前"…），一条都没漏到实机。修法是**两边都改对**：

1. Java 侧改成 `inlinePlaceholder()`：只认**独占一行且 trim 后完全相等**的占位行，
   而且要求**恰好一行**（0 行或 ≥2 行 ⇒ 记一行日志 + 整段不注入，包源码保持原样）；
2. 骨架文件的注释**不再写占位符的完整字样**（改成 `@MMTR_HEADLIGHT_INCLUDE@` 这种描述）。

> 这与 notes/345 §7.20 末尾那条教训是同一族：**判据（以及实现）的匹配粒度必须对着真实约束写**。
> 那一次是"注释里提到 `#version` 让 `contains` 误报"，这一次更狠 —— 同样的模糊匹配让**产物**坏掉。
> 顺带把 piece F 也换成了同一个 `inlinePlaceholder()`（它当时只有一处，属**潜伏**的同一个坑）。

另外两处判据也一起修对了（都是"注释里出现过那句话"引起的假 FAIL）：
探针定位插入语句时用**整行相等**（`indexOfExact`）而不是 `contains`；
`mmtrTerrainLuxScale` 等三条 uniform 的行尾注释**独立成行**（否则"整行相等"的判据永远失败）。

### 4.2 ★★ 上传挂点写对了名字、但那个方法**世界渲染一次都不调**（离线上看不出来）

第一版把上传挂在 `net.irisshaders.iris.gl.program.Program.use()` 上 —— 它看起来**就是**唯一的
"绑定并配好这个 program"的入口（`javap -c` 显示它 `memoryBarrier` → `_glUseProgram` →
`ProgramSamplers.update()` → `ProgramImages.update()`，完全符合直觉）。但把 Iris 全 jar 的字节码
扫一遍就会发现：**世界渲染那几条路一次都没调用它**（全仓只有 `CenterDepthSampler` 用）。
真实的绑定路径有两条，都不经过 `Program.use()`：

| 谁 | 怎么绑 | 证据（`javap -c`） |
| --- | --- | --- |
| 实体/原版那条路 | Iris 的 `ExtendedShader extends ShaderInstance`，由原版 `ShaderProgram.bind()` 进 | `ExtendedShader.method_34586`（= `bind`）：`setCurrentAlphaTest` → `class_285.method_22094`(glUseProgram) → … → `ProgramUniforms.update` / `CustomUniforms.push` |
| 地形（装了 Sodium 时） | **Sodium 自己的** `GlProgram`（Iris 只换了源码），由 `ShaderChunkRenderer.begin` 进 | `IrisChunkShaderInterface.setupState()`：`ProgramUniforms.update` / `ProgramSamplers.update` / `ProgramImages.update` / `CustomUniforms.push`，**没有** glUseProgram（那是 `GlProgram.bind()` 干的，用裸 `GL20C.glUseProgram`） |

两条路的交集就是 **`ProgramUniforms.update()`**：Iris 自己的类、自己的方法名（`remap=false` 在
dev 与发布产物里都对，**也不需要 refmap**），而且都在 program 已经 bind **之后**才调用。
地形那条路另有 `SodiumGlProgramMixin` 兜着（它挂 `GlProgram.bind()`，无光影时也照样生效）。

> **这一条为什么危险**：写错挂点时，离线一切正常 —— 着色器照样注入、编译照样链接、探针 41/41 全绿，
> 只有实机表现为"没有灯"。所以顺手给探针加了一条 **钩子契约**：用**反射**去查
> `net.irisshaders.iris.gl.program.ProgramUniforms.update()` 真的存在、是 `public void`
> （反射查的是游戏真正加载的那份 Iris）。挂点名字一旦被 Iris 改掉，这里先红，不用等重启客户端。

---

## 5. 离线证据（`sandbox/pack-headlight-probe/`，全部可复跑）

```powershell
pwsh -File sandbox\pack-headlight-probe\check.ps1      # 退出码 0 = 全绿
```

三层，一次跑完（完整输出：`logs/2026-09/351-pack-headlight-probe.txt`）：

| 层 | 内容 | 结果 |
| --- | --- | --- |
| ① 配置注册守卫（复用 notes/348 的脚本） | 4 份 mixin 配置在 **模板 / src / build** 三处都注册过 | **OK**（三处） |
| ② 决策路径探针（真的调 `MmtrShaderPackLightField.patch()`） | 输入是 **Iris 自己 dump 的真实源码**（3779 行地形 / 4360 行实体） | **PASS 43 / FAIL 0** |
| ③ 离线 GLSL 编译 + 链接（游戏同一套 LWJGL） | 真 dump 的 `.vsh` × 我们注入后的 `.fsh`，地形与实体各一对 | **OK / link OK** |

②里 43 条判据的关键几条（**负例占 9 条** —— 负例才是保护机制唯一的价值证明）：

- 路由：`gbuffers_terrain.fsh` 是目标；`gbuffers_skybasic.fsh`、`program/gbuffers_terrain.glsl` **不是**；
- 地形**不该**吃光场（piece F 一个都不许进）：`mmtrFragmentLmCoord` 计数 = 0、`lmCoordM` 锚点**原样保留**、
  光场采样器 uniform 计数 = 0；
- 锚点位置：反照率捕获行**紧邻**在 `DoLighting` 之前、加性调用行**紧跟这次调用的结尾**（上一行以 `);` 收尾）；
- **作用域**：插入点所在的那层花括号里必须能看见 `vec3 playerPos` 与 `vec3 worldGeoNormal`
  （实体那份里 `worldGeoNormal` 是声明在**块内部**的，插到块外 = 包编译不过）；
- 幂等：对已注入的源码再 `patch()` ⇒ `null`（资源重载不会叠两份）；
- 负例：把 `DoLighting` 改名 ⇒ 只插函数、**不改**包源码；只有定义没有调用 ⇒ 不算锚点；
  调用跨行超过 8 行 ⇒ 放弃注入（宁可不动包）；注入文本里不许出现 `#version`/`#moj_import`/`#import` 开头的行；
- 占位符契约：两份骨架文件里占位符字样**只出现一次**且独占一行（§4 那个坑的回归守卫）；
- **钩子契约**（§4.2 那个坑的回归守卫）：反射查 `ProgramUniforms.update()` 存在且是 `public void`。

③的实测输出（**注入的 uniform 全部活着**，没有被编译器判成死代码）：

```
uniform mmtrHeadlightCount = 56   mmtrHeadlightPos = 57   mmtrHeadlightDir = 65   mmtrHeadlightColor = 73
uniform mmtrTerrainLuxScale = 83  mmtrTerrainFlat = 81    mmtrTerrainDebug = 82
link: OK
```

---

## 6. 开关与口径（**与无光影那条路共用**，改一行 2 秒生效）

`run/mmtr-lightfield.properties`：

```properties
terrainHeadlights=true    # 地形/实体吃不吃车灯（false = 上传时把 mmtrTerrainLuxScale 压成 0）
terrainLuxScale=1.5       # 强度（与车厢那一侧的 1.0 分开调）
terrainNormal=derivative  # 这条路上**用不到**导数重建（包给了真法线）；flat = 不做兰伯特（兜底档）
terrainDebug=false        # 假色：被车灯照到的地方画成品红、其余全黑
```

> 光影包那条路上 `worldGeoNormal` 是真法线，所以 `terrainNormal=derivative` 与 `flat` 的区别
> 只是"要不要兰伯特"，不涉及重建 —— 这一点与 Sodium 那条路不同（那边顶点格式没有法线）。

---

## 7. 实机验收（**待确认**）

重启客户端 + 开光影包（`run/config/iris.properties`：`shaderPack=ComplementaryReimagined_r5.9.3.zip`、
`enableShaders=true`）。要看的三处：

| # | 判据 | 看哪里 |
| --- | --- | --- |
| ① | 注入真的发生了 | 日志 `[MMTR-LIGHT] 光影注入 …：已注入车灯段（piece H 在第 N 行，…；DoLighting 锚点命中 1 处 → 共 M 行）`；5 秒行里 `光影车灯=N个程序/文本就绪 锚点=gbuffers_terrain.fsh:锚点×1/…` |
| ② | 灯表真的传上去了 | 5 秒行里 `车灯：… 上传=…`（地形那次用的是上一帧的灯表，与 Sodium 那条路同一个滞后处理） |
| ③ | 画面 | `terrainDebug=true`：**品红 = 被车灯照到、其余全黑**（这条不挑夜晚、不看贴图，是"光斑到底落没落上"的判据）；关掉 debug：夜里/隧道里车头前方地面、站台、隧道壁应当被照亮，`terrainLuxScale` 0↔1.5 立刻能看出强弱 |

**已知边界**（与 notes/345 §5 同族）：① 没有遮挡 —— 光穿墙（包那条路可以做，但要用已经在 GPU 上的
实心位图在片元里朝灯 ray march，属另一轮）；② 水（`gbuffers_water`）不吃车灯，与 Sodium 那条路同宽；
③ 只对 Complementary Reimagined r5.9.3 实测过：锚点 `DoLighting(color, …)` 是**包自己的约定**，
别的包找不到锚点时会"只插函数、不动源码"（安全失败，日志里明说）。
